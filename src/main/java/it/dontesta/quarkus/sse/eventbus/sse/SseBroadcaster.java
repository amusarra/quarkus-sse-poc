/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
package it.dontesta.quarkus.sse.eventbus.sse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.pubsub.ReactivePubSubCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationCompleted;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;

/**
 * Broadcasts SSE events to connected clients using Redis Pub/Sub as the
 * event transport. This design allows multiple application instances to
 * deliver events to their own SSE clients regardless of which instance
 * processed the PDF generation request.
 *
 * <h2>Race-condition fix — pending-event buffer</h2>
 * <p>If a Redis Pub/Sub message arrives <em>before</em> the SSE client opens
 * its connection (i.e. no local {@link BroadcastProcessor} exists yet), the
 * raw JSON payload is stored in Redis under the key
 * {@code pending:completed:{processId}} or {@code pending:error:{processId}}
 * with a configurable TTL ({@value #PENDING_EVENT_TTL_SECONDS} s by default).
 * When {@link #createStream} is subsequently called the method checks those
 * keys and, if present, immediately emits the buffered event to the new
 * subscriber (atomic GET + DEL to guarantee exactly-once delivery).
 *
 * <h2>Resource leak prevention</h2>
 * <p>The {@link BroadcastProcessor} entry for a given {@code processId} is
 * removed from the local map on any of these conditions:
 * <ul>
 *   <li>The SSE client disconnects (stream cancellation).</li>
 *   <li>A completion or error event is successfully delivered.</li>
 *   <li>The application shuts down (all open processors are completed).</li>
 * </ul>
 */
@ApplicationScoped
public class SseBroadcaster {

    /** Redis key prefix for pending completed events not yet consumed by an SSE client. */
    static final String PENDING_COMPLETED_PREFIX = "pending:completed:";

    /** Redis key prefix for pending error events not yet consumed by an SSE client. */
    static final String PENDING_ERROR_PREFIX = "pending:error:";

    /**
     * TTL (seconds) for pending-event keys in Redis.
     * If no SSE client reconnects within this window the event is discarded.
     */
    static final long PENDING_EVENT_TTL_SECONDS = 300L;

    /**
     * In-memory map of active SSE processors, keyed by processId.
     * Access is thread-safe via {@link ConcurrentHashMap}.
     *
     * <p><strong>Scope:</strong> intentionally local to the JVM instance.
     * Each instance tracks only the SSE clients connected to itself.
     * Cross-instance delivery relies on Redis Pub/Sub; late-arrival delivery
     * relies on the Redis pending-event buffer (see {@link #checkPendingEvents}).
     */
    private final Map<String, BroadcastProcessor<OutboundSseEvent>> processors = new ConcurrentHashMap<>();

    @Inject
    Sse sse;

    @Inject
    ReactiveRedisDataSource reactiveRedisDS;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.completed", defaultValue = "pdf-generation-completed")
    String completedChannel;

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.errors", defaultValue = "pdf-generation-errors")
    String errorsChannel;

    /** Subscriber handle — used to unsubscribe cleanly on shutdown. */
    private ReactivePubSubCommands.ReactiveRedisSubscriber redisChannelSubscriber;

    void onStart(@Observes StartupEvent ev) {
        Log.debug("SseBroadcaster initializing with Redis Pub/Sub...");
        ReactivePubSubCommands<String> redisPubSub = reactiveRedisDS.pubsub(String.class);

        redisPubSub.subscribe(
                        java.util.List.of(completedChannel, errorsChannel),
                        (channel, json) -> {
                            if (channel.equals(completedChannel)) {
                                onCompletedMessage(json);
                            } else if (channel.equals(errorsChannel)) {
                                onErrorMessage(json);
                            }
                        })
                .subscribe().with(
                        sub -> {
                            this.redisChannelSubscriber = sub;
                            Log.debugf("Subscribed to Redis channels: '%s', '%s'", completedChannel, errorsChannel);
                        },
                        err -> Log.errorf(err, "Failed to subscribe to Redis channels"));

        Log.debug("SseBroadcaster initialized and listening for events via Redis Pub/Sub.");
    }

    void onShutdown(@Observes ShutdownEvent ev) {
        Log.debug("SseBroadcaster shutting down — completing all active SSE streams.");

        processors.forEach((processId, processor) -> {
            Log.debugf("Completing SSE processor for processId: %s on shutdown", processId);
            processor.onComplete();
        });
        processors.clear();

        if (redisChannelSubscriber != null) {
            redisChannelSubscriber.unsubscribe()
                    .subscribe().with(
                            v -> Log.debug("Unsubscribed from Redis channels"),
                            err -> Log.warnf(err, "Error while unsubscribing from Redis channels"));
        }
    }

    /**
     * Returns a reactive SSE stream for the given {@code processId}.
     *
     * <p>After registering the local {@link BroadcastProcessor}, this method
     * asynchronously checks Redis for a <em>pending event</em> that may have
     * been published before this SSE client connected (race-condition fix).
     * If found, the event is emitted immediately and the pending key is deleted.
     *
     * <p>Resource leak prevention: the processor entry is removed on stream
     * cancellation (client disconnect), after event delivery, and on shutdown.
     *
     * @param processId the unique identifier for the PDF generation process
     * @return a {@link Multi} of {@link OutboundSseEvent} events
     */
    public Multi<OutboundSseEvent> createStream(String processId) {
        Log.debugf("Creating SSE stream for processId: %s", processId);
        BroadcastProcessor<OutboundSseEvent> processor =
                processors.computeIfAbsent(processId, id -> BroadcastProcessor.create());

        // Check Redis for an event that arrived before this SSE client connected.
        checkPendingEvents(processId);

        return processor
                .onCancellation().invoke(() -> {
                    processors.remove(processId);
                    Log.debugf("SSE stream cancelled (client disconnected) for processId: %s — processor cleaned up",
                            processId);
                });
    }

    /**
     * Checks Redis for a pending completed or error event for {@code processId}.
     *
     * <p>Lookup order: completed first, then error (only one can exist for any
     * given {@code processId}). The key is atomically consumed (GET then DEL)
     * before dispatching so that concurrent calls cannot deliver the same event
     * twice.
     *
     * @param processId the unique identifier for the PDF generation process
     */
    private void checkPendingEvents(String processId) {
        ReactiveValueCommands<String, String> values = reactiveRedisDS.value(String.class);

        // GETDEL atomically returns the value and removes the key in one round-trip,
        // preventing double-delivery if two concurrent createStream calls race.
        values.getdel(PENDING_COMPLETED_PREFIX + processId)
                .subscribe().with(
                        json -> {
                            if (json != null) {
                                Log.debugf("Consuming pending completed event from Redis for processId: %s", processId);
                                onCompletedMessage(json);
                            } else {
                                // No completed event — check for a pending error event.
                                values.getdel(PENDING_ERROR_PREFIX + processId)
                                        .subscribe().with(
                                                errJson -> {
                                                    if (errJson != null) {
                                                        Log.debugf(
                                                                "Consuming pending error event from Redis for processId: %s",
                                                                processId);
                                                        onErrorMessage(errJson);
                                                    }
                                                },
                                                err -> Log.error(
                                                        "Failed to check pending:error event for processId: " + processId,
                                                        err));
                            }
                        },
                        err -> Log.error(
                                "Failed to check pending:completed event for processId: " + processId, err));
    }

    private void onCompletedMessage(String json) {
        try {
            PdfGenerationCompleted event = objectMapper.readValue(json, PdfGenerationCompleted.class);
            handleCompletionEvent(event, json);
        } catch (Exception e) {
            Log.errorf(e, "Failed to deserialize PDF_COMPLETED event from Redis: %s", json);
        }
    }

    private void onErrorMessage(String json) {
        try {
            PdfGenerationError event = objectMapper.readValue(json, PdfGenerationError.class);
            handleErrorEvent(event, json);
        } catch (Exception e) {
            Log.errorf(e, "Failed to deserialize PDF_ERROR event from Redis: %s", json);
        }
    }

    /**
     * Delivers a {@link PdfGenerationCompleted} event to the local SSE client,
     * or — if no client is currently connected — buffers the raw JSON in Redis
     * so that a late-arriving SSE connection can still receive it.
     *
     * @param event   the deserialized completion event
     * @param rawJson the original JSON string (used for the Redis pending buffer)
     */
    private void handleCompletionEvent(PdfGenerationCompleted event, String rawJson) {
        String processId = event.processId();
        BroadcastProcessor<OutboundSseEvent> processor = processors.get(processId);

        if (processor != null) {
            Log.debugf("Sending PDF_COMPLETED event for processId: %s", processId);
            OutboundSseEvent sseEvent = sse.newEventBuilder()
                    .name("PDF_COMPLETED")
                    .data(event)
                    .mediaType(MediaType.APPLICATION_JSON_TYPE)
                    .build();
            processor.onNext(sseEvent);
            processor.onComplete();
            processors.remove(processId);
            Log.debugf("Removed SSE processor for processId: %s", processId);
        } else {
            // No local SSE client yet — buffer in Redis for late-arriving connections.
            Log.debugf("No active SSE processor for processId: %s — buffering completed event in Redis (TTL=%ds)",
                    processId, PENDING_EVENT_TTL_SECONDS);
            reactiveRedisDS.value(String.class)
                    .setex(PENDING_COMPLETED_PREFIX + processId, PENDING_EVENT_TTL_SECONDS, rawJson)
                    .subscribe().with(
                            v -> Log.debugf("Pending completed event stored in Redis for processId: %s", processId),
                            err -> Log.errorf(err,
                                    "Failed to store pending completed event in Redis for processId: %s", processId));
        }
    }

    /**
     * Delivers a {@link PdfGenerationError} event to the local SSE client,
     * or buffers the raw JSON in Redis for a late-arriving SSE connection.
     *
     * @param event   the deserialized error event
     * @param rawJson the original JSON string (used for the Redis pending buffer)
     */
    private void handleErrorEvent(PdfGenerationError event, String rawJson) {
        String processId = event.processId();
        BroadcastProcessor<OutboundSseEvent> processor = processors.get(processId);

        if (processor != null) {
            Log.debugf("Sending PDF_ERROR event for processId: %s", processId);
            OutboundSseEvent sseEvent = sse.newEventBuilder()
                    .name("PDF_ERROR")
                    .data(event)
                    .mediaType(MediaType.APPLICATION_JSON_TYPE)
                    .build();
            processor.onNext(sseEvent);
            processor.onComplete();
            processors.remove(processId);
            Log.debugf("Removed SSE processor for processId: %s after error event", processId);
        } else {
            // No local SSE client yet — buffer in Redis for late-arriving connections.
            Log.debugf("No active SSE processor for processId: %s — buffering error event in Redis (TTL=%ds)",
                    processId, PENDING_EVENT_TTL_SECONDS);
            reactiveRedisDS.value(String.class)
                    .setex(PENDING_ERROR_PREFIX + processId, PENDING_EVENT_TTL_SECONDS, rawJson)
                    .subscribe().with(
                            v -> Log.debugf("Pending error event stored in Redis for processId: %s", processId),
                            err -> Log.errorf(err,
                                    "Failed to store pending error event in Redis for processId: %s", processId));
        }
    }
}
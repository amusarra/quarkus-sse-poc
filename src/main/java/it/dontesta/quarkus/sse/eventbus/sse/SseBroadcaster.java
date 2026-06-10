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
 * <p>Resource leak prevention: the {@link BroadcastProcessor} entry for a
 * given {@code processId} is removed from the local map on any of these
 * conditions:
 * <ul>
 *   <li>The SSE client disconnects (stream cancellation).</li>
 *   <li>A completion or error event is successfully delivered.</li>
 *   <li>The application shuts down (all open processors are completed).</li>
 * </ul>
 */
@ApplicationScoped
public class SseBroadcaster {

    /**
     * In-memory map of active SSE processors, keyed by processId.
     * Access is thread-safe via {@link ConcurrentHashMap}.
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

    /** Dedicated Redis connection kept alive for channel subscriptions. */
    private ReactivePubSubCommands<String> redisPubSub;

    /** Subscriber handle — used to unsubscribe cleanly on shutdown. */
    private ReactivePubSubCommands.ReactiveRedisSubscriber redisChannelSubscriber;

    void onStart(@Observes StartupEvent ev) {
        Log.debug("SseBroadcaster initializing with Redis Pub/Sub...");
        redisPubSub = reactiveRedisDS.pubsub(String.class);

        // Subscribe to both channels with a single BiConsumer so we can dispatch by channel name.
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

        // Signal completion to all waiting SSE clients so they do not hang.
        processors.forEach((processId, processor) -> {
            Log.debugf("Completing SSE processor for processId: %s on shutdown", processId);
            processor.onComplete();
        });
        processors.clear();

        // Release the Redis subscription connection.
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
     * <p>The underlying {@link BroadcastProcessor} is created on demand and
     * removed from the local map as soon as the stream terminates for any
     * reason (event delivered, client disconnect, or application shutdown).
     *
     * @param processId the unique identifier for the PDF generation process
     * @return a {@link Multi} of {@link OutboundSseEvent} events
     */
    public Multi<OutboundSseEvent> createStream(String processId) {
        Log.debugf("Creating SSE stream for processId: %s", processId);
        BroadcastProcessor<OutboundSseEvent> processor =
                processors.computeIfAbsent(processId, id -> BroadcastProcessor.create());

        // FIX: remove the processor from the map when the SSE client disconnects
        // (stream cancellation) to prevent orphaned BroadcastProcessor entries.
        return processor
                .onCancellation().invoke(() -> {
                    processors.remove(processId);
                    Log.debugf("SSE stream cancelled (client disconnected) for processId: %s — processor cleaned up",
                            processId);
                });
    }

    // ── Redis message handlers ────────────────────────────────────────────────

    private void onCompletedMessage(String json) {
        try {
            PdfGenerationCompleted event = objectMapper.readValue(json, PdfGenerationCompleted.class);
            handleCompletionEvent(event);
        } catch (Exception e) {
            Log.errorf(e, "Failed to deserialize PDF_COMPLETED event from Redis: %s", json);
        }
    }

    private void onErrorMessage(String json) {
        try {
            PdfGenerationError event = objectMapper.readValue(json, PdfGenerationError.class);
            handleErrorEvent(event);
        } catch (Exception e) {
            Log.errorf(e, "Failed to deserialize PDF_ERROR event from Redis: %s", json);
        }
    }

    // ── SSE delivery helpers ──────────────────────────────────────────────────

    private void handleCompletionEvent(PdfGenerationCompleted event) {
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
            Log.warnf("No active SSE processor found for processId: %s — event discarded", processId);
        }
    }

    private void handleErrorEvent(PdfGenerationError event) {
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
            Log.warnf("No active SSE processor found for error on processId: %s — event discarded", processId);
        }
    }
}
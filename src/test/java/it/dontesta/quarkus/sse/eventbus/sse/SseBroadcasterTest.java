/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
package it.dontesta.quarkus.sse.eventbus.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationCompleted;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationError;
import jakarta.inject.Inject;
import jakarta.ws.rs.sse.OutboundSseEvent;

@QuarkusTest
@Tag("publish-subscribe")
@Tag("redis")
@Tag("sse")
class SseBroadcasterTest {

    @Inject
    SseBroadcaster sseBroadcaster;

    @Inject
    ReactiveRedisDataSource reactiveRedisDS;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.completed")
    String completedChannel;

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.errors")
    String errorsChannel;

    @Test
    void testHandleCompletionEvent() throws Exception {
        String processId = UUID.randomUUID().toString();
        String downloadUrl = "/api/pdf/download/" + processId;
        PdfGenerationCompleted completionEvent = new PdfGenerationCompleted(processId, downloadUrl);

        Multi<OutboundSseEvent> stream = sseBroadcaster.createStream(processId);

        CopyOnWriteArrayList<OutboundSseEvent> receivedEvents = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        stream.subscribe().with(
                receivedEvents::add,
                Throwable::printStackTrace,
                latch::countDown);

        // Publish the event to Redis — SseBroadcaster is subscribed and will forward it.
        String json = objectMapper.writeValueAsString(completionEvent);
        reactiveRedisDS.pubsub(String.class)
                .publish(completedChannel, json)
                .subscribe().with(count -> {}, err -> System.err.println("Redis publish error: " + err.getMessage()));

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Stream should complete within 10 seconds");
        assertEquals(1, receivedEvents.size());

        OutboundSseEvent sseEvent = receivedEvents.getFirst();
        assertEquals("PDF_COMPLETED", sseEvent.getName());
        assertInstanceOf(PdfGenerationCompleted.class, sseEvent.getData());
        PdfGenerationCompleted receivedData = (PdfGenerationCompleted) sseEvent.getData();
        assertEquals(processId, receivedData.processId());
        assertEquals(downloadUrl, receivedData.pdfUrl());
    }

    @Test
    void testHandleErrorEvent() throws Exception {
        String processId = UUID.randomUUID().toString();
        String errorMessage = "PDF generation failed";
        PdfGenerationError errorEvent = new PdfGenerationError(processId, errorMessage);

        Multi<OutboundSseEvent> stream = sseBroadcaster.createStream(processId);

        CopyOnWriteArrayList<OutboundSseEvent> receivedEvents = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        stream.subscribe().with(
                receivedEvents::add,
                Throwable::printStackTrace,
                latch::countDown);

        // Publish the event to Redis — SseBroadcaster is subscribed and will forward it.
        String json = objectMapper.writeValueAsString(errorEvent);
        reactiveRedisDS.pubsub(String.class)
                .publish(errorsChannel, json)
                .subscribe().with(count -> {}, err -> System.err.println("Redis publish error: " + err.getMessage()));

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Stream should complete within 10 seconds");
        assertEquals(1, receivedEvents.size());

        OutboundSseEvent sseEvent = receivedEvents.getFirst();
        assertEquals("PDF_ERROR", sseEvent.getName());
        assertInstanceOf(PdfGenerationError.class, sseEvent.getData());
        PdfGenerationError receivedData = (PdfGenerationError) sseEvent.getData();
        assertEquals(processId, receivedData.processId());
        assertEquals(errorMessage, receivedData.errorMessage());
    }

    /**
     * Simulates the race condition where the PDF generation completes (and the
     * Redis Pub/Sub message is dispatched) <em>before</em> the SSE client opens
     * its connection. The pending-event buffer in Redis must ensure the event is
     * still delivered to the late-arriving subscriber.
     */
    @Test
    void testHandleCompletionEvent_lateSSEConnection() throws Exception {
        String processId = UUID.randomUUID().toString();
        String downloadUrl = "/api/pdf/download/" + processId;
        PdfGenerationCompleted completionEvent = new PdfGenerationCompleted(processId, downloadUrl);

        // Step 1 — Publish BEFORE the SSE client connects.
        String json = objectMapper.writeValueAsString(completionEvent);
        CountDownLatch publishLatch = new CountDownLatch(1);
        reactiveRedisDS.pubsub(String.class)
                .publish(completedChannel, json)
                .subscribe().with(
                        count -> publishLatch.countDown(),
                        err -> {
                            System.err.println("Redis publish error: " + err.getMessage());
                            publishLatch.countDown();
                        });

        assertTrue(publishLatch.await(5, TimeUnit.SECONDS), "Redis publish should complete within 5 seconds");

        // Step 2 — Wait for SseBroadcaster to process the Pub/Sub message and
        //           buffer the event in Redis (pending:completed:{processId}).
        Thread.sleep(500);

        // Step 3 — NOW the SSE client opens the connection; createStream must
        //           detect the pending event and emit it immediately.
        Multi<OutboundSseEvent> stream = sseBroadcaster.createStream(processId);

        CopyOnWriteArrayList<OutboundSseEvent> receivedEvents = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        stream.subscribe().with(
                receivedEvents::add,
                Throwable::printStackTrace,
                latch::countDown);

        assertTrue(latch.await(10, TimeUnit.SECONDS),
                "Late SSE client should still receive the buffered event within 10 seconds");
        assertEquals(1, receivedEvents.size());

        OutboundSseEvent sseEvent = receivedEvents.getFirst();
        assertEquals("PDF_COMPLETED", sseEvent.getName());
        assertInstanceOf(PdfGenerationCompleted.class, sseEvent.getData());
        PdfGenerationCompleted receivedData = (PdfGenerationCompleted) sseEvent.getData();
        assertEquals(processId, receivedData.processId());
        assertEquals(downloadUrl, receivedData.pdfUrl());
    }

    /**
     * Same as {@link #testHandleCompletionEvent_lateSSEConnection} but for the
     * error path: the error event is published before the SSE client connects
     * and must still be delivered via the Redis pending-event buffer.
     */
    @Test
    void testHandleErrorEvent_lateSSEConnection() throws Exception {
        String processId = UUID.randomUUID().toString();
        String errorMessage = "PDF generation failed";
        PdfGenerationError errorEvent = new PdfGenerationError(processId, errorMessage);

        // Step 1 — Publish BEFORE the SSE client connects.
        String json = objectMapper.writeValueAsString(errorEvent);
        CountDownLatch publishLatch = new CountDownLatch(1);
        reactiveRedisDS.pubsub(String.class)
                .publish(errorsChannel, json)
                .subscribe().with(
                        count -> publishLatch.countDown(),
                        err -> {
                            System.err.println("Redis publish error: " + err.getMessage());
                            publishLatch.countDown();
                        });

        assertTrue(publishLatch.await(5, TimeUnit.SECONDS), "Redis publish should complete within 5 seconds");

        // Step 2 — Wait for SseBroadcaster to buffer the event.
        Thread.sleep(500);

        // Step 3 — Late SSE connection; must receive the buffered error event.
        Multi<OutboundSseEvent> stream = sseBroadcaster.createStream(processId);

        CopyOnWriteArrayList<OutboundSseEvent> receivedEvents = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        stream.subscribe().with(
                receivedEvents::add,
                Throwable::printStackTrace,
                latch::countDown);

        assertTrue(latch.await(10, TimeUnit.SECONDS),
                "Late SSE client should still receive the buffered error event within 10 seconds");
        assertEquals(1, receivedEvents.size());

        OutboundSseEvent sseEvent = receivedEvents.getFirst();
        assertEquals("PDF_ERROR", sseEvent.getName());
        assertInstanceOf(PdfGenerationError.class, sseEvent.getData());
        PdfGenerationError receivedData = (PdfGenerationError) sseEvent.getData();
        assertEquals(processId, receivedData.processId());
        assertEquals(errorMessage, receivedData.errorMessage());
    }
}
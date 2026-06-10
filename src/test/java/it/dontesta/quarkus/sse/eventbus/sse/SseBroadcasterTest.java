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
}
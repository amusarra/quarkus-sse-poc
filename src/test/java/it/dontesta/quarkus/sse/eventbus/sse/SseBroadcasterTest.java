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

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.mutiny.core.eventbus.EventBus;
import it.dontesta.quarkus.sse.eventbus.codec.PdfGenerationCompletedCodec;
import it.dontesta.quarkus.sse.eventbus.codec.PdfGenerationErrorCodec;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationCompleted;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationError;
import jakarta.inject.Inject;
import jakarta.ws.rs.sse.OutboundSseEvent;

@QuarkusTest
@Tag("publish-subscribe")
@Tag("eventbus")
@Tag("sse")
class SseBroadcasterTest {

    @Inject
    SseBroadcaster sseBroadcaster;

    @Inject
    EventBus eventBus;

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.completed")
    String completedDestination;

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.errors")
    String errorsDestination;

    @Test
    void testHandleCompletionEvent() throws InterruptedException {
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

        eventBus.publish(completedDestination, completionEvent, new DeliveryOptions().setCodecName(
                PdfGenerationCompletedCodec.CODEC_NAME));

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Stream should complete within 5 seconds");
        assertEquals(1, receivedEvents.size());

        OutboundSseEvent sseEvent = receivedEvents.getFirst();
        assertEquals("PDF_COMPLETED", sseEvent.getName());
        assertInstanceOf(PdfGenerationCompleted.class, sseEvent.getData());
        PdfGenerationCompleted receivedData = (PdfGenerationCompleted) sseEvent.getData();
        assertEquals(processId, receivedData.processId());
        assertEquals(downloadUrl, receivedData.pdfUrl());
    }

    @Test
    void testHandleErrorEvent() throws InterruptedException {
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

        eventBus.publish(errorsDestination, errorEvent, new DeliveryOptions().setCodecName(
                PdfGenerationErrorCodec.CODEC_NAME));

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Stream should complete within 5 seconds");
        assertEquals(1, receivedEvents.size());

        OutboundSseEvent sseEvent = receivedEvents.getFirst();
        assertEquals("PDF_ERROR", sseEvent.getName());
        assertInstanceOf(PdfGenerationError.class, sseEvent.getData());
        PdfGenerationError receivedData = (PdfGenerationError) sseEvent.getData();
        assertEquals(processId, receivedData.processId());
        assertEquals(errorMessage, receivedData.errorMessage());
    }
}
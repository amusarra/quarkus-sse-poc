/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
package it.dontesta.quarkus.sse.eventbus.processor.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.mutiny.core.eventbus.EventBus;
import it.dontesta.quarkus.sse.eventbus.codec.PdfGenerationRequestCodec;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationCompleted;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationRequest;
import jakarta.inject.Inject;

@QuarkusTest
class PdfGenerationServiceTest {

    @Inject
    EventBus eventBus;

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.requests", defaultValue = "pdf-generation-requests")
    String requestsDestination;

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.completed", defaultValue = "pdf-generation-completed")
    String completedDestination;

    @Inject
    @ConfigProperty(name = "pdf.generation.delay.max-seconds")
    long maxDelayInSeconds;

    @Test
    void testGeneratePdfAsync() throws Exception {
        String processId = "test-456";
        PdfGenerationRequest request = new PdfGenerationRequest(processId);
        CompletableFuture<PdfGenerationCompleted> resultFuture = new CompletableFuture<>();

        // Register the consumer for the completed event
        eventBus.consumer(
                completedDestination,
                message -> resultFuture.complete((PdfGenerationCompleted) message.body()));

        // Send the request to the event bus
        eventBus.publish(
                requestsDestination,
                request,
                new DeliveryOptions().setCodecName(PdfGenerationRequestCodec.CODEC_NAME));

        // Wait for the result with a timeout
        // Added a timeout to avoid indefinite blocking in case of issues
        long timeout = maxDelayInSeconds + 2;
        PdfGenerationCompleted event = resultFuture.get(timeout, TimeUnit.SECONDS);

        // Verifica del contenuto dell'evento
        assertNotNull(event);
        assertEquals(processId, event.processId());
        assertEquals("https://storage.example.com/pdfs/" + processId + ".pdf", event.pdfUrl());
    }
}

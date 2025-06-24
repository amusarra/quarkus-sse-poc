/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
package it.dontesta.quarkus.sse.eventbus.processor.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.mutiny.core.eventbus.EventBus;
import it.dontesta.quarkus.sse.eventbus.codec.PdfGenerationRequestCodec;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationCompleted;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationRequest;
import jakarta.inject.Inject;

@QuarkusTest
@Tags({
        @Tag("publish-subscribe"),
        @Tag("eventbus")
})
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
        String processId = UUID.randomUUID().toString();
        PdfGenerationRequest request = new PdfGenerationRequest(processId);
        CompletableFuture<PdfGenerationCompleted> resultFuture = new CompletableFuture<>();

        // Registra un consumatore per l'evento di completamento che filtra per processId
        var consumer = eventBus.<PdfGenerationCompleted> consumer(completedDestination)
                .handler(message -> {
                    if (message.body().processId().equals(processId)) {
                        resultFuture.complete(message.body());
                    }
                });

        // Invia la richiesta al bus degli eventi
        eventBus.publish(
                requestsDestination,
                request,
                new DeliveryOptions().setCodecName(PdfGenerationRequestCodec.CODEC_NAME));

        // Attende il risultato con un timeout che considera il ritardo massimo di generazione
        long timeout = maxDelayInSeconds + 5;
        PdfGenerationCompleted event = resultFuture.get(timeout, TimeUnit.SECONDS);

        // Pulisce il consumatore
        consumer.unregister().await().indefinitely();

        // Verifica il contenuto dell'evento
        assertNotNull(event);
        assertEquals(processId, event.processId());
        assertEquals(String.format("/api/pdf/download/%s", processId), event.pdfUrl());
    }
}
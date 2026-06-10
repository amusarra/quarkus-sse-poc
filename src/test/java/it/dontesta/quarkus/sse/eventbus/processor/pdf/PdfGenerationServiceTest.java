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
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.mutiny.core.eventbus.EventBus;
import it.dontesta.quarkus.sse.eventbus.codec.PdfGenerationRequestCodec;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationCompleted;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationRequest;
import jakarta.inject.Inject;

@QuarkusTest
@Tag("publish-subscribe")
@Tag("eventbus")
@Tag("redis")
class PdfGenerationServiceTest {

    @Inject
    EventBus eventBus;

    @Inject
    ReactiveRedisDataSource reactiveRedisDS;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.requests", defaultValue = "pdf-generation-requests")
    String requestsDestination;

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.completed", defaultValue = "pdf-generation-completed")
    String completedChannel;

    @Inject
    @ConfigProperty(name = "pdf.generation.delay.max-seconds")
    long maxDelayInSeconds;

    @Test
    void testGeneratePdfAsync() throws Exception {
        String processId = UUID.randomUUID().toString();
        PdfGenerationRequest request = new PdfGenerationRequest(processId);
        CompletableFuture<PdfGenerationCompleted> resultFuture = new CompletableFuture<>();

        // Subscribe to Redis channel to receive completion events published by PdfEventProcessor.
        var sub = reactiveRedisDS.pubsub(String.class)
                .subscribe(completedChannel, json -> {
                    try {
                        PdfGenerationCompleted event = objectMapper.readValue(json, PdfGenerationCompleted.class);
                        if (event.processId().equals(processId)) {
                            resultFuture.complete(event);
                        }
                    } catch (Exception e) {
                        resultFuture.completeExceptionally(e);
                    }
                })
                .await().atMost(java.time.Duration.ofSeconds(5));

        // Publish the PDF generation request on the Vert.x EventBus.
        eventBus.publish(
                requestsDestination,
                request,
                new DeliveryOptions().setCodecName(PdfGenerationRequestCodec.CODEC_NAME));

        // Wait for the result with a timeout that accounts for the maximum generation delay.
        long timeout = maxDelayInSeconds + 5;
        PdfGenerationCompleted event = resultFuture.get(timeout, TimeUnit.SECONDS);

        // Cleanup the Redis subscription.
        sub.unsubscribe().await().atMost(java.time.Duration.ofSeconds(5));

        // Verify the event payload.
        assertNotNull(event);
        assertEquals(processId, event.processId());
        assertEquals(String.format("/api/pdf/download/%s", processId), event.pdfUrl());
    }
}
/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
package it.dontesta.quarkus.sse.eventbus.processor.pdf;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.core.eventbus.Message;
import io.vertx.mutiny.core.eventbus.MessageConsumer;
import it.dontesta.quarkus.sse.eventbus.codec.PdfGenerationCompletedCodec;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationCompleted;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@Unremovable
@ApplicationScoped
public class PdfEventProcessor {

    private final EventBus eventBus;
    private final ScheduledExecutorService executor;

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.requests", defaultValue = "pdf-generation-requests")
    String requestsDestination;

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.completed", defaultValue = "pdf-generation-completed")
    String completedDestination;

    @Inject
    @ConfigProperty(name = "pdf.generation.delay.min-seconds", defaultValue = "3")
    long minDelayInSeconds;

    @Inject
    @ConfigProperty(name = "pdf.generation.delay.max-seconds", defaultValue = "40")
    long maxDelayInSeconds;

    private MessageConsumer<PdfGenerationRequest> consumer;

    public PdfEventProcessor(
            EventBus eventBus,
            @ConfigProperty(name = "pdf.generation.executor.pool-size", defaultValue = "10") int poolSize) {
        this.eventBus = eventBus;
        this.executor = Executors.newScheduledThreadPool(poolSize);
        Log.debugf("PDF generation executor initialized with a thread pool size of %d", poolSize);
    }

    void onStart(@Observes StartupEvent ev) {
        Log.debug("Initialization of the PdfEventProcessor...");
        setupConsumer();
    }

    private void setupConsumer() {
        consumer = eventBus
                .<PdfGenerationRequest> consumer(requestsDestination)
                .handler(this::handlePdfGenerationRequest);

        Log.debugf(
                "Registered consumer for PDF generation requests on destination: %s", requestsDestination);
        Log.debugf("Destination for PDF generation completion events: %s", completedDestination);
        Log.debugf("PdfEventProcessor initialized and in listen mode for PDF generation requests.");
    }

    void onShutdown(@Observes ShutdownEvent ev) {
        Log.debug("Cleanup resource of the PdfEventProcessor");
        unregisterConsumer();
        shutdownExecutor();
    }

    private void shutdownExecutor() {
        if (!executor.isShutdown()) {
            try {
                executor.shutdown();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Log.error("Error occurred during the shutdown of the executor", e);
                Thread.currentThread().interrupt();
            }
        }
    }

    private void unregisterConsumer() {
        if (consumer != null) {
            consumer.unregister().await().indefinitely();
            Log.debug("Unregistered consumer for PDF generation requests.");
        }
    }

    // Handler to process PDF generation requests
    private void handlePdfGenerationRequest(Message<PdfGenerationRequest> message) {
        PdfGenerationRequest request = message.body();
        Log.debugf("Received PDF generation request with ID: %s", request.processId());

        // Simulate PDF generation asynchronously
        generatePdfAsync(request.processId())
                .thenAccept(
                        pdfUrl -> {
                            // When the PDF is generated, publish a completion event
                            PdfGenerationCompleted completionEvent = new PdfGenerationCompleted(request.processId(), pdfUrl);

                            Log.debugf(
                                    "Attempting to send PDF completion notification for ID: %s", request.processId());

                            eventBus.publish(
                                    completedDestination,
                                    completionEvent,
                                    new DeliveryOptions().setCodecName(PdfGenerationCompletedCodec.CODEC_NAME));
                            Log.debugf("PDF completion notification sent for ID: %s", request.processId());
                        });
    }

    // This method simulates the asynchronous generation of a PDF
    private CompletableFuture<String> generatePdfAsync(String processId) {
        CompletableFuture<String> future = new CompletableFuture<>();

        long delayInSeconds;
        if (minDelayInSeconds >= maxDelayInSeconds) {
            delayInSeconds = minDelayInSeconds;
        } else {
            delayInSeconds = ThreadLocalRandom.current().nextLong(minDelayInSeconds, maxDelayInSeconds);
        }

        // Simulate a delay to represent PDF generation
        executor.schedule(
                () -> {
                    // URL of the generated PDF
                    String pdfUrl = "https://storage.example.com/pdfs/" + processId + ".pdf";
                    Log.debugf("PDF successfully generated: %s", pdfUrl);
                    future.complete(pdfUrl);
                },
                delayInSeconds,
                TimeUnit.SECONDS);

        return future;
    }
}

/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
package it.dontesta.quarkus.sse.eventbus.processor.pdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.fugerit.java.doc.base.config.DocConfig;
import org.fugerit.java.doc.base.process.DocProcessContext;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.quarkus.arc.Unremovable;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.core.eventbus.Message;
import io.vertx.mutiny.core.eventbus.MessageConsumer;
import it.dontesta.quarkus.sse.eventbus.codec.PdfGenerationCompletedCodec;
import it.dontesta.quarkus.sse.eventbus.codec.PdfGenerationErrorCodec;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationCompleted;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationError;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationRequest;
import it.dontesta.quarkus.sse.fjdoc.DocHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;

@Unremovable
@ApplicationScoped
public class PdfEventProcessor {

    private final EventBus eventBus;
    private final ScheduledExecutorService executor;
    private final MinioClient minioClient;
    private final DocHelper docHelper;

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.requests", defaultValue = "pdf-generation-requests")
    String requestsDestination;

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.completed", defaultValue = "pdf-generation-completed")
    String completedDestination;

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.errors", defaultValue = "pdf-generation-errors")
    String errorsDestination;

    @Inject
    @ConfigProperty(name = "pdf.minio.bucket-name")
    String bucketName;

    @Inject
    @ConfigProperty(name = "pdf.generation.delay.min-seconds", defaultValue = "3")
    long minDelayInSeconds;

    @Inject
    @ConfigProperty(name = "pdf.generation.delay.max-seconds", defaultValue = "40")
    long maxDelayInSeconds;

    @Inject
    @ConfigProperty(name = "pdf.generation.output.path", defaultValue = "target/pdf-generated")
    String outputPath;

    private MessageConsumer<PdfGenerationRequest> consumer;

    public PdfEventProcessor(
            EventBus eventBus,
            MinioClient minioClient,
            @ConfigProperty(name = "pdf.generation.executor.pool-size", defaultValue = "10") int poolSize) {
        this.eventBus = eventBus;
        this.minioClient = minioClient;
        this.executor = Executors.newScheduledThreadPool(poolSize);
        this.docHelper = new DocHelper();
        Log.debugf("PDF generation executor initialized with a thread pool size of %d", poolSize);
    }

    void onStart(@Observes StartupEvent ev) {
        Log.debug("Initialization of the PdfEventProcessor...");
        try {
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!found) {
                Log.debugf("MinIO bucket '%s' does not exist. Creating it...", bucketName);
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                Log.debugf("MinIO bucket '%s' created.", bucketName);
            } else {
                Log.debugf("MinIO bucket '%s' already exists.", bucketName);
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not initialize MinIO bucket", e);
        }
        setupConsumer();
    }

    private void setupConsumer() {
        consumer = eventBus
                .<PdfGenerationRequest> consumer(requestsDestination)
                .handler(this::handlePdfGenerationRequest);

        Log.debugf(
                "Registered consumer for PDF generation requests on destination: %s", requestsDestination);
        Log.debugf("Destination for PDF generation completion events: %s", completedDestination);
        Log.debugf("Destination for PDF generation error events: %s", errorsDestination);
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

        generatePdfAsync(request.processId())
                .thenAccept(
                        pdfPath -> {
                            String downloadUrl = String.format("/api/pdf/download/%s", request.processId());
                            PdfGenerationCompleted completionEvent = new PdfGenerationCompleted(request.processId(),
                                    downloadUrl);

                            Log.debugf(
                                    "Attempting to send PDF completion notification for ID: %s", request.processId());

                            eventBus.publish(
                                    completedDestination,
                                    completionEvent,
                                    new DeliveryOptions().setCodecName(PdfGenerationCompletedCodec.CODEC_NAME));
                            Log.debugf("PDF completion notification sent for ID: %s", request.processId());
                        })
                .exceptionally(ex -> {
                    String errorMessage = "Failed to process PDF generation: " + ex.getCause().getMessage();
                    Log.errorf(ex, "Failed to process PDF generation for ID: %s", request.processId());

                    PdfGenerationError errorEvent = new PdfGenerationError(request.processId(), errorMessage);

                    eventBus.publish(
                            errorsDestination,
                            errorEvent,
                            new DeliveryOptions().setCodecName(PdfGenerationErrorCodec.CODEC_NAME));
                    Log.debugf("PDF generation error notification sent for ID: %s", request.processId());
                    return null;
                });
    }

    // This method simulates the asynchronous generation of a PDF
    private CompletableFuture<String> generatePdfAsync(String processId) {
        // Simulate a random delay between minDelayInSeconds and maxDelayInSeconds
        // only for demonstration purposes
        long delay = ThreadLocalRandom.current().nextLong(minDelayInSeconds, maxDelayInSeconds + 1);

        Log.debugf("Scheduling PDF generation for process ID: %s with a delay of %d seconds", processId, delay);

        return CompletableFuture.supplyAsync(() -> {
            String objectKey = processId + ".pdf";
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                String chainId = "document";
                String handlerId = DocConfig.TYPE_PDF;

                DocProcessContext context = DocProcessContext.newContext("processId", processId);

                docHelper.getDocProcessConfig().fullProcess(chainId, context, handlerId, baos);

                byte[] pdfBytes = baos.toByteArray();
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucketName)
                                .object(objectKey)
                                .stream(new ByteArrayInputStream(pdfBytes), pdfBytes.length, -1)
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .build());

                Log.debugf("PDF successfully generated and uploaded to MinIO with key: %s", objectKey);
                return objectKey;
            } catch (Exception e) {
                Log.errorf(e, "Failed to generate and upload PDF for process ID: %s", processId);
                throw new CompletionException(e);
            }
        }, CompletableFuture.delayedExecutor(delay, TimeUnit.SECONDS, executor));
    }
}

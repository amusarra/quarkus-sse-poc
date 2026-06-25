/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
package it.dontesta.quarkus.sse.eventbus.processor.pdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.random.RandomGenerator;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.fugerit.java.doc.base.config.DocConfig;
import org.fugerit.java.doc.base.process.DocProcessContext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.quarkus.arc.Unremovable;
import io.quarkus.logging.Log;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.pubsub.ReactivePubSubCommands;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.core.eventbus.Message;
import io.vertx.mutiny.core.eventbus.MessageConsumer;
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
    ReactiveRedisDataSource reactiveRedisDS;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    MeterRegistry meterRegistry;

    // Metriche di business
    private Counter successCounter;
    private Counter errorCounter;
    private Timer generationTimer;
    private DistributionSummary fileSizeSummary;
    private final AtomicInteger activeGenerations = new AtomicInteger(0);

    /** Dedicated Redis publisher — initialised once at startup. */
    private ReactivePubSubCommands<String> redisPublisher;

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
        initializeMetrics();
        redisPublisher = reactiveRedisDS.pubsub(String.class);
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

    private void initializeMetrics() {
        Log.debug("Initializing Micrometer metrics for PdfEventProcessor...");
        
        successCounter = Counter.builder("pdf.generation.total")
                .tag("status", "success")
                .description("Total number of successful PDF generations")
                .register(meterRegistry);
        
        errorCounter = Counter.builder("pdf.generation.total")
                .tag("status", "error")
                .description("Total number of failed PDF generations")
                .register(meterRegistry);
        
        generationTimer = Timer.builder("pdf.generation.duration.seconds")
                .description("Time taken to generate and upload PDF files")
                .register(meterRegistry);
        
        fileSizeSummary = DistributionSummary.builder("pdf.file.size.bytes")
                .description("Distribution of PDF file sizes in bytes")
                .baseUnit("bytes")
                .register(meterRegistry);
        
        Gauge.builder("pdf.generation.active", activeGenerations, AtomicInteger::get)
                .description("Number of PDF generation tasks currently executing in the worker pool")
                .register(meterRegistry);
        
        Log.debug("Micrometer metrics initialized for PdfEventProcessor");
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
                        result -> {
                            String downloadUrl = String.format("/api/pdf/download/%s", request.processId());
                            PdfGenerationCompleted completionEvent = new PdfGenerationCompleted(request.processId(),
                                    downloadUrl);

                            Log.debugf(
                                    "Attempting to send PDF completion notification for ID: %s", request.processId());

                            publishToRedis(completedDestination, completionEvent);
                            Log.debugf("PDF completion notification sent for ID: %s", request.processId());
                            
                            // Incremento counter successo
                            successCounter.increment();
                        })
                .exceptionally(ex -> {
                    String errorMessage = "Failed to process PDF generation: " + ex.getCause().getMessage();
                    Log.errorf(ex, "Failed to process PDF generation for ID: %s", request.processId());

                    PdfGenerationError errorEvent = new PdfGenerationError(request.processId(), errorMessage);

                    publishToRedis(errorsDestination, errorEvent);
                    Log.debugf("PDF generation error notification sent for ID: %s", request.processId());
                    
                    // Incremento counter errore
                    errorCounter.increment();
                    return null;
                });
    }

    /**
     * Serializes {@code event} to JSON and publishes it to the given Redis channel.
     * Errors are logged but do not propagate to the caller.
     */
    private void publishToRedis(String channel, Object event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            // ReactivePubSubCommands.publish() returns Uni<Void>: the Redis subscriber
            // count is discarded by the Quarkus API, so it cannot be logged here.
            redisPublisher.publish(channel, json)
                    .subscribe().with(
                            v -> Log.debugf("Published event to Redis channel '%s'", channel),
                            err -> Log.errorf(err, "Failed to publish event to Redis channel: '%s'", channel));
        } catch (JsonProcessingException e) {
            Log.errorf(e, "Failed to serialize event for Redis channel: '%s'", channel);
        }
    }

    // This method simulates the asynchronous generation of a PDF
    private CompletableFuture<String> generatePdfAsync(String processId) {
        // Simulate a random delay between minDelayInSeconds and maxDelayInSeconds
        // only for demonstration purposes
        long delay = ThreadLocalRandom.current().nextLong(minDelayInSeconds, maxDelayInSeconds + 1);

        Log.debugf("Scheduling PDF generation for process ID: %s with a delay of %d seconds", processId, delay);

        return CompletableFuture.supplyAsync(() -> {
            activeGenerations.incrementAndGet();
            Timer.Sample sample = Timer.start(meterRegistry);
            
            String objectKey = processId + ".pdf";
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                String[] chainId = new String[] {"simple-document", "complex-document"};
                int selectedRandomIndex = RandomGenerator.getDefault().nextInt(chainId.length);

                String handlerId = DocConfig.TYPE_PDF;

                DocProcessContext context = DocProcessContext.newContext("processId", processId);

                // this contest used only for demonstration purposes, in a real scenario you would populate it with actual data
                // and only complex-document would use it, simple-document does not use any data from the context
                context.setAttribute("listPeople", generatePeopleList());

                docHelper.getDocProcessConfig().fullProcess(chainId[selectedRandomIndex], context, handlerId, baos);

                byte[] pdfBytes = baos.toByteArray();
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucketName)
                                .object(objectKey)
                                .stream(new ByteArrayInputStream(pdfBytes), pdfBytes.length, -1)
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .build());

                Log.debugf("PDF successfully generated and uploaded to MinIO with key: %s", objectKey);
                
                // Registra dimensione del file
                fileSizeSummary.record(pdfBytes.length);
                
                // Registra durata
                sample.stop(generationTimer);
                
                return objectKey;
            } catch (Exception e) {
                Log.errorf(e, "Failed to generate and upload PDF for process ID: %s", processId);
                
                // Registra durata anche in caso di errore
                sample.stop(generationTimer);
                
                throw new CompletionException(e);
            } finally {
                activeGenerations.decrementAndGet();
            }
        }, CompletableFuture.delayedExecutor(delay, TimeUnit.SECONDS, executor));
    }

    /**
     * Return the list with the sample data to be used in the fremarker template 'simple-document.ftl'.
     *
     * @return List of maps containing sample people data
     */
    private List<Map<String, String>> generatePeopleList() {
        return List.of(
            Map.of("name", "Alice", "surname", "Rossi", "title", "Developer"),
            Map.of("name", "Bob", "surname", "Bianchi", "title", "Designer"),
            Map.of("name", "Charlie","surname", "Verdi", "title", "Manager")
        );
    }
}

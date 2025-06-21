/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
package it.dontesta.quarkus.sse.ws.rs;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestStreamElementType;

import io.quarkus.logging.Log;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.MultiEmitter;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.core.eventbus.Message;
import io.vertx.mutiny.core.eventbus.MessageConsumer;
import it.dontesta.quarkus.sse.eventbus.codec.PdfGenerationRequestCodec;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationCompleted;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationRequest;
import it.dontesta.quarkus.sse.qute.Templates;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@ApplicationScoped
@Path("/api/pdf")
public class PdfResource {

    private final ConcurrentMap<String, MultiEmitter<? super String>> sseEmitters = new ConcurrentHashMap<>();

    @Inject
    EventBus eventBus;

    @Inject
    Templates templates;

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.requests", defaultValue = "pdf-generation-requests")
    String requestsDestination;

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.completed", defaultValue = "pdf-generation-completed")
    String completedDestination;

    private MessageConsumer<PdfGenerationCompleted> consumer;

    @PostConstruct
    void init() {
        // Subscribe to the event bus for PDF generation completion events
        consumer = eventBus
                .<PdfGenerationCompleted> consumer(completedDestination)
                .handler(this::handlePdfCompletedEvent);
    }

    @PreDestroy
    void cleanup() {
        if (consumer != null) {
            consumer.unregister().await().indefinitely();
        }
    }

    @POST
    @Path("/generate")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> generatePdf() {
        String processId = UUID.randomUUID().toString();
        Log.debugf("Starting the PDF generation for ID: " + processId);

        // Publish a request to the event bus for PDF generation
        eventBus.publish(
                requestsDestination,
                new PdfGenerationRequest(processId),
                new DeliveryOptions().setCodecName(PdfGenerationRequestCodec.CODEC_NAME));

        Log.debugf("Request the PDF generation for ID %s sent to the event bus.", processId);

        return Uni.createFrom().item(processId);
    }

    @GET
    @Path("/status/{processId}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> getPdfStatus(@PathParam("processId") String processId) {
        Log.debugf("The client requested status for ID: %s", processId);

        return Multi.createFrom()
                .emitter(
                        emitter -> {
                            sseEmitters.put(processId, emitter);
                            Log.debugf("SSE emitter stored for ID: %s", processId);

                            emitter.onTermination(
                                    () -> {
                                        sseEmitters.remove(processId);
                                        Log.debugf("SSE emitter removed for ID: %s", processId);
                                    });
                        });
    }

    @GET
    @Path("/page")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance getPdfGeneratorPage() {
        return templates.pdf();
    }

    // Handler for PDF generation completion events
    private void handlePdfCompletedEvent(Message<PdfGenerationCompleted> message) {
        PdfGenerationCompleted completion = message.body();
        Log.debugf(
                "Received PDF completion event for ID: %s, URL: %s",
                completion.processId(), completion.pdfUrl());

        MultiEmitter<? super String> emitter = sseEmitters.get(completion.processId());
        if (emitter != null) {
            emitter.emit("PDF_READY:" + completion.pdfUrl());
            emitter.complete();
        } else {
            Log.warnf("No active SSE emitter found for ID: %s", completion.processId());
        }
    }
}

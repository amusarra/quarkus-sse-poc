/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
package it.dontesta.quarkus.sse.eventbus.sse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.core.eventbus.Message;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationCompleted;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;

@ApplicationScoped
public class SseBroadcaster {

    private final EventBus eventBus;
    private final Sse sse;
    private final Map<String, BroadcastProcessor<OutboundSseEvent>> processors = new ConcurrentHashMap<>();

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.completed", defaultValue = "pdf-generation-completed")
    String completedDestination;

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.errors", defaultValue = "pdf-generation-errors")
    String errorsDestination;

    @Inject
    public SseBroadcaster(EventBus eventBus, Sse sse) {
        this.eventBus = eventBus;
        this.sse = sse;
    }

    void onStart(@Observes StartupEvent ev) {
        Log.debug("SseBroadcaster is initializing...");
        eventBus.<PdfGenerationCompleted> consumer(completedDestination).handler(this::handleCompletionEvent);
        eventBus.<PdfGenerationError> consumer(errorsDestination).handler(this::handleErrorEvent);
        Log.debug("SseBroadcaster initialized and listening for completion and error events.");
    }

    public Multi<OutboundSseEvent> createStream(String processId) {
        Log.infof("Creating new SSE stream for processId: %s", processId);
        return processors.computeIfAbsent(processId, id -> BroadcastProcessor.create());
    }

    private void handleCompletionEvent(Message<PdfGenerationCompleted> message) {
        PdfGenerationCompleted event = message.body();
        String processId = event.processId();
        BroadcastProcessor<OutboundSseEvent> processor = processors.get(processId);

        if (processor != null) {
            Log.infof("Sending PDF_COMPLETED event for processId: %s", processId);
            OutboundSseEvent sseEvent = sse.newEventBuilder()
                    .name("PDF_COMPLETED")
                    .data(event)
                    .mediaType(MediaType.APPLICATION_JSON_TYPE)
                    .build();
            processor.onNext(sseEvent);
            processor.onComplete();
            processors.remove(processId);
        } else {
            Log.warnf("No active SSE processor found for processId: %s", processId);
        }
    }

    private void handleErrorEvent(Message<PdfGenerationError> message) {
        PdfGenerationError event = message.body();
        String processId = event.processId();
        BroadcastProcessor<OutboundSseEvent> processor = processors.get(processId);

        if (processor != null) {
            Log.infof("Sending PDF_ERROR event for processId: %s", processId);
            OutboundSseEvent sseEvent = sse.newEventBuilder()
                    .name("PDF_ERROR")
                    .data(event)
                    .mediaType(MediaType.APPLICATION_JSON_TYPE)
                    .build();
            processor.onNext(sseEvent);
            processor.onComplete();
            processors.remove(processId);
        } else {
            Log.warnf("No active SSE processor found for error on processId: %s", processId);
        }
    }
}
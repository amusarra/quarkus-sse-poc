/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
package it.dontesta.quarkus.sse.eventbus.config;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.vertx.mutiny.core.Vertx;
import it.dontesta.quarkus.sse.eventbus.codec.PdfGenerationCompletedCodec;
import it.dontesta.quarkus.sse.eventbus.codec.PdfGenerationRequestCodec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class EventBusConfiguration {

    private final Vertx vertx;

    @Inject
    public EventBusConfiguration(Vertx vertx) {
        this.vertx = vertx;
    }

    void onStart(@Observes StartupEvent ev) {
        Log.debug("Starting with the register custom codec for the EventBus...");

        vertx.eventBus().getDelegate().registerCodec(new PdfGenerationRequestCodec());
        vertx.eventBus().getDelegate().registerCodec(new PdfGenerationCompletedCodec());

        Log.debug("Registered custom codecs for the EventBus successfully:");
        Log.debugf("- %s", PdfGenerationRequestCodec.CODEC_NAME);
        Log.debugf("- %s", PdfGenerationCompletedCodec.CODEC_NAME);
    }
}

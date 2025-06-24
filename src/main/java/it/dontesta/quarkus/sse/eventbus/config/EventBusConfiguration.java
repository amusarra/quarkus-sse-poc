/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
package it.dontesta.quarkus.sse.eventbus.config;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.mutiny.core.Vertx;
import it.dontesta.quarkus.sse.eventbus.codec.PdfGenerationCompletedCodec;
import it.dontesta.quarkus.sse.eventbus.codec.PdfGenerationErrorCodec;
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
        Log.debug("Registering custom codecs for the EventBus...");

        registerCodec(new PdfGenerationRequestCodec());
        registerCodec(new PdfGenerationCompletedCodec());
        registerCodec(new PdfGenerationErrorCodec());
        Log.debug("Custom codecs registration process completed.");
    }

    /**
     * Registers a message codec on the Vert.x event bus, handling cases where the codec might already be registered
     * (e.g., during development hot-reloads).
     *
     * @param codec The message codec to register.
     */
    private void registerCodec(MessageCodec<?, ?> codec) {
        try {
            vertx.eventBus().getDelegate().registerCodec(codec);
            Log.debugf("Registered custom codec: '%s'", codec.name());
        } catch (IllegalStateException e) {
            Log.debugf("Custom codec '%s' is already registered. Skipping.", codec.name());
        }
    }
}

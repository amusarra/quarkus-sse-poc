/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
package it.dontesta.quarkus.sse.eventbus.codec;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationError;

public class PdfGenerationErrorCodec implements MessageCodec<PdfGenerationError, PdfGenerationError> {

    public static final String CODEC_NAME = "pdf-generation-error-codec";
    public static final int CODEC_ID = -1;

    @Override
    public void encodeToWire(Buffer buffer, PdfGenerationError pdfGenerationError) {
        // Not needed for a local codec, as it does not serialize data
    }

    @Override
    public PdfGenerationError decodeFromWire(int pos, Buffer buffer) {
        // Not needed for a local codec, as it does not serialize data
        return null;
    }

    @Override
    public PdfGenerationError transform(PdfGenerationError pdfGenerationError) {
        return pdfGenerationError;
    }

    @Override
    public String name() {
        return CODEC_NAME;
    }

    @Override
    public byte systemCodecID() {
        return CODEC_ID;
    }
}
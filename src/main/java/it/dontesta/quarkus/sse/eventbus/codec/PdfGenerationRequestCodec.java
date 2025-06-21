/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
package it.dontesta.quarkus.sse.eventbus.codec;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationRequest;

public class PdfGenerationRequestCodec
        implements MessageCodec<PdfGenerationRequest, PdfGenerationRequest> {

    public static final String CODEC_NAME = "pdf-generation-request-codec";
    public static final int CODEC_ID = -1;

    @Override
    public void encodeToWire(Buffer buffer, PdfGenerationRequest request) {
        String processId = request.processId();
        buffer.appendInt(processId.length());
        buffer.appendString(processId);
    }

    @Override
    public PdfGenerationRequest decodeFromWire(int position, Buffer buffer) {
        int positionLocal = position;
        int processIdLength = buffer.getInt(positionLocal);
        positionLocal += 4;

        String processId = buffer.getString(positionLocal, positionLocal + processIdLength);

        return new PdfGenerationRequest(processId);
    }

    @Override
    public PdfGenerationRequest transform(PdfGenerationRequest request) {
        return request;
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

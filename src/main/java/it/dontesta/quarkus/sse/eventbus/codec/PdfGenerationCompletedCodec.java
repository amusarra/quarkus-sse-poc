/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
package it.dontesta.quarkus.sse.eventbus.codec;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationCompleted;

public class PdfGenerationCompletedCodec
        implements MessageCodec<PdfGenerationCompleted, PdfGenerationCompleted> {

    public static final String CODEC_NAME = "pdf-generation-completed-codec";
    public static final int CODEC_ID = -1;

    @Override
    public void encodeToWire(Buffer buffer, PdfGenerationCompleted completion) {
        String processId = completion.processId();
        buffer.appendInt(processId.length());
        buffer.appendString(processId);

        String pdfUrl = completion.pdfUrl();
        buffer.appendInt(pdfUrl.length());
        buffer.appendString(pdfUrl);
    }

    @Override
    public PdfGenerationCompleted decodeFromWire(int position, Buffer buffer) {
        int positionLocal = position;

        int processIdLength = buffer.getInt(positionLocal);
        positionLocal += 4;
        String processId = buffer.getString(positionLocal, positionLocal + processIdLength);
        positionLocal += processIdLength;

        int pdfUrlLength = buffer.getInt(positionLocal);
        positionLocal += 4;
        String pdfUrl = buffer.getString(positionLocal, positionLocal + pdfUrlLength);

        return new PdfGenerationCompleted(processId, pdfUrl);
    }

    @Override
    public PdfGenerationCompleted transform(PdfGenerationCompleted completion) {
        return completion;
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

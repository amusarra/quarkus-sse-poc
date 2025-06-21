/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
package it.dontesta.quarkus.sse.eventbus.model;

public record PdfGenerationCompleted(String processId, String pdfUrl) {
}

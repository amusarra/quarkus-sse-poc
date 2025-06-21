/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
package it.dontesta.quarkus.sse.qute;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public class Templates {

    @Inject
    @Location("pub/pdf-generator.html")
    Template pdfGenerator;

    public TemplateInstance pdf() {
        return pdfGenerator.instance();
    }
}

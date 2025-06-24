/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
package it.dontesta.quarkus.sse.fjdoc;

import org.fugerit.java.doc.freemarker.process.FreemarkerDocProcessConfig;
import org.fugerit.java.doc.freemarker.process.FreemarkerDocProcessConfigFacade;

/**
 * DocHelper, version : auto generated on 2025-06-23 17:06:02.851
 */
public class DocHelper {

    private FreemarkerDocProcessConfig docProcessConfig = FreemarkerDocProcessConfigFacade
            .loadConfigSafe("cl://fj-doc/fm-doc-process-config.xml");

    public FreemarkerDocProcessConfig getDocProcessConfig() {
        return this.docProcessConfig;
    }

}

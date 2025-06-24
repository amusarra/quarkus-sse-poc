/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
// generated from template 'DocHelperTest.ftl' on 2025-06-23T17:06:02.854+02:00
package it.dontesta.quarkus.sse.fjdoc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.fugerit.java.doc.base.config.DocConfig;
import org.fugerit.java.doc.base.process.DocProcessContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * This is a basic example of Fugerit Venus Doc usage,
 * running this junit will :
 * - creates data to be used in document model
 * - renders the 'document.ftl' template
 * - print the result in markdown format
 *
 * For further documentation :
 * https://github.com/fugerit-org/fj-doc
 *
 * NOTE: This is a 'Hello World' style example, adapt it to your scenario, especially :
 * - remove system out and system err with your logging system
 * - change the doc handler and the output mode (here a ByteArrayOutputStream buffer is used)
 */

@Tag("fj-doc")
@Tag("integration-test")
class DocHelperTest {

    @Test
    void testDocProcess() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            // creates the doc helper
            DocHelper docHelper = new DocHelper();
            // create custom data for the fremarker template 'document.ftl'

            String chainId = "document";
            // handler id
            String handlerId = DocConfig.TYPE_HTML;
            // output generation
            docHelper.getDocProcessConfig().fullProcess(chainId,
                    DocProcessContext.newContext("processId", UUID.randomUUID().toString()), handlerId, baos);
            // print the output
            System.out.println("html output : \n" + new String(baos.toByteArray(), StandardCharsets.UTF_8));
            Assertions.assertNotEquals(0, baos.size());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}

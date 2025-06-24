/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
package it.dontesta.quarkus.sse;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.allOf;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@Tag("integration-test")
@Tag("rest-service")
class PdfResourceIntegrationTest {

    @Test
    void testPdfGenerationFlow() {
        // 1. Request the generation of a PDF document
        String processId = given()
                .when()
                .post("/api/pdf/generate")
                .then()
                .statusCode(200)
                .body(notNullValue())
                .extract()
                .asString();

        // 2. Verify that the status SSE returns the PDF_READY event
        given()
                .when()
                .get("/api/pdf/status/" + processId)
                .then()
                .statusCode(200)
                .contentType(containsString("text/event-stream"))
                .body(allOf(
                        containsString("event:PDF_COMPLETED"),
                        containsString("\"pdfUrl\":\"/api/pdf/download/" + processId + "\"")));
    }
}

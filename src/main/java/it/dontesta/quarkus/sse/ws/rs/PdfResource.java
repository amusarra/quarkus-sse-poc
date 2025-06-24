/*
 * Copyright (c) 2025 Antonio Musarra's Blog.
 * SPDX-License-Identifier: MIT
 */
package it.dontesta.quarkus.sse.ws.rs;

import java.io.InputStream;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.quarkus.logging.Log;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.mutiny.core.eventbus.EventBus;
import it.dontesta.quarkus.sse.eventbus.codec.PdfGenerationRequestCodec;
import it.dontesta.quarkus.sse.eventbus.model.PdfGenerationRequest;
import it.dontesta.quarkus.sse.eventbus.sse.SseBroadcaster;
import it.dontesta.quarkus.sse.qute.Templates;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.OutboundSseEvent;

@ApplicationScoped
@Path("/api/pdf")
public class PdfResource {

    @Inject
    EventBus eventBus;

    @Inject
    Templates templates;

    @Inject
    SseBroadcaster sseBroadcaster;

    @Inject
    MinioClient minioClient;

    @Inject
    @ConfigProperty(name = "pdf.minio.bucket-name")
    String bucketName;

    @Inject
    @ConfigProperty(name = "pdf.eventbus.destination.requests", defaultValue = "pdf-generation-requests")
    String requestsDestination;

    @POST
    @Path("/generate")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> generatePdf() {
        String processId = UUID.randomUUID().toString();
        Log.debugf("Starting the PDF generation for ID: " + processId);

        // Publish a request to the event bus for PDF generation
        eventBus.publish(
                requestsDestination,
                new PdfGenerationRequest(processId),
                new DeliveryOptions().setCodecName(PdfGenerationRequestCodec.CODEC_NAME));

        Log.debugf("Request the PDF generation for ID %s sent to the event bus.", processId);

        return Uni.createFrom().item(processId);
    }

    @GET
    @Path("/status/{processId}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<OutboundSseEvent> getPdfStatus(@PathParam("processId") String processId) {
        Log.debugf("The client requested status for ID: %s", processId);
        return sseBroadcaster.createStream(processId);
    }

    @GET
    @Path("/download/{processId}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Uni<Response> downloadPdf(@PathParam("processId") String processId) {
        return Uni.createFrom().item(() -> {
            String objectKey = processId + ".pdf";
            try {
                InputStream stream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket(bucketName)
                                .object(objectKey)
                                .build());

                Response.ResponseBuilder response = Response.ok(stream);
                response.header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=" + objectKey);
                response.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM);
                return response.build();
            } catch (ErrorResponseException e) {
                if ("NoSuchKey".equals(e.errorResponse().code())) {
                    Log.warnf("PDF with key: %s not found in MinIO bucket: %s", objectKey, bucketName);
                    return Response.status(Response.Status.NOT_FOUND).build();
                }
                Log.errorf(e, "Failed to download PDF with key: %s from MinIO bucket: %s", objectKey, bucketName);
                return Response.serverError().entity(e.getMessage()).build();
            } catch (Exception e) {
                Log.errorf(e, "An unexpected error occurred while downloading PDF with key: %s", objectKey);
                return Response.serverError().entity(e.getMessage()).build();
            }
        });
    }

    @GET
    @Path("/page")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance getPdfGeneratorPage() {
        return templates.pdf();
    }
}

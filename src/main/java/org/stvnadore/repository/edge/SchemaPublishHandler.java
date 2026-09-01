package org.stvnadore.repository.edge;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.stvnadore.repository.SimpleSchemaRepositoryEngine;
import org.stvnadore.repository.domain.PublishRequest;
import org.stvnadore.repository.domain.PublishResult;
import org.stvnadore.repository.domain.SchemaMetadata;
import org.stvnadore.repository.domain.SchemaRepositoryEngine;
import org.stvnadore.repository.infrastructure.StvnCasPackager;
import org.stvnadore.repository.ports.CasStoragePort;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP request handler managing schema publication and retrieval REST endpoints.
 */
public class SchemaPublishHandler implements Handler {
    private final SchemaRepositoryEngine engine;
    private final CasStoragePort casStoragePort;

    /**
     * Constructs a SchemaPublishHandler with an engine and CAS storage port.
     *
     * @param engine the schema repository engine
     * @param casStoragePort the CAS storage port
     */
    public SchemaPublishHandler(SchemaRepositoryEngine engine, CasStoragePort casStoragePort) {
        this.engine = engine;
        this.casStoragePort = casStoragePort;
    }

    /**
     * Convenience constructor extracting the CAS storage port from the engine if available.
     *
     * @param engine the schema repository engine
     */
    public SchemaPublishHandler(SchemaRepositoryEngine engine) {
        this(
            engine,
            (engine instanceof SimpleSchemaRepositoryEngine simpleEngine) ? simpleEngine.getCasStoragePort() : null
        );
    }

    /**
     * Registers REST API routes on the provided Javalin application.
     *
     * @param app the Javalin application instance
     */
    public void configureRoutes(Javalin app) {
        app.post("/api/v1/schemas/{name}", this);
        app.get("/api/v1/schemas/{name}/shapes/{signature}", this::handleGetSchema);
        app.get("/api/v1/schemas/cas/{hash}", this::handleGetCasPayload);
    }

    @Override
    public void handle(Context ctx) throws Exception {
        String contentType = ctx.contentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("application/stvn")) {
            ctx.status(415);
            ctx.json(Map.of(
                "error", "Unsupported Media Type",
                "message", "Request Content-Type must be application/stvn"
            ));
            return;
        }

        String schemaName = ctx.pathParam("name");
        String sourceText = ctx.body();
        PublishRequest request = new PublishRequest(schemaName, sourceText);
        PublishResult result = engine.publish(request);

        switch (result) {
            case PublishResult.Success(var metadata) -> {
                ctx.status(201);
                ctx.json(metadata);
            }
            case PublishResult.IdempotentCollision(var metadata) -> {
                ctx.status(200);
                ctx.json(metadata);
            }
            case PublishResult.SchemaConflict(var name, var existingHash, var submittedHash) -> {
                ctx.status(409);
                ctx.json(Map.of(
                    "error", "Conflict",
                    "message", "Schema name '" + name + "' already exists with hash " + existingHash + ". Mutations are prohibited."
                ));
            }
            case PublishResult.ValidationError(var diagnostics) -> {
                ctx.status(422);
                ctx.json(diagnostics);
            }
            case PublishResult.IndexingDeferred(var metadata) -> {
                ctx.status(202);
                ctx.json(metadata);
            }
        }
    }

    /**
     * Handles GET requests for schema metadata lookup by nominal name and shape signature.
     *
     * @param ctx the Javalin HTTP context
     */
    public void handleGetSchema(Context ctx) {
        String schemaName = ctx.pathParam("name");
        String shapeSignature = ctx.pathParam("signature");
        Optional<SchemaMetadata> metadataOpt = engine.getSchemaMetadata(schemaName, shapeSignature);

        if (metadataOpt.isPresent()) {
            ctx.status(200);
            ctx.json(metadataOpt.get());
        } else {
            ctx.status(404);
        }
    }

    /**
     * Handles GET requests for retrieving raw canonical schema text by 64-character CAS hash.
     *
     * @param ctx the Javalin HTTP context
     */
    public void handleGetCasPayload(Context ctx) {
        String casHash = ctx.pathParam("hash");
        if (casHash.length() != 64) {
            ctx.status(400);
            ctx.json(Map.of("error", "Invalid CAS hash length. Expected 64-char hex string."));
            return;
        }

        if (casStoragePort == null) {
            ctx.status(500);
            ctx.json(Map.of("error", "CAS storage port is not configured."));
            return;
        }

        byte[] envelopeBytes = casStoragePort.read(casHash);
        if (envelopeBytes == null) {
            ctx.status(404);
            return;
        }

        String envelopeText = new String(envelopeBytes, StandardCharsets.UTF_8);
        Optional<String> unpacked = StvnCasPackager.unpackSourceText(envelopeText);
        String responsePayload = unpacked.orElse(envelopeText);

        // Return raw application/stvn schema stream
        ctx.contentType("application/stvn");
        ctx.result(responsePayload);
    }
}

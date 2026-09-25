package org.stvnadore.repository.edge;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.binary.SchemaIdentityStrategy;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryEncoder;
import org.stvnadore.core.binary.exceptions.UnsupportedEncodingStrategyException;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.utils.StvnStringCapacityUtils;
import org.stvnadore.core.validation.MalformedPayloadException;
import org.stvnadore.repository.SimpleSchemaRepositoryEngine;
import org.stvnadore.repository.domain.PublishRequest;
import org.stvnadore.repository.domain.PublishResult;
import org.stvnadore.repository.domain.SchemaMetadata;
import org.stvnadore.repository.domain.SchemaRepositoryEngine;
import org.stvnadore.repository.infrastructure.StvnCasPackager;
import org.stvnadore.repository.ports.CasStoragePort;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.zip.CRC32C;
import org.stvnadore.core.binary.StvnSchemaHasher;

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
        app.post("/api/v1/artifacts/binary/{name}", this::handleBinaryUpload);
        app.get("/api/v1/schemas/{name}/shapes/{signature}", this::handleGetSchema);
        app.get("/api/v1/schemas/cas/{hash}", this::handleGetCasPayload);
    }

    @Override
    public void handle(Context ctx) throws Exception {
        String contentType = ctx.contentType();
        if (contentType == null) {
            ctx.status(415);
            ctx.json(Map.of(
                "error", "Unsupported Media Type",
                "message", "Request Content-Type must be application/stvn or application/stvn-bin"
            ));
            return;
        }

        String lowerContentType = contentType.toLowerCase();
        if (lowerContentType.startsWith("application/stvn-bin") || lowerContentType.startsWith("application/octet-stream")) {
            handleBinaryUpload(ctx);
            return;
        }

        if (!lowerContentType.startsWith("application/stvn")) {
            ctx.status(415);
            ctx.json(Map.of(
                "error", "Unsupported Media Type",
                "message", "Request Content-Type must be application/stvn or application/stvn-bin"
            ));
            return;
        }

        String schemaName = ctx.pathParam("name");
        String sourceText = ctx.body();

        if (sourceText.length() > StvnStringCapacityUtils.DEFAULT_UNBOUNDED_STRING_CAPACITY) {
            ctx.status(422);
            ctx.json(Map.of(
                "error", "Capacity Overflow",
                "message", "Payload exceeds maximum allowed string capacity: " + StvnStringCapacityUtils.DEFAULT_UNBOUNDED_STRING_CAPACITY + " characters"
            ));
            return;
        }

        PublishRequest request = new PublishRequest(schemaName, sourceText);
        PublishResult result = engine.publish(request);
        processPublishResult(ctx, result);
    }

    /**
     * Ingests and verifies incoming binary STVN payload streams.
     * Enforces hardware-accelerated CRC-32C verification and Byte 4 wire governance.
     *
     * @param ctx the Javalin HTTP context
     */
    public void handleBinaryUpload(Context ctx) {
        String schemaName = ctx.pathParam("name");
        byte[] binaryBytes = ctx.bodyAsBytes();

        if (binaryBytes == null || binaryBytes.length == 0) {
            ctx.status(400);
            ctx.json(Map.of("error", "Bad Request", "message", "Binary payload cannot be empty"));
            return;
        }

        if (binaryBytes.length > StvnStringCapacityUtils.DEFAULT_UNBOUNDED_STRING_CAPACITY) {
            ctx.status(422);
            ctx.json(Map.of(
                "error", "Capacity Overflow",
                "message", "Binary payload exceeds maximum allowed capacity: " + StvnStringCapacityUtils.DEFAULT_UNBOUNDED_STRING_CAPACITY + " bytes"
            ));
            return;
        }

        // Zero-Trust Perimeter Verification:
        // Enforces magic bytes, CRC-32C trailer validation (Byte 4 Bit 7), and Strategy Sentinel 0x7 rejection
        try {
            ByteBuffer buffer = ByteBuffer.wrap(binaryBytes);
            var root = StvnBinaryDecoder.open(buffer);

            PublishRequest request = new PublishRequest(schemaName, binaryBytes);
            PublishResult result = engine.publishBinary(request, root);
            processPublishResult(ctx, result);
        } catch (MalformedPayloadException e) {
            ctx.status(422);
            ctx.json(Map.of(
                "error", "Malformed Payload",
                "message", e.getMessage() != null ? e.getMessage() : "Malformed binary payload"
            ));
        } catch (UnsupportedEncodingStrategyException e) {
            ctx.status(422);
            ctx.json(Map.of(
                "error", "Unsupported Encoding Strategy",
                "message", e.getMessage() != null ? e.getMessage() : "Unsupported binary encoding strategy"
            ));
        } catch (IllegalArgumentException e) {
            ctx.status(400);
            ctx.json(Map.of(
                "error", "Bad Request",
                "message", e.getMessage() != null ? e.getMessage() : "Invalid STVN binary"
            ));
        }
    }

    private void processPublishResult(Context ctx, PublishResult result) {
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
            case PublishResult.AliasConflict(var submitted, var existing, var hash) -> {
                ctx.status(409);
                ctx.json(Map.of(
                    "error", "Conflict",
                    "message", "CAS hash '" + hash + "' is already registered under schema '" + existing + "'. Cannot register duplicate content as '" + submitted + "'."
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

        // 1. If stored bytes are already an STVN binary payload (magic bytes 'S','T','V','N')
        boolean isStoredBinary = envelopeBytes.length >= 4 &&
            envelopeBytes[0] == (byte) 'S' && envelopeBytes[1] == (byte) 'T' &&
            envelopeBytes[2] == (byte) 'V' && envelopeBytes[3] == (byte) 'N';

        String acceptHeader = ctx.header("Accept");
        boolean requestsBinary = acceptHeader != null && acceptHeader.toLowerCase().contains("application/stvn-bin");

        if (isStoredBinary) {
            ctx.contentType("application/stvn-bin");
            ctx.result(envelopeBytes);
            return;
        }

        String envelopeText = new String(envelopeBytes, StandardCharsets.UTF_8);
        Optional<String> unpacked = StvnCasPackager.unpackSourceText(envelopeText);
        String responsePayload = unpacked.orElse(envelopeText);

        if (requestsBinary) {
            try {
                byte[] hashBytes = HexFormat.of().parseHex(casHash);
                // Attempt to compile sourceText first, or fallback to envelopeText AST
                Optional<StvnValue> astOpt = StvnCompiler.compile(responsePayload);
                if (astOpt.isEmpty()) {
                    astOpt = StvnCompiler.compile(envelopeText);
                }
                if (astOpt.isEmpty()) {
                    String fallbackEnvelope = StvnCasPackager.packageEnvelope("schema.stvn_inclf", casHash, responsePayload);
                    astOpt = StvnCompiler.compile(fallbackEnvelope);
                }
                if (astOpt.isPresent()) {
                    StvnValue rootVal = astOpt.get();
                    byte[] astSchemaHash = (rootVal.schema() != null)
                        ? StvnSchemaHasher.computeSha256(rootVal.schema())
                        : hashBytes;
                    var encoder = new StvnBinaryEncoder(
                        true,
                        new SchemaIdentityStrategy.ExplicitSha256(astSchemaHash)
                    );
                    ByteBuffer encoded = encoder.encode(rootVal);
                    byte[] binaryResponse = new byte[encoded.remaining()];
                    encoded.get(binaryResponse);

                    // Overwrite embedded 32-byte header hash with authoritative CAS address
                    System.arraycopy(hashBytes, 0, binaryResponse, 5, 32);

                    // Recalculate CRC-32C trailer over payload (excluding last 4 trailer bytes)
                    CRC32C crc = new CRC32C();
                    crc.update(binaryResponse, 0, binaryResponse.length - 4);
                    int crcVal = (int) crc.getValue();
                    binaryResponse[binaryResponse.length - 4] = (byte) (crcVal & 0xFF);
                    binaryResponse[binaryResponse.length - 3] = (byte) ((crcVal >>> 8) & 0xFF);
                    binaryResponse[binaryResponse.length - 2] = (byte) ((crcVal >>> 16) & 0xFF);
                    binaryResponse[binaryResponse.length - 1] = (byte) ((crcVal >>> 24) & 0xFF);

                    ctx.contentType("application/stvn-bin");
                    ctx.result(binaryResponse);
                    return;
                }
            } catch (Exception ignored) {
                // If binary compilation/encoding fails, fall through to text response
            }
        }

        // Return raw application/stvn schema stream
        ctx.contentType("application/stvn");
        ctx.result(responsePayload);
    }
}

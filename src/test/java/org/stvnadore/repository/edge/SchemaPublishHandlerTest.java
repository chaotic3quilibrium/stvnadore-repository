package org.stvnadore.repository.edge;

import io.javalin.http.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.stvnadore.repository.domain.*;
import org.stvnadore.repository.infrastructure.StvnCasPackager;
import org.stvnadore.repository.ports.CasStoragePort;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.mockito.Mockito.*;

public class SchemaPublishHandlerTest {

    private SchemaRepositoryEngine engine;
    private CasStoragePort casStoragePort;
    private SchemaPublishHandler handler;
    private Context ctx;

    @BeforeEach
    public void setUp() {
        engine = mock(SchemaRepositoryEngine.class);
        casStoragePort = mock(CasStoragePort.class);
        handler = new SchemaPublishHandler(engine, casStoragePort);
        ctx = mock(Context.class);
    }

    @Test
    public void testPublishSuccess() throws Exception {
        String name = "my-schema";
        String body = "schema { field1: String }";
        SchemaMetadata metadata = new SchemaMetadata(name, "ShapeSignature", "Hash123");
        PublishResult result = new PublishResult.Success(metadata);

        when(ctx.contentType()).thenReturn("application/stvn");
        when(ctx.pathParam("name")).thenReturn(name);
        when(ctx.body()).thenReturn(body);
        when(engine.publish(new PublishRequest(name, body))).thenReturn(result);

        handler.handle(ctx);

        verify(ctx).status(201);
        verify(ctx).json(metadata);
    }

    @Test
    public void testPublishUnsupportedMediaType() throws Exception {
        when(ctx.contentType()).thenReturn("application/json");

        handler.handle(ctx);

        verify(ctx).status(415);
        verifyNoInteractions(engine);
    }

    @Test
    public void testPublishIdempotentCollision() throws Exception {
        String name = "existing-schema";
        String body = "schema { field1: String }";
        SchemaMetadata metadata = new SchemaMetadata(name, "ShapeSignature", "Hash123");
        PublishResult result = new PublishResult.IdempotentCollision(metadata);

        when(ctx.contentType()).thenReturn("application/stvn");
        when(ctx.pathParam("name")).thenReturn(name);
        when(ctx.body()).thenReturn(body);
        when(engine.publish(new PublishRequest(name, body))).thenReturn(result);

        handler.handle(ctx);

        verify(ctx).status(200);
        verify(ctx).json(metadata);
    }

    @Test
    public void testPublishSchemaConflictThrows409() throws Exception {
        String name = "conflicted-schema";
        String body = "schema { field1: String }";
        PublishResult result = new PublishResult.SchemaConflict(name, "oldHash", "newHash");

        when(ctx.contentType()).thenReturn("application/stvn");
        when(ctx.pathParam("name")).thenReturn(name);
        when(ctx.body()).thenReturn(body);
        when(engine.publish(new PublishRequest(name, body))).thenReturn(result);

        handler.handle(ctx);

        verify(ctx).status(409);
    }

    @Test
    public void testPublishValidationError() throws Exception {
        String name = "bad-schema";
        String body = "invalid-syntax";
        List<CompileDiagnostic> diagnostics = List.of(
            new CompileDiagnostic("Syntax error", 1, 10),
            new CompileDiagnostic("Missing type", 2, 5)
        );
        PublishResult result = new PublishResult.ValidationError(diagnostics);

        when(ctx.contentType()).thenReturn("application/stvn");
        when(ctx.pathParam("name")).thenReturn(name);
        when(ctx.body()).thenReturn(body);
        when(engine.publish(new PublishRequest(name, body))).thenReturn(result);

        handler.handle(ctx);

        verify(ctx).status(422);
        verify(ctx).json(diagnostics);
    }

    @Test
    public void testPublishIndexingDeferred() throws Exception {
        String name = "deferred-schema";
        String body = "schema { field1: String }";
        SchemaMetadata metadata = new SchemaMetadata(name, "ShapeSignature", "Hash123");
        PublishResult result = new PublishResult.IndexingDeferred(metadata);

        when(ctx.contentType()).thenReturn("application/stvn");
        when(ctx.pathParam("name")).thenReturn(name);
        when(ctx.body()).thenReturn(body);
        when(engine.publish(new PublishRequest(name, body))).thenReturn(result);

        handler.handle(ctx);

        verify(ctx).status(202);
        verify(ctx).json(metadata);
    }

    @Test
    public void testGetSchemaSuccess() {
        String name = "my-schema";
        String signature = "ShapeSig";
        SchemaMetadata metadata = new SchemaMetadata(name, signature, "Hash123");

        when(ctx.pathParam("name")).thenReturn(name);
        when(ctx.pathParam("signature")).thenReturn(signature);
        when(engine.getSchemaMetadata(name, signature)).thenReturn(java.util.Optional.of(metadata));

        handler.handleGetSchema(ctx);

        verify(ctx).status(200);
        verify(ctx).json(metadata);
    }

    @Test
    public void testGetSchemaNotFound() {
        String name = "non-existent";
        String signature = "ShapeSig";

        when(ctx.pathParam("name")).thenReturn(name);
        when(ctx.pathParam("signature")).thenReturn(signature);
        when(engine.getSchemaMetadata(name, signature)).thenReturn(java.util.Optional.empty());

        handler.handleGetSchema(ctx);

        verify(ctx).status(404);
        verify(ctx, never()).json(any());
    }

    @Test
    public void testGetCasPayloadSuccess() {
        String casHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        String innerSourceText = "{\n  :type :String\n  :body \"hello\"\n}";
        String envelope = StvnCasPackager.packageEnvelope("my-schema", casHash, innerSourceText);

        when(ctx.pathParam("hash")).thenReturn(casHash);
        when(casStoragePort.read(casHash)).thenReturn(envelope.getBytes(StandardCharsets.UTF_8));

        handler.handleGetCasPayload(ctx);

        verify(ctx).contentType("application/stvn");
        verify(ctx).result(innerSourceText);
    }

    @Test
    public void testGetCasPayloadNotFound() {
        String casHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        when(ctx.pathParam("hash")).thenReturn(casHash);
        when(casStoragePort.read(casHash)).thenReturn(null);

        handler.handleGetCasPayload(ctx);

        verify(ctx).status(404);
    }

    @Test
    public void testPublishBinarySuccess() throws Exception {
        String name = "valid-binary";
        byte[] payload = Files.readAllBytes(Paths.get("target/test-classes/fixtures/valid-syntax/crc32c_trailer_valid.stvn_bin"));
        SchemaMetadata metadata = new SchemaMetadata(name, "ShapeSig", "Hash123");
        PublishResult result = new PublishResult.Success(metadata);

        when(ctx.contentType()).thenReturn("application/stvn-bin");
        when(ctx.pathParam("name")).thenReturn(name);
        when(ctx.bodyAsBytes()).thenReturn(payload);
        when(engine.publishBinary(any(), any())).thenReturn(result);

        handler.handle(ctx);

        verify(ctx).status(201);
        verify(ctx).json(metadata);
    }

    @Test
    public void testPublishBinaryEmptyPayloadThrows400() throws Exception {
        String name = "empty-binary";
        when(ctx.contentType()).thenReturn("application/stvn-bin");
        when(ctx.pathParam("name")).thenReturn(name);
        when(ctx.bodyAsBytes()).thenReturn(new byte[0]);

        handler.handle(ctx);

        verify(ctx).status(400);
        verifyNoInteractions(engine);
    }

    @Test
    public void testPublishBinaryInvalidMagicBytesThrows400() throws Exception {
        String name = "bad-magic";
        byte[] badMagic = new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05};
        when(ctx.contentType()).thenReturn("application/stvn-bin");
        when(ctx.pathParam("name")).thenReturn(name);
        when(ctx.bodyAsBytes()).thenReturn(badMagic);

        handler.handle(ctx);

        verify(ctx).status(400);
        verifyNoInteractions(engine);
    }

    @Test
    public void testPublishBinaryMalformedCrc32cThrows422() throws Exception {
        String name = "tampered-crc";
        byte[] tampered = Files.readAllBytes(Paths.get("target/test-classes/fixtures/invalid-syntax/binary_crc32c_payload_tampered.stvn_bin"));
        when(ctx.contentType()).thenReturn("application/stvn-bin");
        when(ctx.pathParam("name")).thenReturn(name);
        when(ctx.bodyAsBytes()).thenReturn(tampered);

        handler.handle(ctx);

        verify(ctx).status(422);
        verifyNoInteractions(engine);
    }

    @Test
    public void testPublishBinarySentinel0x7Throws422() throws Exception {
        String name = "sentinel-0x7";
        byte[] sentinel = Files.readAllBytes(Paths.get("target/test-classes/fixtures/invalid-syntax/binary_strategy_sentinel_0x7.stvn_bin"));
        when(ctx.contentType()).thenReturn("application/stvn-bin");
        when(ctx.pathParam("name")).thenReturn(name);
        when(ctx.bodyAsBytes()).thenReturn(sentinel);

        handler.handle(ctx);

        verify(ctx).status(422);
        verifyNoInteractions(engine);
    }
}

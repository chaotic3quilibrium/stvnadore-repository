package org.stvnadore.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.binary.SchemaIdentityStrategy;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.utils.StvnStringCapacityUtils;
import org.stvnadore.repository.domain.*;
import org.stvnadore.repository.ports.CasStoragePort;
import org.stvnadore.repository.ports.IndexRepositoryPort;
import org.stvnadore.repository.ports.VersionCatalogCache;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class SimpleSchemaRepositoryEngineTest {

    private CasStoragePort casStoragePort;
    private IndexRepositoryPort indexRepositoryPort;
    private VersionCatalogCache versionCatalogCache;
    private SimpleSchemaRepositoryEngine engine;

    @BeforeEach
    public void setUp() {
        casStoragePort = mock(CasStoragePort.class);
        indexRepositoryPort = mock(IndexRepositoryPort.class);
        versionCatalogCache = mock(VersionCatalogCache.class);
        engine = new SimpleSchemaRepositoryEngine(casStoragePort, indexRepositoryPort, versionCatalogCache);
    }

    @Test
    public void testPublishSuccess() {
        String sourceText = "{\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n    :UserName { #minSize 1 } :String\n  }\n}";
        PublishRequest request = new PublishRequest("user-profile.stvn_inclf", sourceText);
        when(indexRepositoryPort.findBySchemaName("user-profile.stvn_inclf")).thenReturn(Optional.empty());

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.Success.class, result);
        PublishResult.Success success = (PublishResult.Success) result;
        SchemaMetadata metadata = success.metadata();

        assertEquals("user-profile.stvn_inclf", metadata.schemaName());
        assertNotNull(metadata.casHash());
        assertTrue(metadata.shapeSignature().contains(":defs"));

        verify(casStoragePort).write(eq(metadata.casHash()), any(byte[].class));
        verify(indexRepositoryPort).save(eq(metadata), eq(sourceText));
        verify(versionCatalogCache).put(metadata);
    }

    @Test
    public void testPublishRejectsNonStvnInclfFilename() {
        String sourceText = "{\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("user-profile.stvn", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("must strictly end with '.stvn_inclf'"));
        verifyNoInteractions(casStoragePort);
        verifyNoInteractions(indexRepositoryPort);
    }

    @Test
    public void testPublishRejectsSchemaContainingIncludes() {
        String sourceText = "{\n  :defs {\n    :include [ \"other.stvn_inclf\" ]\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("invalid-include.stvn_inclf", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().stream().anyMatch(d ->
            d.message().contains("ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT") || d.message().contains("cannot contain include statements")));
        verifyNoInteractions(casStoragePort);
    }

    @Test
    public void testPublishRejectsSchemaContainingBodyOrType() {
        String sourceText = "{\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n  :type :UserId\n  :body 100\n}";
        PublishRequest request = new PublishRequest("invalid-body.stvn_inclf", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("prohibited in flat schema document"));
        verifyNoInteractions(casStoragePort);
    }

    @Test
    public void testPublishPackageEnclosureAndFqniExpansion() {
        String sourceText = "{\n  :defs {\n    :package :org/example {\n      :UserId { #unsigned #size 64 } :Int\n    }\n  }\n}";
        PublishRequest request = new PublishRequest("packaged.stvn_inclf", sourceText);
        when(indexRepositoryPort.findBySchemaName("packaged.stvn_inclf")).thenReturn(Optional.empty());

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.Success.class, result);
        PublishResult.Success success = (PublishResult.Success) result;
        assertTrue(success.metadata().shapeSignature().contains(":org/example/UserId"));
        verify(casStoragePort).write(eq(success.metadata().casHash()), any(byte[].class));
        verify(indexRepositoryPort).save(eq(success.metadata()), eq(sourceText));
    }

    @Test
    public void testPublishMutationConflictReturnsSchemaConflict() {
        String sourceText = "{\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("user.stvn_inclf", sourceText);
        SchemaMetadata existing = new SchemaMetadata("user.stvn_inclf", "existingSig", "differentHash11111111111111111111111111111111111111111111111111111111");
        when(indexRepositoryPort.findBySchemaName("user.stvn_inclf")).thenReturn(Optional.of(existing));

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.SchemaConflict.class, result);
        PublishResult.SchemaConflict conflict = (PublishResult.SchemaConflict) result;
        assertEquals("user.stvn_inclf", conflict.schemaName());
        assertEquals("differentHash11111111111111111111111111111111111111111111111111111111", conflict.existingHash());
        assertNotEquals(conflict.existingHash(), conflict.submittedHash());

        verifyNoInteractions(casStoragePort);
        verify(indexRepositoryPort, never()).save(any(SchemaMetadata.class), anyString());
    }

    @Test
    public void testPublishIdempotentCollision() {
        String sourceText = "{\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("user.stvn_inclf", sourceText);

        // First compile to get actual hash
        PublishResult first = engine.publish(request);
        assertInstanceOf(PublishResult.Success.class, first);
        String actualHash = ((PublishResult.Success) first).metadata().casHash();

        // Now mock findBySchemaName returning existing with same hash
        SchemaMetadata existing = new SchemaMetadata("user.stvn_inclf", "sig", actualHash);
        when(indexRepositoryPort.findBySchemaName("user.stvn_inclf")).thenReturn(Optional.of(existing));

        PublishResult second = engine.publish(request);
        assertInstanceOf(PublishResult.IdempotentCollision.class, second);
        PublishResult.IdempotentCollision collision = (PublishResult.IdempotentCollision) second;
        assertEquals(actualHash, collision.metadata().casHash());
    }

    @Test
    public void testPublishAliasConflictReturnsAliasConflict() {
        String sourceText = "{\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("alias-user.stvn_inclf", sourceText);

        // Pre-determine hash by running publish once against pristine mock
        PublishResult probe = engine.publish(request);
        assertInstanceOf(PublishResult.Success.class, probe);
        String actualHash = ((PublishResult.Success) probe).metadata().casHash();

        // Reset mocks to test alias conflict pre-check branch
        reset(casStoragePort, indexRepositoryPort, versionCatalogCache);
        when(indexRepositoryPort.findBySchemaName("alias-user.stvn_inclf")).thenReturn(Optional.empty());
        when(indexRepositoryPort.findByCasHash(actualHash)).thenReturn(
            Optional.of(new SchemaMetadata("canonical-user.stvn_inclf", "sig", actualHash))
        );

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.AliasConflict.class, result);
        PublishResult.AliasConflict aliasConflict = (PublishResult.AliasConflict) result;
        assertEquals("alias-user.stvn_inclf", aliasConflict.submittedSchemaName());
        assertEquals("canonical-user.stvn_inclf", aliasConflict.existingSchemaName());
        assertEquals(actualHash, aliasConflict.casHash());

        verifyNoInteractions(casStoragePort);
        verify(indexRepositoryPort, never()).save(any(SchemaMetadata.class), anyString());
    }

    @Test
    public void testPublishConcurrentRaceAliasConflictFallback() {
        String sourceText = "{\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("race-alias.stvn_inclf", sourceText);

        PublishResult probe = engine.publish(new PublishRequest("probe.stvn_inclf", sourceText));
        String actualHash = ((PublishResult.Success) probe).metadata().casHash();

        reset(casStoragePort, indexRepositoryPort, versionCatalogCache);
        when(indexRepositoryPort.findBySchemaName("race-alias.stvn_inclf")).thenReturn(Optional.empty());
        when(indexRepositoryPort.findByCasHash(actualHash))
            .thenReturn(Optional.empty()) // first check before save
            .thenReturn(Optional.of(new SchemaMetadata("winner.stvn_inclf", "sig", actualHash))); // catch block check
        doThrow(new DuplicateIndexException("uq_version_catalog_cas_hash"))
            .when(indexRepositoryPort).save(any(SchemaMetadata.class), eq(sourceText));

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.AliasConflict.class, result);
        PublishResult.AliasConflict conflict = (PublishResult.AliasConflict) result;
        assertEquals("race-alias.stvn_inclf", conflict.submittedSchemaName());
        assertEquals("winner.stvn_inclf", conflict.existingSchemaName());
        assertEquals(actualHash, conflict.casHash());
    }

    @Test
    public void testPublishConcurrentRaceSchemaConflictFallback() {
        String sourceText = "{\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("race-schema.stvn_inclf", sourceText);

        PublishResult probe = engine.publish(new PublishRequest("probe.stvn_inclf", sourceText));
        String actualHash = ((PublishResult.Success) probe).metadata().casHash();

        reset(casStoragePort, indexRepositoryPort, versionCatalogCache);
        when(indexRepositoryPort.findBySchemaName("race-schema.stvn_inclf"))
            .thenReturn(Optional.empty()) // first check
            .thenReturn(Optional.of(new SchemaMetadata("race-schema.stvn_inclf", "sig", "concurrentOtherHash111111111111111111111111111111111111111111111111"))); // catch block check
        when(indexRepositoryPort.findByCasHash(actualHash)).thenReturn(Optional.empty());
        doThrow(new DuplicateIndexException("uq_version_catalog_schema_name"))
            .when(indexRepositoryPort).save(any(SchemaMetadata.class), eq(sourceText));

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.SchemaConflict.class, result);
        PublishResult.SchemaConflict conflict = (PublishResult.SchemaConflict) result;
        assertEquals("race-schema.stvn_inclf", conflict.schemaName());
        assertEquals("concurrentOtherHash111111111111111111111111111111111111111111111111", conflict.existingHash());
        assertEquals(actualHash, conflict.submittedHash());
    }

    @Test
    public void testPublishConcurrentRaceIdempotentCollisionFallback() {
        String sourceText = "{\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("race-idemp.stvn_inclf", sourceText);

        PublishResult probe = engine.publish(new PublishRequest("probe.stvn_inclf", sourceText));
        String actualHash = ((PublishResult.Success) probe).metadata().casHash();

        reset(casStoragePort, indexRepositoryPort, versionCatalogCache);
        when(indexRepositoryPort.findBySchemaName("race-idemp.stvn_inclf"))
            .thenReturn(Optional.empty()) // first check
            .thenReturn(Optional.of(new SchemaMetadata("race-idemp.stvn_inclf", "sig", actualHash))); // catch block check
        when(indexRepositoryPort.findByCasHash(actualHash)).thenReturn(Optional.empty());
        doThrow(new DuplicateIndexException("uq_version_catalog_schema_name"))
            .when(indexRepositoryPort).save(any(SchemaMetadata.class), eq(sourceText));

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.IdempotentCollision.class, result);
        PublishResult.IdempotentCollision collision = (PublishResult.IdempotentCollision) result;
        assertEquals(actualHash, collision.metadata().casHash());
    }

    @Test
    public void testPublishValidationErrorEmpty() {
        PublishRequest request = new PublishRequest("user.stvn_inclf", "");
        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertEquals(1, error.diagnostics().size());
        assertEquals("Source text cannot be empty", error.diagnostics().get(0).message());

        verifyNoInteractions(casStoragePort);
        verifyNoInteractions(indexRepositoryPort);
    }

    @Test
    public void testPublishValidationErrorSyntax() {
        PublishRequest request = new PublishRequest("user.stvn_inclf", "{ :defs { :UserId { #unsigned #size 64 } :Int");
        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertFalse(error.diagnostics().isEmpty());

        verifyNoInteractions(casStoragePort);
        verifyNoInteractions(indexRepositoryPort);
    }

    @Test
    public void testPublishIndexingDeferredOnDatabaseError() {
        String sourceText = "{\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("user-profile.stvn_inclf", sourceText);
        when(indexRepositoryPort.findBySchemaName("user-profile.stvn_inclf")).thenReturn(Optional.empty());

        doThrow(new RuntimeException("Database connection error"))
            .when(indexRepositoryPort).save(any(SchemaMetadata.class), anyString());

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.IndexingDeferred.class, result);
        PublishResult.IndexingDeferred deferred = (PublishResult.IndexingDeferred) result;
        assertNotNull(deferred.metadata().casHash());

        verify(casStoragePort).write(eq(deferred.metadata().casHash()), any(byte[].class));
    }

    @Test
    public void testPublishRejectsRawTabCharacter() {
        String sourceWithTab = "{\n\t:defs {\n\t\t:UserId { #unsigned #size 64 } :Int\n\t}\n}";
        PublishRequest request = new PublishRequest("user-tab.stvn_inclf", sourceWithTab);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().stream().anyMatch(d -> d.message().contains("ERR_TAB_CHARACTER_FORBIDDEN")),
            "Diagnostic must explicitly report ERR_TAB_CHARACTER_FORBIDDEN");
        verifyNoInteractions(casStoragePort);
        verifyNoInteractions(indexRepositoryPort);
    }

    @Test
    public void testPublishRejectsPayloadExceedingCapacityBound() {
        String largePayload = "a".repeat(StvnStringCapacityUtils.DEFAULT_UNBOUNDED_STRING_CAPACITY + 1);
        PublishRequest request = new PublishRequest("overflow.stvn_inclf", largePayload);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("ERR_CAPACITY_OVERFLOW"));
        verifyNoInteractions(casStoragePort);
    }

    @Test
    public void testPublishBinaryRejectsPayloadExceedingCapacityBound() {
        byte[] oversizedBytes = new byte[StvnStringCapacityUtils.DEFAULT_UNBOUNDED_STRING_CAPACITY + 1];
        PublishRequest request = new PublishRequest("overflow.stvn_bin", oversizedBytes);

        PublishResult result = engine.publishBinary(request, mock(org.stvnadore.core.binary.StvnBinaryDecoder.RootPointer.class));

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("ERR_CAPACITY_OVERFLOW"));
    }

    @Test
    public void testPublishBinaryRejectsNonEphemeralStrategy() {
        byte[] payload = new byte[10];
        payload[4] = 0x00; // Strategy 0x0
        PublishRequest request = new PublishRequest("schema.stvn_inclf", payload);
        var mockRoot = mock(StvnBinaryDecoder.RootPointer.class);
        var mockContext = mock(StvnBinaryDecoder.DecodeContext.class);
        when(mockRoot.context()).thenReturn(mockContext);
        when(mockContext.identityStrategy()).thenReturn(Optional.of(new SchemaIdentityStrategy.UniversalDefault()));

        PublishResult result = engine.publishBinary(request, mockRoot);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("ERR_UNSUPPORTED_STRATEGY"));
    }

    @Test
    public void testPublishBinaryRejectsNonInclfFilename() {
        String schemaText = "{\n  :defs {\n    :UserRecord :String\n  }\n}";
        byte[] payload = createValidEphemeral0x8Payload(schemaText);
        PublishRequest request = new PublishRequest("schema.stvn", payload);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("ERR_MALFORMED_SCHEMA_IN_ENVELOPE"));
        assertTrue(error.diagnostics().getFirst().message().contains(".stvn_inclf"));
    }

    @Test
    public void testPublishBinaryRejectsZeroDefsOffset() {
        String schemaText = "{\n  :defs {\n    :UserRecord :String\n  }\n}";
        byte[] payload = createValidEphemeral0x8Payload(schemaText);
        PublishRequest request = new PublishRequest("schema.stvn_inclf", payload);

        var mockRoot = mock(StvnBinaryDecoder.RootPointer.class);
        var mockContext = mock(StvnBinaryDecoder.DecodeContext.class);
        when(mockRoot.rootOffset()).thenReturn(0);
        when(mockRoot.context()).thenReturn(mockContext);
        when(mockContext.identityStrategy()).thenReturn(Optional.of(new SchemaIdentityStrategy.SelfDescribingSchema(schemaText)));
        ByteBuffer emptyBuf = ByteBuffer.allocate(0);
        when(mockContext.buffer()).thenReturn(emptyBuf);
        when(mockContext.offsetSize()).thenReturn(1);
        when(mockContext.payloadStart()).thenReturn(0);

        PublishResult result = engine.publishBinary(request, mockRoot);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("ERR_MALFORMED_SCHEMA_IN_ENVELOPE"));
        assertTrue(error.diagnostics().getFirst().message().contains(":defs"));
    }

    @Test
    public void testPublishBinaryRejectsNonZeroTypeOffset() {
        String schemaText = "{\n  :defs {\n    :UserRecord :String\n  }\n}";
        byte[] payload = createValidEphemeral0x8Payload(schemaText);
        PublishRequest request = new PublishRequest("schema.stvn_inclf", payload);

        var mockRoot = mock(StvnBinaryDecoder.RootPointer.class);
        var mockContext = mock(StvnBinaryDecoder.DecodeContext.class);
        when(mockRoot.rootOffset()).thenReturn(50);
        when(mockRoot.context()).thenReturn(mockContext);
        when(mockContext.identityStrategy()).thenReturn(Optional.of(new SchemaIdentityStrategy.SelfDescribingSchema(schemaText)));
        ByteBuffer buf = ByteBuffer.wrap(new byte[]{0, 0, 1, 0}); // typeOffset is non-zero
        when(mockContext.buffer()).thenReturn(buf);
        when(mockContext.offsetSize()).thenReturn(1);
        when(mockContext.payloadStart()).thenReturn(2);

        PublishResult result = engine.publishBinary(request, mockRoot);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("ERR_MALFORMED_SCHEMA_IN_ENVELOPE"));
        assertTrue(error.diagnostics().getFirst().message().contains("prohibited"));
    }

    @Test
    public void testPublishBinaryRejectsNonZeroBodyOffset() {
        String schemaText = "{\n  :defs {\n    :UserRecord :String\n  }\n}";
        byte[] payload = createValidEphemeral0x8Payload(schemaText);
        PublishRequest request = new PublishRequest("schema.stvn_inclf", payload);

        var mockRoot = mock(StvnBinaryDecoder.RootPointer.class);
        var mockContext = mock(StvnBinaryDecoder.DecodeContext.class);
        when(mockRoot.rootOffset()).thenReturn(50);
        when(mockRoot.context()).thenReturn(mockContext);
        when(mockContext.identityStrategy()).thenReturn(Optional.of(new SchemaIdentityStrategy.SelfDescribingSchema(schemaText)));
        ByteBuffer buf = ByteBuffer.wrap(new byte[]{0, 0, 0, 1}); // bodyOffset is non-zero
        when(mockContext.buffer()).thenReturn(buf);
        when(mockContext.offsetSize()).thenReturn(1);
        when(mockContext.payloadStart()).thenReturn(2);

        PublishResult result = engine.publishBinary(request, mockRoot);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("ERR_MALFORMED_SCHEMA_IN_ENVELOPE"));
        assertTrue(error.diagnostics().getFirst().message().contains("prohibited"));
    }

    @Test
    public void testPublishBinarySuccessMatchesTextualCasHash() {
        String schemaText = "{\n  :defs {\n    :UserRecord :String\n  }\n}";
        PublishRequest textReq = new PublishRequest("binary-biject.stvn_inclf", schemaText);
        when(indexRepositoryPort.findBySchemaName("binary-biject.stvn_inclf")).thenReturn(Optional.empty());

        PublishResult textResult = engine.publish(textReq);
        assertInstanceOf(PublishResult.Success.class, textResult);
        String expectedHash = ((PublishResult.Success) textResult).metadata().casHash();

        byte[] payload = createValidEphemeral0x8Payload(schemaText);
        PublishRequest binReq = new PublishRequest("binary-biject.stvn_inclf", payload);

        PublishResult binResult = engine.publish(binReq);
        assertInstanceOf(PublishResult.Success.class, binResult);
        String binHash = ((PublishResult.Success) binResult).metadata().casHash();

        assertEquals(expectedHash, binHash, "Binary publish CAS hash must equal text publish CAS hash (1:1 Law)");
    }

    private static byte[] createValidEphemeral0x8Payload(String schemaText) {
        byte[] textBytes = schemaText.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(256 + textBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(new byte[]{'S', 'T', 'V', 'N', (byte) 0x88});
        buf.putInt(textBytes.length - 1);
        buf.put(textBytes);
        buf.put((byte) 0x00);
        int payloadStart = buf.position();
        int arenaOffset = payloadStart + 3;
        buf.put((byte) arenaOffset);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 0x42);

        CRC32C crc = new CRC32C();
        crc.update(ByteBuffer.wrap(buf.array(), 0, buf.position()));
        buf.putInt((int) crc.getValue());

        byte[] result = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, result, 0, result.length);
        return result;
    }

    @Test
    public void testPublishTemporalSchemaSuccess() {
        String sourceText = """
            {
              :defs {
                :Timestamp { #ms #minIncl 1000 #maxExcl 2000 } :TimeEpoch
                :EventTime { #offset #minIncl "2026-01-01T00:00:00Z" #maxExcl "2027-01-01T00:00:00Z" } :DateTime
              }
            }
            """;
        PublishRequest request = new PublishRequest("temporal-schema.stvn_inclf", sourceText);
        when(indexRepositoryPort.findBySchemaName("temporal-schema.stvn_inclf")).thenReturn(Optional.empty());

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.Success.class, result);
        PublishResult.Success success = (PublishResult.Success) result;
        SchemaMetadata metadata = success.metadata();

        assertEquals("temporal-schema.stvn_inclf", metadata.schemaName());
        assertNotNull(metadata.casHash());
        assertTrue(metadata.shapeSignature().contains("{ #ms #minIncl 1000 #maxExcl 2000 } :TimeEpoch"));
        assertTrue(metadata.shapeSignature().contains("{ #offset #minIncl \"2026-01-01T00:00:00Z\" #maxExcl \"2027-01-01T00:00:00Z\" } :DateTime"));

        verify(casStoragePort).write(eq(metadata.casHash()), any(byte[].class));
        verify(indexRepositoryPort).save(eq(metadata), eq(sourceText));
        verify(versionCatalogCache).put(metadata);
    }

    @Test
    public void testPublishRejectsBareTimeEpoch() {
        String sourceText = """
            {
              :defs {
                :Timestamp :TimeEpoch
              }
            }
            """;
        PublishRequest request = new PublishRequest("bare-epoch.stvn_inclf", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().stream().anyMatch(d -> d.message().contains("ERR_MISSING_TEMPORAL_FACET")),
            "ValidationError must contain ERR_MISSING_TEMPORAL_FACET: " + error.diagnostics());
        verifyNoInteractions(casStoragePort);
        verifyNoInteractions(indexRepositoryPort);
    }

    @Test
    public void testPublishRejectsBareDateTime() {
        String sourceText = """
            {
              :defs {
                :Timestamp :DateTime
              }
            }
            """;
        PublishRequest request = new PublishRequest("bare-datetime.stvn_inclf", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().stream().anyMatch(d -> d.message().contains("ERR_MISSING_TEMPORAL_FACET")),
            "ValidationError must contain ERR_MISSING_TEMPORAL_FACET: " + error.diagnostics());
        verifyNoInteractions(casStoragePort);
        verifyNoInteractions(indexRepositoryPort);
    }

    @Test
    public void testPublishRejectsClosedBoundsOnTimeEpoch() {
        String sourceText = """
            {
              :defs {
                :Timestamp { #s #maxIncl 1000 } :TimeEpoch
              }
            }
            """;
        PublishRequest request = new PublishRequest("closed-epoch.stvn_inclf", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().stream().anyMatch(d -> d.message().contains("ERR_DISCRETE_BOUND_KIND_PROHIBITED")),
            "ValidationError must contain ERR_DISCRETE_BOUND_KIND_PROHIBITED: " + error.diagnostics());
        verifyNoInteractions(casStoragePort);
        verifyNoInteractions(indexRepositoryPort);
    }

    @Test
    public void testPublishRejectsClosedBoundsOnDateTime() {
        String sourceText = """
            {
              :defs {
                :Timestamp { #zoned #minExcl "2026-01-01T00:00:00Z[UTC]" } :DateTime
              }
            }
            """;
        PublishRequest request = new PublishRequest("closed-datetime.stvn_inclf", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().stream().anyMatch(d -> d.message().contains("ERR_DISCRETE_BOUND_KIND_PROHIBITED")),
            "ValidationError must contain ERR_DISCRETE_BOUND_KIND_PROHIBITED: " + error.diagnostics());
        verifyNoInteractions(casStoragePort);
        verifyNoInteractions(indexRepositoryPort);
    }
}

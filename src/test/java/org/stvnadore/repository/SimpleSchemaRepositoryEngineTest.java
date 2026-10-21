package org.stvnadore.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.binary.SchemaIdentityStrategy;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.StvnVocabulary;
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
        String sourceText = "{\n  :meta {\n    #name \"user_profile\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n    :UserName { #minSize 1 } :String\n  }\n}";
        PublishRequest request = new PublishRequest("user_profile.stvn_d", sourceText);
        when(indexRepositoryPort.findBySchemaName("user_profile.stvn_d")).thenReturn(Optional.empty());

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.Success.class, result);
        PublishResult.Success success = (PublishResult.Success) result;
        SchemaMetadata metadata = success.metadata();

        assertEquals("user_profile.stvn_d", metadata.schemaName());
        assertEquals("test", metadata.domain());
        assertNotNull(metadata.casHash());
        assertTrue(metadata.shapeSignature().contains(":defs"));

        verify(casStoragePort).write(eq(metadata.casHash()), any(byte[].class));
        verify(indexRepositoryPort).save(eq(metadata), eq(sourceText));
        verify(versionCatalogCache).put(metadata);
    }

    @Test
    public void testGate1RejectsLegacyStvnInclf() {
        String sourceText = "{\n  :meta {\n    #name \"user_profile\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("user_profile.stvn_inclf", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("ERR_LEGACY_FILE_EXTENSION_PURGED"));
        verifyNoInteractions(casStoragePort);
        verifyNoInteractions(indexRepositoryPort);
    }

    @Test
    public void testGate1RejectsModularStvnDi() {
        String sourceText = "{\n  :meta {\n    #name \"user_profile\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("user_profile.stvn_di", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT"));
        verifyNoInteractions(casStoragePort);
        verifyNoInteractions(indexRepositoryPort);
    }

    @Test
    public void testGate1RejectsNonStvnDFilename() {
        String sourceText = "{\n  :meta {\n    #name \"user_profile\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("user_profile.stvn", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("ERR_MALFORMED_SCHEMA_IN_ENVELOPE"));
        assertTrue(error.diagnostics().getFirst().message().contains(".stvn_d"));
        verifyNoInteractions(casStoragePort);
        verifyNoInteractions(indexRepositoryPort);
    }

    @Test
    public void testGate2RejectsMissingMeta() {
        String sourceText = "{\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("user_profile.stvn_d", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("ERR_SCHEMA_IDENTITY_MISMATCH"));
        assertTrue(error.diagnostics().getFirst().message().contains("Mandatory :meta block missing"));
    }

    @Test
    public void testGate2RejectsMissingNameOrDomain() {
        String sourceText = "{\n  :meta {\n    #name \"user_profile\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("user_profile.stvn_d", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("ERR_SCHEMA_IDENTITY_MISMATCH"));
        assertTrue(error.diagnostics().getFirst().message().contains("anonymous wildcards are prohibited"));
    }

    @Test
    public void testGate2RejectsNameMismatchAgainstStem() {
        String sourceText = "{\n  :meta {\n    #name \"different_name\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("user_profile.stvn_d", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("ERR_SCHEMA_IDENTITY_MISMATCH"));
        assertTrue(error.diagnostics().getFirst().message().contains("does not match envelope schema stem slot 0"));
    }

    @Test
    public void testGate2StemSlot1DomainMismatch() {
        String sourceText = "{\n  :meta {\n    #name \"user_profile\"\n    #domain \"wrong_domain\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("user_profile.crm.stvn_d", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("ERR_SCHEMA_IDENTITY_MISMATCH"));
        assertTrue(error.diagnostics().getFirst().message().contains("does not match envelope schema stem slot 1"));
    }

    @Test
    public void testGate2StemSlot1DomainMatchSuccess() {
        String sourceText = "{\n  :meta {\n    #name \"user_profile\"\n    #domain \"crm\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("user_profile.crm.stvn_d", sourceText);
        when(indexRepositoryPort.findBySchemaName("user_profile.crm.stvn_d")).thenReturn(Optional.empty());

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.Success.class, result);
        PublishResult.Success success = (PublishResult.Success) result;
        assertEquals("crm", success.metadata().domain());
    }

    @Test
    public void testGate2RejectsNonPosixIdentifier() {
        String sourceText = "{\n  :meta {\n    #name \"bad name!\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("bad_name.stvn_d", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().stream().anyMatch(d -> d.message().contains("ERR_INVALID_METADATA_FACET") || d.message().contains("ERR_SCHEMA_IDENTITY_MISMATCH")));
    }

    @Test
    public void testGate2RejectsKindMismatch() {
        String sourceText = "{\n  :meta {\n    #name \"user_profile\"\n    #domain \"test\"\n    #kind #BODY\n  }\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("user_profile.stvn_d", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("ERR_DOCUMENT_KIND_MISMATCH"));
    }

    @Test
    public void testPublishRejectsSchemaContainingIncludes() {
        String sourceText = "{\n  :meta {\n    #name \"invalid_include\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :include [ \"other.stvn_d\" ]\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("invalid_include.stvn_d", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().stream().anyMatch(d ->
            d.message().contains("ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT") || d.message().contains("cannot contain include statements")));
        verifyNoInteractions(casStoragePort);
    }

    @Test
    public void testPublishRejectsSchemaContainingBodyOrType() {
        String sourceText = "{\n  :meta {\n    #name \"invalid_body\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n  :type :UserId\n  :body 100\n}";
        PublishRequest request = new PublishRequest("invalid_body.stvn_d", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("prohibited in flat schema document"));
        verifyNoInteractions(casStoragePort);
    }

    @Test
    public void testPublishPackageEnclosureAndFqniExpansion() {
        String sourceText = "{\n  :meta {\n    #name \"packaged\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :package :org/example {\n      :UserId { #unsigned #size 64 } :Int\n    }\n  }\n}";
        PublishRequest request = new PublishRequest("packaged.stvn_d", sourceText);
        when(indexRepositoryPort.findBySchemaName("packaged.stvn_d")).thenReturn(Optional.empty());

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.Success.class, result);
        PublishResult.Success success = (PublishResult.Success) result;
        assertTrue(success.metadata().shapeSignature().contains(":org/example/UserId"));
        verify(casStoragePort).write(eq(success.metadata().casHash()), any(byte[].class));
        verify(indexRepositoryPort).save(eq(success.metadata()), eq(sourceText));
    }

    @Test
    public void testPublishMutationConflictReturnsSchemaConflict() {
        String sourceText = "{\n  :meta {\n    #name \"user\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("user.stvn_d", sourceText);
        SchemaMetadata existing = new SchemaMetadata("user.stvn_d", "existingSig", "differentHash11111111111111111111111111111111111111111111111111111111", "test");
        when(indexRepositoryPort.findBySchemaName("user.stvn_d")).thenReturn(Optional.of(existing));

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.SchemaConflict.class, result);
        PublishResult.SchemaConflict conflict = (PublishResult.SchemaConflict) result;
        assertEquals("user.stvn_d", conflict.schemaName());
        assertEquals("differentHash11111111111111111111111111111111111111111111111111111111", conflict.existingHash());
        assertNotEquals(conflict.existingHash(), conflict.submittedHash());

        verifyNoInteractions(casStoragePort);
        verify(indexRepositoryPort, never()).save(any(SchemaMetadata.class), anyString());
    }

    @Test
    public void testPublishIdempotentCollision() {
        String sourceText = "{\n  :meta {\n    #name \"user\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("user.stvn_d", sourceText);

        // First compile to get actual hash
        PublishResult first = engine.publish(request);
        assertInstanceOf(PublishResult.Success.class, first);
        String actualHash = ((PublishResult.Success) first).metadata().casHash();

        // Now mock findBySchemaName returning existing with same hash
        SchemaMetadata existing = new SchemaMetadata("user.stvn_d", "sig", actualHash, "test");
        when(indexRepositoryPort.findBySchemaName("user.stvn_d")).thenReturn(Optional.of(existing));

        PublishResult second = engine.publish(request);
        assertInstanceOf(PublishResult.IdempotentCollision.class, second);
        PublishResult.IdempotentCollision collision = (PublishResult.IdempotentCollision) second;
        assertEquals(actualHash, collision.metadata().casHash());
    }

    @Test
    public void testPublishAliasConflictReturnsAliasConflict() {
        String sourceText = "{\n  :meta {\n    #name \"alias_user\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("alias_user.stvn_d", sourceText);

        // Pre-determine hash by running publish once against pristine mock
        PublishResult probe = engine.publish(request);
        assertInstanceOf(PublishResult.Success.class, probe);
        String actualHash = ((PublishResult.Success) probe).metadata().casHash();

        // Reset mocks to test alias conflict pre-check branch
        reset(casStoragePort, indexRepositoryPort, versionCatalogCache);
        when(indexRepositoryPort.findBySchemaName("alias_user.stvn_d")).thenReturn(Optional.empty());
        when(indexRepositoryPort.findByCasHash(actualHash)).thenReturn(
            Optional.of(new SchemaMetadata("canonical_user.stvn_d", "sig", actualHash, "test"))
        );

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.AliasConflict.class, result);
        PublishResult.AliasConflict aliasConflict = (PublishResult.AliasConflict) result;
        assertEquals("alias_user.stvn_d", aliasConflict.submittedSchemaName());
        assertEquals("canonical_user.stvn_d", aliasConflict.existingSchemaName());
        assertEquals(actualHash, aliasConflict.casHash());

        verifyNoInteractions(casStoragePort);
        verify(indexRepositoryPort, never()).save(any(SchemaMetadata.class), anyString());
    }

    @Test
    public void testPublishConcurrentRaceAliasConflictFallback() {
        String sourceText = "{\n  :meta {\n    #name \"race_alias\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("race_alias.stvn_d", sourceText);
        PublishResult probe = engine.publish(request);
        assertInstanceOf(PublishResult.Success.class, probe);
        String actualHash = ((PublishResult.Success) probe).metadata().casHash();

        reset(casStoragePort, indexRepositoryPort, versionCatalogCache);
        when(indexRepositoryPort.findBySchemaName("race_alias.stvn_d")).thenReturn(Optional.empty());
        when(indexRepositoryPort.findByCasHash(actualHash))
            .thenReturn(Optional.empty()) // first check before save
            .thenReturn(Optional.of(new SchemaMetadata("winner.stvn_d", "sig", actualHash, "test"))); // catch block check
        doThrow(new DuplicateIndexException("uq_version_catalog_cas_hash"))
            .when(indexRepositoryPort).save(any(SchemaMetadata.class), eq(sourceText));

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.AliasConflict.class, result);
        PublishResult.AliasConflict conflict = (PublishResult.AliasConflict) result;
        assertEquals("race_alias.stvn_d", conflict.submittedSchemaName());
        assertEquals("winner.stvn_d", conflict.existingSchemaName());
    }

    @Test
    public void testPublishConcurrentRaceSchemaConflictFallback() {
        String sourceText = "{\n  :meta {\n    #name \"race_schema\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("race_schema.stvn_d", sourceText);

        PublishResult probe = engine.publish(new PublishRequest("race_schema.stvn_d", sourceText));
        assertInstanceOf(PublishResult.Success.class, probe);
        String actualHash = ((PublishResult.Success) probe).metadata().casHash();

        reset(casStoragePort, indexRepositoryPort, versionCatalogCache);
        when(indexRepositoryPort.findBySchemaName("race_schema.stvn_d"))
            .thenReturn(Optional.empty()) // first check
            .thenReturn(Optional.of(new SchemaMetadata("race_schema.stvn_d", "sig", "concurrentOtherHash111111111111111111111111111111111111111111111111", "test"))); // catch block check
        when(indexRepositoryPort.findByCasHash(actualHash)).thenReturn(Optional.empty());
        doThrow(new DuplicateIndexException("uq_version_catalog_schema_name"))
            .when(indexRepositoryPort).save(any(SchemaMetadata.class), eq(sourceText));

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.SchemaConflict.class, result);
        PublishResult.SchemaConflict conflict = (PublishResult.SchemaConflict) result;
        assertEquals("race_schema.stvn_d", conflict.schemaName());
        assertEquals("concurrentOtherHash111111111111111111111111111111111111111111111111", conflict.existingHash());
    }

    @Test
    public void testPublishConcurrentRaceIdempotentCollisionFallback() {
        String sourceText = "{\n  :meta {\n    #name \"race_idemp\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("race_idemp.stvn_d", sourceText);

        PublishResult probe = engine.publish(new PublishRequest("race_idemp.stvn_d", sourceText));
        assertInstanceOf(PublishResult.Success.class, probe);
        String actualHash = ((PublishResult.Success) probe).metadata().casHash();

        reset(casStoragePort, indexRepositoryPort, versionCatalogCache);
        when(indexRepositoryPort.findBySchemaName("race_idemp.stvn_d"))
            .thenReturn(Optional.empty()) // first check
            .thenReturn(Optional.of(new SchemaMetadata("race_idemp.stvn_d", "sig", actualHash, "test"))); // catch block check
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
        PublishRequest request = new PublishRequest("user.stvn_d", "");
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
        PublishRequest request = new PublishRequest("user.stvn_d", "{ :defs { :UserId { #unsigned #size 64 } :Int");
        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertFalse(error.diagnostics().isEmpty());

        verifyNoInteractions(casStoragePort);
        verifyNoInteractions(indexRepositoryPort);
    }

    @Test
    public void testPublishIndexingDeferredOnDatabaseError() {
        String sourceText = "{\n  :meta {\n    #name \"user_profile\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        PublishRequest request = new PublishRequest("user_profile.stvn_d", sourceText);
        when(indexRepositoryPort.findBySchemaName("user_profile.stvn_d")).thenReturn(Optional.empty());

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
        String sourceWithTab = "{\n\t:meta {\n\t\t#name \"user_tab\"\n\t\t#domain \"test\"\n\t\t#kind #DEFS\n\t}\n\t:defs {\n\t\t:UserId { #unsigned #size 64 } :Int\n\t}\n}";
        PublishRequest request = new PublishRequest("user_tab.stvn_d", sourceWithTab);

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
        String largePayload = "a".repeat(StvnVocabulary.DEFAULT_UNBOUNDED_STRING_CAPACITY + 1);
        PublishRequest request = new PublishRequest("overflow.stvn_d", largePayload);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("ERR_CAPACITY_OVERFLOW"));
        verifyNoInteractions(casStoragePort);
    }

    @Test
    public void testPublishBinaryRejectsPayloadExceedingCapacityBound() {
        byte[] oversizedBytes = new byte[StvnVocabulary.DEFAULT_UNBOUNDED_STRING_CAPACITY + 1];
        PublishRequest request = new PublishRequest("overflow.stvn_bd", oversizedBytes);

        PublishResult result = engine.publishBinary(request, mock(org.stvnadore.core.binary.StvnBinaryDecoder.RootPointer.class));

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("ERR_CAPACITY_OVERFLOW"));
    }

    @Test
    public void testPublishBinaryRejectsNonEphemeralStrategy() {
        byte[] payload = new byte[10];
        payload[4] = 0x00; // Strategy 0x0
        PublishRequest request = new PublishRequest("schema.stvn_bd", payload);
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
        String schemaText = "{\n  :meta {\n    #name \"schema\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserRecord :String\n  }\n}";
        byte[] payload = createValidEphemeral0x8Payload("schema", "test", schemaText);
        PublishRequest request = new PublishRequest("schema.stvn", payload);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("ERR_MALFORMED_SCHEMA_IN_ENVELOPE"));
        assertTrue(error.diagnostics().getFirst().message().contains(".stvn_bd"));
    }

    @Test
    public void testPublishBinaryRejectsZeroDefsOffset() {
        String schemaText = "{\n  :meta {\n    #name \"schema\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserRecord :String\n  }\n}";
        byte[] payload = createValidEphemeral0x8Payload("schema", "test", schemaText);
        PublishRequest request = new PublishRequest("schema.stvn_bd", payload);

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
        String schemaText = "{\n  :meta {\n    #name \"schema\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserRecord :String\n  }\n}";
        byte[] payload = createValidEphemeral0x8Payload("schema", "test", schemaText);
        PublishRequest request = new PublishRequest("schema.stvn_bd", payload);

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
        String schemaText = "{\n  :meta {\n    #name \"schema\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserRecord :String\n  }\n}";
        byte[] payload = createValidEphemeral0x8Payload("schema", "test", schemaText);
        PublishRequest request = new PublishRequest("schema.stvn_bd", payload);

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
        String schemaText = "{\n  :meta {\n    #name \"binary_biject\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserRecord :String\n  }\n}";
        PublishRequest textReq = new PublishRequest("binary_biject.stvn_d", schemaText);
        when(indexRepositoryPort.findBySchemaName("binary_biject.stvn_d")).thenReturn(Optional.empty());

        PublishResult textResult = engine.publish(textReq);
        assertInstanceOf(PublishResult.Success.class, textResult);
        String expectedHash = ((PublishResult.Success) textResult).metadata().casHash();

        byte[] payload = createValidEphemeral0x8Payload("binary_biject", "test", schemaText);
        PublishRequest binReq = new PublishRequest("binary_biject.stvn_bd", payload);

        PublishResult binResult = engine.publish(binReq);
        assertInstanceOf(PublishResult.Success.class, binResult);
        String binHash = ((PublishResult.Success) binResult).metadata().casHash();

        assertEquals(expectedHash, binHash, "Binary publish CAS hash must equal text publish CAS hash (1:1 Law)");
    }

    private static byte[] createValidEphemeral0x8Payload(String name, String domain, String schemaText) {
        byte[] textBytes = schemaText.getBytes(StandardCharsets.UTF_8);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] domainBytes = domain.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(512 + textBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(new byte[]{'S', 'T', 'V', 'N', (byte) 0x88});
        // Byte 5: MetaControlByte (0x0D = KIND=#DEFS, HAS_NAME, HAS_DOMAIN)
        buf.put((byte) 0x0D);
        buf.put((byte) nameBytes.length);
        buf.put(nameBytes);
        buf.put((byte) domainBytes.length);
        buf.put(domainBytes);
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
              :meta {
                #name "temporal_schema"
                #domain "test"
                #kind #DEFS
              }
              :defs {
                :Timestamp { #ms #minIncl 1000 #maxExcl 2000 } :TimeEpoch
                :EventTime { #offset #minIncl "2026-01-01T00:00:00Z" #maxExcl "2027-01-01T00:00:00Z" } :DateTime
              }
            }
            """;
        PublishRequest request = new PublishRequest("temporal_schema.stvn_d", sourceText);
        when(indexRepositoryPort.findBySchemaName("temporal_schema.stvn_d")).thenReturn(Optional.empty());

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.Success.class, result);
        PublishResult.Success success = (PublishResult.Success) result;
        SchemaMetadata metadata = success.metadata();

        assertEquals("temporal_schema.stvn_d", metadata.schemaName());
        assertEquals("test", metadata.domain());
        assertNotNull(metadata.casHash());
        assertTrue(metadata.shapeSignature().contains("{#ms #minIncl 1000 #maxExcl 2000}:TimeEpoch"));
        assertTrue(metadata.shapeSignature().contains("{#offset #minIncl \"2026-01-01T00:00:00Z\" #maxExcl \"2027-01-01T00:00:00Z\"}:DateTime"));

        verify(casStoragePort).write(eq(metadata.casHash()), any(byte[].class));
        verify(indexRepositoryPort).save(eq(metadata), eq(sourceText));
        verify(versionCatalogCache).put(metadata);
    }

    @Test
    public void testPublishRejectsBareTimeEpoch() {
        String sourceText = """
            {
              :meta {
                #name "bare_epoch"
                #domain "test"
                #kind #DEFS
              }
              :defs {
                :Timestamp :TimeEpoch
              }
            }
            """;
        PublishRequest request = new PublishRequest("bare_epoch.stvn_d", sourceText);

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
              :meta {
                #name "bare_datetime"
                #domain "test"
                #kind #DEFS
              }
              :defs {
                :Timestamp :DateTime
              }
            }
            """;
        PublishRequest request = new PublishRequest("bare_datetime.stvn_d", sourceText);

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
              :meta {
                #name "closed_epoch"
                #domain "test"
                #kind #DEFS
              }
              :defs {
                :Timestamp { #s #maxIncl 1000 } :TimeEpoch
              }
            }
            """;
        PublishRequest request = new PublishRequest("closed_epoch.stvn_d", sourceText);

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
              :meta {
                #name "closed_datetime"
                #domain "test"
                #kind #DEFS
              }
              :defs {
                :Timestamp { #zoned #minExcl "2026-01-01T00:00:00Z[UTC]" } :DateTime
              }
            }
            """;
        PublishRequest request = new PublishRequest("closed_datetime.stvn_d", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().stream().anyMatch(d -> d.message().contains("ERR_DISCRETE_BOUND_KIND_PROHIBITED")),
            "ValidationError must contain ERR_DISCRETE_BOUND_KIND_PROHIBITED: " + error.diagnostics());
        verifyNoInteractions(casStoragePort);
        verifyNoInteractions(indexRepositoryPort);
    }

    @Test
    public void testGate1RejectsExcisedLegacyExtensionsStvnInclAndCas() {
        String[] legacyExtensions = new String[] {
            "schema.stvn_incl", "schema.stvn_inclf", "schema.stvn_cas",
            "schema.stvn_bin", "schema.stvn_f", "schema.stvn_df",
            "schema.stvn_bf", "schema.stvn_bdf"
        };
        String sourceText = "{\n  :meta {\n    #name \"schema\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserId { #unsigned #size 16 } :Int\n  }\n}";

        for (String legacyName : legacyExtensions) {
            PublishRequest request = new PublishRequest(legacyName, sourceText);
            PublishResult result = engine.publish(request);

            assertInstanceOf(PublishResult.ValidationError.class, result, "Must reject legacy extension: " + legacyName);
            PublishResult.ValidationError error = (PublishResult.ValidationError) result;
            assertTrue(error.diagnostics().getFirst().message().contains("ERR_LEGACY_FILE_EXTENSION_PURGED"),
                "Diagnostic must contain ERR_LEGACY_FILE_EXTENSION_PURGED for: " + legacyName);
        }
        verifyNoInteractions(casStoragePort);
        verifyNoInteractions(indexRepositoryPort);
    }

    @Test
    public void testGate2RejectsReservedSystemDomainsInMeta() {
        String[] reservedDomains = new String[] { "stvn_core", "stvn_ir", "stvn_cas" };

        for (String domain : reservedDomains) {
            String sourceText = "{\n  :meta {\n    #name \"reserved_test\"\n    #domain \"" + domain + "\"\n    #kind #DEFS\n  }\n  :defs {\n    :Val { #size 16 } :Int\n  }\n}";
            PublishRequest request = new PublishRequest("reserved_test." + domain + ".stvn_d", sourceText);
            PublishResult result = engine.publish(request);

            assertInstanceOf(PublishResult.ValidationError.class, result, "Must reject reserved system domain: " + domain);
            PublishResult.ValidationError error = (PublishResult.ValidationError) result;
            assertTrue(error.diagnostics().stream().anyMatch(d -> d.message().contains("ERR_RESERVED_DOMAIN_PROHIBITED")
                    || d.message().contains("Reserved system domain prohibited")),
                "ValidationError must report reserved system domain violation for domain: " + domain);
        }
        verifyNoInteractions(casStoragePort);
        verifyNoInteractions(indexRepositoryPort);
    }
}
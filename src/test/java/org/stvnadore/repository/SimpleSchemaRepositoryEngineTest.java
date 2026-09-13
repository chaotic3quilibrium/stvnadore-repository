package org.stvnadore.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.stvnadore.repository.domain.*;
import org.stvnadore.repository.ports.CasStoragePort;
import org.stvnadore.repository.ports.IndexRepositoryPort;
import org.stvnadore.repository.ports.VersionCatalogCache;

import java.util.Optional;

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
        String sourceText = "{\n  :defs {\n    :UserId :Uint64\n    :UserName :StringNonEmpty\n  }\n}";
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
        String sourceText = "{\n  :defs {\n    :UserId :Uint64\n  }\n}";
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
        String sourceText = "{\n  :defs {\n    :include [ \"other.stvn_inclf\" ]\n    :UserId :Uint64\n  }\n}";
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
        String sourceText = "{\n  :defs {\n    :UserId :Uint64\n  }\n  :type :UserId\n  :body 100\n}";
        PublishRequest request = new PublishRequest("invalid-body.stvn_inclf", sourceText);

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertTrue(error.diagnostics().getFirst().message().contains("prohibited in flat schema document"));
        verifyNoInteractions(casStoragePort);
    }

    @Test
    public void testPublishPackageEnclosureAndFqniExpansion() {
        String sourceText = "{\n  :defs {\n    :package :org/example {\n      :UserId :Uint64\n    }\n  }\n}";
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
        String sourceText = "{\n  :defs {\n    :UserId :Uint64\n  }\n}";
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
        String sourceText = "{\n  :defs {\n    :UserId :Uint64\n  }\n}";
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
        PublishRequest request = new PublishRequest("user.stvn_inclf", "{ :defs { :UserId :Uint64");
        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertFalse(error.diagnostics().isEmpty());

        verifyNoInteractions(casStoragePort);
        verifyNoInteractions(indexRepositoryPort);
    }

    @Test
    public void testPublishIndexingDeferredOnDatabaseError() {
        String sourceText = "{\n  :defs {\n    :UserId :Uint64\n  }\n}";
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
}

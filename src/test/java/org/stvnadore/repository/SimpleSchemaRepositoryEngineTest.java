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
        String sourceText = "{ :type :String :body \"hello\" }";
        PublishRequest request = new PublishRequest("test-schema", sourceText);
        when(indexRepositoryPort.findBySchemaName("test-schema")).thenReturn(Optional.empty());

        PublishResult result = engine.publish(request);
        
        assertInstanceOf(PublishResult.Success.class, result);
        PublishResult.Success success = (PublishResult.Success) result;
        SchemaMetadata metadata = success.metadata();

        assertEquals("test-schema", metadata.schemaName());
        assertNotNull(metadata.casHash());
        assertTrue(metadata.shapeSignature().contains(":defs"));

        verify(casStoragePort).write(eq(metadata.casHash()), any(byte[].class));
        verify(indexRepositoryPort).save(eq(metadata), eq(sourceText));
        verify(versionCatalogCache).put(metadata);
    }

    @Test
    public void testPublishMutationConflictReturnsSchemaConflict() {
        String sourceText = "{ :type :String :body \"hello\" }";
        PublishRequest request = new PublishRequest("test-schema", sourceText);
        SchemaMetadata existing = new SchemaMetadata("test-schema", "existingSig", "differentHash11111111111111111111111111111111111111111111111111111111");
        when(indexRepositoryPort.findBySchemaName("test-schema")).thenReturn(Optional.of(existing));

        PublishResult result = engine.publish(request);

        assertInstanceOf(PublishResult.SchemaConflict.class, result);
        PublishResult.SchemaConflict conflict = (PublishResult.SchemaConflict) result;
        assertEquals("test-schema", conflict.schemaName());
        assertEquals("differentHash11111111111111111111111111111111111111111111111111111111", conflict.existingHash());
        assertNotEquals(conflict.existingHash(), conflict.submittedHash());

        verifyNoInteractions(casStoragePort);
        verify(indexRepositoryPort, never()).save(any(SchemaMetadata.class), anyString());
    }

    @Test
    public void testPublishIdempotentCollision() {
        String sourceText = "{ :type :String :body \"hello\" }";
        PublishRequest request = new PublishRequest("test-schema", sourceText);

        // First compile to get actual hash
        PublishResult first = engine.publish(request);
        assertInstanceOf(PublishResult.Success.class, first);
        String actualHash = ((PublishResult.Success) first).metadata().casHash();

        // Now mock findBySchemaName returning existing with same hash
        SchemaMetadata existing = new SchemaMetadata("test-schema", "sig", actualHash);
        when(indexRepositoryPort.findBySchemaName("test-schema")).thenReturn(Optional.of(existing));

        PublishResult second = engine.publish(request);
        assertInstanceOf(PublishResult.IdempotentCollision.class, second);
        PublishResult.IdempotentCollision collision = (PublishResult.IdempotentCollision) second;
        assertEquals(actualHash, collision.metadata().casHash());
    }

    @Test
    public void testPublishValidationErrorEmpty() {
        PublishRequest request = new PublishRequest("test-schema", "");
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
        PublishRequest request = new PublishRequest("test-schema", "{ :type :String :body \"hello\"");
        PublishResult result = engine.publish(request);
        
        assertInstanceOf(PublishResult.ValidationError.class, result);
        PublishResult.ValidationError error = (PublishResult.ValidationError) result;
        assertFalse(error.diagnostics().isEmpty());

        verifyNoInteractions(casStoragePort);
        verifyNoInteractions(indexRepositoryPort);
    }

    @Test
    public void testPublishIndexingDeferredOnDatabaseError() {
        String sourceText = "{ :type :String :body \"hello\" }";
        PublishRequest request = new PublishRequest("test-schema", sourceText);
        when(indexRepositoryPort.findBySchemaName("test-schema")).thenReturn(Optional.empty());
        
        doThrow(new RuntimeException("Database connection error"))
            .when(indexRepositoryPort).save(any(SchemaMetadata.class), anyString());

        PublishResult result = engine.publish(request);
        
        assertInstanceOf(PublishResult.IndexingDeferred.class, result);
        PublishResult.IndexingDeferred deferred = (PublishResult.IndexingDeferred) result;
        assertNotNull(deferred.metadata().casHash());

        verify(casStoragePort).write(eq(deferred.metadata().casHash()), any(byte[].class));
    }
}

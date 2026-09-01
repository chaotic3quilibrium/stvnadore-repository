package org.stvnadore.repository.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.stvnadore.repository.domain.SchemaMetadata;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class ConcurrentHashMapCacheTest {

    private ConcurrentHashMapCache cache;

    @BeforeEach
    public void setUp() {
        cache = new ConcurrentHashMapCache();
    }

    @Test
    public void testGetMissReturnsEmpty() {
        Optional<SchemaMetadata> result = cache.get("non-existent", "sig123");
        assertTrue(result.isEmpty());
    }

    @Test
    public void testPutAndGetHit() {
        SchemaMetadata metadata = new SchemaMetadata("test-schema", "shape-sig-abc", "hash-xyz");
        cache.put(metadata);

        Optional<SchemaMetadata> result = cache.get("test-schema", "shape-sig-abc");
        assertTrue(result.isPresent());
        assertEquals(metadata, result.get());
    }

    @Test
    public void testGetMissWithDifferentSignature() {
        SchemaMetadata metadata = new SchemaMetadata("test-schema", "shape-sig-abc", "hash-xyz");
        cache.put(metadata);

        Optional<SchemaMetadata> result = cache.get("test-schema", "different-sig");
        assertTrue(result.isEmpty());
    }

    @Test
    public void testPutNullDoesNotThrow() {
        assertDoesNotThrow(() -> cache.put(null));
    }
}

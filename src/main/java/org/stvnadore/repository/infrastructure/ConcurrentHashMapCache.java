package org.stvnadore.repository.infrastructure;

import org.stvnadore.repository.domain.SchemaMetadata;
import org.stvnadore.repository.ports.VersionCatalogCache;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure JDK implementation of VersionCatalogCache using ConcurrentHashMap.
 * Ensures thread-safe, lock-free lookups suitable for Project Loom virtual threads.
 */
public class ConcurrentHashMapCache implements VersionCatalogCache {
    private final ConcurrentHashMap<String, SchemaMetadata> cache = new ConcurrentHashMap<>();

    /**
     * Constructs a new empty ConcurrentHashMapCache.
     */
    public ConcurrentHashMapCache() {}

    @Override
    public Optional<SchemaMetadata> get(String schemaName, String shapeSignature) {
        String key = buildKey(schemaName, shapeSignature);
        SchemaMetadata metadata = cache.get(key);
        return Optional.ofNullable(metadata);
    }

    @Override
    public void put(SchemaMetadata metadata) {
        if (metadata != null) {
            String key = buildKey(metadata.schemaName(), metadata.shapeSignature());
            cache.put(key, metadata);
        }
    }

    private String buildKey(String schemaName, String shapeSignature) {
        return schemaName + ":" + shapeSignature;
    }
}
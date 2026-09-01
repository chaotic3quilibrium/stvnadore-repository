package org.stvnadore.repository.ports;

import org.stvnadore.repository.domain.SchemaMetadata;
import java.util.Optional;

/**
 * Port interface for caching schema version metadata to reduce
 * index repository query overhead.
 */
public interface VersionCatalogCache {
    /**
     * Retrieves cached metadata by schema name and shape signature.
     *
     * @param schemaName     the schema name
     * @param shapeSignature the schema shape signature
     * @return Optional containing the cached SchemaMetadata if hit, or empty on miss
     */
    Optional<SchemaMetadata> get(String schemaName, String shapeSignature);

    /**
     * Caches the given schema metadata record.
     *
     * @param metadata the schema metadata record to cache
     */
    void put(SchemaMetadata metadata);
}

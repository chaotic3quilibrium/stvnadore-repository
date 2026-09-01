package org.stvnadore.repository.domain;

/**
 * Core engine service interface for publishing and querying schema definitions.
 */
public interface SchemaRepositoryEngine {
    /**
     * Validates, packages, stores, and indexes a schema publication request.
     *
     * @param request the schema publication command
     * @return the publication outcome
     */
    PublishResult publish(PublishRequest request);

    /**
     * Looks up schema metadata matching a nominal name and structural shape signature.
     *
     * @param schemaName the schema name
     * @param shapeSignature the flattened shape signature
     * @return Optional containing SchemaMetadata if found, empty otherwise
     */
    java.util.Optional<SchemaMetadata> getSchemaMetadata(String schemaName, String shapeSignature);
}
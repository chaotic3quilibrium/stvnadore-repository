package org.stvnadore.repository.ports;

import org.stvnadore.repository.domain.DuplicateIndexException;
import org.stvnadore.repository.domain.SchemaMetadata;

import java.util.Optional;

/**
 * Port interface for indexing schema metadata in the relational catalog.
 */
public interface IndexRepositoryPort {
    /**
     * Inserts a new schema metadata record and records the author source audit.
     *
     * @param metadata   the compiled schema metadata
     * @param sourceText original author-submitted source text
     * @throws DuplicateIndexException if a schema_name or cas_hash unique constraint is violated
     */
    void save(SchemaMetadata metadata, String sourceText) throws DuplicateIndexException;

    /**
     * Inserts a schema metadata record with empty source text.
     *
     * @param metadata the compiled schema metadata
     * @throws DuplicateIndexException if unique index constraints are violated
     */
    default void save(SchemaMetadata metadata) throws DuplicateIndexException {
        save(metadata, "");
    }

    /**
     * Checks if a schema record exists with the given 64-character CAS hash.
     *
     * @param casHash 64-character lowercase hex hash
     * @return true if an entry exists
     */
    boolean existsByHash(String casHash);

    /**
     * Looks up schema metadata by schema name and shape signature.
     *
     * @param schemaName     the nominal schema identifier
     * @param shapeSignature the flattened shape definitions signature
     * @return Optional containing SchemaMetadata if hit, empty otherwise
     */
    Optional<SchemaMetadata> findByShape(String schemaName, String shapeSignature);

    /**
     * Looks up schema metadata by nominal schema name.
     *
     * @param schemaName the schema name
     * @return Optional containing SchemaMetadata if found, empty otherwise
     */
    Optional<SchemaMetadata> findBySchemaName(String schemaName);
}
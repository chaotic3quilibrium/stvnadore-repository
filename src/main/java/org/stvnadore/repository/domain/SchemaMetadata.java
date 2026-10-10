package org.stvnadore.repository.domain;

/**
 * Immutable metadata record capturing the nominal name, structural shape, CAS address, and domain of a schema.
 *
 * @param schemaName the nominal schema identifier
 * @param shapeSignature the canonical flattened structural shape signature
 * @param casHash 64-character lowercase hexadecimal SHA-256 content address
 * @param domain logical namespace domain of the schema
 */
public record SchemaMetadata(String schemaName, String shapeSignature, String casHash, String domain) {
    /**
     * Backward-compatible constructor defaulting domain to {@code "default"}.
     *
     * @param schemaName the nominal schema identifier
     * @param shapeSignature the canonical flattened structural shape signature
     * @param casHash 64-character lowercase hexadecimal SHA-256 content address
     */
    public SchemaMetadata(String schemaName, String shapeSignature, String casHash) {
        this(schemaName, shapeSignature, casHash, "default");
    }
}
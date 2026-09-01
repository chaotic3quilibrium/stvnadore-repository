package org.stvnadore.repository.domain;

/**
 * Immutable metadata record capturing the nominal name, structural shape, and CAS address of a schema.
 *
 * @param schemaName the nominal schema identifier
 * @param shapeSignature the canonical flattened structural shape signature
 * @param casHash 64-character lowercase hexadecimal SHA-256 content address
 */
public record SchemaMetadata(String schemaName, String shapeSignature, String casHash) {}
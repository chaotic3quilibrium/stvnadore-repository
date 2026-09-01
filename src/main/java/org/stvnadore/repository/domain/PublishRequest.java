package org.stvnadore.repository.domain;

/**
 * Request command for publishing a schema into the repository.
 *
 * @param schemaName the nominal identifier of the schema
 * @param sourceText raw STVN schema source content
 */
public record PublishRequest(String schemaName, String sourceText) {}
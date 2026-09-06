package org.stvnadore.repository.domain;

import org.jspecify.annotations.Nullable;

/**
 * Request command for publishing a schema into the repository.
 *
 * @param schemaName the nominal identifier of the schema
 * @param sourceText raw STVN schema source content, or null if binary
 * @param binaryPayload raw binary payload bytes, or null if textual
 */
public record PublishRequest(
    String schemaName,
    @Nullable String sourceText,
    byte @Nullable [] binaryPayload
) {
    /**
     * Constructs a textual schema publish request.
     *
     * @param schemaName the nominal schema identifier
     * @param sourceText raw STVN schema source content
     */
    public PublishRequest(String schemaName, String sourceText) {
        this(schemaName, sourceText, null);
    }

    /**
     * Constructs a binary schema publish request.
     *
     * @param schemaName the nominal schema identifier
     * @param binaryPayload raw binary payload bytes
     */
    public PublishRequest(String schemaName, byte[] binaryPayload) {
        this(schemaName, null, binaryPayload);
    }
}
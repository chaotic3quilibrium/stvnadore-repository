package org.stvnadore.repository.domain;

import java.util.List;

/**
 * Sealed algebraic hierarchy modeling all possible typed outcomes of publishing a schema.
 */
public sealed interface PublishResult {
  /**
   * Indicates that the schema was successfully published and indexed.
   *
   * @param metadata the published schema metadata
   */
  record Success(SchemaMetadata metadata) implements PublishResult {}
  /**
   * Indicates that the schema name and content hash already exist (idempotent duplicate).
   *
   * @param metadata the existing schema metadata
   */
  record IdempotentCollision(SchemaMetadata metadata) implements PublishResult {}
  /**
   * Indicates that the schema name exists with a different cryptographic hash (mutation conflict).
   *
   * @param schemaName the conflicting schema name
   * @param existingHash the hash of the existing schema
   * @param submittedHash the hash of the submitted schema
   */
  record SchemaConflict(String schemaName, String existingHash, String submittedHash) implements PublishResult {}
  /**
   * Indicates that the schema failed syntactic or semantic validation.
   *
   * @param diagnostics compiler diagnostics describing the errors
   */
  record ValidationError(List<CompileDiagnostic> diagnostics) implements PublishResult {}
  /**
   * Indicates that the CAS payload was saved but relational indexing was deferred to the background sweeper.
   *
   * @param metadata the saved schema metadata
   */
  record IndexingDeferred(SchemaMetadata metadata) implements PublishResult {}
}
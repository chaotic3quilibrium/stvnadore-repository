package org.stvnadore.repository;

import org.stvnadore.core.StvnAnalysisResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.binary.StvnSchemaHasher;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;
import org.stvnadore.repository.domain.*;
import org.stvnadore.repository.infrastructure.StvnCasPackager;
import org.stvnadore.repository.ports.CasStoragePort;
import org.stvnadore.repository.ports.IndexRepositoryPort;
import org.stvnadore.repository.ports.VersionCatalogCache;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Production implementation of {@link SchemaRepositoryEngine}.
 * Coordinates CAS storage, relational indexing, and version catalog caching.
 */
public class SimpleSchemaRepositoryEngine implements SchemaRepositoryEngine {
    private final CasStoragePort casStoragePort;
    private final IndexRepositoryPort indexRepositoryPort;
    private final VersionCatalogCache versionCatalogCache;

    /**
     * Constructs a SimpleSchemaRepositoryEngine with backing storage and index ports.
     *
     * @param casStoragePort the CAS physical storage port
     * @param indexRepositoryPort the relational index repository port
     * @param versionCatalogCache the in-memory catalog cache port
     */
    public SimpleSchemaRepositoryEngine(
            CasStoragePort casStoragePort,
            IndexRepositoryPort indexRepositoryPort,
            VersionCatalogCache versionCatalogCache
    ) {
        this.casStoragePort = casStoragePort;
        this.indexRepositoryPort = indexRepositoryPort;
        this.versionCatalogCache = versionCatalogCache;
    }

    @Override
    public PublishResult publish(PublishRequest request) {
        String sourceText = request.sourceText();
        String schemaName = request.schemaName();

        if (sourceText == null || sourceText.isBlank()) {
            return new PublishResult.ValidationError(List.of(
                new CompileDiagnostic("Source text cannot be empty", 1, 1)
            ));
        }

        // 1. Parse and validate incoming raw include text
        StvnAnalysisResult<Optional<StvnValue>, List<StvnDiagnostic>> analysis = StvnCompiler.analyze(sourceText);
        if (!analysis.diagnostics().isEmpty()) {
            List<CompileDiagnostic> compileDiagnostics = analysis.diagnostics().stream()
                .map(d -> new CompileDiagnostic(d.message(), d.line(), d.column()))
                .toList();
            return new PublishResult.ValidationError(compileDiagnostics);
        }

        Optional<StvnValue> valueOpt = analysis.value();
        if (valueOpt.isEmpty()) {
            return new PublishResult.ValidationError(List.of(
                new CompileDiagnostic("Parsed STVN document has an empty body", 1, 1)
            ));
        }

        StvnValue value = valueOpt.get();

        // 2. Canonicalize AST representation
        String canonicalSource = StvnCompiler.toCanonicalString(value);

        // 3. Derive shape signature and CAS hash
        String shapeSignature;
        String entryPointPath = schemaName + ".stvn";
        try {
            shapeSignature = StvnSchemaFlattener.flatten(Map.of(entryPointPath, canonicalSource), entryPointPath);
        } catch (Exception e) {
            return new PublishResult.ValidationError(List.of(
                new CompileDiagnostic("Schema flattening failed: " + e.getMessage(), 1, 1)
            ));
        }

        ResolvedSchema schema = value.schema();
        String casHash;
        try {
            byte[] hashBytes = StvnSchemaHasher.computeSha256(schema);
            casHash = HexFormat.of().formatHex(hashBytes);
        } catch (Exception e) {
            return new PublishResult.ValidationError(List.of(
                new CompileDiagnostic("Schema hashing failed: " + e.getMessage(), 1, 1)
            ));
        }

        SchemaMetadata metadata = new SchemaMetadata(schemaName, shapeSignature, casHash);

        // 4. Check for schema name collision / mutation attempt
        Optional<SchemaMetadata> existingOpt = indexRepositoryPort.findBySchemaName(schemaName);
        if (existingOpt.isPresent()) {
            SchemaMetadata existing = existingOpt.get();
            if (existing.casHash().equalsIgnoreCase(casHash)) {
                return new PublishResult.IdempotentCollision(metadata);
            } else {
                return new PublishResult.SchemaConflict(schemaName, existing.casHash(), casHash);
            }
        }

        // 5. Package envelope using canonical source text
        String envelopeText = StvnCasPackager.packageEnvelope(schemaName, casHash, canonicalSource);
        byte[] envelopeBytes = envelopeText.getBytes(StandardCharsets.UTF_8);

        // 6. Write to CAS Storage
        try {
            casStoragePort.write(casHash, envelopeBytes);
        } catch (Exception e) {
            return new PublishResult.IndexingDeferred(metadata);
        }

        // 7. Write to Relational Index and Audit Log
        try {
            indexRepositoryPort.save(metadata, sourceText);
            versionCatalogCache.put(metadata);
            return new PublishResult.Success(metadata);
        } catch (DuplicateIndexException e) {
            return new PublishResult.IdempotentCollision(metadata);
        } catch (Exception e) {
            return new PublishResult.IndexingDeferred(metadata);
        }
    }

    @Override
    public Optional<SchemaMetadata> getSchemaMetadata(String schemaName, String shapeSignature) {
        Optional<SchemaMetadata> cached = versionCatalogCache.get(schemaName, shapeSignature);
        if (cached.isPresent()) {
            return cached;
        }

        Optional<SchemaMetadata> dbResult = indexRepositoryPort.findByShape(schemaName, shapeSignature);
        dbResult.ifPresent(versionCatalogCache::put);
        return dbResult;
    }

    /**
     * Returns the underlying CAS storage port instance.
     *
     * @return the CAS storage port
     */
    public CasStoragePort getCasStoragePort() {
        return casStoragePort;
    }
}

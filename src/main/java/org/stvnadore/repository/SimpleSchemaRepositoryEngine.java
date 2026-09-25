package org.stvnadore.repository;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.binary.SchemaIdentityStrategy;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryDecoder.RootPointer;
import org.stvnadore.core.binary.StvnSchemaHasher;
import org.stvnadore.core.utils.StvnStringCapacityUtils;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;
import org.stvnadore.repository.domain.*;
import org.stvnadore.repository.infrastructure.StvnCasPackager;
import org.stvnadore.repository.ports.CasStoragePort;
import org.stvnadore.repository.ports.IndexRepositoryPort;
import org.stvnadore.repository.ports.VersionCatalogCache;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

        if (sourceText == null && request.binaryPayload() != null) {
            try {
                RootPointer root = StvnBinaryDecoder.open(ByteBuffer.wrap(request.binaryPayload()));
                return publishBinary(request, root);
            } catch (Exception e) {
                return new PublishResult.ValidationError(List.of(
                    new CompileDiagnostic("Binary decode failed: " + e.getMessage(), 1, 1)
                ));
            }
        }

        if (sourceText == null || sourceText.isBlank()) {
            return new PublishResult.ValidationError(List.of(
                new CompileDiagnostic("Source text cannot be empty", 1, 1)
            ));
        }

        // Perimeter Gate: Enforce centralized string capacity bounds (16 MiB)
        if (sourceText.length() > StvnStringCapacityUtils.DEFAULT_UNBOUNDED_STRING_CAPACITY) {
            return new PublishResult.ValidationError(List.of(
                new CompileDiagnostic("ERR_CAPACITY_OVERFLOW: Payload exceeds maximum allowed string capacity: " + StvnStringCapacityUtils.DEFAULT_UNBOUNDED_STRING_CAPACITY + " characters", 1, 1)
            ));
        }

        // Gate 1 (Filename Hygiene): Enforce that schema filename strictly ends with .stvn_inclf
        if (!schemaName.endsWith(".stvn_inclf")) {
            return new PublishResult.ValidationError(List.of(
                new CompileDiagnostic("ERR_MALFORMED_SCHEMA_IN_ENVELOPE: Schema filename must strictly end with '.stvn_inclf': " + schemaName, 1, 1)
            ));
        }

        // Headless Validation Gate: Compile schema first to catch syntax errors and raw tab characters
        StvnCompilationResult<StvnValue> compileResult = StvnCompiler.compileToResult(sourceText, schemaName, StvnParserConfig.STRICT);
        if (compileResult.hasErrors()) {
            List<CompileDiagnostic> compileDiagnostics = compileResult.diagnostics().stream()
                .map(d -> {
                    String msg = d.message();
                    if (d.errorCode().isPresent() && !msg.contains(d.errorCode().get())) {
                        msg = d.errorCode().get() + ": " + msg;
                    }
                    return new CompileDiagnostic(msg, d.line(), d.column());
                })
                .toList();
            return new PublishResult.ValidationError(compileDiagnostics);
        }

        // Gate 2 (AST Structure Invariant): Root context must contain strictly a :defs section
        StvnLexer lexer = new StvnLexer(CharStreams.fromString(sourceText));
        lexer.removeErrorListeners();
        StvnParser parser = new StvnParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        var docCtx = parser.stvnDocument();

        if (docCtx.documentBody() == null || docCtx.documentBody().defsEntry() == null) {
            return new PublishResult.ValidationError(List.of(
                new CompileDiagnostic("ERR_MALFORMED_SCHEMA_IN_ENVELOPE: Schema root must contain strictly a :defs section", 1, 1)
            ));
        }

        if (docCtx.documentBody().typeEntry() != null) {
            return new PublishResult.ValidationError(List.of(
                new CompileDiagnostic("ERR_MALFORMED_SCHEMA_IN_ENVELOPE: Top-level :type section is prohibited in flat schema document", 1, 1)
            ));
        }

        if (docCtx.documentBody().bodyEntry() != null) {
            return new PublishResult.ValidationError(List.of(
                new CompileDiagnostic("ERR_MALFORMED_SCHEMA_IN_ENVELOPE: Top-level :body section is prohibited in flat schema document", 1, 1)
            ));
        }

        for (var element : docCtx.documentBody().defsEntry().defsElement()) {
            if (element.includeStmt() != null) {
                return new PublishResult.ValidationError(List.of(
                    new CompileDiagnostic("ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT: Flat schemas (.stvn_inclf) cannot contain :include directives", 1, 1)
                ));
            }
        }

        // 2. Derive canonical structural shape signature via flattener
        String shapeSignature;
        try {
            shapeSignature = StvnSchemaFlattener.flatten(Map.of(schemaName, sourceText), schemaName);
        } catch (Exception e) {
            return new PublishResult.ValidationError(List.of(
                new CompileDiagnostic("Schema flattening failed: " + e.getMessage(), 1, 1)
            ));
        }

        // 3. Compute deterministic SHA-256 CAS address from the canonical flattened AST
        String casHash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(shapeSignature.getBytes(StandardCharsets.UTF_8));
            casHash = HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm missing from environment", e);
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

        // 4.1 Check for nominal alias collision (Nominal Bijectivity Invariant 1:1 Law)
        Optional<SchemaMetadata> existingByHash = indexRepositoryPort.findByCasHash(casHash);
        if (existingByHash.isPresent()) {
            SchemaMetadata existing = existingByHash.get();
            return new PublishResult.AliasConflict(schemaName, existing.schemaName(), casHash);
        }

        // 5. Package envelope using source text
        String envelopeText = StvnCasPackager.packageEnvelope(schemaName, casHash, sourceText);
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
            // Concurrent race fallback: inspect database to resolve whether collision was idempotent, mutation, or alias
            Optional<SchemaMetadata> concurrentOpt = indexRepositoryPort.findBySchemaName(schemaName);
            if (concurrentOpt.isPresent()) {
                if (concurrentOpt.get().casHash().equalsIgnoreCase(casHash)) {
                    return new PublishResult.IdempotentCollision(metadata);
                } else {
                    return new PublishResult.SchemaConflict(schemaName, concurrentOpt.get().casHash(), casHash);
                }
            }
            Optional<SchemaMetadata> hashOpt = indexRepositoryPort.findByCasHash(casHash);
            String existingName = hashOpt.map(SchemaMetadata::schemaName).orElse("unknown");
            return new PublishResult.AliasConflict(schemaName, existingName, casHash);
        } catch (Exception e) {
            return new PublishResult.IndexingDeferred(metadata);
        }
    }

    @Override
    public PublishResult publishBinary(PublishRequest request, RootPointer root) {
        String schemaName = request.schemaName();
        byte[] binaryPayload = request.binaryPayload();

        if (binaryPayload == null || binaryPayload.length == 0) {
            return new PublishResult.ValidationError(List.of(
                new CompileDiagnostic("Binary payload cannot be empty", 1, 1)
            ));
        }

        // Perimeter Gate: Enforce centralized capacity limit on binary payload (16 MiB)
        if (binaryPayload.length > StvnStringCapacityUtils.DEFAULT_UNBOUNDED_STRING_CAPACITY) {
            return new PublishResult.ValidationError(List.of(
                new CompileDiagnostic("ERR_CAPACITY_OVERFLOW: Binary payload exceeds maximum allowed capacity: " + StvnStringCapacityUtils.DEFAULT_UNBOUNDED_STRING_CAPACITY + " bytes", 1, 1)
            ));
        }

        String casHash;
        String shapeSignature;

        if (root.context().identityStrategy().isPresent() &&
            root.context().identityStrategy().get() instanceof SchemaIdentityStrategy.ExplicitSha256 explicit) {
            casHash = HexFormat.of().formatHex(explicit.hash());
            shapeSignature = "binary:" + schemaName + ":" + root.context().encodingStrategy().name();
        } else if (root.schema().isPresent()) {
            ResolvedSchema schema = root.schema().get();
            try {
                byte[] hashBytes = StvnSchemaHasher.computeSha256(schema);
                casHash = HexFormat.of().formatHex(hashBytes);
            } catch (Exception e) {
                return new PublishResult.ValidationError(List.of(
                    new CompileDiagnostic("Schema hashing failed: " + e.getMessage(), 1, 1)
                ));
            }
            shapeSignature = "binary:" + schemaName + ":" + root.context().encodingStrategy().name();
        } else {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hashBytes = digest.digest(binaryPayload);
                casHash = HexFormat.of().formatHex(hashBytes);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("SHA-256 algorithm missing from environment", e);
            }
            shapeSignature = "binary:" + root.context().encodingStrategy().name();
        }

        SchemaMetadata metadata = new SchemaMetadata(schemaName, shapeSignature, casHash);

        // Check for schema name collision / mutation attempt
        Optional<SchemaMetadata> existingOpt = indexRepositoryPort.findBySchemaName(schemaName);
        if (existingOpt.isPresent()) {
            SchemaMetadata existing = existingOpt.get();
            if (existing.casHash().equalsIgnoreCase(casHash)) {
                return new PublishResult.IdempotentCollision(metadata);
            } else {
                return new PublishResult.SchemaConflict(schemaName, existing.casHash(), casHash);
            }
        }

        // Check for nominal alias collision (Nominal Bijectivity Invariant 1:1 Law)
        Optional<SchemaMetadata> existingByHash = indexRepositoryPort.findByCasHash(casHash);
        if (existingByHash.isPresent()) {
            SchemaMetadata existing = existingByHash.get();
            return new PublishResult.AliasConflict(schemaName, existing.schemaName(), casHash);
        }

        // Write binary payload to CAS Storage
        try {
            casStoragePort.write(casHash, binaryPayload);
        } catch (Exception e) {
            return new PublishResult.IndexingDeferred(metadata);
        }

        // Write to Relational Index and Audit Log
        try {
            indexRepositoryPort.save(metadata, "BINARY_PAYLOAD:" + casHash);
            versionCatalogCache.put(metadata);
            return new PublishResult.Success(metadata);
        } catch (DuplicateIndexException e) {
            // Concurrent race fallback: inspect database to resolve whether collision was idempotent, mutation, or alias
            Optional<SchemaMetadata> concurrentOpt = indexRepositoryPort.findBySchemaName(schemaName);
            if (concurrentOpt.isPresent()) {
                if (concurrentOpt.get().casHash().equalsIgnoreCase(casHash)) {
                    return new PublishResult.IdempotentCollision(metadata);
                } else {
                    return new PublishResult.SchemaConflict(schemaName, concurrentOpt.get().casHash(), casHash);
                }
            }
            Optional<SchemaMetadata> hashOpt = indexRepositoryPort.findByCasHash(casHash);
            String existingName = hashOpt.map(SchemaMetadata::schemaName).orElse("unknown");
            return new PublishResult.AliasConflict(schemaName, existingName, casHash);
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

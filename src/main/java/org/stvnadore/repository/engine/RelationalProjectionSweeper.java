package org.stvnadore.repository.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stvnadore.core.StvnAnalysisResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.binary.StvnSchemaHasher;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;
import org.stvnadore.repository.domain.DuplicateIndexException;
import org.stvnadore.repository.domain.SchemaMetadata;
import org.stvnadore.repository.infrastructure.FileSystemCasStorage;
import org.stvnadore.repository.ports.CasDirectoryScannerPort;
import org.stvnadore.repository.ports.CasStoragePort;
import org.stvnadore.repository.ports.IndexRepositoryPort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sweeper that reconciles physical CAS files with the relational catalog index.
 * Re-verifies AST cryptographic hash and relocates invalid files to .quarantine/.
 */
public class RelationalProjectionSweeper implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(RelationalProjectionSweeper.class);

    private final CasStoragePort casStorage;
    private final IndexRepositoryPort indexRepository;
    private final CasDirectoryScannerPort scanner;
    private final Path casRoot;

    /**
     * Constructs a RelationalProjectionSweeper with explicit root directory path.
     *
     * @param casStorage the CAS storage port
     * @param indexRepository the relational index repository port
     * @param scanner the CAS directory scanner port
     * @param casRoot the root filesystem directory of the CAS store
     */
    public RelationalProjectionSweeper(
            CasStoragePort casStorage,
            IndexRepositoryPort indexRepository,
            CasDirectoryScannerPort scanner,
            Path casRoot
    ) {
        this.casStorage = casStorage;
        this.indexRepository = indexRepository;
        this.scanner = scanner;
        this.casRoot = casRoot;
    }

    /**
     * Constructs a RelationalProjectionSweeper with default root resolution.
     *
     * @param casStorage the CAS storage port
     * @param indexRepository the relational index repository port
     * @param scanner the CAS directory scanner port
     */
    public RelationalProjectionSweeper(
            CasStoragePort casStorage,
            IndexRepositoryPort indexRepository,
            CasDirectoryScannerPort scanner
    ) {
        this(
            casStorage,
            indexRepository,
            scanner,
            (casStorage instanceof FileSystemCasStorage fsStorage) ? fsStorage.getRoot() : Paths.get("data/cas")
        );
    }

    @Override
    public void run() {
        logger.info("Starting CAS-to-Relational catalog projection sweep...");
        List<String> hashes;
        try {
            hashes = scanner.listAllCasHashes();
        } catch (Exception e) {
            logger.error("Failed to list CAS hashes. Aborting sweep.", e);
            return;
        }

        logger.info("Discovered {} CAS files to sweep.", hashes.size());

        for (String casHash : hashes) {
            try {
                if (indexRepository.existsByHash(casHash)) {
                    continue;
                }

                logger.info("CAS hash {} is missing from index. Restoring projection...", casHash);

                byte[] envelopeBytes = casStorage.read(casHash);
                if (envelopeBytes == null || envelopeBytes.length == 0) {
                    quarantine(casHash, "EMPTY_PAYLOAD");
                    continue;
                }

                String envelopeText = new String(envelopeBytes, StandardCharsets.UTF_8);

                StvnAnalysisResult<Optional<StvnValue>, List<StvnDiagnostic>> analysis = StvnCompiler.analyze(envelopeText);
                if (!analysis.diagnostics().isEmpty() || analysis.value().isEmpty()) {
                    quarantine(casHash, "CORRUPT_ENVELOPE");
                    continue;
                }

                StvnValue value = analysis.value().get();
                if (!(value instanceof StvnValue.StvnTuple tuple) || tuple.elements().size() < 2) {
                    quarantine(casHash, "INVALID_TUPLE_FORMAT");
                    continue;
                }

                if (!(tuple.elements().get(0) instanceof StvnValue.StvnString nameVal) ||
                    !(tuple.elements().get(1) instanceof StvnValue.StvnString sourceVal)) {
                    quarantine(casHash, "INVALID_TUPLE_ELEMENTS");
                    continue;
                }

                String schemaName = nameVal.value();
                String innerSourceText = sourceVal.value().trim();

                StvnAnalysisResult<Optional<StvnValue>, List<StvnDiagnostic>> innerAnalysis = StvnCompiler.analyze(innerSourceText);
                if (!innerAnalysis.diagnostics().isEmpty() || innerAnalysis.value().isEmpty()) {
                    quarantine(casHash, "INVALID_INNER_AST");
                    continue;
                }

                StvnValue innerVal = innerAnalysis.value().get();
                ResolvedSchema schema = innerVal.schema();

                // Recalculate SHA-256 AST hash
                byte[] calculatedHashBytes = StvnSchemaHasher.computeSha256(schema);
                String calculatedHash = HexFormat.of().formatHex(calculatedHashBytes);

                if (!calculatedHash.equalsIgnoreCase(casHash)) {
                    logger.error("Cryptographic mismatch detected for schema {}! File hash: {}, Computed AST hash: {}",
                            schemaName, casHash, calculatedHash);
                    quarantine(casHash, "HASH_MISMATCH");
                    continue;
                }

                // Derive structural shape signature
                String entryPointPath = schemaName + ".stvn";
                String shapeSignature = StvnSchemaFlattener.flatten(Map.of(entryPointPath, innerSourceText), entryPointPath);

                // Save to relational catalog with original source text
                SchemaMetadata metadata = new SchemaMetadata(schemaName, shapeSignature, casHash);
                indexRepository.save(metadata, innerSourceText);
                logger.info("Successfully recovered projection for schema: {} (hash: {})", schemaName, casHash);

            } catch (DuplicateIndexException e) {
                logger.warn("Duplicate database index detected for CAS hash: {} during reconciliation. Skipping.", casHash, e);
            } catch (Exception e) {
                logger.error("Error reconciling CAS hash: {} into relational index", casHash, e);
            }
        }

        logger.info("CAS-to-Relational catalog projection sweep finished.");
    }

    private void quarantine(String casHash, String failureReason) {
        String prefix = casHash.substring(0, 2);
        String suffix = casHash.substring(2);
        Path sourceFile = casRoot.resolve(prefix).resolve(suffix + ".stvn_cas");
        Path quarantineDir = casRoot.resolve(".quarantine");
        long timestamp = System.currentTimeMillis();
        String originalFilename = suffix + ".stvn_cas";
        String quarantineFilename = originalFilename + "." + timestamp + "." + failureReason + ".quarantine";
        Path targetFile = quarantineDir.resolve(quarantineFilename);

        try {
            Files.createDirectories(quarantineDir);
            if (Files.exists(sourceFile)) {
                Files.move(sourceFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                logger.warn("Quarantined corrupt CAS file {} -> {}", sourceFile, targetFile);
            }
        } catch (IOException e) {
            logger.error("Failed to relocate corrupt CAS file {} to quarantine directory {}", sourceFile, targetFile, e);
        }
    }
}

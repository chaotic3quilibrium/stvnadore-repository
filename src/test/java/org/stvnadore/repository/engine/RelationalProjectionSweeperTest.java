package org.stvnadore.repository.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.binary.StvnSchemaHasher;
import org.stvnadore.repository.domain.DuplicateIndexException;
import org.stvnadore.repository.domain.SchemaMetadata;
import org.stvnadore.repository.infrastructure.FileSystemCasStorage;
import org.stvnadore.repository.infrastructure.StvnCasPackager;
import org.stvnadore.repository.ports.CasDirectoryScannerPort;
import org.stvnadore.repository.ports.IndexRepositoryPort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class RelationalProjectionSweeperTest {

    @TempDir
    Path tempCasRoot;

    private FileSystemCasStorage casStorage;
    private IndexRepositoryPort indexRepository;
    private CasDirectoryScannerPort scanner;
    private RelationalProjectionSweeper sweeper;

    @BeforeEach
    public void setUp() {
        casStorage = new FileSystemCasStorage(tempCasRoot);
        indexRepository = mock(IndexRepositoryPort.class);
        scanner = mock(CasDirectoryScannerPort.class);
        sweeper = new RelationalProjectionSweeper(casStorage, indexRepository, scanner, tempCasRoot);
    }

    @Test
    public void testSweeperSkipsAlreadyIndexed() {
        String hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        when(scanner.listAllCasHashes()).thenReturn(List.of(hash));
        when(indexRepository.existsByHash(hash)).thenReturn(true);

        sweeper.run();

        verify(indexRepository, never()).save(any(SchemaMetadata.class), anyString());
    }

    @Test
    public void testSweeperSuccessfullyReconcilesMissing() throws DuplicateIndexException {
        String schemaName = "user-profile";
        String innerSourceText = "{\n  :type :String\n  :body \"hello\"\n}";

        // Compute actual AST hash
        var ast = StvnCompiler.compile(innerSourceText).orElseThrow();
        byte[] hashBytes = StvnSchemaHasher.computeSha256(ast.schema());
        String matchingHash = HexFormat.of().formatHex(hashBytes);

        String envelope = StvnCasPackager.packageEnvelope(schemaName, matchingHash, innerSourceText);
        casStorage.write(matchingHash, envelope.getBytes(StandardCharsets.UTF_8));

        when(scanner.listAllCasHashes()).thenReturn(List.of(matchingHash));
        when(indexRepository.existsByHash(matchingHash)).thenReturn(false);

        sweeper.run();

        verify(indexRepository).save(
            argThat(metadata ->
                metadata.schemaName().equals(schemaName) &&
                metadata.casHash().equals(matchingHash) &&
                metadata.shapeSignature().contains(":defs")
            ),
            eq(innerSourceText)
        );
    }

    @Test
    public void testSweeperQuarantinesHashMismatch() throws IOException {
        String schemaName = "tampered-schema";
        String innerSourceText = "{\n  :type :String\n  :body \"hello\"\n}";
        String fakeHash = "1111111111111111111111111111111111111111111111111111111111111111";

        String envelope = StvnCasPackager.packageEnvelope(schemaName, fakeHash, innerSourceText);
        casStorage.write(fakeHash, envelope.getBytes(StandardCharsets.UTF_8));

        when(scanner.listAllCasHashes()).thenReturn(List.of(fakeHash));
        when(indexRepository.existsByHash(fakeHash)).thenReturn(false);

        sweeper.run();

        // Should NOT save to index
        verify(indexRepository, never()).save(any(SchemaMetadata.class), anyString());

        // File should be moved to .quarantine directory
        Path quarantineDir = tempCasRoot.resolve(".quarantine");
        assertTrue(Files.exists(quarantineDir));
        try (var stream = Files.list(quarantineDir)) {
            List<Path> files = stream.toList();
            assertEquals(1, files.size());
            assertTrue(files.get(0).getFileName().toString().contains("HASH_MISMATCH"));
        }
    }

    @Test
    public void testSweeperQuarantinesCorruptEnvelope() throws IOException {
        String fakeHash = "2222222222222222222222222222222222222222222222222222222222222222";
        String brokenEnvelope = "this is not valid STVN syntax {{{";

        casStorage.write(fakeHash, brokenEnvelope.getBytes(StandardCharsets.UTF_8));

        when(scanner.listAllCasHashes()).thenReturn(List.of(fakeHash));
        when(indexRepository.existsByHash(fakeHash)).thenReturn(false);

        sweeper.run();

        verify(indexRepository, never()).save(any(SchemaMetadata.class), anyString());

        Path quarantineDir = tempCasRoot.resolve(".quarantine");
        assertTrue(Files.exists(quarantineDir));
        try (var stream = Files.list(quarantineDir)) {
            List<Path> files = stream.toList();
            assertEquals(1, files.size());
            assertTrue(files.get(0).getFileName().toString().contains("CORRUPT_ENVELOPE"));
        }
    }
}

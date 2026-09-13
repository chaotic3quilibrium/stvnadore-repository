package org.stvnadore.repository.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stvnadore.core.StvnSchemaFlattener;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

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
    public void testSweeperSuccessfullyReconcilesMissing() throws DuplicateIndexException, NoSuchAlgorithmException {
        String schemaName = "user-profile.stvn_inclf";
        String innerSourceText = "{\n  :defs {\n    :UserId :Uint64\n    :UserName :StringNonEmpty\n  }\n}";

        // Compute actual canonical AST hash
        String shapeSig = StvnSchemaFlattener.flatten(Map.of(schemaName, innerSourceText), schemaName);
        byte[] hashBytes = MessageDigest.getInstance("SHA-256").digest(shapeSig.getBytes(StandardCharsets.UTF_8));
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
        String schemaName = "tampered-schema.stvn_inclf";
        String innerSourceText = "{\n  :defs {\n    :UserId :Uint64\n  }\n}";
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

    @Test
    public void testSweeperQuarantinesNonStvnInclfFilename() throws IOException {
        String schemaName = "legacy_schema.stvn";
        String innerSourceText = "{\n  :defs {\n    :UserId :Uint64\n  }\n}";
        String fakeHash = "3333333333333333333333333333333333333333333333333333333333333333";

        String envelope = StvnCasPackager.packageEnvelope(schemaName, fakeHash, innerSourceText);
        casStorage.write(fakeHash, envelope.getBytes(StandardCharsets.UTF_8));

        when(scanner.listAllCasHashes()).thenReturn(List.of(fakeHash));
        when(indexRepository.existsByHash(fakeHash)).thenReturn(false);

        sweeper.run();

        verify(indexRepository, never()).save(any(SchemaMetadata.class), anyString());

        Path quarantineDir = tempCasRoot.resolve(".quarantine");
        assertTrue(Files.exists(quarantineDir));
        try (var stream = Files.list(quarantineDir)) {
            List<Path> files = stream.toList();
            assertEquals(1, files.size());
            assertTrue(files.get(0).getFileName().toString().contains("INVALID_FILENAME_EXTENSION"));
        }
    }

    @Test
    public void testSweeperQuarantinesIllegalIncludes() throws IOException {
        String schemaName = "illegal_include.stvn_inclf";
        String innerSourceText = "{\n  :defs {\n    :include [ \"other.stvn_inclf\" ]\n    :UserId :Uint64\n  }\n}";
        String fakeHash = "4444444444444444444444444444444444444444444444444444444444444444";

        String envelope = StvnCasPackager.packageEnvelope(schemaName, fakeHash, innerSourceText);
        casStorage.write(fakeHash, envelope.getBytes(StandardCharsets.UTF_8));

        when(scanner.listAllCasHashes()).thenReturn(List.of(fakeHash));
        when(indexRepository.existsByHash(fakeHash)).thenReturn(false);

        sweeper.run();

        verify(indexRepository, never()).save(any(SchemaMetadata.class), anyString());

        Path quarantineDir = tempCasRoot.resolve(".quarantine");
        assertTrue(Files.exists(quarantineDir));
        try (var stream = Files.list(quarantineDir)) {
            List<Path> files = stream.toList();
            assertEquals(1, files.size());
            assertTrue(files.get(0).getFileName().toString().contains("ILLEGAL_INCLUDES_IN_FLAT_SCHEMA"));
        }
    }

    @Test
    public void testSweeperQuarantinesMalformedInnerStructure() throws IOException {
        String schemaName = "body_structure.stvn_inclf";
        String innerSourceText = "{\n  :defs {\n    :UserId :Uint64\n  }\n  :type :UserId\n  :body 100\n}";
        String fakeHash = "5555555555555555555555555555555555555555555555555555555555555555";

        String envelope = StvnCasPackager.packageEnvelope(schemaName, fakeHash, innerSourceText);
        casStorage.write(fakeHash, envelope.getBytes(StandardCharsets.UTF_8));

        when(scanner.listAllCasHashes()).thenReturn(List.of(fakeHash));
        when(indexRepository.existsByHash(fakeHash)).thenReturn(false);

        sweeper.run();

        verify(indexRepository, never()).save(any(SchemaMetadata.class), anyString());

        Path quarantineDir = tempCasRoot.resolve(".quarantine");
        assertTrue(Files.exists(quarantineDir));
        try (var stream = Files.list(quarantineDir)) {
            List<Path> files = stream.toList();
            assertEquals(1, files.size());
            assertTrue(files.get(0).getFileName().toString().contains("MALFORMED_INNER_STRUCTURE"));
        }
    }
}

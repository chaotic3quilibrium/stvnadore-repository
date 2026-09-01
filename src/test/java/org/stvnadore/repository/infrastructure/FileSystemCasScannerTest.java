package org.stvnadore.repository.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FileSystemCasScannerTest {

    @TempDir
    Path tempDir;

    private FileSystemCasScanner scanner;

    @BeforeEach
    public void setUp() {
        scanner = new FileSystemCasScanner(tempDir);
    }

    @Test
    public void testScanEmptyDirectory() {
        List<String> hashes = scanner.listAllCasHashes();
        assertTrue(hashes.isEmpty());
    }

    @Test
    public void testScanNonExistentDirectory() {
        FileSystemCasScanner nonExistentScanner = new FileSystemCasScanner(tempDir.resolve("does-not-exist"));
        List<String> hashes = nonExistentScanner.listAllCasHashes();
        assertTrue(hashes.isEmpty());
    }

    @Test
    public void testScanTraversesAndFiltersCorrectly() throws IOException {
        String hash1 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        String hash2 = "ff0016bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

        // Create 2/62 sharded paths
        Path dir1 = tempDir.resolve("ba");
        Files.createDirectories(dir1);
        Path file1 = dir1.resolve(hash1.substring(2) + ".stvn_cas");
        Files.writeString(file1, "envelope data 1");

        Path dir2 = tempDir.resolve("ff");
        Files.createDirectories(dir2);
        Path file2 = dir2.resolve(hash2.substring(2) + ".stvn_cas");
        Files.writeString(file2, "envelope data 2");

        // Create a non-.stvn_cas file
        Path invalidFile = dir1.resolve("invalid.txt");
        Files.writeString(invalidFile, "txt data");

        // Create a file in .quarantine directory
        Path quarantineDir = tempDir.resolve(".quarantine");
        Files.createDirectories(quarantineDir);
        Path quarantinedFile = quarantineDir.resolve("corrupt.stvn_cas");
        Files.writeString(quarantinedFile, "quarantined");

        List<String> hashes = scanner.listAllCasHashes();
        assertEquals(2, hashes.size());
        assertTrue(hashes.contains(hash1));
        assertTrue(hashes.contains(hash2));
        assertFalse(hashes.contains("corrupt"));
    }
}

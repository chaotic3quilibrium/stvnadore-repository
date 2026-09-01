package org.stvnadore.repository.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class FileSystemCasStorageTest {

    @TempDir
    Path tempDir;

    private FileSystemCasStorage storage;

    @BeforeEach
    public void setUp() {
        storage = new FileSystemCasStorage(tempDir);
    }

    @Test
    public void testWriteAndReadSuccess() throws IOException {
        String casHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        byte[] content = "test content".getBytes(StandardCharsets.UTF_8);

        storage.write(casHash, content);

        // Verify file exists under 2/62 sharded directory hierarchy: <root>/<prefix_2>/<suffix_62>.stvn_cas
        String prefix = casHash.substring(0, 2);
        String suffix = casHash.substring(2);
        Path expectedFile = tempDir.resolve(prefix).resolve(suffix + ".stvn_cas");
        assertTrue(Files.exists(expectedFile));
        assertArrayEquals(content, Files.readAllBytes(expectedFile));

        // Verify we can read it back via the storage port
        byte[] readContent = storage.read(casHash);
        assertArrayEquals(content, readContent);
    }

    @Test
    public void testWriteImmutabilityGuard() throws IOException {
        String casHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        byte[] originalContent = "original content".getBytes(StandardCharsets.UTF_8);
        byte[] modifiedContent = "mutated content".getBytes(StandardCharsets.UTF_8);

        storage.write(casHash, originalContent);
        // Attempting to overwrite existing hash should be ignored (write-immutability guard)
        storage.write(casHash, modifiedContent);

        byte[] readContent = storage.read(casHash);
        assertArrayEquals(originalContent, readContent);
    }

    @Test
    public void testReadNonExistentReturnsNull() {
        String casHash = "ffff16bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        byte[] readContent = storage.read(casHash);
        assertNull(readContent);
    }

    @Test
    public void testInvalidHashThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> storage.write("abc", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> storage.read("abc"));
    }
}

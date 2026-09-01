package org.stvnadore.repository.infrastructure;

import org.stvnadore.repository.ports.CasStoragePort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * FileSystem implementation of CasStoragePort.
 * Implements 2/62 path sharding: {@code <root>/<hash_prefix_2>/<hash_suffix_62>.stvn_cas}.
 */
public class FileSystemCasStorage implements CasStoragePort {
    private final Path root;

    /**
     * Constructs a new FileSystemCasStorage rooted at the given directory.
     *
     * @param root the base storage directory path
     */
    public FileSystemCasStorage(Path root) {
        this.root = root;
    }

    @Override
    public void write(String casHash, byte[] content) {
        if (casHash == null || casHash.length() != 64) {
            throw new IllegalArgumentException("Invalid CAS hash. Expected 64-char hex string, got: " + casHash);
        }
        Path targetFile = getPathForHash(casHash);
        if (Files.exists(targetFile)) {
            // Write immutability guard: CAS content is immutable once written
            return;
        }
        try {
            Files.createDirectories(targetFile.getParent());
            Files.write(targetFile, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write CAS content for hash: " + casHash, e);
        }
    }

    @Override
    public byte[] read(String casHash) {
        if (casHash == null || casHash.length() != 64) {
            throw new IllegalArgumentException("Invalid CAS hash. Expected 64-char hex string, got: " + casHash);
        }
        Path targetFile = getPathForHash(casHash);
        try {
            if (!Files.exists(targetFile)) {
                return null;
            }
            return Files.readAllBytes(targetFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read CAS content for hash: " + casHash, e);
        }
    }

    /**
     * Computes the 2/62 sharded file path for a 64-character CAS hash.
     *
     * @param casHash 64-character lowercase hexadecimal hash
     * @return resolved path on disk
     */
    public Path getPathForHash(String casHash) {
        String prefix = casHash.substring(0, 2);
        String suffix = casHash.substring(2);
        return root.resolve(prefix).resolve(suffix + ".stvn_cas");
    }

    /**
     * Returns the root storage directory path.
     *
     * @return root storage directory
     */
    public Path getRoot() {
        return root;
    }
}

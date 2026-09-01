package org.stvnadore.repository.infrastructure;

import org.stvnadore.repository.ports.CasDirectoryScannerPort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Local filesystem implementation of CasDirectoryScannerPort.
 * Deep walks the sharded CAS directory, skipping .quarantine/ subdirectories.
 */
public class FileSystemCasScanner implements CasDirectoryScannerPort {
    private final Path root;

    /**
     * Constructs a FileSystemCasScanner for the specified root directory.
     *
     * @param root the base directory path of the CAS store
     */
    public FileSystemCasScanner(Path root) {
        this.root = root;
    }

    @Override
    public List<String> listAllCasHashes() {
        if (!Files.exists(root)) {
            return Collections.emptyList();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> !path.toString().contains(".quarantine"))
                .filter(path -> path.getFileName().toString().endsWith(".stvn_cas"))
                .map(this::extractHashFromPath)
                .filter(hash -> hash != null && hash.length() == 64)
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan CAS directory: " + root, e);
        }
    }

    private String extractHashFromPath(Path path) {
        Path parent = path.getParent();
        if (parent == null) return null;
        String prefix = parent.getFileName().toString();
        String filename = path.getFileName().toString();
        String suffix = filename.substring(0, filename.length() - ".stvn_cas".length());
        return prefix + suffix;
    }
}
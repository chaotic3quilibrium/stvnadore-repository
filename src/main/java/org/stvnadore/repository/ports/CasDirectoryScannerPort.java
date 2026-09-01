package org.stvnadore.repository.ports;

import java.util.List;

/**
 * Port interface for traversing the physical CAS storage directory
 * and retrieving a list of all existing CAS hash values.
 */
public interface CasDirectoryScannerPort {
    /**
     * Traverses the hierarchical split-path CAS directory and returns
     * a flat list of all discovered SHA-256 hex string hashes.
     *
     * @return List of pure hash strings (e.g., "ba7816...")
     */
    List<String> listAllCasHashes();
}

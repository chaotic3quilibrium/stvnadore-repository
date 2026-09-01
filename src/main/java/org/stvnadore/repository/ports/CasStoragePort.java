package org.stvnadore.repository.ports;

/**
 * Port interface for reading and writing raw byte payloads to the Content-Addressable Storage (CAS).
 */
public interface CasStoragePort {
    /**
     * Writes immutable binary content under the specified CAS hash address.
     *
     * @param casHash 64-character SHA-256 hex string address
     * @param content raw binary content to store
     */
    void write(String casHash, byte[] content);

    /**
     * Reads binary content from the specified CAS hash address.
     *
     * @param casHash 64-character SHA-256 hex string address
     * @return byte array if found, or {@code null} if absent
     */
    byte[] read(String casHash);
}
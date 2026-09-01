package org.stvnadore.repository.domain;

/**
 * Thrown when an attempt is made to insert a duplicate schema index into the catalog.
 */
public class DuplicateIndexException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a DuplicateIndexException with a detail message.
     *
     * @param message the detail message
     */
    public DuplicateIndexException(String message) {
        super(message);
    }

    /**
     * Constructs a DuplicateIndexException with a detail message and cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public DuplicateIndexException(String message, Throwable cause) {
        super(message, cause);
    }
}
package org.stvnadore.repository.domain;

/**
 * Represents a compilation or validation diagnostic returned by the repository engine.
 *
 * @param message the descriptive error message
 * @param line 1-based source line index
 * @param column 0-based character column offset
 */
public record CompileDiagnostic(String message, int line, int column) {}
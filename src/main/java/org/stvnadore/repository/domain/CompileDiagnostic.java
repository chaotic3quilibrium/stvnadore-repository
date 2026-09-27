package org.stvnadore.repository.domain;

import org.jspecify.annotations.NullMarked;

/**
 * Represents a compilation or validation diagnostic returned by the repository engine.
 * Captures exact source coordinates and character spans for Value-Oriented Programming (VOP) error localization.
 *
 * @param message the descriptive error message
 * @param line 1-based source line index
 * @param column 0-based character column offset
 * @param startOffset 0-based character start index of the erroneous token span (inclusive)
 * @param endOffset 0-based character end index of the erroneous token span (exclusive)
 */
@NullMarked
public record CompileDiagnostic(
    String message,
    int line,
    int column,
    int startOffset,
    int endOffset
) {
    /**
     * Backwards-compatible convenience constructor defaulting offset spans to zero.
     *
     * @param message the descriptive error message
     * @param line 1-based source line index
     * @param column 0-based character column offset
     */
    public CompileDiagnostic(String message, int line, int column) {
        this(message, line, column, 0, 0);
    }
}
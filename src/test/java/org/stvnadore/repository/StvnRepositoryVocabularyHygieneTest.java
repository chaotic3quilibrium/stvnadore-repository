package org.stvnadore.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnVocabulary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Automated regression test asserting that zero raw typic, facet, or value-track
 * string literals exist in production code outside approved constant definitions.
 */
public class StvnRepositoryVocabularyHygieneTest {

    private static final Pattern STRING_LITERAL_PATTERN = Pattern.compile("\"([^\"]*)\"");

    // Canonical tokens that must NOT appear as raw string literals in src/main/java:
    private static final List<String> FORBIDDEN_RAW_TOKENS = List.of(
        // Typic primitives
        StvnVocabulary.TYPE_BOOLEAN,
        StvnVocabulary.TYPE_INT,
        StvnVocabulary.TYPE_FLOAT,
        StvnVocabulary.TYPE_STRING,
        StvnVocabulary.TYPE_TIME_EPOCH,
        StvnVocabulary.TYPE_DATE_TIME,
        StvnVocabulary.TYPE_SEQ,
        StvnVocabulary.TYPE_SET,
        StvnVocabulary.TYPE_MAP,
        StvnVocabulary.TYPE_TUPLE,
        StvnVocabulary.TYPE_OPTION,
        StvnVocabulary.TYPE_EITHER,
        StvnVocabulary.TYPE_UNION,
        StvnVocabulary.TYPE_ENUM,

        // Structural keywords
        StvnVocabulary.KEYWORD_DEFS,
        StvnVocabulary.KEYWORD_TYPE,
        StvnVocabulary.KEYWORD_BODY,
        StvnVocabulary.KEYWORD_INCLUDE,
        StvnVocabulary.KEYWORD_PACKAGE,
        StvnVocabulary.KEYWORD_USE,

        // Value tokens
        StvnVocabulary.VAL_TRUE,
        StvnVocabulary.VAL_FALSE,
        StvnVocabulary.VAL_SOME,
        StvnVocabulary.VAL_NONE,
        StvnVocabulary.VAL_LEFT,
        StvnVocabulary.VAL_RIGHT,

        // Facet keywords
        StvnVocabulary.FACET_KW_UNSIGNED,
        StvnVocabulary.FACET_KW_EXACT,
        StvnVocabulary.FACET_KW_INVERTIBLE,
        StvnVocabulary.FACET_KW_PRESERVE_INDENT,
        StvnVocabulary.FACET_KW_OFFSET,
        StvnVocabulary.FACET_KW_ZONED,
        StvnVocabulary.FACET_KW_AUDITED,
        StvnVocabulary.FACET_KW_EQUATABLE,
        StvnVocabulary.FACET_KW_COMPARABLE,
        StvnVocabulary.FACET_KW_SCALE_S,
        StvnVocabulary.FACET_KW_SCALE_MS,
        StvnVocabulary.FACET_KW_SCALE_US,
        StvnVocabulary.FACET_KW_SCALE_NS,
        StvnVocabulary.FACET_KW_SIZE,
        StvnVocabulary.FACET_KW_MIN_SIZE,
        StvnVocabulary.FACET_KW_MAX_SIZE,
        StvnVocabulary.FACET_KW_MIN_INCL,
        StvnVocabulary.FACET_KW_MIN_EXCL,
        StvnVocabulary.FACET_KW_MAX_EXCL,
        StvnVocabulary.FACET_KW_MAX_INCL,
        StvnVocabulary.FACET_KW_REGEX,
        StvnVocabulary.FACET_KW_FILTER_INCL,
        StvnVocabulary.FACET_KW_FILTER_EXCL,
        StvnVocabulary.FACET_KW_STRIP,

        // Obsolete 1.x compound tokens
        ":Int32", ":Int64", ":Uint8", ":Uint16", ":Uint32", ":Uint64",
        ":Float32", ":Float64", ":StringFixed", ":StringNonEmpty",
        ":SeqNonEmpty", ":MapInv"
    );

    @Test
    @DisplayName("HYGIENE-01: Zero raw typic, facet, or obsolete 1.x literals in src/main/java")
    void testZeroRawStringLiteralsInMainJava() throws IOException {
        Path mainSrc = Paths.get("src/main/java");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(mainSrc)) {
            List<Path> javaFiles = stream.filter(Files::isRegularFile)
                                         .filter(p -> p.toString().endsWith(".java"))
                                         .toList();

            for (Path file : javaFiles) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    // Skip comments and imports
                    if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") || line.startsWith("import ")) {
                        continue;
                    }

                    Matcher matcher = STRING_LITERAL_PATTERN.matcher(line);
                    while (matcher.find()) {
                        String literal = matcher.group(1);
                        for (String forbidden : FORBIDDEN_RAW_TOKENS) {
                            if (literal.equals(forbidden) ||
                                literal.contains(forbidden + " ") ||
                                literal.contains(" " + forbidden) ||
                                literal.contains(forbidden + "(") ||
                                literal.contains(forbidden + "[")) {
                                violations.add(file.getFileName() + ":" + (i + 1) + " contains raw literal: \"" + literal + "\" (violates token hygiene; use StvnVocabulary)");
                            }
                        }
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(), "Found raw token string literals in src/main/java:\n" + String.join("\n", violations));
    }
}

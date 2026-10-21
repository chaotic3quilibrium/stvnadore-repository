package org.stvnadore.repository.infrastructure;

import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnValue;

import java.util.Optional;

/**
 * Packaging utility for wrapping and unwrapping STVN CAS envelope documents.
 */
public class StvnCasPackager {

    private StvnCasPackager() {
        // Utility class, non-instantiable
    }

    /**
     * Packages raw schema source text into a canonical STVN CAS envelope document.
     *
     * @param schemaName nominal schema identifier
     * @param casHash 64-character SHA-256 digest hex string
     * @param sourceText raw STVN schema source content
     * @return formatted STVN CAS envelope document string
     */
    public static String packageEnvelope(String schemaName, String casHash, String sourceText) {
        String tag = "SHA256-" + casHash;
        String targetName = schemaName;
        if (targetName.endsWith(".stvn_inclf")) {
            targetName = targetName.substring(0, targetName.length() - ".stvn_inclf".length());
        }
        if (!targetName.endsWith(".stvn_d")) {
            targetName = targetName + ".stvn_d";
        }
        return "{:meta{#name \"" + casHash + "\"#domain \"stvn_cas\"#kind #BODY}" +
               StvnVocabulary.KEYWORD_DEFS + "{:SchemaName " + StvnVocabulary.TYPE_STRING + " " +
               ":StvnDef{" + StvnVocabulary.FACET_KW_PRESERVE_INDENT + " " + StvnVocabulary.VAL_TRUE_SHORT + "}" + StvnVocabulary.TYPE_STRING + "}" +
               StvnVocabulary.KEYWORD_TYPE + " " + StvnVocabulary.TYPE_TUPLE + "(:SchemaName :StvnDef)" +
               StvnVocabulary.KEYWORD_BODY + " (\"" + targetName + "\" \"\"\"[" + tag + "]\n" +
               sourceText + "\n" +
               "[" + tag + "]\"\"\")}";
    }

    /**
     * Unpacks raw inner schema source code from an STVN CAS envelope document.
     *
     * @param envelopeText the envelope document text
     * @return Optional containing the raw inner source code if valid, empty otherwise
     */
    public static Optional<String> unpackSourceText(String envelopeText) {
        try {
            Optional<StvnValue> compiled = StvnCompiler.compilePayload(envelopeText, null, StvnParserConfig.SYSTEM_STRICT);
            if (compiled.isPresent() && compiled.get() instanceof StvnValue.StvnTuple tuple) {
                if (tuple.elements().size() >= 2 && tuple.elements().get(1) instanceof StvnValue.StvnString sourceVal) {
                    return Optional.of(sourceVal.value().trim());
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }
}

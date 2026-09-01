package org.stvnadore.repository.infrastructure;

import org.stvnadore.core.StvnCompiler;
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
        return "{\n" +
               "  :defs {\n" +
               "    :SchemaName :String\n" +
               "    :StvnInclf {#preserveIndent #T} :String\n" +
               "  }  \n" +
               "  :type :Tuple(:SchemaName :StvnInclf)\n" +
               "  :body (\n" +
               "    \"" + schemaName + "\"\n" +
               "    \"\"\"->[" + tag + "]\n" +
               sourceText + "\n" +
               "    [" + tag + "]\"\"\"\n" +
               "  )\n" +
               "}";
    }

    /**
     * Unpacks raw inner schema source code from an STVN CAS envelope document.
     *
     * @param envelopeText the envelope document text
     * @return Optional containing the raw inner source code if valid, empty otherwise
     */
    public static Optional<String> unpackSourceText(String envelopeText) {
        try {
            Optional<StvnValue> compiled = StvnCompiler.compile(envelopeText);
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
package org.stvnadore.repository.infrastructure;

import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ast.StvnDocument;
import org.stvnadore.core.ir.StvnValue;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class StvnCasPackagerTest {

    @Test
    public void testPackageEnvelopeCorrectStructureAndParsing() {
        String schemaName = "user-profile.stvn_d";
        String casHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        String sourceText = "{\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";

        // Package the envelope
        String envelope = StvnCasPackager.packageEnvelope(schemaName, casHash, sourceText);

        // Verify it contains the required headers and markers
        assertTrue(envelope.contains(":meta{#name \"" + casHash + "\"#domain \"stvn_cas\"#kind #BODY}"));
        assertTrue(envelope.contains(":defs{"));
        assertTrue(envelope.contains(":SchemaName :String"));
        assertTrue(envelope.contains(":StvnDef{" + StvnVocabulary.FACET_KW_PRESERVE_INDENT + " " + StvnVocabulary.VAL_TRUE_SHORT + "}:String"));
        assertTrue(envelope.contains(":type :Tuple(:SchemaName :StvnDef)"));
        assertTrue(envelope.contains(":body ("));
        assertTrue(envelope.contains("\"user-profile.stvn_d\""));
        assertTrue(envelope.contains("\"\"\"[SHA256-ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad]"));
        assertTrue(envelope.contains("[SHA256-ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad]\"\"\""));

        // Compile and parse via the upstream compiler to verify validity under SYSTEM_STRICT
        Optional<StvnValue> compiledOpt = StvnCompiler.compilePayload(envelope, null, StvnParserConfig.SYSTEM_STRICT);
        assertTrue(compiledOpt.isPresent());

        StvnValue value = compiledOpt.get();
        assertTrue(value instanceof StvnValue.StvnTuple);

        StvnValue.StvnTuple tuple = (StvnValue.StvnTuple) value;
        assertEquals(2, tuple.elements().size());

        assertTrue(tuple.elements().get(0) instanceof StvnValue.StvnString);
        assertTrue(tuple.elements().get(1) instanceof StvnValue.StvnString);

        StvnValue.StvnString nameVal = (StvnValue.StvnString) tuple.elements().get(0);
        StvnValue.StvnString sourceVal = (StvnValue.StvnString) tuple.elements().get(1);

        assertEquals(schemaName, nameVal.value());
        // Verify sourceText matches (strip trailing whitespaces added by fencing indentation helper)
        assertEquals(sourceText, sourceVal.value().trim());
    }

    @Test
    public void testUnpackSourceTextSuccess() {
        String schemaName = "order-events.stvn_d";
        String casHash = "cafebabedeadbeef0123456789abcdefcafebabedeadbeef0123456789abcdef";
        String sourceText = "{\n  :defs {\n    :OrderId { #unsigned #size 64 } :Int\n  }\n}";

        String envelope = StvnCasPackager.packageEnvelope(schemaName, casHash, sourceText);

        Optional<String> unpackedSource = StvnCasPackager.unpackSourceText(envelope);
        assertTrue(unpackedSource.isPresent());
        assertEquals(sourceText, unpackedSource.get());
    }

    @Test
    public void testUnpackSourceTextInvalidSyntax() {
        Optional<String> unpackedSource = StvnCasPackager.unpackSourceText("invalid syntax");
        assertTrue(unpackedSource.isEmpty());
    }

    @Test
    public void testSystemCatalogEnvelopesTargetingSystemDomainsCompileUnderSystemStrict() {
        String schemaName = "user-profile.stvn_d";
        String casHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        String innerSource = "{\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";
        String tag = "SHA256-" + casHash;

        String[] systemDomains = new String[] { "stvn_cas", "stvn_core", "stvn_ir" };

        for (String domain : systemDomains) {
            String envelope = "{:meta{#name \"" + casHash + "\"#domain \"" + domain + "\"#kind #BODY}" +
                StvnVocabulary.KEYWORD_DEFS + "{:SchemaName " + StvnVocabulary.TYPE_STRING + " " +
                ":StvnDef{" + StvnVocabulary.FACET_KW_PRESERVE_INDENT + " " + StvnVocabulary.VAL_TRUE_SHORT + "}" + StvnVocabulary.TYPE_STRING + "}" +
                StvnVocabulary.KEYWORD_TYPE + " " + StvnVocabulary.TYPE_TUPLE + "(:SchemaName :StvnDef)" +
                StvnVocabulary.KEYWORD_BODY + " (\"" + schemaName + "\" \"\"\"[" + tag + "]\n" +
                innerSource + "\n" +
                "[" + tag + "]\"\"\")}";

            // 1. Without SYSTEM_STRICT, strict user compilation rejects system domain
            StvnCompilationResult<StvnDocument> strictResult = StvnCompiler.compileToResult(envelope, null, StvnParserConfig.STRICT);
            assertTrue(strictResult.hasErrors(), "Strict user compilation must reject reserved system domain: " + domain);
            assertTrue(strictResult.diagnostics().stream().anyMatch(d -> d.message().contains("Reserved system domain prohibited")),
                "Must report ERR_RESERVED_DOMAIN_PROHIBITED diagnostic");

            // 2. Under SYSTEM_STRICT, system catalog envelope compiles cleanly with zero errors
            StvnCompilationResult<StvnDocument> systemResult = StvnCompiler.compileToResult(envelope, null, StvnParserConfig.SYSTEM_STRICT);
            assertFalse(systemResult.hasErrors(), "System catalog envelope must compile cleanly under SYSTEM_STRICT for domain: " + domain);

            // 3. UnpackSourceText must successfully unpack the envelope
            Optional<String> unpacked = StvnCasPackager.unpackSourceText(envelope);
            assertTrue(unpacked.isPresent(), "StvnCasPackager.unpackSourceText must unpack envelope with domain: " + domain);
            assertEquals(innerSource, unpacked.get());
        }
    }
}

package org.stvnadore.repository.infrastructure;

import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class StvnCasPackagerTest {

    @Test
    public void testPackageEnvelopeCorrectStructureAndParsing() {
        String schemaName = "user-profile.stvn_inclf";
        String casHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        String sourceText = "{\n  :defs {\n    :UserId { #unsigned #size 64 } :Int\n  }\n}";

        // Package the envelope
        String envelope = StvnCasPackager.packageEnvelope(schemaName, casHash, sourceText);

        // Verify it contains the required headers and markers
        assertTrue(envelope.contains(":defs {"));
        assertTrue(envelope.contains(":SchemaName :String"));
        assertTrue(envelope.contains(":StvnInclf { #preserveIndent } :String"));
        assertTrue(envelope.contains(":type :Tuple(:SchemaName :StvnInclf)"));
        assertTrue(envelope.contains(":body ("));
        assertTrue(envelope.contains("\"user-profile.stvn_inclf\""));
        assertTrue(envelope.contains("\"\"\"[SHA256-ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad]"));
        assertTrue(envelope.contains("[SHA256-ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad]\"\"\""));

        // Compile and parse via the upstream compiler to verify validity
        Optional<StvnValue> compiledOpt = StvnCompiler.compile(envelope);
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
    public void testUnpackEnvelopeSuccess() {
        String schemaName = "order-events.stvn_inclf";
        String casHash = "cafebabedeadbeef0123456789abcdefcafebabedeadbeef0123456789abcdef";
        String sourceText = "{\n  :defs {\n    :OrderId { #unsigned #size 64 } :Int\n  }\n}";

        String envelope = StvnCasPackager.packageEnvelope(schemaName, casHash, sourceText);

        Optional<StvnCasPackager.EnvelopeContent> unpacked = StvnCasPackager.unpackEnvelope(envelope);
        assertTrue(unpacked.isPresent());
        assertEquals(schemaName, unpacked.get().schemaName());
        assertEquals(sourceText, unpacked.get().sourceText());

        Optional<String> unpackedSource = StvnCasPackager.unpackSourceText(envelope);
        assertTrue(unpackedSource.isPresent());
        assertEquals(sourceText, unpackedSource.get());
    }

    @Test
    public void testUnpackEnvelopeInvalidSyntax() {
        Optional<StvnCasPackager.EnvelopeContent> unpacked = StvnCasPackager.unpackEnvelope("invalid syntax");
        assertTrue(unpacked.isEmpty());

        Optional<String> unpackedSource = StvnCasPackager.unpackSourceText("invalid syntax");
        assertTrue(unpackedSource.isEmpty());
    }
}

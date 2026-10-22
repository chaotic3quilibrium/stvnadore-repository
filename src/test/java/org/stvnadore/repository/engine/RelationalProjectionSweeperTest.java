package org.stvnadore.repository.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.repository.domain.DuplicateIndexException;
import org.stvnadore.repository.domain.SchemaMetadata;
import org.stvnadore.repository.infrastructure.FileSystemCasStorage;
import org.stvnadore.repository.infrastructure.StvnCasPackager;
import org.stvnadore.repository.ports.CasDirectoryScannerPort;
import org.stvnadore.repository.ports.IndexRepositoryPort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class RelationalProjectionSweeperTest {

    @TempDir
    Path tempCasRoot;

    private FileSystemCasStorage casStorage;
    private IndexRepositoryPort indexRepository;
    private CasDirectoryScannerPort scanner;
    private RelationalProjectionSweeper sweeper;

    @BeforeEach
    public void setUp() {
        casStorage = new FileSystemCasStorage(tempCasRoot);
        indexRepository = mock(IndexRepositoryPort.class);
        scanner = mock(CasDirectoryScannerPort.class);
        sweeper = new RelationalProjectionSweeper(casStorage, indexRepository, scanner, tempCasRoot);
    }

    @Test
    public void testSweeperSkipsAlreadyIndexed() {
        String hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        when(scanner.listAllCasHashes()).thenReturn(List.of(hash));
        when(indexRepository.existsByHash(hash)).thenReturn(true);

        sweeper.run();

        verify(indexRepository, never()).save(any(SchemaMetadata.class), anyString());
    }

    @Test
    public void testSweeperSuccessfullyReconcilesMissing() throws DuplicateIndexException, NoSuchAlgorithmException {
        String schemaName = "user_profile.stvn_d";
        String innerSourceText = """
            {
              :meta {
                #name "user_profile"
                #domain "test"
                #kind #DEFS
              }
              :defs {
                :UserId { #unsigned #size 64 } :Int
                :UserName { #minSize 1 } :String
              }
            }
            """;

        // Compute actual canonical AST hash
        String shapeSig = StvnSchemaFlattener.flatten(Map.of(schemaName, innerSourceText), schemaName);
        byte[] hashBytes = MessageDigest.getInstance("SHA-256").digest(shapeSig.getBytes(StandardCharsets.UTF_8));
        String matchingHash = HexFormat.of().formatHex(hashBytes);

        String envelope = StvnCasPackager.packageEnvelope(schemaName, matchingHash, innerSourceText);
        casStorage.write(matchingHash, envelope.getBytes(StandardCharsets.UTF_8));

        when(scanner.listAllCasHashes()).thenReturn(List.of(matchingHash));
        when(indexRepository.existsByHash(matchingHash)).thenReturn(false);

        sweeper.run();

        verify(indexRepository).save(
            argThat(metadata ->
                metadata.schemaName().equals(schemaName) &&
                metadata.casHash().equals(matchingHash) &&
                metadata.domain().equals("test") &&
                metadata.shapeSignature().contains(":defs")
            ),
            eq(innerSourceText.trim())
        );
    }

    @Test
    public void testSweeperQuarantinesHashMismatch() throws IOException {
        String schemaName = "tampered_schema.stvn_d";
        String innerSourceText = """
            {
              :meta {
                #name "tampered_schema"
                #domain "test"
                #kind #DEFS
              }
              :defs {
                :UserId { #unsigned #size 64 } :Int
              }
            }
            """;
        String fakeHash = "1111111111111111111111111111111111111111111111111111111111111111";

        String envelope = StvnCasPackager.packageEnvelope(schemaName, fakeHash, innerSourceText);
        casStorage.write(fakeHash, envelope.getBytes(StandardCharsets.UTF_8));

        when(scanner.listAllCasHashes()).thenReturn(List.of(fakeHash));
        when(indexRepository.existsByHash(fakeHash)).thenReturn(false);

        sweeper.run();

        // Should NOT save to index
        verify(indexRepository, never()).save(any(SchemaMetadata.class), anyString());

        // File should be moved to .quarantine directory
        Path quarantineDir = tempCasRoot.resolve(".quarantine");
        assertTrue(Files.exists(quarantineDir));
        try (var stream = Files.list(quarantineDir)) {
            List<Path> files = stream.toList();
            assertEquals(1, files.size());
            assertTrue(files.get(0).getFileName().toString().contains("HASH_MISMATCH"));
        }
    }

    @Test
    public void testSweeperQuarantinesCorruptEnvelope() throws IOException {
        String fakeHash = "2222222222222222222222222222222222222222222222222222222222222222";
        String brokenEnvelope = "this is not valid STVN syntax {{{";

        casStorage.write(fakeHash, brokenEnvelope.getBytes(StandardCharsets.UTF_8));

        when(scanner.listAllCasHashes()).thenReturn(List.of(fakeHash));
        when(indexRepository.existsByHash(fakeHash)).thenReturn(false);

        sweeper.run();

        verify(indexRepository, never()).save(any(SchemaMetadata.class), anyString());

        Path quarantineDir = tempCasRoot.resolve(".quarantine");
        assertTrue(Files.exists(quarantineDir));
        try (var stream = Files.list(quarantineDir)) {
            List<Path> files = stream.toList();
            assertEquals(1, files.size());
            assertTrue(files.get(0).getFileName().toString().contains("CORRUPT_ENVELOPE"));
        }
    }

    @Test
    public void testSweeperQuarantinesNonStvnDFilename() throws IOException {
        String schemaName = "legacy_schema.stvn_inclf";
        String innerSourceText = """
            {
              :meta {
                #name "legacy_schema"
                #domain "test"
                #kind #DEFS
              }
              :defs {
                :UserId { #unsigned #size 64 } :Int
              }
            }
            """;
        String fakeHash = "3333333333333333333333333333333333333333333333333333333333333333";

        String tag = "SHA256-" + fakeHash;
        String envelope = "{:meta{#name \"" + fakeHash + "\"#domain \"cas\"#kind #BODY}" +
               ":defs{:SchemaName :String :StvnDef{#preserveIndent #T}:String}" +
               ":type :Tuple(:SchemaName :StvnDef)" +
               ":body (\"" + schemaName + "\" \"\"\"[" + tag + "]\n" +
               innerSourceText + "\n" +
               "[" + tag + "]\"\"\")}";
        casStorage.write(fakeHash, envelope.getBytes(StandardCharsets.UTF_8));

        when(scanner.listAllCasHashes()).thenReturn(List.of(fakeHash));
        when(indexRepository.existsByHash(fakeHash)).thenReturn(false);

        sweeper.run();

        verify(indexRepository, never()).save(any(SchemaMetadata.class), anyString());

        Path quarantineDir = tempCasRoot.resolve(".quarantine");
        assertTrue(Files.exists(quarantineDir));
        try (var stream = Files.list(quarantineDir)) {
            List<Path> files = stream.toList();
            assertEquals(1, files.size());
            assertTrue(files.get(0).getFileName().toString().contains("INVALID_FILENAME_EXTENSION"));
        }
    }

    @Test
    public void testSweeperQuarantinesIllegalIncludes() throws IOException {
        String schemaName = "illegal_include.stvn_d";
        String innerSourceText = """
            {
              :meta {
                #name "illegal_include"
                #domain "test"
                #kind #DEFS
              }
              :defs {
                :include [ "other.stvn_di" ]
                :UserId { #unsigned #size 64 } :Int
              }
            }
            """;
        String fakeHash = "4444444444444444444444444444444444444444444444444444444444444444";

        String envelope = StvnCasPackager.packageEnvelope(schemaName, fakeHash, innerSourceText);
        casStorage.write(fakeHash, envelope.getBytes(StandardCharsets.UTF_8));

        when(scanner.listAllCasHashes()).thenReturn(List.of(fakeHash));
        when(indexRepository.existsByHash(fakeHash)).thenReturn(false);

        sweeper.run();

        verify(indexRepository, never()).save(any(SchemaMetadata.class), anyString());

        Path quarantineDir = tempCasRoot.resolve(".quarantine");
        assertTrue(Files.exists(quarantineDir));
        try (var stream = Files.list(quarantineDir)) {
            List<Path> files = stream.toList();
            assertEquals(1, files.size());
            assertTrue(files.get(0).getFileName().toString().contains("ILLEGAL_INCLUDES_IN_FLAT_SCHEMA"));
        }
    }

    @Test
    public void testSweeperQuarantinesMalformedInnerStructure() throws IOException {
        String schemaName = "body_structure.stvn_d";
        String innerSourceText = """
            {
              :meta {
                #name "body_structure"
                #domain "test"
                #kind #DEFS
              }
              :defs {
                :UserId { #unsigned #size 64 } :Int
              }
              :type :UserId
              :body 100
            }
            """;
        String fakeHash = "5555555555555555555555555555555555555555555555555555555555555555";

        String envelope = StvnCasPackager.packageEnvelope(schemaName, fakeHash, innerSourceText);
        casStorage.write(fakeHash, envelope.getBytes(StandardCharsets.UTF_8));

        when(scanner.listAllCasHashes()).thenReturn(List.of(fakeHash));
        when(indexRepository.existsByHash(fakeHash)).thenReturn(false);

        sweeper.run();

        verify(indexRepository, never()).save(any(SchemaMetadata.class), anyString());

        Path quarantineDir = tempCasRoot.resolve(".quarantine");
        assertTrue(Files.exists(quarantineDir));
        try (var stream = Files.list(quarantineDir)) {
            List<Path> files = stream.toList();
            assertEquals(1, files.size());
            assertTrue(files.get(0).getFileName().toString().contains("INVALID_INNER_AST")
                || files.get(0).getFileName().toString().contains("MALFORMED_INNER_STRUCTURE"));
        }
    }

    @Test
    public void testSweeperQuarantinesEnvelopeWithTabAsInvalidInnerAst() throws IOException {
        String schemaName = "tabbed_schema.stvn_d";
        String innerSourceText = "{\n  :meta {\n    #name \"tabbed_schema\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n\t:defs {\n\t\t:UserId { #unsigned #size 64 } :Int\n\t}\n}";
        String fakeHash = "9999999999999999999999999999999999999999999999999999999999999999";

        String envelope = StvnCasPackager.packageEnvelope(schemaName, fakeHash, innerSourceText);
        casStorage.write(fakeHash, envelope.getBytes(StandardCharsets.UTF_8));

        when(scanner.listAllCasHashes()).thenReturn(List.of(fakeHash));
        when(indexRepository.existsByHash(fakeHash)).thenReturn(false);

        sweeper.run();

        verify(indexRepository, never()).save(any(SchemaMetadata.class), anyString());
        Path quarantineDir = tempCasRoot.resolve(".quarantine");
        assertTrue(Files.exists(quarantineDir));
        Path quarantinedFile = Files.list(quarantineDir).findFirst().orElseThrow();
        assertTrue(quarantinedFile.toString().contains("INVALID_INNER_AST"),
            "Quarantine tag must report INVALID_INNER_AST on raw tab characters");
    }

    @Test
    public void testSweeperReconcilesTemporalPrimitivesWithoutQuarantine() throws DuplicateIndexException, NoSuchAlgorithmException {
        String schemaName = "temporal_reconciliation.stvn_d";
        String innerSourceText = """
            {
              :meta {
                #name "temporal_reconciliation"
                #domain "test"
                #kind #DEFS
              }
              :defs {
                :Timestamp { #ms #minIncl 1000 #maxExcl 2000 } :TimeEpoch
                :EventTime { #offset #minIncl "2026-01-01T00:00:00Z" #maxExcl "2027-01-01T00:00:00Z" } :DateTime
              }
            }
            """;

        String shapeSig = StvnSchemaFlattener.flatten(Map.of(schemaName, innerSourceText), schemaName);
        byte[] hashBytes = MessageDigest.getInstance("SHA-256").digest(shapeSig.getBytes(StandardCharsets.UTF_8));
        String matchingHash = HexFormat.of().formatHex(hashBytes);

        String envelope = StvnCasPackager.packageEnvelope(schemaName, matchingHash, innerSourceText);
        casStorage.write(matchingHash, envelope.getBytes(StandardCharsets.UTF_8));

        when(scanner.listAllCasHashes()).thenReturn(List.of(matchingHash));
        when(indexRepository.existsByHash(matchingHash)).thenReturn(false);

        sweeper.run();

        verify(indexRepository).save(
            argThat(metadata ->
                metadata.schemaName().equals(schemaName) &&
                metadata.casHash().equals(matchingHash) &&
                metadata.domain().equals("test") &&
                metadata.shapeSignature().contains("{#ms #minIncl 1000 #maxExcl 2000}:TimeEpoch") &&
                metadata.shapeSignature().contains("{#offset #minIncl \"2026-01-01T00:00:00Z\" #maxExcl \"2027-01-01T00:00:00Z\"}:DateTime")
            ),
            eq(innerSourceText.trim())
        );

        Path quarantineDir = tempCasRoot.resolve(".quarantine");
        assertTrue(!Files.exists(quarantineDir) || isDirEmpty(quarantineDir),
            "Temporal schema must reconcile without false-positive quarantines");
    }

    @Test
    public void testSweeperReconcilesEnvelopeWithStvnCasSystemDomain() throws IOException, NoSuchAlgorithmException {
        String schemaName = "sys_profile.stvn_d";
        String innerSourceText = """
            {
              :meta {
                #name "sys_profile"
                #domain "test"
                #kind #DEFS
              }
              :defs {
                :UserId { #unsigned #size 64 } :Int
              }
            }
            """;

        String shapeSig = StvnSchemaFlattener.flatten(Map.of(schemaName, innerSourceText), schemaName);
        byte[] hashBytes = MessageDigest.getInstance("SHA-256").digest(shapeSig.getBytes(StandardCharsets.UTF_8));
        String matchingHash = HexFormat.of().formatHex(hashBytes);

        String tag = "SHA256-" + matchingHash;
        String systemEnvelope = "{:meta{#name \"" + matchingHash + "\"#domain \"stvn_cas\"#kind #BODY}" +
            StvnVocabulary.KEYWORD_DEFS + "{:SchemaName " + StvnVocabulary.TYPE_STRING + " " +
            ":StvnDef{" + StvnVocabulary.FACET_KW_PRESERVE_INDENT + " " + StvnVocabulary.VAL_TRUE_SHORT + "}" + StvnVocabulary.TYPE_STRING + "}" +
            StvnVocabulary.KEYWORD_TYPE + " " + StvnVocabulary.TYPE_TUPLE + "(:SchemaName :StvnDef)" +
            StvnVocabulary.KEYWORD_BODY + " (\"" + schemaName + "\" \"\"\"[" + tag + "]\n" +
            innerSourceText + "\n" +
            "[" + tag + "]\"\"\")}";

        casStorage.write(matchingHash, systemEnvelope.getBytes(StandardCharsets.UTF_8));

        when(scanner.listAllCasHashes()).thenReturn(List.of(matchingHash));
        when(indexRepository.existsByHash(matchingHash)).thenReturn(false);

        sweeper.run();

        verify(indexRepository).save(
            argThat(metadata ->
                metadata.schemaName().equals(schemaName) &&
                metadata.casHash().equals(matchingHash) &&
                metadata.domain().equals("test")
            ),
            eq(innerSourceText.trim())
        );

        Path quarantineDir = tempCasRoot.resolve(".quarantine");
        assertTrue(!Files.exists(quarantineDir) || isDirEmpty(quarantineDir),
            "System domain envelope must reconcile without quarantine");
    }

    @Test
    public void testSweeperAcceptsEnvelopeWithW003WarningsWithoutQuarantine() throws IOException, NoSuchAlgorithmException {
        String schemaName = "tautological_sensor.stvn_d";
        String innerSourceText = """
            {
              :meta {
                #name "tautological_sensor"
                #domain "test"
                #kind #DEFS
              }
              :defs {
                :SensorReading { #unsigned #size 16 #minIncl 0 } :Int
              }
            }
            """;
        // Derive expected canonical CAS hash (which normalizes away #minIncl 0)
        String shapeSignature = StvnSchemaFlattener.flatten(Map.of(schemaName, innerSourceText), schemaName);
        byte[] hashBytes = MessageDigest.getInstance("SHA-256").digest(shapeSignature.getBytes(StandardCharsets.UTF_8));
        String expectedHash = HexFormat.of().formatHex(hashBytes);

        String envelope = StvnCasPackager.packageEnvelope(schemaName, expectedHash, innerSourceText);
        casStorage.write(expectedHash, envelope.getBytes(StandardCharsets.UTF_8));

        when(scanner.listAllCasHashes()).thenReturn(List.of(expectedHash));
        when(indexRepository.existsByHash(expectedHash)).thenReturn(false);

        sweeper.run();

        // Verify indexRepository.save() was called with normalized shape signature and expected CAS hash
        verify(indexRepository, times(1)).save(
            argThat(metadata -> metadata.schemaName().equals(schemaName)
                && metadata.casHash().equals(expectedHash)
                && !metadata.shapeSignature().contains("#minIncl 0")),
            eq(innerSourceText.trim())
        );

        Path quarantineDir = tempCasRoot.resolve(".quarantine");
        assertTrue(!Files.exists(quarantineDir) || isDirEmpty(quarantineDir),
            "Envelope with W003 warning must not be quarantined");
    }

    private static boolean isDirEmpty(Path path) {
        try (var stream = Files.list(path)) {
            return stream.findAny().isEmpty();
        } catch (IOException e) {
            return true;
        }
    }
}

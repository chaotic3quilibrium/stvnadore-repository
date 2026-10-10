package org.stvnadore.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.javalin.Javalin;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.binary.SchemaIdentityStrategy;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.repository.edge.SchemaPublishHandler;
import org.stvnadore.repository.infrastructure.ConcurrentHashMapCache;
import org.stvnadore.repository.infrastructure.DatabaseInitializer;
import org.stvnadore.repository.infrastructure.FileSystemCasStorage;
import org.stvnadore.repository.infrastructure.JdbcIndexRepository;
import org.stvnadore.repository.ports.CasStoragePort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive integration regression test suite verifying the STVN Nominal Bijectivity Invariant
 * ($1:1$ Law), Modern Canonical Types (MCT 2.0.0) CAS preimage divergence, pre-write conflict rejection
 * disk invariance, Strategy 0x7 binary wire content negotiation, and diamond include resolution.
 */
@NullMarked
public class StvnCasBijectivityRegressionTest {

    private static Path tempCasRoot = Paths.get("target/temp_cas_bijectivity");
    private static Javalin app = Javalin.create();
    private static int port;
    private static HikariDataSource dataSource = new HikariDataSource();
    private static CasStoragePort casStorage = new FileSystemCasStorage(tempCasRoot);
    private static HttpClient httpClient = HttpClient.newHttpClient();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void initAll() throws IOException {
        tempCasRoot = Files.createTempDirectory("stvn_bijectivity_cas_");
        casStorage = new FileSystemCasStorage(tempCasRoot);

        String dbName = "stvn_bij_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);

        dataSource = new HikariDataSource(config);
        DatabaseInitializer.initialize(dataSource, true);

        var indexRepo = new JdbcIndexRepository(dataSource);
        var catalogCache = new ConcurrentHashMapCache();
        var engine = new SimpleSchemaRepositoryEngine(casStorage, indexRepo, catalogCache);
        var handler = new SchemaPublishHandler(engine, casStorage);

        app = Javalin.create(cfg -> cfg.http.maxRequestSize = 32_000_000L).start(0);
        handler.configureRoutes(app);
        port = app.port();
        httpClient = HttpClient.newHttpClient();
    }

    @BeforeEach
    void resetState() throws Exception {
        // Clear database records
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM schema_source_audit");
            stmt.execute("DELETE FROM version_catalog");
        }

        // Clean CAS filesystem
        if (Files.exists(tempCasRoot)) {
            try (Stream<Path> stream = Files.walk(tempCasRoot)) {
                stream.filter(p -> !p.equals(tempCasRoot))
                      .sorted(Comparator.reverseOrder())
                      .forEach(p -> {
                          try {
                              Files.deleteIfExists(p);
                          } catch (Exception ignored) {
                          }
                      });
            }
        }
    }

    @AfterAll
    static void tearDownAll() throws IOException {
        if (app != null) {
            app.stop();
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        if (tempCasRoot != null && Files.exists(tempCasRoot)) {
            try (Stream<Path> stream = Files.walk(tempCasRoot)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("BIJECT-01: Bijective CAS lookups across MCT 2.0.0 types (sized ints, temporal scales/modes, sized strings)")
    void testBijectiveCasLookupsAcrossModernCanonicalTypes() throws Exception {
        Map<String, String> schemas = Map.ofEntries(
            // Sized Integers
            Map.entry("schema_int_16.stvn_d", "{\n  :meta {\n    #name \"schema_int_16\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :Val { #size 16 } :Int\n  }\n}"),
            Map.entry("schema_int_32.stvn_d", "{\n  :meta {\n    #name \"schema_int_32\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :Val { #size 32 } :Int\n  }\n}"),
            Map.entry("schema_int_bare.stvn_d", "{\n  :meta {\n    #name \"schema_int_bare\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :Val :Int\n  }\n}"),
            Map.entry("schema_int_unsigned_32.stvn_d", "{\n  :meta {\n    #name \"schema_int_unsigned_32\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :Val { #unsigned #size 32 } :Int\n  }\n}"),

            // Temporal Scales
            Map.entry("schema_time_s.stvn_d", "{\n  :meta {\n    #name \"schema_time_s\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :Ts { #s } :TimeEpoch\n  }\n}"),
            Map.entry("schema_time_ms.stvn_d", "{\n  :meta {\n    #name \"schema_time_ms\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :Ts { #ms } :TimeEpoch\n  }\n}"),
            Map.entry("schema_time_us.stvn_d", "{\n  :meta {\n    #name \"schema_time_us\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :Ts { #us } :TimeEpoch\n  }\n}"),
            Map.entry("schema_time_ns.stvn_d", "{\n  :meta {\n    #name \"schema_time_ns\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :Ts { #ns } :TimeEpoch\n  }\n}"),

            // Temporal Modes
            Map.entry("schema_dt_offset.stvn_d", "{\n  :meta {\n    #name \"schema_dt_offset\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :Dt { #offset } :DateTime\n  }\n}"),
            Map.entry("schema_dt_zoned.stvn_d", "{\n  :meta {\n    #name \"schema_dt_zoned\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :Dt { #zoned } :DateTime\n  }\n}"),
            Map.entry("schema_dt_audited.stvn_d", "{\n  :meta {\n    #name \"schema_dt_audited\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :Dt { #audited } :DateTime\n  }\n}"),

            // Sized Strings
            Map.entry("schema_str_bounded.stvn_d", "{\n  :meta {\n    #name \"schema_str_bounded\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :Str { #minSize 1 #maxSize 20 } :String\n  }\n}"),
            Map.entry("schema_str_bare.stvn_d", "{\n  :meta {\n    #name \"schema_str_bare\"\n    #domain \"test\"\n    #kind #DEFS\n  }\n  :defs {\n    :Str :String\n  }\n}")
        );

        Map<String, String> publishedHashes = new HashMap<>();

        for (Map.Entry<String, String> entry : schemas.entrySet()) {
            String name = entry.getKey();
            String source = entry.getValue();

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/" + name))
                .header("Content-Type", "application/stvn")
                .POST(HttpRequest.BodyPublishers.ofString(source))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, response.statusCode(), "Failed to publish " + name + ": " + response.body());

            JsonNode json = objectMapper.readTree(response.body());
            String casHash = json.get("casHash").asText();
            assertNotNull(casHash);
            assertEquals(64, casHash.length());

            // Verify expected hash matches StvnSchemaFlattener + SHA-256
            String shape = StvnSchemaFlattener.flatten(Map.of(name, source), name);
            byte[] expectedHashBytes = MessageDigest.getInstance("SHA-256").digest(shape.getBytes(StandardCharsets.UTF_8));
            String expectedHash = HexFormat.of().formatHex(expectedHashBytes);
            assertEquals(expectedHash, casHash, "Computed CAS hash mismatch for " + name);

            publishedHashes.put(name, casHash);
        }

        // Invariant 1: Mutually distinct CAS hashes (No preimage semantic collapse)
        Set<String> distinctHashes = new HashSet<>(publishedHashes.values());
        assertEquals(schemas.size(), distinctHashes.size(),
            "All schemas must produce mutually distinct CAS hashes (no collisions across 2.0.0 types)");

        // Invariant 2: Concurrent persistence and exact retrieval via GET /api/v1/schemas/cas/{hash}
        for (Map.Entry<String, String> entry : schemas.entrySet()) {
            String name = entry.getKey();
            String source = entry.getValue();
            String casHash = publishedHashes.get(name);

            HttpRequest getRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/cas/" + casHash))
                .header("Accept", "application/stvn")
                .GET()
                .build();

            HttpResponse<String> getResponse = httpClient.send(getRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, getResponse.statusCode(), "Failed to retrieve CAS hash for " + name);
            assertEquals(source.trim(), getResponse.body().trim(), "Retrieved content must match original source for " + name);

            // Invariant 3: Physical CAS file exists in 2/64 layout (<hash[0..2]>/<full_hash_64>.cas.stvn)
            Path shardedFile = tempCasRoot.resolve(casHash.substring(0, 2)).resolve(casHash + ".cas.stvn");
            assertTrue(Files.exists(shardedFile), "Physical CAS envelope must exist at 2/64 path for " + name);
        }
    }

    @Test
    @DisplayName("BIJECT-02: Pre-write conflict rejection and strict physical CAS disk invariance")
    void testPreWriteConflictRejectionAndDiskInvariance() throws Exception {
        String canonicalName = "CanonicalOrder.stvn_d";
        String aliasName = "CanonicalOrder.orders.stvn_d";
        String sharedSource = """
            {
              :meta {
                #name "CanonicalOrder"
                #domain "orders"
                #kind #DEFS
              }
              :defs {
                :package :com/example/orders {
                  :OrderId { #size 64 } :Int
                  :OrderNotes { #maxSize 255 } :String
                }
              }
            }
            """;

        // 1. Initial canonical publication
        HttpRequest req1 = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/" + canonicalName))
            .header("Content-Type", "application/stvn")
            .POST(HttpRequest.BodyPublishers.ofString(sharedSource))
            .build();

        HttpResponse<String> res1 = httpClient.send(req1, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res1.statusCode(), "Canonical publication must return 201 Created");

        // Count physical CAS files on disk
        long baselineCount;
        try (Stream<Path> stream = Files.walk(tempCasRoot)) {
            baselineCount = stream.filter(p -> p.toString().endsWith(".cas.stvn")).count();
        }
        assertEquals(1L, baselineCount, "Exactly 1 CAS file must exist after initial publication");

        // 2. Attempt Alias publication (identical content under new nominal schema name)
        HttpRequest reqAlias = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/" + aliasName))
            .header("Content-Type", "application/stvn")
            .POST(HttpRequest.BodyPublishers.ofString(sharedSource))
            .build();

        HttpResponse<String> resAlias = httpClient.send(reqAlias, HttpResponse.BodyHandlers.ofString());
        assertEquals(409, resAlias.statusCode(), "Alias conflict must return HTTP 409 Conflict");
        assertTrue(resAlias.body().contains("already registered under schema") || resAlias.body().contains("Conflict"),
            "Response must report alias conflict: " + resAlias.body());

        // Invariant: Physical CAS storage must remain completely invariant (zero writes)
        long afterAliasCount;
        try (Stream<Path> stream = Files.walk(tempCasRoot)) {
            afterAliasCount = stream.filter(p -> p.toString().endsWith(".cas.stvn")).count();
        }
        assertEquals(baselineCount, afterAliasCount, "Alias conflict must NEVER persist a duplicate or orphaned CAS envelope");

        // 3. Attempt Mutation publication (different content under existing nominal schema name)
        String mutatedSource = """
            {
              :meta {
                #name "CanonicalOrder"
                #domain "orders"
                #kind #DEFS
              }
              :defs {
                :package :com/example/orders {
                  :OrderId { #size 32 } :Int
                  :OrderNotes { #maxSize 100 } :String
                }
              }
            }
            """;

        HttpRequest reqMutation = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/" + canonicalName))
            .header("Content-Type", "application/stvn")
            .POST(HttpRequest.BodyPublishers.ofString(mutatedSource))
            .build();

        HttpResponse<String> resMutation = httpClient.send(reqMutation, HttpResponse.BodyHandlers.ofString());
        assertEquals(409, resMutation.statusCode(), "Schema mutation must return HTTP 409 Conflict");
        assertTrue(resMutation.body().contains("already exists with hash") || resMutation.body().contains("Mutations are prohibited"),
            "Response must report schema mutation conflict: " + resMutation.body());

        // Invariant: Physical CAS storage must still remain completely invariant
        long afterMutationCount;
        try (Stream<Path> stream = Files.walk(tempCasRoot)) {
            afterMutationCount = stream.filter(p -> p.toString().endsWith(".cas.stvn")).count();
        }
        assertEquals(baselineCount, afterMutationCount, "Mutation conflict must NEVER write to physical CAS storage");
    }

    @Test
    @DisplayName("BIJECT-03: Strategy 0x7 (0x87) binary wire content negotiation and binary blob passthrough")
    void testStrategy0x7BinaryContentNegotiationAndPassthrough() throws Exception {
        String schemaName = "DeviceTelemetry.stvn_d";
        String schemaSource = """
            {
              :meta {
                #name "DeviceTelemetry"
                #domain "iot"
                #kind #DEFS
              }
              :defs {
                :package :com/example/iot {
                  :DeviceId { #unsigned #size 32 } :Int
                  :Reading { #exact } :Float
                }
              }
            }
            """;

        // 1. Publish schema via text endpoint
        HttpRequest pubReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/" + schemaName))
            .header("Content-Type", "application/stvn")
            .POST(HttpRequest.BodyPublishers.ofString(schemaSource))
            .build();

        HttpResponse<String> pubRes = httpClient.send(pubReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, pubRes.statusCode());

        JsonNode json = objectMapper.readTree(pubRes.body());
        String casHash = json.get("casHash").asText();
        byte[] expectedHashBytes = HexFormat.of().parseHex(casHash);

        // 2. Fetch CAS payload requesting binary wire envelope Accept: application/stvn-bin
        HttpRequest binReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/cas/" + casHash))
            .header("Accept", "application/stvn-bin")
            .GET()
            .build();

        HttpResponse<byte[]> binRes = httpClient.send(binReq, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, binRes.statusCode());
        assertTrue(binRes.headers().firstValue("Content-Type").orElse("").contains("application/stvn-bin"),
            "Content-Type header must indicate application/stvn-bin");

        byte[] wireBytes = binRes.body();
        assertTrue(wireBytes.length >= 41, "Binary payload must include 5-byte header, 32-byte hash, payload, and 4-byte CRC-32C trailer");

        // Verify Magic Bytes 'S', 'T', 'V', 'N'
        assertEquals((byte) 'S', wireBytes[0]);
        assertEquals((byte) 'T', wireBytes[1]);
        assertEquals((byte) 'V', wireBytes[2]);
        assertEquals((byte) 'N', wireBytes[3]);

        // Verify Strategy 0x7 Control Byte (0x87: Bit 7 set = CRC-32C trailer, identityCode = 7)
        assertEquals((byte) 0x87, wireBytes[4], "Control byte must be 0x87 (Strategy 0x7 ExplicitSha256 with CRC-32C trailer)");

        // Verify embedded 32-byte SHA-256 CAS address (offset 6 after Byte 5 MetaControlByte)
        byte[] embeddedHash = Arrays.copyOfRange(wireBytes, 6, 38);
        assertArrayEquals(expectedHashBytes, embeddedHash, "Embedded 32-byte hash must match schema CAS address");

        // 3. Verify decoding via StvnBinaryDecoder.openStrict
        ByteBuffer buffer = ByteBuffer.wrap(wireBytes);
        StvnBinaryDecoder.RootPointer root = StvnBinaryDecoder.openStrict(
            buffer,
            new SchemaIdentityStrategy.ExplicitSha256(expectedHashBytes)
        );
        assertNotNull(root, "RootPointer must be successfully decoded");
        assertTrue(root.context().identityStrategy().isPresent());
        assertInstanceOf(SchemaIdentityStrategy.ExplicitSha256.class, root.context().identityStrategy().get());

        // 4. Verify Strategy 0x7 binary artifact upload is rejected with HTTP 422 under Strategy 0x8 whitelist
        byte[] distinctHashBytes = new byte[32];
        Arrays.fill(distinctHashBytes, (byte) 0xAA);
        byte[] artifactBytes = wireBytes.clone();
        System.arraycopy(distinctHashBytes, 0, artifactBytes, 6, 32);

        // Recalculate CRC-32C trailer for artifactBytes
        CRC32C crc = new CRC32C();
        crc.update(artifactBytes, 0, artifactBytes.length - 4);
        int crcVal = (int) crc.getValue();
        artifactBytes[artifactBytes.length - 4] = (byte) (crcVal & 0xFF);
        artifactBytes[artifactBytes.length - 3] = (byte) ((crcVal >>> 8) & 0xFF);
        artifactBytes[artifactBytes.length - 2] = (byte) ((crcVal >>> 16) & 0xFF);
        artifactBytes[artifactBytes.length - 1] = (byte) ((crcVal >>> 24) & 0xFF);

        String artifactName = "telemetry_artifact.stvn_bd";
        HttpRequest uploadReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/artifacts/binary/" + artifactName))
            .POST(HttpRequest.BodyPublishers.ofByteArray(artifactBytes))
            .build();

        HttpResponse<String> uploadRes = httpClient.send(uploadReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(422, uploadRes.statusCode(), "Strategy 0x7 binary upload must be rejected with HTTP 422: " + uploadRes.body());
        assertTrue(uploadRes.body().contains("ERR_UNSUPPORTED_STRATEGY"));

        // 5. Verify uploading Strategy 0x8 binary schema returns HTTP 201 and re-upload returns HTTP 200 IdempotentCollision
        String binarySchemaText = """
            {
              :meta {
                #name "BinaryDeviceTelemetry"
                #domain "iot"
                #kind #DEFS
              }
              :defs {
                :package :com/example/iot {
                  :DeviceId { #unsigned #size 32 } :Int
                  :Reading { #exact } :Float
                }
              }
            }
            """;
        byte[] valid0x8Payload = createBinarySchemaPayload("BinaryDeviceTelemetry", "iot", binarySchemaText, true, 0, 0);

        HttpRequest firstUploadReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/artifacts/binary/BinaryDeviceTelemetry.stvn_bd"))
            .POST(HttpRequest.BodyPublishers.ofByteArray(valid0x8Payload))
            .build();

        HttpResponse<String> firstUploadRes = httpClient.send(firstUploadReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, firstUploadRes.statusCode(), "Initial upload must return HTTP 201: " + firstUploadRes.body());

        JsonNode firstJson = objectMapper.readTree(firstUploadRes.body());
        String binaryCasHash = firstJson.get("casHash").asText();

        HttpRequest idempUploadReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/artifacts/binary/BinaryDeviceTelemetry.stvn_bd"))
            .POST(HttpRequest.BodyPublishers.ofByteArray(valid0x8Payload))
            .build();

        HttpResponse<String> idempUploadRes = httpClient.send(idempUploadReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, idempUploadRes.statusCode(), "Idempotent upload must return HTTP 200: " + idempUploadRes.body());

        JsonNode idempJson = objectMapper.readTree(idempUploadRes.body());
        assertEquals(binaryCasHash, idempJson.get("casHash").asText(), "Canonical CAS hash of idempotent upload must match");

        // 6. Verify distinct Strategy 0x8 binary schema upload returns HTTP 201 and roundtrips
        String distinctSchema = """
            {
              :meta {
                #name "SensorTelemetry"
                #domain "iot"
                #kind #DEFS
              }
              :defs {
                :package :com/example/iot {
                  :SensorId { #unsigned #size 64 } :Int
                }
              }
            }
            """;
        byte[] distinctPayload = createBinarySchemaPayload("SensorTelemetry", "iot", distinctSchema, true, 0, 0);

        String distinctName = "SensorTelemetry.stvn_bd";
        HttpRequest uploadDistinctReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/artifacts/binary/" + distinctName))
            .POST(HttpRequest.BodyPublishers.ofByteArray(distinctPayload))
            .build();

        HttpResponse<String> uploadDistinctRes = httpClient.send(uploadDistinctReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, uploadDistinctRes.statusCode(), "Distinct Strategy 0x8 upload must return HTTP 201: " + uploadDistinctRes.body());

        JsonNode distinctJson = objectMapper.readTree(uploadDistinctRes.body());
        String distinctCasHash = distinctJson.get("casHash").asText();

        // Retrieve raw binary artifact from CAS endpoint with Accept: application/stvn-bin
        HttpRequest fetchArtifactReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/cas/" + distinctCasHash))
            .header("Accept", "application/stvn-bin")
            .GET()
            .build();

        HttpResponse<byte[]> fetchArtifactRes = httpClient.send(fetchArtifactReq, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, fetchArtifactRes.statusCode());
        assertTrue(fetchArtifactRes.headers().firstValue("Content-Type").orElse("").contains("application/stvn-bin"));
        assertArrayEquals(distinctPayload, fetchArtifactRes.body(), "Stored binary artifact must be streamed back byte-for-byte");
    }

    @Test
    @DisplayName("BIJECT-04: Transitive diamond include graph flattens and publishes without NamespaceCollisionException")
    void testDiamondIncludeModularSchemaFlatteningAndRegistration() throws Exception {
        String dSource = """
            {
              :meta {
                #name "D"
                #domain "diamond"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :package :org/stvnadore/diamond/core {
                  :EntityId { #size 64 } :Int
                  :CreationEpoch { #ms } :TimeEpoch
                }
              }
            }
            """;

        String bSource = """
            {
              :meta {
                #name "B"
                #domain "diamond"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :include [ "D.stvn_di" ]
                :package :org/stvnadore/diamond/left {
                  :LeftPayload :Tuple( :org/stvnadore/diamond/core/EntityId :String )
                }
              }
            }
            """;

        String cSource = """
            {
              :meta {
                #name "C"
                #domain "diamond"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :include [ "D.stvn_di" ]
                :package :org/stvnadore/diamond/right {
                  :RightPayload :Tuple( :org/stvnadore/diamond/core/EntityId :Boolean )
                }
              }
            }
            """;

        String aSource = """
            {
              :meta {
                #name "DiamondAggregate"
                #domain "diamond"
                #kind #DEFS
              }
              :defs {
                :include [ "B.stvn_di" "C.stvn_di" ]
                :package :org/stvnadore/diamond/root {
                  :DiamondAggregate :Tuple( :org/stvnadore/diamond/left/LeftPayload :org/stvnadore/diamond/right/RightPayload :org/stvnadore/diamond/core/CreationEpoch )
                }
              }
            }
            """;

        Map<String, String> workspace = Map.of(
            "D.stvn_di", dSource,
            "B.stvn_di", bSource,
            "C.stvn_di", cSource,
            "A.stvn_di", aSource
        );

        // 1. Flatten diamond dependency graph
        String flattened = StvnSchemaFlattener.flatten(workspace, "A.stvn_di");
        assertNotNull(flattened);
        assertTrue(flattened.contains(":org/stvnadore/diamond/core/EntityId"));
        assertTrue(flattened.contains(":org/stvnadore/diamond/core/CreationEpoch"));
        assertTrue(flattened.contains(":org/stvnadore/diamond/left/LeftPayload"));
        assertTrue(flattened.contains(":org/stvnadore/diamond/right/RightPayload"));
        assertTrue(flattened.contains(":org/stvnadore/diamond/root/DiamondAggregate"));

        // 2. Publish flattened modular schema to repository
        String publishedName = "DiamondAggregate.stvn_d";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/" + publishedName))
            .header("Content-Type", "application/stvn")
            .POST(HttpRequest.BodyPublishers.ofString(flattened))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), "Diamond flattened schema publication must succeed with 201 Created: " + response.body());

        JsonNode json = objectMapper.readTree(response.body());
        String casHash = json.get("casHash").asText();
        assertNotNull(casHash);
        assertEquals(64, casHash.length());

        // 3. Retrieve raw text
        HttpRequest getReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/cas/" + casHash))
            .header("Accept", "application/stvn")
            .GET()
            .build();

        HttpResponse<String> getRes = httpClient.send(getReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getRes.statusCode());
        assertEquals(flattened.trim(), getRes.body().trim());

        // 4. Retrieve binary Strategy 0x7 envelope
        HttpRequest binReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/cas/" + casHash))
            .header("Accept", "application/stvn-bin")
            .GET()
            .build();

        HttpResponse<byte[]> binRes = httpClient.send(binReq, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, binRes.statusCode());
        byte[] bytes = binRes.body();
        assertEquals((byte) 0x87, bytes[4]);

        byte[] expectedHashBytes = HexFormat.of().parseHex(casHash);
        StvnBinaryDecoder.RootPointer root = StvnBinaryDecoder.openStrict(
            ByteBuffer.wrap(bytes),
            new SchemaIdentityStrategy.ExplicitSha256(expectedHashBytes)
        );
        assertNotNull(root);
    }

    @Test
    @DisplayName("BIJECT-05: Multi-tenant nominal namespace isolation guarantees distinct CAS addresses")
    void testMultiTenantNamespaceIsolation() throws Exception {
        String tenantASource = """
            {
              :meta {
                #name "tenant_alpha"
                #domain "alpha"
                #kind #DEFS
              }
              :defs {
                :package :tenant/alpha {
                  :Token :Int
                }
              }
            }
            """;

        String tenantBSource = """
            {
              :meta {
                #name "tenant_beta"
                #domain "beta"
                #kind #DEFS
              }
              :defs {
                :package :tenant/beta {
                  :Token :Int
                }
              }
            }
            """;

        // Publish Tenant Alpha
        HttpResponse<String> resA = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/tenant_alpha.stvn_d"))
                .header("Content-Type", "application/stvn")
                .POST(HttpRequest.BodyPublishers.ofString(tenantASource))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(201, resA.statusCode(), "Tenant alpha publish failed: " + resA.body());
        String hashA = objectMapper.readTree(resA.body()).get("casHash").asText();

        // Publish Tenant Beta
        HttpResponse<String> resB = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/tenant_beta.stvn_d"))
                .header("Content-Type", "application/stvn")
                .POST(HttpRequest.BodyPublishers.ofString(tenantBSource))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(201, resB.statusCode(), "Tenant beta publish failed: " + resB.body());
        String hashB = objectMapper.readTree(resB.body()).get("casHash").asText();

        assertNotEquals(hashA, hashB, "Distinct tenant namespaces must produce divergent CAS hashes");

        // Both schemas can be independently resolved and retrieved
        HttpResponse<String> getA = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/cas/" + hashA))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, getA.statusCode());
        assertTrue(getA.body().contains(":tenant/alpha"));

        HttpResponse<String> getB = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/cas/" + hashB))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, getB.statusCode());
        assertTrue(getB.body().contains(":tenant/beta"));
    }

    private static byte[] createBinarySchemaPayload(String name, String domain, String schemaText, boolean validDefs, int typeOffset, int bodyOffset) {
        byte[] textBytes = schemaText.getBytes(StandardCharsets.UTF_8);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] domainBytes = domain.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(512 + textBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(new byte[]{'S', 'T', 'V', 'N', (byte) 0x88});
        // Byte 5: MetaControlByte (KIND=#DEFS (0x01), HAS_NAME (0x04), HAS_DOMAIN (0x08) -> 0x0D)
        buf.put((byte) 0x0D);
        buf.put((byte) nameBytes.length);
        buf.put(nameBytes);
        buf.put((byte) domainBytes.length);
        buf.put(domainBytes);
        // Strategy 0x8 length prefix and text payload
        buf.putInt(textBytes.length - 1);
        buf.put(textBytes);
        buf.put((byte) 0x00); // 1-byte offset flag
        int payloadStart = buf.position();
        int arenaOffset = payloadStart + 3;
        buf.put((byte) (validDefs ? arenaOffset : 0));
        buf.put((byte) typeOffset);
        buf.put((byte) bodyOffset);
        buf.put((byte) 0x42); // dummy defs payload byte at arenaOffset

        int payloadLen = buf.position();
        CRC32C crc = new CRC32C();
        crc.update(ByteBuffer.wrap(buf.array(), 0, payloadLen));
        buf.putInt((int) crc.getValue());

        byte[] result = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, result, 0, result.length);
        return result;
    }
}

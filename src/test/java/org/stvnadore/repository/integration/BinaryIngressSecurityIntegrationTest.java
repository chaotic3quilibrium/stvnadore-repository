package org.stvnadore.repository.integration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.javalin.Javalin;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.repository.SimpleSchemaRepositoryEngine;
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
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security perimeter integration test verifying zero-trust ingress stream verification.
 * Confirms that corrupted CRC-32C streams, truncated buffers, and sentinel 0x7 payloads
 * are rejected with HTTP 422 and never committed to storage.
 */
@NullMarked
public class BinaryIngressSecurityIntegrationTest {

    private static Path tempCasRoot = Paths.get("target/temp_cas_sec");
    private static Javalin app = Javalin.create();
    private static int port;
    private static HikariDataSource dataSource = new HikariDataSource();
    private static CasStoragePort casStorage = new FileSystemCasStorage(tempCasRoot);
    private static HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeAll
    static void initAll() throws IOException {
        tempCasRoot = Files.createTempDirectory("stvn_security_test_cas_");
        casStorage = new FileSystemCasStorage(tempCasRoot);

        String dbName = "stvn_sec_" + UUID.randomUUID().toString().replace("-", "");
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

        app = Javalin.create().start(0);
        handler.configureRoutes(app);
        port = app.port();
        httpClient = HttpClient.newHttpClient();
    }

    @org.junit.jupiter.api.BeforeEach
    void cleanCasRoot() throws IOException {
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
    @DisplayName("SEC-01: Tampered binary payload with CRC-32C trailer rejected with HTTP 422 and zero disk persistence")
    void testTamperedPayloadRejectedWithoutPersistence() throws Exception {
        Path tamperedFixture = Paths.get("target/test-classes/fixtures/syntax/invalid/scalars/binary_crc32c_payload_tampered.stvn_bin");
        byte[] payload = Files.readAllBytes(tamperedFixture);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/tampered_test"))
            .header("Content-Type", "application/stvn-bin")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("CRC-32C trailer mismatch"),
            "Response body must report CRC-32C trailer mismatch: " + response.body());

        // Verify Zero-Trust Invariant: Nothing committed to physical CAS storage
        try (Stream<Path> stream = Files.walk(tempCasRoot)) {
            long casFileCount = stream.filter(p -> p.toString().endsWith(".stvn_cas")).count();
            assertEquals(0, casFileCount, "Tampered payload must NEVER be persisted to CAS storage");
        }
    }

    @Test
    @DisplayName("SEC-02: Truncated binary payload below 9 bytes rejected with HTTP 422")
    void testTruncatedPayloadRejected() throws Exception {
        Path truncatedFixture = Paths.get("target/test-classes/fixtures/syntax/invalid/scalars/binary_crc32c_truncated.stvn_bin");
        byte[] payload = Files.readAllBytes(truncatedFixture);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/truncated_test"))
            .header("Content-Type", "application/stvn-bin")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("Buffer too small for STVN binary with CRC-32C trailer"),
            "Response body must report buffer size violation: " + response.body());

        try (Stream<Path> stream = Files.walk(tempCasRoot)) {
            long casFileCount = stream.filter(p -> p.toString().endsWith(".stvn_cas")).count();
            assertEquals(0, casFileCount, "Truncated payload must NEVER be persisted to CAS storage");
        }
    }

    @Test
    @DisplayName("SEC-03: Strategy Sentinel 0x7 rejected fail-fast with HTTP 422")
    void testSentinelStrategy0x7Rejected() throws Exception {
        Path sentinelFixture = Paths.get("target/test-classes/fixtures/syntax/invalid/scalars/binary_strategy_sentinel_0x7.stvn_bin");
        byte[] payload = Files.readAllBytes(sentinelFixture);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/sentinel_test"))
            .header("Content-Type", "application/stvn-bin")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("Strategy 0x7 is reserved for multi-byte header extension"),
            "Response body must report strategy 0x7 rejection: " + response.body());

        try (Stream<Path> stream = Files.walk(tempCasRoot)) {
            long casFileCount = stream.filter(p -> p.toString().endsWith(".stvn_cas")).count();
            assertEquals(0, casFileCount, "Sentinel payload must NEVER be persisted to CAS storage");
        }
    }

    @Test
    @DisplayName("SEC-04: Dedicated artifact binary upload router rejects tampered CRC-32C payloads")
    void testDedicatedArtifactRouterRejectsTamperedPayload() throws Exception {
        Path tamperedFixture = Paths.get("target/test-classes/fixtures/syntax/invalid/scalars/binary_crc32c_payload_tampered.stvn_bin");
        byte[] payload = Files.readAllBytes(tamperedFixture);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/artifacts/binary/dedicated_tampered_test"))
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("CRC-32C trailer mismatch"));
    }

    @Test
    @DisplayName("SEC-05: Non-ephemeral Strategy 0x0 payload rejected fail-closed with HTTP 422")
    void testStrategy0x0RejectedWithHttp422() throws Exception {
        Path defaultStrategyFixture = Paths.get("target/test-classes/fixtures/syntax/valid/scalars/crc32c_trailer_valid.stvn_bin");
        byte[] payload = Files.readAllBytes(defaultStrategyFixture);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/default_strat.stvn_inclf"))
            .header("Content-Type", "application/stvn-bin")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("ERR_UNSUPPORTED_STRATEGY"), "Must report ERR_UNSUPPORTED_STRATEGY: " + response.body());
        assertTrue(response.body().contains("Ephemeral Strategy (0x8)"), "Must require Ephemeral Strategy: " + response.body());

        try (Stream<Path> stream = Files.walk(tempCasRoot)) {
            long casFileCount = stream.filter(p -> p.toString().endsWith(".stvn_cas")).count();
            assertEquals(0, casFileCount, "Strategy 0x0 payload must NEVER be persisted to CAS storage");
        }
    }

    @Test
    @DisplayName("SEC-06: Non-ephemeral Strategy 0x7 payload rejected fail-closed with HTTP 422")
    void testStrategy0x7RejectedWithHttp422() throws Exception {
        byte[] payload = new byte[]{
            'S', 'T', 'V', 'N',
            (byte) 0x87, // Control byte: CRC flag (0x80) + Strategy 0x7 (ExplicitSha256)
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // 32B dummy SHA-256 hash
            0, 0, 0, 0 // CRC trailer placeholder
        };
        CRC32C crc = new CRC32C();
        crc.update(payload, 0, payload.length - 4);
        int crcVal = (int) crc.getValue();
        payload[payload.length - 4] = (byte) (crcVal & 0xFF);
        payload[payload.length - 3] = (byte) ((crcVal >>> 8) & 0xFF);
        payload[payload.length - 2] = (byte) ((crcVal >>> 16) & 0xFF);
        payload[payload.length - 1] = (byte) ((crcVal >>> 24) & 0xFF);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/strat_0x7.stvn_inclf"))
            .header("Content-Type", "application/stvn-bin")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("ERR_UNSUPPORTED_STRATEGY"));

        try (Stream<Path> stream = Files.walk(tempCasRoot)) {
            long casFileCount = stream.filter(p -> p.toString().endsWith(".stvn_cas")).count();
            assertEquals(0, casFileCount, "Strategy 0x7 payload must NEVER be persisted to CAS storage");
        }
    }

    @Test
    @DisplayName("SEC-07: Non-ephemeral Strategy 0x1 payload rejected fail-closed with HTTP 422")
    void testStrategy0x1RejectedWithHttp422() throws Exception {
        byte[] payload = new byte[]{
            'S', 'T', 'V', 'N',
            (byte) 0x81, // Control byte: CRC flag (0x80) + Strategy 0x1 (UuidV8Hash)
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // 16B UUID
            0, 0, 0, 0 // CRC trailer placeholder
        };
        CRC32C crc = new CRC32C();
        crc.update(payload, 0, payload.length - 4);
        int crcVal = (int) crc.getValue();
        payload[payload.length - 4] = (byte) (crcVal & 0xFF);
        payload[payload.length - 3] = (byte) ((crcVal >>> 8) & 0xFF);
        payload[payload.length - 2] = (byte) ((crcVal >>> 16) & 0xFF);
        payload[payload.length - 1] = (byte) ((crcVal >>> 24) & 0xFF);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/strat_0x1.stvn_inclf"))
            .header("Content-Type", "application/stvn-bin")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("ERR_UNSUPPORTED_STRATEGY"));
    }

    @Test
    @DisplayName("SEC-08: Non-ephemeral Strategy 0xF payload rejected fail-closed with HTTP 422")
    void testStrategy0xFRejectedWithHttp422() throws Exception {
        byte[] payload = new byte[]{
            'S', 'T', 'V', 'N',
            (byte) 0x8F, // Control byte: CRC flag (0x80) + Strategy 0xF (Reserved)
            0, 0, 0, 0, 0,
            0, 0, 0, 0 // CRC trailer placeholder
        };
        CRC32C crc = new CRC32C();
        crc.update(payload, 0, payload.length - 4);
        int crcVal = (int) crc.getValue();
        payload[payload.length - 4] = (byte) (crcVal & 0xFF);
        payload[payload.length - 3] = (byte) ((crcVal >>> 8) & 0xFF);
        payload[payload.length - 2] = (byte) ((crcVal >>> 16) & 0xFF);
        payload[payload.length - 1] = (byte) ((crcVal >>> 24) & 0xFF);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/strat_0xF.stvn_inclf"))
            .header("Content-Type", "application/stvn-bin")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("ERR_UNSUPPORTED_STRATEGY"));
    }

    @Test
    @DisplayName("SEC-09: Valid Ephemeral Strategy 0x8 binary schema accepted and committed with HTTP 201")
    void testValidEphemeralStrategy0x8Accepted() throws Exception {
        String schemaText = "{\n  :defs {\n    :UserRecord {\n      #maxSize 100\n    } :String\n  }\n}";
        byte[] binaryPayload = createBinarySchemaPayload(schemaText, true, 0, 0);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/valid_user.stvn_inclf"))
            .header("Content-Type", "application/stvn-bin")
            .POST(HttpRequest.BodyPublishers.ofByteArray(binaryPayload))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), "Valid binary payload must return HTTP 201: " + response.body());

        try (Stream<Path> stream = Files.walk(tempCasRoot)) {
            long casFileCount = stream.filter(p -> p.toString().endsWith(".stvn_cas")).count();
            assertTrue(casFileCount > 0, "Valid payload must be persisted to CAS storage");
        }
    }

    @Test
    @DisplayName("SEC-10A: Gate 2 RootPointer table non-zero typeOffset rejected with HTTP 422")
    void testGate2NonZeroTypeOffsetRejected() throws Exception {
        String schemaText = "{\n  :defs {\n    :UserRecord :String\n  }\n}";
        byte[] binaryPayload = createBinarySchemaPayload(schemaText, true, 1, 0);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/type_nonzero.stvn_inclf"))
            .header("Content-Type", "application/stvn-bin")
            .POST(HttpRequest.BodyPublishers.ofByteArray(binaryPayload))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("ERR_MALFORMED_SCHEMA_IN_ENVELOPE"), "Must report ERR_MALFORMED_SCHEMA_IN_ENVELOPE: " + response.body());
        assertTrue(response.body().contains("Top-level :type and :body sections are prohibited"), "Must prohibit :type section: " + response.body());

        try (Stream<Path> stream = Files.walk(tempCasRoot)) {
            long casFileCount = stream.filter(p -> p.toString().endsWith(".stvn_cas")).count();
            assertEquals(0, casFileCount, "Invalid Gate 2 payload must NEVER be persisted to CAS storage");
        }
    }

    @Test
    @DisplayName("SEC-10B: Gate 2 RootPointer table non-zero bodyOffset rejected with HTTP 422")
    void testGate2NonZeroBodyOffsetRejected() throws Exception {
        String schemaText = "{\n  :defs {\n    :UserRecord :String\n  }\n}";
        byte[] binaryPayload = createBinarySchemaPayload(schemaText, true, 0, 1);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/body_nonzero.stvn_inclf"))
            .header("Content-Type", "application/stvn-bin")
            .POST(HttpRequest.BodyPublishers.ofByteArray(binaryPayload))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("ERR_MALFORMED_SCHEMA_IN_ENVELOPE"), "Must report ERR_MALFORMED_SCHEMA_IN_ENVELOPE: " + response.body());
        assertTrue(response.body().contains("Top-level :type and :body sections are prohibited"), "Must prohibit :body section: " + response.body());

        try (Stream<Path> stream = Files.walk(tempCasRoot)) {
            long casFileCount = stream.filter(p -> p.toString().endsWith(".stvn_cas")).count();
            assertEquals(0, casFileCount, "Invalid Gate 2 payload must NEVER be persisted to CAS storage");
        }
    }

    @Test
    @DisplayName("SEC-10C: Gate 2 RootPointer table zero defsOffset rejected fail-closed")
    void testGate2ZeroDefsOffsetRejected() throws Exception {
        String schemaText = "{\n  :defs {\n    :UserRecord :String\n  }\n}";
        byte[] binaryPayload = createBinarySchemaPayload(schemaText, false, 0, 0);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/defs_zero.stvn_inclf"))
            .header("Content-Type", "application/stvn-bin")
            .POST(HttpRequest.BodyPublishers.ofByteArray(binaryPayload))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(422, response.statusCode());

        try (Stream<Path> stream = Files.walk(tempCasRoot)) {
            long casFileCount = stream.filter(p -> p.toString().endsWith(".stvn_cas")).count();
            assertEquals(0, casFileCount, "Zero defs payload must NEVER be persisted to CAS storage");
        }
    }

    @Test
    @DisplayName("SEC-11: Content negotiation returns transcoded UTF-8 text for application/stvn")
    void testContentNegotiationStoredBinaryToText() throws Exception {
        String schemaText = "{\n  :defs {\n    :AccountOwner :String\n  }\n}";
        byte[] binaryPayload = createBinarySchemaPayload(schemaText, true, 0, 0);

        HttpRequest uploadReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/account_owner.stvn_inclf"))
            .header("Content-Type", "application/stvn-bin")
            .POST(HttpRequest.BodyPublishers.ofByteArray(binaryPayload))
            .build();

        HttpResponse<String> uploadRes = httpClient.send(uploadReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, uploadRes.statusCode());

        com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(uploadRes.body());
        String casHash = json.get("casHash").asText();

        // 1. Fetch requesting default / application/stvn text
        HttpRequest fetchTextReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/cas/" + casHash))
            .header("Accept", "application/stvn")
            .GET()
            .build();

        HttpResponse<String> textRes = httpClient.send(fetchTextReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, textRes.statusCode());
        assertTrue(textRes.headers().firstValue("Content-Type").orElse("").contains("application/stvn"));
        assertEquals(schemaText, textRes.body());

        // 2. Fetch requesting application/stvn-bin binary
        HttpRequest fetchBinReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/cas/" + casHash))
            .header("Accept", "application/stvn-bin")
            .GET()
            .build();

        HttpResponse<byte[]> binRes = httpClient.send(fetchBinReq, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, binRes.statusCode());
        assertTrue(binRes.headers().firstValue("Content-Type").orElse("").contains("application/stvn-bin"));
        assertArrayEquals(binaryPayload, binRes.body());
    }

    private static byte[] createBinarySchemaPayload(String schemaText, boolean validDefs, int typeOffset, int bodyOffset) {
        byte[] textBytes = schemaText.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(256 + textBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(new byte[]{'S', 'T', 'V', 'N', (byte) 0x88});
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

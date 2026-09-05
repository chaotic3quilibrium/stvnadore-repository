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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

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
        Path tamperedFixture = Paths.get("target/test-classes/fixtures/invalid-syntax/binary_crc32c_payload_tampered.stvn_bin");
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
        Path truncatedFixture = Paths.get("target/test-classes/fixtures/invalid-syntax/binary_crc32c_truncated.stvn_bin");
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
        Path sentinelFixture = Paths.get("target/test-classes/fixtures/invalid-syntax/binary_strategy_sentinel_0x7.stvn_bin");
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
        Path tamperedFixture = Paths.get("target/test-classes/fixtures/invalid-syntax/binary_crc32c_payload_tampered.stvn_bin");
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
    @DisplayName("SEC-05: Valid binary payload with CRC-32C trailer accepted and committed with HTTP 201")
    void testValidCrc32cPayloadAccepted() throws Exception {
        Path validFixture = Paths.get("target/test-classes/fixtures/valid-syntax/crc32c_trailer_valid.stvn_bin");
        byte[] payload = Files.readAllBytes(validFixture);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/valid_crc_test"))
            .header("Content-Type", "application/stvn-bin")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(201, response.statusCode(), "Valid binary payload must return HTTP 201: " + response.body());

        try (Stream<Path> stream = Files.walk(tempCasRoot)) {
            long casFileCount = stream.filter(p -> p.toString().endsWith(".stvn_cas")).count();
            assertTrue(casFileCount > 0, "Valid payload must be persisted to CAS storage");
        }
    }
}

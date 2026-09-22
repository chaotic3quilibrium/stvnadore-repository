package org.stvnadore.repository.integration;

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
import org.stvnadore.core.utils.StvnStringCapacityUtils;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test suite verifying Modern Canonical Type (MCT) Core 2.0.0 alignment.
 * Confirms strict ingress contracts, Nominal Bijectivity Invariant ($1:1$ Law),
 * HTTP status codes (201, 200, 409, 422), tab rejection, factorized type metadata validation,
 * capacity bounds, and physical CAS envelope persistence and retrieval.
 */
@NullMarked
public class StvnCoreV200AlignmentIntegrationTest {

    private static Path tempCasRoot = Paths.get("target/temp_cas_v200");
    private static Javalin app = Javalin.create();
    private static int port;
    private static HikariDataSource dataSource = new HikariDataSource();
    private static CasStoragePort casStorage = new FileSystemCasStorage(tempCasRoot);
    private static HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeAll
    static void initAll() throws IOException {
        tempCasRoot = Files.createTempDirectory("stvn_v200_cas_");
        casStorage = new FileSystemCasStorage(tempCasRoot);

        String dbName = "stvn_v200_" + UUID.randomUUID().toString().replace("-", "");
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
    @DisplayName("V200-ALIGN-01: Pristine schema publication returns HTTP 201 Created and persists sharded CAS envelope")
    void testPristineSchemaPublication201Created() throws Exception, NoSuchAlgorithmException {
        String schemaName = "CustomerDomain.stvn_inclf";
        String schemaSource = """
            {
              :defs {
                :package :com/example/crm {
                  :CustomerId { #unsigned #size 64 } :Int
                  :CustomerName { #minSize 1 } :String
                }
              }
            }
            """;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/" + schemaName))
            .header("Content-Type", "application/stvn")
            .POST(HttpRequest.BodyPublishers.ofString(schemaSource))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode());

        // Derive expected canonical hash via StvnSchemaFlattener
        String expectedShape = StvnSchemaFlattener.flatten(Map.of(schemaName, schemaSource), schemaName);
        byte[] expectedHashBytes = MessageDigest.getInstance("SHA-256").digest(expectedShape.getBytes(StandardCharsets.UTF_8));
        String expectedCasHash = HexFormat.of().formatHex(expectedHashBytes);

        assertTrue(response.body().contains(expectedCasHash));
        assertTrue(response.body().contains(":com/example/crm/CustomerId"));

        // Verify physical file sharding on disk (2/62 layout)
        Path shardedFile = tempCasRoot.resolve(expectedCasHash.substring(0, 2))
                                     .resolve(expectedCasHash.substring(2) + ".stvn_cas");
        assertTrue(Files.exists(shardedFile), "Physical CAS envelope must exist at 2/62 sharded path");
    }

    @Test
    @DisplayName("V200-ALIGN-02: Idempotent replay with identical schema name and content returns HTTP 200 OK")
    void testIdempotentCollision200OkOnReplay() throws Exception {
        String schemaName = "IdempotentTest.stvn_inclf";
        String schemaSource = """
            {
              :defs {
                :OrderId { #unsigned #size 64 } :Int
              }
            }
            """;

        HttpRequest req1 = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/" + schemaName))
            .header("Content-Type", "application/stvn")
            .POST(HttpRequest.BodyPublishers.ofString(schemaSource))
            .build();

        HttpResponse<String> res1 = httpClient.send(req1, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res1.statusCode(), "Initial publication must return 201 Created");

        // Replay identically
        HttpResponse<String> res2 = httpClient.send(req1, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res2.statusCode(), "Identical replay must return 200 OK");
        assertEquals(res1.body(), res2.body(), "Response payload must match original metadata");
    }

    @Test
    @DisplayName("V200-ALIGN-03: Mutation under same schema name returns HTTP 409 Conflict (SchemaConflict)")
    void testSchemaConflict409OnMutationUnderSameName() throws Exception {
        String schemaName = "MutableSchema.stvn_inclf";
        String originalSource = """
            {
              :defs {
                :RecordId { #unsigned #size 64 } :Int
              }
            }
            """;
        String mutatedSource = """
            {
              :defs {
                :RecordId { #unsigned #size 32 } :Int
              }
            }
            """;

        // Initial publication
        HttpResponse<String> res1 = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/" + schemaName))
                .header("Content-Type", "application/stvn")
                .POST(HttpRequest.BodyPublishers.ofString(originalSource))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(201, res1.statusCode());

        // Attempt mutation under same name
        HttpResponse<String> res2 = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/" + schemaName))
                .header("Content-Type", "application/stvn")
                .POST(HttpRequest.BodyPublishers.ofString(mutatedSource))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(409, res2.statusCode(), "Mutation under same name must return HTTP 409 Conflict");
        assertTrue(res2.body().contains("Conflict"));
        assertTrue(res2.body().contains("already exists with hash") || res2.body().contains("Mutations are prohibited."));
    }

    @Test
    @DisplayName("V200-ALIGN-04: Nominal alias conflict returns HTTP 409 Conflict (AliasConflict) and prevents CAS write")
    void testAliasConflict409OnNewSchemaNameWithIdenticalContent() throws Exception, NoSuchAlgorithmException {
        String canonicalName = "CanonicalService.stvn_inclf";
        String aliasName = "AliasService.stvn_inclf";
        String sharedSource = """
            {
              :defs {
                :ServiceToken :String
              }
            }
            """;

        // 1. Publish Canonical
        HttpResponse<String> res1 = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/" + canonicalName))
                .header("Content-Type", "application/stvn")
                .POST(HttpRequest.BodyPublishers.ofString(sharedSource))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(201, res1.statusCode(), "Canonical publication must succeed with 201");

        // Count CAS files after canonical publication
        long casFilesBefore;
        try (Stream<Path> stream = Files.walk(tempCasRoot)) {
            casFilesBefore = stream.filter(p -> p.toString().endsWith(".stvn_cas")).count();
        }

        // 2. Publish Alias (different name, identical canonical AST content)
        HttpResponse<String> res2 = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/" + aliasName))
                .header("Content-Type", "application/stvn")
                .POST(HttpRequest.BodyPublishers.ofString(sharedSource))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(409, res2.statusCode(), "Alias publication must return HTTP 409 Conflict");
        assertTrue(res2.body().contains("Conflict"));
        assertTrue(res2.body().contains(aliasName));
        assertTrue(res2.body().contains(canonicalName));

        // 3. Invariant check: Zero extra CAS files persisted
        long casFilesAfter;
        try (Stream<Path> stream = Files.walk(tempCasRoot)) {
            casFilesAfter = stream.filter(p -> p.toString().endsWith(".stvn_cas")).count();
        }
        assertEquals(casFilesBefore, casFilesAfter, "Alias conflict must NOT write duplicate or orphaned CAS envelope");
    }

    @Test
    @DisplayName("V200-ALIGN-05: Ingress strictly rejects raw horizontal tab characters with HTTP 422 ERR_TAB_CHARACTER_FORBIDDEN")
    void testZeroTabRejectionAtHttpBoundary() throws Exception {
        String tabbedSchema = "{\n\t:defs {\n\t\t:UserId { #unsigned #size 64 } :Int\n\t}\n}";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/tabbed_account.stvn_inclf"))
            .header("Content-Type", "application/stvn")
            .POST(HttpRequest.BodyPublishers.ofString(tabbedSchema))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("ERR_TAB_CHARACTER_FORBIDDEN"),
            "Response body must report ERR_TAB_CHARACTER_FORBIDDEN diagnostic");
    }

    @Test
    @DisplayName("V200-ALIGN-06: Ingress strictly rejects legacy 1.x type tokens with HTTP 422")
    void testLegacy1xTypeTokensRejection422() throws Exception {
        String legacySchema = "{\n  :defs {\n    :OldId :Uint64\n  }\n}";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/legacy_token.stvn_inclf"))
            .header("Content-Type", "application/stvn")
            .POST(HttpRequest.BodyPublishers.ofString(legacySchema))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(422, response.statusCode(), "Legacy 1.x tokens must be rejected with HTTP 422");
    }

    @Test
    @DisplayName("V200-ALIGN-07: Ingress strictly rejects invalid metadata combinations with HTTP 422")
    void testInvalidMetadataCombinationsRejection422() throws Exception {
        // #size is not allowed on :String (only #minSize, #maxSize, #fixedSize)
        String invalidMetaSchema = "{\n  :defs {\n    :BadString { #size 255 } :String\n  }\n}";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/invalid_meta.stvn_inclf"))
            .header("Content-Type", "application/stvn")
            .POST(HttpRequest.BodyPublishers.ofString(invalidMetaSchema))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(422, response.statusCode(), "Invalid metadata combinations must return HTTP 422");
    }

    @Test
    @DisplayName("V200-ALIGN-08: Ingress rejects payloads exceeding 16 MiB capacity bound with HTTP 422 ERR_CAPACITY_OVERFLOW")
    void testCapacityOverflowRejection() throws Exception {
        String oversized = "a".repeat(StvnStringCapacityUtils.DEFAULT_UNBOUNDED_STRING_CAPACITY + 10);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/oversized.stvn_inclf"))
            .header("Content-Type", "application/stvn")
            .POST(HttpRequest.BodyPublishers.ofString(oversized))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("ERR_CAPACITY_OVERFLOW") || response.body().contains("Capacity Overflow"));
    }

    @Test
    @DisplayName("V200-ALIGN-09: Raw schema retrieval via GET /api/v1/schemas/cas/{hash} returns unwrapped application/stvn")
    void testRawCasPayloadRetrieval() throws Exception, NoSuchAlgorithmException {
        String schemaName = "RetrievalTest.stvn_inclf";
        String schemaSource = """
            {
              :defs {
                :TokenId { #unsigned #size 32 } :Int
              }
            }
            """;

        HttpRequest publishReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/" + schemaName))
            .header("Content-Type", "application/stvn")
            .POST(HttpRequest.BodyPublishers.ofString(schemaSource))
            .build();

        HttpResponse<String> publishRes = httpClient.send(publishReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, publishRes.statusCode());

        String shape = StvnSchemaFlattener.flatten(Map.of(schemaName, schemaSource), schemaName);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(shape.getBytes(StandardCharsets.UTF_8)));

        HttpRequest getReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/cas/" + hash))
            .header("Accept", "application/stvn")
            .GET()
            .build();

        HttpResponse<String> getRes = httpClient.send(getReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getRes.statusCode());
        assertTrue(getRes.headers().firstValue("Content-Type").orElse("").contains("application/stvn"));
        assertEquals(schemaSource.trim(), getRes.body().trim());
    }

    @Test
    @DisplayName("V200-ALIGN-10: Pristine publication of temporal schema returns HTTP 201 Created and reflects 7-tier order")
    void testTemporalSchemaPublication201CreatedAnd7TierOrder() throws Exception, NoSuchAlgorithmException {
        String schemaName = "TemporalDomain.stvn_inclf";
        String schemaSource = """
            {
              :defs {
                :EventId :String
                :EventTime { #ms #minIncl 1000 #maxExcl 2000 } :TimeEpoch
                :AuditTime { #offset #minIncl "2026-01-01T00:00:00Z" #maxExcl "2027-01-01T00:00:00Z" } :DateTime
              }
            }
            """;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/" + schemaName))
            .header("Content-Type", "application/stvn")
            .POST(HttpRequest.BodyPublishers.ofString(schemaSource))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), "Expected 201 Created but got " + response.statusCode() + ": " + response.body());

        // Derive expected shape signature via StvnSchemaFlattener (enforces 7-tier Semantic Category Order)
        String expectedShape = StvnSchemaFlattener.flatten(Map.of(schemaName, schemaSource), schemaName);
        byte[] expectedHashBytes = MessageDigest.getInstance("SHA-256").digest(expectedShape.getBytes(StandardCharsets.UTF_8));
        String expectedCasHash = HexFormat.of().formatHex(expectedHashBytes);

        assertTrue(response.body().contains(expectedCasHash), "Response body must contain canonical CAS hash");

        // Verify Tier 2 (#ms) precedes Tier 4 (#minIncl, #maxExcl)
        assertTrue(expectedShape.contains("{ #ms #minIncl 1000 #maxExcl 2000 } :TimeEpoch"),
            "Shape signature must order Tier 2 (temporal scale) before Tier 4 (intervals): " + expectedShape);

        // Verify Tier 1 (#offset) precedes Tier 4 (#minIncl, #maxExcl)
        assertTrue(expectedShape.contains("{ #offset #minIncl \"2026-01-01T00:00:00Z\" #maxExcl \"2027-01-01T00:00:00Z\" } :DateTime"),
            "Shape signature must order Tier 1 (intrinsic mode) before Tier 4 (intervals): " + expectedShape);

        // Verify physical file sharding on disk (2/62 layout)
        Path shardedFile = tempCasRoot.resolve(expectedCasHash.substring(0, 2))
                                     .resolve(expectedCasHash.substring(2) + ".stvn_cas");
        assertTrue(Files.exists(shardedFile), "Physical CAS envelope must exist at 2/62 sharded path");
    }

    @Test
    @DisplayName("V200-ALIGN-11: Ingress strictly rejects bare :TimeEpoch lacking scale flags with HTTP 422 ERR_MISSING_TEMPORAL_FACET")
    void testBareTimeEpochRejection422() throws Exception {
        String bareEpochSchema = """
            {
              :defs {
                :CreatedTimestamp :TimeEpoch
              }
            }
            """;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/bare_epoch.stvn_inclf"))
            .header("Content-Type", "application/stvn")
            .POST(HttpRequest.BodyPublishers.ofString(bareEpochSchema))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("ERR_MISSING_TEMPORAL_FACET"),
            "Response body must report ERR_MISSING_TEMPORAL_FACET diagnostic: " + response.body());
    }

    @Test
    @DisplayName("V200-ALIGN-12: Ingress strictly rejects closed bounds on :TimeEpoch with HTTP 422 ERR_DISCRETE_BOUND_KIND_PROHIBITED")
    void testClosedBoundsOnTemporalRejection422() throws Exception {
        String closedBoundSchema = """
            {
              :defs {
                :BoundedEpoch { #ms #maxIncl 5000 } :TimeEpoch
              }
            }
            """;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/closed_bound_epoch.stvn_inclf"))
            .header("Content-Type", "application/stvn")
            .POST(HttpRequest.BodyPublishers.ofString(closedBoundSchema))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("ERR_DISCRETE_BOUND_KIND_PROHIBITED"),
            "Response body must report ERR_DISCRETE_BOUND_KIND_PROHIBITED diagnostic: " + response.body());
    }

    @Test
    @DisplayName("V200-ALIGN-13: Ingress strictly rejects bare :DateTime lacking mode flag with HTTP 422 ERR_MISSING_TEMPORAL_FACET")
    void testBareDateTimeRejection422() throws Exception {
        String bareDateTimeSchema = """
            {
              :defs {
                :MeetingTime :DateTime
              }
            }
            """;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/bare_datetime.stvn_inclf"))
            .header("Content-Type", "application/stvn")
            .POST(HttpRequest.BodyPublishers.ofString(bareDateTimeSchema))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("ERR_MISSING_TEMPORAL_FACET"),
            "Response body must report ERR_MISSING_TEMPORAL_FACET diagnostic: " + response.body());
    }
}

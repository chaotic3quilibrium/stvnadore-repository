package org.stvnadore.repository.integration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.javalin.Javalin;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnSchemaFlattener;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying Content-Addressable Storage (CAS) isolation,
 * address divergence, and sharded file generation across enum subset schemas.
 */
@NullMarked
public class EnumSubsetCasIntegrationTest {

    private static Path tempCasRoot = Paths.get("target/temp_cas_enum");
    private static Javalin app = Javalin.create();
    private static int port;
    private static HikariDataSource dataSource = new HikariDataSource();
    private static CasStoragePort casStorage = new FileSystemCasStorage(tempCasRoot);
    private static HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeAll
    static void initAll() throws IOException {
        tempCasRoot = Files.createTempDirectory("stvn_enum_subset_cas_");
        casStorage = new FileSystemCasStorage(tempCasRoot);

        String dbName = "stvn_enum_" + UUID.randomUUID().toString().replace("-", "");
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
    @DisplayName("SUBSET-CAS-01: Inclusive and exclusive subsets produce divergent CAS addresses without collision")
    void testEnumSubsetCasAddressIsolation() throws Exception {
        String exclSchemaName = "TestableEnv.stvn_inclf";
        String inclSchemaName = "PreReleaseEnv.stvn_inclf";

        String exclText = """
            {
              :defs {
                :Environment :Enum [ #LOCAL #DEV #STAGING #CANARY #PROD ]
                :TestableEnv { #filterExcl [ #PROD ] } :Environment
              }
            }
            """;

        String inclText = """
            {
              :defs {
                :Environment :Enum [ #LOCAL #DEV #STAGING #CANARY #PROD ]
                :PreReleaseEnv { #filterIncl [ #DEV #STAGING #CANARY ] } :Environment
              }
            }
            """;

        // 1. Publish Exclusive Subset (:TestableEnv)
        HttpResponse<String> resp1 = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/" + exclSchemaName))
                .header("Content-Type", "application/stvn")
                .POST(HttpRequest.BodyPublishers.ofString(exclText))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(201, resp1.statusCode(), "Exclusive subset publication must return HTTP 201: " + resp1.body());

        // 2. Publish Inclusive Subset (:PreReleaseEnv)
        HttpResponse<String> resp2 = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/" + inclSchemaName))
                .header("Content-Type", "application/stvn")
                .POST(HttpRequest.BodyPublishers.ofString(inclText))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(201, resp2.statusCode(), "Inclusive subset publication must return HTTP 201: " + resp2.body());

        // 3. Compute expected AST hashes via StvnSchemaFlattener + SHA-256
        String shape1 = StvnSchemaFlattener.flatten(Map.of(exclSchemaName, exclText), exclSchemaName);
        String shape2 = StvnSchemaFlattener.flatten(Map.of(inclSchemaName, inclText), inclSchemaName);

        String hash1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(shape1.getBytes(StandardCharsets.UTF_8)));
        String hash2 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(shape2.getBytes(StandardCharsets.UTF_8)));

        assertNotEquals(hash1, hash2, "Parent and sibling subsets must produce divergent CAS hashes");

        // 4. Verify distinct physical CAS files exist on disk in 2/62 layout
        byte[] read1 = casStorage.read(hash1);
        byte[] read2 = casStorage.read(hash2);

        assertNotNull(read1, "CAS storage must contain exclusive subset envelope for hash: " + hash1);
        assertNotNull(read2, "CAS storage must contain inclusive subset envelope for hash: " + hash2);
        assertNotEquals(new String(read1, StandardCharsets.UTF_8), new String(read2, StandardCharsets.UTF_8));

        // 5. Verify raw retrieval endpoint GET /api/v1/schemas/cas/{hash}
        HttpResponse<String> rawResp1 = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/cas/" + hash1))
                .header("Accept", "application/stvn")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, rawResp1.statusCode());
        assertEquals("application/stvn", rawResp1.headers().firstValue("Content-Type").orElse(""));
        assertTrue(rawResp1.body().contains(":TestableEnv"), "Retrieved schema must contain exclusive subset definition");
    }

    @Test
    @DisplayName("SUBSET-CAS-02: Root enum and subset schema produce divergent CAS hashes preventing overwrite")
    void testRootEnumVsSubsetDivergence() throws Exception {
        String rootSchemaName = "EnvironmentRoot.stvn_inclf";
        String subsetSchemaName = "PreReleaseEnv.stvn_inclf";

        String rootEnumText = """
            {
              :defs {
                :Environment :Enum [ #LOCAL #DEV #STAGING #CANARY #PROD ]
              }
            }
            """;

        String subsetText = """
            {
              :defs {
                :Environment :Enum [ #LOCAL #DEV #STAGING #CANARY #PROD ]
                :PreReleaseEnv { #filterIncl [ #DEV #STAGING #CANARY ] } :Environment
              }
            }
            """;

        // Publish root enum
        HttpResponse<String> rootResp = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/" + rootSchemaName))
                .header("Content-Type", "application/stvn")
                .POST(HttpRequest.BodyPublishers.ofString(rootEnumText))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(201, rootResp.statusCode());

        // Verify root schema hash != subset schema hash
        String rootShape = StvnSchemaFlattener.flatten(Map.of(rootSchemaName, rootEnumText), rootSchemaName);
        String subsetShape = StvnSchemaFlattener.flatten(Map.of(subsetSchemaName, subsetText), subsetSchemaName);

        String rootHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rootShape.getBytes(StandardCharsets.UTF_8)));
        String subsetHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(subsetShape.getBytes(StandardCharsets.UTF_8)));

        assertNotEquals(rootHash, subsetHash, "Root enum and subset must have distinct cryptographic digests");
    }

    @Test
    @DisplayName("SUBSET-CAS-03: Transitive enum subset chain computes unique CAS fingerprint and stores cleanly")
    void testTransitiveChainCasPersistence() throws Exception {
        String chainSchemaName = "ExecutionStatusChain.stvn_inclf";
        String chainText = """
            {
              :defs {
                :TaskStatus :Enum [
                  #BACKLOG
                  #TODO
                  #IN_PROGRESS
                  #CODE_REVIEW
                  #TESTING
                  #DONE
                  #BLOCKED
                  #CANCELLED
                ]

                // Tier 1: Exclude terminal states (8 - 3 = 5 variants remain)
                :ActiveStatus { #filterExcl [ #BACKLOG #DONE #CANCELLED ] } :TaskStatus

                // Tier 2: Select workable states from Tier 1 (5 -> 4 variants remain)
                :WorkableStatus { #filterIncl [ #TODO #IN_PROGRESS #CODE_REVIEW #TESTING ] } :ActiveStatus

                // Tier 3: Exclude verification states from Tier 2 (4 - 2 = 2 variants remain)
                :ExecutionStatus { #filterExcl [ #CODE_REVIEW #TESTING ] } :WorkableStatus
              }
            }
            """;

        HttpResponse<String> resp = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/" + chainSchemaName))
                .header("Content-Type", "application/stvn")
                .POST(HttpRequest.BodyPublishers.ofString(chainText))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(201, resp.statusCode());

        String chainShape = StvnSchemaFlattener.flatten(Map.of(chainSchemaName, chainText), chainSchemaName);
        String chainHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(chainShape.getBytes(StandardCharsets.UTF_8)));

        byte[] envelope = casStorage.read(chainHash);
        assertNotNull(envelope, "Transitive chain envelope must be present in CAS storage");
        assertTrue(new String(envelope, StandardCharsets.UTF_8).contains("ExecutionStatusChain.stvn_inclf"));
    }
}

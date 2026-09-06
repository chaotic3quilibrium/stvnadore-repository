package org.stvnadore.repository.integration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.javalin.Javalin;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.binary.StvnSchemaHasher;
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
import java.util.HexFormat;
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
        Path exclPath = Paths.get("target/test-classes/fixtures/valid-syntax/enum_subset_exclusive.stvn");
        Path inclPath = Paths.get("target/test-classes/fixtures/valid-syntax/enum_subset_inclusive.stvn");

        String exclText = Files.readString(exclPath);
        String inclText = Files.readString(inclPath);

        // 1. Publish Exclusive Subset (:TestableEnv)
        HttpResponse<String> resp1 = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/TestableEnv"))
                .header("Content-Type", "application/stvn")
                .POST(HttpRequest.BodyPublishers.ofString(exclText))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(201, resp1.statusCode(), "Exclusive subset publication must return HTTP 201: " + resp1.body());

        // 2. Publish Inclusive Subset (:PreReleaseEnv)
        HttpResponse<String> resp2 = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/PreReleaseEnv"))
                .header("Content-Type", "application/stvn")
                .POST(HttpRequest.BodyPublishers.ofString(inclText))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(201, resp2.statusCode(), "Inclusive subset publication must return HTTP 201: " + resp2.body());

        // 3. Compute expected AST hashes via StvnSchemaHasher
        var schema1 = StvnCompiler.compile(exclText).orElseThrow().schema();
        var schema2 = StvnCompiler.compile(inclText).orElseThrow().schema();

        String hash1 = HexFormat.of().formatHex(StvnSchemaHasher.computeSha256(schema1));
        String hash2 = HexFormat.of().formatHex(StvnSchemaHasher.computeSha256(schema2));

        assertNotEquals(hash1, hash2, "Parent and sibling subsets must produce divergent CAS hashes");

        // 4. Verify distinct physical CAS files exist on disk in 2/62 layout
        byte[] read1 = casStorage.read(hash1);
        byte[] read2 = casStorage.read(hash2);

        assertNotNull(read1, "CAS storage must contain exclusive subset envelope for hash: " + hash1);
        assertNotNull(read2, "CAS storage must contain inclusive subset envelope for hash: " + hash2);
        assertNotEquals(new String(read1), new String(read2));

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
        String rootEnumText = """
            {
              :defs {
                :Environment :Enum [ #LOCAL #DEV #STAGING #CANARY #PROD ]
              }
              :type :Environment
              :body #LOCAL
            }
            """;

        Path inclPath = Paths.get("target/test-classes/fixtures/valid-syntax/enum_subset_inclusive.stvn");
        String inclText = Files.readString(inclPath);

        // Publish root enum
        HttpResponse<String> rootResp = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/EnvironmentRoot"))
                .header("Content-Type", "application/stvn")
                .POST(HttpRequest.BodyPublishers.ofString(rootEnumText))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(201, rootResp.statusCode());

        // Verify root schema hash != subset schema hash
        var rootSchema = StvnCompiler.compile(rootEnumText).orElseThrow().schema();
        var subsetSchema = StvnCompiler.compile(inclText).orElseThrow().schema();

        String rootHash = HexFormat.of().formatHex(StvnSchemaHasher.computeSha256(rootSchema));
        String subsetHash = HexFormat.of().formatHex(StvnSchemaHasher.computeSha256(subsetSchema));

        assertNotEquals(rootHash, subsetHash, "Root enum and subset must have distinct cryptographic digests");
    }

    @Test
    @DisplayName("SUBSET-CAS-03: Transitive enum subset chain computes unique CAS fingerprint and stores cleanly")
    void testTransitiveChainCasPersistence() throws Exception {
        Path chainPath = Paths.get("target/test-classes/fixtures/valid-syntax/enum_subset_transitive_chain.stvn");
        String chainText = Files.readString(chainPath);

        HttpResponse<String> resp = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/schemas/ExecutionStatusChain"))
                .header("Content-Type", "application/stvn")
                .POST(HttpRequest.BodyPublishers.ofString(chainText))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(201, resp.statusCode());

        var chainSchema = StvnCompiler.compile(chainText).orElseThrow().schema();
        String chainHash = HexFormat.of().formatHex(StvnSchemaHasher.computeSha256(chainSchema));

        byte[] envelope = casStorage.read(chainHash);
        assertNotNull(envelope, "Transitive chain envelope must be present in CAS storage");
        assertTrue(new String(envelope).contains("ExecutionStatusChain"));
    }
}

package org.stvnadore.repository.infrastructure;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.stvnadore.repository.domain.DuplicateIndexException;
import org.stvnadore.repository.domain.SchemaMetadata;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class JdbcIndexRepositoryTest {

    private HikariDataSource dataSource;
    private JdbcIndexRepository repository;

    @BeforeEach
    public void setUp() {
        String dbName = "stvn_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);

        dataSource = new HikariDataSource(config);
        DatabaseInitializer.initialize(dataSource, true);
        repository = new JdbcIndexRepository(dataSource);
    }

    @AfterEach
    public void tearDown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    @Test
    public void testSaveAndFindSuccess() throws SQLException {
        String schemaName = "user-account";
        String shapeSig = "{ :defs { :User :String } }";
        String casHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        String sourceText = "schema User { name: String }";

        SchemaMetadata metadata = new SchemaMetadata(schemaName, shapeSig, casHash);
        repository.save(metadata, sourceText);

        assertTrue(repository.existsByHash(casHash));

        Optional<SchemaMetadata> byShape = repository.findByShape(schemaName, shapeSig);
        assertTrue(byShape.isPresent());
        assertEquals(schemaName, byShape.get().schemaName());
        assertEquals(shapeSig, byShape.get().shapeSignature());
        assertEquals(casHash, byShape.get().casHash());

        Optional<SchemaMetadata> byName = repository.findBySchemaName(schemaName);
        assertTrue(byName.isPresent());
        assertEquals(metadata, byName.get());

        // Verify record in schema_source_audit table
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT schema_name, cas_hash, source_text FROM schema_source_audit WHERE schema_name = 'user-account'")) {
            assertTrue(rs.next());
            assertEquals(schemaName, rs.getString("schema_name"));
            assertEquals(casHash, rs.getString("cas_hash"));
            assertEquals(sourceText, rs.getString("source_text"));
        }
    }

    @Test
    public void testSaveDuplicateSchemaNameThrowsDuplicateIndexException() {
        String schemaName = "duplicate-schema";
        String hash1 = "1111111111111111111111111111111111111111111111111111111111111111";
        String hash2 = "2222222222222222222222222222222222222222222222222222222222222222";

        repository.save(new SchemaMetadata(schemaName, "sig1", hash1), "source 1");

        assertThrows(DuplicateIndexException.class, () ->
            repository.save(new SchemaMetadata(schemaName, "sig2", hash2), "source 2")
        );
    }

    @Test
    public void testSaveDuplicateCasHashThrowsDuplicateIndexException() {
        String hash = "3333333333333333333333333333333333333333333333333333333333333333";

        repository.save(new SchemaMetadata("schema-a", "sig1", hash), "source a");

        assertThrows(DuplicateIndexException.class, () ->
            repository.save(new SchemaMetadata("schema-b", "sig2", hash), "source b")
        );
    }

    @Test
    public void testExistsByHashNonExistentReturnsFalse() {
        assertFalse(repository.existsByHash("nonexistent111111111111111111111111111111111111111111111111111111"));
    }

    @Test
    public void testFindByShapeNonExistentReturnsEmpty() {
        Optional<SchemaMetadata> result = repository.findByShape("unknown", "unknown-sig");
        assertTrue(result.isEmpty());
    }

    @Test
    public void testFindBySchemaNameNonExistentReturnsEmpty() {
        Optional<SchemaMetadata> result = repository.findBySchemaName("unknown");
        assertTrue(result.isEmpty());
    }
}

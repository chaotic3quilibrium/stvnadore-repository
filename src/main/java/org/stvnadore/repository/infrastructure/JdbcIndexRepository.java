package org.stvnadore.repository.infrastructure;

import org.stvnadore.repository.domain.DuplicateIndexException;
import org.stvnadore.repository.domain.SchemaMetadata;
import org.stvnadore.repository.ports.IndexRepositoryPort;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * ANSI SQL implementation of IndexRepositoryPort compatible with PostgreSQL and H2.
 */
public class JdbcIndexRepository implements IndexRepositoryPort {
    private final DataSource dataSource;

    /**
     * Constructs a JdbcIndexRepository backed by the given DataSource.
     *
     * @param dataSource the JDBC data source
     */
    public JdbcIndexRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void save(SchemaMetadata metadata, String sourceText) throws DuplicateIndexException {
        String insertCatalogSql = "INSERT INTO version_catalog (schema_name, shape_signature, cas_hash) VALUES (?, ?, ?)";
        String insertAuditSql = "INSERT INTO schema_source_audit (schema_name, cas_hash, source_text) VALUES (?, ?, ?)";

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement stmt = conn.prepareStatement(insertCatalogSql)) {
                    stmt.setString(1, metadata.schemaName());
                    stmt.setString(2, metadata.shapeSignature());
                    stmt.setString(3, metadata.casHash());
                    stmt.executeUpdate();
                }

                try (PreparedStatement stmt = conn.prepareStatement(insertAuditSql)) {
                    stmt.setString(1, metadata.schemaName());
                    stmt.setString(2, metadata.casHash());
                    stmt.setString(3, sourceText);
                    stmt.executeUpdate();
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                    throw new DuplicateIndexException("Duplicate index violation for schema: " + metadata.schemaName(), e);
                }
                throw new RuntimeException("Database error saving schema index: " + metadata.schemaName(), e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to obtain database connection", e);
        }
    }

    @Override
    public boolean existsByHash(String casHash) {
        String sql = "SELECT 1 FROM version_catalog WHERE cas_hash = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, casHash);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error checking existence of hash: " + casHash, e);
        }
    }

    @Override
    public Optional<SchemaMetadata> findByShape(String schemaName, String shapeSignature) {
        String sql = "SELECT schema_name, shape_signature, cas_hash FROM version_catalog WHERE schema_name = ? AND shape_signature = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, schemaName);
            stmt.setString(2, shapeSignature);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new SchemaMetadata(
                        rs.getString("schema_name"),
                        rs.getString("shape_signature"),
                        rs.getString("cas_hash")
                    ));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error finding schema by shape: " + schemaName, e);
        }
    }

    @Override
    public Optional<SchemaMetadata> findBySchemaName(String schemaName) {
        String sql = "SELECT schema_name, shape_signature, cas_hash FROM version_catalog WHERE schema_name = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, schemaName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new SchemaMetadata(
                        rs.getString("schema_name"),
                        rs.getString("shape_signature"),
                        rs.getString("cas_hash")
                    ));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error finding schema by name: " + schemaName, e);
        }
    }
}
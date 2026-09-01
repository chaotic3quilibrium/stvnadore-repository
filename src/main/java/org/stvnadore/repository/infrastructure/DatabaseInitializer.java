package org.stvnadore.repository.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.stream.Collectors;

/**
 * Idempotent relational database schema initialization runner.
 * Executes classpath DDL scripts when DB_AUTO_INIT is enabled.
 */
public final class DatabaseInitializer {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseInitializer.class);

    private DatabaseInitializer() {}

    /**
     * Initializes the database schema if autoInit is true.
     *
     * @param dataSource the configured javax.sql.DataSource
     * @param autoInit   whether auto initialization is enabled
     */
    public static void initialize(DataSource dataSource, boolean autoInit) {
        if (!autoInit) {
            logger.info("Database auto-initialization is disabled (DB_AUTO_INIT=false). Skipping DDL execution.");
            return;
        }

        logger.info("Executing database schema initialization from classpath (schema.sql)...");
        try (InputStream in = DatabaseInitializer.class.getResourceAsStream("/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource '/schema.sql' not found.");
            }

            String sqlScript;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                sqlScript = reader.lines().collect(Collectors.joining("\n"));
            }

            // Strip line comments
            String cleanedScript = sqlScript
                .replaceAll("(?m)^\\s*--.*$", "")
                .replaceAll("(?m)^\\s*//.*$", "");

            String[] statements = cleanedScript.split(";");
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                for (String rawSql : statements) {
                    String sql = rawSql.trim();
                    if (!sql.isEmpty()) {
                        stmt.execute(sql);
                    }
                }
            }
            logger.info("Database schema initialized successfully.");
        } catch (Exception e) {
            logger.error("Failed to initialize database schema", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }
}

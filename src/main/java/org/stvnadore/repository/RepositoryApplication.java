package org.stvnadore.repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.javalin.Javalin;
import org.stvnadore.repository.edge.SchemaPublishHandler;
import org.stvnadore.repository.engine.RelationalProjectionSweeper;
import org.stvnadore.repository.infrastructure.ConcurrentHashMapCache;
import org.stvnadore.repository.infrastructure.DatabaseInitializer;
import org.stvnadore.repository.infrastructure.FileSystemCasScanner;
import org.stvnadore.repository.infrastructure.FileSystemCasStorage;
import org.stvnadore.repository.infrastructure.JdbcIndexRepository;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;

/**
 * Main entry point for the STVN Schema Repository Server daemon.
 */
public class RepositoryApplication {

  private RepositoryApplication() {
    // Application entry class, non-instantiable
  }

  /**
   * Boots the Javalin server, initializes storage backends, and starts the background sweeper.
   *
   * @param args command-line arguments
   */
  public static void main(String[] args) {
    // 1. Configure CAS File Storage Root
    String casRootStr = System.getenv().getOrDefault("CAS_ROOT", "data/cas");
    Path casRoot = Paths.get(casRootStr);
    var casStorage = new FileSystemCasStorage(casRoot);

    // 2. Configure HikariCP DataSource for PostgreSQL / H2
    String dbUrl = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/stvnadore");
    String dbUser = System.getenv().getOrDefault("DB_USER", "postgres");
    String dbPassword = System.getenv().getOrDefault("DB_PASSWORD", "password");
    boolean autoInit = Boolean.parseBoolean(System.getenv().getOrDefault("DB_AUTO_INIT", "false"));

    HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(dbUrl);
    hikariConfig.setUsername(dbUser);
    hikariConfig.setPassword(dbPassword);
    hikariConfig.setMaximumPoolSize(10);
    hikariConfig.setMinimumIdle(2);
    hikariConfig.setIdleTimeout(30000);
    hikariConfig.setConnectionTimeout(5000);

    HikariDataSource dataSource = new HikariDataSource(hikariConfig);
    Runtime.getRuntime().addShutdownHook(new Thread(dataSource::close));

    // Bootstrap database schema if DB_AUTO_INIT is enabled
    DatabaseInitializer.initialize(dataSource, autoInit);

    var indexRepository = new JdbcIndexRepository(dataSource);

    // 3. Configure cache
    var catalogCache = new ConcurrentHashMapCache();

    // 4. Create Engine
    var engine = new SimpleSchemaRepositoryEngine(casStorage, indexRepository, catalogCache);

    // 5. Configure Javalin App using Virtual Threads
    var app = Javalin.create(config -> {
      config.jetty.modifyServer(server -> {
        if (server.getThreadPool() instanceof org.eclipse.jetty.util.thread.QueuedThreadPool queuedThreadPool) {
          queuedThreadPool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
        }
      });
    });
    
    var handler = new SchemaPublishHandler(engine, casStorage);
    handler.configureRoutes(app);
    
    // 6. Configure and spin up RelationalProjectionSweeper in background virtual thread
    var scanner = new FileSystemCasScanner(casRoot);
    var sweeper = new RelationalProjectionSweeper(casStorage, indexRepository, scanner, casRoot);

    Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
        while (true) {
            try {
                sweeper.run();
            } catch (Exception e) {
                System.err.println("RelationalProjectionSweeper encountered a runtime error: " + e.getMessage());
                e.printStackTrace();
            }
            try {
                Thread.sleep(60000);
            } catch (InterruptedException e) {
                System.err.println("RelationalProjectionSweeper background thread interrupted. Exiting sweeper loop.");
                Thread.currentThread().interrupt();
                break;
            }
        }
    });

    app.start(8080);
  }
}

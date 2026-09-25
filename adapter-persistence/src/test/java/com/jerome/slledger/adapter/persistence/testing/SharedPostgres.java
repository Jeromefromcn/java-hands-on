package com.jerome.slledger.adapter.persistence.testing;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One Postgres per test JVM, started on first use; Ryuk removes it at JVM exit.
 *
 * <p>A static singleton rather than {@code @ServiceConnection}: a container bean is bound to one
 * context and stopped when that context closes, and the tests start many short-lived contexts
 * against the same server. Plain {@code spring.datasource.*} properties do the same job.
 *
 * <p>One server, many databases. Stage 5's demos drop and recreate a {@code loan_position} of their
 * own shape; Flyway-managed contexts need Flyway's. Sharing one database, either would corrupt the
 * other, so each gets its own. {@code CREATE DATABASE} costs milliseconds, far less than a
 * container.
 *
 * <p>Published to {@code bootstrap}'s tests through this module's test-jar.
 */
public final class SharedPostgres {

  private static final PostgreSQLContainer CONTAINER =
      new PostgreSQLContainer("postgres:17-alpine");

  private static final Set<String> CREATED = new HashSet<>();
  private static final AtomicInteger FRESH = new AtomicInteger();

  private SharedPostgres() {}

  /**
   * Starts the container if it is not running yet. Tests with a {@code @Timeout} call this from
   * {@code @BeforeAll}, so a first image pull and container start are not charged to a test.
   */
  public static synchronized void start() {
    if (!CONTAINER.isRunning()) {
      CONTAINER.start();
    }
  }

  /** Properties for the named database, created on first use and reused after that. */
  public static synchronized String[] datasourceProperties(String database) {
    start();
    if (CREATED.add(database)) {
      createDatabase(database);
    }
    return properties(database);
  }

  /** A new, empty database with a unique name, for contexts that run Flyway from V1. */
  public static synchronized String[] freshDatabase() {
    return datasourceProperties("ledger_" + FRESH.incrementAndGet());
  }

  /** The JDBC URL of the named database, for tests that open their own connections. */
  public static synchronized String jdbcUrl(String database) {
    start();
    return "jdbc:postgresql://%s:%d/%s"
        .formatted(
            CONTAINER.getHost(),
            CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
            database);
  }

  public static synchronized String username() {
    start();
    return CONTAINER.getUsername();
  }

  public static synchronized String password() {
    start();
    return CONTAINER.getPassword();
  }

  private static String[] properties(String database) {
    return new String[] {
      "spring.datasource.url=" + jdbcUrl(database),
      "spring.datasource.username=" + CONTAINER.getUsername(),
      "spring.datasource.password=" + CONTAINER.getPassword(),
    };
  }

  private static void createDatabase(String database) {
    // Identifiers cannot be bound as parameters; the names come from this class, never from input.
    try (Connection connection =
            DriverManager.getConnection(
                CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + database);
    } catch (SQLException e) {
      throw new IllegalStateException("Could not create database " + database, e);
    }
  }
}

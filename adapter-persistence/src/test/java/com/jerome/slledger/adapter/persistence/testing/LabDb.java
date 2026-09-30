package com.jerome.slledger.adapter.persistence.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;

/**
 * Plain-JDBC databases for the SQL exercises: no Spring context, no ORM, a database of their own on
 * {@link SharedPostgres}, migrated by Flyway from the production migrations plus any test-only
 * locations.
 */
public final class LabDb {

  private LabDb() {}

  /** Creates the named database (once) and returns its JDBC URL. */
  public static String create(String name) {
    SharedPostgres.datasourceProperties(name);
    return SharedPostgres.jdbcUrl(name);
  }

  /** Production migrations plus {@code extraLocations}, up to {@code target} (null: latest). */
  public static void migrate(String url, String target, String... extraLocations) {
    String[] locations = new String[extraLocations.length + 1];
    locations[0] = "classpath:db/migration";
    System.arraycopy(extraLocations, 0, locations, 1, extraLocations.length);
    var configuration =
        Flyway.configure()
            .dataSource(url, SharedPostgres.username(), SharedPostgres.password())
            .locations(locations);
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  public static Connection connect(String url) throws SQLException {
    return DriverManager.getConnection(url, SharedPostgres.username(), SharedPostgres.password());
  }

  public static void execute(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  /** {@code EXPLAIN (ANALYZE, BUFFERS)} of {@code sql}, one line per element. */
  public static List<String> explain(Connection c, String sql) throws SQLException {
    List<String> plan = new ArrayList<>();
    try (Statement s = c.createStatement();
        var rows = s.executeQuery("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + sql)) {
      while (rows.next()) {
        plan.add(rows.getString(1));
      }
    }
    return plan;
  }

  /** A SQL file from the test classpath, e.g. {@code sql/pool-rollup.sql}. */
  public static String sqlFile(String path) {
    try (InputStream in = LabDb.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalArgumentException("No such SQL file on the classpath: " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

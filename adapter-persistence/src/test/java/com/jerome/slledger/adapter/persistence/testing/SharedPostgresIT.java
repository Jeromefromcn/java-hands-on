package com.jerome.slledger.adapter.persistence.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** One server, isolated databases: a table created in one is never visible in another. */
class SharedPostgresIT {

  @Test
  void freshDatabasesAreDistinctAndEmpty() throws SQLException {
    String first = urlOf(SharedPostgres.freshDatabase());
    String second = urlOf(SharedPostgres.freshDatabase());
    assertThat(first).isNotEqualTo(second);

    execute(first, "CREATE TABLE marker (id int)");

    assertThat(tableCount(first)).isEqualTo(1);
    assertThat(tableCount(second)).isZero();
  }

  @Test
  void aNamedDatabaseIsReusedNotRecreated() throws SQLException {
    String url = urlOf(SharedPostgres.datasourceProperties("reuse_check"));
    execute(url, "CREATE TABLE IF NOT EXISTS marker (id int)");

    String again = urlOf(SharedPostgres.datasourceProperties("reuse_check"));

    assertThat(again).isEqualTo(url);
    assertThat(tableCount(again)).isEqualTo(1);
  }

  private static String urlOf(String[] properties) {
    return properties[0].substring("spring.datasource.url=".length());
  }

  private static Connection connect(String url) throws SQLException {
    return DriverManager.getConnection(url, SharedPostgres.username(), SharedPostgres.password());
  }

  private static void execute(String url, String sql) throws SQLException {
    try (Connection connection = connect(url);
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static int tableCount(String url) throws SQLException {
    try (Connection connection = connect(url);
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")) {
      rows.next();
      return rows.getInt(1);
    }
  }
}

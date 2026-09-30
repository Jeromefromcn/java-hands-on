package com.jerome.slledger.adapter.persistence.collateral;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Two-phase commit's blocking, measured with Postgres's own {@code PREPARE TRANSACTION}. The
 * coordinator is played by the test: it prepares a participant, then "dies" without deciding. The
 * participant cannot decide alone, because it has promised to commit if told to, so it keeps the
 * row lock. Nothing here implements 2PC; it shows what a participant is left holding.
 */
@Timeout(120)
class TwoPhaseCommitBlockingIT {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine")
          .withCommand("postgres", "-c", "max_prepared_transactions=10");

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @Test
  @DisplayName(
      "a prepared transaction whose coordinator has gone holds its row lock until someone rolls it back")
  void reproducesPreparedTransactionBlocksWriters() throws Exception {
    // See docs/decisions/10-messaging.md
    StringBuilder evidence = new StringBuilder();
    try (Connection setup = connect();
        Statement s = setup.createStatement()) {
      s.execute("CREATE TABLE collateral (id int PRIMARY KEY, available numeric NOT NULL)");
      s.execute("INSERT INTO collateral VALUES (1, 1000)");
    }

    // The participant: prepare phase done, "yes" voted. Then the coordinator dies.
    try (Connection participant = connect()) {
      participant.setAutoCommit(false);
      try (Statement s = participant.createStatement()) {
        s.execute("UPDATE collateral SET available = available - 100 WHERE id = 1");
        s.execute("PREPARE TRANSACTION 'tx-1'");
      }
      // The connection closes here. The prepared transaction does not: it is on the server's disk.
    }

    try (Connection observer = connect();
        Statement s = observer.createStatement()) {
      ResultSet rs = s.executeQuery("SELECT gid FROM pg_prepared_xacts");
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isEqualTo("tx-1");
    }

    // Another writer wants the same row. It is willing to wait 2 s; the lock is not released by
    // any timer, so this is what "for as long as it takes" looks like when nothing takes it.
    long waitedMs;
    boolean gaveUp;
    try (Connection other = connect();
        Statement s = other.createStatement()) {
      s.execute("SET lock_timeout = '2s'");
      long started = System.nanoTime();
      try {
        s.execute("UPDATE collateral SET available = available - 10 WHERE id = 1");
        gaveUp = false;
      } catch (SQLException e) {
        gaveUp = "55P03".equals(e.getSQLState()); // lock_not_available
      }
      waitedMs = (System.nanoTime() - started) / 1_000_000;
    }
    assertThat(gaveUp).isTrue();
    assertThat(waitedMs).isGreaterThanOrEqualTo(1_900);

    // A person, or a recovery job, decides. Only then does the row move.
    long afterRollbackMs;
    try (Connection recovery = connect();
        Statement s = recovery.createStatement()) {
      s.execute("ROLLBACK PREPARED 'tx-1'");
      s.execute("SET lock_timeout = '2s'");
      long started = System.nanoTime();
      s.execute("UPDATE collateral SET available = available - 10 WHERE id = 1");
      afterRollbackMs = (System.nanoTime() - started) / 1_000_000;
    }
    assertThat(afterRollbackMs).isLessThan(1_000);

    evidence
        .append(
            "2PC participant prepared, coordinator gone: pg_prepared_xacts holds tx-1, connection closed\n")
        .append("another writer of the same row, lock_timeout 2 s: gave up after ")
        .append(waitedMs)
        .append(
            " ms without the row (lock_not_available); with no timeout it waits for as long as the prepared transaction exists\n")
        .append("ROLLBACK PREPARED 'tx-1' by hand, then the same update: ")
        .append(afterRollbackMs)
        .append(" ms\n");
    Evidence.write("10-2pc-blocking.txt", evidence.toString());
  }
}

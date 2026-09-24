package com.jerome.slledger.bootstrap.demo.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.jerome.slledger.bootstrap.demo.tx.propagation.DailyAccrualBatch;
import com.jerome.slledger.bootstrap.demo.tx.propagation.PositionAccrual;
import com.jerome.slledger.bootstrap.demo.tx.support.AccrualEntryRows;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.demo.tx.support.PositionAccrualStep;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemo;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemoRows;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLTransientConnectionException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * A daily accrual batch: one outer transaction records the run, each position accrues in its own
 * {@code REQUIRES_NEW} transaction, and P3's negative quantity makes {@code domain} refuse it.
 */
@Timeout(30)
class RequiresNewAccrualIT {

  private static final LocalDate ON = LocalDate.of(2026, 3, 16);
  private static final Map<String, String> MEASURED = new ConcurrentHashMap<>();

  @Test
  @DisplayName(
      "one failing position does not undo the others or the run: P3 fails, P1 P2 P4 P5 commit")
  void failingPositionIsIsolated() {
    try (var context = startBatch()) {
      seedPositions(context.getBean(TxDemoRows.class));

      List<String> failed = context.getBean(DailyAccrualBatch.class).run(ON);

      assertThat(failed).containsExactly("P3");
      assertThat(accruedPositions(context)).containsExactly("P1", "P2", "P4", "P5");
      assertThat(context.getBean(TxDemoRows.class).accrualRunCount()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName(
      "REQUIRES_NEW holds two connections at once: the suspended outer one and the inner one;"
          + " both are back in the pool afterwards")
  void requiresNewHoldsTwoConnections() {
    try (var context = startBatch()) {
      seedPositions(context.getBean(TxDemoRows.class));

      context.getBean(DailyAccrualBatch.class).run(ON);

      int inside = context.getBean(PositionAccrual.class).activeConnectionsInside();
      int after = activeConnections(context);
      assertThat(inside).isEqualTo(2);
      assertThat(after).isZero();

      MEASURED.put("active connections inside the inner transaction", String.valueOf(inside));
      MEASURED.put("active connections after run()", String.valueOf(after));
    }
  }

  // With one connection in the pool, the outer transaction holds it and the inner REQUIRES_NEW
  // waits for a second one that can never come. It fails only because connectionTimeout bounds
  // the wait. See docs/decisions/05-aop-transactions.md
  @Test
  @DisplayName(
      "with a pool of one, REQUIRES_NEW starves: the inner transaction times out waiting for a"
          + " second connection")
  void reproducesRequiresNewPoolStarvation() {
    String[] poolOfOne = {
      "spring.datasource.hikari.maximum-pool-size=1",
      "spring.datasource.hikari.connection-timeout=1000"
    };
    try (var context =
        TxDemo.start(
            poolOfOne,
            DailyAccrualBatch.class,
            PositionAccrual.class,
            PositionAccrualStep.class,
            AccrualEntryRows.class)) {
      seedPositions(context.getBean(TxDemoRows.class));
      DailyAccrualBatch batch = context.getBean(DailyAccrualBatch.class);

      long started = System.nanoTime();
      Throwable thrown = catchThrowable(() -> batch.run(ON));
      long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

      assertThat(thrown)
          .isInstanceOf(CannotCreateTransactionException.class)
          .hasRootCauseInstanceOf(SQLTransientConnectionException.class);
      assertThat(elapsedMillis).isGreaterThanOrEqualTo(1000);
      assertThat(activeConnections(context)).isZero();
      // The outer transaction rolled back too: the exception is not a DomainException.
      assertThat(context.getBean(TxDemoRows.class).accrualRunCount()).isZero();

      MEASURED.put(
          "pool of 1, connectionTimeout 1000 ms: wait before failure (ms)", elapsedMillis + "");
      MEASURED.put("pool of 1: exception", thrown.getClass().getName());
      MEASURED.put("pool of 1: root cause", rootCause(thrown).toString());
    }
  }

  private static Throwable rootCause(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  private static ConfigurableApplicationContext startBatch() {
    return TxDemo.start(
        DailyAccrualBatch.class,
        PositionAccrual.class,
        PositionAccrualStep.class,
        AccrualEntryRows.class);
  }

  /** P3's negative quantity is rejected by domain, not by the database or a test-only switch. */
  private static void seedPositions(TxDemoRows rows) {
    rows.insertPosition("P1", 1000);
    rows.insertPosition("P2", 2000);
    rows.insertPosition("P3", -1);
    rows.insertPosition("P4", 4000);
    rows.insertPosition("P5", 5000);
  }

  private static List<String> accruedPositions(ConfigurableApplicationContext context) {
    return context
        .getBean(JdbcClient.class)
        .sql("SELECT position_id FROM accrual_entry ORDER BY position_id")
        .query(String.class)
        .list();
  }

  private static int activeConnections(ConfigurableApplicationContext context) {
    return context.getBean(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections();
  }

  @AfterAll
  static void writeEvidence() {
    StringBuilder out = new StringBuilder("RequiresNewAccrualIT (HikariCP, measured)\n\n");
    MEASURED.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(e -> out.append(e.getKey()).append(": ").append(e.getValue()).append("\n"));
    Evidence.write("05-requires-new-connections.txt", out.toString());
  }
}

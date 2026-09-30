package com.jerome.slledger.bootstrap.position;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.SharedPostgres;
import com.jerome.slledger.application.position.ReturnCommand;
import com.jerome.slledger.application.position.ReturnPositionOnce;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLTransientConnectionException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercise 9: three use-case calls, each holding its transaction open, on a pool of two. The third
 * waits for the pool's connection timeout and fails. Each call returns against a position of its
 * own, so the two holders never wait on each other's row lock, only on the pool.
 */
class PoolExhaustionIT {

  private static final LocalDate TRADE_DATE = LocalDate.of(2026, 9, 1);

  @BeforeAll
  static void container() {
    SharedPostgres.start();
  }

  @Test
  @Timeout(30)
  void theThirdCallerWaitsOutTheTimeoutAndFails() throws Exception {
    try (ConfigurableApplicationContext context =
        LedgerApp.start(
            "--spring.datasource.hikari.maximum-pool-size=2",
            "--spring.datasource.hikari.connection-timeout=1000")) {
      JdbcClient jdbc = context.getBean(JdbcClient.class);
      LedgerApp.referenceData(jdbc);
      jdbc.sql(
              "INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
                  + " outstanding_quantity, status, collateral_amount, collateral_currency,"
                  + " rule_type, flat_rate)"
                  + " SELECT 'P' || n, 'CP-001', 'HK0000000001', 100, 100, 'OPEN', 1000.00, 'HKD',"
                  + " 'FLAT', 0.0365 FROM generate_series(1, 3) n")
          .update();
      ReturnPositionOnce once = context.getBean(ReturnPositionOnce.class);
      TransactionTemplate tx = context.getBean(TransactionTemplate.class);
      HikariDataSource pool = context.getBean(DataSource.class).unwrap(HikariDataSource.class);

      CountDownLatch holding = new CountDownLatch(2);
      CountDownLatch release = new CountDownLatch(1);
      ExecutorService threads = Executors.newFixedThreadPool(3);
      try {
        List<Future<?>> holders = new ArrayList<>();
        for (String id : List.of("P1", "P2")) {
          holders.add(
              threads.submit(
                  () ->
                      tx.executeWithoutResult(
                          status -> {
                            once.apply(returnOf(id));
                            holding.countDown();
                            await(release);
                          })));
        }
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
        int activeWhileHeld = pool.getHikariPoolMXBean().getActiveConnections();

        long started = System.nanoTime();
        Future<?> third =
            threads.submit(() -> tx.executeWithoutResult(s -> once.apply(returnOf("P3"))));
        Throwable failure = null;
        try {
          third.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
          failure = e.getCause();
        }
        long waitedMillis = (System.nanoTime() - started) / 1_000_000;

        release.countDown();
        for (Future<?> holder : holders) {
          holder.get(10, TimeUnit.SECONDS);
        }

        Throwable root = NestedExceptionUtils.getRootCause(failure);
        assertThat(activeWhileHeld).isEqualTo(2);
        assertThat(root).isInstanceOf(SQLTransientConnectionException.class);
        assertThat(root.getMessage()).contains("total=2, active=2");
        assertThat(waitedMillis).isGreaterThanOrEqualTo(1000);
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(outstanding(jdbc, "P1")).isEqualTo(90);
        assertThat(outstanding(jdbc, "P2")).isEqualTo(90);
        assertThat(outstanding(jdbc, "P3")).isEqualTo(100);

        Evidence.write(
            "06-pool-exhaustion.txt",
            ("cores (Runtime.availableProcessors)=%d, JDK %s%n"
                    + "maximum-pool-size=2, connection-timeout=1000 ms%n"
                    + "active connections while two transactions held: %d%n"
                    + "third caller waited %d ms, then:%n"
                    + "  %s%n"
                    + "  root cause %s: %s%n"
                    + "active connections after release: %d%n"
                    + "outstanding after: P1=%d P2=%d P3=%d%n")
                .formatted(
                    Runtime.getRuntime().availableProcessors(),
                    Runtime.version(),
                    activeWhileHeld,
                    waitedMillis,
                    failure.getClass().getName(),
                    root.getClass().getName(),
                    root.getMessage(),
                    pool.getHikariPoolMXBean().getActiveConnections(),
                    outstanding(jdbc, "P1"),
                    outstanding(jdbc, "P2"),
                    outstanding(jdbc, "P3")));
      } finally {
        release.countDown();
        threads.shutdownNow();
      }
    }
  }

  private static ReturnCommand returnOf(String id) {
    return new ReturnCommand(new IdempotencyKey("R-" + id), new LoanPositionId(id), 10, TRADE_DATE);
  }

  private static long outstanding(JdbcClient jdbc, String id) {
    return jdbc.sql("SELECT outstanding_quantity FROM loan_position WHERE id = ?")
        .param(id)
        .query(Long.class)
        .single();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(20, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

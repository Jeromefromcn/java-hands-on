package com.jerome.slledger.bootstrap.demo.tx;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.SharedPostgres;
import com.jerome.slledger.bootstrap.demo.tx.isolation.ConcurrentUpdateException;
import com.jerome.slledger.bootstrap.demo.tx.isolation.InsideTxRetryingReturn;
import com.jerome.slledger.bootstrap.demo.tx.isolation.OptimisticReturn;
import com.jerome.slledger.bootstrap.demo.tx.isolation.ReadCommittedReturn;
import com.jerome.slledger.bootstrap.demo.tx.isolation.ReadModifyWriteReturn;
import com.jerome.slledger.bootstrap.demo.tx.isolation.RepeatableReadReturn;
import com.jerome.slledger.bootstrap.demo.tx.isolation.RetryingReturn;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemo;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemoRows;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Two returns against P1 (outstanding 100), of 10 and 20, racing. A barrier makes both threads read
 * before either writes, so every run takes the interleaving that loses an update.
 */
@Timeout(30)
class IsolationIT {

  // Container start-up, including a first image pull, happens here: outside the test timeout.
  @BeforeAll
  static void startPostgres() {
    SharedPostgres.start();
  }

  private static final Map<String, String> BALANCES = new TreeMap<>();

  /** One thread's return: runs with the shared barrier, returns normally or throws. */
  @FunctionalInterface
  interface Return {
    void run(String id, long quantity, CyclicBarrier afterRead) throws Exception;
  }

  // Both transactions read 100; one writes 90, the other writes 80 over it. Neither fails, and
  // one return has vanished from the balance. See docs/decisions/05-aop-transactions.md
  @Test
  @DisplayName("READ COMMITTED loses an update: both returns succeed, the balance shows one")
  void reproducesLostUpdateUnderReadCommitted() throws Exception {
    try (var context = TxDemo.start(ReadCommittedReturn.class)) {
      TxDemoRows rows = context.getBean(TxDemoRows.class);
      ReadModifyWriteReturn service = context.getBean(ReadCommittedReturn.class);
      rows.insertPosition("P1", 100);

      List<Throwable> failures = race(service::returnQuantity);

      assertThat(failures).isEmpty();
      assertThat(rows.outstanding("P1")).isIn(90L, 80L);
      record("READ COMMITTED, read-modify-write", rows, failures);
    }
  }

  @Test
  @DisplayName("REPEATABLE READ rejects the second writer with 40001 instead of losing its update")
  void repeatableReadRejectsSecondWriter() throws Exception {
    try (var context = TxDemo.start(RepeatableReadReturn.class)) {
      TxDemoRows rows = context.getBean(TxDemoRows.class);
      ReadModifyWriteReturn service = context.getBean(RepeatableReadReturn.class);
      rows.insertPosition("P1", 100);

      List<Throwable> failures = race(service::returnQuantity);

      assertThat(failures).hasSize(1);
      assertThat(sqlState(failures.get(0))).isEqualTo("40001");
      assertThat(rows.outstanding("P1")).isIn(90L, 80L);
      record("REPEATABLE READ, read-modify-write", rows, failures);
    }
  }

  @Test
  @DisplayName(
      "optimistic lock with the retry outside the transaction lands both returns: 70, version 2")
  void optimisticLockWithRetryOutsideTransactionLandsBoth() throws Exception {
    try (var context = TxDemo.start(OptimisticReturn.class, RetryingReturn.class)) {
      TxDemoRows rows = context.getBean(TxDemoRows.class);
      RetryingReturn service = context.getBean(RetryingReturn.class);
      rows.insertPosition("P1", 100);

      List<Throwable> failures = race(service::returnQuantity);

      assertThat(failures).isEmpty();
      assertThat(rows.outstanding("P1")).isEqualTo(70);
      assertThat(rows.version("P1")).isEqualTo(2);
      record("optimistic lock, retry outside the transaction", rows, failures);
    }
  }

  // Under REPEATABLE READ the loser's first UPDATE fails with 40001, and Postgres then refuses
  // every further statement in that transaction with 25P02. A retry inside it cannot succeed.
  // See docs/decisions/05-aop-transactions.md
  @Test
  @DisplayName(
      "a retry inside the REPEATABLE READ transaction never succeeds: 40001, then 25P02 on retry")
  void reproducesRetryInsideTransactionNeverSucceeds() throws Exception {
    try (var context = TxDemo.start(InsideTxRetryingReturn.class)) {
      TxDemoRows rows = context.getBean(TxDemoRows.class);
      InsideTxRetryingReturn service = context.getBean(InsideTxRetryingReturn.class);
      rows.insertPosition("P1", 100);

      List<Throwable> failures = race(service::returnQuantity);

      assertThat(failures).hasSize(1);
      assertThat(failures.get(0)).isInstanceOf(ConcurrentUpdateException.class);
      List<String> attempts = ((ConcurrentUpdateException) failures.get(0)).attempts();
      assertThat(attempts).first().isEqualTo("40001");
      assertThat(attempts.subList(1, attempts.size())).isNotEmpty().containsOnly("25P02");
      assertThat(rows.outstanding("P1")).isIn(90L, 80L);
      record("REPEATABLE READ, retry inside the transaction", rows, failures);
    }
  }

  /** Runs the returns of 10 and 20 on two threads; returns what they threw, if anything. */
  private static List<Throwable> race(Return service) throws InterruptedException {
    CyclicBarrier afterRead = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> calls = new ArrayList<>();
      for (long quantity : new long[] {10, 20}) {
        calls.add(
            executor.submit(
                () -> {
                  service.run("P1", quantity, afterRead);
                  return null;
                }));
      }
      List<Throwable> failures = new ArrayList<>();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      for (Future<?> call : calls) {
        try {
          // One deadline for both calls, inside the class's 30 s timeout.
          call.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
          failures.add(e.getCause());
        } catch (TimeoutException e) {
          throw new AssertionError("a return did not finish", e);
        }
      }
      return failures;
    } finally {
      executor.shutdownNow();
    }
  }

  private static String sqlState(Throwable failure) {
    Throwable cause = failure;
    while (cause != null && !(cause instanceof SQLException)) {
      cause = cause.getCause();
    }
    return cause == null ? null : ((SQLException) cause).getSQLState();
  }

  private static void record(String scenario, TxDemoRows rows, List<Throwable> failures) {
    String failed =
        failures.isEmpty()
            ? "none"
            : failures.stream()
                .<String>map(
                    f ->
                        f instanceof ConcurrentUpdateException c
                            ? "ConcurrentUpdateException, attempts " + c.attempts()
                            : f.getClass().getSimpleName() + " SQLState " + sqlState(f))
                .toList()
                .toString();
    BALANCES.put(
        scenario,
        "before 100 (version 0), returns of 10 and 20 -> after "
            + rows.outstanding("P1")
            + " (version "
            + rows.version("P1")
            + "); failed: "
            + failed);
  }

  @AfterAll
  static void writeEvidence() {
    StringBuilder out = new StringBuilder("IsolationIT: P1 at 100, two concurrent returns\n\n");
    BALANCES.forEach(
        (scenario, result) -> out.append(scenario).append("\n  ").append(result).append("\n"));
    Evidence.write("05-lost-update.txt", out.toString());
  }
}

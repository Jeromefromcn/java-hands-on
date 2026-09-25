package com.jerome.slledger.bootstrap.demo.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.persistence.testing.SharedPostgres;
import com.jerome.slledger.bootstrap.demo.tx.idempotency.CheckInsideTx;
import com.jerome.slledger.bootstrap.demo.tx.idempotency.CheckOutsideTx;
import com.jerome.slledger.bootstrap.demo.tx.idempotency.DuplicateRequestException;
import com.jerome.slledger.bootstrap.demo.tx.idempotency.IdempotencyAspect;
import com.jerome.slledger.bootstrap.demo.tx.idempotency.IdempotentReturn;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemo;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemoRows;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * An {@code @Idempotent} check that inserts the request key, and {@code @Transactional}, on the
 * same method. Which one is on the outside decides whether the key insert is part of the business
 * transaction or committed on its own before it.
 */
@Timeout(30)
class IdempotencyOrderingIT {

  // Container start-up, including a first image pull, happens here: outside the test timeout.
  @BeforeAll
  static void startPostgres() {
    SharedPostgres.start();
  }

  private static final Map<String, String> OUTCOMES = new TreeMap<>();

  @Test
  @DisplayName(
      "check outside the transaction, concurrent duplicate: exactly one runs; the loser is"
          + " rejected at once, while the winner's transaction is still open")
  void concurrentDuplicateWithCheckOutside() throws Exception {
    try (var context = start(CheckOutsideTx.class)) {
      Race race = raceSameKey(context);

      assertThat(race.successes()).isEqualTo(1);
      assertThat(race.duplicates()).isEqualTo(1);
      // Rejected before the winner's business work had committed or failed. Not asserted:
      // loserBlockedOnLock == false. Two autocommit inserts of the same key can overlap for
      // microseconds, and a poll landing there would see a real, harmless lock wait.
      assertThat(race.loserFinishedWhileWinnerHeld()).isTrue();
      assertThat(context.getBean(TxDemoRows.class).tradeCount("P1")).isEqualTo(1);
      assertThat(context.getBean(IdempotencyAspect.class).transactionActiveAtCheck()).isFalse();
      OUTCOMES.put("check outside tx, concurrent duplicate", race.toString());
    }
  }

  @Test
  @DisplayName(
      "check inside the transaction, concurrent duplicate: exactly one runs; the loser's insert"
          + " waits on the winner's uncommitted key, then conflicts")
  void concurrentDuplicateWithCheckInside() throws Exception {
    try (var context = start(CheckInsideTx.class)) {
      Race race = raceSameKey(context);

      assertThat(race.successes()).isEqualTo(1);
      assertThat(race.duplicates()).isEqualTo(1);
      assertThat(race.loserBlockedOnLock()).isTrue();
      assertThat(race.loserFinishedWhileWinnerHeld()).isFalse();
      assertThat(context.getBean(TxDemoRows.class).tradeCount("P1")).isEqualTo(1);
      assertThat(context.getBean(IdempotencyAspect.class).transactionActiveAtCheck()).isTrue();
      OUTCOMES.put("check inside tx, concurrent duplicate", race.toString());
    }
  }

  @Test
  @DisplayName(
      "check inside the transaction, retry after a business failure: the key rolled back with"
          + " the business, so the retry runs")
  void retryAfterFailureWithCheckInside() {
    try (var context = start(CheckInsideTx.class)) {
      IdempotentReturn service = context.getBean(IdempotentReturn.class);
      TxDemoRows rows = context.getBean(TxDemoRows.class);

      assertThatThrownBy(() -> service.returnQuantity("K1", "P1", 10, true))
          .isInstanceOf(IllegalStateException.class);
      service.returnQuantity("K1", "P1", 10, false);

      assertThat(rows.tradeCount("P1")).isEqualTo(1);
      assertThat(keyRows(context, "K1")).isEqualTo(1);
      assertThat(context.getBean(IdempotencyAspect.class).transactionActiveAtCheck()).isTrue();
      OUTCOMES.put(
          "check inside tx, retry after failure",
          "retry ran; trades=" + rows.tradeCount("P1") + ", key rows=" + keyRows(context, "K1"));
    }
  }

  // The key was committed in autocommit before the business transaction began, and the business
  // rollback cannot reach it. The retry of a request that never took effect is refused as a
  // duplicate. See docs/decisions/05-aop-transactions.md
  @Test
  @DisplayName(
      "check outside the transaction, retry after a business failure: the key survived the"
          + " rollback, so the retry is wrongly rejected")
  void reproducesRetryRejectedAfterRollback() {
    try (var context = start(CheckOutsideTx.class)) {
      IdempotentReturn service = context.getBean(IdempotentReturn.class);
      TxDemoRows rows = context.getBean(TxDemoRows.class);

      assertThatThrownBy(() -> service.returnQuantity("K1", "P1", 10, true))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> service.returnQuantity("K1", "P1", 10, false))
          .isInstanceOf(DuplicateRequestException.class);

      assertThat(rows.tradeCount("P1")).isZero();
      assertThat(keyRows(context, "K1")).isEqualTo(1);
      assertThat(context.getBean(IdempotencyAspect.class).transactionActiveAtCheck()).isFalse();
      OUTCOMES.put(
          "check outside tx, retry after failure",
          "retry rejected as duplicate; trades="
              + rows.tradeCount("P1")
              + ", key rows="
              + keyRows(context, "K1"));
    }
  }

  record Race(
      int successes,
      int duplicates,
      boolean loserBlockedOnLock,
      boolean loserFinishedWhileWinnerHeld) {}

  /**
   * Two threads, same key, released together. The winner is held inside its business work (after
   * its key insert, before its commit) while this thread watches for the loser. The loser either
   * finishes on its own (rejected already) or shows up in pg_stat_activity waiting on a lock.
   */
  private static Race raceSameKey(ConfigurableApplicationContext context) throws Exception {
    IdempotentReturn service = context.getBean(IdempotentReturn.class);
    JdbcClient jdbc = context.getBean(JdbcClient.class);
    CountDownLatch holdWinner = new CountDownLatch(1);
    service.holdInsideBusinessWork(holdWinner);
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> calls = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        calls.add(
            executor.submit(
                () -> {
                  start.await(10, TimeUnit.SECONDS);
                  service.returnQuantity("K1", "P1", 10, false);
                  return null;
                }));
      }

      // Observation, bounded at 50 x 100 ms: the scenario orders itself.
      boolean loserBlockedOnLock = false;
      boolean loserFinishedWhileWinnerHeld = false;
      for (int attempt = 0; attempt < 50; attempt++) {
        if (calls.stream().anyMatch(Future::isDone)) {
          // Only the loser can finish here: the winner is held until countDown below.
          loserFinishedWhileWinnerHeld = true;
          break;
        }
        if (backendsWaitingOnLock(jdbc) > 0) {
          loserBlockedOnLock = true;
          break;
        }
        TimeUnit.MILLISECONDS.sleep(100);
      }
      holdWinner.countDown();

      int successes = 0;
      int duplicates = 0;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      for (Future<?> call : calls) {
        try {
          // One deadline for both calls, inside the class's 30 s timeout.
          call.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
          successes++;
        } catch (ExecutionException e) {
          assertThat(e.getCause()).isInstanceOf(DuplicateRequestException.class);
          duplicates++;
        }
      }
      return new Race(successes, duplicates, loserBlockedOnLock, loserFinishedWhileWinnerHeld);
    } finally {
      service.holdInsideBusinessWork(null);
      executor.shutdownNow();
    }
  }

  private static long backendsWaitingOnLock(JdbcClient jdbc) {
    return jdbc.sql(
            "SELECT count(*) FROM pg_stat_activity"
                + " WHERE datname = current_database() AND wait_event_type = 'Lock'")
        .query(Long.class)
        .single();
  }

  private static long keyRows(ConfigurableApplicationContext context, String key) {
    return context
        .getBean(JdbcClient.class)
        .sql("SELECT count(*) FROM idempotency_key WHERE key = ?")
        .param(key)
        .query(Long.class)
        .single();
  }

  private static ConfigurableApplicationContext start(Class<?> ordering) {
    ConfigurableApplicationContext context = TxDemo.start(ordering, IdempotentReturn.class);
    context.getBean(TxDemoRows.class).insertPosition("P1", 100);
    return context;
  }

  @AfterAll
  static void writeEvidence() {
    StringBuilder out =
        new StringBuilder(
            "IdempotencyOrderingIT: @Idempotent (key insert) x @Transactional, both orderings\n\n");
    OUTCOMES.forEach(
        (scenario, outcome) -> out.append(scenario).append("\n  ").append(outcome).append("\n"));
    Evidence.write("05-idempotency-ordering.txt", out.toString());
  }
}

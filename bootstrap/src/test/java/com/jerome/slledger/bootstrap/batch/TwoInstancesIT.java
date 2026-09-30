package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.SharedRedis;
import com.jerome.slledger.application.cache.StaleFencingTokenException;
import com.jerome.slledger.batch.accrual.RunAccrual;
import com.jerome.slledger.batch.accrual.RunDailyAccrual;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Two application instances, each a context of its own with its own pool, job repository and lock
 * client, on one Postgres and one Redis. They are in one JVM, which is what a test can do without a
 * second packaging of the application; nothing between them is shared but the two servers.
 */
@Timeout(600)
class TwoInstancesIT {

  private static final int POSITIONS = 1_000;

  private static final StringBuilder BOTH = new StringBuilder();
  private static final StringBuilder ZOMBIE = new StringBuilder();

  private static BatchLedger a;
  private static BatchLedger b;
  private static InstanceProbe.Control controlA;
  private static InstanceProbe.Control controlB;
  private static RunDailyAccrual cutoverA;
  private static RunDailyAccrual cutoverB;

  @BeforeAll
  static void start() {
    SharedRedis.start();
    String[] common = {
      "--ledger.cache.enabled=true",
      "--spring.data.redis.host=" + SharedRedis.host(),
      "--spring.data.redis.port=" + SharedRedis.port(),
      "--ledger.batch.chunk-size=50",
      "--ledger.batch.lock-ttl=2s"
    };
    a = BatchLedger.start(List.of(InstanceProbe.class), common);
    b = BatchLedger.startSharing(a, List.of(InstanceProbe.class), common);
    controlA = a.bean(InstanceProbe.Control.class);
    controlB = b.bean(InstanceProbe.Control.class);
    cutoverA = a.bean(RunDailyAccrual.class);
    cutoverB = b.bean(RunDailyAccrual.class);
  }

  @AfterAll
  static void stop() {
    Evidence.write("11-two-instances.txt", BOTH.toString());
    Evidence.write("11-zombie-refused.txt", ZOMBIE.toString());
    b.close();
    a.close();
  }

  @BeforeEach
  void fresh() {
    a.resetLedger();
    a.resetBatchMetadata();
    a.jdbc.sql("DELETE FROM write_fence").update();
    a.seed(POSITIONS);
    SharedRedis.flushAll();
  }

  @AfterEach
  void reset() {
    controlA.reset();
    controlB.reset();
  }

  private static <T> List<T> together(List<Callable<T>> calls) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(calls.size());
    try {
      CyclicBarrier barrier = new CyclicBarrier(calls.size());
      List<Future<T>> futures = new ArrayList<>();
      for (Callable<T> call : calls) {
        futures.add(
            pool.submit(
                () -> {
                  barrier.await(30, TimeUnit.SECONDS);
                  return call.call();
                }));
      }
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(120, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "two instances launching one date together: one runs, the other is told it is not the owner")
  void onlyOneInstanceRunsTheCutover() throws Exception {
    controlA.sleepMillisPerChunk =
        100; // 20 chunks: the winner holds the lock for about two seconds
    controlB.sleepMillisPerChunk = 100;

    List<RunAccrual.Outcome> outcomes =
        together(List.of(() -> cutoverA.run(BatchLedger.DAY), () -> cutoverB.run(BatchLedger.DAY)));

    List<RunAccrual.Status> statuses = outcomes.stream().map(RunAccrual.Outcome::status).toList();
    assertThat(statuses)
        .containsExactlyInAnyOrder(RunAccrual.Status.COMPLETED, RunAccrual.Status.NOT_OWNER);
    assertThat(a.count("accrual_entry")).isEqualTo(POSITIONS);
    assertThat(
            a.jdbc
                .sql("SELECT count(DISTINCT position_id) FROM accrual_entry")
                .query(Long.class)
                .single())
        .isEqualTo((long) POSITIONS);
    assertThat(a.count("batch_job_execution")).isEqualTo(1);
    boolean aWon = outcomes.get(0).status() == RunAccrual.Status.COMPLETED;
    InstanceProbe.Control winner = aWon ? controlA : controlB;
    InstanceProbe.Control loser = aWon ? controlB : controlA;
    assertThat(winner.bookCalls.get()).isEqualTo(20);
    assertThat(loser.bookCalls.get()).isZero();
    long fenceToken =
        a.jdbc
            .sql("SELECT token FROM write_fence WHERE resource = ?")
            .param(RunDailyAccrual.resource(BatchLedger.DAY))
            .query(Long.class)
            .single();
    BOTH.append(
        """
        Stage 11, section 9: two application instances (two contexts, each with its own pool, job
        repository and Redis client) launch the accrual for 2026-09-30 at the same instant, on one
        Postgres and one Redis. 1,000 positions, chunk size 50, 100 ms of work per chunk so the
        owner holds the lock for about two seconds.

        instance A : %s (book calls %d)
        instance B : %s (book calls %d)
        accrual_entry rows: %d (distinct positions %d); batch_job_execution rows: %d
        fence token stored for %s: %d (the winner's)
        """
            .formatted(
                outcomes.get(0).status(),
                controlA.bookCalls.get(),
                outcomes.get(1).status(),
                controlB.bookCalls.get(),
                a.count("accrual_entry"),
                POSITIONS,
                a.count("batch_job_execution"),
                RunDailyAccrual.resource(BatchLedger.DAY),
                fenceToken));
  }

  /** Waits for the lock's key to expire, which is what makes the paused owner a zombie. */
  private static void awaitLockExpiry() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    try (SharedRedis.Client redis = SharedRedis.client()) {
      while (Boolean.TRUE.equals(
          redis.template().hasKey("sl:lock:" + RunDailyAccrual.resource(BatchLedger.DAY)))) {
        if (System.nanoTime() > deadline) {
          throw new AssertionError("the lock did not expire");
        }
        Thread.sleep(50);
      }
    }
  }

  private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (type.isInstance(t)) {
        return true;
      }
    }
    return false;
  }

  @Test
  @DisplayName(
      "an owner paused past its lease is refused at the fence when it wakes, and writes nothing")
  void pausedOwnerIsRefusedAtTheFence() throws Exception {
    controlA.pauseAtChunk = 3; // chunks 1 and 2 (100 entries) commit; chunk 3 is about to write
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<RunAccrual.Outcome> ownerA = pool.submit(() -> cutoverA.run(BatchLedger.DAY));
      controlA.awaitReached();
      assertThat(a.count("accrual_entry")).isEqualTo(100);
      long tokenA =
          a.jdbc
              .sql("SELECT token FROM write_fence WHERE resource = ?")
              .param(RunDailyAccrual.resource(BatchLedger.DAY))
              .query(Long.class)
              .single();
      awaitLockExpiry();

      RunAccrual.Outcome takeover = cutoverB.run(BatchLedger.DAY);

      assertThat(takeover.status()).isEqualTo(RunAccrual.Status.COMPLETED);
      assertThat(a.count("accrual_entry")).isEqualTo(POSITIONS);
      long tokenB =
          a.jdbc
              .sql("SELECT token FROM write_fence WHERE resource = ?")
              .param(RunDailyAccrual.resource(BatchLedger.DAY))
              .query(Long.class)
              .single();
      assertThat(tokenB).isGreaterThan(tokenA);
      int callsBeforeWaking = controlA.bookCalls.get();

      controlA.resume.countDown();
      Throwable refused = null;
      RunAccrual.Outcome woken = null;
      try {
        woken = ownerA.get(60, TimeUnit.SECONDS);
      } catch (Exception e) {
        refused = e;
      }

      boolean stale =
          refused != null
              ? causedBy(refused, StaleFencingTokenException.class)
              : woken.execution().getAllFailureExceptions().stream()
                  .anyMatch(t -> causedBy(t, StaleFencingTokenException.class));
      assertThat(stale).as("the woken owner's chunk was refused by the fence").isTrue();
      assertThat(controlA.bookCalls.get())
          .isEqualTo(callsBeforeWaking); // it never reached the ledger
      assertThat(a.count("accrual_entry")).isEqualTo(POSITIONS);
      ZOMBIE.append(
          """
          Stage 11, section 9: owner A takes the lock (2 s lease) and is paused before writing
          chunk 3 (a latch stands in for a stop-the-world pause). Its lock expires; instance B takes
          the lock, finds A's execution still STARTED, marks it failed and resumes the date from
          A's saved cursor; A then wakes. 1,000 positions, chunk size 50, two Postgres and Redis
          contexts as in 11-two-instances.txt.

          fence token of A            : %d
          fence token of B (the owner): %d
          B                           : %s; entries %d of %d
          A after waking              : refused by the fence (StaleFencingTokenException at its next
                                        chunk); book calls by A: %d before the pause, %d after
          entries at the end          : %d, none added by A
          """
              .formatted(
                  tokenA,
                  tokenB,
                  takeover.status(),
                  a.count("accrual_entry"),
                  POSITIONS,
                  callsBeforeWaking,
                  controlA.bookCalls.get() - callsBeforeWaking,
                  a.count("accrual_entry")));
    } finally {
      controlA.resume.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("reproducesUnfencedZombieContinuingAfterItsLeaseEnded")
  void reproducesUnfencedZombieContinuingAfterItsLeaseEnded() throws Exception {
    // See docs/decisions/11-batch.md
    // The same schedule, with owner A launched without a lock or a token (the launcher used
    // directly): nothing tells A it was superseded, and after B has finished the date A carries on
    // writing. The primary key is all that keeps the ledger right; a write that is not idempotent
    // (stage 9's total = total + 100) would be applied twice.
    controlA.pauseAtChunk = 3;
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      RunAccrual unfencedA = a.bean(RunAccrual.class);
      Future<RunAccrual.Outcome> ownerA = pool.submit(() -> unfencedA.run(BatchLedger.DAY));
      controlA.awaitReached();
      assertThat(a.count("accrual_entry")).isEqualTo(100);

      RunAccrual.Outcome takeover = cutoverB.run(BatchLedger.DAY);
      assertThat(takeover.status()).isEqualTo(RunAccrual.Status.COMPLETED);
      assertThat(a.count("accrual_entry")).isEqualTo(POSITIONS);
      int callsBeforeWaking = controlA.bookCalls.get();

      controlA.resume.countDown();
      try {
        ownerA.get(60, TimeUnit.SECONDS);
      } catch (Exception ignored) {
        // Its own bookkeeping may fail once B has recovered its execution; the writes are the
        // point.
      }

      int attemptedAfterWaking = controlA.bookCalls.get() - callsBeforeWaking;
      assertThat(attemptedAfterWaking)
          .as("the unfenced zombie wrote after being superseded")
          .isPositive();
      assertThat(controlA.rowsAlreadyPresent.get()).isPositive();
      assertThat(a.count("accrual_entry")).isEqualTo(POSITIONS);
      ZOMBIE.append(
          """

          -- the same schedule with A unfenced (launched without a lock or token)
          A after waking: %d book calls after B had finished the date, %d rows presented to the
          ledger and found already present. The ledger is right (%d entries) only because of the
          primary key on (position_id, business_date); nothing stopped A.
          """
              .formatted(
                  attemptedAfterWaking,
                  controlA.rowsAlreadyPresent.get(),
                  a.count("accrual_entry")));
    } finally {
      controlA.resume.countDown();
      pool.shutdownNow();
    }
  }
}

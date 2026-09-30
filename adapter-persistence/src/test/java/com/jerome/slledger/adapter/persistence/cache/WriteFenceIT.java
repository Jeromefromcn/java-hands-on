package com.jerome.slledger.adapter.persistence.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.LedgerDb;
import com.jerome.slledger.adapter.persistence.testing.SharedRedis;
import com.jerome.slledger.application.cache.DistributedLock.Lease;
import com.jerome.slledger.application.cache.StaleFencingTokenException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The lock, the fence and a write that is not idempotent, against real Redis and real Postgres.
 *
 * <p>The protected write is {@code total = total + 100} on a test-only table. It stands in for any
 * write that adds to what is there. {@code accrual_entry}'s primary key would refuse a second entry
 * for the same day, so it is not the table to show this on; the decision record says why the fence
 * matters anyway.
 */
class WriteFenceIT {

  private static final Duration TTL = Duration.ofSeconds(30);
  private static final String RESOURCE = "daily-accrual";

  private static ConfigurableApplicationContext context;

  private JdbcClient jdbc;
  private TransactionTemplate transaction;
  private SharedRedis.Client redis;
  private RedisLock lock;
  private JdbcWriteFence fence;

  @BeforeAll
  static void start() {
    SharedRedis.start();
    context = LedgerDb.start();
    context
        .getBean(JdbcClient.class)
        .sql("CREATE TABLE accrual_total (position_id text PRIMARY KEY, total bigint NOT NULL)")
        .update();
  }

  @AfterAll
  static void stop() {
    context.close();
  }

  @BeforeEach
  void setUp() {
    jdbc = context.getBean(JdbcClient.class);
    transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    fence = context.getBean(JdbcWriteFence.class);
    jdbc.sql("DELETE FROM accrual_total").update();
    jdbc.sql("DELETE FROM write_fence").update();
    jdbc.sql("INSERT INTO accrual_total VALUES ('P1', 0)").update();
    redis = SharedRedis.client();
    lock = new RedisLock(redis.template(), "lock:", "fence:");
  }

  @AfterEach
  void tearDown() {
    SharedRedis.flushAll();
    redis.close();
  }

  @Test
  void admitsIncreasingAndEqualTokens() {
    transaction.executeWithoutResult(s -> fence.admit(RESOURCE, 1));
    transaction.executeWithoutResult(s -> fence.admit(RESOURCE, 1));
    transaction.executeWithoutResult(s -> fence.admit(RESOURCE, 7));

    assertThat(storedToken()).isEqualTo(7L);
  }

  @Test
  void refusesLowerToken() {
    transaction.executeWithoutResult(s -> fence.admit(RESOURCE, 5));

    assertThatThrownBy(() -> transaction.executeWithoutResult(s -> fence.admit(RESOURCE, 4)))
        .isInstanceOf(StaleFencingTokenException.class);
    assertThat(storedToken()).isEqualTo(5L);
  }

  @Test
  void mustRunInsideATransaction() {
    assertThatThrownBy(() -> fence.admit(RESOURCE, 1))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
  }

  @Test
  void refusedAdmitRollsBackTheWriteThatCameBeforeIt() {
    transaction.executeWithoutResult(s -> fence.admit(RESOURCE, 9));

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    s -> {
                      addToTotal();
                      fence.admit(RESOURCE, 3);
                    }))
        .isInstanceOf(StaleFencingTokenException.class);

    assertThat(total()).isZero();
  }

  @Test
  void failedWriteRollsBackTheAdmittedToken() {
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    s -> {
                      fence.admit(RESOURCE, 6);
                      throw new IllegalStateException("the write failed");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(jdbc.sql("SELECT count(*) FROM write_fence").query(Long.class).single()).isZero();
  }

  @Test
  void reproducesFenceRefusingEveryWriteAfterARedisRestartThatLostTheCounters() {
    // See docs/decisions/09-caching.md
    // A Redis without persistence restarts empty: the counters begin again at 1, below the token
    // Postgres has already admitted. The fence fails closed, and stays closed until the counter
    // passes the stored token or write_fence is cleared.
    for (int i = 0; i < 3; i++) {
      Lease lease = lock.tryAcquire(RESOURCE, TTL).orElseThrow();
      write(lease, true);
      lease.release();
    }
    assertThat(storedToken()).isEqualTo(3L);

    SharedRedis.flushAll();
    Lease afterRestart = lock.tryAcquire(RESOURCE, TTL).orElseThrow();

    assertThat(afterRestart.fencingToken()).isEqualTo(1L);
    assertThatThrownBy(() -> write(afterRestart, true))
        .isInstanceOf(StaleFencingTokenException.class);
    assertThat(total()).isEqualTo(300L);
  }

  @Test
  @Timeout(60)
  void reproducesDoubleWriteAfterHolderPause() throws Exception {
    // See docs/decisions/09-caching.md
    Timeline timeline = pausedHolderThenSecondHolder(false);

    assertThat(timeline.total()).isEqualTo(200L);
    Evidence.write("09-fencing.txt", evidenceFile(timeline, pausedHolderThenSecondHolder(true)));
  }

  @Test
  @Timeout(60)
  void fenceRefusesTheStaleHolder() throws Exception {
    Timeline timeline = pausedHolderThenSecondHolder(true);

    assertThat(timeline.total()).isEqualTo(100L);
    assertThat(timeline.staleHolderOutcome()).isEqualTo("refused: StaleFencingTokenException");
    assertThat(timeline.tokenA()).isLessThan(timeline.tokenB());
  }

  private record Timeline(long tokenA, long tokenB, String staleHolderOutcome, long total) {}

  /**
   * Holder A takes the lock and stops (a latch stands in for a stop-the-world pause). The lock
   * expires. Holder B takes it and writes. A wakes, believing it still holds the lock, and writes.
   */
  private Timeline pausedHolderThenSecondHolder(boolean fenced) throws Exception {
    jdbc.sql("UPDATE accrual_total SET total = 0").update();
    jdbc.sql("DELETE FROM write_fence").update();
    SharedRedis.flushAll();

    CountDownLatch aHoldsLock = new CountDownLatch(1);
    CountDownLatch aMayResume = new CountDownLatch(1);
    AtomicReference<Lease> leaseA = new AtomicReference<>();
    AtomicReference<String> aOutcome = new AtomicReference<>();
    long tokenB;
    Thread holderA =
        Thread.ofPlatform()
            .start(
                () -> {
                  Lease lease = lock.tryAcquire(RESOURCE, TTL).orElseThrow();
                  leaseA.set(lease);
                  aHoldsLock.countDown();
                  await(aMayResume);
                  try {
                    write(lease, fenced);
                    aOutcome.set("wrote");
                  } catch (StaleFencingTokenException e) {
                    aOutcome.set("refused: " + e.getClass().getSimpleName());
                  }
                });
    try {
      await(aHoldsLock);
      redis.template().expire("lock:" + RESOURCE, Duration.ofMillis(1));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (Boolean.TRUE.equals(redis.template().hasKey("lock:" + RESOURCE))) {
        if (System.nanoTime() > deadline) {
          throw new IllegalStateException("the lock did not expire");
        }
        Thread.onSpinWait();
      }
      Lease leaseB = lock.tryAcquire(RESOURCE, TTL).orElseThrow();
      tokenB = leaseB.fencingToken();
      write(leaseB, fenced);
    } finally {
      aMayResume.countDown();
      holderA.join(TimeUnit.SECONDS.toMillis(20));
    }
    Lease b = lock.tryAcquire("never-used", TTL).orElseThrow();
    b.release();
    return new Timeline(
        leaseA.get().fencingToken(), leaseA.get().fencingToken() + 1, aOutcome.get(), total());
  }

  private void write(Lease lease, boolean fenced) {
    transaction.executeWithoutResult(
        s -> {
          if (fenced) {
            fence.admit(lease.resource(), lease.fencingToken());
          }
          addToTotal();
        });
  }

  private void addToTotal() {
    jdbc.sql("UPDATE accrual_total SET total = total + 100 WHERE position_id = 'P1'").update();
  }

  private long total() {
    return jdbc.sql("SELECT total FROM accrual_total WHERE position_id = 'P1'")
        .query(Long.class)
        .single();
  }

  private long storedToken() {
    return jdbc.sql("SELECT token FROM write_fence WHERE resource = ?")
        .param(RESOURCE)
        .query(Long.class)
        .single();
  }

  private static String evidenceFile(Timeline unfenced, Timeline fenced) {
    return """
        Stage 9, section 8: two holders, one non-idempotent write (total = total + 100).
        Holder A takes the lock and pauses; its lock expires; holder B takes it and writes;
        A resumes and writes. The pause is a latch in the test, standing in for a stop-the-world
        pause; the real-process version is in labs/09-caching. Real Redis and Postgres.
        One run should add 100 once.

        without a fence : total = %d   (A's token %d, B's token %d; A's write %s)
        with a fence    : total = %d   (A's token %d, B's token %d; A's write %s)
        """
        .formatted(
            unfenced.total(),
            unfenced.tokenA(),
            unfenced.tokenB(),
            unfenced.staleHolderOutcome(),
            fenced.total(),
            fenced.tokenA(),
            fenced.tokenB(),
            fenced.staleHolderOutcome());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(25, TimeUnit.SECONDS)) {
        throw new IllegalStateException("latch never opened");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}

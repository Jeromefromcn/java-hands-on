package com.jerome.slledger.adapter.persistence.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.SharedRedis;
import com.jerome.slledger.application.cache.DistributedLock.Lease;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class RedisLockIT {

  private static final Duration TTL = Duration.ofSeconds(30);

  private SharedRedis.Client redis;
  private RedisLock lock;
  private AtomicLong tokens;

  @BeforeAll
  static void start() {
    SharedRedis.start();
  }

  @BeforeEach
  void connect() {
    redis = SharedRedis.client();
    tokens = new AtomicLong();
    lock = new RedisLock(redis.template(), "lock:", tokens::incrementAndGet);
  }

  @AfterEach
  void clean() {
    SharedRedis.flushAll();
    redis.close();
  }

  @Test
  void exclusiveWhileHeld() {
    Optional<Lease> first = lock.tryAcquire("accrual", TTL);
    Optional<Lease> second = lock.tryAcquire("accrual", TTL);

    assertThat(first).isPresent();
    assertThat(second).isEmpty();
    assertThat(lock.tryAcquire("other-resource", TTL)).isPresent();

    first.orElseThrow().release();
    assertThat(lock.tryAcquire("accrual", TTL)).isPresent();
  }

  @Test
  void lockKeyCarriesTheTtl() {
    lock.tryAcquire("accrual", TTL).orElseThrow();

    assertThat(redis.template().getExpire("lock:accrual", TimeUnit.MILLISECONDS))
        .isBetween(1L, TTL.toMillis());
  }

  @Test
  void expiresOnItsOwn() {
    lock.tryAcquire("accrual", TTL).orElseThrow();

    // Rather than wait out the TTL, shorten the key's remaining life and poll for it to go.
    expire("lock:accrual");

    assertThat(lock.tryAcquire("accrual", TTL)).isPresent();
  }

  @Test
  void tokensIncreaseAcrossAcquisitions() {
    Lease a = lock.tryAcquire("accrual", TTL).orElseThrow();
    a.release();
    Lease b = lock.tryAcquire("accrual", TTL).orElseThrow();
    b.release();
    Lease c = lock.tryAcquire("accrual", TTL).orElseThrow();

    assertThat(a.fencingToken()).isLessThan(b.fencingToken());
    assertThat(b.fencingToken()).isLessThan(c.fencingToken());
    assertThat(lock.tryAcquire("independent", TTL).orElseThrow().fencingToken())
        .isGreaterThan(c.fencingToken());
  }

  @Test
  void releaseTwiceIsANoOp() {
    Lease a = lock.tryAcquire("accrual", TTL).orElseThrow();
    a.release();
    Lease b = lock.tryAcquire("accrual", TTL).orElseThrow();

    a.release();

    assertThat(redis.template().hasKey("lock:accrual")).isTrue();
    assertThat(lock.tryAcquire("accrual", TTL)).isEmpty();
    b.release();
    assertThat(redis.template().hasKey("lock:accrual")).isFalse();
  }

  @Test
  void leaseThatExpiredDoesNotReleaseTheNextHolder() {
    Lease a = lock.tryAcquire("accrual", TTL).orElseThrow();
    expire("lock:accrual");
    Lease b = lock.tryAcquire("accrual", TTL).orElseThrow();

    a.release();

    assertThat(redis.template().hasKey("lock:accrual")).isTrue();
    assertThat(lock.tryAcquire("accrual", TTL)).isEmpty();
    assertThat(b.fencingToken()).isGreaterThan(a.fencingToken());
  }

  @Test
  void reproducesUnlockOfSomeoneElsesLock() {
    // See docs/decisions/09-caching.md
    // A naive lock: SET NX PX to take it, a plain DEL to release it.
    String key = "lock:naive";
    var values = redis.template().opsForValue();
    assertThat(values.setIfAbsent(key, "holder-A", TTL)).isTrue();
    expire(key);
    assertThat(values.setIfAbsent(key, "holder-B", TTL)).isTrue();

    // A finishes late and releases "its" lock.
    redis.template().delete(key);

    assertThat(redis.template().hasKey(key)).isFalse();
    // B still believes it holds the lock, and C takes it: two holders at once.
    assertThat(values.setIfAbsent(key, "holder-C", TTL)).isTrue();

    // The same sequence with RedisLock leaves B's lock alone (leaseThatExpiredDoesNotRelease...).
  }

  @Test
  void keepsNoCounterInRedis() {
    lock.tryAcquire("accrual", TTL).orElseThrow().release();

    assertThat(redis.template().keys("*")).isEmpty();
  }

  @Test
  void aTokenSourceThatFailsDoesNotLeaveTheLockHeld() {
    RedisLock broken =
        new RedisLock(
            redis.template(),
            "lock:",
            () -> {
              throw new IllegalStateException("the database is away");
            });

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> broken.tryAcquire("accrual", TTL))
        .isInstanceOf(IllegalStateException.class);

    assertThat(redis.template().hasKey("lock:accrual")).isFalse();
  }

  /**
   * Holder A takes the lock and stops before drawing a token (a latch stands in for a
   * stop-the-world pause). Its lock expires, holder B takes the lock and draws a token, and A wakes
   * and draws.
   */
  private record Pause(Optional<Lease> paused, Optional<Lease> later, long laterToken) {}

  private Pause pausedBeforeTheDraw(boolean naive) throws Exception {
    CountDownLatch inTheGap = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    AtomicLong counter = new AtomicLong();
    AtomicReference<Boolean> pausing = new AtomicReference<>(true);
    com.jerome.slledger.application.cache.FencingTokens slow =
        () -> {
          if (pausing.getAndSet(false)) {
            inTheGap.countDown();
            await(resume);
          }
          return counter.incrementAndGet();
        };
    com.jerome.slledger.application.cache.FencingTokens quick = counter::incrementAndGet;

    AtomicReference<Optional<Lease>> paused = new AtomicReference<>(Optional.empty());
    Thread a =
        Thread.ofPlatform()
            .start(
                () ->
                    paused.set(
                        naive
                            ? naiveAcquire("accrual", slow)
                            : new RedisLock(redis.template(), "lock:", slow)
                                .tryAcquire("accrual", TTL)));
    Optional<Lease> later;
    try {
      await(inTheGap);
      expire("lock:accrual");
      later =
          naive
              ? naiveAcquire("accrual", quick)
              : new RedisLock(redis.template(), "lock:", quick).tryAcquire("accrual", TTL);
    } finally {
      resume.countDown();
      a.join(TimeUnit.SECONDS.toMillis(20));
    }
    return new Pause(paused.get(), later, later.map(Lease::fencingToken).orElse(-1L));
  }

  /** The two-step acquire without the check: take the lock, then draw, and believe the lock. */
  private Optional<Lease> naiveAcquire(
      String resource, com.jerome.slledger.application.cache.FencingTokens draw) {
    Boolean taken =
        redis
            .template()
            .opsForValue()
            .setIfAbsent("lock:" + resource, "naive-" + System.nanoTime(), TTL);
    if (!Boolean.TRUE.equals(taken)) {
      return Optional.empty();
    }
    long token = draw.next();
    return Optional.of(
        new Lease() {
          @Override
          public String resource() {
            return resource;
          }

          @Override
          public long fencingToken() {
            return token;
          }

          @Override
          public void release() {}
        });
  }

  @Test
  void reproducesPausedHolderWithARedisCounter() throws Exception {
    // See docs/decisions/11-batch.md
    Pause pause = pausedBeforeTheDraw(true);

    assertThat(pause.later()).isPresent();
    assertThat(pause.paused()).isPresent();
    // The holder that lost its lock returns with a token above the current holder's: its write is
    // admitted by the fence and the current holder's is refused.
    assertThat(pause.paused().orElseThrow().fencingToken()).isGreaterThan(pause.laterToken());
    Evidence.write(
        "11-fence-paused-holder.txt",
        """
        Stage 11, section 1: a holder is paused between taking the lock and drawing its token.
        Its lock expires, a second holder takes the lock and draws, the first wakes and draws.
        The pause is a latch in the test. Real Redis.

        two-step acquire, no check : first holder token %d, second holder token %d  (first holder has a lease)
        """
            .formatted(pause.paused().orElseThrow().fencingToken(), pause.laterToken()));
  }

  @Test
  void pausedHolderGetsNoLease() throws Exception {
    Pause pause = pausedBeforeTheDraw(false);

    assertThat(pause.paused()).isEmpty();
    assertThat(pause.later()).isPresent();
    assertThat(redis.template().hasKey("lock:accrual")).isTrue();
  }

  private void expire(String key) {
    redis.template().expire(key, Duration.ofMillis(1));
    awaitGone(key);
  }

  private void awaitGone(String key) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (Boolean.TRUE.equals(redis.template().hasKey(key))) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException(key + " did not expire");
      }
      Thread.onSpinWait();
    }
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

package com.jerome.slledger.adapter.persistence.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.SharedRedis;
import com.jerome.slledger.application.cache.DistributedLock.Lease;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
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

  @BeforeAll
  static void start() {
    SharedRedis.start();
  }

  @BeforeEach
  void connect() {
    redis = SharedRedis.client();
    lock = new RedisLock(redis.template(), "lock:", "fence:");
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
    assertThat(lock.tryAcquire("independent", TTL).orElseThrow().fencingToken()).isEqualTo(1L);
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
}

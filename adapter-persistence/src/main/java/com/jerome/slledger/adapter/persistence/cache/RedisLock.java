package com.jerome.slledger.adapter.persistence.cache;

import com.jerome.slledger.application.cache.DistributedLock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * A single-instance Redis lock: {@code SET lock:{resource} {owner} NX PX ttl}.
 *
 * <p>Two things are done in Lua, because each is two commands that must not be separated:
 *
 * <ul>
 *   <li><b>Acquire and take a fencing token</b> together. If the {@code INCR} were a second round
 *       trip, a holder paused between the two could lose the lock, see a later holder take a token,
 *       and then receive a <em>higher</em> one for a lock it no longer has.
 *   <li><b>Release only if still the owner.</b> A plain {@code DEL} after the lock has expired
 *       deletes the next holder's lock.
 * </ul>
 *
 * <p>The fence counter has no TTL, deliberately: a counter that expired could restart below a token
 * a store has already admitted. It is one small key per resource.
 *
 * <p>This is one Redis, not Redlock. A failover that loses an acknowledged {@code SET} lets two
 * holders exist, which is one more reason the protected write checks its token.
 */
public final class RedisLock implements DistributedLock {

  private static final RedisScript<Long> ACQUIRE =
      new DefaultRedisScript<>(
          """
          if redis.call('set', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
            return redis.call('incr', KEYS[2])
          end
          return 0
          """,
          Long.class);

  private static final RedisScript<Long> RELEASE =
      new DefaultRedisScript<>(
          """
          if redis.call('get', KEYS[1]) == ARGV[1] then
            return redis.call('del', KEYS[1])
          end
          return 0
          """,
          Long.class);

  private final StringRedisTemplate redis;
  private final String lockPrefix;
  private final String fencePrefix;

  public RedisLock(StringRedisTemplate redis, String lockPrefix, String fencePrefix) {
    this.redis = redis;
    this.lockPrefix = lockPrefix;
    this.fencePrefix = fencePrefix;
  }

  @Override
  public Optional<Lease> tryAcquire(String resource, Duration ttl) {
    String owner = UUID.randomUUID().toString();
    long millis = Math.max(1L, ttl.toMillis());
    Long token =
        redis.execute(
            ACQUIRE,
            List.of(lockPrefix + resource, fencePrefix + resource),
            owner,
            Long.toString(millis));
    if (token == null || token == 0L) {
      return Optional.empty();
    }
    return Optional.of(new RedisLease(resource, token, owner));
  }

  private final class RedisLease implements Lease {

    private final String resource;
    private final long token;
    private final String owner;
    private final AtomicBoolean released = new AtomicBoolean();

    private RedisLease(String resource, long token, String owner) {
      this.resource = resource;
      this.token = token;
      this.owner = owner;
    }

    @Override
    public String resource() {
      return resource;
    }

    @Override
    public long fencingToken() {
      return token;
    }

    @Override
    public void release() {
      if (released.compareAndSet(false, true)) {
        redis.execute(RELEASE, List.of(lockPrefix + resource), owner);
      }
    }
  }
}

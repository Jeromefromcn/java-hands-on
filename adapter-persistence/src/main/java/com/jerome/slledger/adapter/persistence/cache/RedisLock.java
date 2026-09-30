package com.jerome.slledger.adapter.persistence.cache;

import com.jerome.slledger.application.cache.DistributedLock;
import com.jerome.slledger.application.cache.FencingTokens;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * A single-instance Redis lock: {@code SET lock:{resource} {owner} NX PX ttl}, with a fencing token
 * from {@link FencingTokens} (a Postgres sequence in production).
 *
 * <p>Redis keeps no counter. Until stage 11 it did, and a Redis that restarted empty began again at
 * 1, below a token the store had already admitted, so the fence refused every write. The token now
 * comes from the store that checks it.
 *
 * <p>That leaves two steps that cannot be one: take the lock, then draw the token. A holder paused
 * between them could lose the lock, see the next holder draw a token, and then draw a
 * <em>higher</em> one for a lock it no longer has. So the order is: take the lock, draw the token,
 * and check that the lock still belongs to this owner. If it does not, there is no lease. If it
 * does, no other holder can have been given the lock since this one took it, so no token drawn for
 * a later holder exists yet, and every earlier holder either drew already or will fail the same
 * check.
 *
 * <p>Two things are done in Lua because each is two commands that must not be separated:
 *
 * <ul>
 *   <li><b>Confirm the owner</b>: a lock read and compared with the owner in one step.
 *   <li><b>Release only if still the owner.</b> A plain {@code DEL} after the lock has expired
 *       deletes the next holder's lock.
 * </ul>
 *
 * <p>This is one Redis, not Redlock. A failover that loses an acknowledged {@code SET} lets two
 * holders exist, which is one more reason the protected write checks its token.
 */
public final class RedisLock implements DistributedLock {

  private static final RedisScript<Long> ACQUIRE =
      new DefaultRedisScript<>(
          """
          if redis.call('set', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
            return 1
          end
          return 0
          """,
          Long.class);

  private static final RedisScript<Long> STILL_HELD =
      new DefaultRedisScript<>(
          """
          if redis.call('get', KEYS[1]) == ARGV[1] then
            return 1
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
  private final FencingTokens tokens;

  public RedisLock(StringRedisTemplate redis, String lockPrefix, FencingTokens tokens) {
    this.redis = redis;
    this.lockPrefix = lockPrefix;
    this.tokens = tokens;
  }

  @Override
  public Optional<Lease> tryAcquire(String resource, Duration ttl) {
    String owner = UUID.randomUUID().toString();
    long millis = Math.max(1L, ttl.toMillis());
    List<String> key = List.of(lockPrefix + resource);
    Long taken = redis.execute(ACQUIRE, key, owner, Long.toString(millis));
    if (taken == null || taken == 0L) {
      return Optional.empty();
    }
    long token;
    try {
      token = tokens.next();
    } catch (RuntimeException e) {
      // The lock is ours and there is no token to go with it: give it back, do not hold it for
      // a full TTL and starve the others.
      redis.execute(RELEASE, key, owner);
      throw e;
    }
    Long held = redis.execute(STILL_HELD, key, owner);
    if (held == null || held == 0L) {
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

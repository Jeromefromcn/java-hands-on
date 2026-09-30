package labs.caching;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.UUID;

/**
 * The hand-written lock of the main build's {@code RedisLock}, and a fenced write, in the fewest
 * lines: {@code SET NX PX} with a fencing token taken in the same Lua script, a Lua release that
 * checks the owner, and a write that checks the token. The lab copies it rather than depending on
 * the main build's modules, which carry Spring.
 */
final class HandLock implements AutoCloseable {

  private static final String ACQUIRE =
      """
      if redis.call('set', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
        return redis.call('incr', KEYS[2])
      end
      return 0
      """;

  private static final String RELEASE =
      "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end return 0";

  /** Adds to the total only if the token is not lower than the highest one seen. */
  private static final String FENCED_ADD =
      """
      local seen = tonumber(redis.call('get', KEYS[2]) or '0')
      if tonumber(ARGV[1]) >= seen then
        redis.call('set', KEYS[2], ARGV[1])
        redis.call('incr', KEYS[1])
        return 1
      end
      return 0
      """;

  private final RedisClient client;
  private final StatefulRedisConnection<String, String> connection;
  private final RedisCommands<String, String> redis;
  private final String owner = UUID.randomUUID().toString();

  HandLock(String host, int port) {
    this.client = RedisClient.create("redis://%s:%d".formatted(host, port));
    this.connection = client.connect();
    this.redis = connection.sync();
  }

  /** The fencing token, or 0 when the lock is held by someone else. */
  long tryAcquire(String resource, long leaseMillis) {
    Long token =
        redis.eval(
            ACQUIRE,
            ScriptOutputType.INTEGER,
            new String[] {"lock:" + resource, "fence:" + resource},
            owner,
            Long.toString(leaseMillis));
    return token == null ? 0 : token;
  }

  void release(String resource) {
    redis.eval(RELEASE, ScriptOutputType.INTEGER, new String[] {"lock:" + resource}, owner);
  }

  /** {@code total = total + 1}, with nothing checking who is allowed to. */
  void unfencedAdd(String total) {
    redis.incr(total);
  }

  /** @return true if the write was admitted, false if the token was stale */
  boolean fencedAdd(String total, String fence, long token) {
    Long admitted =
        redis.eval(
            FENCED_ADD,
            ScriptOutputType.INTEGER,
            new String[] {total, fence},
            Long.toString(token));
    return admitted != null && admitted == 1L;
  }

  void flushAll() {
    redis.flushall();
  }

  long total(String total) {
    String value = redis.get(total);
    return value == null ? 0 : Long.parseLong(value);
  }

  @Override
  public void close() {
    connection.close();
    client.shutdown();
  }
}

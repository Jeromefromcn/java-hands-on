package labs.caching;

import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * The child process of {@link LockStopSignal}: takes the lock, "works" for a while, then writes
 * once. The parent stops it with SIGSTOP in the middle of the work, which is a stop-the-world
 * pause that nothing inside the process can see or answer, threads and watchdogs included.
 *
 * <p>Arguments: mode (redisson | handwritten | fenced), host, port, lease millis, work millis.
 * Prints one line per event; the parent timestamps them.
 */
public final class Holder {

  static final String RESOURCE = "accrual";
  static final String TOTAL = "total";
  static final String FENCE_SEEN = "fence-seen";

  private Holder() {}

  public static void main(String[] args) throws Exception {
    String mode = args[0];
    String host = args[1];
    int port = Integer.parseInt(args[2]);
    long leaseMillis = Long.parseLong(args[3]);
    long workMillis = Long.parseLong(args[4]);

    switch (mode) {
      case "redisson" -> redisson(host, port, leaseMillis, workMillis);
      case "handwritten" -> handwritten(host, port, leaseMillis, workMillis, false);
      case "fenced" -> handwritten(host, port, leaseMillis, workMillis, true);
      default -> throw new IllegalArgumentException(mode);
    }
  }

  private static void redisson(String host, int port, long leaseMillis, long workMillis)
      throws Exception {
    Config config = new Config();
    config.setLockWatchdogTimeout(leaseMillis);
    config.useSingleServer().setAddress("redis://%s:%d".formatted(host, port));
    RedissonClient client = Redisson.create(config);
    try {
      RLock lock = client.getLock(RESOURCE);
      lock.lock(); // no lease time given: the watchdog renews it every lease/3
      System.out.println("ACQUIRED");
      Thread.sleep(workMillis);
      System.out.println("WRITING");
      client.getAtomicLong(TOTAL).incrementAndGet();
      System.out.println("WROTE");
      try {
        lock.unlock();
        System.out.println("UNLOCKED");
      } catch (IllegalMonitorStateException notHeld) {
        System.out.println("UNLOCK-REFUSED (this process no longer holds the lock)");
      }
    } finally {
      client.shutdown();
    }
  }

  private static void handwritten(
      String host, int port, long leaseMillis, long workMillis, boolean fenced)
      throws Exception {
    try (HandLock lock = new HandLock(host, port)) {
      long token = lock.tryAcquire(RESOURCE, leaseMillis);
      if (token == 0) {
        System.out.println("NOT-ACQUIRED");
        return;
      }
      System.out.println("ACQUIRED token=" + token);
      Thread.sleep(workMillis);
      System.out.println("WRITING");
      if (fenced) {
        boolean admitted = lock.fencedAdd(TOTAL, FENCE_SEEN, token);
        System.out.println(admitted ? "WROTE" : "REFUSED-STALE-TOKEN");
      } else {
        lock.unfencedAdd(TOTAL);
        System.out.println("WROTE");
      }
      lock.release(RESOURCE);
      System.out.println("RELEASED (a no-op if the lock had passed to someone else)");
    }
  }
}

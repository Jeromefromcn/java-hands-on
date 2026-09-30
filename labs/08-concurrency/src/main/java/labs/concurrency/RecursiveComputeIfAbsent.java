package labs.concurrency;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@code computeIfAbsent} holds the lock on the key's bin while it runs the mapping function. A
 * function that touches the same map again is therefore a lock acquired while holding a lock.
 *
 * <p>Two shapes: (1) one thread, recursive on the same map; (2) two threads, each inside its own
 * key's function and calling for the other's key, a textbook lock-order cycle. The second is
 * proven with {@code ThreadMXBean.findDeadlockedThreads()}. Then the fix: compute outside the map
 * call and publish with {@code putIfAbsent}.
 */
public final class RecursiveComputeIfAbsent {

  public static void main(String[] args) throws Exception {
    System.out.print(Env.header("RecursiveComputeIfAbsent — a mapping function that re-enters the map"));

    System.out.println("\n1. one thread, fib(n) memoised with computeIfAbsent calling itself:");
    ConcurrentHashMap<Integer, Long> memo = new ConcurrentHashMap<>();
    memo.put(0, 0L);
    memo.put(1, 1L);
    Throwable[] outcome = new Throwable[1];
    Thread single =
        new Thread(
            () -> {
              try {
                System.out.println("   fib(30) = " + fib(30, memo));
              } catch (Throwable t) {
                outcome[0] = t;
              }
            });
    single.setDaemon(true);
    single.start();
    single.join(3000);
    if (single.isAlive()) {
      System.out.println("   still running after 3 s (a hang)");
    } else if (outcome[0] != null) {
      System.out.println("   threw " + outcome[0]);
    } else {
      System.out.println("   completed without error");
    }

    System.out.println("\n2. two threads, crossed keys:");
    ConcurrentHashMap<Integer, String> shared = new ConcurrentHashMap<>();
    // Keys chosen so they land in different bins of the default table.
    int k1 = 1;
    int k2 = 2;
    CountDownLatch bothInside = new CountDownLatch(2);
    Thread t1 = crossed(shared, k1, k2, bothInside, "t1");
    Thread t2 = crossed(shared, k2, k1, bothInside, "t2");
    t1.start();
    t2.start();
    t1.join(3000);
    t2.join(3000);
    boolean hung = t1.isAlive() && t2.isAlive();
    long[] deadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
    System.out.printf("   both threads still running after 3 s: %s%n", hung);
    System.out.printf(
        "   findDeadlockedThreads(): %s%n",
        deadlocked == null ? "null" : Arrays.toString(deadlocked) + " (" + deadlocked.length + " threads)");
    if (deadlocked != null) {
      for (var info : ManagementFactory.getThreadMXBean().getThreadInfo(deadlocked, 12)) {
        System.out.printf("   %s is %s, owned by %s%n", info.getThreadName(), info.getThreadState(), info.getLockOwnerName());
      }
    }

    System.out.println("\n3. the fix: compute outside the map call, publish with putIfAbsent:");
    ConcurrentHashMap<Integer, Long> safe = new ConcurrentHashMap<>();
    safe.put(0, 0L);
    safe.put(1, 1L);
    System.out.println("   fib(30) = " + fibSafe(30, safe));
    System.exit(0); // the deadlocked threads cannot be stopped
  }

  private static long fib(int n, ConcurrentHashMap<Integer, Long> memo) {
    return memo.computeIfAbsent(n, k -> fib(k - 1, memo) + fib(k - 2, memo));
  }

  private static long fibSafe(int n, ConcurrentHashMap<Integer, Long> memo) {
    Long known = memo.get(n);
    if (known != null) {
      return known;
    }
    long computed = fibSafe(n - 1, memo) + fibSafe(n - 2, memo);
    Long raced = memo.putIfAbsent(n, computed);
    return raced != null ? raced : computed;
  }

  private static Thread crossed(
      ConcurrentHashMap<Integer, String> map, int mine, int other, CountDownLatch bothInside, String name) {
    Thread thread =
        new Thread(
            () ->
                map.computeIfAbsent(
                    mine,
                    k -> {
                      bothInside.countDown();
                      try {
                        bothInside.await(); // both are now inside their own key's function
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      return map.computeIfAbsent(other, o -> "value-" + o);
                    }),
            name);
    thread.setDaemon(true);
    return thread;
  }
}

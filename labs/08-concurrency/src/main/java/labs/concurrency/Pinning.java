package labs.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Virtual threads blocking inside {@code synchronized}, against the same blocking inside a {@link
 * ReentrantLock}.
 *
 * <p>Eight virtual threads run on a scheduler limited to two carrier threads, each blocking for
 * 200 ms while holding its own lock (never contended, so only the blocking matters). If a virtual
 * thread keeps its carrier while it blocks (pinned), two carriers serve eight threads in four
 * waves, about 800 ms. If it releases the carrier, all eight overlap, about 200 ms.
 *
 * <p>JDK 21 pins inside {@code synchronized}; JEP 491 (JDK 24) removed that. Run the same jar on
 * both; on 21 add {@code -Djdk.tracePinnedThreads=full}, which the newer JDK no longer has.
 */
public final class Pinning {

  private static final int THREADS = 8;
  private static final long BLOCK_MILLIS = 200;

  public static void main(String[] args) throws Exception {
    System.out.print(Env.header("Pinning — a virtual thread blocking inside synchronized or a ReentrantLock"));
    System.out.printf(
        "%d virtual threads, each blocking %d ms holding its own lock%n"
            + "jdk.virtualThreadScheduler.parallelism=%s maxPoolSize=%s tracePinnedThreads=%s%n%n",
        THREADS,
        BLOCK_MILLIS,
        System.getProperty("jdk.virtualThreadScheduler.parallelism"),
        System.getProperty("jdk.virtualThreadScheduler.maxPoolSize"),
        System.getProperty("jdk.tracePinnedThreads"));

    run("warm-up (not reported)", true);
    for (int round = 1; round <= 3; round++) {
      System.out.printf("round %d: synchronized %4d ms%n", round, run("synchronized", true));
      System.out.printf("round %d: ReentrantLock %4d ms%n", round, run("ReentrantLock", false));
    }
  }

  private static long run(String label, boolean useMonitor) throws Exception {
    List<Thread> threads = new ArrayList<>();
    long started = System.nanoTime();
    for (int i = 0; i < THREADS; i++) {
      Object monitor = new Object();
      ReentrantLock lock = new ReentrantLock();
      threads.add(
          Thread.ofVirtual()
              .name("v-" + label + "-" + i)
              .start(
                  () -> {
                    if (useMonitor) {
                      synchronized (monitor) {
                        block();
                      }
                    } else {
                      lock.lock();
                      try {
                        block();
                      } finally {
                        lock.unlock();
                      }
                    }
                  }));
    }
    for (Thread t : threads) {
      t.join();
    }
    return (System.nanoTime() - started) / 1_000_000;
  }

  private static void block() {
    try {
      Thread.sleep(BLOCK_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

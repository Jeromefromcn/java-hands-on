package labs.concurrency;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code volatile int count++} is a read, an add and a write. Volatile makes each visible; it does
 * not make the three one step, so two threads can read the same value and both write value + 1.
 */
public final class LostUpdate {

  static volatile int volatileCount;
  static final AtomicInteger atomicCount = new AtomicInteger();

  public static void main(String[] args) throws Exception {
    int threads = args.length > 0 ? Integer.parseInt(args[0]) : 8;
    int perThread = args.length > 1 ? Integer.parseInt(args[1]) : 1_000_000;
    int rounds = 5;
    System.out.print(Env.header("LostUpdate — volatile int count++ against AtomicInteger"));
    System.out.printf("%d threads x %d increments, %d rounds; expected total %d%n%n", threads, perThread, rounds, (long) threads * perThread);

    for (int round = 1; round <= rounds; round++) {
      volatileCount = 0;
      run(threads, () -> {
        for (int i = 0; i < perThread; i++) {
          volatileCount++;
        }
      });
      long expected = (long) threads * perThread;
      System.out.printf(
          "volatile round %d: observed %d, lost %d (%.1f%%)%n",
          round, volatileCount, expected - volatileCount, 100.0 * (expected - volatileCount) / expected);
    }
    for (int round = 1; round <= rounds; round++) {
      atomicCount.set(0);
      run(threads, () -> {
        for (int i = 0; i < perThread; i++) {
          atomicCount.incrementAndGet();
        }
      });
      long expected = (long) threads * perThread;
      System.out.printf(
          "atomic   round %d: observed %d, lost %d%n", round, atomicCount.get(), expected - atomicCount.get());
    }
  }

  private static void run(int threads, Runnable body) throws InterruptedException {
    Thread[] all = new Thread[threads];
    for (int i = 0; i < threads; i++) {
      all[i] = new Thread(body);
      all[i].start();
    }
    for (Thread t : all) {
      t.join();
    }
  }
}

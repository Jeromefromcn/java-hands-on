package labs.concurrency;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Double-checked locking without {@code volatile}: the unsynchronised first check can see a
 * non-null reference to an object whose constructor's stores have not become visible yet, so a
 * reader gets a partially constructed object. {@code volatile} on the field makes the reference
 * write happen after the constructor's writes for every reader that sees it.
 *
 * <p>Racing threads walk an array of fresh holders together, so each holder's first
 * {@code get()} is contended. A violation is a reader seeing default values in an object it was
 * handed. It may not occur on a given JVM and CPU; the output says how many of the checks failed.
 */
public final class DoubleCheckedLocking {

  static final class Data {
    int a;
    int b;
    int c;

    Data() {
      a = 42;
      b = 7;
      c = 99;
    }

    boolean complete() {
      return a == 42 && b == 7 && c == 99;
    }
  }

  static final class Broken {
    private Data data; // NOT volatile

    Data get() {
      if (data == null) {
        synchronized (this) {
          if (data == null) {
            data = new Data();
          }
        }
      }
      return data;
    }
  }

  static final class Fixed {
    private volatile Data data;

    Data get() {
      Data local = data;
      if (local == null) {
        synchronized (this) {
          local = data;
          if (local == null) {
            data = local = new Data();
          }
        }
      }
      return local;
    }
  }

  private static final int HOLDERS = 2_000_000;
  private static final int READERS = 4;

  public static void main(String[] args) throws Exception {
    int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 5;
    System.out.print(Env.header("DoubleCheckedLocking — a singleton with and without volatile"));
    System.out.printf("%d rounds, %d holders per round, %d racing readers%n%n", rounds, HOLDERS, READERS);

    long brokenTotal = 0;
    long fixedTotal = 0;
    for (int round = 1; round <= rounds; round++) {
      long broken = race(true);
      long fixed = race(false);
      brokenTotal += broken;
      fixedTotal += fixed;
      System.out.printf("round %d: partially constructed objects seen: broken=%d fixed=%d%n", round, broken, fixed);
    }
    System.out.printf(
        "%ntotal over %d rounds (%d checks each variant): broken=%d fixed=%d%n",
        rounds, (long) rounds * HOLDERS * READERS, brokenTotal, fixedTotal);
  }

  private static long race(boolean broken) throws Exception {
    Object[] holders = new Object[HOLDERS];
    for (int i = 0; i < HOLDERS; i++) {
      holders[i] = broken ? new Broken() : new Fixed();
    }
    AtomicLong violations = new AtomicLong();
    CyclicBarrier start = new CyclicBarrier(READERS);
    Thread[] readers = new Thread[READERS];
    for (int r = 0; r < READERS; r++) {
      readers[r] =
          new Thread(
              () -> {
                try {
                  start.await();
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
                long bad = 0;
                for (Object holder : holders) {
                  Data data = broken ? ((Broken) holder).get() : ((Fixed) holder).get();
                  if (!data.complete()) {
                    bad++;
                  }
                }
                violations.addAndGet(bad);
              });
      readers[r].start();
    }
    for (Thread t : readers) {
      t.join();
    }
    return violations.get();
  }
}

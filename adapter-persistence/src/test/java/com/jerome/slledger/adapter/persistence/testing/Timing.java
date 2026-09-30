package com.jerome.slledger.adapter.persistence.testing;

import java.util.Arrays;
import java.util.concurrent.Callable;

/**
 * Wall-clock timing for the crossover measurements: warm-up runs discarded, then the median of the
 * measured runs. The median, not the mean, so one GC pause or one cold page does not move it.
 */
public final class Timing {

  public static final int WARMUPS = 2;
  public static final int RUNS = 5;

  private Timing() {}

  /** Median milliseconds of {@link #RUNS} runs of {@code work}, after {@link #WARMUPS} warm-ups. */
  public static double medianMillis(Callable<?> work) throws Exception {
    for (int i = 0; i < WARMUPS; i++) {
      work.call();
    }
    double[] millis = new double[RUNS];
    for (int i = 0; i < RUNS; i++) {
      long started = System.nanoTime();
      work.call();
      millis[i] = (System.nanoTime() - started) / 1_000_000.0;
    }
    Arrays.sort(millis);
    return millis[RUNS / 2];
  }
}

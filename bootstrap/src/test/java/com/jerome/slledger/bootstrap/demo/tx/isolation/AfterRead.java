package com.jerome.slledger.bootstrap.demo.tx.isolation;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** The point between read and write where both racing threads meet. Bounded, never a sleep. */
final class AfterRead {

  private AfterRead() {}

  static void await(CyclicBarrier barrier) {
    if (barrier == null) {
      return;
    }
    try {
      barrier.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (BrokenBarrierException | TimeoutException e) {
      throw new IllegalStateException("the other return never reached its read", e);
    }
  }
}

package com.jerome.slledger.bootstrap.demo.tx.isolation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;

/**
 * Deliberately not transactional: each attempt is its own transaction, so a retry starts from a
 * fresh snapshot and sees the other writer's committed row.
 */
public class RetryingReturn {

  private static final int ATTEMPTS = 3;

  private final OptimisticReturn optimistic;

  public RetryingReturn(OptimisticReturn optimistic) {
    this.optimistic = optimistic;
  }

  public void returnQuantity(String id, long quantity, CyclicBarrier firstAttemptBarrier) {
    List<String> attempts = new ArrayList<>();
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      if (optimistic.tryReturn(id, quantity, attempt == 0 ? firstAttemptBarrier : null)) {
        return;
      }
      attempts.add("0 rows");
    }
    throw new ConcurrentUpdateException(id, attempts);
  }
}

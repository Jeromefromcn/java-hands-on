package com.jerome.slledger.bootstrap.observability;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs an action when the current transaction commits, or at once if there is none. A counter
 * incremented inside a transaction that later rolls back reports something that did not happen;
 * registering the increment for the commit makes the use cases free to call their metrics port from
 * wherever the fact is established.
 */
final class AfterCommit {

  private AfterCommit() {}

  static void run(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              guarded(action);
            }
          });
    } else {
      guarded(action);
    }
  }

  /** A metrics failure is not a reason to fail the work that was measured. */
  private static void guarded(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException ignored) {
      // Deliberately swallowed: see above.
    }
  }
}

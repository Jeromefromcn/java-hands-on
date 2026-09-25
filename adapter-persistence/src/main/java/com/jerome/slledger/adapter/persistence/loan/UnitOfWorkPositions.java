package com.jerome.slledger.adapter.persistence.loan;

import com.jerome.slledger.domain.loan.LoanPosition;
import java.util.IdentityHashMap;
import java.util.Map;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Which aggregate instances this transaction loaded, and the managed entity behind each.
 *
 * <p>Keyed by identity, not by id: the question is "is this the object I handed out in this
 * transaction", and a stale copy of the same position from an earlier transaction has the same id
 * and is exactly what must not match.
 *
 * <p>The map is bound to the transaction through {@link TransactionSynchronizationManager} and
 * unbound when it completes, committed or rolled back, so a pooled thread never carries one
 * transaction's instances into the next.
 */
public class UnitOfWorkPositions {

  /** The positions loaded in the current transaction; refuses to answer outside one. */
  Map<LoanPosition, LoanPositionEntity> current() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "Positions are read and written only inside a transaction");
    }
    @SuppressWarnings("unchecked")
    Map<LoanPosition, LoanPositionEntity> bound =
        (Map<LoanPosition, LoanPositionEntity>) TransactionSynchronizationManager.getResource(this);
    if (bound != null) {
      return bound;
    }
    Map<LoanPosition, LoanPositionEntity> fresh = new IdentityHashMap<>();
    TransactionSynchronizationManager.bindResource(this, fresh);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(UnitOfWorkPositions.this);
          }
        });
    return fresh;
  }
}

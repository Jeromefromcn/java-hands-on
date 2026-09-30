package com.jerome.slledger.application.position;

import com.jerome.slledger.domain.loan.LoanTrade;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * A return, retried when another transaction changed the position first.
 *
 * <p>Deliberately <strong>not</strong> transactional. Each attempt is a whole transaction of its
 * own in {@link ReturnPositionOnce}, so a retry starts with a fresh persistence context and reads
 * the position as it now is. A retry inside one transaction would re-read the stale entity from the
 * first-level cache; see {@code docs/decisions/06-persistence.md}.
 *
 * <p>Retrying is safe because a return is a delta that the aggregate re-validates on every attempt:
 * if the concurrent change left too little outstanding, the retry fails with the domain's refusal,
 * not with a wrong balance.
 */
public class ReturnPosition {

  private final ReturnPositionOnce once;
  private final int maxAttempts;

  public ReturnPosition(ReturnPositionOnce once, int maxAttempts) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1, was " + maxAttempts);
    }
    this.once = once;
    this.maxAttempts = maxAttempts;
  }

  /**
   * @return the return trade booked
   * @throws OptimisticLockingFailureException the last attempt's, once every attempt lost a race
   */
  public LoanTrade apply(ReturnCommand command) {
    for (int attempt = 1; ; attempt++) {
      try {
        return once.apply(command);
      } catch (OptimisticLockingFailureException lostRace) {
        if (attempt == maxAttempts) {
          throw lostRace;
        }
      }
    }
  }
}

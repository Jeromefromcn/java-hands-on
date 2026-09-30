package com.jerome.slledger.application.saga;

import com.jerome.slledger.application.position.OpenPositionCommand;
import com.jerome.slledger.domain.loan.LoanTrade;

/**
 * The steps of opening a position, each in a transaction of its own. Forward steps first, then the
 * compensations, which are idempotent and safe on a step that never ran.
 */
public interface OpenPositionSteps {

  void reserveCredit(String sagaId, OpenPositionCommand command);

  void freezeCollateral(String sagaId, OpenPositionCommand command);

  /** The pivot: local, atomic, and the point after which the saga only moves forward. */
  LoanTrade createPosition(OpenPositionCommand command);

  /** Spends the hold and the reservation. Idempotent; retried until it lands. */
  void complete(String sagaId);

  void cancelHold(String sagaId);

  void releaseReservation(String sagaId);
}

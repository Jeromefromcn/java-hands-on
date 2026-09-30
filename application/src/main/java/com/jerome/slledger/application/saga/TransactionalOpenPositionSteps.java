package com.jerome.slledger.application.saga;

import com.jerome.slledger.application.collateral.CollateralHolds;
import com.jerome.slledger.application.position.OpenPosition;
import com.jerome.slledger.application.position.OpenPositionCommand;
import com.jerome.slledger.domain.loan.LoanTrade;
import org.springframework.transaction.annotation.Transactional;

/** The steps, one transaction each. The saga calls them through this bean's proxy. */
public class TransactionalOpenPositionSteps implements OpenPositionSteps {

  private final CreditReservations reservations;
  private final CollateralHolds holds;
  private final OpenPosition open;

  public TransactionalOpenPositionSteps(
      CreditReservations reservations, CollateralHolds holds, OpenPosition open) {
    this.reservations = reservations;
    this.holds = holds;
    this.open = open;
  }

  @Override
  @Transactional
  public void reserveCredit(String sagaId, OpenPositionCommand command) {
    reservations.reserve(sagaId, command.counterparty(), command.collateral());
  }

  @Override
  @Transactional
  public void freezeCollateral(String sagaId, OpenPositionCommand command) {
    holds.tryFreeze(sagaId, command.counterparty(), command.collateral());
  }

  @Override
  @Transactional
  public LoanTrade createPosition(OpenPositionCommand command) {
    return open.open(command);
  }

  @Override
  @Transactional
  public void complete(String sagaId) {
    holds.confirm(sagaId);
    reservations.consume(sagaId);
  }

  @Override
  @Transactional
  public void cancelHold(String sagaId) {
    holds.cancel(sagaId);
  }

  @Override
  @Transactional
  public void releaseReservation(String sagaId) {
    reservations.release(sagaId);
  }
}

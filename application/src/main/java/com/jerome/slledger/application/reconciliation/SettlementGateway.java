package com.jerome.slledger.application.reconciliation;

/**
 * Asks the custodian's side to book a settlement it is missing. The real connection is out of
 * scope; what the ladder needs is the answer, and that an answer can be "no" or an exception.
 */
public interface SettlementGateway {

  sealed interface Result {}

  /** The custodian has booked it. */
  record Settled() implements Result {}

  /** The custodian refused, or there is no route: the reason is kept on the break. */
  record Rejected(String reason) implements Result {}

  /** May throw for a transport failure; the ladder counts that as a failed attempt. */
  Result resubmit(String positionId);
}

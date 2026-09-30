package com.jerome.slledger.application.saga;

import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;

/**
 * Credit set aside for an open that has not yet become a position. It counts against the
 * counterparty's limit like exposure does, so two sagas cannot each be promised the last of it.
 *
 * <p>Idempotent and safe in any order, like a hold: a release that arrives before its reserve
 * leaves a mark, and the reserve that follows is refused.
 */
public interface CreditReservations {

  /**
   * @throws com.jerome.slledger.domain.error.InsufficientCreditException if the limit cannot absorb
   *     the amount on top of the exposure and the other reservations
   * @throws SagaRefusedException if {@code sagaId} has already been released
   */
  void reserve(String sagaId, CounterpartyId counterparty, Money amount);

  /** The position exists and carries the exposure now: the reservation is spent. */
  void consume(String sagaId);

  /** Gives the credit back. Allowed for a saga that never reserved. */
  void release(String sagaId);
}

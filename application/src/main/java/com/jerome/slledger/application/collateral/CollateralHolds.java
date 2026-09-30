package com.jerome.slledger.application.collateral;

import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;

/**
 * Freezing a counterparty's collateral as Try, Confirm, Cancel.
 *
 * <p>Every operation is idempotent and every operation is safe in any order it can arrive in: a
 * Cancel may come before its Try, a Try after its Cancel, a Confirm twice. The transaction id is
 * the identity of the whole attempt. All three run in the caller's transaction; a refusal is an
 * exception, and the caller's transaction rolls back with it.
 */
public interface CollateralHolds {

  /**
   * Moves {@code amount} from the counterparty's available collateral to frozen, under {@code xid}.
   * A repeat of a Try already done is a no-op.
   *
   * @throws CollateralRefusedException if too little is available, there is no such account, or
   *     {@code xid} has already been cancelled (a late Try; freezing now would hold funds that
   *     nothing will ever release)
   */
  void tryFreeze(String xid, CounterpartyId counterparty, Money amount);

  /**
   * Consumes what {@code xid} froze. A repeat is a no-op.
   *
   * @throws CollateralRefusedException if {@code xid} was never tried, or has been cancelled
   */
  void confirm(String xid);

  /**
   * Returns what {@code xid} froze to available. A repeat is a no-op. Cancelling an {@code xid}
   * that was never tried is allowed and moves nothing, but remembers the cancellation, so that a
   * Try that arrives afterwards is refused.
   *
   * @throws CollateralRefusedException if {@code xid} has been confirmed
   */
  void cancel(String xid);
}

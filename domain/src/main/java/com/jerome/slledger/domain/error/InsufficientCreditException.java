package com.jerome.slledger.domain.error;

import com.jerome.slledger.domain.money.Money;

/**
 * A counterparty asked to borrow more than its remaining credit allows.
 *
 * <p>The message carries all three figures — ceiling, exposure already taken, and the amount
 * requested — because the useful question after a refusal is never "was it refused" but "by how
 * much", and a message that omits the numbers sends the reader back to the database.
 */
public final class InsufficientCreditException extends DomainException {

  private static final long serialVersionUID = 1L;

  public InsufficientCreditException(Money ceiling, Money existingExposure, Money requested) {
    super(
        "Credit limit %s exceeded: %s already drawn, %s requested"
            .formatted(plain(ceiling), plain(existingExposure), plain(requested)));
  }

  private static String plain(Money money) {
    return "%s %s".formatted(money.amount().toPlainString(), money.currency().getCurrencyCode());
  }
}

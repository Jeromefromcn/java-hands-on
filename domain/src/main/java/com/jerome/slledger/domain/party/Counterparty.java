package com.jerome.slledger.domain.party;

import java.util.Objects;

/**
 * A borrowing counterparty and the limit it trades under.
 *
 * <p>A record, not an aggregate: nothing here has a lifecycle. Exposure is a property of the
 * positions a counterparty holds, not of the counterparty, so it is computed rather than stored — a
 * cached exposure figure is a number that can disagree with the positions it summarises.
 *
 * <p>All counterparty names in this repository are synthetic.
 */
public record Counterparty(CounterpartyId id, String name, CreditLimit creditLimit) {

  public Counterparty {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(creditLimit, "creditLimit");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("A counterparty needs a name");
    }
  }
}

package com.jerome.slledger.application.security;

import com.jerome.slledger.domain.party.CounterpartyId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Who is asking: the identity provider's stable subject, the roles of this ledger it holds, and,
 * for an external borrower, the counterparty it speaks for. Built from a verified token at the
 * edge; {@code application} never sees the token.
 */
public record Caller(String subject, Set<Role> roles, Optional<CounterpartyId> counterparty) {

  /** The caller of work no human started: the accrual job, a consumer, the relay. */
  public static final Caller SYSTEM = new Caller("system", Set.of(), Optional.empty());

  public Caller {
    Objects.requireNonNull(subject, "subject");
    roles = Set.copyOf(roles);
    Objects.requireNonNull(counterparty, "counterparty");
  }

  public boolean has(Role role) {
    return roles.contains(role);
  }

  /**
   * Staff read everything. A counterparty reads its own rows, and only if the token says which
   * counterparty it is: a role with no claim is a misconfigured account, and it reads nothing.
   */
  public Visibility visibility() {
    if (has(Role.TRADER) || has(Role.OPS)) {
      return Visibility.ALL;
    }
    if (has(Role.COUNTERPARTY) && counterparty.isPresent()) {
      return Visibility.only(counterparty.get());
    }
    return Visibility.NOBODY;
  }
}

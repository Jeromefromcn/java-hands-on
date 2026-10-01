package com.jerome.slledger.application.security;

import java.util.Optional;

/**
 * The five things a caller can be. The claim is the role's name in the identity provider; nothing
 * outside this enum is a role of this ledger, so an unknown name in a token grants nothing.
 */
public enum Role {
  /** Desk: books opens and returns, reads everything. */
  TRADER("ledger-trader"),
  /** Operations: reads everything, sees health details. */
  OPS("ledger-ops"),
  /** An external borrower: reads its own positions only. */
  COUNTERPARTY("ledger-counterparty"),
  /** Reads the audit trail. */
  AUDITOR("ledger-auditor"),
  /** The metrics scraper. */
  METRICS("ledger-metrics");

  private final String claim;

  Role(String claim) {
    this.claim = claim;
  }

  public String claim() {
    return claim;
  }

  public static Optional<Role> fromClaim(String claim) {
    for (Role role : values()) {
      if (role.claim.equals(claim)) {
        return Optional.of(role);
      }
    }
    return Optional.empty();
  }
}

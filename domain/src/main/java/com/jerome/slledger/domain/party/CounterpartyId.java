package com.jerome.slledger.domain.party;

import com.jerome.slledger.domain.error.MalformedIdentifierException;

/**
 * The identity of a borrowing counterparty.
 *
 * <p>A record rather than a bare String, so that a counterparty identifier cannot be passed where a
 * position identifier is expected. Both are strings underneath; only the type keeps them apart.
 */
public record CounterpartyId(String value) {

  public CounterpartyId {
    if (value == null || value.isBlank()) {
      throw new MalformedIdentifierException(
          "CounterpartyId", String.valueOf(value), "must not be blank");
    }
  }
}

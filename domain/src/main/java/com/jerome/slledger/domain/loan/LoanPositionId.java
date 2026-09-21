package com.jerome.slledger.domain.loan;

import com.jerome.slledger.domain.error.MalformedIdentifierException;

/** The identity of a loan position. Distinct from every other identifier by type, not by shape. */
public record LoanPositionId(String value) {

  public LoanPositionId {
    if (value == null || value.isBlank()) {
      throw new MalformedIdentifierException(
          "LoanPositionId", String.valueOf(value), "must not be blank");
    }
  }
}

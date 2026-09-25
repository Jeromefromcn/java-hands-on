package com.jerome.slledger.application.position;

import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.loan.LoanPositionId;
import java.time.LocalDate;
import java.util.Objects;

/** A request to return {@code quantity} against a position: a delta, not a new balance. */
public record ReturnCommand(
    IdempotencyKey key, LoanPositionId position, long quantity, LocalDate tradeDate) {

  public ReturnCommand {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(tradeDate, "tradeDate");
  }
}

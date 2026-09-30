package com.jerome.slledger.application.port;

import com.jerome.slledger.domain.loan.LoanPositionId;
import java.util.List;
import java.util.Optional;

/**
 * One keyset slice of positions, and the cursor for the next.
 *
 * @param next the id to pass as {@code after} for the following slice; empty on the last one
 */
public record PositionSlice(List<PositionRow> rows, Optional<LoanPositionId> next) {

  public PositionSlice {
    rows = List.copyOf(rows);
  }
}

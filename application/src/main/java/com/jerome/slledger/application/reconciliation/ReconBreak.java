package com.jerome.slledger.application.reconciliation;

import com.jerome.slledger.domain.money.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/** One disagreement in one snapshot, with both sides' figures and what is to be done. */
public record ReconBreak(
    String snapshot,
    String positionId,
    BreakKind kind,
    Disposition disposition,
    LocalDate businessDate,
    Optional<Long> ourQuantity,
    Optional<Long> theirQuantity,
    Optional<Money> ourValue,
    Optional<Money> theirValue,
    String detail) {

  public ReconBreak {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(positionId, "positionId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(disposition, "disposition");
    Objects.requireNonNull(businessDate, "businessDate");
    Objects.requireNonNull(detail, "detail");
  }
}

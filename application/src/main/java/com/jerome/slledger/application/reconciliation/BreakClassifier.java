package com.jerome.slledger.application.reconciliation;

import com.jerome.slledger.application.accrual.CorruptPositionException;
import com.jerome.slledger.application.reconciliation.HoldingPair.Both;
import com.jerome.slledger.application.reconciliation.HoldingPair.Ours;
import com.jerome.slledger.application.reconciliation.HoldingPair.OursOnly;
import com.jerome.slledger.application.reconciliation.HoldingPair.TheirsOnly;
import com.jerome.slledger.application.reconciliation.HoldingPair.Unreadable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Decides whether a position is a break, of which kind, and what happens to it first.
 *
 * <p>The rules, in the order they apply:
 *
 * <ol>
 *   <li>Both sides, different quantity: {@link BreakKind#QUANTITY_MISMATCH}, {@link
 *       Disposition#MANUAL}. A securities position that is short of shares is not rounding, and
 *       which side is right is not something this job can know.
 *   <li>Both sides, same quantity, values in different currencies: {@link
 *       BreakKind#VALUE_MISMATCH}, {@code MANUAL}: there is no rate to compare them with.
 *   <li>Both sides, same quantity, values differ: inside the tolerance {@code AUTO_ACCEPTED},
 *       otherwise {@code MANUAL}. The tolerance is the larger of a fixed sum and a fraction of the
 *       ledger's value.
 *   <li>Ledger only: {@link BreakKind#OURS_ONLY}. Inside the settlement window {@link
 *       Disposition#PENDING_SETTLEMENT}; past it {@link Disposition#RETRY_SETTLEMENT}.
 *   <li>Custodian only: {@link BreakKind#THEIRS_ONLY}, {@code MANUAL}, always. A position is never
 *       created from an external file: that would be creating a loan on somebody else's say-so.
 * </ol>
 */
public final class BreakClassifier {

  private final ReconPolicy policy;

  public BreakClassifier(ReconPolicy policy) {
    this.policy = policy;
  }

  /**
   * @return the break, or empty when the two sides agree
   * @throws CorruptPositionException the ledger's side could not be read
   */
  public Optional<ReconBreak> classify(String snapshot, LocalDate businessDate, HoldingPair pair) {
    return switch (pair) {
      case Both both -> both(snapshot, businessDate, both);
      case OursOnly only -> oursOnly(snapshot, businessDate, only.ours());
      case TheirsOnly only ->
          Optional.of(
              new ReconBreak(
                  snapshot,
                  only.positionId(),
                  BreakKind.THEIRS_ONLY,
                  Disposition.MANUAL,
                  businessDate,
                  Optional.empty(),
                  Optional.of(only.theirs().quantity()),
                  Optional.empty(),
                  Optional.of(only.theirs().value()),
                  "the custodian holds %d, the ledger has no such position"
                      .formatted(only.theirs().quantity())));
      case Unreadable unreadable ->
          throw new CorruptPositionException(unreadable.positionId(), unreadable.reason());
    };
  }

  private Optional<ReconBreak> both(String snapshot, LocalDate businessDate, Both both) {
    Ours ours = both.ours();
    HoldingPair.Theirs theirs = both.theirs();
    if (ours.quantity() != theirs.quantity()) {
      return Optional.of(
          new ReconBreak(
              snapshot,
              both.positionId(),
              BreakKind.QUANTITY_MISMATCH,
              Disposition.MANUAL,
              businessDate,
              Optional.of(ours.quantity()),
              Optional.of(theirs.quantity()),
              Optional.of(ours.value()),
              Optional.of(theirs.value()),
              "the ledger holds %d, the custodian %d"
                  .formatted(ours.quantity(), theirs.quantity())));
    }
    if (!ours.value().currency().equals(theirs.value().currency())) {
      return Optional.of(
          valueBreak(
              snapshot,
              businessDate,
              both,
              Disposition.MANUAL,
              "values in %s and %s cannot be compared"
                  .formatted(ours.value().currency(), theirs.value().currency())));
    }
    BigDecimal difference = ours.value().amount().subtract(theirs.value().amount()).abs();
    if (difference.signum() == 0) {
      return Optional.empty();
    }
    BigDecimal tolerance =
        policy
            .absoluteTolerance()
            .max(ours.value().amount().abs().multiply(policy.relativeTolerance()));
    boolean accepted = difference.compareTo(tolerance) <= 0;
    return Optional.of(
        valueBreak(
            snapshot,
            businessDate,
            both,
            accepted ? Disposition.AUTO_ACCEPTED : Disposition.MANUAL,
            "values differ by %s %s, tolerance %s"
                .formatted(
                    difference.toPlainString(),
                    ours.value().currency(),
                    tolerance.stripTrailingZeros().toPlainString())));
  }

  private static ReconBreak valueBreak(
      String snapshot, LocalDate businessDate, Both both, Disposition disposition, String detail) {
    return new ReconBreak(
        snapshot,
        both.positionId(),
        BreakKind.VALUE_MISMATCH,
        disposition,
        businessDate,
        Optional.of(both.ours().quantity()),
        Optional.of(both.theirs().quantity()),
        Optional.of(both.ours().value()),
        Optional.of(both.theirs().value()),
        detail);
  }

  private Optional<ReconBreak> oursOnly(String snapshot, LocalDate businessDate, Ours ours) {
    boolean insideWindow =
        !businessDate.isAfter(ours.settledOn().plusDays(policy.settlementWindowDays()));
    return Optional.of(
        new ReconBreak(
            snapshot,
            ours.positionId(),
            BreakKind.OURS_ONLY,
            insideWindow ? Disposition.PENDING_SETTLEMENT : Disposition.RETRY_SETTLEMENT,
            businessDate,
            Optional.of(ours.quantity()),
            Optional.empty(),
            Optional.of(ours.value()),
            Optional.empty(),
            "settled %s, the custodian does not have it%s"
                .formatted(
                    ours.settledOn(), insideWindow ? " (inside the settlement window)" : "")));
  }
}

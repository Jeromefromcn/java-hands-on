package com.jerome.slledger.domain.loan;

import com.jerome.slledger.domain.accrual.AccrualRule;
import com.jerome.slledger.domain.error.ExcessiveReturnException;
import com.jerome.slledger.domain.error.IllegalTransitionException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.margin.MarginCall;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * A counterparty's outstanding borrowing of one security: the aggregate this ledger is built
 * around.
 *
 * <p><strong>Not a record, deliberately.</strong> A record's canonical constructor is public, which
 * is a path that reaches every combination of field values, including {@code (outstanding = 1000,
 * status = CLOSED)}. That combination is internally consistent and impossible, and an invariant
 * with a path around it is not an invariant. Here the only ways in are {@link #open} and {@link
 * #returnQuantity}, and both of them are in this file.
 *
 * <p>Equality is by identity, not by value. The same position before and after a return is the same
 * position; a generated {@code equals} over all fields would have said otherwise the moment the
 * outstanding quantity changed.
 *
 * <p><strong>Not thread-safe.</strong> {@link #returnQuantity} reads and writes two fields with no
 * synchronisation, so two concurrent returns can both pass the check and produce a lost update.
 * That is stated here rather than discovered later: stage 8 reproduces exactly that failure and
 * then fixes it with optimistic locking, and it can only be a change to a stated assumption if the
 * assumption was stated.
 *
 * <p>The invariant, true at every instant: {@code 0 <= outstandingQuantity <= originalQuantity},
 * and {@code status} agrees with {@code outstandingQuantity}.
 */
public final class LoanPosition {

  private final LoanPositionId id;
  private final CounterpartyId counterparty;
  private final Isin security;
  private final long originalQuantity;
  private final Money collateral;
  private final AccrualRule accrualRule;

  private long outstandingQuantity;
  private LoanStatus status;

  private LoanPosition(
      LoanPositionId id,
      CounterpartyId counterparty,
      Isin security,
      long originalQuantity,
      Money collateral,
      AccrualRule accrualRule) {
    this.id = id;
    this.counterparty = counterparty;
    this.security = security;
    this.originalQuantity = originalQuantity;
    this.collateral = collateral;
    this.accrualRule = accrualRule;
    this.outstandingQuantity = originalQuantity;
    this.status = LoanStatus.OPEN;
  }

  /**
   * Opens a position for the whole borrowed quantity.
   *
   * <p>The credit check is <strong>not</strong> here, although the plan sketched it here. A limit
   * is checked against a counterparty's exposure across all of its positions, and this position
   * knows only about itself. Doing it here would mean passing in an exposure figure the caller had
   * already computed, which makes the caller the one performing the check while this class takes
   * the credit for it. It belongs to the open-position use case, in stage 4.
   */
  public static LoanPosition open(
      LoanPositionId id,
      CounterpartyId counterparty,
      Isin security,
      long quantity,
      Money collateral,
      AccrualRule accrualRule) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(counterparty, "counterparty");
    Objects.requireNonNull(security, "security");
    Objects.requireNonNull(collateral, "collateral");
    Objects.requireNonNull(accrualRule, "accrualRule");
    if (quantity <= 0) {
      throw new IllegalArgumentException(
          "A position must be opened for a positive quantity, was " + quantity);
    }
    if (collateral.isNegative()) {
      throw new IllegalArgumentException(
          "Collateral must not be negative, was " + collateral.amount().toPlainString());
    }
    return new LoanPosition(id, counterparty, security, quantity, collateral, accrualRule);
  }

  /**
   * The only transition this aggregate has.
   *
   * <p>Every refusal happens before any field is written, so a rejected return leaves the position
   * exactly as it was. Checking first and mutating second is not a style preference here: a
   * partially applied transition is a state the invariant does not cover.
   */
  public void returnQuantity(long quantity) {
    if (quantity <= 0) {
      throw new IllegalArgumentException(
          "A return must be for a positive quantity, was " + quantity);
    }
    if (status == LoanStatus.CLOSED) {
      throw new IllegalTransitionException(LoanStatus.CLOSED.name(), "accept a return");
    }
    if (quantity > outstandingQuantity) {
      throw new ExcessiveReturnException(quantity, outstandingQuantity);
    }

    outstandingQuantity -= quantity;
    status = outstandingQuantity == 0 ? LoanStatus.CLOSED : LoanStatus.PARTIALLY_RETURNED;
  }

  /**
   * Marks the position to market and asks whether the collateral still covers it.
   *
   * <p>Market value is the unit price times the <strong>outstanding</strong> quantity, not the
   * original: collateral posted for ten thousand shares does not have to cover ten thousand once
   * six thousand have come back.
   *
   * <p>Returns an {@code Optional} because "no call" is a real and common answer, not a failure.
   * This is the one place in the domain where {@code Optional} is used, and it is a return type,
   * which is the rule the serialization reproduction later in this stage shows the cost of
   * breaking.
   *
   * <p>A price in a currency other than the collateral's is refused by {@link Money#minus} rather
   * than converted. There is no FX in this domain, and a margin call is not the place to invent a
   * rate nobody recorded.
   */
  public Optional<MarginCall> revalue(Money unitMarketPrice, LocalDate on) {
    Objects.requireNonNull(unitMarketPrice, "unitMarketPrice");
    Objects.requireNonNull(on, "on");

    // Multiplying an amount already at currency scale by a whole number is exact, so the
    // RoundingMode cannot change the result here. It is still named, because Money.multiply
    // refuses to pick one and that refusal is the point.
    Money marketValue =
        unitMarketPrice.multiply(BigDecimal.valueOf(outstandingQuantity), RoundingMode.HALF_UP);
    Money shortfall = marketValue.minus(collateral);

    return shortfall.isNegative() || shortfall.equals(Money.zero(shortfall.currency()))
        ? Optional.empty()
        : Optional.of(new MarginCall(id, on, shortfall));
  }

  public LoanPositionId id() {
    return id;
  }

  public LoanStatus status() {
    return status;
  }

  public long outstandingQuantity() {
    return outstandingQuantity;
  }

  public long originalQuantity() {
    return originalQuantity;
  }

  /**
   * Package-private: the revaluation in {@code margin} needs these, and nothing outside this
   * package has asked. An aggregate that exposes every field it holds has stopped being an
   * aggregate and become a data structure with extra steps.
   */
  Money collateral() {
    return collateral;
  }

  CounterpartyId counterparty() {
    return counterparty;
  }

  Isin security() {
    return security;
  }

  AccrualRule accrualRule() {
    return accrualRule;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof LoanPosition position && id.equals(position.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public String toString() {
    return "LoanPosition[%s %s %d/%d]"
        .formatted(id.value(), status, outstandingQuantity, originalQuantity);
  }
}

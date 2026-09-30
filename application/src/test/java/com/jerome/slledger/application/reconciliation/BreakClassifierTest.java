package com.jerome.slledger.application.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.application.accrual.CorruptPositionException;
import com.jerome.slledger.application.reconciliation.HoldingPair.Both;
import com.jerome.slledger.application.reconciliation.HoldingPair.Ours;
import com.jerome.slledger.application.reconciliation.HoldingPair.OursOnly;
import com.jerome.slledger.application.reconciliation.HoldingPair.Theirs;
import com.jerome.slledger.application.reconciliation.HoldingPair.TheirsOnly;
import com.jerome.slledger.application.reconciliation.HoldingPair.Unreadable;
import com.jerome.slledger.domain.money.Money;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BreakClassifierTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Currency USD = Currency.getInstance("USD");
  private static final LocalDate DAY = LocalDate.of(2026, 9, 30);
  private static final String SNAPSHOT = "S1";

  private final BreakClassifier classifier = new BreakClassifier(ReconPolicy.defaults());

  private static Ours ours(long quantity, String value, Currency currency, LocalDate settled) {
    return new Ours("P-1", quantity, Money.of(value, currency), settled);
  }

  private static Theirs theirs(long quantity, String value, Currency currency) {
    return new Theirs("P-1", quantity, Money.of(value, currency));
  }

  private Optional<ReconBreak> both(Ours ours, Theirs theirs) {
    return classifier.classify(SNAPSHOT, DAY, new Both(ours, theirs));
  }

  private static final LocalDate LONG_SETTLED = LocalDate.of(2026, 9, 1);

  @Test
  @DisplayName("two sides that agree are not a break")
  void agreementIsNoBreak() {
    assertThat(both(ours(1_000, "41260.00", HKD, LONG_SETTLED), theirs(1_000, "41260.00", HKD)))
        .isEmpty();
  }

  @Test
  @DisplayName(
      "different quantities are a quantity mismatch for a person, and win over a value difference")
  void quantityMismatchIsManualAndFirst() {
    ReconBreak found =
        both(ours(1_000, "41260.00", HKD, LONG_SETTLED), theirs(900, "37134.00", HKD))
            .orElseThrow();

    assertThat(found.kind()).isEqualTo(BreakKind.QUANTITY_MISMATCH);
    assertThat(found.disposition()).isEqualTo(Disposition.MANUAL);
    assertThat(found.ourQuantity()).contains(1_000L);
    assertThat(found.theirQuantity()).contains(900L);
    assertThat(found.detail()).contains("1000").contains("900");
  }

  @Test
  @DisplayName("a value difference inside the tolerance is accepted as rounding")
  void smallValueDifferenceIsAccepted() {
    // The tolerance is the larger of 1.00 and 0.01% of 41,260.00, which is 4.126: 3.00 is inside
    // it.
    ReconBreak found =
        both(ours(1_000, "41260.00", HKD, LONG_SETTLED), theirs(1_000, "41263.00", HKD))
            .orElseThrow();

    assertThat(found.kind()).isEqualTo(BreakKind.VALUE_MISMATCH);
    assertThat(found.disposition()).isEqualTo(Disposition.AUTO_ACCEPTED);
  }

  @Test
  @DisplayName("the tolerance edge: exactly at it is accepted, one cent above it is not")
  void toleranceEdge() {
    // Small position: the fixed 1.00 is the larger of the two.
    assertThat(
            both(ours(1, "100.00", HKD, LONG_SETTLED), theirs(1, "101.00", HKD))
                .orElseThrow()
                .disposition())
        .isEqualTo(Disposition.AUTO_ACCEPTED);
    assertThat(
            both(ours(1, "100.00", HKD, LONG_SETTLED), theirs(1, "101.01", HKD))
                .orElseThrow()
                .disposition())
        .isEqualTo(Disposition.MANUAL);
  }

  @Test
  @DisplayName("a large position is held to a fraction of its value, not to a fixed sum")
  void relativeToleranceForLargeValues() {
    // 0.01% of 10,000,000.00 is 1,000.00.
    assertThat(
            both(ours(1, "10000000.00", HKD, LONG_SETTLED), theirs(1, "10000900.00", HKD))
                .orElseThrow()
                .disposition())
        .isEqualTo(Disposition.AUTO_ACCEPTED);
    assertThat(
            both(ours(1, "10000000.00", HKD, LONG_SETTLED), theirs(1, "10001100.00", HKD))
                .orElseThrow()
                .disposition())
        .isEqualTo(Disposition.MANUAL);
  }

  @Test
  @DisplayName("values in different currencies cannot be compared and go to a person")
  void currencyMismatchIsManual() {
    ReconBreak found =
        both(ours(1_000, "41260.00", HKD, LONG_SETTLED), theirs(1_000, "5290.00", USD))
            .orElseThrow();

    assertThat(found.kind()).isEqualTo(BreakKind.VALUE_MISMATCH);
    assertThat(found.disposition()).isEqualTo(Disposition.MANUAL);
    assertThat(found.detail()).contains("HKD").contains("USD");
  }

  @Test
  @DisplayName("a position only the ledger has, inside the settlement window, waits")
  void oursOnlyInsideTheWindowWaits() {
    // Settled 2026-09-28 with a window of two days: still inside on 2026-09-30, outside on the 1st.
    ReconBreak found =
        classifier
            .classify(
                SNAPSHOT,
                DAY,
                new OursOnly(ours(1_000, "41260.00", HKD, LocalDate.of(2026, 9, 28))))
            .orElseThrow();

    assertThat(found.kind()).isEqualTo(BreakKind.OURS_ONLY);
    assertThat(found.disposition()).isEqualTo(Disposition.PENDING_SETTLEMENT);
    assertThat(found.detail()).contains("inside the settlement window");
    assertThat(
            classifier
                .classify(
                    SNAPSHOT,
                    DAY.plusDays(1),
                    new OursOnly(ours(1_000, "41260.00", HKD, LocalDate.of(2026, 9, 28))))
                .orElseThrow()
                .disposition())
        .isEqualTo(Disposition.RETRY_SETTLEMENT);
  }

  @Test
  @DisplayName("a position only the ledger has, past the window, goes to the settlement ladder")
  void oursOnlyPastTheWindowIsRetried() {
    ReconBreak found =
        classifier
            .classify(SNAPSHOT, DAY, new OursOnly(ours(1_000, "41260.00", HKD, LONG_SETTLED)))
            .orElseThrow();

    assertThat(found.disposition()).isEqualTo(Disposition.RETRY_SETTLEMENT);
    assertThat(found.theirQuantity()).isEmpty();
  }

  @Test
  @DisplayName("a position only the custodian holds is never created, and always goes to a person")
  void theirsOnlyIsAlwaysManual() {
    ReconBreak found =
        classifier
            .classify(
                SNAPSHOT, DAY, new TheirsOnly(new Theirs("X-9", 500, Money.of("100.00", HKD))))
            .orElseThrow();

    assertThat(found.kind()).isEqualTo(BreakKind.THEIRS_ONLY);
    assertThat(found.disposition()).isEqualTo(Disposition.MANUAL);
    assertThat(found.positionId()).isEqualTo("X-9");
    assertThat(found.ourQuantity()).isEmpty();
  }

  @Test
  @DisplayName("a ledger row that could not be read is refused as that row's problem")
  void unreadableIsRefused() {
    assertThatThrownBy(() -> classifier.classify(SNAPSHOT, DAY, new Unreadable("P-5", "bad price")))
        .isInstanceOf(CorruptPositionException.class);
  }

  @Test
  @DisplayName("the policy refuses a negative tolerance or window")
  void policyIsValidated() {
    assertThatThrownBy(
            () -> new ReconPolicy(new java.math.BigDecimal("-1"), java.math.BigDecimal.ZERO, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ReconPolicy(java.math.BigDecimal.ONE, java.math.BigDecimal.ZERO, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

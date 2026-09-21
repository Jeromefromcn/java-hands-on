package com.jerome.slledger.domain.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.jerome.slledger.domain.accrual.AccrualRule;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.error.ExcessiveReturnException;
import com.jerome.slledger.domain.error.IllegalTransitionException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.util.Currency;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The state machine: OPEN -> PARTIALLY_RETURNED -> CLOSED, and nothing else.
 *
 * <p>Every arrow has a test and every refused arrow has a test. An arrow nobody wrote a test for is
 * an arrow somebody will add later without noticing they added it.
 */
class LoanPositionTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final AccrualRule RULE = new FlatRate(Rate.of("0.0365"));

  @Test
  void aNewlyOpenedPositionOwesEverythingItBorrowed() {
    LoanPosition position = aPositionOf(10_000L);

    assertThat(position.status()).isEqualTo(LoanStatus.OPEN);
    assertThat(position.outstandingQuantity()).isEqualTo(10_000L);
    assertThat(position.originalQuantity()).isEqualTo(10_000L);
  }

  @Test
  void aPartialReturnMovesToPartiallyReturned() {
    LoanPosition position = aPositionOf(10_000L);

    position.returnQuantity(4_000L);

    assertThat(position.status()).isEqualTo(LoanStatus.PARTIALLY_RETURNED);
    assertThat(position.outstandingQuantity()).isEqualTo(6_000L);
  }

  @Test
  void asecondPartialReturnThatClearsTheBalanceCloses() {
    LoanPosition position = aPositionOf(10_000L);

    position.returnQuantity(4_000L);
    position.returnQuantity(6_000L);

    assertThat(position.status()).isEqualTo(LoanStatus.CLOSED);
    assertThat(position.outstandingQuantity()).isZero();
  }

  @Test
  void aFullReturnInOneGoClosesWithoutPassingThroughPartiallyReturned() {
    LoanPosition position = aPositionOf(10_000L);

    position.returnQuantity(10_000L);

    assertThat(position.status()).isEqualTo(LoanStatus.CLOSED);
  }

  @Test
  void returningMoreThanIsOutstandingIsRefused() {
    LoanPosition position = aPositionOf(10_000L);
    position.returnQuantity(4_000L);

    assertThatExceptionOfType(ExcessiveReturnException.class)
        .isThrownBy(() -> position.returnQuantity(6_001L))
        .withMessageContaining("6001")
        .withMessageContaining("6000");

    // And the refusal left the position untouched, which is the half that is easy to omit.
    assertThat(position.outstandingQuantity()).isEqualTo(6_000L);
    assertThat(position.status()).isEqualTo(LoanStatus.PARTIALLY_RETURNED);
  }

  @Test
  void returningAgainstAClosedPositionIsRefused() {
    LoanPosition position = aPositionOf(10_000L);
    position.returnQuantity(10_000L);

    assertThatExceptionOfType(IllegalTransitionException.class)
        .isThrownBy(() -> position.returnQuantity(1L))
        .withMessageContaining("CLOSED");
  }

  @Test
  void returningNothingOrLessIsRefused() {
    LoanPosition position = aPositionOf(10_000L);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> position.returnQuantity(0L));
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> position.returnQuantity(-1L));
  }

  @Test
  void aPositionCannotBeOpenedForNothing() {
    assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> aPositionOf(0L));
  }

  @Test
  void twoPositionsAreTheSamePositionWhenTheirIdentifiersMatch() {
    // Identity equality, not value equality. The same position before and after a return is the
    // same position; a record's generated equals would have said otherwise the moment the
    // outstanding quantity changed.
    LoanPosition before = aPositionOf(10_000L);
    LoanPosition sameIdentity = aPositionOf(10_000L);
    before.returnQuantity(4_000L);

    assertThat(before).isEqualTo(sameIdentity);
    assertThat(before.hashCode()).isEqualTo(sameIdentity.hashCode());

    Set<LoanPosition> set = new HashSet<>();
    set.add(sameIdentity);
    assertThat(set).contains(before);
  }

  @Test
  void aPositionWithADifferentIdentifierIsADifferentPosition() {
    LoanPosition one = aPositionOf(10_000L);
    LoanPosition other =
        LoanPosition.open(
            new LoanPositionId("LP-0002"),
            new CounterpartyId("CP-0001"),
            new Isin("HK0000000001"),
            10_000L,
            Money.of("4126000.00", HKD),
            RULE);

    assertThat(one).isNotEqualTo(other);
  }

  private static LoanPosition aPositionOf(long quantity) {
    return LoanPosition.open(
        new LoanPositionId("LP-0001"),
        new CounterpartyId("CP-0001"),
        new Isin("HK0000000001"),
        quantity,
        Money.of("4126000.00", HKD),
        RULE);
  }
}

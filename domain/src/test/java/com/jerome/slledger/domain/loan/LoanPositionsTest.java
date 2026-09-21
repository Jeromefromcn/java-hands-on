package com.jerome.slledger.domain.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.jerome.slledger.domain.accrual.AccrualRule;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.error.CurrencyMismatchException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.jerome.slledger.domain.party.CounterpartyId;
import com.jerome.slledger.domain.party.ExposureSummary;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * groupingBy + teeing against the loop that does the same thing.
 *
 * <p>Only one of the two lives in {@code src/main}. The loop is here, in the test, because two
 * implementations of one thing in production code is how they drift apart; keeping it as the oracle
 * in a test is what makes the comparison honest and permanent.
 */
class LoanPositionsTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Currency USD = Currency.getInstance("USD");
  private static final AccrualRule RULE = new FlatRate(Rate.of("0.0365"));
  private static final CounterpartyId NORTHPOINT = new CounterpartyId("CP-0001");
  private static final CounterpartyId SAIWAN = new CounterpartyId("CP-0002");

  @Test
  void theStreamAndTheLoopAgree() {
    List<LoanPosition> positions =
        List.of(
            position("LP-0001", NORTHPOINT, "4126000.00"),
            position("LP-0002", NORTHPOINT, "1200000.00"),
            position("LP-0003", SAIWAN, "900000.00"));

    assertThat(LoanPositions.exposureByCounterparty(positions, HKD))
        .isEqualTo(exposureByCounterpartyWithALoop(positions, HKD));
  }

  @Test
  void eachCounterpartyGetsItsOwnCountAndTotal() {
    List<LoanPosition> positions =
        List.of(
            position("LP-0001", NORTHPOINT, "4126000.00"),
            position("LP-0002", NORTHPOINT, "1200000.00"),
            position("LP-0003", SAIWAN, "900000.00"));

    Map<CounterpartyId, ExposureSummary> exposure =
        LoanPositions.exposureByCounterparty(positions, HKD);

    assertThat(exposure)
        .containsOnlyKeys(NORTHPOINT, SAIWAN)
        .containsEntry(NORTHPOINT, new ExposureSummary(2L, Money.of("5326000.00", HKD)))
        .containsEntry(SAIWAN, new ExposureSummary(1L, Money.of("900000.00", HKD)));
  }

  @Test
  void anEmptyListSummarisesToAnEmptyMapNotToAZeroRow() {
    assertThat(LoanPositions.exposureByCounterparty(List.of(), HKD)).isEmpty();
  }

  @Test
  void aPositionCollateralisedInAnotherCurrencyIsRefusedRatherThanSummarisedAway() {
    // The interesting part of this exercise. teeing is clean right up to the point where the
    // reduction needs an identity, and Money.zero needs a currency to be one. Having to pass
    // the currency in is the money invariant refusing to be summarised away: there is no
    // currency-agnostic zero, so there is no way to quietly add HKD to USD.
    List<LoanPosition> mixed =
        List.of(
            position("LP-0001", NORTHPOINT, "4126000.00"),
            positionIn(USD, "LP-0002", NORTHPOINT, "500000.00"));

    assertThatExceptionOfType(CurrencyMismatchException.class)
        .isThrownBy(() -> LoanPositions.exposureByCounterparty(mixed, HKD));
  }

  /** The oracle. Six statements against one expression; both are readable, neither is clever. */
  private static Map<CounterpartyId, ExposureSummary> exposureByCounterpartyWithALoop(
      List<LoanPosition> positions, Currency currency) {
    Map<CounterpartyId, ExposureSummary> exposure = new HashMap<>();
    for (LoanPosition position : positions) {
      ExposureSummary sofar =
          exposure.getOrDefault(
              position.counterparty(), new ExposureSummary(0L, Money.zero(currency)));
      exposure.put(
          position.counterparty(),
          new ExposureSummary(
              sofar.positionCount() + 1, sofar.totalCollateral().plus(position.collateral())));
    }
    return exposure;
  }

  private static LoanPosition position(String id, CounterpartyId counterparty, String collateral) {
    return positionIn(HKD, id, counterparty, collateral);
  }

  private static LoanPosition positionIn(
      Currency currency, String id, CounterpartyId counterparty, String collateral) {
    return LoanPosition.open(
        new LoanPositionId(id),
        counterparty,
        new Isin("HK0000000001"),
        10_000L,
        Money.of(collateral, currency),
        RULE);
  }
}

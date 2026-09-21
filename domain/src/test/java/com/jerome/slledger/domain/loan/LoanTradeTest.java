package com.jerome.slledger.domain.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.jerome.slledger.domain.error.MalformedIdentifierException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/**
 * LoanTrade has nine components, several of them the same shape. That is the point: this is where
 * the builder earns its place or fails to, and both constructions are kept side by side so the
 * comparison can be read rather than argued.
 */
class LoanTradeTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final IdempotencyKey KEY = new IdempotencyKey("REQ-2026-03-02-0001");
  private static final LoanPositionId POSITION = new LoanPositionId("LP-0001");
  private static final CounterpartyId BORROWER = new CounterpartyId("CP-0001");
  private static final Isin SECURITY = new Isin("HK0000000001");
  private static final LocalDate TRADE_DATE = LocalDate.of(2026, 3, 2);
  private static final LocalDate SETTLEMENT_DATE = LocalDate.of(2026, 3, 4);

  @Test
  void theBuilderAndTheCanonicalConstructorProduceTheSameTrade() {
    // Nine positional arguments. Four of them are LocalDate or long, so transposing two of them
    // compiles and passes every type check.
    LoanTrade positional =
        new LoanTrade(
            KEY,
            POSITION,
            BORROWER,
            SECURITY,
            TradeDirection.BORROW,
            10_000L,
            Money.of("412.60", HKD),
            TRADE_DATE,
            SETTLEMENT_DATE);

    LoanTrade built =
        LoanTrade.builder()
            .idempotencyKey(KEY)
            .position(POSITION)
            .counterparty(BORROWER)
            .security(SECURITY)
            .direction(TradeDirection.BORROW)
            .quantity(10_000L)
            .unitPrice(Money.of("412.60", HKD))
            .tradeDate(TRADE_DATE)
            .settlementDate(SETTLEMENT_DATE)
            .build();

    assertThat(built).isEqualTo(positional);
  }

  @Test
  void aSettlementDateBeforeTheTradeDateIsRefused() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> aTrade().settlementDate(TRADE_DATE.minusDays(1)).build())
        .withMessageContaining("settlement");
  }

  @Test
  void aNonPositiveQuantityIsRefused() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> aTrade().quantity(0L).build());
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> aTrade().quantity(-1L).build());
  }

  @Test
  void theBuilderRefusesToBuildATradeWithAMissingComponent() {
    // The canonical constructor cannot express this: a missing argument is a compile error
    // there, and a null one is not. The builder's build() is the only place a half-specified
    // trade can be caught by name.
    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> LoanTrade.builder().idempotencyKey(KEY).build())
        .withMessageContaining("position");
  }

  @Test
  void theSameRequestSubmittedTwiceCarriesTheSameIdempotencyKey() {
    // The key identifies the request, not the trade. Two trades are the same submission if and
    // only if their keys are equal; deciding what to do about that is a stage 4 concern, and
    // this type only has to make the comparison possible.
    assertThat(aTrade().build().idempotencyKey()).isEqualTo(aTrade().build().idempotencyKey());
    assertThat(new IdempotencyKey("REQ-1")).isNotEqualTo(new IdempotencyKey("REQ-2"));
  }

  @Test
  void anIdempotencyKeyCannotBeBlank() {
    assertThatExceptionOfType(MalformedIdentifierException.class)
        .isThrownBy(() -> new IdempotencyKey(" "));
  }

  @Test
  void aReturnIsTheSameShapeOfTradeInTheOtherDirection() {
    LoanTrade returned = aTrade().direction(TradeDirection.RETURN).quantity(4_000L).build();

    assertThat(returned.direction()).isEqualTo(TradeDirection.RETURN);
    assertThat(returned.position()).isEqualTo(POSITION);
  }

  private static LoanTrade.Builder aTrade() {
    return LoanTrade.builder()
        .idempotencyKey(KEY)
        .position(POSITION)
        .counterparty(BORROWER)
        .security(SECURITY)
        .direction(TradeDirection.BORROW)
        .quantity(10_000L)
        .unitPrice(Money.of("412.60", HKD))
        .tradeDate(TRADE_DATE)
        .settlementDate(SETTLEMENT_DATE);
  }
}

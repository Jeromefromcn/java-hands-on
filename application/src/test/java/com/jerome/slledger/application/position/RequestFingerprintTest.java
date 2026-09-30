package com.jerome.slledger.application.position;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final LocalDate DAY = LocalDate.of(2026, 9, 30);

  private static final OpenPositionCommand OPEN =
      new OpenPositionCommand(
          new IdempotencyKey("k-1"),
          new CounterpartyId("CP-001"),
          new Isin("HK0000000001"),
          100,
          Money.of("1000.00", HKD),
          new FlatRate(Rate.of("0.0400")),
          DAY);

  @Test
  @DisplayName("equal commands give equal fingerprints, and the key is not part of it")
  void equalCommandsEqualFingerprints() {
    OpenPositionCommand otherKey =
        new OpenPositionCommand(
            new IdempotencyKey("k-2"),
            OPEN.counterparty(),
            OPEN.security(),
            OPEN.quantity(),
            OPEN.collateral(),
            OPEN.rule(),
            OPEN.tradeDate());

    assertThat(otherKey.fingerprint()).isEqualTo(OPEN.fingerprint());
    assertThat(OPEN.fingerprint().sha256()).matches("[0-9a-f]{64}");
  }

  @Test
  @DisplayName("each field changed alone changes the fingerprint")
  void eachFieldCounts() {
    List<UnaryOperator<OpenPositionCommand>> changes =
        List.of(
            c ->
                with(
                    c,
                    new CounterpartyId("CP-002"),
                    c.security(),
                    c.quantity(),
                    c.collateral(),
                    c.rule(),
                    c.tradeDate()),
            c ->
                with(
                    c,
                    c.counterparty(),
                    new Isin("HK0000000002"),
                    c.quantity(),
                    c.collateral(),
                    c.rule(),
                    c.tradeDate()),
            c ->
                with(
                    c,
                    c.counterparty(),
                    c.security(),
                    101,
                    c.collateral(),
                    c.rule(),
                    c.tradeDate()),
            c ->
                with(
                    c,
                    c.counterparty(),
                    c.security(),
                    c.quantity(),
                    Money.of("1000.01", HKD),
                    c.rule(),
                    c.tradeDate()),
            c ->
                with(
                    c,
                    c.counterparty(),
                    c.security(),
                    c.quantity(),
                    Money.of("1000", Currency.getInstance("JPY")),
                    c.rule(),
                    c.tradeDate()),
            c ->
                with(
                    c,
                    c.counterparty(),
                    c.security(),
                    c.quantity(),
                    c.collateral(),
                    new FlatRate(Rate.of("0.0401")),
                    c.tradeDate()),
            c ->
                with(
                    c,
                    c.counterparty(),
                    c.security(),
                    c.quantity(),
                    c.collateral(),
                    c.rule(),
                    DAY.plusDays(1)));

    assertThat(changes)
        .allSatisfy(
            change ->
                assertThat(change.apply(OPEN).fingerprint()).isNotEqualTo(OPEN.fingerprint()));
  }

  @Test
  @DisplayName("12.5 and 12.50 HKD are one amount, so one request")
  void moneyScaleDoesNotCount() {
    OpenPositionCommand a =
        with(
            OPEN,
            OPEN.counterparty(),
            OPEN.security(),
            1,
            Money.of(new BigDecimal("12.5"), HKD),
            OPEN.rule(),
            DAY);
    OpenPositionCommand b =
        with(
            OPEN,
            OPEN.counterparty(),
            OPEN.security(),
            1,
            Money.of(new BigDecimal("12.50"), HKD),
            OPEN.rule(),
            DAY);

    assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
  }

  @Test
  @DisplayName("an open and a return never share a fingerprint")
  void openAndReturnDiffer() {
    ReturnCommand ret =
        new ReturnCommand(new IdempotencyKey("k-1"), new LoanPositionId("CP-001"), 100, DAY);

    assertThat(ret.fingerprint()).isNotEqualTo(OPEN.fingerprint());
    assertThat(ret.fingerprint())
        .isEqualTo(
            new ReturnCommand(new IdempotencyKey("k-9"), new LoanPositionId("CP-001"), 100, DAY)
                .fingerprint());
    assertThat(ret.fingerprint())
        .isNotEqualTo(
            new ReturnCommand(new IdempotencyKey("k-1"), new LoanPositionId("CP-001"), 99, DAY)
                .fingerprint());
  }

  private static OpenPositionCommand with(
      OpenPositionCommand c,
      CounterpartyId counterparty,
      Isin security,
      long quantity,
      Money collateral,
      com.jerome.slledger.domain.accrual.AccrualRule rule,
      LocalDate tradeDate) {
    return new OpenPositionCommand(
        c.key(), counterparty, security, quantity, collateral, rule, tradeDate);
  }
}

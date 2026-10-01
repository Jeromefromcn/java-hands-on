package com.jerome.slledger.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.application.position.OpenPosition;
import com.jerome.slledger.application.position.OpenPositionCommand;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.jerome.slledger.domain.party.CounterpartyId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A counter says what committed. An open whose transaction rolls back is not an open. */
@Timeout(120)
class RolledBackOpenCountsNothingIT {

  private static final Currency HKD = Currency.getInstance("HKD");

  private static OpenPositionCommand command(String key) {
    return new OpenPositionCommand(
        new IdempotencyKey(key),
        new CounterpartyId("CP-001"),
        new Isin("HK0000000001"),
        1_000,
        Money.of("50000.00", HKD),
        new FlatRate(Rate.of("0.0365")),
        LocalDate.of(2026, 9, 1));
  }

  private static double opened(MeterRegistry registry) {
    Counter counter = registry.find("ledger.positions.opened").tag("currency", "HKD").counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  @DisplayName("an open that rolls back counts nothing; one that commits counts once")
  void countsWhatCommitted() {
    try (ConfigurableApplicationContext app = LedgerApp.start()) {
      LedgerApp.referenceData(app.getBean(org.springframework.jdbc.core.simple.JdbcClient.class));
      OpenPosition open = app.getBean(OpenPosition.class);
      MeterRegistry registry = app.getBean(MeterRegistry.class);
      TransactionTemplate tx =
          new TransactionTemplate(app.getBean(PlatformTransactionManager.class));

      assertThatThrownBy(
              () ->
                  tx.executeWithoutResult(
                      status -> {
                        open.open(command("ROLLED-BACK"));
                        throw new IllegalStateException(
                            "the caller's transaction fails after the open");
                      }))
          .isInstanceOf(IllegalStateException.class);
      assertThat(opened(registry)).isZero();

      tx.executeWithoutResult(status -> open.open(command("COMMITTED")));
      assertThat(opened(registry)).isEqualTo(1.0);
    }
  }
}

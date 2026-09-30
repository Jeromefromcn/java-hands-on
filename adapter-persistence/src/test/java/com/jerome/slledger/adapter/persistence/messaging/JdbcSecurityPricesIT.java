package com.jerome.slledger.adapter.persistence.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.persistence.testing.LedgerDb;
import com.jerome.slledger.adapter.persistence.testing.LedgerFixtures;
import com.jerome.slledger.application.messaging.SecurityPositions;
import com.jerome.slledger.application.messaging.SecurityPrices;
import com.jerome.slledger.application.position.UnknownReferenceException;
import com.jerome.slledger.domain.error.CurrencyMismatchException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.money.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Timeout(60)
class JdbcSecurityPricesIT {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Currency USD = Currency.getInstance("USD");
  private static final Isin ISIN = new Isin("HK0000000001");

  private static ConfigurableApplicationContext context;

  private SecurityPrices prices;
  private SecurityPositions positions;
  private JdbcClient jdbc;
  private TransactionTemplate transaction;

  @BeforeAll
  static void start() {
    context = LedgerDb.start();
  }

  @AfterAll
  static void stop() {
    context.close();
  }

  @BeforeEach
  void setUp() {
    prices = context.getBean(SecurityPrices.class);
    positions = context.getBean(SecurityPositions.class);
    jdbc = context.getBean(JdbcClient.class);
    transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    jdbc.sql("DELETE FROM loan_position").update();
    jdbc.sql("DELETE FROM security").update();
    jdbc.sql("DELETE FROM counterparty").update();
    new LedgerFixtures(jdbc).counterparty("CP-001").security(ISIN.value(), "41.26");
  }

  private boolean apply(String amount, Currency currency, long version) {
    return transaction.execute(
        status -> prices.applyIfNewer(ISIN, Money.of(amount, currency), version));
  }

  private BigDecimal storedPrice() {
    return jdbc.sql("SELECT market_price_amount FROM security WHERE isin = ?")
        .param(ISIN.value())
        .query(BigDecimal.class)
        .single();
  }

  private long storedVersion() {
    return jdbc.sql("SELECT price_version FROM security WHERE isin = ?")
        .param(ISIN.value())
        .query(Long.class)
        .single();
  }

  @Test
  @DisplayName("a higher version is stored; the same or a lower one is ignored")
  void versionGate() {
    assertThat(apply("41.30", HKD, 1)).isTrue();
    assertThat(apply("43.10", HKD, 3)).isTrue();

    assertThat(apply("42.00", HKD, 2)).isFalse();
    assertThat(apply("99.99", HKD, 3)).isFalse();

    assertThat(storedPrice()).isEqualByComparingTo("43.10");
    assertThat(storedVersion()).isEqualTo(3);
  }

  @Test
  @DisplayName(
      "an unknown security and a price in the wrong currency are refused, and store nothing")
  void refusals() {
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status ->
                        prices.applyIfNewer(new Isin("HK0000000999"), Money.of("1.00", HKD), 1)))
        .isInstanceOf(UnknownReferenceException.class);
    assertThatThrownBy(() -> apply("41.30", USD, 1)).isInstanceOf(CurrencyMismatchException.class);

    assertThat(storedPrice()).isEqualByComparingTo("41.26");
    assertThat(storedVersion()).isZero();
  }

  @Test
  @DisplayName("an update cannot be applied outside a transaction")
  void needsATransaction() {
    assertThatThrownBy(() -> prices.applyIfNewer(ISIN, Money.of("41.30", HKD), 1))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
  }

  @Test
  @DisplayName("two updates racing in the wrong order end on the higher version")
  void racingUpdatesEndOnTheHigherVersion() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 1; round <= 20; round++) {
        long low = round * 10L;
        long high = low + 1;
        jdbc.sql("UPDATE security SET price_version = 0").update();
        Future<Boolean> a = pool.submit(() -> apply("50.00", HKD, high));
        Future<Boolean> b = pool.submit(() -> apply("40.00", HKD, low));
        a.get();
        b.get();
        assertThat(storedVersion()).isEqualTo(high);
        assertThat(storedPrice()).isEqualByComparingTo("50.00");
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("a price keeps exactly the digits it was given")
  void priceRoundTrip() {
    apply("1234567890123456.78", HKD, 1);

    assertThat(storedPrice()).isEqualByComparingTo("1234567890123456.78");
  }

  @Test
  @DisplayName("openIn returns the non-closed positions of one security, read only")
  void openInReturnsOpenPositions() {
    new LedgerFixtures(jdbc)
        .security("HK0000000002", "7.50")
        .position("P-1", "CP-001", ISIN.value(), 1000)
        .position("P-2", "CP-001", ISIN.value(), 500)
        .position("P-3", "CP-001", "HK0000000002", 100);
    jdbc.sql(
            "UPDATE loan_position SET status = 'CLOSED', outstanding_quantity = 0 WHERE id = 'P-2'")
        .update();

    List<LoanPosition> found = transaction.execute(status -> positions.openIn(ISIN));

    assertThat(found).extracting(p -> p.id().value()).containsExactly("P-1");
    assertThat(found.getFirst().collateral()).isEqualTo(Money.of("1000.00", HKD));
  }
}

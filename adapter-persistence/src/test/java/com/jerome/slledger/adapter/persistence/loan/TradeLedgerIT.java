package com.jerome.slledger.adapter.persistence.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.persistence.testing.LedgerDb;
import com.jerome.slledger.adapter.persistence.testing.LedgerFixtures;
import com.jerome.slledger.application.port.BookedTrade;
import com.jerome.slledger.application.port.DuplicateRequestException;
import com.jerome.slledger.application.port.RequestFingerprint;
import com.jerome.slledger.application.port.TradeLedger;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.loan.LoanTrade;
import com.jerome.slledger.domain.loan.TradeDirection;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** The trade ledger's contract since V4: book with a fingerprint, find by key. */
class TradeLedgerIT {

  private static final RequestFingerprint FP = RequestFingerprint.of("request-a");

  private static ConfigurableApplicationContext context;
  private static TradeLedger ledger;
  private static TransactionTemplate tx;
  private static JdbcClient jdbc;

  @BeforeAll
  static void start() {
    context = LedgerDb.start();
    jdbc = context.getBean(JdbcClient.class);
    new LedgerFixtures(jdbc)
        .counterparty("CP-001")
        .security("HK0000000001", "41.26")
        .position("LP-1", "CP-001", "HK0000000001", 100);
    ledger = context.getBean(TradeLedger.class);
    tx = context.getBean(TransactionTemplate.class);
  }

  @AfterAll
  static void stop() {
    context.close();
  }

  @Test
  @DisplayName("a booked trade is found by its key, with the fingerprint that booked it")
  void bookThenFind() {
    LoanTrade trade = trade("key-find");

    tx.executeWithoutResult(s -> ledger.book(trade, FP));
    BookedTrade found = ledger.findByKey(new IdempotencyKey("key-find")).orElseThrow();

    assertThat(found.trade()).isEqualTo(trade);
    assertThat(found.fingerprint()).isEqualTo(FP);
    assertThat(found.bookedBy(FP)).isTrue();
    assertThat(found.bookedBy(RequestFingerprint.of("request-b"))).isFalse();
  }

  @Test
  @DisplayName("an unknown key finds nothing")
  void unknownKey() {
    assertThat(ledger.findByKey(new IdempotencyKey("never-booked"))).isEmpty();
  }

  @Test
  @DisplayName("a second booking under the same key is refused inside the transaction")
  void duplicateRefused() {
    tx.executeWithoutResult(s -> ledger.book(trade("key-dup"), FP));

    assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledger.book(trade("key-dup"), FP)))
        .isInstanceOf(DuplicateRequestException.class);
    assertThat(
            jdbc.sql("SELECT count(*) FROM loan_trade WHERE idempotency_key = 'key-dup'")
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("a trade booked before V4 has no fingerprint and matches no request")
  void preV4Row() {
    jdbc.sql(
            "INSERT INTO loan_trade (idempotency_key, position_id, counterparty_id, isin,"
                + " direction, quantity, unit_price_amount, unit_price_currency, trade_date,"
                + " settlement_date) VALUES ('key-old', 'LP-1', 'CP-001', 'HK0000000001',"
                + " 'BORROW', 100, 41.26, 'HKD', date '2026-09-28', date '2026-09-30')")
        .update();

    BookedTrade old = ledger.findByKey(new IdempotencyKey("key-old")).orElseThrow();

    assertThat(old.fingerprint()).isNull();
    assertThat(old.bookedBy(FP)).isFalse();
  }

  private static LoanTrade trade(String key) {
    return LoanTrade.builder()
        .idempotencyKey(new IdempotencyKey(key))
        .position(new LoanPositionId("LP-1"))
        .counterparty(new CounterpartyId("CP-001"))
        .security(new Isin("HK0000000001"))
        .direction(TradeDirection.BORROW)
        .quantity(100)
        .unitPrice(Money.of("41.26", Currency.getInstance("HKD")))
        .tradeDate(LocalDate.of(2026, 9, 28))
        .settlementDate(LocalDate.of(2026, 9, 30))
        .build();
  }
}

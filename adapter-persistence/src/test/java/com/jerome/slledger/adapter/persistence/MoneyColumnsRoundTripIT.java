package com.jerome.slledger.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.jerome.slledger.adapter.persistence.accrual.AccrualEntryEntity;
import com.jerome.slledger.adapter.persistence.accrual.AccrualEntryMapper;
import com.jerome.slledger.adapter.persistence.instrument.SecurityEntity;
import com.jerome.slledger.adapter.persistence.instrument.SecurityMapper;
import com.jerome.slledger.adapter.persistence.loan.LoanPositionEntity;
import com.jerome.slledger.adapter.persistence.loan.LoanPositionMapper;
import com.jerome.slledger.adapter.persistence.loan.LoanTradeEntity;
import com.jerome.slledger.adapter.persistence.loan.LoanTradeMapper;
import com.jerome.slledger.adapter.persistence.loan.UnsupportedAccrualRuleException;
import com.jerome.slledger.adapter.persistence.party.CounterpartyEntity;
import com.jerome.slledger.adapter.persistence.party.CounterpartyMapper;
import com.jerome.slledger.adapter.persistence.testing.LedgerDb;
import com.jerome.slledger.domain.accrual.AccrualEntry;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.accrual.FloatingRate;
import com.jerome.slledger.domain.accrual.MarginalTieredRate;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.instrument.Security;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.loan.LoanStatus;
import com.jerome.slledger.domain.loan.LoanTrade;
import com.jerome.slledger.domain.loan.TradeDirection;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.jerome.slledger.domain.party.Counterparty;
import com.jerome.slledger.domain.party.CounterpartyId;
import com.jerome.slledger.domain.party.CreditLimit;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The money invariant at every column stage 6 adds: amount, scale and currency survive the trip
 * through {@code numeric(22,6)} plus {@code char(3)}, and a stored amount with more precision than
 * its currency is refused on the way back rather than rounded a second time.
 *
 * <p>The column type does not carry the invariant; {@code Money} does. The raw column always comes
 * back at scale 6, and equality holds only because {@code Money} re-scales on construction. That
 * re-scaling is also a rounding, which is why {@code MoneyColumns} compares before it trusts it.
 */
class MoneyColumnsRoundTripIT {

  private static final LocalDate TRADE_DATE = LocalDate.of(2026, 9, 1);

  private static ConfigurableApplicationContext context;
  private static TransactionTemplate tx;
  private static EntityManagerFactory emf;
  private static JdbcClient jdbc;

  @BeforeAll
  static void start() {
    context = LedgerDb.start();
    tx = context.getBean(TransactionTemplate.class);
    emf = context.getBean(EntityManagerFactory.class);
    jdbc = context.getBean(JdbcClient.class);
  }

  @AfterAll
  static void stop() {
    context.close();
  }

  @BeforeEach
  void empty() {
    jdbc.sql("TRUNCATE accrual_entry, loan_trade, loan_position, security, counterparty").update();
  }

  /**
   * Three currencies, three minor-unit scales. The credit limit uses all sixteen integer digits
   * {@code numeric(22,6)} leaves, so the headroom claim in the decision record is exercised, not
   * computed. The last value is one unit of the digit past the currency's scale.
   */
  static Stream<Arguments> currencies() {
    return Stream.of(
        Arguments.of("HKD", "9999999999999999.99", "41.26", "4126000.00", "143.84", "0.001"),
        Arguments.of("JPY", "9999999999999999", "5120", "51200000", "144", "0.1"),
        Arguments.of("BHD", "9999999999999999.999", "12.345", "123450.000", "0.178", "0.0001"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("currencies")
  void everyMoneyColumnComesBackExactly(
      String code, String limit, String price, String collateral, String fee, String unused) {
    Graph written = Graph.in(Currency.getInstance(code), limit, price, collateral, fee);
    written.persist();

    Graph read = Graph.read();

    assertThat(read.counterparty).isEqualTo(written.counterparty);
    assertThat(read.security).isEqualTo(written.security);
    assertThat(read.trade).isEqualTo(written.trade);
    assertThat(read.entry).isEqualTo(written.entry);
    assertSamePositionState(read.position, written.position);
  }

  /** Which column to corrupt, and how to read the row back through its mapper. */
  enum MoneyColumn {
    CREDIT_LIMIT("counterparty", "credit_limit_amount", Graph::readCounterparty),
    MARKET_PRICE("security", "market_price_amount", Graph::readSecurity),
    COLLATERAL("loan_position", "collateral_amount", Graph::readPosition),
    UNIT_PRICE("loan_trade", "unit_price_amount", Graph::readTrade),
    FEE("accrual_entry", "fee_amount", Graph::readEntry);

    final String table;
    final String column;
    final Function<EntityManager, Object> read;

    MoneyColumn(String table, String column, Function<EntityManager, Object> read) {
      this.table = table;
      this.column = column;
      this.read = read;
    }
  }

  static Stream<Arguments> everyColumnInEveryCurrency() {
    return currencies()
        .flatMap(
            currency ->
                Stream.of(MoneyColumn.values())
                    .map(column -> Arguments.of(column, currency.get()[0], currency.get())));
  }

  @ParameterizedTest(name = "{0} in {1}")
  @MethodSource("everyColumnInEveryCurrency")
  void anAmountWithMorePrecisionThanItsCurrencyIsRefusedOnRead(
      MoneyColumn column, String code, Object[] values) {
    Graph.in(
            Currency.getInstance(code),
            (String) values[1],
            (String) values[2],
            (String) values[3],
            (String) values[4])
        .persist();
    // Written by something other than this code: the only way a column gets a digit its currency
    // does not have. See docs/decisions/06-persistence.md.
    jdbc.sql(
            "UPDATE %s SET %s = %s + %s"
                .formatted(column.table, column.column, column.column, values[5]))
        .update();

    assertThatExceptionOfType(ArithmeticException.class)
        .isThrownBy(() -> inTransaction(column.read))
        .withMessageContaining(code);
  }

  @Test
  void aRowWhoseStatusDisagreesWithItsQuantitiesIsRefusedOnRead() {
    Graph.in(Currency.getInstance("HKD"), "1000000.00", "41.26", "4126000.00", "143.84").persist();
    jdbc.sql("UPDATE loan_position SET status = 'CLOSED'").update();

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> inTransaction(Graph::readPosition))
        .withMessageContaining("CLOSED with 10000 of 10000");
  }

  @Test
  void aRateKeepsEveryDigitAndItsScale() {
    // Rate is deliberately not normalised, and its record equality compares BigDecimal scale.
    // A fixed-scale column would pad 0.0400 to 0.04000000 and round the long one: both would read
    // back as a different Rate. Unconstrained numeric stores the value as given.
    for (String annual : List.of("0.0400", "0.036512345678901234567")) {
      empty();
      FlatRate rule = new FlatRate(Rate.of(annual));
      Graph written =
          Graph.in(Currency.getInstance("HKD"), "1000000.00", "41.26", "4126000.00", "143.84")
              .withRule(rule);
      written.persist();

      assertThat(Graph.read().position.accrualRule()).isEqualTo(rule);
    }
  }

  @Test
  void aFloatingRateIsRefusedBecauseItIsNotAValue() {
    FloatingRate floating = new FloatingRate(date -> Rate.of("0.03"), Rate.of("0.01"));

    assertThatExceptionOfType(UnsupportedAccrualRuleException.class)
        .isThrownBy(
            () ->
                LoanPositionMapper.toEntity(
                    Graph.in(Currency.getInstance("HKD"), "1", "1", "1", "1")
                        .withRule(floating)
                        .position))
        .withMessageContaining("FloatingRate");
  }

  @Test
  void aTieredRateIsRefusedUntilItHasATable() {
    TreeMap<BigDecimal, Rate> tiers = new TreeMap<>();
    tiers.put(BigDecimal.ZERO, Rate.of("0.03"));
    MarginalTieredRate tiered = new MarginalTieredRate(tiers);

    assertThatExceptionOfType(UnsupportedAccrualRuleException.class)
        .isThrownBy(
            () ->
                LoanPositionMapper.toEntity(
                    Graph.in(Currency.getInstance("HKD"), "1", "1", "1", "1")
                        .withRule(tiered)
                        .position))
        .withMessageContaining("MarginalTieredRate");
  }

  private static void assertSamePositionState(LoanPosition actual, LoanPosition expected) {
    assertThat(actual).isEqualTo(expected);
    assertThat(actual.status()).isEqualTo(expected.status());
    assertThat(actual.originalQuantity()).isEqualTo(expected.originalQuantity());
    assertThat(actual.outstandingQuantity()).isEqualTo(expected.outstandingQuantity());
    assertThat(actual.counterparty()).isEqualTo(expected.counterparty());
    assertThat(actual.security()).isEqualTo(expected.security());
    assertThat(actual.collateral()).isEqualTo(expected.collateral());
    assertThat(actual.accrualRule()).isEqualTo(expected.accrualRule());
  }

  private static <T> T inTransaction(Function<EntityManager, T> work) {
    // The transaction's own EntityManager, the one JpaTransactionManager bound to this thread.
    return tx.execute(
        status -> work.apply(EntityManagerFactoryUtils.getTransactionalEntityManager(emf)));
  }

  /** One of each persisted type, all in one currency, linked the way the ledger links them. */
  private record Graph(
      Counterparty counterparty,
      Security security,
      LoanPosition position,
      LoanTrade trade,
      AccrualEntry entry) {

    static Graph in(Currency ccy, String limit, String price, String collateral, String fee) {
      Counterparty counterparty =
          new Counterparty(
              new CounterpartyId("CP-001"),
              "Synthetic Borrower One",
              new CreditLimit(Money.of(limit, ccy)));
      Security security =
          new Security(new Isin("HK0000000001"), "Synthetic Equity", ccy, Money.of(price, ccy));
      LoanPosition position =
          LoanPosition.open(
              new LoanPositionId("LP-001"),
              counterparty.id(),
              security.isin(),
              10_000L,
              Money.of(collateral, ccy),
              new FlatRate(Rate.of("0.0365")));
      LoanTrade trade =
          LoanTrade.builder()
              .idempotencyKey(new IdempotencyKey("K-001"))
              .position(position.id())
              .counterparty(counterparty.id())
              .security(security.isin())
              .direction(TradeDirection.BORROW)
              .quantity(10_000L)
              .unitPrice(security.marketPrice())
              .tradeDate(TRADE_DATE)
              .settlementDate(TRADE_DATE.plusDays(2))
              .build();
      AccrualEntry entry = new AccrualEntry(position.id(), TRADE_DATE, Money.of(fee, ccy));
      return new Graph(counterparty, security, position, trade, entry);
    }

    Graph withRule(com.jerome.slledger.domain.accrual.AccrualRule rule) {
      LoanPosition replaced =
          LoanPosition.restore(
              position.id(),
              position.counterparty(),
              position.security(),
              position.originalQuantity(),
              position.outstandingQuantity(),
              LoanStatus.OPEN,
              position.collateral(),
              rule);
      return new Graph(counterparty, security, replaced, trade, entry);
    }

    void persist() {
      inTransaction(
          em -> {
            em.persist(CounterpartyMapper.toEntity(counterparty));
            em.persist(SecurityMapper.toEntity(security));
            em.persist(LoanPositionMapper.toEntity(position));
            em.persist(LoanTradeMapper.toEntity(trade));
            em.persist(AccrualEntryMapper.toEntity(entry));
            em.flush();
            return null;
          });
    }

    static Graph read() {
      return inTransaction(
          em ->
              new Graph(
                  readCounterparty(em),
                  readSecurity(em),
                  readPosition(em),
                  readTrade(em),
                  readEntry(em)));
    }

    static Counterparty readCounterparty(EntityManager em) {
      return CounterpartyMapper.toDomain(em.find(CounterpartyEntity.class, "CP-001"));
    }

    static Security readSecurity(EntityManager em) {
      return SecurityMapper.toDomain(em.find(SecurityEntity.class, "HK0000000001"));
    }

    static LoanPosition readPosition(EntityManager em) {
      return LoanPositionMapper.toDomain(em.find(LoanPositionEntity.class, "LP-001"));
    }

    static LoanTrade readTrade(EntityManager em) {
      return LoanTradeMapper.toDomain(
          em.createQuery("select t from LoanTradeEntity t", LoanTradeEntity.class)
              .getSingleResult());
    }

    static AccrualEntry readEntry(EntityManager em) {
      return AccrualEntryMapper.toDomain(
          em.createQuery("select e from AccrualEntryEntity e", AccrualEntryEntity.class)
              .getSingleResult());
    }
  }
}

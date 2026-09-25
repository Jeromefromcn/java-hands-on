package com.jerome.slledger.bootstrap.position;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.jerome.slledger.adapter.persistence.testing.SharedPostgres;
import com.jerome.slledger.application.port.DuplicateRequestException;
import com.jerome.slledger.application.port.PositionPage;
import com.jerome.slledger.application.port.PositionRow;
import com.jerome.slledger.application.position.ListPositions;
import com.jerome.slledger.application.position.OpenPosition;
import com.jerome.slledger.application.position.OpenPositionCommand;
import com.jerome.slledger.application.position.ReturnCommand;
import com.jerome.slledger.application.position.ReturnPosition;
import com.jerome.slledger.application.position.UnknownReferenceException;
import com.jerome.slledger.bootstrap.SlLedgerApplication;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.error.CurrencyMismatchException;
import com.jerome.slledger.domain.error.InsufficientCreditException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.loan.LoanStatus;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The three use cases, through the real application's wiring, against a database of their own. */
class PositionUseCasesIT {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Currency USD = Currency.getInstance("USD");
  private static final CounterpartyId HKD_BORROWER = new CounterpartyId("CP-001");
  private static final CounterpartyId USD_BORROWER = new CounterpartyId("CP-002");
  private static final Isin HK_EQUITY = new Isin("HK0000000001");
  private static final Isin US_EQUITY = new Isin("US0000000001");

  /** A Tuesday; T+2 is Thursday 3 September, no Hong Kong holiday in between. */
  private static final LocalDate TRADE_DATE = LocalDate.of(2026, 9, 1);

  private ConfigurableApplicationContext context;
  private OpenPosition open;
  private ReturnPosition returns;
  private ListPositions list;
  private JdbcClient jdbc;

  @BeforeEach
  void start() {
    context = startApplication();
    open = context.getBean(OpenPosition.class);
    returns = context.getBean(ReturnPosition.class);
    list = context.getBean(ListPositions.class);
    jdbc = context.getBean(JdbcClient.class);
    // Reference data has no use case of its own yet; it is inserted as it would be loaded.
    jdbc.sql(
            "INSERT INTO counterparty VALUES"
                + " ('CP-001', 'Synthetic Borrower One', 1000000.00, 'HKD'),"
                + " ('CP-002', 'Synthetic Borrower Two', 1000000.00, 'USD')")
        .update();
    jdbc.sql(
            "INSERT INTO security VALUES"
                + " ('HK0000000001', 'Synthetic HK Equity', 'HKD', 41.26, 'HKD'),"
                + " ('US0000000001', 'Synthetic US Equity', 'USD', 10.00, 'USD')")
        .update();
  }

  @AfterEach
  void stop() {
    context.close();
  }

  @Test
  void anOpenedPositionIsListedWithItsBorrowTrade() {
    LoanPositionId id = open.open(openHkd("K-1", 1_000, "50000.00"));

    PositionPage page = list.page(0, 10);

    assertThat(page.totalRows()).isEqualTo(1);
    PositionRow row = page.rows().getFirst();
    assertThat(row.id()).isEqualTo(id);
    assertThat(row.status()).isEqualTo(LoanStatus.OPEN);
    assertThat(row.outstandingQuantity()).isEqualTo(1_000);
    assertThat(row.collateral()).isEqualTo(Money.of("50000.00", HKD));
    assertThat(
            jdbc.sql(
                    "SELECT direction || ' ' || quantity || ' ' || unit_price_amount || ' '"
                        + " || settlement_date FROM loan_trade")
                .query(String.class)
                .single())
        .isEqualTo("BORROW 1000 41.260000 2026-09-03");
  }

  @Test
  void anOpenBeyondTheCreditLimitLeavesNothingBehind() {
    open.open(openHkd("K-1", 1_000, "600000.00"));

    assertThatExceptionOfType(InsufficientCreditException.class)
        .isThrownBy(() -> open.open(openHkd("K-2", 1_000, "400000.01")));

    assertThat(count("loan_position")).isEqualTo(1);
    assertThat(count("loan_trade")).isEqualTo(1);
  }

  @Test
  void theSameOpenSubmittedTwiceOpensOnePosition() {
    open.open(openHkd("K-1", 1_000, "50000.00"));

    assertThatExceptionOfType(DuplicateRequestException.class)
        .isThrownBy(() -> open.open(openHkd("K-1", 1_000, "50000.00")))
        .withMessageContaining("K-1");

    assertThat(count("loan_position")).isEqualTo(1);
    assertThat(count("loan_trade")).isEqualTo(1);
  }

  /** Review focus 4: a key identifies a submission, whichever direction it was first used for. */
  @Test
  void aReturnReusingAnOpensKeyIsADuplicate() {
    LoanPositionId id = open.open(openHkd("K-1", 1_000, "50000.00"));

    assertThatExceptionOfType(DuplicateRequestException.class)
        .isThrownBy(() -> returns.apply(new ReturnCommand(key("K-1"), id, 100, TRADE_DATE)));

    assertThat(list.page(0, 10).rows().getFirst().outstandingQuantity()).isEqualTo(1_000);
    assertThat(count("loan_trade")).isEqualTo(1);
  }

  @Test
  void anOpenReusingAReturnsKeyIsADuplicate() {
    LoanPositionId id = open.open(openHkd("K-1", 1_000, "50000.00"));
    returns.apply(new ReturnCommand(key("K-2"), id, 100, TRADE_DATE));

    assertThatExceptionOfType(DuplicateRequestException.class)
        .isThrownBy(() -> open.open(openHkd("K-2", 1_000, "50000.00")));

    assertThat(count("loan_position")).isEqualTo(1);
    assertThat(count("loan_trade")).isEqualTo(2);
  }

  /** Review focus 5: exposure is not summed across currencies, it is refused. */
  @Test
  void exposureAcrossCurrenciesIsRefusedNotSummed() {
    open.open(
        new OpenPositionCommand(
            key("K-1"),
            USD_BORROWER,
            US_EQUITY,
            1_000,
            Money.of("10000.00", USD),
            new FlatRate(Rate.of("0.0365")),
            TRADE_DATE));

    assertThatExceptionOfType(CurrencyMismatchException.class)
        .isThrownBy(
            () ->
                open.open(
                    new OpenPositionCommand(
                        key("K-2"),
                        USD_BORROWER,
                        HK_EQUITY,
                        1_000,
                        Money.of("10000.00", HKD),
                        new FlatRate(Rate.of("0.0365")),
                        TRADE_DATE)));

    assertThat(count("loan_position")).isEqualTo(1);
  }

  @Test
  void aPartialAndThenAFullReturnClosesThePosition() {
    LoanPositionId id = open.open(openHkd("K-1", 1_000, "50000.00"));

    returns.apply(new ReturnCommand(key("K-2"), id, 400, TRADE_DATE));
    returns.apply(new ReturnCommand(key("K-3"), id, 600, TRADE_DATE));

    PositionRow row = list.page(0, 10).rows().getFirst();
    assertThat(row.status()).isEqualTo(LoanStatus.CLOSED);
    assertThat(row.outstandingQuantity()).isZero();
    assertThat(count("loan_trade")).isEqualTo(3);
  }

  @Test
  void aReturnAgainstAPositionThatDoesNotExistNamesIt() {
    assertThatExceptionOfType(UnknownReferenceException.class)
        .isThrownBy(
            () ->
                returns.apply(
                    new ReturnCommand(key("K-1"), new LoanPositionId("LP-NONE"), 1, TRADE_DATE)))
        .withMessageContaining("LP-NONE");
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  private static OpenPositionCommand openHkd(String key, long quantity, String collateral) {
    return new OpenPositionCommand(
        key(key),
        HKD_BORROWER,
        HK_EQUITY,
        quantity,
        Money.of(collateral, HKD),
        new FlatRate(Rate.of("0.0365")),
        TRADE_DATE);
  }

  private static IdempotencyKey key(String value) {
    return new IdempotencyKey(value);
  }

  /**
   * The real application on a fresh database. The datasource goes in as command-line arguments, the
   * highest-precedence source, so no profile's YAML can override it.
   */
  static ConfigurableApplicationContext startApplication(String... extraArgs) {
    List<String> args = new ArrayList<>();
    for (String property : SharedPostgres.freshDatabase()) {
      args.add("--" + property);
    }
    args.addAll(Arrays.asList(extraArgs));
    return new SpringApplicationBuilder(SlLedgerApplication.class)
        .web(WebApplicationType.NONE)
        .bannerMode(Banner.Mode.OFF)
        .run(args.toArray(String[]::new));
  }
}

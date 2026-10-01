package com.jerome.slledger.bootstrap.web;

import com.jerome.slledger.bootstrap.position.LedgerApp;
import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The running application, an HTTP client bound to it, and reference data whose ISINs carry valid
 * check digits: the web layer verifies them, unlike {@code domain} and stage 6's fixtures.
 */
public final class WebLedger implements AutoCloseable {

  /** Check digits computed by the ISIN algorithm; {@code IsinValidatorTest} pins them. */
  public static final String HK_EQUITY = "HK0000000007";

  public static final String HK_EQUITY_2 = "HK0000000015";
  public static final String US_EQUITY = "US0000000010";

  public final ConfigurableApplicationContext app;
  public final RestTestClient http;
  public final JdbcClient jdbc;

  private WebLedger(ConfigurableApplicationContext app) {
    this.app = app;
    this.http = LedgerApp.http(app);
    this.jdbc = app.getBean(JdbcClient.class);
  }

  public static WebLedger start(String... args) {
    return start(List.of(), args);
  }

  public static WebLedger start(List<Class<?>> extraSources, String... args) {
    WebLedger ledger = new WebLedger(LedgerApp.startWeb(extraSources, args));
    ledger
        .jdbc
        .sql(
            "INSERT INTO counterparty VALUES"
                + " ('CP-001', 'Synthetic Borrower One', 1000000.00, 'HKD'),"
                + " ('CP-002', 'Synthetic Borrower Two', 1000000.00, 'USD')")
        .update();
    ledger
        .jdbc
        .sql(
            "INSERT INTO security VALUES"
                + " (?, 'Synthetic HK Equity', 'HKD', 41.26, 'HKD'),"
                + " (?, 'Synthetic HK Equity Two', 'HKD', 7.50, 'HKD'),"
                + " (?, 'Synthetic US Equity', 'USD', 10.00, 'USD')")
        .params(HK_EQUITY, HK_EQUITY_2, US_EQUITY)
        .update();
    return ledger;
  }

  public long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  /** An open request body; {@code tradeDate} 2026-09-01 is a Tuesday, T+2 is 3 September. */
  public static String openBody(String isin, long quantity, String amount, String currency) {
    return """
        {"counterpartyId":"CP-001","isin":"%s","quantity":%d,
         "collateral":{"amount":"%s","currency":"%s"},
         "rule":{"type":"FLAT","rate":"0.0365"},"tradeDate":"2026-09-01"}
        """
        .formatted(isin, quantity, amount, currency);
  }

  public static String returnBody(long quantity) {
    return "{\"quantity\":%d,\"tradeDate\":\"2026-09-01\"}".formatted(quantity);
  }

  @Override
  public void close() {
    app.close();
  }
}

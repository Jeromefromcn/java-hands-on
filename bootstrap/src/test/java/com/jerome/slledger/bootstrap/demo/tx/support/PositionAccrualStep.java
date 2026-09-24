package com.jerome.slledger.bootstrap.demo.tx.support;

import com.jerome.slledger.domain.accrual.AccrualEntry;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One position's accrual body: read the quantity, let {@code domain} compute the entry, insert it.
 * No transaction of its own; it runs in whatever boundary the caller draws around it.
 */
public class PositionAccrualStep {

  /** Synthetic fixed price per unit: a demo needs a value, not a market-data feed. */
  private static final BigDecimal PRICE = new BigDecimal("10.00");

  private static final Currency HKD = Currency.getInstance("HKD");

  private final JdbcClient jdbc;
  private final AccrualEntryRows entries;

  public PositionAccrualStep(JdbcClient jdbc, AccrualEntryRows entries) {
    this.jdbc = jdbc;
    this.entries = entries;
  }

  public void accrue(String id, LocalDate on) {
    long quantity =
        jdbc.sql("SELECT outstanding_quantity FROM loan_position WHERE id = ?")
            .param(id)
            .query(Long.class)
            .single();
    Money outstandingValue = Money.of(PRICE.multiply(BigDecimal.valueOf(quantity)), HKD);
    entries.insert(
        AccrualEntry.accrue(
            new LoanPositionId(id), outstandingValue, new FlatRate(Rate.of("0.0525")), on));
  }
}

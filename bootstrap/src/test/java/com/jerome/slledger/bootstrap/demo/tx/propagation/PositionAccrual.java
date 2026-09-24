package com.jerome.slledger.bootstrap.demo.tx.propagation;

import com.jerome.slledger.bootstrap.demo.tx.support.AccrualEntryRows;
import com.jerome.slledger.domain.accrual.AccrualEntry;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One position's accrual, in its own transaction, independent of the batch around it. */
public class PositionAccrual {

  /** Synthetic fixed price per unit: a demo needs a value, not a market-data feed. */
  private static final BigDecimal PRICE = new BigDecimal("10.00");

  private static final Currency HKD = Currency.getInstance("HKD");

  private final JdbcClient jdbc;
  private final AccrualEntryRows entries;
  private final DataSource dataSource;
  private volatile int activeConnectionsInside;

  public PositionAccrual(JdbcClient jdbc, AccrualEntryRows entries, DataSource dataSource) {
    this.jdbc = jdbc;
    this.entries = entries;
    this.dataSource = dataSource;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void accrue(String id, LocalDate on) {
    long quantity =
        jdbc.sql("SELECT outstanding_quantity FROM loan_position WHERE id = ?")
            .param(id)
            .query(Long.class)
            .single();
    activeConnectionsInside =
        ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections();

    Money outstandingValue = Money.of(PRICE.multiply(BigDecimal.valueOf(quantity)), HKD);
    entries.insert(
        AccrualEntry.accrue(
            new LoanPositionId(id), outstandingValue, new FlatRate(Rate.of("0.0525")), on));
  }

  public int activeConnectionsInside() {
    return activeConnectionsInside;
  }
}

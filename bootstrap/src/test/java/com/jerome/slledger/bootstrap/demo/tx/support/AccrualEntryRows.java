package com.jerome.slledger.bootstrap.demo.tx.support;

import com.jerome.slledger.domain.accrual.AccrualEntry;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes and reads {@code accrual_entry}: the first place a {@link Money} crosses into a column.
 * Amount and currency always travel together — neither is written or read without the other.
 */
public class AccrualEntryRows {

  private final JdbcClient jdbc;

  public AccrualEntryRows(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(AccrualEntry entry) {
    jdbc.sql(
            "INSERT INTO accrual_entry(position_id, business_date, amount, currency)"
                + " VALUES (?, ?, ?, ?)")
        .params(
            entry.position().value(),
            entry.accruedOn(),
            entry.fee().amount(),
            entry.fee().currency().getCurrencyCode())
        .update();
  }

  public Optional<AccrualEntry> find(LoanPositionId id, LocalDate on) {
    return jdbc.sql(
            "SELECT amount, currency FROM accrual_entry"
                + " WHERE position_id = ? AND business_date = ?")
        .params(id.value(), on)
        .query(
            (rs, rowNum) ->
                new AccrualEntry(
                    id,
                    on,
                    // char(3) is blank-padded by definition; trim before the ISO lookup.
                    Money.of(
                        rs.getBigDecimal("amount"),
                        Currency.getInstance(rs.getString("currency").trim()))))
        .optional();
  }

  /** The column value exactly as the driver returns it, before {@link Money} normalises it. */
  public BigDecimal rawAmount(LoanPositionId id, LocalDate on) {
    return jdbc.sql("SELECT amount FROM accrual_entry WHERE position_id = ? AND business_date = ?")
        .params(id.value(), on)
        .query(BigDecimal.class)
        .single();
  }
}

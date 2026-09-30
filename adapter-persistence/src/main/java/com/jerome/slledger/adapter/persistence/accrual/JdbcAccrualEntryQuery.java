package com.jerome.slledger.adapter.persistence.accrual;

import com.jerome.slledger.adapter.persistence.support.MoneyColumns;
import com.jerome.slledger.application.port.AccrualEntryQuery;
import com.jerome.slledger.domain.accrual.AccrualEntry;
import com.jerome.slledger.domain.loan.LoanPositionId;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * A position's entries over a date range. The primary key {@code (position_id, business_date)}
 * serves it: the position is fixed, so the range is on the key's second column.
 */
@Repository
public class JdbcAccrualEntryQuery implements AccrualEntryQuery {

  static final String SQL =
      "SELECT position_id, business_date, fee_amount, fee_currency FROM accrual_entry"
          + " WHERE position_id = ? AND business_date BETWEEN ? AND ? ORDER BY business_date";

  private final JdbcClient jdbc;

  JdbcAccrualEntryQuery(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<AccrualEntry> of(LoanPositionId position, LocalDate from, LocalDate to) {
    return jdbc.sql(SQL)
        .params(position.value(), from, to)
        .query(
            (rs, n) ->
                new AccrualEntry(
                    new LoanPositionId(rs.getString("position_id")),
                    rs.getObject("business_date", LocalDate.class),
                    MoneyColumns.read(
                        rs.getBigDecimal("fee_amount"), rs.getString("fee_currency"))))
        .list();
  }
}

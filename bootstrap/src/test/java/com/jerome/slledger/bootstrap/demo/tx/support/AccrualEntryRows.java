package com.jerome.slledger.bootstrap.demo.tx.support;

import com.jerome.slledger.domain.accrual.AccrualEntry;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
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
            "SELECT position_id, business_date, amount, currency FROM accrual_entry"
                + " WHERE position_id = ? AND business_date = ?")
        .params(id.value(), on)
        // Every field from its column, not from the arguments: otherwise record equality in the
        // round-trip test would never check that position and date survived.
        .query(
            (rs, rowNum) ->
                new AccrualEntry(
                    new LoanPositionId(rs.getString("position_id")),
                    rs.getObject("business_date", LocalDate.class),
                    readFee(rs)))
        .optional();
  }

  /**
   * Rebuilds the fee without rounding it. {@code Money.of} re-scales to the currency's minor units
   * (and refuses currencies that have none); the column carries 6 decimal places whatever the
   * currency. If re-scaling changed the value, the row was written with more precision than its
   * currency allows, and it is refused here rather than rounded a second time, outside {@code
   * domain}.
   */
  private static Money readFee(ResultSet rs) throws SQLException {
    BigDecimal stored = rs.getBigDecimal("amount");
    // char(3) is blank-padded by definition; trim before the ISO lookup.
    Money fee = Money.of(stored, Currency.getInstance(rs.getString("currency").trim()));
    if (fee.amount().compareTo(stored) != 0) {
      throw new ArithmeticException(
          "accrual_entry amount " + stored.toPlainString() + " does not fit " + fee.currency());
    }
    return fee;
  }

  /** The column value exactly as the driver returns it, before {@link Money} normalises it. */
  public BigDecimal rawAmount(LoanPositionId id, LocalDate on) {
    return jdbc.sql("SELECT amount FROM accrual_entry WHERE position_id = ? AND business_date = ?")
        .params(id.value(), on)
        .query(BigDecimal.class)
        .single();
  }
}

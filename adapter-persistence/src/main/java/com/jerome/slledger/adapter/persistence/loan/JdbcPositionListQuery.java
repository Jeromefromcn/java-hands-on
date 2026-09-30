package com.jerome.slledger.adapter.persistence.loan;

import com.jerome.slledger.adapter.persistence.support.MoneyColumns;
import com.jerome.slledger.application.port.PositionListQuery;
import com.jerome.slledger.application.port.PositionPage;
import com.jerome.slledger.application.port.PositionRow;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.loan.LoanStatus;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The listing as hand-written SQL: no persistence context at all, so nothing is managed, cached or
 * flushed, and the SQL that runs is the SQL written here. Money still goes through {@link
 * MoneyColumns}. Selected with {@code ledger.persistence.list-query=jdbc}.
 */
@Repository
@ConditionalOnProperty(name = "ledger.persistence.list-query", havingValue = "jdbc")
public class JdbcPositionListQuery implements PositionListQuery {

  static final String COLUMNS =
      "id, counterparty_id, isin, status, original_quantity, outstanding_quantity,"
          + " collateral_amount, collateral_currency";

  static final String PAGE_SQL =
      "SELECT " + COLUMNS + " FROM loan_position ORDER BY id LIMIT ? OFFSET ?";

  static final String COUNT_SQL = "SELECT count(*) FROM loan_position";

  private final JdbcClient jdbc;

  JdbcPositionListQuery(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public PositionPage page(int page, int size) {
    List<PositionRow> rows =
        jdbc.sql(PAGE_SQL).params(size, (long) page * size).query((rs, n) -> row(rs)).list();
    long total = jdbc.sql(COUNT_SQL).query(Long.class).single();
    return new PositionPage(rows, page, size, total);
  }

  /** One row of {@link #COLUMNS}, as the listing, the detail and the keyset slice all read it. */
  static PositionRow row(ResultSet rs) throws SQLException {
    return new PositionRow(
        new LoanPositionId(rs.getString("id")),
        new CounterpartyId(rs.getString("counterparty_id")),
        new Isin(rs.getString("isin").trim()),
        LoanStatus.valueOf(rs.getString("status")),
        rs.getLong("original_quantity"),
        rs.getLong("outstanding_quantity"),
        MoneyColumns.read(
            rs.getBigDecimal("collateral_amount"), rs.getString("collateral_currency")));
  }
}

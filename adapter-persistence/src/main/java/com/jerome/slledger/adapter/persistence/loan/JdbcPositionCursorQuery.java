package com.jerome.slledger.adapter.persistence.loan;

import com.jerome.slledger.application.port.PositionCursorQuery;
import com.jerome.slledger.application.port.PositionRow;
import com.jerome.slledger.application.port.PositionSlice;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Keyset paging on the primary key: {@code WHERE id > ? ORDER BY id LIMIT ?}. The index seeks to
 * the cursor instead of reading and discarding every row before it, which is what {@code OFFSET}
 * does. One extra row is read to know whether a next slice exists.
 */
@Repository
public class JdbcPositionCursorQuery implements PositionCursorQuery {

  static final String FIRST_SQL =
      "SELECT " + JdbcPositionListQuery.COLUMNS + " FROM loan_position ORDER BY id LIMIT ?";

  static final String AFTER_SQL =
      "SELECT "
          + JdbcPositionListQuery.COLUMNS
          + " FROM loan_position WHERE id > ? ORDER BY id LIMIT ?";

  static final String FIRST_BY_COUNTERPARTY_SQL =
      "SELECT "
          + JdbcPositionListQuery.COLUMNS
          + " FROM loan_position WHERE counterparty_id = ? ORDER BY id LIMIT ?";

  static final String AFTER_BY_COUNTERPARTY_SQL =
      "SELECT "
          + JdbcPositionListQuery.COLUMNS
          + " FROM loan_position WHERE counterparty_id = ? AND id > ? ORDER BY id LIMIT ?";

  private final JdbcClient jdbc;

  JdbcPositionCursorQuery(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public PositionSlice after(
      Optional<CounterpartyId> counterparty, Optional<LoanPositionId> after, int size) {
    JdbcClient.StatementSpec statement;
    if (counterparty.isPresent()) {
      String owner = counterparty.get().value();
      statement =
          after
              .map(id -> jdbc.sql(AFTER_BY_COUNTERPARTY_SQL).params(owner, id.value(), size + 1))
              .orElseGet(() -> jdbc.sql(FIRST_BY_COUNTERPARTY_SQL).params(owner, size + 1));
    } else {
      statement =
          after
              .map(id -> jdbc.sql(AFTER_SQL).params(id.value(), size + 1))
              .orElseGet(() -> jdbc.sql(FIRST_SQL).params(size + 1));
    }
    List<PositionRow> rows = statement.query((rs, n) -> JdbcPositionListQuery.row(rs)).list();
    if (rows.size() <= size) {
      return new PositionSlice(rows, Optional.empty());
    }
    List<PositionRow> slice = rows.subList(0, size);
    return new PositionSlice(slice, Optional.of(slice.getLast().id()));
  }
}

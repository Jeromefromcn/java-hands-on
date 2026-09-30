package com.jerome.slledger.adapter.persistence.loan;

import com.jerome.slledger.application.port.PositionCursorQuery;
import com.jerome.slledger.application.port.PositionRow;
import com.jerome.slledger.application.port.PositionSlice;
import com.jerome.slledger.domain.loan.LoanPositionId;
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

  private final JdbcClient jdbc;

  JdbcPositionCursorQuery(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public PositionSlice after(Optional<LoanPositionId> after, int size) {
    List<PositionRow> rows =
        after
            .map(id -> jdbc.sql(AFTER_SQL).params(id.value(), size + 1))
            .orElseGet(() -> jdbc.sql(FIRST_SQL).params(size + 1))
            .query((rs, n) -> JdbcPositionListQuery.row(rs))
            .list();
    if (rows.size() <= size) {
      return new PositionSlice(rows, Optional.empty());
    }
    List<PositionRow> slice = rows.subList(0, size);
    return new PositionSlice(slice, Optional.of(slice.getLast().id()));
  }
}

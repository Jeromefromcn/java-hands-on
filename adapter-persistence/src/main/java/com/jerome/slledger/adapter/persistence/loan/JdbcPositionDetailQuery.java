package com.jerome.slledger.adapter.persistence.loan;

import com.jerome.slledger.application.port.PositionDetailQuery;
import com.jerome.slledger.application.port.PositionRow;
import com.jerome.slledger.domain.loan.LoanPositionId;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * One listing row by id, through {@code JdbcClient}: a projection with no entity to manage, the
 * case stage 6's decision record gives for bypassing the ORM.
 */
@Repository
public class JdbcPositionDetailQuery implements PositionDetailQuery {

  static final String SQL =
      "SELECT " + JdbcPositionListQuery.COLUMNS + " FROM loan_position WHERE id = ?";

  private final JdbcClient jdbc;

  JdbcPositionDetailQuery(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<PositionRow> find(LoanPositionId id) {
    return jdbc.sql(SQL)
        .param(id.value())
        .query((rs, n) -> JdbcPositionListQuery.row(rs))
        .optional();
  }
}

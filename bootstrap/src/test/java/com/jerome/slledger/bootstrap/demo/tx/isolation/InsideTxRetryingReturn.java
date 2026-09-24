package com.jerome.slledger.bootstrap.demo.tx.isolation;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** The same retry loop as {@link RetryingReturn}, but inside one REPEATABLE READ transaction. */
public class InsideTxRetryingReturn {

  private static final int ATTEMPTS = 3;

  private final JdbcClient jdbc;

  public InsideTxRetryingReturn(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public void returnQuantity(String id, long quantity, CyclicBarrier firstAttemptBarrier) {
    List<String> attempts = new ArrayList<>();
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      try {
        if (tryOnce(id, quantity, attempt == 0 ? firstAttemptBarrier : null)) {
          return;
        }
        attempts.add("0 rows");
      } catch (DataAccessException failed) {
        attempts.add(sqlState(failed));
      }
    }
    throw new ConcurrentUpdateException(id, attempts);
  }

  private boolean tryOnce(String id, long quantity, CyclicBarrier afterRead) {
    record Read(long outstanding, long version) {}
    Read read =
        jdbc.sql("SELECT outstanding_quantity, version FROM loan_position WHERE id = ?")
            .param(id)
            .query((rs, rowNum) -> new Read(rs.getLong(1), rs.getLong(2)))
            .single();
    AfterRead.await(afterRead);
    return jdbc.sql(
                "UPDATE loan_position SET outstanding_quantity = ?, version = version + 1"
                    + " WHERE id = ? AND version = ?")
            .params(read.outstanding() - quantity, id, read.version())
            .update()
        == 1;
  }

  private static String sqlState(DataAccessException failed) {
    Throwable cause = failed;
    while (cause != null && !(cause instanceof SQLException)) {
      cause = cause.getCause();
    }
    return cause == null ? "no SQLException" : ((SQLException) cause).getSQLState();
  }
}

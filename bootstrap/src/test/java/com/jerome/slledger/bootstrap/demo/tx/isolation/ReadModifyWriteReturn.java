package com.jerome.slledger.bootstrap.demo.tx.isolation;

import java.util.concurrent.CyclicBarrier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Read the balance, compute in Java, write the result back: the shape that loses updates. The two
 * implementations differ only in the isolation level on their {@code @Transactional}.
 */
public interface ReadModifyWriteReturn {

  void returnQuantity(String id, long quantity, CyclicBarrier afterRead);

  static void readModifyWrite(JdbcClient jdbc, String id, long quantity, CyclicBarrier afterRead) {
    long read =
        jdbc.sql("SELECT outstanding_quantity FROM loan_position WHERE id = ?")
            .param(id)
            .query(Long.class)
            .single();
    AfterRead.await(afterRead);
    jdbc.sql("UPDATE loan_position SET outstanding_quantity = ? WHERE id = ?")
        .params(read - quantity, id)
        .update();
  }
}

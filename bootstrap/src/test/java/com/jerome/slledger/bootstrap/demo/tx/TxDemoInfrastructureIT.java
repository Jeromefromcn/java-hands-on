package com.jerome.slledger.bootstrap.demo.tx;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.demo.tx.support.TxDemo;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemoRows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The plumbing every stage-5 demo stands on: one shared Postgres, a fresh schema per context. */
class TxDemoInfrastructureIT {

  @Test
  @DisplayName("a demo context reaches the shared Postgres and can write and read a position")
  void contextReachesPostgres() {
    try (var context = TxDemo.start()) {
      TxDemoRows rows = context.getBean(TxDemoRows.class);

      rows.insertPosition("P1", 100);

      assertThat(rows.outstanding("P1")).isEqualTo(100);
    }
  }

  @Test
  @DisplayName("every new context starts from an empty schema, whatever an earlier one wrote")
  void secondContextStartsEmpty() {
    try (var first = TxDemo.start()) {
      first.getBean(TxDemoRows.class).insertPosition("P1", 100);
    }

    try (var second = TxDemo.start()) {
      Long rows =
          second
              .getBean(JdbcClient.class)
              .sql("SELECT count(*) FROM loan_position")
              .query(Long.class)
              .single();

      assertThat(rows).isZero();
    }
  }
}

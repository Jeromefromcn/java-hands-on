package com.jerome.slledger.adapter.persistence.accrual;

import com.jerome.slledger.application.accrual.SkippedItems;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSkippedItems implements SkippedItems {

  private final JdbcClient jdbc;

  JdbcSkippedItems(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void record(String job, String scope, String itemKey, String phase, String reason) {
    jdbc.sql(
            "INSERT INTO batch_skip (job, scope, item_key, phase, reason) VALUES (?, ?, ?, ?, ?)"
                + " ON CONFLICT (job, scope, item_key)"
                + " DO UPDATE SET phase = EXCLUDED.phase, reason = EXCLUDED.reason,"
                + " recorded_at = now()")
        .params(job, scope, itemKey, phase, reason)
        .update();
  }
}

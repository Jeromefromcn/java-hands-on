package com.jerome.slledger.adapter.persistence.messaging;

import com.jerome.slledger.application.messaging.ConsumedMessages;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The guard is one statement: {@code INSERT ... ON CONFLICT DO NOTHING}, and the count of rows it
 * inserted is the answer. Two consumers racing on one id are serialised by the primary key: the
 * second waits for the first to commit or roll back, then either inserts (the first rolled back) or
 * finds the row (it committed).
 */
@Repository
public class JdbcConsumedMessages implements ConsumedMessages {

  private final JdbcClient jdbc;

  JdbcConsumedMessages(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean firstTime(String consumer, UUID messageId, Instant at) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "A consumed-message row must be written in the transaction of the effect it guards");
    }
    return jdbc.sql(
                "INSERT INTO consumed_message (consumer, message_id, consumed_at)"
                    + " VALUES (?, ?, ?) ON CONFLICT (consumer, message_id) DO NOTHING")
            .params(consumer, messageId, Timestamp.from(at))
            .update()
        == 1;
  }

  @Override
  public int purgeBefore(Instant cutoff) {
    return jdbc.sql("DELETE FROM consumed_message WHERE consumed_at < ?")
        .param(Timestamp.from(cutoff))
        .update();
  }

  @Override
  public long count() {
    return jdbc.sql("SELECT count(*) FROM consumed_message").query(Long.class).single();
  }
}

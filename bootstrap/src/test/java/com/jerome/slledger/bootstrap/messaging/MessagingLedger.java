package com.jerome.slledger.bootstrap.messaging;

import com.jerome.slledger.adapter.messaging.testing.SharedKafka;
import com.jerome.slledger.application.messaging.Topics;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The real application with messaging on, against a real Postgres and a real Kafka, the relay's
 * scheduler off (a test runs the relay by hand, so it decides when a pass happens). Reference data
 * is loaded; the standard topics exist with one partition, so order across keys is send order.
 */
final class MessagingLedger implements AutoCloseable {

  final ConfigurableApplicationContext app;
  final JdbcClient jdbc;
  final TransactionTemplate transaction;

  private MessagingLedger(ConfigurableApplicationContext app) {
    this.app = app;
    this.jdbc = app.getBean(JdbcClient.class);
    this.transaction = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
  }

  static MessagingLedger start(String... extraArgs) {
    SharedKafka.start();
    SharedKafka.ensureTopic(Topics.PRICE_UPDATES, 1);
    SharedKafka.ensureTopic(Topics.MARGIN_CALLS, 1);
    SharedKafka.ensureTopic(Topics.POSITION_EVENTS, 1);
    List<String> args = new ArrayList<>();
    args.add("--ledger.messaging.enabled=true");
    args.add("--ledger.messaging.bootstrap-servers=" + SharedKafka.bootstrapServers());
    args.add("--ledger.messaging.relay-enabled=false");
    args.addAll(Arrays.asList(extraArgs));
    MessagingLedger ledger =
        new MessagingLedger(LedgerApp.start(List.of(), args.toArray(String[]::new)));
    LedgerApp.referenceData(ledger.jdbc);
    return ledger;
  }

  long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  /** Empties every table a test writes to, keeping reference data. */
  void reset() {
    jdbc.sql("DELETE FROM outbox_event").update();
    jdbc.sql("DELETE FROM accrual_entry").update();
    jdbc.sql("DELETE FROM loan_trade").update();
    jdbc.sql("DELETE FROM loan_position").update();
  }

  @Override
  public void close() {
    app.close();
  }
}

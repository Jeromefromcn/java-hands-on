package com.jerome.slledger.adapter.persistence.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.persistence.testing.LedgerDb;
import com.jerome.slledger.application.messaging.ConsumedMessages;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Timeout(60)
class JdbcConsumedMessagesIT {

  private static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");

  private static ConfigurableApplicationContext context;

  private ConsumedMessages consumed;
  private TransactionTemplate transaction;

  @BeforeAll
  static void start() {
    context = LedgerDb.start();
  }

  @AfterAll
  static void stop() {
    context.close();
  }

  @BeforeEach
  void setUp() {
    consumed = context.getBean(ConsumedMessages.class);
    transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    context.getBean(JdbcClient.class).sql("DELETE FROM consumed_message").update();
  }

  private boolean firstTime(String consumer, UUID id, Instant at) {
    return Boolean.TRUE.equals(transaction.execute(status -> consumed.firstTime(consumer, id, at)));
  }

  @Test
  @DisplayName("the first time is true, the second is false, and another consumer has its own")
  void firstTimeOnlyOnce() {
    UUID id = UUID.randomUUID();

    assertThat(firstTime("a", id, T0)).isTrue();
    assertThat(firstTime("a", id, T0.plusSeconds(1))).isFalse();
    assertThat(firstTime("b", id, T0)).isTrue();
    assertThat(consumed.count()).isEqualTo(2);
  }

  @Test
  @DisplayName("a guard row rolled back with its transaction is not remembered")
  void rolledBackGuardIsForgotten() {
    UUID id = UUID.randomUUID();
    transaction.executeWithoutResult(
        status -> {
          assertThat(consumed.firstTime("a", id, T0)).isTrue();
          status.setRollbackOnly();
        });

    assertThat(consumed.count()).isZero();
    assertThat(firstTime("a", id, T0)).isTrue();
  }

  @Test
  @DisplayName("a guard row cannot be written outside a transaction")
  void needsATransaction() {
    assertThatThrownBy(() -> consumed.firstTime("a", UUID.randomUUID(), T0))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  @DisplayName("purge forgets ids older than the cutoff and keeps the rest")
  void purge() {
    UUID old = UUID.randomUUID();
    UUID recent = UUID.randomUUID();
    firstTime("a", old, T0);
    firstTime("a", recent, T0.plusSeconds(3600));

    int purged = consumed.purgeBefore(T0.plusSeconds(1800));

    assertThat(purged).isEqualTo(1);
    assertThat(firstTime("a", old, T0.plusSeconds(3601))).isTrue();
    assertThat(firstTime("a", recent, T0.plusSeconds(3601))).isFalse();
  }
}

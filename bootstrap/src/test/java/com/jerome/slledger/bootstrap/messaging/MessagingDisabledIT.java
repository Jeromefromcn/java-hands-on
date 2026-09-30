package com.jerome.slledger.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.application.messaging.Outbox;
import com.jerome.slledger.application.messaging.OutboxRelay;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;

/** With messaging off, nothing of it exists: no bean, no thread of its own, no connection. */
@Timeout(120)
class MessagingDisabledIT {

  @Test
  @DisplayName(
      "with ledger.messaging.enabled unset there is no Kafka bean, no relay and no scheduler thread")
  void nothingExists() {
    try (ConfigurableApplicationContext app = LedgerApp.start()) {
      assertThat(app.getBeansOfType(KafkaTemplate.class)).isEmpty();
      assertThat(app.getBeansOfType(MessageListenerContainer.class)).isEmpty();
      assertThat(app.getBeansOfType(OutboxRelay.class)).isEmpty();
      assertThat(app.getBeansOfType(Outbox.class)).isEmpty();
      Set<String> threads =
          Thread.getAllStackTraces().keySet().stream()
              .map(Thread::getName)
              .collect(Collectors.toSet());
      assertThat(threads).doesNotContain("sl-messaging-scheduler");
    }
  }
}

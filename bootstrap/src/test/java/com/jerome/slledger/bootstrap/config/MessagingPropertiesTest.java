package com.jerome.slledger.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class MessagingPropertiesTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(MessagingProperties.class)
  static class Binding {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Binding.class);

  @Test
  @DisplayName("the defaults bind, off, with a dedup TTL a day longer than the topic retention")
  void defaults() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          MessagingProperties properties = context.getBean(MessagingProperties.class);
          assertThat(properties.enabled()).isFalse();
          assertThat(properties.topicRetention()).isEqualTo(Duration.ofDays(7));
          assertThat(properties.dedupTtl()).isEqualTo(Duration.ofDays(8));
        });
  }

  @Test
  @DisplayName("a dedup TTL equal to or shorter than the retention refuses to start")
  void ttlMustOutliveRetention() {
    runner
        .withPropertyValues("ledger.messaging.topic-retention=7d", "ledger.messaging.dedup-ttl=7d")
        .run(context -> assertThat(context).hasFailed());
    runner
        .withPropertyValues("ledger.messaging.topic-retention=7d", "ledger.messaging.dedup-ttl=1h")
        .run(context -> assertThat(context).hasFailed());
    runner
        .withPropertyValues("ledger.messaging.topic-retention=1d", "ledger.messaging.dedup-ttl=25h")
        .run(context -> assertThat(context).hasNotFailed());
  }
}

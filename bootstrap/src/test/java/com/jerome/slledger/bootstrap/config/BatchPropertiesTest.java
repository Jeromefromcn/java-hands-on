package com.jerome.slledger.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.application.accrual.PartitionScheme;
import java.time.Duration;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class BatchPropertiesTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({
    BatchProperties.class,
    RetentionProperties.class,
    ReconciliationProperties.class
  })
  static class Binding {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Binding.class);

  @Test
  @DisplayName("the defaults bind: batch off, timers off, a chunk of 200, the cutover at 23:30")
  void defaults() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          BatchProperties batch = context.getBean(BatchProperties.class);
          assertThat(batch.enabled()).isFalse();
          assertThat(batch.schedulersEnabled()).isFalse();
          assertThat(batch.chunkSize()).isEqualTo(200);
          assertThat(batch.partitionScheme()).isEqualTo(PartitionScheme.NONE);
          assertThat(batch.accrualTime()).isEqualTo(LocalTime.of(23, 30));
          assertThat(batch.lockTtl()).isEqualTo(Duration.ofMinutes(10));
          assertThat(context.getBean(RetentionProperties.class).tombstones())
              .isEqualTo(Duration.ofDays(30));
          assertThat(context.getBean(ReconciliationProperties.class).retryDelays())
              .containsExactly(Duration.ofHours(4), Duration.ofHours(24));
        });
  }

  @Test
  @DisplayName("a chunk size of zero refuses to start")
  void chunkSizeMustBePositive() {
    runner
        .withPropertyValues("ledger.batch.chunk-size=0")
        .run(context -> assertThat(context).hasFailed());
  }
}

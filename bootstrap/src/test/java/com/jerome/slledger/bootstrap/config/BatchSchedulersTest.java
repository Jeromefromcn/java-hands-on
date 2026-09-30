package com.jerome.slledger.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BatchSchedulersTest {

  // 2026-09-30 15:00 UTC is 23:00 in Hong Kong.
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-30T15:00:00Z"), ZoneOffset.UTC);

  @Test
  @DisplayName("a time of day later today is waited for today, in Hong Kong time")
  void laterToday() {
    assertThat(BatchSchedulers.untilNext(CLOCK, LocalTime.of(23, 30)))
        .isEqualTo(Duration.ofMinutes(30));
  }

  @Test
  @DisplayName("a time of day already past is waited for tomorrow")
  void alreadyPast() {
    assertThat(BatchSchedulers.untilNext(CLOCK, LocalTime.of(3, 0))).isEqualTo(Duration.ofHours(4));
  }

  @Test
  @DisplayName("exactly now means tomorrow, not an immediate second run")
  void exactlyNow() {
    assertThat(BatchSchedulers.untilNext(CLOCK, LocalTime.of(23, 0)))
        .isEqualTo(Duration.ofHours(24));
  }
}

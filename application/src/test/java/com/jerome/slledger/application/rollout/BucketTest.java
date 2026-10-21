package com.jerome.slledger.application.rollout;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.domain.party.CounterpartyId;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BucketTest {

  private static final String FLAG = "accrual.day-count-by-market";

  @Test
  void aCounterpartyIsAlwaysInTheSameBucket() {
    CounterpartyId id = new CounterpartyId("CP-001");
    assertThat(Bucket.of(FLAG, id)).isEqualTo(Bucket.of(FLAG, new CounterpartyId("CP-001")));
  }

  @Test
  void raisingThePercentageOnlyAddsCounterparties() {
    for (int i = 0; i < 1_000; i++) {
      CounterpartyId id = new CounterpartyId("CP-%05d".formatted(i));
      for (int p = 0; p < 100; p++) {
        if (Bucket.selected(FLAG, id, p)) {
          assertThat(Bucket.selected(FLAG, id, p + 1)).isTrue();
        }
      }
    }
  }

  @Test
  void fivePercentOfManyIsAboutFivePercent() {
    // 10,000 sequential ids, the shape real ids have. A binomial 5% of 10,000 has a standard
    // deviation of about 22; 4.3% to 5.7% is three of them either side.
    long selected =
        IntStream.range(0, 10_000)
            .filter(i -> Bucket.selected(FLAG, new CounterpartyId("CP-%05d".formatted(i)), 5))
            .count();
    assertThat(selected).isBetween(430L, 570L);
  }

  @Test
  void anotherFlagSelectsOtherCounterparties() {
    long both =
        IntStream.range(0, 10_000)
            .mapToObj(i -> new CounterpartyId("CP-%05d".formatted(i)))
            .filter(id -> Bucket.selected(FLAG, id, 5) && Bucket.selected("other.flag", id, 5))
            .count();
    // Independent 5% samples overlap in about 0.25% of ids (25 of 10,000), not in 5% (500).
    assertThat(both).isLessThan(100);
  }
}

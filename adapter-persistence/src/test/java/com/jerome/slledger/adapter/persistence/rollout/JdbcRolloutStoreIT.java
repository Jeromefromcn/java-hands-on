package com.jerome.slledger.adapter.persistence.rollout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.persistence.testing.LedgerDb;
import com.jerome.slledger.application.rollout.Rollout;
import com.jerome.slledger.application.rollout.RolloutChange;
import com.jerome.slledger.application.rollout.RolloutStore;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The rollout tables against a real Postgres: the flag V19 creates, a change from proposed to
 * applied or superseded, the version that makes {@link RolloutStore#move} a compare-and-set, and
 * the constraint that keeps a maker from being the checker even for a writer that bypasses the
 * application.
 */
class JdbcRolloutStoreIT {

  private static final String FLAG = "accrual.day-count-by-market";
  private static final Instant T0 = Instant.parse("2026-10-21T01:00:00Z");

  private static ConfigurableApplicationContext context;

  private RolloutStore store;
  private JdbcClient jdbc;

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
    store = context.getBean(RolloutStore.class);
    jdbc = context.getBean(JdbcClient.class);
    jdbc.sql("DELETE FROM feature_rollout_change").update();
    jdbc.sql("UPDATE feature_rollout SET percent = 0, version = 0").update();
  }

  @Test
  void theMigrationCreatesTheFlagAtZero() {
    assertThat(store.find(FLAG)).get().extracting(Rollout::percent).isEqualTo(0);
    assertThat(store.find("no.such.flag")).isEmpty();
  }

  @Test
  void aProposedChangeIsAppliedByItsChecker() {
    RolloutChange proposed = store.propose(FLAG, 0, 5, "ops-a", T0);

    assertThat(proposed.status()).isEqualTo(RolloutChange.Status.PROPOSED);
    assertThat(proposed.decidedBy()).isEmpty();
    assertThat(store.move(store.find(FLAG).orElseThrow(), 5, T0.plusSeconds(60))).isTrue();
    store.applied(proposed.id(), "ops-b", T0.plusSeconds(60));

    RolloutChange applied = store.change(FLAG, proposed.id()).orElseThrow();
    assertThat(applied.status()).isEqualTo(RolloutChange.Status.APPLIED);
    assertThat(applied.decidedBy()).contains("ops-b");
    assertThat(applied.decidedAt()).contains(T0.plusSeconds(60));
    Rollout now = store.find(FLAG).orElseThrow();
    assertThat(now.percent()).isEqualTo(5);
    assertThat(now.effectiveSince()).isEqualTo(T0.plusSeconds(60));
    assertThat(store.change("other.flag", proposed.id())).isEmpty();
  }

  @Test
  void aMoveFromAStaleReadChangesNothing() {
    Rollout read = store.find(FLAG).orElseThrow();
    assertThat(store.move(read, 5, T0)).isTrue();

    assertThat(store.move(read, 50, T0)).as("second move from the same read").isFalse();
    assertThat(store.find(FLAG).orElseThrow().percent()).isEqualTo(5);
  }

  @Test
  void movingSupersedesWhatIsStillProposedAndListsNewestFirst() {
    RolloutChange first = store.propose(FLAG, 0, 5, "ops-a", T0);
    RolloutChange second = store.propose(FLAG, 0, 3, "ops-c", T0.plusSeconds(1));

    store.supersedeOpen(FLAG, T0.plusSeconds(2));

    assertThat(store.changes(FLAG, 10))
        .extracting(RolloutChange::id, RolloutChange::status)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(second.id(), RolloutChange.Status.SUPERSEDED),
            org.assertj.core.groups.Tuple.tuple(first.id(), RolloutChange.Status.SUPERSEDED));
    assertThat(store.changes(FLAG, 1)).hasSize(1);
  }

  @Test
  void theTableRefusesAMakerAsChecker() {
    RolloutChange proposed = store.propose(FLAG, 0, 5, "ops-a", T0);

    assertThatThrownBy(() -> store.applied(proposed.id(), "ops-a", T0))
        .hasMessageContaining("feature_rollout_change_maker_is_not_checker");
  }

  @Test
  void aPercentageOutsideZeroToOneHundredIsRefused() {
    Rollout read = store.find(FLAG).orElseThrow();
    assertThatThrownBy(() -> store.move(read, 101, T0)).hasMessageContaining("percent");
  }
}

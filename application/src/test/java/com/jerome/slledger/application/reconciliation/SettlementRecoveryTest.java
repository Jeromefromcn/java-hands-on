package com.jerome.slledger.application.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.application.reconciliation.SettlementAttempt.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementRecoveryTest {

  private static final Instant T0 = Instant.parse("2026-09-30T02:00:00Z");

  /** A clock a test moves. */
  private static final class MovingClock extends Clock {

    private Instant now = T0;

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /** The breaks table, in memory. */
  private static final class Table implements SettlementBreaks {

    static final class Row {
      String position;
      String status = "RETRY_SETTLEMENT";
      int attempts;
      Instant next;
      String failure = "";
      Instant resolvedAt;
    }

    final Map<String, Row> rows = new HashMap<>();

    Table with(String position) {
      Row row = new Row();
      row.position = position;
      row.next = T0;
      rows.put(position, row);
      return this;
    }

    @Override
    public Optional<Due> claimNextDue(Instant now) {
      return rows.values().stream()
          .filter(r -> r.status.equals("RETRY_SETTLEMENT") && !r.next.isAfter(now))
          .min(java.util.Comparator.comparing((Row r) -> r.next).thenComparing(r -> r.position))
          .map(r -> new Due("S1", r.position, r.attempts));
    }

    @Override
    public void resolve(String snapshot, String positionId, int attempts, Instant at) {
      Row row = rows.get(positionId);
      row.status = "RESOLVED";
      row.attempts = attempts;
      row.resolvedAt = at;
      row.next = null;
    }

    @Override
    public void scheduleRetry(
        String snapshot,
        String positionId,
        int attempts,
        Instant nextAttemptAt,
        String lastFailure) {
      Row row = rows.get(positionId);
      row.attempts = attempts;
      row.next = nextAttemptAt;
      row.failure = lastFailure;
    }

    @Override
    public void escalate(String snapshot, String positionId, int attempts, String lastFailure) {
      Row row = rows.get(positionId);
      row.status = "ESCALATED";
      row.attempts = attempts;
      row.next = null;
      row.failure = lastFailure;
    }
  }

  /** Answers each position from a script, in order; an empty script answers "no route". */
  private static final class ScriptedGateway implements SettlementGateway {

    final Map<String, Deque<Object>> script = new HashMap<>();
    final List<String> asked = new ArrayList<>();

    ScriptedGateway answers(String position, Object... answers) {
      script.put(position, new ArrayDeque<>(List.of(answers)));
      return this;
    }

    @Override
    public Result resubmit(String positionId) {
      asked.add(positionId);
      Object next = script.getOrDefault(positionId, new ArrayDeque<>()).poll();
      if (next instanceof RuntimeException e) {
        throw e;
      }
      return next instanceof Result result ? result : new Rejected("no route");
    }
  }

  private final MovingClock clock = new MovingClock();
  private final Table table = new Table();
  private final ScriptedGateway gateway = new ScriptedGateway();

  private SettlementRecovery ladder(int maxAttempts, Duration... delays) {
    return new SettlementRecovery(
        new SettlementAttempt(table, gateway, clock, maxAttempts, List.of(delays)));
  }

  @Test
  @DisplayName(
      "the first attempt is due at once, a failure schedules the next after the first delay")
  void firstAttemptAtOnce() {
    table.with("P-1");
    SettlementRecovery ladder = ladder(3, Duration.ofHours(4), Duration.ofHours(24));

    List<SettlementAttempt.Result> results = ladder.run();

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
    assertThat(results.getFirst().attempt()).isEqualTo(1);
    assertThat(table.rows.get("P-1").next).isEqualTo(T0.plus(Duration.ofHours(4)));
    assertThat(table.rows.get("P-1").failure).isEqualTo("no route");
  }

  @Test
  @DisplayName("nothing is attempted before its time")
  void notDueYet() {
    table.with("P-1");
    SettlementRecovery ladder = ladder(3, Duration.ofHours(4));
    ladder.run();
    clock.advance(Duration.ofHours(3));

    assertThat(ladder.run()).isEmpty();
    assertThat(gateway.asked).containsExactly("P-1");
  }

  @Test
  @DisplayName(
      "a settlement that succeeds on the second attempt resolves the break and stops the ladder")
  void succeedsOnTheSecondAttempt() {
    table.with("P-1");
    gateway.answers(
        "P-1", new SettlementGateway.Rejected("custodian busy"), new SettlementGateway.Settled());
    SettlementRecovery ladder = ladder(3, Duration.ofHours(4), Duration.ofHours(24));
    ladder.run();
    clock.advance(Duration.ofHours(4));

    List<SettlementAttempt.Result> results = ladder.run();

    assertThat(results)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.outcome()).isEqualTo(Outcome.RESOLVED);
              assertThat(r.attempt()).isEqualTo(2);
            });
    assertThat(table.rows.get("P-1").status).isEqualTo("RESOLVED");
    assertThat(table.rows.get("P-1").resolvedAt).isEqualTo(T0.plus(Duration.ofHours(4)));
    clock.advance(Duration.ofDays(10));
    assertThat(ladder.run()).isEmpty();
    assertThat(gateway.asked).hasSize(2);
  }

  @Test
  @DisplayName("the delays are spaced as configured, and the last failure escalates")
  void spacingAndEscalation() {
    table.with("P-1");
    SettlementRecovery ladder = ladder(3, Duration.ofHours(4), Duration.ofHours(24));

    ladder.run(); // attempt 1 at T0
    assertThat(table.rows.get("P-1").next).isEqualTo(T0.plus(Duration.ofHours(4)));
    clock.advance(Duration.ofHours(4));
    ladder.run(); // attempt 2 at T0+4h
    assertThat(table.rows.get("P-1").next).isEqualTo(T0.plus(Duration.ofHours(28)));
    clock.advance(Duration.ofHours(24));
    List<SettlementAttempt.Result> last = ladder.run(); // attempt 3 at T0+28h

    assertThat(last.getFirst().outcome()).isEqualTo(Outcome.ESCALATED);
    assertThat(table.rows.get("P-1").status).isEqualTo("ESCALATED");
    assertThat(table.rows.get("P-1").attempts).isEqualTo(3);
    assertThat(table.rows.get("P-1").next).isNull();
  }

  @Test
  @DisplayName("an escalated break is never attempted again")
  void escalatedIsNotRetried() {
    table.with("P-1");
    SettlementRecovery ladder = ladder(1, Duration.ofHours(4));

    ladder.run();
    clock.advance(Duration.ofDays(30));

    assertThat(ladder.run()).isEmpty();
    assertThat(gateway.asked).containsExactly("P-1");
    assertThat(table.rows.get("P-1").status).isEqualTo("ESCALATED");
  }

  @Test
  @DisplayName("a transport failure counts as a failed attempt and is kept on the break")
  void exceptionIsAFailedAttempt() {
    table.with("P-1");
    gateway.answers("P-1", new IllegalStateException("connection reset"));
    SettlementRecovery ladder = ladder(2, Duration.ofHours(4));

    List<SettlementAttempt.Result> results = ladder.run();

    assertThat(results.getFirst().outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
    assertThat(table.rows.get("P-1").failure)
        .contains("IllegalStateException")
        .contains("connection reset");
  }

  @Test
  @DisplayName("breaks due together are attempted oldest first, each once per run")
  void severalBreaks() {
    table.with("P-2").with("P-1");
    SettlementRecovery ladder = ladder(3, Duration.ofHours(4));

    List<SettlementAttempt.Result> results = ladder.run();

    assertThat(results)
        .extracting(SettlementAttempt.Result::positionId)
        .containsExactly("P-1", "P-2");
  }
}

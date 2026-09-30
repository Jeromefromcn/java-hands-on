package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.application.reconciliation.SettlementGateway;
import com.jerome.slledger.application.reconciliation.SettlementRecovery;
import com.jerome.slledger.batch.reconciliation.RunReconciliation;
import com.jerome.slledger.batch.reconciliation.RunReconciliation.Outcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The settlement ladder against the table, driven by the reconciliation job and then by the
 * scheduler's call. Ten positions; eight are at the custodian. P000009 is not, and settles on the
 * second attempt. P000010 is not, and never does.
 */
@Timeout(300)
class SettlementRecoveryIT {

  private static final LocalDate DAY = LocalDate.of(2026, 9, 30);
  private static final String SNAPSHOT = "CUSTODIAN-20260930";

  private static BatchLedger ledger;

  @TempDir Path directory;

  @BeforeAll
  static void start() {
    ledger = BatchLedger.start(List.of(LadderSupport.class), "--ledger.batch.chunk-size=5");
  }

  @AfterAll
  static void stop() {
    ledger.close();
  }

  private String table() {
    StringBuilder text =
        new StringBuilder("position | disposition | attempts | next_attempt_at | detail\n");
    ledger
        .jdbc
        .sql(
            "SELECT position_id, disposition, attempts, next_attempt_at, detail FROM recon_break"
                + " WHERE snapshot_id = ? ORDER BY position_id")
        .param(SNAPSHOT)
        .query(
            (rs, n) -> {
              text.append(rs.getString(1))
                  .append(" | ")
                  .append(rs.getString(2))
                  .append(" | ")
                  .append(rs.getInt(3))
                  .append(" | ")
                  .append(rs.getString(4))
                  .append(" | ")
                  .append(rs.getString(5))
                  .append('\n');
              return null;
            })
        .list();
    return text.toString();
  }

  @Test
  @DisplayName(
      "an unsettled position is resubmitted on the ladder's schedule, settles, or is escalated and left")
  void ladderOnTheTable() throws Exception {
    ledger.resetLedger();
    ledger.resetBatchMetadata();
    ledger.jdbc.sql("DELETE FROM recon_break").update();
    ledger.jdbc.sql("DELETE FROM custodian_holding").update();
    ledger.seed(10);
    LadderSupport.MovingClock clock = ledger.bean(LadderSupport.MovingClock.class);
    LadderSupport.ScriptedGateway gateway = ledger.bean(LadderSupport.ScriptedGateway.class);
    gateway.answers(
        "P000009",
        new SettlementGateway.Rejected("custodian busy"),
        new SettlementGateway.Settled());
    SettlementRecovery ladder = ledger.bean(SettlementRecovery.class);

    List<String> lines = new ArrayList<>();
    lines.add("position_id,quantity,market_value,currency");
    for (int i = 1; i <= 8; i++) {
      lines.add("P%06d,1000,41260.00,HKD".formatted(i));
    }
    Path file = directory.resolve("snapshot.csv");
    Files.write(file, lines);

    StringBuilder evidence =
        new StringBuilder(
            """
            Stage 11, section 12: the settlement ladder. Ten positions settled on 2026-09-01; the
            custodian's snapshot has eight. P000009 and P000010 are ledger-only and past the window.
            Three attempts, waits of 4 h after the first failure and 24 h after the second. The
            clock is a test clock moved by hand; the gateway is scripted: P000009 is rejected once
            and then settles, P000010 is always rejected. Real Postgres.

            """);

    // 02:00 on the 30th: the reconciliation records both breaks and makes the first attempt.
    Outcome first = ledger.bean(RunReconciliation.class).run(DAY, SNAPSHOT, file);
    assertThat(first.status()).isEqualTo(RunReconciliation.Status.COMPLETED);
    assertThat(gateway.asked).containsExactlyInAnyOrder("P000009", "P000010");
    evidence.append("-- T+0h: reconciliation job (steps: load, compare, settlementRecovery)\n");
    evidence.append(ledger.stepTable(first.execution().getId())).append(table()).append('\n');
    assertThat(
            ledger.stepContextLong(
                first.execution().getId(), "settlementRecovery", "ladder.attempted"))
        .isEqualTo(2L);

    // One hour later nothing is due.
    clock.advance(Duration.ofHours(1));
    assertThat(ladder.run()).isEmpty();
    evidence.append("-- T+1h: ladder.run(): nothing is due\n\n");

    // Four hours after the first attempt: the second.
    clock.advance(Duration.ofHours(3));
    assertThat(ladder.run()).hasSize(2);
    assertThat(dispositionOf("P000009")).isEqualTo("RESOLVED");
    assertThat(dispositionOf("P000010")).isEqualTo("RETRY_SETTLEMENT");
    evidence.append("-- T+4h: ladder.run(): attempt 2 for both\n").append(table()).append('\n');

    // 24 hours after that: the third, and the last.
    clock.advance(Duration.ofHours(24));
    assertThat(ladder.run()).hasSize(1);
    assertThat(dispositionOf("P000010")).isEqualTo("ESCALATED");
    assertThat(attemptsOf("P000010")).isEqualTo(3);
    evidence
        .append("-- T+28h: ladder.run(): attempt 3 for P000010, which escalates\n")
        .append(table())
        .append('\n');

    // An escalated break is left alone, however long it waits.
    clock.advance(Duration.ofDays(30));
    assertThat(ladder.run()).isEmpty();
    assertThat(gateway.asked).hasSize(5); // 2 + 2 + 1
    assertThat(
            ledger
                .jdbc
                .sql(
                    "SELECT count(*) FROM recon_break WHERE disposition IN ('MANUAL', 'ESCALATED')"
                        + " AND resolved_at IS NULL")
                .query(Long.class)
                .single())
        .isEqualTo(1L);
    evidence.append(
        "-- T+30d: ladder.run(): nothing; the escalated break stays in the queue (gateway asked %d times in all)%n"
            .formatted(gateway.asked.size()));
    Evidence.write("11-settlement-ladder.txt", evidence.toString());
  }

  private String dispositionOf(String position) {
    return ledger
        .jdbc
        .sql("SELECT disposition FROM recon_break WHERE snapshot_id = ? AND position_id = ?")
        .params(SNAPSHOT, position)
        .query(String.class)
        .single();
  }

  private int attemptsOf(String position) {
    return ledger
        .jdbc
        .sql("SELECT attempts FROM recon_break WHERE snapshot_id = ? AND position_id = ?")
        .params(SNAPSHOT, position)
        .query(Integer.class)
        .single();
  }
}

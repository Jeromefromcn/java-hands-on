package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.batch.reconciliation.RunReconciliation;
import com.jerome.slledger.batch.reconciliation.RunReconciliation.Outcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A custodian's snapshot with one difference of each class planted, and lines that must not get in.
 * The ledger has 21 positions of 1,000 shares of a 41.26 HKD security, so each is worth 41,260.00.
 * All settled on 2026-09-01 except P000019, which settled on 2026-09-29.
 */
@Timeout(300)
class ReconciliationJobIT {

  private static final LocalDate DAY = LocalDate.of(2026, 9, 30);
  private static final String SNAPSHOT = "CUSTODIAN-20260930";
  private static final StringBuilder EVIDENCE = new StringBuilder();

  private static BatchLedger ledger;
  private static RunReconciliation reconciliation;

  @TempDir Path directory;

  @BeforeAll
  static void start() {
    ledger = BatchLedger.start("--ledger.batch.chunk-size=5");
    reconciliation = ledger.bean(RunReconciliation.class);
  }

  @AfterAll
  static void stop() {
    Evidence.write("11-reconciliation.txt", EVIDENCE.toString());
    ledger.close();
  }

  @BeforeEach
  void fresh() {
    ledger.resetLedger();
    ledger.resetBatchMetadata();
    ledger.jdbc.sql("DELETE FROM recon_break").update();
    ledger.jdbc.sql("DELETE FROM custodian_holding").update();
    ledger.seed(21);
    ledger
        .jdbc
        .sql(
            "UPDATE loan_trade SET settlement_date = DATE '2026-09-29', trade_date = DATE"
                + " '2026-09-27' WHERE position_id = 'P000019'")
        .update();
  }

  private Path snapshot() throws Exception {
    List<String> lines = new ArrayList<>();
    lines.add("position_id,quantity,market_value,currency");
    for (int i = 1; i <= 12; i++) {
      lines.add("P%06d,1000,41260.00,HKD".formatted(i));
    }
    lines.add("P000013,1000,41260.00,"); // no currency: refused
    lines.add("P000014,1000,41260.00,HKD");
    lines.add("P000015,900,37134.00,HKD"); // QUANTITY_MISMATCH
    lines.add("P000016,1000,41260.50,HKD"); // VALUE_MISMATCH inside the tolerance
    lines.add("P000017,1000,41000.00,HKD"); // VALUE_MISMATCH beyond it
    // P000018 is absent and settled long ago: OURS_ONLY, past the window
    // P000019 is absent and settled the day before yesterday: OURS_ONLY, inside the window
    lines.add("P000020,abc,41260.00,HKD"); // refused: quantity
    lines.add("P000021,1000,41260.005,HKD"); // refused: over-precise for HKD
    lines.add("X000001,500,100.00,HKD"); // THEIRS_ONLY
    lines.add("Q000001,1000,10.00,ABC"); // refused: unknown currency, position unknown
    lines.add(",1,1.00,HKD"); // refused: no position id
    Path file = directory.resolve("snapshot.csv");
    Files.write(file, lines);
    return file;
  }

  private List<String> breaks() {
    return ledger
        .jdbc
        .sql(
            "SELECT position_id || ' ' || kind || ' ' || disposition FROM recon_break"
                + " WHERE snapshot_id = ? ORDER BY position_id")
        .param(SNAPSHOT)
        .query(String.class)
        .list();
  }

  @Test
  @DisplayName(
      "one planted break of each class is classified as specified; malformed lines are refused")
  void plantedBreaksAreClassified() throws Exception {
    Outcome outcome = reconciliation.run(DAY, SNAPSHOT, snapshot());

    assertThat(outcome.status()).isEqualTo(RunReconciliation.Status.COMPLETED);
    assertThat(breaks())
        .containsExactly(
            "P000015 QUANTITY_MISMATCH MANUAL",
            "P000016 VALUE_MISMATCH AUTO_ACCEPTED",
            "P000017 VALUE_MISMATCH MANUAL",
            "P000018 OURS_ONLY RETRY_SETTLEMENT",
            "P000019 OURS_ONLY PENDING_SETTLEMENT",
            "X000001 THEIRS_ONLY MANUAL");
    // 17 valid lines loaded: 12 + P14..P17 + X000001. The five refused ones are not in the table.
    assertThat(ledger.count("custodian_holding")).isEqualTo(17);
    assertThat(
            ledger
                .jdbc
                .sql(
                    "SELECT item_key FROM batch_skip WHERE job = 'reconciliation'"
                        + " AND scope = ? ORDER BY item_key")
                .param(SNAPSHOT)
                .query(String.class)
                .list())
        .containsExactly("P000013", "P000020", "P000021", "Q000001", "line:23");
    // A position whose line was refused is not reported as missing from the custodian.
    assertThat(breaks())
        .noneMatch(
            b -> b.startsWith("P000013") || b.startsWith("P000020") || b.startsWith("P000021"));
    String details =
        ledger
            .jdbc
            .sql(
                "SELECT position_id || ' | ' || kind || ' | ' || disposition || ' | ' || detail"
                    + " FROM recon_break WHERE snapshot_id = ? ORDER BY position_id")
            .param(SNAPSHOT)
            .query(String.class)
            .list()
            .stream()
            .reduce("", (a, b) -> a + b + "\n");
    String skips =
        ledger
            .jdbc
            .sql(
                "SELECT item_key || ' | ' || reason FROM batch_skip WHERE job = 'reconciliation'"
                    + " AND scope = ? ORDER BY item_key")
            .param(SNAPSHOT)
            .query(String.class)
            .list()
            .stream()
            .reduce("", (a, b) -> a + b + "\n");
    EVIDENCE.append(
        """
        Stage 11, section 11: reconciliation of the ledger (21 positions of 1,000 shares at 41.26
        HKD, so 41,260.00 each) against a custodian snapshot for 2026-09-30, with one break of each
        class planted and five lines that must not get in. Chunk size 5. Real Postgres.

        -- steps
        %s
        -- recon_break (position | kind | disposition | detail)
        %s
        -- lines refused and recorded in batch_skip (item | reason)
        %s
        custodian_holding rows loaded: %d of 22 lines (5 refused)
        """
            .formatted(
                ledger.stepTable(outcome.execution().getId()),
                details,
                skips,
                ledger.count("custodian_holding")));
  }

  @Test
  @DisplayName("running the same snapshot again in a second job instance adds no break")
  void aRerunAddsNoBreak() throws Exception {
    Path file = snapshot();
    reconciliation.run(DAY, SNAPSHOT, file);
    long before = ledger.count("recon_break");

    Outcome again = reconciliation.run(DAY, SNAPSHOT, file);
    Outcome rerun = reconciliation.run(DAY, SNAPSHOT, file, OptionalLong.of(1));

    assertThat(again.status()).isEqualTo(RunReconciliation.Status.ALREADY_DONE);
    assertThat(rerun.status()).isEqualTo(RunReconciliation.Status.COMPLETED);
    assertThat(before).isEqualTo(6);
    assertThat(ledger.count("recon_break")).isEqualTo(6);
    assertThat(
            ledger.stepContextLong(
                rerun.execution().getId(), "compare", "reconciliation.breaksRecorded"))
        .isZero();
  }

  @Test
  @DisplayName("a second snapshot is compared on its own: its breaks are its own rows")
  void snapshotsAreIndependent() throws Exception {
    Path file = snapshot();
    reconciliation.run(DAY, SNAPSHOT, file);

    reconciliation.run(DAY.plusDays(2), "CUSTODIAN-20261002", file);

    assertThat(ledger.count("recon_break")).isEqualTo(12);
    assertThat(
            ledger
                .jdbc
                .sql(
                    "SELECT disposition FROM recon_break WHERE snapshot_id = ? AND position_id = 'P000019'")
                .param("CUSTODIAN-20261002")
                .query(String.class)
                .single())
        .isEqualTo("RETRY_SETTLEMENT"); // settled 09-29 plus a window of two days: past it on 10-02
  }
}

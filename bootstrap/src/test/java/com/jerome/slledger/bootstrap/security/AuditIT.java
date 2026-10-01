package com.jerome.slledger.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.application.audit.Audit;
import com.jerome.slledger.application.audit.AuditEvent;
import com.jerome.slledger.application.audit.AuditStore;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import com.jerome.slledger.bootstrap.web.WebLedger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The audit trail through the real flows. A booked line commits and rolls back with the trade; a
 * refused attempt and a denial are written in transactions of their own and survive.
 */
class AuditIT {

  private static WebLedger ledger;

  /** Test-only: an audit line written the wrong way, in its own transaction. */
  public static class IndependentBookedLine {
    private final AuditStore store;

    IndependentBookedLine(AuditStore store) {
      this.store = store;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(String positionId) {
      store.append(
          new AuditEvent(
              Instant.now(),
              "probe",
              AuditEvent.Action.OPEN,
              AuditEvent.Subject.POSITION,
              positionId,
              AuditEvent.Result.BOOKED,
              ""));
    }
  }

  @BeforeAll
  static void start() {
    ledger = WebLedger.start(List.of(IndependentBookedLine.class));
  }

  @AfterAll
  static void stop() {
    Evidence.write("13-audit.txt", evidence());
    ledger.close();
  }

  private static List<String> lines() {
    return ledger
        .jdbc
        .sql(
            "SELECT actor || ' | ' || action || ' | ' || subject || ' | ' || subject_id || ' | '"
                + " || result || ' | ' || reason FROM audit_event ORDER BY id")
        .query(String.class)
        .list();
  }

  private static String evidence() {
    return "Stage 13 · section 6 — the audit table after the flows of AuditIT\n"
        + "actor | action | subject | subject_id | result | reason\n\n"
        + String.join("\n", lines())
        + "\n";
  }

  private static String post(String token, String path, String key, String body) {
    var result =
        LedgerApp.http(ledger.app, token)
            .post()
            .uri(path)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange()
            .returnResult(String.class);
    return result.getStatus().value() + " " + result.getResponseBody();
  }

  private static String positionIdIn(String response) {
    Matcher m = Pattern.compile("\"positionId\":\"([^\"]+)\"").matcher(response);
    assertThat(m.find()).as(response).isTrue();
    return m.group(1);
  }

  @Test
  @DisplayName(
      "a committed open and a committed return each leave one BOOKED line naming the token")
  void bookedLines() {
    String trader = TestTokens.builder().subject("trader-77").roles("ledger-trader").build();
    String opened =
        post(
            trader,
            "/loans",
            "k-" + UUID.randomUUID(),
            WebLedger.openBody(WebLedger.HK_EQUITY, 100, "1000.00", "HKD"));
    assertThat(opened).startsWith("201");
    String id = positionIdIn(opened);
    String returned =
        post(
            trader,
            "/loans/" + id + "/returns",
            "k-" + UUID.randomUUID(),
            WebLedger.returnBody(10));
    assertThat(returned).startsWith("201");

    assertThat(
            ledger
                .jdbc
                .sql(
                    "SELECT action || ' ' || result || ' ' || actor FROM audit_event WHERE subject_id = ? ORDER BY id")
                .param(id)
                .query(String.class)
                .list())
        .containsExactly("OPEN BOOKED trader-77", "RETURN BOOKED trader-77");
  }

  @Test
  @DisplayName("an open refused for credit rolls back with no BOOKED line and leaves one REJECTED")
  void refusedOpen() {
    String key = "k-" + UUID.randomUUID();
    long before = ledger.count("audit_event");

    String response =
        post(
            TestTokens.trader(),
            "/loans",
            key,
            WebLedger.openBody(WebLedger.HK_EQUITY, 100, "5000000.00", "HKD"));

    assertThat(response).startsWith("422");
    assertThat(ledger.count("audit_event")).isEqualTo(before + 1);
    assertThat(
            ledger
                .jdbc
                .sql(
                    "SELECT result || ' ' || subject || ' ' || reason FROM audit_event WHERE subject_id = ?")
                .param(key)
                .query(String.class)
                .single())
        .isEqualTo("REJECTED REQUEST InsufficientCreditException");
    assertThat(
            ledger
                .jdbc
                .sql("SELECT count(*) FROM audit_event WHERE result = 'BOOKED' AND subject_id = ?")
                .param(key)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  @DisplayName("a replay of a booked request writes no second line")
  void replayWritesNothing() {
    String key = "k-" + UUID.randomUUID();
    String body = WebLedger.openBody(WebLedger.HK_EQUITY, 100, "1000.00", "HKD");
    String first = post(TestTokens.trader(), "/loans", key, body);
    long afterFirst = ledger.count("audit_event");

    String replay = post(TestTokens.trader(), "/loans", key, body);

    assertThat(first).startsWith("201");
    assertThat(replay).startsWith("201");
    assertThat(ledger.count("audit_event")).isEqualTo(afterFirst);
  }

  @Test
  @DisplayName("a 403 leaves a DENIED line naming the caller and the endpoint")
  void deniedLine() {
    String borrower = TestTokens.counterparty("CP-001");

    String response =
        post(
            borrower,
            "/loans",
            "k-" + UUID.randomUUID(),
            WebLedger.openBody(WebLedger.HK_EQUITY, 100, "1000.00", "HKD"));

    assertThat(response).startsWith("403");
    assertThat(lines()).contains("borrower-CP-001 | ACCESS | ENDPOINT | POST /loans | DENIED | ");
  }

  @Test
  @DisplayName("the trail cannot be rewritten: UPDATE and DELETE are refused")
  void appendOnly() {
    post(
        TestTokens.trader(),
        "/loans",
        "k-" + UUID.randomUUID(),
        WebLedger.openBody(WebLedger.HK_EQUITY, 100, "1000.00", "HKD"));

    assertThatThrownBy(() -> ledger.jdbc.sql("UPDATE audit_event SET actor = 'nobody'").update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> ledger.jdbc.sql("DELETE FROM audit_event").update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  @DisplayName("no column of the trail can hold an amount: only ids, codes and a timestamp")
  void noMoney() {
    List<String> types =
        ledger
            .jdbc
            .sql(
                "SELECT DISTINCT data_type FROM information_schema.columns WHERE table_name = 'audit_event'")
            .query(String.class)
            .list();

    assertThat(types).containsExactlyInAnyOrder("bigint", "timestamp with time zone", "text");
  }

  @Test
  @DisplayName("the auditor reads the trail through the API; a trader does not")
  void auditEndpoint() {
    String opened =
        post(
            TestTokens.trader(),
            "/loans",
            "k-" + UUID.randomUUID(),
            WebLedger.openBody(WebLedger.HK_EQUITY, 100, "1000.00", "HKD"));
    String id = positionIdIn(opened);

    var read =
        LedgerApp.http(ledger.app, TestTokens.auditor())
            .get()
            .uri("/audit?subject=" + id)
            .exchange()
            .returnResult(String.class);

    assertThat(read.getStatus().value()).isEqualTo(200);
    assertThat(read.getResponseBody()).contains(id).contains("\"result\":\"BOOKED\"");
    assertThat(
            LedgerApp.http(ledger.app, TestTokens.trader())
                .get()
                .uri("/audit")
                .exchange()
                .returnResult(String.class)
                .getStatus()
                .value())
        .isEqualTo(403);
    assertThat(
            LedgerApp.http(ledger.app, TestTokens.auditor())
                .get()
                .uri("/audit?limit=201")
                .exchange()
                .returnResult(String.class)
                .getStatus()
                .value())
        .isEqualTo(400);
  }

  @Test
  @DisplayName("a booked line cannot be written outside a transaction")
  void bookedLineNeedsTheBookingsTransaction() {
    assertThatThrownBy(
            () -> ledger.app.getBean(Audit.class).booked(AuditEvent.Action.OPEN, "LP-NONE"))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  @DisplayName("reproducesAuditRowSurvivingRolledBackBooking")
  void reproducesAuditRowSurvivingRolledBackBooking() {
    // See docs/decisions/13-security.md
    // The audit line written in its own transaction, as a refusal's is. Called from a booking that
    // then rolls back, it outlives it: the trail says a position was opened that never existed.
    TransactionTemplate tx = ledger.app.getBean(TransactionTemplate.class);
    IndependentBookedLine wrong = ledger.app.getBean(IndependentBookedLine.class);

    tx.executeWithoutResult(
        status -> {
          wrong.write("LP-GHOST");
          status.setRollbackOnly();
        });

    assertThat(lines()).contains("probe | OPEN | POSITION | LP-GHOST | BOOKED | ");
    assertThat(
            ledger
                .jdbc
                .sql("SELECT count(*) FROM loan_position WHERE id = 'LP-GHOST'")
                .query(Long.class)
                .single())
        .isZero();
  }
}

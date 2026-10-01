package com.jerome.slledger.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import com.jerome.slledger.bootstrap.web.WebLedger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Hostile input through the real HTTP stack: a SQL payload in the one filter that reaches a query,
 * and a request body that carries fields the client was never meant to set.
 */
class InputAttacksIT {

  private static WebLedger ledger;
  private static final StringBuilder EVIDENCE =
      new StringBuilder("Stage 13 · section 7 — hostile input over HTTP (InputAttacksIT)\n\n");

  @BeforeAll
  static void start() {
    ledger = WebLedger.start(List.of(VulnerablePositionController.class));
    ledger
        .jdbc
        .sql(
            "INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
                + " outstanding_quantity, status, collateral_amount, collateral_currency,"
                + " rule_type, flat_rate) VALUES ('LP-A1', 'CP-001', ?, 1000, 1000, 'OPEN',"
                + " 1234.50, 'HKD', 'FLAT', 0.0365)")
        .param(WebLedger.HK_EQUITY)
        .update();
  }

  @AfterAll
  static void stop() {
    Evidence.write("13-mass-assignment.txt", EVIDENCE.toString());
    ledger.close();
  }

  private String row() {
    return ledger
        .jdbc
        .sql(
            "SELECT counterparty_id || ' | ' || status || ' | ' || outstanding_quantity"
                + " FROM loan_position WHERE id = 'LP-A1'")
        .query(String.class)
        .single();
  }

  @Test
  @DisplayName("a SQL payload in the counterparty filter is a value: 200, no rows, nothing changed")
  void sqlPayloadIsAValue() {
    for (String payload :
        new String[] {
          "x' OR '1'='1' --", "x'; UPDATE loan_position SET status = 'CLOSED'; --", "'; --"
        }) {
      var result =
          LedgerApp.http(ledger.app, TestTokens.ops())
              .get()
              .uri("/loans?counterparty={c}", payload)
              .exchange()
              .returnResult(String.class);

      assertThat(result.getStatus().value()).as(payload).isEqualTo(200);
      assertThat(result.getResponseBody()).as(payload).contains("\"totalRows\":0");
    }
    assertThat(row()).isEqualTo("CP-001 | OPEN | 1000");
  }

  @Test
  @DisplayName("reproducesMassAssignmentOfIdAndStatus")
  void reproducesMassAssignmentOfIdAndStatus() {
    // See docs/decisions/13-security.md
    String before = row();

    var result =
        LedgerApp.http(ledger.app, TestTokens.trader())
            .post()
            .uri("/vulnerable/positions")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                "{\"id\":\"LP-A1\",\"counterpartyId\":\"CP-002\",\"quantity\":1,"
                    + "\"status\":\"CLOSED\"}")
            .exchange()
            .returnResult(String.class);

    assertThat(result.getStatus().is2xxSuccessful()).isTrue();
    assertThat(before).isEqualTo("CP-001 | OPEN | 1000");
    assertThat(row()).isEqualTo("CP-002 | CLOSED | 1");
    EVIDENCE
        .append("Body bound to the entity (test-only endpoint), client-chosen id and status:\n")
        .append("  LP-A1 before: ")
        .append(before)
        .append(
            "\n  POST {\"id\":\"LP-A1\",\"counterpartyId\":\"CP-002\",\"quantity\":1,\"status\":\"CLOSED\"}\n")
        .append("  LP-A1 after:  ")
        .append(row())
        .append("\n\n");
    ledger
        .jdbc
        .sql(
            "UPDATE loan_position SET counterparty_id = 'CP-001', status = 'OPEN',"
                + " outstanding_quantity = 1000 WHERE id = 'LP-A1'")
        .update();
  }

  @Test
  @DisplayName("the real open rejects an id, a status or a version in the body, and names no class")
  void realOpenRejectsServerOwnedFields() {
    String valid = WebLedger.openBody(WebLedger.HK_EQUITY, 100, "1000.00", "HKD");
    long positions = ledger.count("loan_position");

    for (String extra :
        new String[] {
          "\"id\":\"LP-A1\"", "\"positionId\":\"LP-A1\"", "\"status\":\"CLOSED\"", "\"version\":99"
        }) {
      String body = valid.replaceFirst("\\{", "{" + extra + ",");
      var result =
          LedgerApp.http(ledger.app, TestTokens.trader())
              .post()
              .uri("/loans")
              .header("Idempotency-Key", UUID.randomUUID().toString())
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .exchange()
              .returnResult(String.class);

      assertThat(result.getStatus().value()).as(extra).isEqualTo(400);
      assertThat(result.getResponseBody())
          .as(extra)
          .contains("urn:sl-ledger:problem:malformed-body")
          .doesNotContain("OpenLoanRequest")
          .doesNotContain("com.jerome");
      EVIDENCE
          .append("Real POST /loans with ")
          .append(extra)
          .append(" -> 400 ")
          .append(result.getResponseBody())
          .append('\n');
    }
    assertThat(ledger.count("loan_position")).isEqualTo(positions);
    assertThat(row()).isEqualTo("CP-001 | OPEN | 1000");
  }

  @Test
  @DisplayName("a position id is always the server's: a clean open answers a fresh id")
  void idIsServerGenerated() {
    var result =
        LedgerApp.http(ledger.app, TestTokens.trader())
            .post()
            .uri("/loans")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .body(WebLedger.openBody(WebLedger.HK_EQUITY, 100, "1000.00", "HKD"))
            .exchange()
            .returnResult(String.class);

    assertThat(result.getStatus().value()).isEqualTo(201);
    assertThat(result.getResponseBody())
        .containsPattern(
            "\"positionId\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
  }
}

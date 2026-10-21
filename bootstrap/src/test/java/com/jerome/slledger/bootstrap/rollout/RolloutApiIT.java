package com.jerome.slledger.bootstrap.rollout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import com.jerome.slledger.bootstrap.security.TestTokens;
import com.jerome.slledger.bootstrap.web.WebLedger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The rollout's release rules over HTTP, with real tokens, and what the audit trail says about them
 * afterwards. The observation period is 2 s here (30 min by default) so that waiting it out is part
 * of the test. See docs/decisions/14-delivery.md.
 */
class RolloutApiIT {

  private static final String PATH = "/rollouts/accrual.day-count-by-market";
  private static final String OPS_A =
      TestTokens.builder().subject("ops-a").roles("ledger-ops").build();
  private static final String OPS_B =
      TestTokens.builder().subject("ops-b").roles("ledger-ops").build();
  private static final List<String> EVIDENCE = new ArrayList<>();

  private static WebLedger ledger;

  @BeforeAll
  static void start() {
    ledger = WebLedger.start("--ledger.rollout.observation=2s");
  }

  @AfterAll
  static void stop() {
    List<String> audit =
        ledger
            .jdbc
            .sql(
                "SELECT actor || ' | ' || action || ' | ' || subject_id || ' | ' || result"
                    + " || ' | ' || reason FROM audit_event WHERE action = 'ROLLOUT' ORDER BY id")
            .query(String.class)
            .list();
    Evidence.write(
        "14-rollout-api.txt",
        "Requests (who: request -> status and problem type or percent)\n"
            + String.join("\n", EVIDENCE)
            + "\n\naudit_event rows (actor | action | subject_id | result | reason)\n"
            + String.join("\n", audit)
            + "\n");
    ledger.close();
  }

  private static String call(String who, String token, String method, String path, String body) {
    var spec =
        "GET".equals(method)
            ? LedgerApp.http(ledger.app, token).get().uri(path)
            : LedgerApp.http(ledger.app, token)
                .post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body == null ? "" : body);
    var result = spec.exchange().returnResult(String.class);
    String response = result.getStatus().value() + " " + summary(result.getResponseBody());
    EVIDENCE.add(
        "%-9s %-5s %-40s -> %s"
            .formatted(
                who, method, path.replace(PATH, "…") + (body == null ? "" : " " + body), response));
    return response;
  }

  private static String summary(String body) {
    if (body == null) {
      return "";
    }
    Matcher type = Pattern.compile("\"type\":\"urn:sl-ledger:problem:([^\"]+)\"").matcher(body);
    if (type.find()) {
      return type.group(1);
    }
    Matcher id = Pattern.compile("^\\{\"id\":(\\d+)").matcher(body);
    if (id.find()) {
      return "change " + id.group(1);
    }
    Matcher percent = Pattern.compile("\"percent\":(\\d+)").matcher(body);
    return percent.find() ? "percent " + percent.group(1) : body;
  }

  private static long changeId(String response) {
    return Long.parseLong(response.replaceAll(".*change (\\d+).*", "$1"));
  }

  @Test
  void raisingNeedsTwoPeopleSmallFirstStepAndObservationLoweringNeedsOne() throws Exception {
    assertThat(call("trader", TestTokens.trader(), "GET", PATH, null)).startsWith("403");
    assertThat(call("auditor", TestTokens.auditor(), "GET", PATH, null)).startsWith("403");
    assertThat(call("ops-a", OPS_A, "GET", PATH, null)).isEqualTo("200 percent 0");
    assertThat(call("ops-a", OPS_A, "GET", "/rollouts/no.such.flag", null))
        .isEqualTo("404 not-found");

    assertThat(call("ops-a", OPS_A, "POST", PATH + "/raises", "{\"toPercent\":25}"))
        .isEqualTo("409 rollout-first-step-too-large");
    String five = call("ops-a", OPS_A, "POST", PATH + "/raises", "{\"toPercent\":5}");
    assertThat(five).startsWith("201 change");
    assertThat(call("ops-a", OPS_A, "POST", PATH + "/raises/" + changeId(five) + "/approval", null))
        .isEqualTo("409 rollout-same-person");
    assertThat(call("ops-b", OPS_B, "POST", PATH + "/raises/" + changeId(five) + "/approval", null))
        .isEqualTo("200 percent 5");

    String fifty = call("ops-a", OPS_A, "POST", PATH + "/raises", "{\"toPercent\":50}");
    assertThat(
            call("ops-b", OPS_B, "POST", PATH + "/raises/" + changeId(fifty) + "/approval", null))
        .isEqualTo("409 rollout-observation-period");
    Thread.sleep(2_100);
    assertThat(
            call("ops-b", OPS_B, "POST", PATH + "/raises/" + changeId(fifty) + "/approval", null))
        .isEqualTo("200 percent 50");

    // A raise proposed at 50%, then the kill switch: one person, effective at once, no observation.
    String stale = call("ops-a", OPS_A, "POST", PATH + "/raises", "{\"toPercent\":100}");
    assertThat(call("ops-a", OPS_A, "POST", PATH + "/lowering", "{\"toPercent\":0}"))
        .isEqualTo("200 percent 0");
    // The raise proposed before the kill is not approvable after it: approving "50 -> 100" now
    // would be a 100% first step.
    assertThat(
            call("ops-b", OPS_B, "POST", PATH + "/raises/" + changeId(stale) + "/approval", null))
        .isEqualTo("409 rollout-stale");

    List<String> results =
        ledger
            .jdbc
            .sql(
                "SELECT actor || ' ' || result || ' ' || reason FROM audit_event"
                    + " WHERE action = 'ROLLOUT' ORDER BY id")
            .query(String.class)
            .list();
    assertThat(results)
        .startsWith(
            "ops-a REJECTED FIRST_STEP_TOO_LARGE",
            "ops-a PROPOSED change %d: 0->5".formatted(changeId(five)),
            "ops-a REJECTED SAME_PERSON",
            "ops-b APPROVED change %d: 0->5".formatted(changeId(five)),
            "ops-a PROPOSED change %d: 5->50".formatted(changeId(fifty)),
            "ops-b REJECTED OBSERVATION_PERIOD",
            "ops-b APPROVED change %d: 5->50".formatted(changeId(fifty)),
            "ops-a PROPOSED change %d: 50->100".formatted(changeId(stale)),
            "ops-a LOWERED 50->0",
            "ops-b REJECTED STALE");
  }

  @Test
  void theTableRefusesAnAppliedChangeWhoseCheckerIsItsMaker() {
    long id =
        ledger
            .jdbc
            .sql(
                "INSERT INTO feature_rollout_change (flag, from_percent, to_percent, proposed_by,"
                    + " proposed_at, status) VALUES ('accrual.day-count-by-market', 0, 5, 'x',"
                    + " now(), 'PROPOSED') RETURNING id")
            .query(Long.class)
            .single();
    assertThatThrownBy(
            () ->
                ledger
                    .jdbc
                    .sql(
                        "UPDATE feature_rollout_change SET status = 'APPLIED', decided_by = 'x',"
                            + " decided_at = now() WHERE id = ?")
                    .param(id)
                    .update())
        .hasMessageContaining("feature_rollout_change_maker_is_not_checker");
  }
}

package com.jerome.slledger.bootstrap.web;

import static com.jerome.slledger.bootstrap.web.WebLedger.HK_EQUITY;
import static com.jerome.slledger.bootstrap.web.WebLedger.HK_EQUITY_2;
import static com.jerome.slledger.bootstrap.web.WebLedger.openBody;
import static com.jerome.slledger.bootstrap.web.WebLedger.returnBody;
import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import com.jayway.jsonpath.JsonPath;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * Real responses from every endpoint, errors included, validated against the OpenAPI document the
 * application serves. A contract that disagrees with the wire fails here, not in a client.
 */
class OpenApiContractIT {

  private static StubPriceServer source;
  private static WebLedger ledger;
  private static String document;
  private static OpenApiInteractionValidator validator;
  private static final List<String> CHECKED = new ArrayList<>();

  @BeforeAll
  static void start() {
    source = StubPriceServer.start();
    ledger = WebLedger.start("--ledger.marketdata.base-url=" + source.baseUrl());
    document =
        ledger
            .http
            .get()
            .uri("/v3/api-docs")
            .exchange()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    validator = OpenApiInteractionValidator.createForInlineApiSpecification(document).build();
  }

  @AfterAll
  static void stop() {
    Evidence.write(
        "07-openapi-contract.txt",
        "Stage 07 · section 16 — responses validated against /v3/api-docs (OpenApiContractIT)\n\n"
            + "Money as served:\n  "
            + JsonPath.parse(document).read("$.components.schemas.Money").toString()
            + "\n\nExchanges validated, each with no validation message:\n  "
            + String.join("\n  ", CHECKED)
            + "\n");
    ledger.close();
    source.close();
  }

  @Test
  @DisplayName("the served contract declares money amounts and rates as strings")
  void moneyIsAStringInTheContract() {
    Map<String, Object> money = JsonPath.read(document, "$.components.schemas.Money");
    Object amountType =
        JsonPath.read(document, "$.components.schemas.Money.properties.amount.type");

    assertThat(money).containsKey("required");
    assertThat(String.valueOf(amountType)).contains("string").doesNotContain("number");
    assertThat(String.valueOf(JsonPath.<Object>read(document, "$.components.schemas.Rate.type")))
        .contains("string");
    assertThat(document).doesNotContain("\"negative\"").doesNotContain("displayName");
  }

  @Test
  @DisplayName("every endpoint's real responses, success and error, match the contract")
  void responsesMatchTheContract() {
    String opened =
        check(HttpMethod.POST, "/loans", "K-1", openBody(HK_EQUITY, 1000, "50000.00", "HKD"), 201);
    String id = JsonPath.read(opened, "$.positionId");
    check(HttpMethod.POST, "/loans", "K-1", openBody(HK_EQUITY, 1000, "50000.00", "HKD"), 201);
    check(HttpMethod.POST, "/loans", "K-2", openBody(HK_EQUITY, 1000, "9000000.00", "HKD"), 422);
    check(HttpMethod.POST, "/loans", "K-3", openBody("HK0000000001", 1000, "50000.00", "HKD"), 400);
    check(HttpMethod.POST, "/loans", null, openBody(HK_EQUITY, 1000, "50000.00", "HKD"), 400);
    check(HttpMethod.POST, "/loans/" + id + "/returns", "K-4", returnBody(10), 201);
    check(HttpMethod.POST, "/loans/" + id + "/returns", "K-5", returnBody(5000), 422);
    check(HttpMethod.POST, "/loans/LP-NONE/returns", "K-6", returnBody(1), 404);

    check(HttpMethod.GET, "/loans?page=0&size=5", null, null, 200);
    check(HttpMethod.GET, "/loans?after=&size=5", null, null, 200);
    check(HttpMethod.GET, "/loans?size=500", null, null, 400);
    check(HttpMethod.GET, "/loans/" + id, null, null, 200);
    check(HttpMethod.GET, "/loans/LP-NONE", null, null, 404);
    check(
        HttpMethod.GET,
        "/loans/" + id + "/accruals?from=2026-09-01&to=2026-09-30",
        null,
        null,
        200);
    check(
        HttpMethod.GET,
        "/loans/" + id + "/accruals?from=2026-09-30&to=2026-09-01",
        null,
        null,
        400);

    source.price(HK_EQUITY, "41.30", "HKD");
    check(HttpMethod.GET, "/securities/" + HK_EQUITY + "/quote", null, null, 200);
    source.answer(HK_EQUITY_2, new StubPriceServer.Json(500, "{}"));
    check(HttpMethod.GET, "/securities/" + HK_EQUITY_2 + "/quote", null, null, 502);
    check(HttpMethod.GET, "/securities/HK0000000023/quote", null, null, 404);

    source.price(HK_EQUITY, "41.30", "HKD");
    check(
        HttpMethod.GET,
        "/quotes?isin=" + HK_EQUITY + "&isin=" + HK_EQUITY_2 + "&isin=HK0000000023",
        null,
        null,
        200);
    check(HttpMethod.GET, "/quotes", null, null, 400);
    check(HttpMethod.GET, "/quotes?isin=HK0000000001", null, null, 400);
  }

  private static String check(HttpMethod method, String uri, String key, String body, int status) {
    var spec = method == HttpMethod.GET ? ledger.http.get().uri(uri) : ledger.http.post().uri(uri);
    if (key != null) {
      spec.header("Idempotency-Key", key);
    }
    if (body != null) {
      ((org.springframework.test.web.servlet.client.RestTestClient.RequestBodySpec) spec)
          .contentType(MediaType.APPLICATION_JSON)
          .body(body);
    }
    EntityExchangeResult<String> result = spec.exchange().expectBody(String.class).returnResult();
    assertThat(result.getStatus().value()).as(method + " " + uri).isEqualTo(status);

    String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
    SimpleResponse response =
        SimpleResponse.Builder.status(result.getStatus().value())
            .withContentType(String.valueOf(result.getResponseHeaders().getContentType()))
            .withBody(result.getResponseBody())
            .build();
    ValidationReport report =
        validator.validateResponse(
            path, method == HttpMethod.GET ? Request.Method.GET : Request.Method.POST, response);
    assertThat(report.getMessages()).as(method + " " + uri + " -> " + status).isEmpty();
    if (body != null) {
      SimpleRequest.Builder request =
          method == HttpMethod.GET
              ? SimpleRequest.Builder.get(path)
              : SimpleRequest.Builder.post(path);
      request.withContentType("application/json").withBody(body);
      if (key != null) {
        request.withHeader("Idempotency-Key", key);
      }
      if (status < 300) {
        assertThat(validator.validateRequest(request.build()).getMessages())
            .as("request " + method + " " + uri)
            .isEmpty();
      }
    }
    CHECKED.add("%-5s %-60s %d".formatted(method, uri, status));
    return result.getResponseBody();
  }
}

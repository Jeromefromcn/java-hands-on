package com.jerome.slledger.bootstrap.web;

import static com.jerome.slledger.bootstrap.web.WebLedger.HK_EQUITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jerome.slledger.adapter.messaging.testing.SharedKafka;
import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.application.messaging.IntegrationEvent.PriceUpdated;
import com.jerome.slledger.application.messaging.PublishPriceUpdate;
import com.jerome.slledger.application.messaging.Topics;
import com.jerome.slledger.batch.accrual.RunAccrual;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The rule in {@code docs/logging.md}, held by a test: the flows that touch a counterparty's name
 * and money are driven end to end, with {@code com.jerome} at DEBUG, and the captured output must
 * contain none of the name or amounts that went in, nor the amounts the system derived from them.
 */
@Timeout(600)
@ExtendWith(OutputCaptureExtension.class)
class LogHygieneIT {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final String NAME = "Hygiene Test Counterparty Ltd";

  @Test
  @DisplayName("no counterparty name and no amount reaches the log, in any flow")
  void logsCarryNoNameAndNoAmount(CapturedOutput output) throws Exception {
    SharedKafka.start();
    for (String topic :
        List.of(Topics.PRICE_UPDATES, Topics.MARGIN_CALLS, Topics.POSITION_EVENTS)) {
      SharedKafka.ensureTopic(topic, 1);
    }
    try (StubPriceServer source = StubPriceServer.start();
        ConfigurableApplicationContext app =
            LedgerApp.startWeb(
                "--logging.level.com.jerome=DEBUG",
                "--ledger.marketdata.base-url=" + source.baseUrl(),
                "--ledger.marketdata.read-timeout=500ms",
                "--ledger.batch.enabled=true",
                "--ledger.messaging.enabled=true",
                "--ledger.messaging.bootstrap-servers=" + SharedKafka.bootstrapServers(),
                "--ledger.messaging.relay-enabled=true",
                "--ledger.messaging.relay-interval=100ms",
                "--ledger.messaging.consumers-enabled=true",
                "--ledger.messaging.consumer-group-prefix=hygiene-" + UUID.randomUUID())) {
      JdbcClient jdbc = app.getBean(JdbcClient.class);
      jdbc.sql("INSERT INTO counterparty VALUES ('CP-777', ?, 1000000.00, 'HKD')")
          .param(NAME)
          .update();
      jdbc.sql("INSERT INTO security VALUES (?, 'Synthetic HK Equity', 'HKD', 41.26, 'HKD')")
          .param(HK_EQUITY)
          .update();
      RestTestClient http =
          RestTestClient.bindToServer().baseUrl("http://localhost:" + LedgerApp.port(app)).build();

      // Open, then return part of it.
      http.post()
          .uri("/loans")
          .header("Idempotency-Key", "hygiene-open")
          .contentType(MediaType.APPLICATION_JSON)
          .body(
              """
              {"counterpartyId":"CP-777","isin":"%s","quantity":1111,
               "collateral":{"amount":"54321.09","currency":"HKD"},
               "rule":{"type":"FLAT","rate":"0.0365"},"tradeDate":"2026-09-01"}
              """
                  .formatted(HK_EQUITY))
          .exchange()
          .expectStatus()
          .isCreated();
      String position = jdbc.sql("SELECT id FROM loan_position").query(String.class).single();
      http.post()
          .uri("/loans/" + position + "/returns")
          .header("Idempotency-Key", "hygiene-return")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"quantity\":111,\"tradeDate\":\"2026-09-02\"}")
          .exchange()
          .expectStatus()
          .is2xxSuccessful();

      // A price that raises a margin call, and the notice that follows.
      app.getBean(PublishPriceUpdate.class)
          .publish(
              new PriceUpdated(
                  UUID.randomUUID(),
                  Instant.now(),
                  new Isin(HK_EQUITY),
                  Money.of("99.99", HKD),
                  9_000));
      await()
          .atMost(Duration.ofSeconds(90))
          // The consumers start from the earliest offset, so notices for other tests' history are
          // replayed on the shared topics; this one is for this test's counterparty.
          .until(() -> output.getAll().contains("MARGIN CALL notice to CP-777"));
      String shortfall =
          Pattern.compile("\"amount\":\"([0-9.]+)\"")
              .matcher(
                  jdbc.sql("SELECT payload FROM outbox_event WHERE type = 'MarginCallRaised'")
                      .query(String.class)
                      .list()
                      .getFirst())
              .results()
              .map(m -> m.group(1))
              .findFirst()
              .orElseThrow();

      // The daily accrual.
      app.getBean(RunAccrual.class).run(LocalDate.of(2026, 9, 3));
      List<String> accrued =
          jdbc.sql("SELECT fee_amount FROM accrual_entry").query(String.class).list();

      // A live quote, then a stale one.
      source.price(HK_EQUITY, "73.21", "HKD");
      http.get().uri("/securities/" + HK_EQUITY + "/quote").exchange().expectStatus().isOk();
      source.answer(HK_EQUITY, new StubPriceServer.Json(500, "{}"));
      http.get().uri("/securities/" + HK_EQUITY + "/quote").exchange().expectStatus().isOk();
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> output.getAll().contains("Serving a stale price"));

      String log = output.getAll();
      List<String> secrets =
          new ArrayList<>(List.of(NAME, "54321.09", "99.99", "73.21", shortfall));
      List<String> found = new ArrayList<>();
      for (String secret : secrets) {
        if (log.contains(secret)) {
          found.add(secret);
        }
      }
      // Derived accrual amounts are short, so they are only searched as standalone numbers.
      for (String amount : accrued) {
        String plain = new java.math.BigDecimal(amount).toPlainString();
        Matcher standalone =
            Pattern.compile("(?<![0-9:.\\-])" + Pattern.quote(plain) + "(?![0-9])").matcher(log);
        if (standalone.find()) {
          found.add("accrual " + plain);
        }
      }

      Evidence.write(
          "12-log-hygiene.txt",
          "searched %d log characters at DEBUG for com.jerome\nname: %s\namounts: %s, shortfall %s, accrued %s\nfound: %s\n"
              .formatted(log.length(), NAME, secrets.subList(1, 4), shortfall, accrued, found));
      assertThat(found).as("secrets found in the log").isEmpty();
      assertThat(log).contains("MARGIN CALL notice").contains("Serving a stale price");
    }
  }
}

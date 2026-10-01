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
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dashboard and the alert rules are written against meter names. A meter renamed in code leaves
 * a panel that is empty and an alert that can never fire, and nothing else notices. This runs the
 * flows that create every meter, takes a real scrape, and checks that each name the dashboard and
 * the rules query is in it.
 */
@Timeout(600)
class InfraQueriesIT {

  private static final Pattern METER =
      Pattern.compile(
          "\\b((?:ledger|resilience4j|kafka|hikaricp|tomcat|jvm|http_server)_[a-z0-9_]+)");

  private static List<String> expressions() throws Exception {
    List<String> expressions = new ArrayList<>();
    JsonNode dashboard =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(Path.of("../infra/grafana/dashboards/ledger.json")));
    for (JsonNode panel : dashboard.get("panels")) {
      JsonNode targets = panel.get("targets");
      if (targets != null) {
        targets.forEach(target -> expressions.add(target.get("expr").asString()));
      }
    }
    Map<String, Object> rules =
        new Yaml().load(Files.readString(Path.of("../infra/prometheus/alerts.yml")));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> groups = (List<Map<String, Object>>) rules.get("groups");
    for (Map<String, Object> group : groups) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> alerts = (List<Map<String, Object>>) group.get("rules");
      alerts.forEach(alert -> expressions.add(String.valueOf(alert.get("expr"))));
    }
    return expressions;
  }

  @Test
  @DisplayName("every meter the dashboard and the alert rules query is in the application's scrape")
  void everyQueriedMeterExists() throws Exception {
    SharedKafka.start();
    for (String topic :
        List.of(Topics.PRICE_UPDATES, Topics.MARGIN_CALLS, Topics.POSITION_EVENTS)) {
      SharedKafka.ensureTopic(topic, 1);
      SharedKafka.ensureTopic(Topics.deadLetterOf(topic), 1);
    }
    try (StubPriceServer source = StubPriceServer.start();
        ConfigurableApplicationContext app =
            LedgerApp.startWeb(
                "--ledger.marketdata.base-url=" + source.baseUrl(),
                "--ledger.batch.enabled=true",
                "--ledger.messaging.enabled=true",
                "--ledger.messaging.bootstrap-servers=" + SharedKafka.bootstrapServers(),
                "--ledger.messaging.relay-enabled=true",
                "--ledger.messaging.relay-interval=100ms",
                "--ledger.messaging.consumers-enabled=true",
                "--ledger.messaging.retry-initial-interval=500ms",
                "--ledger.messaging.consumer-group-prefix=infra-" + UUID.randomUUID())) {
      JdbcClient jdbc = app.getBean(JdbcClient.class);
      jdbc.sql(
              "INSERT INTO counterparty VALUES ('CP-001', 'Synthetic Borrower One', 1000000.00, 'HKD')")
          .update();
      jdbc.sql("INSERT INTO security VALUES (?, 'Synthetic HK Equity', 'HKD', 41.26, 'HKD')")
          .param(HK_EQUITY)
          .update();
      RestTestClient http =
          RestTestClient.bindToServer().baseUrl("http://localhost:" + LedgerApp.port(app)).build();

      http.post()
          .uri("/loans")
          .header("Idempotency-Key", "infra-open")
          .contentType(MediaType.APPLICATION_JSON)
          .body(WebLedger.openBody(HK_EQUITY, 1000, "50000.00", "HKD"))
          .exchange()
          .expectStatus()
          .isCreated();
      app.getBean(PublishPriceUpdate.class)
          .publish(
              new PriceUpdated(
                  UUID.randomUUID(),
                  Instant.now(),
                  new Isin(HK_EQUITY),
                  Money.of("60.00", Currency.getInstance("HKD")),
                  9_000));
      source.price(HK_EQUITY, "41.30", "HKD");
      http.get().uri("/securities/" + HK_EQUITY + "/quote").exchange().expectStatus().isOk();
      app.getBean(RunAccrual.class).run(LocalDate.of(2026, 9, 3));
      // A message that cannot be decoded goes to the dead-letter topic, which is what counts it.
      try (KafkaProducer<String, String> producer =
          new KafkaProducer<>(SharedKafka.producerProperties())) {
        ProducerRecord<String, String> poison =
            new ProducerRecord<>(Topics.PRICE_UPDATES, "k", "this is not json");
        poison.headers().add("eventType", "PriceUpdated".getBytes(StandardCharsets.UTF_8));
        producer.send(poison).get();
      }

      PrometheusMeterRegistry prometheus = app.getBean(PrometheusMeterRegistry.class);
      await()
          .atMost(Duration.ofSeconds(90))
          .until(
              () ->
                  prometheus.scrape().contains("ledger_dead_letters_total")
                      && prometheus.scrape().contains("ledger_notices_sent_total"));
      await()
          .atMost(Duration.ofSeconds(20))
          .until(
              () ->
                  prometheus
                      .scrape()
                      .contains(
                          "ledger_batch_last_success_timestamp_seconds{batch_job=\"dailyAccrual\"} 1"));
      String scrape = prometheus.scrape();

      Set<String> queried = new TreeSet<>();
      for (String expression : expressions()) {
        Matcher matcher = METER.matcher(expression);
        while (matcher.find()) {
          queried.add(matcher.group(1));
        }
      }
      List<String> missing = new ArrayList<>();
      for (String name : queried) {
        boolean present =
            scrape
                .lines()
                .anyMatch(line -> line.startsWith(name + " ") || line.startsWith(name + "{"));
        if (!present) {
          missing.add(name);
        }
      }
      Evidence.write(
          "12-infra-queries.txt",
          "meters queried by the dashboard and the alert rules: "
              + queried.size()
              + "\nmissing from the scrape: "
              + missing
              + "\n"
              + String.join("\n", queried)
              + "\n");
      assertThat(missing).as("queried meters absent from the scrape").isEmpty();
    }
  }
}

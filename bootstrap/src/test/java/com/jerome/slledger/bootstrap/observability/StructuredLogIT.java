package com.jerome.slledger.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The prod log format: one JSON object per line, the trace id a field and not part of a message.
 */
@Timeout(120)
@ExtendWith(OutputCaptureExtension.class)
class StructuredLogIT {

  /** Test-only source, registered by class: a request that logs. */
  @RestController
  static class LogProbe {
    private static final Logger LOG = LoggerFactory.getLogger("log.probe");

    @GetMapping("/probe/log")
    String log() {
      LOG.info("a line written while serving a request");
      return "ok";
    }
  }

  @Test
  @DisplayName("every line is a JSON object, and a request's line carries its traceId as a field")
  void jsonWithTraceId(CapturedOutput output) {
    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
    try (ConfigurableApplicationContext app =
        LedgerApp.startWeb(
            List.of(LogProbe.class), "--logging.structured.format.console=logstash")) {
      RestTestClient.bindToServer()
          .baseUrl("http://localhost:" + LedgerApp.port(app))
          .build()
          .get()
          .uri("/probe/log")
          .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
          .exchange()
          .expectStatus()
          .isOk();
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> output.getAll().contains("a line written while serving a request"));
    }

    JsonMapper mapper = JsonMapper.builder().build();
    List<String> lines = output.getAll().lines().filter(l -> !l.isBlank()).toList();
    // The captured output also holds what the test's own setup printed (Testcontainers, in the
    // plain
    // pattern). The application's lines are the ones in JSON, and all of them must parse.
    List<String> events = lines.stream().filter(l -> l.startsWith("{")).toList();
    assertThat(events).hasSizeGreaterThan(5);
    for (String line : events) {
      assertThat(line).endsWith("}");
      mapper.readTree(line);
    }
    assertThat(lines.stream().filter(l -> l.contains("\"message\":\"Started ")).toList())
        .as("the start-up line is JSON too")
        .isNotEmpty();
    String ours =
        lines.stream()
            .filter(l -> l.contains("a line written while serving a request"))
            .findFirst()
            .orElseThrow();
    JsonNode event = mapper.readTree(ours);
    assertThat(event.get("traceId").asString()).isEqualTo(traceId);
    assertThat(event.get("spanId").asString()).matches("[0-9a-f]{16}");
    Evidence.write("12-structured-log.txt", ours + "\n");
  }
}

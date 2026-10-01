package com.jerome.slledger.bootstrap.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.position.LedgerApp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/** The application serves HTTP at all: stage 7's first observable. */
class WebSmokeIT {

  private static ConfigurableApplicationContext app;
  private static RestTestClient http;

  @BeforeAll
  static void start() {
    app = LedgerApp.startWeb();
    http = LedgerApp.http(app);
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  @Test
  @DisplayName("the OpenAPI document is served")
  void servesApiDocs() {
    http.get().uri("/v3/api-docs").exchange().expectStatus().isOk();
  }

  @Test
  @DisplayName("an unknown path is a 404 ProblemDetail")
  void unknownPathIsProblemDetail() {
    http.get()
        .uri("/no-such-path")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND)
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(app.isActive()).isTrue();
  }
}

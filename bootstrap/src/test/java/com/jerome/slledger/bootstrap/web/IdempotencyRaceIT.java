package com.jerome.slledger.bootstrap.web;

import static com.jerome.slledger.bootstrap.web.WebLedger.HK_EQUITY;
import static com.jerome.slledger.bootstrap.web.WebLedger.openBody;
import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.loan.JpaTradeLedger;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * Two submissions of one Idempotency-Key arriving together. Postgres decides: the second insert
 * waits on the unique index until the first transaction ends. See docs/decisions/07-web.md.
 */
@Timeout(60)
class IdempotencyRaceIT {

  private static final StringBuilder EVIDENCE = new StringBuilder();

  private static WebLedger ledger;
  private static HoldingTradeLedger holding;
  private static ExecutorService threads;

  @BeforeAll
  static void start() {
    ledger = WebLedger.start(List.of(HoldingWiring.class));
    holding = ledger.app.getBean(HoldingTradeLedger.class);
    threads = Executors.newFixedThreadPool(2);
    EVIDENCE.append(
        "Stage 07 · section 10 — two requests, one Idempotency-Key (IdempotencyRaceIT)\n\n");
  }

  @AfterAll
  static void stop() {
    Evidence.write("07-idempotency-race.txt", EVIDENCE.toString());
    threads.shutdownNow();
    ledger.close();
  }

  @BeforeEach
  void clean() {
    ledger.jdbc.sql("DELETE FROM loan_trade").update();
    ledger.jdbc.sql("DELETE FROM loan_position").update();
  }

  @AfterEach
  void releaseAlways() {
    holding.release();
  }

  @RepeatedTest(10)
  @DisplayName("the first commits: the second waits, then replays the first's answer")
  void firstCommits(RepetitionInfo repetition) throws Exception {
    Race race = race(false);

    assertThat(race.first.getStatus()).isEqualTo(HttpStatus.CREATED);
    assertThat(race.second.getStatus()).isEqualTo(HttpStatus.CREATED);
    assertThat(race.second.getResponseHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    assertThat(race.second.getResponseBody()).isEqualTo(race.first.getResponseBody());
    assertThat(ledger.count("loan_position")).isEqualTo(1);
    assertThat(ledger.count("loan_trade")).isEqualTo(1);
    if (repetition.getCurrentRepetition() == 1) {
      record("The first commits", race);
    }
  }

  @RepeatedTest(10)
  @DisplayName("the first rolls back: the second's insert proceeds and it books as a first")
  void firstRollsBack(RepetitionInfo repetition) throws Exception {
    Race race = race(true);

    assertThat(race.first.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(race.second.getStatus()).isEqualTo(HttpStatus.CREATED);
    assertThat(race.second.getResponseHeaders().getFirst("Idempotent-Replayed")).isNull();
    assertThat(ledger.count("loan_position")).isEqualTo(1);
    assertThat(ledger.count("loan_trade")).isEqualTo(1);
    if (repetition.getCurrentRepetition() == 1) {
      record("The first rolls back", race);
    }
  }

  private Race race(boolean rollBack) throws Exception {
    String body = openBody(HK_EQUITY, 1000, "50000.00", "HKD");
    holding.arm(rollBack);

    CompletableFuture<EntityExchangeResult<String>> first =
        CompletableFuture.supplyAsync(() -> post("K-RACE", body), threads);
    assertThat(holding.awaitHeld()).as("first request reached its insert").isTrue();

    long secondSent = System.nanoTime();
    CompletableFuture<EntityExchangeResult<String>> second =
        CompletableFuture.supplyAsync(() -> post("K-RACE", body), threads);
    Map<String, Object> waiting = awaitBlockedOnTransaction();
    long blockedSeen = System.nanoTime();

    holding.release();
    EntityExchangeResult<String> firstResult = first.get(20, TimeUnit.SECONDS);
    EntityExchangeResult<String> secondResult = second.get(20, TimeUnit.SECONDS);
    long secondAnswered = System.nanoTime();
    return new Race(
        firstResult,
        secondResult,
        waiting,
        TimeUnit.NANOSECONDS.toMillis(blockedSeen - secondSent),
        TimeUnit.NANOSECONDS.toMillis(secondAnswered - secondSent));
  }

  /**
   * Polls until some backend waits on another transaction's lock: the second insert, blocked on the
   * first's uncommitted row in the unique index. Polling a condition with a bound, not sequencing
   * by time.
   */
  private static Map<String, Object> awaitBlockedOnTransaction() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Optional<Map<String, Object>> row =
          ledger
              .jdbc
              .sql(
                  "SELECT wait_event_type, wait_event, state, left(query, 60) AS query"
                      + " FROM pg_stat_activity"
                      + " WHERE wait_event_type = 'Lock' AND wait_event = 'transactionid'"
                      + " AND datname = current_database()")
              .query()
              .listOfRows()
              .stream()
              .findFirst();
      if (row.isPresent()) {
        return row.get();
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
    }
    throw new AssertionError("the second insert never waited on the first transaction");
  }

  private static EntityExchangeResult<String> post(String key, String body) {
    return ledger
        .http
        .post()
        .uri("/loans")
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectBody(String.class)
        .returnResult();
  }

  private static void record(String title, Race race) {
    EVIDENCE.append(
        """
        %s
          pg_stat_activity while the first was held:
            %s
          first:  %s %s
          second: %s Idempotent-Replayed=%s
          second's request, sent to first seen blocked:  %d ms
          second's request, sent to answered:            %d ms (includes the hold)
          same body: %s
          loan_position rows: %d, loan_trade rows: %d

        """
            .formatted(
                title,
                race.waiting,
                race.first.getStatus(),
                race.first.getStatus().is5xxServerError() ? "(held booking told to roll back)" : "",
                race.second.getStatus(),
                race.second.getResponseHeaders().getFirst("Idempotent-Replayed"),
                race.sentToBlockedMillis,
                race.sentToAnsweredMillis,
                race.second.getResponseBody().equals(race.first.getResponseBody()),
                ledger.count("loan_position"),
                ledger.count("loan_trade")));
  }

  private record Race(
      EntityExchangeResult<String> first,
      EntityExchangeResult<String> second,
      Map<String, Object> waiting,
      long sentToBlockedMillis,
      long sentToAnsweredMillis) {}

  /** Test-only wiring; not a {@code @Configuration}, so only this test registers it. */
  static class HoldingWiring {

    @Bean
    @Primary
    HoldingTradeLedger holdingTradeLedger(JpaTradeLedger real) {
      return new HoldingTradeLedger(real);
    }
  }
}

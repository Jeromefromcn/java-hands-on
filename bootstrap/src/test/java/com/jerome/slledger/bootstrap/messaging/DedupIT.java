package com.jerome.slledger.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.messaging.testing.Evidence;
import com.jerome.slledger.application.messaging.ConsumedMessages;
import com.jerome.slledger.application.messaging.DedupPurge;
import com.jerome.slledger.application.messaging.IdempotentConsumer;
import com.jerome.slledger.application.messaging.IntegrationEvent.MarginCallRaised;
import com.jerome.slledger.application.messaging.MarginCallNotifier;
import com.jerome.slledger.application.messaging.NoticeChannel;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The consumer-side guard: the effect a duplicate would repeat, the guard that stops it, and what
 * the guard's own retention decides. The channel is a recorder, standing in for an external call.
 */
@Timeout(300)
class DedupIT {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");
  private static final Duration RETENTION = Duration.ofDays(7);
  private static final StringBuilder EVIDENCE = new StringBuilder();

  private static MessagingLedger ledger;

  private ConsumedMessages consumed;
  private final List<NoticeChannel.MarginCallNotice> sent = new ArrayList<>();
  private final NoticeChannel recorder = sent::add;

  /** A clock a test moves. */
  private static final class Manual extends Clock {
    private Instant now = T0;

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @BeforeAll
  static void start() {
    ledger = MessagingLedger.start();
  }

  @AfterAll
  static void stop() {
    ledger.close();
    Evidence.write("10-dedup-ttl.txt", EVIDENCE.toString());
  }

  @BeforeEach
  void setUp() {
    ledger.reset();
    consumed = ledger.app.getBean(ConsumedMessages.class);
    ledger.jdbc.sql("DELETE FROM consumed_message").update();
    sent.clear();
  }

  private static MarginCallRaised call() {
    return new MarginCallRaised(
        UUID.randomUUID(),
        T0,
        new LoanPositionId("P-1"),
        new CounterpartyId("CP-001"),
        new Isin("HK0000000001"),
        LocalDate.of(2026, 9, 30),
        Money.of("10000.00", HKD));
  }

  private MarginCallNotifier guarded(NoticeChannel channel) {
    return new MarginCallNotifier(ledger.app.getBean(IdempotentConsumer.class), channel);
  }

  @Test
  @DisplayName("the same message twice sends two notices without the guard, one with it")
  void reproducesDuplicateNotice() {
    // See docs/decisions/10-messaging.md
    MarginCallRaised event = call();

    // Without the guard: what a consumer that just acts on each delivery does.
    for (int delivery = 1; delivery <= 2; delivery++) {
      recorder.send(
          new NoticeChannel.MarginCallNotice(
              event.counterparty(),
              event.position(),
              event.security(),
              event.calledOn(),
              event.shortfall()));
    }
    int unguarded = sent.size();
    sent.clear();

    MarginCallNotifier notifier = guarded(recorder);
    boolean first = notifier.handle(event);
    boolean second = notifier.handle(event);

    assertThat(unguarded).isEqualTo(2);
    assertThat(first).isTrue();
    assertThat(second).isFalse();
    assertThat(sent).hasSize(1);
    assertThat(sent.getFirst().shortfall()).isEqualTo(Money.of("10000.00", HKD));
    EVIDENCE
        .append("one MarginCallRaised delivered twice: notices sent without the guard=")
        .append(unguarded)
        .append(", with the guard=")
        .append(sent.size())
        .append('\n');
  }

  @Test
  @DisplayName("an effect that fails takes its guard row with it, so the redelivery is acted on")
  void failedEffectRollsBackTheGuard() {
    MarginCallRaised event = call();
    AtomicBoolean fail = new AtomicBoolean(true);
    MarginCallNotifier notifier =
        guarded(
            notice -> {
              if (fail.get()) {
                throw new IllegalStateException("channel down");
              }
              sent.add(notice);
            });

    assertThatThrownBy(() -> notifier.handle(event)).isInstanceOf(IllegalStateException.class);
    assertThat(consumed.count()).isZero();

    fail.set(false);
    assertThat(notifier.handle(event)).isTrue();
    assertThat(notifier.handle(event)).isFalse();

    assertThat(sent).hasSize(1);
    assertThat(consumed.count()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "a purge shorter than the redelivery window lets a duplicate through; ttl > retention does not")
  void reproducesDuplicateAfterTtl() {
    // See docs/decisions/10-messaging.md
    MarginCallRaised event = call();
    Manual clock = new Manual();
    IdempotentConsumer guard = new IdempotentConsumer(consumed, clock);

    // A one-hour TTL against a seven-day topic.
    Duration shortTtl = Duration.ofHours(1);
    sendOnce(guard, event);
    clock.advance(Duration.ofHours(2));
    new DedupPurge(consumed, clock, shortTtl).run();
    boolean duplicateActedOn = sendOnce(guard, event);
    int sentAfterShort = sent.size();

    // Now the rule: 8 days against 7. Redelivered on the last day the topic still holds it.
    sent.clear();
    ledger.jdbc.sql("DELETE FROM consumed_message").update();
    clock.now = T0;
    Duration ttl = RETENTION.plusDays(1);
    sendOnce(guard, event);
    clock.advance(RETENTION.minusMinutes(1));
    new DedupPurge(consumed, clock, ttl).run();
    boolean lateButInWindow = sendOnce(guard, event);

    assertThat(duplicateActedOn).isTrue();
    assertThat(sentAfterShort).isEqualTo(2);
    assertThat(lateButInWindow).isFalse();
    assertThat(sent).hasSize(1);
    EVIDENCE
        .append(
            "ttl 1h, topic retention 7d, duplicate redelivered 2h after the first: notices sent=")
        .append(sentAfterShort)
        .append(" (the guard row was purged)\n");
    EVIDENCE
        .append(
            "ttl 8d, topic retention 7d, duplicate redelivered 6d23h59m after the first, after a purge: notices sent=")
        .append(sent.size())
        .append(" (the guard row is still there)\n");
  }

  private boolean sendOnce(IdempotentConsumer guard, MarginCallRaised event) {
    return Boolean.TRUE.equals(
        ledger.transaction.execute(
            status -> guard.once(MarginCallNotifier.CONSUMER, event.id(), () -> sent.add(null))));
  }

  @Test
  @Tag("measurement")
  @DisplayName("with no purge the table grows by one row per message, without end")
  void reproducesUnboundedDedupTable() {
    // See docs/decisions/10-messaging.md
    IdempotentConsumer guard = ledger.app.getBean(IdempotentConsumer.class);
    int throughCode = 2_000;
    long startedNanos = System.nanoTime();
    for (int i = 0; i < throughCode; i++) {
      guard.once(MarginCallNotifier.CONSUMER, UUID.randomUUID(), () -> {});
    }
    double meanMs = (System.nanoTime() - startedNanos) / 1_000_000.0 / throughCode;
    long sizeAfterCode = tableBytes();

    long bulk = 1_000_000;
    ledger
        .jdbc
        .sql(
            "INSERT INTO consumed_message (consumer, message_id, consumed_at)"
                + " SELECT 'margin-call-notifier', gen_random_uuid(), now() - n * interval '1 second'"
                + " FROM generate_series(1, ?) n")
        .param(bulk)
        .update();
    ledger.jdbc.sql("ANALYZE consumed_message").update();
    long rows = consumed.count();
    long bytes = tableBytes();
    double bytesPerRow = (double) bytes / rows;

    long purgeStarted = System.nanoTime();
    int purged =
        ledger
            .jdbc
            .sql("DELETE FROM consumed_message WHERE consumed_at < now() - interval '1 hour'")
            .update();
    double purgeMs = (System.nanoTime() - purgeStarted) / 1_000_000.0;

    assertThat(rows).isEqualTo(throughCode + bulk);
    assertThat(purged).isGreaterThan(900_000);
    EVIDENCE
        .append("no purge: ")
        .append(throughCode)
        .append(" messages through IdempotentConsumer.once, mean ")
        .append("%.2f".formatted(meanMs))
        .append(" ms each (one transaction each), table+index ")
        .append(sizeAfterCode)
        .append(" bytes\n")
        .append("no purge: ")
        .append(rows)
        .append(" rows (the rest bulk-inserted with generate_series) -> ")
        .append(bytes)
        .append(" bytes table+indexes = ")
        .append("%.1f".formatted(bytesPerRow))
        .append(" bytes/row\n")
        .append("arithmetic, not measured: at 100x that is ")
        .append("%.1f".formatted(bytesPerRow * rows * 100 / 1e9))
        .append(" GB, still growing by one row per message per consumer\n")
        .append("one DELETE of ")
        .append(purged)
        .append(" rows older than 1 hour: ")
        .append("%.0f".formatted(purgeMs))
        .append(" ms\n");
  }

  private long tableBytes() {
    return ledger
        .jdbc
        .sql("SELECT pg_total_relation_size('consumed_message')")
        .query(Long.class)
        .single();
  }
}

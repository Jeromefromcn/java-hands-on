package com.jerome.slledger.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.messaging.JsonEventCodec;
import com.jerome.slledger.adapter.messaging.testing.Evidence;
import com.jerome.slledger.adapter.messaging.testing.SharedKafka;
import com.jerome.slledger.application.messaging.EventSender;
import com.jerome.slledger.application.messaging.IntegrationEvent.MarginCallRaised;
import com.jerome.slledger.application.messaging.IntegrationEvent.PriceUpdated;
import com.jerome.slledger.application.messaging.NoticeChannel;
import com.jerome.slledger.application.messaging.OutboxMessage;
import com.jerome.slledger.application.messaging.PublishPriceUpdate;
import com.jerome.slledger.application.messaging.Topics;
import com.jerome.slledger.application.position.OpenPosition;
import com.jerome.slledger.application.position.OpenPositionCommand;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.loan.LoanTrade;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Everything on at once, the way it runs: an open goes out through the outbox and the relay; a
 * price update comes in over Kafka, is applied, revalues, raises a margin call through the outbox,
 * and the notifier tells the counterparty, once, even when the same message is delivered again. The
 * consumers start from the earliest offset, so history other tests left on the shared topics is
 * replayed; every assertion is about this test's own position.
 */
@Timeout(300)
class MessagingEndToEndIT {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final JsonEventCodec CODEC = new JsonEventCodec();
  private static final StringBuilder EVIDENCE = new StringBuilder();

  /** Records notices instead of logging them. */
  static class RecordingChannel implements NoticeChannel {
    final List<MarginCallNotice> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(MarginCallNotice notice) {
      sent.add(notice);
    }
  }

  static final RecordingChannel CHANNEL = new RecordingChannel();

  static class Recording {
    @Bean
    @Primary
    NoticeChannel recordingNoticeChannel() {
      return CHANNEL;
    }
  }

  private static MessagingLedger ledger;

  @BeforeAll
  static void start() {
    ledger =
        MessagingLedger.start(
            List.of(Recording.class),
            "--ledger.messaging.relay-enabled=true",
            "--ledger.messaging.consumers-enabled=true",
            "--ledger.messaging.relay-interval=100ms",
            "--ledger.messaging.consumer-group-prefix=e2e-" + UUID.randomUUID(),
            "--ledger.messaging.retry-initial-interval=500ms");
  }

  @AfterAll
  static void stop() {
    ledger.close();
    Evidence.write("10-end-to-end.txt", EVIDENCE.toString());
  }

  private static <T> T await(
      String what, java.util.function.Supplier<T> probe, java.util.function.Predicate<T> done) {
    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    T value = probe.get();
    while (!done.test(value)) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out waiting for " + what);
      }
      try {
        Thread.sleep(
            200); // real time: the relay and the consumers are running on their own threads
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      value = probe.get();
    }
    return value;
  }

  private static String messageId(ConsumerRecord<String, String> record) {
    return new String(record.headers().lastHeader("messageId").value(), StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName(
      "open -> relay -> topic; price update -> revalue -> margin call -> one notice, even delivered twice")
  void wholeFlow() throws Exception {
    // 1. An open, through the application's own use case: the relay sends its PositionOpened.
    OpenPosition open = ledger.app.getBean(OpenPosition.class);
    LoanTrade trade =
        open.open(
            new OpenPositionCommand(
                new IdempotencyKey("E2E-" + UUID.randomUUID()),
                new CounterpartyId("CP-001"),
                new Isin("HK0000000001"),
                1_000,
                Money.of("50000.00", HKD),
                new FlatRate(Rate.of("0.0365")),
                LocalDate.of(2026, 9, 1)));
    LoanPositionId position = trade.position();
    List<ConsumerRecord<String, String>> opened =
        await(
            "PositionOpened on the topic",
            () ->
                SharedKafka.readAll(Topics.POSITION_EVENTS).stream()
                    .filter(r -> position.value().equals(r.key()))
                    .toList(),
            list -> !list.isEmpty());
    assertThat(opened).hasSize(1);
    assertThat(ledger.count("outbox_event")).isGreaterThanOrEqualTo(1);
    await(
        "outbox drained",
        () ->
            ledger
                .jdbc
                .sql("SELECT count(*) FROM outbox_event WHERE published_at IS NULL")
                .query(Long.class)
                .single(),
        n -> n == 0);

    // 2. A price update arrives on Kafka (from a feed): 60.00 x 1,000 shares against 50,000.00.
    PriceUpdated drop =
        new PriceUpdated(
            UUID.randomUUID(),
            Instant.now(),
            new Isin("HK0000000001"),
            Money.of("60.00", HKD),
            5_000);
    ledger.app.getBean(PublishPriceUpdate.class).publish(drop);

    List<NoticeChannel.MarginCallNotice> mine =
        await(
            "a notice for the position",
            () -> CHANNEL.sent.stream().filter(n -> n.position().equals(position)).toList(),
            list -> !list.isEmpty());
    assertThat(mine).hasSize(1);
    assertThat(mine.getFirst().shortfall()).isEqualTo(Money.of("10000.00", HKD));
    assertThat(mine.getFirst().counterparty()).isEqualTo(new CounterpartyId("CP-001"));

    // 3. The margin call message is delivered again (a relay resend, a rebalance): same id.
    UUID callId =
        ledger
            .jdbc
            .sql("SELECT id FROM outbox_event WHERE type = 'MarginCallRaised' AND message_key = ?")
            .param(position.value())
            .query((rs, n) -> rs.getObject("id", UUID.class))
            .single();
    ConsumerRecord<String, String> original =
        SharedKafka.readAll(Topics.MARGIN_CALLS).stream()
            .filter(r -> messageId(r).equals(callId.toString()))
            .findFirst()
            .orElseThrow();
    EventSender sender = ledger.app.getBean(EventSender.class);
    sender.send(
        new OutboxMessage(
            callId,
            Topics.MARGIN_CALLS,
            position.value(),
            "MarginCallRaised",
            original.value(),
            Instant.now()));
    // A different call for the same position behind it: when its notice is out, the duplicate has
    // been handled.
    MarginCallRaised sentinel =
        new MarginCallRaised(
            UUID.randomUUID(),
            Instant.now(),
            position,
            new CounterpartyId("CP-001"),
            new Isin("HK0000000001"),
            LocalDate.of(2026, 10, 2),
            Money.of("1.00", HKD));
    sender.send(OutboxMessage.of(sentinel, CODEC.encode(sentinel)));
    await(
        "the sentinel's notice",
        () -> CHANNEL.sent.stream().filter(n -> n.position().equals(position)).toList(),
        list -> list.size() >= 2);

    List<NoticeChannel.MarginCallNotice> after =
        CHANNEL.sent.stream().filter(n -> n.position().equals(position)).toList();
    assertThat(after).hasSize(2);
    assertThat(
            after.stream()
                .map(NoticeChannel.MarginCallNotice::shortfall)
                .map(m -> m.amount().toPlainString())
                .toList())
        .containsExactly("10000.00", "1.00");
    EVIDENCE
        .append(
            "open -> PositionOpened on sl.position-events (relay, 100 ms interval); price 60.00 v5000 on sl.price-updates -> MarginCallRaised shortfall 10000.00 HKD -> 1 notice; the same MarginCallRaised delivered again plus a sentinel call: notices for the position=")
        .append(after.size())
        .append(" (10000.00 once, then the sentinel's 1.00; the duplicate sent none)\n");
  }
}

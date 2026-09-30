package com.jerome.slledger.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.messaging.JsonEventCodec;
import com.jerome.slledger.adapter.messaging.KafkaEventSender;
import com.jerome.slledger.adapter.messaging.KafkaProducers;
import com.jerome.slledger.adapter.messaging.testing.Evidence;
import com.jerome.slledger.adapter.messaging.testing.SharedKafka;
import com.jerome.slledger.application.messaging.EventSendException;
import com.jerome.slledger.application.messaging.EventSender;
import com.jerome.slledger.application.messaging.IntegrationEvent;
import com.jerome.slledger.application.messaging.IntegrationEvent.MarginCallRaised;
import com.jerome.slledger.application.messaging.IntegrationEvent.PositionOpened;
import com.jerome.slledger.application.messaging.Outbox;
import com.jerome.slledger.application.messaging.OutboxMessage;
import com.jerome.slledger.application.messaging.OutboxRelay;
import com.jerome.slledger.application.messaging.OutboxStore;
import com.jerome.slledger.application.messaging.RelayResult;
import com.jerome.slledger.application.messaging.Topics;
import com.jerome.slledger.application.position.OpenPosition;
import com.jerome.slledger.application.position.OpenPositionCommand;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.error.InsufficientCreditException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.loan.LoanTrade;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * The outbox end to end: the application's own {@code OpenPosition} appends, a relay sends to a
 * real Kafka, and each failure between the two is made to happen. The two dual-write reproductions
 * are the case for the outbox; the rest is what the outbox does and does not guarantee.
 */
@Timeout(180)
class OutboxIT {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final LocalDate TRADE_DATE = LocalDate.of(2026, 9, 1);
  private static final StringBuilder DUAL_WRITE = new StringBuilder();
  private static final StringBuilder OUTBOX = new StringBuilder();

  private static MessagingLedger ledger;
  private static KafkaTemplate<String, String> deadBroker;
  private static final AtomicInteger KEYS = new AtomicInteger();

  private OpenPosition open;
  private OutboxStore store;
  private OutboxRelay relay;
  private EventSender sender;
  private Outbox outbox;

  @BeforeAll
  static void start() {
    ledger = MessagingLedger.start();
    // A broker that is not there: nothing listens on port 1, and every wait is short.
    deadBroker =
        KafkaProducers.template("127.0.0.1:1", Duration.ofSeconds(1), Duration.ofMillis(500));
  }

  @AfterAll
  static void stop() {
    deadBroker.destroy();
    ledger.close();
    Evidence.write("10-dual-write.txt", DUAL_WRITE.toString());
    Evidence.write("10-outbox.txt", OUTBOX.toString());
  }

  @BeforeEach
  void setUp() {
    ledger.reset();
    open = ledger.app.getBean(OpenPosition.class);
    store = ledger.app.getBean(OutboxStore.class);
    relay = ledger.app.getBean(OutboxRelay.class);
    sender = ledger.app.getBean(EventSender.class);
    outbox = ledger.app.getBean(Outbox.class);
  }

  @AfterEach
  void clean() {
    ledger.reset();
  }

  private static OpenPositionCommand command(String collateral) {
    return new OpenPositionCommand(
        new IdempotencyKey("K-" + KEYS.incrementAndGet() + "-" + UUID.randomUUID()),
        new CounterpartyId("CP-001"),
        new Isin("HK0000000001"),
        1_000,
        Money.of(collateral, HKD),
        new FlatRate(Rate.of("0.0365")),
        TRADE_DATE);
  }

  private RelayResult pass(OutboxRelay relayToRun) {
    return ledger.transaction.execute(status -> relayToRun.relayBatch());
  }

  /** Every record on the topic whose message id is one of {@code ids}, in topic order. */
  private static List<ConsumerRecord<String, String>> onTopic(String topic, Collection<UUID> ids) {
    Collection<String> wanted = ids.stream().map(UUID::toString).toList();
    return SharedKafka.readAll(topic).stream()
        .filter(record -> wanted.contains(messageId(record)))
        .toList();
  }

  private static String messageId(ConsumerRecord<String, String> record) {
    return new String(record.headers().lastHeader("messageId").value(), StandardCharsets.UTF_8);
  }

  private List<UUID> unpublishedIds() {
    return ledger
        .jdbc
        .sql("SELECT id FROM outbox_event WHERE published_at IS NULL ORDER BY seq")
        .query((rs, row) -> rs.getObject("id", UUID.class))
        .list();
  }

  @Test
  @DisplayName(
      "an open records one unpublished row; a relay pass sends it, with its id in the header")
  void openThenRelay() {
    LoanTrade trade = open.open(command("50000.00"));

    assertThat(ledger.count("outbox_event")).isEqualTo(1);
    assertThat(store.countUnpublished()).isEqualTo(1);
    UUID id = unpublishedIds().getFirst();

    RelayResult result = pass(relay);

    assertThat(result.complete()).isTrue();
    assertThat(result.sent()).isEqualTo(1);
    assertThat(store.countUnpublished()).isZero();
    List<ConsumerRecord<String, String>> records = onTopic(Topics.POSITION_EVENTS, List.of(id));
    assertThat(records).hasSize(1);
    assertThat(records.getFirst().key()).isEqualTo(trade.position().value());
    IntegrationEvent decoded =
        new JsonEventCodec().decode("PositionOpened", records.getFirst().value());
    assertThat(decoded).isInstanceOf(PositionOpened.class);
    PositionOpened opened = (PositionOpened) decoded;
    assertThat(opened.id()).isEqualTo(id);
    assertThat(opened.position()).isEqualTo(trade.position());
    assertThat(opened.collateral()).isEqualTo(Money.of("50000.00", HKD));
    assertThat(opened.quantity()).isEqualTo(1_000);
    OUTBOX.append(
        "open -> 1 outbox row; relay pass sent=1, unpublished after=0; topic record key=position id, header messageId=event id, collateral 50000.00 HKD on the wire\n");
  }

  @Test
  @DisplayName(
      "the outbox row and the position roll back together, even when the throw comes after the append")
  void rollBackTogether() {
    assertThatThrownBy(
            () ->
                ledger.transaction.executeWithoutResult(
                    status -> {
                      open.open(command("50000.00"));
                      // The outbox row and the position both exist here, uncommitted.
                      assertThat(
                              ledger
                                  .jdbc
                                  .sql("SELECT count(*) FROM outbox_event")
                                  .query(Long.class)
                                  .single())
                          .isEqualTo(1);
                      throw new IllegalStateException(
                          "something later in the caller's transaction failed");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(ledger.count("outbox_event")).isZero();
    assertThat(ledger.count("loan_position")).isZero();
    OUTBOX.append("throw after the append: outbox rows=0, positions=0\n");
  }

  @Test
  @DisplayName("an open refused by the credit check leaves no outbox row")
  void refusedOpenLeavesNoRow() {
    assertThatThrownBy(() -> open.open(command("2000000000.00")))
        .isInstanceOf(InsufficientCreditException.class);

    assertThat(ledger.count("outbox_event")).isZero();
  }

  @Test
  @DisplayName(
      "commit, then send to an unreachable broker: the position exists and the event never will")
  void reproducesDualWriteLosesTheEvent() {
    // See docs/decisions/10-messaging.md
    LoanTrade trade = open.open(command("50000.00"));
    // The naive flow: the use case has committed; only now does the caller publish.
    PositionOpened event = openedFor(trade);
    EventSender unreachable = new KafkaEventSender(deadBroker, Duration.ofSeconds(3));
    long started = System.nanoTime();

    assertThatThrownBy(
            () -> unreachable.send(OutboxMessage.of(event, new JsonEventCodec().encode(event))))
        .isInstanceOf(EventSendException.class);
    long tookMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

    assertThat(ledger.count("loan_position")).isEqualTo(1);
    assertThat(onTopic(Topics.POSITION_EVENTS, List.of(event.id()))).isEmpty();
    // The application's own outbox row was written by the same open; the naive event has none.
    // Nothing on the naive path records that an event is owed: a retry has nothing to retry from.
    DUAL_WRITE
        .append("commit then send, broker unreachable: send threw after ")
        .append(tookMs)
        .append(
            " ms; positions committed=1, messages on the topic for it=0, rows recording that it is owed=0 (on the naive path)\n");
  }

  private static PositionOpened openedFor(LoanTrade trade) {
    return new PositionOpened(
        UUID.randomUUID(),
        Instant.now(),
        trade.position(),
        new CounterpartyId("CP-001"),
        new Isin("HK0000000001"),
        1_000,
        Money.of("50000.00", HKD));
  }

  @Test
  @DisplayName(
      "send inside the transaction, then roll back: a message for a position that does not exist")
  void reproducesPhantomMessageBeforeCommit() {
    // See docs/decisions/10-messaging.md
    List<UUID> sentIds = new ArrayList<>();
    assertThatThrownBy(
            () ->
                ledger.transaction.executeWithoutResult(
                    status -> {
                      LoanTrade trade = open.open(command("50000.00"));
                      PositionOpened event = openedFor(trade);
                      sender.send(OutboxMessage.of(event, new JsonEventCodec().encode(event)));
                      sentIds.add(event.id());
                      throw new IllegalStateException("the transaction failed after the send");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(ledger.count("loan_position")).isZero();
    List<ConsumerRecord<String, String>> phantom = onTopic(Topics.POSITION_EVENTS, sentIds);
    assertThat(phantom).hasSize(1);
    DUAL_WRITE
        .append(
            "send inside the transaction, then rollback: positions committed=0, messages on the topic for it=")
        .append(phantom.size())
        .append(" (a downstream system now knows about a position that does not exist)\n");
  }

  private void appendCalls(String key, int count, Instant firstAt) {
    ledger.transaction.executeWithoutResult(
        status -> {
          for (int i = 0; i < count; i++) {
            outbox.append(
                new MarginCallRaised(
                    UUID.randomUUID(),
                    firstAt.plusSeconds(i),
                    new LoanPositionId(key),
                    new CounterpartyId("CP-001"),
                    new Isin("HK0000000001"),
                    TRADE_DATE,
                    Money.of("100.0" + i, HKD)));
          }
        });
  }

  @Test
  @DisplayName(
      "with the broker away rows accumulate and nothing is marked; when it returns, all arrive in order")
  void brokerOutageThenRecovery() {
    appendCalls("P-OUTAGE", 3, Instant.parse("2026-09-30T10:00:00Z"));
    List<UUID> ids = unpublishedIds();
    OutboxRelay deadRelay =
        new OutboxRelay(
            store, new KafkaEventSender(deadBroker, Duration.ofSeconds(3)), Clock.systemUTC(), 50);

    RelayResult down = pass(deadRelay);

    assertThat(down.complete()).isFalse();
    assertThat(down.sent()).isZero();
    assertThat(store.countUnpublished()).isEqualTo(3);
    assertThat(onTopic(Topics.MARGIN_CALLS, ids)).isEmpty();

    RelayResult up = pass(relay);

    assertThat(up.complete()).isTrue();
    assertThat(up.sent()).isEqualTo(3);
    assertThat(store.countUnpublished()).isZero();
    List<ConsumerRecord<String, String>> arrived = onTopic(Topics.MARGIN_CALLS, ids);
    assertThat(arrived.stream().map(OutboxIT::messageId).toList())
        .containsExactlyElementsOf(ids.stream().map(UUID::toString).toList());
    OUTBOX
        .append("broker unreachable: relay pass claimed=")
        .append(down.claimed())
        .append(" sent=")
        .append(down.sent())
        .append(", unpublished=3, none on topic; broker back: sent=")
        .append(up.sent())
        .append(", unpublished=0, 3 on topic in append order for one key\n");
  }

  /** A store whose mark fails: the process died between the broker's ack and the commit. */
  private static final class CrashBeforeMark implements OutboxStore {
    private final OutboxStore real;

    CrashBeforeMark(OutboxStore real) {
      this.real = real;
    }

    @Override
    public void append(OutboxMessage message) {
      real.append(message);
    }

    @Override
    public List<OutboxMessage> claim(int limit) {
      return real.claim(limit);
    }

    @Override
    public void markPublished(Collection<UUID> ids, Instant at) {
      throw new IllegalStateException("the relay process died before its transaction committed");
    }

    @Override
    public long countUnpublished() {
      return real.countUnpublished();
    }

    @Override
    public Optional<Instant> oldestUnpublishedSince() {
      return real.oldestUnpublishedSince();
    }
  }

  @Test
  @DisplayName("a relay that dies after the ack and before the mark sends the same message twice")
  void reproducesRelayCrashRedelivers() {
    // See docs/decisions/10-messaging.md
    appendCalls("P-CRASH", 1, Instant.parse("2026-09-30T10:00:00Z"));
    UUID id = unpublishedIds().getFirst();
    OutboxRelay dying = new OutboxRelay(new CrashBeforeMark(store), sender, Clock.systemUTC(), 50);

    assertThatThrownBy(() -> pass(dying)).isInstanceOf(IllegalStateException.class);

    // Acknowledged by the broker, and still unpublished as far as the database knows.
    assertThat(onTopic(Topics.MARGIN_CALLS, List.of(id))).hasSize(1);
    assertThat(store.countUnpublished()).isEqualTo(1);

    pass(relay);

    List<ConsumerRecord<String, String>> copies = onTopic(Topics.MARGIN_CALLS, List.of(id));
    assertThat(copies).hasSize(2);
    assertThat(copies.stream().map(OutboxIT::messageId).distinct()).hasSize(1);
    assertThat(store.countUnpublished()).isZero();
    OUTBOX.append(
        "relay died after the ack, before the mark: after the next pass the topic holds 2 records with one messageId, unpublished=0 (at-least-once; the consumer's guard removes the duplicate, section 6)\n");
  }

  @Test
  @DisplayName("two relays running at once send every message exactly once between them")
  void twoRelaysSendEachOnce() throws Exception {
    int total = 40;
    for (int i = 0; i < total; i++) {
      appendCalls("P-MANY-" + i, 1, Instant.parse("2026-09-30T10:00:00Z").plusSeconds(i));
    }
    List<UUID> ids = unpublishedIds();
    OutboxRelay small = new OutboxRelay(store, sender, Clock.systemUTC(), 7);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Integer>> runs = new ArrayList<>();
      for (int r = 0; r < 2; r++) {
        runs.add(
            pool.submit(
                () -> {
                  int sent = 0;
                  RelayResult result;
                  do {
                    result = pass(small);
                    sent += result.sent();
                  } while (result.claimed() > 0 && result.complete());
                  return sent;
                }));
      }
      int sentA = runs.get(0).get();
      int sentB = runs.get(1).get();

      assertThat(sentA + sentB).isEqualTo(total);
      assertThat(store.countUnpublished()).isZero();
      List<String> onTopic =
          onTopic(Topics.MARGIN_CALLS, ids).stream().map(OutboxIT::messageId).toList();
      assertThat(onTopic).hasSize(total);
      assertThat(onTopic.stream().collect(Collectors.toSet())).hasSize(total);
      OUTBOX
          .append("two relays, batch 7, 40 rows: relay A sent=")
          .append(sentA)
          .append(", relay B sent=")
          .append(sentB)
          .append(", topic holds 40 records with 40 distinct ids, unpublished=0\n");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("a relay that is not running shows as the age of the oldest unpublished row")
  void deadRelayShowsAsAge() {
    Instant createdAt = Instant.parse("2026-09-30T10:00:00Z");
    appendCalls("P-AGE", 2, createdAt);
    Clock later = Clock.fixed(createdAt.plus(Duration.ofMinutes(5)).plusSeconds(1), ZoneOffset.UTC);

    Duration behind =
        Duration.between(store.oldestUnpublishedSince().orElseThrow(), later.instant());

    assertThat(store.countUnpublished()).isEqualTo(2);
    assertThat(behind).isEqualTo(Duration.ofMinutes(5).plusSeconds(1));
    OUTBOX
        .append("no relay running: unpublished=2, oldest unpublished is ")
        .append(behind)
        .append(" old on a controlled clock (stage 12 alerts on this)\n");
  }
}

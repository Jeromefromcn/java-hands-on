package com.jerome.slledger.adapter.persistence.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.LedgerDb;
import com.jerome.slledger.application.messaging.OutboxMessage;
import com.jerome.slledger.application.messaging.OutboxStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox table and its claim query, against a real Postgres. Two transactions are interleaved
 * by running the second inside the first, on another thread, while the first still holds its rows.
 */
@Timeout(60)
class JdbcOutboxStoreIT {

  private static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");
  private static final StringBuilder EVIDENCE = new StringBuilder();

  private static ConfigurableApplicationContext context;
  private static ExecutorService other;

  private OutboxStore store;
  private JdbcClient jdbc;
  private TransactionTemplate transaction;

  @BeforeAll
  static void start() {
    context = LedgerDb.start();
    other = Executors.newSingleThreadExecutor();
  }

  @AfterAll
  static void stop() {
    other.shutdownNow();
    context.close();
    Evidence.write("10-outbox-store.txt", EVIDENCE.toString());
  }

  @BeforeEach
  void setUp() {
    store = context.getBean(OutboxStore.class);
    jdbc = context.getBean(JdbcClient.class);
    transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    jdbc.sql("DELETE FROM outbox_event").update();
  }

  @AfterEach
  void clean() {
    jdbc.sql("DELETE FROM outbox_event").update();
  }

  private static OutboxMessage message(String key, int n) {
    return new OutboxMessage(
        UUID.randomUUID(), "t", key, "T", "{\"n\":" + n + "}", T0.plusSeconds(n));
  }

  private void append(OutboxMessage... messages) {
    transaction.executeWithoutResult(
        status -> {
          for (OutboxMessage message : messages) {
            store.append(message);
          }
        });
  }

  @Test
  @DisplayName("a row cannot be appended outside a transaction")
  void appendNeedsATransaction() {
    assertThatThrownBy(() -> store.append(message("K", 1)))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class)
        .hasMessageContaining("transaction");
    assertThat(store.countUnpublished()).isZero();
  }

  @Test
  @DisplayName("an appended row that is rolled back leaves nothing behind")
  void rolledBackAppendLeavesNothing() {
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      store.append(message("K", 1));
                      throw new IllegalStateException("the change failed");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(store.countUnpublished()).isZero();
  }

  @Test
  @DisplayName("claim returns unpublished rows oldest first, and mark takes them out")
  void claimAndMark() {
    OutboxMessage a = message("A", 1);
    OutboxMessage b = message("B", 2);
    OutboxMessage c = message("C", 3);
    append(a, b, c);

    List<OutboxMessage> claimed = transaction.execute(status -> store.claim(2));

    assertThat(claimed).extracting(OutboxMessage::id).containsExactly(a.id(), b.id());
    assertThat(claimed.getFirst().payload()).isEqualTo("{\"n\":1}");
    assertThat(claimed.getFirst().createdAt()).isEqualTo(a.createdAt());

    transaction.executeWithoutResult(status -> store.markPublished(List.of(a.id(), b.id()), T0));

    assertThat(store.countUnpublished()).isEqualTo(1);
    List<OutboxMessage> remaining = transaction.execute(status -> store.claim(10));
    assertThat(remaining).extracting(OutboxMessage::id).containsExactly(c.id());
  }

  @Test
  @DisplayName("the age of the outbox is the age of its oldest unpublished row")
  void oldestUnpublished() {
    assertThat(store.oldestUnpublishedSince()).isEmpty();
    OutboxMessage a = message("A", 5);
    OutboxMessage b = message("B", 9);
    append(a, b);

    assertThat(store.oldestUnpublishedSince()).contains(a.createdAt());

    transaction.executeWithoutResult(status -> store.markPublished(List.of(a.id()), T0));
    assertThat(store.oldestUnpublishedSince()).contains(b.createdAt());
  }

  @Test
  @DisplayName("two relays claim disjoint rows and neither waits for the other")
  void twoClaimersAreDisjoint() {
    OutboxMessage[] all = new OutboxMessage[6];
    for (int i = 0; i < all.length; i++) {
      all[i] = message("K" + i, i);
    }
    append(all);

    List<UUID> first =
        transaction.execute(
            status -> {
              List<OutboxMessage> mine = store.claim(3);
              // Still holding them: the second relay runs to completion now, on another thread.
              List<UUID> theirs = claimOnAnotherThread(3).stream().map(OutboxMessage::id).toList();
              assertThat(theirs)
                  .doesNotContainAnyElementsOf(mine.stream().map(OutboxMessage::id).toList());
              assertThat(theirs).hasSize(3);
              return mine.stream().map(OutboxMessage::id).toList();
            });

    assertThat(first).hasSize(3);
    EVIDENCE.append(
        "two relays, 6 rows, 6 distinct keys: relay A claimed 3, relay B claimed 3 while A held its rows, no overlap, no wait\n");
  }

  private List<OutboxMessage> claimOnAnotherThread(int limit) {
    try {
      return other.submit(() -> transaction.execute(status -> store.claim(limit))).get();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The claim without the per-key guard: what SKIP LOCKED alone does. Test-only. */
  private List<UUID> naiveClaim(int limit) {
    return jdbc.sql(
            """
            SELECT id FROM outbox_event WHERE published_at IS NULL
            ORDER BY seq LIMIT ? FOR UPDATE SKIP LOCKED
            """)
        .param(limit)
        .query((rs, row) -> rs.getObject("id", UUID.class))
        .list();
  }

  @Test
  @DisplayName("one key's backlog is handed out together, oldest first")
  void aKeysBacklogDrainsInOnePass() {
    OutboxMessage first = message("POSITION-1", 1);
    OutboxMessage second = message("POSITION-1", 2);
    OutboxMessage third = message("POSITION-1", 3);
    append(first, second, third);

    List<OutboxMessage> claimed = transaction.execute(status -> store.claim(10));

    assertThat(claimed)
        .extracting(OutboxMessage::id)
        .containsExactly(first.id(), second.id(), third.id());
  }

  @Test
  @DisplayName("a claim that cuts a key's backlog short takes the run from its start")
  void limitCutsAtTheOldest() {
    OutboxMessage first = message("POSITION-1", 1);
    OutboxMessage other = message("POSITION-2", 2);
    OutboxMessage second = message("POSITION-1", 3);
    append(first, other, second);

    List<OutboxMessage> claimed = transaction.execute(status -> store.claim(2));

    // The limit takes the two oldest rows overall: POSITION-1's first and POSITION-2's.
    assertThat(claimed).extracting(OutboxMessage::id).containsExactly(first.id(), other.id());
  }

  @Test
  @DisplayName("SKIP LOCKED alone hands out a key's newer message ahead of its older one")
  void reproducesSkipLockedReordersAKey() throws Exception {
    // See docs/decisions/10-messaging.md
    OutboxMessage older = message("POSITION-1", 1);
    OutboxMessage newer = message("POSITION-1", 2);
    OutboxMessage unrelated = message("POSITION-2", 3);
    append(older, newer, unrelated);

    // Relay A claims the older message and is still sending it. Relay B now asks.
    transaction.executeWithoutResult(
        status -> {
          assertThat(store.claim(1)).extracting(OutboxMessage::id).containsExactly(older.id());
          try {
            List<UUID> naive = other.submit(() -> transaction.execute(s -> naiveClaim(10))).get();
            List<UUID> guarded =
                other
                    .submit(
                        () ->
                            transaction.execute(
                                s -> store.claim(10).stream().map(OutboxMessage::id).toList()))
                    .get();

            // Without the guard B takes the newer message of the key A is still sending.
            assertThat(naive).contains(newer.id());
            // With it, B takes only the other key.
            assertThat(guarded).containsExactly(unrelated.id());
            EVIDENCE
                .append(
                    "SKIP LOCKED alone: relay A holds POSITION-1's older message; relay B claims [newer of POSITION-1, POSITION-2's] -> newer would be sent first\n")
                .append(
                    "with the NOT EXISTS guard: relay B claims [POSITION-2's] only; POSITION-1's newer waits for its older\n");
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
  }
}

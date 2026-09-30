package com.jerome.slledger.application.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxRelayTest {

  private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private static OutboxMessage message(String key, int n) {
    return new OutboxMessage(
        UUID.nameUUIDFromBytes(("m" + n).getBytes()), "t", key, "T", "{\"n\":" + n + "}", NOW);
  }

  /** Hands out its messages once and remembers what was marked. */
  private static final class Store implements OutboxStore {
    final List<OutboxMessage> pending = new ArrayList<>();
    final List<UUID> marked = new ArrayList<>();
    Instant markedAt;

    @Override
    public void append(OutboxMessage message) {
      pending.add(message);
    }

    @Override
    public List<OutboxMessage> claim(int limit) {
      return List.copyOf(pending.subList(0, Math.min(limit, pending.size())));
    }

    @Override
    public void markPublished(Collection<UUID> ids, Instant at) {
      marked.addAll(ids);
      markedAt = at;
    }

    @Override
    public long countUnpublished() {
      return pending.size() - marked.size();
    }

    @Override
    public Optional<Instant> oldestUnpublishedSince() {
      return Optional.empty();
    }
  }

  private static final class Sender implements EventSender {
    final List<UUID> sent = new ArrayList<>();
    final int failAt;

    Sender(int failAt) {
      this.failAt = failAt;
    }

    @Override
    public void send(OutboxMessage message) {
      if (sent.size() == failAt) {
        throw new EventSendException("no ack", new RuntimeException("broker away"));
      }
      sent.add(message.id());
    }
  }

  @Test
  @DisplayName("a pass sends the batch in order and marks every message it sent")
  void sendsAndMarks() {
    Store store = new Store();
    for (int i = 1; i <= 3; i++) {
      store.append(message("K", i));
    }
    Sender sender = new Sender(-1);

    RelayResult result = new OutboxRelay(store, sender, CLOCK, 10).relayBatch();

    assertThat(result.complete()).isTrue();
    assertThat(result.claimed()).isEqualTo(3);
    assertThat(result.sent()).isEqualTo(3);
    assertThat(sender.sent)
        .containsExactlyElementsOf(store.pending.stream().map(OutboxMessage::id).toList());
    assertThat(store.marked).isEqualTo(sender.sent);
    assertThat(store.markedAt).isEqualTo(NOW);
  }

  @Test
  @DisplayName("it stops at the first failure and marks only what was acknowledged before it")
  void stopsAtTheFirstFailure() {
    Store store = new Store();
    for (int i = 1; i <= 4; i++) {
      store.append(message("K", i));
    }
    Sender sender = new Sender(2);

    RelayResult result = new OutboxRelay(store, sender, CLOCK, 10).relayBatch();

    assertThat(result.complete()).isFalse();
    assertThat(result.failure()).hasMessageContaining("no ack");
    assertThat(result.sent()).isEqualTo(2);
    assertThat(store.marked).hasSize(2).isEqualTo(sender.sent);
    // The third and fourth were neither sent nor marked; the fourth was not tried after the third.
    assertThat(sender.sent).hasSize(2);
  }

  @Test
  @DisplayName("a failure on the first message marks nothing and touches the store no further")
  void firstMessageFails() {
    Store store = new Store();
    store.append(message("K", 1));

    RelayResult result = new OutboxRelay(store, new Sender(0), CLOCK, 10).relayBatch();

    assertThat(result.sent()).isZero();
    assertThat(store.marked).isEmpty();
    assertThat(store.markedAt).isNull();
  }

  @Test
  @DisplayName("an empty outbox is an empty pass")
  void nothingToDo() {
    RelayResult result = new OutboxRelay(new Store(), new Sender(-1), CLOCK, 10).relayBatch();

    assertThat(result.claimed()).isZero();
    assertThat(result.complete()).isTrue();
  }

  @Test
  @DisplayName("the batch size bounds a pass")
  void batchSizeBoundsAPass() {
    Store store = new Store();
    for (int i = 1; i <= 5; i++) {
      store.append(message("K", i));
    }

    RelayResult result = new OutboxRelay(store, new Sender(-1), CLOCK, 2).relayBatch();

    assertThat(result.claimed()).isEqualTo(2);
    assertThatThrownBy(() -> new OutboxRelay(store, new Sender(-1), CLOCK, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

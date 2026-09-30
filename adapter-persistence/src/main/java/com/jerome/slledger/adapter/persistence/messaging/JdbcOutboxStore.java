package com.jerome.slledger.adapter.persistence.messaging;

import com.jerome.slledger.application.messaging.OutboxMessage;
import com.jerome.slledger.application.messaging.OutboxStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The outbox in Postgres.
 *
 * <p>{@link #claim} is the interesting statement. {@code FOR UPDATE SKIP LOCKED} lets a second
 * relay pass over rows the first is holding instead of waiting for it, which is the point of
 * running two. On its own it breaks per-key order: relay A holds the older message of a key while
 * it sends, relay B skips it, takes the newer message of the same key and sends that first.
 *
 * <p>So a second step keeps, for each key, only the run of messages that starts at the key's oldest
 * unpublished message. A message whose older sibling is unpublished and not in this claim (held by
 * another relay, or beyond the limit) is not handed out; its lock is released with the transaction,
 * a moment later. A plain {@code NOT EXISTS} in the claim would also be correct and is simpler, but
 * it hands out one message per key per pass: a key with a backlog of N would take N passes to
 * drain.
 */
@Repository
public class JdbcOutboxStore implements OutboxStore {

  static final String CLAIM =
      """
      SELECT id, seq, topic, message_key, type, payload, created_at
      FROM outbox_event
      WHERE published_at IS NULL
      ORDER BY seq
      LIMIT ?
      FOR UPDATE SKIP LOCKED
      """;

  private record Row(long seq, OutboxMessage message) {}

  private final JdbcClient jdbc;

  JdbcOutboxStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void append(OutboxMessage message) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "An outbox row must be written in the transaction of the change it describes");
    }
    jdbc.sql(
            "INSERT INTO outbox_event (id, topic, message_key, type, payload, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?)")
        .params(
            message.id(),
            message.topic(),
            message.key(),
            message.type(),
            message.payload(),
            Timestamp.from(message.createdAt()))
        .update();
  }

  @Override
  public List<OutboxMessage> claim(int limit) {
    List<Row> locked =
        jdbc.sql(CLAIM)
            .param(limit)
            .query(
                (rs, row) ->
                    new Row(
                        rs.getLong("seq"),
                        new OutboxMessage(
                            rs.getObject("id", UUID.class),
                            rs.getString("topic"),
                            rs.getString("message_key"),
                            rs.getString("type"),
                            rs.getString("payload"),
                            rs.getTimestamp("created_at").toInstant())))
            .list();
    if (locked.isEmpty()) {
      return List.of();
    }

    Set<String> keys = new HashSet<>();
    Set<Long> mine = new HashSet<>();
    for (Row row : locked) {
      keys.add(row.message().key());
      mine.add(row.seq());
    }
    // Every unpublished message of these keys, whoever holds it, oldest first.
    Map<String, List<Long>> unpublished = new HashMap<>();
    jdbc.sql(
            "SELECT message_key, seq FROM outbox_event"
                + " WHERE published_at IS NULL AND message_key IN (:keys) ORDER BY seq")
        .param("keys", keys)
        .query(
            rs -> {
              unpublished
                  .computeIfAbsent(rs.getString("message_key"), k -> new ArrayList<>())
                  .add(rs.getLong("seq"));
            });

    // Per key, the run of messages this claim holds that starts at the key's oldest unpublished
    // one.
    Set<Long> sendable = new HashSet<>();
    unpublished.forEach(
        (key, seqs) -> {
          for (long seq : seqs) {
            if (!mine.contains(seq)) {
              break;
            }
            sendable.add(seq);
          }
        });
    return locked.stream().filter(row -> sendable.contains(row.seq())).map(Row::message).toList();
  }

  @Override
  public void markPublished(Collection<UUID> ids, Instant at) {
    if (ids.isEmpty()) {
      return;
    }
    jdbc.sql("UPDATE outbox_event SET published_at = :at WHERE id IN (:ids)")
        .param("at", Timestamp.from(at))
        .param("ids", ids)
        .update();
  }

  @Override
  public long countUnpublished() {
    return jdbc.sql("SELECT count(*) FROM outbox_event WHERE published_at IS NULL")
        .query(Long.class)
        .single();
  }

  @Override
  public Optional<Instant> oldestUnpublishedSince() {
    return jdbc.sql("SELECT min(created_at) FROM outbox_event WHERE published_at IS NULL")
        .query(Timestamp.class)
        .optional()
        .map(Timestamp::toInstant);
  }
}

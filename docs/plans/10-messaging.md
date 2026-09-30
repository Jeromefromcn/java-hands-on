# Stage 10 — Messaging · Implementation Plan

Spec: [`docs/specs/10-messaging.md`](../specs/10-messaging.md)

One section per commit, in order. Implement a section, tick its checkbox in
the same commit, then commit. `./mvnw clean verify` at the repository root
after every section that touches the main build.

## Ground rules for every section

- Packages:
  - `com.jerome.slledger.application.messaging` for events, ports, the outbox,
    the relay, the idempotent consumer, price apply, revaluation and the
    notifier; `application.collateral` for the TCC ports and use cases;
    `application.saga` for the orchestrator;
  - `com.jerome.slledger.adapter.persistence.messaging`, `.collateral`,
    `.saga` for JDBC adapters;
  - `com.jerome.slledger.adapter.messaging` for everything that touches a
    Kafka type;
  - `com.jerome.slledger.bootstrap.config` for `ledger.messaging.*`.
- Pure unit tests end in `Test`; anything needing Docker ends in `IT`. Kafka
  comes from `SharedKafka` (one `apache/kafka` container per test JVM), a test
  gives itself topic names with its own prefix, so no test reads another's
  messages.
- A test that asserts wrong behaviour is named `reproduces<Failure>` and
  carries `// See docs/decisions/10-messaging.md`.
- Time-dependent logic takes a `Clock`. Where elapsed real time is the
  subject (a poll interval, a lag), the test says so in a comment and has
  `@Timeout`.
- Evidence is written by tests to `<module>/target/evidence/10-*.txt` and
  copied by hand to `docs/evidence/`.
- The main build runs on the JDK `.sdkmanrc` pins (21). Test data is
  synthetic; ISINs sent over HTTP carry a valid check digit.
- Full `./mvnw clean verify` after every section, not a filtered run.

## Decisions made while planning

Each is carried to the decision record.

1. **The broker in tests is `apache/kafka:4.1.0` in KRaft mode**, through
   Testcontainers' `KafkaContainer`. One container per JVM. Compose uses the
   same image.
2. **Event ids are random UUIDs.** A deterministic id (a hash of position and
   price version) was considered: it would make the outbox itself
   idempotent. Rejected for now; the version gate already gives price updates
   that, and a random id keeps one meaning for `messageId`.
3. **`Clock` is injected wherever a date or an age matters.** A bean is
   provided in `bootstrap`; use cases take it as a constructor argument.
4. **Ack mode is `RECORD`.** The effect of one record is one database
   transaction, so committing an offset per record costs one broker round trip
   per transaction, and a crash re-delivers at most one record.
5. **The relay holds its database transaction across the send.** The batch is
   small and the send has a bounded timeout, so the connection is held for at
   most `batch × send timeout` in the worst case. The alternative (claim, mark
   "in flight", send, then mark published) needs a second state and a
   sweeper for rows stuck in flight; the record says where the first breaks.
6. **A notice is sent inside the consumer's transaction.** An external send
   followed by a rollback sends without recording: the guard covers
   redelivery, not that. Stated, not fixed.
7. **The saga is synchronous and in-process.** Participants are ports; the log
   is a table. There is no message hop between saga and participants, because
   the exercise is the compensation path, and a hop adds only the failures
   this stage already reproduces.
8. **A revaluation loop that cannot value one position skips it and counts
   it.** The price is a fact and must not be blocked by one position's
   collateral currency.

## Review focus

Failure modes the spec implies but no exercise is *about*. Each is pinned by a
test in the section named.

1. **A `Money` on a topic never loses digits or a currency** (sections 3, 5):
   round trip in `HKD`, `JPY`, `BHD` at 16 integer digits; an over-precise
   amount and an unknown currency are refused as non-retryable.
2. **A price update in a currency other than the security's** is refused, not
   stored (section 5).
3. **The outbox row and the position commit or roll back together** (section
   4), including when the use case throws after appending.
4. **The relay never marks a row it did not send** (section 4).
5. **A cancelled TCC transaction is never resurrected by a late try** (section
   9).
6. **A saga whose compensation itself fails** is left in a state recovery can
   finish, and the failure is logged, not swallowed (section 10).
7. **No task, thread or connection exists with messaging off** (section 12).

---

## [ ] 1 · Build wiring and Kafka test support

**POMs and modules**
- New module `adapter-messaging` (parent's `<modules>` before `batch`; its
  dependency entry in `dependencyManagement`; `bootstrap` depends on it).
  Compile: `application`, `spring-kafka`, `spring-boot-starter-json`. Test:
  `spring-boot-starter-test`, `testcontainers-kafka`. `adapter-persistence`
  gains `spring-boot-starter-jdbc`'s already-present `JdbcClient`; nothing new.
- `dependencyConvergence` conflicts resolved by managed entries with a
  one-line reason each; the resolutions go to
  `docs/evidence/10-dependency-convergence.txt`.
- `CLAUDE.md`'s module list gains `adapter-messaging`.

**Test support (`adapter-messaging` test-jar)**
- `SharedKafka`: one `apache/kafka:4.1.0` per JVM; `bootstrapServers()`,
  `createTopic(name, partitions)`, `consume(topic)` helper returning all
  records now on a topic, `Evidence`.

**Infra**
- `infra/compose.yaml`: single-node Kafka, loopback only.

**Tests**
- `SharedKafkaIT`: a produce/consume round trip on a fresh topic.
- `ArchitectureIT`: `application` has no Kafka, Jackson or JDBC-driver type;
  `adapter-messaging` and `adapter-persistence` do not depend on each other.

**Acceptance**
- `./mvnw clean verify` is green; the enforcer still refuses a third-party
  dependency in `application`.

---

## [ ] 2 · In-process events and their limits

**`application.event`**
- `DomainEvents` port: `publish(Object)`. `bootstrap` gets a
  `SpringDomainEvents` over `ApplicationEventPublisher`.

**Test beans (`bootstrap/src/test/.../demo/events`)**
- `Booking` writes a row and publishes; three listeners: plain
  `@EventListener`, `@TransactionalEventListener(AFTER_COMMIT)`, and an
  AFTER_COMMIT listener that writes (with and without `REQUIRES_NEW`).

**Tests**
- Plain listener runs inside the transaction: it is told about a booking that
  then rolls back (`reproducesPhantomEvent`).
- AFTER_COMMIT is not called on rollback, is called once on commit, and its
  read from another connection sees the row.
- `reproducesAfterCommitWriteIsNotCommitted`: the listener's insert is not
  in the table; with `REQUIRES_NEW` it is.
- `reproducesAfterCommitEventLost`: the listener throws; the booking stands,
  the caller gets no error, and no record exists that an event was owed.

**Acceptance**
- Evidence `10-after-commit.txt`. Full verify green.

---

## [ ] 3 · Events, the JSON codec and the Kafka producer

**`application.messaging`**
- Sealed `IntegrationEvent` (`PriceUpdated`, `MarginCallRaised`,
  `PositionOpened`), each with an id, an `occurredAt`, a topic and a key.
  `Topics` holds the names.
- Ports: `EventCodec` (`encode`, `decode(type, payload)`, a
  `MessageDecodeException` that is a non-retryable failure), `EventSender`
  (`send(OutboxMessage)`, blocking on the ack), `PriceUpdatePublisher`.

**`adapter-messaging`**
- `JsonEventCodec`: `Money` as a string amount and a currency code, refusing an
  over-precise amount and an unknown currency; unknown fields are refused so a
  producer change is loud.
- `KafkaEventSender`, `KafkaPriceUpdatePublisher`: idempotent producer,
  `acks=all`, `delivery.timeout.ms` and `max.block.ms` bounded by properties.

**Tests**
- Codec round trip for the three events in `HKD`, `JPY`, `BHD` at 16 integer
  digits; the two refusals; a payload with a JSON number amount is refused.
- `PartitionKeyIT`: the same ISIN always lands on one partition; 1,000 ISINs
  spread over 3; `reproducesRandomKeySplitsOneSecurity`: with a random key the
  same ISIN lands on several partitions.

**Acceptance**
- Evidence `10-partition-key.txt`. Full verify green.

---

## [ ] 4 · The outbox and the two dual-write failures

**Migration V7**: `outbox_event`.

**`application.messaging`**
- `OutboxStore` port: `append`, `claim(limit)` (`FOR UPDATE SKIP LOCKED`, by
  `seq`), `markPublished(ids, at)`, `oldestUnpublishedAge`, `countUnpublished`.
- `Outbox`: encodes an `IntegrationEvent` and appends it. `OutboxRelay`
  (`@Transactional relayBatch()`): claim, send in order, stop at the first
  failure, mark what went.
- `OpenPosition` appends `PositionOpened` (optional collaborator: a
  constructor overload keeps every earlier call site as it was).

**`adapter-persistence`**: `JdbcOutboxStore`.

**Tests**
- `reproducesDualWriteLosesTheEvent`: commit, then send to an unreachable
  broker; the position is there, the message is not, nothing records it.
- `reproducesPhantomMessageBeforeCommit`: send, then roll back: a message for
  a position that does not exist.
- The outbox row and the position commit or roll back together, including a
  throw after the append.
- Broker unreachable: rows accumulate, the relay marks none; broker reachable:
  all are delivered in order per key and marked.
- `reproducesRelayCrashRedelivers`: the mark fails after the send; the next
  relay sends again; two messages with one id are on the topic.
- Two relays concurrently claim disjoint rows; total delivered equals total
  appended.
- `oldestUnpublishedAge` reads the age on a controlled clock.

**Acceptance**
- Evidence `10-dual-write.txt`, `10-outbox.txt`. Full verify green.

---

## [ ] 5 · Applying a price update, and ordering

**Migration V6**: `security.price_version bigint not null default 0`.

**`application.messaging`**
- Port `SecurityPrices.applyIfNewer(isin, Money, version)` returning whether it
  applied; `UnknownReferenceException` for an unknown ISIN, the domain's
  currency mismatch for a wrong currency. Port `SecurityPositions.openOn(isin)`.
- `ApplyPriceUpdate` (`@Transactional`): apply; if applied, revalue, append a
  `MarginCallRaised` for each shortfall, and publish `PriceApplied` through
  `DomainEvents`.

**`adapter-persistence`**: `JdbcSecurityPrices`, `JpaSecurityPositions`.

**`adapter-messaging`**: `PriceUpdateListener` (a plain method a container
calls; container wiring in section 12).

**Tests**
- Apply: newer applies; equal and older are ignored; unknown ISIN and wrong
  currency are refused; a shortfall appends one outbox row per position, none
  when covered; a position in another currency is skipped and counted.
- `reproducesOldPriceOverwritesNew`: a last-write-wins applier handed v2 then
  v1 (through Kafka, two partitions, v1's partition paused) ends on v1's price.
- Fixed by the key: both updates under the ISIN key share a partition, so
  arrival order is send order. Fixed by the version: v2 then v1 ends on v2's
  price.

**Acceptance**
- Evidence `10-ordering.txt`. Full verify green.

---

## [ ] 6 · The idempotent consumer, the notifier and the dedup TTL

**Migration V8**: `consumed_message(consumer, message_id, consumed_at)`.

**`application.messaging`**
- `ConsumedMessages` port: `firstTime(consumer, id, at)`, `purgeBefore(cutoff)`,
  `count()`. `IdempotentConsumer.once(...)` (`@Transactional`).
- `NoticeChannel` port; `MarginCallNotifier` (decode already done): builds a
  notice and sends it through the guard. `DedupPurge` computes the cutoff from
  the TTL and a clock.

**`adapter-persistence`**: `JdbcConsumedMessages`.

**Tests**
- `reproducesDuplicateNotice`: unguarded, one message twice sends two;
  guarded, once. The effect rolls back with the guard row when it throws, and
  the redelivery then sends.
- `reproducesUnboundedDedupTable`: N inserts with no purge; row count and
  `pg_total_relation_size` recorded, projected 100×.
- `reproducesDuplicateAfterTtl`: purge with a TTL shorter than the redelivery
  gap; the duplicate sends twice; with `ttl > retention` it does not.
- Properties validation: `dedup-ttl` must exceed `topic-retention`.

**Acceptance**
- Evidence `10-dedup-ttl.txt`. Full verify green.

---

## [ ] 7 · Offset commit

**`adapter-messaging`** test-side harness with raw `KafkaConsumer`s.

**Tests**
- `reproducesLossWhenOffsetCommittedBeforeProcessing`: commit, effect throws,
  a new consumer in the group receives nothing.
- The same with `enable.auto.commit=true`: the next `poll` commits the offsets
  of records the application failed to handle.
- `reproducesDuplicateWhenProcessedButNotCommitted`: effect runs, consumer
  closes without committing, a new consumer receives the record again; with
  the guard from section 6 the effect runs once.
- The product listener container config: `enable.auto.commit=false`, ack mode
  `RECORD`; asserted from the effective consumer properties.

**Acceptance**
- Evidence `10-offset-commit.txt`. Full verify green.

---

## [ ] 8 · Retry and dead letter

**`adapter-messaging`**
- `ErrorHandling.build(KafkaOperations, RetryProperties)`: `DefaultErrorHandler`
  with `ExponentialBackOffWithMaxRetries`, `DeadLetterPublishingRecoverer`
  to `<topic>.DLT`, non-retryable classes registered
  (`MessageDecodeException`, `DomainException`, `UnknownReferenceException`).

**Tests** (a real listener container over `SharedKafka`)
- A poison message reaches the DLT after 0 retries with the exception in its
  headers.
- A handler that fails twice with a transient exception is called 3 times and
  the message is not dead-lettered; the elapsed back-off is recorded.
- One that never succeeds is dead-lettered after the bound.
- A dead-lettered message does not stop the record behind it.

**Acceptance**
- Evidence `10-retry-dlt.txt`. Full verify green.

---

## [ ] 9 · TCC on collateral, the overdraft and the prepared-transaction block

**Migration V9**: `collateral_account`, `tcc_transaction`.

**`application.collateral`**: `CollateralHolds` port (`tryFreeze`, `confirm`,
`cancel`), `CollateralRefusedException`.

**`adapter-persistence`**: `JdbcCollateralHolds`.

**Tests**
- Test-only `NaiveCollateralHolds`. `reproducesEmptyRollback`,
  `reproducesSuspension`, `reproducesDoubleConfirm`, each against the naive
  participant, each followed by the same script against the product one.
- Product: try/confirm/cancel/confirm-again/cancel-again leave the account
  correct; a confirm after a cancel and a cancel after a confirm are refused;
  a try with too little available is refused and leaves nothing.
- `reproducesOverdraftWithBalanceMinusX`: 32 threads each take 10 of a balance
  of 100; the unguarded form ends negative, the guarded ends at 0 with 10
  successes.
- `reproducesPreparedTransactionBlocksWriters` on a Postgres with
  `max_prepared_transactions`.

**Acceptance**
- Evidence `10-tcc.txt`, `10-collateral-update.txt`, `10-2pc-blocking.txt`.
  Full verify green.

---

## [ ] 10 · The open-position saga

**Migration V10**: `credit_reservation`, `saga_log`.

**`application.saga`**: `SagaLog` port, `CreditReservations` port,
`OpenPositionSaga`, `SagaRecovery`. A `FailurePoint` hook (production no-op) for
injecting a crash.

**Tests**
- Happy path: position, `PositionOpened` in the outbox, hold confirmed,
  reservation consumed, log terminal.
- A failure injected at each step, with the asserted end state (spec).
- A compensation that itself fails leaves the saga recoverable.
- Recovery of both crash points, before and after the pivot.
- Two sagas racing for the last of a credit limit: one wins, one is refused.

**Acceptance**
- Evidence `10-saga.txt`. Full verify green.

---

## [ ] 11 · Backpressure and being thrown out of the group

**Tests** (`adapter-messaging`, real time is the subject and says so)
- A producer faster than a consumer: lag sampled at intervals, growth
  recorded.
- `reproducesKickedOutForSlowProcessing`: `max.poll.interval.ms` 3 s, a handler
  that takes 5 s; a `ConsumerRebalanceListener` sees the revocation and the
  next commit fails. With the handler under the interval it does not.
- The measured worst per-record time against `max.poll.records × time <
  interval`.

**Acceptance**
- Evidence `10-backpressure.txt`. Full verify green.

---

## [ ] 12 · Wiring, end-to-end, and the manual run

**`bootstrap`**
- `MessagingProperties` (`ledger.messaging.*`), `MessagingConfiguration`
  conditional on `enabled`: topics, listener containers, relay and purge
  schedulers, the bridge from `PriceApplied` to cache eviction after commit.
- `local` profile enables it; `application-local.yml` points at compose.

**Tests**
- `MessagingDisabledIT`: no Kafka listener container, no scheduler, no relay.
- `MessagingEndToEndIT`: open a position over HTTP, the relay publishes
  `PositionOpened`; a price update on the topic revalues, the outbox
  publishes `MarginCallRaised`, the notifier sends one notice; the same
  message published twice still sends one; cache entries for the ISIN are
  evicted after the commit.

**Acceptance**
- Evidence `10-manual-run.txt` from the compose stack. Full verify green.

---

## [ ] 13 · Lab: consumer group under SIGKILL

**`labs/10-messaging`** (own POM, not a module): three consumer processes on six
partitions; graceful close and `SIGKILL`; eager and cooperative assignors; the
longest gap for survivors and for orphaned partitions.

**Acceptance**
- Evidence `10-rebalance.txt`. The main build does not build the lab.

---

## [ ] 14 · Decision records, roadmap and README

- `docs/decisions/10-messaging.md`, `docs/decisions/10-collateral-consistency.md`.
- Roadmap: stage 10 ticked, its exercises checked; a reasoned-not-measured
  claim says so.
- `README.md` stage table.

**Acceptance**
- Every number in the records traces to an evidence file. `./mvnw clean
  verify` green.

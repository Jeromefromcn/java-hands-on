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

## [x] 1 · Build wiring and Kafka test support

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

## [x] 2 · In-process events and their limits

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
- `afterCommitListenerHasNoTransactionOfItsOwn`: the listener's write reports
  the finished booking's transaction name; with `REQUIRES_NEW` it reports its
  own. (Planned as "the write is not committed": see below.)
- `reproducesAfterCommitEventLost`: the listener throws; the booking stands,
  the caller gets no error, and no record exists that an event was owed.

**Found while implementing**
- The planned reproduction ("a write from an AFTER_COMMIT listener is not
  committed") does not reproduce on this stack. The listener's JDBC insert ran
  on the finished transaction's connection, still `autoCommit=false`, and
  reached the table anyway: pgjdbc commits an open transaction when the pool
  resets autocommit at cleanup. Kept as an honest finding: the observable
  difference is the transaction name, and a JPA write in the same place would
  have needed a flush nobody issues (reasoned, not reproduced: no JPA entity
  is written from a listener in this stage). The test asserts what was
  observed.
- A listener that throws after the commit does **not** reach the caller: the
  `book` call returned normally, so a caller cannot even know the event was
  lost. Stronger than the plan assumed.
- A bean with a `@Transactional` method is a CGLIB proxy, and a public field
  read from the proxy is `null`. The demo listener keeps its transactional
  method in a separate bean for that reason.

**Acceptance**
- Evidence `10-after-commit.txt`. Full verify green.

---

## [x] 3 · Events, the JSON codec and the Kafka producer

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

**Found while implementing**
- `PublishPriceUpdate` is a small class over `EventCodec` and `EventSender`,
  not a `PriceUpdatePublisher` port: with the codec and the sender already
  ports, a third port would only forward. The spec's port list is otherwise
  as planned.
- Every exception in `application` needs a `serialVersionUID`: the build is
  `-Xlint:all -Werror`.

**Acceptance**
- Evidence `10-partition-key.txt`. Full verify green.

---

## [x] 4 · The outbox and the two dual-write failures

**Migration V6**: `outbox_event`.

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

**Found while implementing**
- Migrations are numbered in the order they are written, so the outbox is V6
  and `price_version` V7 (the spec and this plan had them the other way
  round). Flyway refuses an unapplied migration numbered below an applied one.
- The claim as planned, `SKIP LOCKED` plus `NOT EXISTS (older unpublished row
  of the same key)`, kept per-key order but handed out one message per key
  per pass: three events for one position took three passes (found by
  `brokerOutageThenRecovery`, which expected all three). Replaced by
  `SKIP LOCKED`, then a second query that keeps, per key, the run of claimed
  rows that starts at the key's oldest unpublished row. Same guarantee, and a
  key's backlog drains in one pass. The `NOT EXISTS` form is described in the
  class comment as the simpler alternative and why it was not kept.
- `@Repository` translates exceptions: an `IllegalStateException` thrown by
  the store came out as `InvalidDataAccessApiUsageException`. The store throws
  `IllegalTransactionStateException` (as `JdbcWriteFence` does), which is not
  translated.
- `OpenPosition` gained a second constructor rather than a changed one, so no
  existing call site moved. `LedgerConfiguration` provides a `Clock` bean and
  passes `Outbox.NONE` when messaging is off.
- The first cut of `MessagingProperties` and `MessagingConfiguration` is here,
  not in section 12: the outbox tests need a running relay and sender. Section
  12 adds the listeners, schedulers and purge to them.
- Tests share the well-known topic names on one broker, so they filter by
  message id instead of counting a topic's contents.

**Acceptance**
- Evidence `10-dual-write.txt`, `10-outbox.txt`. Full verify green.

---

## [x] 5 · Applying a price update, and ordering

**Migration V7**: `security.price_version bigint not null default 0`.

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

**Found while implementing**
- `ApplyPriceUpdate` returns a `PriceApplication` (applied, calls raised,
  positions skipped) and does not log: `application` has no logging
  dependency, and the counts are what a caller or a test can act on.
- The `PriceApplied` bridge, `PriceCacheEviction`, is in `bootstrap` and only
  exists when the cache is on (`@ConditionalOnBean(CachedQuoteSources)`);
  its test is in section 12, where a cache and a broker run together.
- The wrong order is made by pausing the partition that holds v1 (a consumer
  with a backlog there), not by racing two producers. It is deterministic and
  it is what the failure looks like from outside. The key fix is shown with
  the *naive* consumer, to isolate what the key alone buys.
- `JpaSecurityPositions` reads every open position in one query. Named in the
  use case's class comment as the limit stage 11's batch revaluation removes.

**Acceptance**
- Evidence `10-ordering.txt`. Full verify green.

---

## [x] 6 · The idempotent consumer, the notifier and the dedup TTL

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

**Found while implementing**
- `reproducesUnboundedDedupTable` is tagged `measurement` (1,002,000 rows) and
  runs under `-Pmeasure`; the other three tests in that class run in the
  normal build. 2,000 of the rows go through `IdempotentConsumer.once`, one
  transaction each, for the per-message cost; the rest are bulk-inserted, and
  the evidence file says which is which. The 100x figure is arithmetic on the
  measured bytes per row and is labelled so.
- The default `dedup-ttl` is 8 days against a 7-day `topic-retention`; the
  properties class refuses a TTL that does not exceed the retention.

**Acceptance**
- Evidence `10-dedup-ttl.txt`. Full verify green.

---

## [x] 7 · Offset commit

**`adapter-messaging`**: `KafkaConsumers`, the one container configuration
(manual commit, `AckMode.RECORD`, earliest reset, bounded poll settings).
The reproductions use raw `KafkaConsumer`s in `bootstrap`'s tests, because the
guard they end with needs the database.

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

**Found while implementing**
- The tests live in `bootstrap`, not `adapter-messaging`: the "with the guard"
  half needs Postgres, and `adapter-messaging` may not depend on
  `adapter-persistence`.
- Both loss reproductions wait a bounded 6 s to observe that nothing arrives:
  absence can only be seen by waiting, and the test says so.
- `KafkaConsumers.Settings` carries `maxPollRecords`, `maxPollInterval` and
  `sessionTimeout` already; section 11 measures what they bound.

**Acceptance**
- Evidence `10-offset-commit.txt`. Full verify green.

---

## [x] 8 · Retry and dead letter

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

**Found while implementing**
- The dead-letter headers name Spring's wrapper, not the cause:
  `kafka_dlt-exception-fqcn` is `ListenerExecutionFailedException`, and the
  real class is in `kafka_dlt-exception-cause-fqcn`. An operator reading the
  DLT wants the second; the original offset is an 8-byte long, not text.
- The retry pauses were **not** honoured as configured: with 100 ms doubling,
  the measured gaps were 106, 505 and 507 ms (not 100, 200, 400). The first
  is right and the rest sit at about half a second. The cause was not found
  (a pause shorter than the container's own cycle is the suspect; changing
  `pollTimeout` did not move it). The default first interval is therefore
  500 ms, where the pauses are longer than whatever is quantising them, and
  the record says the sub-second numbers are approximate.
- `DefaultErrorHandler` retries block the partition; that is chosen, not a
  default to be apologised for: retry topics would let a later price overtake
  the one being retried.

**Acceptance**
- Evidence `10-retry-dlt.txt`. Full verify green.

---

## [x] 9 · TCC on collateral, the overdraft and the prepared-transaction block

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

**Found while implementing**
- The naive participant runs against a table with no CHECKs
  (`naive_account`), because the product table's `CHECK (frozen >= 0)` turns
  the corruption into an exception, which is a fine outcome and a poor
  reproduction. The product CHECKs are stated as the last line of defence.
- One naive Cancel is not enough: the *unguarded* Cancel shows the empty
  rollback (money created); the usual patch (`WHERE frozen >= x`) removes
  that and shows the other two (suspension, and stealing another hold's
  funds). Three reproductions became four for that reason.
- The product's refusal relies on the caller's transaction rolling back (the
  Try inserted its `tcc_transaction` row before the balance update failed);
  the test asserts that no row survives and the same id can be tried again.
- The overdraft is made deterministic with a barrier that releases all 32
  requests after each has read the balance, so it is the check-then-act gap
  itself that is reproduced, not a lucky interleaving. Three variants: read,
  check, write the computed value (lost update: all 32 report success, one
  withdrawal is in the balance); check then `balance - 10` (goes to -220);
  one conditional statement (exactly 10 succeed).
- The 2PC block uses Postgres's own `PREPARE TRANSACTION` on a container
  started with `max_prepared_transactions`. Nothing implements 2PC.

**Acceptance**
- Evidence `10-tcc.txt`, `10-collateral-update.txt`, `10-2pc-blocking.txt`.
  Full verify green.

---

## [x] 10 · The open-position saga

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

**Found while implementing**
- **The saga id is the command's idempotency key.** It makes a replay of a
  finished saga a lookup, and lets recovery ask the ledger (`FindBookedTrade`)
  whether the position exists, which is the one fact the log can be a step
  behind on (crash after the pivot committed, before its DONE entry).
- **Compensation does not consult the log.** Both compensations are
  idempotent and safe on a step that never ran, so recovery compensates
  everything. That is what makes the 12 crash points one code path.
- The saga log refuses to be written inside a transaction: an entry that rolls
  back with its step records nothing. Enforced in `JdbcSagaLog` and tested.
- Adding `collateral_account -> counterparty` as a foreign key broke stage 6's
  `MoneyColumnsRoundTripIT`, whose `TRUNCATE` did not name the new tables
  (found by the full verify of section 9, not by the section's own tests).
  That test's truncate list now names them.
- `credit_reservation` follows the hold's rule (release-before-reserve leaves a
  mark; reserve-after-release is refused) and reserving locks the
  counterparty row, so two sagas cannot each be promised the last of a limit.
  A plain `OpenPosition` still has stage 8's unlocked check; the saga is the
  path that closes it.

**Acceptance**
- Evidence `10-saga.txt`. Full verify green.

---

## [x] 11 · Backpressure and being thrown out of the group

**Tests** (`adapter-messaging`, real time is the subject and says so)
- A producer faster than a consumer: lag sampled at intervals, growth
  recorded.
- `reproducesKickedOutForSlowProcessing`: `max.poll.interval.ms` 3 s, a handler
  that takes 5 s; a `ConsumerRebalanceListener` sees the revocation and the
  next commit fails. With the handler under the interval it does not.
- The measured worst per-record time against `max.poll.records × time <
  interval`.

**Found while implementing**
- The eviction test uses raw consumers with explicit sleeps as the "slow
  database": the product container would need the handler to actually be slow,
  and the raw consumer exposes the two facts asserted (the failed commit, the
  restart at offset 0) directly. Three configurations are run, not two, so
  both knobs are shown: fewer records per poll and faster records each keep
  the batch under the interval.
- The lag experiment measures about 62 records/s of growth against a producer
  of about 100/s and a consumer of about 40/s (the consumer's `sleep(20)` is
  not the whole per-record cost); the numbers are in the evidence file, not
  the ones the arithmetic in the plan would give.

**Acceptance**
- Evidence `10-backpressure.txt`. Full verify green.

---

## [x] 12 · Wiring, end-to-end, and the manual run

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

**Found while implementing**
- `MessagingLedger` (the test helper) starts with the relay scheduler **and the
  consumers off**: the shared topics carry every other test's history, and a
  consumer starting from the earliest offset would replay it into a database
  it does not belong to. Only `MessagingEndToEndIT` turns them on, and asserts
  about its own position only. A `--key=value` given twice on the command line
  is a *list*, not an override (`false,true` failed to bind), so the helper
  merges arguments by key.
- The scheduler is one `SmartLifecycle` bean with a daemon thread pool named
  `sl-messaging-scheduler`, not `@EnableScheduling`: that annotation is
  process-wide, this exists only when messaging does. `MessagingDisabledIT`
  asserts the thread is absent.
- The manual run also proved the migration path: V6–V10 applied at start-up on
  a database that already had V1–V5 from stage 9's volume.
- The saga is not scheduled: nothing in the product starts one yet (HTTP is
  unchanged), so there is nothing for a recovery timer to find. Recorded in
  the decision record as the first thing to wire when the saga gets a caller.
- Cache eviction after commit is tested in `PriceUpdateEvictsCacheIT`, which
  needs the stub price source, Redis and Kafka together; its rollback case
  first failed because the previous test's L1 entry was still there (Redis had
  been flushed, Caffeine had not).

**Acceptance**
- Evidence `10-manual-run.txt` from the compose stack, `10-end-to-end.txt`.
  Full verify green.

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

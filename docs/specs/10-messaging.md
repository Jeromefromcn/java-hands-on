# Stage 10 — Messaging

## Problem

Stage 9 made reads cheap and left every write path alone. Two things in the
ledger still change state and tell nobody: a position is opened and no other
system hears of it, and a price moves and no position is revalued. Stage 5
already found the seam: a callback registered on the transaction loses its
event if the process dies after the commit, and said the fix was stage 10's.

Stage 10 puts events on the ledger: a price update arrives on Kafka and is
applied; a revaluation that finds a shortfall raises a margin call; opening a
position announces itself. Then it drives every failure a message bus brings to
an asserted or measured result: an event sent before its transaction commits,
two updates arriving in the wrong order, one message delivered twice, an
offset committed too early or too late, a poison message, a database write and
a send that disagree, a multi-step flow that fails halfway, a resource that is
frozen by a message that arrives after its own cancellation, a consumer thrown
out of its group, and a lag that only grows.

Five facts shape the stage.

- **Messaging moves the money invariant to a fourth boundary.** A price and a
  shortfall leave the JVM inside a JSON document on a topic. `Money` crosses
  as a string amount and a currency code, and an over-precise amount or an
  unknown currency is refused on read: stage 9's Redis rule, applied again.
- **Not every event is an outbox event.** A `PriceUpdated` arrives from a feed
  the ledger does not own; nothing in the ledger's database commits alongside
  it, so there is nothing to keep consistent with. `MarginCallRaised` and
  `PositionOpened` are consequences of ledger state and go through the
  outbox. The decision record says why the line is there.
- **Delivery is at-least-once, so every effect is idempotent or guarded.** A
  price update is idempotent by its version. A notice sent to a counterparty
  is not, and is guarded by a consumed-message table. The stage shows both,
  and why the second one has a retention problem.
- **The ledger holds no collateral balance yet, and TCC needs one.** Freezing
  collateral is the roadmap's TCC example, so the stage adds a
  `collateral_account` and the freeze that acts on it. It is used by the saga
  and by nothing on the HTTP surface: `POST /positions` is unchanged.
- **Only some exercises belong to the product.** The outbox, its relay, the
  version-gated price apply, the consumed-message guard, the error handler
  with its dead-letter topic, the TCC participant and the saga orchestrator
  are product code. Sending before commit, a random partition key, an
  unguarded consumer, commit-before-process, a TCC without a state table and
  `UPDATE balance = balance - x` are for breaking. `CLAUDE.md` gives broken
  code two homes: a `reproduces<Failure>` test, or `labs/<nn>-<slug>/`. A
  consumer killed with `SIGKILL` needs a real process, so the rebalance
  experiment is a lab.

## Settled design decisions

Decided before this spec was written. The decision record argues each; the
spec states what they commit the code to.

1. **Kafka is a transport, so it gets its own adapter module,
   `adapter-messaging`.** Stage 9 kept Redis inside `adapter-persistence`
   because Redis was a data store there. Kafka is not: nothing in it is the
   ledger's state. The module list in `CLAUDE.md` gains one line. The
   outbox table, the consumed-message table, the TCC tables and the saga log
   are database tables, and their JDBC adapters stay in `adapter-persistence`.
2. **`application` stays free of Kafka and JSON.** It defines ports
   (`OutboxStore`, `EventCodec`, `EventSender`, `PriceUpdatePublisher`,
   `ConsumedMessages`, `CollateralHolds`, `SagaLog`, `NoticeChannel`), and the
   use cases that use them. `ArchitectureIT` gains a rule for it.
3. **Three topics, keyed on what must stay ordered.** `sl.price-updates` is
   keyed by ISIN, `sl.margin-calls` and `sl.position-events` by position id.
   Each has a `.DLT` companion. Partitions are configured, default 3.
4. **A price update carries a version the feed assigns, and the ledger applies
   an update only if its version is higher than the stored one.** The Kafka
   offset is not the version: an offset is per partition and per topic, and
   changes if the topic is rebuilt. Partition-key ordering and the version
   gate are both built; the version is the one that is kept as the guarantee,
   the key is what makes it rarely needed.
5. **The dedup guard is a table written in the effect's own transaction.**
   `consumed_message(consumer, message_id)` with a primary key; the insert
   and the effect commit together or not at all. Its rows are purged after a
   TTL that must exceed the longest period a message can be redelivered,
   which is the topic's retention, and the decision record states the rule
   and the number for the defaults.
6. **Offsets are committed by the application after the effect, never before,
   and never by a timer.** `enable.auto.commit=false`, ack mode `RECORD`.
7. **Errors are classified.** A message that can never succeed (undecodable,
   unknown ISIN, currency mismatch: any `DomainException` or a decode failure)
   goes to the DLT without a retry. Anything else is retried with exponential
   back-off and a bound, then goes to the DLT.
8. **The outbox relay polls, claims rows with `FOR UPDATE SKIP LOCKED`, sends,
   waits for the broker's ack, and marks them published in the same
   transaction.** A crash between the ack and the commit re-sends: the outbox
   is at-least-once, and the consumer's guard closes the loop. Each event has
   a UUID that is the message id end to end.
9. **The saga is an orchestrator with a persisted log, and its last step is a
   pivot.** Steps before the pivot have compensations and run backwards on
   failure; the step that creates the position is local and atomic and is the
   point of no return; what follows it is retried forward, never compensated.
   Outbox and saga solve different problems: outbox is atomicity between one
   database write and one message; saga is a flow over several participants
   with a way back.
10. **TCC state lives in one table with a primary key on the transaction id,
    and cancel may insert.** That single decision fixes empty rollback,
    suspension and idempotency together.
11. **2PC is not implemented.** The blocking is shown once, with Postgres's
    own `PREPARE TRANSACTION`, so the argument is measured and not recited.
12. **Everything is off unless configured.** `ledger.messaging.enabled`
    defaults to `false`; with it off no Kafka bean, thread or connection
    exists and every earlier test behaves as before. The `local` profile turns
    it on.

## Scope

### Module topology and dependencies

No child POM carries a `<version>`. Spring Kafka, kafka-clients and
Testcontainers come from the Boot BOM. Lab POMs carry their own versions.

| Module | Gains | New dependencies |
|---|---|---|
| `domain` | nothing | none |
| `application` | `messaging` (events, outbox, relay, idempotent consumer, price apply, revaluation, notifier), `collateral` (TCC port and use cases), `saga` (orchestrator, open-position saga); ports | none |
| `adapter-persistence` | JDBC adapters: outbox, consumed messages, price version apply, TCC, saga log, security positions; migrations V6–V10 | none |
| `adapter-messaging` (new) | Kafka: JSON event codec, producer, sender, price-update and margin-call listeners, error handler, topics | Spring Kafka, Jackson |
| `adapter-web` | nothing | none |
| `bootstrap` | `ledger.messaging.*` properties and wiring, the in-process event bridge, relay and purge schedulers | none in main |
| `labs/10-messaging` | consumer group under `SIGKILL`, eager against cooperative assignor | kafka-clients, Testcontainers |

### In-process events (`application.event`, bootstrap)

- `ApplyPriceUpdate` publishes an in-process `PriceApplied` after it has
  written, through a `DomainEvents` port; the bridge in `bootstrap` is a
  `@TransactionalEventListener(AFTER_COMMIT)` that evicts the price and
  security caches. This is stage 9's "update the store, then delete the
  entry", with the delete tied to the commit instead of to the caller's
  goodwill.
- On test beans: a plain `@EventListener` runs inside the transaction and sees
  an event for a transaction that then rolls back (`reproducesPhantomEvent`),
  and cannot read the row it was told about from another connection;
  `AFTER_COMMIT` does neither. A write made from an `AFTER_COMMIT` listener is
  silently not committed (`reproducesAfterCommitWriteIsNotCommitted`) until
  the listener opens `REQUIRES_NEW`. A listener that throws after the commit
  loses the event with nothing recorded (`reproducesAfterCommitEventLost`):
  the case for the outbox.

### Price updates over Kafka

- Message: JSON `{"eventId","isin","price":{"amount":"41.30","currency":
  "HKD"},"version":7,"occurredAt"}`. Key: the ISIN. Header `messageId`.
- `PriceUpdatePublisher` sends directly, waits for the ack, and is what a feed
  gateway would call. Idempotent producer on, `acks=all`.
- Partitioning: the same ISIN always lands on one partition; with a random
  key the same ISIN lands on several. Both are asserted, with the
  distribution of 1,000 keys over 3 partitions.
- Ordering: `reproducesOldPriceOverwritesNew` — a consumer applying
  "last write wins" is handed v2 before v1 because v1's partition has a
  backlog, and ends with v1's price. Fixed twice: keying by ISIN (v1 and v2
  share a partition, so the backlog is theirs together) and the version gate
  (`UPDATE ... WHERE price_version < :v`, which is right whatever the
  arrival order). A price at a version equal to the stored one is a duplicate
  and is ignored.
- `ApplyPriceUpdate` (`@Transactional`): apply the price; if it was applied,
  revalue the open positions on that ISIN and append a `MarginCallRaised` to
  the outbox for each shortfall, and publish `PriceApplied`. A position that
  cannot be revalued (collateral in another currency) is counted and logged
  and does not fail the update.
- V6 adds `security.price_version`, default 0.

### Consuming exactly once in effect

- `IdempotentConsumer.once(consumer, messageId, effect)`: inserts the id, and
  runs the effect only if the insert was new, in one transaction.
- `MarginCallNotifier` consumes `sl.margin-calls` and calls a `NoticeChannel`
  through the guard. The channel is an external call and cannot be made
  idempotent by a constraint, which is why the guard is a table.
- `reproducesDuplicateNotice`: the same message delivered twice, unguarded,
  sends two notices; guarded, one.
- `reproducesUnboundedDedupTable`: the table with no purge grows by one row
  per message forever; the size after N messages and its projection are
  evidence. `reproducesDuplicateAfterTtl`: with a purge shorter than the
  redelivery window, a duplicate that arrives after the purge is applied
  again. Both run on a controlled clock.
- The purge takes a cutoff instant. `dedup-ttl` must exceed `topic-retention`.

### Offset commit

- `reproducesLossWhenOffsetCommittedBeforeProcessing`: commit, then the
  effect throws, then a new consumer in the group starts: the message is
  gone. Also with `enable.auto.commit=true`, where the next `poll` commits
  the offsets of records the application failed to handle.
- `reproducesDuplicateWhenProcessedButNotCommitted`: the effect happens, the
  consumer stops before committing, a new consumer receives the message again;
  with the guard the effect happens once.
- The product listeners use manual commit after the effect.

### Retry and dead letter

- A `DefaultErrorHandler` with `ExponentialBackOffWithMaxRetries` and a
  `DeadLetterPublishingRecoverer` to `<topic>.DLT`, the original headers plus
  the exception class and message.
- Non-retryable: decode failures, `DomainException`, unknown reference.
  Retryable: `TransientDataAccessException`, timeouts, anything unclassified.
  The classification is a decision, argued in the record.
- Asserted: a poison message reaches the DLT after **0** retries; a message
  whose handler fails twice and then succeeds is handled once after **2**
  retries and not dead-lettered; one that never succeeds is dead-lettered
  after the bound. Attempts and elapsed time are evidence.
- A message that is dead-lettered does not block the partition behind it.

### Outbox

- V7: `outbox_event(id uuid pk, seq bigserial, topic, message_key, type,
  payload, created_at, published_at)` with a partial index on unpublished
  rows.
- `OpenPosition` appends a `PositionOpened` in its own transaction.
  Revaluation appends `MarginCallRaised`. Nothing else changes.
- `OutboxRelay.relayBatch()`: claim, send in order, mark the sent rows. It
  stops at the first send that fails, marks what did go, and commits.
- `reproducesDualWriteLosesTheEvent`: commit, then send with the broker
  unreachable: the position exists and no message ever will. And
  `reproducesPhantomMessageBeforeCommit`: send inside the transaction, then
  roll back: a message for a position that does not exist.
- Outbox with the broker unreachable: rows accumulate, nothing is lost; the
  broker returns, the next relay delivers all of them, in order per key.
- `reproducesRelayCrashRedelivers`: the relay sends, then dies before the
  mark commits; the next relay sends the same events again; the consumer's
  guard makes the effect single. The relay's own death (no relay running):
  the backlog grows and the age of the oldest unpublished row is what
  reveals it, and the query for it is in the code (stage 12 alerts on it).
- Two relays at once claim disjoint rows and neither blocks.
- Ordering caveat: `seq` order is not commit order across concurrent
  transactions; per-aggregate order holds because a position's own writes are
  serialised by its version. Asserted for one position; stated, not
  asserted, for two.

### TCC on collateral

- V9: `collateral_account(counterparty_id, currency, available, frozen)` with
  non-negative checks, and `tcc_transaction(xid pk, counterparty_id, amount,
  currency, status)`.
- `CollateralHolds`: `tryFreeze(xid, counterparty, Money)`, `confirm(xid)`,
  `cancel(xid)`. Every call is idempotent and runs in the caller's
  transaction.
- Reproduced on a test-only naive participant, one test each, and repaired by
  the product participant:
  `reproducesEmptyRollback` (cancel arrives first and creates money),
  `reproducesSuspension` (a late try after a cancel freezes funds nobody will
  release), `reproducesDoubleConfirm` (a confirm delivered twice deducts
  twice).
- `reproducesOverdraftWithBalanceMinusX`: `UPDATE balance = balance - x` under
  concurrency on a table with no guard goes negative; with `WHERE available >=
  x` it does not. The numbers are evidence.
- `reproducesPreparedTransactionBlocksWriters` (on a Postgres started with
  `max_prepared_transactions`): a transaction prepared and then abandoned
  holds its row lock for as long as it exists; a second writer waits until its
  lock timeout. This is the 2PC blocking, measured.

### Saga

- V10: `credit_reservation(saga_id pk, counterparty_id, amount, currency,
  status)` and `saga_log(saga_id, seq, step, phase, outcome, at)`.
- `OpenPositionSaga`: reserve credit, freeze collateral (TCC try), create the
  position (pivot; local transaction, appends `PositionOpened`), then confirm
  the hold and consume the reservation, retried until it lands.
- A failure at each step is injected. Asserted end states: after a
  reservation failure nothing changed; after a freeze failure the reservation
  is released; after a create failure the hold is cancelled and the
  reservation released; after a confirm failure the position stays and the
  confirm is retried to completion.
- `SagaRecovery` reads the log of a saga that has no terminal entry: a saga
  that has not passed the pivot is compensated, one that has is rolled
  forward. A crash is injected between logging a step and running it, and
  between running it and logging its outcome. The compensation of an
  unknown-state step is the TCC cancel that is safe on an empty rollback.
- HTTP is unchanged.

### Consumer group

- Backpressure: a producer faster than the consumer; the lag is sampled and
  its growth is evidence. `reproducesKickedOutForSlowProcessing`: a handler
  slower than `max.poll.interval.ms` leaves the group and its next commit
  fails; the relationship `max.poll.records × worst per-record time <
  max.poll.interval.ms` is stated and checked with a measured worst case.
- `labs/10-messaging`: three consumer processes on six partitions, one
  removed by a graceful close and by `SIGKILL`, with the eager and the
  cooperative assignor. The lab records the longest gap without processing
  for the survivors and for the orphaned partitions.

### Manual run

- `infra/compose.yaml` gains a single-node Kafka (KRaft), bound to loopback.
- The `local` profile enables messaging. A transcript in evidence: open a
  position, see the outbox row become published and a `PositionOpened` on the
  topic; produce a price drop, see a margin call and a notice.

## Out of scope

- Kafka transactions and exactly-once semantics between topics; a schema
  registry and Avro/Protobuf on the topic (JSON with a `type` and a version
  field is what is used, and the record says where that breaks).
- A Kafka cluster, replication, `min.insync.replicas` tuning: one broker.
- Change-data-capture (Debezium) as the outbox relay, discussed not built.
- Wiring the saga into `POST /positions`.
- Real notification channels, and dashboards and alerts on lag or outbox age
  (stage 12); the queries exist.
- Authentication on Kafka (stage 13).
- Daily accrual and its compensation (stage 11).

## Constraints

- `domain` does not change.
- `./mvnw verify` at the repository root stays green after every section.
- `application` gains no third-party dependency; no Kafka or Jackson type in
  it.
- No test depends on a running Compose stack; Kafka, Redis and Postgres come
  from Testcontainers.
- Concurrency is sequenced with latches and barriers; where time is the
  subject (a poll interval, a lag) the test says so, and it has
  `@Timeout`. Time-dependent logic uses an injected `Clock`.
- Reproduced failures are named `reproduces<Failure>`, carry
  `// See docs/decisions/10-messaging.md`, and assert an observable outcome
  (a row count, a value, a message), never message text alone.
- Numbers in the decision record come from evidence files measured on this
  machine.
- No secret in any committed file. All data is synthetic.

## Acceptance criteria

1. With messaging off, behaviour is unchanged and stage 1–9 tests pass.
2. `AFTER_COMMIT` does not fire on rollback and sees committed data; the four
   in-process reproductions pass.
3. A price update published to Kafka is applied, evicts the caches after the
   commit, and raises margin calls through the outbox.
4. The same ISIN maps to one partition; a random key does not.
5. Old-overwrites-new is reproduced and fixed by the key and by the version.
6. A duplicate message sends one notice with the guard and two without; the
   unbounded table and the late duplicate are both reproduced with numbers.
7. Both offset-commit failures are reproduced and the product order is the
   safe one.
8. A poison message reaches the DLT with no retry; a transient failure is
   retried the configured number of times; a stuck message does not block
   its partition.
9. Both dual-write failures are reproduced; the outbox loses nothing across a
   broker outage and a relay crash, and two relays do not collide.
10. The three TCC failures are reproduced, and repaired by one table; the
    overdraft is reproduced and refused by the guard; the prepared-transaction
    block is measured.
11. Each saga failure point ends in the asserted state; recovery handles both
    crash points.
12. The slow-handler eviction is reproduced; the lab's rebalance evidence
    exists.
13. `ArchitectureIT` passes with the new rules: `application` has no Kafka or
    JSON type; `adapter-messaging` and `adapter-persistence` do not depend on
    each other.
14. `./mvnw clean verify` at the repository root is green.

### Evidence

`docs/evidence/10-*.txt`, text only, measured on this machine: the in-process
event outcomes; the partition distribution; the ordering reproduction and both
fixes; the dedup table growth and the late duplicate; the offset-commit
outcomes; the retry and DLT attempts; the dual-write outcomes; the outbox
outage and crash timelines; the three TCC failures; the overdraft; the prepared
transaction block; the saga end states; the lag and eviction; the rebalance
lab; the manual transcript.

## Done when

The four `CLAUDE.md` deliverables exist: this spec, the plan, the code with
every criterion verified, and `docs/decisions/10-messaging.md`. That record
covers the twelve settled decisions against their alternatives, and:

- the outbox flow drawn end to end, including what happens when the relay dies;
- the dedup TTL rule and its number;
- which exceptions retry and which go to the DLT, and why;
- the Saga against Outbox boundary;
- the three TCC failures on a timeline;
- why 2PC is not used, with the measured block;
- where each choice breaks at 100x.

A second record, `docs/decisions/10-collateral-consistency.md`, is the
roadmap's named document: why `UPDATE balance = balance - x` is not enough
and three or more alternatives compared.

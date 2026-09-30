# Stage 10 — Messaging — Decision Record

Spec: [`docs/specs/10-messaging.md`](../specs/10-messaging.md)
Plan: [`docs/plans/10-messaging.md`](../plans/10-messaging.md)
Companion: [`10-collateral-consistency.md`](10-collateral-consistency.md), the
roadmap's named document on moving client collateral.

Measured on JDK 21.0.12 (Temurin, the `.sdkmanrc` pin), a 4-CPU Linux host,
Spring Boot 4.1.1, Spring Kafka 4.1.1 (kafka-clients 4.2.1), a single-node
`apache/kafka:4.1.0` broker and Postgres 17 (Testcontainers, loopback). Every
number below is copied from a `docs/evidence/10-*.txt` file. Where a value is
deterministic by construction (a controlled clock, a scripted interleaving, a
barrier), the evidence says so and so does this record. Where a claim is
reasoned and not measured, it says so.

## What I chose

### The split: what ships, and what only exists to be broken

| Exercise | Where | Why there |
|---|---|---|
| Outbox, relay, version-gated price apply, consumed-message guard, error handler with a dead-letter topic, TCC participant, saga orchestrator and its log | product code | the service needs it |
| Send before commit, a random partition key, an unguarded consumer, commit-before-process, a TCC participant with no state table, `UPDATE balance = balance - x` | `reproduces<Failure>` tests beside the fix | the broken form is one line from the fixed form |
| A consumer group losing a member to `SIGKILL` | `labs/10-messaging/` | a kill has to kill a process |

### Kafka is a transport, so it gets its own adapter module

Stage 9 kept Redis inside `adapter-persistence` because Redis was a data store
there. Kafka is not: nothing in it is the ledger's state, and the day the bus is
replaced the ledger's tables should not notice. `adapter-messaging` (a new
module; `CLAUDE.md`'s module list gained it) holds every Kafka type. The outbox, consumed-message, TCC and saga
tables are database tables, so their JDBC adapters stay in
`adapter-persistence`. `application` takes neither: it defines ports
(`OutboxStore`, `EventCodec`, `EventSender`, `ConsumedMessages`,
`CollateralHolds`, `SagaLog`, `NoticeChannel`) and `ArchitectureIT` refuses a
Kafka, Jackson or JDBC type in it, and refuses either adapter depending on the
other.

### In-process events: what `AFTER_COMMIT` fixes and what it does not

`ApplyPriceUpdate` publishes an in-process `PriceApplied`; a listener registered
`AFTER_COMMIT` evicts the price and security caches. That is stage 9's "update
the store, then delete the entry" with the delete tied to the commit. Measured
on test beans ([`10-after-commit.txt`](../evidence/10-after-commit.txt)):

- A plain `@EventListener` runs inside the transaction: a booking that then
  rolled back had **told** the listener (`P1`) about a row that does not exist,
  and a reader on another connection could not see the row it was told about.
- `AFTER_COMMIT`: **0** calls on rollback; **1** on commit, and another
  connection *could* read the row.
- A write made from an `AFTER_COMMIT` listener ran under the *booking's*
  transaction name (`EventBooking.book`, already committed), not a new one. It
  reached the table only because pgjdbc commits an open transaction when the
  pool resets autocommit. I planned to reproduce "the write is not committed" and
  could not on this stack; a JPA write in the same place would need a flush nobody
  issues (reasoned, not reproduced). `REQUIRES_NEW` is the only form that says
  what it means.
- A listener that throws after the commit: the booking is committed, **the caller
  receives no exception**, and **0** rows record that an event was owed. That is
  the whole case for the outbox: the event is not just at risk, nothing knows it
  is missing.

The eviction is best-effort by design. A crash between the commit and the call
leaves the old entry to expire by its TTL, the bound stage 9 states.

### A price update is not an outbox event, and its version is the guard

A `PriceUpdated` comes from a feed the ledger does not own. No write of the
ledger's commits alongside it, so there is nothing for an outbox to keep
consistent; the feed retries, and the version makes a repeat harmless. The
`MarginCallRaised` it causes *is* a consequence of ledger state and goes
through the outbox, in the same transaction as the price.

**Key = ISIN.** 1,000 ISINs spread over 3 partitions as 358 / 316 / 326
([`10-partition-key.txt`](../evidence/10-partition-key.txt)), 60 updates of one
security landed on one partition and read back in send order, and with a random
key the same security's 60 updates landed on all three. Kafka orders a
partition and nothing across partitions, so a random key removes the only
ordering there is.

**The wrong order, on a timeline.** Two updates of one price, v1 = 41.30 and
v2 = 43.10. With a key that is not the ISIN they land on different partitions;
the consumer is behind on v1's partition (the test pauses it):

| | partition 0 | partition 1 | stored |
|---|---|---|---|
| send | v1 (41.30) | v2 (43.10) | |
| consumer catches up on partition 1 | | v2 applied | 43.10 |
| consumer catches up on partition 0 | v1 applied | | **41.30**, v2 overwritten |

([`10-ordering.txt`](../evidence/10-ordering.txt).) Two fixes were built and both
work, for different reasons. **The key** puts v1 and v2 behind the same backlog
together (stored 43.10 at version 2, with a naive consumer); it prevents the
reorder. **The version gate** (`price_version` and a `SELECT … FOR UPDATE`
compare in the same transaction) makes the reorder harmless: the same wrong
order ends at 43.10 at version 2, v1 ignored and not an error. I keep the gate
as the guarantee and the key as what makes the gate rarely needed. The key
cannot cover a producer that retries out of order, a topic that is rebuilt, or
two feeds; the version can. The version is the feed's sequence, not the Kafka
offset: an offset is per partition and changes if the topic is rebuilt.

### The outbox

```
  request thread                     relay (own thread, 500 ms)             broker      consumer
  ───────────────                    ──────────────────────────             ──────      ────────
  BEGIN
    INSERT loan_position
    INSERT loan_trade
    INSERT outbox_event  ── one transaction: all three or none ──
  COMMIT
                                     BEGIN
                                       claim  (FOR UPDATE SKIP LOCKED, per-key run)
                                       send m1 ────────────────────────────▶ ack
                                       send m2 ────────────────────────────▶ ack
                                       UPDATE outbox_event SET published_at   (only what was acked)
                                     COMMIT
                                                                                    ▶ IdempotentConsumer.once(id, effect)
```

The relay is at-least-once. The three ways it can fail, each run:

- **The broker is away** (unreachable, `sendTimeout` 1 s in the test): a pass
  claimed 3, sent 0, marked none; 3 rows stayed unpublished. When the broker
  came back a pass sent 3, in append order for one key, and marked them
  ([`10-outbox.txt`](../evidence/10-outbox.txt)). Nothing was lost; the outbox
  is a queue with a database's durability.
- **The relay dies between the ack and its commit.** The mark rolled back; the
  next pass sent the same message again. The topic held **2** records with one
  `messageId`. That is not a defect to fix in the relay, it is the contract, and
  it is why the id travels end to end and the consumer has a guard.
- **The relay is not running at all.** Nothing is wrong *in the database*; the
  backlog grows. It shows as the age of the oldest unpublished row: 5 min 1 s on
  a controlled clock, with the query in the code (`oldestUnpublishedSince`) for
  stage 12 to alert on.

**The claim is `FOR UPDATE SKIP LOCKED`, and that alone breaks per-key order.**
Relay A holds POSITION-1's older message while it sends; relay B skips it and
claims the newer one of the same position; the newer one would be sent first
([`10-outbox-store.txt`](../evidence/10-outbox-store.txt)). I kept SKIP LOCKED
(two relays claimed 3 rows each from 6, without waiting) and added a second
step: keep, per key, only the run of claimed rows that starts at the key's oldest
unpublished row. The first design was `NOT EXISTS (older unpublished row of the
same key)` in the claim; it is correct and simpler and it hands out one message
per key per pass: 3 events for one position sent 1 in the first pass, where the test
expected 3. That test is what found it.

**What the outbox does not order.** `seq` is assigned at insert, not at commit,
so across two concurrent transactions it is not commit order. Per key it is,
because a key here is a position and a position's own writes are serialised by
its version. That is stated, not asserted, for two positions.

**Two relays, 40 rows, batch 7:** one sent 21, the other 19; the topic held 40
records with 40 distinct ids.

### Outbox against saga: different layers

The outbox makes *one database write and one message* atomic. The saga makes *a
sequence of writes in several places* recoverable, with a way back. Opening a
position uses both, at different levels: the saga's pivot step creates the
position, and that step's own transaction appends the `PositionOpened` to the
outbox. Replacing the saga with an outbox would not undo a frozen hold;
replacing the outbox with the saga would still lose the event if the process
died after the commit.

### Exactly-once in effect: the dedup guard and its TTL

A price update needs no dedup table: the version is already idempotence. The
margin-call notice does, because sending it is an external call that no
constraint can make idempotent. `IdempotentConsumer.once(consumer, id, effect)`
inserts `(consumer, message_id)` and runs the effect only if the insert was new,
in one transaction. The same `MarginCallRaised` delivered twice sent **2** notices
without the guard and **1** with it. An effect that throws takes its guard row
with it, so the redelivery is acted on (`failedEffectRollsBackTheGuard`). What the
transaction cannot cover is a notice already sent when the transaction then
rolls back: that is sent again. At-least-once, stated on the channel.

**How long to remember an id.** The rule: longer than the longest a message can
be redelivered, and a replay can only reach messages the topic still holds, so
`dedup-ttl > topic-retention`. The properties class refuses a TTL that does not
exceed it; the default is 8 days against 7
([`10-dedup-ttl.txt`](../evidence/10-dedup-ttl.txt)):

- **TTL 1 h, retention 7 d, the duplicate redelivered 2 h after the first:** 2
  notices (the guard row had been purged).
- **TTL 8 d, retention 7 d, redelivered at 6 d 23 h 59 m, after a purge:** 1 notice.
- **No purge at all** (the story this exercise is for, a key that never
  expires): 2,000 messages through `once`, one transaction each, took a mean of
  1.02 ms; 1,002,000 rows occupied 194,322,432 bytes with indexes, 193.9 bytes a
  row; at 100× that is 19.4 GB and still growing by a row per message per
  consumer (arithmetic on the measured bytes, labelled so in the file). One
  `DELETE` of 996,412 rows past the TTL took 654 ms, which is the price of a purge
  that runs hourly rather than never.

### Offsets: commit after the effect, never by a timer

Both loss modes were made to happen with raw consumers
([`10-offset-commit.txt`](../evidence/10-offset-commit.txt)):

- **Commit, then process, then fail:** effects applied **0**; a new consumer in the
  group received **0** records in 6 s. The message is gone.
- **`enable.auto.commit=true`**, the effect failing and caught: the next `poll`
  committed it; **0** records for a new consumer. The same loss, without the
  code doing it explicitly, which is why it is the more dangerous.
- **Process, then die before the commit:** restarted twice, **2** effects without
  the guard, **1** notice with it.

The product container sets `enable.auto.commit=false` and `AckMode.RECORD`, and
the test shows the order: while the listener was still running, the group had
**0** committed offsets; after it returned, offset **1**. Per record rather than
per batch because the effect of one record is one database transaction; the
cost is a broker round trip per record, and a crash re-delivers at most one.

### Errors: which ones are retried

| Failure | Retried? | Why |
|---|---|---|
| Payload does not decode (`MessageDecodeException`) | no | the same bytes decode the same way |
| A refusal by the domain (`DomainException`, e.g. price in the wrong currency) | no | the same question gets the same answer |
| Unknown security or position (`UnknownReferenceException`) | no | the referent will not appear by waiting |
| A database timeout, a busy lock, a deployment in progress | yes | the cause is not in the message |
| Anything unclassified (a `NullPointerException` from a bug) | yes, bounded | I will not predict every transient class; the cost is seconds |

This is a decision, not a configuration. Measured
([`10-retry-dlt.txt`](../evidence/10-retry-dlt.txt)): the three non-retryable
kinds went to the DLT after **1** attempt each, with the cause class in
`kafka_dlt-exception-cause-fqcn` (the plain `…-fqcn` header names Spring's
wrapper, which is not what an operator wants); a transient failure twice then
success took **3** attempts and produced no dead letter; one that never cleared
took **4** attempts (1 + 3 retries) and then landed on the DLT; a dead-lettered
message did not stop the two behind it.

**Retries block the partition, on purpose.** A retry topic would let a later
price overtake the one being retried, which is the failure the key was chosen
to prevent. The pause is bounded: 4 retries at 0.5, 1, 2, 4 seconds is about 7.5
seconds a hopeless message holds its partition.

**A measured surprise, not explained.** With a configured first pause of 100 ms
doubling, the gaps between attempts were **106, 505 and 507 ms**, not 100, 200
and 400. The first is right; later ones sit at about half a second. I changed
the container's poll timeout and it did not move; I did not find the cause. The
default first interval is 500 ms, above whatever quantises it, and sub-second
back-off should be treated as approximate.

### The saga, and why the last step is a pivot

`OpenPositionSaga`: reserve credit, freeze collateral (a TCC Try), create the
position, then spend the hold and the reservation. The position step is the
**pivot**: one local transaction, it either happened or it did not, and after it
the saga only moves forward, retrying until it lands. Steps before it have
compensations. Steps after it are never compensated: a position exists, and
"undoing" it would be a second, unrelated business operation.

Three decisions make recovery small:

- **The saga id is the idempotency key.** A replay of a finished saga is a lookup
  and recovery can ask the ledger "does this position exist?", the one fact the
  log can be a step behind on.
- **Compensations are idempotent and safe on a step that never ran**, so
  recovery compensates *everything* without reading the log. That is why 12
  crash points share one code path.
- **The log is written outside any transaction** and refuses to be written
  inside one: an entry that rolls back with its step records nothing.

Measured ([`10-saga.txt`](../evidence/10-saga.txt)): failure injected at each
forward step ends compensated with nothing left over; a `complete` that fails
twice is retried and lands; one that fails every time leaves the position in
place, hold `TRIED`, saga pending, and recovery rolls it forward; a compensation
that fails is logged (a `FAILED` entry with the message) and left for recovery.
The process was made to die at each of 12 points (before and after each of the 4
forward steps, and before and after each of the 2 compensations, the latter
while compensating): after recovery every one ended either completed or
compensated, never half. Two sagas of 60,000.00 against a 100,000.00 limit,
started together: one completed, one was refused at the reservation, and there
was one position. A plain `OpenPosition` still has stage 8's unlocked credit
check; the saga is the path that closes it, by locking the counterparty row when
it reserves.

### TCC: three failures, one table

Reproduced against a participant that keeps no record of which transaction did
what ([`10-tcc.txt`](../evidence/10-tcc.txt)). On a timeline, for an account of
1,000.00 and a hold of 100.00:

| | Cancel(X) | Try(X) | result with no record | with `tcc_transaction` |
|---|---|---|---|---|
| **empty rollback** | arrives, no Try ever | never | available **1,100.00**, frozen −100.00 | 1,000.00 / 0, a tombstone written |
| **suspension** | arrives first, "nothing to cancel" | arrives late | available 900.00, frozen **100.00**, held for a transaction already cancelled | Try refused (`ALREADY_CANCELLED`), 1,000.00 / 0 |
| **duplicate confirm** | | | A and B hold 100.00 each (frozen 200.00), Confirm(A) twice → frozen **0** | frozen 100.00, B's hold intact |

The usual patch to the empty rollback (`WHERE frozen >= x`) also *steals*: B holds
100.00, A (never tried) cancels 100.00, B's hold is gone and B's Confirm leaves
frozen at −100.00. One table repairs all four: `tcc_transaction(xid PRIMARY KEY,
status)`. Try and Cancel start with `INSERT … ON CONFLICT DO NOTHING`; whoever
writes the row first decides, and Confirm and Cancel move money only when they
change the status *from* `TRIED`. 40 rounds of a Try racing its own Cancel: 17
Try-first, 23 Cancel-first, and the account ended at 1,000.00 / 0 in all 40.

### Two-phase commit is not used, and here is the block

Measured with Postgres's own `PREPARE TRANSACTION`
([`10-2pc-blocking.txt`](../evidence/10-2pc-blocking.txt)): a participant
prepared, the coordinator gone, the connection closed. `pg_prepared_xacts`
held it. Another writer of the same row, willing to wait 2 s, **waited 2,009 ms
and gave up**; it would have waited for as long as the prepared transaction
exists, which no timer ends. After a hand-run `ROLLBACK PREPARED` the same
update took 3 ms. The specific scenario the roadmap asks for: the coordinator dies after
every participant has voted yes; each participant holds its row locks until a
coordinator or a person decides, and none may decide alone because each has
promised to commit if told to. The trade against a saga: 2PC gives atomic
visibility and takes availability; a saga gives availability and takes atomic
visibility. For collateral, where a locked row means a client cannot trade, I
take the half-done state held explicitly. The full comparison of six schemes is
in [`10-collateral-consistency.md`](10-collateral-consistency.md).

### Backpressure and being removed from the group

([`10-backpressure.txt`](../evidence/10-backpressure.txt).) A producer at about
100 records/s against a consumer at about 40/s: the lag sampled each second was
72, 132, 196, 260, 320, 384 (580 produced), and it drained in 10,654 ms after
the producer stopped. Lag is the queue you never see until it is a delay.

`max.poll.interval.ms` 3,000: a batch of 5 records at 1,000 ms each is 5,000 ms
between polls, the consumer was removed, **the commit failed**
(`CommitFailedException`), 1 partition was lost, and the next poll started again at
**offset 0**: the 5 records it had handled were delivered again. 5 × 400 ms =
2,000 ms committed and moved on (next poll at offset 5); 2 × 1,000 ms = 2,000 ms
committed too (offset 2). The rule is `max.poll.records × worst time per record <
max.poll.interval.ms`, where the worst time is the handler's p99 and not its
mean. Being removed produces duplicates, which is the second reason the guard
exists.

### A consumer group losing a member

([`10-rebalance.txt`](../evidence/10-rebalance.txt), `labs/10-messaging`.) Three
consumer JVMs on six partitions, about 10 records/s/partition, a single local
broker, `session.timeout.ms` 10 s (not Kafka's default: it shortens the run, and
the crash figure below is that timeout plus the rest). Member 3 removed 20 s in,
once by `SIGTERM` (the consumer leaves the group) and once by `SIGKILL` (the
broker must time it out), under the range (eager) and the cooperative-sticky
assignor:

| Assignor | Removal | Partitions member 3 owned sat idle | Longest pause on a survivor's partition | Records handled twice |
|---|---|---|---|---|
| Range | SIGTERM | 2,341 ms | 131 ms | 0 |
| Range | SIGKILL | **12,174 ms** | 140 ms | 0 |
| Cooperative-sticky | SIGTERM | 2,200 ms | 151 ms | 0 |
| Cooperative-sticky | SIGKILL | **12,200 ms** | 109 ms | 0 |

What the numbers say, and what they do not:

- **The orphaned partitions went idle for the session timeout plus about 2 s on a
  crash, and for about 2 s on a graceful leave.** The 10 s is the broker waiting
  for a heartbeat that will not come; that is the SLA cost of a crash, and it is
  set by `session.timeout.ms`, not by the assignor. In production it is the
  timeout you configure (45 s is the client default in this Kafka line; I did not
  run that, so 45 s + is reasoning, not measurement). The 2 s that both cases share
  is consistent with the survivors learning of the rebalance through a heartbeat
  response, at a 3.3 s interval; I did not prove that.
- **The assignor made no measurable difference to the survivors here.** Their
  longest pause was 109-151 ms in all four runs, against a steady state of about
  100 ms. Eager and cooperative should differ (eager revokes every partition of
  every member for each rebalance; cooperative moves only what has to move), but
  with three members, six partitions, trivial handlers and a local broker the
  stop-the-world is too short to see. It does not follow that they do not differ at
  scale; it follows that this lab does not show it. A slow `onPartitionsRevoked`
  handler, or hundreds of partitions, is what would.
- **No record was handled twice**, because the commit follows each poll's batch by
  microseconds and a kill has to land in that gap. It did not, in four runs; the
  guard covers it when it does (`OffsetCommitIT`).
- Kafka 4.x also has the new consumer-group protocol (KIP-848,
  `group.protocol=consumer`), which moves rebalancing to the broker. It was not run.

## What I rejected, and why

- **Kafka inside `adapter-persistence`.** The stage 9 reasoning ("a data store,
  not a transport") does not carry over: Kafka is a transport.
- **Publishing the event after the commit, or before it.** Both reproduced: after,
  with the broker away, the position was committed, the message never came, and
  no row recorded that it was owed (send threw after 501 ms); before, and then a
  rollback, the topic held a message about a position that does not exist
  ([`10-dual-write.txt`](../evidence/10-dual-write.txt)).
- **A random or absent partition key for prices.** Reproduced.
- **The Kafka offset as the version.** Per partition, and it changes with the topic.
- **A dedup table with no TTL,** or a TTL not longer than the retention. Measured.
- **Retry topics.** They reorder a keyed stream.
- **A deterministic event id (a hash of position and price version)** to make the
  outbox itself idempotent. The version gate already gives prices that, and one
  meaning for `messageId` is worth more than a second idempotence mechanism.
- **The relay claiming, marking "in flight", sending, then marking published.**
  Needs a second state and a sweeper for rows stuck in flight; the relay instead
  holds one transaction across its sends (bounded batch, bounded ack wait).
- **Change-data-capture (Debezium) as the relay.** Discussed, not built; it moves
  the polling out of the application and adds a system.
- **Two-phase commit, and a saga with no pivot** (compensating a created
  position). Above.
- **Wiring the saga into `POST /positions`.** The saga is exercised through its
  use case; HTTP is unchanged, so no recovery timer exists for it yet.

## What I observed

- A listener that throws after the commit: caller told nothing, 0 rows recording it.
- 1,000 ISINs over 3 partitions: 358 / 316 / 326; one security, one partition.
- Wrong order without the version: 41.30 stored at version 1; with it: 43.10 at 2.
- Outbox: broker away, 3 rows kept and later sent; a relay crash, 2 records one
  id; two relays, 40 rows, 21 + 19, 40 distinct.
- Dedup: 2 notices → 1; TTL 1 h → 2 notices, 8 d → 1; 193.9 bytes a row.
- Offsets: two loss modes (0 and 0), one duplicate mode (2 → 1).
- Retries: 1 attempt for the three non-retryable kinds; 3 to recover; 4 then DLT;
  gaps 106 / 505 / 507 ms.
- TCC: available 1,100.00 from nothing; 100.00 frozen for ever; frozen 0 from a
  duplicate; 17 + 23 of 40, all consistent.
- Overdraft: 32 told they took 10, balance 90; −220; exactly 10 of 32.
- 2PC: 2,009 ms and no row; 3 ms after `ROLLBACK PREPARED`.
- Saga: 12 crash points, all completed or compensated; 1 position from two racers.
- Lag: 72 → 384, drained in 10,654 ms. Commit failed and batch redelivered when
  the batch took 5 s against 3 s.
- The manual run against the compose stack
  ([`10-manual-run.txt`](../evidence/10-manual-run.txt)): an open over HTTP
  became one outbox row (published), and a `PositionOpened` on the topic; a
  price of 60.00 raised one margin call of 10,000.00 HKD, one notice in the
  log, one row in `consumed_message`; the same margin call delivered again sent
  none, a different one behind it sent one; the stale price update (version 1
  again, at 10.00) left the stored price at 60.00; a message that was not JSON
  landed on `sl.price-updates.DLT` with `MessageDecodeException` as its cause. V6
  through V10 migrated at start-up on the database stage 9 left behind.

## Where the plan was wrong

- **"A write from an `AFTER_COMMIT` listener is not committed."** It was, by the
  driver's doing. The test asserts what happened (the transaction name).
- **The first per-key claim** (`NOT EXISTS`) drained one message per key per pass.
- **One naive Cancel was not enough:** the unguarded one shows the empty rollback,
  the patched one shows suspension and theft; three reproductions became four.
- **Adding a foreign key from `collateral_account` to `counterparty`** broke stage
  6's `TRUNCATE`-based test; the full build found it, not the section's tests.
- **The retry pauses** were not honoured below half a second.
- **A stage 9 defect.** The cross-instance rebuild lock was released before the
  value was stored; an intermittent `HotKeyRebuildIT` failure in one of about a
  dozen full builds was that race. Fixed with a regression test that fails on
  the old code, recorded in stage 9's decision record.
- **A test helper race.** A burst of sends into a topic created a moment before
  made an idempotent producer loop on `NOT_LEADER_OR_FOLLOWER` and then
  `OUT_OF_ORDER_SEQUENCE_NUMBER` until its 120 s delivery timeout, twice in full
  builds and never alone. The tests now send one acknowledged record to every
  partition first. The product's sender is bounded (seconds, one message at a time),
  so the same stall would be an `EventSendException` and a relay retry.

## Where each choice breaks at 100×

- **The relay holds a database transaction across its sends.** At 100× the batch
  times the ack wait is a connection held for as long as that, per relay. The
  fix is the claim, mark-in-flight, send, mark-published shape, with a sweeper for
  rows stuck in flight, or CDC. Neither was needed at this rate and both cost a
  moving part.
- **The outbox table** is polled through a partial index on unpublished rows, which
  stays small; the published rows are never deleted and there is no archival. At
  100× that is a retention job the stage does not write.
- **Per-key order** in the outbox is by key, and the claim query reads every
  unpublished row of the claimed keys. A key with a large backlog makes each
  claim read all of it.
- **The dedup table** is one row per message per consumer for the TTL. Without the
  purge it is the projection above (about 100 million rows, 19.4 GB at the
  measured 193.9 bytes a row, arithmetic); with it, eight days of messages, which
  at 100× the rate is 100× the table. A table partitioned by day and dropped
  whole is cheaper than a `DELETE` of a million rows (654 ms here).
- **Blocking retries** hold a partition for about 7.5 s per hopeless message. A
  burst of them is a stalled partition; a per-key dead-letter breaker would be the
  next step.
- **`ApplyPriceUpdate` revalues every position in one transaction,** reading them
  all in one query. A security with a million positions cannot be revalued that
  way; stage 11's batch is where that goes.
- **JSON with a `type` header** and no schema registry: a producer that adds a
  field is refused by the strict codec on the first message, which is loud, and
  which is a deploy-ordering constraint at 100× teams.
- **One broker, replication factor 1, `acks=all`.** `acks=all` against one
  replica is `acks=1`; the durability the outbox depends on for its "sent" mark
  is the broker's, and this stage did not test a broker loss.
- **The TCC and reservation tables grow** and have no purge; a tombstone has to
  outlive any delayed Try, the same rule as the dedup TTL.

## The oral check: the outbox, on a whiteboard, including the relay dying

Draw three boxes: the application's database, the relay, the broker. In one
transaction the use case writes the position, its trade and an `outbox_event`
row. The relay polls, claims unpublished rows, sends each and waits for the ack,
then marks exactly the ones acknowledged, in one transaction.

Now kill things. The application dies before the commit: no row, no position; both
gone. It dies after: the row is there, the relay will send it. The relay dies
before sending: the row is still unpublished, another pass sends it. The relay
dies **after the ack and before the mark commits**: the broker has the message and
the database does not know, so the next pass sends it again. That duplicate is
the price of not losing anything, so every message carries its id from the row to
the consumer, and the consumer records the id in the same transaction as its
effect. The relay dies and **stays dead**: nothing is wrong in the data, the
backlog grows, and the only symptom is the age of the oldest unpublished row,
which is the alert. Two relays at once: they claim disjoint rows without waiting,
and the claim keeps a key's messages in order by never handing out a message
whose older sibling is still unpublished elsewhere.

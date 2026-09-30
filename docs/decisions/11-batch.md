# Stage 11 — Batch — Decision Record

Spec: [`docs/specs/11-batch.md`](../specs/11-batch.md)
Plan: [`docs/plans/11-batch.md`](../plans/11-batch.md)

Measured on JDK 21.0.12 (Temurin, the `.sdkmanrc` pin), a 4-CPU Linux host,
Spring Boot 4.1.1, Spring Batch 6.0.5, Postgres 17 and Redis 7.4 (Testcontainers,
loopback). Every number below is copied from a `docs/evidence/11-*.txt` file.
Where a value is deterministic by construction (a test clock, a latch, a scripted
gateway) the evidence says so and so does this record. Where a claim is reasoned
and not measured, it says so. Two contexts in one JVM stand in for two instances
in the multi-instance tests; nothing is shared between them but the two servers.

## What I chose

### Spring Batch, and not a scheduled task

The answer that is not "because it is the standard": a scheduled task has no
restart semantics and no history, and these are what a day-end job is judged on.

- **Restart semantics.** A job killed in chunk 4 of 10 leaves, in
  `BATCH_STEP_EXECUTION`, `read 40, write 30, commit 3, rollback 1, FAILED` and a
  saved cursor `accrual.lastKey = P000030`
  ([`11-restart-step-table.txt`](../evidence/11-restart-step-table.txt)). The next
  launch of the same date is the same job *instance* and its step reads 71 rows, the
  ones after the cursor, not 100. A `@Scheduled` method that died there has a log
  line, if that, and starts from the top.
- **Auditable history.** The same tables say which owner ran the date (the fencing
  token is a recorded job parameter), how many rows each execution read, wrote,
  skipped and committed, and when. They are in the ledger's database, in the same
  backup as the entries they produced.
- **What it costs.** Fixed bookkeeping per run and per chunk: at 200 rows a
  hand-written `REQUIRES_NEW` loop was faster than the batch (265 ms against 397 ms,
  [`11-skip-vs-requires-new.txt`](../evidence/11-skip-vs-requires-new.txt)). The
  framework earns its cost where a run is long enough to die in the middle.

### The schema is Flyway's; the framework's auto-configuration is not used

V12 is Spring Batch 6.0.5's PostgreSQL script verbatim. `spring-batch-core` is the
only Batch dependency: Boot's Batch starter creates the job repository whether or
not `ledger.batch.enabled` is set, and the rule is that everything optional is off
unless asked for (`BatchOffIT`). `batch` holds job definitions and no SQL; readers
and writers call ports `adapter-persistence` implements, and `ArchitectureIT` pins
both directions.

### A job is a restart contract: the keyset cursor, saved per chunk

`KeysetReader` reads `WHERE id > :last ORDER BY id LIMIT :n` and saves the last key
it handed out in the step's execution context, which Spring Batch persists inside
the chunk's own transaction. So "resume from the last committed chunk" is true by
construction: the key and the chunk's writes commit together or not at all. No
cursor is held open between chunks.

- **The price of a keyset cursor.** A position that appears *behind* the saved key
  while the job is down is not read by the resumed run (P000015X in the restart
  evidence). A `rerun` instance books it and reports the other 100 as already
  present. This is stated, and it is why the rerun override exists.
- **`READ_COUNT` is not progress.** After an exception the failed step says `read
  40` with 30 written: it counts the chunk that was rolled back. After a `SIGKILL`
  it says `STARTED / EXECUTING` with the counters of the last commit. Progress is the
  saved key and `COMMIT_COUNT`.
- **A reader with no saved state is correct and wasteful**
  (`reproducesRestartFromTheBeginningWithoutACursor`): the primary key refused the
  first 30 again, the resumed run read all 100 and found 30 already present.

### Idempotency is in the data; the framework adds a cheap first line

The primary key `(position_id, business_date)` with `INSERT ... ON CONFLICT DO
NOTHING`, and the writer reports inserted and already-present rows
([`11-idempotent-rerun.txt`](../evidence/11-idempotent-rerun.txt)). Launching a
completed date is answered `ALREADY_DONE` (the framework's instance check, no run);
an explicit `rerun` makes a second instance of the same date, which books 0 and
reports 25 already present. The business date is an identifying `LocalDate`
parameter, so the instance is `(job, date)` and a back-dated date is its own
instance ([`11-job-instances.txt`](../evidence/11-job-instances.txt)). **Where it
breaks:** a back-dated run uses the position as it is *now* (current quantity,
current price, status not closed): the ledger keeps no history, so a position
returned since the date is missing from that date's run. Fixing it needs a
quantity history, which is out of scope.

### What a job that stopped in the middle leaves, and how to re-run it safely

Start from idempotency: a chunk is one transaction and its entries carry a key the
store refuses twice, so any prefix of the work may be repeated. Then the two stops.

1. **An exception**: the step is `FAILED`, the cursor is saved, the next launch of
   the same date resumes.
2. **A kill**: nothing runs afterwards, so the execution stays `STARTED` with no end
   time, and a launch is refused (`ALREADY_RUNNING`) until something says the owner
   is dead. `JobOperator.recover` marks it failed
   ([`11-hard-kill.txt`](../evidence/11-hard-kill.txt): a real child JVM, `SIGKILL`,
   exit 137, 30 entries committed, chunk 4 never committed). What decides "dead" is
   the lock: a live owner holds it.

### One owner for the cutover: Redis for exclusion, Postgres for the order

The fencing token used to come from a Redis counter that a Redis restart reset below
what Postgres had admitted, refusing every write ([`09-caching.md`](09-caching.md),
now resolved). It is a value of a Postgres sequence. The order inside `RedisLock` is:
take the lock, draw the token, check the lock is still this owner's. The check is
what makes the two-step acquire safe: a holder paused between the lock and the draw
returns after the lock has moved on, draws a *higher* token than the current
holder's, finds the lock is not its own and gets no lease. The two-step acquire
without the check gave the paused holder token 2 against the current holder's 1
([`11-fence-paused-holder.txt`](../evidence/11-fence-paused-holder.txt)); after
`FLUSHALL` the next token is above the stored one and the write is admitted
([`11-fence-redis-restart.txt`](../evidence/11-fence-redis-restart.txt)).

- **The fence.** `admit` (an upsert) would have serialised every partition on one row
  lock held to commit, so the run *claims* the resource once before its first step
  and every chunk only `verify`s it, `FOR SHARE`: verifiers of one owner do not block
  each other, and a later owner's claim waits for the ones in flight and then makes
  every later verify of the older token fail (`WriteFenceIT`).
- **Two instances** launching one date at the same instant: one `COMPLETED` (20 book
  calls), one `NOT_OWNER` (0); 1,000 entries, 1 execution
  ([`11-two-instances.txt`](../evidence/11-two-instances.txt)).
- **A paused owner**: A is paused before chunk 3 with its lease expiring, B takes the
  lock, finds A's execution still `STARTED`, recovers it and resumes from A's cursor
  (tokens 1 and 2); A wakes and is refused at its next chunk, 0 book calls after
  waking. The same schedule with A unfenced: one book call with 50 rows after B had
  finished the date, found already present. The ledger is right only because of the
  primary key; a write with no such key (stage 9's `total = total + 100`) would have
  been applied twice ([`11-zombie-refused.txt`](../evidence/11-zombie-refused.txt)).
- **The token is a non-identifying parameter**, so a date that failed under one owner
  resumes as the same instance under the next owner with the next owner's token.
  Restarting the old execution with its own parameters would reuse the dead owner's
  stale token.
- **Where it breaks:** the lease does not renew. A run longer than
  `ledger.batch.lock-ttl` (default 10 minutes) loses its lock under it and is taken
  over; the fence makes that safe and the cost is a run resumed by the other instance
  and refused on the first. A renewing heartbeat is the fix and was not built.

### Skip, retry, and `REQUIRES_NEW`: not the same problem

Skip is for a corrupt row or a domain refusal (`CorruptPositionException`,
`DomainException`), to `ledger.batch.skip-limit`; retry is for a
`TransientDataAccessException` (`retry-limit` 3 is three *retries*, four attempts);
everything else, a stale fencing token included, fails the step
([`11-skip-retry.txt`](../evidence/11-skip-retry.txt)). Five corrupt rows in 200: 10
commits, 0 rollbacks, 195 entries, 5 rows in `batch_skip`, read 200 = written 195 +
skipped 5. An eleventh skip fails the step, rolls back its chunk and leaves 90
entries and 10 skip records.

**Are Spring Batch's skip and stage 5's `REQUIRES_NEW` the same problem? No.**
`REQUIRES_NEW` answers *which transaction does this unit's outcome belong to*: it
commits independently of its caller. Used in a loop it happens to let the loop go on
past a bad row, but it pays a commit per row (196 write transactions for 200 rows
against 28 for the batch), holds two connections per in-flight row
([`05-requires-new-connections.txt`](../evidence/05-requires-new-connections.txt)),
keeps no cursor and records nothing about what it skipped. When both runs fail midway
the loop leaves 96 committed entries and *no record that a run began* (its outer
transaction, and the run row with it, rolled back); the batch leaves 97 and a
`FAILED` step row with `lastKey P000100`. Skip answers *may the run continue past this
item, how many times, and who will know*: it keeps the chunk commit, enforces a
limit, writes a record and composes with restart. They overlap only in letting a run
continue.

### Chunk size

20,000 positions, three runs each, median
([`11-chunk-size.txt`](../evidence/11-chunk-size.txt)):

| chunk size | commits | median | rows/s |
|---:|---:|---:|---:|
| 1 | 20,000 | 48,777 ms (one run) | 410 |
| 10 | 2,000 | 6,139 ms | 3,257 |
| 200 | 100 | 1,336 ms | 14,970 |
| 5,000 | 4 | 1,168 ms | 17,123 |

200 is the default: 5,000 is 13% faster for 25 times the rows a failure redoes and a
transaction that long holds its connection. Chunk size 1 is what a commit per row
costs, which is stage 5's loop.

### Partitioning, and the tail

60,000 positions, 70% of them one counterparty, four worker threads
([`11-partition-skew.txt`](../evidence/11-partition-skew.txt)): no partitioning 3,836
ms; one partition per counterparty (8 partitions, one of 42,000 rows) 2,971 ms, the
big partition 2,750 ms while the other seven finished within about 300 ms each
(longest/mean 4.7); eight balanced partitions of 7,500, 1,649 ms (longest/mean 1.2).
The step ends when its slowest partition does. The scheme is a strategy and the
slices come from the store, because only it knows the distribution.

### Revaluation: inline below a limit, a batch above

`ApplyPriceUpdate` counts open positions; above `ledger.revaluation.inline-limit`
(1,000) it applies the price and records a request in the same transaction and reads
no position. On 50,000 positions
([`11-revaluation-scale.txt`](../evidence/11-revaluation-scale.txt)): the inline read
alone 1,403 ms holding 19.8 MB (405 bytes per position); the whole inline update
14,688 ms in one transaction with the security's row locked throughout, peak live heap
65.8 MB; the job 24,755 ms, peak live heap 2.4 MB, no lock across chunks. The job is
1.7 times slower (a marker insert per call, a commit per chunk) and buys the bound on
memory and on how long a price update of that security can be blocked. A
`margin_call_marker` per `(position, version)`, inserted with the outbox row, makes a
chunk that runs again raise nothing twice
([`11-revaluation-job.txt`](../evidence/11-revaluation-job.txt): 66 calls, 4 skipped,
30 filtered; a rerun raises 0 and finds 66 already raised).

### Reconciliation is a third job

Load a CSV, compare by a full outer join, classify, record
([`11-reconciliation.txt`](../evidence/11-reconciliation.txt)). Four classes, one
planted each among 21 positions, each with a fixed first disposition:

| Class | Disposition | Why |
|---|---|---|
| quantity mismatch | manual, always | a short position is not rounding, and which side is right is not knowable here |
| value mismatch | auto-accepted within `max(1.00, 0.01%)` of the ledger's value, else manual | the sides round at different points; a cent per position is noise |
| ledger only | pending inside the two-day settlement window, then the settlement ladder | custodians book late |
| custodian only | manual, always | a position is never created from an external file |

The thresholds are synthetic defaults; a real book sets them from the custodian's
actual rounding. Five malformed lines (no currency, non-numeric quantity, over-precise
amount, unknown currency, no id) were refused row by row, recorded in `batch_skip`,
and their positions left out of the comparison, because a refused line would
otherwise make its position look absent from the custodian.

**Why reconciliation matters more against a custodian than against an exchange.** An
exchange trade is reconciled against a counterparty with the same obligation and a
matching engine that produced the same record at the same moment. In a
prime-brokerage relationship the custodian *holds* the securities and the collateral:
its record is the legal position, it is updated asynchronously, and the ledger's
position is an instruction and a belief. A break here is not a mismatch of two
records of an event; it may be shares or collateral that are not where the book says
they are, for a business whose fee accrues daily on a balance it may not hold.

### Settlement failure: retry forward, escalate, reverse nothing

For a ledger-only break past its window the ladder resubmits through a settlement port
up to three times, waiting 4 h after the first failure and 24 h after the second, then
escalates; an escalated break is never retried again
([`11-settlement-ladder.txt`](../evidence/11-settlement-ladder.txt), a moved test clock
and a scripted gateway). One rung is one transaction (claim with `SKIP LOCKED`, ask,
record); the custodian has been told by the time the outcome is recorded, so a later
rung's failure must not roll an earlier record back. **The boundary with stage 10's
saga:** the saga compensates steps that have an inverse, synchronously, *before* its
pivot; the ladder acts after the pivot, on a position that exists and whose fees
accrue, where there is nothing to invert. It can only retry forward and escalate. The
stub gateway answers "no route configured", so in a real run every such break ends
escalated: the honest answer for a ledger with no custodian connection.

### Retention

One job, one step per table, each purge many bounded transactions
([`11-retention.txt`](../evidence/11-retention.txt)).

- **Published outbox rows**: 7 days, only there for diagnosis once sent. Unpublished
  rows are never removed.
- **Terminal TCC rows**: 30 days, and *longer than the topic retention*, checked at
  start-up. A cancelled row is what refuses a Try that arrives late.
  `reproducesTombstonePurgedTooEarly`: with the tombstone purged, a late Try froze
  100.00 HKD for an attempt that had been cancelled; with it intact the Try was
  refused ([`11-retention-tombstone.txt`](../evidence/11-retention-tombstone.txt)).
- **Finished sagas and their spent reservations**: 30 days, by the terminal log
  entry's time (a reservation has no timestamp of its own).
- **Consumed message ids**: the existing dedup purge, TTL above the topic retention.

Recovery of unfinished sagas runs first, so a saga it finishes carries a fresh
terminal entry and survives that run's purge. `OpenPositionSaga` still has no caller
in the product; the recovery is wired for the day it has one.

### Template method or strategy: the verdict

Written back to [`02-domain-model.md`](02-domain-model.md): **composition stands**,
because the skeleton is not a class (the keyset reader and the fault-tolerance rules
are shared pieces, everything else differs), the framework had already made `Step`
the template and the reader, processor and writer the injected parts, and two of the
four jobs (reconciliation, retention) do not have the chunk shape at all.

## What I rejected, and why

- **A scheduled task that loops over positions.** No cursor, no history, no instance
  identity.
- **`OFFSET` paging and a cursor held open across chunks.** `OFFSET` costs more the
  deeper the page; an open cursor does not survive a restart or a recycled connection.
- **A single fence row admitted by every chunk.** It serialises the partitions.
- **A Redis-issued fencing token with a better counter.** AOF `always` still regresses
  by the last unsynced write; the store that checks the token should issue it.
- **One `REQUIRES_NEW` per item for skipping.** Above.
- **An auto-created job repository.** Boot's starter creates it unasked.
- **A `BaseBatchJob` class.** Above.
- **Rounding a custodian's over-precise amount to fit.** It would put a number in the
  comparison that the custodian never sent.
- **Creating a position from a custodian-only holding, or reversing ledger money from
  a batch.** A batch that changes the book on an external file's say-so is a second
  source of truth.
- **A launch HTTP endpoint.** A command runner needs no authentication surface (stage
  13's) and is what cron uses.

## What I observed

- **Statistics decide a plan.** The first partition measurement had the
  per-counterparty scheme *slower* than no partitioning (11,674 ms against 5,731, runs
  in [`11-partition-skew-no-statistics.txt`](../evidence/11-partition-skew-no-statistics.txt));
  the big partition took 10.8 s alone for rows the reader reads in 1.1 s, and a thread
  sample taken then (not kept) put 157 of 190 samples in the reader's query. The
  ledger had been bulk-loaded and not analysed. With `ANALYZE` the same scheme was
  faster than no partitioning; the final figures are in
  [`11-partition-skew.txt`](../evidence/11-partition-skew.txt).
- **A page that got slower the deeper it read.** With statistics Postgres merged the
  ordered positions with the *whole* ordered trade index and read every trade entry
  below the cursor for each page (`Index Only Scan ... rows=30500`, 9 ms at the middle
  of 60,000). Repeating the cursor on the trade's own position id made the merge start
  at the cursor: chunk size 10 went from 10.3 s to 6.1 s, size 200 from 1.72 s to 1.34
  s, size 1 from 96 s to 49 s, size 5,000 unchanged
  ([`11-chunk-size-before-cursor-predicate.txt`](../evidence/11-chunk-size-before-cursor-predicate.txt)).
- **A foreign key broke an old test.** `revaluation_request → security` failed stage
  6's `TRUNCATE` (22 errors) in the full build; dropped, with the reason in the
  migration.
- **A flaky stage-9 test was a race in the test.** A pause that took no time let 100
  lock polls run before the other thread was scheduled; fixed with a 2 ms pause.
- **A condition on a bean depends on file-name order.** `@ConditionalOnBean` on the
  cutover launcher was evaluated before the cache configuration's beans existed,
  because scanned configuration classes are read in file-name order; a property
  condition does not depend on it.
- **Retry leaves no rollback count**, and a skipped item does not roll its chunk back.

## Where the plan was wrong

- Section 7's first numbers and section 10's first comparison (a read against a whole
  job) were replaced by measurements that compare like with like; the plan's "Found
  while implementing" records each.
- The plan had `pg_stat_database.xact_commit` counting transactions; it lags a run by
  many seconds. `pg_current_xact_id()` counts transactions that wrote.
- The plan had a partial index on open positions. No measurement showed it needed;
  what was needed was an index on `loan_trade (position_id, direction,
  settlement_date)`.
- The plan had a launch endpoint; a command runner replaced it.

## Where each choice breaks at 100×

- **Keyset and primary-key idempotency** hold, but every job reads `loan_position` and
  `loan_trade` by the same ranges; at 100× positions the reader's join is the
  bottleneck before the writer is. Measured only to 60,000 rows.
- **One Postgres holds the ledger and the job history.** `BATCH_*` rows grow with
  chunks; a retention for the Batch tables themselves was not built.
- **Partitions in one JVM.** Remote partitioning is out of scope; the slices are
  computed by one query at job start.
- **The lease does not renew**, above.
- **Back-dated runs use today's state.** They need history.
- **The reconciliation's key is the ledger's own position id.** A real custodian has
  its own identifiers and a mapping to maintain.
- **The ladder's gateway is a stub**, so every break walks to escalation.
- **The fence row per date** is one row; fine for one cutover, and the partitions
  verify it without blocking, but a claim waits behind an in-flight verifier, so a
  GC-paused owner with an open chunk transaction delays the next owner by its pause.
- **Outbox rows per revaluation:** a security with millions of positions writes
  millions of outbox rows and markers; the relay then has the backlog, and the
  margin-call topic has the burst.

## The oral check: how to safely re-run a batch that stopped in the middle

Start from the data: every write carries a key the store refuses twice, so any prefix
of the work can be repeated and nothing is booked twice. The framework's part is
cheaper: a completed date is refused, a failed one resumes from a cursor saved in the
same transaction as the work it describes. Then the two ways it stops. An exception
leaves `FAILED` and the cursor; launch again. A kill leaves `STARTED` and a launch is
refused until an owner that holds the lock has decided the old one is dead, marks it
failed and resumes; if the old one was only paused, it is refused at its next chunk by
the fencing token, which is issued by the database that checks it. What the resumed
run does not do is read a row that appeared behind the cursor; a rerun instance does,
and books only that.

# Stage 11 — Batch

## Problem

Nothing in the ledger books a fee yet. `accrual_entry` has a primary key, a
read endpoint and a decision record about `double` drift, and no writer: the
daily accrual has been a sentence in every earlier stage's "out of scope".
Stage 10 also left three debts that only a batch can pay: revaluing every
position of a security in one transaction does not survive a large security;
four tables (`outbox_event`, `tcc_transaction`, `credit_reservation`,
`saga_log`) grow with no purge; and a saga recovery exists that nothing calls.
Stage 9 left a fourth: the fencing token comes from Redis and a Redis restart
makes every write fail.

Stage 11 builds the batch layer with Spring Batch and drives its failure modes
to asserted or measured results: a job killed halfway, a job run twice, one bad
row in a million, a partition that trails the rest, two instances that both
think they own the cutover, a lock holder that is paused past its lease, a
position the custodian does not know about.

Five facts shape the stage.

- **A batch is a restart contract before it is a loop.** The reader, processor
  and writer are the easy part. What the stage is about is what a job that dies
  at chunk 40 of 100 leaves behind, what a second launch does with it, and how
  to tell from `BATCH_STEP_EXECUTION` and not from a log line which of those
  happened.
- **The idempotency is in the data, the restart is in the framework.** The
  framework refuses to re-run a completed job instance and resumes a failed one
  from its saved cursor. Neither is enough alone: a new instance of the same
  business day (a different launch parameter, a second deployment) must also
  book nothing twice. That is the primary key `(position_id, business_date)`,
  used with `ON CONFLICT DO NOTHING`, and the job reports the rows it did not
  insert.
- **The money invariant crosses one new boundary: the custodian's file.** A
  holding arrives as an amount and a currency in a CSV. An amount with more
  precision than its currency, or no currency, is refused row by row and does
  not enter a comparison.
- **The lock has its first caller, and it is the reason the fencing token had
  to move.** Only one instance may run the cutover for a business date. The
  lock is coarse mutual exclusion in Redis; the token that makes it safe is
  issued by the Postgres that also checks it.
- **Only some exercises belong to the product.** The accrual, revaluation,
  reconciliation and retention jobs, the settlement retry ladder and the
  fenced launcher are product code. A chunk size of one, a partition scheme
  that ignores skew, an unguarded second run, a lock with no fence and a
  Redis-issued token after a restart are for breaking. `CLAUDE.md` gives broken
  code two homes: a `reproduces<Failure>` test, or `labs/<nn>-<slug>/`. A
  process killed with `SIGKILL` needs a real process, and is a test that starts
  one.

## Settled design decisions

Decided before this spec was written. The decision record argues each; the spec
states what they commit the code to.

1. **The fencing token is a Postgres sequence value, drawn after the Redis lock
   is taken and confirmed by re-reading the lock.** Order: acquire in Redis,
   draw `nextval`, check that the lock still belongs to this holder; if it does
   not, there is no lease. This closes the window a two-command acquire has: a
   holder paused between the two commands cannot come back with a token
   higher than a later holder's, because the check that it still holds the lock
   comes after the draw. Redis keeps no counter, so a Redis restart cannot
   lower a token. The rebuild lock of stage 9 uses the same lock, so
   `sl:fence:price:*` stops being created.
2. **Batch metadata lives in the ledger's Postgres, created by Flyway.** Spring
   Batch's own schema, unchanged, as a migration; `spring.batch.jdbc.
   initialize-schema=never`. A job's history is then in the same backup, the
   same transaction manager and the same audit as the money it moved.
3. **`batch` holds job definitions and no SQL.** It depends on `application`
   and Spring Batch. Readers and writers call ports that `adapter-persistence`
   implements. `application` does not depend on Spring Batch, and `batch`
   does not depend on `adapter-persistence`; `ArchitectureIT` says so.
4. **Every reader is a keyset cursor whose position is saved in the step's
   execution context after every chunk.** `WHERE id > :last ORDER BY id LIMIT
   :n`, never `OFFSET`, and never a cursor held open across chunks. Restart is
   then a seek, and an insert or a status change between two runs cannot make
   the restarted job skip or repeat a row.
5. **Corrupt rows are found by the processor, not the reader.** The
   persistence adapter returns each row as either a valid candidate or a
   corrupt one, so one bad row cannot fail the page read that would then skip
   the whole page. The processor turns a corrupt row into a skippable
   exception. Skipped rows are written to `batch_skip` with the reason, so a
   skip is a record and not only a counter.
6. **Job identity is the business date.** `businessDate` is an identifying
   parameter; `fencingToken`, `chunkSize` and the partition scheme are not.
   Re-launching a completed date is answered "already done", not an error; a
   failed date is resumed by launching it again with the same identifying
   parameter, which is what makes the new token reach the resumed run. The
   restart operation that reuses the old execution's parameters is not used,
   because it would reuse the old token and be refused by the fence.
7. **A partition is a slice of the keyspace, and the scheme is a strategy.**
   `NONE`, `COUNTERPARTY` (the roadmap's) and `BALANCED` (equal-count id
   ranges). The first two exist to be measured against the third.
8. **Skip and retry are classified like Kafka errors were.** A corrupt row or a
   domain refusal is skipped, to a limit; a transient data-access failure is
   retried in place, a bounded number of times; a stale fencing token, an
   interrupt and everything unclassified fail the step. Skip is not
   `REQUIRES_NEW`: the record says why, with the chunk-rollback counts.
9. **The three jobs share parts, not a base class.** The keyset reader, the
   step defaults (skip, retry, listener) and the fenced launcher are composed.
   Each job supplies its own processor and writer. Stage 2's tentative
   "strategy over template method" is checked against this code and the
   conclusion is written back to `docs/decisions/02-domain-model.md`.
10. **Revaluation is inline below a limit and a batch above it.**
    `ApplyPriceUpdate` counts the security's open positions; at or below
    `ledger.revaluation.inline-limit` (default 1,000) it revalues inline, as in
    stage 10; above it, it applies the price and records a
    `revaluation_request` in the same transaction, and a dispatcher launches
    the revaluation job for `(isin, priceVersion)`. A per-position marker
    `(position_id, price_version)` makes a re-run of a chunk raise each margin
    call once.
11. **Reconciliation is a third job with its own inputs.** Custodian holdings
    are read from a CSV, loaded to a table, and compared with the ledger by a
    full outer join. One break per position, classified by the first matching
    rule in the order: quantity, then value, then presence. Each class has a
    fixed disposition; the thresholds are configuration with defaults the
    record justifies.
12. **A settlement failure is retried forward and then escalated; no ledger
    money is reversed by a batch.** A position that exists here and not at the
    custodian past its settlement window gets a bounded retry ladder against a
    settlement port, then goes to a manual queue. The boundary with stage 10's
    saga: the saga compensates steps that have an inverse, before its pivot;
    a batch that finds a fault after the pivot has nothing to invert, and
    can only retry and escalate.
13. **Retention is a job.** Published outbox rows, terminal TCC and reservation
    rows, finished saga logs and consumed-message ids are purged past a
    retention that exceeds the longest delay that could still matter; saga
    recovery runs as a step of the same job. The retention rule for each table
    is stated in the record.
14. **Everything is off unless configured.** `ledger.batch.enabled` defaults to
    `false`: with it off no job, launcher, scheduler or Batch bean exists. The
    Batch tables are created regardless, because the schema is Flyway's.

## Scope

### Module topology and dependencies

No child POM carries a `<version>`. Spring Batch comes from the Boot BOM.

| Module | Gains | New dependencies |
|---|---|---|
| `domain` | nothing | none |
| `application` | `accrual` (candidates, ledger, accrue), `revaluation` (request, marker), `reconciliation` (holdings, classifier, breaks, settlement ladder), `batch` (run summary, fenced run, retention); `cache.FencingTokens` | none |
| `adapter-persistence` | JDBC adapters for every new port; migrations V11 (fencing sequence), V12 (Batch schema), V13 (accrual skips, revaluation, reconciliation, retention) | none |
| `batch` | the four job definitions, the keyset reader, step defaults, partitioners, the run-summary listener, the CSV holdings reader | Spring Batch |
| `bootstrap` | `ledger.batch.*` properties and wiring, the launcher, the schedulers | none in main |
| `adapter-messaging` | nothing | none |

### Fencing token (first section)

- `FencingTokens.next()` is a port in `application.cache`; `JdbcFencingTokens`
  draws from `fencing_token_seq`. `RedisLock` takes a `FencingTokens` and no
  fence prefix. A lease exists only if the lock is still held after the draw.
- `reproducesPausedHolderWithARedisCounter`: the stage-9 acquire, with the
  token drawn in a second step, gives a lease whose lock expired a token
  that is higher than the next holder's. And the same schedule against the new
  lock gives it no lease.
- The stage-9 test `reproducesFenceRefusingEveryWriteAfterARedisRestart...`
  becomes a passing test of the same schedule: writes continue after `FLUSHALL`.
- Two contexts against one Redis and one Postgres: tokens from both are
  strictly increasing in acquisition order.

### Accrual job

- `dailyAccrual(businessDate)`. Reader: active positions on the date (open or
  part-returned, first open settlement date on or before it), with the
  security's current price. Processor: `AccrueDay`, which restores the position
  and calls `AccrualEntry.accrue`. Writer: one `INSERT ... ON CONFLICT DO
  NOTHING` per chunk; the counts of inserted and already-present rows go to the
  step's execution context.
- Chunk size is a parameter. Three sizes are timed on one synthetic ledger
  (`measurement` tag).
- Restart: a writer that fails at a chosen chunk; `BATCH_STEP_EXECUTION` and
  its context are printed; the second launch reads only what remains.
  `HardKillRestartIT` runs the job in a child JVM, kills it with `SIGKILL`,
  prints the table, shows the second launch refused while the first execution
  is still `STARTED`, marks it failed and resumes.
- Idempotency: the same date twice is answered "already done". An operator
  override adds an identifying `rerun` number, which makes a second job
  instance of the same date; it books nothing and reports every row as already
  present, because the primary key refuses each.
- Skip: rows that cannot be restored, and rows the domain refuses, are skipped
  to `ledger.batch.skip-limit` (default 10) and recorded; the eleventh fails
  the step. A transient failure is retried three times. The comparison with
  stage 5's `REQUIRES_NEW` is measured as chunk rollbacks and re-reads.
- Partition: the three schemes on a skewed ledger (one counterparty holds most
  of the positions); the elapsed time of each partition and of the step.
- Listener: a `JobExecutionListener` records a `RunSummary` (job, parameters,
  status, duration, read/write/skip counts) through `BatchRunMetrics`; stage 12
  binds it to Micrometer.

### Fenced launch

- `FencedRun.launch(resource, ttl, body)`: takes the lock, hands the body the
  lease, releases. Empty when another instance holds it.
- `RunDailyAccrual`: locks `accrual:<date>`, launches the job with the token as
  a non-identifying parameter, and admits the token in the writer's
  transaction through the stage-9 `WriteFence`.
- With two contexts on one database and one Redis, launched together: one runs,
  one gets "not the owner", and the ledger has one entry per position.
  A holder paused past its lease and resumed after another instance took the
  lock is refused at its next write; its chunk rolls back; the step fails and
  can be resumed by the current owner.

### Revaluation job

- `revaluation(isin, priceVersion)`; keyset over the security's open positions;
  processor `Revalue`; writer raises the margin calls through the outbox and
  the marker in one transaction.
- `ApplyPriceUpdate` inline path unchanged at or below the limit. Above it:
  price applied, request recorded, no position read. The dispatcher launches
  pending requests and marks them done; a request for a version that has been
  overtaken by a newer price is marked superseded and its job is not launched.
- A security with 50,000 positions is revalued by the job in chunks of 500 and
  the inline path's single read is measured against it (`measurement`).

### Reconciliation job

- `reconciliation(businessDate, snapshot)`: step 1 loads a CSV of custodian
  holdings (`position_id, quantity, market_value, currency`) to
  `custodian_holding`, rejecting a row with a malformed amount, an over-precise
  amount or an unknown currency; step 2 compares.
- Classes and dispositions:

  | Class | Meaning | Disposition |
  |---|---|---|
  | `QUANTITY_MISMATCH` | both have it, quantities differ | manual, always |
  | `VALUE_MISMATCH` | quantities equal, value differs | auto-accept if within tolerance (`max(1.00, 0.01%)` of ours), else manual |
  | `OURS_ONLY` | ledger has an open position, custodian has none | pending inside the settlement window, then the retry ladder |
  | `THEIRS_ONLY` | custodian holds one the ledger does not know | manual, always; never create a position from an external file |

- One deliberate break of each class is planted and asserted.

### Settlement failure

- `SettlementRecovery` runs the ladder for an `OURS_ONLY` break past its
  window: up to three resubmissions through `SettlementGateway`, spaced by
  configuration on an injected clock; a success resolves the break; the last
  failure escalates it to the manual queue with the attempts recorded. An
  escalated break is never retried again by the ladder.

### Retention

- `retention` job: steps purge published outbox rows, terminal TCC and
  reservation rows, finished saga logs and consumed message ids older than
  their retention; a last step runs `SagaRecovery.recoverAll()`. Each purge
  reports rows removed. A row that must outlive a delayed message (a TCC
  tombstone) is not removed before its retention; asserted with a delayed try
  after a purge inside and outside the retention.

### Manual run

- `local` profile turns `ledger.batch.enabled` on, with a launch endpoint
  disabled and a scheduler cron for the accrual. A transcript in evidence:
  run the accrual for one date twice, then the reconciliation on a hand-made
  CSV.

## Out of scope

- A Batch admin UI, remote partitioning over a broker, and Spring Cloud Task.
- Position history: a back-dated run uses the position as it is now, and the
  record says where that breaks (a position returned since).
- Real custodian connectivity: the file and the settlement port are stubs.
- Micrometer, dashboards and alerts on job age or failure (stage 12); the
  `RunSummary` is the hook.
- Multi-currency FX and non-flat accrual rules in the store (the stage-6 limit
  stays: only `FlatRate` is stored).
- Authentication on any launch path (stage 13).

## Constraints

- `domain` does not change.
- `./mvnw verify` at the repository root stays green after every section.
- `application` gains no Spring Batch, JDBC or Redis type; `batch` has no SQL.
- No test depends on a running Compose stack; Postgres and Redis come from
  Testcontainers.
- Time-dependent logic takes an injected `Clock`. Where elapsed real time is
  the subject (a lease, a partition tail), the test says so and has `@Timeout`.
- Reproduced failures are named `reproduces<Failure>`, carry
  `// See docs/decisions/11-batch.md`, and assert an observable outcome (a row
  count, a table value), not message text alone.
- Numbers in a decision record come from evidence files measured here.
- No secret in any committed file. All data is synthetic.

## Acceptance criteria

1. With batch off, behaviour is unchanged and stage 1–10 tests pass.
2. A Redis restart does not make writes fail; a paused holder cannot obtain a
   token higher than a later holder's; the rebuild lock leaves no `fence` key.
3. The accrual job books one entry per active position for a date, in chunks,
   with the reader's cursor saved per chunk.
4. A job failed at a chosen chunk resumes from the saved cursor; the
   `BATCH_STEP_EXECUTION` rows before and after are in evidence. A `SIGKILL`ed
   job is `STARTED`, refused, then resumable.
5. A second run of a date books nothing and says so, both through the
   framework's instance check and, on a fresh instance, through the primary
   key.
6. A corrupt row is skipped and recorded; the limit is enforced; a transient
   failure is retried; the comparison with `REQUIRES_NEW` is written from
   measured counts.
7. Three chunk sizes and three partition schemes are timed on one dataset; the
   skewed partition's tail is shown and fixed by the balanced scheme.
8. A back-dated run for yesterday works and creates a distinct job instance.
9. The run summary is recorded for a completed and a failed run.
10. Two instances launching one date: one runs. A lock holder paused past its
    lease is refused at the fence.
11. The revaluation job raises one margin call per position with a shortfall,
    once, however many times a chunk is re-run; `ApplyPriceUpdate` above the
    limit reads no positions.
12. Reconciliation classifies the four planted breaks as specified, and refuses
    the malformed rows of the CSV.
13. The settlement ladder retries the configured number of times, then
    escalates; the record states its boundary with the saga.
14. Retention removes what it should and keeps a tombstone inside its retention.
15. `ArchitectureIT` passes with the new rules.
16. `./mvnw clean verify` at the repository root is green.

### Evidence

`docs/evidence/11-*.txt`, text only, measured on this machine: the fence
reproduction and its fix; the step tables around a failure and a kill; the
idempotent second run; the skip comparison; chunk-size and partition timings;
the two-instance launch and the zombie refusal; the revaluation timing; the
reconciliation output; the ladder; retention counts; the manual transcript.

## Done when

The four `CLAUDE.md` deliverables exist: this spec, the plan, the code with every
criterion verified, and `docs/decisions/11-batch.md`. That record covers the
fourteen settled decisions against their alternatives, and:

- why Spring Batch and not a scheduled task, answering "restart semantics" and
  "auditable history" with the table rows;
- what a job that stopped in the middle leaves and how it is safely re-run,
  starting from idempotency;
- Spring Batch skip against `REQUIRES_NEW`: the same problem or not;
- why reconciliation matters more against a custodian than against an
  exchange;
- the settlement ladder against the saga: the boundary;
- the template-versus-strategy verdict, also written back to record 02;
- the fencing-token fix, also written back to record 09;
- where each choice breaks at 100x.

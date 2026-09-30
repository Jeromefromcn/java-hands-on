# Stage 11 — Batch · Implementation Plan

Spec: [`docs/specs/11-batch.md`](../specs/11-batch.md)

One section per commit, in order. Implement a section, tick its checkbox in
the same commit, then commit. `./mvnw clean verify` at the repository root
after every section that touches the main build.

## Ground rules for every section

- Packages:
  - `com.jerome.slledger.application.accrual`, `.revaluation`,
    `.reconciliation`, `.batch` for ports, use cases and pure logic;
    `application.cache` gains `FencingTokens`;
  - `com.jerome.slledger.adapter.persistence.accrual`, `.batch`,
    `.reconciliation`, `.revaluation`, `.retention` for JDBC adapters;
  - `com.jerome.slledger.batch.support`, `.accrual`, `.revaluation`,
    `.reconciliation`, `.retention` for Spring Batch definitions;
  - `com.jerome.slledger.bootstrap.config` for `ledger.batch.*`.
- Pure unit tests end in `Test` (in the module that owns the class, against
  fakes); anything needing Docker ends in `IT`. Every job IT lives in
  `bootstrap/src/test/.../batch` because only `bootstrap` has both adapters,
  the batch module and both test-jars on one classpath. Postgres and Redis come
  from `SharedPostgres` and `SharedRedis`; each IT starts a context on a fresh
  database.
- A test that asserts wrong behaviour is named `reproduces<Failure>` and
  carries `// See docs/decisions/11-batch.md`.
- Time-dependent logic takes a `Clock`. Where elapsed real time is the subject
  the test says so and has `@Timeout`.
- Evidence is written by tests to `<module>/target/evidence/11-*.txt` and copied
  by hand to `docs/evidence/`. Measurement tests are `@Tag("measurement")` and
  run under `-Pmeasure`.
- The main build runs on the JDK `.sdkmanrc` pins (21). Test data is synthetic.
- Full `./mvnw clean verify` after every section, not a filtered run, run in a
  throwaway worktree of the committed HEAD when the working tree is dirty.

## Decisions made while planning

Each is carried to the decision record.

1. **Business-date parameter is a `LocalDate` identifying parameter named
   `businessDate`.** The job key is a hash of the identifying parameters, so
   the instance is `(job name, date)`.
2. **Synthetic datasets are built in SQL** (`generate_series`), not through the
   application: 20,000 positions for the chunk-size and partition
   measurements, 50,000 for the revaluation timing. Building through
   `OpenPosition` would time the test's setup.
3. **A partition worker's execution context holds `counterparty`, `idFrom`,
   `idTo`.** A reader reads only what it is given, so a partition scheme
   is a way of writing three keys into a map.
4. **The lease's fencing token reaches the writer as a non-identifying job
   parameter read through `@StepScope`.** Passing it through a mutable field on
   a singleton would leak between concurrent jobs in one JVM.
5. **A skipped item is written to `batch_skip` from a `SkipListener` in its own
   transaction.** A listener that ran in the chunk's transaction would lose the
   record on the rollback that skipping causes.
6. **The child process of the kill test is this build's own test classpath**
   (`java -cp` of the running JVM with a small `main`), so there is no second
   packaging of the application to keep in step.

## Review focus

Failure modes the spec implies but no exercise is *about*. Each is pinned by a
test in the section named.

1. **A `Money` in the custodian CSV never loses digits or a currency** (section
   11): an over-precise amount, a missing currency and an unknown currency are
   refused row by row.
2. **A restarted run never reads a row twice and never skips one** (section 5),
   including when a position is inserted between the failure and the restart.
3. **Skipping does not hide a wrong total:** skipped, written and already-present
   counts add up to what was read (sections 4, 6).
4. **A paused holder never gets a higher token than a later holder** (section 1).
5. **A chunk that fails at the fence leaves no entry behind** (section 9).
6. **No thread, scheduler or Batch bean exists with batch off** (section 14).

---

## [x] 1 · The fencing token moves to Postgres

**Change**
- V11: `CREATE SEQUENCE fencing_token_seq`.
- `application.cache.FencingTokens` (`long next()`); `JdbcFencingTokens` in
  `adapter-persistence`.
- `RedisLock(redis, lockPrefix, FencingTokens)`: Lua acquires only; after the
  acquire the token is drawn; a second script confirms the lock still holds
  this owner, else the lease is discarded and empty is returned. No fence key.
- `CacheConfiguration` and the stage-9 tests updated; the stage-9 lab keeps its
  own hand lock.

**Tests**
- `reproducesPausedHolderWithARedisCounter`: a test-only two-step acquire; the
  paused holder returns with a higher token than the current one.
- `pausedHolderGetsNoLease`: the same schedule on the product lock gives empty.
- The stage-9 Redis-restart test becomes `writesContinueAfterARedisRestart`.
- `tokensAreIncreasingAcrossTwoContexts`.
- No `sl:fence:*` key exists after a rebuild.

**Acceptance**
- The four assertions above pass; `docs/evidence/11-fence-*.txt` written;
  `docs/decisions/09-caching.md` gains the resolution.

---

## [x] 2 · Batch wiring and schema

**Change**
- `batch` POM: `application`, `spring-boot-starter-batch-jdbc`; test deps as
  needed. Convergence conflicts resolved with a managed entry each.
- V12: Spring Batch 6.0.5's PostgreSQL schema, verbatim, with its source noted.
- `application.batch.RunSummary`, `BatchRunMetrics` (log implementation).
- `bootstrap`: `BatchConfiguration` behind `ledger.batch.enabled`, `JobRepository`
  and `JobOperator` on the ledger's DataSource and transaction manager;
  `spring.batch.job.enabled=false`, `spring.batch.jdbc.initialize-schema=never`.
  Boot's Batch auto-configuration is excluded when the flag is off.
- `ArchitectureIT`: `application` has no Spring Batch type; `batch` has no
  JDBC type and no `adapter.persistence` dependency.
- `CLAUDE.md` module list is unchanged (`batch` is already listed).

**Tests**
- `BatchWiringIT`: with the flag on, a one-step job runs and its rows are in the
  Batch tables; with it off, no `JobOperator` bean exists.

**Acceptance**
- `./mvnw clean verify` green; `docs/evidence/11-dependency-convergence.txt`.

---

## [x] 3 · Accrual ports and adapters

**Change**
- `application.accrual`: `AccrualCandidate` (`Valid(LoanPosition, Money
  unitPrice)` or `Corrupt(id, reason)`), `Slice`, `AccrualCandidates.page(
  businessDate, slice, afterId, limit)`, `AccrualCandidates.slices(scheme,
  parts)`, `AccrualLedger.book(List<AccrualEntry>)` returning inserted and
  present counts, `AccrueDay.accrue(candidate, date)`.
- V13: `batch_skip(job, business_date, item_key, reason, recorded_at)`; an index
  for the reader (`loan_position (id) WHERE status <> 'CLOSED'`).
- JDBC adapters. Active on a date: not closed, and its opening trade's
  settlement date is on or before it.

**Tests**
- `AccrueDayTest`: rounding once, a corrupt row throws the skippable exception,
  negative value refused.
- `JdbcAccrualCandidatesIT`: keyset pages are disjoint and complete; a row
  with an unknown rule type comes back `Corrupt`; a position opening after the
  date is not returned; slices cover the keyspace with no overlap.
- `JdbcAccrualLedgerIT`: the second book of the same entries inserts none.

**Acceptance**
- Tests green; money round trip in HKD, JPY and BHD through `accrual_entry`.

---

## [ ] 4 · The accrual job: chunks, parameters, idempotency, run summary

**Change**
- `batch.support.KeysetReader` (saves the last key in the context),
  `StepDefaults`, `RunSummaryListener`.
- `batch.accrual.AccrualJob`: reader, processor, writer, `businessDate`
  parameter validator; counts of inserted and present rows in the context.
- `RunAccrual` (application-facing launcher in `batch`): launch a date; the
  already-complete answer is `ALREADY_DONE`; `rerun` override.
- `bootstrap` wiring; `LogBatchRunMetrics`.

**Tests**
- `AccrualJobIT`: N positions, one entry each; chunk count equals `ceil(N /
  chunk)`; the context holds the last key; a second launch of the date is
  `ALREADY_DONE` with no new rows; a `rerun` instance books nothing and reports
  every row present; a back-dated date is a distinct instance; a listener
  summary is recorded for a completed run.

**Acceptance**
- The tests pass; `docs/evidence/11-idempotent-rerun.txt`,
  `11-job-instances.txt`.

---

## [ ] 5 · Restart, and the table that proves it

**Change**
- A test-only failure injector on the writer (fail at chunk k, once).
- `HardKillMain` (test) and `HardKillRestartIT`.

**Tests**
- `RestartFromCursorIT`: fail at chunk 4 of 10; print `BATCH_STEP_EXECUTION`
  (status, read, write, commit and rollback counts) and the saved context; a
  position inserted with an id inside the already-read range and one after
  it; relaunch reads exactly the remaining rows; the new step execution's read
  count equals the rows after the cursor; totals equal the full ledger plus the
  late one after the cursor and not the one before it.
- `HardKillRestartIT`: child JVM killed with `SIGKILL` after N commits; the
  rows show `STARTED`; a launch is refused; `recover` marks it failed; the
  relaunch completes with one entry per position.
- `reproducesRestartFromTheBeginningWithoutACursor`: a reader with no saved
  state re-reads every row (idempotence saves the data; the counts show the
  waste).

**Acceptance**
- `docs/evidence/11-restart-step-table.txt`, `11-hard-kill.txt`.

---

## [ ] 6 · Skip and retry

**Change**
- `StepDefaults` skip and retry classification; `SkippedItems` port and
  `JdbcSkippedItems`; skip listener in its own transaction.

**Tests**
- `SkipRetryIT`: five corrupt rows among 200 with limit 10: 195 entries, five
  `batch_skip` rows, `PROCESS_SKIP_COUNT` 5, `ROLLBACK_COUNT` measured; 11
  corrupt rows fail the step at the limit; a transient failure at the writer
  is retried and succeeds; a stale-token exception is not retried or skipped.
- `SkipVersusRequiresNewIT`: the stage-5 shape (one `REQUIRES_NEW` per item in
  a loop) against the framework's skip on the same data: entries booked,
  rollbacks, reads, and what each leaves when the process dies mid-way.

**Acceptance**
- `docs/evidence/11-skip-vs-requires-new.txt`; the decision record can state
  whether the two solve the same problem from those counts.

---

## [ ] 7 · Chunk size, measured

**Tests**
- `ChunkSizeMeasurementIT` (`measurement`): 20,000 positions; chunk sizes 10,
  200 and 5,000; each run on a fresh accrual table, three runs, median; commit
  counts and elapsed time.

**Acceptance**
- `docs/evidence/11-chunk-size.txt`.

---

## [ ] 8 · Partitioning, and the tail

**Change**
- `batch.accrual.PartitionSchemes` (`NONE`, `COUNTERPARTY`, `BALANCED`),
  `AccrualPartitioner`, the partitioned step wrapping the worker step, a
  bounded task executor.

**Tests**
- `PartitionedAccrualIT`: each scheme books the same entries; the partitions
  are disjoint and complete.
- `PartitionSkewMeasurementIT` (`measurement`): 20,000 positions with one
  counterparty holding 70%; elapsed per partition and per step for the three
  schemes; the counterparty scheme's tail is shown and `BALANCED` narrows it.

**Acceptance**
- `docs/evidence/11-partition-skew.txt`.

---

## [ ] 9 · One owner for the cutover: lock, token, fence

**Change**
- `application.batch.FencedRun`; `batch.accrual.RunDailyAccrual` takes the lock,
  passes the token as a non-identifying parameter, and the writer admits it
  through `WriteFence` in the chunk's transaction.
- Wiring behind `ledger.cache.enabled` (Redis) when a lock bean is present;
  without Redis a local lock is not offered: the launcher requires it.

**Tests**
- `TwoInstancesIT`: two contexts, one Postgres, one Redis, launched together:
  exactly one runs; the ledger has one entry per position; the other reports
  `NOT_OWNER`.
- `reproducesUnfencedZombieDoubleWrite`: a holder paused past its lease writes
  without a fence, and a second owner writes too: the same date is written by two
  owners (entries are not duplicated only because of the primary key; the test
  counts the writes attempted by each).
- `pausedHolderIsRefusedAtTheFence`: the fenced writer's chunk rolls back, the
  step fails; the current owner resumes it.

**Acceptance**
- `docs/evidence/11-two-instances.txt`, `11-zombie-refused.txt`.

---

## [ ] 10 · Revaluation job and the inline limit

**Change**
- V14: `revaluation_request(isin, price_version, status, created_at)`;
  `margin_call_marker(position_id, price_version)`.
- `application.revaluation`: ports, `Revalue`, `RaiseMarginCalls`,
  `RevaluationDispatcher`. `ApplyPriceUpdate` above the limit records a request
  and reads no position. `SecurityPositions.countOpenIn`, keyset paging.
- `batch.revaluation.RevaluationJob`; a scheduler in `bootstrap`.

**Tests**
- `ApplyPriceUpdateThresholdTest`: at the limit inline; one above it, request
  recorded, `openIn` never called.
- `RevaluationJobIT`: shortfalls raise one call each; re-running the same
  chunk raises none twice; a position that cannot be valued is skipped and
  recorded; a request overtaken by a newer version is `SUPERSEDED`.
- `RevaluationScaleMeasurementIT` (`measurement`): 50,000 positions, inline read
  against the job, elapsed and peak heap around the read.

**Acceptance**
- `docs/evidence/11-revaluation-scale.txt`; the template-versus-strategy verdict
  is written back to `docs/decisions/02-domain-model.md` in this commit.

---

## [ ] 11 · Reconciliation

**Change**
- V15: `custodian_holding(snapshot_id, position_id, quantity, value_amount,
  value_currency)`, `recon_break(snapshot_id, position_id, kind, ...,
  disposition, resolved_at)`.
- `application.reconciliation`: `BreakClassifier`, `ReconBreak`, `Tolerance`,
  ports. `batch.reconciliation.ReconciliationJob`: CSV load step (rejects
  malformed rows to `batch_skip`), compare step.

**Tests**
- `BreakClassifierTest`: every class, the priority order, the tolerance edge,
  currency mismatch on the value.
- `ReconciliationJobIT`: one planted break of each class among clean rows;
  malformed CSV rows refused; a re-run of the same snapshot adds no break.

**Acceptance**
- `docs/evidence/11-reconciliation.txt`.

---

## [ ] 12 · Settlement failure: the ladder

**Change**
- `application.reconciliation.SettlementRecovery`, `SettlementGateway` port
  (test stub), `settlement_attempt` state on `recon_break`.

**Tests**
- `SettlementRecoveryTest` on a controlled clock: the spacing of attempts,
  success on the second attempt resolves, the third failure escalates and
  stops, an escalated break is not retried.
- `SettlementRecoveryIT`: the ladder against the table, driven by the
  reconciliation job.

**Acceptance**
- `docs/evidence/11-settlement-ladder.txt`.

---

## [ ] 13 · Retention and saga recovery

**Change**
- `application.batch.Retention` port and adapters; `batch.retention.RetentionJob`
  (steps: outbox, TCC, reservations, saga log, consumed ids, recovery).

**Tests**
- `RetentionJobIT`: old published and unpublished outbox rows (only published
  removed), terminal and in-flight TCC rows, finished and unfinished saga logs;
  counts per step; `reproducesTombstonePurgedTooEarly`: a delayed try after a
  purge inside retention is refused, after a purge outside it is accepted.

**Acceptance**
- `docs/evidence/11-retention.txt`.

---

## [ ] 14 · Off unless configured, schedulers, manual run

**Change**
- `BatchSchedulers` (accrual cron, revaluation dispatcher, retention),
  `application-local.yml` turns batch on; `infra` needs nothing new.
- `MessagingPropertiesTest`-style `BatchPropertiesTest`.

**Tests**
- `BatchOffIT`: with the flag off, no Batch bean and no thread named for batch;
  the earlier ApplicationScanIT still passes.
- Manual transcript: accrual twice, reconciliation on a hand-made CSV.

**Acceptance**
- `docs/evidence/11-manual-run.txt`.

---

## [ ] 15 · Decision record, roadmap, README

**Change**
- `docs/decisions/11-batch.md` per the spec's "Done when"; the fence
  resolution in record 09; the verdict in record 02 (if not already);
  ROADMAP stage-11 boxes ticked with links; README status.

**Acceptance**
- Every number in the record is in an evidence file; `./mvnw clean verify`
  green on the committed HEAD.

---

## Found while implementing

**Section 2.**
- Only `spring-batch-core` is declared, not Boot's Batch starter: the starter's auto-configuration
  creates the job repository whether or not `ledger.batch.enabled` is set, which would break "off
  unless configured". `@EnableBatchProcessing` and `@EnableJdbcJobRepository` are on a
  conditional configuration instead. `spring.batch.*` properties therefore do not exist.
- V12 is 6.0.5's PostgreSQL script verbatim. The `ledger.batch.*` properties arrive with section 4.
- `ArchitectureIT`'s rule on `batch` keeps `allowEmptyShould(true)` until the first class lands in
  section 4.

**Section 3.**
- The partial index `loan_position (id) WHERE status <> 'CLOSED'` in the plan is not created: the
  reader orders by the primary key and filters, and no measurement showed it needed. What was needed
  instead is `loan_trade (position_id, direction, settlement_date)`: "active on the date" asks for
  the opening trade once per position, and `loan_trade` had no index on `position_id`, so the
  first draft of the query scanned the table per row. And `loan_position (counterparty_id, id)` for
  the counterparty slice. Both are in V13.
- A position with no BORROW trade at all is not active on any date. Product code always writes the
  trade with the position, so this only matters to fixtures, which now insert trades.
- `AccrualRuleColumns` became public (`toRule`) so the accrual adapter reuses the one place that
  turns a stored rule into a `FlatRate`, instead of a second copy that could disagree.

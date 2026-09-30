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

## [x] 4 · The accrual job: chunks, parameters, idempotency, run summary

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

## [x] 5 · Restart, and the table that proves it

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

## [x] 6 · Skip and retry

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

## [x] 7 · Chunk size, measured

**Tests**
- `ChunkSizeMeasurementIT` (`measurement`): 20,000 positions; chunk sizes 10,
  200 and 5,000; each run on a fresh accrual table, three runs, median; commit
  counts and elapsed time.

**Acceptance**
- `docs/evidence/11-chunk-size.txt`.

---

## [x] 8 · Partitioning, and the tail

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

## [x] 9 · One owner for the cutover: lock, token, fence

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

## [x] 10 · Revaluation job and the inline limit

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

## [x] 11 · Reconciliation

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

## [x] 12 · Settlement failure: the ladder

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

## [x] 13 · Retention and saga recovery

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

## [x] 14 · Off unless configured, schedulers, manual run

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

**Section 4.**
- A step-scoped bean is proxied by class, so its declared type cannot be a final class: the reader
  bean is declared as `ItemStreamReader<AccrualCandidate>` and injected by qualifier.
- The writer's running totals (`accrual.inserted`, `accrual.alreadyPresent`) live in the step's
  execution context, so they are saved with each chunk and continue across the executions of one
  instance: after a restart the second execution's context holds the total of both.
- `FailurePoint` (batch.support) is a production hook with a no-op default, as the saga's was, so
  section 5 can fail a chunk between the reader advancing and the commit. It is in this commit
  because the writer calls it.
- The `ArchitectureIT` rule on `batch` no longer needs `allowEmptyShould(true)` but keeps it: an
  empty selection is still legitimate for a rule about what a package must not depend on.
- The run-summary listener counts only top-level steps. A partitioned step's manager already holds
  its workers' sums, so counting the worker rows would double them (used from section 8).

**Section 5.**
- After an exception the failed step's row says `read 40, write 30, commit 3, rollback 1`: the
  read counter includes the chunk that was rolled back, so `READ_COUNT` is not a progress figure.
  What survives is the saved cursor (`accrual.lastKey` = `P000030`) and `COMMIT_COUNT`. After a
  `SIGKILL` the row says `STARTED`, `EXECUTING`, `read 30`: the counters are those of the last
  commit, because nothing ran after the kill to update them.
- A launch after the kill is refused (`ALREADY_RUNNING`) until `JobOperator.recover` marks the
  execution failed. `recover` does not decide the process is dead: the operator does, and
  the decision record says what breaks if they are wrong (two live runs of one date; the fence of
  section 9 is what stops the older one).
- A position that arrives behind the saved cursor while the job is down is not booked by the
  resumed run; a `rerun` instance books exactly that one and reports the other 100 as present.
  Asserted, and stated in the decision record as the price of a keyset cursor.
- The kill test uses the running JVM's own classpath for the child (`java.class.path` under
  failsafe is a manifest-only jar, which `java -cp` follows), so there is no second packaging.

**Section 6.**
- `retryLimit(3)` means three retries after the first attempt: a failure that never clears is four
  attempts at the chunk (measured, six writer calls for chunks 1 and 2 plus four). And a retried chunk
  leaves `ROLLBACK_COUNT` at 0: the row does not show that a retry happened; the count of writer
  attempts, which only the test can see, does.
- A skipped item does not roll its chunk back: five skips in 200 rows is ten commits and no
  rollback. The eleventh skip, past the limit, rolls back the chunk it is in and fails the step
  with `FatalStepExecutionException: Unable to process chunk`; the entry that tripped the limit has
  no `batch_skip` row (the limit was already spent).
- `pg_stat_database.xact_commit`, planned for the transaction count, lags a run by many seconds
  (each backend flushes its counters only after being idle for some time), so the comparison counts
  write transactions with `pg_current_xact_id()` instead. It counts transactions that wrote, which
  is the cost being compared.
- At 200 rows the `REQUIRES_NEW` loop was not slower than the batch (265 ms against 397 ms): the job
  repository's own bookkeeping is a fixed cost that 200 rows do not amortise. The per-row commit is
  what section 7's chunk-size-1 run prices at 20,000 rows.

**Section 7.**
- Chunk size 1 was added to the three planned sizes, run once, as the price of a commit per row.
  It took 124.7 s for 20,000 rows (160 rows/s) against 1.6 s at 200 (12,539 rows/s).
- The first run of each context is slower than the next two (21.6 s against 10.3 and 10.0 s at chunk
  size 10; 3.4 s against 1.6 s at 200): JIT and pool warm-up. The table shows all runs and takes the
  median, so a single cold run does not decide the number.
- 5,000 is 18% faster than 200 (1.30 s against 1.60 s) for 25 times the rows a failure redoes, which
  is the trade the decision record weighs.

**Section 8.** Three things measured here changed earlier sections' numbers.
- *Statistics.* The first partition-skew run had the counterparty scheme *slower* than no
  partitioning at all (11.1 s against 5.4 s), and the big partition alone took 10.8 s for 42,000 rows
  where the reader by itself took 1.1 s for them. A thread sample of the run put 157 of 190 samples in
  the reader's query. The cause was planner statistics: the ledger had just been bulk-loaded and not
  analysed, and autovacuum's analyse runs up to a minute later. `BatchLedger` now runs `ANALYZE` after
  seeding, as a real ledger has statistics; the first result is kept in
  `11-partition-skew-no-statistics.txt`.
- *The reader's cost depended on how deep it was.* With statistics, Postgres chose a merge semi join
  between the ordered positions and the *whole* ordered index of trades, and read every trade entry
  below the cursor for each page (EXPLAIN: `Index Only Scan ... rows=30500`, 9 ms, for a page at the
  middle of 60,000). Repeating the cursor as a condition on the trade's own position id makes the merge
  start at the cursor. Chunk size 10 went from 10.3 s to 6.1 s, size 200 from 1.72 s to 1.34 s, size 1
  from 96 s to 49 s; size 5,000 (four pages) did not change. Section 7's evidence file is the final
  one; the run before the predicate is `11-chunk-size-before-cursor-predicate.txt`.
- Partitioning does not need `gridSize` here: the scheme decides the number of slices, so
  `AccrualPartitioner` ignores it and `BatchSettings.partitions` is the balanced scheme's target.
- The manager step is named `accrual` and the workers `accrualWorker:partitionN`, so the run
  summary's rule "skip names containing `:partition`" counts each row once. With no partitioning
  there is no manager: the chunk step is `accrual`, and every test that reads its context by that
  name is unchanged.

**Section 9.**
- A single fence row would have serialised the partitions: `WriteFence.admit` is an upsert, so every
  chunk of every partition would take the row lock and hold it to commit, and the chunks could not
  overlap. The fence gained `verify(resource, token)`, which only reads the row `FOR SHARE`: many
  verifiers of one owner run side by side, and a later owner's `admit` (the run's *claim*, made once
  by a listener before the first step) waits for the ones in flight and then makes every later verify
  of the older token fail. `WriteFenceIT` pins that: two verifiers hold the share lock at once, a claim
  waits behind them, and the older token is refused after it.
- The zombie scenario needs the second owner to be *able* to launch: the first owner's execution is
  still `STARTED` in the repository, so the framework refuses (`ALREADY_RUNNING`). `RunDailyAccrual`
  therefore recovers a stale `STARTED` execution when, and only when, it holds the lock: a live
  owner holds the lock, so an owner that lost it is dead or paused, and the fence covers the second
  case. This is also what turns a `SIGKILL` into automatic recovery once the lease has expired.
- The token is a non-identifying job parameter, so a date that failed under one owner resumes as the
  same job instance under the next owner with the next owner's token. The other way (restarting the
  old execution with its own parameters) would reuse the dead owner's stale token and be refused.
- Two contexts in one JVM stand in for two instances: what they share is only Postgres and Redis.
  The unfenced reproduction shows what the fence adds: the woken owner presented 50 rows to the
  ledger after the other owner had finished the date, and only the primary key kept them out.
- The lock lease does not renew. A run longer than `ledger.batch.lock-ttl` loses its lock under it and
  is taken over; the fence makes that safe and the cost is a run that is resumed by the other
  instance and refused on the first. The decision record says so under "where it breaks".
- `RunDailyAccrual` is a bean only when `ledger.cache.enabled` is (the lock is the cache
  configuration's). A `@ConditionalOnBean(DistributedLock)` on it evaluated too early, because
  component-scanned configuration classes are read in file-name order (`BatchConfiguration` before
  `CacheConfiguration`); a property condition does not depend on that order.

**Section 10.**
- The first scale measurement compared a read with a whole job and told nothing. The final one
  measures three things on 50,000 positions: the inline read alone (1.4 s, 19.8 MB held, 405 bytes
  per position), the whole inline update (14.7 s in one transaction with the security's row locked
  throughout, 65.8 MB peak live heap) and the job (24.8 s, 2.4 MB peak live heap, no lock across
  chunks). The job is slower by 1.7 times (a marker insert per call and a commit per chunk); what it
  buys is the bound on memory and on how long a price update of that security is blocked.
- `SecurityPositions` gained `countOpenIn`, so it is no longer a functional interface and
  `ApplyPriceUpdateTest` implements it as a class. `PriceApplication` gained `deferred`; its
  three-argument constructor stays.
- `ApplyPriceUpdate` defers only if a `RevaluationLauncher` bean exists (batch and messaging both on).
  Without one the limit is ignored, because a request nobody dispatches would leave positions
  unrevalued for good.
- `loan_position (isin, id)` (V14) is the revaluation reader's index; the inline path's
  `findByIsin...` had needed the same and had no index either.
- The composition-over-template verdict is written to `docs/decisions/02-domain-model.md` here, as the
  plan required, with the count of jobs it was checked against; section 13 adds the retention job to
  that count.
- The full build of section 10's commit failed in stage 6's `MoneyColumnsRoundTripIT` (22 errors,
  `cannot truncate a table referenced in a foreign key constraint`): `revaluation_request` had a
  foreign key to `security`, which that test truncates. The same lesson as stage 10's outbox-era FK.
  Dropped in V14 (not yet released), with the reason in the migration; a request for a security that
  is gone runs a job over no positions and completes.

**Section 11.**
- A malformed line is skipped *and its position is left out of the compare step*. Without that, a
  line refused for a missing currency would leave its position looking like one the custodian does
  not hold, and the job would report a break that is really a data problem. The compare reader's
  query excludes positions with a `batch_skip` row for this snapshot on both sides of the join;
  the price is that the compare step reads `batch_skip`, which ties the two steps together through a
  table instead of through the step context. The alternative (a `custodian_holding_rejected` table)
  is the same coupling with one more table.
- The CSV reader hands out raw lines with their numbers and leaves parsing to the processor: a line
  that cannot be parsed is then a skippable processor failure that still knows which line it was.
  Line 23 (an empty position id) is filed as `line:23`; a line that names a position is filed under
  it. `CsvLinesReader` saves the number of lines handed out and a reopened reader skips that many.
- Position ids in the snapshot are the ledger's own ids. A real custodian has its own identifiers
  and a mapping to maintain; that is out of scope and named in the decision record.
- The settlement window is inclusive: settled on the 29th with a window of two days is still inside
  on the 1st (asserted on both sides of the boundary; the first test of it expected the wrong day).
- `ReconPolicy` defaults (1.00, 0.01%, two days) and the `ledger.reconciliation.*` names are in
  `ReconciliationProperties`; the decision record says what they would have to be based on.

**Section 12.**
- The ladder is split like stage 10's relay and stage 5's lesson demand: `SettlementRecovery` loops and
  is not transactional, `SettlementAttempt.attemptNext` is one transaction (claim with `FOR UPDATE SKIP
  LOCKED`, ask, record). One class calling its own `@Transactional` method would bypass the proxy.
- The job's third step is a tasklet with `PROPAGATION_NOT_SUPPORTED`: Spring Batch wraps a tasklet in a
  transaction by default, and one transaction around every attempt would roll back the record of an
  attempt the custodian had already been told about if a later one failed.
- `ReconBreaks.save` takes the time the breaks were found, so a break's first attempt is due on the
  application's clock and not on the database's `now()`: the ladder compares against the same clock it
  schedules with, and the test moves that clock.
- The gateway in `bootstrap` answers every resubmission "no route configured", so in a real run every
  ledger-only break past its window walks the ladder and ends escalated. That is the honest answer for
  a ledger with no custodian connection, and the decision record says so instead of making the stub
  settle things.
- `maxAttempts` is three; with delays of 4 h and 24 h the last attempt is 28 h after the first.

**Section 13.**
- `credit_reservation` has no timestamp, so a reservation is purged with its saga: the saga's terminal
  `saga_log` entry carries the time, and the purge deletes a finished saga's spent reservations and log
  in one statement (data-modifying CTEs). A reservation still `RESERVED` is never removed.
- The purge loops are `RunRetention` (not transactional) over `RetentionPurges` (one bounded batch, one
  transaction), again to keep each batch behind the proxy. The test sets a batch size of two so the
  loop runs several times over three rows.
- Steps are tasklets with no transaction of their own (`Tasklets.step`), so a purge of millions of rows is
  many short transactions and not one that holds locks for the length of the table.
- Recovery runs first, so a saga it finishes carries a fresh terminal entry and survives the same run's
  purge. `OpenPositionSaga` still has no caller in the product; the recovery is wired so that the day it
  has one, a saga a dead process left unfinished is finished by this job and not by nothing.
- The retention of a TCC tombstone is checked at start-up against the messaging topic retention
  (`ledger.retention.tombstones` must be longer): configuration that would make a late Try get through
  refuses to start. `reproducesTombstonePurgedTooEarly` is the reason: with the tombstone gone, a late
  Try froze 100.00 HKD for an attempt that had been cancelled.

**Section 14.**
- No HTTP launch endpoint was built (the spec listed one "disabled"): a command runner launches a job by
  name at start-up and exits with its result, which is what a cron entry or a Kubernetes Job uses and
  needs no authentication surface (stage 13's). `ledger.batch.command.*`.
- The timers are a property of their own (`ledger.batch.schedulers-enabled`, default false) inside
  batch-on. They wait for the accrual time and the retention time in Hong Kong, poll for revaluation
  requests and due settlement retries, and never schedule the reconciliation (it needs a file).
- Without Redis the scheduled accrual runs unfenced and says so at start-up: the lock belongs to the
  cache configuration, so `RunDailyAccrual` exists only with `ledger.cache.enabled`.
- Manual run: the local profile starts batch; the web server had to be disabled for it because port 8080
  was taken on this machine. Two positions existed from stage 10; one of them has no BORROW trade and
  so is invisible to the accrual and the reconciliation, which the transcript says.
- The full build of section 13's commit stopped in the first module on an intermittent failure of stage
  9's `ReadThroughTest.theRebuildLockIsHeldUntilTheValueIsStored`: its pause callback was instant, so
  the 100 lock polls could all run before the other thread was scheduled, the lock was never seen
  released and the read fell back to the source (a second source call, by design). The test's pause now
  takes 2 ms. A real race in the test, not the product.

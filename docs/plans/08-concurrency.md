# Stage 08 — Concurrency · Implementation Plan

Spec: [`docs/specs/08-concurrency.md`](../specs/08-concurrency.md)

One section per commit, in order. Implement a section, tick its checkbox in
the same commit as the next section's start, then stop for review.
`./mvnw clean verify` at the repository root after every section that touches
the main build.

## Ground rules for every section

- Packages:
  - `com.jerome.slledger.application.quote` for `PriceFanOut`, `PoolStats`
    and the request context (`application.context`);
  - `com.jerome.slledger.adapter.web.quote` for the `/quotes` controller;
  - `com.jerome.slledger.bootstrap.config` for executor beans and properties.
- Main-build tests: pure unit tests end in `Test`; anything that starts a
  server ends in `IT` and lives in `bootstrap`, using `LedgerApp.startWeb(…)`
  and `StubPriceServer` from stage 7.
- Measurements are tagged `measurement` and run only under
  `./mvnw verify -Pmeasure`.
- Every blocking test has `@Timeout(30)` and releases its latches in
  `finally`. No `Thread.sleep`.
- A test asserting wrong behaviour is named `reproduces<Failure>` and carries
  `// See docs/decisions/08-concurrency.md`.
- Evidence is written by tests to `<module>/target/evidence/08-*.txt` and
  copied by hand to `docs/evidence/`. Lab evidence is written to the lab's
  `target/` and copied the same way.
- The main build runs on the JDK `.sdkmanrc` pins (21).
- Test data is synthetic. ISINs sent over HTTP carry a valid check digit.

## Decisions made while planning

The spec left these open. Each is flagged in its section and carried to the
decision record.

1. **The lab builds `domain` from source, not from `~/.m2`.** `domain` has no
   third-party dependency, so the lab's POM adds `../../domain/src/main/java`
   as a source root with `build-helper-maven-plugin`. No `install` into the
   user's local repository, and no version to keep in step.
2. **The lab is a standalone POM, not a child of the root.** It is not in the
   root's `<modules>`, so `./mvnw verify` never builds it. It carries its own
   `<version>`s, which the "no child version" rule does not cover because it
   is not a child. It is run with `./mvnw -f labs/08-concurrency/pom.xml …`,
   using the checked-in wrapper.
3. **The executor bean is a `ThreadPoolExecutor`, not `Executor`.** The
   counters the experiments read (`getLargestPoolSize`, `getQueue().size()`,
   `getActiveCount`) are on the concrete type. `application` sees only
   `java.util.concurrent.Executor`; `bootstrap` exposes the concrete bean to
   tests through a `PoolStats` snapshot supplier, so no test casts.
4. **Rejected count is a wrapping `RejectedExecutionHandler`.** The JDK pool
   has no rejected counter. Each policy is wrapped in a handler that
   increments an `AtomicLong` before delegating.
5. **The virtual-thread executor has no pool counters.** It is
   `Executors.newThreadPerTaskExecutor(virtualFactory)`. Concurrency is
   bounded by a `Semaphore` in front of it (`market-data.executor.max-concurrent`),
   because an unbounded virtual executor would simply move stage 7's failure
   from Tomcat to the source. The semaphore is the bulkhead's crude
   precursor; stage 12 replaces it.
6. **The whole-batch wait is a deadline, not per-future timeouts.**
   `PriceFanOut` joins with one deadline for the batch. A future still running
   at the deadline becomes that ISIN's error item and is cancelled.

## Review focus

Failure modes the spec implies but no exercise is *about*.

1. **A rejected task in a fan-out.** One ISIN rejected by a full queue must
   not lose the other results, and must not leave a future that never
   completes (section 2).
2. **A task cancelled at the deadline still holds a pool thread** until the
   source answers. Cancelling the future does not free the worker; the test
   asserts what actually happens (section 2).
3. **The MDC on pooled threads.** The trace id set by the filter is not on
   the executor's threads unless copied; the fan-out's log lines must still
   carry it, and must not leak it to the next task (section 7).
4. **`CallerRuns` and the fan-out deadline.** A rejected task run on the
   request thread runs *before* the join, so the deadline does not cover it
   (section 5).
5. **The same ISIN twice in one request.** Deduplicated before submission,
   asserted by the stub's call counter (section 2).

---

## [x] 1 · Executor, properties and counters

**`bootstrap` `MarketDataExecutorProperties`**
- `market-data.executor.{core,max,queue,policy,type,max-concurrent}`;
  `policy` is `abort | caller-runs | discard | discard-oldest`, `type` is
  `platform | virtual`. Defaults: 4 / 8 / 16 / `abort` / `platform` / 32.
  The default policy is revisited in section 5.
- Validated at startup: `core >= 1`, `max >= core`, `queue >= 1`.

**`application` `PoolStats`**
- `record PoolStats(int poolSize, int largestPoolSize, int active, int queued,
  long completed, long rejected)` and a `Supplier<PoolStats>`.

**`bootstrap` `MarketDataExecutorConfiguration`**
- Named thread factory `market-data-N`.
- `ArrayBlockingQueue` of `queue` capacity.
- Handler wrapper counting rejections (decision 4).
- Beans: `Executor marketDataExecutor` and `Supplier<PoolStats>`.
- `virtual` type per decision 5 arrives in section 8; here `type=virtual` is
  refused at startup with a clear message.

**Tests (`bootstrap`)**
- `MarketDataExecutorConfigurationTest`: each policy value builds the right
  handler; a bad size combination fails startup; thread names start with
  `market-data-`; the rejected counter increments for a forced rejection.

**Acceptance**
- Tests pass; application starts with defaults.
- `./mvnw clean verify` is green.

**Found while implementing**
- **The prefix is `ledger.marketdata.executor`, not `market-data.executor`.**
  Every other stage-7 property for this source is under `ledger.marketdata`;
  a second spelling would be a trap.
- **`MarketDataPool` replaces the `ThreadPoolExecutor` bean plus a stats
  supplier.** It wraps the pool, implements `Executor`, and has `stats()`.
  `PoolStats` lives in `bootstrap.config`, not `application`: only wiring and
  tests read it. Nothing casts to reach a counter (planning decision 3, in
  a simpler form).
- Worker threads are daemons, so a leaked pool cannot hold a test JVM open.

---

## [x] 2 · `PriceFanOut`, and the two `CompletableFuture` traps

**`application` `PriceFanOut`**
- `List<QuoteOutcome> quote(List<Isin>)`, with `QuoteOutcome` a sealed type:
  `Priced(Isin, Money)` or `Failed(Isin, Kind)`, `Kind` being `UNAVAILABLE`,
  `REJECTED`, `UNKNOWN`, `TIMED_OUT`, `OVERLOADED`.
- Deduplicates ISINs, submits one `supplyAsync(…, executor)` per ISIN, maps
  each stage's exception with `handle`, and joins with a single deadline
  (decision 6).
- A `RejectedExecutionException` at submission becomes `Failed(OVERLOADED)`
  for that ISIN only (review focus 1).
- `groupByCurrency(List<QuoteOutcome>)` returns `Map<Currency, List<Money>>`;
  it never adds across currencies.

**Tests (`application`, plain Java, fake `MarketPriceSource`)**
- `oneFailureKeepsTheOthers`, `duplicateIsinsAreFetchedOnce`,
  `rejectedSubmissionBecomesOverloadedForThatIsinOnly`,
  `deadlineTurnsSlowIsinIntoTimedOut`, `mixedCurrenciesAreGroupedNotSummed`.
- `reproducesCommonPoolUsedWhenNoExecutorGiven`: a `supplyAsync` without an
  executor runs on a thread named `ForkJoinPool.commonPool-worker-*`; with
  the common pool saturated by blocked tasks, an unrelated `supplyAsync` does
  not start within a bound.
- `reproducesExceptionSwallowedWithoutJoin`: a stage that throws, whose
  future nobody joins, produces no exception in the submitting thread; the
  test asserts `isCompletedExceptionally()` is true while nothing was thrown.
  The product path is asserted to surface the same failure as `Failed`.
- `cancelledFutureStillHoldsItsWorker` (review focus 2): after the deadline,
  the pool's active count is unchanged until the source is released.

**Acceptance**
- All pass; `./mvnw clean verify` is green.

**Found while implementing**
- `PriceFanOut` delegates to `QuotePrice`, so each task keeps stage 7's
  security lookup and currency check. Its timeout is `Duration`-based and
  wired in section 3.
- The added kind `ERROR` covers unexpected failures, logged with the cause and
  returned without it. `OverloadedException` is thrown only when every ISIN
  was refused.
- `reproducesCommonPoolUsedWhenNoExecutorGiven` is skipped (assumption) when
  the common pool's parallelism is 1: `supplyAsync` then silently uses a
  thread per task. This machine has 4 CPUs, so it runs.
- `application` has no SLF4J; the class logs through `commons-logging` (Spring's
  `spring-jcl`), which Boot routes to the same backend and MDC.

---

## [x] 3 · `GET /quotes` and the `503`

**`adapter-web`**
- `QuotesController`: repeatable `isin` parameter, `@Isin` on each, at most
  `quotes.max-isins` (default 20); over is `400` with the field named.
- Response items per spec; prices are strings through the existing Jackson
  module.
- `OverloadedException` mapped to `503` with
  `urn:sl-ledger:problem:overloaded` and `Retry-After`. It is thrown when
  *every* ISIN was rejected; a partial rejection is per-item.

**Tests**
- `@WebMvcTest` slice: validation, the `400` cap, item shapes, the `503`.
- `bootstrap` `QuotesIT`: against the stub, two ISINs; one ISIN unknown (item
  error, request still `200`); a two-currency batch grouped by currency.
- The OpenAPI contract test gains `/quotes`, including the `503`.

**Acceptance**
- The controller imports only `application` (ArchUnit still passes).
- `./mvnw clean verify` is green.

**Found while implementing**
- **The response carries no per-currency block.** Prices of different
  securities are not addable, so a "group" would be a list of the same items
  reorganised. Each item carries its own currency; the grouping stays a tested
  use-case function (section 2) and `QuotesIT.mixedCurrencies` asserts the
  wire has no total.
- **The fan-out deadline is `ledger.marketdata.executor.deadline` (3 s).**
  It lives with the executor's other properties, not in `MarketDataProperties`.
- **The 503 contract check is in `QuotesIT`, not `OpenApiContractIT`.** The
  refusal needs a pool of one, so it has its own application; it builds the
  validator from that application's `/v3/api-docs`. `OpenApiContractIT` gained
  `/quotes` for `200` and both `400`s.
- `@ValidIsin` gained `TYPE_USE` so it applies to `List<@ValidIsin String>`.

---

## [x] 4 · Pool exhaustion and the jstack

**`bootstrap` `PoolExhaustionIT`**
- `reproducesPoolExhaustionWithHangingSource`: `core=2, max=2, queue=2,
  policy=abort`, the stub hangs. Fire enough `/quotes` requests that the pool
  is busy and the queue is full and a further submission is rejected. Assert
  `rejected > 0`, `queued == 2`, `active == 2` from `PoolStats`.
- At that moment, dump the `market-data-*` threads and the Tomcat workers,
  formatted like `jstack`, to `docs/evidence/08-pool-exhaustion-jstack.txt`,
  and record `findDeadlockedThreads()`.
- Marked lines in the file (a comment header, added by hand after the run):
  every `market-data-*` worker in the same wait frame in
  `RestMarketPriceSource.priceOf`, none `BLOCKED` on a monitor, no
  deadlock reported, and the queue depth at capacity. Release the latch in
  `finally`.

**Acceptance**
- The test passes; the evidence file exists with the marked lines.
- `./mvnw clean verify` is green.

**Found while implementing**
- The test class is `MarketDataPoolExhaustionIT`; stage 6 already has a
  `PoolExhaustionIT` for the database pool.
- The read timeout is set to 0 for this test only, so the two workers stay
  parked for as long as the source hangs; with stage 7's 2 s timeout they would
  fail with `UNAVAILABLE` before the dump could be taken.
- The workers' wait frame is the one stage 7 found: `CompletableFuture.get`
  under `JdkClientHttpRequest.executeInternal`, not a socket read (stage 7,
  decision 8). The evidence header states the five reasons in order.
- The two queued tasks never reach the source (`arrived == 2`): a full queue
  is invisible from the source's side.

---

## [ ] 5 · The four rejection policies

**`bootstrap` `RejectionPolicyIT`** (parameterised over the four policies)
- Same load each time: `core=1, max=1, queue=1`, hanging stub, 6 submissions
  through the fan-out. Per policy, record: how many outcomes were
  `OVERLOADED`, whether any submit threw, which thread executed each task,
  and whether any task vanished with no outcome.
- `Discard` and `DiscardOldest` drop tasks silently: the test asserts that the
  fan-out still returns an outcome for every ISIN (the dropped ones as
  `TIMED_OUT` at the deadline) and records the delay this costs.
- `reproducesCallerRunsPushingLoadOntoTheRequestThread`: under `caller-runs`,
  the rejected task's thread name is a Tomcat worker (`http-nio-*-exec-*`),
  the request does not return until the source answers, and a concurrent
  `GET /loans` with a 2 s client timeout starves as in stage 7 (review focus
  4).
- Evidence: `docs/evidence/08-rejection-policies.txt`, one row per policy.

**Default policy**
- After the run, the default in `MarketDataExecutorProperties` is set to the
  chosen policy and the reasoning is written to the plan's "Found while
  implementing". The expectation, to be confirmed by the rows and not assumed:
  `abort`, because it converts overload into a fast, honest `503`.

**Acceptance**
- All four rows are in evidence; the `caller-runs` reproduction passes.
- `./mvnw clean verify` is green.

**Found while implementing** — _to fill in_

---

## [ ] 6 · Pool parameters: the queue that hides the maximum

**`bootstrap` `PoolSizingIT`**
- `reproducesPoolNeverGrowingPastCore`: `core=2, max=8, queue=100`, hanging
  stub, 20 submissions. Assert `largestPoolSize == 2` and `queued == 18`.
- Contrast `poolGrowsWithShortQueue`: `core=2, max=8, queue=2`, the same
  submissions. Assert `largestPoolSize > 2`.
- Evidence: `docs/evidence/08-pool-sizing.txt` with both counter sets, and
  the `ThreadPoolExecutor` rule that explains it (threads beyond core are
  created only when the queue *rejects an offer*).

**Acceptance**
- Both pass; evidence exists.
- `./mvnw clean verify` is green.

---

## [ ] 7 · Request context and the `ThreadLocal` leak

**`application` `RequestContext`**
- A holder over a `ThreadLocal<Snapshot>` with `capture()`, `restore(snapshot)`
  and `clear()`, and `Snapshot` carrying the trace id.
- `PriceFanOut` wraps each task: capture on the submitting thread, `restore`
  inside the task, `clear` in `finally`. The MDC trace id is copied the same
  way (review focus 3).

**Tests**
- `reproducesRequestContextBleedingToNextTask`: a one-thread pool, task A sets
  a value with no cleanup, task B reads A's value.
- `wrappedSubmissionDoesNotBleed`: the same shape through the wrapper; B reads
  nothing.
- `reproducesInheritableThreadLocalStaleInPool`: an
  `InheritableThreadLocal` set by the submitter before the pool's thread is
  created, then changed; the second task on that thread still sees the first
  value.
- `fanOutLogLinesCarryTraceIdAndDoNotLeakIt`: after a fan-out, the executor
  thread's MDC is empty.

**Acceptance**
- All pass; `./mvnw clean verify` is green.

---

## [ ] 8 · Virtual-thread executor and the throughput measurement

**`bootstrap`**
- `market-data.executor.type=virtual`: `newThreadPerTaskExecutor` over a
  virtual thread factory named `market-data-v-N`, with the semaphore from
  decision 5. A refused permit is the same `RejectedExecutionException` the
  platform pool gives, so the `503` path is unchanged.
- Startup no longer refuses `virtual` (section 1).

**Tests**
- `VirtualExecutorIT`: thread names are virtual; a full semaphore yields
  `OVERLOADED`; results equal the platform pool's for the same stub.
- `VirtualThroughputMeasurement` (tag `measurement`): the same batch of
  slow-then-released calls against a platform pool of 8 and the virtual
  executor at `max-concurrent=200`. Records elapsed and calls per second to
  `docs/evidence/08-virtual-throughput.txt`.

**Acceptance**
- `./mvnw clean verify` and `./mvnw verify -Pmeasure` are green.

**Found while implementing** — _to fill in_

---

## [ ] 9 · Lab scaffold and the four memory-model exercises

**`labs/08-concurrency/`**
- `pom.xml` standalone (decision 2), Java 21 release, JMH and its annotation
  processor, `build-helper-maven-plugin` adding `domain`'s source (decision
  1). `README.md` with the run command for each exercise and the evidence
  file it produces.
- `Visibility` — a stop flag without `volatile`, a reader in a hot loop, N
  rounds, each bounded by a wall-clock limit. Prints how many of N rounds did
  not terminate. Then the `volatile` variant.
- `LostUpdate` — N threads × M increments on `volatile int`, then
  `AtomicInteger`. Prints the expected total, the observed total and the
  loss for each.
- `DoubleCheckedLocking` — a non-`volatile` singleton with a stress harness
  looking for an observed instance whose field is not yet set, then the
  `volatile` fix.
- `RecursiveComputeIfAbsent` — a mapping function that calls
  `computeIfAbsent` on the same map, bounded by a watchdog thread; records
  what the pinned JDK does (hang or exception), then the fix.
- Each prints a header with `java -version` and the CPU architecture.

**Evidence**
- `docs/evidence/08-visibility.txt`, `08-lost-update.txt`, `08-dcl.txt`,
  `08-compute-if-absent.txt`. A failure that did not reproduce is recorded as
  "0 of N rounds", not omitted.

**Acceptance**
- Each command in the README runs and writes its evidence file.
- `./mvnw clean verify` at the root is unchanged and green.

**Found while implementing** — _to fill in_

---

## [ ] 10 · CAS against `synchronized`, and JMH

**`labs/08-concurrency`**
- `Balance` over `Money`: an `AtomicReference<Money>` variant with a CAS loop
  and a `synchronized` variant; both check currency on every update.
- `BalanceBenchmark` (JMH): threads = 1 (low contention) and threads = 2×
  cores (high contention), throughput mode. Two sets of numbers.
- `AccrualBenchmark`: the accrual computation over `domain`.
  - First version returns nothing and consumes nothing: the JIT removes the
    work and reports an impossible number. Kept as a named class, marked as
    the mistake.
  - Second version consumes through `Blackhole`. Both runs are recorded.

**Evidence**
- `docs/evidence/08-cas-vs-synchronized.txt`, `08-jmh-no-blackhole.txt`,
  `08-jmh-blackhole.txt`, each with the JMH configuration header.

**Acceptance**
- Commands in the README produce the files.
- The "impossible number" is stated with its reason in the file's header,
  written from the measured output, not in advance.
- `./mvnw clean verify` at the root is green.

**Found while implementing** — _to fill in_

---

## [ ] 11 · Virtual-thread pinning, on two JDKs

**`labs/08-concurrency` `Pinning`**
- N virtual threads on a small carrier pool
  (`-Djdk.virtualThreadScheduler.parallelism=2`), each blocking inside
  `synchronized` on a shared lock, then the same with `ReentrantLock`.
  Records elapsed time for both.
- On 21: run with `-Djdk.tracePinnedThreads=full`; the output is the
  evidence.
- On 25: the same command. `tracePinnedThreads` no longer exists, so the
  lab records the elapsed times only and states that the flag is gone.

**Evidence**
- `docs/evidence/08-pinning-jdk21.txt` and `08-pinning-jdk25.txt`, each with
  the `java -version` header and both timings.

**Acceptance**
- Both files exist; the difference between the two JDKs is visible in the
  `synchronized` timing, or its absence is recorded.
- `./mvnw clean verify` at the root is green.

**Found while implementing** — _to fill in_

---

After section 11: `docs/decisions/08-concurrency.md`, as its own commit, per
the spec's "Done when" list.

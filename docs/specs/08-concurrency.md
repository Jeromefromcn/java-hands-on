# Stage 08 — Concurrency

## Problem

Stage 7 ended with one deliberately painful fact: `GET /securities/{isin}/quote`
holds a Tomcat worker for as long as the market data source takes. The read
timeout bounds one call, not the pool. Stage 7's decision record named the
fix's owners: the circuit breaker is stage 12's, and the *executor* is this
stage's.

Stage 8 puts a bounded, observable executor between the request thread and the
slow source, then drives every concurrency failure the roadmap lists to an
asserted or measured result: pool exhaustion, each rejection policy, a
queue so long that `maximumPoolSize` is never reached, the default
`ForkJoinPool` trap, a swallowed exception, `ThreadLocal` bleed, lost updates
from `volatile`, CAS under contention, virtual-thread pinning, visibility
without `volatile`, a half-constructed singleton, a benchmark the JIT deletes,
and a `computeIfAbsent` deadlock.

Four facts shape the stage.

- **Only some of the twelve exercises belong to the product.** A bounded
  executor around the price source, a fan-out that fetches many prices, and a
  request context that must not leak between pooled requests are all things
  this service needs. A `volatile int count++` race, a broken double-checked
  lock and a recursive `computeIfAbsent` are not; they exist to be broken.
  `CLAUDE.md` gives broken code exactly two homes: a `reproduces<Failure>` test,
  or `labs/<nn>-<slug>/`. The split is the first design decision below.
- **The JDK baseline finally matters.** `.sdkmanrc` pins Java 21 so that this
  stage can compare against a newer JVM. On 21, a virtual thread blocked in a
  `synchronized` block pins its carrier. On 24 and later (JEP 491) it does not.
  The same reproduction is run on both and the difference is the finding.
- **Stage 7's hang is already a fixture.** The `com.sun.net.httpserver` stub
  that can be told to hang until a latch is released is the slow downstream.
  This stage reuses it and adds nothing to simulate slowness.
- **Several results are about probability, not determinism.** A visibility
  failure may not appear in a given run, or on a given CPU. The roadmap says so
  itself: sporadic is why it is hard to find. Where a failure may not
  reproduce, the test asserts a bound and never a hope, and the evidence
  records what this machine actually did.

## Settled design decisions

These are proposed for review. The decision record argues each one against its
alternatives; the spec states what they commit the code to.

1. **Product code and labs are split by whether the service would ship it.**
   - Product (in the main build, TDD, `./mvnw verify`): the bounded executor
     for the price source, the multi-price fan-out use case and its endpoint,
     the request-context holder and its cleanup, the virtual-thread executor
     option.
   - Labs (`labs/08-concurrency/`, self-contained, not wired into the build):
     JMM visibility, `volatile` vs `AtomicInteger`, double-checked locking,
     `computeIfAbsent` recursion, CAS vs `synchronized` on a balance, and the
     JMH benchmark. Each is a runnable class or a small module with a
     `README.md` giving the command and the evidence file it produces.
   - A failure that is *observed on product code* (rejection under load,
     `CallerRuns` pushing back onto Tomcat, the pool never growing) is a
     `reproduces<Failure>` test in the module that owns the code.
2. **The executor is configured, and its policy is chosen by measurement.**
   Core size, maximum size, queue capacity and rejection policy are properties
   (`market-data.executor.*`), so one configuration governs the bean and the
   experiments. The default policy is picked after the four policies are run
   against the same load; the choice and the numbers go in the decision record.
   The rejection is translated at the web boundary to a `503` with a
   `ProblemDetail`, not left as `RejectedExecutionException`.
3. **Fan-out results keep per-security outcomes.** Fetching several prices
   returns each security's price *or* its failure, never one failure that
   discards the others, and never a sum across currencies. Money is combined
   only after `Money`'s own currency check; a batch with mixed currencies is
   reported per currency.
4. **Request context is one `ThreadLocal`, and it is wrapped, not trusted.**
   The trace id already travels in the MDC (stage 7). This stage adds a
   request-context holder for one more per-request value and hands it to
   executor tasks explicitly, by capturing before submit and setting and
   clearing inside the task, with `try/finally`. `InheritableThreadLocal` is
   demonstrated as insufficient, not adopted.

## Scope

### Module topology and dependencies

| Module | Gains | New dependencies |
|---|---|---|
| `domain` | nothing | none |
| `application` | `PriceFanOut` use case; `RequestContext` holder; the executor is injected as `java.util.concurrent.Executor` | none |
| `adapter-persistence` | nothing | none |
| `adapter-web` | `GET /quotes?isin=…` (repeatable); the `503` mapping for rejected work | none |
| `bootstrap` | executor beans and their properties; `virtual` / `platform` switch | none |
| `labs/08-concurrency` | the lab classes and the JMH module | JMH (lab's own POM, outside the main build) |

No child POM in the main build carries a `<version>`. The JMH dependency and its
annotation processor are pinned inside the lab, which is not a module of the
root build. The lab benchmarks `domain`'s accrual computation through the
built `domain` jar; how it obtains that jar is settled in the plan.

### The bounded executor

- A `ThreadPoolExecutor` with a named thread factory (`market-data-N`), a
  bounded `ArrayBlockingQueue`, and a configurable rejection policy.
- It exposes its size, active count, queue depth and rejected count for the
  experiments and for stage 12's metrics. No metrics library is added here.
- `PriceFanOut` submits one task per ISIN to this executor, each calling
  `MarketPriceSource.priceOf`, and joins them with a bound on the total wait.
- The single-quote endpoint from stage 7 is left as it is. Only the new
  fan-out endpoint uses the executor, so the stage-7 tests keep meaning what
  they meant.

### Endpoint

| Method and path | Use case | Success |
|---|---|---|
| `GET /quotes?isin=A&isin=B…` | fan-out price fetch | `200`, per-ISIN result |

- At most a configured number of ISINs per request; more is `400`.
- Each ISIN is validated with `@Isin` as elsewhere.
- Response items are `{"isin": …, "price": {"amount": "…", "currency": "…"}}`
  or `{"isin": …, "error": {"type": …}}`. Prices are strings, as everywhere.
- Work refused by the executor is `503`, with a stable `ProblemDetail.type`.
  The whole request is not failed because *one* ISIN timed out: that ISIN
  carries its own error item.

### The twelve exercises

Each is driven to a number or an asserted result. "Lab" and "test" are per
design decision 1.

1. **Thread-pool exhaustion.** *Test.* A small pool, a bounded queue, a
   hanging source: submit until the rejection policy fires. A `jstack` dump
   taken at that moment goes to `docs/evidence/08-pool-exhaustion-jstack.txt`,
   and the decision record marks which frames say "pool full" and not
   "deadlock": every worker parked in a socket read, none `BLOCKED` on a
   monitor, `findDeadlockedThreads()` returning null, queue at capacity.
2. **The four rejection policies.** *Test.* The same load, run once per policy
   (`Abort`, `CallerRuns`, `Discard`, `DiscardOldest`), recording what each
   does to the submitter, to the task, and to the caller's own thread.
   `reproducesCallerRunsPushingLoadOntoTheRequestThread` shows a Tomcat worker
   executing the slow call itself, and the unrelated `GET /loans` request
   starving as in stage 7. Evidence: one row per policy.
3. **Pool parameters.** *Test.* A configuration with a very long queue and a
   larger maximum. `reproducesPoolNeverGrowingPastCore` asserts that
   `getLargestPoolSize()` stays at the core size while the queue fills, and
   that the same load with a short queue does grow the pool. Both counts are
   evidence.
4. **`CompletableFuture`.** *Test.* Fan-out over several securities.
   - `reproducesCommonPoolUsedWhenNoExecutorGiven`: `supplyAsync` without an
     executor runs on `ForkJoinPool.commonPool`, asserted by thread name, and
     a saturated common pool delays unrelated work.
   - `reproducesExceptionSwallowedWithoutJoin`: an exception in a stage nobody
     `join`s or `exceptionally`s is observed only by its absence; the test
     shows where it went. The product path handles each stage's failure.
5. **`ThreadLocal` leak.** *Test.* `reproducesRequestContextBleedingToNextTask`
   runs two tasks on a one-thread pool and reads the first's value in the
   second. The wrapped submission from design decision 4 fixes it, and the same
   test shape asserts the clean result.
   `reproducesInheritableThreadLocalStaleInPool` shows the value inherited at
   thread *creation* being stale by the second task.
6. **CAS vs `synchronized`.** *Lab.* An `AtomicReference`-based balance update
   against a `synchronized` one, at low and at high contention. Two sets of
   numbers, from JMH, in evidence, with CAS spin cost visible at the high end.
7. **Virtual threads.** *Test plus lab.*
   - Product: `market-data.executor.type=virtual` swaps the platform pool for a
     virtual-thread-per-task executor. Throughput against the platform pool,
     same hanging-then-released source, measured under `-Pmeasure`.
   - `reproducesVirtualThreadPinning` (Java 21): a blocking call inside
     `synchronized` on a virtual thread, run with
     `-Djdk.tracePinnedThreads=full`; the output goes to
     `docs/evidence/08-pinning-jdk21.txt`. The same block with `ReentrantLock`
     is measured after it.
   - The same run on the newer JDK is recorded as
     `08-pinning-jdk25.txt`. The decision record states what changed and what
     it means for a service pinned to 21.
8. **Java Memory Model visibility.** *Lab.* A stop flag without `volatile`
   read in a hot loop. Run many rounds. It may hang after JIT compilation or it
   may not; the lab bounds every run and reports how many of N rounds failed to
   terminate. Then `volatile`. The decision record names the happens-before
   rule that the fix relies on: a write to a `volatile` field
   happens-before every subsequent read of that field.
9. **`volatile` is not atomic.** *Lab.* N threads doing `volatile int count++`
   M times each: the lost-update count and the final total, then
   `AtomicInteger`. Own numbers, in evidence.
10. **Double-checked locking.** *Lab.* A singleton whose field is not
    `volatile`, with a stress harness that looks for a thread observing the
    instance before its fields are set. As in item 8, it may not reproduce on
    this machine; the evidence says so if it does not. Then the `volatile`
    fix, and the reason it is needed (safe publication of a partially
    constructed object).
11. **JMH.** *Lab.* A benchmark of the accrual computation. The lab first
    commits the mistake, a result never consumed, so the JIT eliminates the
    work and reports an impossible number. Then `Blackhole`. Both numbers, and
    the JMH configuration, are evidence.
12. **`ConcurrentHashMap.computeIfAbsent`.** *Lab.* A mapping function that
    recursively calls `computeIfAbsent` on the same map. It hangs or throws
    `IllegalStateException` depending on the JDK version; the lab bounds it and
    records which. The fix (compute outside, `putIfAbsent`) is included.

### Concurrency at the money invariant

- The fan-out never adds `Money` of different currencies, and never converts
  silently. A test sends a batch with two currencies and asserts a per-currency
  grouping.
- The CAS balance in item 6 is a lab type over `Money`, not a `BigDecimal`
  field on its own; the lab checks currency on every update.
- No new column, cache, serializer or message payload is added, so the
  persistence and HTTP checks from earlier stages are unchanged. The new
  `/quotes` response is added to the OpenAPI contract test.

### Evidence

`docs/evidence/08-*.txt`, text only, measured on this machine:

- `jstack` under pool exhaustion, with the marked lines;
- the four rejection policies, one row each, plus `CallerRuns` starvation;
- the never-grows-past-core observation;
- common-pool and swallowed-exception observations;
- the `ThreadLocal` bleed and its fix;
- CAS vs `synchronized`, two contention levels;
- virtual vs platform throughput; pinning on 21 and on the newer JDK;
- the visibility, `volatile` counter and DCL rounds, with the failure counts;
- the JMH runs with and without `Blackhole`;
- the `computeIfAbsent` outcome.

## Out of scope

- Circuit breaker, bulkhead, retry and stale-price fallback (stage 12).
- Metrics and tracing libraries (stage 12). The executor exposes counters
  only.
- Caching prices (stage 9).
- Applying virtual threads to Tomcat itself, or to the database pool.
- Distributed locking and cross-instance coordination.
- Changing `domain`, or any persisted schema.
- Rewriting stage 7's single-quote endpoint to use the executor.

## Constraints

- `domain` does not change and gains no dependency.
- `./mvnw verify` at the repository root stays green after every section.
  Labs are outside that build.
- The main build is compiled and tested on the JDK `.sdkmanrc` pins. The
  newer-JDK pinning run is a separate, manually invoked command in the lab
  `README.md`.
- Concurrency in tests is sequenced with latches or barriers, never
  `Thread.sleep`, and every blocking test has a bound (`@Timeout`). The
  exceptions are the labs' probabilistic harnesses, whose bound is a round
  count and a wall-clock limit.
- Reproduced failures are asserted by exception type, thread name, pool
  counter or `ProblemDetail.type`, never by message text alone.
- A test that cannot reproduce on this machine says so in its evidence and
  asserts only its bound. It is not weakened to always pass, and not deleted.
- Numbers in the decision record come from evidence files, measured on this
  machine.
- Measurements run only under `./mvnw verify -Pmeasure`, tagged `measurement`.
- No secret in any committed file. All data is synthetic.

## Acceptance criteria

1. `domain` is unchanged and its enforcer rule still passes.
2. `GET /quotes` has a test against a running server, including a partial
   failure, a mixed-currency batch and the over-limit `400`.
3. Executor rejection maps to `503` with a stable `ProblemDetail.type`, and
   the OpenAPI contract test covers `/quotes`.
4. Pool exhaustion is reproduced, its `jstack` is committed, and the marked
   lines distinguish it from a deadlock.
5. All four rejection policies are run against the same load, and
   `CallerRuns` starving an unrelated endpoint is asserted.
6. The never-grows-past-core configuration is asserted, with its
   short-queue contrast.
7. The common-pool and swallowed-exception traps are each asserted, and the
   product path is shown to handle both.
8. `ThreadLocal` bleed is reproduced and fixed with `try/finally`;
   `InheritableThreadLocal` is shown to fail in a pool.
9. CAS vs `synchronized` has two contention levels of measured output.
10. Pinning is captured on Java 21 with the trace flag, the `ReentrantLock`
    version is measured, and the newer-JDK run is recorded.
11. The visibility, `volatile` counter and DCL labs run and record their
    failure counts, including any that did not reproduce.
12. The JMH lab shows the eliminated-work number and the `Blackhole` number.
13. The `computeIfAbsent` lab records its outcome on the pinned JDK.
14. Every file in the evidence list exists and holds measured output.
15. `./mvnw verify` at the repository root is green.

## Done when

The four `CLAUDE.md` deliverables exist: this spec, the plan, the code with
every criterion above verified, and `docs/decisions/08-concurrency.md`. The
decision record must cover:

- the product / lab split, and why each exercise went where it did;
- the choice of rejection policy for the price fan-out, argued from the four
  measured rows, and why the other three were rejected;
- how to tell a full pool from a deadlock in a thread dump, using the
  committed dump;
- the pool-parameter finding: why a long queue means `maximumPoolSize` is
  never used;
- the default executor trap and the swallowed exception, and how the product
  path avoids both;
- the request-context design, and why `InheritableThreadLocal` fails;
- CAS against `synchronized`, with both contention results;
- virtual threads: the throughput difference, the pinning on 21, what changed
  on the newer JDK, and whether this service should use virtual threads;
- the memory-model exercises: which rule fixes each, and which ones did not
  reproduce here and why that is itself the point;
- the JMH mistake and its fix;
- the answer to the roadmap's oral check, as an ordered path: latency has
  risen at three in the morning; what is looked at first, what that rules
  out, what is looked at next;
- where each choice breaks at 100x.

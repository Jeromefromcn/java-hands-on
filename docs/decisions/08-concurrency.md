# Stage 08 — Concurrency — Decision Record

Spec: [`docs/specs/08-concurrency.md`](../specs/08-concurrency.md)
Plan: [`docs/plans/08-concurrency.md`](../plans/08-concurrency.md)

Measured on JDK 21.0.12 (Temurin, the `.sdkmanrc` pin) and, where stated,
JDK 25.0.2, on a 4-core aarch64 host, with Spring Boot 4.1.1, Tomcat 11.0.24
and JMH 1.37. Every number below is copied from a `docs/evidence/08-*.txt`
file. Where a claim is reasoned rather than measured, it says so.

## What I chose

### The split: what ships, and what only exists to be broken

Stage 7 left one painful fact: a slow market data source holds a Tomcat worker
per quote. This stage puts a bounded executor between the request thread and
the source, and then breaks concurrency on purpose.

| Exercise | Where | Why there |
|---|---|---|
| Pool exhaustion, four policies, pool sizing | tests over `MarketDataPool` and the running server | the executor ships; its failures are observed on product code |
| `CompletableFuture` traps, `ThreadLocal` bleed | `reproduces*` tests in `application` | the fan-out and the request context ship; the wrong version is a test |
| Virtual-thread executor | product option (`type=virtual`), measured under `-Pmeasure` | it ships as a switch |
| Visibility, `volatile count++`, DCL, `computeIfAbsent`, CAS, JMH, pinning | `labs/08-concurrency/` | none of it would ship; the broken form must not sit in `main` |

The rule that decided each line: would the service run this code? If yes, the
failure is a `reproduces<Failure>` test next to it. If no, it is a lab outside
the build, so `./mvnw verify` stays green and no `volatile`-less singleton is
ever a bean.

### The executor: `MarketDataPool`

A `ThreadPoolExecutor` with threads named `market-data-N`, a bounded
`ArrayBlockingQueue`, a configured rejection policy, and a rejected counter
(the JDK keeps none, so each handler is wrapped in one that counts). Size,
queue, policy and type are properties under `ledger.marketdata.executor`.
Defaults: core 4, max 8, queue 16, `abort`, platform, 3 s batch deadline.

`PriceFanOut` submits one task per distinct ISIN, always with the executor
named, joins them under one deadline, and turns every stage's failure into
that security's outcome. The endpoint `GET /quotes?isin=…` returns `200` with
a price or an error type per item, and `503` with `Retry-After` only when the
executor refused every security. The single-quote endpoint from stage 7 is
untouched, so its tests still mean what they meant.

### Rejection policy: `abort`

The same load through each policy (core 1, max 1, queue 1; six requests; a
source that holds each call 500 ms; batch deadline 300 ms),
[`08-rejection-policies.txt`](../evidence/08-rejection-policies.txt):

| Policy | Outcomes | Batch took | Ran source calls on | Rejected counter |
|---|---|---|---|---|
| `abort` | 2 `TIMED_OUT`, 4 `OVERLOADED` | 307 ms | `market-data-1` | 4 |
| `caller-runs` | 4 `UNAVAILABLE`, 2 `TIMED_OUT` | 1315 ms | `main`, `market-data-1` | 2 |
| `discard` | 6 `TIMED_OUT` | 300 ms | `market-data-1` | 4 |
| `discard-oldest` | 6 `TIMED_OUT` | 300 ms | `market-data-1` | 4 |

I chose `abort`. It is the only policy that says what happened. The four
refused requests are named and returned at once, the submitter is not held, and
the counter and the outcomes agree. It also matches what the caller can act on:
`503` with `Retry-After` is a signal a client can back off from.

**Rejected: `discard` and `discard-oldest`.** A dropped task has no outcome of
its own. All six came back `TIMED_OUT`, indistinguishable from a slow source,
and the batch spent the whole deadline waiting for work that would never run.
The counter still saw four drops; the caller saw none. Silent loss of a price
request is the worst answer for a money service, because an absent price looks
like a late one.

**Rejected: `caller-runs`, the counter-intuitive one.** It looks like
back-pressure: when the pool is full, the submitter does the work itself and so
slows down. Here the submitter is a Tomcat worker, and the work is a call to the
very source that is slow. Measured, the batch took 1315 ms against a 300 ms
deadline, because the deadline starts counting only after submission, and the
submitting thread was busy running two source calls inside it. Over HTTP,
[`08-caller-runs-starvation.txt`](../evidence/08-caller-runs-starvation.txt):
four Tomcat workers, executor core 1 / max 1 / queue 1, a hanging source. Three
calls reached the source, one on the pool and two on Tomcat workers running the
tasks the executor had refused. `GET /loans`, which never touches the source,
failed with `HttpTimeoutException` after 2 s. Under `abort` and the same setup,
the two refused quotes answered `503` in 283 ms and `GET /loans` answered `200`
in 293 ms. `caller-runs` did not shed load; it moved the pool's bound onto
Tomcat's, which is the resource stage 7 showed running out.

### Full pool, or deadlock? What the dump says

[`08-pool-exhaustion-jstack.txt`](../evidence/08-pool-exhaustion-jstack.txt):
executor core 2 / max 2 / queue 2, read timeout off, a hanging source. Four
requests were admitted and a fifth got `503`. `PoolStats` at that moment:
`active=2, queued=2, rejected=1`; only 2 requests reached the source, the queued
two never did (a full queue is invisible from the source's side).

The lines that say "pool full", in the order I would check them:

1. Both `market-data-*` workers are `WAITING` at the same frame
   (`CompletableFuture.get` under `JdkClientHttpRequest.executeInternal`, inside
   `RestMarketPriceSource.priceOf`). They wait on something outside the JVM.
2. Zero workers are `BLOCKED`. A deadlock is a cycle of threads each holding what
   another needs; a thread parked on a future holds no monitor.
3. `findDeadlockedThreads()` returned null.
4. The counters equal the configuration: `active == max`, `queued == queue`. A
   pool at both limits is a capacity fact, not a fault.
5. Releasing the source drains everything; the test asserts all four admitted
   requests then answer `200`. A deadlock does not recover.

The frame is a future, not a socket read, as stage 7's decision 8 found: the
JDK client reads the socket on its own selector thread.

### Pool parameters: the queue that hides `max`

[`08-pool-sizing.txt`](../evidence/08-pool-sizing.txt), 20 blocking tasks each:

| Configuration | `largestPoolSize` | queued | rejected |
|---|---|---|---|
| core 2, max 8, **queue 100** | 2 | 18 | 0 |
| core 2, max 8, **queue 2** | 8 | 2 | 10 |

`ThreadPoolExecutor` creates a thread beyond `core` only when the queue refuses
an offer. A queue that never refuses means `max` is a number nobody reaches:
the first configuration was written to allow 8 threads and never used more
than 2. The second reaches 8, and pays for it by refusing half the load. The
counters make the behaviour visible instead of assumed.

### The `CompletableFuture` traps and how the product path avoids them

[`08-completablefuture-traps.txt`](../evidence/08-completablefuture-traps.txt).

- **No executor named.** `supplyAsync` ran on `ForkJoinPool.commonPool-worker-1`.
  With the common pool's 3 workers blocked, an unrelated `supplyAsync` did not
  complete within 500 ms. The common pool is shared by everything in the JVM,
  and this service does not size it. `PriceFanOut` always passes the executor.
  On a machine where the common pool's parallelism is 1, `supplyAsync` silently
  falls back to a thread per task, so the reproduction is skipped there
  (an `assumeTrue`), not weakened.
- **A stage nobody joins.** The future was done and completed exceptionally, and
  nothing was thrown to the submitting thread: the failure exists only inside
  an object no one reads. `PriceFanOut` handles every stage with `handle`, so
  the same failure is that security's `Failed(UNAVAILABLE)`.
- **Cancelling does not free the worker.** After the deadline the fan-out
  cancels the future; the pool's active count stayed 1 until the source
  answered. A timeout on the wait is not a timeout on the work. The bound on the
  work is the client's read timeout.

### Request context: the work carries it

[`08-threadlocal-bleed.txt`](../evidence/08-threadlocal-bleed.txt). A one-thread
pool, task A sets `request-A` and does not clear it; task B, another request,
reads `request-A`. Through `RequestContext.propagating` (capture on submit,
restore in `finally`) task A reads `request-A` and task B reads nothing.

`InheritableThreadLocal` does not help. Set to `request-A` before the pool
creates its thread, it is copied at thread creation; after the submitter sets
`request-B`, the second task on that thread still reads `request-A`. Inheritance
happens when the thread is made, and a pooled thread is made once.

Both wrappers **restore the executing thread's previous value; neither clears.**
A plain `clear()` in `finally` is the textbook fix and is wrong under
`caller-runs`, where the task runs on the request's own thread and clearing
erases the context the request is still using. Two tests pin it
(`callerRunsKeepsTheSubmittersContext`, `callerRunsKeepsSubmittersMdc`). Two
layers carry two contexts because `application` has no SLF4J: `PriceFanOut`
carries `RequestContext`, `MarketDataPool` carries the MDC.

### Virtual threads: should this service use them?

Throughput, 400 tasks each blocking 100 ms, three rounds,
[`08-virtual-throughput.txt`](../evidence/08-virtual-throughput.txt): platform
pool of 8, 5006 ms best (79 calls/s); virtual executor, 104 ms best (3846
calls/s), 48.1x. The platform figure is arithmetic, 400 / 8 x 100 ms; the
virtual figure is one wait, because 400 blocked tasks cost 400 cheap threads.
Blocking I/O is the case virtual threads are for.

Pinning, [`08-pinning-jdk21.txt`](../evidence/08-pinning-jdk21.txt) and
[`08-pinning-jdk25.txt`](../evidence/08-pinning-jdk25.txt): eight virtual threads
on a scheduler limited to two carriers, each blocking 200 ms holding its own
lock. On JDK 21 with `synchronized`, 800 ms every round: four waves of two,
because a blocked virtual thread inside a monitor keeps its carrier.
`ReentrantLock`: 200 to 201 ms. On JDK 25 the same jar, unchanged: `synchronized`
200 ms. `-Djdk.tracePinnedThreads=full` on 21 printed one stack with
`reason:MONITOR` and stayed quiet afterwards (the JDK reports each site once),
and does not exist on 25.

So the answer depends on the JDK this service is pinned to, and it is pinned to
21. On 21, virtual threads are usable for the fan-out only if nothing on the
blocking path holds a monitor. That is not a property I can promise for
`RestClient`, the JDK HTTP client and every library beneath them, and pinned
calls on two carriers cost 4x here. The switch stays available
(`type=virtual`) and off by default. I would turn it on after moving to 25, or
after a thread dump under load shows no pinned frame on the path.

The virtual shape is bounded by a semaphore (`max-concurrent`), not by a queue.
An unbounded virtual executor would move stage 7's failure from Tomcat to the
source: threads are cheap, the source's capacity is not. A task with no permit
is refused as `abort` refuses. That semaphore is a crude bulkhead; stage 12
replaces it.

### CAS against `synchronized`

[`08-cas-vs-synchronized.txt`](../evidence/08-cas-vs-synchronized.txt), one
shared `Money` balance, throughput in operations per microsecond (higher is
better):

| Threads | CAS | `synchronized` |
|---|---|---|
| 1 | 37.9 | 28.8 |
| 2 | 11.0 | 10.1 |
| 4 | 5.8 | 10.7 |
| 8 | 5.9 | 13.5 |

Uncontended, the lock-free loop wins. At two threads they tie. From four up CAS
is roughly half the lock's throughput: every failed `compareAndSet` throws away
a `Money.plus` and repeats it, while the lock queues the threads and does each
update once. "Lock-free" means no thread can block the others; it does not mean
faster under contention. A balance updated by many writers wants the lock (or
the database's row lock, which is what this ledger already uses in stage 6).

### The memory-model labs

All in [`labs/08-concurrency`](../../labs/08-concurrency/README.md), each
printing the JVM and CPU first.

- **Visibility** ([`08-visibility.txt`](../evidence/08-visibility.txt)). A
  reader spinning on a plain `boolean`: 8 of 8 rounds never saw the write; with
  `volatile`, 0 of 8 hung. The roadmap expects this to be sporadic. Here it was
  every time, because the loop is JIT-compiled inside the 200 ms pause and the
  compiled loop hoists the read. The sporadic character is real, but it is the
  JIT's timing, so a shorter pause or a cold loop would show it flickering. What
  fixes it: a write to a `volatile` field happens-before every subsequent read
  of that field (JLS 17.4.5, the volatile rule). The reader's read therefore must
  see the write, and the JIT may not hoist it.
- **`volatile` is not atomic**
  ([`08-lost-update.txt`](../evidence/08-lost-update.txt)). 8 threads times
  1,000,000 `count++` on a `volatile int`: observed totals 2.5 to 3.7 million of
  8,000,000, a loss of 54.1% to 68.5% across five rounds. `AtomicInteger`: 0
  lost in all five. Volatile makes each read and each write visible; the
  increment is read, add, write, and two threads can read the same value.
- **Double-checked locking**
  ([`08-dcl.txt`](../evidence/08-dcl.txt)). **Did not reproduce.** 0
  partially constructed objects in 40,000,000 checks per variant, on aarch64
  with JDK 21. The Java Memory Model permits the failure: without `volatile`,
  the reference write may become visible before the constructor's writes, and a
  reader then holds an object with default field values. That it was not
  observed here is a fact about this JVM and CPU, not evidence that the code is
  safe; I have not verified why HotSpot did not show it. The fix is `volatile`
  on the field, which orders the constructor's writes before the reference
  publication for every reader that sees the reference. The lab keeps the
  `volatile` variant and records both zeros.
- **`computeIfAbsent`**
  ([`08-compute-if-absent.txt`](../evidence/08-compute-if-absent.txt)). On JDK
  21, one thread re-entering the same map throws
  `IllegalStateException: Recursive update` rather than hanging. Two threads,
  each inside its own key's function and asking for the other's, deadlock:
  `findDeadlockedThreads()` reported both, each `BLOCKED`, owned by the other.
  The map holds a bin lock while the function runs, so the function must not
  touch the map. The fix: compute outside, publish with `putIfAbsent`.

### JMH: the benchmark the JIT deletes

[`08-jmh-no-blackhole.txt`](../evidence/08-jmh-no-blackhole.txt) and
[`08-jmh-blackhole.txt`](../evidence/08-jmh-blackhole.txt). The daily fee as a
`double`, compile-time constants in and the result dropped: 0.527 ns/op. The
same arithmetic read from state and passed to a `Blackhole`: 1.689 ns/op. The
first is 3.2x faster than the second, which is the tell: it is consistent with
the work having been removed. I did not inspect the generated code, so this is
inference from the numbers. The `BigDecimal` version, the real `FlatRate`, was
64.282 against 64.190 ns/op, equal within error: the mistake did not bite there.
I did not investigate why. The mistake is still a mistake; it only produced
an absurd figure in the cheap case. The lesson is not "Blackhole fixes it" but
that a benchmark number needs a reason it is plausible.

## What I rejected, and why

- **Everything in the main build.** A `volatile`-less singleton or a recursive
  `computeIfAbsent` as product code, even guarded, breaks the rule that `main`
  never ships broken code. Labs keep the failure runnable and the build green.
- **`discard` / `discard-oldest` as defaults.** Silent loss (above).
- **`caller-runs` as back-pressure.** It applies back-pressure to the wrong
  resource (above).
- **A separate executor bean type per shape.** `MarketDataPool` holds both the
  platform and the virtual shape behind one `execute` and one `stats`, so the
  use cases, the tests and the counters do not branch on the type.
- **`clear()` in `finally` for context cleanup.** Wrong under caller-runs
  (above); restore the previous value instead.
- **`InheritableThreadLocal`.** Copies at thread creation, so a pool goes stale
  (above).
- **A per-currency block in the `/quotes` response.** Prices of different
  securities are not addable, so a "total" is meaningless, and a "group" is the
  same list reorganised. Each item carries its own currency, the grouping stays a
  tested use-case function, and `QuotesIT` asserts the wire has no total.
- **A fifth exercise home, a `benchmarks` module.** The module list is fixed in
  `CLAUDE.md`; the JMH code lives in the lab.

## What I observed

The numbers are in the sections above and in `docs/evidence/08-*.txt`. Things
that did not go the way the spec expected:

- **The DCL failure did not reproduce**, in 40,000,000 checks (above).
- **The visibility failure was not sporadic**; it was 8 of 8.
- **The JMH mistake showed only in the `double` case.**
- **`caller-runs` made the batch slower than its own deadline**, which I had
  not predicted: the deadline only starts after submission.
- **A dropped task under `discard` cost the whole deadline.** Six `TIMED_OUT`
  at 300 ms, from work that never started.
- **The default for `queue` matters more than the one for `max`.** Nothing in the
  pool tells you `max` is unreachable except the counters; the counter that shows
  it (`largestPoolSize` 2 of an allowed 8) is the one to watch.

Deviations from the spec, all recorded in the plan's "Found while
implementing": the property prefix is `ledger.marketdata.executor`, not
`market-data.executor`, to match every other stage-7 property for this source;
the 503 contract check is in `QuotesIT` (a pool of one needs its own
application); pinning and the JMH runs are in the lab, not in the main build,
because they need a second JDK and a shaded jar; the `reproducesVirtualThreadPinning`
name from the spec is the lab's `Pinning` class and its two evidence files.

## Where each choice breaks at 100x

- **`abort` and a bounded queue.** At 100x quote traffic the queue of 16 and the
  eight threads fill in milliseconds, and `503` becomes the common answer, not
  the exception. That is the correct signal, but it needs a client that backs
  off on `Retry-After`, and a cache (stage 9) so most quotes never reach the
  pool. Tuning core/max/queue is capacity planning against the source's
  measured latency: threads needed is about request rate times source latency
  (reasoned, not measured here).
- **The batch deadline.** One deadline for the whole batch means one slow
  security spends everyone's time. At 20 ISINs per request and 100x traffic, a
  slow source has every batch waiting the full 3 s. A per-security timeout with
  a circuit breaker (stage 12) replaces it.
- **Cancelled work still holds a worker.** Measured at one worker. At 100x a
  slow source pins every worker for the client's read timeout regardless of the
  batch deadline; the read timeout, not the deadline, is the bound on capacity.
- **The virtual executor's semaphore.** 400 concurrent permits is a guess
  configured by hand. At 100x it either protects the source, if set to what the
  source can take, or is a number nobody revisits. It needs to become adaptive,
  and that is what a bulkhead with a queue and a limit is.
- **Virtual threads on JDK 21.** One pinned call held a carrier for its whole
  block: 800 ms against 200 ms with two carriers. On the default scheduler
  (parallelism equals the CPU count, 4 here) at 100x, a monitor-holding library
  on the blocking path takes carriers out of service one call at a time. This is
  why the switch is off, and the upgrade to 25 is what removes the risk.
- **`RequestContext` and the MDC.** Two hand-written wrappers cover the
  executor this stage created. Every other place that hops threads (a scheduler,
  an async event listener, stage 10's message consumers) is a new place to forget
  one. At 100x more such places, the context has to travel by construction (a
  task decorator on every executor, or scoped values on a newer JDK), not by
  each caller remembering.
- **CAS balance.** Not shipped. If it were, it collapses to half the lock's
  throughput at four threads on this machine, and gets worse as writers grow.
  The ledger's real balance is a database row (stage 6), where the lock is the
  database's.

## The oral check: latency rises at three in the morning

The order matters, because each step rules something out and tells you which
step is next.

1. **Scope it.** Is it one endpoint or all of them, and is it latency or
   errors? All endpoints slow points at something shared (the JVM, the database
   pool, a node); one endpoint points at its own dependency. A rising `503` rate
   on `/quotes` alone means the executor's counters are the next place to look.
2. **Read the counters before anything heavier.** `PoolStats`: `active == max`,
   `queued == queue`, `rejected` climbing means the executor is saturated, and
   the cause is upstream of it (a slow source) or a burst. That rules out a
   deadlock, GC and a code path bug in one look. If the counters are idle and
   requests are still slow, the executor is exonerated and the wait is
   elsewhere: Tomcat, the database pool, or the JVM itself.
3. **Take three thread dumps a few seconds apart.** Same frame, outside the JVM,
   in every worker: a dependency is slow (this stage's dump). Workers `BLOCKED`
   with an owner: lock contention, and `findDeadlockedThreads()` says whether it
   is a cycle. Workers busy in application frames and moving between dumps: CPU
   work, not waiting. Three dumps, because one cannot tell "stuck" from "busy".
4. **Ask the dependency directly.** The source's own latency, from the same
   host, with the same client settings. This confirms or rules out step 3's
   reading.
5. **Then the JVM.** GC pause times and safepoints (stage 15's tooling); a full
   GC at three in the morning with a stop-the-world pause looks like every
   thread stalling at once, which is why it comes after the dumps rather than
   before.
6. **Then what runs at three in the morning.** The stage 11 batch, a backup, a
   deploy: the hour is a clue. A scheduled job competing for the same database
   pool or the same executor is the most common cause and the cheapest to
   check, but it is a hypothesis to test with the steps above, not a starting
   assumption. This step is reasoned from how such systems fail; it is not
   something this stage measured.

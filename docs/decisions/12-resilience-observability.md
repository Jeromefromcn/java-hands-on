# Stage 12 — Resilience and observability — Decision Record

Spec: [`docs/specs/12-resilience-observability.md`](../specs/12-resilience-observability.md)
Plan: [`docs/plans/12-resilience-observability.md`](../plans/12-resilience-observability.md)
Logging rule: [`docs/logging.md`](../logging.md)

Measured on JDK 21.0.12 (Temurin, the `.sdkmanrc` pin), a 4-CPU Linux host,
Spring Boot 4.1.1, Resilience4j 2.3.0, OpenTelemetry through Micrometer Tracing,
Prometheus 3.5.0, Postgres 17 and Kafka 4.1 (Testcontainers, loopback; the
compose stack for the manual run). Every number below is copied from a
`docs/evidence/12-*.txt` file. Where a value is a configured default and not a
measurement, the text says "configured". Where a claim is reasoned and not
measured, it says so; the last section is entirely that.

## What I chose

### The one sentence that matters: alert on the outcome, not the cause

The three alerts (`infra/prometheus/alerts.yml`) are each a thing someone
outside the ledger would notice:

| Alert | Outcome it names | Fires when |
|---|---|---|
| `OutboxEventsNotReachingConsumers` | margin call notices and position events are late | the oldest unsent outbox event is over 300 s old, for 2 minutes |
| `AccrualNotBooked` | a day's fees were not booked | no `dailyAccrual` execution has completed for 26 h (a job that never has reads 0, so a new deployment pages until its first run) |
| `QuotesNotLive` | clients are not getting live prices | over 20% of price requests failed or were answered stale, over 5 minutes, with traffic above 0.1/s |

Why these are not CPU, pool saturation or breaker state: those are
explanations. A saturated pool that sheds load correctly (stage 8) hurts no one,
and an open breaker that serves stale prices (below) is the system working; each
of them would page for a cause that may have no outcome. The reverse is the case
for the three above: whatever the cause (relay down, Kafka down, a failing
sender; a failed job, a missing launch, a lock never released; a source outage,
a refused answer, a bulkhead too small), the outcome is the same and so is the
page. The dashboard carries the causes (breaker state, pool, bulkhead and rate
tokens, consumer lag, dead letters) for the person who has been paged.

`promtool test rules` fires each alert on a series that means it and keeps it
silent on a healthy one, including a young outbox backlog (silent), an accrual
that completed within the day (silent) and one failed quote on a quiet system
(silent): 7 cases, `SUCCESS`
([`12-alert-rules.txt`](../evidence/12-alert-rules.txt)). The first version of the
accrual fixture did not fire, because hourly samples leave the series absent
between them for Prometheus's 5-minute lookback; the fixtures are one-minute.

### Resilience4j's core libraries, wired in code

Not the Spring Boot starter and not annotations. The decorator
(`ResilientMarketPriceSource`) is one class that reads top to bottom; there is no
proxy to bypass by a self-call (stage 5), and nothing depends on a starter's
Boot 4 support. Order, outside in: **breaker, bulkhead, rate limiter, the call.**
An open breaker refuses before a slot or a token is taken. The bulkhead is
outside the limiter, the reverse of Resilience4j's own documentation, because
neither waits: a call the bulkhead refuses has then not spent a token, and the
limiter counts the calls that really reached the source. Our own refusals are
ignored by the breaker (`OwnRefusalsDoNotOpenTheBreakerTest`): the source did
nothing wrong when this instance was busy.

There is **no retry** on the quote path. A quote is a read the client may repeat,
and a retry inside the adapter multiplies the load on a source that is already
failing. The retry storm below is what it would turn into.

### The breaker's thresholds, and where they come from

| Setting | Configured | Why |
|---|---|---|
| slow-call threshold | 1 s | 22 times the healthy p99 below, half the 2 s read timeout; a call this slow is one that is about to time out |
| slow-call rate | 80% | slow answers open it only when nearly all of them are slow |
| failure rate | 50% | |
| window | 20 calls, minimum 10 | the rates mean nothing on fewer calls |
| open wait | 10 s | long enough that a recovering source is not probed continuously, short enough that an outage's end is noticed |
| half-open probes | 3 | |

What was measured: a healthy source, through the whole client stack, answered in
42,905 µs (p50), 43,870 (p90), 45,272 (p99), 52,014 (p99.9) and 62,759 (max),
2,000 calls after 200 of warm-up
([`12-market-data-latency.txt`](../evidence/12-market-data-latency.txt)). The floor
is the JDK client and server's 40 ms delayed-acknowledgement interaction on
loopback, not the ledger's code, so the number says nothing about a real source's
latency; what it fixes is the order of magnitude below which a threshold would
open the breaker on a healthy day. The thresholds are therefore defaults to be
re-derived from the real source's p99 when there is one.

The states, in order, with a test's shortened windows (window 10, minimum 5, open
wait 200 ms; [`12-breaker-transitions.txt`](../evidence/12-breaker-transitions.txt)):
`CLOSED -> OPEN` at 01:17:07.153 after 5 failed calls (failure rate 100%),
`OPEN -> HALF_OPEN` at .353 (200.3 ms later, on the library's timer, not on a call),
`HALF_OPEN -> CLOSED` at .392 after two good probes. While open the delegate's
call count did not move. Against the compose stack with the defaults
([`12-manual-run.txt`](../evidence/12-manual-run.txt)): `CLOSED -> OPEN` at
10:35:58.513 (failure rate 50% over 20 calls), `OPEN -> HALF_OPEN` at 10:36:08.513
(10.000 s later), `HALF_OPEN -> CLOSED` at 10:36:20.133, when traffic resumed.
Note what the first line costs: the window held 10 good calls and the rate
only reached 50% with the tenth failure, so ten real attempts were made on a dead
source before the breaker refused the next four (all 14 answers of the outage
were stale). A count-based window is slow to open on a source that was healthy
for a while; see the 100x section.

### The bulkhead is the fix for stage 8's pain

Eight Tomcat workers, a source that answers after 3,000 ms, twelve concurrent
`/quote` calls, and `GET /loans` (which never touches the source) timed meanwhile
([`12-bulkhead-starvation.txt`](../evidence/12-bulkhead-starvation.txt)):

| | `GET /loans` | `/quote` |
|---|---|---|
| no bulkhead | 2,968 ms | 12 × 200 |
| bulkhead of 4 | 22 ms | 4 × 200, 8 × 503 at once |

Without it every worker is inside the source and an unrelated endpoint waits for
one for the length of the delay. With it, the surplus is refused without a
thread, and the endpoint that has nothing to do with the source never notices.
`reproducesSlowSourceStarvesUnrelatedEndpoint` asserts the first row as the wrong
behaviour and the second as the cure, in one test body with the bulkhead
switched. The default is 32 concurrent calls, matching the virtual-thread
executor's permits; it is a property.

### The stale price: degrade, say so, bound it

When the source cannot give a price and a last-known price younger than 15 minutes
exists in the security's currency, the quote is that price with `"stale": true`,
the time it was true (`asOf`), and a `Warning: 110 - "Response is Stale"` header
([`12-stale-quote.txt`](../evidence/12-stale-quote.txt)); it is logged at WARN with
the ISIN and the age, and counted (`ledger_quote_requests_total{result="stale"}`).
A live price carries neither field. Past the maximum age, or with nothing
remembered, the original failure is thrown: an old price is not "better than
nothing". Only `QuotePrice` degrades; opening and returning a position read the
security row's price, and `staleQuoteNeverFeedsLending` opens a position while the
source is down and the last-known price is 41.30, and books it at the row's 41.26.

It lives in the use case, not the adapter, for two reasons: the adapter cannot see
the security's currency (a stale price in another currency is refused), and any
other future caller of `MarketPriceSource` would get a stale price it never asked
for. The store is in memory and per instance, deliberately: it exists for the
moment dependencies fail, so it depends on none.

### One timeout budget, checked at start-up

```
GET /quotes                        GET /securities/{isin}/quote     ledger reads and writes
  30 s   edge                        30 s   edge                      30 s   edge
   3 s     fan-out deadline           2.5 s market data (0.5+2)        13 s   database (3 acquire + 10 statement)
   2.5 s     market data (0.5+2)
```

([`12-timeout-budget.txt`](../evidence/12-timeout-budget.txt).) The rule is per layer:
the outer value must be strictly greater than the sum of what it calls. `TimeoutBudget`
refuses start-up otherwise, naming the chain; unbounded (zero) is longer than
anything and breaks every chain it is in. The statement timeout is a
`SET statement_timeout` on every connection, and the database honours it:
`pg_sleep(5)` under a 1 s setting was cancelled after 1,013 ms
([`12-statement-timeout.txt`](../evidence/12-statement-timeout.txt)).

What an inverted budget does, reproduced
([`12-retry-storm.txt`](../evidence/12-retry-storm.txt)): ten callers, up to three
attempts each, a server that works for 1,000 ms. With a caller timeout of 300 ms the
server saw 30 requests, 30 in flight at once, and completed none that anyone
waited for; with a 3 s timeout it saw 10, 10 in flight, 10 completed. The outer layer
gave up while the inner was still working, so the retry doubled the work instead of
replacing it. The default connect timeout went from 1 s to 500 ms for this reason:
1 s + 2 s read equalled the 3 s fan-out deadline, and equal is refused.

### Probes: liveness asks one question, readiness another

`liveness` is `livenessState` alone; `readiness` is `readinessState` and the
database; Redis, Kafka, the source and the breaker are not in either
([`12-probes.txt`](../evidence/12-probes.txt)). With the database refusing connections,
readiness is 503 and liveness 200 (a restart does not fix a database). With Redis
absent, both are 200 while the aggregate `/actuator/health` is 503, which is why the
aggregate is not a probe. `reproducesReadinessUpWhileDatabaseIsDown` sets Boot's
default readiness membership and shows `UP` with the database down: a rolling
update's load balancer would send traffic to an instance that cannot answer it.
`server.shutdown=graceful` makes readiness `REFUSING_TRAFFIC` first
(`readinessRefusesTrafficOnShutdown`).

### Metrics: Micrometer, counters that count what committed

`LedgerMetrics` is a port with empty defaults; the Micrometer implementation
registers each counter about data for the commit of the surrounding transaction
(and counts at once outside one), so a use case may call it from anywhere it
establishes the fact. `RolledBackOpenCountsNothingIT`: an open whose caller's
transaction fails afterwards counts 0; a committed one counts 1. A scrape of a
running ledger with messaging, consumers and the breaker on is 343 series and
84,469 bytes, 11 of them `ledger_*`
([`12-metrics-scrape.txt`](../evidence/12-metrics-scrape.txt)); none carries a label
named counterparty, position, isin, id, key or trade. Gauges that read the
database (oldest unsent outbox event, last successful batch) cache the read for
2 s and 5 s; the last-success gauge reads Spring Batch's metadata, so it survives a
restart, and reads 0 for a job that never completed, because `now - last > window`
is true for 0 and silently false for NaN, and "never" must page. The tag is
`batch_job`, not `job`: Prometheus owns `job` and renames a clash to `exported_job`.

`InfraQueriesIT` keeps the dashboard and the rules honest: it reads their
expressions (25 meters), takes a real scrape after driving every flow that creates
one, and fails on any that is absent
([`12-infra-queries.txt`](../evidence/12-infra-queries.txt)). It failed first, on two
meters the flow had not raised, which is the check working.

### Traces: the context goes in the outbox row

Micrometer Tracing over OpenTelemetry, W3C `traceparent`, OTLP to Jaeger. An HTTP
request's span has ended long before the relay, on another thread, sends the event
it caused, so the context is stored with the event
(`outbox_event.traceparent`, V16), put on the Kafka record, and continued by each
listener ([`12-trace-across-kafka.txt`](../evidence/12-trace-across-kafka.txt)): the
trace id of `POST /loans` (`83d13444…`) is the id on the response, in the outbox
row and in the record's header. A price published inside a span of its own gave
`feed publishes price` → `consume sl.price-updates` (parent: the feed) →
`consume sl.margin-calls` (parent: the price consumer, because the margin call was
written inside that consumer's span), all in trace `1fdf0feb…`.
`reproducesTraceBrokenAtOutbox` builds the pre-stage-12 outbox and shows the
consumer's span in a different trace from the request that wrote the event. In the
compose run Jaeger held the `http post /loans` trace under the id the response
carried.

`TraceIdFilter` now takes the server span's trace id (so the response header, the
logs and the backend name one trace) and runs second, after Boot's observation filter,
which starts the span. Sampling is 1.0 everywhere except `prod`, 0.1. The cost, on a
warm JVM, of `GET /loans` at sampling 1.0 against 0.0: 1,419 µs against 1,270 µs
median, 3,472 against 2,577 at p99 (runs 4 and 6 of
[`12-tracing-overhead.txt`](../evidence/12-tracing-overhead.txt)); about 150 µs a
sampled request. The first three runs are dominated by JIT warm-up (2,492 µs at
sampling 0.0 in run 1), so the settings run forwards and then backwards.

### Logs: JSON in prod, the MDC carried, no names and no amounts

In `prod` every event is one JSON object with `traceId` and `spanId` as fields
([`12-structured-log.txt`](../evidence/12-structured-log.txt)). A pooled thread logs
with no trace id unless the task carries the MDC
([`12-mdc-pool-undecorated.txt`](../evidence/12-mdc-pool-undecorated.txt): `null`; with
the decorator [`12-mdc-pool-decorated.txt`](../evidence/12-mdc-pool-decorated.txt) sees
the submitter's, and the next task from a thread with no trace sees `null` again:
the worker is not left holding the previous id). One helper, `MdcPropagation`, serves
the stage 8 pool, Boot's application executor and the accrual partition pool.

The rule ([`docs/logging.md`](../logging.md)): ids, counts, currencies, dates and
outcomes; never a counterparty name, an amount, a payload or a secret. `LogHygieneIT`
drives open, return, a price that raises a margin call and its notice, the accrual
and a live and a stale quote, with `com.jerome` at DEBUG, and searches 72,090
characters of output for the name and every amount that went in or came out:
`found: []`
([`12-log-hygiene.txt`](../evidence/12-log-hygiene.txt)). The one violation it needed
fixed was the margin call notice's log line, which printed the shortfall. With that
amount put back the test fails and names it (`found: [45668.91]`).

## What I rejected

- **The Resilience4j Spring Boot starter and annotations.** Behaviour hidden in a
  proxy, ordering by annotation precedence, a dependency on Boot 4 support. Rejected
  for a class that can be read.
- **A retry in the quote path.** Multiplies load on a failing source, and the budget
  reproduction shows how.
- **Falling back inside the adapter.** It cannot check the currency and every caller
  would get a stale price silently.
- **A Redis-shared last-known store.** Adds a dependency to the path that exists
  because dependencies fail.
- **Redis, Kafka or the source in a probe.** One dependency's outage would take every
  instance out of rotation together. They are degraded around; they are metrics.
- **The aggregate `/actuator/health` as a probe.** It is DOWN wherever Redis is
  absent; the exposure test and the probe test say so.
- **Pattern-based log scrubbing.** A mask for amounts cannot recognise a name, and a
  rule kept only partly gives the comfort of a rule kept. The rule is at the source and
  held by a test over real flows.
- **Cause-based alerts.** Above.
- **A producer span at the relay.** The stored context goes on the record as it is and
  the consumer is a child of the writer; a span for the send would be one more row and
  no new information.
- **Micrometer in the use cases, or increments inside the transaction.** The use case
  sees a port; the implementation counts at commit.
- **Metrics tagged by counterparty or position.** One series per counterparty, kept for
  as long as the backend keeps series.
- **OTLP metrics push.** Prometheus scrapes; a second path to a collector that may not
  be there is noise.
- **`@ConditionalOnBean(Tracer.class)` for the tracing port.** Evaluated before the
  tracer is registered, so it was always false and the application silently ran with no
  tracing (`HttpTraceIT` caught it: the response header and the exported span
  disagreed).
- **The Maven gate depending on Compose.** The alert rules are tested by
  `infra/prometheus/check.sh` in Docker; the gate does not need an image pull.

## What I observed

- **The quote cache hides a short outage by design.** In the compose run with the
  local profile's cache on, all 14 quotes of the outage were answered from the stage 9
  cache and the source was never asked; neither the breaker nor the stale price was
  exercised. The run that is in the evidence has the cache off. The breaker and the
  stale price are for the requests the cache does not hide.
- **Boot's aggregate health is not a probe.** DOWN with no Redis, whether or not the
  cache is on.
- **`MarketDataUnavailableException` is a 504 and `OverloadedException` a 503**, stage
  7 and 8's mapping unchanged; the spec said 503 for a quote with nothing to fall back
  to, which is wrong.
- **A budget that is "equal" is refused**, and that refused nine existing ITs: a
  read timeout of 500 ms, 10 s or 0 against a 3 s fan-out deadline. Found by the full
  build of the next commit, not by the section's own tests, which is why the full
  build now runs after every commit.
- **`ConfigurationPrecedenceIT`, which starts every profile, failed** when `prod` gained
  a collector URL with no default; it supplies a placeholder as it does for the market
  data URL.
- **Order dependence between ITs that share a JVM:** `HttpTraceIT` passed alone and
  timed out after `TraceAcrossKafkaIT`, because a closed context shuts its span
  processor down and a shut-down in-memory exporter drops every span. One exporter per
  context.
- **`LogHygieneIT` first passed on the wrong evidence:** consumers start from the
  earliest offset, so notices replayed from other tests' history satisfied "a notice
  was logged". It now waits for a notice to its own counterparty.
- **On this machine a container cannot reach the host through `host.docker.internal`**
  (`no route to host`: the firewall refuses traffic between bridges), so Prometheus
  and Grafana use host networking on loopback. Linux only.
- **A price published at a fixed version is silently ignored once the shared topic's
  history holds one at least as new.** `InfraQueriesIT` failed in the full build and
  passed alone for this reason (the margin call never happened); the three tests that
  publish a price now use the time as the version.
- **Prometheus's lookback made an hourly fixture useless** (above).
- **`TraceIdFilter` had to move** from first to second place to have a span to read.

## Where each choice breaks at 100×

Reasoned, not measured: nothing here was run at 100×.

- **The breaker is per instance and its window is a count.** At 100× the traffic a
  20-call window is a fraction of a second, and the breaker flaps; it should become a
  time-based window once traffic is steady. At 100× the instances, each learns the
  outage alone, and each probes the recovering source with 3 calls at the same
  10-second boundary: 100 instances are 300 probes at once into a source that has just
  come back. The open wait needs jitter, or the state needs to be shared.
- **The rate limiter is per instance too.** 200 per second per instance is 20,000 a
  second to the source at 100 instances; the limit protects this instance's budget,
  not the source's. A real quota is a shared limiter or one the gateway enforces.
- **The bulkhead's 32 is a constant.** Concurrency is rate times latency; at 100× the
  rate the same latency needs 100× the slots, and a fixed 32 turns the surplus into
  503s. It should be derived from a measured p99 and the instance's share of traffic.
- **The last-known price is per instance and empty after a deploy.** An outage that
  begins just after a rolling update meets instances with nothing to fall back to, and
  the stale price protects exactly those that were already warm.
- **Gauges that read the database run once per instance.** Every instance computes the
  same outbox age and last-success time; with a 2 s and a 5 s cache that is 100
  instances × a query per interval on the outbox's partial index and the batch tables.
  Cheap, and still redundant: one exporter, or one elected instance, is the shape.
- **Series and cardinality.** 343 series per instance is 34,300 at 100 instances, which
  Prometheus carries; the risk is the labels that are per instance by construction
  (Kafka's `client_id` per consumer), which multiply with every deploy.
- **Tracing.** Head sampling at 0.1 of 100× the requests is ten times today's traffic
  in spans; the in-memory Jaeger is not a backend. The tail decision (keep the slow and
  the failed) belongs at the collector. The outbox column adds a 55-byte string per
  row, and the retention job already purges those rows.
- **Logs.** One JSON object per event at 100× the traffic is a log pipeline's problem
  first: sampling of INFO on the hot path, and no DEBUG in production. The hygiene test
  covers the flows it drives; a new log statement is not covered until a flow reaches
  it, and the rule has no static check (a type with a safe `toString`, or an ArchUnit
  rule against logging a `Money`, would be the next step).
- **The alert on outbox age** is one `max` over instances reading the same table, and
  stays correct; the quote alert's 5-minute rate window is too slow when 100× the
  traffic makes a minute of failures a very large number of failed requests.
- **The timeout budget counts one statement per request.** A request that makes three
  sequential queries can take three times the 10 s ceiling before the edge's 30 s; the
  chain is a bound per call, not per use case.

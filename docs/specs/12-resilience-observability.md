# Stage 12 — Resilience and observability

## Problem

The ledger depends on things it does not own: a market data source that can be
slow or down, a Kafka that can lag, a Postgres that can refuse. Stage 7 bounded
the wait on the source and stage 8 bounded the pool behind it, and nothing else
happens when the source is bad: every quote is an error, every caller keeps
trying, and nobody is told. Stage 10 left three quantities that can be read
(oldest unpublished outbox row, consumer lag, dead-letter growth) and stage 11
left a `BatchRunMetrics` port that logs. Nothing is exported, nothing is
traced, and the logs are text.

Stage 12 makes the failure of a dependency a bounded, visible and *labelled*
event, and drives each mechanism to an asserted or measured result: a breaker
that opens and closes, a slow source that no longer starves unrelated
endpoints, a price that is stale and says so, timeouts that shrink inward, a
readiness probe that tells the truth, metrics a dashboard and three alerts
read, a trace that survives a Kafka hop, and a log line that carries no
counterparty or amount.

Five facts shape the stage.

- **A fallback that is not announced is a wrong answer.** A quote served from
  memory after the source failed is useful to a screen and dangerous to a
  lending decision. The response says `stale` and from when, a counter counts
  it, and a price past a maximum age is an error, not a fallback.
- **Timeouts are one budget, not many settings.** The outer layer must outlive
  the inner ones, or the caller retries work that is still running and the
  source sees the load multiplied. The budget is checked at start-up, not by
  reading YAML.
- **Probes answer different questions.** Liveness asks "is restarting this
  process the fix"; readiness asks "can it serve now". A dependency that can be
  degraded around (Redis, Kafka, the source) must not be in either, or one
  dependency's outage takes every instance out of rotation together.
- **A trace crosses a boundary only if the boundary carries it.** HTTP and
  Kafka clients do not know that the outbox relay runs on another thread, later,
  after the request's span has ended. The context is written to the outbox row
  with the event, because that is the only place both sides can see.
- **The money invariant meets the logs.** An amount or a counterparty name in a
  log line is a copy of the ledger in a system with a different retention and
  audience. The rule is written down and held by a test that drives the real
  flows and searches the output.

## Settled design decisions

The decision record argues each; the spec states what they commit the code to.

1. **Resilience4j core libraries, wired in code.** `circuitbreaker`,
   `bulkhead`, `ratelimiter` and `micrometer`; not the Spring Boot starter.
   Behaviour is visible in one decorator class, there is no annotation to
   fall to stage 5's self-invocation, and no dependence on a starter's Boot 4
   support.
2. **One decorator wraps the source: `ResilientMarketPriceSource` in
   `adapter-web`.** Order, outside in: circuit breaker, rate limiter,
   bulkhead, the HTTP call. A breaker that is open then refuses before a
   permit or a token is taken, and a bulkhead refusal is *ours* and is not
   counted against the source's health.
3. **No retry on the quote path.** Quoting is a read a client may repeat; a
   retry inside the adapter multiplies load on a source that is already
   failing. Retry belongs where stages 10 and 11 put it (the consumer's
   backoff, the settlement ladder).
4. **Failure classification.** Counted as failures of the source:
   `MarketDataUnavailableException` (timeout, connection),
   `MarketDataRejectedException` (5xx, unreadable or foreign answer), and calls
   slower than the slow-call threshold. Not counted: `UnknownReferenceException`
   (404 is an answer), `BulkheadFullException`, `RequestNotPermitted`. Our
   refusals map to `OverloadedException` (HTTP 503 as in stage 8); an open
   breaker maps to `MarketDataUnavailableException`.
5. **Degradation lives in `QuotePrice`, not in the adapter.** Only the display
   path degrades. `QuotePrice` records every price it returns in a bounded
   last-known store; when the live lookup fails with unavailable or
   overloaded, and a last-known price no older than
   `ledger.quote.stale-max-age` exists in the security's currency, it returns
   that price marked stale with its `asOf`. Open and return use the security
   row's price and never see a stale quote.
6. **Probe groups.** `liveness` = `livenessState` only. `readiness` =
   `readinessState` + `db`. Redis, Kafka, market data and the circuit breaker
   are health *details* and metrics, not probe members. `server.shutdown=
   graceful`, so readiness goes `REFUSING_TRAFFIC` first on shutdown.
7. **Timeout budget, validated at start-up.** `ledger.timeouts.*` holds the
   edge deadline, the fan-out deadline, the source's connect and read
   timeouts, the database statement timeout and the pool's acquire timeout.
   `TimeoutBudget` states the chains (edge → fan-out → source; edge → pool
   acquire → statement) and refuses to start when a chain's outer value does
   not exceed the sum of what it calls. The statement timeout is applied to
   the connection, and a test proves the database honours it.
8. **Metrics are exported through Micrometer and scraped by Prometheus.**
   Business counters are incremented *after* the commit that makes them true
   (never inside a transaction that can roll back) and never tagged with a
   counterparty or position id. Outcome gauges (oldest unpublished outbox age,
   open positions, last successful accrual) read the database or the stage
   10/11 stores. `BatchRunMetrics` is bound to Micrometer here.
9. **Alerts are three, each on an outcome**, written as Prometheus rules and
   tested with `promtool test rules`: events not reaching consumers (outbox
   age), fees not booked (no successful accrual run inside its window), and
   quotes unusable (the share of quote requests that failed or were served
   stale). Cause-based rules (CPU, pool saturation, breaker state) are
   dashboard panels, not pages.
10. **Tracing is Micrometer Tracing with the OpenTelemetry bridge, exported
    over OTLP to Jaeger.** The application owns a `Tracing` port
    (`currentTraceparent`, `inSpan`); `bootstrap` implements it. `TraceIdFilter`
    adopts the server span's trace id instead of generating one, falling back
    to its own when no tracer is present. The W3C `traceparent` is stored in a
    new nullable `outbox_event.traceparent` column, put on the Kafka record as
    a header by the sender, and read by each listener, which continues the
    trace in a consumer span.
11. **Structured logs in `prod`, text elsewhere.** Boot's `logstash` JSON
    format with the MDC (`traceId`, `spanId`) in every event. A `TaskDecorator`
    copies the MDC into pooled threads Spring creates; the stage 8 pool keeps
    its own copy of that logic and both use one helper.
12. **Log redaction is a rule plus a test.** Rule: logs carry ids (position,
    counterparty id, ISIN, message id), currencies and counts; they never carry
    a counterparty name or a monetary amount. `LoggingNoticeChannel` changes to
    comply. `LogHygieneIT` runs open, return, margin call, accrual and quote
    flows with the synthetic names and amounts of the test and fails if any
    appears in the captured output.

## Scope

In: the plan's sections. Product code: the resilient source, the last-known
store and stale marking, the timeout budget, probe groups, the metrics binders,
the tracing port and its propagation, structured logging, the MDC decorator,
and `infra/` for Prometheus, Grafana and Jaeger.

Out: authentication on the actuator endpoints (stage 13; the management port is
separate and not routed in `prod` until then), packaging of the dashboards into
an image (stage 14), a metrics backend with retention, log shipping, tail
sampling, and anything that retries a quote.

Broken versions go where `CLAUDE.md` puts them:
`reproducesSlowSourceStarvesUnrelatedEndpoint`,
`reproducesReadinessUpWhileDatabaseIsDown`,
`reproducesRetryStormWhenOuterTimeoutIsShorter`, `reproducesMdcLostOnPooledThread`
and `reproducesTraceBrokenAtOutbox` are tests that assert the wrong behaviour
and point at the decision record.

## Acceptance

1. With the source made to fail, the breaker's states `CLOSED → OPEN →
   HALF_OPEN → CLOSED` are logged in order by a test, and the thresholds are
   derived from a measured healthy latency distribution in the decision
   record.
2. With the source made slow, `/quote` requests are bounded by the bulkhead
   and an unrelated endpoint stays within its latency bound; the same test
   without the bulkhead shows it blocked.
3. With the breaker open and a last-known price present, `/quote` answers
   `200` with `stale: true` and `asOf`; with none, or past the maximum age,
   it answers the stage 7 `503`. A stale price is never used by open or
   return.
4. `TimeoutBudget` refuses a configuration whose outer value is not greater
   than the inner sum; the retry storm of an outer-shorter budget is
   reproduced and counted at a stub source; the database aborts a statement
   at the configured timeout.
5. Readiness is `DOWN` when the database cannot be reached and `UP` when Redis
   or the source is down; liveness is `UP` in both cases. The default Boot
   readiness group is shown reporting `UP` with the database down.
6. `/actuator/prometheus` serves the business, resilience, pool, outbox, batch
   and JVM meters; `/actuator/env` is not exposed; no meter carries a
   counterparty or position tag.
7. A promtool test fires each of the three alerts on a fixture series and does
   not fire on a healthy one.
8. One trace id is found on the HTTP span, the outbox row, the Kafka record,
   and the consumer span of a price update that leads to a margin call, across
   the relay's separate thread; the broken version, without the outbox
   column, starts a new trace at the relay.
9. In `prod` format every log event is one JSON object with `traceId`; a
   pooled thread's log line carries the submitter's trace id with the
   decorator and none without; `LogHygieneIT` is green.
10. `./mvnw clean verify` is green; `ArchitectureIT` still holds (no
    Resilience4j, Micrometer or OpenTelemetry type in `domain` or `application`).
11. The decision record contains only measured numbers and the 100x section.

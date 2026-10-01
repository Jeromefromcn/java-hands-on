# Stage 12 — Resilience and observability · Implementation Plan

Spec: [`docs/specs/12-resilience-observability.md`](../specs/12-resilience-observability.md)

One section per commit, in order. Implement a section, tick its checkbox in
the same commit, then commit. `./mvnw clean verify` at the repository root
after every section that touches the main build.

## Ground rules for every section

- Packages: `application.observability` (ports: `Tracing`, `LedgerMetrics`),
  `application.quote` (last-known store, stale marking), `adapter.web.client`
  (resilient source), `adapter.web.quote`, `bootstrap.config` and
  `bootstrap.observability` (Micrometer / tracing / logging wiring).
- Third-party observability and resilience types (Resilience4j, Micrometer,
  OpenTelemetry) never appear in `domain` or `application`; `ArchitectureIT`
  gets the rule in section 1. Adapters and `bootstrap` may use them.
- Pure unit tests end in `Test`; anything needing Docker or a Spring context
  ends in `IT`. Postgres, Redis and Kafka come from the shared test containers.
- A test that asserts wrong behaviour is named `reproduces<Failure>` and
  carries `// See docs/decisions/12-resilience-observability.md`.
- Time-dependent logic takes a `Clock`. Where elapsed real time is the subject
  (breaker wait, timeouts) the test says so and has `@Timeout`.
- Evidence is written by tests to `<module>/target/evidence/12-*.txt` and
  copied by hand to `docs/evidence/`. Measurement tests are
  `@Tag("measurement")` and run under `-Pmeasure`.
- Full `./mvnw clean verify` after every section, run in a throwaway worktree
  of the committed HEAD when the working tree is dirty.

## Decisions made while planning

Each is carried to the decision record.

1. **The stub source of the stage 7/8 tests (`StubPriceServer`) is reused** and
   extended with a switchable mode (healthy, delayed, failing) rather than
   adding WireMock to the test classpath.
2. **Breaker, bulkhead and rate limiter are configured through
   `ledger.resilience.marketdata.*`** with defaults justified in section 3 and
   4's evidence; every value is a property so a test can shrink a window.
3. **The last-known store is in memory and per instance.** A stale price is a
   courtesy to a screen; sharing it through Redis would add a dependency to the
   path that exists because dependencies fail.
4. **`Tracing` is a port with a `NONE` implementation**, so the application
   module and every existing test run with no tracer.
5. **Tracing tests use the OpenTelemetry SDK's in-memory span exporter**, not
   mocks of the bridge: the claim is about what the real propagator does.
6. **Alert rules are tested by `promtool` in a Docker run** by
   `infra/prometheus/check.sh`; the Maven gate does not depend on Compose.

## Review focus

1. A stale price never reaches open or return (section 5).
2. Our own refusals (bulkhead, rate limit) never open the breaker (sections 3, 4).
3. No meter has a high-cardinality tag (section 7).
4. A counter is never incremented in a transaction that rolls back (section 7).
5. A trace started by an HTTP request is the same trace in the consumer, with
   the relay on its own thread (section 9).
6. No log line from the flows carries a counterparty name or an amount
   (section 11).

---

## [x] 1 · Actuator baseline

**Change**
- `bootstrap` POM: `spring-boot-starter-actuator`, `micrometer-registry-prometheus`.
- `management.endpoints.web.exposure.include=health,info,prometheus`; `env`,
  `beans`, `configprops`, `heapdump` are not exposed. `management.endpoint.
  health.show-details=never` (stage 13 revisits).
- `application-prod.yml`: `management.server.port` set separately from the
  application port.
- `server.shutdown=graceful`.
- `ArchitectureIT`: no `io.github.resilience4j`, `io.micrometer`,
  `io.opentelemetry` type in `domain` or `application`.

**Tests**
- `ActuatorExposureIT`: `/actuator/health` is 200; `/actuator/env`,
  `/actuator/beans` are 404; `/actuator/prometheus` serves text with
  `jvm_memory_used_bytes`.

**Acceptance**
- Full build green; a secret placed in an env var does not appear on any
  exposed endpoint.

## [x] 2 · Liveness and readiness

**Change**
- `management.endpoint.health.probes.enabled=true`; groups: `liveness` =
  `livenessState`; `readiness` = `readinessState`, `db`.
- Redis, Kafka and market data have no health indicator in a probe group.

**Tests**
- `ProbesIT`: with the database reachable both are `UP`. With the data source
  made to refuse connections (a test proxy over `DataSource`), readiness is
  503 and liveness is 200. With Redis stopped (cache on), both are `UP`.
- `reproducesReadinessUpWhileDatabaseIsDown`: the same refusal with the group
  set to Boot's default membership (`readinessState` only) reports `UP`; the
  assertion is the wrong answer, with the comment saying what a rolling update
  does with it.
- Graceful shutdown: after `close()` begins, readiness is `REFUSING_TRAFFIC`
  (`AvailabilityChangeEvent` captured).

**Acceptance**
- The three assertions above pass; evidence file with both groups' bodies.

## [x] 3 · Circuit breaker

**Change**
- `adapter-web` POM: `resilience4j-circuitbreaker`.
- `ResilientMarketPriceSource` (decorator over any `MarketPriceSource`),
  `ResilienceProperties` (`ledger.resilience.marketdata.breaker.*`: sliding
  window size, minimum calls, failure-rate threshold, slow-call duration and
  rate, open wait, half-open permits).
- Classification as in the spec (decision 4). An open breaker throws
  `MarketDataUnavailableException` with the breaker's name and state in the
  message.
- State transitions are logged (`from → to`, with the metrics that caused it).
- `StubPriceServer` gains a mode switch.

**Tests**
- `BreakerTransitionsTest`: failures open it; while open the source is not
  called (call count); after the wait it is half-open; success closes it. The
  recorded transition log is the evidence file.
- `UnknownSecurityDoesNotOpenBreakerTest`: a stream of 404s leaves it closed.
- `SlowCallsOpenBreakerTest`: slow answers (under the read timeout) open it by
  the slow-call rate.
- `MarketDataBreakerMeasurement` (`measurement`): 500 healthy calls give
  p50/p99/max; the slow-call threshold and read timeout in the record are
  derived from them.

**Acceptance**
- Transition log shows `CLOSED → OPEN → HALF_OPEN → CLOSED` in order; the
  record's threshold table cites the measured percentiles.

## [x] 4 · Bulkhead and rate limiter

**Change**
- `resilience4j-bulkhead`, `resilience4j-ratelimiter`; both inside the breaker
  as the spec orders them. Bulkhead: semaphore, `max-concurrent`, wait 0.
  Rate limiter: limit per second, wait 0. Their exceptions map to
  `OverloadedException`; `PriceFanOut` maps `OverloadedException` to the
  `OVERLOADED` outcome.
- Breaker ignores both exceptions.

**Tests**
- `BulkheadRefusesBeyondLimitTest` and `RateLimiterRefusesBeyondRateTest`.
- `OwnRefusalsDoNotOpenBreakerTest`.
- `reproducesSlowSourceStarvesUnrelatedEndpoint` (IT, real server, Tomcat
  threads capped small): without the bulkhead, enough concurrent `/quote`
  calls against a delayed source leave a `GET /positions` waiting past a bound;
  with it, the same load leaves it within the bound and the surplus `/quote`
  calls are 503 fast. Latencies measured and written as evidence.

**Acceptance**
- The reproduction and the fix are the same test body with the bulkhead
  switched; both latency tables are in the evidence.

## [x] 5 · Stale price degradation

**Change**
- `application.quote`: `Quote(Money price, boolean stale, Instant asOf)`;
  `LastKnownPrices` (bounded, per instance, `Clock`-driven);
  `QuotePrice.quote` returns `Quote`; on unavailable/overloaded it consults the
  store within `stale-max-age` and the security's currency.
- `PriceFanOut`/`QuoteOutcome.Priced` carry the flag; `QuoteController` and
  `QuotesController` add `stale` and, when stale, `asOf`; the single response
  also sets `Warning: 110 - "Response is Stale"`.
- WARN log per degradation (isin, age) and a counter hook on `LedgerMetrics`.
- OpenAPI document and contract test updated.

**Tests**
- `QuotePriceDegradationTest` (fakes): fresh → not stale; failure with a recent
  last-known → stale with `asOf`; failure past the max age → the exception;
  failure with a last-known in another currency → the exception; unknown
  security → not-found, never stale.
- `StaleQuoteIT`: breaker forced open; `/quote` is 200 with `stale: true`;
  `/quotes` marks only the degraded items.
- `staleQuoteNeverFeedsLending`: with the source dead and a last-known price,
  opening a position still uses the security row's price.

**Acceptance**
- Contract test green with the new fields; evidence of a stale response body.

## [ ] 6 · Timeout budget

**Change**
- `ledger.timeouts.*` (edge, fan-out, source connect, source read, db
  statement, pool acquire) bound to `TimeoutBudget`, which validates the two
  chains at start-up and exposes the table. `MarketDataProperties`, the
  fan-out deadline and Hikari/JDBC settings read from it; the statement
  timeout is applied to every connection (`options=-c statement_timeout=…` or
  a Hikari init SQL).
- The table is written to the start-up log and to the evidence file.

**Tests**
- `TimeoutBudgetTest`: the defaults pass; an outer value equal to or below the
  inner sum is refused with the offending chain named.
- `StatementTimeoutIT`: `pg_sleep` longer than the setting fails at about the
  setting, measured.
- `reproducesRetryStormWhenOuterTimeoutIsShorter`: a client with a shorter
  timeout than the stub's delay and three attempts drives the stub to a peak
  of in-flight handlers above the number of requests the client meant to make;
  the same client with a budget-correct timeout does not. Counts are evidence.

**Acceptance**
- Table of timeouts, outermost first, strictly decreasing along each chain;
  storm counts in the record.

## [ ] 7 · Metrics

**Change**
- `application.observability.LedgerMetrics` (port, `NONE`); Micrometer
  implementation in `bootstrap`. Meters: `ledger.positions.opened` (tag
  currency), `ledger.margin.calls.raised`, `ledger.notices.sent`,
  `ledger.quote.requests` (tag outcome: live, stale, error),
  `ledger.accrual.run.duration` and `ledger.accrual.last.success.timestamp`
  via `BatchRunMetrics`, gauges for `ledger.outbox.oldest.age.seconds`,
  `ledger.outbox.unpublished`, `ledger.positions.active`, the market data
  pool, `ledger.consumer.lag` (per topic, from the consumers' own metrics)
  and `ledger.dlt.records` (per topic).
- Resilience4j meters bound with `resilience4j-micrometer`; Hikari, Tomcat and
  JVM come from Boot.
- Business counters fire after commit: from the booking's caller (after the
  idempotent booking returns), from a transactional after-commit listener for
  margin calls, from the notifier after the guard returns.

**Tests**
- `MetricsIT`: open a position, apply a price that raises a call, quote with the
  breaker open; the scrape shows the counters at the expected values, the
  gauges present, and no tag named `counterparty`, `position` or `isin`.
- `RolledBackOpenCountsNothingTest`: a booking that fails after the append
  leaves `ledger.positions.opened` unchanged.

**Acceptance**
- Scrape text of the run is the evidence; cardinality assertion passes.

## [ ] 8 · Tracing in process

**Change**
- `bootstrap` POM: `spring-boot-starter-opentelemetry` (or the Micrometer
  tracing OpenTelemetry bridge and OTLP exporter), OTel SDK testing in test
  scope.
- `application.observability.Tracing` and the bootstrap implementation.
  `TraceIdFilter` takes the server span's trace id when present, ordered after
  the observation filter; otherwise generates one as before.
- `management.tracing.sampling.probability`: 1.0 default, `prod` lower;
  `management.otlp.tracing.endpoint` from the environment.
- The existing `[traceId]` log pattern keeps working.

**Tests**
- `HttpTraceIT`: a request gets a span; `X-Trace-Id`, the MDC and the span's
  trace id are the same value; an incoming `traceparent` is continued, an
  incoming `X-Trace-Id` is still ignored.

**Acceptance**
- Existing web tests pass unchanged; trace id on the response equals the
  exported span's.

## [ ] 9 · Trace across the outbox and Kafka

**Change**
- V16: `ALTER TABLE outbox_event ADD COLUMN traceparent text`.
- `OutboxMessage` gains a nullable `traceparent` (the six-argument constructor
  stays); `StoredOutbox` stamps it from `Tracing`; `JdbcOutboxStore` writes and
  reads it; `KafkaEventSender` sets the `traceparent` header.
- `PublishPriceUpdate` stamps the current traceparent too.
- Listeners read the header and run in a consumer span via `Tracing.inSpan`;
  the outbox written by the consumer inherits that span.

**Tests**
- `TraceAcrossKafkaIT`: (a) an HTTP open: the same trace id on the request's
  span, the outbox row and the Kafka record, which the relay thread sent;
  (b) a price update published inside a span: the price consumer's span, the
  margin-call outbox row, and the margin-call consumer's span share one trace
  id and each consumer span's parent is the previous hop.
- `reproducesTraceBrokenAtOutbox`: with stamping removed the relay's send starts
  a new trace.

**Acceptance**
- Span tree printed as evidence; `ArchitectureIT` still green.

## [ ] 10 · Structured logs and MDC across threads

**Change**
- `application-prod.yml`: `logging.structured.format.console=logstash`.
- `MdcPropagation` helper (one implementation) used by `MarketDataPool` and a
  `MdcTaskDecorator` applied to the Spring-created executors and schedulers.

**Tests**
- `StructuredLogIT`: with the prod format every captured line is a JSON object
  with `traceId` after a request.
- `reproducesMdcLostOnPooledThread`: a `ThreadPoolTaskExecutor` with no
  decorator logs with no trace id; with the decorator it carries the
  submitter's, and the worker's own MDC is restored after the task.

**Acceptance**
- Both log lines in the evidence.

## [ ] 11 · Log hygiene

**Change**
- `LoggingNoticeChannel` logs ids and currency, never name or amount. Any
  other site found by the test is fixed the same way.
- The redaction rule is written into `docs/specs` (this spec), the decision
  record and a short `docs/logging.md`.

**Tests**
- `LogHygieneIT`: runs open, return, price update with a margin call, accrual
  and quote (live and stale) with a counterparty name and amounts that appear
  nowhere else, and fails if any occurs in the captured output at DEBUG for
  `com.jerome`.

**Acceptance**
- Green; mutation check: putting the amount back into the notice log makes it
  fail.

## [ ] 12 · Infra: Prometheus, Grafana, Jaeger, alerts

**Change**
- `infra/compose.yaml`: `prometheus`, `grafana` (provisioned data source and one
  dashboard), `jaeger` (OTLP). `infra/prometheus/prometheus.yml`,
  `alerts.yml`, `alerts_test.yml`, `check.sh`.
- Dashboard JSON: quotes by outcome, breaker state, bulkhead/limiter rejects,
  pools, outbox age, consumer lag, accrual last success, JVM.
- Three alerts: `OutboxEventsNotReachingConsumers`, `AccrualNotBooked`,
  `QuotesUnusable`; each with a `summary` that states the outcome and a
  `runbook` line.

**Tests**
- `check.sh` runs `promtool check rules` and `promtool test rules` in Docker:
  each alert fires on its fixture and stays silent on a healthy series.
- Manual run: the application against the compose stack, scrape visible in
  Prometheus targets, a trace in Jaeger; transcript in evidence.

**Acceptance**
- `check.sh` output in evidence; dashboard JSON parses and every query names a
  meter that exists in section 7's scrape.

## [ ] 13 · Decision record and close

**Change**
- `docs/decisions/12-resilience-observability.md` (chose / rejected / observed /
  100x), `ROADMAP.md` ticks, plan notes, README pointer.

**Acceptance**
- Every number in the record is traced to an evidence file; nothing estimated.

---

## Found while implementing

**Section 1.**
- Boot's `/actuator/health` aggregate includes Redis as soon as `spring-data-redis` is on the classpath,
  whether or not `ledger.cache.enabled` is set. A context with no Redis answers 503 on the aggregate. The
  aggregate is therefore not usable as a probe; section 2 defines the probe groups and the exposure test
  reads the liveness probe.


**Section 2.**
- Boot already creates the `liveness` and `readiness` groups once probes are enabled; what the section
  decides is their membership. `readiness` includes `db`, and nothing else outside the process.
- The aggregate `/actuator/health` stays DOWN in any environment without Redis (the indicator is
  registered whether or not the cache is on). The test asserts that on purpose: it is the demonstration of
  why the aggregate is not what an orchestrator should poll.
- The data source is made to refuse connections by a `BeanPostProcessor` that wraps it in a JDK proxy,
  not by stopping the shared Postgres container other tests use.

**Sections 3 and 4** (one commit: the decorator composes the three and the tests for the second pair run
through the same class).
- Order outside in is breaker, **bulkhead**, rate limiter, call; the spec said rate limiter before bulkhead.
  With both refusing at once (wait zero), putting the bulkhead outside means a call it refuses has not
  spent a token, so the limiter counts calls that really reached the source. Resilience4j's own
  documentation orders them the other way; its reason (the limiter as the outermost cheap check) does not
  hold when neither waits.
- The stage 7/8 reproductions (`ThreadExhaustionIT`, `CallerRunsIT`, `MarketDataPoolExhaustionIT`) now start
  with `ledger.resilience.marketdata.enabled=false`. That switch is an operator's kill switch, not a broken
  path: the product default is protected.
- Measured on this machine, the healthy stub answers in 42.9 ms (p50), 45.3 ms (p99) and 62.8 ms (max)
  through the whole client stack, 2,000 calls after 200 of warm-up
  (`docs/evidence/12-market-data-latency.txt`). The floor is the JDK client and server's 40 ms
  delayed-acknowledgement interaction on loopback, not the ledger's code, which is why the record treats
  the number as a floor and not as a model of a real source.
- The starvation reproduction: 8 Tomcat workers, a source that answers after 3,000 ms, 12 concurrent
  `/quote` calls. `GET /loans` took 2,968 ms without the bulkhead and 22 ms with a bulkhead of 4, which
  also answered 8 of the 12 quote calls with 503 at once (`docs/evidence/12-bulkhead-starvation.txt`).
- `MarketDataUnavailableException` is HTTP 504 and `OverloadedException` is 503 (stage 7 and 8's mapping,
  unchanged). The spec's "the stage 7 503" for a quote with nothing to fall back to is a 504.

**Section 5.**
- The fallback applies to every failure of the source except "unknown security": unavailable, refused
  answer (a 5xx or an unreadable body) and our own overload refusal. The spec named unavailable and
  overloaded only; a 500 from the source is the same situation to a screen, and without it the first
  failures before the breaker opens would be errors and the later ones stale, which no client could explain.
  A price in the wrong currency is still refused, and is never degraded.
- Fresh responses are unchanged byte for byte: `stale` and `asOf` are omitted when the price is live. A
  client has to read the flag to know it is looking at an old price, and the existing contract tests and
  clients are not touched.
- `QuotePrice.quote` returns a `Quote`; `QuoteOutcome.Priced` gained `stale` and `asOf` with the old
  two-argument constructor kept for a live price. `LedgerMetrics` (a port with empty defaults) is
  introduced here because the degradation is the first thing to count; section 7 adds its other methods.

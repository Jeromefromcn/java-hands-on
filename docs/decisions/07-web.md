# Stage 07 — Web Layer — Decision Record

Spec: [`docs/specs/07-web.md`](../specs/07-web.md)
Plan: [`docs/plans/07-web.md`](../plans/07-web.md)

Measured on Spring Boot 4.1.1 / Spring Framework 7.0.9, Tomcat 11.0.24,
Jackson 3.1.5 (and Jackson 2.21 inside springdoc), springdoc 3.1.1,
swagger-request-validator 3.0.0, JDK 21.0.12, PostgreSQL 17.11 in
Testcontainers 2.0.5, on a 4-core host. Every number below is copied from a
`docs/evidence/07-*.txt` file. Where a claim is reasoned rather than measured,
it says so.

## What I chose

### The three settled decisions

**1. A return is `POST /loans/{id}/returns`, not `PATCH /loans/{id}`.** A
return is a delta ("return 400") with its own idempotency key and its own
trade. It is appended to the position; it does not edit the position. A
`PATCH` carrying a new outstanding quantity would be a state overwrite, and an
overwrite from a client needs the version the client read (`If-Match`). Stage 6
named exactly that as the condition that moves `@Version` into `domain`. This
choice leaves it untriggered: `domain` still carries no version, and every
write is still a delta the aggregate re-validates. The alternative, `PATCH`
with a merge-patch body of `{"return": 400}`, would be a delta disguised as a
patch, which is not what merge-patch means. The return answers `201` with no
`Location`, because a trade has no resource of its own.

**2. The booked trade is the idempotency record.** The key is claimed by the
business write itself: the trade insert, in the use case's transaction,
against the unique constraint stage 6 already had. There is no separate
request/response table, so a claim and a booking cannot commit apart. A
duplicate rolls its own transaction back; `IdempotentBooking`, which is
deliberately not transactional (the shape of stage 6's retry loop), then reads
the committed trade in a new transaction and rebuilds the original answer from
it. The answer is built only from the trade row, never from the position's
current state, so a replay after later returns is still byte-identical.
`LoanWriteIT.replayIsIdentical` pins the bytes; `PositionUseCasesIT
.aReplayAfterAReturnStillAnswersTheOriginalOpen` pins the original quantity.
A request that failed stored nothing, so its retry runs again, and an open
refused for credit succeeds on retry once the limit is raised. Redis was
rejected: it is not in the stack until stage 9, and a key claimed in Redis
commits separately from the Postgres write it guards.

**3. Same key, different request, is `422`.** V4 adds
`loan_trade.request_fingerprint`, a SHA-256 of the command's canonical form:
the kind (`OPEN`/`RETURN`), then the fields in fixed order, money as `Money`
holds it (so `12.5` and `12.50` HKD are one request), rates at their own scale.
It is computed in `application` from the command, not from the body text:
`LoanWriteIT` replays with a different field order and `50000.0` for
`50000.00`, and gets the replay. Without it, a client that reused a key by
mistake would be told its new request succeeded. A mutation that skipped the
comparison turned 2 tests red under each lock strategy. Trades booked before V4
have no fingerprint and match nothing: a replay against one is refused rather
than guessed at.

### Concurrent arrival, and what it costs

Postgres decides. The second insert of the same key blocks on the unique index
until the first transaction ends
([`07-idempotency-race.txt`](../evidence/07-idempotency-race.txt), 10
repetitions each, all green):

| First request | Second request | Rows |
|---|---|---|
| commits | `201`, `Idempotent-Replayed: true`, body identical | 1 position, 1 trade |
| rolls back (`500`) | `201`, not replayed: its insert proceeds | 1 position, 1 trade |

The test proves the wait is real, not lucky ordering: while the first is held,
`pg_stat_activity` shows the second as `wait_event_type=Lock,
wait_event=transactionid` on `insert into loan_trade`. It was seen blocked 29 ms
after being sent in the commit case, 19 ms in the rollback case.

No request ever sees "in progress", so the IETF draft's `409` is never
produced. The cost is the other side of that: for as long as the first
transaction runs, the second holds a Tomcat worker and a pooled connection and
does nothing. In the manual run, every replay also left two Hibernate `WARN`
lines (SQLState `23505`) under its trace id
([`07-manual-run.txt`](../evidence/07-manual-run.txt)): a replay is a failed
insert plus a rolled-back transaction, not a lookup.

### Decisions made while planning and implementing

1. **New reads are `JdbcClient` only**: keyset listing, position detail and
   accrual entries. They are projections with no entity to manage, the case
   stage 6 gave for bypassing the ORM.
2. **Accrual entries are not paged; the range is capped at 366 days.** One
   entry per position per day, so the cap bounds the answer. A reversed or
   longer range is `400 invalid-query`; an unknown position is `404`, not an
   empty list.
3. **`@RateRange` reads a `RateBounds` bean** that `bootstrap` builds from the
   existing `accrual.*` properties, so the binder and the validator cannot
   disagree. `RateRangeValidatorTest` swaps the bean and the bounds follow.
4. **`UnknownReferenceException` says subject or reference.** The plan mapped
   by `kind()`; the quote endpoint broke that, because its security is in the
   URL (`404`) while an open's security is in the body (`422`).
5. **Problem types are `urn:sl-ledger:problem:<kind>`**: a stable name that
   does not point at a host this project does not own.
6. **`502` for an upstream answer the ledger refuses, `504` for no answer.**
   The spec had only `504`.
7. **The thread dump shows a future, not a socket read** (see exercise 8).
8. **The outbound client lives in `adapter-web.client`**, not a seventh
   module. `ArchitectureIT` forbids the inbound side from depending on it.
9. **`-parameters` is on.** `spring-boot-starter-parent` sets it; this project
   imports the BOM instead, so every unnamed `@PathVariable` failed at request
   time with a `500`. Found in section 5; a `clean` build was needed before the
   flag took effect.

## The exercises

### 1 · The five use cases as resources

| Method and path | Use case |
|---|---|
| `POST /loans` | open |
| `POST /loans/{id}/returns` | return |
| `GET /loans?page=&size=` / `GET /loans?after=&size=` | list |
| `GET /loans/{id}`, `GET /loans/{id}/accruals?from=&to=` | query |
| `GET /securities/{isin}/quote` | live price, the outbound call |

Daily accrual (stage 11) and revaluation (stage 10) are designed, not built;
the roadmap's first rule forbids depending on later stages. Both are runs, not
resources that a client edits:

- `POST /accrual-runs` with `{"businessDate": "2026-09-30"}` and an
  `Idempotency-Key`, answering `202 Accepted` with `Location:
  /accrual-runs/{id}`. The run's identity is its business date (stage 11's
  `JobParameters`), so a second `POST` for the same date is a replay of the
  first run, not a second run. `GET /accrual-runs/{id}` reports its state.
- `POST /revaluations` the same way, with `202` and a run resource; its margin
  calls come out as events (stage 10), readable as `GET
  /revaluations/{id}/margin-calls`.

`202` and not `201`: the work is not done when the request is answered, and a
batch that runs for minutes cannot hold a request thread. This is reasoned, not
measured.

### 2 · Idempotency keys

See the three decisions above. A key is 1 to 255 printable ASCII characters
with no spaces (`@IdempotencyKeyHeader`), because it is stored and logged. A
missing key is `400 missing-header` and books nothing.

### 3 · Bean Validation

- **ISIN check digit at the boundary, not in `domain`.** Stage 2 kept `domain`
  to the shape, so fixtures need no valid digit. Input from outside is held to
  more: a transposed digit in a client request is exactly what the check digit
  catches, and the boundary is the last place it can still be refused.
  `IsinValidatorTest` pins hand-worked vectors (`HK0000000007`,
  `US0000000010`, …) and shows `HK0000000001`, stage 6's fixture, accepted by
  `domain` and refused by `@ValidIsin`.
- **Separate request types, not validation groups.** Open and return differ
  in almost every field, so one type with two groups would carry fields that
  are forbidden half the time, and a new rule would have to remember which
  group it belongs to. `ValidationGroupsTest` exercises groups on a test-only
  type and pins the trap that decided it: a type whose every constraint names
  a group validates *nothing* under `@Valid`, which checks `Default`.
- **Two constraints the roadmap did not name.** `@NotNegative` on collateral,
  because `domain` refuses a negative one with an `IllegalArgumentException`,
  which would surface as a `500`; and the key format above.
- **A constrained header switches the whole call to method validation.** Once
  `@IdempotencyKeyHeader` was on a parameter, Spring 7 validated the `@Valid`
  body through method validation too, and the first run reported every body
  error under the parameter name `request`. The handler unpacks
  `ParameterErrors` into field paths (`rule.rate`).

### 4 · Errors as `ProblemDetail`

| Cause | Status | Why this code |
|---|---|---|
| malformed JSON, invalid field, missing header | `400` | the request cannot be read as meant |
| the resource in the URL does not exist | `404` | it is about the URL |
| a body names a counterparty or security that does not exist | `422` | the URL exists; the content is unprocessable |
| credit, excessive return, currency mismatch, other domain rules | `422` | well-formed, refused by a business rule |
| `CLOSED` position, lost optimistic race after retries, lock timeout | `409` | valid, but lost to the resource's state or a concurrent writer; retrying may succeed |
| key reused for a different request | `422` | the content contradicts what the key already means |
| upstream answered badly / did not answer | `502` / `504` | the gateway's fault, not the client's |
| anything else | `500` | |

A `500` says nothing about its cause
([`07-problem-500.txt`](../evidence/07-problem-500.txt)): an exception with
`SECRET-DETAIL connection to db-internal-7 refused` produced a body with none
of that, no class name, no frame, and the trace id the log line carries. The
trace id is added to every problem by one `ResponseBodyAdvice`, so no handler
can forget it. Framework errors (`405`, `415`, unknown path) get a kind from
their status.

The manual run found the leak no test had looked for: a malformed body's
`path` was Jackson's `getPathReference()`, which names the Java type at each
step, `com.jerome.slledger.adapter.web.loan.OpenLoanRequest["collateral"]`. It
is now built from field names (`collateral`), and a test asserts no
`com.jerome` appears.

### 5 · Jackson: precision lost in transit

[`07-json-precision.txt`](../evidence/07-json-precision.txt). `9999999999999999.99
HKD` written by Jackson's default serializer is the JSON number
`9999999999999999.99`. Read back as a double, which is what a JavaScript
`JSON.parse` does, it is `1.0E16`: **0.01 HKD is gone before this service runs
any code.** A real Node v20.20.2 reads it as `10000000000000000` and, on
re-serialization, sends that back. The default also wrote `"negative": false`,
from `Money.isNegative()`: a derived value leaking into the contract.

The fix is `LedgerJacksonModule`:

- `Money` is `{"amount": "<plain string>", "currency": "<ISO code>"}`, exactly
  two fields.
- On input, a number amount is refused; so are `1e3`, `12,50`, `" 1"`, `+1`,
  `.5`; so is anything `Money` would round (`12.505 HKD`, `1.5 JPY`, `1.0005
  BHD`). Precision is judged by value, as stage 6's column reads are: `12.500
  HKD` loses nothing and is accepted. A mutation that accepted numbers and
  skipped the precision check turned 5 cases red.
- Rates are strings at their own scale; dates are ISO `yyyy-MM-dd`.

The same module reads the market data source's answers, so a price from
outside is held to the same rules as a request body.

### 6 · Offset against keyset

[`07-pagination.txt`](../evidence/07-pagination.txt), 500,000 positions, page
size 20, median of 20 requests over HTTP after 5 warm-ups:

| Offset | Offset page (ms) | Keyset slice (ms) |
|---|---|---|
| 0 | 36.30 | 6.17 |
| 250,000 | 83.70 | 5.44 |
| 499,980 | 131.57 | 4.50 |

Two costs, not one. The offset query walks the primary-key index from the start
and discards every row before the page: at 499,980, `EXPLAIN ANALYZE` shows
`rows=500000` read for 20 returned, 142 ms and 8,585 buffers. The keyset query
seeks (`Index Cond: id > 'LP-499980'`): 0.046 ms, 4 buffers. And the offset
response carries a total, whose `count(*)` is a parallel sequential scan of
every row, 37.8 ms under `EXPLAIN ANALYZE`, about the whole of the offset-0
median. Offset is slow at page 0 already.

Correctness decides it as much as speed. With one row inserted ahead of the
boundary between two offset requests, page 1 starts with the row that ended
page 0 (`reproducesOffsetPageDriftUnderInsert`); the keyset walk returned each
of the 30 original rows exactly once. Offset stays because a total and "jump to
page 7" are what a screen asks for; anything that walks every row uses the
cursor.

### 7 · Filter, interceptor, aspect, and where the trace id is born

[`07-layer-order.txt`](../evidence/07-layer-order.txt), on a real Tomcat:

| Request | Filter | Interceptor | Aspect |
|---|---|---|---|
| success | in, out | `preHandle`, `postHandle`, `afterCompletion` | in, out |
| handler throws | in, out (`500`) | `preHandle`, `afterCompletion(ex = null)` | sees the exception |
| invalid body (`400`) | in, out | `preHandle`, `afterCompletion` | never runs |
| wrong method / type (`405`/`415`) | in, out | never runs | never runs |
| unknown path (`404`) | in, out | runs: the handler is the static resource handler | never runs |

What each can see: the filter, the raw request and every request; the
interceptor, the chosen handler, but not the arguments, and not the exception
once `@RestControllerAdvice` has resolved it; the aspect, the method's
arguments, result and exception, but only for requests that reached the
controller method. On Tomcat, the `@ResponseBody` is written and committed
before `postHandle` runs, so `postHandle` cannot add a header. Under MockMvc the
response was not committed at that point; a MockMvc-only test would have taught
the opposite, which is why this one runs a server.

The trace id is generated in the filter, at the highest precedence, because the
filter is the only layer that sees `405`s and `415`s. It goes into the MDC
(printed by `logging.pattern.correlation`), onto the response as `X-Trace-Id`,
and into every problem body, and it is removed in `finally`, because Tomcat
reuses the thread. It has the W3C trace-id shape so stage 12 can adopt it. An
incoming `X-Trace-Id` is ignored: a client-chosen string in the logs is an
injection path.

### 8 · `RestClient`, timeouts, and a server taken down by its downstream

[`07-thread-exhaustion.txt`](../evidence/07-thread-exhaustion.txt), Tomcat at
4 workers:

- **No read timeout.** Four quote requests hang at the source. `GET /loans`,
  which never touches the source, timed out at the client after 2002 ms: no
  worker was free to read it. The dump shows 4 of 4 workers `WAITING` in
  `CompletableFuture.get` under `JdkClientHttpRequest.executeInternal`, inside
  `RestMarketPriceSource.priceOf`. `findDeadlockedThreads()` returned null.
  Those are the lines that say "pool full" and not "deadlock": every worker
  waits on the same outside thing, none is `BLOCKED` on a monitor another
  holds, and the count equals the pool size.
- **Read timeout 500 ms.** The same four got `504` by 729–732 ms, and the
  `GET /loans` sent alongside them got `200` in 684 ms.
- **Connect timeout.** Against `10.255.255.1` with connect 500 ms and read 5 s,
  the call failed after 501 ms: the connect timeout, surfaced by Spring as
  `HttpTimeoutException`. This machine drops the SYN rather than refusing it.
  With read and connect both at 500 ms the two cannot be told apart, because
  the JDK client's request timeout covers the connect too.

The frame is a future, not a socket read, as planned: Spring calls
`HttpClient.sendAsync` and waits, and the socket is read by the client's
selector thread. The spec's wording is corrected here.

`QuotePrice` is deliberately not transactional. The security is read first, in
the directory's own short read, and the remote call runs with no transaction
open. Wrapped in one, the hang above would have held four database connections
too. That consequence is reasoned, not measured.

Defaults are now `connect-timeout: 1s`, `read-timeout: 2s`. Nothing is retried
and nothing is cached; the circuit breaker, the bulkhead and the stale-price
fallback are stage 12's.

### 9 · OpenAPI and the contract test

[`07-openapi-money.txt`](../evidence/07-openapi-money.txt). springdoc
described `Money` by introspecting it with Jackson 2, never seeing
`LedgerJacksonModule`: `amount` was a `number`, `currency` was
`java.util.Currency`'s bean properties (`displayName`, `symbol`, …), and
`negative` was a field. A client generated from that contract would parse
amounts as doubles, the loss in exercise 5. The POSTs were documented as `200`,
and no error response at all.

An `OpenApiCustomizer` restates the money invariant for the contract (`amount`
a string with a decimal pattern, `currency` three letters, both required, no
other fields), makes `Rate` a string, and gives every operation a `default`
response of `application/problem+json` with `type`, `title`, `status` and
`traceId` required. `OpenApiContractIT` validates 18 real exchanges, errors
included, against the served document
([`07-openapi-contract.txt`](../evidence/07-openapi-contract.txt)). Dropping the
`Money` replacement failed the first exchange with "string found, number
expected" and "string found, object expected".

## The gate and the measurements

`./mvnw clean verify` went from 1 min 49 s at the start of the stage to 2 min
39 s, with 142 integration tests in `bootstrap`. `DeepPageIT` is tagged
`measurement` and runs only under `-Pmeasure`; its 500,000-row fixture took
20.8 s to insert.

## What I rejected, and why

- **`PATCH /loans/{id}` for returns**: an overwrite that would need
  `If-Match` and a version in `domain` (decision 1).
- **A request/response store for idempotency**, in Postgres or Redis: a claim
  that commits apart from the booking (decision 2).
- **Returning `409` for an in-flight duplicate**: Postgres already serialises
  the two; a visible in-progress state would add a status table for nothing.
- **Validation groups in product code**: see exercise 3.
- **Accepting JSON number amounts "leniently"**: by the time one arrives, the
  client may already have rounded it.
- **Clamping an over-large page size**: a silent change of what was asked for.
  It is a `400`.
- **Trusting an incoming trace id**: an injection path, and there is no
  upstream to trust until stage 12.
- **A seventh module for one client class**: the module list is fixed in
  `CLAUDE.md`, and an ArchUnit rule keeps the client apart instead.
- **MockMvc for the layer-order exercise**: it does not commit responses the
  way a server does.

## What I observed

- A JSON number loses 0.01 HKD of `9999999999999999.99` before this service
  sees it, and a browser's round trip sends the rounded value back.
- Jackson's default wrote `"negative": false`; springdoc advertised
  `Currency`'s bean properties. Both describe the Java type, not the wire.
- A constrained request header moved body validation into method validation
  and changed which exception carried the errors.
- The error path of a malformed body leaked a Java class name; only the manual
  run caught it.
- Offset paging costs 36 ms at page 0 on 500,000 rows because of its count,
  and repeats a row when one is inserted between pages.
- An unknown path has a handler, so interceptors see it.
- Four hanging downstream calls took a four-worker server down for requests
  that never touch the downstream; a 500 ms read timeout kept it serving.
- Boot 4 split `RestClient.Builder` into `spring-boot-starter-restclient`, and
  without `-parameters` every unnamed path variable was a runtime `500`.
- `bootstrap`'s stage 4 test-scoped `spring-web` would have taken Spring MVC
  off the runtime classpath by nearest-wins mediation, the trap stage 6 hit
  with JDBC ([`07-dependency-convergence.txt`](../evidence/07-dependency-convergence.txt)).

## The money invariant, at every new component

| Component | How money crosses it | Checked by |
|---|---|---|
| request bodies | string amount + ISO currency, refused if a number or over-precise | `LedgerJacksonModuleTest`, `MoneyOverHttpIT` |
| responses | the same two-field object | `LoanReadIT`, `MoneyOverHttpIT` |
| HTTP → JPA → Postgres → HTTP | `HKD` `9999999999999999.99`, `JPY` `9999999999999999`, `BHD` `9999999999999999.999` round-trip exactly; the column is `numeric` | `MoneyOverHttpIT` |
| the market data source | read through the same module; wrong currency, over-precise, or numeric price is `502` | `QuoteIT` |
| request fingerprints | `Money`'s normalised amount plus currency code | `RequestFingerprintTest` |
| the OpenAPI contract | `amount` a string, `currency` a code, both required | `OpenApiContractIT` |

## Known gaps

- Accrual and revaluation endpoints are designed, not built (stages 11, 10).
- No endpoint creates or edits counterparties and securities; the `local`
  profile seeds synthetic ones.
- Errors raised inside a filter before `DispatcherServlet` go to Boot's
  `/error` page, not through `LedgerProblemHandler`. None of this stage's
  filters raise one.
- The trace id is local to this service until stage 12 propagates it.

## Where it breaks at 100x

- **Idempotency by unique constraint.** Each replay is a failed insert, a
  rolled-back transaction and two `WARN` lines. At 100x retry traffic (a client
  retrying aggressively after timeouts), that is 100x wasted transactions and
  log volume; a read of the key before the business transaction would answer
  most replays without a write. Concurrent duplicates each hold a worker and a
  connection for the length of the first transaction, so a burst of retries on
  slow opens drains the pool the way exercise 8's hang drained the workers.
- **`loan_trade` as the key store.** Keys never expire. At 100x trades the
  unique index grows without bound, and every booking pays for it. A retention
  rule (keys answer replays for N days, then only the trade remains) would
  need the fingerprint in a table that can be pruned.
- **Offset paging.** Measured at 500,000 rows: 131.57 ms at the deep end and a
  37.8 ms count on every page. Both scan in proportion to the rows, so at 100x
  rows they are expected to reach seconds (reasoned, not measured). The total
  has to become an estimate or go, and deep offsets have to be refused in
  favour of the cursor.
- **The accrual range cap.** 366 entries per response is fine per position. A
  fee report across all positions is not this endpoint's job; at 100x
  positions it belongs to a batch export.
- **Synchronous quotes.** Every quote holds a Tomcat worker for as long as the
  source takes. The read timeout bounds one call, not the pool: at 100x quote
  traffic, a source slowing to just under 2 s occupies workers in proportion to
  its latency and rate, and the server starves exactly as measured with 4
  workers. That is stage 12's bulkhead and circuit breaker, and stage 8's
  executor.

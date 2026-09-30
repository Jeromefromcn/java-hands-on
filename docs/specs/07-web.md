# Stage 07 — Web Layer

## Problem

Stage 6 left the ledger reachable only from tests. The open, return and
list use cases exist in `application` and run against Postgres, but
`adapter-web` is an empty module and `SlLedgerApplication` serves nothing.
Stage 7 puts HTTP in front of the use cases. It also puts the first
outbound HTTP call behind a port: the external market data source that
stages 8, 9 and 12 build on.

HTTP is a new boundary, and the money invariant has to hold across it in
both directions. A JSON number is an IEEE double to most clients. An
amount that crosses as a number can lose digits before this service ever
sees it. The stage makes that loss happen once, then closes it.

Each exercise the roadmap lists is driven to an asserted result against a
running server: idempotent retries under concurrency, validation,
error mapping, JSON precision, deep pagination, the order of the
request-handling layers, and an outbound call with no timeout exhausting
the server's threads.

Four facts shape the stage.

- **Two of the five roadmap use cases do not exist yet.** Daily accrual is
  stage 11 and revaluation is stage 10. Rule 1 of the roadmap forbids
  depending on later stages. Stage 7 exposes open, return and query. The
  accrual and revaluation resources are designed in the decision record,
  not implemented.
- **Idempotency already has a unique constraint.** Stage 6 put one on
  `loan_trade.idempotency_key` and translates its violation into
  `DuplicateRequestException`. A repeated request is refused today, not
  answered. Stage 7 turns the refusal into a replay of the original result.
- **`domain` validates an ISIN's shape, not its check digit.** Stage 2
  chose that so synthetic fixtures need no valid mod-10 digit. External
  input is a different trust level. The check digit is verified at the
  HTTP boundary only.
- **`RealReferenceRates` throws "not implemented until stage 7".** No
  persisted accrual rule consumes a reference rate, because `FloatingRate`
  is not persisted (stage 6). The source that stages 8, 9 and 12 need is a
  *price* source. This stage builds that one, and corrects the
  `RealReferenceRates` message to say the replacement is unscheduled.

## Settled design decisions

These were decided before this spec was written. The decision record
argues each one; the spec states what they commit the code to.

1. **A return is `POST /loans/{id}/returns`, not `PATCH /loans/{id}`.**
   - A return is a delta with its own idempotency key and its own trade.
     It is an event appended to the position, not an edit of the
     position's state.
   - `PATCH` with a new outstanding quantity is a state overwrite. It
     would need a cross-request version (`If-Match`). Stage 6 named that
     as the condition that moves `@Version` into `domain`. This choice
     keeps that condition untriggered.
   - A `PATCH` carrying "return 10" would be a delta disguised as a merge
     patch, which is not what `PATCH` media types mean.
2. **The booked trade is the idempotency record.** There is no separate
   request/response store.
   - The key is claimed by the business write itself: the trade insert,
     in the use case's transaction. A claim and a business write that
     commit separately can disagree. These cannot.
   - A duplicate rolls its own transaction back. A non-transactional
     wrapper in `application`, the same shape as stage 6's retry loop,
     then reads the committed trade and rebuilds the original result
     from it.
   - A response is therefore built only from what the trade row holds,
     never from the position's live state. A replay then returns the same
     body even after later returns changed the position.
   - A request that *failed* stored nothing, so its retry runs again. That
     is intended: a refused open that is retried after the credit limit
     was raised should succeed.
   - Redis is rejected here. It is not in the stack until stage 9, and a
     key claimed in Redis commits separately from the Postgres write it
     guards.
3. **Same key, different request, is refused.** Each trade stores a
   fingerprint of the command that produced it (V4 adds the column). A
   replay whose fingerprint differs is refused with `422`. Without it, a
   client that reused a key by mistake would be told its new request
   succeeded. The fingerprint is computed in `application` from the
   command, so it does not depend on JSON field order or whitespace.

## Scope

### Module topology and dependencies

No child POM carries a `<version>`. The exact artifact ids and versions
(the Boot 4 web starter, springdoc's Boot 4 line, the schema validator)
are confirmed in the plan, not assumed here.

| Module | Gains | New dependencies |
|---|---|---|
| `domain` | nothing | none |
| `application` | idempotent wrappers for open and return; the market price port; position detail and accrual-entry read ports; keyset paging on the listing port | none |
| `adapter-persistence` | V4 (`request_fingerprint`); trade lookup by key; the new read ports; keyset listing | none |
| `adapter-web` | controllers, request/response DTOs, custom constraints, the exception handler, the Jackson money module, the trace-id filter, the market data `RestClient` adapter | Spring MVC, Jackson, Bean Validation, springdoc |
| `bootstrap` | wiring; the servlet container; timeout properties | the Boot web starter |

The outbound `RestClient` adapter lives in `adapter-web`, in its own
`client` package that no controller imports. An ArchUnit rule enforces
that separation. A seventh module was considered and not added, because
the module list is fixed in `CLAUDE.md` and one class does not justify
changing it.

### Endpoints

| Method and path | Use case | Success |
|---|---|---|
| `POST /loans` | open | `201`, `Location: /loans/{id}` |
| `POST /loans/{id}/returns` | return | `201` |
| `GET /loans/{id}` | position detail | `200` |
| `GET /loans?page=&size=` | offset listing, with total | `200` |
| `GET /loans?after=&size=` | keyset listing, no total | `200` |
| `GET /loans/{id}/accruals?from=&to=` | accrual entries of one position | `200` |
| `GET /securities/{isin}/quote` | live price from the market data source | `200` |

`POST` endpoints require an `Idempotency-Key` header. A replayed response
carries the original status and body, plus `Idempotent-Replayed: true`.
Accrual entries are only read here. Stage 11 writes them, and test
fixtures fill them until then.

### Idempotency

The status codes follow the IETF `Idempotency-Key` header draft:

- missing key: `400`;
- same key, same fingerprint: the original response, replayed;
- same key, different fingerprint: `422`;
- same key while the first request is still running: see below.

Postgres decides the concurrent case. The second insert of the same key
blocks on the unique index until the first transaction ends. If the first
commits, the second gets the unique violation and replays. If the first
rolls back, the second insert proceeds and the second request runs as a
first. No request sees an in-progress state, so the draft's `409` for "in
progress" is never produced. The decision record states the cost: a
waiting request holds a connection for the length of the first
transaction.

Both outcomes are tested with latches, with two requests on the same key
arriving together:

- first commits: both responses are identical, and there is one position
  and one trade;
- first rolls back: the second succeeds, and there is still one position.

### Bean Validation

- Request DTOs are records validated with `@Valid`.
- `@Isin` verifies shape and the mod-10 check digit. Its test vectors are
  synthetic ISINs, with check digits computed by the standard algorithm
  and one digit off for each refusal case.
- `@RateRange` bounds a rate. Its bounds come from the existing
  `accrual.rate-lower-bound` / `rate-upper-bound` properties, not from
  annotation literals, so one configuration governs both the binder and
  the validator.
- Open and return have separate DTOs, because their fields differ. The
  groups exercise uses one test-only DTO validated under two groups, and
  asserts that each group accepts what the other refuses. The decision
  record says why product code uses separate types instead.
- A validation failure is a `400` `ProblemDetail` listing each field and
  its message.

### Errors

Every error body is RFC 9457 `application/problem+json`, with a stable
`type` URI per error kind.

| Cause | Status |
|---|---|
| validation, malformed JSON, `MalformedIdentifierException` | `400` |
| position in the path not found | `404` |
| counterparty or security in the body not found | `422` |
| `InsufficientCreditException`, `ExcessiveReturnException` | `422` |
| `IllegalTransitionException` | `409` |
| optimistic conflict after every retry attempt | `409` |
| idempotency fingerprint mismatch | `422` |
| market data source timed out or unreachable | `504` |
| anything else | `500` |

A `500` body carries the request's trace id and a generic detail. A test
throws an unexpected exception carrying a recognisable message and
asserts that neither the message, nor any class name, nor any stack frame
reaches the body.

### The money invariant at the HTTP boundary

- Money is `{"amount": "1234.50", "currency": "HKD"}`. The amount is a JSON
  string, at the currency's scale.
- On input, an amount sent as a JSON number is refused with `400`. By the
  time it arrives, a client may already have rounded it.
- An amount with more decimals than its currency allows is refused, not
  rounded. `Money`'s constructor rounds `HALF_UP` silently, so the
  deserializer checks the scale before it constructs. This is stage 6's
  read rule, applied to a new boundary.
- Rates are strings too, unscaled.
- `LocalDate` is ISO `yyyy-MM-dd` in both directions.
- `reproducesPrecisionLossAsJsonNumber` serializes an amount as a number
  and reads it back the way a JavaScript client does, as a double. It
  asserts that the value changed. The fixed serializer is then asserted to
  round-trip the same amount exactly.
- The end-to-end round trip the roadmap asks for at the end of part B is
  tested: open over HTTP, read back over HTTP, with `HKD`, `JPY` and
  `BHD` amounts at 16 integer digits. Amount, scale and currency must
  survive HTTP, JPA, Postgres and the way back.

### Pagination

- The offset listing is stage 6's port, unchanged, with its total.
- The keyset listing orders by position id and takes the last id seen as
  `after`. It returns no total.
- A fixture of 500,000 positions is generated with `generate_series`.
- Two `measurement` tests, run only under `-Pmeasure`, time a shallow and
  a deep page both ways. The numbers go to
  `docs/evidence/07-pagination.txt`.
- The regular suite asserts correctness, not time. With inserts landing
  between page requests, offset paging is shown to skip or repeat a row,
  and keyset paging is shown to do neither.

### Filter, interceptor, aspect

- A servlet `Filter` in `adapter-web` generates a trace id per request,
  puts it in the MDC, and returns it as `X-Trace-Id`. It clears the MDC in
  `finally`. The id is generated in the filter because the filter is the
  only one of the three layers that sees every request, including those
  rejected before a handler is chosen.
- A `HandlerInterceptor` and an `@Aspect` around controller methods are
  **test-only**. Each of the three layers records what it can see, and the
  test asserts the order of entries and exits.
- The decision record draws the order, and states what each layer can and
  cannot see: the raw request, the chosen handler, the method arguments,
  the exception.
- The aspect needs `spring-boot-starter-aspectj`, which stays at test
  scope.

### Market data source and timeouts

- `application` gains a `MarketPriceSource` port: `Money priceOf(Isin)`.
- `adapter-web`'s `RestClient` implementation calls
  `GET {base-url}/prices/{isin}`, with a separate connect timeout and read
  timeout taken from configuration.
- Tests stand the source up with the JDK's `com.sun.net.httpserver`, so
  there is no new dependency. One handler can be told to hang until a
  latch is released.
- `reproducesThreadExhaustionWithoutTimeout`:
  - Tomcat's `max-threads` is set to a small number, and the client has no
    read timeout.
  - That many quote requests hang on the source.
  - A request to an unrelated endpoint (`GET /loans`) then cannot be
    served within a bound. The test asserts that bound is exceeded.
  - A thread dump taken at that moment goes to
    `docs/evidence/07-thread-exhaustion.txt`, with the frames that show
    every worker parked in a socket read.
- With the read timeout set, the same hanging requests fail with `504`
  within the timeout, and the unrelated request is served.
- The connect timeout is exercised against an address that does not
  answer. Whether this machine's network drops or refuses the connection
  decides what the test can see. The evidence records what happened, and
  the test asserts only a bound.
- Nothing is retried and nothing is cached. The circuit breaker and the
  stale-price fallback are stage 12's. This stage only creates the pain
  they remove.

### OpenAPI

- springdoc generates `/v3/api-docs`.
- The money schema must declare `amount` as a string. A test asserts this
  on the generated document, because the invariant should hold in the
  contract as well as on the wire.
- A contract test validates real responses from each endpoint, including
  error responses, against the generated schema.

### Manual run

- A seed migration location, enabled only in the `local` profile, adds
  synthetic counterparties and securities. Without it, the running
  service could not open a position by hand.
- It never runs in tests or in `prod`.
- The market data source for a manual run is a small stub started with
  `infra/`. Its exact shape is settled in the plan.

## Out of scope

- Implementing the daily accrual and revaluation endpoints (stages 11 and
  10). Their resource design is in the decision record.
- Endpoints to create or edit counterparties and securities.
- Redis for idempotency (stage 9).
- Retries, circuit breaking, bulkheads and fallbacks for the market data
  source (stage 12).
- Thread-pool and virtual-thread work on the outbound call (stage 8).
- Authentication and authorization (stage 13).
- Trace propagation to other services and OpenTelemetry (stage 12). The
  trace id here is local to this service.
- `If-Match` and any cross-request version.
- Rate-limiting and request-size limits.

## Constraints

- `domain` does not change.
- `./mvnw verify` at the repository root stays green after every section.
- Controllers call use cases only. They never reach a repository or an
  `adapter-persistence` type. ArchUnit enforces this.
- No controller imports the market data client package.
- Concurrency in tests (idempotency races, thread exhaustion) is
  sequenced with latches or barriers, never with `Thread.sleep`. Every
  blocking scenario has a bound.
- Reproduced failures are asserted by status code, exception type or
  `ProblemDetail.type`, never by message text alone.
- Numbers in the decision record come from evidence files, measured on
  this machine.
- No secret in any committed file. The market data base URL is
  configuration, and the source needs no credentials.
- All data is synthetic, including the seed migration.

## Acceptance criteria

1. `SlLedgerApplication` starts against `infra/` Postgres in the `local`
   profile. A position can be opened, returned, listed and read with
   `curl`, and the transcript is in evidence.
2. `domain` is unchanged and its enforcer rule still passes.
3. `ArchitectureIT` passes, with the two new rules: controllers reach only
   `application`, and no controller imports the client package.
4. Every endpoint in the table above has a test against a running server.
5. Both idempotency races give the outcomes stated above, and a
   fingerprint mismatch gives `422`.
6. `@Isin` refuses a wrong check digit that `domain` accepts, and
   `@RateRange` follows the configured bounds.
7. Each row of the error table has a test. The `500` test finds no leaked
   message, class name or stack frame.
8. Money round-trips over HTTP in `HKD`, `JPY` and `BHD`. A JSON-number
   amount and an over-precise amount are both refused.
   `reproducesPrecisionLossAsJsonNumber` passes.
9. Keyset paging neither skips nor repeats under concurrent inserts, and
   offset paging is shown to do one or the other.
10. The filter, interceptor and aspect order is asserted.
11. `reproducesThreadExhaustionWithoutTimeout` passes, and the timeout
    variant returns `504` and keeps the server responsive.
12. The generated OpenAPI document declares money amounts as strings, and
    the contract test passes.
13. The evidence files listed below exist and hold measured output.
14. `./mvnw verify` at the repository root is green.

### Evidence

`docs/evidence/07-*.txt`, text only, measured on this machine:

- the manual `curl` transcript;
- the idempotency races: both outcomes, with the blocked insert's wait;
- the precision-loss reproduction;
- pagination timings, shallow and deep, offset and keyset;
- the filter / interceptor / aspect order;
- the thread dump under exhaustion, and the timeout variant's timings;
- the connect-timeout observation.

## Done when

The four `CLAUDE.md` deliverables exist: this spec, the plan, the code
with every criterion above verified, and `docs/decisions/07-web.md`. The
decision record must cover:

- the three settled decisions, argued against their alternatives;
- the concurrent-arrival handling, and what it costs in connections;
- the accrual and revaluation resource design, not implemented;
- why the ISIN check digit is verified at the boundary and not in
  `domain`;
- why product code uses separate DTOs instead of validation groups;
- the error mapping, and why each `4xx` is the code it is;
- the JSON precision loss, measured, and the string-amount rule;
- offset against keyset, with both timings and the correctness finding;
- the layer order diagram, and where the trace id is generated;
- the thread exhaustion, from the thread dump;
- where each choice breaks at 100x.

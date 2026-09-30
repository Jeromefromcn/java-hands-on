# Stage 07 — Web Layer · Implementation Plan

Spec: [`docs/specs/07-web.md`](../specs/07-web.md)

One section per commit, in order. Implement a section, tick its checkbox in
the same commit, then stop for review. `./mvnw clean verify` at the
repository root after every section.

## Ground rules for every section

- Packages:
  - `com.jerome.slledger.adapter.web.<area>` for inbound HTTP (`loan`,
    `quote`, `error`, `json`, `trace`, `validation`);
  - `com.jerome.slledger.adapter.web.client` for the outbound market data
    client, and nothing else;
  - `com.jerome.slledger.application.<usecase>` and `application.port` as
    in stage 6.
- No `<version>` in a child POM. Versions confirmed while planning:
  - Spring Boot 4.1.1's BOM supplies Jackson 3.1.5, Tomcat 11.0.24,
    `spring-boot-starter-webmvc`, `spring-boot-starter-webmvc-test` and
    `spring-boot-resttestclient`.
  - springdoc is not in the BOM. `springdoc-openapi-starter-webmvc-api`
    3.1.1 is built against Boot 4.1.0. It goes into the parent's
    `dependencyManagement` behind a `springdoc.version` property. The
    `-api` artifact is used, not `-ui`: the stage needs the document, not
    a browser page.
  - `com.atlassian.oai:swagger-request-validator-core` 3.0.0 validates
    responses against the document, at test scope, also managed in the
    parent.
- `@Transactional` stays only in `application`.
- Tests that start a server or need Docker end in `IT` and run under
  failsafe. `adapter-web`'s slice tests (`@WebMvcTest`) and pure unit tests
  end in `Test`. Slice tests replace the use cases with `@MockitoBean`:
  the use case is the boundary the web adapter talks to, which is the case
  `docs/decisions/test-doubles.md` gives for a mock.
- End-to-end tests live in `bootstrap` and start the real application on
  a random port through `LedgerApp.startWeb(…)`, a variant of stage 6's
  `LedgerApp.start` with `WebApplicationType.SERVLET` and
  `--server.port=0`. They call it with Spring's `RestTestClient` and read
  bodies as `String` whenever the assertion is about the JSON text itself.
- ISINs sent over HTTP carry a valid check digit, computed by a test
  helper. Stage 6's fixtures (`HK0000000001` and so on) are unchanged:
  `domain` never checked the digit, and those rows are loaded by SQL, not
  over HTTP.
- Every reproduced failure asserts a status code, an exception type or a
  `ProblemDetail.type`, never message text alone. A test asserting wrong
  behaviour is named `reproduces<Failure>` and carries
  `// See docs/decisions/07-web.md`.
- Concurrency is sequenced with `CountDownLatch` / `CyclicBarrier`, never
  `Thread.sleep`. Every test that can block carries `@Timeout(30)`.
- Evidence is written by tests to `<module>/target/evidence/07-*.txt` and
  copied by hand to `docs/evidence/`.
- Test data is synthetic.

## Decisions made while planning

The spec left these open or implied them. Each is flagged in its section
and carried to the decision record.

1. **Keyset listing, position detail and accrual entries are new ports,
   implemented with `JdbcClient` only.** Stage 6's `PositionListQuery`
   keeps both of its implementations and its contract. The three new reads
   are projections with no entity to manage, which is the case stage 6's
   decision record gave for bypassing the ORM. One implementation each
   means no second contract test to keep in step.
2. **Accrual entries are not paged. The date range is capped at 366
   days.** One position has at most one entry per day, so the cap bounds
   the response. `from > to` or a range over the cap is `400`.
3. **`@RateRange` reads its bounds from a `RateBounds` bean.**
   `AccrualRuleProperties` lives in `bootstrap`, which `adapter-web`
   cannot see. `adapter-web` declares `record RateBounds(BigDecimal lower,
   BigDecimal upper)`, and `bootstrap` supplies it from the existing
   properties. The validator is built by Spring's
   `LocalValidatorFactoryBean`, so it can be injected.
4. **`UnknownReferenceException` gains `kind()`.** The handler maps
   `position` to `404` and `counterparty` or `security` to `422` by kind,
   not by parsing the message.
5. **A return's `201` carries no `Location`.** A trade has no resource of
   its own. The body names the position.
6. **Problem `type` URIs are `urn:sl-ledger:problem:<kind>`.** A URN
   names the kind without pointing at a host that this project does not
   own.
7. **Upstream error responses map to `502`.** The spec's table has `504`
   for a timeout or an unreachable source, but not for a source that
   answers with a `5xx` or a body that breaks the money rule. Those are
   `502`. An upstream `404` for an ISIN is `404`.
8. **The thread dump will not show a socket read.** Boot's `RestClient`
   uses the JDK `HttpClient` when no other client library is on the
   classpath. A caller of `HttpClient.send` waits on a future, and the
   socket is read by the client's selector thread. The evidence records
   the frames as they appear. The spec's "every worker parked in a socket
   read" is corrected in the decision record to what was observed.
9. **Stage 6's duplicate-key tests change meaning.** `PositionUseCasesIT`
   asserts `DuplicateRequestException` for a repeated key. From section 7
   on, the use cases replay instead. Those tests are rewritten to assert
   the replay. The refusal itself is still tested one level down, on
   `TradeLedger.book`, which keeps throwing. No test is deleted without a
   replacement that pins the same row count.
10. **The manual market data stub is a WireMock container in
    `infra/compose.yaml`.** It serves `GET /prices/{isin}` from one mapping
    file, with no code. The image tag is pinned in section 17 and recorded
    there.

## Review focus

These are failure modes the spec implies but no exercise is *about*. Each is
pinned by a test in the section named.

1. **A replay after the position changed.** Open, return 10, then replay
   the open. The replayed body is identical to the first, with the original
   quantity, not the current outstanding (section 7).
2. **A key used for an open, then for a return.** The fingerprints differ,
   so the result is `422`, not a replay of the open (section 8).
3. **An amount that is a JSON string but not a number** (`"12,50"`,
   `"1e3"`, `" 1"`). The first and third are `400`. `1e3` is decided in
   section 2 and asserted either way, because `BigDecimal` accepts it
   (section 2).
4. **An error raised before a handler is chosen** (an unknown path, a
   wrong media type). It still carries `X-Trace-Id` and is a
   `ProblemDetail` (sections 3 and 4).
5. **The MDC after a request.** A request that throws leaves no trace id
   on the worker thread for the next request (section 3).
6. **An upstream price whose currency is not the security's currency, or
   whose amount is over-precise.** Refused as `502`, never rounded
   (section 14).

---

## [x] 1 · Build wiring and an empty server

**POMs**
- Parent `dependencyManagement`: `springdoc-openapi-starter-webmvc-api`
  (`${springdoc.version}` = 3.1.1) and `swagger-request-validator-core`
  (`${swagger-request-validator.version}` = 3.0.0).
- `adapter-web`:
  - Compile: `spring-boot-starter-webmvc`, `spring-boot-starter-validation`,
    `springdoc-openapi-starter-webmvc-api`.
  - Test: `spring-boot-starter-webmvc-test`, `spring-boot-starter-aspectj`.
  - Binds `maven-failsafe-plugin`.
- `bootstrap`: test gains `spring-boot-resttestclient` and
  `swagger-request-validator-core`.
- springdoc brings swagger-core, which is on Jackson 2
  (`com.fasterxml.jackson`) while Boot 4 is on Jackson 3 (`tools.jackson`).
  The two have different group ids and coexist. `dependencyConvergence`
  conflicts are resolved by explicit `dependencyManagement` entries with a
  one-line reason each, never by disabling the rule. Resolutions go to
  `docs/evidence/07-dependency-convergence.txt`.

**`adapter-web`**: `WebConfiguration`, a `@Configuration` with
`@ComponentScan` of `adapter.web`, imported by `LedgerConfiguration`.

**`bootstrap`**
- `LedgerApp.startWeb(…)`, as in the ground rules.
- `WebSmokeIT`: the application starts on a random port.
  `GET /v3/api-docs` is `200`, and an unknown path is `404`.
- `spring.mvc.problemdetails.enabled: true` in `application.yml`.

**Acceptance**
- `WebSmokeIT` passes, and the existing ITs still start with
  `WebApplicationType.NONE`.
- `dependencyConvergence` passes, with its evidence file.
- `./mvnw clean verify` is green.

---

## [x] 2 · Money, rates and dates in JSON

**`adapter-web.json`**: `LedgerJacksonModule`, a Jackson 3 `JacksonModule`
bean, which Boot registers on its `JsonMapper`.
- `Money` serializes as `{"amount": "<plain string at currency scale>",
  "currency": "<ISO code>"}`. `toPlainString`, never scientific notation.
- `Money` deserialization:
  - `amount` must be a JSON string. A number token is refused.
  - The string must match `-?[0-9]+(\.[0-9]+)?`. `1e3`, `12,50` and
    `" 1"` are refused (Review focus 3: `1e3` is refused, because the
    pattern is the contract and exponent notation is one more way for two
    clients to disagree).
  - A scale above the currency's minor units is refused **before** `Money`
    is constructed, because `Money` would round it silently.
  - An unknown currency code is refused.
  - Each refusal is a Jackson `MismatchedInputException` subtype, so
    section 4 maps it to `400` with the field path.
- `Rate` serializes and deserializes as a string, unscaled.
- `LocalDate` stays ISO `yyyy-MM-dd`. A test pins it, because it is the
  default and a default can change.

**Tests (`adapter-web`, unit)**
- round trips of `Money` in `HKD`, `JPY` and `BHD` at 16 integer digits,
  asserting amount, scale and currency;
- each refusal above;
- `reproducesPrecisionLossAsJsonNumber`:
  - serialize `9999999999999999.99 HKD` with a plain `JsonMapper` and no
    module, which writes a number;
  - read it back with `USE_BIG_DECIMAL_FOR_FLOATS` off, which is what a
    JavaScript client's `JSON.parse` does: an IEEE double;
  - assert the value read back is not the value written, and record both
    in `docs/evidence/07-json-precision.txt`;
  - the same amount through `LedgerJacksonModule` round-trips exactly.

**Acceptance**
- The tests pass.
- `./mvnw clean verify` is green.

**Found while implementing**
- **Over-precision is judged by value, not by digit count.** `12.500 HKD`
  is accepted as `12.50 HKD`; `12.505 HKD` is refused. That is stage 6's
  column rule (refuse only what re-scaling would round), and nothing is
  lost by accepting trailing zeros. The spec's wording, "more decimals than
  its currency allows", is narrowed to this in the decision record.
- **The default serialization also leaks `"negative": false`**, from
  `Money.isNegative()`. The module writes exactly two fields.
- `LedgerJacksonModule` is `final`: `-Werror` refused the `this`-escape of
  registering serializers in a non-final constructor.
- A mutation that accepted number amounts and skipped the precision check
  turned 5 refusal cases red.

---

## [x] 3 · The trace-id filter

**`adapter-web.trace.TraceIdFilter`**, a `OncePerRequestFilter` at
`Ordered.HIGHEST_PRECEDENCE`:
- a fresh 32-hex-digit id per request, the W3C trace-id shape, so stage 12
  can adopt it;
- an incoming `X-Trace-Id` is ignored. A client-chosen id in logs is a log
  injection path, and there is no upstream to trust yet;
- `MDC.put("traceId", …)`, `X-Trace-Id` on the response, and
  `MDC.remove` in `finally`;
- the id is also stored as a request attribute, for section 4's `500`
  body.

**Tests (`adapter-web`, `@WebMvcTest` with a test-only probe controller)**
- every response carries `X-Trace-Id`, and two requests get different ids;
- a request to an unknown path still gets one (Review focus 4);
- after a request whose handler throws, `MDC.get("traceId")` is null
  (Review focus 5). MockMvc runs the request on the test's own thread, so
  the test reads the MDC of the thread that served it;
- an incoming `X-Trace-Id` does not appear in the response.

**Acceptance**
- The tests pass.
- `./mvnw clean verify` is green.

---

## [x] 4 · Errors as `ProblemDetail`

**`application`**: `UnknownReferenceException.kind()` (decision 4).

**`adapter-web.error.LedgerProblemHandler`**, a `@RestControllerAdvice`
extending `ResponseEntityExceptionHandler`:

| Exception | Status | `type` suffix |
|---|---|---|
| `MethodArgumentNotValidException` | `400` | `validation`, with `errors: [{field, message}]` |
| unreadable body, including section 2's refusals | `400` | `malformed-body`, with the JSON path |
| `MissingRequestHeaderException` | `400` | `missing-header` |
| `MalformedIdentifierException` | `400` | `malformed-identifier` |
| `UnknownReferenceException`, kind `position` | `404` | `not-found` |
| `UnknownReferenceException`, other kinds | `422` | `unknown-reference` |
| `InsufficientCreditException` | `422` | `insufficient-credit` |
| `ExcessiveReturnException` | `422` | `excessive-return` |
| `CurrencyMismatchException` | `422` | `currency-mismatch` |
| `IllegalTransitionException` | `409` | `illegal-transition` |
| `ConcurrencyFailureException` | `409` | `concurrent-update` |
| `Exception` | `500` | `internal`, detail fixed, `traceId` property |

`ConcurrencyFailureException` covers both the optimistic failure the retry
loop gives up on and a pessimistic lock timeout. Section 8 adds the
idempotency rows, and section 14 adds `502` and `504`.

Why each `4xx` is the code it is goes into the decision record. In short:
`404` is for the resource in the URL, and `422` is for a well-formed body
that names something missing or breaks a business rule.

**Tests (`adapter-web`, `@WebMvcTest`, a probe controller throwing each
exception)**
- one per row: status, `type`, `Content-Type: application/problem+json`;
- `500`: the exception carries a recognisable message. The body contains
  neither that message, nor any `com.jerome` or `java.` class name, nor
  the string `at `, and it contains the trace id from section 3;
- a wrong `Content-Type` on a `POST` is `415` as a `ProblemDetail` with a
  trace id (Review focus 4).

**Acceptance**
- The tests pass.
- `./mvnw clean verify` is green.

**Found while implementing**
- **Other `DomainException`s map to `422 domain-rule`**, not `500`:
  `NotABusinessDayException` and friends are refusals of a well-formed
  request, like the named ones.
- **The trace id is added by one `ResponseBodyAdvice`**
  (`TraceIdOnProblems`) to every `ProblemDetail`, not by each handler, so
  no error path can forget it.
- **A framework error's body is built inside `super.handleExceptionInternal`.**
  Typing it before the call found a `null` body, and the `415` went out
  with no `type`. It is typed after the call.

---

## [x] 5 · Read endpoints: list, detail, accruals

**`application.port`** (decision 1)
- `PositionDetailQuery.find(LoanPositionId) → Optional<PositionRow>`;
- `AccrualEntryQuery.of(LoanPositionId, LocalDate from, LocalDate to) →
  List<AccrualEntry>`, ordered by date.

**`application.position`**: `ShowPosition` and `ListAccruals`, both
`@Transactional(readOnly = true)`. `ListAccruals` refuses a reversed or
over-long range (decision 2) and an unknown position (`404`, not an empty
list).

**`adapter-persistence`**: `JdbcPositionDetailQuery` and
`JdbcAccrualEntryQuery`, reading money through `MoneyColumns`, so stage
6's over-precision refusal applies. Contract tests in `adapter-persistence`
against `SharedPostgres`.

**`adapter-web.loan.LoanReadController`**
- `GET /loans?page=&size=`: `{rows, page, size, totalRows}`. `size`
  defaults to 20 and is capped at 200;
- `GET /loans/{id}`;
- `GET /loans/{id}/accruals?from=&to=`.

**`bootstrap` `LoanReadIT`**, against a running server: each endpoint's
body, an unknown id (`404`), and a bad range (`400`).

**Acceptance**
- The ITs pass.
- `./mvnw clean verify` is green.

**Found while implementing**
- **The compiler did not keep parameter names.** Every `@PathVariable` and
  `@RequestParam` without an explicit name failed at request time with a
  `500` (`IllegalArgumentException: … Ensure that the compiler uses the
  '-parameters' flag`). `spring-boot-starter-parent` sets `-parameters`,
  and this project imports the BOM instead of inheriting the parent, so it
  never had it. The parent POM now sets `<parameters>true</parameters>`.
  The incremental compile did not notice the configuration change; only a
  `clean` build did.
- **`InvalidQueryException`** (application) is the reversed or over-long
  range, mapped to `400 invalid-query`. A page size over 200 is refused
  with `400`, not clamped.
- **Responses use web views (`PositionView`, `AccrualsView`)**, not the
  application's rows. The domain's id records would otherwise serialize as
  `{"value": "…"}` objects.
- The JDBC row mapping is shared: `JdbcPositionListQuery.row` serves the
  listing and the detail.

---

## [x] 6 · V4 fingerprint and trade lookup by key

**`application.port`**
- `RequestFingerprint`, a record holding a SHA-256 hex string, with a
  static factory per command. It hashes a canonical string of the
  command's fields: the kind (`OPEN`, `RETURN`), then the fields in a
  fixed order, amounts as `toPlainString` with their currency, and rates
  unscaled. Two commands are equal requests exactly when their canonical
  strings are equal. The idempotency key itself is not hashed.
- `BookedTrade(LoanTrade trade, RequestFingerprint fingerprint)`.
- `TradeLedger`:
  - `book(LoanTrade, RequestFingerprint)` replaces `book(LoanTrade)`, and
    still raises `DuplicateRequestException`;
  - `findByKey(IdempotencyKey) → Optional<BookedTrade>`.

**`adapter-persistence`**
- `V4__loan_trade_request_fingerprint.sql`: `request_fingerprint char(64)`,
  nullable. Rows booked before V4 have none, and a replay against such a
  row is refused as a mismatch rather than guessed at.
- `JpaTradeLedger` implements both methods.

**Tests**
- `RequestFingerprintTest`:
  - equal commands give equal fingerprints;
  - each field changed alone changes it;
  - `12.50 HKD` and `12.5 HKD` are equal, because `Money` normalises
    scale first;
  - an open and a return never collide.
- The `TradeLedger` contract in `adapter-persistence`: book, find by key,
  duplicate refused, and a pre-V4 row read back with no fingerprint.

**Acceptance**
- The tests pass. Stage 6's tests pass with the new `book` signature.
- `./mvnw clean verify` is green.

**Found while implementing**
- **The fingerprint factories live on the commands**
  (`OpenPositionCommand.fingerprint()`, `ReturnCommand.fingerprint()`), not
  on `RequestFingerprint`: the port package must not depend on the use-case
  package that depends on it. `AccrualRules.canonical` gives each rule type
  its text; a flat rate keeps its scale, as `Rate` does.
- `application` gets its first tests, and with them `junit-jupiter` and
  `assertj-core` at test scope.
- `MoneyColumnsRoundTripIT` persists a trade entity directly, so it now
  passes a fingerprint too.

---

## [x] 7 · Idempotent use cases

**`application.position`**
- `OpenPosition.open` returns `OpenedPosition(LoanPositionId, LoanTrade)`
  instead of `LoanPositionId`, and books with the command's fingerprint.
- `ReturnPositionOnce` books with its fingerprint. `ReturnPosition.apply`
  returns `ReturnedQuantity(LoanPositionId, LoanTrade)`.
- `FindBookedTrade`, `@Transactional(readOnly = true)`, wraps
  `TradeLedger.findByKey`.
- `IdempotentOpen` and `IdempotentReturn`, **not** transactional, the same
  shape as stage 6's retry loop:
  1. call the use case;
  2. on `DuplicateRequestException`, whose transaction has rolled back,
     call `FindBookedTrade`;
  3. same fingerprint: return the result rebuilt from the trade, marked
     `replayed`;
  4. different fingerprint: throw `IdempotencyKeyReusedException`.
- Results: `Outcome<T>(T result, boolean replayed)`.

A replay rebuilds its result only from the trade row: position id,
direction, quantity, unit price, trade date and settlement date. It never
reads the position (spec decision 2).

**`bootstrap`**: wires the four new beans.

**`bootstrap` `PositionUseCasesIT`** (decision 9)
- the same open twice: the second is a replay with an equal result, and
  there is one position and one trade;
- open, return 10, then replay the open: the result equals the first
  open's, with the original quantity (Review focus 1);
- the same key with a different quantity: `IdempotencyKeyReusedException`;
- an open's key reused for a return: `IdempotencyKeyReusedException`;
- a refused open (over the credit limit), then the same key after the
  limit is raised: the second succeeds (spec decision 2, last point);
- stage 6's other assertions are unchanged.

**Acceptance**
- The ITs pass under both lock strategies.
- `./mvnw clean verify` is green.

**Found while implementing**
- **One wrapper, not two, and the trade is the result.** `IdempotentBooking`
  has `open` and `returnQuantity`, sharing one replay path.
  `OpenPosition.open` and `ReturnPosition.apply` return the booked
  `LoanTrade`, which names the position. That trade is exactly what a
  replay rebuilds, so `Outcome(LoanTrade, replayed)` needs no second
  result type.
- **Decision 9 was unnecessary.** `OpenPosition` itself still refuses a
  duplicate, so stage 6's three duplicate tests stay as they were, and the
  replay tests were added beside them. No test changed meaning.
- A mutation that skipped the fingerprint comparison turned 2 tests red
  under each lock strategy: the reused key for a different open, and the
  open's key reused for a return.

---

## [x] 8 · Write endpoints

**`adapter-web.loan`**
- DTOs, records: `OpenLoanRequest(counterpartyId, isin, quantity,
  collateral: Money, rule: {type, rate}, tradeDate)` and
  `ReturnRequest(quantity, tradeDate)`. Only `type: FLAT` is accepted,
  because only `FlatRate` persists.
- Responses: `OpenedLoanResponse(positionId, trade)` and
  `ReturnedResponse(positionId, trade)`, with `trade` as `{direction,
  quantity, unitPrice, tradeDate, settlementDate}`.
- `LoanWriteController`:
  - `POST /loans`: `201`, `Location: /loans/{id}`;
  - `POST /loans/{id}/returns`: `201`, no `Location` (decision 5);
  - both require `Idempotency-Key`. A replay returns the same status and
    body, plus `Idempotent-Replayed: true`.
- `LedgerProblemHandler` gains `IdempotencyKeyReusedException` → `422`,
  `idempotency-key-reused`.

**`bootstrap` `LoanWriteIT`**, against a running server:
- open, then `GET` the `Location`;
- a replay is byte-identical, with the replay header;
- a missing key is `400`, and nothing is booked;
- open, then return with the open's key: `422` (Review focus 2);
- partial and full returns, then the detail shows `CLOSED`;
- over the credit limit: `422`, `insufficient-credit`;
- returning more than is outstanding: `422`, `excessive-return`.

**Acceptance**
- The ITs pass.
- `./mvnw clean verify` is green.

**Found while implementing**
- **One response type, `BookedResponse(positionId, trade)`, serves both
  endpoints.** It is built from the trade only.
- **The web ITs use their own reference data (`WebLedger`)**, with ISINs
  whose check digits are valid, so section 9's `@Isin` does not break them.
- The replay test sends the same request with a different field order and
  `50000.0` for `50000.00`. It is replayed, because the fingerprint is of
  the command, not of the body text.

---

## [x] 9 · Bean Validation

**`adapter-web.validation`**
- `@Isin` / `IsinValidator`: the shape, then the check digit. Letters
  expand to two digits (`A` = 10 … `Z` = 35), then Luhn over the result.
- `@RateRange` / `RateRangeValidator`, with bounds from the `RateBounds`
  bean (decision 3). `bootstrap` supplies it from `AccrualRuleProperties`.
- Standard constraints on the DTOs: `@NotNull`, `@Positive` quantity,
  `@NotBlank` ids, and `@Valid` on nested objects.

**Groups, test-only**: a `TradeInstruction` DTO in `adapter-web`'s tests,
validated under `OnOpen` and `OnReturn`. `OnOpen` requires collateral and
a rule, and `OnReturn` forbids them. The test asserts that each group
accepts what the other refuses. The decision record says why product code
uses two types instead.

**Tests**
- `IsinValidatorTest`: synthetic ISINs with computed check digits are
  accepted, and the same ISINs with the last digit changed are refused.
  One of the refused values is accepted by `domain`'s `Isin`, which is the
  point.
- `RateRangeValidatorTest`: the bounds follow the bean.
- `LoanWriteIT` gains: a bad check digit, a rate above the configured
  upper bound, and a zero quantity are each `400` with the field named.

**Acceptance**
- The tests pass.
- `./mvnw clean verify` is green.

**Found while implementing**
- **The annotation is `@ValidIsin`, not `@Isin`**: every request type that
  uses it also imports `domain`'s `Isin`.
- **Two more constraints the plan did not name.** `@NotNegative` on
  collateral: `domain` refuses a negative one with an
  `IllegalArgumentException`, which would surface as a `500`.
  `@IdempotencyKeyHeader`: 1 to 255 printable ASCII characters, because the
  key is stored and logged.
- **A constrained header turns on method validation for the whole call.**
  Once `@IdempotencyKeyHeader` is on a parameter, Spring 7 validates the
  `@Valid` body as part of method validation too, and raises
  `HandlerMethodValidationException` instead of
  `MethodArgumentNotValidException`. The first run reported the body's
  errors under the parameter name `request`. The handler now unpacks
  `ParameterErrors` to field paths (`rule.rate`).
- **The groups test also pins the default-group trap**: a type whose every
  constraint names a group validates nothing under `@Valid`, which checks
  `Default`.
- The hand-computed check digits (`HK0000000007`, `US0000000010`, …) agree
  with `IsinValidator.checkDigitFor`, and stage 6's `HK0000000001` is
  refused at the boundary while `domain` accepts it.

---

## [x] 10 · Idempotency races

**`bootstrap` `IdempotencyRaceIT`**, against a running server. A test-only
`TradeLedger` decorator, registered by `LedgerApp.startWeb`'s extra
sources like stage 6's `BarrierAfterFind`, holds the **first** caller
inside its transaction, right after `book`, until a latch is released.

- **The first commits.**
  1. Request A books and is held.
  2. Request B is sent with the same key and the same body.
  3. The test polls `pg_stat_activity` until B's backend waits on a
     `transactionid` lock. Polling has a bound, and there is no sleep.
  4. A is released and commits.
  5. B gets `201` with `Idempotent-Replayed: true` and a body equal to
     A's.
  6. There is one position and one trade.
- **The first rolls back.** The same sequence, but the decorator throws
  after the latch, so A rolls back and gets `500`. B's insert proceeds:
  B gets `201`, not replayed, and there is one position.
- B's measured wait, and the `pg_stat_activity` row, go to
  `docs/evidence/07-idempotency-race.txt`.

**Acceptance**
- Both scenarios pass ten runs in a row (`@RepeatedTest(10)`).
- `./mvnw clean verify` is green.

**Found while implementing**
- The rolled-back first request answers `500 internal`: the test's
  decorator throws an `IllegalStateException`, which is exactly an
  unexpected failure.
- One application context serves all 20 repetitions; tables are emptied
  between them. Starting one per repetition would have cost about 2 s each
  for no extra coverage.
- Polling `pg_stat_activity` parks 5 ms between queries, bounded at 10 s.
  It waits for a condition; nothing is sequenced by time.

---

## [x] 11 · The money invariant, end to end

**`bootstrap` `MoneyOverHttpIT`**
- Reference data in `HKD`, `JPY` and `BHD`, with credit limits wide
  enough.
- Open over HTTP with collateral `9999999999999999.99 HKD`,
  `9999999999999999 JPY` and `9999999999999999.999 BHD`.
- `GET /loans/{id}`: the amount string, its scale and its currency are
  exactly what was sent.
- The raw row in Postgres is checked too, as `numeric` and `char(3)`.
- An open with the amount as a JSON number is `400`, and one with a
  `JPY` amount carrying decimals is `400`. Nothing is booked for either.

**Acceptance**
- The IT passes.
- `./mvnw clean verify` is green.

---

## [x] 12 · Keyset pagination and deep pages

**`application.port`**: `PositionCursorQuery.after(Optional<LoanPositionId>
after, int size) → PositionSlice(rows, Optional<LoanPositionId> next)`.
`next` is empty on the last slice. `ListPositions` gains
`slice(after, size)`.

**`adapter-persistence`**: `JdbcPositionCursorQuery`, with `WHERE id > ?
ORDER BY id LIMIT ?` on the primary key.

**`adapter-web`**: `GET /loans?after=&size=` returns `{rows, next}`.
Sending `page` and `after` together is `400`.

**Correctness, `bootstrap` `PaginationConsistencyIT`**
- 30 positions with ids chosen so that the test controls the sort order.
- Offset: read page 0 (size 10), insert a position whose id sorts before
  the page boundary, then read page 1. Its first row repeats page 0's
  last. `reproducesOffsetPageDriftUnderInsert`.
- Keyset: the same sequence. Walking every slice yields every row that
  existed at the start exactly once.

**Measurement, `bootstrap` `DeepPageIT`**, tagged `measurement`
- 500,000 positions from `generate_series`, one counterparty, one
  security.
- Offsets 0, 250,000 and 499,980 against the keyset cursor at the same
  rows. There are 5 warm-up requests, then the median of 20, over HTTP.
- `EXPLAIN (ANALYZE, BUFFERS)` of the offset query at 499,980 and of the
  keyset query at the same row.
- The test asserts only that both return the same rows. Timings and
  plans go to `docs/evidence/07-pagination.txt`.

**Acceptance**
- `PaginationConsistencyIT` passes.
- `DeepPageIT` passes under `-Pmeasure`, and its evidence is committed.
- `./mvnw clean verify` is green.

**Found while implementing**
- **One response type for both, `PositionListView`**, with the fields that
  do not apply left out (`@JsonInclude(NON_NULL)`). One handler and one
  schema for `GET /loans`, which section 16's contract test needs;
  `PositionPageView` is gone.
- **`after=` with no value starts a keyset walk.** Without it, a client
  could not ask for the first keyset slice.
- **Offset is slow at depth 0 too.** Its `count(*)` is a parallel
  sequential scan of every row. Under `EXPLAIN ANALYZE` it took 37.8 ms
  alone, about the whole 36.3 ms median of an offset-0 request; the first
  keyset slice took 6.2 ms.
- `JdbcPositionCursorQuery` reads `size + 1` rows to know whether a next
  slice exists.

---

## [ ] 13 · Filter, interceptor, aspect

All test-only, in `adapter-web`'s tests, `@WebMvcTest` with
`@EnableAspectJAutoProxy`:
- `RecordingInterceptor`: `preHandle`, `postHandle` and
  `afterCompletion`, each recording the handler it sees and the exception
  in `afterCompletion`;
- `RecordingAspect`: `@Around` any `@RestController` method, recording
  its arguments and whether it threw;
- the production `TraceIdFilter`, plus a test filter after it that
  records entry and exit.

**`LayerOrderTest`**
- The success path records: filter in, interceptor `preHandle`, aspect in,
  aspect out, interceptor `postHandle`, interceptor `afterCompletion`,
  filter out.
- On a throwing handler, the aspect sees the exception, and `postHandle`
  is not called. `afterCompletion` gets no exception, because the
  `@RestControllerAdvice` resolved it first. The test asserts the observed
  value, whichever it is, and records it.
- Only the filter sees a request with no handler (an unknown path).
- What each layer saw goes to `docs/evidence/07-layer-order.txt`.

**Acceptance**
- The test passes.
- `./mvnw clean verify` is green.

---

## [ ] 14 · Market data source

**`application`**
- `port.MarketPriceSource.priceOf(Isin) → Money`. It throws
  `MarketDataUnavailableException` (timeout or unreachable),
  `MarketDataRejectedException` (a bad upstream answer) or
  `UnknownReferenceException("security", …)`.
- `quote.QuotePrice`, **not** transactional. It first reads the security
  through `SecurityDirectory`, so an unknown ISIN is `404` without an
  upstream call. It then calls the port, and refuses a price whose
  currency is not the security's currency (Review focus 6). No
  transaction spans the upstream call: a slow source must not hold a
  database connection while it hangs.

**`adapter-web.client.RestMarketPriceSource`**
- `RestClient` on `JdkClientHttpRequestFactory`, with an `HttpClient`
  whose `connectTimeout` comes from `ledger.marketdata.connect-timeout`,
  and a factory `readTimeout` from `ledger.marketdata.read-timeout`. An
  absent timeout means none. The base URL is `ledger.marketdata.base-url`.
- It reads `{isin, price: Money}` through `LedgerJacksonModule`, so the
  money rule applies to what comes in from outside too.
- Mapping: timeout or connection failure → `MarketDataUnavailableException`,
  upstream `404` → unknown security, other `4xx`/`5xx` or an unreadable
  body → `MarketDataRejectedException` (decision 7).

**`adapter-web.quote.QuoteController`**: `GET /securities/{isin}/quote`.
`LedgerProblemHandler` gains `504` (`market-data-unavailable`) and `502`
(`market-data-rejected`).

**`bootstrap` test support**: `StubPriceServer`, on
`com.sun.net.httpserver.HttpServer` with a cached thread pool as its
executor. Each ISIN can be set to answer, answer badly, `404`, or hang
until a latch opens.

**`bootstrap` `QuoteIT`**: a normal quote; an upstream `404`; an upstream
`500` → `502`; an over-precise amount → `502`; a currency mismatch →
`502`; a hang with a 500 ms read timeout → `504` within 2 s.

**`RealReferenceRates`**: the message now says the implementation is
unscheduled (spec, fact 4).

**Acceptance**
- `ArchitectureIT` gains its two rules and passes:
  - classes in `adapter.web..` outside `adapter.web.client..` do not
    depend on `adapter.web.client..`;
  - controllers depend on no `adapter.persistence..` class. That is
    stage 3's rule 2, which is no longer vacuous, and its Javadoc and
    `allowEmptyShould` are updated.
- `QuoteIT` passes.
- `./mvnw clean verify` is green.

---

## [ ] 15 · Thread exhaustion, and the timeouts that prevent it

**`bootstrap` `ThreadExhaustionIT`**
- `reproducesThreadExhaustionWithoutTimeout`:
  - Start the application with `server.tomcat.threads.max=4`,
    `server.tomcat.threads.min-spare=4` and no read timeout.
  - Send 4 quote requests for a hanging ISIN. The stub's counter shows
    all 4 arrived.
  - `GET /loans` with a 2 s client timeout does not complete. The test
    asserts the client's `HttpTimeoutException`.
  - At that moment, dump every thread (`ThreadMXBean.dumpAllThreads`,
    formatted like `jstack`) to
    `docs/evidence/07-thread-exhaustion.txt`, then release the latch.
- `timeoutKeepsServerResponsive`: the same setup with a 500 ms read
  timeout. All 4 quotes get `504`, each within 2 s, and the `GET /loans`
  sent alongside them gets `200`. The per-request timings are recorded.
- `connectTimeoutObservation`:
  - the base URL points at `10.255.255.1`, a non-routable address, with a
    500 ms connect timeout;
  - the test asserts `504` within 3 s;
  - it records whether the failure came from the connect timeout or from
    an immediate refusal, because that depends on the network (spec).
- The evidence file marks the worker frames that show the pool is full.
  They are all `http-nio-*-exec-*` threads waiting in `HttpClientImpl.send`
  (decision 8).

**Acceptance**
- All three pass, and each releases its latch in `finally`.
- `./mvnw clean verify` is green.

---

## [ ] 16 · OpenAPI and the contract test

**`adapter-web`**: an `OpenApiCustomizer` that replaces the inferred
`Money` schema with `{amount: string, pattern, currency: string}` and
`Rate` with a string. Before it exists, springdoc infers `amount` as a
`number`. The first test run, without the customizer, records that
inferred schema to `docs/evidence/07-openapi-money.txt`.

**`bootstrap` `OpenApiContractIT`**
- `GET /v3/api-docs`: `Money.amount` is `type: string`.
- For each endpoint, a real request is made. Its response, including
  `400`, `404`, `422` and `502` bodies, is validated with
  `OpenApiInteractionValidator` against the served document. The test
  fails on any validation message.
- Each controller declares its error responses with
  `@ApiResponse(content = @Content(schema = ProblemDetail))`, so the error
  bodies have a schema to be checked against.

**Acceptance**
- The IT passes.
- `./mvnw clean verify` is green.

---

## [ ] 17 · Manual run

- **Seed**: `bootstrap/src/main/resources/db/seed-local/R__local_seed.sql`
  inserts synthetic counterparties and securities with valid check
  digits, using `ON CONFLICT DO NOTHING`. Only `application-local.yml`
  adds `classpath:db/seed-local` to `spring.flyway.locations`. A test
  asserts that the default and `test` profiles do not list it.
- **Stub**: `infra/compose.yaml` gains `marketdata`, a pinned WireMock
  image bound to `127.0.0.1`, with `infra/wiremock/mappings/prices.json`
  answering `GET /prices/{isin}` with a fixed synthetic price (decision
  10). `application-local.yml` sets `ledger.marketdata.base-url` and both
  timeouts.
- **Run**: Compose up, `spring-boot:run` with the `local` profile, then
  `curl`: open, replay, return, list, detail, accruals and quote. The
  transcript, with the headers, goes to
  `docs/evidence/07-manual-run.txt`.
- Grepping the log for the database password finds nothing.

**Acceptance**
- The transcript shows every endpoint answering as specified.
- `./mvnw clean verify` is green, with no test touching Compose.

---

After section 17, `docs/decisions/07-web.md` is written as a separate
commit. It covers every item the spec's "Done when" lists and the ten
decisions above, with numbers only from `docs/evidence/07-*.txt`. ROADMAP's
stage 7 checkboxes and progress row are ticked in that commit.

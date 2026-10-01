# Stage 13 — Security — Decision Record

Spec: [`docs/specs/13-security.md`](../specs/13-security.md)
Plan: [`docs/plans/13-security.md`](../plans/13-security.md)
Logging rule: [`docs/logging.md`](../logging.md)

Measured on JDK 21.0.12 (Temurin, the `.sdkmanrc` pin), a 4-CPU Linux host, Spring
Boot 4.1.1 with Spring Security 7.1.1, Postgres 17 (Testcontainers), Keycloak 26.4 and
Prometheus 3.5.0 (compose, loopback, for the manual run), Trivy 0.65.0 (Docker). Every
number below is copied from a `docs/evidence/13-*.txt` file. Where a claim is reasoned and
not measured, the text says so; the last section is entirely that.

## What I chose

### The authorization model: who may do what, and which layer says so

This is the answer to "who can do what to what, and where does it live in the code".

| Operation | trader | ops | counterparty | auditor | metrics |
|---|:-:|:-:|:-:|:-:|:-:|
| `POST /loans`, `POST /loans/{id}/returns` | yes | | | | |
| `GET /loans`, `/loans/{id}`, `/loans/{id}/accruals` | all rows | all rows | **own rows** | | |
| `GET /securities/{isin}/quote`, `GET /quotes` | yes | yes | | | |
| `GET /audit` | | | | yes | |
| `GET /actuator/prometheus` | | | | | yes |
| `GET /actuator/health` (details) | status only | **details** | status only (the rule is by role; observed for a trader) | | |
| `GET /actuator/health/liveness`, `/readiness` | no token needed | | | | |

`docs/evidence/13-authorization-matrix.txt` is this table observed: eight endpoints as six
callers (no role, and the five roles), 403 exactly where the table is empty, never 401 for a
valid token.

Three layers, each answering one question and no other:

1. **The filter chain** (`SecurityConfiguration`, `adapter-web`): *is there a valid token?*
   Stateless, no session, no CSRF token (there is no cookie to forge a request with), no form
   or basic login. Public: the two probes, because an orchestrator cannot send a token. The
   scrape needs `ledger-metrics`. Everything else needs any valid token.
2. **Method security** (`@PreAuthorize` through `Access.*`, on every controller handler): *may
   this role call this operation?* Answered before a transaction exists (below). An
   `ArchitectureIT` rule fails the build for a handler without one; removing the annotation
   from `QuotesController.quotes` produced exactly that failure.
3. **The query** (`Visibility` into `ListPositions`, `ShowPosition`, `ListAccruals`, then a
   `WHERE counterparty_id = ?`): *whose rows?* A role check cannot answer this, and a check on
   the returned row cannot answer it for a list.

`domain` and `application` import no Spring Security type (`ArchitectureIT`). `application`
owns `Caller` (subject, roles, counterparty claim), `Visibility` (`ALL`, `only(cp)`,
`NOBODY`) and the `Callers` port; the edge builds a `Caller` from the token's
`realm_access.roles` and `counterparty_id` claims. A role name the ledger does not define is
dropped, and a `ledger-counterparty` token with no `counterparty_id` claim sees nothing: a
misconfigured account fails closed.

### A refusal is not an oracle: 404 for a foreign id, 403 for a foreign filter

Counterparty CP-002 asking for `/loans/LP-A1`, which is CP-001's, gets the response a missing id
gets: the same status, the same type and, but for the id it named itself and the per-request
trace id, the same body (`13-counterparty-scope.txt`). The first version of the endpoint, the
test-only one that checks the role and not the owner, answered 200 with the other counterparty's
position, collateral amount included: that is `reproducesPositionOfAnotherCounterpartyReadByIdChange`.
Asking the list for `?counterparty=CP-001` with CP-002's token is a 403 instead: the request names
someone else's rows and says so, and refusing it reveals nothing the caller did not already write.

The list is the reason the filter is in the SQL. `totalRows` for CP-002 is 1, its own; a
post-filter on the page would show a total of 4 and a short page, which is the other
counterparties' row count. A partial index `(counterparty_id, id)` (V17) serves both the filter
and the keyset order. There is no overload of the list queries without the counterparty
argument: "everyone" is `Optional.empty()`, spelled out at the call.

### `@PreAuthorize` runs first, and the numbers say why

On paper: method security's advisors are ordered 100 (`@PreFilter`), 200 (`@PreAuthorize`), 450,
500 (`@PostAuthorize`) and 600 (`@PostFilter`); the transaction advisor is
`LOWEST_PRECEDENCE`. A lower order is outer. Observed on the running context
(`13-security-vs-transaction.txt`):

| call | events, in order |
|---|---|
| permitted | authorization decision (no transaction) → transaction begun → body (transaction active) → committed |
| denied | authorization decision (no transaction) → `AuthorizationDeniedException`; no transaction begun |
| denied, with the transaction advisor placed outside (built by hand) | transaction begun → rolled back; the refusal took a connection |

The same table says something the exercise did not ask: `@PostAuthorize` (500) is outer to the
transaction as well, so it judges a result *after the transaction has committed*. A
`@PostAuthorize` on a use case that writes refuses the caller and keeps the write. This ledger
uses none; the rule is "authorize before, never after".

### Authentication: a resource server that verifies, and a provider that issues

Spring Security's `oauth2-resource-server`; Keycloak (compose) mints the tokens. The key set is
`jwk-set-uri` in the local and prod profiles and `public-key-location` in tests, so the tests sign
tokens with a key generated in memory when the JVM starts and the application has no test branch;
no key is committed. Issuer and audience are both validated, and the 401 says nothing about
which check failed: the same 401 problem for no token, a malformed one, an expired one, a wrong
issuer, a wrong audience and a key the service does not trust (`13-token-rejection.txt`), with
the trace id; the reason is in the log line with the same trace id.

`TraceIdFilter` (order `HIGHEST_PRECEDENCE + 2`) is outside the security chain (order −100), so a
401 carries a trace id. The container has six filters and the security chain twelve
(`13-filter-chain.txt`); `CsrfFilter`, `UsernamePasswordAuthenticationFilter`,
`BasicAuthenticationFilter` and `LogoutFilter` are not among them, and an authenticated request
sets no cookie.

A real token from the compose Keycloak, not a test key, was accepted by the application started
with the local profile: five users and a service account, each token carrying exactly the claims
the application reads (`13-manual-run.txt`). borrower2 (CP-002) saw no rows, got 404 for
borrower1's position, 403 for `?counterparty=CP-001` and 403 for `POST /loans`; ops1 got 403 on
`POST /loans`; Prometheus, from the compose service, scraped with a token it fetched itself by
client credentials, target health `up`.

### The audit trail: two transactions on purpose

`audit_event` (V18) holds when, who (the token's subject), what, which subject, how it ended and a
machine reason. Ids and codes only: no amount and no name, so it cannot disagree with the ledger
and holds nothing the money invariant protects; a test asserts the column types are `bigint`,
`timestamptz` and `text`, nothing numeric. A trigger refuses `UPDATE` and `DELETE`.

- **A booked open or return is written inside the booking's transaction** (`Audit.booked`,
  `Propagation.MANDATORY`). It commits with the trade and rolls back with it, and `MANDATORY`
  makes that a rule: called with no transaction it throws instead of writing a line that outlives
  nothing. An open refused for credit left no `BOOKED` row; a replay of a booked request left no
  second one.
- **A refused attempt is written outside it** (`Audit.refused`, `REQUIRES_NEW`, from
  `IdempotentBooking`, the one place that sees the failure after the rollback) and so is an
  authorization denial (`Audit.denied`, from the problem handler). The credit-limit refusal left
  one `REJECTED REQUEST ... InsufficientCreditException`; a counterparty's `POST /loans` left
  `DENIED ENDPOINT POST /loans`; the manual run shows an `OPEN BOOKED` row naming the Keycloak
  subject.
- **The reproduction:** `reproducesAuditRowSurvivingRolledBackBooking` writes the `BOOKED` line in
  its own transaction from inside a booking that then rolls back. The line stays, naming a
  position (`LP-GHOST`) that does not exist.

A failure to write a refusal's line is suppressed onto the original exception: the trail must
not turn a 422 into a 500. That makes the refused-attempt rows best effort, which the last section
says plainly.

### Hostile input

`'; --` alone, concatenated into the listing's `WHERE`, is not a leak in this stack: the driver
ends up with two statements and the call fails with "Multiple ResultSets were returned by the
query". The attacks that matter are the same break made to succeed (`13-sql-injection.txt`):

| counterparty input | concatenated query (test-only) | real adapters (JPA and JDBC) |
|---|---|---|
| `CP-002` | 2 rows | 2 rows |
| `x' OR '1'='1' --` | **5 rows**, every counterparty's | 0 rows |
| `x'; UPDATE loan_position SET status = 'CLOSED'; --` | error raised, positions CLOSED **0 → 5** | 0 rows, 0 CLOSED |
| `'; --` | error | 0 rows |

The error on the stacked statement is not a rollback: the driver had executed both statements
when it noticed there were two. Over HTTP the same three payloads in `?counterparty=` are
200 with `"totalRows":0` and the position is untouched.

Mass assignment: the test-only endpoint binds the body to something shaped like the entity and
saves it by id, as `JpaRepository.save` does. Position LP-A1, `CP-001 | OPEN | 1000`, became
`CP-002 | CLOSED | 1` after one POST that named the id and the status
(`13-mass-assignment.txt`). The real request records have no field for an id, a status or a
version, so a client cannot set one, and now they cannot send one either: `fail-on-unknown-properties`
is on, and `"id"`, `"positionId"`, `"status"` and `"version"` in the open body each answer 400
`malformed-body` with the offending property as the `path`, nothing booked.

### Secrets: absent from every place that was searched

`SecretsIT` starts the application with sentinel values for the Redis password and a Kafka JAAS
config and sends a bearer token that is not a JWT, then searches: eleven actuator and
documentation paths read as three roles, the scrape, and the captured log (33,080 characters in
the recorded run) covering start-up, a bad token, a 403, a malformed body and an unknown path.
Zero occurrences. `env`, `configprops`, `beans`, `heapdump`, `threaddump`, `loggers` and `mappings`
are not exposed at all. `ConfigurationSecretsScanTest` checks every file git would commit: a
secret-named key must be empty, an environment placeholder, a file or URL reference, or
`change-me`; no file may hold a private key. It first failed on two things that were mine to fix:
the scan read the ignored `infra/.env`, which holds this machine's real values and is not a
committed file (it now asks git which files count), and `access.token.claim` is not a secret
(the key matcher now needs the name to end in `token`).

`/actuator/prometheus` needs `ledger-metrics`; health details need `ledger-ops`. The same chain
applies on a separate management port: with `--management.server.port` set, a request with no token
to `/actuator/prometheus` is 401 and one with the metrics token is 200 (`13-actuator-authorization.txt`).

### Dependencies: the scan found something, and it was in the Boot BOM

`infra/security/scan.sh` runs Trivy over the poms and over the 198 jars of `bootstrap`'s
resolved runtime classpath. Before any change it reported nine (package, CVE) pairs with a fix
available: `tomcat-embed-core` 11.0.24 (three CRITICAL, including a security-constraint bypass
and a FORM authentication bypass), `tools.jackson` `jackson-databind` 3.1.5 and
`com.fasterxml.jackson` `jackson-databind` 2.21.5 (three HIGH each; one is CPU denial of service by
unbounded numeric parsing, which a JSON API takes as input). The Boot BOM pins those versions, and
the BOM is imported, not inherited, so overriding its properties does nothing; the fixed versions
(Tomcat 11.0.25, Jackson 3.1.7 and 2.21.7) are imported *ahead* of it, where the first import
wins. After: 0 in both passes, `.trivyignore` empty (`13-dependency-scan.txt`). The Tomcat
CVEs concern container-level security constraints and the DIGEST and FORM authenticators, which
this application does not use (authorization is Spring Security's); they were upgraded anyway
because "does not apply" is a reason to suppress, and an upgrade needed no reason.

## What I rejected

- **Issuing tokens ourselves (Spring Authorization Server), or sessions and cookies.** The ledger
  has no login screen and no users; a provider owns credentials, rotation and revocation, and a
  resource server owns none of them. Sessions would bring CSRF, a store and sticky state back.
- **`requestMatchers("/loans/**").hasRole(...)` as the authorization.** A URL pattern is a second
  copy of the routing table; a handler added under another path is open. Annotations sit on the
  handler, and a build rule says every handler has one.
- **Roles on the use cases.** The batch job and the Kafka consumer call them with no human; a role
  there would need a fake caller, and `application` would import Spring Security.
- **`@PostAuthorize` or a post-filter for ownership.** It runs after the transaction (above), and a
  list filtered after the query has a wrong total and a short page.
- **403 for a foreign id.** It tells the caller the id exists.
- **Postgres row-level security instead of the `WHERE`.** It is the better backstop (a forgotten
  `WHERE` cannot leak) and needs the caller's counterparty on every connection (`SET LOCAL`)
  through a pool; that is a design of its own. Named in the last section as the next step.
- **Auditing by annotation or by an event after commit.** An annotation audits a method call, not
  the transaction's outcome; an after-commit write loses the line if the process dies between the
  commit and the write, which is the dual-write problem stage 10 solved for events.
- **Validating `counterparty` against a pattern as the injection defence.** Validation narrows the
  input and is not the defence; the bind parameter is. `CounterpartyId` accepts any non-blank string
  on purpose, so that the tests hand the real query the real payloads.
- **OWASP Dependency-Check as a Maven plugin.** It wants an NVD API key to be usable and a long first
  download inside the build. Trivy in Docker has neither cost and is a script like `promtool`'s.
- **Scanning only the poms.** Trivy skips a dependency whose version it cannot determine and says so
  with a warning; the runtime classpath cannot be skipped. Both run, and the second is the one that
  counts.
- **A static IP allowlist or a network boundary instead of a role on `/actuator/prometheus`.** The
  boundary is still the right outer layer; the role is what remains true when it is misconfigured.

## What I observed

- **Boot ignores a public key when `issuer-uri` is set.** The decoder becomes OIDC discovery, so
  every test failed with a 401 until the issuer check moved to its own validator bean
  (`ledger.security.issuer`, no default). Discovery would also make the first request depend on a
  second endpoint.
- **An invalid token on a public path is a 401.** `BearerTokenAuthenticationFilter` verifies any
  token it is shown, so a probe request carrying a stale `Authorization` header from a proxy fails
  where the same request with none succeeds (`TokenRejectionIT`).
- **Spring Security's observations broke a stage 12 test.** With them on, a request carries extra
  spans that finish before the server span, and `HttpTraceIT` took the first finished span. They are
  off (`management.observations.enable.spring.security=false`): the outcome of authentication is the
  response code and the audit row, and the test now selects the span of kind `SERVER`.
- **`AccessDeniedException` became a 500.** The stage 7 catch-all handler caught what method
  security threw; `LedgerProblemHandler` now maps it, and `ForbiddenException`, to 403.
- **Jackson's message for an unknown property names the Java class.** The first version of the
  400 handed out `OpenLoanRequest`; it now says "a property this operation does not accept" and
  gives the property as the `path`.
- **`MetricsIT` failed in a full build and passed alone**, the stage 12 failure again: a price
  published at a fixed version is ignored when the shared topic's history holds a newer one. It was
  the one publisher the stage 12 fix missed; it now uses the time as the version.
- **The Keycloak import failed once.** A custom user profile that listed only `username` and the
  claim removed `email`, `firstName` and `lastName`, which the users have. The realm file declares the
  four defaults and `counterparty_id` (editable by admins only: a user must not edit the claim that
  scopes it).
- **The jar scan I first wrote scanned the wrong thing.** `bootstrap-*.jar` is the plain jar
  until stage 14 repackages it, so its zero was meaningless; I noticed because it said "1 jar". The
  scan now copies the resolved runtime classpath (198 jars) and scans that, and a fat jar containing
  `tomcat-embed-core-11.0.24` was shown to be detected when nested.
- **A health check on the application port is 503 in the manual run** with Redis not started, as in
  stage 12's tests: the aggregate includes Redis, readiness does not.

### What a token costs

`13-security-overhead.txt`, microseconds, one client, sequential, 5,000 calls after 2,000 warm-up:

| | mean | p50 | p99 | max |
|---|---:|---:|---:|---:|
| `JwtDecoder.decode` (RS256, 2048-bit, in process) | 144.9 | 134 | 197 | 13,460 |
| `GET /loans`, no token → 401 | 1,261.2 | 1,038 | 4,435 | 22,160 |
| `GET /loans`, valid token → 200 | 2,806.4 | 2,545 | 6,629 | 31,273 |

The verification is about 5% of the 200's median. The 401 is slower than the arithmetic suggests
because it builds a problem body, takes the trace id and writes one INFO log line.

## Where each choice breaks at 100×

Reasoned, not measured, except where a measured number above is used as the input.

- **Token verification is CPU per request.** At 134 µs a decode, one core verifies about 7,400
  tokens a second, and a 100× load makes it the first thing a security review asks about. The
  answer is a short cache keyed by the token (a verified token stays verified until it expires) or
  verification at a gateway; both trade a revocation delay for CPU.
- **Revocation is expiry.** Tokens live 300 seconds (configured in the realm). A fired trader keeps
  their access for up to that long, and nothing on this side can shorten it. At 100× the cost of a
  mistake is the same; the lever is the lifespan, or an introspection call the cache above avoids.
- **100 instances fetch the key set.** The decoder caches it and refetches on an unknown `kid`; a
  key rotation at the provider sends every instance to Keycloak at once, and Keycloak is the
  dependency this chain has made unavailable-means-outage. Stale keys keep working until they are
  refused, so the failure is a login outage, not a data one.
- **The identity provider is the root of trust for the data scope.** `counterparty_id` in a token
  decides whose rows are returned. A mapper misconfigured at the provider is a cross-tenant read that
  no test in this repository can see. Row-level security in Postgres, with the counterparty set per
  transaction, would make the database refuse what the query forgot; it is the next layer, not done.
- **One database account.** A SQL bug in any adapter reads everything; the audit trigger can be
  dropped by an owner. The trail needs a copy somewhere the application cannot reach (an append-only
  bucket or a log pipeline with its own retention), which is a pipeline question for stage 14.
- **The audit table grows by one row per booking, per refusal and per denial.** A caller who hits a
  forbidden endpoint in a loop writes a row each time to a table that cannot be pruned: write
  amplification an attacker controls. It needs a rate limit per caller or a sampled `DENIED`, and the
  stage 11 retention job cannot delete from it by design, so its retention is a partition drop, not a
  `DELETE`.
- **Refusals are audited best effort.** If the database is down, the 422 or 403 is answered and the
  row is lost with a suppressed exception. For a regulator that is a gap; the fix is the outbox the
  rest of the ledger already has, written before the answer is sent.
- **The booked row adds a write to every booking's transaction.** One more insert on the hot write
  path, in the same transaction, on an identity column's sequence; at 100× the bookings it is a
  contention point the trade table does not have. The `(subject_id, id DESC)` index doubles it.
- **Idempotency keys are global.** A key is looked up by its value alone; a second caller who sends
  the same key and the same body gets the first caller's answer. Only traders can book and the body
  must match exactly, so what leaks is the price and the settlement date of a trade the caller
  asked for; with counterparties allowed to book, the key would have to be scoped by caller.
- **404-for-foreign is not constant time.** A foreign id and a missing id take the same code path
  after the query, but the query's cost differs for a hit and a miss in a different way at scale;
  a timing oracle on position ids is possible and is unmeasured. The ids are random UUIDs, which is
  what makes guessing them the weaker attack.
- **Unknown properties are rejected, so a client that adds a field breaks.** A tolerant reader is a
  sound principle for a read; for a write the cost of silently dropping a field the client believed it
  set was judged higher. At 100× the clients, one of them will send something new before the server
  knows it, and the 400 is how that is found.
- **Kafka is plaintext and unauthenticated** on the compose broker, and the application's connection
  to it carries no credentials. Broker authentication and TLS are outside this stage and are named
  here so that "secured" is not read as covering them. The same holds for Postgres and Redis on
  loopback.
- **The scan sees published CVEs with a fix, once.** `--ignore-unfixed` means a vulnerability with no
  fix is invisible to the gate, and the scan runs when someone runs it until stage 14 puts it in a
  pipeline. The two Boot-BOM overrides are debt that must be removed when the BOM catches up: a
  version pinned below the BOM's later release would be a downgrade nobody sees.
- **Secrets in the environment are visible to anyone who can read `/proc` or `docker inspect`.** The
  rule keeps them out of files, logs and endpoints, which is what was tested; a secret manager with
  short-lived credentials is what takes them out of the process environment.

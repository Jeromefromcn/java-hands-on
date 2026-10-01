# Stage 13 — Security · Implementation Plan

Spec: [`docs/specs/13-security.md`](../specs/13-security.md)

One section per commit, in order. Implement a section, tick its checkbox in
the same commit, then commit. `./mvnw clean verify` at the repository root
after every section, run in a throwaway worktree of the committed HEAD.

## Ground rules for every section

- Packages: `application.security` (`Caller`, `Visibility`, `ForbiddenException`,
  `Callers` port, `AuditTrail` port), `adapter.web.security` (chain, token
  conversion, `Callers` implementation), `adapter.persistence.audit`.
- No `org.springframework.security` type in `domain` or `application`
  (`ArchitectureIT`, section 1).
- Pure unit tests end in `Test`; anything needing Docker or a Spring context
  ends in `IT`. Postgres, Redis and Kafka come from the shared test containers.
- Tests never switch security off. They send a token signed by a throwaway
  key (`TestTokens`), and the application verifies it with the matching public
  key set through configuration, exactly the path production uses with a JWK set.
- A test that asserts wrong behaviour is named `reproduces<Failure>`, runs
  against test-only code, and carries `// See docs/decisions/13-security.md`.
- Evidence is written by tests to `<module>/target/evidence/13-*.txt` and
  copied by hand to `docs/evidence/`.
- Synthetic data only; the Keycloak realm's users have synthetic names and
  take their passwords from the environment.

## Decisions made while planning

Each is carried to the decision record.

1. **The test key is configuration, not a bean override.** The resource server
   is configured with `public-key-location` in tests and `jwk-set-uri` in the
   local and prod profiles; the application code has no test branch.
2. **Controllers carry `@PreAuthorize`, use cases do not.** Roles describe the
   HTTP channel; the batch job and the Kafka consumer call the same use cases
   with no human and must not need a role.
3. **Read use cases take a `Visibility` argument; booking use cases read the
   actor from an ambient `Callers` port.** Data scope must be visible in a
   signature so a new read cannot forget it; the actor of a booking is only
   metadata for the audit row, and threading it through `OpenPositionCommand`
   would change every test of stages 6-11.
4. **A rejected booking is audited by `IdempotentBooking`**, the one place that
   sees the failure after the booking's transaction has rolled back.
5. **The audit row of a denial is written from the problem handler**, in its
   own transaction.
6. **`ledger-counterparty` without a `counterparty_id` claim has an empty
   visibility.** Fail closed.

## Review focus

1. Every handler states who may call it (section 3).
2. No foreign row is distinguishable from a missing one (section 4).
3. The audit row is in the booking's transaction, the refusal's is not (section 6).
4. No caller input is concatenated into SQL (section 7).
5. No secret value in an exposed endpoint, a log or a committed file (section 8).

---

## [x] 1 · Resource server and the caller

**Change**
- `adapter-web` POM: `spring-boot-starter-security`,
  `spring-boot-starter-security-oauth2-resource-server`; test scope
  `spring-boot-starter-security-test`.
- `application.security`: `Role` (enum of the five), `Caller`, `Visibility`,
  `Callers` (ambient port, `NONE` is the system caller), `ForbiddenException`.
- `adapter.web.security`: `SecurityConfiguration` (stateless chain, probes
  public, rest authenticated, CSRF/session/form/basic off, issuer and
  audience validators, `JwtAuthenticationConverter` reading
  `realm_access.roles` into `ROLE_*`), `JwtCaller` (builds a `Caller` from
  the `Jwt`; implements `Callers`), problem+json `AuthenticationEntryPoint`
  and `AccessDeniedHandler` carrying the trace id.
- `bootstrap`: `application.yml` gets the resource-server properties from the
  environment; `application-test.yml` points at the test public key.
- Tests: `TestTokens` (RSA key, builders for each role and for broken tokens),
  `LedgerApp` starts with the test key, every existing HTTP test sends the
  trader/ops token. `WebTestApplication`/`LayerOrderIT` get a permit-all test chain.
- `ArchitectureIT`: no `org.springframework.security` in `domain` or `application`.

**Tests**
- `CallerTest`: roles, counterparty claim, `visibility()` for each combination
  including counterparty role without claim (empty).
- Existing suite green with tokens.

**Acceptance:** the application rejects a request with no token and serves one
with a valid token; `./mvnw clean verify` green.

## [x] 2 · The filter chain, and what a bad token gets

**Change**
- A test prints `FilterChainProxy`'s filters in order and the position of
  `TraceIdFilter` and of Boot's observation filter relative to the security
  chain; evidence `13-filter-chain.txt`.
- 401 problems: missing, malformed, expired, wrong issuer, wrong audience, and
  a token signed by another key.

**Tests**
- `FilterChainIT`, `TokenRejectionIT` (each case: status, `application/problem+json`,
  `X-Trace-Id`, `WWW-Authenticate`, no detail naming the reason).

## [x] 3 · Who may call what

**Change**
- `@PreAuthorize` on every handler of `LoanReadController`,
  `LoanWriteController`, `QuoteController`, `QuotesController`.
- `LedgerOpenApi`: bearer security scheme, `401`/`403` responses.
- `ArchitectureIT`: every public method of a `@RestController` carrying a
  request mapping has `@PreAuthorize`.

**Tests**
- `AuthorizationMatrixIT`: each endpoint x each role (and no role); evidence
  `13-authorization-matrix.txt` is the table.

## [x] 4 · Whose rows

**Change**
- `Visibility` through `ListPositions`, `ShowPosition`, `ListAccruals`; the
  three position queries (JDBC, JPA, keyset) filter on an optional counterparty
  in SQL. `GET /loans?counterparty=` for staff.
- A foreign position or accrual range is `UnknownReferenceException`; a list
  filter naming another counterparty is `ForbiddenException` (`403`).

**Tests**
- `reproducesPositionOfAnotherCounterpartyReadByIdChange` (test-only controller
  that authenticates and does not scope).
- `CounterpartyScopeIT`: B gets A's position `404` with the body of a missing
  id; list total counts only B's; accruals likewise; staff see all.
- `VisibilityUseCaseTest` on the use cases with fake queries.

## [x] 5 · Authorization before the transaction

**Change**
- None in product code if the advisor orders are as derived; otherwise the fix.

**Tests**
- `MethodSecurityOrderIT`: a probe bean with `@PreAuthorize("@probe.check()")`
  and `@Transactional`: denied call, transaction was never open; permitted
  call, transaction open; connection count unchanged by a denied call.
  Evidence `13-security-vs-transaction.txt`.

## [ ] 6 · Audit

**Change**
- `V17__audit_event.sql` (table, append-only trigger).
- `AuditTrail` port (`record` joins the caller's transaction,
  `recordIndependent` starts its own), JDBC implementation.
- `OpenPosition`, `ReturnPosition` record `BOOKED` rows; `IdempotentBooking`
  records `REJECTED`; the problem handler records `DENIED`.
- `GET /audit?subject=` for `ledger-auditor`, newest first, capped.

**Tests**
- `AuditIT`: committed open has its row with the token subject; credit-limit
  rejection leaves no `BOOKED` and one `REJECTED`; a `403` leaves `DENIED`;
  update/delete refused; no amount in any column.
- `reproducesAuditRowSurvivingRolledBackBooking`: a test-only booking that
  writes the audit row in a separate transaction, then rolls back.

## [ ] 7 · Injection and mass assignment

**Change**
- Unknown JSON properties rejected (`400`).

**Tests**
- `reproducesSqlInjectionByConcatenation` (test-only concatenating query:
  leak, stacked statement), then the real `?counterparty=` filter with the same
  payloads.
- `reproducesMassAssignmentOfIdAndStatus` (test-only bean-bound entity), then
  the real open with `id`/`status`/`positionId` in the body.

## [ ] 8 · Actuator and secrets

**Change**
- Health details `when-authorized` for `ledger-ops`; `/actuator/prometheus`
  requires `ledger-metrics`.
- `infra/prometheus/prometheus.yml` scrapes with OAuth2 client credentials.

**Tests**
- `ActuatorAuthorizationIT`; `SecretsIT` (sentinels across actuator bodies and
  captured logs, including a failing request); `ConfigurationSecretsScanTest`
  (no literal secret in any committed configuration or Compose file).

## [ ] 9 · Keycloak

**Change**
- Compose service, realm import with env-substituted secrets, synthetic users
  (one per role, two counterparties), `counterparty_id` mapper, audience mapper.
- Manual run: token from Keycloak, each role against the running application;
  evidence `13-manual-run.txt`.

## [ ] 10 · Dependency scan

**Change**
- `infra/security/scan.sh` (Trivy in Docker), `.trivyignore`.
- Run it; fix or justify every finding.

## [ ] 11 · Decision record

**Change**
- `docs/decisions/13-security.md`, ROADMAP ticks, plan notes.

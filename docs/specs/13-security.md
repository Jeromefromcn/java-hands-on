# Stage 13 — Security

## Problem

Until now every endpoint answers anyone who can reach the port. The ledger
holds positions of named counterparties, books fee-bearing trades and exposes
a metrics endpoint that describes the business; none of that has an owner, a
role or a record of who did what. Stage 12 deferred the actuator and health
details to this stage on purpose: security rewrites the request chain, and a
chain that was still moving would have put a layer of noise on every stage 7-12
experiment.

Stage 13 puts identity in front of the API and then drives each failure a
secured API usually has to a reproduced, fixed and asserted result: a caller
who reads another counterparty's position by changing an id, an audit row that
lies after a rollback, a SQL string that is a query, a request body that is an
entity, a secret that reaches a log.

Five facts shape the stage.

- **Authentication says who; it decides nothing about what.** A valid token for
  a counterparty is as valid as one for a trader. The first bug of any API with
  login is an endpoint that checks the token and nothing else, so the rule that
  every endpoint states who may call it is held by a test, not by review.
- **Authorization has two questions at two layers.** "May this role call this
  operation" is about the channel and is answered at the edge, before a
  transaction or a connection exists. "May this caller see this *row*" is about
  data and is answered where the data is selected, in the `WHERE`, so that a
  page, a total and a 404 cannot leak what a filter after the fact would.
- **A refusal must not be an oracle.** A position that exists and is not yours
  answers exactly what a position that does not exist answers.
- **An audit row is a statement about the ledger.** One that says "position
  opened" while the position was rolled back is false; one missing for a
  refused attempt is a gap an investigation falls into. The two cases are
  different transactions on purpose.
- **The money invariant meets the audit table.** The audit trail names a
  subject and an action; it carries no amount. The amount is on the trade the
  subject id points to.

## Settled design decisions

The decision record argues each; the spec states what they commit the code to.

1. **Stateless JWT resource server.** Spring Security's
   `oauth2-resource-server`; no session, no CSRF token (there is no cookie to
   forward, so there is no forged request to defend against), no form login, no
   basic auth. Keycloak (Compose) issues the tokens for manual work; automated
   tests sign their own with a throwaway RSA key and never depend on Compose.
   Issuer and audience are validated; the key set comes from configuration
   (`jwk-set-uri` from the environment).
2. **Roles come from the token, one set, five of them.** `ledger-trader`
   (open, return, read all), `ledger-ops` (read all, quotes, health details),
   `ledger-counterparty` (read *own* positions and accruals; needs a
   `counterparty_id` claim, and without one is refused everything),
   `ledger-auditor` (read the audit trail), `ledger-metrics` (scrape
   `/actuator/prometheus`). A token with no known role can do nothing.
3. **Three layers, each with one job.**
   - *Filter chain* (`adapter-web`): authenticate; public only the liveness and
     readiness probes; everything else needs a valid token. 401 and 403 are
     RFC 9457 problems with the request's trace id, like every other error.
   - *Method security* (`adapter-web`): `@PreAuthorize` on every controller
     handler names the roles that may call it. `ArchitectureIT` fails the
     build for a handler with no `@PreAuthorize`.
   - *Data scope* (`application` + `adapter-persistence`): the read use cases
     take a `Visibility` derived from the caller (`ALL` or `ONLY(counterparty)`)
     and the queries filter on it in SQL. A row outside the scope is not found.
4. **`application` and `domain` stay free of Spring Security.** `application`
   owns `Caller` (subject, roles, optional counterparty) and `Visibility`, pure
   and unit-tested; `adapter-web` builds a `Caller` from the `Jwt`. `ArchitectureIT`
   gets the rule.
5. **`@PreAuthorize` runs before `@Transactional`.** The method-security
   advisor's order is earlier than the transaction advisor's, so a refused call
   never takes a connection. Derived on paper, then proved by a probe bean
   carrying both annotations, and the reverse is shown to be the failure it
   would be.
6. **Audit table `audit_event`, append-only.** One row per booked open and
   return (`OPEN`, `RETURN`, outcome `BOOKED`), written in the booking's own
   transaction so it commits and rolls back with the trade. One row per
   *refused* attempt (a business rule that rolled the booking back, or an
   authorization denial), written in a separate transaction so it survives.
   The actor is the token's subject. A trigger rejects `UPDATE` and `DELETE`.
7. **Foreign ids are 404, not 403.** A counterparty caller asking for a
   position of another counterparty gets the stage 7 `404` for an unknown
   position. Asking the list endpoint for a counterparty that is not its own
   is a `403`: nothing is revealed by refusing a request that names someone
   else.
8. **Injection and binding.** Every query that takes caller input binds it as a
   parameter. A listing filter `?counterparty=` exists and is the real
   injection surface. Request bodies bind to dedicated request records, never
   to entities, and unknown properties are rejected (`400`) rather than ignored,
   so `{"id": "..."}` in an open request is an error the client sees.
9. **Secrets are environment-only and proved absent.** A scan of every
   configuration file and Compose file for literal secrets; a test that starts
   the application with sentinel secret values and searches every exposed
   actuator endpoint and the captured logs. Health details are shown only to
   `ledger-ops`.
10. **Dependency scanning is a script, not a plugin.** Trivy, run in Docker over
    the repository (`infra/security/scan.sh`), failing on HIGH and CRITICAL
    that have a fix, with every suppression in `.trivyignore` carrying a
    reason. Wiring it into a pipeline is stage 14's.

## Scope

In: the plan's sections. Product code: the security chain and token
conversion, `Caller`/`Visibility`, role and data authorization, the audit
trail and its read endpoint, the listing filter, unknown-property rejection,
actuator authorization, and `infra/` for Keycloak and the scan script.

Out: user management and token issuance (Keycloak's), refresh and logout
flows, mTLS, rate limiting per caller (stage 12's limiter is per source),
encryption at rest, field-level encryption, a WAF, a CI pipeline (stage 14),
and application-level audit of reads.

Broken versions go where `CLAUDE.md` puts them:
`reproducesPositionOfAnotherCounterpartyReadByIdChange`,
`reproducesSqlInjectionByConcatenation`,
`reproducesMassAssignmentOfIdAndStatus` and
`reproducesAuditRowSurvivingRolledBackBooking` are tests that assert the wrong
behaviour against test-only code and point at the decision record.

## Acceptance

1. The full Spring Security filter list is printed, with `TraceIdFilter`'s
   place in it; a request with no token, a malformed token, an expired token
   and a token of the wrong issuer or audience is `401` as a problem carrying
   `X-Trace-Id`.
2. A table-driven test calls every endpoint as every role and compares to the
   authorization matrix in the decision record; `ArchitectureIT` fails when a
   controller handler has no `@PreAuthorize`.
3. The id-change over-read is reproduced against a test-only authenticated-only
   endpoint (counterparty B reads A's position), and the real endpoint answers
   B `404` for A's position, the same body as for an id that does not exist;
   the list is filtered in SQL, its total counts only B's rows.
4. A probe bean with `@PreAuthorize` and `@Transactional` shows the denial
   happening with no transaction open and a permitted call with one open.
5. An open that commits has an audit row naming the token subject; an open
   that rolls back (credit limit) has no `BOOKED` row and one `REJECTED` row;
   a `403` has a `DENIED` row; `UPDATE`/`DELETE` on the table are refused.
6. `x' OR '1'='1' --` returns every row through the concatenated test-only
   query and none through the real filter; a stacked statement is shown to
   run through concatenation and not through the real filter. A body carrying
   `id`/`status` binds into the test-only entity and is a `400` on the real
   endpoint; the position id is always server-generated.
7. No sentinel secret appears in any actuator body or in the captured logs;
   no literal secret is in any committed configuration file;
   `/actuator/health` details need `ledger-ops`, `/actuator/prometheus` needs
   `ledger-metrics`.
8. A token issued by the Compose Keycloak, not a test key, is accepted by the
   running application (manual run, evidence committed).
9. The scan script runs and its result, including any suppression and why, is
   in the decision record.
10. `./mvnw clean verify` is green; the existing stage 7-12 tests pass with a
    token, none with security switched off.
11. The decision record contains only measured numbers and the 100x section.

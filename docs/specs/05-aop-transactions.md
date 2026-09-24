# Stage 05 — AOP and Transactions

## Problem

Stage 4 ended with a hand-rolled JDK-proxy `BeanPostProcessor`. Stage 5
replaces that mental model with Spring's own: the auto-proxy creator,
`@Transactional`, and `@Aspect` advice. It then drives each of the
transaction failure modes the roadmap lists — self-invocation, propagation,
self-deadlock, isolation, checked-exception commit, async visibility, advice
ordering — to an observable, asserted failure on a real Postgres.

Two facts shape the stage:

- **Half the exercises need a real database.** Connection counts under
  `REQUIRES_NEW`, a row lock held across two transactions, Postgres's
  behaviour at `READ COMMITTED` vs `REPEATABLE READ` — none of these exist
  without one, and H2's locking and isolation semantics differ from
  Postgres's. But persistence proper (Flyway, JPA, `@Version`,
  `adapter-persistence`) is stage 6. So this stage uses the thinnest layer
  that still hits a real Postgres: Testcontainers, `JdbcClient`, a
  hand-written `schema.sql`, all at test scope.
- **There is still no application-layer use case to put `@Transactional`
  on.** Stage 4's reasoning holds: the five use cases arrive once stage 6
  can persist them. Everything transactional in this stage is a test-only
  demo, with ledger-flavoured tables so the scenarios read as the domain's
  real problems (a return racing a return, a daily accrual batch with one
  bad position) rather than as `foo`/`bar`.

## Scope

### Module topology and dependencies

Only `bootstrap` changes. It gains, **all at test scope**, no `<version>`
(every one is governed by the `spring-boot-dependencies` BOM):

- `spring-boot-starter-jdbc` — HikariCP, `JdbcClient`, `spring-tx`.
- The Spring Boot AspectJ starter — `@Aspect` support. The exact artifact
  id for Spring Boot 4 is confirmed in the plan, not assumed here.
- `org.postgresql:postgresql`.
- `spring-boot-testcontainers` and Testcontainers' Postgres module.

`SlLedgerApplication`'s runtime classpath is unchanged: the real application
gets no `DataSource`, no transaction manager, and no aspect.

All demo code lives under `com.jerome.slledger.bootstrap.demo.tx`
(`bootstrap/src/test/java`). One Postgres container is shared by the whole
test JVM (a static singleton, started once), and each test resets the tables
it touches, so container start-up is paid once rather than per test class.
Stage-5 tests start their contexts from a dedicated test-only
`@SpringBootConfiguration`, never from `SlLedgerApplication`.

### Schema

`bootstrap/src/test/resources/demo/tx/schema.sql`, four tables, nothing a
demo does not use:

- `loan_position(id, outstanding_quantity bigint, version bigint)`
- `loan_trade(id, position_id, quantity)`
- `accrual_entry(position_id, business_date, amount numeric(19,6), currency char(3))`
- `idempotency_key(key primary key)`

### Existing tests the new classpath would break

Two consequences of putting JDBC and `@Transactional` demos on the test
classpath, both fixed in this stage rather than worked around:

- **`ArchitectureIT` rule 3** ("`@Transactional` lives only in
  `application`") imports every class on the failsafe classpath, test
  classes included, so the demos would violate it. The importer gains
  `ImportOption.DoNotIncludeTests`: the rule governs production code, and
  test-only demos are exactly where the roadmap wants reproductions to live.
  Dodging the rule by annotating only methods instead of classes is
  rejected; it would pass by exploiting a gap in the rule, not by being
  compliant.
- **Stage 4's full-application tests** (`ConfigurationPrecedenceTest`,
  `CircularDependencyIT`, wherever they boot `SlLedgerApplication`) would
  now trigger `DataSourceAutoConfiguration` and fail with no URL. They
  exclude it explicitly, with a comment saying why — the real application
  has no datasource; only the test classpath does.

### The money invariant at its first persisted column

This is the first stage where a `Money` crosses into a column type. The
daily-accrual demo (exercises 3 and 9) computes each entry with `domain`'s
`FlatRate` and `Money`, writes `amount numeric(19,6)` plus `currency
char(3)`, and a round-trip test asserts the `Money` read back equals the one
written — amount, scale, and currency — so the invariant is checked, not
assumed, at the moment a new component touches it.

### The ten exercises

Each exercise is a test class under `demo.tx`. Tests asserting the wrong
behaviour are named `reproduces<Failure>` and carry a comment pointing at
`docs/decisions/05-aop-transactions.md`.

1. **Proxy mechanism.** One service behind an interface, one without.
   `ApplicationContextRunner` with `spring.aop.proxy-target-class` `true` and
   `false`; `AopUtils.isJdkDynamicProxy` / `isCglibProxy` assert which proxy
   each gets. Prediction: Boot defaults to `true`, so even the interface-backed
   service is CGLIB-proxied until the property is flipped. A further
   assertion: a `final` method on a CGLIB-proxied bean bypasses the advice
   silently — the same root cause as exercise 2, reached from a different
   direction.

2. **Self-invocation.** `returnAll()` calls `this.recordReturn()`, which is
   `@Transactional`, decrements `outstanding_quantity`, then throws before
   inserting the `loan_trade`. `reproducesSelfInvocationPartialWrite` asserts
   the decrement is committed and the trade absent. Three fixes, each its own
   test asserting the whole unit rolls back: split into a second bean;
   `AopContext.currentProxy()` (with `exposeProxy = true`); a `@Lazy`
   self-injection. The decision record states each fix's cost.

3. **`REQUIRES_NEW` in the daily accrual.** An outer `REQUIRED` transaction
   records a batch-run row; each position accrues in its own `REQUIRES_NEW`
   transaction; the third position fails deliberately. Asserts the other
   entries and the batch-run row are committed and the failing position has
   no entry. Inside the inner transaction, `HikariPoolMXBean
   .getActiveConnections()` is read and asserted to be 2.
   `reproducesRequiresNewPoolStarvation` shrinks the pool to 1 and asserts the
   inner transaction fails with `SQLTransientConnectionException` after
   `connectionTimeout` — the connection-doubling risk, made concrete.

4. **`REQUIRES_NEW` self-deadlock.** The outer transaction updates a
   `loan_position` row (holding its row lock); the inner `REQUIRES_NEW`
   updates the same row and waits on the outer, which waits on the inner.
   Postgres cannot detect it — half the wait-for cycle is a Java thread
   blocked on a method return, invisible to the database. The inner
   transaction sets `SET LOCAL lock_timeout` so the test terminates.
   `reproducesRequiresNewSelfDeadlock` asserts SQLState `55P03`. While
   blocked, a thread dump and a `pg_locks` / `pg_stat_activity` snapshot are
   captured to `docs/evidence/05-requires-new-self-deadlock.txt`.

5. **Isolation and lost update.** Two threads each run read balance →
   compute → write back for a return, interleaved deterministically with
   latches.
   - `reproducesLostUpdateUnderReadCommitted`: at `READ COMMITTED` the final
     balance reflects only one of the two returns.
   - At `REPEATABLE READ`, Postgres fails the second with SQLState `40001`.
     Asserted as such: not a lost update, but a failure handed back to the
     application, which must retry.
   - Fix: optimistic locking on the hand-written `version` column —
     `UPDATE … WHERE id = ? AND version = ?`, zero rows means conflict,
     retry. Asserts both returns land. The retry loop sits **outside** the
     transaction, and a test shows why retrying inside it cannot succeed.

6. **`rollbackFor`.** A test-local checked exception thrown from a
   `@Transactional` method. `reproducesCommitOnCheckedException` asserts the
   row is committed despite the exception; with `rollbackFor` it is rolled
   back.

7. **Transaction + `@Async`.** A `@Transactional` method inserts a trade,
   then an `@Async` method on another bean reads it by id. The outer method
   waits on the async result while still inside its transaction, so the read
   is guaranteed to happen before commit. `reproducesAsyncReadBeforeCommit`
   asserts the trade is not visible.

8. **`afterCommit`.** The same flow, with the async read triggered from a
   `TransactionSynchronizationManager.registerSynchronization` `afterCommit`
   callback. Asserts the read sees the trade; and that when the outer
   transaction rolls back, the callback never fires. The decision record
   notes that `afterCommit` is not crash-safe — a crash between commit and
   the callback loses the event, which is stage 10's Outbox problem — and
   that `@TransactionalEventListener` is its declarative equivalent.

9. **`TransactionTemplate`.** Exercise 3's per-position `REQUIRES_NEW`
   rewritten as a `TransactionTemplate` inside the loop. Asserts the same
   outcome as exercise 3. The transaction boundary becomes finer than the
   method boundary, and the second bean exercise 3 needed to dodge
   self-invocation is no longer required.

10. **`@Idempotent` × `@Transactional` ordering.** A custom `@Idempotent`
    `@Around` aspect inserts the request's key into `idempotency_key`; a
    duplicate throws `DuplicateRequestException`. Both orderings are built,
    via `@Order` on the aspect and `@EnableTransactionManagement(order = …)`,
    and each runs two scenarios: two concurrent requests with the same key,
    and a business failure followed by a retry with the same key.
    Prediction: only the check **inside** the transaction, leaning on the
    primary-key constraint, gives both exactly-once execution and a
    retryable failure — outside, the key is written in autocommit and
    survives the business rollback, so the retry is wrongly rejected. The
    tests verify the prediction; if it is wrong, the decision record says so,
    as stage 4's did for the lifecycle order.

### Evidence

`docs/evidence/05-*.txt`, text only, measured on this machine: proxy class
names per configuration, active-connection counts, lock-wait duration and
the self-deadlock stack plus `pg_locks` snapshot, the lost-update before and
after balances.

## Out of scope

- JPA, `@Version`, Flyway, `adapter-persistence`. Stage 6.
- Any application-layer use case or `@Transactional` class under `main`.
- The Outbox pattern. Stage 10; exercise 8 only names the gap.
- `@Retryable` / Spring Retry. The hand-written retry loop is enough to show
  where retry must sit.
- A running Postgres from `infra/` Compose. Tests use Testcontainers only.

## Constraints

- `domain` gets no new code and no new dependency; `FlatRate` and `Money`
  are consumed as they are.
- `./mvnw verify` at the repository root stays green after every section.
- Every reproduced failure is asserted by its specific exception type or
  SQLState, not by `assertThrows(Exception.class, …)`.
- Every concurrency scenario (exercises 4, 5, 10) is made deterministic with
  latches or barriers, never with `Thread.sleep` as the ordering mechanism.
- Every blocking scenario has a bound (`lock_timeout`, pool
  `connectionTimeout`, test timeout) — no test can hang the build.
- All data is synthetic.

## Acceptance criteria

1. `SlLedgerApplication`'s runtime dependencies are unchanged
   (`./mvnw -pl bootstrap dependency:tree -Dscope=runtime` shows no JDBC,
   AspectJ, or Postgres artifact).
2. `ArchitectureIT` excludes test classes, and all three rules pass.
3. Stage 4's tests pass, excluding `DataSourceAutoConfiguration` where they
   boot `SlLedgerApplication`.
4. The accrual `Money` round-trip test passes: amount, scale, and currency
   are unchanged through `numeric(19,6)` + `char(3)`.
5. Each of the ten exercises has its test class, with the assertions stated
   above, green.
6. The evidence files listed above exist and hold measured output.
7. `./mvnw verify` at the repository root is green.

## Done when

The four `CLAUDE.md` deliverables exist: this spec, the plan, the code with
every criterion above verified, and `docs/decisions/05-aop-transactions.md`,
which must carry: why self-invocation bypasses the proxy, argued from how the
proxy is built rather than restating that the call "doesn't go through the
proxy"; the cost of each of the three fixes; the measured connection count
under `REQUIRES_NEW`; why the self-deadlock is invisible to Postgres; the
real reason finance code wants stronger isolation or optimistic locking;
where retry must sit; when `TransactionTemplate` beats the annotation; and the
verified answer to where the idempotency check belongs.

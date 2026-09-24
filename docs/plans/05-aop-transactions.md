# Stage 05 — AOP and Transactions · Implementation Plan

Spec: [`docs/specs/05-aop-transactions.md`](../specs/05-aop-transactions.md)

One section per commit, in order. Implement a section, tick its checkbox in
the same commit, then stop for review. `./mvnw verify` at the repository root
after every section.

## Ground rules for every section

- Only `bootstrap` changes, plus `docs/evidence/05-*.txt`. `domain` is
  consumed, never modified.
- Every new dependency is **test scope**, no `<version>` (all governed by the
  `spring-boot-dependencies` BOM, which imports `testcontainers-bom`).
- All demo code lives under `com.jerome.slledger.bootstrap.demo.tx`
  (`bootstrap/src/test/java`), never under `main`.
- Test classes that need Postgres end in `IT` and run under failsafe; the one
  that doesn't (section 3) ends in `Test`. Docker-backed tests stay out of
  the surefire inner loop, the same split stage 3 made for `ArchitectureIT`.
- Every reproduced failure asserts its specific exception type, cause, or
  SQLState — never `assertThrows(Exception.class, …)`.
- Concurrency ordering uses `CountDownLatch` / `CyclicBarrier`, never
  `Thread.sleep`. Every test that can block carries `@Timeout(30)` on top of
  its own `lock_timeout` / `connectionTimeout` bound.
- A test that asserts the wrong behaviour is named `reproduces<Failure>` and
  carries `// See docs/decisions/05-aop-transactions.md`.
- Evidence is written by the test to `bootstrap/target/evidence/05-*.txt`,
  then copied to `docs/evidence/` by hand as part of the section — a test
  never writes into `docs/`.

## Review focus

Failure modes the spec implies but no exercise is *about*; each is pinned by
a test in the section named.

1. **State leaking between tests through the shared container** — every
   context re-runs `schema.sql`, which drops and recreates its tables
   (section 1 asserts a second context starts empty).
2. **`SET LOCAL lock_timeout` leaking onto a pooled connection** — after the
   self-deadlock test, a fresh transaction's `SHOW lock_timeout` returns the
   server default (section 6).
3. **Connections left checked out after a failed inner transaction** — the
   pool's active count is back to 0 after each `REQUIRES_NEW` scenario,
   including the failing position and the starvation case (section 5).
4. **Currencies whose minor units are not 2** — the `Money` round-trip covers
   `JPY` (0) and `BHD` (3) as well as `HKD` (2) (section 2).
5. **A retried request after a business rollback** — covered under both
   advice orderings, not only the "correct" one (section 12).

---

## [x] 1 · Test-scope JDBC, a shared Postgres, and the two fixes it forces

`bootstrap/pom.xml` gains, all `<scope>test</scope>`:
`org.springframework.boot:spring-boot-starter-jdbc`,
`org.springframework.boot:spring-boot-starter-aspectj` (Boot 4's name;
`spring-boot-starter-aop` is the Boot 3 artifact),
`org.postgresql:postgresql`, `org.testcontainers:testcontainers-postgresql`
(Testcontainers 2.x artifact name; class
`org.testcontainers.postgresql.PostgreSQLContainer`).

**Deviation from the spec:** `spring-boot-testcontainers` is *not* added.
`@ServiceConnection` binds a container to one context, and a container bean
is stopped when that context closes; this stage starts many short-lived
contexts against one container. A static singleton plus plain
`spring.datasource.*` properties does the same job with no extra dependency.

`demo.tx.support`:

```java
/** One Postgres per test JVM, started on first use; Ryuk removes it at JVM exit. */
public final class SharedPostgres {
  private static final PostgreSQLContainer CONTAINER =
      new PostgreSQLContainer("postgres:17-alpine");

  public static synchronized String[] datasourceProperties() {
    if (!CONTAINER.isRunning()) CONTAINER.start();
    return new String[] {
      "spring.datasource.url=" + CONTAINER.getJdbcUrl(),
      "spring.datasource.username=" + CONTAINER.getUsername(),
      "spring.datasource.password=" + CONTAINER.getPassword(),
    };
  }
}

/** Auto-configuration on, component scan off: each test names exactly the beans it loads. */
@SpringBootConfiguration
@EnableAutoConfiguration
public class TxDemoApplication {}

public final class TxDemo {
  /** Starts a non-web context of TxDemoApplication + beans against SharedPostgres, schema reset. */
  public static ConfigurableApplicationContext start(Class<?>... beans);
  public static ConfigurableApplicationContext start(String[] extraProperties, Class<?>... beans);
}
```

`TxDemo.start` adds `spring.sql.init.mode=always` and
`spring.sql.init.schema-locations=classpath:demo/tx/schema.sql`.

`bootstrap/src/test/resources/demo/tx/schema.sql` — `DROP TABLE IF EXISTS`
then `CREATE TABLE` for:

- `loan_position(id text primary key, outstanding_quantity bigint not null, version bigint not null default 0)`
- `loan_trade(id bigserial primary key, position_id text not null, quantity bigint not null)`
- `accrual_run(id bigserial primary key, business_date date not null)`
- `accrual_entry(position_id text not null, business_date date not null, amount numeric(19,6) not null, currency char(3) not null, primary key (position_id, business_date))`
- `idempotency_key(key text primary key)`

**Deviation from the spec:** a fifth table, `accrual_run`. Exercise 3 says
the outer transaction "records a batch-run row", which the spec's
four-table list has nowhere to put. No `CHECK` on `outstanding_quantity`:
section 5's failing position is rejected by `domain`, not by the database.

`TxDemoRows` (same package) — the `JdbcClient` helpers every later section
uses: `insertPosition(String id, long quantity)`, `outstanding(String id)`,
`version(String id)`, `tradeCount(String positionId)`,
`insertTrade(String positionId, long quantity) → long id`,
`tradeExists(long id)`, `accrualRunCount()`.

**The two fixes the new classpath forces:**

- `ArchitectureIT`: `new ClassFileImporter().withImportOption(new
  ImportOption.DoNotIncludeTests())`. Class Javadoc updated: rule 3 governs
  production code; test-only demos are where reproductions belong.
- `ConfigurationPrecedenceTest.start`: `.properties("spring.autoconfigure
  .exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")`
  with a comment — the JDBC starter is on the test classpath only, and the
  real application has no datasource. (`CircularDependencyIT` boots no
  auto-configuration and needs nothing.)

`demo/tx/TxDemoInfrastructureIT`:

- a context starts, `TxDemoRows.insertPosition("P1", 100)` then
  `outstanding("P1") == 100`;
- a second context started afterwards sees zero rows in `loan_position`
  (Review focus 1).

**Acceptance** — both tests pass. `./mvnw -pl bootstrap dependency:tree
-Dscope=runtime` shows no `spring-jdbc`, `HikariCP`, `aspectjweaver`,
`postgresql` or `testcontainers` artifact; output saved to
`docs/evidence/05-runtime-classpath.txt`. `ArchitectureIT` and all stage-4
tests green. `./mvnw verify` green.

---

## [x] 2 · The money invariant at its first column

`demo.tx.support.AccrualEntryRows`:

- `insert(AccrualEntry entry)` — writes `entry.fee().amount()` into
  `numeric(19,6)` and `entry.fee().currency().getCurrencyCode()` into
  `char(3)`.
- `find(LoanPositionId id, LocalDate on) → Optional<AccrualEntry>` —
  rebuilds `Money.of(rs.getBigDecimal("amount"), Currency.getInstance(rs.getString("currency").trim()))`.
- `rawAmount(LoanPositionId id, LocalDate on) → BigDecimal` — the column
  value as the driver returns it, before `Money` sees it.

`demo/tx/AccrualMoneyRoundTripIT`, parameterised over `HKD`, `JPY`, `BHD`:
`AccrualEntry.accrue(id, Money.of(<value>, ccy), new FlatRate(Rate.of("0.0525")), date)`,
insert, find.

- `find(...)` equals the original entry (record equality, so `BigDecimal`
  scale included).
- `rawAmount(...).scale() == 6` — asserted, and the reason stated in a
  comment: the column pads to 6, equality holds only because `Money`'s
  constructor re-scales to the currency's minor units on the way back in.
  The column type does not carry the invariant; `Money` does.
- `BHD`'s 3 minor digits survive (Review focus 4).

**Acceptance** — three parameterised cases pass. `./mvnw verify` green.

---

## [x] 3 · Proxy mechanism: JDK vs CGLIB, and the `final` method

`demo.tx.proxy` (no database):

- `PositionQueries` (interface) + `InterfacePositionQueries implements PositionQueries`.
- `ConcretePositionQueries` (no interface) with an ordinary method
  `describe()` and a `final` method `describeFinal()`.
- `@Aspect CountingAspect` — `@Around("within(com.jerome.slledger.bootstrap.demo.tx.proxy..*) && !within(CountingAspect)")`,
  increments an `AtomicInteger` exposed by `count()`.

`ProxyMechanismTest`, via `ApplicationContextRunner` with
`AutoConfigurations.of(AopAutoConfiguration.class)`
(`org.springframework.boot.autoconfigure.aop`):

- default properties: the interface-backed bean is CGLIB
  (`AopUtils.isCglibProxy`); the concrete one is CGLIB.
- `spring.aop.proxy-target-class=false`: the interface-backed bean is a JDK
  proxy (`AopUtils.isJdkDynamicProxy`), only retrievable by
  `PositionQueries`, not by `InterfacePositionQueries` (asserted:
  `NoSuchBeanDefinitionException`); the concrete one is still CGLIB — no
  interface, no choice.
- `describe()` on the CGLIB bean bumps the count; `describeFinal()` does not,
  and throws no error either.

Each case logs `bean.getClass().getName()`; the four names go to
`docs/evidence/05-proxy-classes.txt`.

**Acceptance** — all assertions pass; the predicted Boot default is confirmed
or the test and the decision record say it was falsified. `./mvnw verify` green.

---

## [x] 4 · Self-invocation and its three fixes

`demo.tx.selfinvocation`. Every variant does the same unit of work on
position `P1` (outstanding 100), returning 30:

1. `UPDATE loan_position SET outstanding_quantity = outstanding_quantity - 30`,
2. throw `IllegalStateException("trade booking failed")` before
3. `INSERT INTO loan_trade`.

Beans:

- `SelfInvokingReturnService` — `returnAll()` (no annotation) calls
  `this.recordReturn()` (`@Transactional`).
- `SplitReturnService` + `ReturnRecorder` — `recordReturn()` moved to the
  second bean.
- `ProxyLookupReturnService` — `((ProxyLookupReturnService) AopContext.currentProxy()).recordReturn()`;
  its test context adds a `@EnableAspectJAutoProxy(exposeProxy = true)` config.
- `SelfInjectedReturnService` — `@Autowired @Lazy SelfInjectedReturnService self;`
  calls `self.recordReturn()`.

`SelfInvocationIT`:

- `reproducesSelfInvocationPartialWrite`: after the exception,
  `outstanding("P1") == 70` and `tradeCount("P1") == 0`. Also asserts
  `TransactionSynchronizationManager.isActualTransactionActive()` was `false`
  inside `recordReturn()` (captured into a field) — the direct evidence that
  no transaction began, not just an inference from the data.
- three fix tests: `outstanding("P1") == 100`, `tradeCount == 0`, and the
  captured flag `true`.

Before/after rows for the reproduction go to
`docs/evidence/05-self-invocation.txt`.

**Acceptance** — four tests pass. `./mvnw verify` green.

**Deviation found in this section, fixed as its own commit:** `ArchitectureIT`
rule 3 checked only `classes().that().areAnnotatedWith(...)`, so a
method-level `@Transactional` passed anywhere. Every demo here annotates
methods, and removing `DoNotIncludeTests` left rule 3 green — section 1's
fix was not load-bearing. Rule 3 now also checks
`methods().that().areAnnotatedWith(...)`, with a fixture test
(`transactionalRuleCatchesMethodLevelAnnotation`) that failed against the
class-only rule first. Without `DoNotIncludeTests` rule 3 now fails on the
demos, as intended.

---

## [x] 5 · `REQUIRES_NEW` in the daily accrual, and pool starvation

`demo.tx.propagation`:

- `DailyAccrualBatch.run(LocalDate on)` — `@Transactional`, inserts one
  `accrual_run` row, then for each position calls
  `PositionAccrual.accrue(id, on)`, catching `DomainException` per position
  and continuing. Returns the list of failed ids.
- `PositionAccrual.accrue(String id, LocalDate on)` —
  `@Transactional(propagation = REQUIRES_NEW)`. Reads
  `outstanding_quantity`, builds `Money.of(quantity × "10.00", HKD)`
  (synthetic fixed price), `AccrualEntry.accrue(..., new FlatRate(Rate.of("0.0525")), on)`,
  `AccrualEntryRows.insert`. Records
  `((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections()`
  into a field before returning.

Positions `P1..P5` with quantities 1000, 2000, **-1**, 4000, 5000: `P3`'s
negative quantity makes `domain` throw `NegativeOutstandingValueException` —
the failure is real domain behaviour, not a test-only switch.

`RequiresNewAccrualIT`:

- `run` returns `[P3]`; `accrual_entry` has P1, P2, P4, P5; `accrualRunCount() == 1`.
- the active count captured inside the inner transaction `== 2`.
- after `run`, active connections `== 0` (Review focus 3).
- `reproducesRequiresNewPoolStarvation`: context started with
  `spring.datasource.hikari.maximum-pool-size=1` and
  `spring.datasource.hikari.connection-timeout=1000`; `run` throws
  `CannotCreateTransactionException` whose cause chain contains
  `SQLTransientConnectionException`; elapsed time measured and asserted
  `>= 1000 ms`; active count `== 0` afterwards.

Measured active count and starvation wait go to
`docs/evidence/05-requires-new-connections.txt`.

**Acceptance** — all tests pass. `./mvnw verify` green.

---

## [x] 6 · `REQUIRES_NEW` self-deadlock

`demo.tx.deadlock`:

- `OuterAdjustment.adjust(String id)` — `@Transactional`,
  `UPDATE loan_position SET outstanding_quantity = outstanding_quantity - 1 WHERE id = ?`
  (row lock taken), then `InnerAdjustment.adjust(id)`.
- `InnerAdjustment.adjust(String id)` — `@Transactional(propagation = REQUIRES_NEW)`,
  first `SET LOCAL lock_timeout = '3s'`, then the same `UPDATE`.

`RequiresNewSelfDeadlockIT`:

- `reproducesRequiresNewSelfDeadlock`: runs `adjust("P1")` on an executor
  thread. The test thread polls `pg_stat_activity` (bounded: 50 × 100 ms)
  until one backend shows `wait_event_type = 'Lock'`, then captures:
  the worker thread's stack (`ThreadMXBean.getThreadInfo(id, Integer.MAX_VALUE)`),
  and `pg_locks` joined to `pg_stat_activity` for the database.
  The polling is observation, not ordering — the scenario orders itself.
  The worker's `Future` then fails with a `DataAccessException` whose root
  `SQLException.getSQLState()` is `55P03`; elapsed `>= 3000 ms`;
  `outstanding("P1")` unchanged (outer rolled back too, since the exception
  propagated).
- after it, a new transaction's `SHOW lock_timeout` returns `0` — the
  `SET LOCAL` did not leak into the pool (Review focus 2).

Stack, lock snapshot and elapsed time to
`docs/evidence/05-requires-new-self-deadlock.txt`.

**Acceptance** — both tests pass; evidence shows the worker thread parked
inside the inner `UPDATE` and two backends of the same JVM, one holding and
one waiting on the same tuple. `./mvnw verify` green.

**Found while implementing:** the leak check as first written passed with a
plain `SET` too — the self-deadlock path always rolls back, and Postgres
reverts even a plain `SET` on rollback. The test now also runs a committed
`InnerAdjustment.adjust` (called directly, no outer lock) before borrowing
the pool; with `SET` in place of `SET LOCAL` it fails on `"3s"`. And the
evidence shows the waiter blocked on the holder's `transactionid`
(`ShareLock`, not granted) while itself holding the `tuple` lock — not two
backends on the same tuple; the worker thread is `RUNNABLE` in a socket
read, not parked.

---

## [x] 7 · Isolation levels, lost update, optimistic lock

`demo.tx.isolation`:

- `ReadModifyWriteReturn.returnQuantity(String id, long qty, CyclicBarrier afterRead)`
  — reads `outstanding_quantity`, awaits the barrier (both threads have read
  before either writes), writes `read - qty`. Two beans with the same body:
  `ReadCommittedReturn` (`@Transactional(isolation = READ_COMMITTED)`) and
  `RepeatableReadReturn` (`@Transactional(isolation = REPEATABLE_READ)`).
- `OptimisticReturn.tryReturn(String id, long qty, CyclicBarrier afterRead) → boolean`
  — `@Transactional`; reads quantity and version, awaits, then
  `UPDATE … SET outstanding_quantity = ?, version = version + 1 WHERE id = ? AND version = ?`;
  returns whether one row was updated.
- `RetryingReturn.returnQuantity(String id, long qty, CyclicBarrier firstAttemptBarrier)`
  — **not** transactional; loops `OptimisticReturn.tryReturn` up to 3 times
  (barrier only on the first attempt), throws `ConcurrentUpdateException`
  if all fail.
- `InsideTxRetryingReturn` — the same loop, but `@Transactional` at
  `REPEATABLE_READ` around it and calling the read/update inline.

`IsolationIT`, position `P1` at 100, two threads returning 10 and 20:

- `reproducesLostUpdateUnderReadCommitted`: both succeed; final outstanding
  is `90` or `80`, not `70`.
- `repeatableReadRejectsSecondWriter`: exactly one thread fails, root
  `SQLException` SQLState `40001`; final is `90` or `80`.
- `optimisticLockWithRetryOutsideTransactionLandsBoth`: final `70`,
  `version == 2`.
- `reproducesRetryInsideTransactionNeverSucceeds`: the loser's first
  `UPDATE` fails with `40001`; its retry, still in the same transaction,
  fails with `25P02` (current transaction is aborted) — asserted by SQLState
  on the retry attempt. Final `90` or `80`. The decision record also has to
  say what this test does *not* show: at `READ COMMITTED` with plain JDBC an
  in-transaction retry can succeed, because each statement takes a fresh
  snapshot. "Retry outside" is a rule for snapshot isolation and for stage
  6's persistence context, and the record must say that instead of claiming
  it is universal.

Before/after balances per case to `docs/evidence/05-lost-update.txt`.

**Acceptance** — four tests pass. `./mvnw verify` green.

---

## [x] 8 · `rollbackFor` and the checked exception

`demo.tx.rollback`:

- `SettlementRejectedException extends Exception` (checked, test-local).
- `DefaultRollbackBooking.book(String positionId)` — `@Transactional`,
  inserts a trade, throws `SettlementRejectedException`.
- `RollbackForBooking.book(String positionId)` —
  `@Transactional(rollbackFor = SettlementRejectedException.class)`, same body.

`RollbackForIT`:

- `reproducesCommitOnCheckedException`: exception thrown, `tradeCount == 1`.
- `rollbackForRollsBackCheckedException`: exception thrown, `tradeCount == 0`.

**Acceptance** — both pass. `./mvnw verify` green.

---

## [ ] 9 · Transaction + `@Async`: reading before commit

`demo.tx.async`:

- `TradeReader.findAsync(long tradeId) → CompletableFuture<Boolean>` —
  `@Async`, returns `TxDemoRows.tradeExists(tradeId)`.
- `BookingWithAsyncRead.book(String positionId) → boolean` —
  `@Transactional`, inserts a trade, calls `findAsync`, `.get(5, SECONDS)`
  while still inside the transaction, returns the result.
- test config: `@EnableAsync`.

`AsyncVisibilityIT`:

- `reproducesAsyncReadBeforeCommit`: `book` returns `false`; afterwards
  `tradeExists` on the test thread is `true` — the row was there, just not
  committed when the other connection looked.
- the async method ran on a different thread (thread name captured, asserted
  `!=` the caller's) — otherwise the test would prove nothing.

**Acceptance** — both assertions pass. `./mvnw verify` green.

---

## [ ] 10 · `afterCommit`: publishing only what was committed

`demo.tx.async` (continued):

- `BookingWithAfterCommitRead.book(String positionId, boolean failAfterInsert) → CompletableFuture<Boolean>`
  — `@Transactional`, inserts a trade, registers
  `TransactionSynchronization.afterCommit` that calls
  `TradeReader.findAsync(id)` and completes the returned future with its
  result; if `failAfterInsert`, throws `IllegalStateException` after
  registering.

`AfterCommitIT`:

- committed path: the future completes `true`.
- rolled-back path: `book` throws; the future is still incomplete after the
  transaction ends (asserted with `isDone() == false` once `book` has
  returned — no timing involved, since `afterCommit` would have run
  synchronously before `book` returned).

**Acceptance** — both pass. `./mvnw verify` green.

---

## [ ] 11 · `TransactionTemplate`: a boundary finer than the method

`demo.tx.template.TemplateDailyAccrualBatch.run(LocalDate on)` —
`@Transactional`, inserts the `accrual_run` row, then per position runs the
same accrual body as section 5 inside
`requiresNew.executeWithoutResult(...)`, where `requiresNew` is a
`TransactionTemplate` built from the `PlatformTransactionManager` with
`PROPAGATION_REQUIRES_NEW`. One bean; no `PositionAccrual` collaborator.

The accrual body itself (read quantity → `AccrualEntry.accrue` → insert) is
extracted in this section into `demo.tx.support.PositionAccrualStep` and
section 5's `PositionAccrual` switched to call it, so both sections run
literally the same body.

`TransactionTemplateIT`: same data as section 5; same outcome — returns
`[P3]`, four entries, one run row.

**Acceptance** — passes, and section 5's tests still pass after the
extraction. `./mvnw verify` green.

---

## [ ] 12 · `@Idempotent` × `@Transactional`: who is on the outside

`demo.tx.idempotency`:

- `@Idempotent` — method annotation; the key is the first `String` argument.
- `IdempotencyAspect` — `@Around("@annotation(Idempotent)")`:
  `INSERT INTO idempotency_key(key) VALUES (?) ON CONFLICT DO NOTHING`;
  zero rows → throw `DuplicateRequestException(key)`; otherwise proceed.
  Records whether a transaction was active when it ran.
- `IdempotentReturn.returnQuantity(String key, String positionId, long qty, boolean fail)`
  — `@Idempotent @Transactional`; decrements, inserts a trade, throws
  `IllegalStateException` if `fail`.
- two ordering configs:
  - `CheckOutsideTx`: aspect `@Order(0)`, `@EnableTransactionManagement(order = 10)`.
  - `CheckInsideTx`: aspect `@Order(10)`, `@EnableTransactionManagement(order = 0)`.

`IdempotencyOrderingIT`, each scenario under both configs (four tests):

- **concurrent duplicate** — two threads, same key, released together by a
  barrier; exactly one succeeds, the other gets `DuplicateRequestException`;
  `tradeCount == 1`.
- **retry after failure** — first call with `fail = true`, then the same key
  with `fail = false`.
  - `CheckInsideTx`: the retry succeeds; `tradeCount == 1`; the key row
    exists once.
  - `CheckOutsideTx` — `reproducesRetryRejectedAfterRollback`: the retry
    throws `DuplicateRequestException`; `tradeCount == 0`; the key row
    survived the business rollback.
- each test also asserts the aspect's recorded "transaction active" flag
  (`false` outside, `true` inside), so the ordering itself is proven, not
  assumed from the outcome.

For the concurrent-duplicate case under `CheckInsideTx`, the second insert
blocks on the first transaction's uncommitted primary-key entry, then sees
the conflict once it commits — the plan predicts this; the test's outcome
confirms or falsifies it, and the decision record reports which.

**Acceptance** — four tests pass (with the prediction confirmed or the test
and record adjusted to what was measured). `./mvnw verify` green.

---

After section 12: `docs/decisions/05-aop-transactions.md`, a separate
commit, carrying every item the spec's "Done when" lists, with numbers only
from `docs/evidence/05-*.txt`.

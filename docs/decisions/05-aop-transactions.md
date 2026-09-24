# Stage 05 — AOP and Transactions — Decision Record

Spec: [`docs/specs/05-aop-transactions.md`](../specs/05-aop-transactions.md)
Plan: [`docs/plans/05-aop-transactions.md`](../plans/05-aop-transactions.md)

Measured on Spring Boot 4.1.1 / Spring Framework 7.0.9, JDK 21.0.12,
PostgreSQL `postgres:17-alpine` under Testcontainers 2.0.5, HikariCP as
auto-configured. Every demo is test-only, under
`bootstrap/src/test/java/.../demo/tx`; the real application's runtime
classpath gained nothing
([`05-runtime-classpath.txt`](../evidence/05-runtime-classpath.txt)).

## What I chose

**The thinnest layer that still hits a real Postgres.** `JdbcClient`, a
hand-written `schema.sql`, one Postgres container per test JVM, all at test
scope. Persistence proper is stage 6; what this stage needed from a database
was row locks, connection counts and Postgres's own isolation behaviour, and
none of those exist in H2 in the same form. Each demo context re-runs
`schema.sql`, which drops and recreates the tables, so no context sees
another's rows (`TxDemoInfrastructureIT`).

**Why self-invocation skips the transaction — from how the proxy is built.**
The auto-proxy creator does in `postProcessAfterInitialization` what stage
4's `TimingBeanPostProcessor` did by hand: the container keeps the target
object it just built, creates a *second* object — by default a CGLIB
subclass such as `InterfacePositionQueries$$SpringCGLIB$$0`
([`05-proxy-classes.txt`](../evidence/05-proxy-classes.txt)); the
self-deadlock stack shows `InnerAdjustment$$SpringCGLIB$$0.adjust` calling
into `InnerAdjustment.adjust` — and stores that second object as the bean. The transaction advice lives in the
interceptor chain attached to the proxy object. The proxy's override of
`recordReturn()` runs the chain and then calls `recordReturn()` on the
target. The target is an ordinary instance of the original class; it holds
no reference to the proxy or to the chain. So inside `returnAll()`, `this`
is the target, and `this.recordReturn()` is a plain virtual call on an
object whose class has no transaction code in it at all. Nothing is
"skipped": there is nothing on that path to skip. The measured consequence
([`05-self-invocation.txt`](../evidence/05-self-invocation.txt)): inside
`recordReturn()`, `isActualTransactionActive()` was `false`; the decrement
ran in autocommit and stayed (100 → 70) while the trade was never booked.
Compile-time AspectJ weaving is the model where this is not true, because
the advice is woven into the method body itself; Spring AOP never touches
the method body.

The `final` method is the same fact from the other side. A CGLIB proxy is a
subclass, and a subclass cannot override a `final` method, so a call to
`describeFinal()` on the proxy runs the inherited body on the proxy object
itself: the counting advice stayed at 1 and no error was raised
(`ProxyMechanismTest`). What the body sees there (the proxy's own fields,
never initialised by a constructor) was not examined.

**The three fixes, each with its cost.** All three were measured to roll
back the whole unit (outstanding stays 100, transaction flag `true`).

- *Split into a second bean* (`SplitReturnService` → `ReturnRecorder`). The
  call now crosses a proxy because it crosses a bean. Cost: a class whose
  only reason to exist is the proxy boundary, and a name to invent for it.
  Benefit: nothing hidden; any reader who knows the proxy model sees why it
  works. This is the default.
- *`AopContext.currentProxy()`*. Cost: it needs `exposeProxy = true`, which
  makes every proxied call in the context set and restore a `ThreadLocal`,
  whether or not anything reads it (overhead not measured); it couples the
  service to Spring AOP's API and to a cast to its own class; and forgetting
  the flag fails only at call time, with `IllegalStateException: Cannot find
  current proxy` — the context starts cleanly
  ([`05-mutation-checks.txt`](../evidence/05-mutation-checks.txt) §13). That
  is the same exception type as the demo's own business failure; only the
  test's message assertion told the two apart.
- *`@Lazy` self-injection*. Cost: a field whose type is the class it sits
  in, which reads like a mistake; it works only because `@Lazy` injects a
  resolving proxy instead of the bean — without it, startup fails with
  `BeanCurrentlyInCreationException` on the field `self` (§12); and the call
  site `self.recordReturn()` is the only hint that
  anything unusual is happening.

**Boot proxies by subclass by default, interface or not** — the spec's
prediction, confirmed. With defaults, even `InterfacePositionQueries` was
`InterfacePositionQueries$$SpringCGLIB$$0`. With
`spring.aop.proxy-target-class=false` it became `jdk.proxy2.$Proxy56`, and
could then be looked up only by its interface: `getBean(InterfacePositionQueries.class)`
threw `NoSuchBeanDefinitionException`. A class with no interface is CGLIB
either way. The default matters because injecting by concrete class — which
this codebase does — only works with subclass proxies.

**`REQUIRES_NEW` per position in the daily accrual, and what it costs in
connections** ([`05-requires-new-connections.txt`](../evidence/05-requires-new-connections.txt)).
Inside a position's inner transaction the pool reported **2** active
connections: the outer transaction's, suspended but not returned, and the
inner one's. After the batch, **0**, including after P3's failure. With the
pool capped at 1, the inner transaction waited **1018 ms** (timeout 1000 ms)
and failed with `CannotCreateTransactionException`, root cause
`SQLTransientConnectionException ... (total=1, active=1, idle=0, waiting=0)`
— and since that is not a `DomainException`, the whole batch rolled back,
run row included. The alternative the batch cannot use is `REQUIRED` plus a
`catch`: with `REQUIRED`, P3's exception marks the one shared transaction
rollback-only as it leaves the inner proxy, and the outer `catch` cannot
undo that — commit throws `UnexpectedRollbackException`
([`05-mutation-checks.txt`](../evidence/05-mutation-checks.txt) §3).

**Why the `REQUIRES_NEW` self-deadlock is invisible to Postgres**
([`05-requires-new-self-deadlock.txt`](../evidence/05-requires-new-self-deadlock.txt)).
The snapshot while blocked: backend 75 (inner) `active`, waiting on
`transactionid` 834 (`ShareLock`, not granted), `blocked_by={74}`; backend
74 (outer) `idle in transaction`, waiting on `ClientRead`, holding
`transactionid` 834. Postgres's deadlock detector looks for a cycle in the
graph of backends waiting on locks. There is one edge in that graph,
75 → 74. Backend 74 is waiting for its client to send the next statement;
the client is the worker thread, and the worker thread's stack shows it
inside `InnerAdjustment.adjust`, reading the socket of backend 75. The edge
74 → 75 is a Java method call that has not returned, and it exists only in
the JVM. With no cycle to find, the database would wait forever; only the
inner transaction's `SET LOCAL lock_timeout = '3s'` ended it, after
**3031 ms**, SQLState `55P03`. The exception propagated through the outer
method too, so P1 was unchanged.

The fix is not a timeout; the timeout only makes the hang finite. The fix is
not to give a new transaction work on rows its caller already holds.

**Optimistic locking with the retry outside the transaction**
([`05-lost-update.txt`](../evidence/05-lost-update.txt)). P1 at 100, returns
of 10 and 20 racing, a barrier forcing both reads before either write:

| Scenario | Final | Failures |
|---|---|---|
| `READ COMMITTED`, read → compute → write | 90 | none |
| `REPEATABLE READ`, same code | 80 | one: `CannotAcquireLockException`, SQLState `40001` |
| `REPEATABLE READ`, retry inside the transaction | 80 | one, attempts `[40001, 25P02, 25P02]` |
| `version` check, retry outside the transaction | **70**, version 2 | none |

**The real reason finance code wants stronger isolation or optimistic
locking.** Not that `READ COMMITTED` is "less correct" in the abstract. At
`READ COMMITTED` both returns reported success and the balance says 90: a
return of 20 was accepted, a trade would have been booked for it, and the
position no longer reconciles with its trades — and nothing, anywhere,
raised an error. The discrepancy is found at reconciliation, by someone
else, later, if at all. `REPEATABLE READ` and the version column do not
make the race go away; they turn a silent wrong number into a loud failure
the application is forced to handle at the moment it happens. In a ledger,
a failed write is an operational event; a lost write is an incident.

**Where retry must sit: outside the transaction.** Under `REPEATABLE READ`
the loser's first `UPDATE` fails with `40001`, and from then on Postgres
refuses every statement in that transaction with `25P02` (current
transaction is aborted). A retry inside it cannot succeed; the only thing
the transaction can still do is roll back. What this does *not* show: at
`READ COMMITTED` with plain JDBC an in-transaction retry *can* succeed,
because each statement takes a fresh snapshot — measured by running the
same in-transaction retry at `READ COMMITTED`, where neither return failed
([`05-mutation-checks.txt`](../evidence/05-mutation-checks.txt) §5). So
"retry outside" is not universal physics. It is the rule for snapshot
isolation, and for stage 6's persistence context, where a retry inside the
same transaction re-reads a stale entity from the first-level cache. It is
the one placement that is correct under both, which is why it is the rule
here.

**Spring's default rollback rule is kept, because `domain` throws
unchecked.** A checked exception from a `@Transactional` method commits
(`RollbackForIT`: exception thrown, trade still there); `rollbackFor` makes
it roll back. The rule treats a checked exception as a declared business
outcome, not a failure. Stage 2 made `DomainException` unchecked, so the
default fits this codebase; `rollbackFor` is only needed where a checked
exception from a library crosses a transactional method.

**`afterCommit` for anything that must see committed data, knowing it is
not crash-safe.** An `@Async` read issued inside the transaction runs on a
pool thread, on its own connection, and saw `false` for a trade that was
there all along (`AsyncVisibilityIT`; removing `@Async` makes the same read
see its own uncommitted row — [§6](../evidence/05-mutation-checks.txt)).
Triggered from `afterCommit`, the read saw it; when the transaction rolled
back, the callback ran 0 times (`AfterCommitIT`; `afterCompletion` in its
place runs on rollback too — §7). The gap: a crash between the commit and
the callback loses the event, and nothing records that it was owed. That is
stage 10's Outbox. `@TransactionalEventListener(phase = AFTER_COMMIT)` is the
declarative form of the same callback, with the same gap; it was not tested
here.

**`TransactionTemplate` when the boundary is finer than the method.** The
per-position boundary is a property of one loop iteration, not of a method.
`TemplateDailyAccrualBatch` draws it with a `REQUIRES_NEW` template inside
the loop and gets the same outcome as the annotation version (`[P3]` failed,
four entries, one run row) with one bean instead of two — the second bean
existed only to put a proxy between the loop and the accrual. Both run the
same body, `PositionAccrualStep`. The annotation is still better where the
boundary *is* the method: it keeps transaction code out of the business
code, and it is where stage 6's use cases will get their transactions.

**The idempotency check belongs inside the transaction** — the spec's
prediction, verified
([`05-idempotency-ordering.txt`](../evidence/05-idempotency-ordering.txt)).
`IdempotencyAspect` claims the request key with
`INSERT ... ON CONFLICT DO NOTHING`; the primary key makes the claim atomic.

- *Check outside* (`@Order(0)`, transactions at 10): the key commits in
  autocommit before the business transaction starts. A business failure
  rolls the business back and leaves the key; the retry is rejected as a
  duplicate (trades 0, key rows 1) — a request that never took effect can
  never be retried. In the concurrent case the loser never waited on a
  lock and was rejected while the winner was still held inside its business
  work by the test — before anyone knew whether the winner would commit.
- *Check inside* (transactions at 0, aspect at 10): the key insert is part
  of the business transaction. A failure rolls both back; the retry runs
  (trades 1, key rows 1). In the concurrent case the loser's insert was
  observed in `pg_stat_activity` waiting on a lock — the winner's
  uncommitted primary-key entry — could not finish while the winner was
  held, and conflicted once the winner committed.
  Exactly one ran in both orderings.

Each test also asserted the aspect's "transaction active" flag (`false`
outside, `true` inside), so the ordering was proven, not assumed from the
outcome.

## What I rejected, and why

**`@ServiceConnection` and `spring-boot-testcontainers`.** A container bean
is bound to one context and stopped when that context closes; this stage
starts dozens of short-lived contexts against one database. A static
singleton plus plain `spring.datasource.*` properties does the same with no
extra dependency.

**H2 for the concurrency demos.** Its locking and isolation are not
Postgres's; a lost update or a `40001` reproduced on H2 says nothing about
production.

**An in-transaction retry loop**, at any isolation level. It works at
`READ COMMITTED` today and silently stops working the day the method moves
to `REPEATABLE READ` or onto JPA.

**The class-only `@Transactional` architecture rule.** Stage 3's rule 3
checked `classes().areAnnotatedWith(...)`, so a method-level
`@Transactional` anywhere passed. Every demo here annotates methods, and
removing `DoNotIncludeTests` left the rule green (§1). It now checks
methods too, with a fixture test that failed against the old form first;
without `DoNotIncludeTests` it now fails on the demos, 5 violations (§2).

**Stereotype annotations on test-only configuration.** Stage 4's record
says its demos live "in test-only classes the real component scan never
sees". That turned out false for this stage: `SlLedgerApplication` scans
`com.jerome.slledger.bootstrap`, and on the test classpath that includes
`demo.tx`. `TxDemoApplication` (a `@SpringBootConfiguration`, since section
1) and `AsyncConfig` (`@Configuration`, since section 9) were being loaded
into stage 4's full-application tests without failing anything, until two
configuration classes collided on a bean name. `ApplicationScanTest` now
asserts no `demo.tx` bean reaches the application — it failed first with
`["asyncConfig", "txDemoApplication"]` (§9). The final review then widened
the guard from `demo.tx` to every `demo` package, and it failed again with
`["diagnosticsBean"]` (§10): stage 4's own Linux-only `@Component` had been
in every full-application test context on this machine since stage 4. No
demo class carries a stereotype now; the configuration ones keep their
`@Enable…` annotation, which carries an `@Import`, so `TxDemo.start` still
processes them, and `DiagnosticsBean` keeps its `@Conditional`, which is
still evaluated when a test registers it explicitly. Test classes' own nested
`@Configuration` classes (stage 4's `ProxyBeanMethodsIT`) did not leak;
why was not investigated, so nothing here relies on it.

**Asserting Spring's exception subclass for lock failures.** The two lock
failures were translated differently: `40001` became
`CannotAcquireLockException`, but `55P03` (lock timeout) became
`UncategorizedSQLException`. Code that catches `PessimisticLockingFailureException`
to handle lock timeouts would miss the self-deadlock. The tests assert
`DataAccessException` plus the root cause's SQLState.

## What I observed

- **`SET` versus `SET LOCAL` only differs on commit.** The first version of
  the pool-leak check passed with a plain `SET lock_timeout` too, because
  the self-deadlock path always rolls back and Postgres reverts even a
  plain `SET` on rollback (§4a). With a committed inner transaction added,
  plain `SET` left `"3s"` on a pooled connection (§4b); `SET LOCAL` left
  `"0"` on all three.
- **The blocked worker thread is `RUNNABLE`,** in `sun.nio.ch.Net.poll`
  under PgJDBC's socket read — not parked, not `BLOCKED`. A thread dump
  alone would not flag it as waiting on anything; `pg_stat_activity` does.
- **The waiter waits on a transaction ID, not a tuple.** Backend 75 held the
  `tuple` lock on `(0,1)` and waited on 74's `transactionid`. The plan's
  wording ("two backends on the same tuple") was corrected to match.
- **Environment:** VS Code's Java extension compiles into the same
  `target/` as Maven. Once, a mid-edit ECJ build left classes throwing
  "Unresolved compilation problems" that Maven's incremental compile
  treated as current. Every section's gate after that was
  `./mvnw clean verify`.

## The money invariant at its first column

`accrual_entry` stores `amount numeric(19,6)` and `currency char(3)`.
`AccrualMoneyRoundTripIT` wrote and read back an entry in `HKD` (fee
143.84), `JPY` (144) and `BHD` (0.178): record equality held, so amount,
scale and currency survived. The raw column value had scale 6 in every
case; equality holds only because `Money`'s constructor re-scales to the
currency's minor units on the way back in. The column type does not carry
the invariant — `Money` does.

That re-scaling is also a rounding, and the first version of the reader
applied it silently: a row written by anything else as `HKD 143.845` would
have come back as `143.85`, a second rounding step outside `domain`, where
the stage-2 rule says rounding happens once. The final review caught it
(§11). The reader now builds the `Money`, compares its amount with the
stored value, and refuses the row with `ArithmeticException` if they
differ; `AccrualMoneyRoundTripIT.overPreciseAmountIsRefusedOnRead` pins it.
Any future reader of a money column needs the same check, not a bare
`Money.of`. `char(3)` comes back blank-padded and is trimmed before
`Currency.getInstance`. `numeric(19,6)` leaves 13 integer digits: ample for
a daily fee, worth re-checking before stage 6 reuses the type for
notionals.

## Where it breaks at 100x

- **`REQUIRES_NEW` doubles connections per batch worker.** Measured: 2 per
  in-flight position, and a pool of 1 starved and lost the whole batch.
  A pool sized for N concurrent batch workers holds N/2 of them safely, not
  N; at 100x workers the pool must be sized for 2 per worker, or the batch
  must stop nesting transactions. Unmeasured beyond one worker.
- **One commit per position.** Each position is its own transaction, so
  100x positions is 100x commits. Throughput was not measured; chunked
  commits with skip handling are stage 11's Spring Batch comparison.
- **A self-deadlock costs two connections for the whole `lock_timeout`.** At
  3 s per occurrence, a code path that hits it under load drains the pool
  3 s at a time; the timeout bounds the hang, not the damage.
- **Optimistic retry under contention.** Three attempts were enough for two
  writers. On a hot position with many writers, attempts run out and
  `ConcurrentUpdateException` becomes common; that is where stage 6's
  pessimistic-lock comparison comes in.
- **Idempotency inside the transaction** makes a duplicate wait, holding a
  connection, for as long as the winner's transaction runs. Aggressive
  client retries on a slow request turn into pool pressure. The key table
  also grows without bound; it needs an expiry policy before real traffic.
- **`afterCommit` events** are lost on a crash in the window after commit.
  At 100x volume the window is hit 100x as often in absolute terms; that is
  the argument for stage 10's Outbox, not a new one.
- **`exposeProxy`** puts a `ThreadLocal` set/restore on every proxied call in
  the context. Unmeasured; one more reason fix 1 is the default.

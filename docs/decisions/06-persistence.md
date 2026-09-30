# Stage 06 — Persistence — Decision Record

Spec: [`docs/specs/06-persistence.md`](../specs/06-persistence.md)
Plan: [`docs/plans/06-persistence.md`](../plans/06-persistence.md)

Measured on Spring Boot 4.1.1 / Spring Framework 7.0.9, Hibernate 7.4.5,
Flyway 12.4.0, HikariCP 7.0.2, PgJDBC 42.7.13, JDK 21.0.12, PostgreSQL 17.11
(`postgres:17-alpine`, Testcontainers 2.0.5), on a 4-core host with the
database container on the same machine. Every number below is copied from a
`docs/evidence/06-*.txt` file. Where a claim is reasoned rather than measured,
it says so.

## What I chose

### The three settled decisions

**1. Repository ports split by what they speak.** `domain.loan` owns
`LoanPositionRepository` (`find`, `activeOf`, `add`), in the aggregate's own
terms. `application` owns every other outgoing port: the paged listing
(`PositionListQuery`), the directories and the trade ledger. The alternative
was one place for all ports, in `application`. That would have made the
aggregate's persistence contract something the use-case layer defines, and a
second use case would have been free to define a different one. With the port
in `domain`, the aggregate states once what "store me" means. The listing is
not in `domain` because no aggregate in `domain` has a word for "page 3 of 25
rows". It is a read model, and it never loads a `LoanPosition`.

`activeOf` was not in the spec (deviation 2). The open use case needs the
counterparty's live positions for the credit check. The alternative was a SQL
`sum(collateral_amount)` behind an application port. That would add up
collateral across currencies unless every future query remembered not to. With
`activeOf`, `LoanPositions.exposureByCounterparty` in `domain` does the sum and
refuses mixed currencies. `PositionUseCasesIT.exposureAcrossCurrenciesIsRefusedNotSummed`
pins that under both lock strategies.

Lock strategy is not part of the port. Both adapters implement the same
interface, selected by `ledger.persistence.locking`, and the use-case suite
runs unchanged under each: 18 tests, 9 per strategy, all green, with one
test asserting which adapter class is in the context.

**2. `AccrualEntry` stays outside the `LoanPosition` aggregate.** Nothing is
an invariant across a position and its entries. "One entry per position per
day" is the table's primary key, not an aggregate rule. Putting entries inside
would mean three things:
- every daily accrual bumps the position's version;
- that version bump conflicts with a concurrent return;
- loading a position loads a year of entries.

The JPA entity keeps a lazy, read-only `@OneToMany` for the N+1 and lazy-loading
exercises. The persistence model is a separate type, so that association never
reaches `domain`.

**3. `@Version` lives on the entity only, and a guard makes that hold.**
`domain` carries no version. The adapter's `add` copies the aggregate's two
mutable fields onto the *managed* entity that `find` loaded in the same
transaction, and Hibernate's flush writes `UPDATE … WHERE version = ?`. That is
only correct when load and save share one transaction. The spec's guard was
`Propagation.MANDATORY`. The plan replaced it (deviation 1), because
`MANDATORY` only asks whether *some* transaction is active. In the failing
sequence, T3 saves T1's stale copy, and T3 is an active transaction.

Measured with a naive "map every time" adapter
([`06-unit-of-work.txt`](../evidence/06-unit-of-work.txt)):
- T1 loads P1 at 100.
- T2 returns 30 and commits: 70.
- T3 returns 10 on T1's copy.
- The row reads **90**, and no exception is raised.

The version check ran and passed, comparing version 1 (just re-read) with
version 1. The guard that replaced `MANDATORY` is `UnitOfWorkPositions`: a
transaction-scoped identity map of the instances this transaction loaded.
Under it, the same sequence fails at T3 with
`PositionNotLoadedInThisTransactionException`, and the row stays at 70. Two
mutations of the guard each turned 2–3 tests red. The map is keyed by identity,
not by id, because the stale copy has the same id and is exactly what must not
match.

This is enough because every write in this ledger is a delta ("return 10")
that the aggregate re-validates inside the transaction, not a state
overwrite. **Revisit it when** an endpoint accepts a whole position back from
a client (an `If-Match` flow in stage 7). A cross-request version then has to
live in `domain`.

### The other deviations from the spec

3. **A duplicate idempotency key raises `DuplicateRequestException`**,
   translated from the unique-constraint name `loan_trade_idempotency_key_key`.
   It does not return the original result: that is stage 7's `Idempotency-Key`
   work. A return that reuses an open's key is a duplicate too (Review focus 4).
4. **Stage 4's full-application tests became `…IT`** and run against
   `SharedPostgres` with no `DataSourceAutoConfiguration` exclusion.
5. **One container, many databases.** `SharedPostgres.freshDatabase()` gives
   each Flyway-managed context its own database. Stage 5's demos keep one
   `tx_demo` database with Flyway and JPA switched off.

### Schema and the money columns

Every money column is `numeric(22,6)` plus `char(3)`, never one without the
other. That leaves 16 integer digits. `MoneyColumnsRoundTripIT` round-trips
`9999999999999999.99` HKD, `9999999999999999` JPY and `9999999999999999.999` BHD
as credit limits ([`06-money-columns.txt`](../evidence/06-money-columns.txt)).
The largest amount any fixture writes is the throughput tests' credit limit,
`1000000000.00` HKD: 10 integer digits, 6 digits of headroom. `flat_rate` is
unconstrained `numeric`, not the plan's `numeric(12,8)`. The fixed scale padded
`0.0400` to `0.04000000`, and `Rate`'s record equality compares scale, so 4
round-trip tests went red under that mutation.

Rules persist as `rule_type` plus `flat_rate`. `FloatingRate` and
`MarginalTieredRate` are refused on write with `UnsupportedAccrualRuleException`.
That is a known gap: a floating rule holds a `ReferenceRates` service and is
not a value, and a tiered one needs a child table.

## The exercises

### 1 · Flyway: a type change under blue/green

[`06-blue-green.txt`](../evidence/06-blue-green.txt). The one-step
`ALTER COLUMN idempotency_key TYPE uuid USING idempotency_key::uuid` fails in
two ways:
- With an old-style key already stored (`req-42`), the migration itself fails
  with `22P02`.
- On an empty table, it succeeds, and then the version still serving half the
  traffic cannot insert at all: `42804`, "column is of type uuid but
  expression is of type character varying".

The old writer fails even with a valid UUID string, because the failure is the
bind type, not the key's shape. During a blue/green deploy both versions run
against one schema, so a change that only the new version survives cannot
ship in one step.

Expand/contract, as measured:
1. **V1001** adds a nullable `idempotency_uuid` with a unique index. Old and new
   writers both succeed.
2. **V1002** adds a trigger that fills the new column for the old writer, and
   backfills existing rows with the same deterministic rule (`md5(key)::uuid`
   for non-UUID keys). Both writers succeed, and no row has a null uuid.
3. **V1003** (contract) drops the text column. After it, the old writer fails
   with `42703`. That is why contract waits until the old version is retired.

V1003 does not rename the new column back to `idempotency_key`. The version
deployed during expand writes `idempotency_uuid`, and a rename would break it
exactly as V1000 broke the old one.

### 2 · N+1, and paging over a collection fetch

[`06-n-plus-one.txt`](../evidence/06-n-plus-one.txt), 100 positions × 5
entries.

| Variant | Statements | Entities loaded |
|---|---|---|
| lazy, entries touched per position | **101** | 600 |
| `join fetch` | 1 | 600 |
| entity graph (`fetchgraph: entries`) | 1 | 600 |
| `default_batch_fetch_size=25` | 5 | 600 |
| ids paged with `limit`, then `join fetch` by ids | 2 | 60 |

Every variant's entries are asserted complete. The entity graph is the JPA
one Spring Data's `@EntityGraph` builds, applied directly. Batch fetching is
the global setting in its own context. Either annotation on the production
entity would have changed what the N+1 reproduction measures.

**The `JOIN FETCH` + pagination trap, as planned, no longer happens on
Hibernate 7.4.5.** A plain `join fetch` with `setMaxResults(10)` is rewritten:
the page is taken over positions in a subquery (`from (select distinct …
fetch first ? rows only)`), then joined to entries. The result is 10 positions,
60 entities, one statement and no warning. The same holds without `distinct`,
with `left join fetch`, and with the entity graph. The in-memory page still
happens once the order refers to the fetched collection
(`order by p.id, e.businessDate`): no `limit` in the SQL, **600 entities
hydrated for 10 positions**, and `HHH90003004` logged as a warning.
`hibernate.query.fail_on_pagination_over_collection_fetch=true` turns that
into a `HibernateException`.

**A worse failure was found instead: a page silently ignored.** The
subquery rewrite is decided when a query string is first interpreted, and
cached per session factory. If the same `join fetch` string ran once without
a page, every later `setMaxResults(10)` on it returns **all 100 positions**:
no subquery, no in-memory page, no warning. With
`hibernate.query.plan_cache_enabled=false` the page is 10 again. The reverse
order (a page first, then unpaged) is correct.
`reproducesPageIgnoredAfterTheSameQueryRanUnpaged` pins it. No production
query is exposed: the listing is a projection with no fetch. The rule for this
project is that a page over a collection is always two queries (ids with
`limit`, then fetch by ids). That is correct under every behaviour measured
here, and costs one extra statement.

### 3 · Lazy loading, and why open-in-view is a bad default

[`06-lazy-loading.txt`](../evidence/06-lazy-loading.txt). With
`open-in-view: false`, serialising the entity after the service returned
fails. Jackson 3 raises `DatabindException`, and its root cause is
`LazyInitializationException` ("no session"). That is the failure a
controller returning an entity would hit.

Wrapped in `OpenEntityManagerInViewInterceptor`, the same serialisation
succeeds. The load's transaction issued 1 statement. **Serialisation issued 1
more, after the transaction had ended**, and
`isActualTransactionActive()` was `false` when Hibernate prepared it. That is
why the default is bad. OSIV does not make lazy loading work; it moves the
query out of the transaction into rendering:
- the query runs in autocommit, with no isolation relative to what the service
  read;
- the view layer decides how many queries run, so the N+1 of exercise 2 moves
  into the serialiser, where nobody counts it;
- the connection is held for the whole request, not just the transaction.

The DTO projection (`PositionRow`) serialised with **0** statements.

### 4 and 11 · The persistence context, and the second-level cache

[`06-persistence-context.txt`](../evidence/06-persistence-context.txt).
- Two `findById` of one id in one transaction: 1 prepared statement, and the
  same instance.
- **Accidental dirty write**: a `@Transactional` method that lower-cases
  `collateral_currency` "for display" and never saves committed `hkd`. That is
  1 flush and 1 entity update, and it writes a row that `MoneyColumns.read`
  would then refuse.
- The same method with `readOnly = true`: 0 flushes, 0 updates. Spring set
  flush mode `MANUAL`, **and** `session.isDefaultReadOnly() = true`, and the
  loaded entity is itself read-only. So both the flush and the dirty-check
  snapshot are skipped. `ListPositions` is `readOnly = true` for this reason.

The second-level cache stays off. `findById` in two transactions issued 2
statements, with 0 L2 hits and 0 puts, and `isSecondLevelCacheEnabled()` is
`false`. **Why off, for this project:** every instance of the application
would hold its own cache, and a return committed on one node would not evict
the position on the others. A stale outstanding quantity is exactly the input
the aggregate re-validates a return against. The fix for that is a shared
cache with invalidation, which is stage 9's Redis, not a per-node Hibernate
region. One measured surprise: without the property, Hibernate also reports
the cache disabled, because the region factory is `NoCachingRegionFactory`.
The explicit `false` in `application.yml` changes nothing today. It is kept for
the day a JCache provider reaches the classpath, when the default would follow
the classpath. That future is not measured.

### 5 · Optimistic locking, and where the retry sits

[`06-optimistic-retry.txt`](../evidence/06-optimistic-retry.txt). A position
at 100 received two returns, 10 and 20, held by a barrier until both had read
version 0:
- **No retry:** exactly one lost, with `ObjectOptimisticLockingFailureException`.
  The row is 80 at version 1, with one return trade.
- **Retry outside the transaction** (`ReturnPosition` → `ReturnPositionOnce`):
  both landed. The row is 70 at version 2, with 3 trades.
- **Retry inside one transaction**, stage 5's unmeasured claim, now measured.
  It is worse than predicted:
  - every re-`find` returned the same managed entity, still at version 0, so
    all three flushes failed;
  - what each attempt read was the entity's own uncommitted decrements,
    **100, 90, 80**, never the committed 80 (the third 80 is the loop's
    arithmetic);
  - the method returned normally, and the commit threw
    `UnexpectedRollbackException`.

The failure inside the transaction is raw JPA,
`jakarta.persistence.OptimisticLockException`. `EntityManager.flush()` is not
behind a `@Repository` proxy, so nothing translates it, and a loop catching
Spring's `OptimisticLockingFailureException` would not catch it at all.

The retry is a hand-written ten-line loop, not `@Retryable`. That avoids a
dependency, and the loop makes the placement visible: it has to be a
different bean from the transaction, for stage 5's self-invocation reason.

### 6 · Optimistic vs pessimistic, and when to pick pessimistic

[`06-lock-throughput.txt`](../evidence/06-lock-throughput.txt). 8 threads,
`max-attempts` 5, median of 3 runs after a warm-up.

| Strategy | Shape | Wall ms | Landed | QPS | Optimistic failures | Exhausted |
|---|---|---|---|---|---|---|
| optimistic | HOT (1 position, 400 returns) | 1782 | **229 / 400** | 128.5 | 1070 | 171 |
| pessimistic | HOT | 1370 | 400 / 400 | 292.0 | 0 | 0 |
| optimistic | SPREAD (40 positions × 10) | 602 | 400 / 400 | 664.5 | 0 | 0 |
| pessimistic | SPREAD | 577 | 400 / 400 | 693.2 | 0 | 0 |

Correctness held in every run: for every position, outstanding equals the
initial quantity minus the returns that landed, and the trade count equals
the landed count. The run-to-run spread is wide (HOT pessimistic
1148–1587 ms), so the SPREAD difference of 664.5 against 693.2 is not a
result. HOT is one:
- optimistic lost 171 of 400 returns after 5 attempts each, and burned 1070
  failed transactions doing it;
- pessimistic landed all 400 at more than twice the QPS.

**When to pick pessimistic:** when writes concentrate on few rows and every
write must land. A borrower's single large position returned in many small
lots is that shape. Optimistic wins nothing measurable when writes are spread,
and costs a retry storm when they are not. The pessimistic adapter's cost is
that a waiter **holds its connection while it waits on the row lock**, so a
hot row turns into pool pressure (see pool sizing). It is bounded by a
3000 ms lock timeout: a second finder gave up after 3014 ms with
`CannotAcquireLockException`. Hibernate 7.4.5 emits `for no key update` for
`PESSIMISTIC_WRITE` on Postgres, not `for update`.

The default stays **optimistic**. Nothing in this stage's workload is known to
be hot, and optimistic holds no lock across the read. The property exists so
the switch is configuration, not code.

### 7 · Where this project bypasses the ORM

[`06-jpa-vs-jdbc.txt`](../evidence/06-jpa-vs-jdbc.txt). The listing through a
JPQL constructor projection and through `JdbcClient` returned equal pages at
sizes 10 and 25 over 57 positions, including JPY, BHD and a partially returned
row. Code size: 42 + 9 lines (query and its column record) against 34. The
SQL differs only in syntax (`offset ? rows fetch first ? rows only` against
`LIMIT ? OFFSET ?`).

The rule this project follows:
- **Aggregate writes go through JPA.** The identity map, the managed-entity
  version check and dirty checking are what the `@Version`-on-entity design
  depends on, and exercise 5 measured them working.
- **Reads that are set operations go through hand-written SQL**, via
  `JdbcClient`: reports, rankings, roll-ups, gaps-and-islands. The ORM cannot
  express window functions or recursion, and there is no entity to manage.
- **Plain projections** (the listing) can go either way. JPA stays the
  default because it is what the tests have run longest. `ledger.persistence.list-query=jdbc`
  switches it, and both implementations are held to the same pages.

### 8 · The index-order finding

[`06-explain.txt`](../evidence/06-explain.txt). The monthly fee report sums
fees per position over 20 days, on 500,000 `accrual_entry` rows.

| Indexes | Plan | Execution | Buffers |
|---|---|---|---|
| none | Parallel Seq Scan | 23.173 ms | 3201 |
| primary key `(position_id, business_date)` | **Parallel Seq Scan**; the key is not used | 23.437 ms | 3201 |
| plus `(business_date, position_id)` | Bitmap Index Scan | 4.715 ms | 106 |
| Flyway V1–V3 | Bitmap Index Scan on V3's index | 4.636 ms | 107 |

The prediction held. The primary key looks as if it covers a date-range
query, and it does not: its leading column is unconstrained, and Postgres 17
has no skip scan. **Read an index left to right: it narrows by its first
column first.** V3 is `(business_date, position_id)`. The matching 10,000 rows
sat in 65 heap blocks, because the fixture, like daily accrual, writes a day's
entries together. A table clustered by position would cost more heap reads
through the same index; that was not measured.

V3 is a plain `CREATE INDEX`, which blocks writes for the whole build. Flyway
wraps each migration in a transaction, and `CREATE INDEX CONCURRENTLY` refuses
to run in one. On a live table it would be `CONCURRENTLY`, in a script with
`executeInTransaction=false`, plus a check for an `INVALID` index left behind
by a failed build.

### 9 · Pool sizing

[`06-pool-exhaustion.txt`](../evidence/06-pool-exhaustion.txt). With a pool of
2 and a 1000 ms timeout, two transactions held their connections and the
third caller waited **1010 ms**. It then failed with
`CannotCreateTransactionException`, root cause `SQLTransientConnectionException`
("total=2, active=2, idle=0, waiting=0"). The failure comes at transaction
begin, before any use-case code runs: `JpaTransactionManager` takes the
connection when the transaction starts, not at the first statement.

The reasoning for size, on this machine's 4 cores:
- **The pool bounds concurrent transactions, not requests.** A connection is
  held for the transaction's duration, so connections in use ≈ transaction
  rate × duration (Little's law). From exercise 6's SPREAD runs, derived and
  not separately measured: 8 threads landed 400 returns in 577 ms, about
  11.5 ms per return per thread at 693 QPS. That keeps about 8 connections
  busy, and the default pool of 10 served it without a timeout.
- **Past the database's cores, more connections queue inside Postgres
  instead of in Hikari.** HikariCP's own starting heuristic is
  `cores × 2 + effective spindles`, about 9 here. That is a heuristic, not
  measured on this machine.
- **Lock waits hold connections.** Under pessimistic HOT, 8 transactions
  queue on one row, and all 8 hold a connection while 7 of them do nothing.
  A hot row can exhaust a pool sized for throughput.
- **Stage 5's `REQUIRES_NEW` holds two connections per worker**, measured
  there. A batch that nests transactions needs twice the pool for the same
  concurrency, or it starves itself.

So the pool is sized from the transaction rate the service must sustain and
its measured duration, plus headroom for lock waits. It is not sized from
request concurrency, and never far past what the database's cores can run.

### 10 · Hand-written SQL, and where it crosses over

Each query lives in a file under `adapter-persistence/src/test/resources/sql/`.
Each is asserted against a fixture built so that the assertion discriminates,
and its result is asserted equal to the Java implementation's at every size.
All timings are medians of 5 after 2 warm-ups, and include fetching over
JDBC. The database is on the same host (loopback), which favours any
implementation that ships more rows.

**Window functions** ([`06-sql-window.txt`](../evidence/06-sql-window.txt)).
The fixture's values are 500, 500, 400, 400, 300, 200, so the three top-threes
are three different sets:
- `ROW_NUMBER` cuts between the 400s by id: P1–P3;
- `RANK` keeps both 400s and skips to 5: P1–P4;
- `DENSE_RANK` keeps the 300: P1–P5.

Only `ROW_NUMBER` gets the `id` tiebreaker. With `id` in their `ORDER BY`,
`RANK` and `DENSE_RANK` would never tie. The ranking is partitioned by the
market price's currency, because market value is in that currency. The query
did not win at any size measured:

| Positions | SQL ms | SQL ms, `work_mem` 64MB | Java ms |
|---|---|---|---|
| 1,000 | 5.0 | 4.6 | 3.9 |
| 10,000 | 31.8 | 34.5 | 19.7 |
| 100,000 | 318.1 | 308.9 | 150.9 |

At 100k the sort spilled at the default `work_mem` (4MB), but with 64MB SQL
was still 308.9 ms, so the spill is not the cause. Half the rows come back
(50,000 of 100,000), so moving the work into the database saves little
transfer. No crossover was found within the range measured.

**Recursive CTE** ([`06-sql-recursive.txt`](../evidence/06-sql-recursive.txt)).
Every pool's holdings roll up its subtree per currency; the USD holding in A2
reaches A and ROOT as a line of its own. `CYCLE … SET is_cycle USING path`
makes a parent loop terminate, and counts each pool once.

| Pools | Every pool: SQL ms | Every pool: Java ms | One pool: SQL ms | One pool: Java ms |
|---|---|---|---|---|
| 1,000 | 12.4 | 4.6 | **2.1** | 5.1 |
| 10,000 | 173.3 | 31.0 | **8.3** | 23.9 |
| 100,000 | 2233.4 | 191.5 | **84.1** | 188.4 |

Rolling up every pool, SQL loses by 2.7× at 1k, rising to 11.7× at 100k. The CTE walks each pool's subtree once
per root: 588,889 recursive rows at 100k pools, N × depth. The Java side does
one post-order pass. Rolling up one pool, the question usually asked, SQL wins
at every size: it touches that subtree only, while Java must load every pool
to find it. The planner estimated 50,085,000 rows for the recursion that
produced 588,889.

**Self-join plus dates** ([`06-sql-streaks.txt`](../evidence/06-sql-streaks.txt)).
The fixture's days 1–5, 8–9 and 12 give streaks of 5, 2 and 1.

| Entries | Self-join ms | Window ms | Java ms |
|---|---|---|---|
| 1,000 | 3.9 | 3.2 | 3.2 |
| 10,000 | 72.6 | 12.9 | 10.1 |
| 100,000 | 1974.1 | 130.5 | 70.3 |

The self-join pairs every start with every later end before `min` picks one,
so it grows with the square of the streaks per position. At 100k the join
filter discarded 4,628,703 rows. The window form (`date - row_number()`) is
linear, and Java's single pass is faster still at every size.

**The crossover rule these three measured:** the database wins when the answer
is much smaller than the data it reads, and the query can reach that data
selectively (the one-pool roll-up). It loses when the answer is a large
fraction of the input, or when SQL's formulation is asymptotically worse than
the obvious loop (every-pool roll-up, the self-join). Over a real network the
row-shipping side pays more. The loopback numbers above understate that, and
by how much was not measured.

## What I rejected, and why

- **`Propagation.MANDATORY` as the overwrite guard.** It passes the exact
  sequence it was meant to stop (see decision 3).
- **`@Version` in `domain`.** It would put a persistence concern in a module
  that has no dependencies, to support a cross-request overwrite that no use
  case performs yet.
- **An `@EntityGraph` repository method or `@BatchSize` on the production
  entity**, for the N+1 exercise. Both are fine fixes. On the shared entity,
  they would have changed what the reproduction measured.
- **`@Retryable`.** It adds a dependency to replace a ten-line loop, and it
  hides the one thing exercise 5 is about: which side of the transaction the
  retry is on.
- **Locking in `activeOf`.** A lock on the rows the credit check reads would
  not stop a concurrent open: the row that open inserts is one nobody read.
  See the known gaps.
- **`open-in-view` on.** See exercise 3.
- **Disabling Hibernate's query plan cache** to avoid the ignored page. It
  would cost every query its reinterpretation, to protect against a pattern
  this project does not use and forbids: a page over a collection fetch is
  always two queries.

## What I observed

- **Tests green, application unable to start.** `bootstrap/pom.xml` still
  declared stage 5's `spring-boot-starter-jdbc` at `test` scope. A direct
  declaration beats the transitive `compile` one from `adapter-persistence`,
  so HikariCP was on the test classpath only, and the first real run found no
  `entityManagerFactory` ([`06-bootstrap-run.txt`](../evidence/06-bootstrap-run.txt)).
  Only running the application against real infrastructure caught it. After
  the fix, the application started against the Compose Postgres: V1 and V2
  were applied, and later V3. The password appeared 0 times in the log.
- **`ArchitectureIT` read the `adapter-persistence` test-jar as production
  code.** `DoNotIncludeTests` only recognises `test-classes` directories. The
  first test-only `@Transactional` bean in the test-jar broke rule 3. The
  import now also excludes `-tests.jar`.
- **Two tests first read a proxy's field**, which a CGLIB proxy never sets:
  the recording was on the target. Both now return what they observed. This
  is stage 5's proxy model, found again from the other side.
- **Spring translates only what crosses a `@Repository` proxy.** Inside an
  adapter, `PositionNotLoadedInThisTransactionException` had to stop extending
  `IllegalStateException`, or it would have become
  `InvalidDataAccessApiUsageException` (section 5). Outside any proxy,
  `EntityManager.flush()` threw raw JPA (exercise 5).
- **Two predictions were wrong, and are recorded as measured.** The
  second-level cache is already off without the property. The pagination trap
  needs an order on the fetched collection to fire on Hibernate 7.4.5.

## The money invariant, at every new component

- **Columns.** Every money column round-trips amount, scale and currency for
  HKD, JPY and BHD. An amount stored with more precision than its currency is
  refused on read on all 5 columns: 15 cases, all red under the mutation that
  drops the check.
- **`JdbcClient` mapper.** The listing reads money through `MoneyColumns`,
  and its pages equal the JPA ones, JPY and BHD included.
- **Serializer.** The entity serialises its raw column,
  `"collateralAmount":1000.000000` at scale 6 for HKD, beside a separate
  `"collateralCurrency"`. That is one more reason no entity reaches a response.
  The DTO serialises `Money` as `{"amount":1000.00,"currency":"HKD"}`: currency
  and scale kept. The amount is a JSON number, and a client that parses it into
  binary floating point loses the guarantee. Stage 7 must decide the wire
  format at the boundary.
- **Hand-written SQL.** Rankings partition by the price's currency, and
  roll-ups group by currency. Neither query adds amounts across currencies.
- **Known gap: the fee report.** Exercise 8's query sums `fee_amount` per
  position without grouping by `fee_currency`. It is correct only because all
  of a position's entries share one currency, and nothing in the schema
  enforces that. A production fee report must group by currency too.

## Known gaps

- **Write skew on the credit check.** Two concurrent opens for one
  counterparty can both pass `checkCanAbsorb` against the same exposure, under
  either lock strategy. The natural fix is to lock the counterparty row for
  the duration of an open. Reasoned, not reproduced; a stage 8 concurrency
  exercise.
- **`FloatingRate` and `MarginalTieredRate`** cannot be stored.
- **Reference data** (counterparties, securities) has no use case. Tests
  insert it with SQL.

## Where it breaks at 100x

- **Optimistic locking on a hot position.** At 8 writers on one row,
  optimistic already lost 171 of 400 returns after 5 attempts each. At 100x
  writers per hot position, no retry budget saves it. It needs the pessimistic
  adapter, or writes to one position serialised through a queue (stage 10).
- **Pessimistic locking's ceiling.** One position took 400 returns in 1370 ms,
  about 3.4 ms per locked transaction, so roughly 290 returns per second per
  position, whatever the pool size. At 100x that rate on one position, waiters
  hit the 3000 ms lock timeout. Each of them holds a connection while waiting,
  so the lock queue drains the pool for every other position too.
- **The pool.** Derived from the measured rate and duration, not measured at
  scale. 100x the transaction rate at the same duration needs about 100x the
  connections. Postgres will not run that many usefully on the cores it has,
  so the answer at 100x is more instances behind a pooler, or shorter
  transactions, not a bigger Hikari pool.
- **Schema changes.** V2's `ADD COLUMN … DEFAULT` is metadata-only in Postgres
  11+, and survives. V3's plain `CREATE INDEX` blocks writes for a build that
  grows with the table, so it must become `CONCURRENTLY` first. The expand
  step's single-statement backfill `UPDATE` holds a lock on every row it
  touches, and bloats the table. At 100x rows it has to run in batches.
- **`accrual_entry` volume.** One row per position per day: 100x positions is
  100x rows per day. The date-first index keeps the fee report selective. The
  self-join streak query, already 1974 ms at 100k rows, cannot follow; the
  window form or an incremental streak table has to replace it.
- **The recursive roll-up of every pool** was 2233 ms at 100k pools and grows
  with N × depth. A per-request roll-up of one pool stays cheap. A report of
  all pools at 100x needs a materialised roll-up, maintained on write.
- **Reads without a cache.** Every read goes to Postgres. At 100x reads, the
  reference data, which changes rarely, is the first thing to cache. It needs
  cross-node invalidation, so it is stage 9's Redis.
- **Hibernate's query plan cache and the ignored page.** More distinct query
  strings mean more interpretations cached per factory. The ignored page is a
  correctness bug, not a scale one, and the two-query rule is what keeps it out.

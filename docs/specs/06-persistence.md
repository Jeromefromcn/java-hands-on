# Stage 06 — Persistence

## Problem

Stage 5 reached Postgres through the thinnest layer that still exercised
transactions: `JdbcClient` and a hand-written schema, all at test scope.
Stage 6 puts the ledger itself in Postgres. Positions, trades and accrual
entries move into tables that Flyway owns. JPA maps them. The first real
use cases arrive in `application` with `@Transactional` on them.
`SlLedgerApplication` gets a `DataSource` for the first time.

Each ORM and database failure mode the roadmap lists is then driven to an
observable, asserted result against real Postgres. The list is N+1, lazy
loading outside a transaction, accidental dirty writes, optimistic and
pessimistic locking, a missing or misordered index, and pool exhaustion.
Three hand-written SQL queries are added that an ORM does not exercise.

Three facts shape the stage.

- **The persistence model is a separate type from the domain model.** Stage
  1's enforcer forbids any third-party dependency in `domain`, which rules
  out JPA annotations on domain types. Every aggregate therefore crosses a
  mapper. Three questions that stage 1 deferred and stage 2 left open are
  settled here, in the section below: where the repository port lives,
  whether `AccrualEntry` belongs to the `LoanPosition` aggregate, and where
  `@Version` lives.
- **`LoanPosition` cannot be rebuilt from a row today.** Its only
  constructor path is `open()`, which always yields `OPEN` with the full
  quantity. A position read back as `PARTIALLY_RETURNED` has no way in.
- **There is no web layer until stage 7**, and earlier stages may not
  depend on later ones. The lazy-loading exercise reproduces the
  controller-serialization failure without a controller (see exercise 3).

## Settled design decisions

These were decided before this spec was written. The decision record
argues each one; the spec states what they commit the code to.

1. **Repository ports are split by what they speak.**
   - `domain` owns one repository per aggregate root, in the aggregate's
     own language: `LoanPositionRepository` in `domain.loan`, with `find`
     and `add` only. This is the Evans/Vernon placement.
   - `application` owns every other outgoing port: read-side projections
     (the paged position list) and technical ports.
   - Lock strategy is **not** part of the domain port. Pessimistic and
     optimistic locking are two adapter implementations of the same port,
     selected by configuration, so the use case is identical under both.
   - The name `LoanPositionRepository` avoids a clash with `domain`'s
     existing static utility class `LoanPositions`.
2. **`AccrualEntry` stays outside the `LoanPosition` aggregate.** Stage 2
   already modelled it this way: a record that references its position by
   `LoanPositionId`. Nothing is an invariant across a position and its
   entries. "One entry per position per day" is a unique constraint, not an
   aggregate rule. Putting the entries inside would make every daily accrual
   bump the position's version and conflict with concurrent returns, and
   would load a year of entries with every position. The JPA entity may
   still carry a `@OneToMany` to entries, for the N+1 exercise. The
   persistence model is separate, so that association does not reach
   `domain`.
3. **`@Version` lives only on the JPA entity.**
   - `domain` carries no version.
   - The adapter's `add` of an already-persisted position finds the
     *managed* entity for that id in the current persistence context. That
     is a first-level cache hit with no SQL. The adapter copies the
     aggregate's mutable state onto it, and Hibernate's flush issues
     `UPDATE … WHERE version = ?`.
   - This is only correct when load and save share one transaction.
     Otherwise the save loads a fresh entity with the current version and
     silently overwrites, and the lost update is undetected. The adapter's
     methods therefore run with `Propagation.MANDATORY`.
   - `reproducesSilentOverwriteAcrossTransactions` demonstrates the trap
     with the guard bypassed.
   - This is sufficient because every write in this ledger is a delta ("return
     10") that the domain re-validates inside the transaction, not a state
     overwrite. A cross-request version (an HTTP `If-Match`) would need the
     version in `domain`. The decision record states that as the condition
     for revisiting.

## Scope

### Module topology and dependencies

No child POM carries a `<version>`. The exact Boot 4 artifact ids (the
Flyway starter in particular) are confirmed in the plan, not assumed here.

| Module | Gains | New dependencies |
|---|---|---|
| `domain` | `LoanPositionRepository`; `LoanPosition.restore(…)` | none |
| `application` | open, return and query use cases; the retry loop; the position-list projection port | `spring-tx` only |
| `adapter-persistence` | JPA entities, mappers, both repository implementations, the projection query, Flyway migrations under `db/migration` | Spring Data JPA starter, Flyway starter + its Postgres module |
| `bootstrap` | `@Configuration` wiring the use cases; the Postgres driver at runtime scope | `org.postgresql:postgresql` moves from test to runtime |

`application` classes carry `@Transactional` and no stereotype annotation.
They are plain classes that `bootstrap` instantiates with `@Bean`, so
`application` needs `spring-tx` and nothing from Boot. ArchUnit rule 3
(`@Transactional` lives only in `application`) now has real classes to
check, and it passes on them.

### `LoanPosition.restore`

It is a second static factory that takes every field, including status and
outstanding quantity. It enforces the aggregate's invariant before it
returns:

- `0 <= outstanding <= original`;
- `status` agrees with `outstanding`.

A row that violates the invariant is refused with an exception, never
repaired. `open` keeps its current contract. The class javadoc paragraph
that says stage 8 fixes the lost update with optimistic locking is reworded
to separate two different things:

- The in-memory object is not thread-safe. That is stage 8's subject.
- A row updated by two transactions is this stage's `@Version`.

### Use cases (`application`)

- **Open a position.**
  - Loads the counterparty's current positions and runs the credit check
    against its `CreditLimit`.
  - Creates the position and its `LoanTrade`.
  - The idempotency key is enforced by a unique constraint on
    `loan_trade.idempotency_key`, inside the transaction. Stage 5 verified
    that this is where the check belongs.
- **Return a quantity.** It loads the position, calls `returnQuantity`,
  saves it and books the trade, all in one transaction. A retry loop
  **outside** the transaction handles optimistic conflicts: a hand-written
  loop in `application` with a bounded number of attempts.
- **List positions.** It is paged, and it reads through the projection port,
  not the aggregate repository.

Daily accrual (stage 11) and revaluation (stage 10) are not implemented.
The `accrual_entry` table exists and test fixtures fill it.

### Schema and migrations

The production migrations under `adapter-persistence/src/main/resources/db/migration`:

- **V1** creates the tables:
  - `counterparty`
  - `security`
  - `loan_position`
  - `loan_trade`
  - `accrual_entry`
- **V2** adds `loan_position.version`, with a default for existing rows.
  Optimistic locking arrives as a migration, the way it would on a live
  system.
- **V3** adds the index that exercise 8 settles on.

Every money column is a `numeric` amount plus a `char(3)` currency, never
one without the other. Stage 5 warned that `numeric(19,6)` leaves 13
integer digits, which is too few for notionals, so money columns here are
`numeric(22,6)`. The decision record states the headroom it buys.

Accrual rules are persisted as `rule_type` plus `flat_rate`. Only
`FlatRate` is supported:

- `FloatingRate` holds a `ReferenceRates` service and is not a value.
- `MarginalTieredRate` would need a child table.

The mapper refuses either one with an explicit exception naming the type.
The decision record lists this as a known gap.

`spring.jpa.open-in-view=false` and `spring.jpa.hibernate.ddl-auto=validate`
are set in `bootstrap`'s `application.yaml`. Flyway owns the schema, and
Hibernate only checks it.

### The money invariant at every new column

The following amounts now cross into columns:

- collateral (`loan_position`);
- unit price (`loan_trade`);
- credit limit (`counterparty`);
- market price (`security`);
- fee (`accrual_entry`, now through JPA instead of `JdbcClient`).

Every mapper that reads money applies stage 5's rule. It builds the `Money`,
compares the result with the stored value, and refuses the row if
re-scaling would round. A round-trip test per column asserts amount, scale
and currency. At least one currency with a scale other than 2 is included
(`JPY`, `BHD`).

### Shared test infrastructure

`SharedPostgres` moves from `bootstrap`'s tests to `adapter-persistence`'s,
and is published as a `test-jar` that `bootstrap` consumes at test scope.
One container per test JVM stays the rule.

Stage 4's full-application tests currently exclude
`DataSourceAutoConfiguration`. The application now genuinely needs a
database, so the exclusion is removed and those tests run against
`SharedPostgres`.

### Manual infrastructure

`infra/compose.yaml` gains one Postgres service. It is used to run
`SlLedgerApplication` by hand and for exploratory `EXPLAIN`s. Its
credentials come from an env file that is not committed, and a committed
`.env.example` documents the variables. Automated tests never touch it.

### The eleven exercises

Test classes asserting the wrong behaviour are named `reproduces<Failure>`
and carry a comment pointing at `docs/decisions/06-persistence.md`.

Tests are placed in two modules:

- Persistence-only behaviour (exercises 2, 3, 4, 7, 8, 10, 11) lives in
  `adapter-persistence`'s tests.
- Anything that needs use-case wiring (1's application side, 5, 6, 9) lives
  in `bootstrap`.

1. **Flyway.**
   - V1–V3 as above.
   - The incompatible migration is never added to `db/migration`. It lives
     in a test-only Flyway location. It changes `idempotency_key` from
     `text` to `uuid`.
   - `reproducesBlueGreenBreakageOnColumnTypeChange` runs the "old
     version's" write against the migrated schema and asserts it fails. The
     old write binds a non-UUID key with `setString`.
   - The expand/contract sequence is three more test-only migrations. The
     test asserts that the old write and the new write both succeed after
     each of the first two steps:
     - add the new column;
     - dual-write and backfill;
     - drop the old column.

2. **N+1.**
   - Fixture: 100 positions, each with accrual entries.
   - SQL is counted with Hibernate `Statistics`, not by reading `show-sql`.
     `reproducesNPlusOne` asserts 101 statements.
   - Each fix gets its own test asserting both the statement count and that
     the loaded entries are complete and correct:
     - `JOIN FETCH`;
     - `@EntityGraph`;
     - `@BatchSize`.
   - `JOIN FETCH` combined with pagination is reproduced twice. The
     default version warns (`HHH90003004`) and paginates in memory, which
     the test shows by fetching every row. The second version sets
     `hibernate.query.fail_on_pagination_over_collection_fetch` and asserts
     the exception.

3. **Lazy loading.**
   - With `open-in-view` off, Jackson serializes a position entity after its
     transaction has closed. `reproducesLazyInitializationOnSerialization`
     asserts `LazyInitializationException`. This is the mechanism that would
     fire in a controller.
   - The same serialization is then wrapped in
     `OpenEntityManagerInViewInterceptor` over a `MockHttpServletRequest`.
     It now succeeds, and the statement count shows SQL issued after the
     service's transaction ended. That output is the evidence for why the
     default is a bad default.
   - The fix is a DTO projection, which serializes with no lazy access.

4. **First-level cache and dirty checking.**
   - Two `find`s of one id in one transaction issue one `SELECT`.
   - `reproducesAccidentalDirtyWrite`: a method meant only to read modifies
     a managed entity and never calls `save`. The change is committed.
   - The fix is `@Transactional(readOnly = true)`. The test asserts the
     change is not written, and records whether Hibernate skipped the flush
     or the dirty check.

5. **Optimistic locking.**
   - `@Version` works through the managed-entity mapping above. Two
     concurrent returns, sequenced with latches, produce one
     `ObjectOptimisticLockingFailureException`.
   - The retry loop outside the transaction lets both returns land.
   - A test shows why retry inside the transaction cannot succeed with JPA:
     the retry re-reads the stale entity from the first-level cache. Stage 5
     reasoned this but did not measure it.
   - `reproducesSilentOverwriteAcrossTransactions` covers the `MANDATORY`
     guard, as described above.
   - `@Retryable` is not introduced. It would add a dependency to replace a
     ten-line loop, and the loop makes the placement explicit.

6. **Pessimistic locking.**
   - The second adapter implementation loads with `PESSIMISTIC_WRITE`
     (`SELECT … FOR UPDATE`).
   - Throughput is compared across both adapters: a fixed thread count, a
     fixed number of returns, and two shapes:
     - one hot position;
     - returns spread across many positions.
   - The tests assert only correctness: final balances and the
     trade count. Durations, QPS and retry counts go to
     `docs/evidence/06-lock-throughput.txt`.
   - The decision record says when pessimistic wins. Two shapes are run
     because the answer is expected to differ between them.

7. **JPA vs `JdbcClient`.**
   - The position-list projection is implemented twice: Spring Data JPA and
     `JdbcClient`.
   - Both are asserted to return identical pages.
   - Generated SQL and lines of code are captured to evidence.
   - The decision record says which access paths in this project should
     bypass the ORM.

8. **Execution plans.**
   - 500,000 `accrual_entry` rows, generated with `generate_series`.
   - One date-range query, run three ways with
     `EXPLAIN (ANALYZE, BUFFERS)`:
     - no supporting index;
     - a composite index in the right column order;
     - the same columns reversed.
   - Each plan is saved to evidence.
   - Tests assert the plan's scan type (index vs sequential), never a
     duration.
   - Prediction: the unique `(position_id, business_date)` index already
     exists, looks as if it covers a date-range query, and does not. The
     tests confirm or refute this.
   - V3's index is the one this exercise selects.

9. **Connection pool.**
   - Pool size 2 with a short `connectionTimeout`.
   - More concurrent use-case calls than connections, each held open by a
     latch.
   - Asserts `SQLTransientConnectionException` as the root cause, and
     records the measured wait.
   - The decision record gives the sizing reasoning: cores, transaction
     duration, and why stage 5's `REQUIRES_NEW` doubles the need. It uses
     this machine's core count.

10. **Hand-written SQL.** Each query has its SQL in a file and a test with
    fixture data built so the assertion discriminates.
    - **Window functions**: rank each counterparty's positions by market
      value and take the top three. The fixture includes ties, so
      `ROW_NUMBER`, `RANK` and `DENSE_RANK` give three different asserted
      results.
    - **Recursive CTE**: roll collateral up a pool hierarchy.
      `collateral_pool` is not one of the six domain concepts, so the table
      exists only in the test schema, never in a production migration.
    - **Self-join plus date ranges**: consecutive holding days per position,
      as gaps-and-islands over `accrual_entry.business_date`. A position
      accrues on every day it is held.
    - Each query is `EXPLAIN`ed and timed against an application-side
      equivalent at 1k, 10k and 100k rows. The decision record states where,
      measured, the two cross over, or that they did not cross within that
      range.

11. **Second-level cache.** It stays off. A test asserts it is disabled on
    the `EntityManagerFactory`. The decision record says why for this
    project: a per-node cache with no cross-node invalidation. Stage 9's
    Redis is where that is solved.

### Evidence

`docs/evidence/06-*.txt`, text only, measured on this machine:

- the blue/green failure output;
- statement counts per N+1 variant;
- the post-transaction SQL under OSIV;
- lock throughput for both adapters and both shapes;
- the two projection implementations' SQL;
- the three `EXPLAIN` plans;
- pool wait time;
- each hand-written query's plan and the crossover timings.

## Out of scope

- Daily accrual and revaluation use cases (stages 11 and 10).
- Persisting `FloatingRate` and `MarginalTieredRate`.
- A cross-request version in `domain`, or any `If-Match` handling (stage 7,
  if an overwrite-style endpoint appears).
- `@Retryable` / Spring Retry.
- Second-level cache, and any cache (stage 9).
- Web endpoints (stage 7). Exercise 3 uses an interceptor and a mock request.
- Loading the holiday calendar from the database. `HongKongHolidays` and
  `SettlementDates` javadocs name stage 6 for it. That is not agreed here,
  so their comments are corrected to say the replacement is unscheduled.
- In-memory thread safety of `LoanPosition` (stage 8).

## Constraints

- `domain` gains only `LoanPositionRepository` and `restore`, and stays free
  of every third-party dependency.
- `./mvnw verify` at the repository root stays green after every section.
- The broken variants in exercises 1 and 10 never enter
  `src/main/resources/db/migration`.
- Reproduced failures are asserted by specific exception type or SQLState.
- Concurrency (exercises 5, 6, 9) is sequenced with latches or barriers,
  never with `Thread.sleep`. Every blocking scenario has a bound.
- Numbers in the decision record come from evidence files, measured on this
  machine.
- No secret in any committed file. The Compose credentials live in an
  uncommitted env file.
- All data is synthetic.

## Acceptance criteria

1. `SlLedgerApplication` starts against `infra/` Postgres. Flyway applies
   V1–V3, and Hibernate's `validate` passes.
2. `domain`'s enforcer rule still passes, and `domain` has no new dependency.
3. `ArchitectureIT` passes with `application` and `adapter-persistence`
   holding real classes. Rule 3 finds `@Transactional` only in
   `application`.
4. `LoanPosition.restore` refuses every invariant-violating combination
   tested. `open` behaves as before.
5. Every money column has a passing round-trip test, and over-precise
   stored amounts are refused on read.
6. Both repository implementations pass the same contract test, and the
   use cases pass under either one.
7. Stage 4's full-application tests run against `SharedPostgres` with no
   `DataSourceAutoConfiguration` exclusion.
8. Each of the eleven exercises has its test class, with the assertions
   stated above, green.
9. The evidence files listed above exist and hold measured output.
10. `./mvnw verify` at the repository root is green.

## Done when

The four `CLAUDE.md` deliverables exist: this spec, the plan, the code with
every criterion above verified, and `docs/decisions/06-persistence.md`. The
decision record must cover:

- the three settled decisions, argued against their alternatives;
- the blue/green failure and the expand/contract sequence;
- the N+1 statement counts and the `JOIN FETCH` pagination trap;
- why `open-in-view` defaults badly, from the measured post-transaction SQL;
- the retry placement, measured under JPA;
- optimistic vs pessimistic throughput in both shapes, and when to pick
  pessimistic;
- where this project bypasses the ORM;
- the index-order finding;
- pool sizing;
- each hand-written query's crossover;
- why the second-level cache stays off;
- where each choice breaks at 100x.

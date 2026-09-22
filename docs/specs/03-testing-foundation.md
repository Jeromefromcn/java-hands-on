# Stage 03 — Testing Foundation

## Problem

Stage 4 introduces the container. From that point on, every stage's
acceptance check leans on the test suite instead of on reading code by eye —
and the suite itself has had no attention paid to it yet. `domain`'s 90-odd
tests are flat `@Test` methods with no shared assertion vocabulary, nothing
has verified that the module boundaries stage 1 drew in Maven are also true
at the package level once code exists to violate them, and nothing stops
coverage from becoming a number nobody trusts. This stage is entirely test
infrastructure — no new domain behaviour — because everything after it is
audited by tooling put in place here.

## Scope

### JUnit 5 organisation, retrofit onto existing `domain` tests

- `@Nested` groups on at least one existing test class where the flat method
  list already mixes unrelated fixtures — `MoneyTest` (construction rules) and
  `MoneyArithmeticTest` (operations) are the candidates; group by the method
  under test, not by happy/unhappy path, since the state-machine and
  precision tests already name the scenario in the method name.
- `@ParameterizedTest` + `@CsvSource` replacing `AccrualRuleTest`'s hand-rolled
  boundary cases for `TieredRate` where the same assertion shape repeats
  across threshold values — one table, one assertion, not one method per row.
- `@DisplayName` written as the business sentence, on the classes touched
  above only. Not a blanket pass over 90 tests: retrofitting untouched tests
  is scope creep this stage doesn't need.

### AssertJ: a custom `MoneyAssert`

`MoneyAssert extends AbstractAssert<MoneyAssert, Money>` in
`domain/src/test/java`, entry point `MoneyAssertions.assertThat(Money)` (a
name distinct from `Assertions.assertThat` so both can be statically
imported). At minimum: `isEqualTo(String)` parsing `"12.34 HKD"` against
`amount()` and `currency()` together, and `hasCurrency(Currency)`. Retrofit
it onto `AccrualEntryTest` and `AccrualPrecisionTest`, which currently compare
`Money` by unpacking `amount()` and `currency()` separately — the two-line
comparison collapsing into the one-line, currency-aware assertion is the
acceptance criterion, not the class existing in isolation.

### Mockito: the over-mock, then the boundary

Two test classes against `FloatingRate` (`accrual/FloatingRate.java`, which
already depends on the one-method `ReferenceRates` interface — the only real
collaborator boundary `domain` has, since it takes no I/O):

- `FloatingRateOverMockedTest` — mocks `Rate` and `Money` in addition to
  `ReferenceRates`. Both are immutable records with no behaviour to fake;
  mocking them buys nothing and the test still has to `when(...)` their
  trivial accessors. Kept, not deleted, so the decision record can point at
  it as the negative example.
- `FloatingRateTest` — mocks only `ReferenceRates` (the actual boundary: a
  future market-data call), constructs `Rate` and `Money` for real. Same
  assertions, less setup.

Both compile and pass; `verify` doesn't fail on either. The decision record
states the rule the comparison establishes: mock what crosses a boundary you
don't control (I/O, a clock, a random source), never a value type your own
module defines.

### ArchUnit, three rules, bound to `verify`

New module-local test dependency in `bootstrap` (the one module with every
other module on its compile classpath, transitively: `bootstrap` →
`adapter-persistence` → `application` → `domain`, plus `adapter-web` and
`batch` direct). `archunit-junit5` at test scope.

Three rules, one class, `ArchitectureTest`:

1. `domain` package doesn't depend on any `adapter` package.
2. `adapter.web` doesn't depend on `adapter.persistence`.
3. Every class annotated `@Transactional` lives in the `application` package.

Rules 1–3 are checked now while `application`, `adapter-persistence` and
`adapter-web` hold no classes — they pass vacuously, which is stated in the
test's `@DisplayName` and in the decision record, not hidden. The point of
writing them now rather than in stage 4 or 5 is that the first class that
would violate rule 1 or 3 fails the build the moment it's written, not after
a review misses it.

Bound to `maven-failsafe-plugin`'s `integration-test`/`verify` phases (new
plugin, pinned in root `pluginManagement`), not `surefire`/`test`: these are
architecture checks, not unit tests, and `IT`-suffixed naming keeps them out
of `mvn test`'s faster inner loop. `./mvnw verify` at the repository root is
the acceptance command, per `CLAUDE.md`.

### JaCoCo, bound to `verify`, with a stated threshold

`jacoco-maven-plugin` in root `pluginManagement`, bound in `domain` only
(the only module with source to cover). `prepare-agent` at `initialize`,
`report` at `test`, `check` at `verify` with a line-coverage minimum. The
number and its justification are a decision-record deliverable, not a
default: this codebase's existing behaviour tests already exercise every
production line through the domain, since `domain` has no framework
plumbing, no getters nobody calls, and no dead branches — so the honest
threshold is close to what the suite already achieves, stated as a floor
that would catch a large regression, not chosen to look impressive.

A companion example: one class or method with 100% line coverage from a test
that asserts nothing meaningful (calls the method, asserts no exception) —
`Counterparty`'s canonical constructor is the candidate, since a test that
merely constructs it covers every line without checking a single field. Kept
as a named test demonstrating the gap between coverage and verification, with
a comment pointing at the decision record.

### `docs/decisions/test-doubles.md`

Stub / mock / fake / spy, each with the concrete class from this codebase
that is that kind of double — not the general definition. Candidates already
in the suite: the inline `HolidayCalendar` lambdas in `SettlementDatesTest`
(stub), the `ReferenceRates` mock in the new `FloatingRateTest` (mock, because
its call is verified, not just stubbed), and whichever of the two new
`FloatingRate` tests demonstrates a fake if one is written (a real in-memory
`ReferenceRates` implementation, as opposed to a Mockito double) — added only
if it clarifies the distinction, not to force all four categories to exist.

### Cucumber: one feature

`cucumber-junit-platform-engine` at test scope, `domain` module, wired so
`./mvnw -pl domain test` picks it up via the JUnit Platform Suite Engine (one
`@Suite` class with `@IncludeEngines("cucumber")` and
`@SelectClasspathResource("features")`). One `.feature` file:

```gherkin
Feature: Loan repayment reduces outstanding balance
  Scenario: Partial return after a multi-day loan
    Given a counterparty borrows 1000 shares
    And 3 days pass
    When the counterparty returns 400 shares
    Then the outstanding balance is 600 shares
    And accrual has been booked for 3 days
```

Step definitions call the same `LoanPosition` and `AccrualEntry` types the
existing unit tests use — no new domain API for Cucumber's sake. The
deliverable is being able to say, when asked, what this bought over the
equivalent `@Test` method: a scenario a non-engineer reader can follow, at
the cost of an indirection layer between the sentence and the assertion.

### Decision content only, no code

- **Coverage threshold rationale** — folded into the JaCoCo section above,
  written in `docs/decisions/03-testing-foundation.md`.
- **Test pyramid ratio for this project**, and why a securities-lending
  ledger's integration-test share is larger than a typical web CRUD app's:
  the domain invariants (rounding, state transitions) are cheap to unit test
  and mostly are already; what unit tests structurally cannot catch is a
  wrong transaction boundary, a lost update under real concurrency, or a
  serialization format silently breaking the money invariant — all three
  arrive with a real Postgres or a real thread pool, which is what stage 6
  and stage 8's Testcontainers-backed suites are for. This links forward to
  stage 6's pass-line rather than repeating stage 6's content here.

## Out of scope

- Any Spring annotation, anywhere. The enforcer rule from stage 1 still
  applies to `domain`; the other modules stay empty except for the new
  `ArchitectureTest` in `bootstrap`, which is plain JUnit 5 + ArchUnit.
- New domain behaviour. Every test added exercises existing `domain` types.
- Retrofitting `@Nested`/`@ParameterizedTest`/`@DisplayName` across all
  ~90 existing tests — two illustrative classes are enough to demonstrate the
  organisation; the rest stay as they are.
- Testcontainers, a real database, a real message broker — nothing in this
  stage needs I/O. That starts in stage 6.
- A second Cucumber feature. One is the deliverable; more is scope creep the
  spec explicitly declines.

## Constraints

- `domain` still takes no third-party dependency outside test scope. The new
  test dependencies (Mockito, ArchUnit's `domain`-side usage if any, Cucumber)
  are all test scope, from the imported BOM where it governs them.
- `-Xlint:all -Werror` still applies; a `clean verify` is still the
  acceptance command for anything touching `domain`.
- `./mvnw verify` at the repository root stays green throughout, including
  the new failsafe-bound ArchUnit run.
- All data synthetic, as before; the Cucumber scenario's numbers are
  fabricated for readability, not drawn from a real trade.

## Acceptance criteria

1. `MoneyTest` and `MoneyArithmeticTest` (or their post-`@Nested` successors)
   pass under `./mvnw -pl domain clean verify`, organised with `@Nested` by
   method under test.
2. `AccrualRuleTest`'s `TieredRate` boundary cases run as one
   `@ParameterizedTest` with `@CsvSource`, same assertions as before,
   verified equal in count and content to what they replace.
3. `MoneyAssertions.assertThat(money).isEqualTo("12.34 HKD")` compiles and is
   used in `AccrualEntryTest` and `AccrualPrecisionTest` in place of the
   previous two-line comparisons.
4. `FloatingRateOverMockedTest` and `FloatingRateTest` both pass; the latter
   mocks only `ReferenceRates`.
5. `./mvnw verify` at the repository root runs `ArchitectureTest`'s three
   rules in the failsafe phase and is green; deleting a rule and reproducing
   a violation (a throwaway `@Transactional` class outside `application`) is
   evidenced in `docs/evidence/03-archunit-violation.txt`, then reverted —
   same procedure stage 2 used for the sealed-interface gate.
6. `./mvnw -pl domain verify` runs the JaCoCo `check` goal and fails the build
   if coverage drops below the recorded threshold; the threshold and the
   measured actual are both in `docs/evidence/03-jacoco-coverage.txt`.
7. The 100%-covered-but-meaningless test exists, is named to say so, and its
   existence is explained in the decision record.
8. `docs/decisions/test-doubles.md` names all four double kinds against a
   concrete class in this codebase.
9. `./mvnw -pl domain test` runs the one Cucumber scenario via the JUnit
   Platform Suite Engine and it passes; its console output is in
   `docs/evidence/03-cucumber-run.txt`.
10. `./mvnw verify` at the repository root is green.

## Done when

The four `CLAUDE.md` deliverables exist: this spec, the plan, the code with
every criterion above verified, and `docs/decisions/03-testing-foundation.md`
carrying the coverage-threshold rationale, the test-pyramid ratio and why
this domain's integration share is larger than a typical CRUD app's, the
mock-boundary rule established by the `FloatingRate` comparison, and a
pointer to `docs/decisions/test-doubles.md`.

# Stage 03 — Testing Foundation · Implementation Plan

Spec: [`docs/specs/03-testing-foundation.md`](../specs/03-testing-foundation.md)

One section per commit, in order. Implement a section, tick its checkbox in
the same commit, then stop for review. `./mvnw -pl domain clean verify` after
every `domain`-touching section; `./mvnw verify` at the repository root after
every section that touches `bootstrap` or the parent POM, since that is what
the failsafe-bound ArchUnit rules run under.

## Ground rules for every section

- No new domain behaviour. Every test targets an existing `domain` type.
- `-Xlint:all -Werror` still applies to `domain`. A `clean verify`, not an
  incremental one — stage 2 section 3 recorded a wrong finding from skipping
  that.
- New third-party test dependencies get their version from the imported BOM
  where the BOM manages them (Mockito does; ArchUnit, Cucumber and JaCoCo's
  plugin do not and need an explicit `<version>` pinned as a property in the
  parent POM, per stage 1's rule that no plugin resolves unpinned).

---

## [x] 1 · Dependencies and plugins, no code yet

Parent `pom.xml`:

- New properties: `archunit.version` (1.5.0 — `com.tngtech.archunit`, not
  `org.archunit`; the plan's first draft had the wrong groupId, caught by
  dependency resolution failing on `bootstrap`), `cucumber.version` (7.20.1),
  `jacoco-maven-plugin.version` (0.8.12), `maven-failsafe-plugin.version`
  (matching the pinned `maven-surefire-plugin.version`, 3.5.2 — failsafe and
  surefire ship in lockstep from the same Maven Surefire project).
- `dependencyManagement`: `com.tngtech.archunit:archunit-junit5:${archunit.version}`,
  `io.cucumber:cucumber-java:${cucumber.version}`,
  `io.cucumber:cucumber-junit-platform-engine:${cucumber.version}`. Mockito
  needs no entry — `mockito-bom` arrives transitively through
  `spring-boot-dependencies`, and `org.junit.platform:junit-platform-suite-engine`
  needs none either, for the same reason via `junit-bom`.
- `pluginManagement`: `maven-failsafe-plugin` bound to `integration-test` and
  `verify` goals (`integration-test`, `verify`), and `jacoco-maven-plugin`
  bound to `initialize` (`prepare-agent`), `test` (`report`) — the `check`
  execution and its threshold arrive in section 7, once there is a number to
  put in it. Both added to `pluginManagement` only; no module opts in yet.

`domain/pom.xml` gains, all `test` scope, no `<version>`:
`org.mockito:mockito-junit-jupiter`, `org.junit.platform:junit-platform-suite`,
`io.cucumber:cucumber-java`, `io.cucumber:cucumber-junit-platform-engine`.

`bootstrap/pom.xml` gains `com.tngtech.archunit:archunit-junit5` at `test` scope, and
adopts the `maven-failsafe-plugin` execution from `pluginManagement` in its
own `<build><plugins>` — the only module that does, since it is the only
module with a rule to run in this stage.

No test class exists yet. This section only has to compile.

**Acceptance** — `./mvnw verify` at the repository root is green;
`./mvnw dependency:tree -pl domain` shows the four new dependencies at `test`
scope; `./mvnw dependency:tree -pl bootstrap` shows `archunit-junit5` at
`test` scope.

## [x] 2 · `@Nested` and `@DisplayName` on `Money`'s tests

`MoneyTest` becomes two `@Nested` classes: `Construction` (normalisation,
rounding, the fraction-digit edge cases, null rejection) and
`UnsupportedCurrencies` (the XAU/XXX refusal, kept separate because it is a
different failure mode from the rounding tests around it, not because the
class is otherwise too big). `MoneyArithmeticTest` becomes `Addition`,
`Subtraction`, `Comparison`, `Multiplication` — one `@Nested` class per
operation, matching the method groups already implicit in the file's
ordering. Every existing `@Test` moves into exactly one nested class; none is
rewritten, renamed beyond its new location, or reasserted differently. A
`@DisplayName` is added to each nested class and to `MoneyTest`/
`MoneyArithmeticTest` themselves, written as the business sentence — e.g.
`@DisplayName("adding two amounts in the same currency")` — not restating the
method name.

**Acceptance** — `./mvnw -pl domain clean verify` green; test count for both
classes unchanged from before the move (assert by reading the surefire
summary before and after); running `./mvnw -pl domain test -Dtest=MoneyTest`
prints the nested class names in the output.

## [x] 3 · `@ParameterizedTest` on the tiered-rate boundary

In `AccrualRuleTest`, `aTieredRateChargesEachSliceAtItsOwnRateNotTheWholeValueAtTheTopRate`
and `atTheThresholdNothingHasCrossedIntoTheUpperTier` collapse into one
`@ParameterizedTest(name = "{0} -> daily fee {1}")` with `@CsvSource`:

```
2000000.00, 300
1000000.00, 100
```

`oneCentAboveTheThresholdIsChargedOnThatOneCentOnly` stays a standalone
`@Test`: it carries a second assertion (strictly greater than the
at-threshold figure) that doesn't fit the same row shape, and forcing it in
would hide that extra check inside a CSV cell. The hand-computed comment
above each figure moves with its row, restated as a comment above the
`@CsvSource` block rather than lost.

**Acceptance** — `./mvnw -pl domain clean verify` green; the parameterized
test reports 2 invocations in the surefire output; the total assertion count
for `AccrualRuleTest` is unchanged from before the collapse (two tests
removed, one parameterized test with two invocations added).

## [x] 4 · `MoneyAssert`

`domain/src/test/java/.../money/MoneyAssertions.java` (entry point) and
`MoneyAssert.java` (`extends AbstractAssert<MoneyAssert, Money>`):

```java
public static MoneyAssert assertThat(Money actual)
public MoneyAssert isEqualTo(String expected)   // "12.34 HKD" -> Money.of + AssertJ isEqualTo
public MoneyAssert hasCurrency(Currency expected)
```

Parses `expected` by splitting on the single space; a malformed string throws
`IllegalArgumentException` from the assertion helper itself (a test bug,
not a domain concern — no `DomainException` involved).

Test for the assertion class itself,
`MoneyAssertTest`: one case proving `isEqualTo` fails with a readable message
when currencies differ, one proving it fails when amounts differ, one proving
`hasCurrency` passes and fails correctly. An assertion helper with no test of
its own is exactly the kind of test-support code that silently stops
asserting anything.

Then retrofit: in `AccrualEntryTest`, every
`assertThat(entry.fee()).isEqualTo(Money.of("X", CCY))` becomes
`MoneyAssertions.assertThat(entry.fee()).isEqualTo("X CCY")`. In
`AccrualPrecisionTest`, the `bigDecimalTotal` assertion in
`reproducesDoubleDrift` does the same. `MoneyAssertions.assertThat` is called
fully qualified in both, **not** statically imported alongside AssertJ's own
`Assertions.assertThat` — a single unqualified `assertThat` imported from both
classes is ambiguous to javac (an exact-match `assertThat(Money)` competes
with AssertJ's generic `<T> assertThat(T)`, and neither is more specific by
the overload-resolution rules), caught by a compile error on the first
attempt. `MoneyAssertTest` itself has no such conflict — it only needs
`MoneyAssertions.assertThat` — so it statically imports that one alone.

**Acceptance** — `./mvnw -pl domain clean verify` green; `git diff` on
`AccrualEntryTest` and `AccrualPrecisionTest` shows only assertion-line
changes, no behavioural change; `MoneyAssertTest` passes with all four
cases.

## [ ] 5 · Mockito: over-mock, then the boundary

`FloatingRateOverMockedTest`, `@ExtendWith(MockitoExtension.class)`: mocks
`ReferenceRates`, `Rate`, and `Money`, stubs `Rate`'s and `Money`'s trivial
accessors with `when(...)`, asserts the same outcome
`AccrualRuleTest.aFloatingRateTakesTheReferenceOfTheDayAndAddsItsSpread`
already covers. A comment at the top states this class exists as the
negative example and points at the decision record.

`FloatingRateTest`, same `@ExtendWith(MockitoExtension.class)`: mocks only
`ReferenceRates` via `@Mock`, constructs real `Rate` values, computes on a
real `BigDecimal`, and additionally verifies the interaction —
`verify(reference).on(ANY_DAY)` — which `AccrualRuleTest`'s lambda-based stub
could not do, since a lambda isn't spied on. That verification is the
concrete thing Mockito buys here that a hand-written stub does not.

Both classes pass. Neither replaces `AccrualRuleTest`'s existing floating-rate
test, which stays as the plain-stub baseline for the decision record's
stub-vs-mock comparison.

**Acceptance** — `./mvnw -pl domain clean verify` green with both new classes
passing; `FloatingRateTest` fails if the `verify(reference).on(...)` line is
temporarily deleted and `FloatingRate` is changed to never call `reference`
(a scratch edit, reverted, not committed) — confirming the verification is
load-bearing and not decorative.

## [ ] 6 · `ArchitectureTest`, three rules, failsafe

`bootstrap/src/test/java/com/jerome/slledger/bootstrap/ArchitectureTest.java`
(plain JUnit 5 + ArchUnit, no Spring):

```java
class ArchitectureTest {
  private static final JavaClasses CLASSES =
      new ClassFileImporter().importPackages("com.jerome.slledger");

  @Test @DisplayName("domain does not depend on any adapter (vacuous: no adapter classes exist yet)")
  void domainDoesNotDependOnAdapters() { ... }

  @Test @DisplayName("adapter-web does not depend on adapter-persistence (vacuous: neither has classes yet)")
  void webDoesNotDependOnPersistence() { ... }

  @Test @DisplayName("@Transactional lives only in application (vacuous: no @Transactional class exists yet)")
  void transactionalOnlyInApplication() { ... }
}
```

Named `ArchitectureTest`, not `ArchitectureIT` — Failsafe's default inclusion
pattern is `**/*IT.java`, `**/IT*.java`, `**/*ITCase.java`; renaming to match
is simpler than reconfiguring the pattern, so the class is renamed to
`ArchitectureIT` before the acceptance check, not left relying on a custom
`<includes>`.

**Acceptance** — `./mvnw verify` at the repository root runs `ArchitectureIT`
in the `integration-test` phase (visible in the Failsafe log block, separate
from the Surefire block) and is green.

## [ ] 7 · The ArchUnit violation, reproduced and reverted

Scratch edit, not committed: add a throwaway class
`bootstrap/src/main/java/.../ScratchTransactional.java` annotated
`@Transactional` (a hand-rolled no-op annotation if Spring's isn't on the
classpath yet — check first; if only Jakarta's or Spring's is resolvable
already via a transitive dependency, use that one and say which in the
evidence file). Run `./mvnw verify`, capture the ArchUnit failure message
naming the offending class, save to
`docs/evidence/03-archunit-violation.txt`. Delete the scratch class. Run
`./mvnw verify` again to confirm green. `git status` clean of the deletion
before committing this section — same procedure as stage 2's sealed-interface
gate and stage 1's compiler gate.

**Acceptance** — `docs/evidence/03-archunit-violation.txt` holds the failure
output and the command that produced it; `git status` shows no
`ScratchTransactional` file; `./mvnw verify` is green.

## [ ] 8 · JaCoCo coverage gate

`domain/pom.xml` adopts `jacoco-maven-plugin` from `pluginManagement` and adds
a `check` execution bound to `verify`, with a `RULE` at `BUNDLE` level and a
`LINE` counter minimum. Run `./mvnw -pl domain clean verify` once first
without a threshold to read the actual current line coverage from
`target/site/jacoco/index.html` or the console summary; set the configured
minimum a few points below that measured figure — not at 100%, and not
picked before measuring. The gap and the reasoning (this module has no
framework plumbing to inflate or deflate the number, so the honest floor is
close to actual, chosen to catch a large regression rather than to look
impressive) goes in `docs/evidence/03-jacoco-coverage.txt` alongside the
measured percentage.

Add `CounterpartyConstructionCoversEveryLineAssertsNothing` (or similarly
named) to `CounterpartyTest`: constructs a `Counterparty`, asserts only that
construction doesn't throw. A comment states it covers every line of the
canonical constructor and is kept as the named counter-example to coverage-as
-verification, pointing at the decision record.

**Acceptance** — `./mvnw -pl domain clean verify` fails if the threshold in
the `pom.xml` is temporarily raised above the measured figure (a scratch
edit, reverted) and passes at the committed threshold;
`docs/evidence/03-jacoco-coverage.txt` states both numbers.

## [ ] 9 · `docs/decisions/test-doubles.md`

Stub, mock, fake, spy, each against a named class already in this test suite:

- **Stub** — the inline `HolidayCalendar` lambdas in `SettlementDatesTest`
  (e.g. `date -> date.isAfter(tradeDate)`): a canned answer, no verification
  of how it was called.
- **Mock** — `FloatingRateTest`'s `@Mock ReferenceRates`, verified with
  `verify(reference).on(...)`: the call itself is part of the assertion.
- **Fake** — write one if the comparison earns its place: a real in-memory
  `ReferenceRates` implementation backed by a `Map<LocalDate, Rate>`, used
  wherever a test currently constructs a lambda that does more than return a
  constant. If no existing test benefits from replacing its lambda, state
  that in the document instead of manufacturing a use — the spec permits
  this.
- **Spy** — not currently used anywhere in this suite; the document says so
  and gives the shape it would take (wrapping a real `AccrualRule`
  implementation to record call counts) rather than inventing a use to fill
  the category.

**Acceptance** — the document names a concrete class or file for stub and
mock, and is explicit about fake and spy rather than padding.

## [ ] 10 · One Cucumber scenario

`domain/src/test/resources/features/loan-repayment.feature`:

```gherkin
Feature: Loan repayment reduces outstanding balance
  Scenario: Partial return after a multi-day loan
    Given a counterparty borrows 1000 shares
    And 3 days pass
    When the counterparty returns 400 shares
    Then the outstanding balance is 600 shares
    And accrual has been booked for 3 days
```

`domain/src/test/java/.../cucumber/LoanRepaymentSteps.java` — step
definitions driving `LoanPosition.open`, `LoanPosition.returnQuantity`, and
`AccrualEntry.accrue` in a loop for the day count, holding state (the
`LoanPosition` instance, an accrual entry list) as instance fields the way
Cucumber's per-scenario object lifecycle expects.

`domain/src/test/java/.../cucumber/CucumberSuite.java`:

```java
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
class CucumberSuite {}
```

**Acceptance** — `./mvnw -pl domain test` runs the scenario through the
JUnit Platform Suite Engine (visible as `CucumberSuite` in the Surefire
summary) and it passes; console output saved to
`docs/evidence/03-cucumber-run.txt`.

---

## Then

`docs/decisions/03-testing-foundation.md` — chose / rejected / observed /
where it breaks at 100x. It carries:

- the JaCoCo threshold and the measured actual it was set against (section 8)
- the meaningless-coverage example and what it demonstrates
- the mock-boundary rule from comparing `FloatingRateOverMockedTest` against
  `FloatingRateTest` (section 5): mock what crosses a boundary this module
  doesn't control, never a value type this module defines
- a pointer to `docs/decisions/test-doubles.md`
- the test-pyramid ratio for this project and why the integration share is
  larger than a typical CRUD app's, linking forward to stage 6's pass line
- the ArchUnit rules' vacuous-today status, stated plainly, and what each one
  will catch once `application`/`adapter-web`/`adapter-persistence` gain code

# Stage 03 — Testing Foundation — Decision Record

Spec: [`docs/specs/03-testing-foundation.md`](../specs/03-testing-foundation.md)
Plan: [`docs/plans/03-testing-foundation.md`](../plans/03-testing-foundation.md)

## What I chose

**`@Nested` grouped by the method under test, not by happy/unhappy path.**
`MoneyTest` splits into `Construction` and `UnsupportedCurrencies`;
`MoneyArithmeticTest` splits into `Addition`, `Subtraction`, `Comparison`,
`Multiplication`. The state-machine and precision tests elsewhere already
name the scenario in the method name, so grouping by outcome would have
duplicated information the method name already carries; grouping by
operation is the axis that wasn't already visible in a flat list.

**`MoneyAssertions.assertThat(Money)` is called fully qualified, never
statically imported unqualified alongside AssertJ's own `assertThat`.** A
single unqualified `assertThat` imported from both `MoneyAssertions` and
`Assertions` is ambiguous to javac — `assertThat(Money)`'s exact-type match
competes with AssertJ's generic `<T> assertThat(T)` instantiated at `T =
Money`, and neither wins under Java's most-specific-method rule. Caught at
compile time on the first attempt, not discovered as a style preference.

**Mock what crosses a boundary this module doesn't control; never a value
type this module defines.** Established by `FloatingRateOverMockedTest`
against `FloatingRateTest`: mocking `Rate` and `Money` (in addition to the
one real boundary, `ReferenceRates`) costs six `when(...)` stubs to reach the
same single assertion the boundary-only version reaches with one, and every
one of those six stubs exists only because the object it stubs was mocked
instead of constructed — none of it exercises real behaviour. `Rate` and
`Money` are immutable value types with no I/O, no clock, no randomness; there
is nothing about them a mock earns over the real thing.

**A verification note, not a stronger claim than the evidence supports.** A
scratch edit that made `FloatingRate.dailyFee` skip calling `reference.on(on)`
entirely failed `FloatingRateTest` on the *value* assertion, before execution
reached `verify(reference).on(DAY)` — because the wrong rate also produces
the wrong fee. The interaction and the outcome are causally linked in this
scenario, so `verify(...)` could not be isolated as the one line catching
that particular regression. What it demonstrably buys instead: a direct
assertion that the mechanism worked (the day actually reached the
collaborator), independent of whether the value assertion would also have
caught the same defect. Value assertions and interaction verification often
overlap rather than being strictly additive, and this stage's evidence
supports that claim and not a stronger one.

**ArchUnit rules are written now, before there is anything to violate, and
say so.** `ArchitectureIT`'s three rules pass vacuously today —
`application`, `adapter-persistence` and `adapter-web` hold no classes yet.
Written now rather than when the first violating class appears, because the
first class that actually violates rule 1 (`domain` importing an `adapter`
type) or rule 3 (`@Transactional` outside `application`) then fails the build
the moment it is written, not after a code review misses it. Each is checked
by fully-qualified string name (`"org.springframework.transaction.annotation.Transactional"`)
rather than the annotation class itself, since ArchUnit inspects bytecode and
neither Spring nor Jakarta is a dependency anywhere in this tree until
stage 4 — the rule compiles and is meaningful before its subject exists.

**The JaCoCo threshold is set from a measurement, not a round number.**
Measured line coverage the day this was written: 96.68% (320/331 lines).
Committed threshold: 94%. `domain` carries no framework plumbing to inflate
or deflate the figure — no unused getters, no generated code, no dead
configuration branches — so the honest floor sits close to what the suite
already achieves, with enough gap below the measured figure to survive a
small, legitimate addition (a new branch, not yet exercised the same day
it's written) without failing the build for something that isn't a
regression. Not 100%: `LoanPosition`'s defensive guards account for most of
the eleven missed lines, and a domain type is allowed a guard that is
expensive to trigger from a single-threaded unit test without that absence
being something this gate should block the build over. Confirmed
load-bearing by temporarily raising the threshold to 99% and watching the
build fail (reverted, not committed) — see
[`docs/evidence/03-jacoco-coverage.txt`](../evidence/03-jacoco-coverage.txt).

## What I rejected, and why

**Retrofitting `@Nested`/`@ParameterizedTest`/`@DisplayName` across all
existing tests.** Two illustrative classes (`MoneyTest`,
`MoneyArithmeticTest`) and one parameterized collapse
(`AccrualRuleTest`'s tiered-rate boundary) demonstrate the organisation; the
remaining ~30 test methods elsewhere stay untouched. A blanket pass would
have been scope this stage's spec explicitly declined, for no reader benefit
over the two worked examples.

**A fake `ReferenceRates` and a spy, manufactured to fill out
`test-doubles.md`'s four categories.** No test in this suite currently needs
either. `docs/decisions/test-doubles.md` states that plainly for both,
with the concrete shape a spy would take once stage 11's batch job needs to
assert "every active position was touched exactly once" — the first real
need for one on this roadmap — rather than inventing a use now to avoid an
honest "not used yet."

**A second Cucumber feature.** One scenario demonstrates the mechanism and
its cost (an indirection layer between the sentence and the assertion,
justified for a business scenario a non-engineer reads, not for the ~35
other behavioural tests where the reader is another engineer and a
`@DisplayName`-annotated `@Test` already reads as a sentence). More features
would have been the padding the spec's "Out of scope" section named directly.

## What I observed

### The JaCoCo coverage gate

See [`docs/evidence/03-jacoco-coverage.txt`](../evidence/03-jacoco-coverage.txt)
for the full breakdown. Summary: 96.68% measured, 94% committed threshold,
missed lines concentrated in `LoanPosition` (7 of 11) — its stated-but-
unproven thread-safety guards from `docs/decisions/02-domain-model.md`.

### The meaningless-coverage counter-example

`CounterpartyTest.constructionCoversEveryLineOfTheConstructorButAssertsNothingAboutTheResult`
walks the identical lines `aCounterpartyCarriesItsOwnLimit` already walks,
and checks nothing beyond "construction did not throw." JaCoCo's line counter
marks both tests' lines green identically — coverage tooling has no
mechanism to distinguish a test that verifies from one that merely visits.
The gap has to be caught by reading the test, which is exactly what this
named counter-example makes visible in the suite itself rather than only
assert in prose.

### Test doubles

See [`docs/decisions/test-doubles.md`](test-doubles.md) — stub
(`SettlementDatesTest`'s inline `HolidayCalendar` lambdas) and mock
(`FloatingRateTest`'s verified `ReferenceRates`) against concrete classes in
this suite; fake and spy stated as not currently used, with a spy's shape
sketched against stage 11's batch job.

### Test pyramid ratio for this project, and why the integration share is larger

This domain's invariants — money rounding, the state machine's legal
transitions, the sealed-event exhaustiveness — are cheap to unit test and
mostly already are: 106 tests in `domain` alone, all sub-second, none
touching I/O. What unit tests structurally cannot catch, no matter how many
are added, is a wrong transaction boundary, a lost update under genuine
concurrency, or a serialization format silently breaking the money
invariant — all three only exist once a real Postgres, a real thread pool,
or a real wire format is in the picture. For a securities-lending ledger,
those three failure classes are the ones that show up as an incorrect
balance or a double-booked accrual in production, not as a wrong unit-level
calculation, which is why this project's integration-test share is planned
to be larger than a typical CRUD web app's despite having more, not fewer,
domain invariants to unit test. The concrete pass line for that claim is
stage 6's Testcontainers-backed persistence suite and stage 8's
optimistic-locking reproduction — both arrive with a real dependency this
stage deliberately has none of.

## Where it breaks at 100x

- **The JaCoCo threshold** doesn't need to move at 100x the code size on its
  own — it is a ratio, not a count — but a module that grows framework
  plumbing (which `domain` is specifically designed never to acquire) would
  make 94% an unreachable number for reasons that have nothing to do with
  test quality, at which point the honest fix is excluding generated code
  from the bundle, not lowering the threshold.
- **The vacuous ArchUnit rules** stop being vacuous the moment `application`
  gains its first class, which is stage 4 — at that point rule 1 and rule 3
  either continue passing meaningfully or catch a real violation on day one.
  Rule 2 (`adapter-web` vs `adapter-persistence`) stays vacuous longer, since
  both adapters arrive together in stages 6–7.
- **The one Cucumber scenario**, at 100x the number of business scenarios
  this project actually has, would be the wrong tool past a handful of
  features — step definition classes accumulate their own coupling problems
  (a wording change in one feature silently breaking a step reused by
  another) exactly like any other shared-fixture test infrastructure, and at
  that scale a BDD suite needs its own step-reuse discipline this stage's one
  scenario is too small to have needed yet.
- **The mock-boundary rule** (mock only what crosses a boundary this module
  doesn't control) holds regardless of scale — it's a rule about what a
  collaborator *is*, not how many of them there are — but a module that
  accumulates many real boundaries (which `domain` is designed not to) would
  need the rule applied consistently by more than one engineer's judgement,
  which is what `docs/decisions/test-doubles.md` existing as a written
  reference, rather than tribal knowledge, is for.

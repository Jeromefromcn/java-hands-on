# Stage 02 — Pure Java Domain Model · Implementation Plan

Spec: [`docs/specs/02-domain-model.md`](../specs/02-domain-model.md)

One section per commit, in order. Implement a section, tick its checkbox in
the same commit, then stop for review. Each acceptance line is a command and
what it must print.

Test first, throughout. A section's test is written and seen to fail before
its production code exists — for the money arithmetic and the state machine
this is not ceremony, it is the only way the boundary cases get named before
the implementation makes them look obvious.

## Ground rules for every section

- Package root `com.jerome.slledger.domain`, one package per concept.
- No annotation from any framework. The enforcer fails the build if one
  arrives; do not wait for it to tell you.
- Money is `Money`, never a bare `BigDecimal`, in every signature that crosses
  a type boundary. Bare `BigDecimal` is permitted **only** inside an
  `AccrualRule` implementation, where the value is deliberately unrounded.
- `Optional` in return types only. Never a field, never a parameter.
- `-Xlint:all -Werror` is on. A warning is a build failure.
- Run `./mvnw -pl domain verify` before every commit, not `test` — `verify`
  is what the gate runs.

---

## [x] 1 · Test dependencies and `Money`

`domain/pom.xml` gains exactly two dependencies, both `test` scope, both
without a `<version>` so the BOM governs them:
`org.junit.jupiter:junit-jupiter` and `org.assertj:assertj-core`. Not
`spring-boot-starter-test`: it drags Spring onto the test classpath of the one
module whose entire premise is that Spring is not there.

No `maven-surefire-plugin` entry is needed in `<build><plugins>`: the default
lifecycle for `jar` packaging already binds `surefire:test`, and stage 1's
`pluginManagement` already pins its version, which is what
`requirePluginVersions` checks.

Then `money/Money.java`:

```java
public record Money(BigDecimal amount, Currency currency) {
  public Money {
    Objects.requireNonNull(amount);
    Objects.requireNonNull(currency);
    amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP);
  }
  public static Money of(String amount, Currency currency)
  public static Money of(BigDecimal amount, Currency currency)
  public static Money zero(Currency currency)
}
```

The compact constructor is the whole point: normalisation happens on every
path in, including the canonical constructor a deserializer would call.

Tests, written first:

- `Money.of("10.00", HKD)` equals `Money.of("10.0", HKD)` — the trap the
  normalisation exists to close. Assert it directly, and assert that the
  underlying `BigDecimal`s are *not* equal to each other, so the test says why
  normalisation is needed rather than just that it happened.
- `Money.of("1.005", HKD).amount()` is `1.01` — HALF_UP at two digits.
- `Money.of("1.004", HKD).amount()` is `1.00`.
- A zero-fraction-digit currency (JPY) normalises to scale 0.
- `null` amount or currency throws `NullPointerException`.

**Acceptance** — `./mvnw -pl domain verify` green; `./mvnw dependency:tree -pl
domain` shows junit-jupiter and assertj-core at `test` scope and nothing at
`compile`. Paste that tree into `docs/evidence/02-domain-test-scope.txt`.

## [x] 2 · `Money` arithmetic and the exception hierarchy

`error/DomainException.java`:

```java
public abstract class DomainException extends RuntimeException {
  protected DomainException(String message) { super(message); }
}
```

Unchecked, and abstract so nobody throws the base type. `error/CurrencyMismatchException.java`
extends it and carries both currencies in the message.

`Money` gains:

```java
public Money plus(Money other)                                  // same currency or throw
public Money minus(Money other)                                 // same currency or throw
public Money multiply(BigDecimal factor, RoundingMode rounding) // no default rounding
public boolean isNegative()
public boolean isGreaterThan(Money other)                       // same currency or throw
```

There is deliberately no `multiply(BigDecimal)`. A convenience overload that
picks a rounding mode is how the rounding policy stops being visible at the
call site.

Tests first: addition of equal currencies; `plus` across HKD and USD throws
`CurrencyMismatchException` whose message names both; `minus` can go negative
and `isNegative` reports it; `multiply` with HALF_UP and with DOWN give
different results on the same input, proving the parameter is load-bearing;
comparison across currencies throws.

**Acceptance** — `./mvnw -pl domain verify` green. The cross-currency test
asserts on the message text, not only the exception type.

## [x] 3 · Identity value objects, `equals` and `hashCode`

`instrument/Isin.java`, `party/CounterpartyId.java`, `loan/LoanPositionId.java`
— records wrapping a validated `String`. `Isin` validates the 12-character
shape and rejects anything else with a `DomainException` branch.

Then `IdentityContractTest`, which is where the roadmap gets corrected:

- `partialFieldHashCodeStillRetrievesItsEntry` — a two-field key whose
  `hashCode` uses one field and whose `equals` uses both. Put it in a
  `HashMap`, get it back. **It comes back.** The contract holds; it degrades
  to collisions, nothing more.
- `reproducesHashMapLookupMissWhenHashCodeIsNotOverridden` — `equals`
  overridden, `hashCode` inherited from `Object`. Two equal instances, one
  put, the other used to `get`. Returns `null`.
- `reproducesHashMapLookupMissAfterMutatingAKeyField` — a mutable field inside
  `hashCode`, mutated after insertion. The entry is stranded in the wrong
  bucket: `get` returns `null` while `containsValue` still finds it. Assert
  both halves — the second is what makes it obviously a bucket problem.

All three broken key types are **test classes only**, nested private static
inside the test. Nothing broken reaches `src/main`.

**Acceptance** — `./mvnw -pl domain verify` green with all three tests
passing, including the one asserting the roadmap's version *works*. The three
outcomes go to `docs/evidence/02-hashcode-contract.txt`.

## [ ] 4 · `Rate`, `AccrualRule` and `AccrualEntry`

This is the stage's one real calculation. Review it line by line.

`money/Rate.java` — `record Rate(BigDecimal annual)`, unrounded, rejecting
negative. Not a `Money`: a rate is not an amount and must not be normalised to
two decimal places.

`accrual/AccrualRule.java`:

```java
public interface AccrualRule {
  /**
   * The day's fee on the given outstanding market value, UNROUNDED.
   * Implementations return bare BigDecimal on purpose: rounding happens
   * exactly once, in AccrualEntry.accrue. See docs/decisions/02-domain-model.md.
   */
  BigDecimal dailyFee(BigDecimal outstandingValue, LocalDate on);
}
```

Three implementations, the strategy pattern earning its place:

- `FlatRate(Rate rate)` — `value × rate.annual() / 365`, `MathContext.DECIMAL64`.
  ACT/365 fixed, named as a constant with a comment saying it is a choice.
- `TieredRate(NavigableMap<BigDecimal, Rate> fromThreshold)` — marginal, not
  cliff: each slice of the value is charged at its own tier's rate. The test
  that matters is the one at a threshold boundary.
- `FloatingRate(ReferenceRates reference, Rate spread)` where
  `ReferenceRates` is a one-method interface `Rate on(LocalDate)`. Not a
  `Function<LocalDate, Rate>` field — a named interface says what the
  collaborator is.

`accrual/AccrualEntry.java`:

```java
public record AccrualEntry(LoanPositionId position, LocalDate accruedOn, Money fee) {
  public static AccrualEntry accrue(
      LoanPositionId position, Money outstandingValue, AccrualRule rule, LocalDate on) {
    BigDecimal raw = rule.dailyFee(outstandingValue.amount(), on);
    return new AccrualEntry(position, on, Money.of(raw, outstandingValue.currency()));
  }
}
```

`Money.of` rounds HALF_UP to currency scale. **That line is the entire
rounding policy.** There is no other place in the domain where an accrual
becomes a booked amount.

Tests first: flat rate on a known value and rate, asserted against a figure
computed by hand and written in the test as a comment showing the arithmetic;
tiered rate exactly at a boundary and one cent either side; floating rate with
a stub `ReferenceRates` returning different rates on two dates, proving the
date reaches the rule.

**Acceptance** — `./mvnw -pl domain verify` green. Every expected figure in
these tests is hand-computed and shown in a comment, not copied from a first
run of the implementation.

## [ ] 5 · The `double` drift, measured

`reproducesDoubleDrift` in `AccrualPrecisionTest`, with a comment pointing at
`docs/decisions/02-domain-model.md`.

Accrue 365 consecutive days on a fixed outstanding value and flat rate, twice:
once accumulating in `double` (the whole calculation in `double`, including
the daily fee), once through `AccrualEntry.accrue` summing `Money`. Assert the
two differ, and print both totals and the difference.

Second measurement in the same test class: the `BigDecimal` per-entry sum
against the mathematically exact figure (365 days computed at full precision
and rounded once). That difference is the price of the per-entry rounding
policy, and the spec promised to measure it rather than assume it.

Both numbers to `docs/evidence/02-precision-drift.txt`, produced by running
the test, not by hand.

**Acceptance** — `./mvnw -pl domain test -Dtest=AccrualPrecisionTest` green,
and the evidence file holds the two figures with the inputs that produced
them (value, rate, day count) so they can be recomputed.

## [ ] 6 · `Security`, `Counterparty`, `CreditLimit`

`instrument/Security.java` — `record Security(Isin isin, String name, Currency
currency, Money marketPrice)`. The price is a `Money`, and its currency must
equal the security's currency; the compact constructor checks it and throws
`CurrencyMismatchException`. This is the money invariant applied to the first
type that could have quietly broken it.

`party/CreditLimit.java` — `record CreditLimit(Money ceiling)`, with
`void checkCanAbsorb(Money existingExposure, Money additional)` throwing
`InsufficientCreditException` naming ceiling, existing exposure and the
requested amount.

`party/Counterparty.java` — `record Counterparty(CounterpartyId id, String
name, CreditLimit creditLimit)`. Synthetic names only; a comment in the test
fixtures says so.

`error/InsufficientCreditException.java` extends `DomainException`.

Tests first: a security priced in a different currency from its own throws; an
exposure exactly at the ceiling is allowed and one cent over is not — the
boundary is the test that matters.

**Acceptance** — `./mvnw -pl domain verify` green; the at-the-ceiling and
one-cent-over cases are separate named tests.

## [ ] 7 · `java.time`: the calendar and T+2

`calendar/HolidayCalendar.java` — `boolean isBusinessDay(LocalDate)`, default
method excluding Saturday and Sunday and delegating a `boolean
isHoliday(LocalDate)` to the implementation.

`calendar/HongKongHolidays.java` — an immutable `Set<LocalDate>` of 2026 Hong
Kong general holidays, hand-entered, with a file-level comment stating exactly
that: hand-entered from public general-holiday information, not a market-data
feed, not authoritative, and needing replacement each year. That comment is
load-bearing — it is what stops this file being mistaken for a source of truth
in stage 6.

`calendar/SettlementDates.java` — `LocalDate settlementDate(LocalDate
tradeDate, HolidayCalendar calendar)`, advancing two **business** days.

All three take and return `LocalDate` only. No `Instant`, no `ZonedDateTime`,
no `Clock` — a settlement date has no instant and no time zone, and the moment
one of those types appears in this signature the distinction the spec makes is
gone.

Tests first: a Monday trade settles Wednesday; a Thursday trade settles Monday
across the weekend; a trade before Lunar New Year skips the holiday block and
lands on the correct later date; a trade *on* a holiday is rejected or
normalised — pick one, write down which in the decision record, and test it.

**Acceptance** — `./mvnw -pl domain verify` green; the Lunar New Year case
asserts an explicit date literal, not an arithmetic expression that could
repeat the implementation's own bug.

## [ ] 8 · `LoanTrade`, its builder, and the idempotency key

`loan/IdempotencyKey.java` — `record IdempotencyKey(String value)`, rejecting
blank.

`loan/LoanTrade.java` — `record LoanTrade(IdempotencyKey key, LoanPositionId
position, CounterpartyId counterparty, Isin security, TradeDirection
direction, long quantity, Money unitPrice, LocalDate tradeDate, LocalDate
settlementDate)`. `TradeDirection` is an enum: `BORROW`, `RETURN`.

Nine components, several of them the same shape, which is the point. Add
`LoanTrade.builder()`, and in the test write the same trade both ways —
canonical constructor and builder — assert they are equal, and leave both
versions in the test as the comparison the roadmap asks for. The builder
validates on `build()`: settlement date on or after trade date, quantity
positive.

**Acceptance** — `./mvnw -pl domain verify` green; one test constructs the
identical trade through both routes and asserts equality, and two tests show
`build()` rejecting a settlement date before the trade date and a
non-positive quantity.

## [ ] 9 · `LoanPosition` and the state machine

`loan/LoanStatus.java` — enum `OPEN`, `PARTIALLY_RETURNED`, `CLOSED`.

`loan/LoanPosition.java` — not a record. It has identity, a lifecycle, and
transitions that must be refused, and a record's public canonical constructor
would let any caller construct an inconsistent state directly. Fields:
`LoanPositionId id`, `CounterpartyId counterparty`, `Isin security`, `long
originalQuantity`, `long outstandingQuantity`, `Money collateral`,
`AccrualRule rule`, `LoanStatus status`. Mutation only through:

```java
public static LoanPosition open(...)   // status OPEN, outstanding == original
public void returnQuantity(long quantity)
```

`returnQuantity` is the only transition. It throws `ExcessiveReturnException`
when the quantity exceeds `outstandingQuantity`, throws
`IllegalTransitionException` when the status is already `CLOSED`, moves to
`PARTIALLY_RETURNED` on a partial return and `CLOSED` when the outstanding
quantity reaches zero.

Both exceptions extend `DomainException` and carry the offending numbers.

A comment on the class states it is not thread-safe and points at stage 8.
Writing "not thread-safe" now is what makes stage 8's optimistic-locking
exercise a change to a stated assumption rather than a discovery.

Tests first, one per transition and one per refusal: full return closes;
partial return moves to `PARTIALLY_RETURNED` and a second partial return
closes; returning one more than outstanding throws; returning against a
`CLOSED` position throws; returning zero or a negative quantity throws.

**Acceptance** — `./mvnw -pl domain verify` green; every arrow and every
refused arrow has its own named test.

## [ ] 10 · Revaluation and `MarginCall`

`margin/MarginCall.java` — `record MarginCall(LoanPositionId position,
LocalDate calledOn, Money shortfall)`, rejecting a non-positive shortfall: a
margin call for zero is not a margin call.

`LoanPosition` gains:

```java
public Optional<MarginCall> revalue(Money unitMarketPrice, LocalDate on)
```

Market value is `unitMarketPrice × outstandingQuantity`; the shortfall is
market value minus collateral; `Optional.empty()` when collateral covers it.
This is the one place `Optional` is used, and it is a return type — which is
the rule the next section demonstrates the cost of breaking.

Currency mismatch between price and collateral throws
`CurrencyMismatchException`.

Tests first: under-collateralised produces a call whose shortfall is the exact
difference; exactly-collateralised produces empty (the boundary); over-collateralised
produces empty; a price in the wrong currency throws.

**Acceptance** — `./mvnw -pl domain verify` green; the exactly-collateralised
boundary is its own test.

## [ ] 11 · `sealed interface LoanEvent` and exhaustiveness

`event/LoanEvent.java`:

```java
public sealed interface LoanEvent permits Opened, Returned, Accrued, MarginCalled {
  LoanPositionId position();
  Instant occurredAt();
}
```

Four records implementing it. `occurredAt` is an `Instant`, not a
`LocalDate` — this is machine time, and the contrast with the `LocalDate`
business dates on the same aggregate is exactly the distinction the spec
draws.

A consumer in the test uses a pattern-matching `switch` over all four with **no
`default` branch**. Omitting `default` is what makes the compiler check
exhaustiveness; adding one throws the check away, which is the whole lesson.

Then demonstrate the gate: delete one `case`, run `./mvnw -pl domain compile`,
capture the compiler error, restore the case. The violation is not committed —
same procedure as stage 1's `01-compiler-gate.txt`.

**Acceptance** — `./mvnw -pl domain verify` green with the switch intact; the
compiler's exhaustiveness error is in `docs/evidence/02-sealed-exhaustive.txt`
with the command that produced it, and `git status` is clean of the deletion.

## [ ] 12 · The cost of `Optional` in the wrong place

`reproducesOptionalFieldSerialization` in `OptionalDisciplineTest`, comment
pointing at the decision record.

A private static class inside the test, `Serializable`, holding an
`Optional<String>` field. Serialize it through
`ObjectOutputStream`. `NotSerializableException`, naming `java.util.Optional`.
Assert the exception and the name in its message.

This is a runtime failure, not a style opinion, which is why it is this and
not a hand-waved example: `Optional` was designed as a return type and does
not implement `Serializable`, so a field of that type breaks any mechanism
that walks fields — serialization here, and the same class of mechanism in
stage 6's persistence and stage 10's message payloads.

A second test shows the same class with a nullable field and an
`Optional`-returning accessor serializing fine.

**Acceptance** — `./mvnw -pl domain verify` green; the exception message in
`docs/evidence/02-optional-serialization.txt`.

## [ ] 13 · `groupingBy` + `teeing`, against the loop

`party/ExposureSummary.java` — `record ExposureSummary(long positionCount,
Money totalCollateral)`.

A static method taking `List<LoanPosition>` and returning
`Map<CounterpartyId, ExposureSummary>`, implemented with
`Collectors.groupingBy` and `Collectors.teeing(counting(), reducing over
Money, ExposureSummary::new)`.

The `Money` reduction needs an identity, and `Money.zero(currency)` needs a
currency — so the method takes the currency as a parameter and throws if any
position's collateral disagrees. Finding that out is the useful part: `teeing`
is clean right up to the point where the monoid needs a currency, and that is
the money invariant refusing to be summarised away.

The equivalent `for` loop lives in the **test**, not in `src/main` — two
implementations of one thing in production code is how they drift. One test
runs both over the same fixtures and asserts equal maps.

**Acceptance** — `./mvnw -pl domain verify` green; one test asserts the two
implementations agree, and a second asserts mixed currencies throw.

## [ ] 14 · Data-structure selection, measured

`labs/02-domain-model/` — self-contained, **not** a module of the main build
and not listed in the parent's `<modules>`. A `main` that builds N positions
with random maturity dates and answers "the nearest K by maturity" three ways:
`TreeMap<LocalDate, List<...>>`, a bounded `PriorityQueue`, and a sorted
`List` with `Collections.binarySearch`.

Measure build cost and query cost separately — they are what distinguishes the
three, and a single combined number would hide it. Warm up before measuring,
and say in the output that this is wall-clock on a warm JVM without JMH, so
the figures are indicative and the ranking is the claim, not the absolute
numbers. JMH arrives in stage 16.

Output to `docs/evidence/02-datastructure-timing.txt`, with N, K, the JVM
version and the machine.

**Acceptance** — `./mvnw verify` at the repository root is green and does
**not** build `labs/`; the evidence file exists and states its own caveats.

---

## Then

`docs/decisions/02-domain-model.md` — chose / rejected / observed / where it
breaks at 100x. It carries:

- the measured `double` drift over 365 days, and the per-entry rounding error
  against the exact figure (section 5)
- the `hashCode` correction: what the roadmap's partial-field version actually
  produces, and the two versions that genuinely lose the entry (section 3)
- the rounding decision: per entry, HALF_UP, currency scale, and the audit
  argument for it
- why domain exceptions are unchecked
- `LocalDate` vs `Instant` vs `ZonedDateTime`, with the rule and where each
  type is allowed
- the four patterns, each with one sentence on what it looks like without it
- **template method vs strategy for the batch skeleton** — the reasoning,
  explicitly recorded as *unverified*, to be checked against real code in
  stage 11
- the data-structure timings and each structure's boundary (section 14)

Same commit adds a pointer in `ROADMAP.md` stage 11 back to this decision
record, so the unverified claim is picked up rather than forgotten. A
forward-reference that lives only in the document being referenced is not a
mechanism.

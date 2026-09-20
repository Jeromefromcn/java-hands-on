# Stage 02 — Pure Java Domain Model

## Problem

Stage 1 produced six empty modules and a build that fails when someone
violates a structural rule. There is no domain code at all. Every later stage
— the container in Stage 4, the transaction boundaries in Stage 5, the
persistence model in Stage 6 — needs types to wire up, and wiring up types
that were designed *after* the framework arrived is how framework concerns end
up inside business invariants.

So the domain is written first, with no framework present to hide anything.
`domain` has no third-party dependency outside test scope, and the enforcer
rule from Stage 1 keeps it that way.

The invariant this stage establishes, and that the remaining fourteen stages
inherit: **money is `BigDecimal` with an explicit currency, at every
boundary.** Stage 2 is where that stops being a sentence in `CLAUDE.md` and
becomes a type that cannot be constructed wrongly.

## Scope

### The six concepts, plus what they need to exist

| Package | Types |
|---|---|
| `money` | `Money`, `Rate` |
| `instrument` | `Security`, `Isin` |
| `party` | `Counterparty`, `CounterpartyId`, `CreditLimit` |
| `loan` | `LoanPosition`, `LoanPositionId`, `LoanStatus`, `LoanTrade`, `IdempotencyKey` |
| `accrual` | `AccrualEntry`, `AccrualRule` and its implementations |
| `margin` | `MarginCall` |
| `event` | `LoanEvent` (sealed) → `Opened`, `Returned`, `Accrued`, `MarginCalled` |
| `calendar` | `HolidayCalendar`, `HongKongHolidays`, `SettlementDates` |
| `error` | `DomainException` and its branches |

Packages are named after concepts, not after technical layers. There is no
`model`, `util` or `service` package: a package that can hold anything tells a
reader nothing.

### The three decisions that shape everything downstream

**`Money` is always at its currency's scale.** `record Money(BigDecimal
amount, Currency currency)`, with the compact constructor applying
`setScale(currency.getDefaultFractionDigits(), HALF_UP)`. Three consequences,
all deliberate:

- The record's generated `equals` becomes correct. Without normalisation,
  `Money.of("10.00", HKD)` and `Money.of("10.0", HKD)` are unequal, because
  `BigDecimal.equals` compares scale as well as value. This is the single most
  common way a money type is wrong.
- The rounding policy stops being a discipline and becomes a property of the
  type: **an unrounded `Money` cannot be constructed.**
- Intermediate high-precision arithmetic therefore has to happen on bare
  `BigDecimal` inside an `AccrualRule`, converting to `Money` only at the exit.
  That makes the rounding point singular and visible. `Money.multiply` takes an
  explicit `RoundingMode`; there is no convenience overload that picks one.

**Rounding: per entry, `HALF_UP`, to the currency's fraction digits.** A daily
`AccrualEntry` is the smallest auditable unit; an amount that has been booked
cannot be recomputed at month end. Accumulation is therefore the sum of
already-rounded values, and every total can be reproduced entry by entry. The
cost is accumulated rounding error against the mathematically exact figure.
That error is measured, not assumed, and the number goes in the decision
record.

**Domain exceptions are unchecked.** `DomainException extends
RuntimeException`. A caller cannot recover from "credit limit exceeded" at the
call site; a checked exception would only make every intervening layer declare
or wrap it. Checked is for a caller that has a second path available, and this
domain has none.

**Time types split by meaning, not by convenience.** Trade date, settlement
date and accrual date are `LocalDate` — business dates on the Hong Kong
calendar, with no instant attached. Event occurrence and idempotency-key
timestamps are `Instant` — machine time, UTC. `ZonedDateTime` does not appear
in `domain` at all; presenting a time to a human is an `adapter-web` concern.

### Deliberate failures reproduced

Each lands as a test that asserts the *wrong* behaviour, named
`reproduces<Failure>`, carrying a comment pointing at the decision record.
`main` stays green.

- `reproducesDoubleDrift` — 365 days of accrual accumulated in `double`
  against the `BigDecimal` figure. The difference is the number this stage
  exists to be able to quote.
- `reproducesHashMapLookupMiss` — see the deviation below.
- `reproducesOptionalFieldSerialization` — `Optional` is not `Serializable`;
  a type carrying one as a field fails at runtime, not at review time.

### One deliberate deviation from the roadmap

The roadmap asks for a `hashCode` computed from *some* of the fields, put into
a `HashMap`, and the entry then failing to come back. **It will come back.** A
partial-field `hashCode` still satisfies the contract — equal objects still
hash equally — so it degrades to collisions, not to lookup misses.

The two cases that genuinely lose the entry are reproduced instead:
overriding `equals` without `hashCode` (falling back to identity hash), and
putting a mutable field in `hashCode` and mutating it after insertion. The
decision record states what the roadmap's version actually produces and why,
because that correction is worth more than following the bullet.

### Patterns, chosen from need

Four, each with a written answer to "what does this look like without it":

- **Strategy** — `AccrualRule`: flat rate, tiered rate, floating rate.
- **State machine** — `LoanPosition`: `OPEN → PARTIALLY_RETURNED → CLOSED`,
  illegal transitions throw.
- **Builder** — `LoanTrade`, compared against the record's canonical
  constructor with the same field count.
- **Template method vs strategy** — the shared skeleton of the daily accrual
  and the revaluation batch. See out of scope.

### Test dependencies

JUnit 5 and AssertJ enter `domain` at test scope, versions from the Spring
Boot BOM. Stage 1's `bannedDependencies` rule already permits test scope. This
does not pre-empt Stage 3, which is about test *organisation* — `@Nested`,
`@ParameterizedTest`, a custom `MoneyAssert`, ArchUnit, JaCoCo, Cucumber — not
about having a runner at all.

## Out of scope

- **Template method vs strategy for the batch skeleton — decision record
  only, no code.** There is no batch code in Stage 2 to attach a skeleton to,
  and a skeleton written before its second implementation exists is a guess.
  The reasoning is recorded now and **verified against real code in Stage 11**;
  `ROADMAP.md` stage 11 carries a pointer back to
  `docs/decisions/02-domain-model.md` so the claim is checked rather than
  forgotten.
- Repositories, ports, use-case orchestration (`application`, Stage 4).
- Any Spring annotation, anywhere. The enforcer fails the build if one arrives.
- Persistence mapping, column types, schema (Stage 6).
- Serialization formats for the wire (Stage 7 and Stage 10). The money
  invariant is re-checked against each of them there.
- Concurrency and optimistic locking on `LoanPosition` (Stage 8). The state
  machine is written single-threaded here and is not claimed to be thread-safe.
- Test organisation, assertions library design, coverage gates (Stage 3).

## Constraints

- `domain` takes no third-party dependency outside test scope. Enforced.
- Java 21. `record`, `sealed`, and pattern-matching `switch` are used where
  they fit, not everywhere they compile.
- All data is synthetic. The Hong Kong holiday set is hand-entered from public
  general-holiday information and is labelled as such in the source; it is not
  a market-data feed and is not claimed to be authoritative.
- `Optional` appears in return types only. The one violation is confined to
  the reproduction test.
- Every number in the decision record is measured on this machine.
- `main` builds green. `./mvnw verify` is the gate.

## Acceptance criteria

Each is a command and an observable result.

1. `./mvnw -pl domain clean verify` is green, and `dependency:tree -pl domain`
   shows JUnit and AssertJ at `test` scope and nothing at `compile`.
2. `Money.of("10.00", HKD).equals(Money.of("10.0", HKD))` is true, and a test
   asserts it. Constructing `Money` from an amount with more decimal places
   than the currency allows yields the `HALF_UP` result at currency scale.
3. Adding `Money` of two different currencies throws `CurrencyMismatchException`.
4. `reproducesDoubleDrift` prints and asserts the 365-day difference between
   the `double` and `BigDecimal` accruals. The figure goes in the decision
   record.
5. A pattern-matching `switch` over `LoanEvent` with one case removed fails to
   compile. The compiler message goes to `docs/evidence/`.
6. `reproducesHashMapLookupMiss` demonstrates both genuine failure modes, and a
   companion test shows a partial-field `hashCode` still retrieving its entry.
7. `SettlementDates.settlementDate(tradeDate)` returns T+2 skipping weekends
   and the Hong Kong holidays in `HongKongHolidays`, verified against dates
   spanning a long holiday.
8. Every `LoanPosition` transition outside `OPEN → PARTIALLY_RETURNED → CLOSED`
   throws `IllegalTransitionException`; returning more than the outstanding
   balance throws `ExcessiveReturnException`; opening beyond a
   `Counterparty`'s `CreditLimit` throws `InsufficientCreditException`. All
   three are unchecked and all three carry the offending amounts in the
   message.
9. Revaluing a `LoanPosition` whose collateral no longer covers its market
   value produces a `MarginCall` carrying the shortfall as a `Money`; a
   sufficiently collateralised position produces none.
10. `reproducesOptionalFieldSerialization` fails with `NotSerializableException`
    on a type holding an `Optional` field.
11. The `groupingBy` + `teeing` summary and the equivalent `for` loop produce
    the same result, asserted by one test running both.
12. `labs/02-domain-model/` measures `TreeMap`, `PriorityQueue` and a sorted
    `List` for "nearest N positions by maturity" and writes timings to
    `docs/evidence/02-datastructure-timing.txt`. It is not part of the main
    build.
13. `./mvnw verify` at the repository root is green.

## Done when

The four deliverables in `CLAUDE.md` exist: this spec, the plan, the code with
every criterion above verified, and `docs/decisions/02-domain-model.md`
carrying the measured `double` drift, the rounding-error figure, the
data-structure timings, the `hashCode` correction, and the answer to "where
does this break at 100x".

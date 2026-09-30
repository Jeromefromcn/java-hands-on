# Stage 02 — Pure Java Domain Model — Decision Record

Spec: [`docs/specs/02-domain-model.md`](../specs/02-domain-model.md)
Plan: [`docs/plans/02-domain-model.md`](../plans/02-domain-model.md)

## What I chose

**`Money` normalises to currency scale in its compact constructor**
(`setScale(currency.getDefaultFractionDigits(), HALF_UP)`). This is what makes
the generated `record` `equals` correct at all — `BigDecimal.equals` compares
scale, so `Money.of("10.00", HKD)` and `Money.of("10.0", HKD)` are unequal
unless something normalises first. It also makes rounding a property of the
type rather than a discipline: an unrounded `Money` cannot be constructed, so
every high-precision step has to happen on bare `BigDecimal` inside an
`AccrualRule` and convert once, at the exit. `AccrualEntry.accrue` is that one
exit point; there is no second place in the domain where an accrual becomes a
booked amount.

**Rounding: per entry, `HALF_UP`, to currency scale — not at the total.** A
daily `AccrualEntry` is the smallest auditable unit; once booked it is never
recomputed. Totals are sums of already-rounded values, so any total can be
reproduced entry by entry from the ledger. The measured cost of that choice is
in [Observed](#what-i-observed) below.

**Domain exceptions are unchecked** (`DomainException extends
RuntimeException`). A caller has no second path when credit is insufficient or
a return is excessive — a checked exception would only force every
intervening layer to declare or wrap something it cannot act on. Checked is
for a caller with an alternative; this domain has none.

**Time types split by meaning, not convenience.** Trade date, settlement date,
accrual date: `LocalDate`, Hong Kong business dates, no instant attached.
Event occurrence (`LoanEvent.occurredAt`): `Instant`, machine time, UTC.
`ZonedDateTime` appears nowhere in `domain` — showing a time to a person is an
`adapter-web` concern, not a domain one.

**A trade date that is not a business day is refused, not rolled forward**
(`SettlementDates.settlementDate` throws `NotABusinessDayException`). Rolling
forward silently would mean the settlement date no longer corresponds to the
trade date the caller supplied, and there is no unambiguous "next" business
day from the caller's perspective — refusing pushes that decision back to
whoever generated the trade date in the first place.

**`LoanPosition` is a class, not a record.** It has identity, a lifecycle and
transitions that must be refused (`returnQuantity`); a record's public
canonical constructor would let any caller build an inconsistent state
directly, bypassing the state machine. Mutation is confined to the two named
operations.

**Four patterns, chosen from what the domain already needed, not added for
coverage:**

- **Strategy** (`AccrualRule`: `FlatRate`, `TieredRate`, `FloatingRate`).
  Without it: a chain of `if (rateType == ...)` inside `AccrualEntry.accrue`,
  and every new fee schedule touches code that has nothing to do with fee
  schedules.
- **State machine** (`LoanPosition`: `OPEN → PARTIALLY_RETURNED → CLOSED`).
  Without it: `outstandingQuantity == 0` scattered across the codebase as the
  informal definition of "closed," recomputed differently in each place that
  cares.
- **Builder** (`LoanTrade`, nine components). Without it: a nine-argument
  canonical constructor call at every trade site, positional and unreadable,
  where swapping `quantity` and `unitPrice`'s position compiles silently.
- **Template method vs strategy for the batch skeleton — decision only, no
  code, per spec's Out of Scope.** See [Unverified](#unverified-carried-to-stage-11).

## What I rejected, and why

**Rolling a non-business trade date forward to the next business day**
(rejected in favour of refusing it). Considered because it is the more
"helpful" default. Rejected because the caller's chosen trade date is
supposed to be authoritative — silently substituting a different date changes
what was actually agreed, and a caller that meant to trade on a holiday almost
certainly has a bug upstream that refusing surfaces immediately instead of
producing a settlement date nobody asked for.

**The roadmap's `hashCode`-from-some-fields exercise, as specified.** The
roadmap expects a partial-field `hashCode` to lose a `HashMap` entry. It
doesn't: equal objects still hash equally under a partial-field `hashCode`, so
the contract holds and the only cost is collisions. Reproduced instead — the
two cases that genuinely lose the entry: `equals` overridden without
`hashCode` (falls back to `Object`'s identity hash), and a mutable field
inside `hashCode` mutated after insertion (entry stranded in its original
bucket, `get` returns `null`, `containsValue` still finds it because
`containsValue` doesn't hash). Full outcomes:
[`docs/evidence/02-hashcode-contract.txt`](../evidence/02-hashcode-contract.txt).

**A `multiply(BigDecimal)` convenience overload on `Money`.** Rejected —
picking a default `RoundingMode` is exactly the decision this type exists to
keep visible at the call site. `Money.multiply` takes rounding mode as a
required parameter; there is no path around naming it.

**`default` branch on the `LoanEvent` switch.** Rejected — a `default` always
matches, so it would silently absorb both a deleted case and a newly added
event type, and the compiler's exhaustiveness check (only available because
the interface is `sealed`) would have nothing to check. Confirmed by removing
a case and separately adding a fifth event type: both fail the same way, at
compile time, at every switch site. Evidence:
[`docs/evidence/02-sealed-exhaustive.txt`](../evidence/02-sealed-exhaustive.txt).

## What I observed

### `double` drift over the accrual, measured

Scenario: 1,000,000.00 HKD outstanding, 3.50% flat annual rate, ACT/365, 365
daily entries. Full figures and the exact `BigDecimal` literals:
[`docs/evidence/02-precision-drift.txt`](../evidence/02-precision-drift.txt).

| # | Comparison | Result |
|---|---|---|
| 1 | `BigDecimal` sum of 365 booked entries vs `double` sum of the same, one position | 34,999.85 vs 34999.8499999998530256561934947967529296875 — difference ≈ **−1.47 × 10⁻¹⁰ HKD**, and both round to the same cent |
| 2 | Same, at 100,000-position portfolio scale | BigDecimal 3,499,985,000.00 vs double 3,499,984,999.99 — **one cent short**, no single entry wrong, invisible to entry-level reconciliation |
| 3 | `1.005` rounded HALF_UP to 2dp, `BigDecimal` vs `double` (`Math.round(1.005*100)/100.0`) | 1.01 vs **1.00**, because the nearest representable `double` to 1.005 is already below the midpoint. Neighbouring half-cents (1.015, 1.025) fail the same way; others (2.005, 8.005, 1.045) happen to round correctly — no rule a reviewer can apply by eye |
| 4 | This domain's own rounding policy: exact year's fee (1,000,000.00 × 0.0350 = 35,000.00) vs sum of 365 booked entries | 35,000.00 vs 34,999.85 — **0.15 HKD/position/year**, nine orders of magnitude larger than item 1, and the only one of the four that was chosen on purpose |

The finding that corrects the roadmap's assumption: a single position over a
single year does **not** show `double` drift at the cent — item 1 rounds to
the identical figure. Drift becomes visible only at portfolio scale (item 2)
or as an outright wrong rounding on an individual value that never touched
accumulation at all (item 3). "Error accumulates over a year" is not what this
measurement shows; "the deliberate rounding policy costs 130x more than the
IEEE-754 error, and IEEE-754 fails unpredictably rather than by degree" is.

### `hashCode` contract, measured

Full detail: [`docs/evidence/02-hashcode-contract.txt`](../evidence/02-hashcode-contract.txt).
Summary already covered under [Rejected](#what-i-rejected-and-why) above. One
additional finding: on this build (`-Xlint:all -Werror`), overriding `equals`
without `hashCode` is a **compile failure**, not a runtime surprise —
`[WARNING] ... overrides equals, but neither it nor any superclass overrides
hashCode]`, caught only on a *clean* build (an earlier run against a stale
incremental build missed it and produced a wrong finding, corrected in the
same evidence file). The mutable-hashCode-field case draws no compiler warning
at all and is the one that reaches production silently.

### Data-structure timings, measured

`labs/02-domain-model/NearestMaturities.java`, N = 1,000,000 positions, K =
20, two independent runs (500 and 200 queries). Full output and the
run-to-run instability discussion:
[`docs/evidence/02-datastructure-timing.txt`](../evidence/02-datastructure-timing.txt).

| Structure | Build cost | Query cost | Breaks at |
|---|---|---|---|
| `TreeMap<LocalDate, List<>>` | ~270 ms | ~0.3–2.4 ms/500q | memory: one node per distinct date, boxed `LocalDate` keys, at 100x this is the first structure to hit the heap profile |
| `PriorityQueue` (bounded K) | free (0 ms) | ~3.4–11.3 **s**/run, 4–5 orders slower than the other two | repetition: full O(N log K) scan every call, fine once, unusable in a loop |
| sorted `ArrayList` + binary search | ~630–650 ms | fastest of the three | any mutation: insertion is O(N) to shift, or O(N log N) to re-sort |

Claim that survives both runs (query timings varied by up to 7× between them
purely on JIT warmup, build costs were stable):
`query: sorted < TreeMap ≪ PriorityQueue` (4–5 orders apart), `build: PriorityQueue < TreeMap < sorted`.
Crossover — the point at which `TreeMap`'s build cost is repaid — landed
between 12 and 16 queries in the two runs; sorted list between 28 and 38.
Since this domain's positions open and close through the trading day
(mutating set, repeated query), `TreeMap` is the realistic choice despite
losing on raw query speed to the sorted list, which cannot answer the "N just
changed" case without a full rebuild.

## Where it breaks at 100x

- **The rounding policy (0.15 HKD/position/year) scales linearly with
  position count and stays reconcilable** — every cent is traceable to a
  booked entry. It doesn't "break," it is the intended cost of auditability.
  What breaks first is the **`double` drift at portfolio scale** (item 2
  above): at 100,000 positions it is already one cent short in a way no
  entry-level check catches; at 100x that (10,000,000 positions) the same
  mechanism produces a growing, silent, reconciliation-invisible discrepancy
  — this domain never uses `double` for money, which is the point of having
  measured it once here.
- **`TreeMap<LocalDate, List<Position>>`** breaks on memory footprint first,
  not on algorithmic complexity — at 100,000,000 positions this becomes a
  question for an indexed column in Postgres (Stage 6), not an in-memory
  structure at all.
- **`PriorityQueue`'s full scan** goes from ~20 ms to roughly 2 s per query at
  100x; it was already four orders of magnitude behind at 1,000,000 positions,
  so it is disqualified for any repeated-query use well before 100x is
  reached.
- **`SettlementDates`'s `GIVE_UP_AFTER_CALENDAR_DAYS = 365` walk** is O(1) in
  practice (2 business days out of any real calendar resolve in under two
  weeks) and does not degrade with scale — it degrades only if a
  `HolidayCalendar` implementation reports an unrealistic closure length,
  which is a configuration fault, not a volume problem.

## Unverified, carried to stage 11

**Template method vs strategy for the daily-accrual and revaluation batch
skeleton.** No batch code exists yet — Stage 2 has no second implementation of
a batch to generalise from, and a skeleton written before one exists is a
guess. Reasoning, to be checked against real code in Stage 11:

`AccrualRule` is already Strategy (interchangeable fee calculation, same
shape, swapped whole). The batch skeleton (iterate active positions → compute
→ book → handle failures) looks like Template Method's classic case — a fixed
algorithm shape with pluggable steps — but the two batches (daily accrual,
revaluation) share the iteration and error-handling shape while differing in
*what varies per step* in a way that might be simpler as an injected
`Function`/strategy per step than as inherited abstract methods, given that
`application`-layer classes in this codebase are not expected to form a
hierarchy elsewhere. Tentative call: **composed strategies over an inherited
template**, because inheritance here would be introduced solely for this
skeleton rather than reflecting an existing type relationship — but this is
exactly the kind of judgement that looks obvious in the abstract and wrong
against the first real batch. `ROADMAP.md` stage 11 carries a pointer back to
this paragraph so the claim gets checked rather than forgotten.

### Checked in stage 11: the composition call stands, for a reason the tentative one missed

Stage 11 wrote the accrual and the revaluation (and, after them, a reconciliation and a retention
job) on Spring Batch. What the code showed, against the paragraph above:

- **The skeleton is not a class.** "Iterate active positions, compute, book, handle failures" is
  three things in this code, and none of them is a base class. The iteration is `KeysetReader`, a
  final class that both jobs use unchanged and that differs between them only in the page function it
  is given. The failure handling is `StepDefaults.faultTolerant`, a static function that takes a step
  builder, applies the skip and retry rules and hands it back. The rest (what is read, what is
  decided, what is written) is each job's own reader query, processor and writer.
- **What actually differs is more than what is shared.** The accrual decides a fee entry for every
  item and writes it with `ON CONFLICT DO NOTHING`; the revaluation decides a margin call or
  nothing (returning `null` filters the item out of the chunk) and writes an outbox row behind a
  marker. Reading differs (a date's active positions in partition slices, against one security's),
  the parameters differ (`businessDate`, against `isin` and `priceVersion`) and so does what
  idempotence means. As a template the two would share a loop and need four abstract hooks
  (`read`, `decide`, `write`, `scope`); as composed parts they share two small classes and nothing
  is overridden. The shared code is about sixty lines.
- **The framework had already answered the question.** Spring Batch's `Step` is the fixed algorithm
  and `ItemReader`, `ItemProcessor` and `ItemWriter` are the pluggable steps, injected. A
  `BaseBatchJob` above it would have been a template over a template. The tentative paragraph
  imagined the choice as "a `Function` per step or an abstract method per step"; the first real
  batch is a third thing, a builder passed through a function, and it was not in the paragraph.
- **The two other jobs do not have the shape.** The reconciliation is a load step and a compare
  step over two inputs; the retention job is a chain of purges that are tasklets, not chunks. A
  template built from the first two batches would have fitted two jobs of four and been bent, or
  left, for the others.

What composition cost: each job repeats about forty lines of wiring (a job builder, a parameters
validator, a listener, a step builder, and its step-scoped reader, processor and writer beans).
That is boilerplate a helper could hold, not a hierarchy, and it was left as it is because the two
copies differ in every line that is not boilerplate. The tentative call, **composed strategies over
an inherited template**, stands; the reason is the framework and the data, not the codebase's habit
of having no hierarchies.

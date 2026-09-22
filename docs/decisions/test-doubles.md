# Test doubles, against concrete classes in this suite

Not the general definitions — each category is tied to a specific test in
this codebase, so the difference is something that was actually built, not
something asserted in the abstract.

## Stub

The inline `HolidayCalendar` lambdas in `SettlementDatesTest`, e.g.:

```java
HolidayCalendar closedFromTomorrow = date -> date.isAfter(tradeDate);
```

A canned answer for one call shape, nothing more. The test never asks "was
this called, and with what" — it only cares what `settlementDate` computes
given that answer. A stub can be wrong in a way nothing catches: if
`SettlementDates` stopped calling `isBusinessDay` at all and hard-coded a
result, this stub would never notice. That risk is acceptable here because
the object under test (`SettlementDates`) is exercised through its real
return value, and a wrong return value is what the test actually asserts on.

## Mock

`FloatingRateTest`'s `@Mock ReferenceRates reference`, verified with:

```java
verify(reference).on(DAY);
```

The distinguishing feature is that the call itself is part of the assertion,
not just the answer it returns. This is the one collaborator in `domain` that
represents a real external boundary — a future market-data source — so
proving `FloatingRate` actually asked it, rather than assuming the return
value alone implies the call happened, is the point of the test. See
`docs/decisions/03-testing-foundation.md` for the fuller mock-boundary
argument this comparison establishes, and `FloatingRateOverMockedTest` for
what mocking a collaborator that *isn't* a boundary (`Rate`, `Money`) costs
instead of buys.

## Fake

Not currently written. No test in this suite currently benefits from
replacing a canned-answer lambda with a real, simplified implementation
backed by state — `SettlementDatesTest`'s calendar stubs each need exactly
one date's answer, and a stateful in-memory `ReferenceRates` (say, backed by
a `Map<LocalDate, Rate>`) would only be worth writing once a test needs
several dates' worth of behaviour from the same instance across multiple
calls in a way that's awkward to express as a lambda. That test doesn't exist
yet. Manufacturing a fake to fill this category would be exactly the kind of
padding `docs/specs/03-testing-foundation.md` declined — the honest note is
that the suite hasn't needed one, not that it has one.

## Spy

Not currently used anywhere in this suite. The shape it would take, if a test
needed it: wrapping a real `AccrualRule` implementation (a real `FlatRate`,
say) to record how many times `dailyFee` was called during a batch run,
while still delegating to the real computation for the return value —
useful for asserting "the batch touched every position exactly once"
without needing to mock the computation itself. Stage 11's batch job is the
first place in this roadmap where that assertion ("did every active
position get processed, not zero times and not twice") becomes something
worth writing a test for; until then a spy would have nothing real to wrap.

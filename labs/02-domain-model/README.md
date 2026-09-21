# Lab 02 — nearest maturities, three structures

Self-contained. Not a Maven module, not listed in the parent's `<modules>`,
and it imports nothing from `domain`. `./mvnw verify` at the repository root
does not build it.

```
java labs/02-domain-model/NearestMaturities.java
```

Answers "the K positions maturing soonest" three ways — a `TreeMap` keyed by
maturity, a `PriorityQueue` bounded at K, and a sorted `ArrayList` — and times
the build and the queries separately, because that separation is what
distinguishes them.

The harness checks the three return the same maturities before reporting.

Results and what each structure's boundary is:
[`docs/evidence/02-datastructure-timing.txt`](../../docs/evidence/02-datastructure-timing.txt).

Wall clock, no JMH. The ranking is the claim, not the numbers.

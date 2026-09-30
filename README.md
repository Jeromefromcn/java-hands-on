# java-hands-on

A securities-lending ledger, built one stage at a time to exercise Java,
Spring and the JVM — including the parts that only show up when something
breaks.

The business domain is deliberately small: six entities, five use cases.
It exists to generate *real* problems — decimal precision, concurrent updates,
batch restartability, cache inconsistency, thread-pool exhaustion — rather
than to be a product.

**The roadmap is the point: [ROADMAP.md](ROADMAP.md).** 16 stages, each a
runnable increment, each ending in a decision record.

## What makes this different from a tutorial project

Every stage requires reproducing a failure before fixing it. The repo is full
of measured numbers rather than assertions: what the `double` drift actually
was over 365 days of accrual, where the GC log turned from *collecting* to
*not collecting*, how much throughput virtual-thread pinning actually cost.

Each stage also ships a one-page decision record answering four questions:
what was chosen, what was rejected and why, what was observed, and where the
choice breaks at 100x load.

## Domain

| Entity | Meaning |
|---|---|
| `Security` | ISIN, currency, current market price |
| `Counterparty` | Borrower, with a credit limit |
| `LoanPosition` | Outstanding quantity, fee rate, collateral |
| `LoanTrade` | A borrow or return, with trade and settlement dates |
| `AccrualEntry` | Daily fee accrued on the outstanding balance |
| `MarginCall` | Raised when revaluation leaves collateral short |

Use cases: open a loan, return part or all of it, accrue fees daily,
revalue at market and raise margin calls, query positions and entries.

All data is synthetic.

## Stack

Java 21 · Spring Boot · Maven multi-module · PostgreSQL · Redis · Kafka ·
Spring Batch · Testcontainers · Micrometer / Prometheus / OpenTelemetry

Modules: `domain` (plain Java, zero third-party dependencies) ·
`application` · `adapter-persistence` · `adapter-web` · `adapter-messaging` · `batch` ·
`bootstrap`

## Running it

```bash
./mvnw verify                 # build + tests (Testcontainers; needs Docker)
docker compose -f infra/docker-compose.yml up -d   # infra for manual experiments
./mvnw -pl bootstrap spring-boot:run
```

`main` always builds green. Code that reproduces a failure lives either in a
test that asserts the wrong behaviour, or under `labs/`, never in the product
path.

## Layout

```
docs/specs/        one spec per stage, committed before the code
docs/decisions/    one decision record per stage
docs/evidence/     jstack output, GC logs, JMH results, EXPLAIN plans
labs/              self-contained failure reproductions
infra/             Docker Compose for manual experiments
```

## Status

In progress. See the progress table at the end of [ROADMAP.md](ROADMAP.md).

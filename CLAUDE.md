# CLAUDE.md

**Public repo.** A securities-lending ledger, built incrementally to exercise
Java / Spring / JVM. The roadmap in `ROADMAP.md` drives everything: 16 stages,
each a runnable increment. Read the stage before touching its code.

## Public means public

- Nothing here references a private repo, an employer, a job description,
  a recruiter, or a compensation figure. If a rationale only makes sense by
  naming one of those, rewrite it as an engineering rationale or drop it.
- All data is synthetic. No real counterparty names, no licensed market data.
- No secrets, ever — env vars only, and verify they stay out of
  `/actuator/env` and logs.

## Definition of done

A stage is done when all three exist:

1. `docs/specs/<nn>-<slug>.md` — committed **before** the code
2. working code, TDD, tests green
3. `docs/decisions/<nn>-<slug>.md` — chose / rejected / observed / where it
   breaks at 100x

**Code alone is not done.** The decision record is the deliverable that the
code cannot replace; writing it after the fact defeats the point.

## Deliberate breakage

Most stages require *reproducing* a failure — lost update, N+1, thread
pinning, OOM, TCC hanging. The broken version never lands as product code on
`main`. It goes in exactly one of:

- a test that asserts the *wrong* behaviour, named `reproduces<Failure>`, with
  a comment pointing at the decision record, or
- `labs/<nn>-<slug>/` — self-contained, not wired into the main build

`main` always builds green. `./mvnw verify` is the gate.

## Numbers

Every number in a decision record is a measured value from this machine —
latency, QPS, GC pause, the `double` vs `BigDecimal` drift. Never an estimate,
never reconstructed from memory afterwards. If it wasn't measured, say so.

## The one invariant

Money is `BigDecimal` **with an explicit currency**, at every boundary.
Any new component (serializer, cache, message payload, column type) must be
checked against it before the stage closes. This is the single thing that
holds across all 16 stages.

## Build

Maven multi-module, Java 21. Modules: `domain` (no third-party dependencies at
all), `application`, `adapter-persistence`, `adapter-web`, `batch`, `bootstrap`.
Versions live only in the parent `dependencyManagement`; child POMs carry no
`<version>`. `./mvnw` is checked in — use it, not a system `mvn`.

Infrastructure for manual experiments is Docker Compose under `infra/`.
Automated tests use Testcontainers and must not depend on anything Compose
started.

## Git

- English only in anything committed — messages, comments, docs under `docs/`.
- One logical change per commit. Spec, implementation and decision record are
  three commits, in that order.
- Commit message: `<stage-nn>: <what changed>`, e.g. `stage-05: reproduce
  self-invocation transaction failure`.
- Commit the text evidence: jstack output, GC logs, JMH results, `EXPLAIN`
  plans. Never commit `*.hprof` or heap dumps — reference them from the
  decision record instead.

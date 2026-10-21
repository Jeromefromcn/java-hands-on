# Stage 14 — Packaging and Delivery — Decision Record

Spec: [`docs/specs/14-delivery.md`](../specs/14-delivery.md)
Plan: [`docs/plans/14-delivery.md`](../plans/14-delivery.md)

Measured on JDK 21.0.12 (Temurin, the `.sdkmanrc` pin) on a 4-CPU arm64 host with 24 GB of
RAM that also runs a K3s cluster and other services; Docker 29.6.0; GitHub-hosted runners for the
pipeline (x64 for the gates, `ubuntu-24.04-arm` for the image); K3s v1.36.2 with ArgoCD and
Kyverno for the deployment. Every number below is copied from a `docs/evidence/14-*.txt` file.
Where a claim is reasoned and not measured, the text says so; the last section is entirely that.

**Not done in this stage:** the rolling update under continuous load (spec acceptance 7, plan
section 14). The probes and the `preStop` it would exercise are deployed and configured as
described below, but the zero-5xx claim is not measured and is not made here.

## What I chose

### One image, its layers ordered by how often they change

`bootstrap/Dockerfile` extracts the repackaged jar into Boot's layers in a build stage and copies
each layer into an image layer of its own, on a digest-pinned Temurin 21 JRE, as user 10001. The
project's own modules have a layer of their own, `ledger`, between `snapshot-dependencies` and the
application's classes.

A one-line edit in `domain` rebuilds one layer of 582 kB; the 109 MB `dependencies` layer and the
115 kB `application` layer keep their digests (`14-layer-diff.txt`). Boot's default layout gets
close: in a full reactor build it puts the modules in `application`, and the same edit rebuilds one
688 kB layer. The `ledger` layer exists because the default depends on how Maven is invoked:
`./mvnw -pl bootstrap package` puts the modules in `snapshot-dependencies` instead. What it saves
per edit is bootstrap's 115 kB, not more.

The finding that mattered was elsewhere. Two `--no-cache` builds of the *same* jar differed in
three layers, the 109 MB one included: `extract` stamps the directories and the rebuilt
`application.jar` with the time of the `RUN`, and a layer's digest covers those times. A warm
build cache hid it on this machine; a CI runner has no cache. The Dockerfile now sets every
extracted file to the build's `outputTimestamp`, and two builds of one commit are identical. Layering
without reproducible timestamps rebuilds everything on every pipeline run.

### The heap is a share of the container, and the share is measured

`JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"`, no `-Xmx`.

The JVM reads the cgroup limit (`14-container-awareness.txt`): under `--memory=512m` it reports
`Memory Limit: 512.00M` and a max heap of 134,217,728 bytes at the default 25%, 402,653,184 at
75%; at 1 GB, 268,435,456 and 805,306,368. Under `--cpus=1.5` it counts 2 CPUs.

Two choices came from measuring, not from the usual advice:

- **70%, not 75%.** The ledger's non-heap (metaspace, code cache, compressed class space) measured
  205-211 MB after start and 500 reads (`14-ledger-memory.txt`). 25% of a 768 MB limit is 192 MB;
  a full 75% heap there leaves the kernel to end the process. The cluster runs it at a 1 GiB limit:
  a 718 MB heap and about 300 MB for the rest.
- **G1 named.** Below 1792 MB the JVM does not consider itself a server and picks SerialGC by
  itself: measured at both 512 MB and 1 GB. A limit change from 1 GiB to 2 GiB would have changed
  the collector without anyone deciding it.

`ExitOnOutOfMemoryError` because the two ways of running out look nothing alike
(`14-oomkilled-vs-oome.txt`): `-Xmx1g` in a 512 MB container is killed by the kernel at 448 MB held,
exit 137, `OOMKilled=true`, and the process writes nothing about it; the percentage ends in
`java.lang.OutOfMemoryError: Java heap space` at 320 MB held, exit 3 with the flag and exit 1
without it. Without the flag a server's request thread dies and the process stays up, answering
probes.

### Startup: three variants measured, none shipped by default

Five starts each against the same Compose services (`14-startup.txt`), median time from `docker
run` to the first 200 from readiness:

| Variant | Ready (median) | Image | What it costs |
|---|---:|---:|---|
| baseline (what ships) | 17,865 ms | 198 MB | — |
| CDS (AppCDS, dynamic archive) | 13,410 ms | 233 MB | a 138 MB layer that differs on every build |
| Spring AOT | 15,071 ms | 198 MB | the bean graph fixed at build time |
| AOT + CDS | 10,081 ms | 240 MB | both |

CDS was going to ship, until the layer diff of the CDS image (`14-layer-diff-cds.txt`): the archive
layer differs between two builds of the same commit. The image digest stops being reproducible from
the commit, which stage 1 established for the jar, and every release ships 138 MB more. `--target
cds` builds it; the release target does not. Its training run has to refresh the context with no
database, which takes Flyway off, schema validation off, JDBC metadata off and the dialect named.

AOT is out for a reason that is not a number. The AOT image, built under the local profile and
started with `LEDGER_MESSAGING_ENABLED=false`, started three Kafka clients; the JVM image started
none. A property that decides whether a bean exists is read when the AOT code is generated, not when
the image runs, which contradicts one image running as three environments.

### GraalVM native: not for this service, and not built on this host

No executable was produced (`14-native.txt`). At a 6 GB heap the analysis step ran for 29 minutes
at its cap with the host swapping; at 10 GB, seven of eight steps took 15 min 20 s, peaking at
8.55 GB of heap, and the host, which also runs the cluster, thrashed until it was rebooted. The
build's container had no memory limit, so the kernel never killed it. `native.sh` now runs it under
a 12 GB limit with no swap and refuses to start with less than 14 GB available.

Native startup time and memory are therefore **not measured**. The verdict does not need them: the
ledger runs two replicas that are replaced one at a time with `maxUnavailable: 0`, so startup time
does not reach availability, and AOT's frozen bean graph, which native requires, already ruled
itself out above. What a native build costs to make is measured; what it would save is not needed.

### Configuration belongs to the environment

The image carries the packaged defaults and the profile files. An environment supplies a profile,
endpoints and secrets as environment variables and, for anything larger than a variable, a mounted
`config/` directory. The same image id ran as dev (local profile, text logs, one port), staging
(prod profile, JSON logs, management on its own port) and prod (staging plus a mounted
`config/application.yml` raising the Hikari pool from 10 to 20) (`14-three-envs.txt`).

Precedence was proved against the real jar in its own process (`14-config-precedence.txt`):
command-line argument over environment variable over mounted `config/` over packaged, removed one
at a time. Twice the experiment met that order by accident: the environments first shared a
database, and the prod profile's Flyway refused a schema carrying the local profile's seed
migrations; then a `SPRING_DATASOURCE_URL` meant for staging overrode dev's profile file. Each
environment now has its own database.

### Shutdown drains, in a budget

`server.shutdown=graceful` (since stage 12) and `spring.lifecycle.timeout-per-shutdown-phase=20s`.
Closed 0.5 s into a 3 s request (`14-shutdown.txt`): with `immediate`, the client got
`IOException: HTTP/1.1 header parser received no bytes`; with graceful, the close took 2,543 ms,
the client got its 200, and a new connection during the close was refused.

The Kafka listener containers stop immediately after the record in hand. Spring Kafka's default
first handles the rest of the last poll: five records of 400 ms made `stop()` take 2,059 ms and
handle all five, and at 50 records a poll that is a drain longer than a pod's grace period, cut at
an arbitrary record. With `stopImmediate` the stop took 428 ms, committed offset 1, and the next
member of the group handled the other four, each once. Either way no offset is committed ahead of
its effect.

In the cluster: `preStop` sleeps 10 s while the endpoint removal reaches every node, then SIGTERM
starts the 20 s drain, inside `terminationGracePeriodSeconds: 45`. The inequality is stated in the
manifest; the rolling update that would test it under load was not run.

### Three probes, three questions

Startup (`/actuator/health/liveness`, 2 s × 30): has it finished starting. Liveness (10 s × 3): is
the process wedged; it never checks a dependency, so a database outage cannot restart every pod at
once. Readiness (2 s × 1, includes the database): should it get traffic. On the cluster the first
pod's startup probe failed while Spring started (16.2 s); without a startup probe that failure
would have counted towards a liveness restart (`14-k3s-deploy.txt`).

### The pipeline: a step per gate, and every gate made to fail

`.github/workflows/ci.yml`. The verify job is four Maven runs so that a red run names its gate:
format and enforcer (12 s), compile with `-Werror` (15 s), unit tests (37 s), integration tests with
ArchUnit and the coverage floor (665 s). `dependency-scan` runs Trivy over the poms and the
resolved runtime classpath (62 s). On `main` and `drill/*` only, the image job builds on a native
arm64 runner, scans the image with Trivy *before* pushing it, pushes to GHCR and signs it keyless
with Cosign (89 s). It does not deploy.

Every module has a coverage floor now, at its measured line coverage rounded down to 5%
(`14-coverage.txt`); only `domain` had one before. The figures count each module's own tests:
`application` measures 52.8% and `adapter-web` 47.8% although most of both runs end to end in
bootstrap's tests, which are credited to bootstrap (90.6%), and `batch`, with no tests of its own,
has no data and no floor.

Each gate was made to fail once on a throwaway branch (`14-gate-drills.txt`), and each stopped at
its own step with the later jobs not run: Spotless, `-Werror` on a deprecated-for-removal
constructor, `requireNoRepositories`, four unit test classes on a 366-day year, ArchUnit's
`everyHandlerHasAnAccessRule`, the coverage floor (domain at 0.57 against 0.94), Trivy on
lz4-java's CVE-2026-106451, and Trivy on an old base image, which stopped the push and the
signing: the vulnerable image never reached the registry.

The pipeline was worth having before any drill. Its first runs found a HIGH vulnerability published
after stage 13 (lz4-java 1.10.1, pinned to 1.11.4), four tests that depended on this machine's test
order or timing, the coverage floor catching the rollout store untested in its own module, and a
secret-scanning test that had been failing on committed scripts for four sections because only
targeted tests ran between full verifies.

### Four eyes: what the configuration enforces, and what it cannot

`main` requires the `verify` and `dependency-scan` checks, a pull request with one approving review
from a code owner, dismissal of stale reviews and resolved conversations; no force push, no deletion
(`14-branch-protection.txt`). A pull request breaking a gate with no review was refused for merging.

The administrator may bypass, and does: every push of this stage to `main` printed "Bypassed rule
violations for refs/heads/main: Changes must be made through a pull request. 2 of 2 required status
checks are expected." That is the gap a one-person repository cannot close. GitHub forbids
approving your own pull request, so with `enforce_admins` on, nothing could ever merge. What the
configuration does deliver is that the bypass is recorded, not silent.

The second pair of eyes in a financial pipeline sits in three places here, and only the last two
are real with one person:

1. **Code review** on the pull request (configured; needs a second person).
2. **Promotion is a separate act.** The pipeline builds and signs; it does not deploy. A release
   reaches the cluster only by a commit to the GitOps repository naming a signed digest
   (`sl-ledger: promote 3b278854eac9`), and admission refuses any digest this repository's
   workflow did not sign. The fake digest in `14-k3s-deploy.txt` was refused.
3. **A money rule changes behind maker-checker**, below.

### Releasing a money rule: a flag, two people, small steps, one-person kill

The change released this way: the accrual day count becomes a value of the domain (`DayCount`:
ACT/365F as before, ACT/360 for the USD, EUR and CHF money markets), applied once per fee, so every
existing figure is unchanged on ACT/365F. Which convention a position gets is a rollout of the flag
`accrual.day-count-by-market`, selecting counterparties by a stable hash bucket (SHA-256 of flag and
id, mod 100), so a statement never mixes conventions and raising the percentage only adds
counterparties.

The rules (`RolloutRules`), each refused as a 409 and audited (`14-rollout-api.txt`):

- the maker is not the checker (also a table constraint, for a writer that bypasses the code);
- the first step from zero is at most 5%;
- a raise is approved only after the current step was in effect for the observation period
  (30 min by default, 10 min on the cluster);
- a raise proposed before the rollout moved is stale;
- **lowering is one person and immediate**: restricting a change only reduces exposure, and a kill
  switch that waits for a second person is a ticket.

The accrual job takes the percentage once, into its execution context. A restart of a failed run
keeps it: raised to 100% between a failure at chunk 3 and the restart, the run finished its date at
5% (`14-accrual-rollout.txt`). One business date is never booked under two rules.

On the cluster (`14-canary.txt`): 40 synthetic counterparties; ops-a proposed 5%, could not
approve it, ops-b did; ops-b's immediate approval of 25% was refused for the observation period.
The run for 2026-10-21 booked exactly the two counterparties the bucket function predicted
(CP-K018, CP-K034) at 1.01 USD a day on ACT/360 and the other 38 at 1.00. ops-a killed it alone;
the run for 2026-10-22 booked all 40 at 1.00, and 2026-10-21's two entries at 1.01 stayed booked.

### Rollback is a revert, and the drill timed it

A release that mistyped the prod profile's upper rate bound (0.0150 for 0.1500) passed every gate,
because no test loads the prod profile: built, scanned, signed from `drill/rate-bound`, admitted,
probes green, and every booking refused (`14-rollback-drill.txt`):

| Interval | Time |
|---|---:|
| promotion pushed → first refused booking | 62 s |
| first refused booking → rollback pushed | 525 s |
| rollback pushed → first good booking | 50 s |

The middle number is the honest one. The drill's driver detected the failure 16 s after it began
and then failed its own revert (a flag `git revert` rejects), logged a revert it had not made, and
509 s passed before anyone read that log. The revert itself, by hand, took 50 s to good bookings,
8 s of it ArgoCD applying the commit. 112 bookings were refused in between. Nothing was rebuilt to
roll back: the previous digest was still signed in the registry.

## What I rejected

- **A configuration server for the flag.** The ledger's database already has transactions, the
  audit table and role checks; a second store would need all three.
- **Argo Rollouts or mesh traffic splitting for the canary.** They split traffic by request; the
  ledger's risk is in what a batch books per counterparty, which a percentage of requests does not
  select.
- **Deploying from the pipeline.** A job that can write the GitOps repository is a job that can
  promote by itself; promotion is the step where a person decides.
- **Buildpacks.** A second toolchain choosing the base image and JVM flags elsewhere, against a
  Dockerfile of a page that can be read.
- **CDS by default, AOT, native**: above.
- **Approving one's own raise through `enforce_admins`.** It cannot be made to mean four eyes with
  one person; it only makes the repository unmergeable.
- **Lowering the coverage floor when the rollout store dropped `adapter-persistence` to 0.69.**
  The floor was right; the test was missing.

## What I observed

| Measurement | Value | Evidence |
|---|---:|---|
| layers rebuilt by a one-line domain edit | 1 (582 kB) | `14-layer-diff.txt` |
| layers different between two no-cache builds, before / after fixing times | 3 / 0 | `14-layer-diff.txt` |
| non-heap of the ledger after start | 205-211 MB | `14-ledger-memory.txt` |
| collector chosen by the JVM at 512 MB and 1 GB | Serial | `14-container-awareness.txt` |
| `-Xmx1g` in 512 MB / 75% in 512 MB | exit 137 OOMKilled / exit 3 OOME | `14-oomkilled-vs-oome.txt` |
| ready: baseline / CDS / AOT / AOT+CDS | 17.9 / 13.4 / 15.1 / 10.1 s | `14-startup.txt` |
| native build at 10 GB heap, 7 of 8 steps | 920 s, 8.55 GB peak, host rebooted | `14-native.txt` |
| graceful close with a 3 s request in flight | 2,543 ms, 200 | `14-shutdown.txt` |
| consumer stop, default / immediate | 2,059 ms, 5 handled / 428 ms, 1 handled | `14-shutdown.txt` |
| CI verify / scan / image | 736 / 62 / 89 s | CI run 39609315365 |
| gates made to fail and stopping at their step | 8 of 8 | `14-gate-drills.txt` |
| counterparties selected at 5%, predicted / booked on ACT/360 | 2 / 2 | `14-canary.txt` |
| rollback: revert pushed → good booking | 50 s | `14-rollback-drill.txt` |

## Where each choice breaks at 100×

Reasoned, not measured.

- **The pipeline's 11-minute integration step.** At 100 times the tests it is hours; the gate stops
  being run before each merge and becomes something read the next morning. It would have to be split
  by module and run in parallel, and the per-module coverage floors would have to become one merged
  report so that splitting does not hide code that only another module's tests reach.
- **Promotion as a hand-written digest commit.** At 100 services or 100 releases a day, a person
  copying digests is the bottleneck and the typo. The step would become a generated pull request on
  the GitOps repository that a second person approves, which is also where four eyes becomes real.
- **The rollout's hash bucket.** It assumes counterparties are interchangeable. At 100 times the
  counterparties the 5% still holds, but 5% of a ledger whose ten largest borrowers hold most of
  the exposure can select one of them by chance; the first step would need an exposure cap, not a
  head count.
- **Observation by waiting.** "In effect for 30 minutes" says nothing about whether anyone looked.
  At volume the condition for the next step has to be a measured one (no reconciliation breaks, fee
  totals within a bound of the old rule), checked by the pipeline, not a clock.
- **Rollback by revert.** Fine while a bad release only refuses work. A release that *books* wrong
  amounts is not undone by a revert: what it booked stays booked, and at 100 times the volume the
  correction run is the incident. That asymmetry, not caution as a temperament, is why the rule
  change went behind a flag at all.
- **One image per environment, one database per environment.** Correct at any scale, but the
  three local environments shared one Postgres server; at 100 times the services the environments
  need their own infrastructure, not their own database names.
- **The 1 GiB pod and a 70% heap.** The non-heap was measured on one workload. Caches (stage 9) and
  thread pools grow it; at 100 times the traffic the 300 MB margin is the first thing to recheck,
  and the failure will be exit 137 with no log line, the kind section 3 reproduced.

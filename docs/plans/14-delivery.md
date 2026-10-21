# Stage 14 — Packaging and delivery · Implementation Plan

Spec: [`docs/specs/14-delivery.md`](../specs/14-delivery.md)

One section per commit, in order. Implement a section, tick its checkbox in
the same commit, then commit. `./mvnw clean verify` at the repository root
after every section, run in a throwaway worktree of the committed HEAD.

## Ground rules for every section

- Scripts for manual experiments live in `infra/delivery/`; they take every
  path and secret as an argument or environment variable and write their
  output to `target/evidence/14-*.txt`, copied by hand to `docs/evidence/`.
- Numbers come from this machine (4 arm64 cores, Docker 29) or from the
  cluster, named per number. Startup numbers are medians of at least five runs.
- Packages: `domain.accrual` (`DayCount`), `application.rollout` (flag rules,
  ports), `adapter.persistence.rollout`, `adapter.web.rollout`.
- Pure unit tests end in `Test`; anything needing Docker or a Spring context
  ends in `IT`.
- A test that asserts wrong behaviour is named `reproduces<Failure>`, runs
  against test-only configuration, and carries
  `// See docs/decisions/14-delivery.md`.
- Work in the GitOps repository follows its own rules: git first, one change
  per commit, no plaintext secret, ArgoCD syncs; nothing is applied by hand.
- Synthetic data only. The cluster's signing key for test tokens lives outside
  both repositories.

## Decisions made while planning

Each is carried to the decision record.

1. **The ledger's own modules get a layer of their own.** They are SNAPSHOT
   versions, so Boot's default puts them in `snapshot-dependencies` beside any
   third-party snapshot; a layer named for them says what changes on every
   commit.
2. **The image is built from the repackaged jar, not by buildpacks.** A
   Dockerfile is twenty lines that can be read; a builder image is a second
   toolchain whose base and JVM flags are chosen elsewhere.
3. **The coverage floor is per module, at the measured line coverage rounded
   down to the next 5%.** A floor above today's number fails today; a floor far
   below it never fails.
4. **The flag store is the ledger's database.** It already has transactions,
   the audit table and role checks; a config server would be a second store
   that needs all three.
5. **The rollout selects counterparties, not positions.** A counterparty sees
   one rule on all of its positions, so a statement never mixes conventions; the
   bucket is a stable hash of the counterparty id, so raising the percentage
   only adds counterparties.
6. **Lowering is one person, raising is two.** Restricting a change can only
   reduce exposure; making a kill switch wait for a second person makes it a
   ticket.
7. **The accrual job takes the flag once, into the job's execution context.**
   A restart of a failed run restores it from there, so a retried date is
   booked under the rule its first attempt used.
8. **Promotion to the cluster is a digest bump committed by hand to the GitOps
   repository.** The pipeline pushes and signs; it does not deploy. The step
   between "built" and "running" is a human commit, which is where the second
   pair of eyes sits.
9. **The bug release for the rollback drill is built from a `drill/*` branch.**
   The admission policy accepts this repository's workflow identity on `main`
   and on `drill/*`; a broken version never lands on `main`.

## Review focus

1. Only the `ledger` layer changes on a module edit (section 2).
2. Graceful shutdown completes an in-flight request and commits no Kafka
   offset ahead of its effect (section 7).
3. The maker-checker rules: same person, first step, observation period, kill
   (section 11).
4. One business date under one rule, restart included (section 12).
5. The rolling update's zero comes from the order readiness, endpoints,
   server, exit (section 14).

---

## [x] 1 · Repackaged jar with a ledger layer

**Change**
- `bootstrap` POM: `spring-boot-maven-plugin` `repackage` and `build-info`
  bound; `src/main/layers/layers.xml` with `dependencies`,
  `spring-boot-loader`, `snapshot-dependencies`, `ledger`
  (`com.jerome.slledger:*`), `application`. Failsafe reads
  `target/classes`, not the repackaged jar.

**Tests**
- `LayeredJarIT`: the repackaged jar's `BOOT-INF/layers.idx` lists the five
  layers in order; every module jar of this project is in `ledger` and nothing
  else is; `build-info.properties` is present.

## [x] 2 · The image, and what a one-line change rebuilds

**Change**
- `bootstrap/Dockerfile`: extract `--layers` in a build stage, one `COPY` per
  layer onto a pinned Temurin 21 JRE, numeric non-root user, JVM options
  (`MaxRAMPercentage=75`, `ExitOnOutOfMemoryError`), `EXPOSE 8080 8081`,
  revision label and `LEDGER_REVISION` environment from a build argument.
- `.dockerignore`; `infra/delivery/image.sh` (package then build, tag by
  commit); `/actuator/info` shows build and revision.
- `infra/delivery/layer-diff.sh`: builds the image at a commit and at the same
  commit plus a one-line change in `domain`, in a temporary worktree, and
  prints both images' layer digests and sizes; then the same with the default
  layers.

**Evidence** `14-layer-diff.txt`.

## [x] 3 · Heap in a container

**Change**
- `labs/14-delivery/heap/`: a self-contained allocator and a script that runs
  it in the Temurin image under `--memory`: `-XshowSettings:system` and
  `MaxHeapSize` at 512 MB and 1 GB limits; `-Xmx` above the limit ending
  `OOMKilled`/137; `MaxRAMPercentage` ending `OutOfMemoryError`/3.

**Evidence** `14-container-awareness.txt`, `14-oomkilled-vs-oome.txt`.

## [ ] 4 · Startup: baseline, CDS, AOT

**Change**
- `infra/delivery/startup.sh`: runs the image N times per variant against the
  Compose services, reads "Started ... in" and the time to readiness; CDS
  archive by a training run (`spring.context.exit=onRefresh`); AOT by
  `process-aot` in an `aot` profile and `-Dspring.aot.enabled=true`.
- What ships follows the numbers: the Dockerfile keeps or gains the CDS step.

**Evidence** `14-startup.txt` (table and the cost of each variant).

## [ ] 5 · GraalVM native

**Change**
- `native` profile in `bootstrap` (`native-maven-plugin`, AOT), built in a
  GraalVM container by `infra/delivery/native.sh`; hints for whatever fails.
- Startup, RSS, image size and build time against the JVM image.

**Evidence** `14-native.txt` (including every failure met).

## [ ] 6 · One image, three environments

**Change**
- `infra/delivery/envs/` with three environment files (no secrets: those come
  from the caller's environment) and an optional mounted `config/` directory.
- `infra/delivery/three-envs.sh`: the same digest started three times,
  `/actuator/info` and one setting per run.

**Tests**
- `ConfigPrecedenceIT`: the repackaged jar started as a process with a
  command-line argument, an environment variable, a mounted
  `config/application.yml` and the packaged default for one property, removing
  them one at a time; the winner each time.

**Evidence** `14-three-envs.txt`, `14-config-precedence.txt`.

## [ ] 7 · Shutdown

**Change**
- `spring.lifecycle.timeout-per-shutdown-phase` set explicitly.
- Kafka listener containers stop in the graceful phase with their in-flight
  record handled.

**Tests**
- `reproducesInFlightRequestCutOffByImmediateShutdown`: a test-only slow
  endpoint, context closed mid-request with `server.shutdown=immediate`; the
  client loses the response.
- `GracefulShutdownIT`: same with graceful; response completes, new
  connections refused during the drain.
- `ConsumerShutdownIT`: a record whose handler is in progress when the context
  closes; its effect and its offset, or neither and a redelivery.

**Evidence** `14-shutdown.txt`.

## [ ] 8 · The pipeline

**Change**
- JaCoCo `check` per module with the floor from planning decision 3.
- `.github/workflows/ci.yml`: `verify` and `dependency-scan` on every push and
  pull request; `image` (arm64 runner) after both, on `main` and `drill/*`:
  package, build, Trivy gate, push to GHCR, Cosign keyless sign, digest in the
  summary.
- `.github/CODEOWNERS`.

**Acceptance:** a green run on `main` with a signed image.

## [ ] 9 · Gates that fail, and the rule on main

**Change**
- One throwaway branch per gate, each with the smallest change that should
  fail it, run through the workflow; the failed run's gate and its blocked
  later jobs recorded; branches deleted.
- Branch protection on `main`: required checks `verify` and
  `dependency-scan`, pull request with one review, administrators may bypass.

**Evidence** `14-gate-drills.txt`, `14-branch-protection.txt`.

## [ ] 10 · Day count as a value

**Change**
- `domain.accrual.DayCount` (`ACT_365F`, `ACT_360`, convention per
  currency); `AccrualRule` exposes the annual fee and divides by a day count
  once; `AccrualEntry.accrue` takes one. `ACT_365F` reproduces every existing
  figure.

**Tests**
- Every existing accrual test unchanged and green; `DayCountTest` (USD and
  EUR on `ACT_360`, HKD on `ACT_365F`, one known value each).

## [ ] 11 · The rollout flag and its rules

**Change**
- `application.rollout`: `Rollout` (percentage, effective since), `RolloutChange`
  (proposed by, approved by), `RolloutRules` (maker is not checker, first
  step at most 5%, raise only after the observation period, lower is
  immediate), `Bucket` (stable hash of a counterparty id into 0-99), ports
  `RolloutStore`, use cases `ProposeRaise`, `ApproveRaise`, `Lower`.
- `V19__feature_rollout.sql`; JDBC store; audit rows for propose, approve,
  refuse and lower.

**Tests**
- `RolloutRulesTest`, `BucketTest` (stable, uniform enough at 10 000 ids,
  monotone in the percentage), `RolloutStoreIT`.

## [ ] 12 · The flag on the API and in the accrual

**Change**
- `POST /rollouts/{flag}/raises`, `POST /rollouts/{flag}/raises/{id}/approval`,
  `POST /rollouts/{flag}/lower`, `GET /rollouts/{flag}`; `ledger-ops` only;
  `409` problems for refused rules.
- The accrual job's listener stores the percentage in the job execution
  context on first start; the processor picks `DayCount` per position from it.

**Tests**
- `RolloutApiIT` (matrix, maker-checker over HTTP, audit rows);
  `AccrualRolloutIT` (5%: exactly the selected counterparties' USD positions on
  `ACT_360`; a restart keeps the first value; after lowering, the next date is
  `ACT_365F`).

## [ ] 13 · On K3s

**Change** (GitOps repository)
- Namespace, Postgres, Redis, Kafka, market-data stub and key-set stub,
  sealed secrets, the ledger Deployment (startup, liveness, readiness probes,
  `preStop` sleep, resources, restricted security context), Service, ArgoCD
  Application, admission policy entry for this repository's workflow identity.
- Here: `infra/delivery/token.sh` (signs a test token with a key given by
  path), the cluster's public key set in the stub.

**Evidence** `14-k3s-deploy.txt` (sync, admission, probes, a token-bearing
request).

## [ ] 14 · Rolling update under load

**Change**
- A k6 script in `infra/delivery/` run as a pod in the namespace; a rollout
  with `preStop` removed (GitOps commit, then reverted), and with it.

**Evidence** `14-rolling-update.txt` (both runs: requests, 5xx, failed
connections).

## [ ] 15 · Canary

**Change**
- None in code. On the cluster: raise to 5% (maker, checker), a raise inside
  the observation period refused, accrual run, the kill switch, next run.

**Evidence** `14-canary.txt`.

## [ ] 16 · Rollback drill

**Change**
- A `drill/*` branch with a bug, built and signed by the pipeline, promoted
  by digest, detected by a smoke loop, reverted; three times measured.

**Evidence** `14-rollback-drill.txt`.

## [ ] 17 · Decision record

**Change**
- `docs/decisions/14-delivery.md`; roadmap ticks and progress table; this
  plan's notes.

---

## Found while implementing

- **Section 1.** `<includeModuleDependencies/>` knows a module only when it is
  in the same reactor: `./mvnw -pl bootstrap package` left the `ledger` layer
  empty. The layer selects by coordinates (`com.jerome.slledger:*`) instead.
- **Section 1.** `build-info.properties` lands at the jar's root
  `META-INF/`, not under `BOOT-INF/classes/`; `/actuator/info` reads it there.
- **Section 2.** Planning decision 1's premise was half wrong: in a full
  reactor build Boot's default already puts the project's modules in
  `application`, and a domain edit changes one 688 kB layer either way. They go
  to `snapshot-dependencies` only when bootstrap is built alone. The `ledger`
  layer stays for that reason (a layout independent of how Maven was invoked)
  and saves the 115 kB of bootstrap's own classes per module edit; the decision
  record says so instead of claiming more.
- **Section 2.** Two `--no-cache` builds of the same jar differed in three
  layers, the 109 MB `dependencies` one included: `extract` stamps directories
  and `application.jar` with the time of the `RUN`. The Dockerfile now sets
  every extracted file to the build's `outputTimestamp`; two builds are then
  identical. A warm build cache had hidden it.
- **Section 3.** The spec's 75% does not fit the ledger at a 768 MB limit: its
  non-heap measured 205-211 MB after start and 500 reads, and 25% of 768 MB is
  192 MB, so a full heap would end in an OOM kill, the failure the percentage
  exists to avoid. The image ships 70% and the cluster gets a 1 GiB limit
  (718 MB heap, about 300 MB for the rest).
- **Section 3.** Below 1792 MB the JVM is not "server class" and picks
  SerialGC by itself (measured at 512 MB and 1 GB). The image names G1, so a
  limit change cannot change the collector behind anyone's back.

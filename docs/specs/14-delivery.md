# Stage 14 — Packaging and delivery

## Problem

Up to stage 13 the ledger runs as `./mvnw spring-boot:run` or a jar on this
machine, against Compose. Nothing about it is a release: there is no image, no
statement of which commit is running, no gate between a commit and a running
process except a developer remembering `./mvnw verify`, no way to change a
money rule for some counterparties and not others, and no measured answer to
"how long does it take to undo a bad release".

Stage 14 turns the build into a delivery path, commit to cluster, and drives
each failure that path usually has to a reproduced and fixed result: an image
whose every layer changes on a one-line edit, a heap the kernel kills instead
of the JVM, a request cut in half by a shutdown, a rolling update that drops
requests, a gate that is green because it checks nothing, a rule change that
reaches everyone at once, a rollback nobody has timed.

Six facts shape the stage.

- **An image is an artifact, not a build.** Configuration that differs between
  environments is outside it; the digest that ran in staging is the digest that
  runs in production, or the staging run proved nothing about production.
- **The container's memory limit is the JVM's budget, not the heap's.** The
  heap, metaspace, thread stacks, code cache and direct buffers share it, and
  the two ways of running out look nothing alike: the JVM's own
  `OutOfMemoryError` is a stack trace in a log, the kernel's OOM kill is a
  restart with exit code 137 and no log line at all.
- **Startup speed is bought with flexibility.** CDS costs a training run and an
  archive tied to one JVM build; Spring AOT freezes the bean graph (conditions
  and profiles) at build time; a native image freezes the classpath and needs
  every reflective access declared. Each is measured and its price written
  down.
- **Zero-downtime is a sequence, not a switch.** Readiness must go false before
  the endpoint is removed, the endpoint must be removed before the server stops
  accepting, in-flight work must finish before the process exits, and all of it
  must fit inside the pod's grace period.
- **A gate that never failed is not known to work.** Every gate in the pipeline
  is made to fail once on purpose, and the failure is shown to block.
- **In a ledger, rolling back code does not roll back what it booked.** An
  accrual entry booked by a bad version stays booked; that asymmetry, not
  temperament, is why money systems release behind flags, in steps, with two
  people.

## Settled design decisions

The decision record argues each; the spec states what they commit the code to.

1. **One image, layered for change frequency.** `spring-boot-maven-plugin`
   repackages `bootstrap` (bound in the build from now on) with a custom
   `layers.xml`: `dependencies`, `spring-boot-loader`, `snapshot-dependencies`,
   and last `ledger` (this project's own modules, which are SNAPSHOTs and
   would otherwise sit in `snapshot-dependencies`), then `application`. The
   Dockerfile extracts the layers in a build stage and copies each into its own
   image layer on a pinned Eclipse Temurin 21 JRE base, running as a numeric
   non-root user. A one-line change in a module changes only the `ledger`
   layer, proved by comparing layer digests.
2. **Heap is a percentage of the container.** `-XX:MaxRAMPercentage=75`,
   `-XX:+ExitOnOutOfMemoryError`, no `-Xmx` in the image. Container awareness
   is verified on the pinned JDK by reading the limits the JVM reports under a
   Docker memory limit. The fixed-`-Xmx`-above-limit failure (OOMKilled) and
   the percentage failure (`OutOfMemoryError`) are both reproduced in
   `labs/14-delivery/`.
3. **Startup: three measured variants, one shipped.** Baseline, CDS (AppCDS
   archive from a training run with `spring.context.exit=onRefresh`) and Spring
   AOT, each started several times against the same services; median of
   "started in" and of time to readiness. What ships is decided by the numbers
   and by what each variant disables, not ahead of them.
4. **GraalVM native is built, measured and judged, not shipped.** A `native`
   profile in `bootstrap`, built inside a GraalVM container so the machine needs
   no GraalVM install. Startup, RSS and build cost are recorded, and every
   reflection or resource problem met on the way.
5. **Configuration is the environment's.** The image carries the packaged
   defaults and the profile files; an environment supplies a profile, secrets
   and endpoints through environment variables and an optional mounted
   `config/` directory. Three environments run the same digest. The precedence
   between command line, environment variable, mounted file and packaged file is
   verified against the real jar in a separate JVM, since an in-process test
   cannot set an environment variable.
6. **Shutdown drains, in order.** `server.shutdown=graceful` (already set in
   stage 12) with an explicit `spring.lifecycle.timeout-per-shutdown-phase`; the
   cut-off request is reproduced with `immediate`. The Kafka listener
   containers stop after the in-flight record is handled and its offset is
   committed; a record whose handling was interrupted is delivered again, not
   lost. In Kubernetes a `preStop` sleep covers endpoint propagation, and
   `preStop + shutdown phase < terminationGracePeriodSeconds` is a stated
   inequality.
7. **Three probes, three questions.** Startup (has it finished starting; long
   threshold, so a slow start is not a liveness kill), liveness (is the process
   wedged; never checks a dependency) and readiness (should it get traffic;
   includes the database, as stage 12 set). A rolling update under continuous
   load shows zero 5xx, after the version without `preStop` is shown to drop
   requests.
8. **The pipeline is GitHub Actions, gates in order.** `./mvnw verify` (format,
   enforcer, compile with `-Werror`, tests, ArchUnit, a JaCoCo coverage floor
   new in this stage), the dependency scan, then the image: built on a native
   arm64 runner, scanned by Trivy, pushed to GHCR and signed keyless with
   Cosign. Each gate is made to fail once on a throwaway branch and the blocked
   run is recorded.
9. **Four eyes is a branch rule plus a promotion step.** `main` requires the
   CI checks and a reviewed pull request; the administrator's bypass is
   recorded, not hidden. Promotion to the cluster is a separate commit, by
   digest, in the GitOps repository. What a one-person repository cannot
   enforce is written down, not implied.
10. **The rule change is a flag with maker-checker, steps and a kill switch.**
    The accrual day-count basis becomes a value of the rule set
    (`ACT/365F` today; `ACT/360` for currencies whose money markets use it).
    The new basis is released behind a flag whose rollout percentage selects
    counterparties by a stable hash. Raising the percentage needs a maker and a
    different checker; the first step is at most 5%; any raise needs the
    current step to have been in effect for an observation period; lowering it,
    to zero included, is one person and immediate. Every change is audited. The
    accrual job reads the flag once per run and records the value it used, so
    one business date is never booked under two rules.
11. **Deployed to K3s, git first.** The ledger, its own Postgres, Redis and
    Kafka, the market-data stub and a static key set stand in for the identity
    provider, in their own namespace, managed by ArgoCD from the GitOps
    repository. Admission verifies the image's signature against this
    repository's workflow identity. Promotion and rollback are commits.
12. **The rollback is timed.** A version with a bug is built from a branch that
    never merges, promoted, detected by a smoke check, and rolled back by a
    revert; the times from promotion, detection and revert to a clean check are
    measured.

## Scope

In: the plan's sections. Product code: layer configuration and image, JVM
options, build info, the shutdown settings, the flag and its maker-checker
rules, the day-count basis, the flag's endpoints and audit, the accrual job's
flag snapshot, the coverage floor, the CI workflow, CODEOWNERS, and `infra/`
scripts for image, startup and environment experiments. Outside this
repository: the GitOps manifests, the admission policy entry and the
ArgoCD Application.

Out: a configuration server (the flag lives in the ledger's database, which is
the configuration store that already has transactions, audit and roles),
progressive delivery controllers (Argo Rollouts, service-mesh traffic
splitting), multi-arch images, SBOM attestation, a staging cluster (the three
environments are three runs of one image on this machine plus the cluster),
autoscaling, and backups of the cluster's Postgres.

Broken versions go where `CLAUDE.md` puts them:
`reproducesInFlightRequestCutOffByImmediateShutdown` is a test that asserts the
wrong behaviour; the heap failures are in `labs/14-delivery/`; the
rolling-update drop and the bug release live only on branches and in the GitOps
history, never in this repository's `main`.

## Acceptance

1. A one-line change in a module rebuilds an image whose layer digests differ
   from the previous image's in the `ledger` layer only (evidence lists both
   digest sets); the default layering is shown to change a larger layer.
2. Under `docker run --memory`, the JVM reports the container's limit and a
   max heap of 75% of it; a fixed `-Xmx` above the limit ends `OOMKilled`
   with exit 137 and the percentage setting ends with `OutOfMemoryError` and
   exit 3, both recorded.
3. Baseline, CDS and AOT startup are measured with at least five runs each;
   the table and what each one disables are in the decision record.
4. A native image of the ledger is built and started, or the point where it
   could not be is recorded; its startup, RSS and build time are measured.
5. One image digest runs as three environments with different settings and no
   rebuild; a test against the real jar shows command line over environment
   variable over mounted file over packaged file.
6. `reproducesInFlightRequestCutOffByImmediateShutdown` shows the client losing
   the response; the graceful setting completes it. A record in flight when the
   consumer is stopped is either committed after its effect or delivered again,
   never committed without it.
7. On K3s, a rolling update under continuous load answers zero 5xx and zero
   failed connections with `preStop`; without it, the drop is shown.
8. Each pipeline gate (format, enforcer, test, ArchUnit, coverage, dependency
   scan, image scan) has a recorded failed run that stopped the pipeline at
   that gate; a green run on `main` pushes a signed image.
9. `main`'s protection rule is in place and its required checks are the
   pipeline's jobs (if the repository can carry one; otherwise the gap is
   recorded).
10. The flag's rules are unit tested (same person approving, a first step over
    5%, a raise inside the observation period, an unauthorised role are each
    refused; a kill is immediate); a canary at 5% books `ACT/360` for exactly
    the selected counterparties and the kill switch returns the next run to
    `ACT/365F`; the run records the flag value it used.
11. The rollback drill's three times are in the decision record.
12. `./mvnw clean verify` is green; no stage 1-13 test is weakened.
13. The decision record contains only measured numbers and the 100x section.

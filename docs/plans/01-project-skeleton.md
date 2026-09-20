# Stage 01 — Project Skeleton · Implementation Plan

Spec: [`docs/specs/01-project-skeleton.md`](../specs/01-project-skeleton.md)

One section per commit, in order. Implement a section, tick its checkbox in
the same commit, then stop for review. Each acceptance line is a command and
what it must print.

---

## [x] 1 · Maven Wrapper and parent POM

Aggregator POM only, no modules yet: `groupId`, `artifactId`, `pom` packaging,
Java 21 properties, `project.build.outputTimestamp`. Maven Wrapper checked in
(`mvnw`, `mvnw.cmd`, `.mvn/wrapper/`). `.gitignore` covering `target/`,
`*.hprof`, IDE files — but *not* GC logs, which are committed evidence.

**Acceptance** — `./mvnw -v` prints the pinned Maven version and Java 21;
`./mvnw validate` is green.

## [x] 2 · Six empty modules

`domain`, `application`, `adapter-persistence`, `adapter-web`, `batch`,
`bootstrap` listed in `<modules>`, each with a minimal POM and the dependency
direction from the spec's table. No code.

**Acceptance** — `./mvnw clean package` is green and produces six jars under
`*/target/`.

## [x] 3 · Spring Boot BOM imported

`spring-boot-dependencies` in the parent's `dependencyManagement` with
`<scope>import</scope>` and `<type>pom</type>`. The parent does **not** inherit
`spring-boot-starter-parent`. One dependency added to `adapter-web` with no
`<version>`.

**Acceptance** — `./mvnw dependency:tree -pl adapter-web` shows that
dependency resolved to the BOM's version.

## [x] 4 · Compiler settings and editor config

`maven-compiler-plugin` on release 21, `-Xlint:all`, warnings as errors where
practical. `.editorconfig`.

**Acceptance** — `./mvnw clean compile` is green; a throwaway class using a
record pattern compiles, and is removed before the commit.

## [x] 5 · Spotless with google-java-format

Bound to `validate` so a misformatted file fails an ordinary build, not only
an explicit `spotless:check`.

**Acceptance** — misformat a file, `./mvnw spotless:check` fails naming it;
`./mvnw spotless:apply` then makes it pass. The violation is not committed.

## [ ] 6 · Enforcer: versions, SNAPSHOTs, convergence

`maven-enforcer-plugin` with `requireJavaVersion`, `requireMavenVersion`,
`requireReleaseDeps`, `dependencyConvergence`.

**Acceptance** — `labs/01-project-skeleton/convergence/` holds a POM
introducing two versions of one library; building it fails with a
`dependencyConvergence` message, and `dependency:tree -Dverbose` shows Maven's
nearest-wins arbitration. Both outputs go to `docs/evidence/`. `main` stays
green.

## [ ] 7 · Enforcer: the `domain` boundary

`bannedDependencies` on `domain` — allow only the JDK and test-scoped
dependencies, ban everything else. This is the gate the spec exists for.

**Acceptance** — adding a third-party dependency to `domain` fails the build
with the enforcer's message; the demonstration lives in `labs/`, and the
message text goes to `docs/evidence/`.

## [ ] 8 · Profiles and reproducible build

`local` and `ci` profiles. Verify `project.build.outputTimestamp` actually
produces byte-identical jars.

**Acceptance** — `./mvnw help:active-profiles -Plocal` and `-Pci` each show
exactly the expected profile; two consecutive `./mvnw clean package` runs
produce identical SHA-256 checksums for all six jars. Checksums recorded for
the decision record.

---

## Then

`docs/decisions/01-project-skeleton.md` — chose / rejected / observed / where
it breaks at 100x, carrying the measured checksums, the arbitration output and
the enforcer messages from sections 6–8.

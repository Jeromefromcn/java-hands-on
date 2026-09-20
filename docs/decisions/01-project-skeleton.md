# Stage 01 — Project Skeleton · Decision Record

Spec: [`docs/specs/01-project-skeleton.md`](../specs/01-project-skeleton.md) ·
Plan: [`docs/plans/01-project-skeleton.md`](../plans/01-project-skeleton.md)

Every number below was measured on this machine (linux aarch64, Maven 3.9.11
through the wrapper, Temurin 21.0.12). The raw output is in `docs/evidence/`.

## What I chose

**Maven multi-module as the architectural boundary, not package naming.**
Six modules with the dependency direction fixed by their POMs:
`adapter-* → application → domain`, and `domain` depending on nothing. The
point is not tidiness. A layering rule expressed in package names is enforced
by whoever reviews the pull request; the same rule expressed as a module
dependency is enforced by the compiler.

**`domain` takes no third-party dependency outside test scope**, checked
transitively by the enforcer's `bannedDependencies`. This single rule decides
more than it appears to: it rules out JPA annotations on domain types, which
rules out the most common architecture in Spring applications, which means
the persistence model has to be a separate type from the domain model. Stage 6
inherits that as a constraint rather than a discussion.

**The Spring Boot BOM is imported, not inherited.** `spring-boot-dependencies`
4.1.1 goes into the parent's `dependencyManagement` with `<scope>import</scope>`
rather than the project inheriting `spring-boot-starter-parent`. A POM has
exactly one `<parent>`, and in a regulated shop that slot is taken by the
organisation's own parent, which carries the internal repository, the mandatory
scanning plugins and the artifact rules. Importing is therefore the only shape
available there, not the more elegant one.

What inheriting would have supplied beyond versions — encoding, a
`java.version` property, resource filtering, plugin versions, the repackage
binding — this project configures itself. In stage 1 that overlaps exactly with
what the stage set out to practise, so the cost is not felt yet; it will be
when the `spring-boot-maven-plugin` is wired up.

**Every gate is a build failure, at `validate`.** Formatting (Spotless with
google-java-format), lint (`-Xlint:all -Werror`), the JDK version, SNAPSHOT
dependencies, dependency convergence, plugin versions and the `domain`
boundary all fail the build rather than printing a warning. A rule that only
reports is a rule nobody reads.

**Java 21, pinned twice.** `.sdkmanrc` selects Temurin 21.0.12 and
`requireJavaVersion [21,22)` rejects anything else. The machine default is a
newer JDK on purpose: stage 8 compares a newer JVM against this baseline, so
the baseline must not drift.

## What I rejected, and why

**Gradle, indefinitely.** Not on "Maven is more standard" grounds, but because
two build systems in one repository means two sources of dependency versions
that drift silently, and an IDE that imports duplicate modules. The comparison
exercise the roadmap suggests is deferred with it; if it ever happens it lives
in `labs/`, never at the repository root.

**Inheriting `spring-boot-starter-parent`.** Convenient, and unavailable in
most environments this project is modelled on.

**Lombok in `domain`.** It is not a library, it is a compiler hack: it patches
the javac AST through internal API, and it has broken on JDK 9, 16 and 21.
This project deliberately switches JDKs in stages 8 and 15, so the baseline
cannot depend on something that breaks when the JDK moves. Java 21 records
remove most of what Lombok was there for. Other modules may use it.

**Warnings as warnings.** `-Werror` will eventually make a third-party
deprecation fail this build. That is the intended moment to narrow `-Xlint`
with a concrete case to point at, rather than starting narrow and never
revisiting it.

**A `local` profile with content in it for symmetry.** It is empty. A profile
that exists to look balanced is where differences hide.

## What I observed

**An unpinned plugin is not a warning, it is a moving build.** Before the fix,
`maven-compiler-plugin` carried no version: Maven resolved RELEASE from central
and selected **3.16.0**, while `maven-jar-plugin` came from the super-POM at
**3.4.1**. The reproducible-build checksums measured before that was fixed
therefore described a build that could change without anyone touching this
repository. They were re-measured, not reused.

**Reproducibility is configured, not inherent.** Two consecutive
`./mvnw clean package` runs (4.08s then 4.19s) produce byte-identical jars:

```
23770d7223d9b791dccbca21e52accab83a99f9ef79cd9038f0d839ab9d9eb58  domain-0.1.0-SNAPSHOT.jar
c6684f64f724c303276c1fa6505d87ab2469295b630735c48c2dd8cccb373e73  application-0.1.0-SNAPSHOT.jar
```

With `project.build.outputTimestamp` removed, the same two runs differ in all
six checksums. Without that control the first result would have proved nothing,
since the jars contain no classes yet.

**Maven's arbitration is nearest-wins, and it is not the only rule.** In the
convergence lab, guice 5.1.0 brings guava 30.1-jre transitively while the POM
declares 33.0.0-jre directly; the direct declaration wins at distance 0:

```
\- (com.google.guava:guava:jar:30.1-jre - omitted for conflict with 33.0.0-jre)
```

Separately, putting an explicit `<version>1.7.36</version>` on slf4j-api in
`adapter-web` produced **1.7.36 in that module's own tree and 2.0.18 in
`bootstrap`'s**: a managed version overrides a transitive one regardless of
distance, so the same artifact resolved to two versions in one reactor. That is
a compile-against-one, run-against-the-other bug, and `dependencyConvergence`
does not catch it — the rule evaluates one module at a time, and each module
saw only one version. This is the shape of the `NoSuchMethodError` stage 15
sets out to reproduce.

**A phase and a goal are different things.** `./mvnw -f <lab> validate` fails on
the convergence rule; `./mvnw -f <lab> dependency:tree -Dverbose` succeeds and
prints the tree, because the enforcer is bound to `validate` and a bare goal
invocation runs no lifecycle. The gate stops the build; the diagnostic that
explains the gate still works. The same holds for the JDK gate.

**`bannedDependencies` catches the tail, not just the head.** Adding guava to
`domain` at compile scope names `failureaccess`, `listenablefuture`, `jsr305`,
`checker-qual` and `error_prone_annotations` as well. At test scope the same
dependency passes, which is what stage 3 needs.

**The machine's "Java 21" was a JRE.** `/usr/lib/jvm/java-21-openjdk-arm64`
has no `javac`, so `./mvnw -v` reported Java 21 while nothing could compile.
`validate` did not reveal it because it compiles nothing. The version a build
reports and the version it can use are different claims.

**Two gates have never fired in an ordinary build.** No module has `src/` yet,
so `-Werror` and Spotless were exercised with throwaway files that were then
deleted. `docs/evidence/01-compiler-gate.txt` and `01-format-gate.txt` are the
only record that they work until stage 2 adds real code.

## Where this breaks at 100x

**Build time.** 4.1s for six empty modules is all fixed cost: JVM startup,
plugin resolution, and the `validate` chain. Compilation, Spotless and tests
scale with code, and `-Werror` plus format checking run on every module on
every build. At a few hundred source files the `validate` phase stops being
free, and the answer is incremental builds and a CI cache, not removing gates.

**`dependencyConvergence` gets louder as the tree grows.** With one starter it
passes; with a dozen Spring and persistence dependencies it will fail
regularly, and each failure needs a real decision (exclusion, managed version,
or accepting the conflict) rather than a reflex. The rule staying on is the
point; the cost is that upgrades stop being one-line changes.

**The `domain` boundary holds only while nobody needs money or time libraries.**
JSR-354 (`javax.money`) is the obvious candidate. `java.util.Currency` covers
ISO 4217 and `BigDecimal` covers the arithmetic, so the need does not arise
yet. If it does, the honest move is to record the exception here, not to widen
the rule quietly.

**Pinning every plugin version means owning every plugin upgrade.** Nine
versions are now this repository's responsibility. Without Dependabot or
similar, they rot. That is the price of the checksums above meaning anything.

**`requireJavaVersion [21,22)` will be wrong eventually.** It is correct while
21 is the baseline the later stages compare against. The day this project
moves to a newer LTS, that range has to be changed deliberately, which is the
intent.

## The invariant

Money is `BigDecimal` with an explicit currency, at every boundary. This stage
introduced no serializer, no cache, no message payload and no column type, so
there was nothing to check it against. First checked in stage 2.

## Still open

Two decisions were deferred to stage 2 rather than settled here, because both
need real types to argue about:

1. **Where the repository ports live** — `application` (as the module table
   currently says, Clean Architecture's shape) or `domain` (DDD's shape, where
   a Repository is part of the aggregate's language). The dependency direction
   is identical either way.
2. **Whether `AccrualEntry` sits inside the `LoanPosition` aggregate** — it
   decides the transaction boundary, where `@Version` goes, and whether a
   position's 365 entries per year load with it. It connects directly to the
   N+1 work in stage 6 and the locking work in stage 8.

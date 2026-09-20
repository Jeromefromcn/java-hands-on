# Stage 01 — Project Skeleton

## Problem

There is no build. Every later stage needs a place to put code, a single
source of truth for dependency versions, and a build that fails loudly when
someone violates a structural rule. Stage 1 produces that, and nothing else.

The structural rule that matters most here is the module boundary: `domain`
must never acquire a third-party dependency. Stated in prose it will be
violated within three stages; stated as an enforcer rule it fails the build
the moment it is violated. Stage 1 is where prose becomes an executable gate.

## Scope

A Maven multi-module build, Java 21, with six modules:

| Module | Contains | Depends on |
|---|---|---|
| `domain` | entities, value objects, domain services | nothing (zero third-party) |
| `application` | use cases, ports | `domain` |
| `adapter-persistence` | repository implementations | `application` |
| `adapter-web` | HTTP endpoints | `application` |
| `batch` | batch jobs | `application` |
| `bootstrap` | the runnable Spring Boot application | all of the above |

All six modules are created empty. Stage 1 delivers structure and gates, not
domain code — the first real types arrive in Stage 2.

Also in scope:

- Parent aggregator POM; `spring-boot-dependencies` imported as a BOM
  (`<scope>import</scope>`), **not** inherited via `spring-boot-starter-parent`
- All versions in the parent's `dependencyManagement`; no `<version>` in any
  child POM
- Maven Wrapper (`./mvnw`) checked in, pinning the Maven version
- `.editorconfig`, Spotless with google-java-format, `.gitignore`
- `maven-enforcer-plugin`: minimum JDK and Maven versions, no SNAPSHOT
  dependencies, `dependencyConvergence`, and `bannedDependencies` guarding
  `domain`
- `local` and `ci` profiles
- Reproducible builds via `project.build.outputTimestamp`

## Out of scope

- **Gradle comparison — deferred indefinitely.** Two build systems in one
  repository means two sources of dependency versions that drift silently, and
  an IDE that imports duplicate modules. If it is ever revisited it goes in
  `labs/`, self-contained, never at the repository root. The roadmap's
  *Maven vs Gradle* decision record is deferred with it.
- Any domain type, entity or business logic (Stage 2)
- Spring context configuration, beans, auto-configuration (Stage 4)
- Database, schema, migrations (Stage 6)
- CI pipeline configuration. The `ci` profile is defined here; what invokes it
  is a Stage 14 concern.

## Constraints

- Build tool is Maven, and `./mvnw` is the only supported entry point. A
  system `mvn` is not used, so that the Maven version is part of the repo.
- Java 21. Not 25 — the Java 25 features this project cares about are
  exercised in Stage 8, deliberately, against a pinned baseline.
- `main` always builds green. Every deliberate violation used to demonstrate a
  gate lives in `labs/01-project-skeleton/`, never in the main build.
- The money invariant (BigDecimal with an explicit currency at every boundary)
  introduces no component in this stage — there is no serializer, column type
  or payload yet. Nothing to check against it; first checked in Stage 2.

## Acceptance criteria

Each is a command and an observable result. A criterion that cannot be
checked by running something does not belong here.

1. `./mvnw -v` reports the wrapper-pinned Maven version and Java 21, with no
   system Maven on `PATH`.
2. `./mvnw clean package` is green and produces six jars under
   `*/target/`.
3. `./mvnw dependency:tree` resolves a dependency declared in a child POM
   without a `<version>`, proving the imported BOM governs versions.
4. Formatting: introducing a misformatted file makes `./mvnw spotless:check`
   fail; `./mvnw spotless:apply` makes it pass.
5. Adding any third-party dependency to `domain` fails the build with the
   enforcer's `bannedDependencies` message.
6. Introducing two versions of the same library fails the build with a
   `dependencyConvergence` message; the tree reports Maven's nearest-wins
   arbitration.
7. A SNAPSHOT dependency anywhere fails the build.
8. `./mvnw -Plocal` and `./mvnw -Pci` both resolve, and `help:active-profiles`
   shows exactly the expected profile active in each case.
9. Two consecutive `./mvnw clean package` runs produce jars with identical
   SHA-256 checksums. The checksums go in the decision record.

## Done when

The four deliverables named in `CLAUDE.md` exist: this spec, the plan, the
code with every criterion above verified, and
`docs/decisions/01-project-skeleton.md` carrying the measured checksums and
the dependency-arbitration output observed on this machine.

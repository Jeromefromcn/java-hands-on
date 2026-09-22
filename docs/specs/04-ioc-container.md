# Stage 04 — IoC Container

## Problem

Stage 4 introduces Spring Boot. The roadmap frames it as "hook stage 2's use
cases into the container, add no new functionality" — but stage 2 only built
`domain` value types and strategies, not an application-layer use case class;
the five use cases (open, return, daily accrual, revaluation, query) are
scoped to later stages once persistence and concurrency exist to make them
real. So this stage hooks the container onto what stage 2 actually left
behind: `domain`'s `ReferenceRates` interface (the one collaborator boundary
`FloatingRate` depends on) becomes a Spring-managed bean with two
implementations chosen at startup, and `domain`'s accrual-rule construction
is driven by `@ConfigurationProperties` instead of hardcoded bounds. Every
other exercise — bean lifecycle order, `BeanPostProcessor`, `proxyBeanMethods`,
circular dependency, scope mismatch — is container mechanics with no honest
attachment point in the domain yet, so it stays contained in tests that prove
understanding rather than product code that would only be decorative.

## Scope

### Module topology

`bootstrap` gains a `main` source tree for the first time — every other
module (`application`, `adapter-persistence`, `adapter-web`, `batch`) stays
empty, since this stage's exercises don't need them and adding classes there
just to look busy is scope creep. `bootstrap` becomes the one Spring Boot
module: `spring-boot-starter` and `spring-boot-starter-validation`
(for `@ConfigurationProperties` + `@Validated`) as compile dependencies,
`spring-boot-starter-test` at test scope, `spring-boot-maven-plugin` bound so
`./mvnw -pl bootstrap spring-boot:run` works.

`SlLedgerApplication` (`@SpringBootApplication`, package
`com.jerome.slledger.bootstrap`) is the entry point. It does nothing beyond
starting the container — no controller, no scheduled job, since those belong
to stages 7 and 11.

### Bean lifecycle order

`LifecycleLoggingBean` implements `BeanNameAware`, `InitializingBean`, has a
`@PostConstruct` method, and the enclosing config implements
`SmartInitializingSingleton` on a second bean (`AllBeansReadyMarker`) — records
each callback's name into an injected `List<String>` recorder bean (not a
static field: static state makes the assertion fragile across parallel test
runs). A test constructs a minimal `ApplicationContextRunner` context with
just these beans and asserts the recorded order. The full call order goes in
the decision record as prose, not just as a passing test — the spoken
acceptance in the roadmap is drawing this from memory, which the record has
to support.

### `BeanPostProcessor`: a timing proxy

`@Timed` (retention runtime, target method or type) plus `TimingBeanPostProcessor`
implementing `BeanPostProcessor`: in `postProcessAfterInitialization`, any
bean whose class (or a method on it) carries `@Timed` gets wrapped in a JDK
dynamic proxy (the bean always has an interface here — `ReferenceRates`) that
logs elapsed nanoseconds around each invocation via SLF4J. Applied to the
`ReferenceRates` bean so market-data lookups get timed without touching
`FloatingRate` or the two implementation classes. This is a hand-rolled proxy,
explicitly not Spring AOP — the decision record states why this is the
shortest path to understanding what `@Transactional`'s proxy does before
stage 5 introduces the framework's own version, and where a hand-rolled
`BeanPostProcessor` proxy stops scaling (no pointcut language, no advice
ordering, every cross-cutting concern needs its own processor) compared to
`@Aspect`.

### `proxyBeanMethods`

Two `@Configuration` classes — `ProxyBeanMethodsTrueConfig` (default) and
`ProxyBeanMethodsFalseConfig` (`@Configuration(proxyBeanMethods = false)`) —
each declaring `@Bean widget()` and `@Bean holder()` where `holder()` calls
`widget()` directly. Both are **nested static classes inside the test that
exercises them**, not wired into `SlLedgerApplication`'s component scan —
they exist only to be loaded one at a time via `ApplicationContextRunner`,
so they leave no footprint on the real app context. The test asserts
`context.getBean(Holder.class).widget() == context.getBean(Widget.class)`
holds under `true` and fails under `false`. The decision record states when
`proxyBeanMethods = false` breaks in practice: any `@Configuration` class
whose `@Bean` methods call each other for wiring, not just for readability.

### Circular dependency

`reproducesConstructorCircularDependency` (per `CLAUDE.md`'s naming
convention for a test that asserts the wrong-looking-but-real behaviour):
two nested static `@Component`s, `Alpha` and `Beta`, each constructor-injecting
the other. Asserts `ApplicationContextRunner`'s context fails to refresh with
a `BeanCurrentlyInCreationException` (unwrapped from
`UnsatisfiedDependencyException`). A second pair, `AlphaSetter`/`BetaSetter`,
using setter injection instead, asserts the context **does** refresh — kept
as the positive contrast in the same test class. Neither pair is wired into
the real application. Decision record: why Spring Boot 2.6+ defaults
`allow-circular-references` to `false`, and which of the three caches (
singleton objects / early singleton objects / singleton factories) the
setter-injection case still needs even though it succeeds — constructor
injection cannot use the third-level cache to break the cycle because the
object doesn't exist yet at the point the cycle is discovered, which setter
injection sidesteps by handing out an incomplete-but-referenceable instance.

### Scope: request-scoped context into a singleton

`CounterpartyRequestContext` (`@Component` `@RequestScope`, holds a mutable
`counterpartyId` field) simulates "the counterparty on whose behalf the
current request runs" ahead of stage 7's actual web layer. A singleton
`CurrentCounterpartyHolder` cannot constructor- or field-inject it directly
(no request active at singleton creation time); it takes an
`ObjectProvider<CounterpartyRequestContext>` and resolves it per call. The
test simulates a request without a running server —
`MockHttpServletRequest` + `RequestContextHolder.setRequestAttributes` inside
try/finally — since building the real web layer to test this is stage 7's
job, not this stage's; only `spring-test`'s mock servlet classes are needed,
already on the classpath transitively via `spring-boot-starter-test`. A second
test shows the direct-injection failure this sidesteps: field-injecting
`CounterpartyRequestContext` into a singleton and touching it outside a
request throws `BeanCreationException` wrapping
`IllegalStateException: No thread-bound request found`.

### Conditional wiring: market-data source

`MarketDataSource` implementations of `domain.accrual.ReferenceRates`:

- `MockReferenceRates` — deterministic fixed rates, `@ConditionalOnProperty(
  name = "marketdata.source", havingValue = "mock", matchIfMissing = true)`.
- `RealReferenceRates` — `@ConditionalOnProperty(name = "marketdata.source",
  havingValue = "real")`, implemented as a stub that throws
  `UnsupportedOperationException` on every method — the real `RestClient` call
  is stage 7's `RestClient` exercise; this stage only proves the wiring
  switches, not that the HTTP call works.

A third, hand-written `@Conditional` — `OnOperatingSystemCondition` (implements
`Condition`, checks `os.name` — a condition with no business meaning, chosen
because it is externally observable in a test without mocking `Environment`)
gates a `DiagnosticsBean` that only exists on the CI runner's OS family.
Exists solely to prove `@Conditional` is written, not just consumed via
`@ConditionalOnProperty`; the decision record says so plainly rather than
inventing a business justification for an OS-conditional bean.

Both `MockReferenceRates`/`RealReferenceRates` carry `@Timed`, so the
`BeanPostProcessor` section and this section compose in the same context
test rather than needing a second demonstration bean.

### Configuration binding: accrual rule bounds

`AccrualRuleProperties` (`@ConfigurationProperties(prefix = "accrual")`,
`@Validated`): `rateLowerBound`/`rateUpperBound` (`BigDecimal`, `@DecimalMin`/
`@DecimalMax`... Bean Validation doesn't constrain one field against another,
so the cross-field check — lower strictly less than upper — is a
`@AssertTrue`-annotated method on the properties class, not two independent
range annotations), `roundingPrecision` (`int`, `@Min(0)` `@Max(10)`). A
`@Bean` method constructs a `domain.accrual.FlatRate` from the validated
properties at startup — the one place this stage lets a container-managed
bean produce a `domain` value, proving the wiring reaches all the way to
stage 2's types. Invalid bounds (`lowerBound > upperBound`, or a negative
`roundingPrecision`) fail context refresh with `ConfigurationPropertiesBindException`
wrapping a `ConstraintViolationException` — asserted directly, not eyeballed
from console output.

### Profiles: `local` / `test` / `prod`

Four YAML files in `bootstrap/src/main/resources`: `application.yml` (base:
`accrual` bounds wide enough to be safe defaults, `marketdata.source: mock`),
`application-local.yml` (same mock source, tighter bounds resembling a real
desk), `application-test.yml` (used by the test-scope `ApplicationContextRunner`
runs above where a profile matters — currently only the precedence test),
`application-prod.yml` (`marketdata.source: real`). A single test,
`ConfigurationPrecedenceTest`, builds a `SpringApplicationBuilder` four ways —
base only; `+profile local`; `+profile local` with an environment-variable
override (set via `SpringApplicationBuilder.environment().getPropertySources()`
inserted programmatically, since a JVM's real env vars can't be set from
inside a running test); the same again with a `--accrual.rounding-precision=`
command-line argument — and asserts the resolved value at each step,
proving command-line > env var > profile YAML > base YAML by construction,
not by citing the precedence table.

## Out of scope

- Any `@Transactional`, `@Aspect`, or Spring-managed AOP proxy beyond the
  hand-rolled `TimingBeanPostProcessor`. Stage 5's entirely.
- Persistence, HTTP endpoints, batch jobs — `adapter-persistence`,
  `adapter-web`, `batch` stay empty. Stages 6, 7, 11.
- A working `RealReferenceRates` HTTP call. Stage 7's `RestClient` exercise
  implements it for real; this stage's stub only proves the conditional
  switches.
- New domain behaviour in `domain`. Every type this stage touches
  (`ReferenceRates`, `FlatRate`) already exists from stage 2.
- Wiring any of this stage's demo beans (`LifecycleLoggingBean`, the
  `proxyBeanMethods` pair, the circular-dependency pair,
  `CounterpartyRequestContext`, `OnOperatingSystemCondition`/`DiagnosticsBean`)
  into `SlLedgerApplication`'s real component scan. They exist to be loaded
  one at a time by `ApplicationContextRunner` in tests; the real application
  only ever starts with the market-data and accrual-properties beans.

## Constraints

- `domain` still takes no third-party dependency outside test scope, and gets
  no new dependency at all this stage — it is only consumed, never modified.
- `./mvnw verify` at the repository root stays green throughout, including
  the failsafe-bound `ArchitectureTest` from stage 3 (still vacuous for rules
  1 and 3 — this stage adds no `adapter` or `@Transactional` class).
- All configuration values (rate bounds, precision) are synthetic, chosen for
  the exercise, not drawn from a real desk's parameters.
- Every container-refresh-failure exercise (circular dependency, invalid
  config binding, missing request context) is asserted via the specific
  exception type and message, not just "the context fails to start" — the
  same rigor stage 3 applied to `ArchitectureTest`'s reproduced violation.

## Acceptance criteria

1. `./mvnw -pl bootstrap spring-boot:run` starts `SlLedgerApplication` and
   stays up (manually verified once, evidence in
   `docs/evidence/04-bootstrap-run.txt`, then stopped — no long-running
   process left behind).
2. `LifecycleLoggingBeanTest` asserts the exact callback order via the
   recorder bean; the order is also written out in the decision record.
3. `TimingBeanPostProcessorTest` asserts a `@Timed`-annotated bean's proxy
   logs (or otherwise records, if asserting log output directly is
   impractical) an elapsed duration around a call, and that a
   non-`@Timed` bean in the same context is untouched (identity-equal to
   the unwrapped instance).
4. `ProxyBeanMethodsIT` asserts singleton reuse under `true` and distinct
   instances under `false`, both from nested static config classes.
5. `reproducesConstructorCircularDependency` asserts
   `BeanCurrentlyInCreationException` (possibly wrapped in
   `UnsatisfiedDependencyException` — assert on the cause chain); the
   setter-injection counterpart in the same class asserts successful
   refresh.
6. `CounterpartyScopeTest` asserts the `ObjectProvider` path resolves the
   request-scoped value inside a simulated request and the direct-injection
   counterpart throws `IllegalStateException` (wrapped) outside one.
7. `MarketDataSourceConditionalTest` asserts `mock` is selected when the
   property is unset (default) and when explicitly `mock`, `real` when set
   to `real`, and that `OnOperatingSystemCondition`'s `DiagnosticsBean` is
   present or absent matching the actual `os.name` at test time.
8. `AccrualRulePropertiesTest` asserts a valid binding produces the expected
   `FlatRate`, and that `lowerBound > upperBound` and a negative
   `roundingPrecision` each fail context refresh with the exception chain
   stated above.
9. `ConfigurationPrecedenceTest` proves command-line > env var (simulated) >
   profile YAML > base YAML for at least one property, by resolved value at
   each of the four construction steps.
10. `./mvnw verify` at the repository root is green, including stage 3's
    `ArchitectureTest`.

## Done when

The four `CLAUDE.md` deliverables exist: this spec, the plan, the code with
every criterion above verified, and `docs/decisions/04-ioc-container.md`
carrying the full bean-lifecycle order, the `proxyBeanMethods = false`
failure case, the circular-dependency explanation naming which cache level
each injection style needs, the `ObjectProvider`-vs-scoped-proxy tradeoff,
and why this stage's `BeanPostProcessor` proxy is not yet Spring AOP.

# Stage 04 — IoC Container · Implementation Plan

Spec: [`docs/specs/04-ioc-container.md`](../specs/04-ioc-container.md)

One section per commit, in order. Implement a section, tick its checkbox in
the same commit, then stop for review. `./mvnw verify` at the repository root
after every section — this stage only ever touches `bootstrap`, and the
failsafe-bound `ArchitectureTest` from stage 3 has to stay green throughout.

## Ground rules for every section

- No new `domain` code and no new dependency in `domain`. `domain`'s
  `ReferenceRates` and `FlatRate` are consumed as they already exist.
- No demo bean (lifecycle, `proxyBeanMethods` pair, circular-dependency pair,
  request-scope pair, `OnOperatingSystemCondition`/`DiagnosticsBean`) is
  ever added to `SlLedgerApplication`'s component scan. Anything that must
  not run in the real app stays a nested static class inside the test that
  exercises it, or lives under a test-only package
  (`bootstrap/src/test/java/.../demo`) — never under `main`.
- Every "context fails to start" assertion names the specific exception type
  and, where the real cause is wrapped, unwraps to it. No test that just
  checks `assertThrows(Exception.class, ...)`.

---

## [x] 1 · Dependencies, plugin, and the runnable entry point

Parent `pom.xml`: no new property needed — `spring-boot-starter`,
`spring-boot-starter-validation`, `spring-boot-starter-test`, and
`spring-boot-maven-plugin` are all governed by the already-imported
`spring-boot-dependencies` BOM at `${spring-boot.version}`.

`bootstrap/pom.xml` gains, no `<version>` on any of them:
`org.springframework.boot:spring-boot-starter` (compile),
`org.springframework.boot:spring-boot-starter-validation` (compile),
`org.springframework.boot:spring-boot-starter-test` (test — supersedes the
plain `junit-jupiter` dependency already there, since the starter pulls it
in transitively; remove the now-redundant direct entry). `<build><plugins>`
gains `spring-boot-plugin` bound with no custom execution (default
`repackage` goal stays available for stage 14, not invoked by `verify`
in this stage).

`SlLedgerApplication` (`com.jerome.slledger.bootstrap`,
`@SpringBootApplication`, a `main` calling `SpringApplication.run`) plus a
minimal `application.yml` (`spring.application.name: sl-ledger` only — the
`accrual`/`marketdata` keys arrive in sections 7–9).

**Acceptance** — `./mvnw -pl bootstrap spring-boot:run` starts and logs the
Spring Boot banner and "Started SlLedgerApplication"; stopped manually
(Ctrl-C), console output captured to `docs/evidence/04-bootstrap-run.txt`.
`./mvnw verify` at the repository root is green.

---

## [x] 2 · Bean lifecycle order, creation to destruction

`com.jerome.slledger.bootstrap.demo.lifecycle` (test-only package,
`bootstrap/src/test/java`). Every class appends to one shared
`CallOrderRecorder` bean (a `List<String>` wrapper that logs the full list,
one entry per line, when read):

- `LifecycleLoggingBean` — the observed bean. Constructor, an `@Autowired`
  setter, `BeanNameAware`, `@PostConstruct`, `InitializingBean`, a custom
  init method, `@PreDestroy`, `DisposableBean`, a custom destroy method. The
  custom init/destroy methods are registered through
  `GenericApplicationContext.registerBean` with a customizer
  (`setInitMethodName`/`setDestroyMethodName`), called from
  `withInitializer` — `withBean` only accepts a customizer alongside a
  `Supplier`, which would bypass constructor injection. The equivalent of
  `@Bean(initMethod = ..., destroyMethod = ...)` without a `@Configuration`.
- `RecordingBeanFactoryPostProcessor` — `BeanFactoryPostProcessor`. Runs
  before any bean exists, so it cannot be injected with the recorder; it
  fetches it with `beanFactory.getBean`, which instantiates the recorder
  early. That early instantiation is the phase's main hazard and is noted
  in the decision record, not hidden.
- `RecordingBeanPostProcessor` — `InstantiationAwareBeanPostProcessor`,
  recording its four hooks (before/after instantiation, before/after
  initialization) for `LifecycleLoggingBean` only, ignoring every other
  bean.
- `AllBeansReadyMarker` — `SmartInitializingSingleton`.
- `ContainerPhaseBean` — `SmartLifecycle` (`start`/`stop`) and
  `ApplicationListener<ContextRefreshedEvent>`.

`LifecycleLoggingBeanTest` runs one context and asserts four slices of the
recorded list, one test each, each slice filtered from the full list and
checked with `containsExactly` (relative order and no duplicates):

1. **Bean's own callbacks** — `constructor`, `setter injection`,
   `setBeanName`, `@PostConstruct`, `afterPropertiesSet`, `initMethod`.
   `constructor` first is guaranteed by Java; the setter proves
   `populateBean` sits between instantiation and the Aware callbacks.
2. **Post-processor hooks interleaved** — `postProcessBeanFactory` first,
   then the bean's callbacks with the four BPP hooks between them.
   Hypothesis to verify, not assume: the custom BPP's
   `postProcessBeforeInitialization` runs *after* `@PostConstruct`, because
   `CommonAnnotationBeanPostProcessor` is `PriorityOrdered` and a plain BPP
   is not. If the run disagrees, the test asserts what actually happens and
   the decision record explains it. **Result: falsified** — the plain BPP
   runs first, because `CommonAnnotationBeanPostProcessor` is also a
   `MergedBeanDefinitionPostProcessor` and those are re-registered at the end
   of the chain. Evidence in `docs/evidence/04-bean-lifecycle.txt`.
3. **Container-level phases** — the bean's `postProcessAfterInitialization`,
   then `afterSingletonsInstantiated`, `SmartLifecycle.start`,
   `ContextRefreshedEvent`.
4. **Destruction** — asserted after `run()` returns, since the runner closes
   the context on exit: `SmartLifecycle.stop`, `@PreDestroy`, `destroy`,
   `destroyMethod`.

Out of this section: `ApplicationReadyEvent` (published only by
`SpringApplication`, which `ApplicationContextRunner` doesn't use — named in
the decision record as untested), and `getEarlyBeanReference` (only fires in
a circular reference — section 5).

**Acceptance** — all four tests pass; the logged full list matches them;
`./mvnw verify` green.

---

## [x] 3 · `BeanPostProcessor`: a timing proxy

`com.jerome.slledger.bootstrap.timing` (`main` — this one is real
infrastructure, not a demo, even though nothing in the real app uses it
yet until section 7): `@Timed` (`@Retention(RUNTIME)`, `@Target({TYPE})`),
`TimingBeanPostProcessor` (`@Component`, implements `BeanPostProcessor`) —
in `postProcessAfterInitialization`, if the bean's class is annotated
`@Timed`, wrap it in a JDK `Proxy` over all its interfaces (fails loudly —
`IllegalStateException` naming the bean — if it implements none, since this
stage's only `@Timed` target has one) that logs elapsed nanoseconds via
SLF4J around each method call, otherwise returns the bean unchanged.
Where BPP hooks sit in the lifecycle is section 2's job; this section is only
about what a BPP can do in `postProcessAfterInitialization` — replace the
bean with something else.

Test-only: `@Timed` demo interface `Greeter` + impl `GreeterImpl` (test
package), asserting via `ApplicationContextRunner` that (a) the bean
returned from the context is a JDK proxy (`Proxy.isProxyClass`) whose
invocation still returns the real result, and (b) a second, non-`@Timed`
bean in the same context is identity-equal (`==`) to its own unwrapped
instance — the processor is a no-op for it. Two more, added while writing
it: (c) each call logs `<beanName>.<method> took <n> ns`, captured with
`OutputCaptureExtension` (the spec's acceptance criterion 3); (d) a `@Timed`
bean with no interface fails startup with the `IllegalStateException`
naming the bean — the behaviour described above, otherwise untested.

**Acceptance** — both assertions pass; `./mvnw verify` green.

---

## [x] 4 · `proxyBeanMethods`

`ProxyBeanMethodsIT` (`bootstrap/src/test/java`), nested static classes:
`TrueConfig` (`@Configuration`, default `proxyBeanMethods = true`) and
`FalseConfig` (`@Configuration(proxyBeanMethods = false)`), each with
`@Bean Widget widget()` (returns `new Widget()`, a trivial record) and
`@Bean Holder holder()` (returns `new Holder(widget())` — calling the
`@Bean` method directly, the pattern under test). Two
`ApplicationContextRunner` cases: `withUserConfiguration(TrueConfig.class)`
asserts `getBean(Holder.class).widget() == getBean(Widget.class)`;
`withUserConfiguration(FalseConfig.class)` asserts they are **not** the same
instance (a fresh `new Widget()`, since `widget()` is now called as a plain
Java method, not intercepted by the CGLIB proxy). Each case also asserts the
config bean's own class — a `$$SpringCGLIB$$` subclass under `true`, the
plain class under `false` — so the test shows the mechanism, not just the
outcome. Framework behaviour, so no red phase: the test was checked by
flipping `TrueConfig` to `false` and watching it fail, then reverted.

**Acceptance** — both assertions pass; `./mvnw verify` green.

---

## [x] 5 · Circular dependency

`CircularDependencyIT`, nested static classes: `Alpha`/`Beta`
(`@Component`, constructor-inject each other) and `AlphaSetter`/`BetaSetter`
(`@Component`, no-arg constructor, setter-inject each other, setters
annotated `@Autowired`).

Found while writing it: `ApplicationContextRunner` does not use Spring
Framework's default (cycles allowed). It mirrors Spring Boot's — cycles off —
unless `withAllowCircularReferences(true)` is called. So the two runner cases
explicitly allow cycles, and a third case shows Boot's default for real.

`reproducesConstructorCircularDependency` — runner with cycles **allowed**,
`.withUserConfiguration(Alpha.class, Beta.class)`, asserts the context
failed with root cause `BeanCurrentlyInCreationException`. Allowing cycles is
what makes this assertion mean something: the constructor cycle fails even
when the container is permitted to break cycles. Named per the
wrong-behaviour convention in `CLAUDE.md` — the "wrong" behaviour is the
failure itself, which is Spring's correct, intentional refusal.

`setterCycleResolvesWhenCyclesAllowed` — same runner, the setter pair,
asserts the context starts and each bean holds the other (`isSameAs`).

`reproducesSetterCycleRejectedBySpringBootDefault` — the same setter pair
through `SpringApplicationBuilder` (no web, no banner), asserts startup
throws with root cause `BeanCurrentlyInCreationException`. The
FailureAnalyzer's console report is kept in
`docs/evidence/04-circular-dependency.txt`.

**Acceptance** — both assertions pass; `./mvnw verify` green.

---

## [x] 6 · Scope: request-scoped context into a singleton

Two things found while writing it. `spring-web` and the servlet API were not
on the classpath (the spec assumed `spring-boot-starter-test` brought them);
`bootstrap` gains both at **test** scope, versions from the BOM. And
`@RequestScope` already carries a scoped proxy (`proxyMode = TARGET_CLASS`),
so direct injection with it never fails — the failing case needs plain
`@Scope("request")`.

`com.jerome.slledger.bootstrap.demo.scope` (test-only):

- `CounterpartyRequestContext` — `@Scope("request")`, no proxy, mutable
  `counterpartyId`.
- `ProxiedCounterpartyRequestContext` — the same, with `@RequestScope`.
- `CurrentCounterpartyHolder` — constructor takes
  `ObjectProvider<CounterpartyRequestContext>`, `currentId()` calls
  `getObject()` each time.
- `DirectlyInjectedHolder` — constructor-injects `CounterpartyRequestContext`.
- `ProxyInjectedHolder` — constructor-injects
  `ProxiedCounterpartyRequestContext`.

`CounterpartyScopeTest` builds an `AnnotationConfigApplicationContext` with
`registerScope("request", new RequestScope())` by hand (no servlet container)
and simulates each request with `ServletRequestAttributes(new
MockHttpServletRequest())` on `RequestContextHolder`, reset in `finally`:

1. `objectProviderResolvesPerRequest` — a value set in one request is read
   back in it; a second request sees a fresh bean (`null`).
2. `reproducesDirectInjectionOutsideRequest` — refresh throws
   `BeanCreationException`, root cause `IllegalStateException: No
   thread-bound request found`: the singleton is built when no request
   exists.
3. `scopedProxyResolvesPerCall` — the injected field is a CGLIB proxy; per
   request behaviour matches case 1; called outside a request it throws
   `ScopeNotActiveException`, root cause the same `IllegalStateException`.
   The failure moves from startup to call time.

**Acceptance** — all three pass; `./mvnw verify` green.

---

## [x] 7 · Conditional wiring: market-data source

`com.jerome.slledger.bootstrap.marketdata` (`main`):
`MockReferenceRates` and `RealReferenceRates`, both `implements
domain.accrual.ReferenceRates`, both `@Component` `@Timed`.
`MockReferenceRates`: `@ConditionalOnProperty(name = "marketdata.source",
havingValue = "mock", matchIfMissing = true)`, returns fixed synthetic rates.
`RealReferenceRates`: `@ConditionalOnProperty(name = "marketdata.source",
havingValue = "real")`, every method throws
`new UnsupportedOperationException("real market data source not implemented until stage 7")`.

`OnOperatingSystemCondition` (`implements Condition`, checks
`condition.getSystemEnvironment()`/`System.getProperty("os.name")` for a
fixed family, e.g. `"Linux"`) gates `DiagnosticsBean` (`@Component`
`@Conditional(OnOperatingSystemCondition.class)`, no behaviour beyond
existing).

`MarketDataSourceConditionalTest` (`ApplicationContextRunner`, scanning the
`marketdata` package): default (no property) and explicit
`marketdata.source=mock` both select `MockReferenceRates`; `marketdata.source=real`
selects `RealReferenceRates`, and calling any of its methods throws the
stated `UnsupportedOperationException`. `DiagnosticsBean`'s presence is
asserted against the actual `System.getProperty("os.name")` read at test
time (`context.containsBean("diagnosticsBean")` equals whether the running
JVM's OS matches), not hardcoded to the CI runner's expected family — this
keeps the test honest if it ever runs on a different OS.

Settled while writing it: `OnOperatingSystemCondition` and `DiagnosticsBean`
are demo beans, so per the ground rules they live in the test-only
`demo.conditional` package, not `marketdata`; the test registers
`DiagnosticsBean` on its own runner. The condition reads `os.name` through
the `Environment` rather than `System.getProperty`, which is what makes a
second case possible: `withSystemProperties("os.name=Windows 11")` turns the
bean off, so the condition is shown to decide, not just to agree with this
machine. A fifth case asserts the selected source is the `@Timed` JDK proxy —
the spec's "the two sections compose in one context".

**Acceptance** — all assertions pass; `./mvnw verify` green.

---

## [ ] 8 · Configuration binding: accrual rule bounds

`com.jerome.slledger.bootstrap.config` (`main`): `AccrualRuleProperties`
(`@ConfigurationProperties(prefix = "accrual")`, `@Validated`) with
`BigDecimal rateLowerBound`, `BigDecimal rateUpperBound` (no per-field
`@DecimalMin`/`@DecimalMax` — there's no fixed absolute bound, only the
pair's relative ordering), `int roundingPrecision` (`@Min(0)` `@Max(10)`),
and an `@AssertTrue` method `isBoundsOrdered()` returning
`rateLowerBound.compareTo(rateUpperBound) < 0`. `AccrualRuleConfig`
(`@Configuration`, `@EnableConfigurationProperties(AccrualRuleProperties.class)`,
`@Bean FlatRate defaultAccrualRate(AccrualRuleProperties props)` constructing
a `domain.accrual.FlatRate` from `props.rateLowerBound()` — the
mid-point or lower bound, stated in a one-line comment why that one, not
both, since `FlatRate` takes a single rate).

`AccrualRulePropertiesTest` (`ApplicationContextRunner` with
`.withPropertyValues(...)`): valid bounds produce the expected `FlatRate`
value; `rateLowerBound > rateUpperBound` and `roundingPrecision = -1` each
fail refresh, asserted down to `ConstraintViolationException` (via
`ConfigurationPropertiesBindException.getCause()` — check the actual
Spring Boot 4 exception chain while writing this test and record the real
type in the test's comment if it differs from this plan's guess).

**Acceptance** — all assertions pass; `./mvnw verify` green.

---

## [ ] 9 · Profiles and precedence

`bootstrap/src/main/resources/application.yml` gains base
`accrual.rate-lower-bound`, `accrual.rate-upper-bound`,
`accrual.rounding-precision`, `marketdata.source: mock` (safe defaults).
Three new files: `application-local.yml` (mock source, narrower bounds),
`application-test.yml` (used only by section-9's own test), `application-prod.yml`
(`marketdata.source: real`, production-width bounds).

`ConfigurationPrecedenceTest`: four `SpringApplicationBuilder` runs against
`SlLedgerApplication`, `webApplicationType(NONE)`, each started and closed
in the test:
1. no profile — resolves `accrual.rounding-precision` to the base value.
2. `--spring.profiles.active=local` — resolves to `local`'s value.
3. same, plus a simulated environment-variable override: an
   `EnvironmentPostProcessor`-free approach — add a `MapPropertySource`
   named `"test-env-simulation"` directly to
   `builder.application().addInitializers(ctx -> ctx.getEnvironment()
   .getPropertySources().addFirst(...))` — resolves to the simulated
   env value, proving it outranks the profile YAML.
4. same, plus `--accrual.rounding-precision=<other value>` as a program
   argument — resolves to the command-line value, outranking all three.

Each run's resolved value is asserted via
`context.getEnvironment().getProperty("accrual.rounding-precision")`, not
by re-reading YAML files — proving resolution, not configuration.

**Acceptance** — all four assertions pass with four distinct values (base <
local < simulated-env < command-line, chosen as distinct numbers precisely so
a wrong precedence produces a wrong number instead of an accidental pass);
`./mvnw verify` green.

---

## [ ] 10 · Decision record

`docs/decisions/04-ioc-container.md`: the bean lifecycle order from section 2
written out as prose (not just "see the test"); the `proxyBeanMethods = false`
failure case and when it bites in practice; the circular-dependency
explanation naming which of the three caches each injection style needs and
why constructor injection can't use the third-level cache to escape;
`ObjectProvider` vs. a scoped proxy (`@Scope(proxyMode = TARGET_CLASS)`) as
the two ways to inject a narrower-scoped bean into a singleton, and why this
stage picked `ObjectProvider` for the demo (explicit at the call site, no
CGLIB subclass surprise) while noting a scoped proxy is the more common
production choice for read-heavy access; why `TimingBeanPostProcessor` is
not Spring AOP, and where it stops scaling; the market-data conditional
wiring and config-binding sections as the "hooked into the container" part
the roadmap actually asked for, given stage 2 left no use-case layer to hook
in more directly.

**Acceptance** — decision record reviewed against every bullet above;
`./mvnw verify` green at the repository root one final time.

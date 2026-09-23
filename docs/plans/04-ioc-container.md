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

## [ ] 2 · Bean lifecycle order, creation to destruction

`com.jerome.slledger.bootstrap.demo.lifecycle` (test-only package,
`bootstrap/src/test/java`). Every class appends to one shared
`CallOrderRecorder` bean (a `List<String>` wrapper that logs the full list,
one entry per line, when read):

- `LifecycleLoggingBean` — the observed bean. Constructor, an `@Autowired`
  setter, `BeanNameAware`, `@PostConstruct`, `InitializingBean`, a custom
  init method, `@PreDestroy`, `DisposableBean`, a custom destroy method. The
  custom init/destroy methods are registered through the
  `ApplicationContextRunner.withBean` customizer
  (`setInitMethodName`/`setDestroyMethodName`) — the equivalent of
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
   the decision record explains it.
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

## [ ] 3 · `BeanPostProcessor`: a timing proxy

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
instance — the processor is a no-op for it.

**Acceptance** — both assertions pass; `./mvnw verify` green.

---

## [ ] 4 · `proxyBeanMethods`

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
Java method, not intercepted by the CGLIB proxy).

**Acceptance** — both assertions pass; `./mvnw verify` green.

---

## [ ] 5 · Circular dependency

`CircularDependencyIT`, nested static classes: `Alpha`/`Beta`
(`@Component`, constructor-inject each other) and `AlphaSetter`/`BetaSetter`
(`@Component`, no-arg constructor, setter-inject each other, setters
annotated `@Autowired`).

`reproducesConstructorCircularDependency` — `ApplicationContextRunner`
`.withUserConfiguration(Alpha.class, Beta.class)`, asserts
`.run(context -> assertThat(context).hasFailed())` then unwraps
`context.getStartupFailure()` down to `BeanCurrentlyInCreationException`
(via `UnsatisfiedDependencyException.getCause()`), per the wrong-behaviour
naming convention in `CLAUDE.md` — the "wrong" behaviour here is the
failure itself, which is Spring's correct, intentional refusal.

`AlphaSetter`/`BetaSetter` case: same runner shape, asserts the context
refreshes successfully and `AlphaSetter`'s injected `BetaSetter` is not
null.

**Acceptance** — both assertions pass; `./mvnw verify` green.

---

## [ ] 6 · Scope: request-scoped context into a singleton

`com.jerome.slledger.bootstrap.demo.scope` (test-only):
`CounterpartyRequestContext` (`@Component`, `@RequestScope`, mutable
`String counterpartyId` field with getter/setter), `CurrentCounterpartyHolder`
(`@Component`, takes `ObjectProvider<CounterpartyRequestContext>` in its
constructor, exposes `currentId()` calling `.getObject().getCounterpartyId()`),
`DirectlyInjectedHolder` (`@Component`, field-injects
`CounterpartyRequestContext` directly, exposes the same `currentId()`).

`CounterpartyScopeTest`: builds an `AnnotationConfigApplicationContext`
(needs the concrete context type, not `ApplicationContextRunner`, since the
test manipulates `RequestContextHolder` around the calls) registering all
three plus a `RequestScope` registration
(`beanFactory.registerScope("request", new RequestScope())` — no servlet
container is running, so the scope has to be registered by hand rather than
relying on `@EnableWebMvc`'s auto-registration). Simulated request via
`ServletRequestAttributes(new MockHttpServletRequest())` set on
`RequestContextHolder` in `try`, cleared in `finally`:
`CurrentCounterpartyHolder.currentId()` returns the value set on the
request-scoped bean fetched from the same context inside the block.
Outside any `try` block (no request bound), `DirectlyInjectedHolder`'s
`getBean` call — the field injection happens at context refresh time — is
asserted to throw, unwrapping to
`IllegalStateException: No thread-bound request found`.

**Acceptance** — both assertions pass; `./mvnw verify` green.

---

## [ ] 7 · Conditional wiring: market-data source

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

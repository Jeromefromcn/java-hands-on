# Stage 04 — IoC Container — Decision Record

Spec: [`docs/specs/04-ioc-container.md`](../specs/04-ioc-container.md)
Plan: [`docs/plans/04-ioc-container.md`](../plans/04-ioc-container.md)

Measured on Spring Boot 4.1.1 / Spring Framework 7.0.9, JDK 21.0.12.

## What I chose

**The container hooks onto what stage 2 left, not onto use cases that don't
exist yet.** Stage 2 built value types and strategies, no application-layer
use case. So the real application wires exactly two things: a
`ReferenceRates` source chosen by property (`MockReferenceRates` /
`RealReferenceRates`), and a `FlatRate` built from validated
`AccrualRuleProperties`. Everything else — lifecycle, `proxyBeanMethods`,
cycles, scopes, the OS condition — is container mechanics with no honest
place in the domain, so it lives in test-only classes the real component
scan never sees.

**The bean lifecycle, in the order this stage measured it** (full list in
[`04-bean-lifecycle.txt`](../evidence/04-bean-lifecycle.txt)):

1. `BeanFactoryPostProcessor.postProcessBeanFactory` — definitions exist, no
   bean does. Calling `getBean` here (as the recorder demo must) instantiates
   that bean early, before any `BeanPostProcessor` is registered, so it
   silently misses every BPP — the phase's main hazard.
2. `postProcessBeforeInstantiation` → constructor (constructor injection
   happens here) → `postProcessAfterInstantiation`.
3. Property population: setter and field injection.
4. Aware callbacks: `setBeanName` (then factory/context aware).
5. `postProcessBeforeInitialization` — a plain BPP runs **before**
   `@PostConstruct`. The plan predicted the opposite; it was falsified.
   `@PostConstruct` is itself a BPP (`CommonAnnotationBeanPostProcessor`),
   and although it is `PriorityOrdered`, it is also a
   `MergedBeanDefinitionPostProcessor`, which Spring re-registers at the end
   of the chain.
6. `@PostConstruct` → `afterPropertiesSet` → custom `initMethod`.
7. `postProcessAfterInitialization` — where a BPP may hand back a different
   object (section 3's proxy). What the context stores from here on is the
   replacement.
8. After all singletons: `afterSingletonsInstantiated` →
   `SmartLifecycle.start` → `ContextRefreshedEvent`.
9. Close: `SmartLifecycle.stop` → `@PreDestroy` → `destroy` →
   `destroyMethod`.

`ApplicationReadyEvent` is not in the list: only `SpringApplication`
publishes it, and `ApplicationContextRunner` isn't one. Untested, not assumed.

**A hand-rolled JDK-proxy `BeanPostProcessor` for timing, not Spring AOP.**
`TimingBeanPostProcessor` replaces each `@Timed` bean with a
`java.lang.reflect.Proxy` in `postProcessAfterInitialization`. That is the
same place, and the same trick, Spring's own auto-proxy creator uses for
`@Transactional`. Writing it by hand first makes stage 5's proxy something
already seen from the inside. It fails startup, naming the bean, when a
`@Timed` class has no interface — a JDK proxy can only stand in for
interfaces, and silently skipping would make `@Timed` a no-op nobody notices.
Section 7 put it on the real `ReferenceRates` beans, so it is exercised by
the application and not only by its own test.

**`proxyBeanMethods = true` is the default because `@Bean` methods call each
other.** Under `true` the configuration class is a `$$SpringCGLIB$$`
subclass; `holder()` calling `widget()` is intercepted and gets the
container's singleton. Under `false` it is the plain class, `widget()` is an
ordinary Java call, and `Holder` receives a second `Widget` the container
never manages — no lifecycle callbacks, no BPP, no proxy. `false` is safe
only when no `@Bean` method calls another one for wiring. The fix under
`false` is to take the dependency as a `@Bean` method parameter, which
`AccrualRuleConfig` does (`defaultAccrualRate(AccrualRuleProperties props)`)
and which is why it can declare `proxyBeanMethods = false`.

**A narrower-scoped bean reaches a singleton through `ObjectProvider` in the
demo, while noting a scoped proxy is the more common production choice.**
Directly injecting a plain `@Scope("request")` bean into a singleton fails
at startup: `BeanCreationException`, root cause `IllegalStateException: No
thread-bound request found`. The singleton is built when no request exists.
Two ways out, both tested:

- `ObjectProvider<T>` + `getObject()` per call. The lookup is visible at the
  call site: a reader sees that every call asks for "the current one".
- A scoped proxy (`@RequestScope` already carries
  `proxyMode = TARGET_CLASS`). The injected field is a CGLIB proxy that
  resolves the target on every method call. Call sites read like an ordinary
  field. Outside a request, the failure moves from startup to the call:
  `ScopeNotActiveException`, same root cause.

The demo leads with `ObjectProvider` because the lookup is explicit. A
scoped proxy is what read-heavy production code usually uses, because it
keeps call sites clean — at the cost of a CGLIB subclass the reader can't
see, and a failure that only appears at call time.

**Property switches select the market-data source; one `Condition` is
written by hand.** `@ConditionalOnProperty` with `matchIfMissing = true` on
the mock makes it the default. The real source is a stub that throws
`UnsupportedOperationException` until stage 7. `OnOperatingSystemCondition`
has no business meaning, and this record won't pretend it does: it exists
to show a `Condition` written rather than consumed. It reads `os.name`
through the `Environment`, not `System.getProperty`. That is what lets a
test flip it (`os.name=Windows 11`) and show the condition decides, rather
than merely agreeing with this machine.

**Configuration is validated at startup, and the cross-field rule is a
method.** `AccrualRuleProperties` is a record with `@Validated`. Bean
Validation has no "field A < field B" annotation, so ordering is an
`@AssertTrue boolean isBoundsOrdered()`. The bounds are `@NotNull`, so a
missing one reports as missing instead of an NPE inside the ordering check.
`FlatRate` takes the lower bound, because it is a value someone configured;
a mid-point would need a rounding decision nobody has made.
`roundingPrecision` is bound and validated but not yet consumed — `FlatRate`
rounds with `domain`'s own `MathContext`.

## What I rejected, and why

**Demo beans in `main`.** Every lifecycle, cycle, scope and condition demo
would be on the real application's component scan the day someone widened
it. They live in test sources; the real app starts with market data, accrual
properties and the timing BPP only.

**The plan's guess at the validation exception.** It expected
`ConstraintViolationException`. Spring Boot never throws it here: the chain
is `BeanCreationException` → `ConfigurationPropertiesBindException` →
`BindException` → `BindValidationException`. Boot validates through its own
`ValidationBindHandler` and reports violations as `ValidationErrors`. The
test asserts the measured chain.

**The plan's environment-variable simulation.** It proposed `addFirst` of a
`MapPropertySource`. That ranks above `commandLineArgs`, so the "env var"
beats the command line — the opposite of the real order — and the test
would have asserted a falsehood. Checked by mutation
([`04-config-precedence.txt`](../evidence/04-config-precedence.txt):
`expected: 8 but was: 6`). The committed test replaces the
`systemEnvironment` source in place with the real environment plus
`ACCRUAL_ROUNDINGPRECISION=6`: same name, same rank.

**Allowing circular references to make a setter cycle work.** It works (see
below), and Spring Boot turns it off by default since 2.6 for good reason. A
cycle means neither bean can be understood alone. The bean handed out early
is incomplete, so anything it does before its own initialisation finishes
sees a half-built object. And a BPP that replaces the bean after
initialisation (like `TimingBeanPostProcessor`, which does not implement
`getEarlyBeanReference`) leaves the other bean holding the raw object while
the context holds the proxy. Spring detects that case and fails; this stage
did not reproduce it.

## What I observed

**Circular dependencies and the three caches**
([`04-circular-dependency.txt`](../evidence/04-circular-dependency.txt)).
`DefaultSingletonBeanRegistry` in 7.0.9 still holds `singletonObjects`
(complete beans), `earlySingletonObjects` (early references already handed
out) and `singletonFactories` (an `ObjectFactory` per bean in creation),
confirmed with `javap`.

- *Setter cycle:* A is constructed, and a factory for it goes into
  `singletonFactories`. Populating A needs B; populating B needs A; the
  lookup finds A's factory, calls it (this is where
  `getEarlyBeanReference` lets a BPP proxy early), and moves the result to
  `earlySingletonObjects`. B completes, then A. It needs the third cache.
  The second alone would force every bean's proxy to be created eagerly,
  cycle or not.
- *Constructor cycle:* A's constructor needs B before A exists. The factory
  is registered only after instantiation, so when B asks for A there is
  nothing in any cache — only a "currently in creation" mark.
  `BeanCurrentlyInCreationException`, even with circular references allowed.
  The third cache can't help: there is no object yet to reference.
- `ApplicationContextRunner` follows Boot's policy (cycles off), not
  Framework's. The runner cases call `withAllowCircularReferences(true)`
  explicitly; a third case shows Boot's default rejecting the setter cycle.

**Property precedence, by resolved value.** `accrual.rounding-precision`
set to distinct values per layer, read back from the started application:
base YAML 2 → `local` profile 4 → environment variable 6 → command line 8.
The bound `AccrualRuleProperties` agreed each time. Starting `local`, `test`
and `prod` wired `mockReferenceRates`, `mockReferenceRates` and
`realReferenceRates` respectively.

**Timing proxy output**, one call each in the `verify` run: 9,321 ns for
`mockReferenceRates.on`, 211,400 ns for `realReferenceRates.on` (it throws;
the figure includes exception construction), 251,921 ns for the first
`greeter.greet` and 18,680 ns for a later one. Single, cold, logged calls —
they show the proxy works, not what it costs. Proxy overhead was not
measured.

**Startup:** `Started SlLedgerApplication in 1.631 seconds` (section 1's
run, [`04-bootstrap-run.txt`](../evidence/04-bootstrap-run.txt)).

## Where it breaks at 100x

- **`TimingBeanPostProcessor`** logs one INFO line per call. At 100x the call
  rate that is 100x the log volume from a single bean, and the logging, not
  the reflection, is the likely cost — unmeasured. More fundamentally it
  doesn't scale in *kinds*: no pointcut language (only "the whole class"),
  no ordering between two processors wrapping the same bean, interface-only,
  and each new cross-cutting concern needs its own processor. That is the
  job `@Aspect` and the auto-proxy creator do, and why stage 5 moves to them.
- **`ObjectProvider` lookups** cost a scope lookup per call. Fine per
  request; in a hot loop inside a request it's a lookup per iteration, so
  resolve it once per request.
- **Profile-per-environment YAML** stays readable at three profiles. At
  100x the environments (per-desk, per-region) it becomes a merge nobody can
  predict. The answer then is fewer profiles and externalised config — the
  precedence test here is the check that stays valid either way.
- **Startup-time validation** is the right place for config errors at any
  scale. What breaks at 100x is the number of properties classes: a missing
  `@Validated` is silent, and nothing here enforces its presence. An
  ArchUnit rule ("every `@ConfigurationProperties` class is `@Validated`")
  would.

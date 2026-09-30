# Stage 09 — Caching · Implementation Plan

Spec: [`docs/specs/09-caching.md`](../specs/09-caching.md)

One section per commit, in order. Implement a section, tick its checkbox in
the same commit, then commit. `./mvnw clean verify` at the repository root
after every section that touches the main build.

## Ground rules for every section

- Packages:
  - `com.jerome.slledger.application.cache` for the generic read-through,
    the tier port, `TtlPolicy`, `DistributedLock`, `WriteFence`;
  - `com.jerome.slledger.application.quote` for the two decorators'
    collaborators only where they already live; the decorators themselves
    are in `application.cache`;
  - `com.jerome.slledger.adapter.persistence.cache` for Caffeine, Redis,
    the codec, the invalidation bus, `RedisLock` and `JdbcWriteFence`;
  - `com.jerome.slledger.bootstrap.config` for `ledger.cache.*`.
- Pure unit tests end in `Test`. Anything that needs Docker ends in `IT` and
  runs under failsafe. Redis comes from `SharedRedis`, one container per test
  JVM, the same shape as `SharedPostgres`. A test that shares Redis uses
  keys with its own prefix and finishes by flushing them, and the suite
  asserts no key without a TTL survives.
- Time is a `Ticker` the test drives. No `Thread.sleep`. Every test that can
  block has `@Timeout(30)` and releases its latches in `finally`.
- A test asserting wrong behaviour is named `reproduces<Failure>` and carries
  `// See docs/decisions/09-caching.md`.
- Evidence is written by tests to `<module>/target/evidence/09-*.txt` and
  copied by hand to `docs/evidence/`. Lab evidence is written to the lab's
  `target/` and copied the same way. A number that is deterministic by
  construction says so in its file.
- The main build runs on the JDK `.sdkmanrc` pins (21).
- Test data is synthetic. ISINs sent over HTTP carry a valid check digit.

## Decisions made while planning

The spec left these open. Each is flagged in its section and carried to the
decision record.

1. **`Entry` is a sealed type, `Present<V>` or `Absent`.** Nothing stores or
   returns `null`; a miss in a tier is `Optional.empty()`, a known miss is
   `Absent`. The two cannot be confused.
2. **`CacheTier` takes a TTL on every `put`.** Caffeine's per-entry `Expiry`
   and Redis's `PX` both support it, and the jitter needs it.
3. **A load that throws stores nothing and is not single-flighted past its
   own failure.** The waiters of a failed load each retry as their own
   load; otherwise one failure would be served to a crowd.
4. **Single-flight uses a `ConcurrentHashMap` of in-flight futures, not
   Caffeine's `get(key, loader)`.** `ReadThrough` lives in `application`,
   which takes no Caffeine dependency; the map costs twenty lines.
5. **The cross-instance lock is optional in `ReadThrough`.** A rebuild that
   cannot get the lock waits a bounded number of polls on L2 and then loads
   anyway. Failing to load because a lock is unavailable would make Redis a
   hard dependency of a read; it stays an optimisation.
6. **`WriteFence` is one table and one conditional upsert.** `write_fence
   (resource, token)`; `admit` runs
   `INSERT ... ON CONFLICT (resource) DO UPDATE SET token = EXCLUDED.token
   WHERE write_fence.token <= EXCLUDED.token RETURNING token` and treats "no
   row returned" as refused. Equal tokens are admitted: one holder makes
   several writes under one token.
7. **The lab sends `SIGSTOP` with `kill -STOP <pid>`** from the parent JVM. It
   is Linux-specific and the lab README says so.

## Review focus

Failure modes the spec implies but no exercise is *about*. Each is pinned by a
test in the section named.

1. **A cached value's currency differs from the security's.** The decorator
   sits below `QuotePrice`, so the existing currency check still runs on a
   cached price (section 6).
2. **An undecodable Redis value.** Treated as a miss and reloaded, never
   returned and never allowed to break the read (section 5).
3. **Redis down.** A tier that throws must not fail a read: the chain falls
   through to the load. The failure is counted (section 2).
4. **The jitter never yields a non-positive TTL** at a fraction near 1
   (section 2).
5. **Evict racing a load.** An evict that arrives while a load is in flight
   must not be overwritten by the in-flight result (section 2).
6. **A lock released twice, or by a lease whose TTL already passed.** A
   second `release` is a no-op and never deletes another holder's key
   (section 7).

---

## [x] 1 · Build wiring and Redis test support

**POMs**
- `adapter-persistence`: compile `com.github.ben-manes.caffeine:caffeine`,
  `spring-boot-starter-data-redis`, `spring-boot-starter-json`; the last was
  test-scope and is promoted. No `<version>`.
- `bootstrap`: test gains `spring-boot-starter-cache` (Spring Cache demo).
- `dependencyConvergence` conflicts, if any, resolved by managed entries with
  a one-line reason each. Resolutions go to
  `docs/evidence/09-dependency-convergence.txt`.

**Test support (`adapter-persistence` test-jar)**
- `SharedRedis`: one `redis:7.4-alpine` container per JVM, started on first
  use; `host()`, `port()`, `flushAll()`; the same shape as `SharedPostgres`.
- `Evidence` already exists.

**Tests**
- `SharedRedisIT`: two clients see one server; `flushAll` empties it.

**Acceptance**
- `./mvnw clean verify` is green; `application` still has no third-party
  dependency (enforcer).

---

## [x] 2 · The read-through core

**`application.cache`**
- `Entry<V>` sealed (`Present<V>`, `Absent`); `CacheTier<K,V>` (`get`, `put`
  with TTL, `evict`); `TtlPolicy(base, jitterFraction, negative)` with
  `ttlFor(Random)` and `negativeTtl()`; `ReadThrough<K,V>` over a list of tiers,
  a loader `Function<K, Optional<V>>`, a `TtlPolicy`, an optional
  `DistributedLock`, a `Random` and a counters object.
- Chain: tiers in order; a hit in tier *n* is promoted into tiers before it;
  a load is single-flight per key (planning decision 4); `Optional.empty()`
  from the loader is stored as `Absent` with the negative TTL; a throwing
  load stores nothing (planning decision 3); a throwing tier is counted and
  skipped (review focus 3).
- `evict(key)` evicts all tiers and marks any in-flight load for that key as
  stale, so its result is returned to its callers and not stored (review
  focus 5).

**Tests (`application`, fake in-memory tiers, fake clock)**
- `secondReadIsAHit`, `l2HitIsPromotedToL1`, `unknownIsCachedAsAbsent`,
  `loadThatThrowsStoresNothing`, `throwingTierIsSkippedAndCounted`,
  `evictDuringLoadIsNotOverwritten`, `jitterStaysWithinFractionAndPositive`,
  `sameSeedGivesSameTtls`.
- `singleFlightLoadsOncePerKeyInOneJvm` (breakdown, in one JVM): 50 threads,
  one barrier, one key, loader counts calls, asserts 1. The same 50 threads
  without single-flight (a test-side naive reader) call the loader 50 times:
  `reproducesStampedeWithoutSingleFlight`.

**Acceptance**
- Tests pass; `./mvnw clean verify` is green.

**Found while implementing**
- **The TTL policy is per tier, not per cache.** `ReadThrough.Layer` pairs a tier
  with its own `TtlPolicy`: L1 lives 5 s and L2 30 s, and a promotion into L1 uses
  L1's policy, so a value copied out of L2 does not outlive L1's own bound.
- **`DistributedLock`, `WriteFence` and `StaleFencingTokenException` are here already**
  (planned for sections 7 and 8), because `ReadThrough.Rebuild` needs the lock port.
  `WriteFence.admit` returns nothing and throws, so an enclosing transaction rolls back
  on a refusal without the caller having to check.
- **The evict-during-load guard takes a monitor, not a flag alone.** Marking the flight
  stale and storing its result are both under the flight's monitor, so an evict cannot
  slip between the stale check and the `put`.
- The waiter tests find "everyone is parked" by polling thread states after each thread
  has counted itself in, not by sleeping.

---

## [x] 3 · Caffeine tier: bounds, expiry, avalanche

**`adapter.persistence.cache`**
- `CaffeineTier<K,V>`: `maximumSize`, per-entry `Expiry` from the `put` TTL,
  an injectable `Ticker`, `recordStats`.

**Tests (`adapter-persistence`)**
- `reproducesUnboundedGrowth`: a test-side `Caffeine.newBuilder().build()`
  with no bound holds every one of 100,000 distinct keys; `CaffeineTier` with
  `maximumSize=1,000` holds at most 1,000 after `cleanUp()`. Heap in use
  before and after is recorded; the file notes stage 15 will reuse this. The
  unbounded builder is in the test only.
- `entryExpiresAtItsOwnTtl`: two entries with different TTLs on the ticker.
- `reproducesAvalancheWithFixedTtl`: 1,000 keys stored at one tick with the
  same TTL; advancing the ticker past it and reading every key through
  `ReadThrough` gives 1,000 loads in one bucket. With a 20% jitter, the same
  1,000 keys' loads spread over the jitter window; the maximum bucket is
  asserted below a bound and the two histograms go to
  `09-avalanche.txt`. The file states the numbers are deterministic by
  construction (controlled ticker).

**Acceptance**
- Tests pass; `./mvnw clean verify` is green.

**Found while implementing**
- **Removal work runs on the calling thread** (`executor(Runnable::run)`), so a test's
  `size()` after `cleanUp()` is exact and the avalanche steps are reproducible.
- The avalanche uses a 1 s TTL over 3 s of controlled time (60 steps of 50 ms), not the
  30 s L2 default: the mechanism is the same and the file stays a page long.
- The tests are in `CaffeineTierTest`, a surefire test: no Docker.

---

## [x] 4 · Spring Cache abstraction

**`bootstrap` test sources only**
- `@EnableCaching` test configuration with a `CaffeineCacheManager`;
  `PriceService` bean with `@Cacheable`, `@CachePut`, `@CacheEvict`, over a
  counting source.

**Tests**
- `cacheableCallsSourceOncePerKey`, `cachePutRefreshesWithoutReading`,
  `cacheEvictForcesReload`, `nullResultIsCachedOrNotByConfiguration` (the
  default refuses `null` by `unless`, and the test records what happens
  without it: a `null` is cached).
- `reproducesSelfInvocationBypassesCache`: a method of the same class that
  calls the `@Cacheable` method on `this` calls the source every time; the
  same call through another bean (or the injected proxy) calls it once.

**Acceptance**
- Tests pass; nothing here is wired into the product path.

**Found while implementing**
- **The demo uses `ConcurrentMapCacheManager`, not Caffeine.** The exercise is about the
  proxy; the engine underneath is irrelevant to it and section 3 already covers Caffeine.
- The tests are a surefire `SpringCacheTest` on an `AnnotationConfigApplicationContext`,
  with no server and no database. The bean is a CGLIB proxy (asserted from its class name).
- `unless = "#result == null"` is what stops an unknown key being cached; without it the
  manager's default caches the `null`. Both are asserted, and section 6's negative entry
  makes the same choice deliberately and with a short TTL.

---

## [x] 5 · Redis tier and serialization

**`adapter.persistence.cache`**
- `PriceCodec`: encode/decode `Entry<Money>` as JSON per the spec. Decode
  refuses an over-precise amount and an unknown currency.
- `RedisTier`: `StringRedisTemplate`, key prefix from configuration, `PX`
  from the `put` TTL, undecodable value is a miss (review focus 2).

**Tests (`adapter-persistence`, `SharedRedis`)**
- `moneyRoundTripsThroughRedis` for `HKD`, `JPY`, `BHD` at 16 integer
  digits, value and scale and currency equal, `absent` too.
- `undecodableValueIsAMiss`, `overPreciseStoredAmountIsRefused`,
  `everyKeyHasATtl`.
- `reproducesJdkSerializationRefusesMoney`: `ObjectOutputStream` on a `Money`
  throws `NotSerializableException`; a `JdkSerializationRedisSerializer` fails
  the same.
- `reproducesPrecisionLossWithDoubleCodec`: a test-side codec that writes the
  amount as a JSON number and reads it as a double changes a 17-digit
  amount. Evidence file `09-redis-roundtrip.txt` holds both.

**Acceptance**
- Tests pass; `./mvnw clean verify` is green.

**Found while implementing**
- **The TTL check covers the `price:` prefix only.** Section 7's fencing counters
  (`INCR fence:{resource}`) must never expire: a counter that expired could hand out a
  token lower than one already admitted. "No key without a TTL" is true of the price
  keys and false, on purpose, of the fence keys.
- **`PriceCodec.decodeStrict` throws and `decode` maps it to empty**, so a test can assert
  the refusal and the tier can treat it as a miss with one method each.
- The double-codec reproduction uses a 19-significant-digit amount; the loss is 0.11.
  16 integer digits with two decimals is already beyond a double's 15–17 digits.
- A `Duration` below one millisecond is rounded up to 1 ms in `RedisPriceTier.put`:
  `SET ... PX 0` is an error, and a zero TTL from a jitter draw must not fail a read.

---

## [x] 6 · Decorators and penetration

**`application.cache`**
- `CachingMarketPriceSource implements MarketPriceSource` over a
  `ReadThrough<Isin, Money>`; source `UnknownReferenceException` is the
  loader's `Optional.empty()`, and a cached `Absent` is rethrown as
  `UnknownReferenceException`.
- `CachingSecurityDirectory implements SecurityDirectory` over
  `ReadThrough<Isin, Security>`, L1 only.

**Tests (`application`, fakes)**
- `unknownIsinCostsOneLookup`: 1,000 requests for an unknown ISIN call the
  directory once; the absent entry expires and the next request calls it
  again. `reproducesPenetrationWithoutNegativeCache`: the undecorated
  directory is called 1,000 times.
- `cachedPriceStillFacesTheCurrencyCheck` (review focus 1): `QuotePrice` over
  the decorated source refuses a cached price in another currency.
- `sourceFailureIsNotCached`.

**Acceptance**
- Tests pass; `QuotePrice` and `PriceFanOut` have no diff.

**Found while implementing**
- **The decorators take their layers and `Random` in the constructor**, and the price
  decorator also an optional `Rebuild` (null for none); `QuotePrice` and `PriceFanOut`
  are untouched (`git diff` empty).
- **`MarketDataUnavailableException` and `MarketDataRejectedException` are not cached;
  `UnknownReferenceException` from the source is.** A source's "no such security" is an
  answer, its outage is not.
- The decorators also expose `evict(Isin)`, which section 10's invalidation calls.

---

## [x] 7 · `RedisLock`

**`application.cache`**: `DistributedLock`, `Lease` (`resource`, `token`,
`release`).

**`adapter.persistence.cache` `RedisLock`**
- `SET lock:{resource} {owner} NX PX ttl`; `INCR fence:{resource}` for the
  token, taken after a successful set; a Lua script releases only if the value
  still equals the owner; a second `release` is a no-op (review focus 6).

**Tests (`SharedRedis`)**
- `exclusiveWhileHeld`, `expiresAfterTtl` (asserted on the key's own `PTTL`,
  not on waiting), `tokensIncreaseAcrossAcquisitions`,
  `releaseTwiceIsANoOp`.
- `reproducesUnlockOfSomeoneElsesLock`: a test-side naive `DEL` release,
  after the first holder's TTL passed and a second holder acquired, removes
  the second holder's lock. The Lua release does not.

**Acceptance**
- Tests pass; `./mvnw clean verify` is green.

**Found while implementing**
- **Acquire and `INCR` are one Lua script, not two commands** (the plan had `INCR` "after
  a successful set"). Two round trips let a holder that pauses between them come back with
  a token *higher* than a later holder's, for a lock it no longer has, which defeats the
  fence. The cost is that the lock key and the fence key are touched by one script, so on
  a Redis Cluster they would need a shared hash tag; recorded for the 100x section.
- The unlock reproduction is asserted at the Redis key: after a naive `DEL` the second
  holder's key is gone and a third holder acquires. `leaseThatExpiredDoesNotReleaseTheNextHolder`
  is the same sequence on `RedisLock` and leaves the key in place.
- Expiry is forced with `PEXPIRE key 1` and polled until the key is gone, so no test waits
  out a real TTL.

---

## [x] 8 · Fencing token and the pause

**`adapter-persistence`**
- `V5__write_fence.sql`: `write_fence(resource text primary key, token bigint
  not null)`.
- `JdbcWriteFence implements WriteFence` (planning decision 6).

**Tests (`SharedRedis` and `SharedPostgres`)**
- `admitsIncreasingAndEqualTokens`, `refusesLowerToken`.
- `reproducesDoubleWriteAfterHolderPause`: a test-only table
  `accrual_total(position_id, total)`, two holders. Holder A acquires with a
  short TTL and pauses on a latch; the ticker-free Redis TTL is shortened with
  `PEXPIRE` so the lock is provably gone; holder B acquires and adds 100;
  A resumes and adds 100 with no fence: the total is 200 where one run
  should give 100.
- `fenceRefusesTheStaleHolder`: the same steps with `admit` in the write's
  transaction: A's write is refused and the total is 100.
- `fenceInSameTransactionRollsBackWithTheWrite`: a refused admit leaves no
  partial write; an accepted admit and a failing write leave the fence
  token unchanged.
- Evidence `09-fencing.txt` holds the two totals and the token sequence.

**Acceptance**
- Tests pass; `./mvnw clean verify` is green (Flyway validates V5).

**Found while implementing**
- **`JdbcWriteFence` checks for a transaction by hand instead of `@Transactional(MANDATORY)`.**
  `ArchitectureIT` keeps `@Transactional` in `application`; the rule caught the adapter, and
  the adapter now throws `IllegalTransactionStateException` outside a transaction, which is
  what `MANDATORY` does.
- **Section 4's demo configuration was picked up by the application's component scan**
  (`ApplicationScanIT` failed). Section 4 had been checked without the failsafe ITs. It is
  no longer a `@Configuration`, like the stage 5 demos, and every section is now checked
  with the whole `./mvnw clean verify`.
- The paused holder is a real thread parked on a latch; the lock's expiry is forced with
  `PEXPIRE 1`. Both tokens in the evidence are read from the leases, not derived.
- `refusedAdmitRollsBackTheWriteThatCameBeforeIt` writes first and admits second, the order
  a careless caller would use; the whole transaction still rolls back.

---

## [x] 9 · Breakdown across instances

**`application.cache` `ReadThrough`**
- With a `DistributedLock`: a miss takes the lock for `lock:price:{isin}`; the
  holder loads and stores both tiers; a caller that did not get the lock polls
  L2 a bounded number of times, then loads anyway (planning decision 5).

**Tests (`SharedRedis`, two `ReadThrough` instances with their own L1 and one
Redis L2)**
- `twoInstancesLoadOnce`: both ask for one hot key together; the counting
  loader is called once in total.
- `lockHolderFailureDoesNotBlockTheOthers`: the holder's load throws; the
  waiter loads for itself.
- `waiterFallsBackAfterBoundedPolls` with Redis flushed of the lock only.
- `reproducesStampedeAcrossInstancesWithoutLock`: same test with no lock, the
  loader is called twice.
- Evidence `09-breakdown.txt`: call counts for 1 JVM with and without
  single-flight, and 2 instances with and without the lock.

**Acceptance**
- Tests pass; `./mvnw clean verify` is green.

**Found while implementing**
- **A waiter tries the lock again on every poll**, not only once. With a single attempt
  followed by polls of L2, a holder whose load failed would leave every waiter polling an
  empty L2 until its polls ran out. Now the first waiter to see the lock free takes over
  (`lockHolderFailureDoesNotBlockTheOthers`). The fallback after the last poll remains.
- The tests use `Integer.MAX_VALUE` polls with `Thread.yield()` as the pause, so a waiter
  really waits for the holder; the test releases the loader only after a waiter has polled
  at least once, which is what "the second instance was blocked" means.
- The evidence file is written in `@AfterAll`, because test methods run in no fixed order.

---

## [x] 10 · Invalidation and the two-level cache

**`adapter.persistence.cache`**
- `RedisInvalidationBus`: `publish(isin)` on `sl:price:invalidate`; a
  `RedisMessageListenerContainer` subscriber that evicts L1 only.

**Tests (`SharedRedis`, two instances)**
- `evictOnOneClearsTheOthersL1`, with the latency from publish to L1 clear
  recorded across 200 rounds (median and maximum, this machine).
- `reproducesStaleL1WithoutInvalidation`: instance B keeps the old price until
  its L1 TTL, asserted in ticks.
- `reproducesMissedInvalidationWhenSubscriberWasDown`: B's subscriber stopped,
  message published, B restarted: it serves the old price until its L1 TTL.
- `staleBoundIsTheL1Ttl`. Evidence `09-invalidation.txt`.

**Acceptance**
- Tests pass; `./mvnw clean verify` is green.

**Found while implementing**
- **`ReadThrough.evictNearest` is new**, and `CachingMarketPriceSource` gained `evictLocal`.
  A message from another instance must drop only the nearest-tier copy (the sender already
  cleared the shared tier) and must still stop an in-flight load from storing an old result.
- **`Invalidation<K>` is a port in `application.cache`**, taken by the decorator as an
  optional constructor argument; the Redis bus is subscribed after the decorator exists
  (`bus.start(prices::evictLocal)`), which avoids a construction cycle.
- The test for the stale windows also asserts the boundary exactly: at `TTL - 1 ns` the old
  price is still served, at `TTL` the new one, on a controlled ticker.
- `RedisMessageListenerContainer.start()` returns after the subscription is registered, so
  no test waits for "the subscriber is ready".
- A message that is not an ISIN is counted and dropped, and does not stop the subscriber.

---

## [ ] 11 · Write order and delayed double delete

**Tests (`SharedRedis`, `adapter-persistence`; a test-side `PriceStore` with
latch hooks stands in for the database)**
- `reproducesStaleCacheAfterUpdateThenDelete`: reader R loads the old value,
  writer W updates and deletes, R stores the old value. The old value's
  survival is asserted in ticks.
- `reproducesStaleCacheAfterDeleteThenUpdate`: W deletes, R loads and stores
  the old value, W updates.
- `delayedSecondDeleteRepairsBothOrders`.
- `reproducesDelayedDeleteDefeatedBySlowReader`: R slower than the delay
  stores after the second delete.
- Evidence `09-write-order.txt`: the four timelines.

**Acceptance**
- Tests pass; `./mvnw clean verify` is green.

---

## [ ] 12 · Wiring, configuration, end to end

**`bootstrap` `CacheConfiguration`**, conditional on `ledger.cache.enabled=true`
- `ledger.cache.{l1-max-size, l1-ttl, l2-ttl, negative-ttl, jitter, redis.*,
  lock-ttl}`. Defaults: 10,000 / 5 s / 30 s / 2 s / 0.2. Validated at startup.
- Wraps `MarketPriceSource` and `SecurityDirectory` with the decorators, wires
  Redis and the invalidation bus.
- `infra/compose.yaml`: Redis, loopback, password from `infra/.env`;
  `.env.example` gains the variable name; `application-local.yml` enables it.

**Tests (`bootstrap`)**
- `CachedQuoteIT`: `GET /securities/{isin}/quote` twice: the stub is called
  once; HKD amount and scale unchanged on the wire; unknown ISIN: one
  directory hit for many requests; Redis holds the JSON with a TTL.
- `CacheDisabledIT`: default configuration, no cache beans, the stub is
  called every time.
- `ArchitectureIT` gains a rule: `application` depends on no Caffeine,
  Lettuce, Spring Data Redis or Jackson type.

**Acceptance**
- The manual transcript is in `docs/evidence/09-manual-run.txt`.
- `./mvnw clean verify` is green.

---

## [ ] 13 · The lab

**`labs/09-caching/`** — standalone POM, not in the root's `<modules>`,
Testcontainers Redis.
- Redisson: watchdog on a slow-but-alive holder (renews); child JVM stopped
  with `SIGSTOP` beyond the lease (no renewal, second holder acquires),
  timeline to `target/evidence/09-watchdog.txt`. The hand-written lock gets the
  same stop-signal run.
- Bloom filter (Guava) against negative caching for 100,000 unknown ISINs:
  memory, false-positive rate, lookups reaching the store.
- Protobuf (`protobuf-java`) against JSON for the same price: sizes, and a
  `double` field's precision loss.
- Unbounded Caffeine heap hook for stage 15, documented in the README.

**Acceptance**
- `./mvnw -f labs/09-caching/pom.xml verify` runs; evidence files exist.
- The root `./mvnw clean verify` still does not build the lab.

---

## [ ] 14 · Decision record and roadmap

- `docs/decisions/09-caching.md` per the spec's list, every number from an
  evidence file.
- Copy every evidence file into `docs/evidence/`.
- Tick stage 9 in `ROADMAP.md`'s exercise list and progress table.

**Acceptance**
- Every spec criterion has a test or an evidence file named in the record.
- `./mvnw clean verify` is green.

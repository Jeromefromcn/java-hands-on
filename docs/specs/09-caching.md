# Stage 09 — Caching

## Problem

Stage 7 made every quote a remote call. Stage 8 bounded the damage: a fixed
executor between the request thread and the slow source. Neither reduces
the number of calls. A price changes rarely and is read constantly, so
almost every call fetches an answer the ledger already had a moment ago.

Stage 9 puts a cache in front of the price source and the security lookup,
then drives every failure a cache brings to an asserted or measured result:
unbounded growth, a proxy that is bypassed, a serialization that loses
digits, keys that miss forever, one expired key hit by a crowd, many keys
expiring together, two write orders that disagree, and a lock that its
holder no longer owns.

Four facts shape the stage.

- **Caching moves the money invariant to a new boundary.** A price that
  leaves the JVM for Redis is serialized. `Money` is not `Serializable`,
  and a `double` in a wire format is where digits go missing. The stage
  makes both failures happen once, then closes them.
- **The ledger has no price writer.** Prices arrive from an external
  source; nothing in this ledger updates them. The "update the database,
  then invalidate the cache" exercise therefore runs against a test-side
  store that stands in for the database. The product's own guarantee is a
  bounded staleness (a TTL), and the decision record states the number.
- **The one distributed-lock use in the roadmap is stage 11's.** Daily
  accrual must run on one instance, but it does not exist yet. Stage 9
  builds the lock and the fencing guard that stage 11 will call, and
  reproduces the failure on a test-only table, not on a real accrual.
  `accrual_entry`'s primary key is `(position_id, business_date)`, so the
  real table already refuses a second entry for the same day. The failure
  worth fencing is a write that is *not* idempotent, such as adding to a
  running total. The decision record says why the fence is still needed
  even where a primary key exists.
- **Only some of the eleven exercises belong to the product.** A bounded
  two-tier cache, negative caching, single-flight rebuild, jittered
  expiry, invalidation across instances and the lock with its fence are
  product code. An unbounded cache, a naive `DEL`, a `double` codec, the
  cache-aside race, Redisson and a Bloom filter are for breaking or
  comparing. `CLAUDE.md` gives broken code two homes: a
  `reproduces<Failure>` test, or `labs/<nn>-<slug>/`. Redisson, Guava and
  protobuf-java are compared in the lab, so no main-build module takes
  those dependencies.

## Settled design decisions

These were decided before this spec was written. The decision record argues
each; the spec states what they commit the code to.

1. **The product path is an explicit read-through decorator, not
   `@Cacheable`.** The decorator can cache "absent", jitter the TTL per
   entry, rebuild under a per-key lock and write two tiers. The annotation
   cannot do the middle two, and it is a proxy: a call from inside the
   same class skips it. `@Cacheable`, `@CachePut` and `@CacheEvict` are
   still exercised, on test-only beans, including the self-invocation
   failure that shares its cause with stage 5.
2. **Two tiers.** A per-instance Caffeine cache (L1) and Redis (L2). The
   security lookup is L1 only, because a `Security` carries a `Money` and a
   currency and its Redis encoding buys nothing the price cache does not
   already prove. The price cache uses both tiers.
3. **Redis code lives in `adapter-persistence`, package
   `adapter.persistence.cache`.** Redis is a data store here, not a
   transport. A seventh module was considered and not added, for the
   reason stage 7 gave: the module list is fixed in `CLAUDE.md` and one
   package does not justify changing it. The generic read-through and the
   ports live in `application`, which stays free of third-party
   dependencies.
4. **Money crosses Redis as JSON with a string amount and a currency
   code.** On read, an amount with more decimals than its currency allows
   is refused, not rounded. This is stage 7's boundary rule and stage 6's
   read rule applied to a third boundary.
5. **A miss is cached.** "The source does not know this ISIN" is stored as
   an explicit *absent* entry with a short TTL. A Bloom filter is
   measured against it in the lab and not adopted, for reasons the
   decision record gives after the measurement.
6. **A hot key is rebuilt under mutual exclusion, not by logical
   expiry.** In one JVM, exactly one caller loads; the others wait for
   that load. Across instances, the rebuild takes the Redis lock. Logical
   expiry (serve stale, refresh in the background) is rejected for this
   data and the decision record says why.
7. **TTLs are jittered.** Every entry's TTL is the base TTL plus or minus a
   configured fraction, drawn per entry.
8. **The write order is: update the store, then delete the cache entry,
   and delete it a second time after a delay.** Both single-delete orders
   are reproduced with their own windows, and the delayed second delete's
   cost is stated.
9. **The lock is `SET key token NX PX ttl`, released by a Lua script that
   deletes only if the value still matches.** Every acquisition also takes
   a fencing token from a Redis `INCR`. The fence is enforced where the
   write lands, in Postgres, by a `write_fence` table (migration V5).
   Redisson and its watchdog are compared in the lab, on real processes.
10. **The cache is off unless configured.** `ledger.cache.enabled` defaults
    to `false`, so every existing test and profile behaves as it did.
    The `local` profile turns it on. Every value cached in the product has
    a bounded staleness of at most `l2-ttl + l1-ttl` (jitter included), and
    the decision record states the resulting number for the defaults.

## Scope

### Module topology and dependencies

No child POM carries a `<version>`. Caffeine, Lettuce, Spring Data Redis
and Testcontainers come from the Boot BOM. Lab POMs carry their own
versions (the lab is not a child of the root).

| Module | Gains | New dependencies |
|---|---|---|
| `domain` | nothing | none |
| `application` | `cache` package: entry type, tier port, `TtlPolicy`, `ReadThrough`; `DistributedLock` and `WriteFence` ports; `CachingMarketPriceSource`, `CachingSecurityDirectory` | none |
| `adapter-persistence` | `cache` package: Caffeine tier, Redis tier, JSON codec, invalidation bus, `RedisLock`; `JdbcWriteFence`; V5 `write_fence` | Caffeine, Spring Data Redis (Lettuce), Jackson |
| `adapter-web` | nothing | none |
| `bootstrap` | `ledger.cache.*` properties and wiring; Spring Cache demo beans (test only) | none in main |
| `labs/09-caching` | Redisson and watchdog on real processes, protobuf and Bloom comparisons, an unbounded Caffeine heap hook for stage 15 | Redisson, Guava, protobuf-java, Testcontainers |

### The cache (`application.cache`)

- An entry is *present* (a value) or *absent* (a known miss). There is no
  `null`.
- A tier stores and retrieves entries with a TTL per `put`, and evicts.
- `TtlPolicy` returns a per-entry TTL from a base, a jitter fraction and a
  random source, so a test fixes the draw.
- `ReadThrough` reads L1, then L2, then loads. A load is single-flight per
  key in the JVM; across instances it takes the lock when one is
  configured. A tier hit in L2 is promoted into L1. A load that finds
  "unknown" stores *absent* with the negative TTL. A load that throws
  stores nothing.
- `CachingMarketPriceSource` and `CachingSecurityDirectory` decorate the
  existing ports. Nothing above them changes: `QuotePrice` and
  `PriceFanOut` are unmodified.

### Expiry and rebuild

- L1 is bounded by `maximum-size` and by a per-entry expiry, on a
  `Ticker` a test can drive.
- Avalanche: a fixed-TTL cache with N keys stored in one instant issues N
  loads in one instant when they expire. The jittered cache spreads the
  same N loads over the jitter window. Both are counted per time bucket on
  a controlled ticker, and the histogram is evidence. It is deterministic,
  so it is a measurement of the mechanism, not of this machine's clock;
  the evidence file says so.
- Breakdown: many threads ask for one expired hot key at once. Without
  single-flight the source is called once per thread; with it, once. Two
  instances sharing L2 and the lock also call the source once.

### Redis tier and serialization

- Key: `price:{isin}`. Value: JSON `{"state":"present","amount":"41.26",
  "currency":"HKD"}` or `{"state":"absent"}`.
- Round trip is asserted for `HKD`, `JPY` and `BHD` at 16 integer digits,
  against a real Redis.
- `reproducesJdkSerializationRefusesMoney`: `Money` is not `Serializable`.
- `reproducesPrecisionLossWithDoubleCodec`: a codec that writes the amount
  as a JSON number, read back as a double, changes the value.
- An over-precise stored amount and an unknown currency are refused on
  read, and the tier treats an undecodable value as a miss, never as a
  price.
- Nothing else is stored in Redis without a TTL, checked by scanning keys
  after every test.

### Invalidation across instances

- A `PriceInvalidation` publishes an evict for an ISIN on a Redis channel.
  Every instance's subscriber evicts its L1 and nothing else.
- Two instances share one Redis. An evict on one clears the other's L1.
  Propagation latency is measured.
- `reproducesStaleL1WithoutInvalidation`: without the channel, the second
  instance keeps serving the old price until its L1 TTL. With a subscriber
  that was disconnected when the message was published, the same happens:
  pub/sub does not replay. The bound on staleness is the L1 TTL, not the
  channel.

### Consistency between store and cache

Run against a test-side store with hooks for interleaving, and a real
Redis tier:

- Order A (update, then delete): a reader that loaded the old value before
  the update and writes it back after the delete leaves the old value in
  the cache. The window is reproduced with latches.
- Order B (delete, then update): a reader between the two repopulates the
  old value. Reproduced.
- Delayed second delete: repairs both, at the cost of a second delete per
  write and a delay that must exceed the longest read. A read slower than
  the delay defeats it, and that is reproduced too.
- The fixture stores the old value's age, so each test asserts the time
  the stale value survived, in ticks of the controlled clock.

### Distributed lock and fencing

- `DistributedLock.tryAcquire(resource, ttl)` returns a lease with a
  fencing token, or nothing. `release` deletes only if the caller still
  holds the lock.
- `reproducesUnlockOfSomeoneElsesLock`: a naive `DEL` after expiry
  releases the second holder's lock.
- Holder pause beyond TTL: a holder pauses (a latch in the test, standing
  in for a stop-the-world pause) while its lock expires; a second holder
  acquires; the first resumes and writes. The test-only table
  `accrual_total(position_id, total)`, updated by `total = total + x`,
  shows the increment applied twice.
- `WriteFence.admit(resource, token)` runs in the same transaction as the
  write and refuses a token below the highest it has seen. With the fence,
  the stale holder's write is refused and the total is right.
- The lab repeats the pause with a real stop signal on a real JVM: a
  child process holds the lock and is stopped with `SIGSTOP` beyond the
  TTL, for the hand-written lock and for Redisson. The lab records what
  Redisson's watchdog does while the process is merely slow, and that it
  does not renew while the process is stopped.

### Manual run

- `infra/compose.yaml` gains Redis, bound to loopback, password from
  `infra/.env`. `infra/.env.example` gains the variable name only.
- The `local` profile enables the cache and points at it. A transcript of
  a quote served from the cache is in evidence.

## Out of scope

- Hibernate's second-level cache. It was refused in stage 6 for having no
  cross-node invalidation; this stage's invalidation channel does not
  change that decision, and the decision record says so.
- A Redis cluster, Sentinel, and Redlock across independent masters. The
  lock here is single-instance, and the decision record says where that
  breaks.
- Wiring the lock into a daily accrual (stage 11).
- Cache metrics and dashboards (stage 12). Hit and miss counts exist as
  counters the tests read.
- Cache-warming and eviction on a schedule.
- Authentication on Redis beyond a password (stage 13).

## Constraints

- `domain` does not change.
- `./mvnw verify` at the repository root stays green after every section.
- `application` gains no third-party dependency.
- No test depends on a running Compose stack; Redis and Postgres come from
  Testcontainers.
- Concurrency is sequenced with latches and barriers, never `Thread.sleep`.
  Every test that can block has `@Timeout(30)`. Time-dependent behaviour
  runs on a controlled ticker.
- Reproduced failures are named `reproduces<Failure>`, carry
  `// See docs/decisions/09-caching.md`, and assert an observable outcome
  (a call count, a value), never message text alone.
- Numbers in the decision record come from evidence files measured on this
  machine, and a value that is deterministic by construction says so.
- No secret in any committed file. The Redis password is an environment
  variable.
- All data is synthetic.

## Acceptance criteria

1. Price and security lookups go through the cache when
   `ledger.cache.enabled=true`; with it off, behaviour is unchanged and
   every stage 1–8 test passes.
2. L1 is bounded; `reproducesUnboundedGrowth` shows the unbounded variant
   holding every key, and the bounded one not.
3. `@Cacheable`, `@CachePut` and `@CacheEvict` behave as documented on the
   test beans, and `reproducesSelfInvocationBypassesCache` counts source
   calls that the proxy should have prevented.
4. Money round-trips through Redis in `HKD`, `JPY` and `BHD`, and both
   serialization failures are reproduced.
5. An unknown ISIN costs one lookup, not one per request; the absent entry
   expires.
6. A crowd on one hot key produces one load in one JVM and, with the lock,
   one load across two instances.
7. The avalanche histogram before and after jitter is in evidence.
8. Evict on one instance clears the other's L1, with a measured latency,
   and both stale-L1 reproductions pass.
9. Both single-delete orders are reproduced with their windows, and the
   delayed second delete is shown to repair them and to fail for a slow
   reader.
10. The lock is exclusive, releases only its own holder, and expires.
11. The pause reproduction applies an increment twice without the fence and
    once with it.
12. The lab's stop-signal runs and its evidence exist.
13. `ArchitectureIT` still passes: `application` has no dependency on
    Caffeine, Lettuce or Redis types.
14. `./mvnw clean verify` at the repository root is green.

### Evidence

`docs/evidence/09-*.txt`, text only, measured on this machine:

- the avalanche histogram, before and after jitter;
- the L1 growth with and without a bound;
- the Redis round trip for the three currencies, and the double-codec loss;
- the penetration lookup counts, and the Bloom filter comparison;
- the breakdown call counts;
- the invalidation latency and the two stale-L1 windows;
- the two write-order windows and the delayed delete;
- the pause reproduction with and without the fence;
- the lab's stop-signal timeline for the hand-written lock and Redisson;
- the protobuf and JSON sizes for the same price;
- the manual `curl` transcript.

## Done when

The four `CLAUDE.md` deliverables exist: this spec, the plan, the code with
every criterion above verified, and `docs/decisions/09-caching.md`. The
decision record must cover:

- the ten settled decisions, argued against their alternatives;
- the cache-aside race, as a timeline of who runs first and second;
- why mutual rebuild and not logical expiry;
- Bloom filter against negative caching, with the numbers;
- why the fence is needed even where a primary key exists;
- what Redisson's watchdog solves and why it cannot replace a fence;
- the staleness bound for the default configuration;
- where each choice breaks at 100x.

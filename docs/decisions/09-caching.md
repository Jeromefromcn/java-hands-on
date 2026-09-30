# Stage 09 — Caching — Decision Record

Spec: [`docs/specs/09-caching.md`](../specs/09-caching.md)
Plan: [`docs/plans/09-caching.md`](../plans/09-caching.md)

Measured on JDK 21.0.12 (Temurin, the `.sdkmanrc` pin), a 4-CPU Linux host,
Spring Boot 4.1.1, Redis 7.4 (Testcontainers, loopback) and Caffeine 3.2.
Every number below is copied from a `docs/evidence/09-*.txt` file. Where a
number is deterministic by construction (a controlled ticker, a scripted
interleaving), the evidence file says so and so does this record. Where a claim
is reasoned and not measured, it says so.

## What I chose

### The split: what ships, and what only exists to be broken

| Exercise | Where | Why there |
|---|---|---|
| Two-tier read-through, negative entries, single-flight, jittered TTL | product code (`application.cache`, `adapter.persistence.cache`) | the service needs it |
| Invalidation channel, rebuild lock, fence | product code | the service needs it, stage 11 will call the lock and the fence |
| Unbounded cache, `@Cacheable` self-invocation, JDK serializer, `double` codec, naive `DEL`, cache-aside races, penetration and stampede without the fix | `reproduces<Failure>` tests beside the fix | the broken form is one line away from the fixed form |
| Stopped lock holder, Redisson's watchdog, Bloom filter, protobuf, heap to OOM | `labs/09-caching/` | Redisson, Guava and protobuf-java must not enter the main build; a stopped process needs a real process |

### The product path is an explicit read-through, not `@Cacheable`

`ReadThrough` takes tiers nearest first, a loader, a TTL policy per tier, and
optionally a cross-instance lock. `CachingMarketPriceSource` and
`CachingSecurityDirectory` decorate the ports stage 7 defined; `QuotePrice` and
`PriceFanOut` are unchanged. The annotation route was built and broken first
(`SpringCacheTest`) and is not the product because:

- it cannot store "known absent" with its own short TTL, jitter a TTL per entry,
  or rebuild a hot key under a lock across instances;
- it is a proxy. `reproducesSelfInvocationBypassesCache`: two calls to one
  `@Cacheable` method made on `this` reached the source **2** times; the same
  two calls made through another bean reached it **1** time
  ([`09-self-invocation.txt`](../evidence/09-self-invocation.txt)). Same cause
  as stage 5's `@Transactional`: the call never crosses the proxy;
- with no `unless`, a `null` result is cached (asserted); with it, an unknown
  key is asked about every time. Neither is right for a miss. Section 6's
  explicit `Absent` is the third option.

### Two tiers, and only the quote path uses them

L1 is Caffeine, bounded (`maximumSize` 10,000) with a per-entry expiry from the
TTL each `put` carries. L2 is Redis, key `sl:price:{isin}`, always with a TTL.
The security lookup is L1 only.

**Only `QuotePrice` gets the cached lookups.** Opening and returning a position
read the security inside their transaction and use its market price for the
credit check. A price up to a minute old is fine to show a client and not fine
to lend against. The decorators are therefore held in a `CachedQuoteSources`
record and are not beans of the `MarketPriceSource` / `SecurityDirectory`
types: registering them `@Primary` would have changed the credit check silently.

The cache is off unless `ledger.cache.enabled=true`; the `local` profile turns
it on. With it off, every stage 1–8 test is unchanged (`CacheDisabledIT`: three
quotes, three source calls).

### Redis code lives in `adapter-persistence`

Redis is a data store here, not a transport. A seventh module was rejected for
stage 7's reason: the module list is fixed and one package does not justify
changing it. `application` stays free of Caffeine, Lettuce and Jackson;
`ArchitectureIT` now enforces it.

### Money in Redis: JSON with a string amount

`{"state":"present","amount":"41.30","currency":"HKD"}`, read back with stage
7's rules (plain decimal only, no more decimals than the currency, a known
currency). An undecodable value is a miss and is reloaded, never served.

| Option | Result | Evidence |
|---|---|---|
| JDK serialization | `Money` is not `Serializable`: `NotSerializableException`, and Spring's serializer fails the same way | `reproducesJdkSerializationRefusesMoney` |
| JSON, amount as a number | `12345678901234567.89 HKD` read as a double comes back `12345678901234568`, off by 0.11 | [`09-redis-double-loss.txt`](../evidence/09-redis-double-loss.txt) |
| JSON, amount as a string | round trips exactly for `HKD`, `JPY`, `BHD` at 16 integer digits, scale and currency intact, through a real Redis | [`09-redis-roundtrip.txt`](../evidence/09-redis-roundtrip.txt) |
| Protobuf (lab) | 12 bytes against JSON's 53 for `41.26 HKD`. With a `double` field: `41.25999999999999801…` for 41.26. With a string or an `int64` count of minor units: exact | [`09-protobuf-vs-json.txt`](../evidence/09-protobuf-vs-json.txt) |

I chose JSON with a string. Protobuf is smaller by four times or so, and an
`int64` in minor units is exact; neither is worth a schema toolchain and a
format no one can read with `redis-cli` for a value that is 53 bytes. The
decision that mattered is not JSON against protobuf. It is that the amount is
never a floating-point type in any of them, and the third boundary (after HTTP
and Postgres) holds the invariant. The protobuf lab is the reason I can say
that with numbers.

### A miss is cached; a Bloom filter was measured and not adopted

`CachingSecurityDirectory` stores `Absent` with a 2 s TTL. 1,000 requests for
one unknown ISIN: **1** directory read, against **1,000** without
([`09-penetration.txt`](../evidence/09-penetration.txt), deterministic).
Through the running application, 20 requests for an unknown ISIN cost the
directory one load (`CachedQuoteIT`).

The Bloom filter (Guava, 1% false-positive rate, 10,000 known keys) against the
negative cache (Caffeine, 1,000 entries), in store lookups
([`09-bloom-vs-negative.txt`](../evidence/09-bloom-vs-negative.txt)):

| Workload | No fix | Negative cache | Bloom filter |
|---|---|---|---|
| A: scan, 100,000 distinct unknown keys | 100,000 | **100,000** | 956 |
| B: 100 unknown keys × 1,000 requests | 100,000 | 100 | 1,000 |

The honest reading is that the negative cache does **nothing** against a scan
of distinct keys. Every key is new; 100,000 lookups reach the store and the
cache churns. The filter is the better defence against a scan, in 12,120 bytes
measured. It also loses on workload B, where its 1,000 lookups are one
false-positive key asked 1,000 times, because it cannot learn.

I still did not adopt it, for a correctness reason, not a cost one. A security
listed after the filter was built is refused, and nothing expires that refusal
until someone rebuilds the filter. A ledger that refuses a real security is
wrong; a ledger that does one extra read is slow. A negative entry's wrong
answer is bounded by its TTL and needs no rebuild. The defence against a scan
that this stage cannot give is rate limiting and authentication (stage 13); the
Bloom filter is what I would add in front of the directory if the reference
data were rebuilt from a single source on every change. That is a judgement,
supported by the two tables above, and I did not measure a filter kept in step
with inserts.

### Breakdown: mutual rebuild, not logical expiry

One hot key expires; many callers ask at once
([`09-breakdown.txt`](../evidence/09-breakdown.txt), real Redis):

| Setup | Source calls |
|---|---|
| 1 JVM, 50 threads, naive read-then-load | 50 |
| 1 JVM, 50 threads, `ReadThrough` (single-flight) | **1** |
| 2 instances, shared L2, no lock | 2 |
| 2 instances, shared L2, Redis rebuild lock | **1** |

In one JVM the mutual exclusion is a map of in-flight loads. Across instances
it is the Redis lock, and the second instance polls L2 until the first has
stored the answer. Two properties were found by writing the tests and are in
the code: a waiter tries the lock again on every poll, so a holder whose load
failed does not strand the others (`lockHolderFailureDoesNotBlockTheOthers`);
and after a bounded number of polls the waiter loads anyway
(`waiterFallsBackAfterBoundedPolls`), because the lock is an optimisation and
must never turn a slow Redis into a failed read.

**Why not logical expiry** (serve the stale value, refresh in the background).
This is reasoned, not measured. Logical expiry hides the cost of a hot key from
callers, which is its appeal. Here it would serve a price past its TTL while the
source is down, silently: the decision that a stale price is unacceptable is
stage 12's to make explicitly, with a circuit breaker and a stale-price
fallback that says so. It also needs a refresher and a second copy of every
entry. I would choose it for data that is expensive to compute and cheap to be
wrong about. A price is neither.

### Avalanche: jittered TTL

1,000 keys stored at one instant, a 1 s TTL, every key read at every 50 ms step
for 3 s, on a controlled ticker
([`09-avalanche.txt`](../evidence/09-avalanche.txt), deterministic by
construction; it measures the mechanism, not this machine's clock):

| | Peak loads in one step | Total loads in 3 s |
|---|---|---|
| Fixed TTL | **1,000** (at t = 1,000, 2,000 and 3,000 ms; zero between) | 3,000 |
| ±20% jitter | 148 | 2,421 |

The peak is 6.8 times lower. The file is the "spike graph" the roadmap asks for:
one column of numbers per step and a bar beside it, with three spikes on the
left and a flat band on the right. Jitter also lowered the total (2,421
against 3,000), because keys whose TTL happened to be longer were reloaded less
often within the window; that is a side effect of a short window, not a claim.

L1 grows without a bound only on purpose:
[`09-l1-growth.txt`](../evidence/09-l1-growth.txt) — 100,000 distinct keys,
100-character values, an unbounded Caffeine cache held all **100,000** and grew
the heap by 24,265,464 bytes; `CaffeineTier` with `maximumSize` 1,000 held
at most 1,000 and grew it by 346,720. `labs/09-caching` `UnboundedHeap` under
`-Xmx64m` filled to `OutOfMemoryError` at **236,866** entries
([`09-unbounded-heap.txt`](../evidence/09-unbounded-heap.txt)); that is the
starting point for stage 15.

### Write order: update the store, then delete the cache, then delete it again

This ledger has no price writer; prices arrive from outside. The exercise ran
against a test-side store and a real Redis tier, with each interleaving forced
by latches ([`09-write-order.txt`](../evidence/09-write-order.txt)). R is a
reader that misses and loads; W changes 41.26 to 42.00.

```
Order A: update the store, then delete the cache
  1. R: cache miss
  2. R: loaded 41.26 from the store
  3. W: updated the store to 42.00
  4. W: first delete of the cache entry
  5. R: cached 41.26            => cache 41.26, store 42.00, stale up to 29,996 ms

Order B: delete the cache, then update the store
  1. W: first delete of the cache entry
  2. R: cache miss
  3. R: loaded 41.26 from the store
  4. R: cached 41.26
  5. W: updated the store to 42.00   => cache 41.26, store 42.00, stale up to 29,998 ms
```

Both leave the old value in the cache until its TTL runs out (29,996 ms and 29,998 ms
are Redis's `PTTL` for the stale entry at the end of each scenario, out of 30 s).
What decides the choice is how likely each window is, and that is reasoned, not
measured. In A, R must be slower than W's *whole* update and delete. In B, R only
has to land between W's two steps, and W's second step is the database write,
the slow one. B's window is the longer, so I choose A: **update the store, then
delete the cache**.

The **delayed second delete** repairs both (asserted; the cache is empty after
it and the next read loads 42.00). Its cost is a second delete for every write
and a delay that must exceed the longest read. `reproducesDelayedDeleteDefeatedBySlowReader`
shows the failure: R, slower than the delay, caches 41.26 *after* the second
delete has already run. The delay is a guess about the slowest read, and the
TTL remains the real bound. Without a writer in this ledger, none of this is
wired in; the API exists (`evict`, with its invalidation), and the rule is what
the first writer inherits.

### Multi-level: an invalidation channel that is only an optimisation

`RedisInvalidationBus` publishes the ISIN on `sl:price:invalidate`; each
subscriber evicts its L1 and nothing else. The writer already cleared L2.

- Latency from `A.evict` to B's L1 being empty, 200 rounds, two instances and a
  real Redis on loopback
  ([`09-invalidation-latency.txt`](../evidence/09-invalidation-latency.txt)):
  median **2,068 µs**, p99 12,097 µs, max 16,116 µs. This includes A's own
  deletes, so it is an upper bound on the channel alone.
- `reproducesStaleL1WithoutInvalidation`: with no channel, B serves 41.26 at
  `TTL − 1 ns` and 42.00 at `TTL`, on a controlled ticker, so the window is
  exactly the L1 TTL of 5 s
  ([`09-invalidation-stale.txt`](../evidence/09-invalidation-stale.txt)).
- `reproducesMissedInvalidationWhenSubscriberWasDown`: the message published
  while B's subscriber was stopped is gone. B, resubscribed, still serves the
  old price until the same 5 s. Pub/sub does not replay.

So the channel shortens staleness in the ordinary case and bounds nothing in the
bad one. **The bound is the L1 TTL.** That is why the staleness of a served
price is stated as `l2-ttl + l1-ttl`: an entry copied out of L2 at the end of
its life lives a full L1 TTL after that. With the defaults it is **35 s**, and
up to **42 s** with the 20% jitter on both tiers. This is arithmetic on the
configuration, not a measurement.

### The lock: `SET NX PX` and a Lua release, with the token taken in the same script

`RedisLock`: acquire is one Lua script that does `SET lock:{r} {owner} NX PX ttl`
and, only if that succeeded, `INCR fence:{r}`. Release is a Lua script that
deletes only if the value is still the owner's; a second release is a no-op.
`reproducesUnlockOfSomeoneElsesLock`: after the first holder's TTL passed and a
second holder took the lock, a plain `DEL` removed the second holder's lock, and
a third holder acquired: two holders at once. The Lua release leaves it alone
(`leaseThatExpiredDoesNotReleaseTheNextHolder`).

The token came out of the plan wrong (see below): as a second command after the
`SET`, a holder paused between the two could come back with a token *higher*
than a later holder's, for a lock it no longer held, which is exactly what the
fence must never allow.

### What happens when the holder pauses past the TTL

Two runs of the same scenario. Holder A takes the lock and stops; the lock
expires; holder B takes it and adds 100 to a total; A resumes and adds 100. One
run should add 100 once.

- In the main build, A's pause is a latch, the write is `total = total + 100`
  in Postgres, the lock is real Redis
  ([`09-fencing.txt`](../evidence/09-fencing.txt)): without a fence the total is
  **200** (A's token 1, B's token 2, A's write went through); with the fence
  admitted in the write's transaction it is **100** and A's write is refused
  with `StaleFencingTokenException`.
- In the lab the pause is real: a child JVM stopped with `kill -STOP`, for the
  hand-written lock and for Redisson
  ([`09-watchdog.txt`](../evidence/09-watchdog.txt)), lease and watchdog timeout
  3,000 ms:

| Scenario | Second holder acquired at | Total | What happened |
|---|---|---|---|
| 1. Redisson, holder alive, 9 s of work | 10,024 ms | 2 (two sequential rounds) | the watchdog renewed the lease; the second holder waited for the release |
| 2. Redisson, holder stopped | 3,948 ms | **2** | nothing renewed; the lock passed; the resumed child wrote at 6,932 ms and its unlock was refused |
| 3. Hand-written, holder stopped | 4,111 ms | **2** | the same |
| 4. Hand-written + fence, holder stopped | 4,025 ms | **1** | the resumed child's write: `REFUSED-STALE-TOKEN` |
| 5. Hand-written, holder alive, 6 s of work | 4,441 ms | **2** | no watchdog: the lease ran out under a live holder |

The answer to "what happens when the holder's GC pause exceeds the TTL" is
scenario 3: the lock is gone, another instance takes it, and when the first
resumes it writes as if it still held the lock. A `SIGSTOP` is not a GC pause,
but from inside the process the two are indistinguishable, and that is the point
of using it.

### Fencing token, and what the watchdog does and does not do

**The watchdog solves scenario 5.** A holder that is alive but slower than the
lease keeps the lock, because a thread inside the process renews it every
third of the lease. It is the difference between scenario 1 and scenario 5, and
it is worth having.

**It cannot solve scenario 2**, and that is why it does not replace a fence. The
renewal runs in the holder's own process. A pause that stops the process stops
the renewer with the work, so the lease runs out exactly as it does with no
watchdog at all (3,948 ms against 4,111 ms above). Anything that can pause the
work can pause the thing that keeps the lock alive. Only the store the write
lands in can know that a write is late, and it knows it by comparing a token
that only increases. I did not use Redisson's fenced lock (`RFencedLock`); the
lab compares the hand-written fence, and I did not measure the alternative.

**The fence is `write_fence(resource, token)`, checked in the write's own
transaction.** One conditional upsert: raise the stored token if it is not
higher, refuse if it is. Equal tokens are admitted, because one holder makes
several writes under one token. It refuses to run outside a transaction, so a
refused write and the work it guarded roll back together
(`refusedAdmitRollsBackTheWriteThatCameBeforeIt`,
`failedWriteRollsBackTheAdmittedToken`).

**Why the fence is needed where `accrual_entry` already has a primary key.**
The key `(position_id, business_date)` stops a second *row* for a day. It does
not say which holder is stale. If the stale holder A resumes before B writes,
A's entry, computed with an old rate, takes the slot, and B, the legitimate
holder, gets the duplicate-key error. The primary key makes the insert
idempotent; it cannot tell the late writer from the right one. And any write
that is not an insert (a running balance, a counter) has no key to protect it.
The fence is checked against who holds the lock now, not against what already
exists.

### Redis restart resets the fence: found late, pinned, not fixed

The fence counters live in Redis and never expire. If Redis restarts without
persistence, they begin again at 1, below the token Postgres has admitted.
`reproducesFenceRefusingEveryWriteAfterARedisRestartThatLostTheCounters`: after
three admitted writes (stored token 3), a flush, and a new acquisition (token
1), every write is refused. It fails closed, which is the safe direction, and it
stays closed until the counter passes the stored token or `write_fence` is
cleared. The compose file's Redis has no persistence, on purpose, and its
comment says this. The two real fixes are AOF with `always` (which still can
regress by the last unsynced write) or issuing the token from the same
Postgres that checks it, with Redis doing only the coarse exclusion. I would
take the second. It was not built because the lock has no caller until stage 11.
**Scheduled: stage 11, first plan section** (see `ROADMAP.md`). That change also
removes the never-expiring `sl:fence:price:*` key the cache rebuild lock leaves
behind, since the rebuild path will no longer take a token from Redis.

## What I rejected, and why

- **`@Cacheable` as the product path.** It cannot cache a miss with its own
  TTL, jitter, or lock a rebuild across instances, and it is a proxy.
- **A shared cache for the credit check.** A price a minute old is acceptable
  to show and not to lend against.
- **Hibernate's second-level cache.** Stage 6 refused it for having no
  cross-node invalidation. This stage's channel does not change that: it is
  fire-and-forget and bounds nothing. The refusal stands.
- **A Bloom filter in front of the directory.** It wins the scan, in 956
  lookups against 100,000, and refuses a security listed after it was built with
  nothing to expire the refusal.
- **Logical expiry for hot keys**, above.
- **Redlock, and a lock with the token in a second command.** One Redis, one
  script. A failover that loses an acknowledged `SET` still allows two holders,
  which is one more reason the write checks its token.
- **A fence counter with a TTL.** A counter that expired could restart below a
  token already admitted.
- **Protobuf or `int64` minor units for the Redis value**, for the reasons above.
- **A seventh module for Redis.**

## What I observed

- The self-invocation bypass: 2 source calls for two calls on `this`, 1 through
  another bean.
- 100,000 distinct keys: 100,000 held unbounded, at most 1,000 bounded.
- A 3 s window with a 1 s TTL: 1,000-load spikes at 1,000, 2,000 and 3,000 ms,
  148 at worst with jitter.
- 1,000 unknown-ISIN requests: 1 directory read against 1,000.
- A hot key and 50 threads: 50 source calls naive, 1 with single-flight; two
  instances: 2 without the lock, 1 with it.
- Invalidation: median 2,068 µs, p99 12,097 µs, max 16,116 µs (200 rounds); the
  stale window without it is exactly the L1 TTL.
- The fence: 200 against 100.
- The stop signal: 3,948 ms to a second holder under Redisson with the holder
  stopped, 10,024 ms with it merely slow.
- Through the running application against the compose stack
  ([`09-manual-run.txt`](../evidence/09-manual-run.txt)): the first quote
  0.687 s, the second 0.0096 s; Redis held the JSON with a 25,259 ms `PTTL`;
  WireMock's journal counted **1** request for the ISIN after two quotes; five
  requests for an unknown ISIN were five `404`s. One key was not expected:
  `sl:fence:price:HK0000000007`, the rebuild lock's fence counter. A cache
  rebuild does not need a token, and the counter has no TTL; it is one key per
  security that reaches the source, so it is bounded, but it is a cost the lock
  pays for a fence the cache never uses.

## Where the plan was wrong

Four things the plan said and the code disproved. Each was caught by a test or
a rule, not by reading.

1. **The token was a second Redis command.** Section 7's plan said "`INCR` after
   a successful set". It was written as one Lua script before any test ran,
   after working through what a pause between the two would do; the reason is in
   the lock section above.
2. **A waiter tried the lock once.** The plan had polls of L2 only. The failure
   test showed a holder whose load failed would leave every waiter polling an
   empty tier until its polls ran out. Each poll now tries the lock again.
3. **`@Transactional(MANDATORY)` in the adapter.** `ArchitectureIT` refused it:
   `@Transactional` lives only in `application`. The fence now checks for an
   active transaction itself.
4. **Section 4's demo configuration was scanned into the application.**
   `ApplicationScanIT` failed. I had run the section without the failsafe
   tests. It is no longer a `@Configuration`, and every section from then on ran
   the whole `./mvnw clean verify`.

## Where each choice breaks at 100x

- **L1 is per instance.** 100 instances hold 100 copies and 100 hit ratios; the
  shared tier absorbs the misses, so Redis sees the union of them. L1's
  `maximumSize` is a per-instance memory budget and its TTL the staleness bound;
  neither scales with traffic, so neither is what breaks.
- **One Redis.** Every rebuild, every fence counter, every invalidation and every
  L2 read goes through it. A hot key is one key on one shard. The acquire script
  touches the lock key and the fence key together, so on a Redis Cluster they
  need a shared hash tag.
- **Pub/sub fan-out.** One evict is one message to *every* instance, including
  the ones that never held the key. 100x the instances is 100x the messages per
  write, and a slow subscriber is disconnected and misses them (measured with a
  stopped one). The TTL is what keeps this correct; the channel is what keeps it
  fast, and only until it does not.
- **The rebuild lock's fallback.** 50 polls of 20 ms is about a second. Past
  that, a waiter loads for itself. With 100x the instances all asking for one
  expired key, the lock still lets one through and the rest wait; when the
  source takes longer than a second, they all fall back together, which is the
  stampede again, one second late.
- **The negative cache under a scan.** Workload A: it does nothing, and the
  cache churns. Its 1,000 entries are evicted by the scan itself. The defence is
  upstream of the cache (rate limits, authentication), and I have not built it.
- **The fence and Redis restart.** Fail-closed liveness loss, above. At 100x
  the number of restarts is what matters, and a counter that lives in a cache
  is the wrong place for a value the database must trust.
- **Single-instance locking.** A failover that loses an acknowledged write gives
  two holders. The fence is the only defence, which is the argument for having
  it, and for the token to come from the store that checks it.
- **Delayed second delete.** The delay is a guess about the slowest read. At 100x
  the load, read latency has a longer tail, and the guess is wrong more often.
  The TTL is the bound.
- **Jitter.** At 100x the keys, the jitter window has 100x more keys in it and
  the peak per step rises with them. Jitter divides the spike; it does not
  remove it. The peak of 148 above is per 1,000 keys.
- **What I did not measure.** A Redis Cluster, a failover, Redlock, a filter
  kept in step with inserts, the cost of the second delete under load, real GC
  pauses (`SIGSTOP` was used instead), and Redisson's `RFencedLock`.

## The oral check: a cache inconsistency, on a timeline

The question: "a client sees 41.26 after the price changed to 42.00. Who ran
first, and second?" The answer has a time axis, not a "might":

1. **R** (a reader on instance B) misses in L1 and L2 and starts loading the
   price from the source.
2. **W** (a writer) updates the store to 42.00.
3. **W** deletes the L2 entry and publishes the invalidation.
4. **R** finishes its load, which began before step 2 and so holds 41.26, and
   writes it into L2 and its own L1.
5. Nothing evicts it. It is served until the L1 TTL (5 s) and, from L2 to the
   next instance, until the L2 TTL (30 s): 35 s at most, 42 s with jitter.

The invalidation in step 3 arrived before R's write, so it evicted nothing. That
is order A, which I chose over B because its window needs the reader to be
slower than the whole write. The delayed second delete would repair it if it ran
after step 4, and would not if R were slower than the delay. What holds the line
is the TTL, and the number is on the page.

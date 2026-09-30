# Lab 09 — caching, compared and broken on purpose

Self-contained. Not a Maven module, not listed in the parent's `<modules>`;
`./mvnw verify` at the repository root does not build it. It is the only place
that takes Redisson, Guava and protobuf-java. Redis comes from Testcontainers,
so Docker must be running. The lock experiment shells out to `kill`, so it is
Linux only.

Build once, with the JDK `.sdkmanrc` pins (`sdk env`):

```
./mvnw -f labs/09-caching/pom.xml package
```

Every lab prints the JVM and CPU it ran on first. Evidence goes to
`labs/09-caching/target/evidence/` and is copied to `docs/evidence/` by hand.

| Exercise | Command (from `labs/09-caching`) | Evidence |
|---|---|---|
| A lock holder that is stopped, and one that is only slow: hand-written lock, fencing token, Redisson's watchdog. Child JVM stopped with `SIGSTOP` (~90 s) | `java -cp target/labs.jar labs.caching.LockStopSignal` | `09-watchdog.txt` |
| Bloom filter against a negative cache | `java -cp target/labs.jar labs.caching.BloomVsNegativeCache` | `09-bloom-vs-negative.txt` |
| Protobuf against JSON for one price, and the `double` field | `java -cp target/labs.jar labs.caching.ProtobufVsJson` | `09-protobuf-vs-json.txt` |
| An unbounded Caffeine cache until the heap ends (stage 15's starting point) | `java -Xmx64m -cp target/labs.jar labs.caching.UnboundedHeap` | `09-unbounded-heap.txt` |

Add `-XX:+HeapDumpOnOutOfMemoryError` to the last one for a dump. Never
commit the `.hprof`: reference it from the decision record.

Decision record: [`docs/decisions/09-caching.md`](../../docs/decisions/09-caching.md).

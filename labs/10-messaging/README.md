# Lab 10 — a consumer group losing a member

Self-contained. Not a Maven module, not listed in the parent's `<modules>`;
`./mvnw verify` at the repository root does not build it. Kafka comes from
Testcontainers, so Docker must be running. The consumers are separate JVMs so
that a kill is a kill, and the stop is `SIGKILL`: Linux only.

Build once, with the JDK `.sdkmanrc` pins (`sdk env`):

```
./mvnw -f labs/10-messaging/pom.xml package
```

| Exercise | Command (from `labs/10-messaging`) | Evidence |
|---|---|---|
| Three consumers on six partitions, one removed by SIGTERM and by SIGKILL, under the eager (range) and the cooperative-sticky assignor. About five minutes | `java -cp target/labs.jar labs.messaging.RebalanceGap` | `10-rebalance.txt` |

Every run prints the JVM and CPU it ran on first. Evidence goes to
`labs/10-messaging/target/evidence/` and is copied to `docs/evidence/` by hand.

`session.timeout.ms` is set to 10 s for the run, not Kafka's default: the
number the lab reports for a crash is that timeout plus the rebalance, and the
decision record states the production default separately and says it was not
measured.

Decision record: [`docs/decisions/10-messaging.md`](../../docs/decisions/10-messaging.md).

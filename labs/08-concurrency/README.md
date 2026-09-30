# Lab 08 — concurrency, broken on purpose

Self-contained. Not a Maven module, not listed in the parent's `<modules>`;
`./mvnw verify` at the repository root does not build it. `domain` is compiled
in from source, so nothing is installed into `~/.m2`.

Build once, with the JDK `.sdkmanrc` pins (`sdk env`):

```
./mvnw -f labs/08-concurrency/pom.xml package
```

Every lab prints the JVM and CPU it ran on first. Results are what the
machine did; a failure that did not occur is reported as "0 of N".

| Exercise | Command | Evidence |
|---|---|---|
| JMM visibility | `java -cp labs/08-concurrency/target/labs.jar labs.concurrency.Visibility` | `08-visibility.txt` |
| `volatile` is not atomic | `java -cp … labs.concurrency.LostUpdate` | `08-lost-update.txt` |
| Double-checked locking | `java -cp … labs.concurrency.DoubleCheckedLocking` | `08-dcl.txt` |
| `computeIfAbsent` re-entry | `java -cp … labs.concurrency.RecursiveComputeIfAbsent` | `08-compute-if-absent.txt` |

Decision record: [`docs/decisions/08-concurrency.md`](../../docs/decisions/08-concurrency.md).

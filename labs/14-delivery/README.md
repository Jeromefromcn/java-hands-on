# Lab 14 — packaging and delivery

Self-contained. Not a Maven module; `./mvnw verify` at the repository root
does not build it. Needs Docker. Allocate.java is compiled by the script with
the JDK `.sdkmanrc` pins (`sdk env`) and run in the ledger's own base image
(`bootstrap/Dockerfile`'s `BASE`).

| Exercise | Command (from the repository root) | Evidence |
|---|---|---|
| What the JVM reads from the container (memory limit, CPUs, max heap, the collector it picks) at no limit, 512 MB, 1 GB, 1.5 CPUs; then a fixed `-Xmx` above the limit (the kernel kills it) against a percentage of the limit (the JVM throws). About two minutes | `labs/14-delivery/heap/run.sh` | `14-container-awareness.txt`, `14-oomkilled-vs-oome.txt` |

Evidence goes to `labs/14-delivery/target/evidence/` and is copied to
`docs/evidence/` by hand. See `docs/decisions/14-delivery.md`.

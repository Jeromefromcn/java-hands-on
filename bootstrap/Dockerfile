# Stage 14: the ledger's image, built from the repackaged jar (./mvnw package) with the layers that
# bootstrap/src/main/layers/layers.xml declares. Build context: bootstrap/target.
#
#   infra/delivery/image.sh            # package, then build, tagged by commit
#
# One COPY per layer, in the order of layers.idx, so a layer that did not change is a layer the
# registry and the node already have. See docs/decisions/14-delivery.md.

# Temurin 21 JRE (the JDK .sdkmanrc pins), Ubuntu 24.04. Pinned by the multi-arch index digest: the
# tag moves when Adoptium respins it, the digest does not.
ARG BASE=eclipse-temurin:21.0.12.1_1-jre-noble@sha256:35501d3f93173b420ac9001658eb68157e813316b865087e345c187fca422c4c

FROM ${BASE} AS layers
WORKDIR /build
COPY bootstrap-*.jar application.jar
# Plain jars in lib/ and an application.jar whose manifest names them: no nested-jar loader at run
# time, and the shape a CDS archive needs.
#
# Then every file and directory gets one fixed time. A layer's digest is the digest of a tar, and
# the tar holds modification times: the extracted directories and the rebuilt application.jar carry
# the time of this RUN, so without this a rebuild of the same jar on a runner with no cache changes
# every layer the ledger adds, the 109 MB of dependencies included (docs/evidence/14-layer-diff.txt).
# The time is the build's project.build.outputTimestamp, which the jars' entries already carry.
ARG SOURCE_DATE_EPOCH=1789862400
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted \
 && find extracted -exec touch -h -d "@${SOURCE_DATE_EPOCH}" {} +

FROM ${BASE} AS jvm
# The revision the image was built from; /actuator/info reports it.
ARG REVISION=unknown
LABEL org.opencontainers.image.source="https://github.com/Jeromefromcn/java-hands-on" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.title="sl-ledger"
WORKDIR /app
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/ledger/ ./
COPY --from=layers /build/extracted/application/ ./

# A numeric non-root user: Kubernetes' runAsNonRoot can only verify a number.
USER 10001:10001
EXPOSE 8080 8081
# The heap is a share of the container's limit, never a fixed -Xmx: the same image runs under any
# limit the orchestrator sets. 70%, not the usual 75%: the ledger's non-heap (metaspace, code cache)
# measured 205-211 MB, which 25% of a 768 MB limit does not hold. G1 is named because the JVM picks
# Serial by itself below 1792 MB, so a limit change would silently change the collector.
# ExitOnOutOfMemoryError: a JVM whose heap ran out is restarted, not left serving with dead threads.
# All measured in docs/evidence/14-container-awareness.txt and 14-ledger-memory.txt.
ENV LEDGER_REVISION=${REVISION} \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
# exec form: java is PID 1 and gets the SIGTERM, which starts Spring's graceful shutdown.
ENTRYPOINT ["java", "-jar", "application.jar"]

# Measured, not shipped: the above plus a CDS archive (AppCDS, dynamic). The training run starts
# the application context and exits once it is refreshed (spring.context.exit=onRefresh), and the
# JVM writes every class it loaded to the archive; a start then maps them instead of loading and
# verifying them again. Readiness 17.9 s -> 13.4 s (median of five,
# docs/evidence/14-startup.txt). Not the default because of what it costs the image: a 138 MB
# layer that differs on every build, the same commit built twice included
# (docs/evidence/14-layer-diff-cds.txt), so the image digest is no longer reproducible from the
# commit and every release ships that layer. `--target cds` builds it.
#
# The training runs at build time with no network: the settings below let the context refresh
# without a database (no Flyway, no schema validation, no JDBC metadata, so the dialect is named)
# and without the identity provider (the key set is not fetched during a refresh). A JVM that
# finds the archive does not match its jars or its own build ignores it and starts as `jvm` does.
FROM jvm AS cds
USER root
RUN java -XX:ArchiveClassesAtExit=/app/application.jsa -Dspring.context.exit=onRefresh \
      -Xlog:cds=off -Xlog:cds+dynamic=off -jar application.jar \
      --spring.datasource.url=jdbc:postgresql://127.0.0.1:1/training \
      --spring.flyway.enabled=false --spring.jpa.hibernate.ddl-auto=none \
      --spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
      --spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
      --spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/training \
      --ledger.security.issuer=training > /dev/null \
 && chmod a+r /app/application.jsa
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -XX:SharedArchiveFile=/app/application.jsa"

# What ships: the image without the archive. The last stage is the default target.
FROM jvm AS release

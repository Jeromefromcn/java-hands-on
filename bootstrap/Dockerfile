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

FROM ${BASE}
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
ENV LEDGER_REVISION=${REVISION} \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# exec form: java is PID 1 and gets the SIGTERM, which starts Spring's graceful shutdown.
ENTRYPOINT ["java", "-jar", "application.jar"]

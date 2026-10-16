# ScholarChain services image. Owner: build-engineer.
# Built locally by `sbt dockerLocal`, which stages services.jar (the services assembly) next to this
# file in modules/services/target/docker-context and passes JAVA_RUNTIME_IMAGE from the version
# ledger (project/Versions.scala). The default below must equal the ledger value; build.sbt fails
# at load time if it drifts.
#
# Policy: pinned Java runtime (Amazon Corretto 17, matching EMR 7.x's default Java), pinned by tag
# and digest; non-root numeric user; JVM heap sized as a percentage of the container memory limit,
# which the task definition sets. No secrets, endpoints, or account data are baked in.

ARG JAVA_RUNTIME_IMAGE=amazoncorretto:17.0.20-al2023-headless@sha256:f0360fa5e8b6bd5cf85443c1425705de9b55928068a8045f5093ed0b6f9661d9
FROM ${JAVA_RUNTIME_IMAGE}

WORKDIR /opt/scholarchain

# Root-owned and world-readable: the runtime user can read the application but not modify it.
COPY services.jar /opt/scholarchain/services.jar

# Numeric UID/GID so the image needs no user-management tools; 10001 is outside system ranges.
USER 10001:10001

# Percentage-based memory: heap starts at 50% and is capped at 75% of the container limit,
# leaving headroom for metaspace, threads, and direct buffers. Exit on OOM so the orchestrator
# replaces the task instead of leaving a degraded JVM running.
ENTRYPOINT ["java", "-XX:InitialRAMPercentage=50.0", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/opt/scholarchain/services.jar"]

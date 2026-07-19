# syntax=docker/dockerfile:1

# =============================================================================
# CardDemo Walking Skeleton — account-svc production image (multi-stage)
# -----------------------------------------------------------------------------
# Stage 1 (build):   compile + repackage the Spring Boot 3.5 / Java 21 fat JAR
#                    with Maven on Eclipse Temurin 21 (JDK), running OpenAPI
#                    codegen from the frozen contract.
# Stage 2 (runtime): run the fat JAR on the lean Eclipse Temurin 21 (JRE),
#                    as a non-root user, with curl for the compose healthcheck.
#
# Service identity: account-svc — typed-stub Account bounded context.
#   Provenance [SRC: COACTVWC/COACTUPC | ACCTDAT] — legacy Account View
#   (CAVW -> COACTVWC) and Account Update (CAUP -> COACTUPC) over the ACCTDAT
#   VSAM KSDS (app/csd/CARDDEMO.CSD). All account operations are [DEFERRED]
#   typed placeholders; this image is a request-serving, health-gated,
#   long-running container in the docker-compose topology.
#
# Referenced by docker-compose.yml as service `account-svc`:
#   build:
#     context: ./services/account-svc
#     dockerfile: Dockerfile
#
# CRITICAL coordination facts baked into this file:
#   * Container port is 8080. docker-compose injects SERVER_PORT=8080 and maps
#     host ${ACCOUNT_SVC_PORT:-8082}:8080. The server port is NEVER hard-coded
#     here — Spring relaxed binding maps SERVER_PORT -> server.port
#     (application.yml default is ${SERVER_PORT:8080}).
#   * The compose healthcheck runs `curl -f http://localhost:8080/actuator/health`.
#     The eclipse-temurin:21-jre base image does NOT ship curl, so this runtime
#     stage installs it explicitly. Omitting curl would prevent the container
#     from ever reporting healthy and would stall the whole `service_healthy`
#     dependency chain (Oracle -> Flyway -> request-serving services).
#   * Datasource credentials are NEVER baked in. docker-compose injects
#     SPRING_DATASOURCE_URL / _USERNAME / _PASSWORD, SPRING_FLYWAY_ENABLED=false,
#     CORRELATION_HEADER and SPRING_PROFILES_ACTIVE at runtime.
#
# OpenAPI build-context constraint (READ CAREFULLY):
#   The build context is `./services/account-svc` (the service directory), so
#   the frozen contract under /contracts is OUTSIDE this context and cannot be
#   COPY-ed directly. The pom.xml `openapi-generator` reads ${openapi.spec}
#   (default ../../contracts/account-svc.openapi.yaml) and the property is
#   overridable precisely so the container build can repoint it to an in-context
#   copy. This Dockerfile therefore expects a BUILD-TIME copy of the frozen spec
#   at the service root (`account-svc.openapi.yaml`), placed by the CI/compose
#   pre-step, and overrides `-Dopenapi.spec=/build/contracts/account-svc.openapi.yaml`.
#   The copy is a transient build artifact ONLY — the single source of truth
#   remains /contracts/account-svc.openapi.yaml and is never duplicated in git.
#   (Integration note: alternatively set the compose build context to the repo
#   root — `context: .`, `dockerfile: services/account-svc/Dockerfile` — and
#   COPY contracts/account-svc.openapi.yaml directly; the pre-step approach keeps
#   the per-service context convention used across the topology, e.g. ui/Dockerfile.)
# =============================================================================

# ---------- Stage 1: build the fat JAR (JDK 21 + Maven) ----------
# maven:3.9-eclipse-temurin-21 == Eclipse Temurin 21 (JDK) + Apache Maven 3.9,
# satisfying the "temurin 21-jdk build stage" intent while providing Maven
# without a separate install step. Tag pins Maven 3.9.x and JDK major 21.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the POM first so the dependency-resolution layer is cached independently
# of source churn. (The .m2 cache mount on the package step below does the heavy
# lifting for offline-friendly rebuilds.)
COPY pom.xml .

# In-context copy of the frozen OpenAPI 3.1 contract (build-time artifact placed
# by the CI/compose pre-step — see header). Landing it under /build/contracts
# mirrors the repo layout the pom expects and is targeted via -Dopenapi.spec.
COPY account-svc.openapi.yaml /build/contracts/account-svc.openapi.yaml

# Application sources (explicit COPY — there is no .dockerignore in scope, so an
# explicit path deterministically avoids copying any host target/ into the image).
COPY src ./src

# Build the runnable Spring Boot fat JAR:
#   * generate-sources -> OpenAPI server interfaces + model DTOs from the spec
#     resolved via -Dopenapi.spec (interfaceOnly=true; controllers are hand-written).
#   * -DskipTests: the context-load/health smoke test needs a provisioned Oracle
#     (gvenzl) which is unavailable during an image build; it runs in the Maven/CI
#     layer instead. The image only needs a runnable artifact.
#   * --mount=type=cache keeps the local Maven repository across builds (BuildKit)
#     without bloating the final image; deps download from Maven Central on first run.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -Dopenapi.spec=/build/contracts/account-svc.openapi.yaml -DskipTests clean package

# ---------- Stage 2: lean runtime (JRE 21, non-root, curl) ----------
FROM eclipse-temurin:21-jre AS runtime

# Install curl for the docker-compose healthcheck (see header — non-negotiable).
# Temurin JRE images are Ubuntu-based; clean apt lists to keep the layer small.
# curl is intentionally NOT apt-version-pinned: the base image is pinned by tag
# (eclipse-temurin:21-jre) but its Ubuntu package index rolls forward, so an exact
# `curl=<version>` pin would break the build whenever the base image refreshes.
# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as a dedicated non-root user and own the (writable) app directory.
RUN useradd --system --uid 1001 --shell /usr/sbin/nologin spring \
    && mkdir -p /app \
    && chown spring:spring /app
WORKDIR /app

# Copy ONLY the repackaged fat JAR from the build stage — the JDK, Maven and all
# build tooling stay behind, keeping the runtime image lean. The account-svc-*.jar
# glob matches the single repackaged artifact (the .jar.original thin jar is excluded).
COPY --chown=spring:spring --from=build /build/target/account-svc-*.jar /app/app.jar

USER spring

# Container-internal port; docker-compose maps ${ACCOUNT_SVC_PORT:-8082}:8080.
EXPOSE 8080

# Self-describing health probe mirroring the docker-compose healthcheck. The
# compose-level healthcheck takes precedence at orchestration time; this makes
# the image healthy-aware when run standalone. start-period allows for JVM +
# Spring Boot startup and for Oracle to become reachable (compose-gated).
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Launch the service. The port is NOT hard-coded: compose injects SERVER_PORT=8080
# and Spring relaxed binding applies (application.yml default ${SERVER_PORT:8080}).
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

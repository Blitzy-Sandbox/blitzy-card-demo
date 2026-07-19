# syntax=docker/dockerfile:1

# =============================================================================
# CardDemo Walking Skeleton — bff (backend-for-frontend) production image.
# Multi-stage build: Maven/Temurin JDK 21 build  ->  Temurin JRE 21 runtime.
# -----------------------------------------------------------------------------
# Stage 1 (build):   OpenAPI codegen + compile + repackage the Spring Boot 3.5 /
#                    Java 21 executable fat JAR with Maven on Eclipse Temurin 21.
# Stage 2 (runtime): run the fat JAR on the lean Eclipse Temurin 21 JRE, as a
#                    non-root user, with curl for the compose healthcheck.
#
# Service identity: bff — the thin aggregation service and the SINGLE backend the
# React 19 SPA binds to (AAP 0.1.1 / 0.8: "the UI binds ONLY to the BFF"). It
# fronts the one live vertical tracer slice, the Credit Card View path — UI ->
# bff GET /api/cards/{cardNumber} -> card-svc GET /cards/{cardNumber} -> a real
# Oracle read of one Flyway-seeded row. Provenance [SRC: CARDDEMO.CSD] (service
# topology derives from the legacy CICS transaction/program/file registrations).
#
# The bff is a REQUEST-SERVING, HEALTH-GATED, LONG-RUNNING container (AAP 0.1.2):
# it must build green, start green, and expose Spring Boot Actuator
# /actuator/health. In docker-compose the `ui` service depends on `bff` reporting
# healthy (`depends_on: condition: service_healthy`).
#
# Referenced by docker-compose.yml as service `bff`:
#   build:
#     context: ./services/bff        # the SERVICE directory (NOT the repo root)
#     dockerfile: Dockerfile
#
# -----------------------------------------------------------------------------
# *** CRITICAL — BUILD CONTEXT IS ./services/bff, AND THE CONTRACT IS VENDORED ***
# -----------------------------------------------------------------------------
# Unlike the domain services that read ../../contracts/<svc>.openapi.yaml, the
# bff pom.xml points its openapi-generator at a VENDORED, byte-identical copy of
# the frozen contract that lives INSIDE this module:
#     <inputSpec>${project.basedir}/src/main/resources/openapi/bff.openapi.yaml
# That path is inside `src/`, which IS in this build context, so OpenAPI codegen
# runs fully self-contained INSIDE the Docker build (generate-sources ->
# com.carddemo.bff.api.* interfaces + com.carddemo.bff.model.* DTOs). There is
# therefore NO need — and NO ability — to COPY the repo-root /contracts folder:
# it is OUTSIDE this narrower context. Do NOT add `COPY ../../contracts` (it
# would escape the context and break `docker compose build bff`).
#
# -----------------------------------------------------------------------------
# Coordinated runtime facts (MUST match docker-compose + application.yml)
# -----------------------------------------------------------------------------
#   * Container-internal port is 8080 (EXPOSE 8080). application.yml defaults
#     server.port=${SERVER_PORT:8080}; compose injects SERVER_PORT=8080 and maps
#     the host ${BFF_PORT:-8080}:8080. The internal port is ALWAYS 8080.
#   * The compose healthcheck runs `curl -f http://localhost:8080/actuator/health`.
#     eclipse-temurin:21-jre is Ubuntu-based and ships NO curl, so this runtime
#     stage installs it explicitly — REQUIRED, not optional. Omitting curl would
#     leave the container permanently unhealthy and stall the whole
#     `service_healthy` chain (the `ui` service would never start).
#   * All runtime configuration is injected by compose/.env at RUN time and is
#     NEVER baked into the image: SERVER_PORT, SPRING_PROFILES_ACTIVE=docker,
#     CARD_SVC_URL, AUTH_SVC_URL, CORRELATION_HEADER, and the (harmlessly ignored,
#     because the bff has no JDBC/JPA on its classpath) SPRING_DATASOURCE_* and
#     SPRING_FLYWAY_ENABLED. No domain logic and no persistence live in this image.
#
# Lean & reproducible: explicit image tags (never `latest`), --no-install-recommends,
# apt lists cleaned in-layer, layer-cached dependency resolution, non-root runtime.
# =============================================================================


# ---------- Stage 1: build the Spring Boot fat JAR (Temurin JDK 21 + Maven) ----------
# maven:3.9-eclipse-temurin-21 == Eclipse Temurin 21 (JDK) + Apache Maven 3.9. It
# satisfies the "Temurin JDK 21 build toolchain" intent while providing Maven
# without a separate install step — there is deliberately NO committed Maven
# wrapper (mvnw), and the plain eclipse-temurin:21-jdk image has NO Maven.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 1) Copy ONLY the POM first, then warm the dependency cache. Keying this layer
#    on the POM alone means dependencies are re-resolved only when pom.xml
#    changes, not on ordinary source edits. The bff inherits directly from
#    spring-boot-starter-parent (no repo parent module), so this is self-contained.
#    `|| true` keeps the step NON-FATAL: `dependency:go-offline` cannot always
#    pre-resolve every build-time plugin transitive (notably the openapi-generator
#    plugin realm), and that is fine — the subsequent ONLINE `mvn package` fetches
#    anything still missing, so a partial go-offline must never fail the build.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline || true

# 2) Copy the sources LAST (the most frequently changing input). This includes
#    the VENDORED OpenAPI spec at src/main/resources/openapi/bff.openapi.yaml that
#    the openapi-generator plugin consumes at generate-sources time — codegen runs
#    INSIDE this build, against the in-context spec (see the header note).
COPY src ./src

# 3) generate-sources (OpenAPI codegen) -> compile -> repackage the executable
#    Spring Boot fat JAR (target/bff-0.1.0-SNAPSHOT.jar).
#    -DskipTests: keep image builds deterministic and fast. The context-load
#    smoke test runs in the CI Maven reactor, not during the image build (the bff
#    context loads with no external DB/card-svc, so it would also pass here — but
#    skipping is the deterministic default for image builds).
RUN mvn -B -q -DskipTests clean package


# ---------- Stage 2: minimal runtime (Temurin JRE 21 + curl for healthcheck) ----------
# The build stage above (JDK, Maven, ~/.m2, target/) is discarded; only this
# stage's layers ship in the final image.
FROM eclipse-temurin:21-jre AS runtime

# curl is REQUIRED by both the docker-compose healthcheck and the image-level
# HEALTHCHECK below (`curl -f http://localhost:8080/actuator/health`). The base
# JRE image is Ubuntu-based and does NOT ship curl. --no-install-recommends keeps
# the layer minimal; the apt lists are removed in the SAME layer so they never
# persist in the image. curl is intentionally NOT apt-version-pinned: the base
# image is pinned by tag but its Ubuntu package index rolls forward, so an exact
# `curl=<version>` pin would break the build whenever the base image refreshes.
# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Run as a dedicated, unprivileged system user (defense-in-depth; never root).
# Primary group `root` (gid 0) so the COPYed jar (owner root, mode 644) stays
# readable and /app (mode 755) remains traversable — `java -jar` needs only read
# access to the artifact.
RUN useradd -r -u 1001 -g root spring
USER 1001

# Copy ONLY the repackaged fat JAR from the build stage. The glob `bff-*.jar`
# tolerates the version in the artifact name (bff-0.1.0-SNAPSHOT.jar) and matches
# exactly that single repackaged executable jar — the non-runnable thin jar
# `bff-0.1.0-SNAPSHOT.jar.original` does NOT match `*.jar`, so this single-file
# COPY to /app/app.jar is unambiguous. (COPY runs as root and places the file
# root:root; readable by the gid-0 runtime user above.)
COPY --from=build /build/target/bff-*.jar app.jar

# Container-internal port (Spring Boot default). docker-compose maps the host
# ${BFF_PORT:-8080}:8080; the internal port is always 8080.
EXPOSE 8080

# Image-level health probe mirroring the compose healthcheck, so `docker run` and
# `docker compose` behave identically. Under compose the compose-level healthcheck
# drives the `service_healthy` gate. The generous --start-period accommodates JVM
# + Spring context warm-up; --interval/--retries match a service that gates the ui.
HEALTHCHECK --interval=15s --timeout=5s --retries=10 --start-period=60s \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Launch the Spring Boot fat jar. Datasource / profile / port / correlation /
# downstream-URL env are injected by compose/.env at run time — intentionally NOT
# baked into the image.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

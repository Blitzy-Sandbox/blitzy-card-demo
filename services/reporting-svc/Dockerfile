# syntax=docker/dockerfile:1

# =============================================================================
# CardDemo Walking Skeleton — reporting-svc image (multi-stage)
# Provenance: [SRC: CORPT00C, CBSTM03A/B | TRANSACT]
# -----------------------------------------------------------------------------
# Stage 1 (build):   Eclipse Temurin 21 JDK + Maven — run OpenAPI codegen,
#                    compile, and repackage the Spring Boot fat JAR.
# Stage 2 (runtime): Eclipse Temurin 21 JRE — run the fat JAR as a non-root
#                    user. This is the invokable job-stub entrypoint.
#
# ASYNC JOB STUB — HEALTH-EXEMPT (this service differs from the other seven):
#   reporting-svc MUST build green and expose an invokable job entrypoint, but it
#   is deliberately NOT held to start-and-serve, is NOT part of the docker-compose
#   `depends_on: condition: service_healthy` chain, and publishes NO host port.
#   Consequently this image intentionally declares:
#     * NO HEALTHCHECK instruction    (nothing to probe; health-exempt)
#     * NO EXPOSE / no published port  (never reached over a fixed host port)
#   These omissions are BY DESIGN — do NOT "fix" them by adding a healthcheck or
#   publishing a port. See AAP 0.1.2 / 0.4 / 0.8 and the frozen contract
#   contracts/reporting-svc.openapi.yaml, which defines no /actuator/health path.
#
# BUILD CONTEXT MUST BE THE REPOSITORY ROOT:
#   docker build -f services/reporting-svc/Dockerfile -t carddemo/reporting-svc:local .
#
#   The in-image Maven build runs the openapi-generator-maven-plugin, whose
#   inputSpec is `${project.basedir}/../../contracts/reporting-svc.openapi.yaml` —
#   the frozen OpenAPI single source of truth that lives in the repo-root
#   /contracts folder, OUTSIDE services/reporting-svc/. A service-scoped context
#   (./services/reporting-svc) does NOT contain /contracts and would break codegen,
#   so the build context must be the repo root and this file COPYs both trees.
#
#   NOTE for docker-compose (platform-owned; not edited here): the `reporting-svc`
#   service build MUST therefore use the repo root as its context —
#     build:
#       context: .                                    # repo root (NOT ./services/reporting-svc)
#       dockerfile: services/reporting-svc/Dockerfile
# =============================================================================


# ---------- Stage 1: build (OpenAPI codegen + compile + package) ----------
# maven:3.9-eclipse-temurin-21 == Eclipse Temurin 21 JDK + Maven 3.9. This
# satisfies the "temurin 21-jdk" build-stage lineage while providing Maven
# without requiring a committed mvnw wrapper.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Frozen OpenAPI SSoT. Placed at /workspace/contracts so that, from the module
# directory /workspace/services/reporting-svc, the pom's relative inputSpec
# `../../contracts/reporting-svc.openapi.yaml` resolves correctly. This mirrors
# the repository layout (contracts/ as a sibling of services/reporting-svc/).
COPY contracts/reporting-svc.openapi.yaml ./contracts/reporting-svc.openapi.yaml

# Module build descriptor. Copied before the sources so the dependency warm-up
# layer below is reused whenever only source files change.
COPY services/reporting-svc/pom.xml ./services/reporting-svc/pom.xml

WORKDIR /workspace/services/reporting-svc

# Warm the local Maven repository (plugins + dependencies) in a cache-friendly
# layer. Resilient (`|| true`): the authoritative resolution happens in the
# `package` step below, which still has network access, so a partial go-offline
# never fails the build. The reporting-svc pom uses an empty <relativePath/>, so
# its spring-boot-starter-parent is fetched from Maven Central — no root pom is
# copied or required.
RUN mvn -B -q -e -DskipTests dependency:go-offline || true

# Module sources (entrypoint, correlation-id filter, OpenAPI config anchor,
# application.yml). Copied last so edits here do not invalidate the warm-up layer.
COPY services/reporting-svc/src ./src

# Build the executable Spring Boot fat JAR:
#   generate-sources -> openapi-generator emits ReportsApi/StatementsApi + models
#                       from ../../contracts/reporting-svc.openapi.yaml
#   compile          -> generated interfaces + hand-written beans
#   package          -> spring-boot-maven-plugin repackage
#                       -> target/reporting-svc-<version>.jar
# Tests are skipped in the image build (the primary build-green gate is CI); the
# context-load smoke test needs no DB and runs in the Maven/CI reactor instead.
RUN mvn -B -q -e -DskipTests clean package


# ---------- Stage 2: runtime (JRE — invokable job-stub entrypoint) ----------
# eclipse-temurin:21-jre is the slim runtime half of the multi-stage lineage
# (Temurin 21 JDK build -> Temurin 21 JRE runtime).
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as a dedicated, non-root system user for good container hygiene.
RUN groupadd --system carddemo \
    && useradd --system --gid carddemo --home /app carddemo

# Copy only the repackaged Spring Boot fat JAR from the build stage and normalize
# its name to /app/app.jar. The wildcard keeps this version-agnostic: it matches
# reporting-svc-<version>.jar but NOT the thin reporting-svc-<version>.jar.original
# emitted alongside it. Ownership is assigned to the non-root runtime user.
COPY --from=build --chown=carddemo:carddemo \
     /workspace/services/reporting-svc/target/reporting-svc-*.jar /app/app.jar

USER carddemo

# reporting-svc is an ASYNC JOB STUB: health-exempt, publishes NO host port, and
# is NOT in the docker-compose service_healthy chain. NO HEALTHCHECK and NO EXPOSE
# are declared on purpose (see the header block). The entrypoint launches the
# Spring Boot application, which is the invokable job-stub entrypoint: the image
# builds green and is invokable, but it is not required to serve HTTP or to stay
# up under a health gate. Datasource credentials are injected at runtime via
# docker-compose environment — none are baked into this image.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

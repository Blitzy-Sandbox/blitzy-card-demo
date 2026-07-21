# syntax=docker/dockerfile:1

# =============================================================================
# CardDemo Walking Skeleton — payment-svc image (multi-stage, build-from-source)
# -----------------------------------------------------------------------------
# Packages the Bill Payment bounded-context service — Spring Boot 3.5.x / Java 21,
# a BUILD-GREEN, TYPED-STUB service. Provenance: [SRC: COBIL00C | ACCTDAT] (legacy
# CICS transaction CB00 -> program COBIL00C reading the ACCTDAT VSAM dataset,
# app/csd/CARDDEMO.CSD). No live payment/business logic is added by this image;
# the service exposes the frozen OpenAPI contract and returns typed placeholders.
#
# Stage 1 (build):   JDK 21 + Maven. Runs the OpenAPI generator against the frozen
#                    contract, compiles, and repackages the bootable Spring Boot jar.
# Stage 2 (runtime): JRE 21 + curl. Long-running, health-gated container.
#
# ---------------------------------------------------------------------------
# !!! CRITICAL — BUILD CONTEXT MUST BE THE REPOSITORY ROOT !!!
# ---------------------------------------------------------------------------
# The service's Maven build generates its API interfaces + DTO models from the
# FROZEN, single-source-of-truth contract at repo-root
# `contracts/payment-svc.openapi.yaml`. The POM references it by relative path
# (`${project.basedir}/../../contracts/payment-svc.openapi.yaml`). Contracts are
# NEVER duplicated into a service, so both `services/payment-svc/**` and
# `contracts/**` must be visible to the build. Therefore every COPY source path
# below is relative to the REPO ROOT, and this image MUST be built from the repo
# root:
#
#     docker build -f services/payment-svc/Dockerfile -t carddemo/payment-svc .
#
# The corresponding docker-compose service entry MUST use a repo-root context
# (context: .), NOT a per-service context (./services/payment-svc):
#
#     payment-svc:
#       build:
#         context: .                                  # <-- repo root, NOT ./services/payment-svc
#         dockerfile: services/payment-svc/Dockerfile
#
# >>> Cross-cutting requirement for the root docker-compose.yml author: a
# >>> from-source multi-stage build with a shared frozen contract cannot use a
# >>> per-service build context.
#
# ---------------------------------------------------------------------------
# !!! CRITICAL — curl IS INSTALLED IN THE RUNTIME STAGE ON PURPOSE !!!
# ---------------------------------------------------------------------------
# The docker-compose `service_healthy` gate probes this service with
# `CMD-SHELL curl -f http://localhost:8080/actuator/health || exit 1`. Curl
# is installed explicitly below (and apt lists cleaned) so the health probe
# never depends on whether the pinned base bundles curl. Do not remove it — the
# compose health gate
# (and the HEALTHCHECK below) depend on it.
# =============================================================================


# ---------- Stage 1: build (OpenAPI codegen + compile + repackage) ----------
# The pinned maven-on-Temurin-21 base provides a JDK 21 toolchain + Maven, satisfying the
# "JDK 21 build" requirement. The bootable jar it produces runs on the JRE 21
# runtime stage below.
# S-01 (supply-chain hardening): build base standardized across ALL 8 services and
# pinned by digest. Ubuntu 24.04 LTS (noble) + Temurin 21 JDK + Maven 3.9.9. Earlier
# the services mixed floating maven:3.9(.9)-eclipse-temurin-21 tags; the digest makes
# builds reproducible. This stage is discarded by the multi-stage build and ships
# nothing into the runtime image.
FROM maven:3.9.9-eclipse-temurin-21-noble@sha256:2fa8b35172589aafa259fe953f4829859169bb0516f28f2b5a027c7c52017bee AS build

WORKDIR /build

# Bring in the frozen contract FIRST. The generator reads
# contracts/payment-svc.openapi.yaml; copying the whole `contracts/` dir is the
# simplest and safe choice (only payment-svc.openapi.yaml is strictly required).
COPY contracts/ ./contracts/

# Reproduce the repo-relative module layout under /build so the POM's
# `../../contracts/payment-svc.openapi.yaml` resolves to /build/contracts/... .
# The POM (parent = spring-boot-starter-parent with an empty <relativePath/>) is
# independently buildable and does NOT require the repo-root aggregator pom.
COPY services/payment-svc/pom.xml ./services/payment-svc/pom.xml
COPY services/payment-svc/src ./services/payment-svc/src

WORKDIR /build/services/payment-svc

# Compile + repackage. This single command:
#   1. runs the openapi-generator-maven-plugin `generate` goal (generate-sources
#      phase) -> emits com.carddemo.payment.api.* interfaces + com.carddemo.payment.model.*,
#   2. compiles the generated + hand-written sources,
#   3. repackages the bootable (layered) fat jar via spring-boot-maven-plugin.
# `-DskipTests` is REQUIRED: the context-load/smoke test needs a live Oracle DB,
# which is not present during an image build (tests run in CI with Oracle
# provisioned). `-B` = batch/non-interactive.
RUN mvn -B -DskipTests clean package


# ---------- Stage 2: runtime (JRE 21, health-ready, non-root) ----------
# S-01 (supply-chain hardening): runtime base pinned by digest to
# eclipse-temurin:21-jre-noble (Ubuntu 24.04 LTS). The floating :21-jre tag had
# drifted to an Ubuntu 26.04 build bundling an unused /usr/bin/pebble Go binary with
# 5 HIGH CVEs; the -noble line ships no pebble. The digest freezes the scanned image.
FROM eclipse-temurin:21-jre-noble@sha256:373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64 AS runtime

# curl -- used by the image-level HEALTHCHECK below and the docker-compose
# `service_healthy` probe (`curl -f http://localhost:8080/actuator/health`) -- is
# ALREADY provided by the eclipse-temurin:21-jre-noble base image, so NO package
# install is needed. Dropping the redundant `apt-get install curl` removes a
# build-time network dependency and the Hadolint DL3008 unpinned-package finding
# (P7-DOCKER-HADOLINT); the curl-based health probes keep working unchanged.

# Run as an unprivileged system user (defence in depth). uid 1001 is stable and
# predictable for volume/permission reasoning. A home dir is created so the JVM
# has a writable user.home if it ever needs one.
RUN useradd --system --create-home --uid 1001 spring

WORKDIR /app

# Copy the single bootable jar from the build stage under a stable name. The glob
# matches the artifact payment-svc-0.1.0-SNAPSHOT.jar (artifactId `payment-svc`,
# version `0.1.0-SNAPSHOT` from pom.xml). --chown makes it owned/readable by the
# non-root runtime user.
COPY --from=build --chown=spring:spring \
     /build/services/payment-svc/target/payment-svc-*.jar /app/app.jar

USER spring

# Container-internal HTTP port (Spring Boot server.port defaults to 8080). This is
# documentation only; docker-compose maps host 8085 -> container 8080.
EXPOSE 8080

# Mirror the compose healthcheck so standalone `docker run` is health-aware too.
# start-period gives the JVM + Spring context time to come up before failures
# count; retries/interval tolerate a briefly-unavailable datasource during the
# Oracle -> Flyway -> service start-up chain.
HEALTHCHECK --interval=15s --timeout=5s --retries=10 --start-period=60s \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Launch the service. SERVER_PORT, SPRING_DATASOURCE_* and friends are supplied by
# the environment (docker-compose / .env) and application.yml defaults — never
# hardcoded here.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

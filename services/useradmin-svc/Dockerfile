# syntax=docker/dockerfile:1

# =============================================================================
# CardDemo Walking Skeleton — useradmin-svc service image (multi-stage)
# -----------------------------------------------------------------------------
# Bounded context : User Administration + Admin Menu (typed stub, [DEFERRED]).
# Provenance      : [SRC: COUSR00C-03C, COADM01C | USRSEC]
#                     CU00 -> COUSR00C (User List)    CU01 -> COUSR01C (User Add)
#                     CU02 -> COUSR02C (User Update)  CU03 -> COUSR03C (User Delete)
#                     CA00 -> COADM01C (Admin Menu)   over the USRSEC VSAM KSDS.
#
# Stage 1 (build) : Temurin 21 JDK + Maven -> Spring Boot 3.5.x fat JAR. The
#                   Maven build runs the OpenAPI generator against the frozen
#                   SSoT contract and repackages useradmin-svc-0.1.0-SNAPSHOT.jar.
# Stage 2 (run)   : slim Temurin 21 JRE -> long-running, health-gated container
#                   that serves Actuator /actuator/health on port 8080 (host port
#                   ${USERADMIN_SVC_PORT}, default 8086, mapped by docker-compose).
#
# =============================================================================
# ★★ BUILD CONTEXT = REPOSITORY ROOT (needs /contracts for OpenAPI codegen) ★★
# -----------------------------------------------------------------------------
# The module pom drives contract-first code generation whose input spec is the
# repo-relative path ${project.basedir}/../../contracts/useradmin-svc.openapi.yaml
# — a file that lives OUTSIDE this service directory. Docker cannot COPY files
# from outside the build context, so this image MUST be built with the
# repository ROOT as the context. This Dockerfile therefore replicates the repo
# layout inside the image (services/useradmin-svc/** + contracts/**) so that the
# pom's relative inputSpec resolves during the in-image Maven build.
#
#   Intended invocation (context "." = repo root):
#     docker build -f services/useradmin-svc/Dockerfile \
#                  -t carddemo/useradmin-svc:local .
#
#   Intended docker-compose usage:
#     useradmin-svc:
#       build:
#         context: .                                   # repo root (NOT ./services/useradmin-svc)
#         dockerfile: services/useradmin-svc/Dockerfile
#
#   COORDINATION NOTE: a root docker-compose.yml that sets
#   `build.context: ./services/<svc>` CANNOT see /contracts and WILL break the
#   OpenAPI codegen for this service. This image REQUIRES the repo-root context
#   shown above (`context: .`, `dockerfile: services/useradmin-svc/Dockerfile`).
#
#   FALLBACK (documented only — NOT implemented here; repo-root context is
#   preferred to avoid drift from the frozen SSoT contract): if the compose
#   author insists on keeping `context: ./services/useradmin-svc`, vendor a copy
#   of contracts/useradmin-svc.openapi.yaml into the service build context (e.g.
#   a pre-build step copies it under services/useradmin-svc/) and repoint the pom
#   inputSpec at the vendored copy. Do this only as a last resort.
#
# SECURITY: no secrets or datasource credentials are baked into this image.
# docker-compose injects SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME,
# SPRING_DATASOURCE_PASSWORD, SPRING_FLYWAY_ENABLED, SPRING_PROFILES_ACTIVE,
# CORRELATION_HEADER and SERVER_PORT at runtime (Spring relaxed binding).
# =============================================================================


# ---------- Stage 1: build the Spring Boot fat JAR (runs OpenAPI codegen) ----------
# Temurin 21 JDK with Maven preinstalled — honors the "temurin 21-jdk" build
# stage without needing a committed Maven wrapper. The base is digest-pinned
# (see the S-01 note directly below) for reproducible builds.
# S-01 (supply-chain hardening): build base standardized across ALL 8 services and
# pinned by digest. Ubuntu 24.04 LTS (noble) + Temurin 21 JDK + Maven 3.9.9. Earlier
# the services mixed floating maven:3.9(.9)-eclipse-temurin-21 tags; the digest makes
# builds reproducible. This stage is discarded by the multi-stage build and ships
# nothing into the runtime image.
FROM maven:3.9.9-eclipse-temurin-21-noble@sha256:2fa8b35172589aafa259fe953f4829859169bb0516f28f2b5a027c7c52017bee AS build
WORKDIR /build

# Copy ONLY what the build needs, preserving the repo-relative layout so the
# pom's relative OpenAPI inputSpec (../../contracts/useradmin-svc.openapi.yaml)
# resolves. NEVER `COPY . .` — that bloats the image and busts layer caching.
#   1. the module build descriptor (Spring Boot parent + OpenAPI generator plugin)
COPY services/useradmin-svc/pom.xml services/useradmin-svc/pom.xml
#   2. the frozen SSoT contract the OpenAPI generator reads at generate-sources
COPY contracts/useradmin-svc.openapi.yaml contracts/useradmin-svc.openapi.yaml
#   3. the hand-written sources (entrypoint, controllers, config, application.yml)
COPY services/useradmin-svc/src services/useradmin-svc/src

# Build the module from its own directory. This triggers openapi-generator
# (reading ../../contracts/useradmin-svc.openapi.yaml), compiles the generated
# API interfaces plus the hand-written controllers, and repackages the runnable
# fat JAR at target/useradmin-svc-0.1.0-SNAPSHOT.jar. Tests are intentionally
# skipped in the image build — the CI Maven reactor runs the test suite on the
# host, not inside this container image.
WORKDIR /build/services/useradmin-svc
RUN mvn -B -DskipTests clean package


# ---------- Stage 2: slim JRE runtime ----------
# S-01 (supply-chain hardening): runtime base pinned by digest to
# eclipse-temurin:21-jre-noble (Ubuntu 24.04 LTS). The floating :21-jre tag had
# drifted to an Ubuntu 26.04 build bundling an unused /usr/bin/pebble Go binary with
# 5 HIGH CVEs; the -noble line ships no pebble. The digest freezes the scanned image.
FROM eclipse-temurin:21-jre-noble@sha256:373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64 AS runtime

# The docker-compose healthcheck calls `curl -f http://localhost:8080/actuator/health`,
# so curl is installed explicitly (independent of base drift). Drop the apt
# lists to keep the runtime layer small.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as a dedicated non-root user with a fixed UID/GID (defence in depth; a
# deterministic non-root UID also plays well with hardened orchestrators that
# enforce runAsNonRoot). The application never writes to /app at runtime (Spring
# Boot uses the system temp dir), so a read-only application directory suffices.
# A regular (non --system) account is used so the fixed UID 1001 does not trip
# the SYS_UID_MAX check; --user-group creates a matching `spring` group.
RUN useradd --uid 1001 --user-group --create-home --shell /usr/sbin/nologin spring
USER spring

WORKDIR /app

# Copy the single repackaged fat JAR from the build stage. The glob matches the
# repackaged useradmin-svc-<version>.jar; the Spring Boot plugin renames the
# plain (thin) jar to *.jar.original, which this pattern deliberately excludes,
# so exactly one file is copied to the single-file target /app/app.jar.
COPY --from=build --chown=spring:spring \
     /build/services/useradmin-svc/target/useradmin-svc-*.jar /app/app.jar

# Container listens on 8080 (matches the application.yml SERVER_PORT default and
# the SERVER_PORT=8080 injected by docker-compose). EXPOSE is documentation only;
# docker-compose maps ${USERADMIN_SVC_PORT}:8080 (default host port 8086).
EXPOSE 8080

# Optional JVM tuning knob, honored by the entrypoint; empty by default so the
# image runs with sensible container-aware JVM defaults out of the box.
ENV JAVA_OPTS=""

# Document container health intent. docker-compose overrides this with its own
# healthcheck, but declaring it here means a standalone `docker run` reports
# health too. Mirrors the compose probe: Actuator /actuator/health via curl.
# NOTE: standalone (no Oracle reachable) the DB health indicator reports DOWN
# (HTTP 503) so the container shows "unhealthy" — full green is validated via
# docker-compose with Oracle up and the Flyway migration applied.
HEALTHCHECK --interval=15s --timeout=5s --retries=10 --start-period=60s \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Launch the Spring Boot application. `exec` replaces the shell so the JVM
# becomes PID 1 and receives SIGTERM/SIGINT directly for graceful shutdown,
# while `sh -c` still expands ${JAVA_OPTS}. No Spring properties are hardcoded
# here — docker-compose injects them as environment variables at runtime.
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /app/app.jar"]

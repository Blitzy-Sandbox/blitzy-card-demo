# =============================================================================
# bff -- multi-stage, SELF-CONTAINED image (thin backend-for-frontend aggregator).
# =============================================================================
# The bff is the SINGLE backend the React SPA binds to. It fronts THE ONE LIVE
# TRACER (GET /api/cards/{cardNumber} -> card-svc), performs aggregation only (no
# domain logic), and is a REQUEST-SERVING, HEALTH-GATED, long-running container:
# it starts in the docker-compose `service_healthy` chain (ui depends on it) and
# is probed over HTTP against Spring Boot Actuator /actuator/health.
#
# -----------------------------------------------------------------------------
# *** CRITICAL -- BUILD CONTEXT MUST BE THE REPOSITORY ROOT ***
# -----------------------------------------------------------------------------
# Unlike the domain services, the bff pom.xml generates from a VENDORED copy of
# the frozen BFF contract that lives INSIDE this module at
#     services/bff/src/main/resources/openapi/bff.openapi.yaml
# (kept byte-identical to the canonical /contracts/bff.openapi.yaml SSoT; the
# in-repo vendoring is the P4-M01 fix so the Java server and the UI TS client are
# generated from the SAME spec). The bff pom therefore reads
#     ${project.basedir}/src/main/resources/openapi/bff.openapi.yaml
# and NEVER ../../contracts, so it does NOT need the /contracts tree copied in.
#
# The build context is nonetheless the REPOSITORY ROOT for UNIFORMITY with every
# other service (card/auth/account/transaction/payment/useradmin/reporting all
# use `context: . , dockerfile: services/<svc>/Dockerfile`). A CLEAN checkout /
# no-cache Compose build succeeds with NO host-built jar (finding P4-M08): the
# whole build -- codegen from the vendored spec, compile, package -- happens
# inside the image.
#
#   Correct manual build (note the trailing '.' == repo root):
#     docker build -f services/bff/Dockerfile -t carddemo/bff .
#
#   Required docker-compose wiring (kept in sync in /docker-compose.yml):
#     services:
#       bff:
#         build:
#           context: .                               # repo root, NOT ./services/bff
#           dockerfile: services/bff/Dockerfile
#
# Runtime configuration is INJECTED by compose/.env (never hardcoded here):
#   SERVER_PORT (internal stays 8080), CARD_SVC_URL / AUTH_SVC_URL / ... (internal
#   compose DNS), SPRING_PROFILES_ACTIVE=docker, CORRELATION_HEADER. The bff has
#   no JDBC/JPA on its classpath, so datasource env is harmlessly ignored.
# =============================================================================


# -----------------------------------------------------------------------------
# Phase A -- BUILD STAGE: OpenAPI codegen (vendored spec) + compile + package.
# -----------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /build

# 1) The service POM first (before src) for POM-keyed dependency layer caching.
#    (No `COPY contracts/` -- bff generates from its vendored in-module spec.)
COPY services/bff/pom.xml services/bff/pom.xml

# 2) Non-fatal offline dependency pre-resolution (see auth/card Dockerfile notes).
RUN mvn -f services/bff/pom.xml -B -q -DskipTests dependency:go-offline || true

# 3) Sources last (this INCLUDES the vendored bff.openapi.yaml the pom reads at
#    src/main/resources/openapi/bff.openapi.yaml).
COPY services/bff/src services/bff/src

# Build from the module directory so ${project.basedir} = /build/services/bff and
# the vendored inputSpec resolves.
WORKDIR /build/services/bff

# generate-sources (OpenAPI codegen from the vendored spec) -> compile -> package
# the executable fat jar. -DskipTests: no external deps needed at image-build time.
RUN mvn -B -q -DskipTests clean package


# -----------------------------------------------------------------------------
# Phase B -- RUNTIME STAGE: slim, health-checkable, JRE-only image.
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

# curl for the image-level HEALTHCHECK and the compose healthcheck (ui gates on
# bff `service_healthy`).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Dedicated non-root system user (primary group root/gid 0 so the root-owned jar
# stays readable; java -jar needs only read access).
RUN useradd -r -u 1001 -g root appuser

WORKDIR /app

# Copy ONLY the packaged fat jar. bff has no custom <finalName> (artifactId=bff),
# so the repackaged executable jar is bff-<version>.jar; the `*.jar.original` thin
# jar does NOT match the `bff-*.jar` glob.
COPY --from=build /build/services/bff/target/bff-*.jar /app/app.jar

# Drop privileges after the copy.
USER appuser

# Internal port 8080; compose maps host ${BFF_PORT:-8080}:8080 (dev overlay only).
EXPOSE 8080

# Image-level health mirrors the compose healthcheck / service_healthy gate that
# `ui` depends on.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Run the Spring Boot fat jar; runtime env injected by compose/.env.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

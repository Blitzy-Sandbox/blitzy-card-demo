# =============================================================================
# account-svc -- multi-stage, SELF-CONTAINED Docker image (typed-stub Account).
# =============================================================================
# Provenance: [SRC: COACTVWC/COACTUPC | ACCTDAT] -- legacy Account View/Update.
#
# account-svc is a REQUEST-SERVING, HEALTH-GATED, long-running container: it
# starts in the docker-compose `depends_on: condition: service_healthy` chain and
# is probed over HTTP against Spring Boot Actuator /actuator/health. All endpoints
# are [DEFERRED] typed placeholders; no domain logic ships in the skeleton.
#
# -----------------------------------------------------------------------------
# *** CRITICAL -- BUILD CONTEXT MUST BE THE REPOSITORY ROOT ***
# -----------------------------------------------------------------------------
# The account-svc pom.xml runs OpenAPI codegen from the FROZEN contract SSoT via
# the property  <openapi.spec>${project.basedir}/../../contracts/account-svc.openapi.yaml</openapi.spec>.
# The contract lives under /contracts and is DELIBERATELY NOT duplicated into this
# service. Building with the REPOSITORY ROOT as the build context lets this
# Dockerfile COPY both `contracts/` and `services/account-svc/` while preserving
# the services/account-svc -> ../../contracts layout the pom's default expects --
# so NO build-time contract copy and NO `-Dopenapi.spec` override are needed.
# A CLEAN checkout / no-cache Compose build now succeeds with NO host-built jar
# and NO CI/compose pre-step (finding P4-M08): codegen, compile and package all
# happen inside the image, identical to card-svc.
#
#   Correct manual build (note the trailing '.' == repo root):
#     docker build -f services/account-svc/Dockerfile -t carddemo/account-svc .
#
#   Required docker-compose wiring (kept in sync in /docker-compose.yml):
#     services:
#       account-svc:
#         build:
#           context: .                               # repo root, NOT ./services/account-svc
#           dockerfile: services/account-svc/Dockerfile
#
# Runtime configuration is INJECTED by compose/.env (never hardcoded here):
#   SPRING_DATASOURCE_URL / _USERNAME / _PASSWORD, SERVER_PORT (internal stays
#   8080), SPRING_FLYWAY_ENABLED=false, SPRING_PROFILES_ACTIVE=docker,
#   CORRELATION_HEADER.
# =============================================================================


# -----------------------------------------------------------------------------
# Phase A -- BUILD STAGE: OpenAPI codegen + compile + package the Spring Boot jar.
# -----------------------------------------------------------------------------
# S-01 (supply-chain hardening): build base standardized across ALL 8 services and
# pinned by digest. Ubuntu 24.04 LTS (noble) + Temurin 21 JDK + Maven 3.9.9. Earlier
# the services mixed floating maven:3.9(.9)-eclipse-temurin-21 tags; the digest makes
# builds reproducible. This stage is discarded by the multi-stage build and ships
# nothing into the runtime image.
FROM maven:3.9.9-eclipse-temurin-21-noble@sha256:2fa8b35172589aafa259fe953f4829859169bb0516f28f2b5a027c7c52017bee AS build

WORKDIR /build

# 1) Frozen contract SSoT first (stable layer). Preserving contracts/ at
#    /build/contracts keeps the ../../contracts layout intact: from WORKDIR
#    /build/services/account-svc, the pom's default openapi.spec resolves to
#    /build/contracts/account-svc.openapi.yaml.
COPY contracts/ contracts/

# 2) The service POM next (before src) for POM-keyed dependency layer caching.
COPY services/account-svc/pom.xml services/account-svc/pom.xml

# 3) Non-fatal offline dependency pre-resolution (see auth/card Dockerfile notes).
RUN mvn -f services/account-svc/pom.xml -B -q -DskipTests dependency:go-offline || true

# 4) Sources last -- the most frequently changing input.
COPY services/account-svc/src services/account-svc/src

# Build from the module directory so the pom's default
# ${project.basedir}/../../contracts resolves to /build/contracts.
WORKDIR /build/services/account-svc

# generate-sources (OpenAPI codegen) -> compile -> package the executable fat jar.
# -DskipTests: Oracle is not available at image-build time.
RUN mvn -B -q -DskipTests clean package


# -----------------------------------------------------------------------------
# Phase B -- RUNTIME STAGE: slim, health-checkable, JRE-only image.
# -----------------------------------------------------------------------------
# S-01 (supply-chain hardening): runtime base pinned by digest to
# eclipse-temurin:21-jre-noble (Ubuntu 24.04 LTS). The floating :21-jre tag had
# drifted to an Ubuntu 26.04 build bundling an unused /usr/bin/pebble Go binary with
# 5 HIGH CVEs; the -noble line ships no pebble. The digest freezes the scanned image.
FROM eclipse-temurin:21-jre-noble@sha256:373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64 AS runtime

# curl for the image-level HEALTHCHECK and the compose healthcheck.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Dedicated non-root system user (primary group root/gid 0 so the root-owned jar
# stays readable; java -jar needs only read access).
RUN useradd -r -u 1001 -g root appuser

WORKDIR /app

# Copy ONLY the packaged fat jar. account-svc has no custom <finalName>, so the
# repackaged executable jar is account-svc-<version>.jar; the `*.jar.original`
# thin jar does NOT match the `account-svc-*.jar` glob.
COPY --from=build /build/services/account-svc/target/account-svc-*.jar /app/app.jar

# Drop privileges after the copy.
USER appuser

# Internal port 8080; compose maps host ${ACCOUNT_SVC_PORT:-8082}:8080 (dev only).
EXPOSE 8080

# Image-level health mirrors the compose healthcheck / service_healthy gate.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Run the Spring Boot fat jar; runtime env injected by compose/.env.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

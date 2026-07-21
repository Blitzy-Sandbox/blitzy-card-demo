# =============================================================================
# auth-svc -- multi-stage, SELF-CONTAINED Docker image (permissive Sign-On stub).
# =============================================================================
# Provenance: [SRC: COSGN00C | COSGN00.bms] -- legacy CICS Sign-On transaction
#   CC00 -> COSGN00C reading the USRSEC VSAM KSDS.
#
# auth-svc is a REQUEST-SERVING, HEALTH-GATED, long-running container: it starts
# in the docker-compose `depends_on: condition: service_healthy` chain, is probed
# over HTTP against Spring Boot Actuator /actuator/health, and is the SOLE token
# authority + first hop of the real correlation-ID propagation (the BFF calls it).
#
# -----------------------------------------------------------------------------
# *** CRITICAL -- BUILD CONTEXT MUST BE THE REPOSITORY ROOT ***
# -----------------------------------------------------------------------------
# The auth-svc pom.xml runs OpenAPI codegen from the FROZEN contract SSoT by the
# RELATIVE path  ${project.basedir}/../../contracts/auth-svc.openapi.yaml. The
# contract lives under /contracts and is DELIBERATELY NOT duplicated into this
# service, so the in-image codegen (openapi-generator-maven-plugin) can find it,
# this Dockerfile is authored to be built with the REPOSITORY ROOT as the build
# context -- it COPYies both `contracts/` and `services/auth-svc/` while
# preserving the services/auth-svc -> ../../contracts layout the pom needs.
# This makes a CLEAN checkout / no-cache Compose build succeed with NO host-built
# jar (finding P4-M08); the whole build -- codegen, compile, package -- happens
# inside the image, identical to card-svc.
#
#   Correct manual build (note the trailing '.' == repo root):
#     docker build -f services/auth-svc/Dockerfile -t carddemo/auth-svc .
#
#   Required docker-compose wiring (kept in sync in /docker-compose.yml):
#     services:
#       auth-svc:
#         build:
#           context: .                               # repo root, NOT ./services/auth-svc
#           dockerfile: services/auth-svc/Dockerfile
#   If compose instead used `context: ./services/auth-svc`, the `COPY contracts/`
#   line below would FAIL because /contracts is outside that narrower context.
#   This is the standard monorepo pattern for shared-contract codegen.
#
# Runtime configuration is INJECTED by compose/.env (never hardcoded here):
#   SPRING_DATASOURCE_URL / _USERNAME / _PASSWORD, SERVER_PORT (internal stays
#   8080), SPRING_FLYWAY_ENABLED=false, SPRING_PROFILES_ACTIVE=docker,
#   CORRELATION_HEADER. No auth behavior lives here -- the permissive-stub logic
#   lives in src/.
# =============================================================================


# -----------------------------------------------------------------------------
# Phase A -- BUILD STAGE: OpenAPI codegen + compile + package the Spring Boot jar.
# Eclipse Temurin JDK 21 with Maven preinstalled (pinned tag; never `latest`).
# -----------------------------------------------------------------------------
# S-01 (supply-chain hardening): build base standardized across ALL 8 services and
# pinned by digest. Ubuntu 24.04 LTS (noble) + Temurin 21 JDK + Maven 3.9.9. Earlier
# the services mixed floating maven:3.9(.9)-eclipse-temurin-21 tags; the digest makes
# builds reproducible. This stage is discarded by the multi-stage build and ships
# nothing into the runtime image.
FROM maven:3.9.9-eclipse-temurin-21-noble@sha256:2fa8b35172589aafa259fe953f4829859169bb0516f28f2b5a027c7c52017bee AS build

WORKDIR /build

# 1) Frozen contract SSoT first -- it changes rarely, so this layer caches well.
#    Preserving contracts/ at /build/contracts keeps the ../../contracts layout
#    intact: from WORKDIR /build/services/auth-svc, the pom's
#    ${project.basedir}/../../contracts resolves to /build/contracts, so codegen
#    finds /build/contracts/auth-svc.openapi.yaml.
COPY contracts/ contracts/

# 2) The service POM next (before src) so the dependency layer is keyed on the
#    POM alone and is not invalidated by ordinary source edits.
COPY services/auth-svc/pom.xml services/auth-svc/pom.xml

# 3) Optional layer-caching optimization: pre-resolve dependencies into a layer
#    keyed on the POM. Made NON-FATAL (`|| true`) so partial offline resolution
#    never breaks the build -- the subsequent ONLINE `mvn package` fetches
#    anything still missing.
RUN mvn -f services/auth-svc/pom.xml -B -q -DskipTests dependency:go-offline || true

# 4) Sources last -- the most frequently changing input.
COPY services/auth-svc/src services/auth-svc/src

# Build from the module directory so ${project.basedir}/../../contracts resolves
# to /build/contracts (see note 1).
WORKDIR /build/services/auth-svc

# generate-sources (OpenAPI codegen) -> compile -> package the executable fat jar.
# -DskipTests: Oracle is NOT available at image-build time (CI provisions it via
# gvenzl/setup-oracle-free@v1; the smoke test is a mocked context-load anyway).
RUN mvn -B -q -DskipTests clean package


# -----------------------------------------------------------------------------
# Phase B -- RUNTIME STAGE: slim, health-checkable, JRE-only image.
# The builder stage above is discarded; only this stage's layers ship.
# -----------------------------------------------------------------------------
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

# Dedicated non-root system user (defense-in-depth). Primary group `root` (gid 0)
# so the COPYed jar (mode 644, owner root) stays readable; `java -jar` needs only
# read access, and /app (mode 755) remains traversable.
RUN useradd -r -u 1001 -g root appuser

WORKDIR /app

# Copy ONLY the packaged fat jar from the build stage. auth-svc has no custom
# <finalName>, so `spring-boot-maven-plugin repackage` yields exactly
# auth-svc-<version>.jar (the non-runnable `*.jar.original` does NOT match the
# `auth-svc-*.jar` glob), making this single-file COPY to /app/app.jar unambiguous.
COPY --from=build /build/services/auth-svc/target/auth-svc-*.jar /app/app.jar

# Drop privileges AFTER the copy (best practice). appuser can read /app/app.jar.
USER appuser

# The service listens on 8080 INTERNALLY (Spring Boot default). Compose maps the
# host port ${AUTH_SVC_PORT:-8081}:8080 (dev overlay only); the internal port is
# always 8080.
EXPOSE 8080

# Image-level health so docker/compose can gate dependents on `service_healthy`.
# The generous start-period accommodates JVM + Oracle connection warm-up.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Run the Spring Boot fat jar. Datasource / profile / port / correlation env are
# injected by compose/.env at run time -- intentionally NOT baked into the image.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

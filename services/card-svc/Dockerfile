# =============================================================================
# card-svc -- multi-stage Docker image for THE ONE LIVE TRACER card service.
# =============================================================================
# Provenance: [SRC: COCRDSLC | CARDDAT] -- app/csd/CARDDEMO.CSD
#   (transaction CCDL -> program COCRDSLC "VIEW CARD DETAIL" over file CARDDAT).
#
# This image packages the card bounded-context service -- home of the single
# fully-wired vertical tracer slice of the CardDemo walking skeleton:
# GET /cards/{cardNumber}, a REAL Oracle 23ai read of one Flyway-seeded row.
# It is a REQUEST-SERVING, HEALTH-GATED, long-running container that must build
# and start GREEN in the docker-compose `service_healthy` chain (AAP 0.1.2/0.4).
#
# -----------------------------------------------------------------------------
# *** CRITICAL -- BUILD CONTEXT MUST BE THE REPOSITORY ROOT ***
# -----------------------------------------------------------------------------
# The card-svc pom.xml reads the FROZEN OpenAPI 3.1 contract (the single source
# of truth) by the RELATIVE path:
#     ${project.basedir}/../../contracts/card-svc.openapi.yaml
# The contract lives under /contracts and is DELIBERATELY NOT duplicated into
# this service. So the in-image OpenAPI codegen (openapi-generator-maven-plugin)
# can find it, this Dockerfile is authored to be built with the REPOSITORY ROOT
# as the build context, letting it COPY both `contracts/` and `services/card-svc/`
# while preserving the services/card-svc -> ../../contracts layout the pom needs.
#
#   Correct manual build (note the trailing '.' == repo root):
#     docker build -f services/card-svc/Dockerfile -t carddemo/card-svc .
#
#   Required docker-compose wiring -- the compose file is owned by ANOTHER agent,
#   so do NOT edit it; this block is the cross-cutting coordination contract:
#     services:
#       card-svc:
#         build:
#           context: .                               # repo root, NOT ./services/card-svc
#           dockerfile: services/card-svc/Dockerfile
#   If compose instead used `context: ./services/card-svc`, the `COPY contracts/`
#   line below would FAIL because /contracts is outside that narrower context.
#   This is the standard monorepo pattern for shared-contract codegen.
#
# Runtime configuration is INJECTED by compose/.env (never hardcoded here):
#   SPRING_DATASOURCE_URL / _USERNAME / _PASSWORD, SERVER_PORT (internal stays
#   8080), SPRING_FLYWAY_ENABLED=false, SPRING_PROFILES_ACTIVE=docker,
#   CORRELATION_HEADER.
# =============================================================================


# -----------------------------------------------------------------------------
# Phase A -- BUILD STAGE: OpenAPI codegen + compile + package the Spring Boot jar.
# Eclipse Temurin JDK 21 with Maven preinstalled (pinned tag; never `latest`).
# This avoids a separate Maven install and any Maven wrapper (deliberately none).
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
#    intact: from WORKDIR /build/services/card-svc, the pom's
#    ${project.basedir}/../../contracts resolves to /build/contracts, so codegen
#    finds /build/contracts/card-svc.openapi.yaml.
COPY contracts/ contracts/

# 2) The service POM next (before src) so the dependency layer is keyed on the
#    POM alone and is not invalidated by ordinary source edits.
COPY services/card-svc/pom.xml services/card-svc/pom.xml

# 3) Optional layer-caching optimization: pre-resolve dependencies into a layer
#    keyed on the POM. Made NON-FATAL (`|| true`) so partial offline resolution
#    (e.g. plugins fetched later during `package`) never breaks the build -- the
#    subsequent ONLINE `mvn package` fetches anything still missing.
RUN mvn -f services/card-svc/pom.xml -B -q -DskipTests dependency:go-offline || true

# 4) Sources last -- the most frequently changing input.
COPY services/card-svc/src services/card-svc/src

# Build from the module directory so ${project.basedir}/../../contracts resolves
# to /build/contracts (see note 1).
WORKDIR /build/services/card-svc

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

# curl is required by BOTH the image-level HEALTHCHECK below and the
# docker-compose healthcheck (`curl -f http://localhost:8080/actuator/health`).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Dedicated non-root system user (defense-in-depth). Primary group `root` (gid 0)
# so the COPYed jar (mode 644, owner root) stays readable; `java -jar` needs only
# read access, and /app (mode 755) remains traversable.
RUN useradd -r -u 1001 -g root appuser

WORKDIR /app

# Copy ONLY the packaged fat jar from the build stage. The glob `card-svc-*.jar`
# matches exactly the repackaged executable jar (card-svc-0.1.0-SNAPSHOT.jar);
# the `*.jar.original` thin jar does NOT match `*.jar`, so this single-file COPY
# to /app/app.jar is unambiguous. The name tracks pom.xml (artifactId=card-svc,
# version=0.1.0-SNAPSHOT).
COPY --from=build /build/services/card-svc/target/card-svc-*.jar /app/app.jar

# Drop privileges AFTER the copy (best practice). appuser can read /app/app.jar.
USER appuser

# The service listens on 8080 INTERNALLY (Spring Boot default). Compose maps the
# host port ${CARD_SVC_PORT:-8083}:8080; the internal port is always 8080.
EXPOSE 8080

# Image-level health so docker/compose can gate dependents on `service_healthy`.
# The generous start-period accommodates JVM + Oracle connection warm-up.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Run the Spring Boot fat jar. Datasource / profile / port / correlation env are
# injected by compose/.env at run time -- intentionally NOT baked into the image.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

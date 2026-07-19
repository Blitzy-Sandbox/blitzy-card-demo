# syntax=docker/dockerfile:1

# =============================================================================
# CardDemo Walking Skeleton — transaction-svc runtime image (multi-stage)
# -----------------------------------------------------------------------------
# What this is:
#   Container image for the TRANSACTION bounded-context service, a Spring Boot
#   3.5.x / Java 21 application. In the walking skeleton every endpoint is a
#   typed placeholder ([DEFERRED] — no business logic), but the service is
#   REQUEST-SERVING and HEALTH-GATED: it starts green as a long-running
#   container and exposes Spring Boot Actuator /actuator/health so the
#   docker-compose `depends_on: condition: service_healthy` chain (and the
#   dependent `bff`) can gate on it.
#   Provenance: [SRC: COTRN00C/COTRN01C/COTRN02C | TRANSACT]
#     - Legacy CICS programs COTRN00C (CT00, list), COTRN01C (CT01, view) and
#       COTRN02C (CT02, add) over the TRANSACT VSAM KSDS, registered in
#       app/csd/CARDDEMO.CSD.
#
# -----------------------------------------------------------------------------
# PREREQUISITE — `mvn package` MUST run BEFORE `docker build` (read this first):
#   The root /docker-compose.yml builds this service with
#     build: { context: ./services/transaction-svc, dockerfile: Dockerfile }
#   so the Docker build context is the SERVICE DIRECTORY ONLY. It does NOT
#   include the repository's /contracts folder, and a Dockerfile cannot COPY
#   files from outside its build context (`../../contracts` is rejected by
#   Docker). Because OpenAPI code generation needs
#   ../../contracts/transaction-svc.openapi.yaml, in-container Maven codegen is
#   IMPOSSIBLE with this context.
#
#   Therefore this Dockerfile uses the PREBUILT-JAR strategy: Maven runs first
#   on the host / in CI (where /contracts IS reachable) via `mvn package`,
#   which runs OpenAPI codegen and repackages the executable Spring Boot fat
#   jar as target/transaction-svc.jar (name fixed by
#   <finalName>transaction-svc</finalName> in pom.xml). This Dockerfile then
#   simply packages that prebuilt jar into a runtime image — it does NOT run
#   Maven or codegen and does NOT copy any contract.
#
#   Consequence: a cold `docker compose build` (or `docker build`) with NO
#   prior Maven build FAILS at the jar COPY step below. That is EXPECTED and
#   INTENDED given the compose build-context choice — run `mvn package` first.
#
# -----------------------------------------------------------------------------
# Build (from the repo root, after a Maven build produced the jar, e.g.
#   `mvn -pl services/transaction-svc -am package` or a full reactor build):
#   docker build -t carddemo/transaction-svc:local services/transaction-svc
# Run: orchestrated by /docker-compose.yml, which maps the host port
#   ${TRANSACTION_SVC_PORT:-8084} -> container 8080 and sets SERVER_PORT=8080.
#   The compose healthcheck is:
#     ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health || exit 1"]
# =============================================================================

# ---------- Stage 1: builder (validate & stage the prebuilt jar) ----------
# The mandated 21-jdk builder stage. It does NOT compile anything here (codegen
# + package already happened on the host/CI, see PREREQUISITE above); it stages
# the prebuilt jar and fails fast with a clear error if it is missing.
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

# Copy the prebuilt Spring Boot executable jar produced by `mvn package`
# (finalName=transaction-svc => target/transaction-svc.jar). The service-dir
# build context cannot reach ../../contracts, so codegen + package happen on
# the host / in CI first. The repackaged thin jar (transaction-svc.jar.original)
# is intentionally NOT matched — only the executable fat jar is copied.
COPY target/transaction-svc.jar app.jar

# Fail fast with a clear, early error if the jar is missing or empty — this
# means the `mvn package` prerequisite was skipped. `-s` is true only for a
# file that exists and has a size greater than zero.
RUN test -s app.jar

# ---------- Stage 2: runtime (lean JRE + curl for the compose healthcheck) ----------
FROM eclipse-temurin:21-jre AS runtime

# Install curl for the docker-compose Actuator healthcheck
# ("curl -f http://localhost:8080/actuator/health"). eclipse-temurin:21-jre is
# Ubuntu-based but does NOT ship curl; without it the container never reports
# `healthy` and the whole service_healthy gating chain (and the bff) stalls.
# --no-install-recommends + cleaning the apt lists keeps the layer small.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Create a dedicated non-root system user/group to run the JVM (least
# privilege). nologin shell + system account: this identity only runs the app.
RUN groupadd --system spring \
 && useradd --system --gid spring --home-dir /app --shell /usr/sbin/nologin spring

WORKDIR /app

# Bring only the executable jar across from the builder stage (the JDK, apt
# caches, and any build scratch stay behind — the runtime image is JRE-only).
COPY --from=builder /build/app.jar /app/app.jar

# Ensure the runtime user owns its working directory and the jar.
RUN chown -R spring:spring /app

# Drop root for everything below (and at container runtime).
USER spring

# Container-internal port. Compose publishes ${TRANSACTION_SVC_PORT:-8084}:8080
# and sets SERVER_PORT=8080; Spring Boot binds SERVER_PORT via relaxed env
# binding, so the app listens on 8080 to match this EXPOSE and the healthcheck
# URL. Do NOT hardcode a different port.
EXPOSE 8080

# Exec form (JSON array) so the JVM is PID 1 and receives SIGTERM directly for
# graceful shutdown — no `sh -c` wrapper (which would break PID-1 signalling).
# Plain `java -jar` (no Spring Boot layered-jar / jarmode extraction) is used
# deliberately: it is bulletproof across Boot patch versions for this skeleton.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

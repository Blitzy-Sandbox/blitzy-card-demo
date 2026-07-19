# syntax=docker/dockerfile:1

# =============================================================================
# CardDemo Walking Skeleton — auth-svc runtime image (multi-stage)
# Provenance: [SRC: COSGN00C | COSGN00.bms] — legacy CICS Sign-On transaction
# CC00 -> COSGN00C reading the USRSEC VSAM KSDS.
# -----------------------------------------------------------------------------
# auth-svc is a REQUEST-SERVING, HEALTH-GATED, LONG-RUNNING container: it starts
# in the docker-compose `depends_on: condition: service_healthy` chain and is
# probed over HTTP against Spring Boot Actuator /actuator/health.
#
# Stage 1 (builder): eclipse-temurin:21-jdk — take the PRE-BUILT, repackaged,
#                    LAYERED Spring Boot jar and explode it into cache-friendly
#                    layers with `java -Djarmode=layertools ... extract`.
# Stage 2 (runtime): eclipse-temurin:21-jre — copy the exploded layers, add curl
#                    for the healthcheck, run as a NON-ROOT user, and launch via
#                    the Spring Boot 3.2+ JarLauncher. (Per AAP §0.3.)
#
# ---- CONTRACT: this image expects a PRE-BUILT jar (READ THIS FIRST) ----------
# The Spring Boot jar MUST be built OUTSIDE Docker (`mvn package`) BEFORE
# `docker build`, because the sibling pom.xml runs OpenAPI codegen from
# ../../contracts/auth-svc.openapi.yaml — a path OUTSIDE this build context.
# The planned docker-compose.yml builds this image with:
#     build:
#       context: ./services/auth-svc      # <-- CANNOT see the repo-level /contracts
#       dockerfile: Dockerfile
# A build context of ./services/auth-svc cannot see /contracts, so a fully
# self-contained "run Maven (with codegen) inside the image" build would FAIL
# code generation. Instead CI/dev builds the jar first (where ../../contracts
# resolves), then builds this image:
#     mvn -f services/auth-svc/pom.xml clean package   # or: mvn -pl services/auth-svc -am package
#     docker compose build auth-svc                     # then build this image
# This Dockerfile then simply COPYies target/*.jar from the build context.
# Because target/ lives INSIDE ./services/auth-svc, it IS in the compose build
# context — so this design is fully compatible with the planned compose config.
#
# ---- ALTERNATIVE (documented for maintainers; NOT the active path) -----------
# If someone instead sets the compose build context to the repository root:
#     build:
#       context: .
#       dockerfile: services/auth-svc/Dockerfile
# then a self-contained Maven build becomes possible: COPY both
# `services/auth-svc` and `contracts` into a jdk stage and run `mvn package`
# there (codegen would resolve /contracts). This variant is intentionally NOT
# implemented here because it diverges from the planned compose context
# (context: ./services/auth-svc). Keep the pre-built-jar design as the one
# active path unless the compose context is deliberately changed.
#
# ---- Coordinated runtime facts (MUST match docker-compose + application.yml) --
#   * Container-internal port is 8080 (EXPOSE 8080). Compose sets SERVER_PORT=8080
#     and maps host AUTH_SVC_PORT (8081) -> container 8080.
#   * The compose healthcheck runs `curl -f http://localhost:8080/actuator/health`.
#     eclipse-temurin:21-jre is Debian/Ubuntu-based and ships NO curl, so curl is
#     installed below — REQUIRED, not optional.
#   * Datasource/correlation config arrives at RUNTIME via env vars injected by
#     compose (SPRING_DATASOURCE_URL / SPRING_DATASOURCE_USERNAME /
#     SPRING_DATASOURCE_PASSWORD, SPRING_PROFILES_ACTIVE=docker,
#     SPRING_FLYWAY_ENABLED=false, CORRELATION_HEADER=X-Correlation-ID,
#     SERVER_PORT). This image hardcodes NONE of them (only a harmless
#     SERVER_PORT default).
#   * No auth behavior lives here — the permissive-stub logic lives in src/.
#
# Lean & reproducible: explicit image tags (no `latest`), --no-install-recommends,
# apt lists cleaned in-layer, layered COPY for cache efficiency, non-root runtime.
# =============================================================================


# ---------- Stage 1: builder / prep (explode the layered jar) ----------
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# Copy the PRE-BUILT, repackaged Spring Boot jar from the build context
# (services/auth-svc/target/). `spring-boot-maven-plugin repackage` leaves
# exactly ONE runnable jar — the non-runnable original is `*.jar.original`,
# which this glob does NOT match — so `target/*.jar` resolves to a single file
# (auth-svc-0.1.0-SNAPSHOT.jar).
COPY target/*.jar app.jar

# Explode the executable jar into Spring Boot layers for cache-efficient image
# layering. Produces four directories under /workspace, ordered least- to
# most-frequently-changing: dependencies/, spring-boot-loader/,
# snapshot-dependencies/, application/.
#
# VERSION NOTE: `-Djarmode=layertools ... extract` is DEPRECATED as of Spring
# Boot 3.3 (it prints a deprecation warning but still works on the 3.5.x line
# this project pins). Its successor is
# `java -Djarmode=tools -jar app.jar extract --layers --launcher`.
# FALLBACK: if a future Spring Boot release removes the `layertools` jarmode
# entirely, drop this extract + the per-layer COPY below and instead copy
# `app.jar` straight into the runtime stage with
# `ENTRYPOINT ["java","-jar","/app/app.jar"]`.
RUN java -Djarmode=layertools -jar app.jar extract


# ---------- Stage 2: runtime (lean JRE, non-root, healthchecked) ----------
FROM eclipse-temurin:21-jre

# Install curl for the container healthcheck (both the compose healthcheck and
# the HEALTHCHECK below curl /actuator/health). --no-install-recommends keeps the
# layer minimal; the apt lists are removed in the SAME layer so they are never
# persisted in the image.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Create a dedicated, unprivileged system user/group and run as it (never root).
# --home /app matches WORKDIR so the process owns its working directory.
RUN groupadd --system app \
    && useradd --system --gid app --home /app app

WORKDIR /app

# Copy the exploded layers from the builder in dependency-stability order
# (least- to most-frequently-changing) so Docker caches the large, stable
# dependency layers across rebuilds and only re-lays the small application layer
# when code changes. `--chown=app:app` hands ownership to the non-root runtime
# user. Merged into a single /app directory, these four layers form a
# JarLauncher-runnable layout (META-INF/MANIFEST.MF + BOOT-INF/*).
COPY --from=builder --chown=app:app /workspace/dependencies/ ./
COPY --from=builder --chown=app:app /workspace/spring-boot-loader/ ./
COPY --from=builder --chown=app:app /workspace/snapshot-dependencies/ ./
COPY --from=builder --chown=app:app /workspace/application/ ./

# Container-internal port; docker-compose maps host AUTH_SVC_PORT (8081) -> 8080.
EXPOSE 8080

# Harmless default so a bare `docker run` listens on 8080; compose overrides via
# the injected SERVER_PORT env var
# (application.yml: server.port=${SERVER_PORT:8080}).
ENV SERVER_PORT=8080

# Standalone `docker run` health signal that MIRRORS the compose healthcheck, so
# `docker run` and `docker compose` behave identically. The generous
# --start-period gives the JVM + Spring context (and a possibly slow Oracle
# connection) time to come up before failures are counted; --interval/--retries
# match a service that gates other containers via `service_healthy`. Under
# compose the compose-level healthcheck drives the `service_healthy` gate.
HEALTHCHECK --interval=15s --timeout=5s --retries=10 --start-period=60s \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Drop privileges for the running container.
USER app

# Spring Boot 3.2+ layered launcher main class. Verified in the repackaged jar
# MANIFEST (Main-Class=org.springframework.boot.loader.launch.JarLauncher): it
# reads BOOT-INF/classpath.idx from the merged layers and launches the
# Start-Class (the @SpringBootApplication).
# VERSION NOTE: Spring Boot < 3.2 used org.springframework.boot.loader.JarLauncher
# (no `.launch` segment); this project is 3.5.x, so the `.launch.` form is correct.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

# syntax=docker/dockerfile:1.7
# =============================================================================
# AWS CardDemo — Multi-stage Dockerfile
# =============================================================================
#
# Builds the Spring Boot 3.x CardDemo application JAR with the Apache Maven
# Wrapper inside a disposable Maven + Eclipse Temurin 17 JDK build stage and
# packages it into a lean Eclipse Temurin 17 JRE Alpine runtime image suitable
# for deployment to Amazon ECS Fargate (pushed to ECR).
#
# -----------------------------------------------------------------------------
# AAP traceability
# -----------------------------------------------------------------------------
#
#   * AAP §0.4.1  -- `Dockerfile` listed as CREATE under "Configuration, Build,
#                   Deployment, and Documentation"; first-class build artifact
#                   of the COBOL -> Java/Spring Boot migration.
#   * AAP §0.5.1  -- mandates a multi-stage build with a Maven 3.8+ build stage
#                   and an `eclipse-temurin:17-jre-alpine` runtime stage. Java
#                   17+ is the LTS baseline (Spring Boot 3.x minimum).
#   * AAP §0.7.1  -- Minimal Change Clause: no secrets, no plaintext
#                   credentials, no application config files copied into the
#                   image; runtime credentials are sourced exclusively from
#                   AWS Secrets Manager. Non-root container user is required
#                   by the ECS Fargate security baseline.
#   * AAP §0.7.2  -- AWS deployment flow: `docker build` -> push to ECR ->
#                   ECS Fargate task-definition update.
#   * AAP §0.3.3  -- Health checks via Spring Actuator
#                   (`/actuator/health/liveness`, `/actuator/health/readiness`)
#                   are wired into both the container HEALTHCHECK and the ALB
#                   target group health checks.
#
# -----------------------------------------------------------------------------
# Multi-stage build overview
# -----------------------------------------------------------------------------
#
#   Stage 1 (build)   FROM maven:3.9-eclipse-temurin-17 AS build
#                      - Apache Maven 3.9.x + Eclipse Temurin 17 JDK
#                      - Resolves dependencies via `./mvnw dependency:go-offline`
#                        in a SEPARATE layer so that source code changes do NOT
#                        invalidate the dependency cache (build acceleration).
#                      - Builds the Spring Boot fat JAR via
#                        `./mvnw -DskipTests=true clean package`.
#                      - Extracts the JAR into Spring Boot layered-JAR layers
#                        (`dependencies/`, `spring-boot-loader/`,
#                         `snapshot-dependencies/`, `application/`) for
#                        fine-grained Docker layer reuse on rebuild.
#                      - DISCARDED after the build stage; never reaches the
#                        final runtime image.
#
#   Stage 2 (runtime) FROM eclipse-temurin:17-jre-alpine AS runtime
#                      - Lean Alpine-based JRE 17; Maven, JDK, and source code
#                        are NOT present in this stage.
#                      - Creates a dedicated non-root `spring:spring` user.
#                      - Copies the four Spring Boot layers (in cache-friendly
#                        order: least-changing first) from the build stage,
#                        chown'd to `spring:spring`.
#                      - Exposes Spring Boot's default port 8080 (consumed by
#                        the ALB target group and `docker-compose.yml`).
#                      - Wires a container HEALTHCHECK against
#                        `/actuator/health/liveness` using BusyBox `wget`
#                        (Alpine).
#                      - Launches the application via the Spring Boot 3.2+
#                        `org.springframework.boot.loader.launch.JarLauncher`
#                        main class (the renamed launcher for layered JARs in
#                        Spring Boot 3.2+).
#
# -----------------------------------------------------------------------------
# JVM tuning rationale (AAP §0.5.1, §0.6.1)
# -----------------------------------------------------------------------------
#
#   * -XX:+UseContainerSupport             JVM respects cgroup CPU/memory
#                                          limits (Fargate task sizing). Default
#                                          ON for JDK 17 but stated explicitly
#                                          for clarity.
#   * -XX:MaxRAMPercentage=75.0            Caps JVM heap at 75% of container
#                                          memory; leaves 25% for native heap,
#                                          metaspace, code cache, and OS.
#   * -Dfile.encoding=UTF-8                Locks encoding to UTF-8 so the
#                                          golden-output diff tests (AAP
#                                          §0.6.1 BigDecimal parity & byte-
#                                          identical output) succeed regardless
#                                          of host locale.
#   * -Djava.security.egd=file:/dev/./urandom
#                                          Non-blocking entropy source; avoids
#                                          startup stalls in containers with a
#                                          warm-but-low /dev/random pool.
#
# -----------------------------------------------------------------------------
# Build args (CI integration)
# -----------------------------------------------------------------------------
#
#   * BUILD_VERSION    semver / git-describe tag of the release; surfaced via
#                      the OCI `org.opencontainers.image.version` label.
#   * BUILD_COMMIT     git commit SHA; surfaced via the OCI
#                      `org.opencontainers.image.revision` label.
#   * MAVEN_IMAGE      build-stage base image (overridable for air-gapped
#                      registries or mirror substitution).
#   * RUNTIME_IMAGE    runtime-stage base image (same override surface).
#
# Example CI invocation:
#   docker build \
#     --build-arg BUILD_VERSION=$(git describe --tags --always) \
#     --build-arg BUILD_COMMIT=$(git rev-parse HEAD) \
#     -t ${ECR_REGISTRY}/carddemo:${TAG} .
#
# =============================================================================


# -----------------------------------------------------------------------------
# Global build args (must precede the first FROM to be usable as image refs)
# -----------------------------------------------------------------------------
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-17
ARG RUNTIME_IMAGE=eclipse-temurin:17-jre-alpine


# =============================================================================
# Stage 1: build -- Maven 3.9.x + Temurin 17 JDK
# =============================================================================
# Per AAP §0.5.1: the build container must contain Apache Maven 3.8+ and
# Eclipse Temurin 17 JDK. The `maven:3.9-eclipse-temurin-17` tag satisfies
# both constraints with a single curated upstream image.
FROM ${MAVEN_IMAGE} AS build

# -----------------------------------------------------------------------------
# Clear MAVEN_CONFIG (known Maven Docker image / Apache Maven Wrapper conflict)
# -----------------------------------------------------------------------------
# The official `maven:3.9-eclipse-temurin-17` image sets
# `ENV MAVEN_CONFIG=/root/.m2` to point Maven at its local repository
# directory. However, the Apache Maven Wrapper script (`mvnw`) interprets
# `$MAVEN_CONFIG` as a *Maven CLI arguments string* (intended to be read from
# `~/.m2/maven.config`) and appends its value to the command line. The result
# of leaving `MAVEN_CONFIG=/root/.m2` in place is that `mvnw` invokes Maven
# with a phantom "/root/.m2" lifecycle argument, producing:
#
#   [ERROR] Unknown lifecycle phase "/root/.m2".
#
# Clearing MAVEN_CONFIG to the empty string removes the phantom argument while
# leaving the local repository discovery (which is independently driven by
# `~/.m2/settings.xml` and the default `${user.home}/.m2/repository` lookup)
# fully intact.
ENV MAVEN_CONFIG=""

# Working directory for the build. Deliberately NOT `/app` so the runtime
# image's `/app` directory cannot be accidentally confused with build artifacts.
WORKDIR /build

# -----------------------------------------------------------------------------
# Layer A -- Maven Wrapper + build descriptor
# -----------------------------------------------------------------------------
# Copy the Maven Wrapper resources and POM FIRST so the resulting dependency
# cache layer is invalidated only when:
#   * the wrapper itself changes (.mvn/wrapper/maven-wrapper.properties or .jar)
#   * the wrapper scripts change (mvnw / mvnw.cmd)
#   * the POM changes (pom.xml)
#
# Source code changes under `src/` do NOT invalidate this layer, so iterative
# rebuilds during development hit the cached dependency layer.
COPY .mvn/ ./.mvn/
COPY mvnw mvnw.cmd ./
COPY pom.xml ./

# Ensure mvnw is executable. Required because some host filesystems (Windows
# bind mounts via WSL2, CIFS, etc.) can drop the +x bit during COPY. Idempotent
# on Linux hosts where mvnw already carries +x in Git.
RUN chmod +x ./mvnw

# -----------------------------------------------------------------------------
# Layer B -- Pre-fetch all Maven dependencies (cached unless pom.xml changes)
# -----------------------------------------------------------------------------
# `dependency:go-offline` resolves every Maven dependency (compile, test,
# plugin, BOM) into the local repository inside the image. The `-B` flag
# disables interactive prompts (CI batch mode) and `-q` quiets the output so
# the dependency download list does not flood CI logs.
#
# `-DskipTests` is harmless here (no tests are run) but suppresses surefire's
# plugin lookup, marginally accelerating dependency resolution.
#
# `|| true` keeps the build resilient to occasional spurious failures during
# transitive dependency resolution of pre-fetch (some Spring Cloud plugins
# attempt to resolve their dependencies lazily at execute-time, which can fail
# under `dependency:go-offline`). The subsequent `clean package` step always
# re-resolves any missing artifacts and will fail visibly if a dependency is
# genuinely unreachable.
RUN ./mvnw -B -q dependency:go-offline -DskipTests || true

# -----------------------------------------------------------------------------
# Layer C -- Source tree
# -----------------------------------------------------------------------------
# Copy only the `src/` subtree -- the .dockerignore exclusions (Phase 1, 5, 6,
# 7) ensure that target/, .git/, secrets, IaC, and the COBOL reference tree
# under app/ are NEVER added to the build context.
COPY src/ ./src/

# -----------------------------------------------------------------------------
# Layer D -- Compile + package
# -----------------------------------------------------------------------------
# Build the Spring Boot fat JAR. Tests are intentionally skipped because the
# CI pipeline (.github/workflows/build.yml) runs the full unit + integration
# test gate against a clean Maven build (with Testcontainers / LocalStack); the
# Docker build serves only to produce the deployable artifact.
#
# The POM's `<finalName>carddemo</finalName>` yields `/build/target/carddemo.jar`.
# The POM's `<layers><enabled>true</enabled></layers>` configures the
# spring-boot-maven-plugin to produce a layered fat JAR.
RUN ./mvnw -B -DskipTests=true clean package \
    && test -f target/carddemo.jar \
    && ls -la target/

# -----------------------------------------------------------------------------
# Layer E -- Spring Boot layered-JAR extraction
# -----------------------------------------------------------------------------
# Extract the layered fat JAR into four sub-directories:
#
#   dependencies/           third-party JARs (slowest-changing -- copy first)
#   spring-boot-loader/     Spring Boot loader classes (changes only on
#                           Spring Boot upgrade)
#   snapshot-dependencies/  SNAPSHOT JARs (rare for production builds)
#   application/            application classes + resources (fastest-changing)
#
# Copying these as four separate Docker layers in the runtime stage (in the
# above order) maximises layer reuse: typical commit-level changes only
# invalidate the application/ layer.
WORKDIR /build/target
RUN java -Djarmode=layertools -jar carddemo.jar extract --destination extracted \
    && ls -la extracted/


# =============================================================================
# Stage 2: runtime -- Eclipse Temurin 17 JRE on Alpine
# =============================================================================
# Per AAP §0.5.1 -- `eclipse-temurin:17-jre-alpine` is the explicitly
# mandated runtime base. Alpine is preferred for minimal image size; this
# image contains only the JRE and a BusyBox userland (no Maven, no JDK, no
# source code, no shell utilities beyond BusyBox).
FROM ${RUNTIME_IMAGE} AS runtime

# -----------------------------------------------------------------------------
# Build-arg re-declaration for label substitution
# -----------------------------------------------------------------------------
# ARGs declared before the first FROM are global *image refs* only; to use a
# build arg inside a stage's RUN/LABEL/ENV instructions, it must be re-declared
# in that stage. These two surface the release version and commit SHA into the
# image metadata for traceability.
ARG BUILD_VERSION=local-dev
ARG BUILD_COMMIT=unknown

# -----------------------------------------------------------------------------
# OCI image metadata (AAP §0.5.1 -- "image metadata")
# -----------------------------------------------------------------------------
# Standard OCI annotations. Container registries (ECR, Harbor, GHCR) and
# scanners (Trivy, Snyk) read these labels to display image provenance.
#
# `org.opencontainers.image.title="carddemo"` is the canonical export listed
# in the file schema (members_exposed).
LABEL org.opencontainers.image.title="carddemo" \
      org.opencontainers.image.description="AWS CardDemo COBOL -> Java/Spring Boot migration -- Spring Boot 3.x application deployed on Amazon ECS Fargate" \
      org.opencontainers.image.vendor="AWS Mainframe Modernization" \
      org.opencontainers.image.source="https://github.com/Blitzy-Sandbox/blitzy-card-demo" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.revision="${BUILD_COMMIT}" \
      org.opencontainers.image.base.name="eclipse-temurin:17-jre-alpine" \
      org.opencontainers.image.documentation="https://github.com/Blitzy-Sandbox/blitzy-card-demo/blob/main/README.md"

# -----------------------------------------------------------------------------
# Non-root runtime user (AAP §0.7.1 -- PCI-DSS / ECS Fargate baseline)
# -----------------------------------------------------------------------------
# Alpine BusyBox `adduser` syntax:
#   -S  system account (no aging, no password, fixed UID range)
#   -G  primary group for the new user
# Both the group and user are named `spring` for clarity. The combined UID/GID
# falls into Alpine's system range (<1000), which is conventional for
# non-interactive service accounts.
RUN addgroup -S spring && adduser -S spring -G spring

# Working directory for the application. The four layered-JAR sub-trees are
# unpacked into this directory (they merge into a single classpath-friendly
# layout: BOOT-INF/, META-INF/, org/, etc.).
WORKDIR /app

# -----------------------------------------------------------------------------
# Layer copies -- ordered slowest-changing to fastest-changing
# -----------------------------------------------------------------------------
# Each layer is COPY'd with --chown=spring:spring so the runtime user owns
# its own classpath; no chmod -R is required afterwards. The order matters
# for Docker's layer cache: editing only application code invalidates only
# the last (application/) layer, keeping ~250 MB of dependencies cached.
COPY --from=build --chown=spring:spring /build/target/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /build/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /build/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /build/target/extracted/application/ ./

# -----------------------------------------------------------------------------
# Drop privileges
# -----------------------------------------------------------------------------
# All subsequent instructions and the container's main process run as the
# non-root `spring` user. The directory was chown'd by the COPY --chown above,
# so no recursive chown is needed here.
USER spring:spring

# -----------------------------------------------------------------------------
# Network surface
# -----------------------------------------------------------------------------
# Spring Boot's default servlet container port (Tomcat) -- 8080. Mapped 1:1 by
# docker-compose.yml (`"8080:8080"`) and by the ECS task definition / ALB
# target group (infrastructure/terraform/ecs.tf + alb.tf). EXPOSE is purely
# advisory metadata -- the actual port binding is configured by the runtime
# orchestrator.
EXPOSE 8080

# -----------------------------------------------------------------------------
# Container HEALTHCHECK (AAP §0.3.3)
# -----------------------------------------------------------------------------
# Polls Spring Actuator's liveness probe -- the contract used by the ECS task
# health check and the ALB target group health check. `wget` is provided by
# BusyBox in the Alpine base image (no extra package install required).
#
# Tunables:
#   --interval=30s     poll every 30 seconds once started
#   --timeout=10s      individual probe times out after 10 seconds
#   --start-period=60s grace period for slow Spring Boot startup
#                      (Hibernate + Flyway + Kafka + AWS SDK init)
#   --retries=3        three consecutive failures -> container marked unhealthy
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8080/actuator/health/liveness || exit 1

# -----------------------------------------------------------------------------
# ENTRYPOINT (exec form -- AAP §0.5.1)
# -----------------------------------------------------------------------------
# Exec form (JSON array) is used so the JVM becomes PID 1 -- this allows the
# container runtime to deliver SIGTERM directly to the JVM for graceful
# shutdown of Spring Boot (closing HTTP connections, draining Kafka consumer
# offsets, releasing JDBC pool connections).
#
# Launcher: `org.springframework.boot.loader.launch.JarLauncher`
#   -- the layered-JAR launcher introduced in Spring Boot 3.2 (this project
#      runs Spring Boot 3.3.13 per pom.xml). It reads layers.idx /
#      classpath.idx written into the application/ layer at build time and
#      assembles the classpath in the correct order.
#
# JVM flags are documented in the header comment above. They are baked in
# rather than sourced from JAVA_OPTS to keep PID-1 signal handling simple;
# operators wanting to add flags can set the standard `JAVA_TOOL_OPTIONS`
# environment variable in the ECS task definition, which the JVM picks up
# automatically.
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Dfile.encoding=UTF-8", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "org.springframework.boot.loader.launch.JarLauncher"]

# =============================================================================
# End of Dockerfile
# =============================================================================

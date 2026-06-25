# =============================================================================
# CardDemo - Multi-stage container image (COBOL -> Java 25 / Spring Boot 3.5.15)
#
# Produces a slim, runnable image of the CardDemo Spring Boot application by:
#   Stage 1 (builder) - compiling and packaging the executable Spring Boot JAR
#                       with the project's pinned Maven Wrapper (Maven 3.9.9).
#   Stage 2 (runtime) - copying ONLY the resulting JAR onto a slim JRE base and
#                       running it as a non-root user.
#
# Team build command (project-guide.md, Section 9 - "Building Docker image"):
#   docker build --network=host -t carddemo:latest .
#
# The --network=host flag is required so the Maven Wrapper can reach Maven
# Central to (a) bootstrap the wrapper JAR, (b) download the Apache Maven 3.9.9
# distribution pinned in .mvn/wrapper/maven-wrapper.properties, and (c) resolve
# all project dependencies. No artifacts are vendored into the repository.
#
# Security / hygiene:
#   * No secrets (JWT signing key, AWS keys, LOCALSTACK_AUTH_TOKEN) are ever baked
#     into the image. They are injected at runtime via environment variables /
#     docker-compose / a secret manager (No-Hardcoded-Credentials rule).
#   * The final image contains neither Maven, the wrapper, nor the source tree -
#     only a JRE plus the application JAR.
#   * The application runs as a dedicated, unprivileged (non-root) user.
# =============================================================================


# -----------------------------------------------------------------------------
# Stage 1: builder - compile + package the executable Spring Boot fat JAR.
#
# Uses the full JDK 25 (Eclipse Temurin) image; the Java version matches the
# project's pinned target (Java 25 LTS, pom.xml <java.version>25</java.version>).
# This stage is discarded after the build, so its size/tooling do not affect the
# final runtime image.
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS builder

# Make apt fully non-interactive (no tzdata/debconf prompts) for reproducible builds.
ENV DEBIAN_FRONTEND=noninteractive

WORKDIR /build

# The Eclipse Temurin base ships without curl/wget. The Maven Wrapper (v3.3.2)
# downloads its bootstrap JAR (maven-wrapper.jar) over HTTPS using curl/wget and
# has no in-repo Java downloader fallback, so curl + ca-certificates are required
# before the wrapper can run. Installed and the apt cache purged in a single
# layer to keep the (throwaway) build stage lean.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Copy the wrapper + POM FIRST (before sources) so Docker can cache the populated
# Maven repository as its own layer. This layer is only rebuilt when the wrapper
# config or pom.xml change - not on every source edit.
#   .mvn/ also carries jvm.config (JDK 25 native-access / unsafe-memory flags)
#   consumed by the wrapper, so the whole directory is copied.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Pre-fetch dependencies into a dedicated, cacheable layer. `dependency:go-offline`
# resolves the plugin and dependency graph up front; chmod guards against a
# wrapper script that lost its executable bit on checkout (e.g. on Windows).
RUN chmod +x mvnw \
    && ./mvnw -B -q dependency:go-offline

# Now copy the application sources and build. Changes here do not invalidate the
# dependency layer above.
COPY src/ src/

# Package the application, skipping tests (the image build is a packaging step;
# the full test suite + coverage + CVE gates run via `./mvnw verify` in CI/local,
# not during image creation). The Spring Boot Maven plugin's `repackage` goal
# produces the single executable fat JAR under target/ and renames the plain JAR
# to *.jar.original (which the runtime-stage *.jar glob deliberately excludes).
RUN ./mvnw -B clean package -DskipTests


# -----------------------------------------------------------------------------
# Stage 2: runtime - minimal image that runs the packaged JAR.
#
# Uses the slim JRE 25 (Eclipse Temurin) image - no compiler/Maven toolchain in
# the shipped image. Only the application JAR is carried over from the builder.
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime

ENV DEBIAN_FRONTEND=noninteractive

# Single layer that (1) installs curl for the container HEALTHCHECK against the
# Spring Boot Actuator endpoint, (2) purges the apt cache, and (3) creates a
# dedicated, unprivileged system user/group. Running as a fixed non-root UID/GID
# (1001) satisfies the "no root container" best practice and is compatible with
# Kubernetes `runAsNonRoot`.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 1001 carddemo \
    && useradd --system --uid 1001 --gid carddemo \
         --home-dir /app --shell /usr/sbin/nologin carddemo

WORKDIR /app

# Copy ONLY the executable Spring Boot JAR from the builder stage. The *.jar glob
# matches exactly one file (carddemo-<version>.jar); the repackage goal renamed
# the non-executable original to *.jar.original, so it is not matched. Ownership
# is handed to the non-root runtime user.
COPY --from=builder --chown=carddemo:carddemo /build/target/*.jar /app/app.jar

# Public application HTTP port (Spring Boot default, server.port=${SERVER_PORT:8080}).
EXPOSE 8080

# Isolated management/actuator port (management.server.port=${MANAGEMENT_SERVER_PORT:9091}).
# Declared for documentation/in-network scraping only; docker-compose intentionally
# does NOT publish this port to the host, keeping info/metrics/prometheus off the
# public surface (DECISION_LOG D-033).
EXPOSE 9091

# Container-aware JVM defaults: size the heap relative to the container's memory
# limit (cgroups), rather than the host's total RAM. Fully overridable at runtime
# by supplying a different JAVA_OPTS value. SPRING_PROFILES_ACTIVE is intentionally
# left UNSET here so this image stays environment-agnostic; docker-compose / the
# deployment platform selects the active profile (e.g. "local").
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# Drop privileges: everything from here runs as the unprivileged carddemo user.
USER carddemo

# Liveness/health probe consistent with the Actuator health endpoint, which is
# served on the isolated management port (management.server.port=9091). The probe
# runs inside the container, so it reaches the management port over localhost even
# though that port is not published to the host. A generous start-period
# accommodates JVM warm-up plus Flyway schema migration on first boot. `curl -f`
# fails (non-zero) on any non-2xx response, marking the container unhealthy if the
# application is not serving UP.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:9091/actuator/health || exit 1

# Launch the JAR. The `sh -c "exec java ..."` form expands ${JAVA_OPTS} while
# `exec` replaces the shell so the JVM becomes PID 1 and receives SIGTERM directly
# (enabling Spring Boot graceful shutdown). Any extra arguments passed to
# `docker run <image> <args...>` are forwarded to the application via "$@".
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]

# =============================================================================
# CardDemo Java - Application Container Image
# =============================================================================
# Multi-stage build for the carddemo-java Spring Boot service (the Java 25 LTS +
# Spring Boot 3.x migration of the AWS CardDemo mainframe credit-card management
# application). This file has no COBOL equivalent; it is cross-cutting delivery
# infrastructure required by AAP 0.3.1 / 0.4.1 ("Dockerfile (application image)")
# and is consumed by the `app` service in docker-compose.yml.
#
#   Stage 1 (build)   - compiles the sources with the JDK 25 toolchain and the
#                       project's committed Maven Wrapper, producing the Spring
#                       Boot executable (fat) JAR target/carddemo-java.jar.
#   Stage 2 (runtime) - a slim JRE 25 image that carries ONLY that fat JAR and
#                       runs it as a dedicated, unprivileged (non-root) user.
#
# Design constraints honoured (AAP 0.7.7 / 0.8.1):
#   * No credentials or secrets are baked into any layer. Every runtime setting
#     (datasource URL/user/password, AWS / LocalStack endpoint,
#     LOCALSTACK_AUTH_TOKEN, the active Spring profile, ...) is injected at run
#     time via environment variables / docker-compose, never via this Dockerfile.
#     `docker history` on the resulting image therefore reveals no secrets.
#   * Java 25 LTS only. No Google Cloud / Node.js tooling - Environment 2 is out
#     of scope (AAP 0.3.2).
#   * The runtime image stays minimal: no curl, wget, or other extra OS packages
#     are installed, keeping the CVE surface small for the OWASP zero-critical /
#     zero-high gate (AAP 0.8.7).
#
#   Build:  docker build -t carddemo-java .
#   Run:    docker run --rm -p 8080:8080 \
#                 -e SPRING_PROFILES_ACTIVE=local \
#                 -e JAVA_OPTS="-XX:MaxRAMPercentage=75.0" \
#                 carddemo-java
#
# Build-context note: the COPY instructions below are explicit (mvnw, .mvn,
# pom.xml, src) rather than "COPY . .", so transient build output such as
# target/ is never pulled into the image even when no .dockerignore is present.
# =============================================================================


# -----------------------------------------------------------------------------
# Stage 1 - build: compile and package the Spring Boot fat JAR using JDK 25.
# -----------------------------------------------------------------------------
# eclipse-temurin:25-jdk supplies the full Java 25 LTS toolchain. The committed
# Maven Wrapper (only-script distribution) self-provisions Maven 3.9.x at build
# time; it needs no curl/wget/unzip in the image because it transparently falls
# back to a pure-Java download and to `tar` extraction (both available here).
FROM eclipse-temurin:25-jdk AS build

# All build work happens under a single, predictable working directory.
WORKDIR /workspace/app

# ---- Dependency layer (cached independently of the application source) -------
# Copy ONLY the wrapper and the build descriptor first. As long as pom.xml and
# the wrapper are unchanged, Docker reuses the cached dependency layer produced
# by the next RUN and skips re-downloading the Maven distribution and the project
# dependencies, so edits under src/ rebuild quickly (AAP: "warm dependencies
# before copying src").
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Ensure the wrapper is executable (it is committed +x, but COPY can drop the
# executable bit on some host platforms) and pre-fetch the Maven distribution
# plus every project dependency into this layer's local repository.
RUN chmod +x ./mvnw \
 && ./mvnw -B dependency:go-offline

# ---- Application layer -------------------------------------------------------
# Bring in the sources and build the executable JAR. Tests are skipped here
# (-DskipTests): image packaging is not the test gate - CI runs the full
# `mvn verify` (including the *IT integration suite under -Pintegration, which
# needs Testcontainers). The spring-boot-maven-plugin repackages the artifact in
# place as a runnable fat JAR under the stable name target/carddemo-java.jar
# (fixed via <finalName> in pom.xml).
COPY src/ src/
RUN ./mvnw -B -DskipTests clean package


# -----------------------------------------------------------------------------
# Stage 2 - runtime: minimal JRE 25 image that runs the fat JAR as a non-root
# user.
# -----------------------------------------------------------------------------
# eclipse-temurin:25-jre is a slim Java 25 runtime (no compiler), which keeps the
# shipped image small and reduces the attack / CVE surface.
FROM eclipse-temurin:25-jre AS runtime

# OCI image metadata (informational only - contains no secrets).
LABEL org.opencontainers.image.title="carddemo-java" \
      org.opencontainers.image.description="Java 25 LTS + Spring Boot 3.x migration of the AWS CardDemo mainframe credit-card management application" \
      org.opencontainers.image.source="https://github.com/aws-samples/aws-mainframe-modernization-carddemo" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:25-jre"

# ---- Non-root runtime user ---------------------------------------------------
# Create a dedicated system group and user with a fixed UID/GID (predictable for
# Kubernetes securityContext and bind-mount/volume ownership). The account owns
# no home directory and uses a nologin shell, minimising attack surface.
RUN groupadd --system --gid 1001 carddemo \
 && useradd  --system --uid 1001 --gid carddemo \
             --no-create-home --home-dir /app --shell /usr/sbin/nologin carddemo

WORKDIR /app

# Copy the repackaged fat JAR from the build stage under a stable runtime name
# and hand ownership to the unprivileged user. Only this single artifact crosses
# the stage boundary - the JDK, Maven, and source tree are all left behind.
COPY --from=build --chown=carddemo:carddemo /workspace/app/target/carddemo-java.jar /app/app.jar

# Spring Boot listens on 8080 by default (application.yml: server.port
# ${SERVER_PORT:8080}); this matches `curl http://localhost:8080/actuator/health`.
EXPOSE 8080

# Runtime-tunable environment with safe, NON-secret defaults:
#   JAVA_OPTS              - extra JVM flags, word-split by the entrypoint shell.
#                           Java 25 is container-aware by default (it honours
#                           cgroup CPU/memory limits), so heap sizing generally
#                           needs no flags; pass e.g. -XX:MaxRAMPercentage to tune.
#                           Virtual threads are opt-in via the application's
#                           `spring.threads.virtual.enabled` property and are not
#                           forced here.
#   SPRING_PROFILES_ACTIVE - active Spring profile; defaults to 'local' to match
#                           docker-compose. Override at run time as needed.
# Datasource and AWS credentials are intentionally NOT defined here: they are
# supplied at run time through the environment / compose (AAP 0.8.1).
ENV JAVA_OPTS="" \
    SPRING_PROFILES_ACTIVE="local"

# Container health probe against the Spring Boot Actuator health endpoint, which
# SecurityConfig exposes publicly (permitAll on /actuator/health/**). The minimal
# JRE image ships without curl or wget, so - to avoid adding OS packages and CVE
# surface - the probe uses only bash's built-in /dev/tcp pseudo-device to issue
# an HTTP/1.1 GET and asserts the Actuator "status":"UP" payload. The port tracks
# SERVER_PORT (default 8080); start-period covers JVM + Flyway startup time.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT:-8080} || exit 1; printf 'GET /actuator/health HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 || exit 1; grep -q '\"status\":\"UP\"' <&3 || exit 1"]

# Drop privileges: everything from here runs as the unprivileged carddemo user.
USER carddemo

# Launch the application. The shell form with `exec` lets JAVA_OPTS be split into
# individual JVM arguments while ensuring the JVM replaces the shell as PID 1, so
# it receives SIGTERM directly for graceful Spring Boot shutdown. Any extra
# `docker run` arguments are forwarded to the application via "$@".
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]

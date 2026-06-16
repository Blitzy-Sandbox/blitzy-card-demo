# =============================================================================
# CardDemo Java - Application Container Image
# =============================================================================
# Multi-stage Docker build for the carddemo-java Spring Boot service, the
# Java 25 LTS + Spring Boot 3.x migration of the AWS CardDemo COBOL mainframe
# application (source commit SHA 27d6c6f). This image is consumed by the `app`
# service in docker-compose.yml, which builds from this Dockerfile and runs the
# service alongside PostgreSQL, LocalStack, Jaeger, Prometheus, and Grafana.
#
# Design goals (AAP sections 0.3.1, 0.4.1, 0.7.7, 0.8.1):
#   * Multi-stage build: a JDK stage compiles and packages the executable
#     (fat) JAR; a slim JRE stage runs it -> small, minimal runtime surface.
#   * Java 25 LTS throughout (matches pom.xml <release>25</release>).
#   * Runs as a dedicated NON-ROOT user (defense in depth / hardening).
#   * ZERO credentials or secrets baked into any layer; every runtime value
#     (datasource URL/credentials, AWS endpoint, LOCALSTACK_AUTH_TOKEN, active
#     Spring profile) is injected at run time via environment variables.
#   * Minimal attack surface for the OWASP / zero-CVE posture: no extra OS
#     packages are installed. The container health probe reuses the JRE's own
#     java.net.http client (no curl/wget added) via a tiny class compiled in
#     the build stage.
#   * Builder-agnostic: uses only classic Dockerfile features (no BuildKit-only
#     syntax) so `docker build` succeeds on both BuildKit and the legacy
#     builder.
#
# Build context: the carddemo-java/ project directory. Build with:
#   docker build -t carddemo-java .
#
# No GCP/Node tooling is present: Environment 2 (GCP via localgcp) is out of
# scope for this Java/AWS migration (AAP section 0.3.2).
# =============================================================================

# Base images are exposed as build args so the Java baseline can be overridden
# (e.g. pinned to a digest in CI for supply-chain hardening) without editing
# the stage definitions. Declared before the first FROM so they are usable in
# the FROM instructions below.
ARG JDK_IMAGE=eclipse-temurin:25-jdk
ARG JRE_IMAGE=eclipse-temurin:25-jre

# -----------------------------------------------------------------------------
# Stage 1: build
# -----------------------------------------------------------------------------
# Full JDK 25 image. Compiles the application with the project's Maven wrapper
# (mvnw -> Apache Maven 3.9.x) and produces the Spring Boot executable JAR.
# -----------------------------------------------------------------------------
FROM ${JDK_IMAGE} AS build

# All build work happens under /build.
WORKDIR /build

# ---- Container health probe (compiled here, run in the runtime stage) -------
# The slim JRE runtime image ships no curl/wget and no jdk.compiler, so we
# cannot rely on an HTTP CLI or single-file source mode for HEALTHCHECK. To
# keep the runtime image free of extra OS packages (zero-CVE posture), we
# compile a tiny probe HERE (where javac exists) and copy only the resulting
# .class into the runtime stage. At run time it uses java.net.http (present in
# the JRE) to GET /actuator/health and exits 0 (healthy) or 1 (unhealthy).
# This layer depends only on the JDK, so it is cached across application
# changes. The Java source contains no single quotes, so each line is emitted
# verbatim as a single-quoted printf argument (portable, no heredoc required).
RUN set -eu; \
    mkdir -p /opt/healthcheck; \
    printf '%s\n' \
      'import java.net.URI;' \
      'import java.net.http.HttpClient;' \
      'import java.net.http.HttpRequest;' \
      'import java.net.http.HttpResponse;' \
      'import java.time.Duration;' \
      '' \
      '/** Minimal, dependency-free liveness/readiness probe for HEALTHCHECK. */' \
      'public final class HealthCheck {' \
      '    private HealthCheck() { }' \
      '    public static void main(String[] args) {' \
      '        String url = System.getenv("HEALTHCHECK_URL");' \
      '        if (url == null || url.isBlank()) {' \
      '            url = "http://localhost:8080/actuator/health";' \
      '        }' \
      '        try {' \
      '            HttpClient client = HttpClient.newBuilder()' \
      '                    .connectTimeout(Duration.ofSeconds(3)).build();' \
      '            HttpRequest request = HttpRequest.newBuilder()' \
      '                    .uri(URI.create(url)).timeout(Duration.ofSeconds(3))' \
      '                    .GET().build();' \
      '            HttpResponse<Void> response =' \
      '                    client.send(request, HttpResponse.BodyHandlers.discarding());' \
      '            if (response.statusCode() == 200) {' \
      '                System.exit(0);' \
      '            }' \
      '            System.err.println("Health check failed: HTTP " + response.statusCode());' \
      '            System.exit(1);' \
      '        } catch (Exception e) {' \
      '            System.err.println("Health check error: " + e);' \
      '            System.exit(1);' \
      '        }' \
      '    }' \
      '}' \
      > /opt/healthcheck/HealthCheck.java; \
    javac -d /opt/healthcheck /opt/healthcheck/HealthCheck.java; \
    rm -f /opt/healthcheck/HealthCheck.java

# ---- Dependency layer (cached unless pom.xml / wrapper change) --------------
# Copy ONLY the Maven wrapper, its configuration, and the POM first, then warm
# the local repository. This way Docker can reuse the (slow) dependency layer
# whenever only application source changes, dramatically speeding up rebuilds.
COPY mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./

# Ensure the wrapper is executable (defensive: a Windows checkout may drop the
# executable bit) and pre-fetch all project dependencies into the image's local
# Maven repository.
RUN chmod +x mvnw \
 && ./mvnw -B -ntp -DskipTests dependency:go-offline

# ---- Application layer ------------------------------------------------------
# Now copy the sources and build the Spring Boot fat JAR. Tests are skipped for
# image builds (-DskipTests); the Testcontainers/LocalStack integration tests
# (*IT.java) are gated behind the `integration` Maven profile and are not run
# here. The spring-boot-maven-plugin `repackage` goal produces the executable
# JAR at target/<artifactId>-<version>.jar.
COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests clean package

# -----------------------------------------------------------------------------
# Stage 2: runtime
# -----------------------------------------------------------------------------
# Slim JRE 25 image. Contains only the Java runtime, the application JAR, and
# the tiny health-probe class -> minimal footprint and attack surface.
# -----------------------------------------------------------------------------
FROM ${JRE_IMAGE} AS runtime

# Standard OCI image metadata. No secrets; the revision references the original
# COBOL source commit for traceability (COBOL sources are not copied; AAP 0.8.1).
LABEL org.opencontainers.image.title="carddemo-java" \
      org.opencontainers.image.description="Java 25 + Spring Boot migration of the AWS CardDemo COBOL mainframe application (runtime image)" \
      org.opencontainers.image.source="https://github.com/aws-samples/aws-mainframe-modernization-carddemo" \
      org.opencontainers.image.revision="27d6c6f" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:25-jre"

# Create a dedicated, unprivileged system user/group with a fixed UID/GID
# (10001) for predictable, rootless operation under restrictive orchestrators.
# The account has no login shell. /app holds the application and is owned by it.
RUN groupadd --system --gid 10001 carddemo \
 && useradd --system --uid 10001 --gid 10001 \
            --home-dir /app --no-create-home --shell /usr/sbin/nologin carddemo \
 && mkdir -p /app \
 && chown carddemo:carddemo /app

WORKDIR /app

# Copy the executable JAR from the build stage. The repackaged fat JAR is the
# only *.jar in target/ (the original thin JAR is renamed *.jar.original and is
# excluded by this glob), so it resolves to a single file -> app.jar.
COPY --from=build --chown=carddemo:carddemo /build/target/*.jar /app/app.jar

# Copy the precompiled, dependency-free health-probe class (see build stage).
COPY --from=build --chown=carddemo:carddemo /opt/healthcheck/ /app/healthcheck/

# Extra JVM flags appended at startup. Empty by default; supply at run time,
# e.g. -e JAVA_OPTS="-XX:MaxRAMPercentage=60". NEVER place secrets here.
ENV JAVA_OPTS=""

# Spring Boot's default HTTP port (also serves /actuator/health). The active
# Spring profile is intentionally NOT baked in: it is selected at run time via
# SPRING_PROFILES_ACTIVE (docker-compose sets it to `local`), keeping the image
# environment-agnostic (12-factor).
EXPOSE 8080

# Drop privileges: everything below runs as the non-root carddemo user.
USER carddemo

# Container-native health probe hitting /actuator/health with the JRE's own
# HTTP client (no curl/wget). start-period covers app startup + Flyway
# migrations; the small JVM flags keep each probe lightweight.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["java", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xms8m", "-Xmx32m", \
         "-cp", "/app/healthcheck", "HealthCheck"]

# Launch the service. `sh -c "exec java ..."` expands $JAVA_OPTS while `exec`
# replaces the shell so the JVM becomes PID 1 and receives SIGTERM directly
# (clean, graceful Spring Boot shutdown). -XX:MaxRAMPercentage=75.0 sets a sane
# container-aware heap baseline (JDK container support is on by default) and can
# be overridden via JAVA_OPTS. All datasource/AWS/profile configuration is
# provided through the environment at run time.
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75.0 $JAVA_OPTS -jar /app/app.jar"]

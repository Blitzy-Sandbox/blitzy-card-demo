# =============================================================================
#  CardDemo — Greenfield Java 25 LTS + Spring Boot 3.x Migration
#  Multi-stage application container image
# =============================================================================
#
#  PROVENANCE & TRACEABILITY
#  Brand-new, greenfield infrastructure artifact. It has NO source equivalent in
#  the legacy AWS CardDemo COBOL/CICS/VSAM/JCL/BMS estate (the mainframe had no
#  container-packaging step). The legacy build/install cadence — the DFHCSDUP
#  resource-install job (app/jcl/CBADMCDJ.jcl) plus the BUILDBAT/BUILDBMS/BUILDONL
#  procedures (samples/jcl) — is wholly REPLACED by the Maven build executed
#  INSIDE this image and the Spring Boot plugin's executable-JAR packaging.
#  Traceability to the frozen COBOL baseline is by original commit SHA 27d6c6f.
#
#  MINIMAL CHANGE CLAUSE (AAP §0.7.1)
#  Only what is strictly required to BUILD and RUN the migrated application is
#  present, and every technology choice is documented at its point of use. No
#  tooling beyond the build (Maven, via the project's own wrapper) and the
#  runtime (a JRE) is added. AWS/DB configuration is injected at RUNTIME and is
#  NEVER baked into the image (no secrets, no .env, no AWS credentials).
#
#  WHY JAVA 25 (Eclipse Temurin)
#  The migration target is Java 25 LTS / OpenJDK 25 (AAP §0.5.1; tech-spec L319,
#  L491–L496). Eclipse Temurin is the TCK-tested OpenJDK distribution; the 25
#  tags resolve to OpenJDK 25.0.3 — a later patch of the same 25 LTS line that
#  AAP §0.5.1 pins as "25.0.2-compatible". pom.xml sets <java.version>25</…> and
#  <maven.compiler.release>25</…>, so the compiler emits class-file major version
#  69 and a Java 25 runtime is required to launch the result.
#
#  IMAGE CONTRACT
#  Consumed by docker-compose.yml (the `app` service builds from this Dockerfile).
#  The build context is the carddemo-java/ project directory; all COPY paths below
#  are relative to it.
# =============================================================================


# -----------------------------------------------------------------------------
# STAGE 1 — build: compile the sources and repackage the executable Spring Boot
#                  JAR. This stage is discarded; its size and build-only tooling
#                  never reach the final image.
#
# Base: eclipse-temurin:25-jdk — a FULL JDK 25 (includes javac). It is paired
# with the project's OWN Maven Wrapper (./mvnw — a declared dependency of this
# file) instead of a pre-baked Maven, because the wrapper pins EXACTLY Maven
# 3.9.9 (.mvn/wrapper/maven-wrapper.properties), the version mandated by
# AAP §0.5.1. The pinned base tag keeps the build reproducible.
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

# All build work happens under /build, kept separate from the runtime /app.
WORKDIR /build

# The committed Maven Wrapper ships ONLY maven-wrapper.properties — the
# maven-wrapper.jar is intentionally not checked in — so ./mvnw downloads the
# wrapper jar on first use via curl or wget. The slim Temurin base image has
# neither, so curl is installed here (build stage ONLY; it is absent from the
# final runtime image). This is the minimal addition needed to run the pinned
# build tool, documented per the Minimal Change Clause (AAP §0.7.1).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# --- Dependency layer (cached independently of application source) -----------
# Copy ONLY the build descriptor and the wrapper first. Docker then re-uses the
# (expensive) dependency-download layer from cache on every build where the
# sources change but pom.xml / the wrapper do not.
COPY pom.xml ./
COPY mvnw mvnw.cmd ./
COPY .mvn/ .mvn/

# Guard the wrapper's executable bit (it can be lost on Windows / zip checkouts).
RUN chmod +x mvnw

# Warm the local Maven repository: bootstrap Maven 3.9.9 via the wrapper and
# resolve the full dependency closure declared in pom.xml (Spring Boot 3.5.11
# BOM, Spring Cloud AWS 3.3.0, Testcontainers 2.0.3, Flyway, …). Flags:
# -B = batch / non-interactive, -ntp = suppress transfer-progress noise.
RUN ./mvnw -B -ntp dependency:go-offline

# --- Source + package --------------------------------------------------------
# Copy the sources last (they change most often) and build the executable JAR.
# Tests are deliberately SKIPPED during the image build: the integration/E2E
# suites require Docker + Testcontainers/LocalStack (pom.xml defaults skipITs
# to true) and must not run while building an image. Tests belong to CI /
# `mvn verify -Pintegration`; skipping them keeps the image build deterministic
# and fast.
COPY src/ src/
RUN ./mvnw -B -ntp clean package -DskipTests

# Result: /build/target/carddemo-java.jar — the Spring Boot repackaged (fat)
# executable JAR (pom <finalName>carddemo-java</finalName>). The repackage goal
# renames the plain JAR to *.jar.original, so target/*.jar matches exactly one
# artifact and the glob copy in stage 2 is unambiguous.


# -----------------------------------------------------------------------------
# STAGE 2 — runtime: run the JAR on a minimal JRE 25. Contains only the JRE and
#                    the application JAR — no Maven, no JDK, no source.
#
# Base: eclipse-temurin:25-jre-alpine — a JRE-only Alpine image (musl libc),
# chosen for the smallest secure runtime footprint that still provides a full
# Java 25 runtime. All Spring Boot dependencies here (Tomcat, Hibernate, the
# PostgreSQL JDBC driver, Flyway, the AWS SDK) are pure Java and run on musl.
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS runtime

# --- Least privilege: run as a non-root system user (AAP §0.7.2 security) -----
# BusyBox adduser/addgroup syntax: -S creates a system account (no password,
# no login shell). The /app directory is created and handed to that user.
RUN addgroup -S appgroup \
    && adduser -S appuser -G appgroup \
    && mkdir -p /app \
    && chown appuser:appgroup /app

WORKDIR /app

# Copy ONLY the executable JAR out of the build stage, owned by the runtime user.
COPY --from=build --chown=appuser:appgroup /build/target/*.jar app.jar

# Spring Boot serves the REST API and the Actuator health endpoint on port 8080
# by default (tech-spec L978). EXPOSE documents the port; docker-compose maps it.
EXPOSE 8080

# JVM options are supplied at RUNTIME via JAVA_OPTS (e.g. heap or GC flags) and
# default to empty so the JVM's container-aware ergonomics apply unchanged. The
# active Spring profile is provided at runtime by docker-compose through the
# SPRING_PROFILES_ACTIVE environment variable (read natively by Spring Boot);
# the `local` profile is deliberately NOT baked in so one image runs everywhere.
ENV JAVA_OPTS=""

# Switch to the unprivileged user for the health check and the application.
USER appuser

# Container health probe against the Actuator health endpoint. Uses the BusyBox
# wget present in the Alpine JRE image (curl is not installed): `-q -O -` makes
# it quiet and discards the body; wget exits non-zero on a connection failure or
# an HTTP error, which `|| exit 1` reports as unhealthy. start-period allows for
# Spring Boot + Flyway startup before failures are counted.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q -O - http://localhost:8080/actuator/health || exit 1

# Launch via `sh -c "exec java …"` so that:
#   * ${JAVA_OPTS} undergoes shell word-splitting/expansion (a pure exec-form
#     ENTRYPOINT cannot expand environment variables),
#   * `exec` replaces the shell so the JVM becomes PID 1 and receives SIGTERM for
#     graceful container shutdown,
#   * any extra arguments passed to `docker run` ("$@") are forwarded to the app.
# No credentials or secrets are embedded; all AWS/DB config arrives via env vars.
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /app/app.jar \"$@\"", "--"]

# =============================================================================
# CardDemo — Multi-Stage Dockerfile for Java 25 + Spring Boot 3.x Application
# =============================================================================
# This Dockerfile builds and packages the CardDemo credit card management
# application, migrated from COBOL/CICS/VSAM to Java 25 LTS + Spring Boot 3.x.
#
# Build stages:
#   1. builder  — Compiles the application using Maven wrapper and JDK 25
#   2. runtime  — Runs the application using JRE 25 (minimal Alpine image)
#
# Usage:
#   docker build -t carddemo:latest .
#   docker run -p 8080:8080 --env-file .env carddemo:latest
#
# Networking note:
#   In environments with restricted Docker networking (Docker-in-Docker,
#   corporate proxies, certain CI/CD runners), the Maven dependency download
#   step may fail with "Connection reset" errors. In these cases, use:
#     docker build --network=host -t carddemo:latest .
#
# Security:
#   - Non-root user (appuser) in runtime stage
#   - No credentials or secrets baked into image layers
#   - Minimal Alpine base image for reduced attack surface
# =============================================================================

# ---------------------------------------------------------------------------
# Stage 1: Build — Compile the application with Maven and JDK 25
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk-alpine AS builder

# Set working directory for the build context
WORKDIR /app

# Copy Maven wrapper configuration first for Docker layer caching.
# Changes to source code will not invalidate the dependency download layer.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Make the Maven wrapper script executable
RUN chmod +x mvnw

# Download all project dependencies into the local Maven repository.
# This layer is cached and only rebuilt when pom.xml or .mvn/ change,
# dramatically speeding up subsequent builds.
#
# NOTE: If this step fails with "Connection reset" or network errors,
# rebuild with: docker build --network=host -t carddemo:latest .
# This is common in Docker-in-Docker or restrictive networking environments.
RUN ./mvnw dependency:go-offline -B \
    -Dmaven.repo.local=/app/.m2/repository

# Copy the application source code
COPY src/ src/

# Build the application JAR, skipping tests (tests run in CI pipeline).
# The Spring Boot Maven plugin produces an executable uber-JAR in target/.
RUN ./mvnw clean package -DskipTests -B \
    -Dmaven.repo.local=/app/.m2/repository \
    && mv target/*.jar target/app.jar

# ---------------------------------------------------------------------------
# Stage 2: Runtime — Minimal JRE image for production deployment
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS runtime

# Metadata labels for container identification and traceability
LABEL maintainer="CardDemo Team" \
      description="CardDemo — Credit Card Management Application (Java 25 + Spring Boot 3.x)" \
      version="1.0.0" \
      source.migration="COBOL/CICS/VSAM → Java/Spring Boot/PostgreSQL/AWS"

# Set working directory for the application
WORKDIR /app

# Create a dedicated non-root user and group for running the application.
# Running containers as non-root is a security best practice that limits
# the blast radius of any container escape vulnerability.
RUN addgroup -S appgroup && \
    adduser -S appuser -G appgroup && \
    mkdir -p /app/logs && \
    chown -R appuser:appgroup /app

# Copy the built JAR from the builder stage.
# Only the final artifact is included — no source code, build tools, or
# intermediate compilation outputs are present in the runtime image.
COPY --from=builder --chown=appuser:appgroup /app/target/app.jar app.jar

# Set the default Spring profile to 'local' for development convenience.
# Override at runtime with: docker run -e SPRING_PROFILES_ACTIVE=prod ...
ENV SPRING_PROFILES_ACTIVE=local

# JVM runtime configuration via JAVA_OPTS environment variable.
# These defaults are suitable for containerized deployments:
#   -XX:+UseContainerSupport  — Respect container memory/CPU limits (default in JDK 25)
#   -XX:MaxRAMPercentage=75.0 — Use up to 75% of container memory for heap
#   -Djava.security.egd       — Use non-blocking entropy source for faster startup
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

# Expose the Spring Boot default HTTP port
EXPOSE 8080

# Health check using the Spring Boot Actuator health endpoint.
# Ensures container orchestrators (Docker, Kubernetes) can verify
# the application is running and responsive.
#   --interval=30s  — Check every 30 seconds
#   --timeout=3s    — Fail if no response within 3 seconds
#   --start-period=60s — Allow 60 seconds for application startup
#   --retries=3     — Mark unhealthy after 3 consecutive failures
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# Switch to non-root user for runtime execution
USER appuser

# Launch the Spring Boot application.
# The 'exec' form of ENTRYPOINT ensures proper signal handling (SIGTERM).
# JAVA_OPTS is expanded at runtime via the shell form wrapper.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

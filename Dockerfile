# syntax=docker/dockerfile:1.7
# ---------------------------------------------------------------------------
# AWS CardDemo (COBOL -> Java/Spring Boot migration target)
# Multi-stage Docker build:
#   stage 1: Maven 3.9 + Temurin JDK 17, produces an executable Spring Boot jar
#   stage 2: Temurin JRE 17 (Alpine), runs the jar as non-root user
# Per AAP §0.3.1 "Dockerfile NEW - multi-stage Docker build"
# ---------------------------------------------------------------------------

ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-17
ARG RUNTIME_IMAGE=eclipse-temurin:17-jre-alpine

# ===== Stage 1: build =====
FROM ${MAVEN_IMAGE} AS build
WORKDIR /workspace

# Copy build descriptor first to leverage Docker layer cache for dependencies
COPY pom.xml ./
COPY .mvn/ .mvn/
COPY mvnw mvnw.cmd ./

# Pre-fetch dependencies (cached layer if pom.xml unchanged)
RUN mvn -B -q dependency:go-offline -DskipTests || true

# Copy source tree
COPY src/ src/

# Build the executable JAR
RUN mvn -B clean package -DskipTests \
    && ls -la target/

# ===== Stage 2: runtime =====
FROM ${RUNTIME_IMAGE} AS runtime
WORKDIR /app

# Create non-root runtime user
RUN addgroup -S spring && adduser -S spring -G spring

# Copy executable jar from build stage
COPY --from=build /workspace/target/carddemo.jar /app/app.jar

# Drop privileges
USER spring:spring

# Container entry
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

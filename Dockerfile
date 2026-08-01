# ******************************************************************
# * File         : Dockerfile
# * Application  : CardDemo
# * Type         : Container image definition (multi-stage, JDK 25)
# * Function     : Builds and packages the single executable JAR that replaces
# *                the z/OS execution substrate of the frozen COBOL corpus in
# *                app/ - one modular monolith, explicitly not microservices,
# *                because EXEC CICS SYNCPOINT ROLLBACK in
# *                app/cbl/COACTUPC.cbl spans an account write and a customer
# *                write in a single unit of work and app/cbl/CBTRN02C.cbl
# *                commits three writes together. There is no legacy equivalent
# *                of this file; the mainframe had no container image. It
# *                conceptually supersedes the z/OS compile templates
# *                samples/BATCMP.jcl, samples/CICCMP.jcl and samples/BMSCMP.jcl.
# ******************************************************************
# * Copyright Amazon.com, Inc. or its affiliates.
# * All Rights Reserved.
# *
# * Licensed under the Apache License, Version 2.0 (the "License").
# * You may not use this file except in compliance with the License.
# * You may obtain a copy of the License at
# *
# *    http://www.apache.org/licenses/LICENSE-2.0
# *
# * Unless required by applicable law or agreed to in writing,
# * software distributed under the License is distributed on an
# * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# * either express or implied. See the License for the specific
# * language governing permissions and limitations under the License
# ******************************************************************

# --------------------------------------------------------------------------
# Stage 1 - build
# --------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk-noble AS build

WORKDIR /workspace

ENV MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=256m"

# The Temurin Noble base image ships neither curl nor wget nor mvn, while
# .mvn/wrapper/maven-wrapper.properties pins distributionType=only-script - so the
# lite mvnw launcher must fetch the pinned Maven 3.9.11 distribution over HTTPS
# itself. With no downloader present the wrapper aborts before Maven ever starts,
# with "no downloader available: install curl or wget". curl is therefore
# installed here, in the BUILD stage only; it never reaches the runtime image
# assembled below, so the shipped container gains no network tooling.
#
# This does not weaken reproducibility. maven-wrapper.properties carries
# distributionSha256Sum and the wrapper verifies the downloaded archive against it
# before use, so the Maven version in play is fixed by checksum rather than by
# whatever happens to be installed. ca-certificates is already present in the base
# image and is deliberately not reinstalled.
#
# DL3008 is waived on purpose: pinning an exact Ubuntu revision for a transient
# build-stage tool makes the build fail the moment that revision leaves the
# archive on a security update, which trades real reproducibility for fragility.
# The artefact that must be deterministic - the Maven distribution - is pinned by
# URL and SHA-256 above.
# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Copy only the build descriptor and wrapper first so the dependency layer is
# cached independently of source changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x ./mvnw && ./mvnw -B -ntp dependency:go-offline

COPY src/ src/

# The OWASP scan needs an NVD feed and is executed by CI, not by the image
# build; the image build compiles, tests and packages.
RUN ./mvnw -B -ntp -Ddependency-check.skip=true clean package

# --------------------------------------------------------------------------
# Stage 2 - runtime
# --------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-noble AS runtime

# Least privilege: never run the application as root.
RUN groupadd --system --gid 10001 carddemo \
 && useradd  --system --uid 10001 --gid carddemo --create-home --shell /usr/sbin/nologin carddemo

WORKDIR /app

COPY --from=build --chown=carddemo:carddemo /workspace/target/carddemo-*.jar /app/carddemo.jar

USER carddemo:carddemo

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom" \
    SPRING_PROFILES_ACTIVE=""

# Composite readiness check; replaces app/jcl/OPENFIL.jcl and
# app/jcl/CLOSEFIL.jcl file-availability management.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD ["/bin/sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health/readiness HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q UP <&3"]

ENTRYPOINT ["java", "-jar", "/app/carddemo.jar"]

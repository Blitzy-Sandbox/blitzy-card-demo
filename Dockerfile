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

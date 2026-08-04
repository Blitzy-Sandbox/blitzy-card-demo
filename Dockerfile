# ******************************************************************
# * Program     : Dockerfile
# * Application : CardDemo
# * Type        : Container image definition - two stage, JDK 25 build
# *               and JRE 25 runtime, for the CardDemo application
# *               container
# * Function    : Builds and ships the ONE executable Spring Boot JAR
# *               that replaces the z/OS execution substrate of the
# *               frozen COBOL corpus in app/. The image is a modular
# *               monolith and explicitly NOT a set of microservices:
# *               EXEC CICS SYNCPOINT ROLLBACK at
# *               app/cbl/COACTUPC.cbl:4100 spans an account rewrite and
# *               a customer rewrite inside one unit of work, and
# *               2000-POST-TRANSACTION at app/cbl/CBTRN02C.cbl:424
# *               commits a transaction category balance upsert, an
# *               account update and a transaction insert together.
# *               Splitting those writes across services would need
# *               compensating transactions and would forfeit parity, so
# *               one JAR in one container is the deployment shape.
# * Source      : No legacy equivalent - the mainframe had no container
# *               image. This file conceptually supersedes the complete
# *               z/OS build tooling, namely samples/jcl/BATCMP.jcl,
# *               samples/jcl/CICCMP.jcl, samples/jcl/BMSCMP.jcl,
# *               samples/proc/BUILDBAT.prc, samples/proc/BUILDBMS.prc
# *               and samples/proc/BUILDONL.prc, which compiled the 28
# *               programs of app/cbl against the 28 copybooks of app/cpy
# *               and assembled the 17 mapsets of app/bms, together with
# *               the JES2 execution of the 29 members of app/jcl.
# *               Nothing is ported from samples/; the whole tree is out
# *               of scope and .dockerignore keeps it out of the build
# *               context - all @ 7756d89
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
#
# There is deliberately NO `# syntax=` parser directive. Every
# instruction below is plain Dockerfile: no RUN --mount, no COPY
# --chmod, no heredoc. Pinning an external frontend image would add a
# registry download that buys nothing, so the built-in frontend is used
# and the Apache-2.0 banner above stays on line 1.
#
# WHAT THIS IMAGE IS
#
# One deployable artefact: target/carddemo-<version>.jar, repackaged by
# spring-boot-maven-plugin, running on a Temurin JRE 25 as an
# unprivileged user. It serves the 17 REST endpoints that replace the 17
# CICS screen programs and hosts the 5 stage Spring Batch pipeline that
# replaces the JCL job stream. Both live in the same JVM for the
# atomicity reason given in the banner.
#
# HOW TO BUILD
#
#   docker build --pull -t carddemo:local .
#
# The build context is filtered by .dockerignore, which excludes .git,
# target, .env and .env.* , every key/certificate/credential pattern and
# samples/ . Only pom.xml, .mvn/, mvnw, src/, app/, localstack-init/ and
# the four repository files the unit tier reads - .env.example,
# docker-compose.yml, .github/ and, through app/, the frozen corpus - are
# COPYed, so nothing else can reach any layer even if it survives the
# filter. See REQUIRED TEST CONTRACTS below for why each one is there.
#
# WHAT THIS BUILD IS NOT
#
# It is NOT the full verification gate. `mvn package` runs the enforcer
# checks, compiles with warnings escalated to errors and executes the
# surefire unit tests, and that is all it can honestly claim. The
# coverage floor (jacoco check-line-coverage), the vulnerability scan
# (dependency-check) and the failsafe integration tier are all bound to
# the `verify` phase in pom.xml and therefore do not run here, by
# design: the integration tier needs Testcontainers and a Docker socket
# and the scan needs the NVD feed, neither of which a container build
# should assume. The full gate is owned by .github/workflows/build.yml:
#
#   ./mvnw -B -ntp -Ddependency-check.skip=true clean verify
#   ./mvnw -B -ntp org.owasp:dependency-check-maven:12.1.0:check
#
# A successful image build must never be reported as a pass of that
# gate.
#
# Nor does it scan the produced image. No image scanner runs as part of
# this build, so an image build must never be recorded as an image scan.
# Scanning is reproducible on
# demand, because both bases are pinned by digest below rather than by a
# moving tag; scan the exact content with, for example:
#
#   trivy image --ignore-unfixed carddemo:local
#
# Dependency-level vulnerabilities are covered separately by the
# dependency-check invocation above, which scans what is packaged INSIDE
# the jar rather than the base layers underneath it. The two are
# complementary; neither substitutes for the other.
#
# HOW TO RUN
#
# The application takes ALL of its configuration from the environment;
# see .env.example for the full key contract. Bring the substrate up
# first, then join the container to the same network so the service
# names resolve. Container names carry the ${CLONE_INDEX} suffix that
# docker-compose.yml applies, so adjust both if CLONE_INDEX is set:
#
#   docker compose up -d
#   docker run -d --name carddemo-app --network carddemo_default \
#     -p 8080:8080 \
#     -e SPRING_PROFILES_ACTIVE=local \
#     -e SERVER_PORT=8080 \
#     -e JWT_SIGNING_KEY="$JWT_SIGNING_KEY" \
#     -e POSTGRES_HOST=postgres \
#     -e POSTGRES_DB=carddemo -e POSTGRES_USER=carddemo \
#     -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
#     -e AWS_ENDPOINT_URL=http://localstack:4566 \
#     -e AWS_REGION=us-east-1 -e AWS_DEFAULT_REGION=us-east-1 \
#     -e OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4318 \
#     --read-only --tmpfs /tmp:rw,nosuid,nodev,size=64m \
#     carddemo:local
#
# The container name carddemo-app is not cosmetic: it is one of the two
# scrape targets in observability/prometheus.yml, the other being
# host.docker.internal:8080 for a JVM started on the host with
# `SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run`.
#
# KEY CONFIGURATION AND DEFAULTS
#
# Baked in, and nothing else is:
#   SPRING_PROFILES_ACTIVE  empty. The image names NO profile; see the
#                           note at the ENV instruction below.
#   JAVA_TOOL_OPTIONS       container aware heap sizing, fail fast on OOM,
#                           and the JDK 25 Unsafe memory-access option that
#                           keeps a plain-text JEP 498 warning out of the
#                           JSON log stream. Replace the whole variable to
#                           override; the ENV instruction below explains each
#                           of the three options.
#   HOME                    /opt/carddemo, which exists, is owned by
#                           root and is not writable by the application.
#   EXPOSE                  8080 only, matching server.port's documented
#                           non-secret default in application.yml.
#
# Deliberately NOT baked in, because the image must stay environment
# neutral and secret free: any Spring profile name, the JWT signing key,
# the datasource URL/user/password, the AWS emulator endpoint, the S3
# bucket / SQS queue / SNS topic names, and the OTLP endpoint. An absent
# value fails placeholder resolution and therefore fails startup, which
# is the intended behaviour rather than a defect. Note that the AWS
# endpoint and credential contract is not merely unset here: it is
# supplied by the base profile as an emulator-only default and validated
# by com.cardemo.config.AwsConfig before any client is built, so an
# operator cannot turn this image into a live-AWS client by omission.
#
# FILESYSTEM AND PRIVILEGE
#
# The process runs as uid 10001 / gid 10001, never root. The JAR is
# root owned and mode 0444, so the application cannot rewrite its own
# code. /tmp is the ONLY path that must be writable - embedded Tomcat
# creates its document base under java.io.tmpdir, and nothing else
# writes to disk because logback-spring.xml has a single ConsoleAppender
# and no file appender. The image is therefore compatible with
# `--read-only` provided /tmp is supplied as a tmpfs, as shown above.
#
# COMMON FAILURE MODES AND TROUBLESHOOTING
#
#   build: "no downloader available: install curl or wget"
#       The builder base no longer ships curl. mvnw is a lite
#       only-script launcher and must fetch the Maven distribution
#       pinned in .mvn/wrapper/maven-wrapper.properties itself.
#       Remedy: choose a builder base that ships curl or wget; do not
#       repoint the wrapper at a .tar.gz, which the launcher rejects.
#   build: "cannot expand ... neither unzip nor the JDK jar tool"
#       Expected only on a JRE builder. The chosen builder is a JDK, so
#       $JAVA_HOME/bin/jar is present and the wrapper uses it; unzip is
#       absent from the base and is deliberately not installed.
#   build: SHA-256 mismatch
#       maven-wrapper.properties pins the archive by checksum and Maven
#       never starts on a mismatch. Update distributionUrl and
#       distributionSha256Sum in the same commit.
#   build: "release version 25 not supported"
#       The base image drifted below JDK 25. The enforcer
#       requireJavaVersion [25,) rule in pom.xml fails first, in the
#       dependency layer, before any source is compiled.
#   build: "FATAL: expected exactly one JAR in target/"
#       More than the repackaged artefact was produced - a classifier,
#       a sources or javadoc JAR. The build stops rather than guessing
#       which one to ship. Remedy: inspect the listing the message
#       prints and give the extra artefact a classifier the selection
#       can exclude.
#   run: "Could not resolve placeholder 'JWT_SIGNING_KEY'" or similar
#       A required environment variable was not supplied. Compare the
#       run command against .env.example; the image intentionally has
#       no defaults for secrets or host names.
#   run: container reports "unhealthy" while the log shows "Started
#       CardDemoApplication"
#       GET /actuator/health/readiness is not answering 200 with
#       "status":"UP". Either a readiness contributor is DOWN - it
#       includes readinessState and db, so an unreachable PostgreSQL
#       does this - or the Spring Security filter chain does not permit
#       the probe anonymously and returns 401. Reproduce from inside the
#       container with the same probe the HEALTHCHECK uses:
#         docker exec carddemo-app bash -c \
#           'exec 3<>/dev/tcp/127.0.0.1/8080; printf "GET \
#            /actuator/health/readiness HTTP/1.1\r\nHost: h\r\n\
#            Connection: close\r\n\r\n" >&3; cat <&3'
#   run: read only filesystem errors mentioning java.io.tmpdir
#       /tmp was not mounted as a tmpfs alongside --read-only.
#
# ******************************************************************
# * STAGE 1 of 2 - build
# *
# * BASE IMAGE CHOICE, and why it is this one. The tag pins Maven
# * exactly at 3.9.11, the version .mvn/wrapper/maven-wrapper.properties
# * pins and .github/workflows/build.yml provisions, on a Temurin JDK 25
# * over Ubuntu Noble - the same distribution family as the runtime
# * stage, so the two stages share a libc. The digest is the OCI IMAGE
# * INDEX digest, not a per platform manifest digest, so pinning it
# * fixes the content exactly while still resolving on all five
# * published platforms (linux/amd64, arm64/v8, ppc64le, riscv64,
# * s390x); no architecture is assumed. Verified 2026-08-02 with
# * `docker buildx imagetools inspect`.
# *
# * It also ships /usr/bin/curl, which is what makes the wrapper usable
# * with no package installation at all: eclipse-temurin JDK images
# * carry neither curl nor wget, so using one would force an apt-get
# * against a floating Ubuntu archive. unzip is absent here too, and
# * that is fine - mvnw falls back to $JAVA_HOME/bin/jar to expand the
# * checksum verified archive.
# ******************************************************************
FROM maven:3.9.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c AS build

WORKDIR /workspace

# Matches the MAVEN_OPTS of .github/workflows/build.yml so the image
# build and the verification gate compile under identical JVM limits.
ENV MAVEN_OPTS="-Xmx2048m -XX:MaxMetaspaceSize=512m"

# Build descriptor and launcher first, so the dependency layer below is
# keyed only on them and survives every source change.
#
# .mvn/ is not optional. It carries wrapper/maven-wrapper.properties,
# which pins Maven 3.9.11 by URL and SHA-256, and jvm.config, whose
# single line suppresses the sun.misc.Unsafe warnings that the Guice
# 5.1.0 bundled with that distribution emits under JDK 25. Every line of
# jvm.config is passed to the JVM verbatim as a raw option, so it is
# copied untouched - a comment or a blank line there breaks the launcher.
#
# mvnw is committed mode 100755 and COPY preserves that, so no chmod is
# needed or performed.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Dependency layer. `test-compile` is chosen over `dependency:go-offline`
# on purpose: go-offline invokes maven-dependency-plugin, which is
# neither pinned in pom.xml nor managed by spring-boot-starter-parent
# 3.5.11, so it would resolve whatever release is newest on Central at
# build time. That is exactly the non-determinism pom.xml's own enforcer
# RequirePluginVersions rule exists to prevent. `test-compile` resolves
# the full compile plus test dependency graph using only pinned or
# parent managed plugins, reports "No sources to compile" because src/
# has not been copied yet, and runs the enforcer first - so a base image
# that drifted below JDK 25 or Maven 3.9.11 fails here, in seconds,
# rather than after a full compile.
RUN ./mvnw -B -ntp test-compile

# REQUIRED TEST CONTRACTS - not optional, and not source.
#
# This project's unit tier deliberately reads the frozen corpus at test
# time instead of retyping its field widths and literals into Java,
# which is what makes the parity assertions assertions about the corpus
# rather than about a second copy of the implementation. Surefire runs
# with ${basedir} as its working directory, so those reads resolve
# against this stage's WORKDIR. Omit any of them and the in-image test run
# fails in the hundreds with NoSuchFileException on every such read - not
# one of them a real defect, and every one of them attributed to the test
# rather than to this file. The readers, by evidence:
#   app/cpy/       Path.of("app","cpy") is listed, and members are
#                  resolved dynamically as member + ".cpy" -
#                  ExceptionHierarchyTest reads CSMSG02Y.cpy for the
#                  abend field widths, CommAreaTest reads COCOM01Y.cpy,
#                  MenuOptionCopybook reads COMEN02Y.cpy and
#                  COADM02Y.cpy, RecordLayoutCopybook reads the eleven
#                  record layouts
#   app/cpy-bms/   BmsSymbolicMap resolves members as member + ".CPY"
#                  for the screen field contracts
#   app/cbl/       TransactionSourceTest reads six programs, CBACT04C,
#                  CBTRN02C, COADM01C, COBIL00C, COTRN02C and CSUTLDTC,
#                  to recover literals from the corpus itself
#   app/data/ASCII/ dailytran.txt, the 300 record posting fixture
#   localstack-init/ InitAwsScriptGuardTest executes init-aws.sh with
#                  hostile input and asserts the guards fail closed
#                  before any AWS call, so no aws CLI is needed - only
#                  bash, which this base provides
#   .env.example   EnvironmentTemplateContractTest locates the repository
#                  root by looking for a directory holding BOTH pom.xml
#                  and .env.example, then reconciles every template
#                  assignment against the four profile files. Without it
#                  the class initialiser throws and all thirteen of its
#                  assertions error out
#   docker-compose.yml and .github/workflows/build.yml
#                  the same test's LIVE assertion requires every template
#                  name to have a committed consumer, and reads all four
#                  of docker-compose.yml, localstack-init/init-aws.sh,
#                  pom.xml and .github/workflows/build.yml to find one -
#                  a missing consumer file is an unchecked IO exception,
#                  not a skipped check
#
# Those three additions and the withdrawal of the app/data/EBCDIC
# exclusion in .dockerignore were established by evidence, not by
# inspection: the first image build of this stage failed with
# EnvironmentTemplateContractTest erroring in its initialiser and
# SourceCitationResolutionTest reporting seven app/data/EBCDIC citations
# as unresolved, while the identical suite passed on the host. Every
# failure was an artefact of the context rather than of the tree, which
# is precisely the class of defect this comment exists to prevent
# recurring.
#
# The whole of app/ is copied rather than those four directories
# because app/ is frozen by contract - .github/workflows/build.yml has
# a dedicated "Frozen corpus guard (app/ must be unmodified)" job - so
# this is an immutable, permanently cached 2.7 MB layer, whereas a hand
# maintained list of subdirectories would silently break the image build
# the first time a new test reads a corpus member outside it. That
# reasoning is why app/data/EBCDIC now travels with it: 204 KB of
# codepage reference that no code parses, and that seven committed
# citations name, so the citation gate needs the paths to resolve.
# Nothing from app/ crosses into the runtime stage: only
# /image/carddemo.jar does.
COPY app/ app/
COPY localstack-init/ localstack-init/

# The three repository files the unit tier reads that are neither source
# nor corpus. Placed immediately before src/ so they sit above the
# longest layer and below the dependency layer: editing one re-runs the
# build and the tests, which is correct, and never re-resolves the
# dependency graph. .env.example is a template, and the distinction its
# entries draw matters more than a blanket claim: the two that MUST stay
# empty are empty and carry no default anywhere - JWT_SIGNING_KEY, which
# fails fast when unset, and NVD_API_KEY - while the local-only demo
# values needed to bring the compose topology up are present and are
# meant to be, namely POSTGRES_PASSWORD, the two LocalStack AWS keys and
# GRAFANA_ADMIN_PASSWORD. None of them reaches a live account: the AWS
# pair addresses the emulator only. An earlier revision of this comment
# said every credential-bearing entry was empty, which was not true of
# those four and is corrected here. The file is confined to this stage,
# so no template and no compose file reaches the runtime image.
COPY .env.example docker-compose.yml ./
COPY .github/ .github/

COPY src/ src/

# Compile, test and package. Warnings are escalated to errors by
# pom.xml, the surefire configuration excludes **/integration/** and
# **/e2e/**, and jacoco check, dependency-check and failsafe are bound
# to `verify` - so this runs the unit tier only, with no Docker socket
# and no NVD feed required. See "WHAT THIS BUILD IS NOT" above.
#
# Then select the artefact to ship. No glob decides what runs in
# production: target/ is asserted to hold exactly one *.jar and the
# build fails loudly, printing what it found, if that is ever untrue -
# spring-boot-maven-plugin repackages in place and leaves the plain
# archive as *.jar.original, and a stray classifier, sources or javadoc
# artefact must be an explicit decision, not a silent coin toss. The
# manifest is then read with the JDK jar tool and both halves of the
# executable JAR contract are checked: a Spring Boot JarLauncher as
# Main-Class, proving repackage actually ran, and the Start-Class that
# pom.xml declares in <start-class>. MANIFEST.MF is CRLF terminated, so
# carriage returns are stripped before matching. Only then is the
# artefact installed under one fixed, unambiguous name, root owned and
# read only, ready for a literal COPY in the runtime stage.
RUN set -eu; \
    ./mvnw -B -ntp clean package; \
    jarCount="$(find /workspace/target -maxdepth 1 -type f -name '*.jar' | wc -l)"; \
    if [ "$jarCount" -ne 1 ]; then \
      echo "FATAL: expected exactly one JAR in target/, found ${jarCount}. Candidates:" >&2; \
      find /workspace/target -maxdepth 1 -type f -name '*.jar*' -printf '  %p\n' >&2; \
      exit 1; \
    fi; \
    appJar="$(find /workspace/target -maxdepth 1 -type f -name '*.jar')"; \
    if [ "$(basename "${appJar}")" != 'carddemo-1.0.0.jar' ]; then \
      echo "FATAL: expected target/carddemo-1.0.0.jar but the build produced $(basename "${appJar}")." >&2; \
      echo "       pom.xml's <version> drives both that name and the org.opencontainers.image.version" >&2; \
      echo "       label in the runtime stage below. Update the label in the same commit as the bump." >&2; \
      exit 1; \
    fi; \
    mkdir -p /tmp/manifest; \
    ( cd /tmp/manifest && "${JAVA_HOME}/bin/jar" --file "${appJar}" --extract META-INF/MANIFEST.MF ); \
    tr -d '\r' < /tmp/manifest/META-INF/MANIFEST.MF > /tmp/manifest/manifest.txt; \
    if ! grep -q '^Main-Class: .*JarLauncher$' /tmp/manifest/manifest.txt; then \
      echo "FATAL: ${appJar} is not a repackaged Spring Boot JAR - no JarLauncher Main-Class." >&2; \
      exit 1; \
    fi; \
    if ! grep -q '^Start-Class: com\.cardemo\.CardDemoApplication$' /tmp/manifest/manifest.txt; then \
      echo "FATAL: ${appJar} does not declare Start-Class com.cardemo.CardDemoApplication." >&2; \
      exit 1; \
    fi; \
    install -D -m 0444 -o root -g root "${appJar}" /image/carddemo.jar

# ******************************************************************
# * STAGE 2 of 2 - runtime
# *
# * A verified Java 25 JRE image exists, so the runtime carries a JRE
# * and not a JDK: no compiler, no jar tool, no jshell, nothing that
# * could build or repackage code in production. The tag pins the exact
# * patch level 25.0.3+9 - the OpenJDK 25.0.3 this project targets - and
# * the digest is again the OCI image index digest, preserving all five
# * platforms. Verified 2026-08-02 with `docker buildx imagetools
# * inspect`.
# *
# * Nothing is installed here. Every tool this stage uses - bash for the
# * health probe, groupadd and useradd for the fixed identity - is
# * already in the base, so there is no apt-get against a floating
# * archive and no network access at runtime image assembly time.
# ******************************************************************
FROM eclipse-temurin:25.0.3_9-jre-noble@sha256:2f1da100788559b397bcf48c736169ea5b070bde84e55f203bbee8e83d87a175 AS runtime

# OCI annotations. Project identity, licence and provenance only.
#
# image.version is set explicitly, and deliberately so. LABELs are
# INHERITED, and this base carries exactly one of its own:
# org.opencontainers.image.version="24.04", Ubuntu Noble's release. That
# value is measurably wrong about this image - the software packaged here
# is CardDemo 1.0.0, not an operating system - and omitting the label
# does not withhold a claim, it silently republishes the base's. Verified
# with `docker image inspect` on the pinned digest, whose entire label
# set is that one entry. So it is restated with the only value evidence
# supports: pom.xml's <version>, which is 1.0.0. The duplication that
# would otherwise invite drift is closed in the builder stage above,
# where the artefact name is asserted to be exactly carddemo-1.0.0.jar,
# so a version bump fails the build here rather than shipping a stale
# release claim.
#
# There is still no image.created or image.revision: the first embeds a
# timestamp that would make two otherwise identical builds differ, the
# second a commit SHA this build does not receive, and neither may imply
# a support or release status this project does not offer.
LABEL org.opencontainers.image.title="CardDemo" \
      org.opencontainers.image.version="1.0.0" \
      org.opencontainers.image.description="CardDemo credit card management - the Java 25 Spring Boot modular monolith replacing the CICS, VSAM, JCL and BMS substrate of the COBOL corpus kept under app/" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/Blitzy-Sandbox/blitzy-card-demo" \
      org.opencontainers.image.vendor="Amazon.com, Inc. or its affiliates" \
      org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:25.0.3_9-jre-noble" \
      org.opencontainers.image.base.digest="sha256:2f1da100788559b397bcf48c736169ea5b070bde84e55f203bbee8e83d87a175"

# Assert the runtime, rather than trusting the tag. The digest above
# fixes the content, but a rebase or a manual FROM edit must not be able
# to ship a JVM that cannot load class file major version 69. This reads
# what the JVM itself reports and refuses to produce an image otherwise.
#
# The same instruction establishes the fixed unprivileged identity.
# --system keeps the account out of the human uid range, --no-create-home
# leaves no writable home behind, and the login shell is nologin, so the
# account cannot be used interactively. gid and uid are pinned to 10001
# so a host bind mount's ownership is predictable across rebuilds.
#
# useradd emits one EXPECTED notice here, and it is not a defect:
#   useradd warning: carddemo's uid 10001 is greater than SYS_UID_MAX 999
# --system conventionally allocates below SYS_UID_MAX, and an explicit
# --uid outside that range is reported. 10001 is deliberate: a high,
# fixed, non-root id will not collide with an account the host already
# owns, which is what keeps ownership predictable under a user namespace
# or a bind mount. Do NOT "fix" the notice by moving the id below 1000 -
# that reintroduces exactly the collision the pinning avoids. The account
# is verifiably correct regardless: `id` in the running container reports
# uid=10001(carddemo) gid=10001(carddemo).
RUN set -eu; \
    javaSpec="$(java -XshowSettings:properties -version 2>&1 \
                | sed -n 's/^[[:space:]]*java\.specification\.version = //p')"; \
    if [ "${javaSpec}" != "25" ]; then \
      echo "FATAL: runtime JVM reports Java specification version '${javaSpec}', expected 25." >&2; \
      exit 1; \
    fi; \
    groupadd --system --gid 10001 carddemo; \
    useradd --system --uid 10001 --gid 10001 --no-create-home \
            --home-dir /opt/carddemo --shell /usr/sbin/nologin carddemo; \
    install -d -m 0755 -o root -g root /opt/carddemo

# The base image sets HOME=/root, which uid 10001 cannot read. Point it
# at the application directory instead: it exists, it is root owned and
# world readable, so a library probing ~/.aws or ~/.m2 gets a clean
# "not found" rather than a permission error, and nothing can be written
# to HOME at all.
ENV HOME=/opt/carddemo

WORKDIR /opt/carddemo

# One literal path in, one literal path out. The artefact arrives root
# owned and mode 0444 from `install` in the build stage, so the
# application user can read its own code and nothing more. Only the JAR
# crosses the stage boundary: no sources, no Maven repository, no
# compiler, no surefire reports, no build logs.
COPY --from=build /image/carddemo.jar /opt/carddemo/carddemo.jar

# Numeric, so it resolves without /etc/passwd and satisfies platforms
# that reject an image whose USER is a name.
USER 10001:10001

# The HTTP port and nothing else. Actuator shares it - management.server
# has no separate port - so /actuator/health, /actuator/info and
# /actuator/prometheus are all reachable here.
EXPOSE 8080

# Container aware defaults, overridable as one unit by supplying
# JAVA_TOOL_OPTIONS at run time. MaxRAMPercentage sizes the heap from
# the cgroup limit instead of the host's memory, and ExitOnOutOfMemoryError
# makes the orchestrator restart a doomed JVM instead of letting it limp.
# Nothing obsolete is carried: -XX:+UseContainerSupport is on by default,
# UTF-8 is the default charset since JEP 400, and the java.security.egd
# workaround has been inert since JDK 9.
#
# THE THIRD OPTION SUPPRESSES A JDK 25 WARNING THAT WOULD OTHERWISE BREAK
# THE LOG CONTRACT. NOTATION: long JDK options take a leading pair of
# hyphens, so in this prose the option is written WITHOUT its prefix, as
# sun-misc-unsafe-memory-access=allow; the ENV line below carries the real,
# fully prefixed form.
#
# JEP 498 made the memory-access methods of sun.misc.Unsafe terminally
# deprecated in JDK 24 and warning-on-first-use in JDK 25. A packaged
# startup was measured emitting, on stderr and in plain text:
#   WARNING: A terminally deprecated method in sun.misc.Unsafe has been
#   called ... by io.opentelemetry.internal.shaded.jctools.util.UnsafeAccess
#   ... opentelemetry-sdk-trace-1.49.0.jar
# Three reasons make that worth suppressing rather than tolerating. It is
# UNSTRUCTURED: logback-spring.xml emits JSON with traceId, spanId and
# correlationId, and this line has none of that shape, so a log pipeline
# parsing the stream either drops it or records a parse failure on every
# container start. It is UNACTIONABLE HERE: the caller is JCTools shaded
# inside the OpenTelemetry SDK, reached through the Micrometer tracing
# bridge, so nothing in this repository can stop the call. And it is
# TRANSIENT-LOOKING BUT NOT TRANSIENT: without the option, JDK 26 is
# expected to turn the warning into an error, so the option is also what
# keeps this image booting on the next release.
# The option is deliberately NARROW. It re-permits exactly one thing, the
# Unsafe memory-access methods, and grants no other access; it is not
# --add-opens, not --enable-native-access and not --illegal-access. The
# alternative the finding also allows - upgrading OpenTelemetry or JCTools
# past the shaded call - is rejected here because every non-BOM coordinate
# in pom.xml is pinned deliberately and the OTel version is managed by the
# Micrometer tracing BOM, so raising it to silence a warning would move a
# transitive contract for a cosmetic gain.
# The build stage already needed the identical option for its own reason -
# .mvn/jvm.config carries it because the Guice 5.1.0 bundled with Maven
# 3.9.11 calls the same methods - so both stages are now consistent, and
# neither depends on the other.
#
# SPRING_PROFILES_ACTIVE is present and EMPTY on purpose. It names no
# profile, so the image stays environment neutral exactly as the profile
# layering contract in application.yml requires, while still declaring
# the variable in the image configuration where `docker inspect` will
# show it as part of the environment contract the operator must satisfy.
#
# THAT EMPTY DEFAULT IS SAFE, AND IT IS SAFE STRUCTURALLY RATHER THAN BY
# CONVENTION. It once was not. With no profile active, the base
# configuration used to enable the S3, SQS and SNS clients while declaring
# no endpoint override and no credentials, so a `docker run` with nothing
# else supplied performed SDK regional endpoint discovery against real
# amazonaws.com hosts and signed with whatever the SDK default provider
# chain resolved - environment variables, a mounted credentials file,
# container credentials or instance metadata. AAP 0.3.2 admits no such
# path. Two changes closed it, neither of which depends on the operator
# naming a profile:
#   * application.yml, the BASE profile, now carries the three service
#     endpoints - indirected through AWS_ENDPOINT_URL and defaulting to the
#     emulator edge, a loopback address that cannot be an AWS host - and an
#     inert static credential pair whose only function is to displace the
#     SDK default provider chain.
#   * com.cardemo.config.AwsConfig validates those five values in its
#     constructor, which necessarily runs before the S3, SQS and SNS
#     templates it declares, and aborts the context refresh unless every
#     endpoint is an absolute http/https URL with an explicit port, no user
#     information and a host on its permitted emulator list, and both
#     credentials are present without a real AWS key prefix.
# So the worst outcome of a profile-less `docker run` is a connection
# failure to a loopback port, never a live AWS call. Supplying
# SPRING_PROFILES_ACTIVE=local, as the run recipe above does, remains the
# recommended way to get the developer defaults; it is no longer what makes
# the run safe.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError --sun-misc-unsafe-memory-access=allow" \
    SPRING_PROFILES_ACTIVE=""

# Readiness probe. bash is the only HTTP capable tool in this base -
# there is no curl, no wget and no nc - and its /dev/tcp redirection
# needs no package installation and no credential, which is precisely
# the condition under which a Dockerfile HEALTHCHECK is worth having
# here. /bin/sh is dash in this image and dash has NO /dev/tcp, so the
# interpreter is named explicitly rather than left to /bin/sh.
#
# The probe is the Java analogue of the CEMT SET FIL(...) OPE and CLO
# steps of app/jcl/OPENFIL.jcl and app/jcl/CLOSEFIL.jcl, which made the
# VSAM datasets available to the online region. It asks the readiness
# group declared in application.yml, which includes readinessState and
# db, and treats only "status":"UP" as healthy - a 401, a 404 or an
# OUT_OF_SERVICE body all correctly read as not ready. The port follows
# SERVER_PORT so the probe cannot drift from the listener.
#
# start-period covers Flyway applying V1, V2 and V3 on a cold database;
# failures inside it are not counted against the retry budget.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=4 \
  CMD ["/usr/bin/bash", "-c", "set -eu; exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT:-8080}; printf 'GET /actuator/health/readiness HTTP/1.1\\r\\nHost: 127.0.0.1\\r\\nConnection: close\\r\\n\\r\\n' >&3; grep -q '\"status\":\"UP\"' <&3"]

# Exec form, so the JVM is pid 1 and receives SIGTERM directly. That is
# what makes `server.shutdown: graceful` effective: in flight requests
# drain and a running batch step finishes instead of being dropped. A
# shell form entrypoint would interpose /bin/sh -c, which would not
# forward the signal. This also replaces the base image's
# /__cacert_entrypoint.sh, whose only job is an optional import of extra
# certificate authorities from /certificates; the local substrate speaks
# plain HTTP to LocalStack and PostgreSQL, so nothing is lost.
ENTRYPOINT ["java", "-jar", "/opt/carddemo/carddemo.jar"]

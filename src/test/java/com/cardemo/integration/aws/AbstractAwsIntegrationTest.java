/*
 ******************************************************************
 * Program     : AbstractAwsIntegrationTest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 integration-test harness (Failsafe tier)
 * Function    : Owns the LocalStack and PostgreSQL 16 Testcontainers, the dynamic property wiring, the
 *               fixed clock and the deterministic self-cleanup helpers for the AWS integration tier.
 * Source      : app/csd/CARDDEMO.CSD:499-505 @ 7756d89 (DEFINE TDQUEUE(JOBS) RECORDSIZE(80)
 *               RECORDFORMAT(FIXED) -> SQS FIFO)
 * Source      : app/jcl/DEFGDGB.jcl:25-57 + app/jcl/DALYREJS.jcl:24-28 + app/jcl/REPTFILE.jcl:25-28
 *               @ 7756d89 (7 GDG bases -> 3 S3 buckets)
 * Source      : app/catlg/LISTCAT.txt:3937-3950 @ 7756d89 (totals block: GDG 7, CLUSTER 10, AIX 3, PATH 3)
 * Source      : app/jcl/OPENFIL.jcl:26-30 + app/jcl/CLOSEFIL.jcl:26-30 @ 7756d89 (CEMT SET FIL for 5
 *               files -> health probes)
 * Note        : No COBOL analogue for a test harness exists; this tier is new capability mandated by
 *               Rule 1 Clause A.
 ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 ******************************************************************
 */
package com.cardemo.integration.aws;

import static org.assertj.core.api.Assertions.assertThat;

import io.awspring.cloud.s3.S3Template;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.DeleteTopicRequest;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.QueueNameExistsException;

/**
 * Shared harness for the AWS integration tier of the CardDemo migration: the single place where this
 * package's two container lifecycles, its dynamic property wiring, its pinned clock, its deterministic
 * self-cleanup and its fixed-width record geometry are defined.
 *
 * <h2>1. What it does</h2>
 *
 * <p>Three mainframe constructs were replaced by cloud services, and each replacement has a property that
 * only a real emulator can demonstrate. This class stands those services up and hands subclasses the
 * smallest surface that lets them assert those properties without restating the evidence.
 *
 * <ul>
 *   <li><strong>The transient data queue becomes a FIFO queue.</strong>
 *       {@code app/csd/CARDDEMO.CSD:499-505} declares
 *       {@code DEFINE TDQUEUE(JOBS) GROUP(CARDDEMO) ... TYPE(EXTRA) DATABUFFERS(1) DDNAME(INREADER)
 *       ERROROPTION(IGNORE) OPENTIME(INITIAL) TYPEFILE(OUTPUT) RECORDSIZE(80) RECORDFORMAT(FIXED)
 *       BLOCKFORMAT(UNBLOCKED) DISPOSITION(MOD)}. The <strong>eighty-byte FIXED</strong> record is what
 *       fixes the shape of the typed report message, which is why
 *       {@link #JOB_SUBMISSION_RECORD_LENGTH} is eighty and not a rounder number. The producing
 *       transaction is {@code DEFINE TRANSACTION(CR00) ... PROGRAM(CORPT00C)} at
 *       {@code app/csd/CARDDEMO.CSD:409-410}.</li>
 *   <li><strong>Seven generation data groups become three buckets.</strong> {@code app/jcl/DEFGDGB.jcl:25-57}
 *       defines six bases, every one {@code LIMIT(5)} with {@code SCRATCH}, and
 *       {@code app/jcl/DALYREJS.jcl:24-28} supplies the seventh,
 *       {@code NAME(AWS.M2.CARDDEMO.DALYREJS) LIMIT(5) SCRATCH}, in its own member. Six plus one is
 *       <strong>seven</strong>, which is exactly what the catalogue totals block reports at
 *       {@code app/catlg/LISTCAT.txt:3937-3950} - {@code GDG 7}, {@code CLUSTER 10}, {@code AIX 3},
 *       {@code PATH 3} - and what its seven {@code GDG BASE} entries enumerate at L684 DALYREJS, L1098
 *       SYSTRAN, L1202 TCATBALF.BKUP, L1527 TRANREPT, L1631 TRANSACT.BKUP, L2919 TRANSACT.COMBINED and
 *       L3021 TRANSACT.DALY. The three logical bucket roles are kept distinct and are documented on
 *       {@link #batchInputBucket()}, {@link #batchOutputBucket()} and {@link #statementsBucket()}.</li>
 *   <li><strong>The file-availability jobs become health probes.</strong> {@code app/jcl/OPENFIL.jcl:22} is
 *       {@code //OPCIFIL EXEC PGM=SDSF} and {@code :26-30} issues five
 *       {@code CEMT SET FIL(&lt;name&gt;) OPE} commands, for TRANSACT, CCXREF, ACCTDAT, CXACAIX and USRSEC;
 *       {@code app/jcl/CLOSEFIL.jcl:22} is {@code //CLCIFIL EXEC PGM=SDSF} and {@code :26-30} issues the
 *       identical five with {@code CLO}. Neither member has a Java analogue as a job - they are replaced
 *       by the readiness probe group, which is why this harness starts the whole context rather than a
 *       slice of it.</li>
 * </ul>
 *
 * <p>Two further legacy facts are recorded here once so that no subclass has to rediscover them.
 *
 * <dl>
 *   <dt>{@code FILE STATUS IS: NNNN} is a preserved quirk, not a formatting defect</dt>
 *   <dd>{@code app/cbl/CBTRN02C.cbl:714-727} is {@code 9910-DISPLAY-IO-STATUS}, and it emits
 *       {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04} on <em>both</em> branches, at {@code :721} and
 *       at {@code :725}. {@code IO-STATUS-04} is {@code PIC 9} followed by {@code PIC 999} at
 *       {@code :138-140}, so it is exactly four characters. COBOL concatenates a literal and an identifier
 *       in {@code DISPLAY} with no separator, and the literal already contains the placeholder text, so
 *       status {@code '23'} emits {@code FILE STATUS IS: NNNN0023} and not {@code FILE STATUS IS: 0023}.
 *       The stray {@code NNNN} is preserved and never repaired. Nothing in this package reformats, wraps,
 *       truncates, escapes away or otherwise post-processes it, and no masking rule may touch it: masking
 *       covers credentials, password digests and social security numbers only.</dd>
 *   <dt>The monthly report range is the FULL current calendar month</dt>
 *   <dd>{@code app/cbl/CORPT00C.cbl:212-238}. Lines {@code :217-219} set the start date to the current
 *       year, the current month and the literal {@code '01'}. Then {@code :223} moves 1 into the day field,
 *       <em>discarding today's day</em>; {@code :224} adds one to the month; {@code :225-228} roll the year
 *       when the month exceeds twelve; {@code :229-230} compute
 *       {@code FUNCTION DATE-OF-INTEGER(FUNCTION INTEGER-OF-DATE(WS-CURDATE-N) - 1)}, which is the first of
 *       the <em>next</em> month minus one day, that is the <strong>last day of the current month</strong>;
 *       and {@code :232-234} then read back the already-mutated year, month and day fields.
 *       <strong>This corrects the plan's description of the range as month-to-date. Severity:
 *       Blocker</strong> - a month-to-date implementation would produce a different end date on every day
 *       of the month except the last, so it would pass on one day in thirty and fail silently on the rest.
 *       Remediation, for whoever owns the report request type: derive the end date as the last day of the
 *       start date's month. Yearly is {@code yyyy-01-01} through {@code yyyy-12-31} at {@code :239-255};
 *       custom is six discrete components assembled with dash separators into a ten-byte
 *       {@code yyyy-MM-dd} at {@code :60-71} and {@code :381-386}.</dd>
 * </dl>
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>Build and run the whole suite with {@code ./mvnw -B -ntp clean verify}. <strong>This tier is collected
 * by Failsafe, not Surefire.</strong> The root build binds {@code maven-failsafe-plugin} to
 * {@code **}{@code /integration/}{@code **}{@code /*Test.java} and
 * {@code **}{@code /e2e/}{@code **}{@code /*Test.java} at {@code integration-test} and {@code verify}, even
 * though these classes keep the {@code Test} suffix, while {@code maven-surefire-plugin} includes
 * {@code **}{@code /*Test.java} but <em>excludes</em> {@code **}{@code /integration/}{@code **} and
 * {@code **}{@code /e2e/}{@code **}. A class moved out of this package tree therefore matches neither
 * include set and is collected by neither plugin: it silently never runs, and the build stays green while
 * reporting success from both plugins. That is the worst failure mode available here, so
 * <strong>this package and this class name must not be renamed or relocated</strong>, and no sub-package
 * may be introduced beneath it.
 *
 * <p>This class is {@code abstract} and declares <strong>no test method</strong>, so the JUnit Platform
 * never treats it as a container and Failsafe reports zero tests from it without error. Both sibling
 * harnesses in the tier have the same shape, which is the evidence that it is safe.
 *
 * <p><strong>A reachable container runtime is a prerequisite for this tier</strong>, and it is a
 * prerequisite rather than a convenience: there is no in-memory substitute, because an in-memory database
 * would not exercise {@code spring.jpa.hibernate.ddl-auto: validate} against the Flyway-owned schema and an
 * in-memory queue would not exercise FIFO semantics. Where no daemon or socket is available the correct
 * report is that the gate is <em>blocked</em>, never an untested pass. At the time of writing the
 * provisioned environment supplies Docker Engine 29.7.0 with Compose v5.3.1, and both container images are
 * already resident in its cache, so the suite starts without reaching the network. Host {@code java},
 * {@code javac} and {@code mvn} are also present - an earlier note claiming they were absent, and that
 * Maven therefore had to run inside a container, is withdrawn as stale. Severity of what that stale note
 * left in place: <strong>Low</strong>; it caused no wrong artefact, only a wrong instruction.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p>The {@code test} profile is active. {@code src/main/resources/application-test.yml} deliberately
 * declares no datasource address and no cloud endpoint of its own, because a file cannot know an ephemeral
 * mapped port; this class supplies both from the running containers, at a precedence above every profile
 * file. <strong>No address, port, credential or connection string is written in this source.</strong>
 *
 * <ul>
 *   <li><strong>Images are pinned, never floating.</strong> The database is
 *       PostgreSQL 16 by content digest and the emulator is a stated LocalStack release; see
 *       {@link #POSTGRES_IMAGE_REFERENCE} and {@link #LOCALSTACK_IMAGE_REFERENCE}. A mutable tag such as
 *       {@code latest} would let the same source select different software tomorrow with nothing in the
 *       build changing, which is the determinism Rule 1 Clause A exists to prevent.</li>
 *   <li><strong>The database connection is injected, not configured.</strong> {@code @ServiceConnection}
 *       contributes the URL, database name, user and password as connection details, so not even a
 *       container default appears here.</li>
 *   <li><strong>The cloud endpoints are registered from the container object.</strong>
 *       {@link #registerContainerProperties(DynamicPropertyRegistry)} sets
 *       {@code spring.cloud.aws.region.static}, {@code spring.cloud.aws.credentials.access-key},
 *       {@code spring.cloud.aws.credentials.secret-key} and the three per-service endpoint keys
 *       {@code spring.cloud.aws.s3.endpoint}, {@code spring.cloud.aws.sqs.endpoint} and
 *       {@code spring.cloud.aws.sns.endpoint}, every value read from an accessor on the emulator container.
 *       It also sets the logical resource names {@code carddemo.aws.s3.batch-input-bucket},
 *       {@code carddemo.aws.s3.batch-output-bucket}, {@code carddemo.aws.s3.statements-bucket},
 *       {@code carddemo.aws.sqs.report-queue} and {@code carddemo.aws.sns.notification-topic}.</li>
 *   <li><strong>Time is pinned.</strong> {@link #FIXED_INSTANT} is published as a {@code @Primary}
 *       {@code java.time.Clock} bean by {@link FixedClockTestConfiguration}, so the production code under
 *       test runs on it rather than on the wall clock, and it is also exposed directly as
 *       {@link #FIXED_CLOCK} for a subclass to compute an expectation with.</li>
 * </ul>
 *
 * <p><strong>Least privilege.</strong> Every cloud call in this tier reaches the emulator container and
 * nothing else. {@code com.cardemo.config.AwsConfig} allow-lists the endpoint host and aborts startup on
 * anything outside that set, so a misconfiguration fails the context instead of escaping to a real
 * account; the credentials are the emulator's own throwaway pair, read from the container. There is no live
 * account, no live credential and no live endpoint anywhere on any code path from here. A live endpoint or
 * credential reaching a code path in this tier would be a <strong>Blocker</strong>.
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>{@code Could not find a valid Docker environment}</dt>
 *   <dd>No reachable container runtime. Start one. A green build must never be obtainable by having no
 *       daemon, which is why {@link #POSTGRES} and {@link #LOCALSTACK} are started eagerly and a startup
 *       failure is rethrown with that explanation attached rather than skipped.</dd>
 *   <dt>A dependency fails to resolve under {@code org.testcontainers}</dt>
 *   <dd>The <strong>Testcontainers 2.0.3 coordinate trap</strong>, and the highest-severity finding of the
 *       migration: <strong>Blocker</strong>. Only the prefixed module coordinates exist at 2.0.3 -
 *       {@code testcontainers}, {@code testcontainers-postgresql}, {@code testcontainers-localstack} and
 *       {@code testcontainers-junit-jupiter}. The bare {@code postgresql}, {@code localstack} and
 *       {@code junit-jupiter} artifact identifiers under that group <strong>do not exist at 2.0.3</strong>
 *       and fail resolution outright. Compounding it, Spring Boot 3.5.11 already imports the Testcontainers
 *       bill of materials at a 1.x version, so a competing bill-of-materials import yields
 *       ordering-dependent resolution that may silently select 1.x. Remediation is two-part and
 *       <em>both</em> parts are required: override the managed version through the
 *       {@code testcontainers.version} property rather than importing a second bill of materials, and use
 *       only prefixed module coordinates. Overriding without renaming resolves artefacts that do not
 *       exist; renaming without overriding resolves the wrong version. The root build already does both, so
 *       this is documented here as a standing hazard rather than an open defect - and the build file is
 *       owned elsewhere, so the remediation for a regression is to restore those two settings there, never
 *       to add a dependency from this tier.</dd>
 *   <dt>The class-package or method shape you expected from Testcontainers does not compile</dt>
 *   <dd>The 2.x line moved packages and changed signatures, and the API this class uses was verified
 *       against the resolved 2.0.3 artefacts rather than assumed from the 1.x shape. Severity:
 *       <strong>Medium</strong>, recorded so the next reader does not repeat the check. The canonical types
 *       are {@code org.testcontainers.postgresql.PostgreSQLContainer} and
 *       {@code org.testcontainers.localstack.LocalStackContainer}; the deprecated
 *       {@code org.testcontainers.containers} equivalents still ship and must not be used. Two specific
 *       divergences bite: the canonical database container <strong>declares no type parameter</strong>, so
 *       adding a diamond does not compile; and services are enabled through
 *       {@code withServices(String...)}, because the 1.x service enumeration survives only on the
 *       deprecated type.</dd>
 *   <dt>The build fails on a warning that looks harmless</dt>
 *   <dd>{@code maven-compiler-plugin} runs at release 25 with {@code -Xlint:all}, {@code -Werror} and
 *       {@code failOnWarning}, so a single unused import, raw type, unchecked cast, deprecation or
 *       switch fall-through fails the build. That is deliberate and must not be relaxed.</dd>
 *   <dt>Context startup aborts with {@code Could not resolve placeholder} naming the token signing key</dt>
 *   <dd>Severity: <strong>High</strong>, and the defect is not in this file.
 *       {@code src/main/resources/application.yml} maps the signing key to an environment variable with no
 *       default so that no deployment can boot with a key an attacker already knows, and
 *       {@code src/main/resources/application-test.yml} - which is owned elsewhere - supplies no
 *       test value, so the refresh aborts before any test runs. Remediation, for the owner of that file:
 *       supply a non-production test value in the {@code test} profile. Until it does, every harness in
 *       this tier has to register one itself, and both siblings already do; this class follows that
 *       precedent in {@link #registerContainerProperties(DynamicPropertyRegistry)} with a value that is
 *       recognisably test-only. It is not patched from here, and no real key is ever written anywhere.</dd>
 *   <dt>A bucket will not delete, reporting that it is not empty</dt>
 *   <dd>The output bucket has versioning enabled, because object versioning is what stands in for a
 *       relative generation reference. A versioned bucket cannot be deleted while any object
 *       <em>version</em> or delete marker remains, and deleting the visible objects is not enough.
 *       {@link #deleteBucketAndAllVersions(String)} handles that explicitly, and it is the boundary
 *       condition Rule 1 Clause B is asking about.</dd>
 *   <dt>A test passes alone and fails in a suite</dt>
 *   <dd>Almost always a resource name shared between tests. Derive every name from
 *       {@link #scopedResourceName(String)}, which is unique per concrete class and stable across runs, and
 *       create it through the helpers so that {@link #cleanUpResourcesCreatedByThisTest()} removes it.</dd>
 * </dl>
 *
 * <h2>Deliberate divergences from the sibling harnesses</h2>
 *
 * <p>The two other harnesses in {@code src/test/java/com/cardemo/integration} establish the container
 * discipline for the whole tier, and this class conforms to it - the same PostgreSQL 16 digest, the same
 * {@code @ServiceConnection} injection, the same three-migration precondition, the same eager start. It
 * does <strong>not</strong> import from either of them, and neither imports from here: each leaf owns its
 * own holder, in both directions. Three differences are deliberate and are stated so that a reviewer
 * comparing the files does not read them as omissions.
 *
 * <ol>
 *   <li><strong>Two containers rather than one.</strong> The persistence leaf needs only a database. This
 *       leaf needs the emulator as well, because the object store and the queue <em>are</em> its
 *       subject.</li>
 *   <li><strong>No class-level {@code @Transactional}.</strong> The sibling harnesses use it for
 *       per-test JPA rollback isolation. This tier performs no JPA write, so it would isolate nothing, and
 *       a surrounding transaction can interfere with a health probe and cannot roll back a container-side
 *       resource in any case - an object that has been written to a bucket is written. Isolation here is
 *       achieved by creating and deleting named cloud resources instead, which is what
 *       {@link #cleanUpResourcesCreatedByThisTest()} does. For the same reason there is no
 *       {@code @DirtiesContext}, no {@code @Sql}, no truncation and no bulk delete: the context is shared
 *       and only container-side resources are created and cleaned.</li>
 *   <li><strong>The container fields carry no {@code @Container} annotation.</strong> This is the one place
 *       where the obvious annotation is wrong, and the reason is mechanical rather than stylistic; it is
 *       set out in full beside the static initialiser below. Severity of using
 *       {@code @Container} here instead: <strong>Medium</strong>, presenting as a first subclass that
 *       passes and every later one failing on a closed connection pool.</li>
 * </ol>
 *
 * <h2>Global mutable state: exactly one documented exception</h2>
 *
 * <p>Rule 1 Clause B requires that global mutable state be avoided in favour of dependency injection.
 * {@link #POSTGRES} and {@link #LOCALSTACK} are the <strong>single deliberate, documented exception</strong>
 * in this class: they are {@code static} because one container lifecycle must span the whole class
 * hierarchy, they are {@code final} and are never reassigned, and they are effectively immutable once
 * started. <strong>No other {@code static} field of any kind is permitted here</strong> unless it is also
 * {@code final} and deeply immutable - a {@code String}, an {@code int}, a {@code Clock} fixed at
 * construction, an {@code Instant}, a {@code DateTimeFormatter} or a {@code Logger}. Every collaborator is
 * injected; every per-test resource record is an instance field.
 *
 * <h2>Information that is Not available</h2>
 *
 * <p>Rule 1 Clause F requires that missing information be stated plainly rather than filled with an
 * invention. Three things are missing, and each is disclosed with what would be needed to close it.
 *
 * <ol>
 *   <li><strong>The boundary-parity expected-output baseline is Not available.</strong> The repository holds
 *       dataset <em>definition</em> job control and zero captured data: a search across expected, baseline,
 *       golden, system-output and per-dataset name patterns returned only definition members, and a
 *       byte-size sweep for 430-byte and 133-byte artefacts returned nothing. <em>What is needed:</em> a
 *       captured 430-byte {@code DALYREJS} reject dataset together with the resulting {@code TRANSACT},
 *       {@code ACCTDATA} and {@code TCATBALF} images from a real {@code POSTTRAN} execution at a known
 *       input state. Until those exist, <strong>this harness creates no baseline file and no subclass may
 *       invent one</strong>. A baseline produced by running the Java implementation and then asserting
 *       against it is circular and is forbidden: it would prove only that the code agrees with itself.</li>
 *   <li><strong>A file-unavailable exercise is Not available.</strong> A census across {@code app/cbl}
 *       found the file status {@code '35'} literal zero times and the corresponding not-open response code
 *       zero times, so the legacy corpus never takes that path. No test for it is fabricated here.
 *       <em>What is needed:</em> a legacy program that actually handles that status, or a stated
 *       requirement that the Java tier introduce the path as new behaviour.</li>
 *   <li><strong>A source locator for the CICS transaction-identifier field is Not available.</strong> The
 *       field does not occur anywhere in the repository; the complete exchange-interface-block census in
 *       {@code app/cbl} is the communication-area length field and the attention-identifier field only. It
 *       is supplied by the transaction monitor rather than by the corpus, so <strong>no
 *       {@code app/...} line reference for it may be fabricated</strong>, and the correlation identifier
 *       that replaces it as the thread of request identity is documented as new capability instead.
 *       Severity: <strong>Medium</strong>. <em>What is needed:</em> nothing from this repository - the
 *       citation belongs to the vendor's own copybook, which is not part of this corpus.</li>
 * </ol>
 *
 * <p>Two further findings are recorded for completeness because a subclass will meet them.
 * <strong>Medium:</strong> the retention limit for the report generation base is declared inconsistently in
 * the source - {@code app/jcl/DEFGDGB.jcl:37-39} says {@code LIMIT(5)} with {@code SCRATCH} while
 * {@code app/jcl/REPTFILE.jcl:25-28} says {@code LIMIT(10)} with no {@code SCRATCH} for the same base. It
 * is the one legacy inconsistency actually resolved rather than merely logged, because a single lifecycle
 * value has to be chosen; it is resolved <strong>to 10</strong>, the larger, and retention becomes a
 * documented lifecycle rule rather than an enforced one. <strong>Low:</strong> the job name on
 * {@code app/jcl/OPENFIL.jcl:1} is misspelled relative to its member name while
 * {@code app/jcl/CLOSEFIL.jcl:1} is correct; it is preserved and never repaired.
 *
 * <p>Finally, one scoping note. {@code localstack-init/init-aws.sh} provisions the developer compose
 * topology and is <strong>not a precondition for this tier</strong>, which provisions its own resources
 * into its own container in {@link #registerContainerProperties(DynamicPropertyRegistry)}. That script is
 * owned elsewhere and is neither created nor modified from here.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(AbstractAwsIntegrationTest.FixedClockTestConfiguration.class)
public abstract class AbstractAwsIntegrationTest {

    // =================================================================================================
    // Pinned images. Stated, never defaulted, and never a mutable tag.
    // =================================================================================================

    /**
     * The relational image, referenced by content digest rather than by tag.
     *
     * <p>A digest is content-addressed and cannot move, which a tag can: the same tag was measured
     * resolving to different patch releases at different times, so a build pinned only by tag can exercise
     * the migrated schema against an engine nobody chose. This digest resolves to PostgreSQL 16 and is
     * resident in the provisioned environment's image cache, so the suite starts without reaching the
     * network.
     *
     * <p><strong>The identical digest is named by both sibling harnesses in this tier, and the three must
     * stay equal.</strong> It is written out here rather than shared through a constant because each
     * harness documents a hard limit on its own {@code static} fields, so a shared constant could not live
     * in any of them without weakening a rule that exists to keep global mutable state out of the tier, and
     * because no leaf may own another leaf's substrate. Duplicating a <em>digest</em> is safe in a way that
     * duplicating a tag would not be: if the three ever diverge they name three visibly different immutable
     * images rather than silently resolving to different content under one label.
     */
    private static final String POSTGRES_IMAGE_REFERENCE =
            "postgres@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1";

    /**
     * The emulator image, pinned to an exact release.
     *
     * <p>{@code latest} is refused outright: it would make the outcome of this tier depend on the day it
     * ran, which is the environment-specific assumption Rule 1 Clause C forbids and the determinism Clause A
     * requires. This release is the one the developer compose topology also names, so a test and a locally
     * running application exercise the same emulator.
     */
    private static final String LOCALSTACK_IMAGE_REFERENCE = "localstack/localstack:4.14.0";

    // =================================================================================================
    // Record geometry. Every length is proven by a locator, and none is rounded or inferred.
    // =================================================================================================

    /**
     * Length of a transaction or daily-transaction image, in bytes.
     *
     * <p>Proven four independent ways: {@code app/proc/TRANREPT.prc:29} declares
     * {@code DCB=(LRECL=350,RECFM=FB,BLKSIZE=0)}; {@code app/jcl/CREASTMT.JCL:50} declares
     * {@code DCB=(LRECL=350,BLKSIZE=3500,RECFM=FB)}; {@code app/jcl/CREASTMT.JCL:32} declares
     * {@code RECORDSIZE(350 350)}; and {@code app/cpy/COSTM01.CPY} composes a 32-byte key with a 318-byte
     * remainder, which is 350.
     */
    protected static final int TRANSACTION_RECORD_LENGTH = 350;

    /**
     * Length of a reject record, in bytes.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:176-178} declares {@code 01 REJECT-RECORD.} with
     * {@code 05 REJECT-TRAN-DATA PIC X(350).} followed by {@code 05 VALIDATION-TRAILER PIC X(80).}, so 350
     * plus 80. Corroborated independently by {@code app/jcl/POSTTRAN.jcl:34-36}, which declares
     * {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} - note {@code RECFM=F}, fixed <em>unblocked</em>.
     */
    protected static final int REJECT_RECORD_LENGTH = 430;

    /**
     * Length of the trailer appended to a rejected transaction image, in bytes.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:178}, {@code 05 VALIDATION-TRAILER PIC X(80).}
     */
    protected static final int REJECT_TRAILER_LENGTH = 80;

    /**
     * Length of the numeric failure reason inside the reject trailer, in bytes.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:181}, {@code 05 WS-VALIDATION-FAIL-REASON PIC 9(04).} Being a
     * {@code PIC 9} field it is zero-filled on the left, not blank-padded on the right.
     */
    protected static final int REJECT_FAIL_REASON_LENGTH = 4;

    /**
     * Length of the failure description inside the reject trailer, in bytes.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:182}, {@code 05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76).} Four plus
     * seventy-six is the eighty-byte trailer, which is the arithmetic that resolves the declared 430.
     */
    protected static final int REJECT_FAIL_REASON_DESC_LENGTH = 76;

    /**
     * Length of a printed report line, in bytes.
     *
     * <p>{@code app/proc/TRANREPT.prc:76}, {@code DCB=(LRECL=133,RECFM=FB,BLKSIZE=0)}.
     */
    protected static final int REPORT_LINE_LENGTH = 133;

    /**
     * Length of a statement markup line, in bytes.
     *
     * <p>{@code app/jcl/CREASTMT.JCL:94}, {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)}.
     */
    protected static final int STATEMENT_MARKUP_LINE_LENGTH = 100;

    /**
     * Length of a statement text line, in bytes.
     *
     * <p>{@code app/jcl/CREASTMT.JCL:89}, {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)}.
     */
    protected static final int STATEMENT_TEXT_LINE_LENGTH = 80;

    /**
     * Length of one job-submission parameter record, in bytes.
     *
     * <p>{@code app/csd/CARDDEMO.CSD:502} declares {@code RECORDSIZE(80)} and {@code :503} declares
     * {@code RECORDFORMAT(FIXED)} for the queue that the report submission path writes to. It shares a
     * value with {@link #STATEMENT_TEXT_LINE_LENGTH} but not a meaning, and the two are declared separately
     * so that changing one cannot silently change the other.
     */
    protected static final int JOB_SUBMISSION_RECORD_LENGTH = 80;

    /**
     * Length of the statement work-cluster key, in bytes.
     *
     * <p>{@code app/jcl/CREASTMT.JCL:30} declares {@code KEYS(32 0)}, and {@code app/cpy/COSTM01.CPY}
     * composes {@code 05 TRNX-KEY.} from {@code 10 TRNX-CARD-NUM PIC X(16).} and
     * {@code 10 TRNX-ID PIC X(16).}, which is 32. The two agree, which is what makes either one usable as
     * proof.
     */
    protected static final int STATEMENT_WORK_KEY_LENGTH = 32;

    /** Card-number component of the statement work key. {@code app/cpy/COSTM01.CPY}, {@code PIC X(16)}. */
    protected static final int STATEMENT_KEY_CARD_NUMBER_LENGTH = 16;

    /** Identifier component of the statement work key. {@code app/cpy/COSTM01.CPY}, {@code PIC X(16)}. */
    protected static final int STATEMENT_KEY_TRANSACTION_ID_LENGTH = 16;

    // =================================================================================================
    // Decimal precision. Three tiers that must never be conflated, and no binary floating point anywhere.
    // =================================================================================================

    /** Scale of every monetary and rate quantity in the corpus: two decimal places, without exception. */
    protected static final int MONEY_SCALE = 2;

    /** Precision of an account money column: {@code PIC S9(10)V99} becomes {@code NUMERIC(12,2)}. */
    protected static final int ACCOUNT_MONEY_PRECISION = 12;

    /**
     * Precision of a transaction amount or category balance: {@code PIC S9(09)V99} becomes
     * {@code NUMERIC(11,2)}. Narrower than {@link #ACCOUNT_MONEY_PRECISION} by one digit, deliberately.
     */
    protected static final int TRANSACTION_AMOUNT_PRECISION = 11;

    /** Precision of the disclosure interest rate: {@code PIC S9(04)V99} becomes {@code NUMERIC(6,2)}. */
    protected static final int INTEREST_RATE_PRECISION = 6;

    // =================================================================================================
    // Pinned time. Never the wall clock.
    // =================================================================================================

    /**
     * The single instant this tier runs at.
     *
     * <p>Chosen rather than defaulted, for three reasons. It falls in a <strong>thirty-day month</strong>,
     * so a full-calendar-month range ending on the 30th is distinguishable both from a 31-day month and from
     * a month-to-date range ending on the 10th - which is precisely the discrimination the report range of
     * {@code app/cbl/CORPT00C.cbl:212-238} needs, and a wall clock would make that expectation change on
     * every day but the last. It is also the timestamp the transaction fixtures already carry, so an
     * expectation computed from it agrees with seeded data instead of contradicting it. And it is the
     * instant both sibling harnesses pin, so the three tiers cannot silently disagree about what "now"
     * means.
     */
    protected static final Instant FIXED_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    /**
     * The clock built on {@link #FIXED_INSTANT}, in UTC.
     *
     * <p>The zone is stated rather than inherited, because a default zone would reintroduce exactly the
     * host dependence the fixed instant removes. This same clock is published as the context's
     * {@code @Primary} {@code java.time.Clock} by {@link FixedClockTestConfiguration}, so an expectation
     * computed here and a value produced by the code under test come from one source.
     */
    protected static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    /** The calendar date {@link #FIXED_INSTANT} falls on in UTC, for date-range expectations. */
    protected static final LocalDate FIXED_DATE = LocalDate.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

    /**
     * A probe instant in a twenty-eight-day month.
     *
     * <p>February of a common year. The full-calendar-month range for it is the 1st through the 28th.
     */
    protected static final Instant PROBE_INSTANT_28_DAY_MONTH = Instant.parse("2023-02-14T00:00:00Z");

    /**
     * A probe instant in a twenty-nine-day month.
     *
     * <p>February of a leap year. The full-calendar-month range for it is the 1st through the 29th, which
     * is the case a naive fixed-length month calculation gets wrong.
     */
    protected static final Instant PROBE_INSTANT_29_DAY_LEAP_MONTH = Instant.parse("2024-02-14T00:00:00Z");

    /**
     * A probe instant in a thirty-day month.
     *
     * <p>The same month as {@link #FIXED_INSTANT}, at a different day, so a subclass can prove the range
     * depends on the month rather than on the day within it. The range is the 1st through the 30th.
     */
    protected static final Instant PROBE_INSTANT_30_DAY_MONTH = Instant.parse("2022-06-25T00:00:00Z");

    /**
     * A probe instant in a thirty-one-day month.
     *
     * <p>The full-calendar-month range for it is the 1st through the 31st.
     */
    protected static final Instant PROBE_INSTANT_31_DAY_MONTH = Instant.parse("2022-07-14T00:00:00Z");

    /**
     * A probe instant in December, which is the case that proves the year-roll arithmetic.
     *
     * <p>{@code app/cbl/CORPT00C.cbl:224-228} adds one to the month and then rolls the year when the result
     * exceeds twelve, before subtracting a day. December is the only month that exercises that roll, so an
     * implementation that omits it computes an end date in the wrong year and no other probe detects it.
     * The full-calendar-month range here is 2022-12-01 through 2022-12-31.
     */
    protected static final Instant PROBE_INSTANT_DECEMBER_YEAR_ROLL = Instant.parse("2022-12-14T00:00:00Z");

    /**
     * Formatter for the generation-key discriminator: a compact UTC timestamp, ASCII digits only.
     *
     * <p>{@code Locale.ROOT} is explicit. The platform default is refused because it can emit non-ASCII
     * digits for some locales, which would make an object key depend on the machine that produced it.
     */
    private static final DateTimeFormatter GENERATION_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

    /**
     * Highest generation ordinal whose zero-padded rendering keeps lexicographic and numeric order aligned.
     *
     * <p>{@link #generationPrefix(String, int)} pads to four digits, so ordering holds for 0 through 9999
     * and breaks at 10000, where {@code "10000"} sorts below {@code "9999"}. The bound is stated and
     * enforced rather than left as a latent surprise, because "the current generation" resolves as the
     * lexicographically greatest key.
     */
    protected static final int MAX_GENERATION_ORDINAL = 9999;

    /** Prefix every resource this tier creates carries, so a stray resource is identifiable at a glance. */
    private static final String RESOURCE_NAME_PREFIX = "carddemo-it-";

    /** Longest readable class-derived fragment kept in a resource name before the stable digest. */
    private static final int RESOURCE_NAME_SLUG_BUDGET = 22;

    /** Hexadecimal characters of the class-name digest kept in a resource name. */
    private static final int RESOURCE_NAME_DIGEST_LENGTH = 8;

    /** Longest bucket name the object store accepts. Names are validated against it, never truncated to it. */
    private static final int MAX_BUCKET_NAME_LENGTH = 63;

    /** Shortest bucket name the object store accepts. */
    private static final int MIN_BUCKET_NAME_LENGTH = 3;

    /** Suffix the queue service requires of a first-in-first-out queue. */
    private static final String FIFO_QUEUE_SUFFIX = ".fifo";

    /** Diagnostics for cleanup, the one place where a failure is reported rather than propagated. */
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractAwsIntegrationTest.class);

    // =================================================================================================
    // The two containers: the single documented exception to "no global mutable state".
    // =================================================================================================

    /**
     * The relational substrate: one PostgreSQL 16 container, started once per JVM and shared by every
     * subclass.
     *
     * <p>{@code @ServiceConnection} is what keeps every address and every credential out of this source. It
     * contributes JDBC connection details as a bean, and those details take precedence over the datasource
     * properties, so the URL, the database name, the user and the password all come from the running
     * container and none of them is written here - not even a container default.
     *
     * <p>This tier writes nothing through JPA, so the database is not its subject. It is nonetheless
     * required, because {@code @SpringBootTest} without narrowing starts the whole context: Flyway applies
     * its three migrations and {@code spring.jpa.hibernate.ddl-auto: validate} then genuinely validates the
     * mapped entities against the schema those migrations own. Starting a narrower slice would make the
     * readiness probe that replaces {@code app/jcl/OPENFIL.jcl} untestable and would let schema drift pass
     * unnoticed.
     *
     * <p>{@code asCompatibleSubstituteFor} is required because a digest reference carries no tag, so the
     * library cannot otherwise recognise it as the image whose wait strategy and connection-URL builder it
     * should use. Note also that the declaration takes <strong>no type argument</strong>: on the
     * Testcontainers 2.x line the canonical type is not generic, unlike its deprecated predecessor, and
     * adding a diamond does not compile.
     *
     * <p>This is one of exactly two {@code static} fields in this class. See the class documentation for why
     * that exception exists and why it is not widened, and the initialiser below for why the field carries
     * no {@code org.testcontainers.junit.jupiter.Container} annotation.
     */
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE_REFERENCE).asCompatibleSubstituteFor("postgres"));

    /**
     * The cloud substrate: one emulator container exposing the object store, the queue and the notification
     * service, started once per JVM alongside the database container and shared by every subclass.
     *
     * <p>All three services are requested because all three are this leaf's subject. The object store
     * receives the fixed-width outputs that replace the generation data groups of
     * {@code app/jcl/DEFGDGB.jcl}; the queue is the first-in-first-out replacement for
     * {@code DEFINE TDQUEUE(JOBS)} at {@code app/csd/CARDDEMO.CSD:499-505}; and the notification service
     * stands in for operator notification, whose topic must exist for a publish to resolve an identifier.
     * Services are enabled through {@code withServices(String...)} because the 2.x line takes service names
     * as strings - the enumeration that older code passed here survives only on the deprecated container
     * type and is not used.
     *
     * <p>Every cloud interaction in this tier is with this container. No live endpoint and no live
     * credential appears anywhere in this package, and there is no code path from here that could reach
     * one.
     *
     * <p>This is the second and last {@code static} field in this class.
     */
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE_REFERENCE))
                    .withServices("s3", "sqs", "sns");

    /*
     * ONE CONTAINER LIFECYCLE PER JVM, NOT ONE PER TEST CLASS.
     *
     * The two containers above are started here, exactly once, and are never stopped by the test framework;
     * the Testcontainers resource reaper removes them when the JVM exits.
     *
     * The absence of a field-level @Container annotation is deliberate and is the single most easily
     * "corrected" line in this file, so the mechanism is recorded rather than asserted. It was verified
     * directly against the resolved 2.0.3 artefact rather than taken on trust: the JUnit 5 extension's
     * beforeAll resolves the test class, obtains the store from the CLASS-level extension context, finds
     * the shared containers and starts them into that store; the adapter it wraps each container in
     * implements close() as a call to stop(); and afterAll signals the same class-level entry. A shared
     * static @Container is therefore started in beforeAll and STOPPED in afterAll of every test class.
     *
     * Spring's test context, by contrast, is cached across classes on a key that does not include the test
     * class, so sibling subclasses of this harness share one context. Put those two facts together and the
     * second subclass to run addresses a cached datasource whose pool still points at the first subclass's
     * removed container: the pool reports a closed connection and every test then fails on a connection
     * timeout. This package is planned to carry five concrete subclasses, so that is a certainty rather
     * than a theoretical concern - and both sibling harnesses in this tier record having reproduced it.
     *
     * The alternatives were considered and rejected on the merits. @DirtiesContext would rebuild the whole
     * context for every class, which this tier rules out. Container reuse cannot help, because stop()
     * carries no reuse guard and reuse additionally depends on host-level configuration a deterministic
     * build must not assume. Forcing a distinct context key per subclass cannot be enforced from a base
     * class, so it would fail silently the first time a subclass omitted the marker.
     *
     * The class-level @Testcontainers annotation is kept: it registers the extension so that a subclass may
     * still declare its own @Container field where a genuinely per-class lifecycle is what that subclass
     * wants, and it costs nothing here.
     */
    static {
        try {
            // deepStart brings both up concurrently; join surfaces either failure on this thread.
            Startables.deepStart(POSTGRES, LOCALSTACK).join();
        } catch (final RuntimeException startupFailure) {
            throw new IllegalStateException(
                    "The AWS integration tier could not start its containers. A reachable container runtime "
                            + "is a prerequisite for this tier: there is no in-memory substitute, because an "
                            + "in-memory object store would not preserve record geometry across a real "
                            + "transport and an in-memory queue would not exercise first-in-first-out "
                            + "semantics. Where no daemon or socket is available the correct report is that "
                            + "the gate is blocked, never an untested pass.",
                    startupFailure);
        }
    }

    // =================================================================================================
    // Property wiring. Every value is read from a container accessor; not one is a literal address.
    // =================================================================================================

    /**
     * Contributes every property the application needs that injected connection details cannot supply, and
     * provisions the object-store buckets, the queue and the notification topic before the context refreshes.
     *
     * <p><strong>Inputs.</strong> The registry Spring supplies while creating the context. Every address,
     * region and credential written into it is read from an accessor on {@link #LOCALSTACK}. Not one is a
     * host, a port, an environment variable or a system property - which is the whole point, because Rule 1
     * Clause C forbids environment-specific assumptions and a mapped container port cannot be known in
     * advance. The remaining values are fixed logical resource names, matching the names the developer
     * compose topology provisions so that a test and a locally running application agree about which bucket
     * holds which record length.
     *
     * <p><strong>Outputs.</strong> None; the registry is mutated.
     *
     * <p><strong>Side effects, and why they live here.</strong> This method also creates the three buckets,
     * enables versioning on the output bucket, creates the first-in-first-out queue and creates the
     * notification topic. That work has to happen <em>before</em> the refresh rather than in a per-test
     * hook, because the configured missing-queue strategy is to fail: a queue-backed listener would abort
     * startup against a queue that did not yet exist. This is the only hook that runs after the containers
     * are up and before the context is built, so it is where the work belongs. It is idempotent, following
     * the legacy precedent {@code IF LASTCC=12 THEN SET MAXCC=0} that {@code app/jcl/DEFGDGB.jcl:29}
     * applies after each of its six generation-group definitions: a second call over already-provisioned
     * resources converges instead of failing, which matters because a subclass that customises the context
     * causes a second context and therefore a second call.
     *
     * <p><strong>Error modes.</strong> An already-existing bucket, queue or topic is an accepted control
     * path, not a failure - exactly as a return code of 12 was on the mainframe. Anything else propagates,
     * so the run fails loudly at the earliest point rather than presenting a half-provisioned environment to
     * a test that then fails somewhere far less informative.
     *
     * @param registry the dynamic property registry Spring supplies while creating the context; never
     *     {@code null}
     */
    @DynamicPropertySource
    static void registerContainerProperties(final DynamicPropertyRegistry registry) {
        final URI endpoint = LOCALSTACK.getEndpoint();
        final String region = LOCALSTACK.getRegion();
        final String accessKey = LOCALSTACK.getAccessKey();
        final String secretKey = LOCALSTACK.getSecretKey();

        // The logical names the application binds. Declared as locals rather than static fields because
        // this class documents that no further static field may be added to it.
        final String inputBucket = "carddemo-batch-input";
        final String outputBucket = "carddemo-batch-output";
        final String statementsBucket = "carddemo-statements";
        final String reportQueue = "carddemo-report-jobs" + FIFO_QUEUE_SUFFIX;
        final String notificationTopic = "carddemo-notifications";

        provisionCloudResources(endpoint, region, accessKey, secretKey,
                inputBucket, outputBucket, statementsBucket, reportQueue, notificationTopic);

        registry.add("spring.cloud.aws.region.static", () -> region);
        registry.add("spring.cloud.aws.credentials.access-key", () -> accessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", () -> secretKey);
        registry.add("spring.cloud.aws.s3.endpoint", endpoint::toString);
        registry.add("spring.cloud.aws.sqs.endpoint", endpoint::toString);
        registry.add("spring.cloud.aws.sns.endpoint", endpoint::toString);

        registry.add("carddemo.aws.s3.batch-input-bucket", () -> inputBucket);
        registry.add("carddemo.aws.s3.batch-output-bucket", () -> outputBucket);
        registry.add("carddemo.aws.s3.statements-bucket", () -> statementsBucket);
        registry.add("carddemo.aws.sqs.report-queue", () -> reportQueue);
        registry.add("carddemo.aws.sns.notification-topic", () -> notificationTopic);

        // Deliberately synthetic, deliberately not a secret, and long enough to satisfy the minimum length
        // the token signer enforces. The production property resolves an environment variable with no
        // default so that the application cannot boot with a key an attacker already knows; the test profile
        // supplies no value of its own - a High finding against that file, recorded in the class
        // documentation with its remediation - so a harness in this tier has to supply one, and what it
        // supplies must be recognisable at a glance as test-only material that no deployment could mistake
        // for a real key. Both sibling harnesses do the same, in the same shape.
        registry.add("carddemo.security.jwt.signing-key",
                () -> "carddemo-aws-tier-test-signing-key-not-a-secret");
    }

    /**
     * Creates the three buckets, enables versioning on the output bucket, creates the first-in-first-out
     * queue and creates the notification topic, all idempotently.
     *
     * <p>Short-lived clients are built here rather than taken from the application context, because this
     * runs before the context exists, and they are closed on the way out. Every connection parameter comes
     * from the container, so this method introduces no address and no credential of its own.
     *
     * @param endpoint the container's service endpoint
     * @param region the container's region
     * @param accessKey the container's throwaway access key
     * @param secretKey the container's throwaway secret key
     * @param inputBucket logical name of the batch input bucket
     * @param outputBucket logical name of the batch output bucket, the only versioned one
     * @param statementsBucket logical name of the statements bucket
     * @param reportQueue physical name of the report queue, whose suffix the queue service requires
     * @param notificationTopic name of the operator notification topic
     */
    private static void provisionCloudResources(final URI endpoint, final String region,
            final String accessKey, final String secretKey, final String inputBucket,
            final String outputBucket, final String statementsBucket, final String reportQueue,
            final String notificationTopic) {

        final StaticCredentialsProvider credentials =
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));

        try (S3Client s3 = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .forcePathStyle(Boolean.TRUE)
                .build()) {

            createBucketIfAbsent(s3, inputBucket);
            createBucketIfAbsent(s3, outputBucket);
            createBucketIfAbsent(s3, statementsBucket);

            // Versioning is the mechanism that stands in for a relative generation reference: a next
            // generation becomes a new object under a greater key, and history is retained underneath. It is
            // enabled on the output bucket only, because that is the bucket the generation bases map onto.
            s3.putBucketVersioning(PutBucketVersioningRequest.builder()
                    .bucket(outputBucket)
                    .versioningConfiguration(VersioningConfiguration.builder()
                            .status(BucketVersioningStatus.ENABLED)
                            .build())
                    .build());
        }

        try (SqsClient sqs = SqsClient.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .build()) {

            createFifoQueueIfAbsent(sqs, reportQueue);
        }

        try (SnsClient sns = SnsClient.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .build()) {

            // Topic creation is idempotent at the service level: creating an existing topic with identical
            // attributes returns the same identifier rather than failing, so no guard is needed here.
            sns.createTopic(CreateTopicRequest.builder().name(notificationTopic).build());
        }
    }

    /**
     * Creates a bucket unless it already exists.
     *
     * @param s3 an open object-store client; never {@code null}
     * @param bucket the bucket name to create; never {@code null}
     */
    private static void createBucketIfAbsent(final S3Client s3, final String bucket) {
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (final BucketAlreadyOwnedByYouException | BucketAlreadyExistsException alreadyProvisioned) {
            // An accepted control path, not an error: this is the object-store equivalent of the legacy
            // IF LASTCC=12 THEN SET MAXCC=0 guard at app/jcl/DEFGDGB.jcl:29. Logged at debug with the cause
            // preserved rather than discarded, because Rule 1 Clause B forbids swallowing.
            LOGGER.debug("Bucket '{}' already existed; provisioning converged.", bucket, alreadyProvisioned);
        }
    }

    /**
     * Creates a first-in-first-out queue unless it already exists.
     *
     * <p>Both attributes are required together. The queue service refuses a first-in-first-out queue that
     * does not declare itself one, and it requires a deduplication scheme; content-based deduplication is
     * chosen so that a caller need not invent a deduplication identifier, which the eighty-byte fixed record
     * of {@code app/csd/CARDDEMO.CSD:502-503} had no field for.
     *
     * @param sqs an open queue client; never {@code null}
     * @param queueName the queue name to create, suffix included; never {@code null}
     */
    private static void createFifoQueueIfAbsent(final SqsClient sqs, final String queueName) {
        try {
            sqs.createQueue(CreateQueueRequest.builder()
                    .queueName(queueName)
                    .attributesWithStrings(Map.of(
                            QueueAttributeName.FIFO_QUEUE.toString(), "true",
                            QueueAttributeName.CONTENT_BASED_DEDUPLICATION.toString(), "true"))
                    .build());
        } catch (final QueueNameExistsException alreadyProvisioned) {
            LOGGER.debug("Queue '{}' already existed; provisioning converged.", queueName, alreadyProvisioned);
        }
    }

    // =================================================================================================
    // Injected collaborators. The context's own clients, never rebuilt here.
    // =================================================================================================

    /**
     * The context's object-store client.
     *
     * <p>Injected rather than constructed, so that a subclass exercises the client the application actually
     * configures - including its path-style addressing and its endpoint guard - instead of a throwaway that
     * might be configured differently and would therefore prove nothing about production.
     */
    @Autowired
    private S3Client s3Client;

    /** The context's object-store template, the abstraction the production writers use. */
    @Autowired
    private S3Template s3Template;

    /**
     * The context's queue client.
     *
     * <p>Asynchronous because that is the only queue client the cloud starter contributes; there is no
     * synchronous bean to inject. Calls made through it are completed by
     * {@link #await(CompletableFuture, String)}, which preserves the original failure type.
     */
    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    /** The context's queue template, the abstraction the report submission path uses. */
    @Autowired
    private SqsTemplate sqsTemplate;

    /** The context's notification client. */
    @Autowired
    private SnsClient snsClient;

    /** The fixed clock the context is running on, injected back rather than rebuilt. */
    @Autowired
    private Clock clock;

    /** Physical name of the report queue the application binds, so no subclass restates the literal. */
    @Value("${carddemo.aws.sqs.report-queue}")
    private String reportQueueName;

    /** Logical name of the report queue, which is also the message group identifier. */
    @Value("${carddemo.aws.sqs.report-queue-logical-name}")
    private String reportQueueLogicalName;

    /** Batch input bucket, the object-store replacement for the daily transaction sequential dataset. */
    @Value("${carddemo.aws.s3.batch-input-bucket}")
    private String batchInputBucket;

    /** Batch output bucket - the versioned one, standing in for generation retention. */
    @Value("${carddemo.aws.s3.batch-output-bucket}")
    private String batchOutputBucket;

    /** Statements bucket, holding the eighty-byte text and hundred-byte markup streams. */
    @Value("${carddemo.aws.s3.statements-bucket}")
    private String statementsBucket;

    /** Operator notification topic. */
    @Value("${carddemo.aws.sns.notification-topic}")
    private String notificationTopic;

    // =================================================================================================
    // Per-test resource ledger. Instance state, deliberately: nothing here is shared between tests.
    // =================================================================================================

    /**
     * Buckets this test created, most recent first, each paired with whether versioning was enabled on it.
     *
     * <p>Two separate ledgers would allow the two to drift, so one record type carries both facts. A deque
     * is used rather than a list because cleanup runs in reverse creation order, which is the order least
     * likely to trip a dependency between resources.
     */
    private final Deque<CreatedBucket> createdBuckets = new ArrayDeque<>();

    /** Queue URLs this test created, most recent first. */
    private final Deque<String> createdQueueUrls = new ArrayDeque<>();

    /** Topic identifiers this test created, most recent first. */
    private final Deque<String> createdTopicArns = new ArrayDeque<>();

    /**
     * A bucket this test created, and whether object versioning was enabled on it.
     *
     * @param name the bucket name
     * @param versioned whether versioning was enabled, which decides how the bucket has to be emptied
     */
    private record CreatedBucket(String name, boolean versioned) {
    }

    /**
     * Sole constructor, for subclasses.
     *
     * <p>Explicit and empty. It is declared rather than defaulted so that no initialisation can ever be
     * added to it accidentally: this class is extended, and a constructor that called an overridable method
     * would publish a partially built instance.
     */
    protected AbstractAwsIntegrationTest() {
        // Intentionally empty; all state is injected by the framework or created per test.
    }

    /**
     * Removes every cloud resource this test created, in reverse creation order.
     *
     * <p><strong>Side effects.</strong> Deletes buckets, queues and topics from the emulator container.
     * <strong>It deletes only what this test's own helpers created</strong> - the three buckets, the queue
     * and the topic that {@link #registerContainerProperties(DynamicPropertyRegistry)} provisioned for the
     * application are shared by the whole hierarchy and are never touched here, because removing them would
     * break every later test and the live context along with them.
     *
     * <p>It is an instance method, not a static teardown, precisely so that the ledger it drains is per-test
     * state. That is what makes a test pass when run alone, in any order and twice in succession.
     *
     * <p><strong>Error modes.</strong> A resource that is already absent is an accepted control path, so a
     * repeated or partially failed run converges rather than cascading. Any other failure is reported once
     * at warning level <em>with its root cause attached</em> and does not mask the assertion that the test
     * was actually making; cleanup deliberately continues over the remaining resources rather than
     * abandoning them and leaking. Nothing is swallowed silently and there is no empty catch block.
     */
    @AfterEach
    final void cleanUpResourcesCreatedByThisTest() {
        while (!this.createdTopicArns.isEmpty()) {
            final String topicArn = this.createdTopicArns.pop();
            try {
                this.snsClient.deleteTopic(DeleteTopicRequest.builder().topicArn(topicArn).build());
            } catch (final RuntimeException failure) {
                LOGGER.warn("Could not delete the notification topic this test created. Identifier: {}.",
                        topicArn, failure);
            }
        }

        while (!this.createdQueueUrls.isEmpty()) {
            final String queueUrl = this.createdQueueUrls.pop();
            try {
                await(this.sqsAsyncClient.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build()),
                        "delete the queue at " + queueUrl);
            } catch (final QueueDoesNotExistException alreadyAbsent) {
                LOGGER.debug("Queue at '{}' was already absent; cleanup converged.", queueUrl, alreadyAbsent);
            } catch (final RuntimeException failure) {
                LOGGER.warn("Could not delete the queue this test created. URL: {}.", queueUrl, failure);
            }
        }

        while (!this.createdBuckets.isEmpty()) {
            final CreatedBucket bucket = this.createdBuckets.pop();
            try {
                deleteBucketAndAllVersions(bucket.name());
            } catch (final RuntimeException failure) {
                LOGGER.warn("Could not delete the bucket this test created. Name: {}, versioned: {}.",
                        bucket.name(), bucket.versioned(), failure);
            }
        }
    }

    // =================================================================================================
    // Accessors. The collaborators and configured names a subclass needs, exposed read-only.
    // =================================================================================================

    /**
     * The context's object-store client.
     *
     * @return the injected client, never {@code null}
     */
    protected final S3Client s3Client() {
        return this.s3Client;
    }

    /**
     * The context's object-store template, which is what the production writers go through.
     *
     * @return the injected template, never {@code null}
     */
    protected final S3Template s3Template() {
        return this.s3Template;
    }

    /**
     * The context's queue client.
     *
     * @return the injected asynchronous client, never {@code null}
     */
    protected final SqsAsyncClient sqsAsyncClient() {
        return this.sqsAsyncClient;
    }

    /**
     * The context's queue template, which is what the report submission path goes through.
     *
     * @return the injected template, never {@code null}
     */
    protected final SqsTemplate sqsTemplate() {
        return this.sqsTemplate;
    }

    /**
     * The context's notification client.
     *
     * @return the injected client, never {@code null}
     */
    protected final SnsClient snsClient() {
        return this.snsClient;
    }

    /**
     * The clock the application context is running on.
     *
     * <p>Injected back rather than rebuilt, so that an expectation a subclass computes and a value the code
     * under test produces provably come from the same instance. It equals {@link #FIXED_CLOCK}.
     *
     * @return the fixed clock, never {@code null}
     */
    protected final Clock clock() {
        return this.clock;
    }

    /**
     * Physical name of the report queue, suffix included.
     *
     * <p>Bound from configuration rather than restated, because a retyped literal here could pass while the
     * configured value was wrong - which is the failure the binding is meant to catch.
     *
     * @return the queue name, never blank
     */
    protected final String reportQueueName() {
        return this.reportQueueName;
    }

    /**
     * Logical name of the report queue, which is also the message group identifier a first-in-first-out send
     * has to carry.
     *
     * @return the logical name, never blank
     */
    protected final String reportQueueLogicalName() {
        return this.reportQueueLogicalName;
    }

    /**
     * The batch input bucket: the object-store replacement for the daily transaction sequential dataset that
     * {@code app/jcl/POSTTRAN.jcl} reads. <strong>Not versioned</strong> - input is supplied, not generated.
     *
     * @return the bucket name, never blank
     */
    protected final String batchInputBucket() {
        return this.batchInputBucket;
    }

    /**
     * The batch output bucket: <strong>the versioned one</strong>, and the only one of the three that is.
     *
     * <p>It receives every generation the seven bases of {@code app/jcl/DEFGDGB.jcl:25-57} and
     * {@code app/jcl/DALYREJS.jcl:24-28} produced, namespaced by generation prefix so that they cannot
     * collide. Versioning is what stands in for a relative generation reference; without it the substitution
     * silently loses history and nothing in the application would report it.
     *
     * @return the bucket name, never blank
     */
    protected final String batchOutputBucket() {
        return this.batchOutputBucket;
    }

    /**
     * The statements bucket, holding the eighty-byte text stream of {@code app/jcl/CREASTMT.JCL:89} and the
     * hundred-byte markup stream of {@code :94}. <strong>Not versioned.</strong>
     *
     * @return the bucket name, never blank
     */
    protected final String statementsBucket() {
        return this.statementsBucket;
    }

    /**
     * The operator notification topic, replacing mainframe operator notification.
     *
     * @return the topic name, never blank
     */
    protected final String notificationTopic() {
        return this.notificationTopic;
    }

    // =================================================================================================
    // Deterministic naming. Stable across runs, unique per concrete class, never random.
    // =================================================================================================

    /**
     * Builds a resource name that is unique to the running subclass and identical on every run.
     *
     * <p><strong>Why not a random identifier.</strong> Rule 1 Clause A requires determinism, and a name an
     * assertion later depends on must therefore be reproducible: a random or wall-clock-derived name makes a
     * failure impossible to reproduce and makes a leaked resource impossible to attribute. Uniqueness here
     * comes instead from the concrete test class, which is stable, already unique, and exactly the scope over
     * which a name has to be distinct - the five concrete classes in this package must not collide with each
     * other, and none needs to differ from its own previous run because cleanup removes what it created.
     *
     * <p>The name is composed of a fixed prefix, a readable fragment of the concrete class's simple name, a
     * short digest of its fully qualified name, and the supplied role. The digest is present because the
     * readable fragment is truncated to keep within the object store's length limit, and truncation alone
     * could make two long class names collide; a digest of the untruncated name cannot. The digest is a
     * cryptographic hash of a fixed string, so it is the same on every machine and every run - it is not a
     * source of randomness, and {@link String#hashCode()} was refused for this because nothing guarantees it
     * across implementations.
     *
     * <p>Everything is lower-cased with {@code Locale.ROOT}. The no-argument overload is refused: under a
     * Turkish locale it maps {@code I} to a dotless character that the object store rejects, which would
     * make a name depend on the host's locale.
     *
     * @param role short suffix naming what the resource is for, such as {@code output} or {@code reports};
     *     must be non-{@code null}, non-blank, and composed only of lowercase letters, digits and hyphens
     * @return a name valid as an object-store bucket name, and therefore valid as a queue or topic name too
     * @throws NullPointerException if {@code role} is {@code null}
     * @throws IllegalArgumentException if {@code role} is blank, contains a character the object store
     *     rejects, or makes the resulting name fall outside the permitted length range
     */
    protected final String scopedResourceName(final String role) {
        Objects.requireNonNull(role, "role must not be null: a resource name has to say what it is for");
        final String normalisedRole = role.toLowerCase(Locale.ROOT);
        if (normalisedRole.isBlank()) {
            throw new IllegalArgumentException(
                    "role must not be blank: a resource name has to say what it is for");
        }
        if (!normalisedRole.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "role '%s' may contain only lowercase letters, digits and hyphens, because the object "
                            + "store rejects anything else in a bucket name", role));
        }

        final Class<?> concreteClass = getClass();
        final String slug = truncate(sanitise(concreteClass.getSimpleName()), RESOURCE_NAME_SLUG_BUDGET);
        final String digest = shortDigestOf(concreteClass.getName());
        final String name = RESOURCE_NAME_PREFIX + slug + "-" + digest + "-" + normalisedRole;

        if (name.length() < MIN_BUCKET_NAME_LENGTH || name.length() > MAX_BUCKET_NAME_LENGTH) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "the derived resource name '%s' is %d characters, outside the %d to %d the object store "
                            + "permits. Shorten the role rather than truncating the name, because a truncated "
                            + "name can collide with another test's.",
                    name, name.length(), MIN_BUCKET_NAME_LENGTH, MAX_BUCKET_NAME_LENGTH));
        }
        return name;
    }

    /**
     * Reduces a class name to the lowercase alphanumeric-and-hyphen alphabet a bucket name permits.
     *
     * @param text the text to reduce; never {@code null}
     * @return the reduced text, with runs of hyphens collapsed and any leading or trailing hyphen removed
     */
    private static String sanitise(final String text) {
        final String lowered = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return lowered.replaceAll("^-+", "").replaceAll("-+$", "");
    }

    /**
     * Truncates text to a budget without ever leaving a trailing hyphen behind.
     *
     * @param text the text to truncate; never {@code null}
     * @param budget the greatest number of characters to keep; must be positive
     * @return the text, at most {@code budget} characters long and never ending in a hyphen
     */
    private static String truncate(final String text, final int budget) {
        if (text.length() <= budget) {
            return text;
        }
        return text.substring(0, budget).replaceAll("-+$", "");
    }

    /**
     * Computes a short, stable, lowercase hexadecimal digest of a fixed string.
     *
     * @param text the text to digest; never {@code null}
     * @return the first {@link #RESOURCE_NAME_DIGEST_LENGTH} hexadecimal characters of its SHA-256 digest
     * @throws IllegalStateException if the platform cannot supply SHA-256, which every conformant Java
     *     platform is required to
     */
    private static String shortDigestOf(final String text) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, RESOURCE_NAME_DIGEST_LENGTH);
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable, so a stable resource name cannot be derived. Every conformant "
                            + "Java platform is required to provide it, so this indicates a broken or "
                            + "restricted security provider configuration rather than a defect in this test.",
                    unavailable);
        }
    }

    // =================================================================================================
    // Self-created, self-cleaned resources. Idempotent on the way in, convergent on the way out.
    // =================================================================================================

    /**
     * Creates an unversioned bucket and registers it for cleanup.
     *
     * <p><strong>Side effects.</strong> Creates a bucket in the emulator container and records it, so that
     * {@link #cleanUpResourcesCreatedByThisTest()} removes it after the test method. Idempotent: a bucket
     * that already exists is accepted and still registered, following the legacy precedent
     * {@code IF LASTCC=12 THEN SET MAXCC=0} at {@code app/jcl/DEFGDGB.jcl:29}, so a repeated run converges.
     *
     * @param bucketName the bucket to create; must be non-{@code null} and non-blank
     * @return the same bucket name, so a caller can create and use in one expression
     * @throws NullPointerException if {@code bucketName} is {@code null}
     * @throws IllegalArgumentException if {@code bucketName} is blank
     */
    protected final String createBucket(final String bucketName) {
        return createBucket(bucketName, false);
    }

    /**
     * Creates a bucket with object versioning enabled and registers it for cleanup.
     *
     * <p>Versioning is the mechanism that stands in for a relative generation reference: a next generation
     * becomes a new object under a greater key, with prior content retained as an earlier version. A
     * versioned bucket cannot be deleted while any version or delete marker remains, which
     * {@link #deleteBucketAndAllVersions(String)} handles.
     *
     * <p><strong>Side effects.</strong> Creates a bucket, enables versioning on it, and records it for
     * cleanup. Idempotent in both steps: enabling versioning on an already-versioned bucket is a no-op at
     * the service level.
     *
     * @param bucketName the bucket to create; must be non-{@code null} and non-blank
     * @return the same bucket name
     * @throws NullPointerException if {@code bucketName} is {@code null}
     * @throws IllegalArgumentException if {@code bucketName} is blank
     */
    protected final String createVersionedBucket(final String bucketName) {
        return createBucket(bucketName, true);
    }

    /**
     * Creates a bucket, optionally versioned, and registers it for cleanup.
     *
     * @param bucketName the bucket to create; must be non-{@code null} and non-blank
     * @param versioned whether to enable object versioning
     * @return the same bucket name
     */
    private String createBucket(final String bucketName, final boolean versioned) {
        final String bucket = requireResourceName(bucketName, "bucketName");

        createBucketIfAbsent(this.s3Client, bucket);
        if (versioned) {
            this.s3Client.putBucketVersioning(PutBucketVersioningRequest.builder()
                    .bucket(bucket)
                    .versioningConfiguration(VersioningConfiguration.builder()
                            .status(BucketVersioningStatus.ENABLED)
                            .build())
                    .build());
        }
        this.createdBuckets.push(new CreatedBucket(bucket, versioned));
        return bucket;
    }

    /**
     * Deletes a bucket after removing every object, every non-current object version and every delete
     * marker it holds.
     *
     * <p><strong>Why this is not simply a delete.</strong> An unversioned bucket only has to be emptied of
     * its visible objects. A <em>versioned</em> bucket also retains a version for every overwrite and a
     * delete marker for every logical delete, and the service refuses to remove a bucket while any of those
     * remain - so deleting the visible objects and then the bucket fails with the bucket reported as not
     * empty. Listing object versions returns all three kinds, so one pass over that listing removes
     * everything; each entry must be deleted <em>by version identifier</em>, because deleting by key alone
     * on a versioned bucket adds a delete marker instead of removing anything. This is the boundary
     * condition Rule 1 Clause B is asking about, and it is handled rather than hoped away.
     *
     * <p>The listing is paginated explicitly. A truncated first page silently left behind is exactly how
     * this operation appears to work and then fails once a test writes more objects than one page holds.
     *
     * <p><strong>Side effects.</strong> Irreversibly removes the bucket and all of its content from the
     * emulator container.
     *
     * <p><strong>Error modes.</strong> A bucket that is already absent is an accepted control path and
     * returns quietly, so cleanup converges. Anything else propagates to the caller with its cause intact.
     *
     * @param bucketName the bucket to remove; must be non-{@code null} and non-blank
     * @throws NullPointerException if {@code bucketName} is {@code null}
     * @throws IllegalArgumentException if {@code bucketName} is blank
     */
    protected final void deleteBucketAndAllVersions(final String bucketName) {
        final String bucket = requireResourceName(bucketName, "bucketName");
        try {
            String keyMarker = null;
            String versionIdMarker = null;
            boolean morePages = true;
            while (morePages) {
                final ListObjectVersionsRequest.Builder request =
                        ListObjectVersionsRequest.builder().bucket(bucket);
                if (keyMarker != null) {
                    request.keyMarker(keyMarker);
                }
                if (versionIdMarker != null) {
                    request.versionIdMarker(versionIdMarker);
                }
                final ListObjectVersionsResponse page = this.s3Client.listObjectVersions(request.build());

                for (final ObjectVersion version : page.versions()) {
                    deleteObjectVersion(bucket, version.key(), version.versionId());
                }
                for (final DeleteMarkerEntry marker : page.deleteMarkers()) {
                    deleteObjectVersion(bucket, marker.key(), marker.versionId());
                }

                morePages = Boolean.TRUE.equals(page.isTruncated());
                keyMarker = page.nextKeyMarker();
                versionIdMarker = page.nextVersionIdMarker();
            }

            this.s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
        } catch (final NoSuchBucketException alreadyAbsent) {
            LOGGER.debug("Bucket '{}' was already absent; cleanup converged.", bucket, alreadyAbsent);
        }
    }

    /**
     * Deletes one specific object version, which is the only form of delete that removes content from a
     * versioned bucket.
     *
     * @param bucket the bucket holding it; never {@code null}
     * @param key the object key; never {@code null}
     * @param versionId the version identifier; may be {@code null} on an unversioned bucket, in which case
     *     the request is a plain delete
     */
    private void deleteObjectVersion(final String bucket, final String key, final String versionId) {
        final DeleteObjectRequest.Builder request = DeleteObjectRequest.builder().bucket(bucket).key(key);
        if (versionId != null) {
            request.versionId(versionId);
        }
        this.s3Client.deleteObject(request.build());
    }

    /**
     * Creates a first-in-first-out queue and registers it for cleanup.
     *
     * <p>The queue is first-in-first-out because the transient data queue it replaces was: it was declared
     * {@code RECORDFORMAT(FIXED)} with {@code DISPOSITION(MOD)} at {@code app/csd/CARDDEMO.CSD:502-505},
     * appending records in order to a single reader. That ordering is a behaviour, not an incidental
     * property, so a standard queue is not an acceptable substitute - and it is worth knowing that a
     * first-in-first-out queue <em>rejects</em> a send a standard queue would have accepted, because the
     * send has to carry a message group identifier. Content-based deduplication is enabled so that a caller
     * need not invent a deduplication identifier, for which the eighty-byte fixed record had no field.
     *
     * <p>The name is suffixed automatically if the caller has not already suffixed it, because the queue
     * service requires the suffix and a missing one fails with a message that does not obviously say so.
     *
     * <p><strong>Side effects.</strong> Creates a queue in the emulator container and records it for
     * cleanup. Idempotent: an existing queue is accepted, its URL resolved, and it is still registered.
     *
     * @param queueName the queue to create, with or without the required suffix; must be non-{@code null}
     *     and non-blank
     * @return the URL of the created or already-existing queue, never blank
     * @throws NullPointerException if {@code queueName} is {@code null}
     * @throws IllegalArgumentException if {@code queueName} is blank
     */
    protected final String createFifoQueue(final String queueName) {
        final String requested = requireResourceName(queueName, "queueName");
        final String name = requested.endsWith(FIFO_QUEUE_SUFFIX) ? requested : requested + FIFO_QUEUE_SUFFIX;

        final String queueUrl;
        try {
            queueUrl = await(this.sqsAsyncClient.createQueue(CreateQueueRequest.builder()
                    .queueName(name)
                    .attributesWithStrings(Map.of(
                            QueueAttributeName.FIFO_QUEUE.toString(), "true",
                            QueueAttributeName.CONTENT_BASED_DEDUPLICATION.toString(), "true"))
                    .build()), "create the first-in-first-out queue " + name).queueUrl();
        } catch (final QueueNameExistsException alreadyProvisioned) {
            LOGGER.debug("Queue '{}' already existed; creation converged.", name, alreadyProvisioned);
            final String existing = await(this.sqsAsyncClient.getQueueUrl(
                    builder -> builder.queueName(name)), "resolve the existing queue " + name).queueUrl();
            this.createdQueueUrls.push(existing);
            return existing;
        }

        this.createdQueueUrls.push(queueUrl);
        return queueUrl;
    }

    /**
     * Deletes a queue.
     *
     * <p><strong>Side effects.</strong> Removes the queue from the emulator container.
     *
     * <p><strong>Error modes.</strong> A queue that is already absent is an accepted control path and
     * returns quietly. Anything else propagates with its cause intact.
     *
     * @param queueUrl the URL of the queue to remove; must be non-{@code null} and non-blank
     * @throws NullPointerException if {@code queueUrl} is {@code null}
     * @throws IllegalArgumentException if {@code queueUrl} is blank
     */
    protected final void deleteQueue(final String queueUrl) {
        final String url = requireResourceName(queueUrl, "queueUrl");
        try {
            await(this.sqsAsyncClient.deleteQueue(DeleteQueueRequest.builder().queueUrl(url).build()),
                    "delete the queue at " + url);
        } catch (final QueueDoesNotExistException alreadyAbsent) {
            LOGGER.debug("Queue at '{}' was already absent; cleanup converged.", url, alreadyAbsent);
        }
    }

    /**
     * Creates a notification topic and registers it for cleanup.
     *
     * <p><strong>Side effects.</strong> Creates a topic in the emulator container and records it for
     * cleanup. Idempotent at the service level: creating an existing topic with identical attributes returns
     * the same identifier rather than failing, so no guard is needed and a repeated run converges.
     *
     * @param topicName the topic to create; must be non-{@code null} and non-blank
     * @return the identifier of the created or already-existing topic, never blank
     * @throws NullPointerException if {@code topicName} is {@code null}
     * @throws IllegalArgumentException if {@code topicName} is blank
     */
    protected final String createNotificationTopic(final String topicName) {
        final String name = requireResourceName(topicName, "topicName");
        final String topicArn =
                this.snsClient.createTopic(CreateTopicRequest.builder().name(name).build()).topicArn();
        this.createdTopicArns.push(topicArn);
        return topicArn;
    }

    /**
     * Deletes a notification topic.
     *
     * <p><strong>Side effects.</strong> Removes the topic from the emulator container. The delete is
     * idempotent at the service level, so an absent topic is not an error.
     *
     * @param topicArn the identifier of the topic to remove; must be non-{@code null} and non-blank
     * @throws NullPointerException if {@code topicArn} is {@code null}
     * @throws IllegalArgumentException if {@code topicArn} is blank
     */
    protected final void deleteNotificationTopic(final String topicArn) {
        final String arn = requireResourceName(topicArn, "topicArn");
        this.snsClient.deleteTopic(DeleteTopicRequest.builder().topicArn(arn).build());
    }

    /**
     * Rejects a {@code null} or blank resource identifier before it reaches a service call.
     *
     * <p>A blank name produces a service-side failure whose message names neither the caller nor the
     * parameter, so it is refused here where both are known. Rule 1 Clause B requires null and empty cases
     * to be handled explicitly rather than left to fail somewhere less informative.
     *
     * @param value the supplied value
     * @param parameterName the parameter's name, for the failure message
     * @return {@code value}, unchanged and never trimmed - a name is used exactly as given
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    private static String requireResourceName(final String value, final String parameterName) {
        Objects.requireNonNull(value, parameterName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(parameterName + " must not be blank");
        }
        return value;
    }

    /**
     * Completes an asynchronous service call on the calling thread, preserving the original failure.
     *
     * <p>The asynchronous client wraps a failure in a completion exception, which would hide the service
     * exception type that the idempotency guards above need to see. This unwraps it and rethrows the
     * original where it is a runtime exception, so a caller can still catch the specific
     * already-exists or already-absent type, and otherwise wraps it with context. Rule 1 Clause B forbids
     * swallowing and requires the root cause to be preserved; both paths here do that.
     *
     * @param <T> the response type
     * @param future the call in flight; never {@code null}
     * @param description what was being attempted, phrased to complete "Failed to ..."
     * @return the response
     * @throws IllegalStateException if the call failed with a checked cause, or was cancelled
     */
    private static <T> T await(final CompletableFuture<T> future, final String description) {
        try {
            return future.join();
        } catch (final CompletionException wrapped) {
            final Throwable cause = wrapped.getCause();
            if (cause instanceof RuntimeException serviceFailure) {
                throw serviceFailure;
            }
            throw new IllegalStateException("Failed to " + description + ".",
                    cause == null ? wrapped : cause);
        } catch (final CancellationException cancelled) {
            throw new IllegalStateException("Cancelled while attempting to " + description + ".", cancelled);
        }
    }

    // =================================================================================================
    // Fixed-width payloads. Record length is preserved byte-exactly at the object-store boundary.
    // =================================================================================================

    /**
     * Renders text as a fixed-width field with COBOL {@code PIC X(n)} semantics: blank-padded on the right.
     *
     * <p><strong>Trailing spaces are load bearing.</strong> A field shorter than its declaration is padded,
     * never left short, because a downstream reader locates the next field by offset. Nothing here trims,
     * strips, normalises or re-encodes, and a subclass must not either: a record read back out of a bucket
     * is compared as it arrived.
     *
     * <p>Content longer than the field is <strong>refused rather than truncated</strong>. Silent truncation
     * is how record geometry drifts unnoticed, and a test that quietly shortens its own input proves nothing
     * about the boundary it was written to exercise.
     *
     * @param content the field content; must be non-{@code null}, and may be empty to produce all spaces
     * @param length the exact field width in bytes; must be positive
     * @return a string of exactly {@code length} characters
     * @throws NullPointerException if {@code content} is {@code null}
     * @throws IllegalArgumentException if {@code length} is not positive, or {@code content} is longer than
     *     {@code length}
     */
    protected static String fixedWidth(final String content, final int length) {
        Objects.requireNonNull(content, "content must not be null; pass an empty string for an all-blank field");
        if (length <= 0) {
            throw new IllegalArgumentException(
                    "length must be positive, because every field in the corpus has a declared width; was "
                            + length);
        }
        if (content.length() > length) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "content is %d characters but the field is %d wide. It is refused rather than truncated: "
                            + "silent truncation is how record geometry drifts unnoticed.",
                    content.length(), length));
        }
        final StringBuilder padded = new StringBuilder(length).append(content);
        while (padded.length() < length) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /**
     * Renders text as a fixed-width field and encodes it to bytes.
     *
     * <p>The charset is stated explicitly. A platform-default encoding would make the byte length of a
     * record depend on the machine that produced it, which is the one property this tier exists to pin.
     * Single-byte Latin-1 is used because it maps every code point in the corpus's alphabet to exactly one
     * byte, so a character count and a byte count agree - which is what makes a declared record length
     * meaningful. No transcoding of the mainframe's own code page is attempted anywhere; that is out of
     * scope and no such file is read.
     *
     * @param content the field content; must be non-{@code null}
     * @param length the exact field width in bytes; must be positive
     * @return exactly {@code length} bytes
     * @throws NullPointerException if {@code content} is {@code null}
     * @throws IllegalArgumentException if {@code length} is not positive, or {@code content} is too long
     */
    protected static byte[] fixedWidthBytes(final String content, final int length) {
        return fixedWidth(content, length).getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * Asserts that a payload is exactly the declared number of bytes, naming the locator that declares it.
     *
     * <p>The failure message carries the expected length, the actual length and the source locator, because
     * a bare length mismatch does not tell the reader which declaration is authoritative - and in this
     * corpus the authority is always a specific line of job control or a specific copybook field.
     *
     * @param payload the bytes to measure; must be non-{@code null}
     * @param expectedLength the declared record length
     * @param sourceLocator the locator that fixes it, such as {@code app/jcl/POSTTRAN.jcl:34-36}; must be
     *     non-{@code null}
     * @throws NullPointerException if {@code payload} or {@code sourceLocator} is {@code null}
     * @throws AssertionError if the payload is not exactly {@code expectedLength} bytes
     */
    protected static void assertFixedWidth(final byte[] payload, final int expectedLength,
            final String sourceLocator) {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(sourceLocator, "sourceLocator must not be null: a length claim needs evidence");
        assertThat(payload.length)
                .as("record length must be exactly %d bytes as declared at %s, but was %d. Record length is "
                                + "preserved byte-exactly at the object-store boundary, so a difference here "
                                + "is a parity failure and not a formatting detail.",
                        expectedLength, sourceLocator, payload.length)
                .isEqualTo(expectedLength);
    }

    /**
     * Builds a reject record: a transaction image followed by the validation trailer.
     *
     * <p>The layout is {@code app/cbl/CBTRN02C.cbl:176-182}. The transaction image occupies
     * {@link #TRANSACTION_RECORD_LENGTH} bytes, then a {@link #REJECT_FAIL_REASON_LENGTH}-byte numeric
     * reason and a {@link #REJECT_FAIL_REASON_DESC_LENGTH}-byte description, totalling
     * {@link #REJECT_RECORD_LENGTH} - which is independently what
     * {@code app/jcl/POSTTRAN.jcl:34-36} declares.
     *
     * <p>The reason is a {@code PIC 9(04)} field, so it is <strong>zero-filled on the left</strong>, unlike
     * every {@code PIC X} field here which is blank-padded on the right. Conflating the two is the single
     * most likely way to get this record wrong.
     *
     * @param transactionImage the transaction record content; must be non-{@code null} and at most
     *     {@link #TRANSACTION_RECORD_LENGTH} characters
     * @param failReason the numeric reject reason; must be between 0 and 9999 so that it fits the field
     * @param failReasonDescription the reason text; must be non-{@code null} and at most
     *     {@link #REJECT_FAIL_REASON_DESC_LENGTH} characters
     * @return a string of exactly {@link #REJECT_RECORD_LENGTH} characters
     * @throws NullPointerException if either string argument is {@code null}
     * @throws IllegalArgumentException if any component does not fit its field
     */
    protected static String rejectRecord(final String transactionImage, final int failReason,
            final String failReasonDescription) {
        if (failReason < 0 || failReason > MAX_GENERATION_ORDINAL) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "failReason must fit PIC 9(04), so 0 through %d; was %d",
                    MAX_GENERATION_ORDINAL, failReason));
        }
        return fixedWidth(transactionImage, TRANSACTION_RECORD_LENGTH)
                + String.format(Locale.ROOT, "%0" + REJECT_FAIL_REASON_LENGTH + "d", failReason)
                + fixedWidth(failReasonDescription, REJECT_FAIL_REASON_DESC_LENGTH);
    }

    /**
     * Builds the thirty-two-byte statement work-cluster key.
     *
     * <p>{@code app/cpy/COSTM01.CPY} composes {@code 05 TRNX-KEY.} from a sixteen-character card number
     * followed by a sixteen-character identifier, and {@code app/jcl/CREASTMT.JCL:30} independently declares
     * {@code KEYS(32 0)} on the work cluster. Both components are {@code PIC X}, so both are blank-padded on
     * the right and the card number's field is <em>not</em> zero-filled.
     *
     * @param cardNumber the card number component; must be non-{@code null} and at most
     *     {@link #STATEMENT_KEY_CARD_NUMBER_LENGTH} characters
     * @param transactionId the identifier component; must be non-{@code null} and at most
     *     {@link #STATEMENT_KEY_TRANSACTION_ID_LENGTH} characters
     * @return a string of exactly {@link #STATEMENT_WORK_KEY_LENGTH} characters
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if either component is too long for its field
     */
    protected static String statementWorkKey(final String cardNumber, final String transactionId) {
        return fixedWidth(cardNumber, STATEMENT_KEY_CARD_NUMBER_LENGTH)
                + fixedWidth(transactionId, STATEMENT_KEY_TRANSACTION_ID_LENGTH);
    }

    /**
     * Parses a decimal quantity at the corpus's two-decimal scale, checking it fits the declared precision.
     *
     * <p><strong>No binary floating point appears anywhere in this tier.</strong> Every monetary and rate
     * quantity is a fixed-scale decimal, rounded half-to-even, and a caller comparing two of them must use
     * {@code compareTo} and never {@code equals}: two decimals that differ only in trailing zeros are equal
     * in value but not equal as objects, so {@code equals} reports a difference that does not exist.
     *
     * <p>The precision argument exists because the three tiers must never be conflated - an account money
     * column is {@link #ACCOUNT_MONEY_PRECISION} digits, a transaction amount or category balance is
     * {@link #TRANSACTION_AMOUNT_PRECISION}, and the interest rate alone is {@link #INTEREST_RATE_PRECISION}.
     * A value that overflows its column is refused here rather than at insert time.
     *
     * <p>Zoned-decimal overpunch sign decoding is deliberately <strong>not</strong> implemented here. It
     * belongs to the seed migration, where it is position-aware from the picture clauses, and a second
     * implementation in a test harness could disagree with it without either being obviously wrong.
     *
     * @param text the decimal text, optionally signed; must be non-{@code null} and parsable
     * @param precision the total number of digits the target column permits; must be greater than
     *     {@link #MONEY_SCALE}
     * @return the value at scale {@link #MONEY_SCALE}, rounded half-to-even
     * @throws NullPointerException if {@code text} is {@code null}
     * @throws NumberFormatException if {@code text} is not a valid decimal
     * @throws IllegalArgumentException if {@code precision} is too small, or the value does not fit it
     */
    protected static BigDecimal decimal(final String text, final int precision) {
        Objects.requireNonNull(text, "text must not be null");
        if (precision <= MONEY_SCALE) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "precision must exceed the scale of %d; was %d", MONEY_SCALE, precision));
        }
        final BigDecimal value = new BigDecimal(text).setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
        if (value.precision() - value.scale() > precision - MONEY_SCALE) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s does not fit NUMERIC(%d,%d): it needs %d integer digits but only %d are declared",
                    value.toPlainString(), precision, MONEY_SCALE,
                    value.precision() - value.scale(), precision - MONEY_SCALE));
        }
        return value;
    }

    // =================================================================================================
    // Generation keys. The object-store expression of a relative generation reference.
    // =================================================================================================

    /**
     * Builds the key prefix a next-generation write lands under.
     *
     * <p>This is the object-store expression of a relative next-generation reference: each write goes to a
     * <strong>new object under a monotonically increasing prefix</strong>, so the ordinal orders the
     * generations and the versioned bucket retains what each one replaced. The seven generation bases of
     * {@code app/jcl/DEFGDGB.jcl:25-57} and {@code app/jcl/DALYREJS.jcl:24-28} each get their own
     * {@code gdgPrefix}, which is what keeps them from colliding inside one bucket.
     *
     * <p>The discriminator is derived from {@link #FIXED_INSTANT} and an explicit ordinal, never from the
     * wall clock and never from a random value, because an ordering assertion has to be reproducible. The
     * ordinal is zero-padded so that <strong>lexicographic order and numeric order agree</strong> - which
     * matters because "the current generation" resolves as the lexicographically greatest key. That
     * alignment holds up to {@link #MAX_GENERATION_ORDINAL} and is enforced rather than assumed.
     *
     * <p>Retention is a separate matter and is deliberately not enforced here. The source declares it
     * inconsistently for the report base - see the class documentation - and it is resolved to ten as a
     * documented lifecycle rule rather than something this tier trims.
     *
     * @param gdgPrefix the per-base prefix, as configured for the generation base; must be non-{@code null}
     *     and non-blank
     * @param generation the generation ordinal; must be between 0 and {@link #MAX_GENERATION_ORDINAL}
     * @return the prefix, ending in a separator so that an object key can be appended directly
     * @throws NullPointerException if {@code gdgPrefix} is {@code null}
     * @throws IllegalArgumentException if {@code gdgPrefix} is blank, or the ordinal is out of range
     */
    protected static String generationPrefix(final String gdgPrefix, final int generation) {
        final String prefix = requireResourceName(gdgPrefix, "gdgPrefix");
        if (generation < 0 || generation > MAX_GENERATION_ORDINAL) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "generation must be between 0 and %d so that zero-padded keys sort in numeric order; "
                            + "was %d. Beyond that bound the lexicographically greatest key stops being the "
                            + "latest generation, which is how 'the current generation' is resolved.",
                    MAX_GENERATION_ORDINAL, generation));
        }
        return prefix + "/" + GENERATION_TIMESTAMP_FORMAT.format(FIXED_INSTANT)
                + "-" + String.format(Locale.ROOT, "%04d", generation) + "/";
    }

    /**
     * Resolves the current generation: the lexicographically greatest key under a generation prefix.
     *
     * <p>This is the object-store expression of a relative current-generation reference. Because
     * {@link #generationPrefix(String, int)} pads the ordinal, greatest-by-string is also
     * latest-by-generation.
     *
     * <p>The listing is paginated by the client's own paginator rather than read as a single page, so a
     * prefix holding more objects than one page returns still resolves correctly. Ordering is computed with
     * an explicit comparator over the keys; no reliance is placed on the order the service happens to return
     * them in.
     *
     * <p><strong>Side effects.</strong> None; this reads from the emulator container.
     *
     * @param bucketName the bucket to search; must be non-{@code null} and non-blank
     * @param gdgPrefix the per-base prefix to search under; must be non-{@code null} and non-blank
     * @return the greatest key found, or empty when no generation has been written yet - which is the
     *     equivalent of referencing a generation on an empty base, and is a legitimate state rather than an
     *     error
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if either argument is blank
     */
    protected final Optional<String> currentGenerationKey(final String bucketName, final String gdgPrefix) {
        final String bucket = requireResourceName(bucketName, "bucketName");
        final String prefix = requireResourceName(gdgPrefix, "gdgPrefix");

        return this.s3Client.listObjectsV2Paginator(ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .build())
                .contents()
                .stream()
                .map(S3Object::key)
                .max(Comparator.naturalOrder());
    }

    /**
     * Builds a clock fixed at an arbitrary instant, in UTC.
     *
     * <p>Provided so that a subclass can exercise a month-length or year-roll case with the probe instants
     * above without ever reaching for the wall clock. The zone is always UTC, for the same reason
     * {@link #FIXED_CLOCK} is.
     *
     * @param instant the instant to fix the clock at; must be non-{@code null}
     * @return a fixed clock, never {@code null}
     * @throws NullPointerException if {@code instant} is {@code null}
     */
    protected static Clock clockFixedAt(final Instant instant) {
        Objects.requireNonNull(instant, "instant must not be null");
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    /**
     * Publishes the pinned clock as the context's primary time source.
     *
     * <p>Registered through {@code @Import} on the enclosing class so that it applies to every subclass
     * without any of them having to remember it. The application's own configuration contributes a
     * system-zone clock; this one takes precedence, so the code under test and an expectation computed in a
     * test are reading the same instant. Without it, any assertion on a date the application derived would
     * be correct on one day and wrong on the others - which for the full-calendar-month range of
     * {@code app/cbl/CORPT00C.cbl:212-238} means correct on the last day of the month only.
     *
     * <p>Bean method proxying is disabled because nothing here calls another bean method, and leaving it on
     * would add a proxy for no purpose.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockTestConfiguration {

        /** Sole constructor, used by the framework. */
        FixedClockTestConfiguration() {
            // Intentionally empty; this configuration holds no state.
        }

        /**
         * The fixed clock every bean in the context resolves.
         *
         * <p>It is the same instance the tests see as {@link AbstractAwsIntegrationTest#FIXED_CLOCK}, so the
         * two provably cannot disagree.
         *
         * @return the fixed clock, never {@code null}
         */
        @Bean
        @Primary
        Clock carddemoFixedTestClock() {
            return FIXED_CLOCK;
        }
    }
}

/*
 * ******************************************************************
 * Component   : AbstractBatchIntegrationTest.java
 * Application : CardDemo
 * Type        : JUnit 5 integration-test harness (Spring Boot 3.5.11,
 *               Testcontainers 2.0.3) - abstract, declares no test
 * Function    : Shared substrate for every batch integration test in
 *               com.cardemo.integration.batch. It stands up one
 *               PostgreSQL 16 container and one LocalStack container,
 *               binds the datasource by injected connection details and
 *               the object-store and queue endpoints from the running
 *               container, self-provisions the three buckets and the
 *               one FIFO queue idempotently, pins time to a single
 *               instant inside the legacy report window, and exposes a
 *               deliberately small protected surface: the fixed clock,
 *               the persistence context, a uniform job launcher and a
 *               fail-loud fixed-width fixture reader.
 * Source      : app/catlg/LISTCAT.txt (10 KSDS clusters, 3 alternate
 *               indexes each NONUNIQKEY at :285, :488 and :3678 - the
 *               physical specification the migrated schema this harness
 *               validates against was derived from),
 *               app/data/ASCII/dailytran.txt (105300 bytes, 300 records
 *               of 350 bytes; columns 279-304 carry exactly one
 *               distinct originating timestamp and columns 305-330 are
 *               26 blanks on every record, which is why the processing
 *               timestamp must come from an injected clock),
 *               app/jcl/DEFGDGB.jcl:29 (IF LASTCC=12 THEN SET MAXCC=0 -
 *               the idempotent-provisioning precedent this harness
 *               reproduces against LocalStack),
 *               app/csd/CARDDEMO.CSD:499-505 (DEFINE TDQUEUE(JOBS)
 *               TYPE(EXTRA) DDNAME(INREADER) TYPEFILE(OUTPUT)
 *               RECORDSIZE(80) RECORDFORMAT(FIXED) - the queue the FIFO
 *               queue replaces), app/cbl/CBACT04C.cbl:1-21 (the banner
 *               convention reproduced above),
 *               app/proc/TRANREPT.prc:39-46 (PARM-START-DATE
 *               C'2022-01-01' and PARM-END-DATE C'2022-07-06' with an
 *               INCLUDE COND inclusive at both ends - the window the
 *               pinned instant must fall inside),
 *               CONTRIBUTING.md:33-34 @ 7756d89
 * ******************************************************************
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
 * ******************************************************************
 */
package com.cardemo.integration.batch;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueNameExistsException;

/**
 * Shared harness for the batch integration tier of the CardDemo migration, and the single place where the
 * two container lifecycles, the property wiring, the pinned instant and the fixture reader for
 * {@code com.cardemo.integration.batch} are defined.
 *
 * <h2>What it does</h2>
 *
 * <p>This class is {@code abstract} and declares <strong>no test method</strong>. It exists so that every
 * concrete batch integration test starts from an identical, deterministic substrate rather than assembling
 * one of its own. It contributes exactly five things and nothing else:
 *
 * <ul>
 *   <li>One PostgreSQL 16 container, bound to the application datasource by injected connection details, so
 *       that {@code spring.jpa.hibernate.ddl-auto: validate} genuinely validates all 11 entities against the
 *       schema the three Flyway migrations create.</li>
 *   <li>One LocalStack container exposing the object store and the queue, so that the byte-exact outputs the
 *       parity contract is measured on - the 430-byte reject record, being the 350-byte transaction image
 *       plus an 80-byte trailer of a four-digit reason code and a 76-character description, the 133-byte
 *       report line and the 80-byte and 100-byte statement lines - can be written and read back.</li>
 *   <li>Deterministic property values for the object store, the queue and the token signing key, every one
 *       of them read from a container accessor or fixed as a logical resource name.</li>
 *   <li>A clock pinned to one instant, published as a bean so the production tier consumes it.</li>
 *   <li>A protected surface of seven members, documented one by one below.</li>
 * </ul>
 *
 * <p><strong>Why this tier needs two containers where the repository tier needs one.</strong> The five data
 * jobs of the migrated batch stream span both substrates in a single run: they read and write the 11
 * PostgreSQL tables, they emit fixed-width objects to the object store in place of the seven generation data
 * group bases catalogued in {@code app/jcl/DEFGDGB.jcl}, and the report path publishes to the FIFO queue that
 * replaces {@code DEFINE TDQUEUE(JOBS) TYPE(EXTRA) DDNAME(INREADER) TYPEFILE(OUTPUT) RECORDSIZE(80)
 * RECORDFORMAT(FIXED)} at {@code app/csd/CARDDEMO.CSD:499-505}. A harness with only a database container
 * could not exercise the object-store or queue half of any of them, so the sibling repository tier's
 * single-container exception is deliberately widened to two here. That widening is the whole of the
 * justification, and it is recorded rather than assumed.
 *
 * <p><strong>Shared containers, isolated data - and why those are not in conflict.</strong> One deterministic
 * container lifecycle each is wanted for correctness and for cost: a per-test restart would add tens of
 * seconds per test and would prove nothing a rollback does not. Avoiding global mutable state is wanted for
 * the same reason. The two are reconciled by <em>per-test transactional rollback</em>, not by per-test
 * container restart: this class carries {@code @Transactional}, so Spring opens a transaction before each
 * subclass test method and rolls it back afterwards. <strong>The containers are shared; the data is
 * not.</strong> Nothing is truncated, no seeded row is deleted and no cleanup script runs - the three
 * migrations own the schema and the seed data, and wiping seeded rows would break every other test's
 * expected counts.
 *
 * <p><strong>The one carve-out: a job launch is not, and cannot be, inside that rollback.</strong> Spring
 * Batch refuses job repository work while a transaction is active and commits every chunk on its own
 * transaction, so a launched job neither joins nor can be undone by the test transaction. A test method that
 * calls {@link #launchJob(org.springframework.batch.core.Job,
 * org.springframework.batch.core.JobParameters)} must therefore be annotated
 * {@code @Transactional(propagation = Propagation.NOT_SUPPORTED)}, and must treat everything the job reads as
 * committed seed data and everything it writes as permanent for the remainder of the run. That method
 * documents the constraint, the measurement behind it and the one-line remedy in full; it refuses loudly
 * rather than letting the framework fail several frames away.
 *
 * <p><strong>The two container fields are the only static state in this package.</strong> They are static
 * because one lifecycle has to span the whole class hierarchy rather than restart per class or per test, and
 * they are {@code final} so they are immutable after start. Neither carries test-visible mutable state. Every
 * other value this class needs - the pinned instant, the property keys, the logical bucket and queue names -
 * is an inline literal at its single point of use or a bean, precisely so that no further static field is
 * introduced. That is the entire exception, and it is not widened again.
 *
 * <p><strong>Why the container fields are started by a static initialiser and carry no
 * {@code org.testcontainers.junit.jupiter.Container} annotation.</strong> A shared {@code @Container} is
 * started in {@code beforeAll} and <em>stopped in {@code afterAll} of every test class</em>, because the
 * extension holds it in the class-level store. Spring's test context is cached on a key that does not include
 * the test class, so two subclasses of this harness with the same configuration share one context. Together
 * those two facts strand the cached connection pool on a removed container, and every test in the second class
 * fails on pool exhaustion rather than on anything it asserted. That was reproduced with two identically
 * configured probe classes before this harness was settled, which is why the containers are started once here
 * instead and left for the resource reaper to remove at JVM exit. The block beside the fields records the
 * measurement and why each alternative was rejected.
 *
 * <h2>Numeric comparison policy, stated once so no subclass restates it</h2>
 *
 * <p>Money and rates are {@code java.math.BigDecimal} throughout, with {@code RoundingMode.HALF_EVEN}, at the
 * precision each COBOL picture clause dictates: {@code S9(10)V99} becomes {@code NUMERIC(12,2)} - the five
 * account money columns; {@code S9(09)V99} becomes {@code NUMERIC(11,2)} - {@code TRAN-AMT},
 * {@code DALYTRAN-AMT} and {@code TRAN-CAT-BAL}; and {@code S9(04)V99} becomes {@code NUMERIC(6,2)} -
 * {@code DIS-INT-RATE} alone. <strong>No {@code float} and no {@code double} appears in any financial
 * field.</strong>
 *
 * <p><strong>Compare by {@code compareTo}, never by {@code equals}.</strong>
 * {@code new BigDecimal("194.00").equals(new BigDecimal("194.0"))} is {@code false} while their
 * {@code compareTo} is {@code 0}, and a PostgreSQL {@code NUMERIC(n,2)} column always reads back at scale 2.
 * An assertion written with {@code equals} - which is what an assertion library's default equality check uses
 * for {@code BigDecimal} - therefore fails on a value that is arithmetically correct. Subclasses assert money
 * with a comparison-based matcher.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Build and run everything with the pinned wrapper: {@code ./mvnw clean verify}.
 *
 * <p><strong>Which plugin collects this tier, and why the path is load-bearing.</strong> Failsafe 3.5.4 is
 * bound to {@code src/test/java/com/cardemo/integration/**} and {@code .../e2e/**} and runs them at
 * {@code integration-test} and {@code verify}, even though the classes keep the {@code Test} suffix. Surefire
 * 3.5.4 is bound to {@code .../unit/**} and explicitly excludes both of those trees. A class moved out of
 * {@code integration/**} therefore matches neither include set and is collected by <em>neither</em> plugin:
 * the build stays green, both plugins report success, and the tests silently never run. Do not rename or
 * relocate this class or any subclass of it.
 *
 * <p><strong>A reachable container runtime is a prerequisite.</strong> This tier starts real containers; there
 * is no in-memory substitute and none is wanted, because an in-memory database would not validate the
 * PostgreSQL schema and an in-memory queue would not exercise FIFO semantics. Where no daemon or socket is
 * available the correct report is that the gate is blocked, together with the prerequisite - never an
 * untested pass.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li>Profile {@code test} is active. It deliberately declares no datasource URL, host, port, username or
 *       password, and this class mirrors that exactly: the datasource is bound from injected connection
 *       details, so no address and no credential is written anywhere in this source. Neither is there any
 *       ambient-environment lookup, nor any read or write of a JVM-wide system property - a build that
 *       reaches for the surrounding environment is not deterministic.</li>
 *   <li>Container image tags are stated, never defaulted: PostgreSQL {@code postgres:16} and LocalStack
 *       {@code localstack/localstack:4.14.0}. An image tag is the one literal a deterministic build
 *       requires; the database field documents why that tag names the major line and the Debian-based
 *       image rather than a patch level or the {@code alpine} variant.</li>
 *   <li>Time is pinned to {@code 2022-06-10T19:27:53Z} in UTC and published as a {@code java.time.Clock}
 *       bean. See the determinism note below for why the value is not arbitrary.</li>
 *   <li>{@code spring.batch.job.enabled} is {@code false}, so no job runs at startup and every launch is
 *       explicit. This class adds no runner, no scheduler and no {@code @EnableBatchProcessing} - on Spring
 *       Boot 3.x that annotation <em>disables</em> Batch auto-configuration and would remove the
 *       {@code JobLauncher} this harness depends on.</li>
 *   <li>The Spring Batch {@code BATCH_*} metadata tables come from {@code spring.batch.jdbc.initialize-schema},
 *       which the {@code test} profile sets to {@code always}. They are framework bookkeeping, never a fourth
 *       Flyway migration and never part of the 11-table business schema.</li>
 *   <li>Exactly three migrations apply from {@code classpath:db/migration}, in order:
 *       {@code V1__create_schema.sql} creates 11 tables with every column {@code NOT NULL}, 5 {@code CHECK}
 *       constraints, 10 foreign keys and a {@code version BIGINT NOT NULL} column on exactly four of them -
 *       {@code account}, {@code card}, {@code customer} and the reserved-word table emitted double-quoted
 *       lowercase as {@code "transaction"}; {@code V2__create_indexes.sql} creates three
 *       <strong>non-unique</strong> B-tree indexes, matching the three {@code NONUNIQKEY} alternate indexes
 *       at {@code app/catlg/LISTCAT.txt:285}, {@code :488} and {@code :3678}, and creates no unique index at
 *       all; {@code V3__seed_data.sql} seeds ten tables and leaves {@code "transaction"} empty.</li>
 *   <li>{@code spring.main.allow-bean-definition-overriding} is {@code false}, so a bean-name collision fails
 *       the context refresh instead of silently shadowing. The clock bean this class publishes is therefore
 *       given a name of its own and marked primary rather than reusing a production bean name.</li>
 * </ul>
 *
 * <h2>Determinism: why the clock is pinned, and to that instant</h2>
 *
 * <p>{@code TRAN-PROC-TS} is generated at post time, not carried on the input. In
 * {@code app/data/ASCII/dailytran.txt} columns 305-330 hold 26 blanks on all 300 records - exactly one
 * distinct all-space value - so the processing timestamp comes <em>entirely</em> from the clock. The
 * transaction report then filters the processing date twice: once at the sort layer, where
 * {@code app/proc/TRANREPT.prc:39-42} declares {@code PARM-START-DATE,C'2022-01-01'} and
 * {@code PARM-END-DATE,C'2022-07-06'} and {@code :45-46} applies
 * {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)}, inclusive at both
 * ends; and again inside the processor. A batch tier that read the ambient clock would stamp every generated
 * processing date far outside that window, both filters would exclude every record, and the transaction
 * report would come back <strong>empty</strong> - a failure that looks like a defect in the report and is not
 * one.
 *
 * <p>The pinned value is {@code 2022-06-10T19:27:53Z}. It is not chosen for convenience: columns 279-304 of
 * all 300 fixture records carry exactly one distinct originating timestamp, {@code 2022-06-10 19:27:53.000000},
 * so pinning to that instant makes the generated processing timestamp agree with the input's own notion of
 * present moment, and places it comfortably inside the inclusive 2022-01-01 to 2022-07-06 window. Nothing in
 * this package may reach the wall clock by any route - no current-instant or current-date accessor on
 * {@code java.time}, no epoch-millisecond reading, no legacy date construction. The injected clock is the
 * only time source, and {@link #clock()} is how a subclass obtains it.
 *
 * <p>Timestamps themselves stay {@code CHAR(26)} strings, never a temporal column type, and are rendered to
 * six fractional digits of which the trailing four are literal zeros. Three incompatible producers exist in
 * the source - a batch rendering, an online rendering and a pure pass-through - and they are not normalised
 * into one. Every case and format operation uses {@code java.util.Locale#ROOT}: the source's comparison
 * asymmetry is case-based, and a platform-default locale would silently diverge on the first Turkish-locale
 * machine that ran the suite. Nothing here depends on randomness, on hash order or on map iteration order,
 * and no second thread or second connection is opened to demonstrate locking.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>Container startup fails, or every test in the tier is skipped</dt>
 *   <dd>No container runtime is reachable. Start the daemon and re-run; report the gate as blocked rather
 *       than as a pass if it cannot be started.</dd>
 *
 *   <dt>{@code Could not resolve dependencies ... org.testcontainers:localstack:2.0.3}</dt>
 *   <dd>The Testcontainers 2.x line renamed every module artefact, and the bare {@code postgresql},
 *       {@code localstack} and {@code junit-jupiter} ids under {@code org.testcontainers} do not exist at
 *       2.0.3. The remedy has two halves and <em>both</em> are required: pin the managed version by
 *       overriding the version property - never by importing a second bill of materials, since Spring Boot
 *       3.5.11 already imports one at a 1.x version and a competing import resolves in whichever order it
 *       likes - and use only the four prefixed coordinates {@code testcontainers},
 *       {@code testcontainers-postgresql}, {@code testcontainers-localstack} and
 *       {@code testcontainers-junit-jupiter}. Overriding without renaming resolves artefacts that do not
 *       exist; renaming without overriding resolves the wrong version. This is a Blocker, and the build file
 *       is not the place to work around it.</dd>
 *
 *   <dt>{@code warnings found and -Werror specified}</dt>
 *   <dd>Compilation escalates every warning to an error, so one unused import, one raw type or one deprecated
 *       call fails the whole build. The most common trigger in this tier is the deprecated Testcontainers
 *       types: import {@code org.testcontainers.postgresql.PostgreSQLContainer} and
 *       {@code org.testcontainers.localstack.LocalStackContainer}, never the legacy
 *       {@code org.testcontainers.containers} equivalents. Note also that the 2.x PostgreSQL container type
 *       is <em>not</em> generic, so it is declared without a type argument.</dd>
 *
 *   <dt>The transaction report comes back empty</dt>
 *   <dd>Something in the path under test read the ambient clock instead of the injected one. See the
 *       determinism note above: this is the single most expensive mistake available in this package, because
 *       it produces a plausible-looking empty report rather than an error.</dd>
 *
 *   <dt>Startup fails inside Hibernate schema validation</dt>
 *   <dd>{@code ddl-auto: validate} compares JDBC type codes, so a column type that differs from the mapped
 *       one aborts the refresh. The fix belongs upstream, in {@code V1__create_schema.sql} or in the entity.
 *       Do not widen a column to make the message go away and do not patch the test.</dd>
 *
 *   <dt>{@code Connection is not available, request timed out after 30000ms (total=0, active=0, idle=0)},
 *       usually preceded by {@code This connection has been closed}</dt>
 *   <dd>A cached application context is addressing a container that no longer exists. It means something
 *       reintroduced a per-class container lifecycle - almost always a field-level
 *       {@code org.testcontainers.junit.jupiter.Container} annotation on one of the two container fields, or a
 *       second pair of containers declared in a subclass. Remove it: the containers here are started once per
 *       JVM on purpose, and the reasoning is recorded beside them.</dd>
 *
 *   <dt>{@code Existing transaction detected in JobRepository}, or the refusal message this harness raises
 *       in its place</dt>
 *   <dd>A test method called the job-launch helper without opting out of the class-level transaction. Annotate
 *       that method {@code @Transactional(propagation = Propagation.NOT_SUPPORTED)}. Do not reach for
 *       {@code setValidateTransactionState(false)}: the guard is correct, and switching it off would buy a
 *       false promise of rollback that Spring Batch's per-chunk commits cannot keep.</dd>
 *
 *   <dt>{@code JobInstanceAlreadyCompleteException} on a second launch</dt>
 *   <dd>The same job was launched twice with the same identifying parameters, and a job launch is outside the
 *       rollback so the first instance survived. Vary a parameter. This exposure is deliberate: the migration
 *       plan requires a repeated run to collide rather than be silently absorbed.</dd>
 *
 *   <dt>{@code BeanDefinitionOverrideException} naming a clock</dt>
 *   <dd>A production configuration has published a clock bean under the same name as the one here. Bean
 *       definition overriding is switched off deliberately. Rename, do not enable overriding.</dd>
 *
 *   <dt>A subclass declared {@code final}, and the context refresh fails building a proxy for it</dt>
 *   <dd>{@code @Import} on a test class is honoured by registering the test class itself as a configuration
 *       class, and the class-level {@code @Transactional} here then makes it a proxy target. Leave concrete
 *       subclasses non-final. A subclass that needs extra beans of its own may declare its own nested
 *       {@code @TestConfiguration}, which is detected normally.</dd>
 * </dl>
 *
 * <p><strong>Evidence not available, stated rather than invented.</strong> No captured legacy output exists
 * anywhere in the repository to compare against: a search for expected, baseline and golden artefacts, for
 * {@code .out} and system-output captures, and for reject, report, statement and HTML dataset captures
 * returned dataset <em>definition</em> job control only and zero captured data. Producing a boundary-parity
 * baseline needs a captured 430-byte reject dataset plus the resulting transaction, account and
 * category-balance images from a real posting run at a known input state. Until those exist this harness
 * creates no baseline file and no subclass may invent one. Likewise, file status {@code '35'} and its
 * file-unavailable response code do not occur anywhere in the COBOL corpus, so no test for that path is
 * fabricated here.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
@Import(AbstractBatchIntegrationTest.FixedClockTestConfiguration.class)
public abstract class AbstractBatchIntegrationTest {

    /**
     * The relational substrate: one PostgreSQL 16 container, started once per JVM and shared by every subclass.
     *
     * <p>{@code @ServiceConnection} is what keeps every address and every credential out of this source. It
     * contributes JDBC connection details as a bean, and those details take precedence over the datasource
     * properties, so the URL, the database name, the user and the password all come from the running
     * container and none of them is written here - not even the container defaults.
     *
     * <p>The image tag is stated rather than defaulted, because the migrated schema is written against
     * PostgreSQL 16 and letting the library pick would make the build's outcome depend on the day it ran. It
     * names the major line, not a patch level or a digest, and that is a deliberate choice with two reasons
     * rather than an oversight. It is the tag the provisioned environment has already pulled for this tier, so
     * the suite runs without reaching the network - and a test that needs the network to start is not
     * deterministic. And it is the Debian-based image: the {@code alpine} variant the compose stack uses for
     * the developer database is built on musl, whose text collation orders differently from glibc, which in a
     * tier whose contract includes reproducing sort order exactly is a divergence worth refusing. The residual
     * risk is small and real - a republished {@code 16} tag could move the patch level - and the remedy, once
     * the environment's image cache is refreshed to match, is to name a digest here.
     *
     * <p>Note the declaration has no type argument: on the Testcontainers 2.x line this type is not generic,
     * unlike its deprecated predecessor in {@code org.testcontainers.containers}. Adding one does not
     * compile.
     *
     * <p>This is one of exactly two static fields in this package. See the class documentation for why that
     * exception exists, why it is not widened further, and why the field carries no
     * {@code org.testcontainers.junit.jupiter.Container} annotation.
     */
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16"));

    /**
     * The cloud substrate: one LocalStack container exposing the object store and the queue, started once per
     * JVM alongside the database container and shared by every subclass.
     *
     * <p>Only the two services this tier actually uses are requested. The object store receives the
     * fixed-width batch outputs that replace the generation data groups of {@code app/jcl/DEFGDGB.jcl}, and
     * the queue is the FIFO replacement for {@code DEFINE TDQUEUE(JOBS)} at
     * {@code app/csd/CARDDEMO.CSD:499-505}. The notification service is not requested, because no test in
     * this tier publishes a notification; its topic <em>name</em> is still supplied as a property so that
     * property resolution succeeds, which is a different thing from starting the service.
     *
     * <p>All interaction is with this container. No live endpoint and no live credential appears anywhere in
     * this tier, and there is no code path from here that could reach one.
     *
     * <p>This is the second and last static field in this package.
     */
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
                    .withServices("s3", "sqs");

    /*
     * ONE CONTAINER LIFECYCLE PER JVM, NOT ONE PER TEST CLASS.
     *
     * The two containers above are started here, exactly once, and are never stopped by the test framework;
     * the Testcontainers resource reaper removes them when the JVM exits. This is the singleton-container
     * arrangement, and it is what makes the requirement "reused across the entire class hierarchy - one
     * lifecycle each" literally true.
     *
     * It replaces the field-level @Container annotation deliberately, on measured evidence. The JUnit 5
     * Testcontainers extension puts a shared @Container into the *class-level* extension store, so the
     * container is started in beforeAll and STOPPED in afterAll of every test class. Spring's test context,
     * by contrast, is cached across classes on a key that does not include the test class, so two sibling
     * subclasses of this harness with the same configuration share one context. Put those two facts together
     * and the second class runs against a cached datasource whose Hikari pool still addresses the first
     * class's now-removed container: the pool logs "This connection has been closed" and every test then
     * fails with "Connection is not available, request timed out after 30000ms (total=0, active=0, idle=0)".
     * That was reproduced here with two probe classes carrying identical configuration - the first passed,
     * the second failed exactly that way - which makes it a Blocker for a seven-subclass tier rather than a
     * theoretical concern. Remediation is this static start plus the absence of @Container; the alternatives
     * were all rejected on the merits: @DirtiesContext would rebuild the context for every class and is ruled
     * out for this tier, withReuse(true) cannot help because GenericContainer.stop() carries no reuse guard
     * and reuse additionally depends on host-level configuration this build must not assume, and forcing a
     * distinct context key per subclass cannot be enforced from a base class, so it would fail silently the
     * first time a subclass omitted the marker.
     *
     * The class-level @Testcontainers annotation is kept: it registers the extension so that a subclass may
     * still declare its own @Container field where a per-class lifecycle is genuinely what that subclass
     * wants, and it costs nothing here.
     */
    static {
        try {
            // deepStart brings both up concurrently; join surfaces either failure on this thread.
            Startables.deepStart(POSTGRES, LOCALSTACK).join();
        } catch (final RuntimeException startupFailure) {
            throw new IllegalStateException(
                    "The batch integration tier could not start its containers. A reachable container runtime "
                            + "is a prerequisite for this tier: there is no in-memory substitute, because an "
                            + "in-memory database would not validate the PostgreSQL schema and an in-memory "
                            + "queue would not exercise FIFO semantics. Where no daemon or socket is available "
                            + "the correct report is that the gate is blocked, never an untested pass.",
                    startupFailure);
        }
    }

    /**
     * Contributes every property the application needs that injected connection details cannot supply, and
     * provisions the object-store buckets and the FIFO queue before the application context is refreshed.
     *
     * <p><strong>Inputs.</strong> The registry Spring supplies during context creation. Every value written
     * into it is read from a container accessor - endpoint, region, access key, secret key - or is a fixed
     * logical resource name. Not one of them is a host, a port, an address, an environment variable or a
     * system property, which is the whole point: the build must not depend on the machine it runs on.
     *
     * <p><strong>Outputs.</strong> None; the registry is mutated. The properties registered are the object
     * store, queue and notification endpoints and the static region and credentials for the AWS clients; the
     * three logical bucket names and the physical FIFO queue name the application binds; and the token
     * signing key, which resolves an environment variable with no default and would otherwise fail the
     * context refresh before any test ran.
     *
     * <p><strong>Side effects, and why they live here.</strong> This method also creates the three buckets,
     * enables versioning on the output bucket and creates the FIFO queue. That provisioning has to happen
     * <em>before</em> the context refreshes rather than in a per-test hook, because the queue-not-found
     * strategy is to fail: any queue-backed listener would abort startup against a queue that did not yet
     * exist. This method is the only hook that runs after the containers are up and before the context is
     * built, so it is where the work belongs. It is idempotent, following the legacy precedent
     * {@code IF LASTCC=12 THEN SET MAXCC=0} at {@code app/jcl/DEFGDGB.jcl:29}: a second call over
     * already-provisioned resources converges instead of failing, which matters because a subclass that
     * customises the context causes a second context - and therefore a second call.
     *
     * <p><strong>Error modes.</strong> An already-existing bucket or queue is an accepted control path, not a
     * failure. Anything else - an unreachable container, a refused request, a queue whose FIFO attributes
     * disagree with the ones requested - propagates, so the run fails loudly at the earliest point rather
     * than presenting a half-provisioned environment to a test that then fails somewhere less informative.
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

        // The logical names the application binds. They match the names the container-runtime initialisation
        // script provisions for the compose topology, so a test and a locally running application agree about
        // which bucket holds which record length.
        final String inputBucket = "carddemo-batch-input";
        final String outputBucket = "carddemo-batch-output";
        final String statementsBucket = "carddemo-statements";
        final String reportQueue = "carddemo-report-jobs.fifo";

        provisionCloudResources(endpoint, region, accessKey, secretKey,
                inputBucket, outputBucket, statementsBucket, reportQueue);

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
        registry.add("carddemo.aws.sns.notification-topic", () -> "carddemo-notifications");

        // Deliberately synthetic, deliberately not a secret, and deliberately long enough to satisfy the
        // 32-byte minimum the token signer enforces. The production property resolves an environment variable
        // with no default so that the application cannot boot with a key an attacker already knows; a test
        // still has to supply something, and what it supplies must be recognisable at a glance as test-only
        // material that no deployment could mistake for a real key.
        registry.add("carddemo.security.jwt.signing-key",
                () -> "carddemo-integration-test-signing-key-not-a-secret");
    }

    /**
     * Creates the three buckets, enables versioning on the output bucket and creates the FIFO queue, all
     * idempotently.
     *
     * <p>Short-lived clients are built here rather than taken from the application context, because this runs
     * before the context exists. They are closed on the way out. Every connection parameter comes from the
     * container, so this method introduces no address and no credential of its own.
     *
     * @param endpoint         the container's service endpoint
     * @param region           the container's region
     * @param accessKey        the container's access key
     * @param secretKey        the container's secret key
     * @param inputBucket      logical name of the batch input bucket
     * @param outputBucket     logical name of the batch output bucket, the only versioned one
     * @param statementsBucket logical name of the statements bucket
     * @param reportQueue      physical name of the report FIFO queue, whose suffix the queue service requires
     */
    private static void provisionCloudResources(final URI endpoint, final String region,
            final String accessKey, final String secretKey, final String inputBucket,
            final String outputBucket, final String statementsBucket, final String reportQueue) {

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

            // Relative generation references become object versions over a versioned bucket, so the output
            // bucket - and only the output bucket - carries versioning. Re-applying the same configuration is
            // a no-op, which is what makes this safe to run twice.
            s3.putBucketVersioning(request -> request
                    .bucket(outputBucket)
                    .versioningConfiguration(VersioningConfiguration.builder()
                            .status(BucketVersioningStatus.ENABLED)
                            .build()));
        }

        final Map<QueueAttributeName, String> fifoAttributes = new EnumMap<>(QueueAttributeName.class);
        fifoAttributes.put(QueueAttributeName.FIFO_QUEUE, "true");
        fifoAttributes.put(QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true");

        try (SqsClient sqs = SqsClient.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .build()) {
            try {
                sqs.createQueue(request -> request.queueName(reportQueue).attributes(fifoAttributes));
            } catch (final QueueNameExistsException alreadyProvisioned) {
                // Accepted control path, not an error: a queue of this name survives from an earlier context in
                // the same run, exactly as a duplicate DEFINE is accepted at app/jcl/DEFGDGB.jcl:29. The
                // service raises this only when the existing attributes differ from the requested ones, so the
                // response is verified rather than assumed.
                confirmFifoQueuePresent(sqs, reportQueue, alreadyProvisioned);
            }
        }
    }

    /**
     * Creates one bucket, treating an existing bucket of the same name as a converged outcome rather than a
     * failure.
     *
     * @param s3         the client to create through
     * @param bucketName logical name of the bucket to create
     * @throws IllegalStateException if the service reports the bucket already exists but a follow-up existence
     *     check then fails, which means provisioning has not in fact converged
     */
    private static void createBucketIfAbsent(final S3Client s3, final String bucketName) {
        try {
            s3.createBucket(request -> request.bucket(bucketName));
        } catch (final BucketAlreadyOwnedByYouException | BucketAlreadyExistsException alreadyProvisioned) {
            // Two distinct responses that mean the same thing here, so both are caught. The claim is then
            // checked rather than trusted, which is what keeps this from being a swallowed exception.
            try {
                s3.headBucket(request -> request.bucket(bucketName));
            } catch (final RuntimeException notPresent) {
                throw new IllegalStateException(
                        "Bucket '" + bucketName + "' was reported as already existing, yet the follow-up "
                                + "existence check failed, so provisioning has not converged. The "
                                + "already-exists response read: " + alreadyProvisioned.getMessage(),
                        notPresent);
            }
        }
    }

    /**
     * Confirms that an already-existing report queue really is the FIFO queue this tier requires.
     *
     * <p>This reproduces the read-back self-check the container-runtime initialisation script performs for the
     * same resource: existence, then the FIFO attribute. A queue that exists under the right name but without
     * FIFO semantics would let ordering-sensitive assertions pass for the wrong reason, which is worse than a
     * missing queue.
     *
     * @param sqs                the client to query through
     * @param queueName          physical name of the report FIFO queue
     * @param alreadyProvisioned the response that reported the queue already exists, quoted in any failure so
     *     the original service text is not lost
     * @throws IllegalStateException if the queue cannot be resolved, or resolves but is not a FIFO queue
     */
    private static void confirmFifoQueuePresent(final SqsClient sqs, final String queueName,
            final RuntimeException alreadyProvisioned) {
        final String queueUrl;
        try {
            queueUrl = sqs.getQueueUrl(request -> request.queueName(queueName)).queueUrl();
        } catch (final RuntimeException notPresent) {
            throw new IllegalStateException(
                    "Queue '" + queueName + "' was reported as already existing, yet it could not be resolved, "
                            + "so provisioning has not converged. The already-exists response read: "
                            + alreadyProvisioned.getMessage(),
                    notPresent);
        }

        final Map<QueueAttributeName, String> observed = sqs.getQueueAttributes(request -> request
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.FIFO_QUEUE)).attributes();

        if (!"true".equals(observed.get(QueueAttributeName.FIFO_QUEUE))) {
            throw new IllegalStateException(
                    "Queue '" + queueName + "' exists but is not a FIFO queue, so message ordering cannot be "
                            + "relied on and the report bridge would not behave as the transient data queue it "
                            + "replaces. The already-exists response read: "
                            + alreadyProvisioned.getMessage());
        }
    }

    /**
     * The persistence context bound to the test method's transaction.
     *
     * <p>Instance state, never static: it is created fresh for every test instance and discarded with it, so it
     * introduces none of the cross-test coupling the two container fields are the sole documented exception to.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * The launcher every job in this tier is started through.
     *
     * <p>Supplied by Batch auto-configuration. It is deliberately not built by hand: {@code JobBuilderFactory}
     * and {@code StepBuilderFactory} were removed in Spring Batch 5, and a hand-rolled launcher would not share
     * the job repository the framework wired to the same datasource the containers provide.
     */
    @Autowired
    private JobLauncher jobLauncher;

    /**
     * The single fixed clock the whole context shares, injected back rather than reconstructed.
     *
     * <p>Injecting it - rather than building a second identical instance for the test to read - is what makes
     * the assertion meaningful: a subclass comparing a produced timestamp against this clock is comparing
     * against the very object the production bean under test consumed, so the two cannot drift apart.
     */
    @Autowired
    private Clock clock;

    /**
     * Creates the harness for one test instance.
     *
     * <p>Declared explicitly rather than left implicit, and {@code protected} rather than {@code public},
     * because an abstract class's constructor is part of the contract its subclasses inherit and belongs in the
     * documented surface: JUnit instantiates the concrete subclass, whose own constructor reaches this one
     * through an implicit {@code super()} call. Narrowing it from the implicit {@code public} also keeps the
     * type from looking instantiable in its own right, which it is not.
     *
     * <p>The body is empty by design and that is not an omission. Every collaborator this class needs - the
     * persistence context, the job launcher and the fixed clock - arrives by injection <em>after</em>
     * construction, and the two containers are already running by the time any test instance exists, having
     * been started once per JVM by the static initialiser above. There is consequently nothing to initialise
     * here, and doing anything here would introduce per-instance state the tier deliberately does not have.
     */
    protected AbstractBatchIntegrationTest() {
        // Intentionally empty; see the constructor documentation above.
    }

    /**
     * Returns the fixed clock the application context is running on.
     *
     * <p>Purpose: give a subclass the one legitimate source of time in this tier, so that expected values can
     * be derived rather than hardcoded. Inputs: none. Output: the same non-null {@code java.time.Clock}
     * instance every production bean in the context received, fixed to {@link #fixedInstant()} in UTC and
     * therefore returning an identical reading on every call. Side effects: none; the clock is immutable.
     * Error modes: none - if the bean were absent the context refresh would already have failed, long before
     * this method could be reached.
     *
     * @return the shared fixed clock, never {@code null}
     */
    protected final Clock clock() {
        return clock;
    }

    /**
     * Returns the instant {@link #clock()} is pinned to.
     *
     * <p>Purpose: let a subclass build an expected timestamp string, or an expected date filter bound, from the
     * same instant the code under test will stamp - without restating the literal and without risking the two
     * falling out of step. Inputs: none. Output: the pinned instant, read from the shared clock so that the
     * literal exists in exactly one place in this file. Side effects: none. Error modes: none.
     *
     * @return the fixed instant, which lies inside the inclusive 2022-01-01 to 2022-07-06 report window
     *     declared at {@code app/proc/TRANREPT.prc:39-42}, never {@code null}
     */
    protected final Instant fixedInstant() {
        return clock.instant();
    }

    /**
     * Returns the persistence context bound to the current test method's transaction.
     *
     * <p>Purpose: give every subclass one shared, documented way to reach the persistence context instead of
     * each wiring its own - the duplication Clause C of the project's single rule asks to be avoided. Inputs:
     * none. Output: the transaction-scoped {@code jakarta.persistence.EntityManager}. Side effects: none by
     * itself; anything invoked on it participates in the test transaction and is therefore rolled back when the
     * method ends. Error modes: an operation issued outside a transaction fails on the returned object, not
     * here.
     *
     * @return the transactional persistence context, never {@code null}
     */
    protected final EntityManager entityManager() {
        return entityManager;
    }

    /**
     * Flushes pending writes to the database and then detaches every managed entity.
     *
     * <p>Purpose: force the database to see, and to judge, what has been written so far, so that a
     * {@code CHECK} violation, a primary-key collision or an optimistic-lock failure surfaces at a point the
     * test chose rather than at an arbitrary later moment - typically at commit, where the stack trace no
     * longer names the statement that caused it. Clearing afterwards guarantees the next read is served from
     * the database rather than from the first-level cache, which is the only way to prove a value was
     * genuinely persisted with the precision and scale the schema declares.
     *
     * <p>Inputs: none. Output: none. Side effects: SQL is issued inside the test transaction, and all managed
     * instances become detached, so any reference held across this call must be re-read before use. Error
     * modes: the underlying persistence provider throws on a constraint violation - notably the 9-digit
     * customer social-security check, which is one of the five {@code CHECK} constraints and fails with a
     * message that names the constraint rather than the field.
     */
    protected final void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * Launches one job and returns its completed execution.
     *
     * <p>Purpose: give all seven subclasses a single uniform way to start a job, so that none reimplements
     * checked-exception handling and none reports a launch failure as a business failure. Inputs: the job to
     * run and its parameters, both required. Output: the {@code JobExecution} the launcher produced, from which
     * the exit status, the step executions and the read, write, filter and skip counts can be asserted.
     *
     * <p><strong>A launching test method must opt out of the class-level transaction, and this method refuses
     * to run until it does.</strong> Annotate it
     * {@code @Transactional(propagation = Propagation.NOT_SUPPORTED)}. The reason is a hard framework
     * constraint, not a preference: the job repository proxy built by
     * {@code org.springframework.batch.core.repository.support.AbstractJobRepositoryFactoryBean} tests
     * {@code TransactionSynchronizationManager.isActualTransactionActive()} before every repository call and
     * throws {@code Existing transaction detected in JobRepository} when one is, so
     * {@code createJobExecution} fails before the first chunk is read. Even with that guard turned off through
     * {@code setValidateTransactionState(false)} the outcome would still be wrong rather than merely
     * unsupported, because Spring Batch commits each chunk through its own transaction manager: a job launch
     * can never be undone by a test-managed rollback, so pretending otherwise would leave a subclass believing
     * in isolation it does not have. Disabling a framework safety check to obtain a false guarantee is not a
     * remedy, so this harness does not offer one.
     *
     * <p>Side effects, and they matter for isolation. A job launched from this method runs entirely
     * <em>outside</em> the tier's rollback scope: the business rows it writes and the Spring Batch metadata
     * rows it records are committed and survive the test. Three consequences follow, and a subclass must plan
     * for all three. A job reads only <em>committed</em> state, which on this branch means the three Flyway
     * migrations' seed - notably the 300 {@code daily_transaction} rows - and not anything a test persisted
     * inside a transaction that is still open. A subclass must not assert on the absolute number of executions
     * in the job repository, because that count accumulates across the run. And relaunching the same job with
     * the same identifying parameters fails with an instance-already-complete error rather than running again,
     * which is the duplicate-instance exposure the migration plan requires to surface rather than be smoothed
     * over; vary a parameter when a second run is genuinely wanted.
     *
     * <p>Error modes: an active transaction is refused with an {@code IllegalStateException} naming the remedy
     * above, and any of the four checked launch failures - already running, restart refused, instance already
     * complete, or parameters invalid - is wrapped in an {@code IllegalStateException} that names the job and
     * keeps the original as its cause. Parameter <em>values</em> are never placed in either message: they can
     * carry account and card identifiers, and Clause D of the project's single rule forbids that class of value
     * reaching a log or an assertion message.
     *
     * @param job        the job to launch, must not be {@code null}
     * @param parameters the parameters to launch it with, must not be {@code null}; use
     *     {@link #jobParameters(java.util.Map)} to build them
     * @return the resulting job execution, never {@code null}
     * @throws NullPointerException  if {@code job} or {@code parameters} is {@code null}
     * @throws IllegalStateException if a transaction is active on the calling thread, or if the launcher
     *     refused the request, with the framework exception preserved as the cause in the second case
     */
    protected final JobExecution launchJob(final Job job, final JobParameters parameters) {
        Objects.requireNonNull(job, "The job to launch must not be null.");
        Objects.requireNonNull(parameters, "The job parameters must not be null.");

        // Checked here, on the caller's thread, rather than left to surface from inside a JDK proxy several
        // frames away. The condition is character-for-character the one the job repository proxy applies, so a
        // pass here is a guarantee rather than an approximation.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Job '" + job.getName() + "' cannot be launched while a transaction is active on this "
                            + "thread. This class is annotated @Transactional so that ordinary assertions roll "
                            + "back, but Spring Batch refuses job repository work inside an existing "
                            + "transaction and commits each chunk on its own transaction regardless. Annotate "
                            + "the launching test method @Transactional(propagation = Propagation.NOT_SUPPORTED)"
                            + " and treat everything the job writes as committed.");
        }

        try {
            return jobLauncher.run(job, parameters);
        } catch (final JobExecutionException launchRefused) {
            throw new IllegalStateException(
                    "Job '" + job.getName() + "' could not be launched. This is a launch failure, not a "
                            + "business failure: the job never ran, so no exit status and no reject count "
                            + "exists to assert on.",
                    launchRefused);
        }
    }

    /**
     * Builds job parameters from a name-to-value map, in a deterministic order.
     *
     * <p>Purpose: keep parameter construction identical across the tier, and keep it deterministic. Spring
     * Batch derives a job instance from the identifying parameters, so a map whose iteration order varies
     * between runs - which is exactly what a hash-ordered map gives - would produce differently ordered
     * parameter sets and, in turn, run-to-run variation in what counts as the same instance. Sorting by name
     * removes that entirely.
     *
     * <p>All values are added as strings. That is not a simplification: every batch parameter in the source is
     * character data, including the ten-character date the interest calculator receives as a linkage parameter
     * at {@code app/cbl/CBACT04C.cbl:175-180}, which is eight date digits followed by two zeros and must not be
     * reinterpreted as a temporal type.
     *
     * <p>Inputs: a non-null map whose every name is non-null and not blank and whose every value is non-null.
     * Output: an immutable {@code JobParameters}. Side effects: none. Error modes: a null map, a null or blank
     * name, or a null value is rejected immediately with a message that names the offending parameter but never
     * quotes its value.
     *
     * @param parameters the parameters to convert, must not be {@code null}
     * @return the assembled job parameters, never {@code null}
     * @throws NullPointerException     if {@code parameters} is {@code null}
     * @throws IllegalArgumentException if any name is {@code null} or blank, or any value is {@code null}
     */
    protected final JobParameters jobParameters(final Map<String, String> parameters) {
        Objects.requireNonNull(parameters, "The job parameter map must not be null.");

        for (final Map.Entry<String, String> candidate : parameters.entrySet()) {
            final String name = candidate.getKey();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(
                        "A job parameter name must be neither null nor blank; an unnamed parameter cannot "
                                + "contribute to a job instance identity.");
            }
            if (candidate.getValue() == null) {
                throw new IllegalArgumentException(
                        "Job parameter '" + name + "' has a null value. Supply an explicit value, or omit the "
                                + "parameter altogether - a null is not the same request as an absent one.");
            }
        }

        final JobParametersBuilder builder = new JobParametersBuilder();
        for (final Map.Entry<String, String> parameter : new TreeMap<>(parameters).entrySet()) {
            builder.addString(parameter.getKey(), parameter.getValue());
        }
        return builder.toJobParameters();
    }

    /**
     * Reads one fixed-width fixture from the root of the test classpath, byte for byte.
     *
     * <p>Purpose: hand a subclass the fixture records exactly as the legacy dataset holds them, so that a
     * reader, a processor or a writer can be asserted against real geometry rather than against a
     * hand-written approximation. The nine fixtures are flat direct children of the test resource root, with no
     * enclosing folder, and are resolved here by bare name for that reason.
     *
     * <p>What this method deliberately does <em>not</em> do is as important as what it does. It does not trim,
     * strip, normalise, re-encode or otherwise touch a single character. The records are fixed-width and their
     * trailing spaces are payload: truncating them would collapse the account record from 300 columns to 112,
     * the customer record from 500 to 332, the card record from 150 to 91 and the daily transaction record from
     * 350 to 304, and every offset-based assertion in the tier would then fail for a reason that has nothing to
     * do with the code under test. Splitting is on the line-feed terminator alone - the fixtures contain no
     * carriage return anywhere - and only a single empty element produced by the file's final terminator is
     * discarded, so the returned size is the record count.
     *
     * <p>The encoding is stated explicitly as 7-bit ASCII rather than inherited from the platform. All nine
     * fixtures are pure 7-bit ASCII, and a platform-dependent default would make the same file decode
     * differently on a differently configured machine, which Clause C of the project's single rule rules out.
     *
     * <p>Inputs: the fixture's bare file name, for example the daily transaction dataset, which carries 300
     * records of 350 columns. Note that the ASCII fixture spells that name in full even though the mainframe DD
     * name elides a letter; a name that is one letter short resolves to nothing. Output: an immutable list of
     * records in file order, each the exact bytes of one record with no terminator. Side effects: none.
     *
     * <p>Error modes: a name that resolves to no resource fails immediately with a message naming it. That
     * loudness is the whole point - the alternative is a null stream, a confusing failure raised far from its
     * cause, and a misspelling that looks like a defect in the reader. A read failure is wrapped with the
     * resource name and keeps the original as its cause; nothing is swallowed.
     *
     * @param resourceName bare name of the fixture at the classpath root, must not be {@code null} or blank
     * @return the records in file order, trailing spaces intact, never {@code null}
     * @throws NullPointerException     if {@code resourceName} is {@code null}
     * @throws IllegalArgumentException if {@code resourceName} is blank
     * @throws IllegalStateException    if the resource cannot be found, or cannot be read, with the underlying
     *     failure preserved as the cause in the latter case
     */
    protected final List<String> readFixture(final String resourceName) {
        Objects.requireNonNull(resourceName, "The fixture resource name must not be null.");
        if (resourceName.isBlank()) {
            throw new IllegalArgumentException("The fixture resource name must not be blank.");
        }

        final byte[] content;
        try (InputStream fixture =
                AbstractBatchIntegrationTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (fixture == null) {
                throw new IllegalStateException(
                        "Fixture '" + resourceName + "' was not found at the root of the test classpath. The "
                                + "fixtures are flat direct children of the test resource root, so the name "
                                + "must carry no directory segment, and the ASCII files spell each dataset "
                                + "name in full even where the mainframe DD name abbreviates it.");
            }
            content = fixture.readAllBytes();
        } catch (final IOException readFailure) {
            throw new IllegalStateException(
                    "Fixture '" + resourceName + "' was found but could not be read.", readFailure);
        }

        final String[] records = new String(content, StandardCharsets.US_ASCII).split("\n", -1);
        final int recordCount = records.length > 0 && records[records.length - 1].isEmpty()
                ? records.length - 1
                : records.length;
        final List<String> lines = new ArrayList<>(recordCount);
        for (int index = 0; index < recordCount; index++) {
            lines.add(records[index]);
        }
        return List.copyOf(lines);
    }

    /**
     * Publishes the one fixed clock the whole context runs on.
     *
     * <p>This is imported by the enclosing class rather than component-scanned, so that all seven subclasses
     * inherit it without restating it and without any of them being able to forget it. It is a nested type, not
     * a field, so it does not widen this package's two-static-field budget.
     *
     * <p>The bean is marked primary, and is given a name of its own rather than the obvious one, for a single
     * reason: {@code spring.main.allow-bean-definition-overriding} is {@code false} in this application, so a
     * name that collided with a production bean would fail the context refresh outright. Being primary means
     * that if the production tier later publishes a clock of its own, this fixed one still wins here - which is
     * the entire purpose of the class.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockTestConfiguration {

        /**
         * Creates the configuration.
         *
         * <p>Declared explicitly, and empty by design: the single bean below is stateless and there is nothing
         * to initialise. Spring instantiates this type reflectively, so the constructor is package-private
         * rather than public.
         */
        FixedClockTestConfiguration() {
            // Intentionally empty; see the constructor documentation above.
        }

        /**
         * The fixed UTC clock every time-dependent bean in the context receives.
         *
         * <p>The instant is the one carried by all 300 records of the daily transaction fixture in columns
         * 279-304 as their originating timestamp - a single distinct value across the whole file - and it falls
         * inside the inclusive 2022-01-01 to 2022-07-06 window that the report sort and the report processor
         * both filter on. Pinning here rather than in each test is what stops a generated processing timestamp
         * from landing outside that window and emptying the transaction report.
         *
         * <p>The zone is {@code UTC} and is stated rather than inherited, matching the persistence layer's own
         * JDBC time zone, so a machine configured for any other zone renders identical timestamps.
         *
         * @return the fixed clock, never {@code null}
         */
        @Bean
        @Primary
        Clock carddemoFixedTestClock() {
            return Clock.fixed(Instant.parse("2022-06-10T19:27:53Z"), ZoneOffset.UTC);
        }
    }
}

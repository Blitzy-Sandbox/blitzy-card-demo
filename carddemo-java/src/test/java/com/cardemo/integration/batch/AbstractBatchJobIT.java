/*
 * ============================================================================
 *  CardDemo — Greenfield Java 25 LTS + Spring Boot 3.x Migration
 *  Shared Spring Batch Integration-Test Harness (foundational abstract base)
 * ============================================================================
 *
 *  PROVENANCE & TRACEABILITY (AAP §0.7.1 / §0.7.2)
 *  Net-new greenfield test infrastructure with NO COBOL source equivalent. The
 *  behaviour exercised by the concrete subclasses is translated from the frozen
 *  AWS CardDemo COBOL/JCL baseline at commit SHA 27d6c6f; the COBOL/JCL sources
 *  (for example app/jcl/POSTTRAN.jcl and the app/data/ASCII/*.txt fixtures such
 *  as dailytran.txt / acctdata.txt that subclasses stage into S3) are read-only
 *  reference and are NEVER copied into this repository. Base package is
 *  com.cardemo (decision D-006 — deliberately NOT com.carddemo), matching
 *  <groupId>com.cardemo</groupId> in carddemo-java/pom.xml.
 * ============================================================================
 */
package com.cardemo.integration.batch;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.DeleteTopicRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Foundational abstract base class for every Spring Batch <em>job</em> integration test in
 * {@code com.cardemo.integration.batch}.
 *
 * <p>This is the keystone of the batch-layer <strong>parity-validation</strong> suite for the AWS
 * CardDemo COBOL/JCL &rarr; Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x migration. The legacy JCL/JES
 * batch estate (the 5-stage pipeline {@code POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr;
 * CREASTMT/TRANREPT}) is migrated to Spring Batch {@code Job}/{@code Step}/{@code Flow} beans; this
 * harness launches those real jobs against a real PostgreSQL&nbsp;16 database (the VSAM&nbsp;KSDS
 * replacement) and a real LocalStack AWS surface (S3 = GDG generations, SQS&nbsp;FIFO = CICS&nbsp;TDQ
 * {@code WRITEQ}, SNS = CICS notifications), then asserts on the final repository state and the
 * produced S3 objects. Every per-job {@code *IT} subclass
 * ({@code DailyTransactionPostingJobIT}, {@code InterestCalculationJobIT},
 * {@code CombineTransactionsJobIT}, {@code StatementGenerationJobIT}, {@code TransactionReportJobIT},
 * and the pipeline orchestrator IT) <strong>extends</strong> this class and contains only
 * job-specific arrange/act/assert logic. This base class itself defines <strong>no</strong>
 * {@code @Test} methods.</p>
 *
 * <h2>Why this class is {@code abstract} and carries no tests</h2>
 * <p>Being {@code abstract}, JUnit&nbsp;5 never instantiates or runs it, and the
 * {@code maven-failsafe-plugin} (which executes {@code **}{@code /*IT.java} and
 * {@code **}{@code /integration/**} under {@code mvn verify -Pintegration}) skips it because abstract
 * classes are not runnable test classes. It therefore safely centralizes all shared lifecycle and
 * helper machinery without ever executing as a test in its own right.</p>
 *
 * <h2>Why the Testcontainers SINGLETON pattern (NOT {@code @Container} / {@code @Testcontainers})</h2>
 * <p>All six concrete subclasses declare an <em>identical</em> Spring context configuration
 * ({@code @SpringBootTest(webEnvironment = NONE)} + {@code @ActiveProfiles("test")} + the
 * {@code @DynamicPropertySource} below). Spring's {@code TestContext} framework caches and reuses a
 * single {@code ApplicationContext} across test classes <em>only when the resolved configuration —
 * including every registered property — is identical</em>; the datasource URL and the AWS endpoint
 * are part of that configuration, so they must stay <strong>stable for the whole suite</strong>.</p>
 * <p>This is exactly why both containers use the Testcontainers <strong>singleton pattern</strong>:
 * each {@code static} container is started once in a {@code static} initializer and shared by every
 * subclass. The fields are deliberately <strong>not</strong> annotated with {@code @Container} and
 * the class is deliberately <strong>not</strong> driven by the {@code @Testcontainers} JUnit
 * extension. {@code @Container} ties a container's start/stop lifecycle to a single test class, so the
 * extension would stop the container after the first subclass and start a fresh one (with a fresh
 * mapped port and therefore a different URL/endpoint) for the next subclass — invalidating the cached
 * context's datasource/AWS endpoint and forcing a slow context reload (and a fresh Flyway run) for
 * every class. The singletons are intentionally <strong>never explicitly stopped</strong>; the
 * Testcontainers <em>Ryuk</em> sidecar reaps them when the JVM exits. Sharing one started container +
 * one stable URL/endpoint is what lets all six IT classes share <strong>one</strong> cached context
 * and one Flyway migration. This mirrors the established sibling convention
 * {@code com.cardemo.integration.repository.AbstractRepositoryIT} and realizes the documented goal of
 * "one container for the whole batch IT suite, fast &amp; hermetic" (AAP §0.7.7 — LocalStack
 * verification; tests create and destroy their own resources).</p>
 *
 * <h2>Testcontainers 2.x note (warning-free build, AAP §0.7.8)</h2>
 * <p>This module pins {@code testcontainers-bom:2.0.3}. In Testcontainers&nbsp;2.x the legacy
 * {@code org.testcontainers.containers.PostgreSQLContainer} and
 * {@code org.testcontainers.containers.localstack.LocalStackContainer} (with its {@code Service} enum)
 * are {@code @Deprecated}; this class therefore uses the current, non-deprecated
 * {@link org.testcontainers.postgresql.PostgreSQLContainer} and
 * {@link org.testcontainers.localstack.LocalStackContainer} (whose {@code withServices(String...)}
 * accepts service names as strings), keeping the build free of deprecation warnings.</p>
 *
 * <h2>Why NOT {@code @SpringBatchTest}</h2>
 * <p>This class is deliberately <strong>not</strong> annotated {@code @SpringBatchTest}. That
 * annotation registers a {@code JobLauncherTestUtils} bean (via {@code BatchTestContextCustomizer})
 * whose {@code setJob(...)} is autowired, and the auto-wire only succeeds when the context holds a
 * <strong>single</strong> {@code Job} bean. The CardDemo context defines <strong>six</strong>
 * {@code Job} beans ({@code dailyTransactionPostingJob}, {@code interestCalculationJob},
 * {@code combineTransactionsJob}, {@code statementGenerationJob}, {@code transactionReportJob},
 * {@code cardDemoBatchPipelineJob}), so {@code @SpringBatchTest} would fail context load with
 * {@code NoUniqueBeanDefinitionException: ... expected single matching bean but found 6}. Instead,
 * {@link #launchJob(Job, JobParameters)} constructs a {@code JobLauncherTestUtils} <em>manually</em>
 * per call and sets the explicit {@code Job}, which is ambiguity-free and lets a subclass autowire
 * its job by bean name (e.g. {@code @Autowired private Job dailyTransactionPostingJob;}). This is a
 * documented technology-specific decision (Minimal Change Clause, AAP §0.7.1).</p>
 *
 * <h2>Context bootstrap</h2>
 * <p>{@code @SpringBootTest(webEnvironment = NONE)} loads the full application context rooted at
 * {@code com.cardemo.CardDemoApplication} (located by default detection — this test lives under the
 * {@code com.cardemo} base package, so no explicit {@code classes=} is needed) without a servlet
 * container, which batch/repository tests do not require. {@code @ActiveProfiles("test")} activates
 * {@code application-test.yml}, under which Flyway provisions the production schema
 * ({@code V1__create_schema} &rarr; {@code V2__create_indexes} &rarr; {@code V3__seed_data}) and seeds
 * the 9 canonical ASCII fixtures inside the throwaway PostgreSQL container, while
 * {@code spring.jpa.hibernate.ddl-auto=validate} keeps Hibernate from creating the schema.</p>
 *
 * @see org.testcontainers.postgresql.PostgreSQLContainer
 * @see org.testcontainers.localstack.LocalStackContainer
 * @see DynamicPropertySource
 * @see JobLauncherTestUtils
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
public abstract class AbstractBatchJobIT {

    /** Logger used only for non-fatal AWS teardown diagnostics (see {@link #teardownAws()}). */
    private static final Logger LOG = LoggerFactory.getLogger(AbstractBatchJobIT.class);

    /**
     * Pinned LocalStack image tag for reproducible integration runs. A fixed major tag is preferred
     * over {@code :latest} so the AWS emulator surface is deterministic across machines and CI.
     */
    private static final String LOCALSTACK_IMAGE = "localstack/localstack:3";

    // -------------------------------------------------------------------------
    // Shared, singleton containers (started once for the whole batch IT suite).
    // -------------------------------------------------------------------------

    /**
     * Shared, singleton PostgreSQL&nbsp;16 container backing every batch integration test — the
     * relational replacement for the legacy z/OS VSAM&nbsp;KSDS data layer.
     *
     * <p>Started once in the {@code static} initializer and never explicitly stopped (reaped by the
     * Testcontainers <em>Ryuk</em> sidecar at JVM exit), so its mapped port — and hence the JDBC URL
     * registered by {@link #registerProperties(DynamicPropertyRegistry)} — stays stable for the whole
     * suite, allowing all subclasses to share one cached Spring context. The database name
     * {@code carddemo} mirrors production / {@code docker-compose}. The single
     * {@code @SuppressWarnings("resource")} is deliberate: the container is an {@code AutoCloseable}
     * intentionally left open (closing it would defeat the singleton-sharing strategy).</p>
     */
    @SuppressWarnings("resource") // Singleton container intentionally never closed; reaped by Ryuk at JVM exit.
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("carddemo")
                    .withUsername("carddemo")
                    .withPassword("carddemo");

    /**
     * Shared, singleton LocalStack container providing the S3, SQS, and SNS surfaces — the cloud
     * replacement for GDG generations (S3), the CICS TDQ {@code WRITEQ} online&rarr;batch bridge
     * (SQS&nbsp;FIFO), and CICS notification messaging (SNS). Started once alongside {@link #POSTGRES};
     * the same singleton rationale and {@code @SuppressWarnings("resource")} justification apply.
     */
    @SuppressWarnings("resource") // Singleton container intentionally never closed; reaped by Ryuk at JVM exit.
    protected static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE))
                    .withServices("s3", "sqs", "sns");

    // -------------------------------------------------------------------------
    // AWS SDK v2 clients pointed at LocalStack (built once, shared by helpers).
    // These are SEPARATE from the production Spring Cloud AWS auto-configured
    // beans; they exist purely so the harness can provision resources and make
    // out-of-band content assertions against S3/SQS/SNS.
    // -------------------------------------------------------------------------

    /** S3 client targeting LocalStack (path-style), used for provisioning and object assertions. */
    @SuppressWarnings("resource") // Shared static client intentionally not closed; released at JVM exit.
    protected static final S3Client S3;

    /** SQS client targeting LocalStack, used for FIFO queue provisioning/teardown. */
    @SuppressWarnings("resource") // Shared static client intentionally not closed; released at JVM exit.
    protected static final SqsClient SQS;

    /** SNS client targeting LocalStack, used for topic provisioning/teardown. */
    @SuppressWarnings("resource") // Shared static client intentionally not closed; released at JVM exit.
    protected static final SnsClient SNS;

    static {
        // Start the singletons FIRST so the AWS client builders below can read the (now-mapped)
        // LocalStack endpoint/region/credentials. start() blocks until each container is ready.
        POSTGRES.start();
        LOCALSTACK.start();

        S3 = S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                // Path-style is REQUIRED for LocalStack S3 (http://host:port/bucket/key); virtual-host
                // style (http://bucket.host/...) does not resolve against the emulator.
                .forcePathStyle(true)
                .build();

        SQS = SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();

        SNS = SnsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    // -------------------------------------------------------------------------
    // AWS resource names — MUST match application-test.yml (carddemo.aws.*) and
    // com.cardemo.config.AwsConfig.AwsResourceProperties EXACTLY, so the
    // production beans and these tests resolve the same buckets/queue/topic.
    // Subclasses reference these constants when staging input / asserting output.
    // -------------------------------------------------------------------------

    /** Batch staging INPUT bucket (replaces sequential PS datasets / GDG input generations). */
    protected static final String BATCH_INPUT_BUCKET = "carddemo-batch-input";

    /** Batch staging OUTPUT bucket (replaces GDG output generations, e.g. combined/SYSTRAN). */
    protected static final String BATCH_OUTPUT_BUCKET = "carddemo-batch-output";

    /** Generated-statement bucket (CBSTM03A/CBSTM03B &rarr; StatementGenerationJob text + HTML). */
    protected static final String STATEMENTS_BUCKET = "carddemo-statements";

    /**
     * Report-job FIFO queue (CICS TDQ {@code WRITEQ 'JOBS'} &rarr; SQS FIFO trigger). The
     * {@code .fifo} suffix is mandatory for FIFO queues.
     */
    protected static final String REPORT_JOBS_QUEUE = "carddemo-report-jobs.fifo";

    /** Notifications topic (CICS notification messaging &rarr; SNS fan-out). */
    protected static final String NOTIFICATIONS_TOPIC = "carddemo-notifications";

    /** Resolved URL of {@link #REPORT_JOBS_QUEUE}, captured at provisioning for idempotent teardown. */
    private static String reportQueueUrl;

    /** Resolved ARN of {@link #NOTIFICATIONS_TOPIC}, captured at provisioning for idempotent teardown. */
    private static String notificationsTopicArn;

    // -------------------------------------------------------------------------
    // Phase 3 — dynamic property wiring (highest precedence; overrides the yml).
    // -------------------------------------------------------------------------

    /**
     * Registers the singleton containers' coordinates as Spring properties, overriding the
     * {@code application-test.yml} defaults so JPA/Flyway target the Testcontainer PostgreSQL and the
     * Spring Cloud AWS clients target LocalStack.
     *
     * <p>Suppliers are passed by lambda / method reference so each value is resolved lazily, after the
     * containers have started. All four datasource properties are registered — including
     * {@code driver-class-name} — because the {@code test} profile may select the
     * {@code jdbc:tc:postgresql:...} URL scheme paired with
     * {@code org.testcontainers.jdbc.ContainerDatabaseDriver}; since this registrar overrides the URL
     * to a plain {@code jdbc:postgresql://...} value, the driver must also be overridden to
     * {@code org.postgresql.Driver} to avoid a URL/driver mismatch.</p>
     *
     * <p><strong>{@code spring.batch.job.enabled=false}</strong> is set explicitly (belt-and-suspenders;
     * it is also set in {@code application.yml}). In Spring&nbsp;Boot&nbsp;3.5.x the
     * {@code JobLauncherApplicationRunner} is gated by
     * {@code @ConditionalOnBooleanProperty(name = "spring.batch.job.enabled", matchIfMissing = true)};
     * forcing it {@code false} guarantees no {@code Job} bean auto-executes on context startup, so each
     * test controls launch timing via {@link #launchJob(Job, JobParameters)}.</p>
     *
     * <p>The AWS endpoint/region/credentials are pointed at LocalStack (global + per-service overrides
     * for safety). The bucket/queue/topic <em>names</em> are deliberately NOT overridden here: the
     * {@code application-test.yml} defaults already equal {@link #BATCH_INPUT_BUCKET} etc., and this
     * harness creates exactly those resources in {@link #provisionAws()} — keeping the production beans
     * and the tests in lock-step.</p>
     *
     * @param registry the Spring-provided registry into which properties are added; never {@code null}.
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // Datasource -> Testcontainer PostgreSQL (override the yml jdbc:tc: defaults).
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // Disable batch auto-run so tests control job launch timing (see method Javadoc).
        registry.add("spring.batch.job.enabled", () -> "false");

        // Spring Cloud AWS -> LocalStack (global endpoint + region + credentials).
        registry.add("spring.cloud.aws.region.static", LOCALSTACK::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", LOCALSTACK::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", LOCALSTACK::getSecretKey);
        registry.add("spring.cloud.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());

        // Per-service endpoint overrides (defensive; ensure every client targets the emulator).
        registry.add("spring.cloud.aws.s3.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.sqs.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.sns.endpoint", () -> LOCALSTACK.getEndpoint().toString());
    }

    // -------------------------------------------------------------------------
    // Phase 4 — hermetic AWS resource provisioning / teardown (AAP §0.7.7:
    // tests create AND destroy their own resources; zero live dependencies).
    // -------------------------------------------------------------------------

    /**
     * Provisions the S3 buckets, the SQS FIFO queue, and the SNS topic inside the LocalStack
     * container before any test in a subclass runs.
     *
     * <p>Provisioning is idempotent: bucket creation ignores
     * {@link BucketAlreadyOwnedByYouException}/{@link BucketAlreadyExistsException} (a prior subclass
     * may have created them), and the FIFO queue is created with the mandatory {@code FifoQueue=true}
     * plus {@code ContentBasedDeduplication=true} attributes. The resolved queue URL and topic ARN are
     * captured into {@link #reportQueueUrl} / {@link #notificationsTopicArn} for {@link #teardownAws()}.</p>
     */
    @BeforeAll
    static void provisionAws() {
        createBucketIfAbsent(BATCH_INPUT_BUCKET);
        createBucketIfAbsent(BATCH_OUTPUT_BUCKET);
        createBucketIfAbsent(STATEMENTS_BUCKET);

        // FIFO queue: name MUST end with ".fifo"; FifoQueue + ContentBasedDeduplication are required.
        reportQueueUrl = SQS.createQueue(CreateQueueRequest.builder()
                .queueName(REPORT_JOBS_QUEUE)
                .attributes(Map.of(
                        QueueAttributeName.FIFO_QUEUE, "true",
                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true"))
                .build()).queueUrl();

        notificationsTopicArn = SNS.createTopic(CreateTopicRequest.builder()
                .name(NOTIFICATIONS_TOPIC)
                .build()).topicArn();
    }

    /**
     * Destroys every AWS resource created by {@link #provisionAws()} after all tests in a subclass
     * complete: each bucket is emptied then deleted, the queue is deleted, and the topic is deleted.
     *
     * <p>Every step is wrapped in its own {@code try/catch} so a teardown failure can never mask a test
     * result (only a warning is logged). The containers themselves are reaped by the Testcontainers
     * <em>Ryuk</em> sidecar at JVM exit, not here.</p>
     */
    @AfterAll
    static void teardownAws() {
        for (String bucket : new String[] {BATCH_INPUT_BUCKET, BATCH_OUTPUT_BUCKET, STATEMENTS_BUCKET}) {
            try {
                clearBucketObjects(bucket);
                S3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
            } catch (RuntimeException e) {
                LOG.warn("AWS teardown: could not delete S3 bucket '{}' (ignored so it cannot mask a "
                        + "test result)", bucket, e);
            }
        }
        try {
            if (reportQueueUrl != null) {
                SQS.deleteQueue(DeleteQueueRequest.builder().queueUrl(reportQueueUrl).build());
            }
        } catch (RuntimeException e) {
            LOG.warn("AWS teardown: could not delete SQS queue '{}' (ignored)", REPORT_JOBS_QUEUE, e);
        }
        try {
            if (notificationsTopicArn != null) {
                SNS.deleteTopic(DeleteTopicRequest.builder().topicArn(notificationsTopicArn).build());
            }
        } catch (RuntimeException e) {
            LOG.warn("AWS teardown: could not delete SNS topic '{}' (ignored)", NOTIFICATIONS_TOPIC, e);
        }
    }

    /**
     * Returns the shared S3 client targeting LocalStack, for subclasses that prefer an accessor over
     * the {@link #S3} field (e.g. when staging input objects or asserting produced output).
     *
     * @return the shared, path-style {@link S3Client}; never {@code null}.
     */
    protected static S3Client s3Client() {
        return S3;
    }

    /**
     * Returns the shared SQS client targeting LocalStack.
     *
     * @return the shared {@link SqsClient}; never {@code null}.
     */
    protected static SqsClient sqsClient() {
        return SQS;
    }

    /**
     * Returns the shared SNS client targeting LocalStack.
     *
     * @return the shared {@link SnsClient}; never {@code null}.
     */
    protected static SnsClient snsClient() {
        return SNS;
    }

    /**
     * Creates an S3 bucket, tolerating the case where it already exists (idempotent provisioning).
     *
     * @param bucket the bucket name to create; must not be {@code null}.
     */
    private static void createBucketIfAbsent(String bucket) {
        try {
            S3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException alreadyExists) {
            // A prior subclass already provisioned this bucket; safe to ignore (idempotent).
            LOG.debug("S3 bucket '{}' already exists; reusing it.", bucket);
        }
    }

    /**
     * Deletes every object in a bucket, paginating through all results so buckets with more than one
     * page of keys are fully emptied before deletion.
     *
     * @param bucket the bucket to empty; must not be {@code null}.
     */
    private static void clearBucketObjects(String bucket) {
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder().bucket(bucket);
            if (continuationToken != null) {
                request.continuationToken(continuationToken);
            }
            ListObjectsV2Response response = S3.listObjectsV2(request.build());
            for (S3Object object : response.contents()) {
                S3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(object.key()).build());
            }
            continuationToken = Boolean.TRUE.equals(response.isTruncated())
                    ? response.nextContinuationToken()
                    : null;
        } while (continuationToken != null);
    }

    // -------------------------------------------------------------------------
    // Phase 5 — Spring Batch launch & assertion helpers (the heart of the harness).
    // Injected from the auto-configured context; field injection is acceptable in tests.
    // -------------------------------------------------------------------------

    /** Auto-configured synchronous {@code JobLauncher} used to launch jobs under test. */
    @Autowired
    protected JobLauncher jobLauncher;

    /** Auto-configured persistent (JDBC) {@code JobRepository} backing batch metadata. */
    @Autowired
    protected JobRepository jobRepository;

    /**
     * Application {@code DataSource} (Testcontainer PostgreSQL). Exposed for subclasses that need raw
     * JDBC to reset business tables between batch runs (so each test sees deterministic seed state),
     * complementing the JPA-level {@link #flushAndClear()} helper.
     */
    @Autowired
    protected DataSource dataSource;

    /**
     * JPA entity manager for subclasses that assert on post-run entity state or exercise {@code @Version}
     * optimistic-locking semantics. Use {@link #flushAndClear()} within an active transaction to force a
     * real database round-trip.
     */
    @PersistenceContext
    protected EntityManager em;

    /**
     * Clears Spring Batch execution metadata before each test so every test starts from clean batch
     * state and a re-launch cannot collide with a prior {@code JobInstance}.
     */
    @BeforeEach
    protected void clearBatchMetadataBeforeEachTest() {
        clearJobRepository();
    }

    /**
     * Launches a fully-wired {@code Job} with the given parameters and returns its terminal
     * {@link JobExecution}.
     *
     * <p>A {@link JobLauncherTestUtils} is constructed <em>manually</em> per call (rather than autowired
     * via {@code @SpringBatchTest}) so the harness works with the six-{@code Job} context without a
     * {@code NoUniqueBeanDefinitionException} — see the class-level Javadoc. Subclasses autowire the
     * specific job by bean name and pass it here, typically with {@link #uniqueParams()}:</p>
     * <pre>{@code
     * @Autowired private Job dailyTransactionPostingJob;
     * ...
     * JobExecution exec = launchJob(dailyTransactionPostingJob, uniqueParams());
     * assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);
     * }</pre>
     *
     * @param job    the {@code Job} bean to run; must not be {@code null}.
     * @param params the parameters for this launch (use {@link #uniqueParams()} for a unique instance).
     * @return the completed {@link JobExecution} (inspect {@code getStatus()} / {@code getExitStatus()}).
     * @throws Exception if the launch fails (mirrors {@link JobLauncherTestUtils#launchJob(JobParameters)}).
     */
    protected JobExecution launchJob(Job job, JobParameters params) throws Exception {
        JobLauncherTestUtils utils = new JobLauncherTestUtils();
        utils.setJob(job);
        utils.setJobLauncher(jobLauncher);
        utils.setJobRepository(jobRepository);
        return utils.launchJob(params);
    }

    /**
     * Builds a {@link JobParameters} guaranteed to produce a unique {@code JobInstance} per launch,
     * avoiding {@code JobInstanceAlreadyCompleteException} on re-runs.
     *
     * @return parameters carrying a {@code run.id} (nanotime) and {@code requestedAt} (ISO instant).
     */
    protected JobParameters uniqueParams() {
        return uniqueParams(null);
    }

    /**
     * Builds a {@link JobParameters} with caller-supplied parameters plus the uniqueness keys.
     *
     * <p>The customizer runs first so a subclass can add job-specific parameters (for example
     * {@code parmDate}, {@code startDate}, {@code endDate}); the uniqueness keys ({@code run.id},
     * {@code requestedAt}) are appended afterwards so a unique {@code JobInstance} is always produced.</p>
     *
     * @param customizer optional callback to add job-specific parameters; may be {@code null}.
     * @return the assembled, always-unique {@link JobParameters}.
     */
    protected JobParameters uniqueParams(Consumer<JobParametersBuilder> customizer) {
        JobParametersBuilder builder = new JobParametersBuilder();
        if (customizer != null) {
            customizer.accept(builder);
        }
        builder.addLong("run.id", System.nanoTime());
        builder.addString("requestedAt", Instant.now().toString());
        return builder.toJobParameters();
    }

    /**
     * Removes all Spring Batch execution metadata via {@link JobRepositoryTestUtils}.
     *
     * <p>In {@code spring-batch-test} 5.2.x the utility is constructed from the {@code JobRepository}
     * alone ({@code new JobRepositoryTestUtils(jobRepository)}); no {@code DataSource} argument is
     * required. This assumes the {@code BATCH_*} metadata tables exist — they are Flyway-provisioned
     * (see {@code com.cardemo.config.BatchConfig}).</p>
     */
    protected void clearJobRepository() {
        JobRepositoryTestUtils utils = new JobRepositoryTestUtils(jobRepository);
        utils.removeJobExecutions();
    }

    // -------------------------------------------------------------------------
    // S3 assertion / staging helpers reused by subclasses.
    // -------------------------------------------------------------------------

    /**
     * Lists every object key in a bucket.
     *
     * @param bucket the bucket to list; must not be {@code null}.
     * @return the object keys (possibly empty, never {@code null}).
     */
    protected List<String> listKeys(String bucket) {
        return listKeys(bucket, null);
    }

    /**
     * Lists object keys in a bucket filtered by an optional key prefix, paginating through all results.
     *
     * @param bucket the bucket to list; must not be {@code null}.
     * @param prefix the key prefix to filter by, or {@code null} for no filter.
     * @return the matching object keys (possibly empty, never {@code null}).
     */
    protected List<String> listKeys(String bucket, String prefix) {
        List<String> keys = new ArrayList<>();
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder().bucket(bucket);
            if (prefix != null) {
                request.prefix(prefix);
            }
            if (continuationToken != null) {
                request.continuationToken(continuationToken);
            }
            ListObjectsV2Response response = S3.listObjectsV2(request.build());
            for (S3Object object : response.contents()) {
                keys.add(object.key());
            }
            continuationToken = Boolean.TRUE.equals(response.isTruncated())
                    ? response.nextContinuationToken()
                    : null;
        } while (continuationToken != null);
        return keys;
    }

    /**
     * Counts the objects in a bucket.
     *
     * @param bucket the bucket to count; must not be {@code null}.
     * @return the number of objects in the bucket.
     */
    protected long countObjects(String bucket) {
        return listKeys(bucket).size();
    }

    /**
     * Counts the objects in a bucket whose keys match a prefix.
     *
     * @param bucket the bucket to count; must not be {@code null}.
     * @param prefix the key prefix to filter by, or {@code null} for no filter.
     * @return the number of matching objects.
     */
    protected long countObjects(String bucket, String prefix) {
        return listKeys(bucket, prefix).size();
    }

    /**
     * Fetches an S3 object's content as a UTF-8 string — for content/parity assertions on reject files,
     * generated statements, and the 133-character transaction report.
     *
     * @param bucket the bucket containing the object; must not be {@code null}.
     * @param key    the object key; must not be {@code null}.
     * @return the object content decoded as UTF-8.
     */
    protected String getObjectAsString(String bucket, String key) {
        return S3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .asUtf8String();
    }

    /**
     * Stages a text object into S3 (for example a fixed-width DALYTRAN input file).
     *
     * @param bucket the destination bucket; must not be {@code null}.
     * @param key    the destination key; must not be {@code null}.
     * @param body   the object content; must not be {@code null}.
     */
    protected void putObject(String bucket, String key, String body) {
        S3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromString(body));
    }

    /**
     * Stages a binary object into S3 (for byte-exact fixed-width / EBCDIC-equivalent input staging).
     *
     * @param bucket the destination bucket; must not be {@code null}.
     * @param key    the destination key; must not be {@code null}.
     * @param body   the object content; must not be {@code null}.
     */
    protected void putObject(String bucket, String key, byte[] body) {
        S3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(body));
    }

    /**
     * Empties a bucket (deletes all objects) without deleting the bucket itself — for tests that need a
     * clean bucket before a run.
     *
     * @param bucket the bucket to empty; must not be {@code null}.
     */
    protected void emptyBucket(String bucket) {
        clearBucketObjects(bucket);
    }

    /**
     * Flushes pending JPA changes to the database and clears the persistence context, so a subsequent
     * {@code findById(...)} re-reads from the database rather than the first-level cache. Must be called
     * within an active transaction.
     */
    protected void flushAndClear() {
        em.flush();
        em.clear();
    }
}

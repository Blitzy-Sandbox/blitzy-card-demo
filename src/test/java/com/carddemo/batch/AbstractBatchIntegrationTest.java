package com.carddemo.batch;

import java.util.Map;
import java.util.concurrent.CompletionException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

/**
 * Shared Testcontainers + LocalStack base class for the CardDemo batch <strong>integration
 * tests</strong> ({@code *IT}, executed by the Maven Failsafe plugin). It is <em>not itself a test</em>:
 * it is {@code abstract} and declares no {@code @Test} method, so neither Surefire nor Failsafe ever
 * executes it directly &mdash; JUnit&nbsp;5 does not instantiate abstract classes and there is nothing
 * runnable to collect. Concrete {@code *IT} subclasses inherit the fully wired Spring context, the
 * live infrastructure, and the AWS self-provisioning helpers defined here.
 *
 * <h2>What this base provides</h2>
 * <ul>
 *   <li>A full {@link SpringBootTest} application context (bootstrapped from
 *       {@code com.carddemo.CardDemoApplication}, discovered by package search) started with
 *       {@link SpringBootTest.WebEnvironment#NONE} &mdash; the batch and SQS tiers need the context,
 *       not an embedded web server &mdash; under the {@code test} Spring profile
 *       ({@link ActiveProfiles @ActiveProfiles("test")}).</li>
 *   <li>A real <strong>PostgreSQL&nbsp;16</strong> instance ({@link #POSTGRES}) against which Flyway
 *       applies the production migrations {@code V1__schema.sql} &rarr; {@code V2__indexes.sql} &rarr;
 *       {@code V3__seed_data.sql} on context start, so parity assertions (AAP&nbsp;Gate&nbsp;1/4) run
 *       against the same schema and seed data production uses. Hibernate
 *       {@code ddl-auto=validate} (inherited from {@code application-test.yml}) then validates the
 *       {@code com.carddemo.entity.*} mappings against that schema.</li>
 *   <li>A real <strong>LocalStack</strong> instance ({@link #LOCALSTACK}) exposing S3, SQS and SNS, so
 *       every AWS interaction is exercised locally with <strong>zero live AWS</strong> and dummy
 *       {@code test}/{@code test} credentials (AAP&nbsp;&sect;0.7.7).</li>
 *   <li>Constructor-free, protected AWS SDK v2 clients ({@link #s3Client}, {@link #sqsAsyncClient})
 *       and a family of {@code protected} helpers that let each subclass self-provision and tear down
 *       exactly the S3 buckets and SQS FIFO queue it needs.</li>
 * </ul>
 *
 * <h2>Singleton-container pattern (shared across every {@code *IT})</h2>
 * <p>Both containers are {@code static}, are started once inside a {@code static} initializer, and are
 * deliberately <strong>not</strong> annotated with {@code @Container}. That is the Testcontainers
 * <em>singleton-container</em> pattern: the JUnit&nbsp;5 {@link Testcontainers @Testcontainers}
 * extension therefore performs no per-class start/stop, so the same PostgreSQL and LocalStack
 * instances are reused across all subclass {@code *IT}s in a single {@code mvn verify} run instead of
 * being recreated per class. This keeps the integration suite fast when several {@code *IT} classes
 * extend this base. The containers are reaped automatically by the Testcontainers Ryuk sidecar when
 * the JVM exits &mdash; there is intentionally no {@code stop()} call.</p>
 *
 * <h2>Runtime wiring &mdash; only the volatile, container-derived values</h2>
 * <p>{@code application-test.yml} owns every static, environment-agnostic setting (Flyway location,
 * {@code ddl-auto=validate}, {@code spring.batch.jdbc.initialize-schema=always},
 * {@code spring.batch.job.enabled=false}, S3 path-style access, region, and the dummy credentials).
 * This base only contributes the values that cannot be known until the containers have bound their
 * random host ports &mdash; the JDBC URL/username/password and the LocalStack endpoint &mdash; through
 * {@link #registerDynamicProperties(DynamicPropertyRegistry)}. It intentionally does <strong>not</strong>
 * set {@code spring.cloud.aws.sqs.listener.auto-startup}; that is left to the profile (and any
 * subclass, such as a report-launcher {@code IT}, that needs the listener started may override it via
 * its own {@code @TestPropertySource} and pre-create the queue).</p>
 *
 * <h2>Self-provisioning contract (AAP&nbsp;&sect;0.7.7) &mdash; MUST be honored by every subclass</h2>
 * <p>This base <strong>never</strong> pre-creates any S3 bucket or SQS queue, so no integration test
 * may depend on pre-existing LocalStack state. Each subclass MUST, in its own {@code @BeforeEach} /
 * {@code @BeforeAll}, create <em>only</em> the buckets and queues it exercises (using
 * {@link #createBucket(String)} / {@link #createFifoQueue(String)}), and MUST, in its own
 * {@code @AfterEach} / {@code @AfterAll}, tear them down (using {@link #deleteBucketRecursively(String)}
 * / {@link #deleteQueue(String)}). The teardown helpers are idempotent (a missing bucket/queue is
 * treated as already-gone), so cleanup is safe even if setup failed part-way. Centralizing these
 * helpers here keeps every {@code *IT} compliant with the zero-ambient-state requirement and DRY.</p>
 *
 * <p>Because the application defines several Spring Batch jobs, this base deliberately does not bind a
 * {@code JobLauncherTestUtils} to any specific job. A subclass that launches one job defines its own
 * {@code JobLauncherTestUtils} {@code @Bean} (typically in a nested {@code @TestConfiguration}) wired
 * to that job, then autowires it to launch the job explicitly &mdash; jobs never auto-run on context
 * start because {@code spring.batch.job.enabled=false}.</p>
 *
 * <p>Source COBOL/JCL is referenced read-only at commit SHA {@code 27d6c6f}; it is not copied here.
 * Design rationale lives in {@code docs/decision-log.md}, not in these comments (Explainability rule).</p>
 *
 * @see PostgreSQLContainer
 * @see LocalStackContainer
 * @see DynamicPropertySource
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractBatchIntegrationTest {

    // -----------------------------------------------------------------------------------------------
    // Logical AWS resource names — mirror the carddemo.aws.* keys in application.yml so a subclass
    // provisions exactly the bucket/queue the wired application resolves at runtime. (The statements
    // bucket is "carddemo-statements" per application.yml, not "carddemo-batch-statements".)
    // -----------------------------------------------------------------------------------------------

    /** Batch <em>input</em> bucket (legacy daily-transaction dataset staging); {@code carddemo.aws.s3.input-bucket}. */
    protected static final String BUCKET_INPUT = "carddemo-batch-input";

    /** Batch <em>output</em> bucket (rejected transactions / reports); {@code carddemo.aws.s3.output-bucket}. */
    protected static final String BUCKET_OUTPUT = "carddemo-batch-output";

    /** Generated-statements bucket (CREASTMT / CBSTM03A HTML &amp; PS); {@code carddemo.aws.s3.statements-bucket}. */
    protected static final String BUCKET_STATEMENTS = "carddemo-statements";

    /** Report-request FIFO queue (migrated CORPT00C TDQ&nbsp;&rarr;&nbsp;JES bridge); {@code carddemo.aws.sqs.report-queue}. */
    protected static final String QUEUE_REPORT_FIFO = "carddemo-report-jobs.fifo";

    // -----------------------------------------------------------------------------------------------
    // Container images and AWS test constants.
    // -----------------------------------------------------------------------------------------------

    /**
     * PostgreSQL image tag. Pinned to {@code postgres:16} to match {@code docker-compose.yml} and the
     * image pre-pulled by the environment setup, guaranteeing the integration suite starts offline and
     * consistently (the {@code -alpine} variant is not cached here).
     */
    private static final String POSTGRES_IMAGE = "postgres:16";

    /** LocalStack community image; pinned repo-wide to {@code localstack/localstack:4} (S3/SQS/SNS verified). */
    private static final String LOCALSTACK_IMAGE = "localstack/localstack:4";

    /** Deterministic fixed database name for the throwaway PostgreSQL container. */
    private static final String DB_NAME = "carddemo";

    /** Deterministic fixed database user for the throwaway PostgreSQL container. */
    private static final String DB_USERNAME = "carddemo";

    /** Deterministic fixed database password for the throwaway PostgreSQL container (non-secret, test-only). */
    private static final String DB_PASSWORD = "carddemo";

    /** AWS region advertised to the SDK; LocalStack accepts any value, {@code us-east-1} is the convention. */
    private static final String AWS_REGION = "us-east-1";

    /** Well-known LocalStack dummy access key (NOT a real credential); forces a static, offline provider. */
    private static final String AWS_ACCESS_KEY = "test";

    /** Well-known LocalStack dummy secret key (NOT a real credential); forces a static, offline provider. */
    private static final String AWS_SECRET_KEY = "test";

    // -----------------------------------------------------------------------------------------------
    // Shared singleton containers — started once, reused across every subclass IT (see class Javadoc).
    // -----------------------------------------------------------------------------------------------

    /**
     * Shared PostgreSQL&nbsp;16 container. Uses the Testcontainers&nbsp;2.0 module class
     * {@link org.testcontainers.postgresql.PostgreSQLContainer}, which is a concrete, self-typed
     * (non-generic) container &mdash; so it is referenced without type arguments and never as a raw
     * generic type. The legacy {@code org.testcontainers.containers.PostgreSQLContainer} shim is
     * {@code @Deprecated} in 2.0.x, so this class is used deliberately to stay warning-free under
     * {@code -Xlint:all} (Gate&nbsp;2).
     */
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName(DB_NAME)
            .withUsername(DB_USERNAME)
            .withPassword(DB_PASSWORD);

    /**
     * Shared LocalStack container exposing S3, SQS and SNS. Uses the Testcontainers&nbsp;2.0 module
     * class {@link org.testcontainers.localstack.LocalStackContainer} (the legacy
     * {@code org.testcontainers.containers.localstack.LocalStackContainer} shim is {@code @Deprecated}
     * in 2.0.x). Services are enabled with the string {@code withServices(String...)} overload &mdash;
     * the modern, warning-free form under {@code -Xlint:all} (Gate&nbsp;2).
     */
    protected static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE))
                    .withServices("s3", "sqs", "sns");

    static {
        // Singleton-container pattern: start once here (no @Container), share across all subclass ITs;
        // Ryuk reaps both containers at JVM exit, so no explicit stop() is required.
        POSTGRES.start();
        LOCALSTACK.start();
    }

    // -----------------------------------------------------------------------------------------------
    // Dynamic property wiring — only the values that depend on the containers' random host ports.
    // -----------------------------------------------------------------------------------------------

    /**
     * Registers the container-derived Spring properties that cannot be hard-coded in
     * {@code application-test.yml} (the JDBC coordinates and the LocalStack endpoint), plus the region,
     * dummy credentials, and S3 path-style flag restated here so a full-context integration test is
     * fully self-describing. The global {@code spring.cloud.aws.endpoint} and the per-service S3/SQS/SNS
     * endpoint overrides are all pointed at LocalStack so no client can reach live AWS.
     *
     * @param registry the Spring test registry that receives the lazily-evaluated property suppliers;
     *                 each supplier is invoked after the containers have started and bound their ports
     */
    @DynamicPropertySource
    static void registerDynamicProperties(final DynamicPropertyRegistry registry) {
        // PostgreSQL coordinates (random host port) — consumed by the DataSource and Flyway.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Region + dummy credentials (restated explicitly; LocalStack accepts any value).
        registry.add("spring.cloud.aws.region.static", () -> AWS_REGION);
        registry.add("spring.cloud.aws.credentials.access-key", () -> AWS_ACCESS_KEY);
        registry.add("spring.cloud.aws.credentials.secret-key", () -> AWS_SECRET_KEY);

        // Endpoint overrides -> LocalStack (global + per-service, all identical) so every generated
        // S3/SQS/SNS client connects to the container's mapped edge port instead of real AWS.
        registry.add("spring.cloud.aws.endpoint", AbstractBatchIntegrationTest::localstackEndpoint);
        registry.add("spring.cloud.aws.s3.endpoint", AbstractBatchIntegrationTest::localstackEndpoint);
        registry.add("spring.cloud.aws.sqs.endpoint", AbstractBatchIntegrationTest::localstackEndpoint);
        registry.add("spring.cloud.aws.sns.endpoint", AbstractBatchIntegrationTest::localstackEndpoint);

        // LocalStack S3 requires path-style URLs (http://endpoint/bucket/key).
        registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> Boolean.TRUE);
    }

    /**
     * Resolves the LocalStack edge endpoint as a string for the {@code spring.cloud.aws.*.endpoint}
     * properties. Evaluated lazily (after {@code LOCALSTACK.start()}), so the mapped host port is known.
     *
     * @return the LocalStack endpoint URI rendered as a string (for example {@code http://localhost:32768})
     */
    private static String localstackEndpoint() {
        return LOCALSTACK.getEndpoint().toString();
    }

    // -----------------------------------------------------------------------------------------------
    // AWS SDK v2 clients — auto-configured by Spring Cloud AWS 3.3.0 and injected for subclass use.
    // -----------------------------------------------------------------------------------------------

    /** Synchronous S3 client (auto-configured, LocalStack-backed) used by the S3 provisioning helpers. */
    @Autowired
    protected S3Client s3Client;

    /** Asynchronous SQS client (auto-configured, LocalStack-backed) used by the SQS provisioning helpers. */
    @Autowired
    protected SqsAsyncClient sqsAsyncClient;

    // ===============================================================================================
    // S3 self-provisioning helpers (AAP §0.7.7). A subclass creates only what it needs and tears it
    // down; the base pre-creates nothing.
    // ===============================================================================================

    /**
     * Creates an S3 bucket in LocalStack. Intended to be called from a subclass {@code @BeforeEach} /
     * {@code @BeforeAll} for the specific bucket(s) that test exercises.
     *
     * @param bucket the bucket name to create (for example {@link #BUCKET_INPUT}); must not be {@code null}
     */
    protected void createBucket(final String bucket) {
        s3Client.createBucket(request -> request.bucket(bucket));
    }

    /**
     * Deletes an S3 bucket after first removing every object it contains (S3 forbids deleting a
     * non-empty bucket). Safe to call from a subclass teardown even if the bucket was never created or
     * is already empty: a {@link NoSuchBucketException} is treated as "already gone" and ignored, so
     * cleanup never fails a test.
     *
     * @param bucket the bucket name to delete recursively; must not be {@code null}
     */
    protected void deleteBucketRecursively(final String bucket) {
        try {
            // The paginator transparently walks every page of keys; delete each object, then the bucket.
            for (final S3Object object : s3Client.listObjectsV2Paginator(request -> request.bucket(bucket)).contents()) {
                s3Client.deleteObject(request -> request.bucket(bucket).key(object.key()));
            }
            s3Client.deleteBucket(request -> request.bucket(bucket));
        } catch (final NoSuchBucketException ignored) {
            // Idempotent teardown: the bucket does not exist, so there is nothing to clean up.
        }
    }

    /**
     * Uploads a byte payload to S3. The bytes are stored verbatim (no transformation), which is what
     * makes byte-for-byte parity assertions (AAP&nbsp;Gate&nbsp;1/4) meaningful when combined with
     * {@link #readObject(String, String)}.
     *
     * @param bucket  the destination bucket (must already exist); must not be {@code null}
     * @param key     the object key; must not be {@code null}
     * @param content the exact bytes to store; must not be {@code null}
     */
    protected void putObject(final String bucket, final String key, final byte[] content) {
        s3Client.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromBytes(content));
    }

    /**
     * Reads an S3 object back as a byte array for exact-content assertions.
     *
     * @param bucket the source bucket; must not be {@code null}
     * @param key    the object key; must not be {@code null}
     * @return the object's bytes exactly as stored
     * @throws NoSuchKeyException if the object does not exist
     */
    protected byte[] readObject(final String bucket, final String key) {
        return s3Client.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asByteArray();
    }

    /**
     * Reports whether an S3 object exists, without downloading it. A missing object (either a mapped
     * {@link NoSuchKeyException} or a bare {@code 404} {@link S3Exception}, both of which LocalStack may
     * return for a HEAD on an absent key) yields {@code false}; any other error propagates.
     *
     * @param bucket the bucket to probe; must not be {@code null}
     * @param key    the object key to probe; must not be {@code null}
     * @return {@code true} if the object exists, {@code false} if it is absent
     */
    protected boolean objectExists(final String bucket, final String key) {
        try {
            s3Client.headObject(request -> request.bucket(bucket).key(key));
            return true;
        } catch (final NoSuchKeyException absent) {
            return false;
        } catch (final S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    // ===============================================================================================
    // SQS self-provisioning helpers (AAP §0.7.7). FIFO semantics match the migrated CORPT00C bridge.
    // ===============================================================================================

    /**
     * Creates a FIFO SQS queue in LocalStack with content-based deduplication enabled and returns its
     * URL. The name MUST carry the mandatory {@code .fifo} suffix (an AWS requirement for FIFO queues);
     * a non-conforming name fails fast so the misconfiguration is caught in the test, not at runtime.
     *
     * @param queueName the FIFO queue name (for example {@link #QUEUE_REPORT_FIFO}); must end in {@code .fifo}
     * @return the created queue's URL
     * @throws IllegalArgumentException if {@code queueName} does not end with {@code .fifo}
     */
    protected String createFifoQueue(final String queueName) {
        if (queueName == null || !queueName.endsWith(".fifo")) {
            throw new IllegalArgumentException("FIFO queue name must end with \".fifo\": " + queueName);
        }
        final Map<QueueAttributeName, String> attributes = Map.of(
                QueueAttributeName.FIFO_QUEUE, "true",
                QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true");
        return sqsAsyncClient.createQueue(request -> request.queueName(queueName).attributes(attributes))
                .join()
                .queueUrl();
    }

    /**
     * Resolves the URL of an existing SQS queue by name.
     *
     * @param queueName the queue name to resolve; must not be {@code null}
     * @return the queue URL
     * @throws QueueDoesNotExistException (wrapped in a {@link CompletionException}) if the queue is absent
     */
    protected String queueUrl(final String queueName) {
        return sqsAsyncClient.getQueueUrl(request -> request.queueName(queueName))
                .join()
                .queueUrl();
    }

    /**
     * Deletes an SQS queue by name. Safe to call from a subclass teardown even if the queue was never
     * created: a {@link QueueDoesNotExistException} (surfaced by the async client as the cause of a
     * {@link CompletionException}) is treated as "already gone" and ignored, so cleanup never fails a test.
     *
     * @param queueName the queue name to delete; must not be {@code null}
     */
    protected void deleteQueue(final String queueName) {
        try {
            final String url = sqsAsyncClient.getQueueUrl(request -> request.queueName(queueName)).join().queueUrl();
            sqsAsyncClient.deleteQueue(request -> request.queueUrl(url)).join();
        } catch (final CompletionException e) {
            if (e.getCause() instanceof QueueDoesNotExistException) {
                // Idempotent teardown: the queue does not exist, so there is nothing to delete.
                return;
            }
            throw e;
        }
    }

    /**
     * Sends a message to a FIFO queue. A {@code messageGroupId} is mandatory for FIFO queues; because
     * the queues created by {@link #createFifoQueue(String)} enable content-based deduplication, no
     * explicit deduplication id is supplied.
     *
     * @param queueUrl the target queue URL (typically from {@link #createFifoQueue(String)} or
     *                 {@link #queueUrl(String)}); must not be {@code null}
     * @param body     the message body; must not be {@code null}
     * @param groupId  the FIFO message group id that orders related messages; must not be {@code null}
     */
    protected void sendMessage(final String queueUrl, final String body, final String groupId) {
        sqsAsyncClient.sendMessage(request -> request.queueUrl(queueUrl).messageBody(body).messageGroupId(groupId))
                .join();
    }
}

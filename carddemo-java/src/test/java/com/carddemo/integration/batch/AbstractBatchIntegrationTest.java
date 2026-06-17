package com.carddemo.integration.batch;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.assertj.core.api.Assertions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Shared Testcontainers/LocalStack base for the CardDemo Spring Batch pipeline integration tests
 * (the Java re-platform of the {@code app/jcl} 5-stage pipeline, source commit {@code 27d6c6f};
 * REFERENCE ONLY, no COBOL/JCL copied).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
public abstract class AbstractBatchIntegrationTest {

    // ------------------------------------------------------------------------
    // Singleton containers (D-shared-containers): started once in the static
    // initializer and torn down exactly once via a JVM shutdown hook (see
    // tearDownSharedResources). NOT annotated @Container (that would impose
    // per-class start/stop and break the cached-context endpoints); the shutdown
    // hook is the correct teardown point for singletons shared across subclasses.
    // ------------------------------------------------------------------------

    // The relocated, non-deprecated org.testcontainers.postgresql.PostgreSQLContainer (D3) is a
    // non-generic class (it fixes its self type to itself), so it takes no type parameter; this is
    // not a raw type and triggers no -Xlint:rawtypes warning.
    /** Shared PostgreSQL 16 instance backing every batch IT (one seeded database for all subclasses). */
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("carddemo")
                    .withUsername("carddemo")
                    .withPassword("carddemo");

    /** Shared LocalStack instance providing S3, SQS, and SNS for every batch IT. */
    protected static final LocalStackContainer LOCALSTACK = createLocalStack();

    /**
     * Builds the LocalStack container, enabling S3/SQS/SNS via the {@code SERVICES} environment
     * variable (Testcontainers 2.x non-deprecated wiring) and applying {@code LOCALSTACK_AUTH_TOKEN}
     * only when the environment supplies a non-blank value.
     *
     * @return the configured (not-yet-started) LocalStack container
     */
    private static LocalStackContainer createLocalStack() {
        LocalStackContainer container = new LocalStackContainer(
                DockerImageName.parse("localstack/localstack:4.5.0"))
                .withEnv("SERVICES", "s3,sqs,sns");
        String token = System.getenv("LOCALSTACK_AUTH_TOKEN");
        if (token != null && !token.isBlank()) {
            container.withEnv("LOCALSTACK_AUTH_TOKEN", token);
        }
        return container;
    }

    static {
        // Guarded so a Docker-less class load does not explode static init: constructing the
        // container objects needs no Docker, only start() does. When Docker is absent the
        // @Testcontainers(disabledWithoutDocker=true) condition skips the subclasses cleanly.
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
            LOCALSTACK.start();
            Runtime.getRuntime().addShutdownHook(
                    new Thread(AbstractBatchIntegrationTest::tearDownSharedResources, "batch-it-teardown"));
        }
    }

    // ------------------------------------------------------------------------
    // Resource-name and record-length constants — MUST match application-test.yml,
    // docker-compose.yml, and localstack-init/init-aws.sh exactly.
    // ------------------------------------------------------------------------

    /** S3 bucket for batch input staging (GDG re-host). */
    protected static final String BUCKET_INPUT = "carddemo-batch-input";
    /** S3 bucket for batch output: rejects ({@code DALYREJS}) and staging ({@code SYSTRAN}). */
    protected static final String BUCKET_OUTPUT = "carddemo-batch-output";
    /** S3 bucket for generated statements. */
    protected static final String BUCKET_STATEMENTS = "carddemo-statements";
    /** SQS FIFO queue for the report-submission bridge (CICS TDQ replacement). */
    protected static final String REPORT_QUEUE = "carddemo-report-jobs.fifo";
    /** Companion FIFO dead-letter queue paired with {@link #REPORT_QUEUE} (DECISION_LOG D-029). */
    protected static final String REPORT_DLQ = "carddemo-report-jobs-dlq.fifo";
    /** Receives a message may incur on the main queue before SQS moves it to the DLQ. */
    protected static final int MAX_RECEIVE_COUNT = 5;
    /** SNS topic for notification fan-out. */
    protected static final String NOTIFICATIONS_TOPIC = "carddemo-notifications";
    /** Conventional S3 key for the daily transaction input fixture. */
    protected static final String DAILY_TRAN_KEY = "dailytran.txt";
    /** Exact S3 key under which the reject writer emits its single fixed-width object. */
    protected static final String REJECT_OBJECT_KEY = "DALYREJS";
    /** Fixed record length (bytes) of a daily transaction record. */
    protected static final int DAILY_TRAN_RECORD_LENGTH = 350;
    /** Fixed record length (bytes) of a reject record (POSTTRAN.jcl DALYREJS LRECL=430). */
    protected static final int REJECT_RECORD_LENGTH = 430;
    /** Number of records in the {@code dailytran.txt} fixture. */
    protected static final int EXPECTED_DAILY_RECORDS = 300;

    // ------------------------------------------------------------------------
    // Property wiring + AWS resource provisioning (D2). @ServiceConnection is not
    // usable here (spring-cloud-aws-testcontainers / spring-boot-testcontainers
    // are absent), so every property is registered manually, and the S3/SQS/SNS
    // resources are self-provisioned before the context refreshes so listeners bind.
    // ------------------------------------------------------------------------

    /**
     * Registers the PostgreSQL datasource and AWS (LocalStack) properties, then self-provisions the
     * three S3 buckets, the FIFO report queue and its companion dead-letter queue, and the
     * notifications topic inside the LocalStack container. Provisioning runs here (before context
     * refresh) so an {@code @SqsListener} can bind to an already-existing queue.
     *
     * @param registry the registry the Spring TestContext framework supplies for dynamic properties
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.cloud.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.region.static", LOCALSTACK::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", LOCALSTACK::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", LOCALSTACK::getSecretKey);
        registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");

        try {
            LOCALSTACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_INPUT);
            LOCALSTACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_OUTPUT);
            LOCALSTACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_STATEMENTS);
            // Main report queue plus its companion FIFO dead-letter queue, then a RedrivePolicy on
            // the main queue targeting the DLQ so poison/failed messages are isolated after
            // MAX_RECEIVE_COUNT receives rather than looping or being dropped (DECISION_LOG D-029).
            // This mirrors localstack-init/init-aws.sh so the test stack matches the runtime stack.
            LOCALSTACK.execInContainer("awslocal", "sqs", "create-queue",
                    "--queue-name", REPORT_QUEUE,
                    "--attributes", "FifoQueue=true,ContentBasedDeduplication=true");
            LOCALSTACK.execInContainer("awslocal", "sqs", "create-queue",
                    "--queue-name", REPORT_DLQ,
                    "--attributes", "FifoQueue=true,ContentBasedDeduplication=true");
            String dlqUrl = LOCALSTACK.execInContainer("awslocal", "sqs", "get-queue-url",
                    "--queue-name", REPORT_DLQ,
                    "--query", "QueueUrl", "--output", "text").getStdout().trim();
            String dlqArn = LOCALSTACK.execInContainer("awslocal", "sqs", "get-queue-attributes",
                    "--queue-url", dlqUrl,
                    "--attribute-names", "QueueArn",
                    "--query", "Attributes.QueueArn", "--output", "text").getStdout().trim();
            String mainUrl = LOCALSTACK.execInContainer("awslocal", "sqs", "get-queue-url",
                    "--queue-name", REPORT_QUEUE,
                    "--query", "QueueUrl", "--output", "text").getStdout().trim();
            // The RedrivePolicy attribute value is itself a JSON-encoded string; passed as a single
            // argv element (no shell), so embedded quotes are escaped rather than file:// indirected.
            String redrivePolicy = "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\""
                    + dlqArn + "\\\",\\\"maxReceiveCount\\\":\\\"" + MAX_RECEIVE_COUNT + "\\\"}\"}";
            LOCALSTACK.execInContainer("awslocal", "sqs", "set-queue-attributes",
                    "--queue-url", mainUrl,
                    "--attributes", redrivePolicy);
            LOCALSTACK.execInContainer("awslocal", "sns", "create-topic", "--name", NOTIFICATIONS_TOPIC);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to provision LocalStack AWS resources", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while provisioning LocalStack AWS resources", e);
        }
    }

    /**
     * Tears down the shared batch test infrastructure at JVM exit: a best-effort deletion of every
     * self-provisioned AWS resource (the three S3 buckets and their objects, the SQS FIFO queue and
     * its companion dead-letter queue, and the SNS topic) followed by stopping both shared
     * containers. This makes the batch suite satisfy the LocalStack Verification rule that tests
     * provision <em>and</em> tear down their own resources.
     *
     * <p>The teardown is a JVM shutdown hook rather than an {@code @AfterAll} because {@link #POSTGRES}
     * and {@link #LOCALSTACK} are shared static singletons reused across every batch subclass; an
     * {@code @AfterAll} would stop them after the first subclass and break the rest. The hook runs
     * exactly once, after the last test in the JVM, and every step is a best-effort guard so a cleanup
     * failure can neither fail the build nor mask a test result.</p>
     */
    private static void tearDownSharedResources() {
        deleteBucketQuietly(BUCKET_INPUT);
        deleteBucketQuietly(BUCKET_OUTPUT);
        deleteBucketQuietly(BUCKET_STATEMENTS);
        execQuietly("sh", "-c",
                "awslocal sqs delete-queue --queue-url "
                        + "$(awslocal sqs get-queue-url --queue-name " + REPORT_QUEUE
                        + " --query QueueUrl --output text)");
        execQuietly("sh", "-c",
                "awslocal sqs delete-queue --queue-url "
                        + "$(awslocal sqs get-queue-url --queue-name " + REPORT_DLQ
                        + " --query QueueUrl --output text)");
        execQuietly("sh", "-c",
                "awslocal sns delete-topic --topic-arn "
                        + "$(awslocal sns list-topics --output text | grep " + NOTIFICATIONS_TOPIC
                        + " | awk '{print $2}')");
        stopQuietly();
    }

    /** Best-effort removal of an S3 bucket and all of its objects (recursive {@code rb --force}). */
    private static void deleteBucketQuietly(String bucket) {
        execQuietly("awslocal", "s3", "rb", "s3://" + bucket, "--force");
    }

    /**
     * Runs a command inside the LocalStack container, swallowing failures so shutdown teardown stays
     * best-effort (the container is stopped immediately afterwards, reclaiming any residual state).
     */
    private static void execQuietly(String... command) {
        try {
            LOCALSTACK.execInContainer(command);
        } catch (IOException | RuntimeException e) {
            // Best-effort cleanup: the container stop below reclaims any residual state.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Best-effort stop of both shared containers; Testcontainers/Ryuk reaps anything left behind. */
    private static void stopQuietly() {
        try {
            if (LOCALSTACK.isRunning()) {
                LOCALSTACK.stop();
            }
        } catch (RuntimeException e) {
            // Best-effort: Testcontainers/Ryuk reaps the container at JVM exit.
        }
        try {
            if (POSTGRES.isRunning()) {
                POSTGRES.stop();
            }
        } catch (RuntimeException e) {
            // Best-effort: Testcontainers/Ryuk reaps the container at JVM exit.
        }
    }

    /** Protected no-argument constructor invoked by the concrete batch integration-test subclasses. */
    protected AbstractBatchIntegrationTest() {
        // No initialization required; shared state is the static singletons plus injected beans.
    }

    // ------------------------------------------------------------------------
    // Injected beans shared by helpers and inherited by subclasses (D5). Jobs are
    // launched explicitly via JobLauncher because spring.batch.job.enabled=false.
    // ------------------------------------------------------------------------

    /** Launcher used to run batch jobs explicitly (subclasses inject their {@code Job} beans). */
    @Autowired
    protected JobLauncher jobLauncher;

    /** Synchronous S3 client (AwsConfig bean) used by the S3 helper methods. */
    @Autowired
    protected S3Client s3Client;

    /** Asynchronous SQS client (the only SQS bean; AwsConfig defines no synchronous SqsClient). */
    @Autowired
    protected SqsAsyncClient sqsAsyncClient;

    // ------------------------------------------------------------------------
    // Per-test S3 cleanup. Containers/DB are shared singletons, so the three
    // buckets are emptied before each test to keep S3 assertions deterministic.
    // The DB is intentionally NOT purged (subclasses rely on the Flyway seed plus
    // count-deltas / dynamic expectations).
    // ------------------------------------------------------------------------

    /** Empties the input, output, and statements buckets so prior tests cannot contaminate S3 assertions. */
    @BeforeEach
    void cleanS3Buckets() {
        emptyBucket(BUCKET_INPUT);
        emptyBucket(BUCKET_OUTPUT);
        emptyBucket(BUCKET_STATEMENTS);
    }

    /**
     * Deletes every object in the given bucket without deleting the bucket itself.
     *
     * @param bucket the bucket whose objects are removed
     */
    protected void emptyBucket(String bucket) {
        ListObjectsV2Response listing = s3Client.listObjectsV2(
                ListObjectsV2Request.builder().bucket(bucket).build());
        for (S3Object obj : listing.contents()) {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(obj.key()).build());
        }
    }

    // ------------------------------------------------------------------------
    // Reusable helpers for subclasses.
    // ------------------------------------------------------------------------

    /**
     * Locates an {@code app/data/ASCII} fixture that lives outside the Maven module (D6). Honors the
     * {@code carddemo.fixtures.dir} system property and the {@code CARDDEMO_FIXTURES_DIR} environment
     * variable first, then walks up to six parent directories from the working directory.
     *
     * @param fileName the fixture file name (for example {@code dailytran.txt})
     * @return the resolved fixture path, or {@code null} when it cannot be found
     */
    protected static Path locateFixture(String fileName) {
        String sysProp = System.getProperty("carddemo.fixtures.dir");
        if (sysProp != null && !sysProp.isBlank()) {
            Path candidate = Paths.get(sysProp, fileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        String envDir = System.getenv("CARDDEMO_FIXTURES_DIR");
        if (envDir != null && !envDir.isBlank()) {
            Path candidate = Paths.get(envDir, fileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        Path dir = Paths.get("").toAbsolutePath();
        for (int level = 0; level <= 6 && dir != null; level++) {
            Path candidate = dir.resolve("app/data/ASCII").resolve(fileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * Reads a fixture's bytes, skipping the calling test (via JUnit assumption) when the fixture is
     * unreachable so a fixture-less environment stays green rather than failing.
     *
     * @param fileName the fixture file name
     * @return the fixture contents
     * @throws IOException if the located fixture cannot be read
     */
    protected byte[] requireFixtureBytes(String fileName) throws IOException {
        Path path = locateFixture(fileName);
        Assumptions.assumeTrue(path != null, fileName + " fixture not reachable; skipping IT");
        return Files.readAllBytes(path);
    }

    /**
     * Uploads a byte payload to S3 under an exact key.
     *
     * @param bucket the destination bucket
     * @param key    the exact object key
     * @param data   the object payload
     */
    protected void putS3Object(String bucket, String key, byte[] data) {
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(data));
    }

    /**
     * Reads an S3 object by its exact key, returning {@code null} when the key does not exist.
     *
     * @param bucket the source bucket
     * @param key    the exact object key
     * @return the object bytes, or {@code null} if the key is absent
     */
    protected byte[] getS3ObjectOrNull(String bucket, String key) {
        try {
            return s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    /**
     * Counts reject records by reading the exact {@code DALYREJS} key (D7): the output bucket also
     * holds the 350-byte {@code SYSTRAN} staging object, so listing/summing all objects would be
     * wrong. An absent key means zero rejects; otherwise the length must be a whole multiple of the
     * 430-byte reject record length.
     *
     * @return the number of reject records (0 when the reject object is absent)
     */
    protected long countRejectRecords() {
        byte[] data = getS3ObjectOrNull(BUCKET_OUTPUT, REJECT_OBJECT_KEY);
        if (data == null) {
            return 0L;
        }
        Assertions.assertThat(data.length % REJECT_RECORD_LENGTH).isZero();
        return (long) data.length / REJECT_RECORD_LENGTH;
    }

    /**
     * Lists the keys of every object currently in a bucket.
     *
     * @param bucket the bucket to list
     * @return an immutable list of object keys
     */
    protected List<String> listObjectKeys(String bucket) {
        return s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
                .contents().stream()
                .map(S3Object::key)
                .toList();
    }

    /** Trailing zoned-decimal overpunch characters carrying a positive sign: index 0 is '+0', index 9 is '+9'. */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";
    /** Trailing zoned-decimal overpunch characters carrying a negative sign: index 0 is '-0', index 9 is '-9'. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /**
     * Decodes a fixed-width signed zoned-decimal field with a trailing overpunch sign into a scale-2
     * {@link BigDecimal}, proving COBOL decimal fidelity (compared with {@code compareTo}, never
     * {@code equals}). The final character carries both the units digit and the sign; the preceding
     * characters are plain digits. Plain digits {@code 0}-{@code 9} are treated as positive.
     *
     * @param raw the raw fixed-width field (last character is the trailing overpunch)
     * @return the decoded value with scale 2
     */
    protected static BigDecimal decodeOverpunch(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("Overpunch field must be non-empty");
        }
        int lastIndex = raw.length() - 1;
        String leading = raw.substring(0, lastIndex);
        char last = raw.charAt(lastIndex);

        int positiveDigit = POSITIVE_OVERPUNCH.indexOf(last);
        int negativeDigit = NEGATIVE_OVERPUNCH.indexOf(last);

        int finalDigit;
        boolean negative;
        if (positiveDigit >= 0) {
            finalDigit = positiveDigit;
            negative = false;
        } else if (negativeDigit >= 0) {
            finalDigit = negativeDigit;
            negative = true;
        } else if (last >= '0' && last <= '9') {
            finalDigit = last - '0';
            negative = false;
        } else {
            throw new IllegalArgumentException("Invalid trailing overpunch character: '" + last + "'");
        }

        BigDecimal magnitude = new BigDecimal(leading + finalDigit).movePointLeft(2);
        return negative ? magnitude.negate() : magnitude;
    }

    /**
     * Starts a job-parameters builder seeded with a unique {@code run.id} so every launch yields a
     * distinct {@code JobInstance}. Callers chain additional parameters (for example
     * {@code inputLocation}, {@code parmDate}, {@code startDate}, {@code endDate}).
     *
     * @return a {@link JobParametersBuilder} carrying a unique {@code run.id}
     */
    protected JobParametersBuilder baseParams() {
        return new JobParametersBuilder().addLong("run.id", System.currentTimeMillis());
    }

    /**
     * Convenience launch wrapper around {@link JobLauncher#run}.
     *
     * @param job    the job to run
     * @param params the launch parameters (use {@link #baseParams()} for a unique run id)
     * @return the resulting {@link JobExecution}
     * @throws Exception if the launcher rejects the run (already running, restart, completed, or
     *                   invalid parameters)
     */
    protected JobExecution launch(Job job, JobParameters params) throws Exception {
        return jobLauncher.run(job, params);
    }
}

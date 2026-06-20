package com.carddemo.integration.batch;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
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
 * Shared Testcontainers + LocalStack base for the CardDemo Spring Batch pipeline integration tests,
 * supporting the Java re-platform of the {@code app/jcl} 5-stage pipeline at source commit
 * {@code 27d6c6f} (reference only; no COBOL/JCL is copied).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
public abstract class AbstractBatchIntegrationTest {

    /** S3 bucket holding the daily transaction input file (matches {@code application-test.yml}). */
    protected static final String BUCKET_INPUT = "carddemo-batch-input";

    /** S3 bucket holding batch output objects: posting staging ({@code SYSTRAN}) and rejects ({@code DALYREJS}). */
    protected static final String BUCKET_OUTPUT = "carddemo-batch-output";

    /** S3 bucket holding generated statement objects. */
    protected static final String BUCKET_STATEMENTS = "carddemo-statements";

    /** SQS FIFO queue replacing the CICS report-submission transient-data-queue (name must end in {@code .fifo}). */
    protected static final String REPORT_QUEUE = "carddemo-report-jobs.fifo";

    /** SNS topic used for notification fan-out. */
    protected static final String NOTIFICATIONS_TOPIC = "carddemo-notifications";

    /**
     * Deterministic-per-run, test-only HMAC-SHA256 signing secret. It is generated once per JVM from
     * {@link SecureRandom} (32 random bytes, Base64-encoded to a string of &ge; 32 UTF-8 bytes, which
     * satisfies the HS256 minimum enforced by {@code SecurityConfig}) and registered below as
     * {@code carddemo.security.jwt.secret}, so the Spring Security filter chain loads even when the
     * {@code JWT_SECRET} environment variable is absent. It is never committed and never reaches
     * production — each test run uses a fresh random value.
     */
    private static final String TEST_JWT_SECRET = generateTestJwtSecret();

    private static String generateTestJwtSecret() {
        byte[] secretBytes = new byte[32];
        new SecureRandom().nextBytes(secretBytes);
        return Base64.getEncoder().encodeToString(secretBytes);
    }

    /** Canonical S3 key for the daily transaction input fixture. */
    protected static final String DAILY_TRAN_KEY = "dailytran.txt";

    /** Exact S3 key written by the reject writer; rejects MUST be read by this key only (never by listing). */
    protected static final String REJECT_OBJECT_KEY = "DALYREJS";

    /** Fixed record length, in bytes, of a daily transaction record. */
    protected static final int DAILY_TRAN_RECORD_LENGTH = 350;

    /** Fixed record length, in bytes, of a daily transaction reject record ({@code DALYREJS}). */
    protected static final int REJECT_RECORD_LENGTH = 430;

    /** Number of records in the {@code dailytran.txt} fixture. */
    protected static final int EXPECTED_DAILY_RECORDS = 300;

    /**
     * Singleton PostgreSQL 16 container shared by all subclass integration tests. Started once and
     * never stopped (Ryuk reaps it at JVM exit). The non-deprecated Testcontainers 2.x
     * {@code org.testcontainers.postgresql.PostgreSQLContainer} is not generic, so no type argument
     * is used (this is not a raw type).
     */
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("carddemo")
                    .withUsername("carddemo")
                    .withPassword("carddemo");

    /** Singleton LocalStack container (S3 + SQS + SNS) shared by all subclass integration tests. */
    protected static final LocalStackContainer LOCALSTACK = createLocalStack();

    static {
        // Constructing the container objects needs no Docker; only start() does. Guard the start so a
        // Docker-less load (the disabledWithoutDocker skip path) never explodes static initialization.
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
            LOCALSTACK.start();
        }
    }

    /** Launches batch jobs explicitly because {@code spring.batch.job.enabled} is {@code false} in the test profile. */
    @Autowired
    protected JobLauncher jobLauncher;

    /** Synchronous S3 client (defined by {@code AwsConfig}) used by the S3 helper methods. */
    @Autowired
    protected S3Client s3Client;

    /** Asynchronous SQS client (defined by {@code AwsConfig}; there is no synchronous client) for subclass use. */
    @Autowired
    protected SqsAsyncClient sqsAsyncClient;

    /**
     * Protected no-argument constructor. This base class is abstract and is never instantiated
     * directly; subclasses are created by the JUnit/Spring test runtime.
     */
    protected AbstractBatchIntegrationTest() {
        // No initialization required; Spring injects the @Autowired fields after construction.
    }

    /**
     * Builds the LocalStack container with the {@code s3,sqs,sns} services enabled, applying the
     * {@code LOCALSTACK_AUTH_TOKEN} environment variable only when it is present and non-blank.
     *
     * @return the configured (not yet started) LocalStack container
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

    /**
     * Wires the PostgreSQL datasource and the Spring Cloud AWS endpoint/region/credentials to the
     * running singleton containers, then self-provisions the three S3 buckets, the report FIFO
     * queue, and the notifications topic inside LocalStack. Provisioning happens here (rather than in
     * a lifecycle callback) so the resources exist before the application context refreshes and any
     * {@code @SqsListener} binds to the queue.
     *
     * @param registry the dynamic property registry supplied by the Spring TestContext framework
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
        // Provide a generated, test-only JWT signing secret so the Spring Security filter chain
        // loads even when JWT_SECRET is not set in the environment (e.g. local developer runs).
        registry.add("carddemo.security.jwt.secret", () -> TEST_JWT_SECRET);

        try {
            LOCALSTACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_INPUT);
            LOCALSTACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_OUTPUT);
            LOCALSTACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_STATEMENTS);
            LOCALSTACK.execInContainer("awslocal", "sqs", "create-queue",
                    "--queue-name", REPORT_QUEUE,
                    "--attributes", "FifoQueue=true,ContentBasedDeduplication=true");
            LOCALSTACK.execInContainer("awslocal", "sns", "create-topic", "--name", NOTIFICATIONS_TOPIC);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to provision LocalStack AWS resources", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while provisioning LocalStack AWS resources", e);
        }
    }

    /**
     * Empties the three CardDemo buckets before each test. Because the containers and the seeded
     * database are shared singletons across all six integration test classes, this keeps S3
     * assertions (rejects, statements, reports, staging) free of objects written by a prior test.
     * The buckets themselves are provisioned once and are not deleted; the database is not purged.
     */
    @BeforeEach
    void cleanS3Buckets() {
        emptyBucket(BUCKET_INPUT);
        emptyBucket(BUCKET_OUTPUT);
        emptyBucket(BUCKET_STATEMENTS);
    }

    /**
     * Deletes every object in the given bucket without deleting the bucket itself.
     *
     * @param bucket the bucket name to empty
     */
    protected void emptyBucket(String bucket) {
        ListObjectsV2Response listing = s3Client.listObjectsV2(
                ListObjectsV2Request.builder().bucket(bucket).build());
        for (S3Object object : listing.contents()) {
            s3Client.deleteObject(
                    DeleteObjectRequest.builder().bucket(bucket).key(object.key()).build());
        }
    }

    /**
     * Locates a fixture file that lives outside the module at repository-root {@code app/data/ASCII/}.
     * The {@code carddemo.fixtures.dir} system property and {@code CARDDEMO_FIXTURES_DIR} environment
     * variable are honoured first; otherwise the search walks upward from the working directory.
     *
     * @param fileName the fixture file name, for example {@code dailytran.txt}
     * @return the resolved path, or {@code null} when the fixture cannot be reached
     */
    protected static Path locateFixture(String fileName) {
        String configuredDir = System.getProperty("carddemo.fixtures.dir");
        if (configuredDir == null || configuredDir.isBlank()) {
            configuredDir = System.getenv("CARDDEMO_FIXTURES_DIR");
        }
        if (configuredDir != null && !configuredDir.isBlank()) {
            Path candidate = Paths.get(configuredDir, fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        Path current = Paths.get("").toAbsolutePath();
        for (int level = 0; level <= 6 && current != null; level++) {
            Path candidate = current.resolve(Paths.get("app", "data", "ASCII", fileName));
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * Reads a fixture's bytes, skipping (not failing) the calling test when the fixture is
     * unreachable so a checkout without the source tree stays green.
     *
     * @param fileName the fixture file name
     * @return the fixture bytes
     * @throws IOException if the located fixture cannot be read
     */
    protected byte[] requireFixtureBytes(String fileName) throws IOException {
        Path path = locateFixture(fileName);
        Assumptions.assumeTrue(path != null, fileName + " fixture not reachable; skipping IT");
        return Files.readAllBytes(path);
    }

    /**
     * Uploads a byte payload to S3 under the given bucket and key.
     *
     * @param bucket the target bucket
     * @param key    the target object key
     * @param data   the object bytes
     */
    protected void putS3Object(String bucket, String key, byte[] data) {
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(data));
    }

    /**
     * Reads an S3 object by its exact key, returning {@code null} when the key is absent.
     *
     * @param bucket the bucket to read from
     * @param key    the exact object key
     * @return the object bytes, or {@code null} if the key does not exist
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
     * Counts the daily-transaction reject records by reading the single fixed-width {@code DALYREJS}
     * object by its exact key. The output bucket also holds 350-byte staging objects, so rejects are
     * never counted by listing or summing the bucket. An absent key means zero rejects; otherwise the
     * object length must be an exact multiple of the 430-byte reject record length.
     *
     * @return the number of 430-byte reject records (0 when {@code DALYREJS} is absent)
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
     * Lists every object key in the given bucket.
     *
     * @param bucket the bucket to list
     * @return the object keys (possibly empty)
     */
    protected List<String> listObjectKeys(String bucket) {
        return s3Client.listObjectsV2(
                ListObjectsV2Request.builder().bucket(bucket).build())
                .contents().stream().map(S3Object::key).toList();
    }

    /**
     * Decodes a COBOL zoned-decimal value carrying a trailing overpunch sign into a scale-2
     * {@link BigDecimal}, reproducing the source decimal fidelity so callers can assert with
     * {@code compareTo} rather than the scale-sensitive {@code equals}. Trailing sign table:
     * {@code '{'} = +0, {@code 'A'..'I'} = +1..+9, {@code '}'} = -0, {@code 'J'..'R'} = -1..-9,
     * and {@code '0'..'9'} = +digit.
     *
     * @param raw the raw zoned-decimal field including its trailing overpunch character
     * @return the decoded value with scale 2
     */
    protected static BigDecimal decodeOverpunch(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("Overpunch value must be non-empty");
        }
        char last = raw.charAt(raw.length() - 1);
        String leading = raw.substring(0, raw.length() - 1);
        int digit;
        boolean negative;
        if (last >= '0' && last <= '9') {
            digit = last - '0';
            negative = false;
        } else if (last == '{') {
            digit = 0;
            negative = false;
        } else if (last == '}') {
            digit = 0;
            negative = true;
        } else if (last >= 'A' && last <= 'I') {
            digit = last - 'A' + 1;
            negative = false;
        } else if (last >= 'J' && last <= 'R') {
            digit = last - 'J' + 1;
            negative = true;
        } else {
            throw new IllegalArgumentException("Unrecognized overpunch sign character: '" + last + "'");
        }
        BigDecimal magnitude = new BigDecimal(leading + digit).movePointLeft(2);
        return negative ? magnitude.negate() : magnitude;
    }

    /**
     * Starts a job-parameters builder seeded with a unique {@code run.id} so every launch yields a
     * distinct {@code JobInstance}; callers add their own parameters (input location, dates, ...).
     *
     * @return a fresh builder carrying a unique {@code run.id}
     */
    protected JobParametersBuilder baseParams() {
        return new JobParametersBuilder().addLong("run.id", System.currentTimeMillis());
    }

    /**
     * Launches a job via the autowired {@link JobLauncher}, wrapping the checked Spring Batch launch
     * exceptions in an unchecked {@link IllegalStateException} for concise use in subclasses.
     *
     * @param job    the job to launch
     * @param params the job parameters
     * @return the resulting {@link JobExecution}
     */
    protected JobExecution launch(Job job, JobParameters params) {
        try {
            return jobLauncher.run(job, params);
        } catch (JobExecutionException e) {
            throw new IllegalStateException("Failed to launch batch job: " + job.getName(), e);
        }
    }
}

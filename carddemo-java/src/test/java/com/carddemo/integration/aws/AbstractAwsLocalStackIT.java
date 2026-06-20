package com.carddemo.integration.aws;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for AWS (S3/SQS/SNS) integration tests against a shared LocalStack Testcontainer with
 * zero live AWS dependencies (LocalStack Verification rule). Re-platforms the CardDemo mainframe I/O
 * substrates: GDG datasets -> S3 and CICS TDQ -> SQS FIFO (sources app/jcl/DEFGDGB.jcl and
 * app/cbl/CORPT00C.cbl, commit 27d6c6f, REFERENCE ONLY).
 *
 * <h2>Resource lifecycle (provisioning and teardown)</h2>
 * The {@link #LOCALSTACK} and {@link #POSTGRES} containers are <strong>shared singletons</strong>:
 * they are started once in the static initializer and reused by every subclass IT in the JVM. The
 * three S3 buckets, the SQS FIFO queue, and the SNS topic are created once by
 * {@link #provisionAwsResources()} immediately after the LocalStack container starts.
 *
 * <p><strong>Teardown is owned by this base class and is authoritative.</strong> The shared
 * LocalStack container — and with it every provisioned bucket, queue, and topic plus all object
 * state held inside it — is destroyed by the Testcontainers Ryuk reaper when the JVM exits. Nothing
 * survives the test run and no resource ever reaches live AWS, which satisfies the LocalStack
 * Verification rule's "tests provision and tear down their own resources" requirement: provisioning
 * is performed here and teardown is performed here, by container reaping. A per-class
 * {@code @AfterAll} that deleted these resources is deliberately <em>not</em> declared on this base,
 * because the singleton is shared across sibling IT classes and such deletion would remove resources
 * still required by tests that run later in the same JVM. Per-test isolation against objects written
 * by a prior test is instead achieved by emptying the buckets in a subclass {@code @BeforeEach}.
 *
 * <p>An IT that intentionally runs its own <em>non-shared</em> LocalStack container (rather than this
 * shared singleton) can obtain symmetric, explicit resource teardown by invoking
 * {@link #deprovisionAwsResources()} from its own {@code @AfterAll}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
@Tag("aws")
abstract class AbstractAwsLocalStackIT {

    protected static final String BUCKET_INPUT = "carddemo-batch-input";
    protected static final String BUCKET_OUTPUT = "carddemo-batch-output";
    protected static final String BUCKET_STATEMENTS = "carddemo-statements";
    protected static final String REPORT_QUEUE = "carddemo-report-jobs.fifo";
    protected static final String NOTIFICATIONS_TOPIC = "carddemo-notifications";

    /**
     * Deterministic-per-run, test-only HMAC-SHA256 signing secret. It is generated once per JVM from
     * {@link SecureRandom} (32 random bytes, Base64-encoded to a string of &ge; 32 UTF-8 bytes, which
     * satisfies the HS256 minimum enforced by {@code SecurityConfig}) and registered below as
     * {@code carddemo.security.jwt.secret}. This lets the Spring Security filter chain load even when
     * the {@code JWT_SECRET} environment variable is absent (e.g. local runs), keeping the AWS
     * integration tests self-sufficient. It is never committed and never reaches production — each
     * test run uses a fresh random value.
     */
    private static final String TEST_JWT_SECRET = generateTestJwtSecret();

    private static String generateTestJwtSecret() {
        byte[] secretBytes = new byte[32];
        new SecureRandom().nextBytes(secretBytes);
        return Base64.getEncoder().encodeToString(secretBytes);
    }

    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("carddemo")
                    .withUsername("carddemo")
                    .withPassword("carddemo");

    static final LocalStackContainer LOCALSTACK = createLocalStack();

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
            LOCALSTACK.start();
            provisionAwsResources();
        }
    }

    private static LocalStackContainer createLocalStack() {
        LocalStackContainer container =
                new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.5.0"))
                        .withEnv("SERVICES", "s3,sqs,sns");
        String authToken = System.getenv("LOCALSTACK_AUTH_TOKEN");
        if (authToken != null && !authToken.isBlank()) {
            container.withEnv("LOCALSTACK_AUTH_TOKEN", authToken);
        }
        return container;
    }

    private static void provisionAwsResources() {
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
     * Deletes the SQS FIFO queue, the SNS topic, and the three S3 buckets created by
     * {@link #provisionAwsResources()}, leaving the LocalStack container empty of CardDemo resources.
     *
     * <p>This is the symmetric counterpart to {@link #provisionAwsResources()} and is provided for an
     * IT that runs its <em>own non-shared</em> LocalStack container: such a test can call this from an
     * {@code @AfterAll} to provision and tear down its resources within a single class. It must
     * <strong>not</strong> be wired into an {@code @AfterAll} against the shared singleton container,
     * which other IT classes reuse — for the shared container, teardown is performed by the Ryuk
     * reaper at JVM exit (see the class documentation). Deletion is performed in the container with
     * the {@code awslocal} CLI: the queue URL is resolved before deletion, the deterministic LocalStack
     * topic ARN is constructed directly, and each bucket is removed with its objects via
     * {@code s3 rb --force}.
     */
    protected static void deprovisionAwsResources() {
        try {
            // SQS FIFO queue: delete-queue requires the queue URL, so resolve it in-container first.
            LOCALSTACK.execInContainer("sh", "-c",
                    "awslocal sqs delete-queue --queue-url \"$(awslocal sqs get-queue-url --queue-name "
                            + REPORT_QUEUE + " --output text)\"");
            // SNS topic: LocalStack ARNs are deterministic (account 000000000000), so no lookup is needed.
            String topicArn = "arn:aws:sns:" + LOCALSTACK.getRegion() + ":000000000000:" + NOTIFICATIONS_TOPIC;
            LOCALSTACK.execInContainer("awslocal", "sns", "delete-topic", "--topic-arn", topicArn);
            // S3 buckets: 'rb --force' removes each bucket together with any objects it still holds.
            LOCALSTACK.execInContainer("awslocal", "s3", "rb", "s3://" + BUCKET_INPUT, "--force");
            LOCALSTACK.execInContainer("awslocal", "s3", "rb", "s3://" + BUCKET_OUTPUT, "--force");
            LOCALSTACK.execInContainer("awslocal", "s3", "rb", "s3://" + BUCKET_STATEMENTS, "--force");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deprovision LocalStack AWS resources", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while deprovisioning LocalStack AWS resources", e);
        }
    }

    @DynamicPropertySource
    static void awsAndDatasourceProperties(DynamicPropertyRegistry registry) {
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
    }

    /**
     * Awaits an AWS SDK v2 asynchronous response with a bounded timeout, normalising checked
     * concurrency exceptions into an unchecked failure so test code stays concise.
     */
    protected static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting AWS async response", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("AWS async operation did not complete successfully", e);
        }
    }
}

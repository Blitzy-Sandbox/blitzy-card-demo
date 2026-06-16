package com.carddemo.integration.aws;

import java.io.IOException;
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
            Runtime.getRuntime().addShutdownHook(
                    new Thread(AbstractAwsLocalStackIT::tearDownSharedResources, "localstack-aws-it-teardown"));
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
     * Tears down the shared test infrastructure at JVM exit: a best-effort deletion of every
     * provisioned AWS resource (the three S3 buckets and their objects, the SQS FIFO queue, and the
     * SNS topic) followed by stopping both containers. This satisfies the LocalStack Verification
     * rule that integration tests provision <em>and</em> tear down their own resources.
     *
     * <p>Teardown is registered as a JVM shutdown hook rather than an {@code @AfterAll} method
     * because {@link #POSTGRES} and {@link #LOCALSTACK} are shared static singletons reused across
     * every integration subclass in the JVM: an {@code @AfterAll} would stop them after the first
     * subclass and break the remaining ones. The hook runs exactly once, after the last test in the
     * JVM. Every step is isolated in its own best-effort guard so a cleanup failure can never fail
     * the build or mask a test result.</p>
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

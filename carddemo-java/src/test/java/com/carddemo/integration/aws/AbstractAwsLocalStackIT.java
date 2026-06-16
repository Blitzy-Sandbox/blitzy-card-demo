package com.carddemo.integration.aws;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
 * <p>The shared containers and AWS resources are provisioned and torn down through an explicit
 * JUnit 5 lifecycle rather than a static initializer plus a JVM shutdown hook. The suite-level
 * {@link SharedAwsResourcesExtension} provisions exactly once (the first integration class that
 * runs) and registers an {@link ExtensionContext.Store.CloseableResource} in the <em>root</em>
 * store, so teardown runs once at the end of the whole test suite and, unlike a shutdown hook,
 * <strong>fails the build deterministically</strong> if cleanup breaks. Provisioning is also
 * triggered defensively from {@link #awsAndDatasourceProperties(DynamicPropertyRegistry)} so the
 * container endpoints are always available when Spring resolves the dynamic properties, regardless
 * of callback ordering.</p>
 */
@SpringBootTest
@Import(SqsListenerAutoStartupDisabledConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(AbstractAwsLocalStackIT.SharedAwsResourcesExtension.class)
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

    /**
     * Guards one-time startup/provisioning. {@code volatile} so the value written under the
     * {@link #ensureStarted()} monitor is visible to the defensive call from the
     * {@code @DynamicPropertySource} method (which may run on a different thread).
     */
    private static volatile boolean started;

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

    /**
     * Idempotently starts the shared containers and provisions the shared AWS resources. Invoked by
     * the suite extension's {@link SharedAwsResourcesExtension#beforeAll(ExtensionContext)} and,
     * defensively, by the {@code @DynamicPropertySource} method so container endpoints exist before
     * Spring resolves them. Synchronized on the class monitor so concurrent test-engine threads
     * provision exactly once. A provisioning failure propagates as an unchecked exception and fails
     * the build deterministically.
     */
    private static synchronized void ensureStarted() {
        if (started) {
            return;
        }
        POSTGRES.start();
        LOCALSTACK.start();
        provisionAwsResources();
        started = true;
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
     * Tears down the shared test infrastructure once, at the end of the whole suite: deletes every
     * provisioned AWS resource (the three S3 buckets and their objects, the SQS FIFO queue, and the
     * SNS topic) <em>before</em> stopping both containers, then re-throws an aggregated failure if
     * any step failed. This satisfies the LocalStack Verification rule that integration tests
     * provision <em>and</em> tear down their own resources, and — unlike the former JVM shutdown
     * hook — makes a cleanup failure fail the build.
     *
     * <p>A genuine infrastructure exception (the container cannot be reached, or a container stop
     * throws) is recorded and re-thrown; a non-zero {@code awslocal} exit code (for example deleting
     * a resource that no longer exists) is tolerated because {@code execInContainer} returns
     * normally for it. Every step runs even when an earlier one fails, so all failures are reported
     * together and the containers are always stopped.</p>
     *
     * @throws Exception an aggregate carrying every recorded cleanup failure as a suppressed cause
     */
    private static void tearDownSharedResources() throws Exception {
        List<Exception> failures = new ArrayList<>();
        if (LOCALSTACK.isRunning()) {
            deleteBucket(BUCKET_INPUT, failures);
            deleteBucket(BUCKET_OUTPUT, failures);
            deleteBucket(BUCKET_STATEMENTS, failures);
            exec(failures, "sh", "-c",
                    "awslocal sqs delete-queue --queue-url "
                            + "$(awslocal sqs get-queue-url --queue-name " + REPORT_QUEUE
                            + " --query QueueUrl --output text)");
            exec(failures, "sh", "-c",
                    "awslocal sns delete-topic --topic-arn "
                            + "$(awslocal sns list-topics --output text | grep " + NOTIFICATIONS_TOPIC
                            + " | awk '{print $2}')");
        }
        stopContainers(failures);
        if (!failures.isEmpty()) {
            IllegalStateException aggregate = new IllegalStateException(
                    "Failed to tear down " + failures.size() + " shared LocalStack AWS resource(s)");
            failures.forEach(aggregate::addSuppressed);
            throw aggregate;
        }
    }

    /** Removes an S3 bucket and all of its objects (recursive {@code rb --force}). */
    private static void deleteBucket(String bucket, List<Exception> failures) {
        exec(failures, "awslocal", "s3", "rb", "s3://" + bucket, "--force");
    }

    /**
     * Runs a command inside the LocalStack container, recording only genuine infrastructure
     * exceptions (the container could not be reached). A non-zero exit code returns normally and is
     * tolerated, because cleanup of an already-absent resource is not a failure.
     */
    private static void exec(List<Exception> failures, String... command) {
        try {
            LOCALSTACK.execInContainer(command);
        } catch (IOException | RuntimeException e) {
            failures.add(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failures.add(e);
        }
    }

    /** Stops both shared containers, recording any stop failure so teardown can fail the build. */
    private static void stopContainers(List<Exception> failures) {
        try {
            if (LOCALSTACK.isRunning()) {
                LOCALSTACK.stop();
            }
        } catch (RuntimeException e) {
            failures.add(e);
        }
        try {
            if (POSTGRES.isRunning()) {
                POSTGRES.stop();
            }
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }

    @DynamicPropertySource
    static void awsAndDatasourceProperties(DynamicPropertyRegistry registry) {
        ensureStarted();
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

    /**
     * Suite-level JUnit 5 extension that provisions the shared containers and AWS resources exactly
     * once and registers a {@link ExtensionContext.Store.CloseableResource} in the root store so the
     * deterministic teardown runs after the entire suite. When Docker is unavailable the
     * {@code @Testcontainers(disabledWithoutDocker = true)} gate disables the tests, so the
     * extension simply returns without provisioning.
     */
    static final class SharedAwsResourcesExtension implements BeforeAllCallback {

        private static final ExtensionContext.Namespace NAMESPACE =
                ExtensionContext.Namespace.create(SharedAwsResourcesExtension.class);
        private static final String RESOURCE_KEY = "carddemo-shared-localstack-resources";

        @Override
        public void beforeAll(ExtensionContext context) {
            if (!DockerClientFactory.instance().isDockerAvailable()) {
                return;
            }
            context.getRoot()
                    .getStore(NAMESPACE)
                    .getOrComputeIfAbsent(
                            RESOURCE_KEY,
                            key -> {
                                ensureStarted();
                                return new SharedResourcesCloseable();
                            },
                            SharedResourcesCloseable.class);
        }
    }

    /**
     * Root-store closeable whose {@link #close()} performs the one-time deterministic teardown of
     * all shared resources at the end of the whole test suite, failing the build on any cleanup
     * error.
     */
    static final class SharedResourcesCloseable implements ExtensionContext.Store.CloseableResource {
        @Override
        public void close() throws Exception {
            tearDownSharedResources();
        }
    }
}

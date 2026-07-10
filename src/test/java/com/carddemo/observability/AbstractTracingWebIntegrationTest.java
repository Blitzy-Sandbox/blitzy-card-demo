package com.carddemo.observability;

import java.time.Duration;
import java.util.function.Predicate;

import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Shared Testcontainers + LocalStack base for the CardDemo <strong>distributed-tracing</strong>
 * integration tests, which boot the full web application with tracing <em>enabled</em> and capture the
 * emitted OpenTelemetry spans in-process to prove trace continuity across the
 * <strong>REST&nbsp;&rarr;&nbsp;service&nbsp;&rarr;&nbsp;JDBC&nbsp;&rarr;&nbsp;AWS</strong> boundaries
 * (QA finding <strong>F-2</strong>) and the 5xx error-status enrichment (QA finding <strong>A-2</strong>).
 *
 * <h2>Why tracing must be force-enabled here</h2>
 * <p>The {@code test} profile ({@code application-test.yml}) sets {@code management.tracing.enabled=false}
 * so the ordinary suite never dials an OTLP collector. This base re-enables tracing for its subclasses via
 * {@link SpringBootTest#properties()} ({@code management.tracing.enabled=true} +
 * {@code management.tracing.sampling.probability=1.0}) so Spring Boot creates the OpenTelemetry SDK,
 * the {@code Tracer}, and the Micrometer&nbsp;&rarr;&nbsp;OTel {@code ObservationHandler} that turns the
 * {@code @Observed} service methods into spans. OTLP <em>export</em> stays disabled (inherited from the
 * profile), so no Jaeger/collector is contacted; spans are instead captured by an in-memory
 * {@link InMemorySpanCollector} wired as a synchronous {@code SimpleSpanProcessor}
 * ({@link InMemoryTracingTestConfig}). This is fully hermetic &mdash; the same zero-live-AWS,
 * container-only philosophy as the rest of the suite (AAP&nbsp;&sect;0.7.7).</p>
 *
 * <h2>Infrastructure</h2>
 * <p>Uses the Testcontainers singleton-container pattern (static PostgreSQL&nbsp;16 + LocalStack started
 * once and reaped by Ryuk at JVM exit) mirroring {@code com.carddemo.batch.AbstractBatchIntegrationTest}
 * and {@code PrometheusMetricsIT}, so the context starts against a real database (Flyway
 * {@code V1}&rarr;{@code V2}&rarr;{@code V3}) and real LocalStack (S3/SQS/SNS) with dummy
 * {@code test}/{@code test} credentials. The Testcontainers&nbsp;2.0 module classes
 * ({@link PostgreSQLContainer}, {@link LocalStackContainer}) are used so the suite stays warning-free
 * under {@code -Xlint:all} (Gate&nbsp;2).</p>
 *
 * <p>This base is {@code abstract} and declares no {@code @Test} method, so neither Surefire nor Failsafe
 * executes it directly. Source COBOL/JCL is referenced read-only at commit SHA {@code 27d6c6f}; design
 * rationale lives in {@code docs/decision-log.md}, not in these comments (Explainability rule).</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Re-enable tracing for these tests only (the test profile disables it). OTLP export
                // stays OFF (inherited), so spans flow only to the in-memory collector below.
                "management.tracing.enabled=true",
                "management.tracing.sampling.probability=1.0"
        })
@ActiveProfiles("test")
@Testcontainers
@Import(AbstractTracingWebIntegrationTest.InMemoryTracingTestConfig.class)
public abstract class AbstractTracingWebIntegrationTest {

    // -----------------------------------------------------------------------------------------------
    // Container images and AWS test constants (mirror AbstractBatchIntegrationTest / PrometheusMetricsIT
    // so the wired app resolves the same coordinates it does across the integration suite).
    // -----------------------------------------------------------------------------------------------

    /** PostgreSQL image; pinned to {@code postgres:16} to match {@code docker-compose.yml} and the cached image. */
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
    // Span-await tuning. Spans finish on request/SDK threads slightly after the client call returns, so
    // assertions poll (no Thread.sleep) under a short bounded timeout.
    // -----------------------------------------------------------------------------------------------

    /** Maximum time to wait for an expected span to be exported to the in-memory collector. */
    protected static final Duration SPAN_AWAIT_TIMEOUT = Duration.ofSeconds(15);

    /** Poll cadence while awaiting an expected span. */
    protected static final Duration SPAN_POLL_INTERVAL = Duration.ofMillis(100);

    // -----------------------------------------------------------------------------------------------
    // Shared singleton containers — started once, reaped by Ryuk at JVM exit (no @Container / no stop()).
    // -----------------------------------------------------------------------------------------------

    /** Shared PostgreSQL&nbsp;16 container (Testcontainers&nbsp;2.0 module class; non-deprecated, warning-free). */
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName(DB_NAME)
            .withUsername(DB_USERNAME)
            .withPassword(DB_PASSWORD);

    /** Shared LocalStack container exposing S3/SQS/SNS (Testcontainers&nbsp;2.0 module class). */
    protected static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE))
                    .withServices("s3", "sqs", "sns");

    static {
        POSTGRES.start();
        LOCALSTACK.start();
    }

    /**
     * Registers the container-derived Spring properties (JDBC coordinates and the LocalStack endpoint)
     * plus the region, dummy credentials, and S3 path-style flag &mdash; identical to the batch
     * integration base so the full web context wires against LocalStack and never reaches live AWS.
     *
     * @param registry the Spring test registry that receives the lazily-evaluated property suppliers
     */
    @DynamicPropertySource
    static void registerDynamicProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.cloud.aws.region.static", () -> AWS_REGION);
        registry.add("spring.cloud.aws.credentials.access-key", () -> AWS_ACCESS_KEY);
        registry.add("spring.cloud.aws.credentials.secret-key", () -> AWS_SECRET_KEY);

        registry.add("spring.cloud.aws.endpoint", AbstractTracingWebIntegrationTest::localstackEndpoint);
        registry.add("spring.cloud.aws.s3.endpoint", AbstractTracingWebIntegrationTest::localstackEndpoint);
        registry.add("spring.cloud.aws.sqs.endpoint", AbstractTracingWebIntegrationTest::localstackEndpoint);
        registry.add("spring.cloud.aws.sns.endpoint", AbstractTracingWebIntegrationTest::localstackEndpoint);

        registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> Boolean.TRUE);
    }

    /**
     * Resolves the LocalStack edge endpoint as a string (evaluated lazily, after {@code LOCALSTACK.start()}).
     *
     * @return the LocalStack endpoint URI rendered as a string
     */
    private static String localstackEndpoint() {
        return LOCALSTACK.getEndpoint().toString();
    }

    // -----------------------------------------------------------------------------------------------
    // Injected collaborators shared by every tracing IT.
    // -----------------------------------------------------------------------------------------------

    /** {@link TestRestTemplate} bound to the embedded server's random port (relative URLs resolve to it). */
    @Autowired
    protected TestRestTemplate restTemplate;

    /** The in-memory span sink wired as a synchronous {@code SpanProcessor} (see {@link InMemoryTracingTestConfig}). */
    @Autowired
    protected InMemorySpanCollector spanCollector;

    /** Auto-configured, LocalStack-backed S3 client &mdash; the very bean the OTel S3 customizer instruments. */
    @Autowired
    protected S3Client s3Client;

    // ===============================================================================================
    // Span assertion helpers.
    // ===============================================================================================

    /**
     * Discards all previously captured spans so a test asserts only on the action under test. Call this
     * immediately before the request/operation whose spans are being verified.
     */
    protected void resetSpans() {
        spanCollector.reset();
    }

    /**
     * Polls the in-memory collector (no {@code Thread.sleep}) until at least one captured span matches the
     * predicate, then returns the first such span.
     *
     * @param description human-readable description of the awaited span (used in the timeout message)
     * @param matcher     the span predicate to satisfy
     * @return the first captured span matching {@code matcher}
     * @throws org.awaitility.core.ConditionTimeoutException if no matching span appears within the timeout
     */
    protected SpanData awaitSpan(final String description, final Predicate<SpanData> matcher) {
        Awaitility.await(description)
                .atMost(SPAN_AWAIT_TIMEOUT)
                .pollInterval(SPAN_POLL_INTERVAL)
                .until(() -> spanCollector.getFinishedSpans().stream().anyMatch(matcher));
        return spanCollector.getFinishedSpans().stream()
                .filter(matcher)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no captured span matched: " + description));
    }

    /**
     * Predicate factory: a span of the given {@link SpanKind} whose (lower-cased) name contains the given
     * fragment. Matching on name substrings keeps assertions robust against exact naming conventions that
     * differ slightly across instrumentation versions.
     *
     * @param kind            the required span kind (SERVER / CLIENT / INTERNAL / PRODUCER / CONSUMER)
     * @param nameFragmentLc  a lower-cased fragment that the span name must contain
     * @return a predicate matching such spans
     */
    protected static Predicate<SpanData> spanOfKindWithName(final SpanKind kind, final String nameFragmentLc) {
        return span -> span.getKind() == kind
                && span.getName() != null
                && span.getName().toLowerCase(java.util.Locale.ROOT).contains(nameFragmentLc);
    }

    // ===============================================================================================
    // Minimal S3 self-provisioning helpers (AAP §0.7.7) used by the AWS-boundary span test.
    // ===============================================================================================

    /**
     * Creates an S3 bucket in LocalStack.
     *
     * @param bucket the bucket name to create; must not be {@code null}
     */
    protected void createBucket(final String bucket) {
        s3Client.createBucket(request -> request.bucket(bucket));
    }

    /**
     * Uploads a byte payload to S3 through the (instrumented) S3 client, producing an S3 client span.
     *
     * @param bucket  the destination bucket (must already exist); must not be {@code null}
     * @param key     the object key; must not be {@code null}
     * @param content the exact bytes to store; must not be {@code null}
     */
    protected void putObject(final String bucket, final String key, final byte[] content) {
        s3Client.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromBytes(content));
    }

    /**
     * Deletes an S3 bucket after removing every object it contains. Idempotent: a missing bucket is
     * treated as already-gone so teardown never fails a test.
     *
     * @param bucket the bucket to delete recursively; must not be {@code null}
     */
    protected void deleteBucketRecursively(final String bucket) {
        try {
            for (final S3Object object : s3Client.listObjectsV2Paginator(request -> request.bucket(bucket)).contents()) {
                s3Client.deleteObject(request -> request.bucket(bucket).key(object.key()));
            }
            s3Client.deleteBucket(request -> request.bucket(bucket));
        } catch (final NoSuchBucketException ignored) {
            // Idempotent teardown: nothing to clean up.
        }
    }

    // ===============================================================================================
    // Test configuration: capture spans in-process instead of exporting them over OTLP.
    // ===============================================================================================

    /**
     * Registers the {@link InMemorySpanCollector} and wraps it in a synchronous {@code SimpleSpanProcessor}
     * bean. Spring Boot's {@code OpenTelemetryAutoConfiguration} collects every {@code SpanProcessor} bean
     * into the {@code SdkTracerProvider}, so this processor receives every finished span (service,
     * JDBC, AWS, and HTTP-server spans) with no external collector.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryTracingTestConfig {

        /**
         * The in-memory span sink, also injected into tests for assertions.
         *
         * @return a fresh collector
         */
        @Bean
        InMemorySpanCollector inMemorySpanCollector() {
            return new InMemorySpanCollector();
        }

        /**
         * A synchronous processor that exports each finished span to the in-memory collector as soon as it
         * ends, so tests can poll for spans deterministically.
         *
         * @param collector the in-memory sink
         * @return a {@code SimpleSpanProcessor} over the collector
         */
        @Bean
        SpanProcessor inMemorySpanProcessor(final InMemorySpanCollector collector) {
            return SimpleSpanProcessor.create(collector);
        }
    }
}

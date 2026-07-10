package com.carddemo.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.dto.SignonRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end observability integration test that boots the <strong>full CardDemo web application</strong>
 * (embedded server on a random port) and scrapes the live Micrometer/Prometheus endpoint over HTTP
 * exactly as Prometheus would, then asserts that <em>every</em> metric series referenced by the
 * committed Grafana dashboard ({@code grafana/dashboards/carddemo-dashboard.json}) is actually exposed.
 *
 * <h2>Why this test exists (QA finding F-3, AAP&nbsp;&sect;0.7.1 &mdash; Observability)</h2>
 * <p>The QA telemetry-correctness checkpoint found the Grafana dashboard referencing PromQL series that
 * the application never emitted (a dashboard&nbsp;&harr;&nbsp;application <em>contract</em> break): the
 * auth-attempt counter and the transaction-amount summary did not exist, the batch-throughput/reject
 * panels named the wrong series, and the HTTP latency histogram buckets were not enabled. This test is
 * the permanent runtime guard for that contract: it fails if any dashboard-referenced series stops being
 * exported, so a future regression cannot silently reintroduce a "dashboard shows No&nbsp;data" defect.</p>
 *
 * <h2>How the assertions are made deterministic</h2>
 * <ul>
 *   <li>The four <em>custom</em> metric families are pre-registered at value&nbsp;0 in their owning beans'
 *       constructors at context start &mdash; {@code carddemo_transactions_posted_total} and
 *       {@code carddemo_transactions_rejected_total} by {@code MetricsConfig},
 *       {@code carddemo_auth_attempts_total} (both {@code result} tags) by {@code SignonService}, and
 *       {@code carddemo_transaction_amount_*} by {@code TransactionAddService}. They are therefore present
 *       in the exposition immediately after boot, independent of any traffic.</li>
 *   <li>The one <em>lazy</em> family, {@code http_server_requests_seconds_bucket}, is created only after
 *       the first HTTP request completes. This test drives exactly one such request &mdash; a login with a
 *       well-formed but non-existent credential pair &mdash; which (a) creates that timer with native
 *       histogram buckets (enabled via {@code management.metrics.distribution.percentiles-histogram} in
 *       {@code application.yml}) and (b) travels the real end-to-end auth path, so
 *       {@code carddemo_auth_attempts_total{result="failure"}} is incremented to a non-zero value,
 *       proving the counter is wired into production code rather than merely declared.</li>
 * </ul>
 *
 * <h2>Infrastructure</h2>
 * <p>Uses the Testcontainers singleton-container pattern (static PostgreSQL&nbsp;16 + LocalStack started
 * once in a {@code static} initializer, reaped by Ryuk at JVM exit) mirroring
 * {@code com.carddemo.batch.AbstractBatchIntegrationTest}, so the full application context starts against
 * a real database (Flyway {@code V1}&rarr;{@code V2}&rarr;{@code V3}) and real LocalStack with
 * <strong>zero live AWS</strong> and dummy {@code test}/{@code test} credentials (AAP&nbsp;&sect;0.7.7).
 * The {@code test} profile disables tracing and sets the SQS {@code queue-not-found-strategy=create}, so
 * the report-launcher {@code @SqsListener} auto-creates its FIFO queue and the context boots cleanly. The
 * Testcontainers&nbsp;2.0 module classes ({@link PostgreSQLContainer}, {@link LocalStackContainer}) are
 * used so the suite stays warning-free under {@code -Xlint:all} (Gate&nbsp;2).</p>
 *
 * <p>Source COBOL/JCL is referenced read-only at commit SHA {@code 27d6c6f}; it is not copied here.
 * Design rationale lives in {@code docs/decision-log.md}, not in these comments (Explainability rule).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@DisplayName("F-3: /actuator/prometheus exposes every Grafana-dashboard-referenced metric series")
class PrometheusMetricsIT {

    // -----------------------------------------------------------------------------------------------
    // Container images and AWS test constants (mirror AbstractBatchIntegrationTest so the wired app
    // resolves the same coordinates it does in the batch integration suite).
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
    // Login payload: well-formed (both fields non-blank and <= 8 chars, satisfying SignonRequest bean
    // validation) but referencing a user that does not exist in the V3 seed data, so SignonService
    // takes the "user not found" branch -> BadCredentialsException -> HTTP 401, incrementing
    // carddemo_auth_attempts_total{result="failure"} exactly once.
    // -----------------------------------------------------------------------------------------------

    /** Non-existent, 8-character user id (passes {@code @Size(max = 8)}; absent from the seed data). */
    private static final String BOGUS_USER = "NOUSER99";

    /** Well-formed, 8-character password (passes {@code @Size(max = 8)}; never checked because the user is absent). */
    private static final String BOGUS_PASSWORD = "NOPASS99";

    // -----------------------------------------------------------------------------------------------
    // Shared singleton containers — started once, reaped by Ryuk at JVM exit (no @Container / no stop()).
    // -----------------------------------------------------------------------------------------------

    /** Shared PostgreSQL&nbsp;16 container (Testcontainers&nbsp;2.0 module class; non-deprecated, warning-free). */
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName(DB_NAME)
            .withUsername(DB_USERNAME)
            .withPassword(DB_PASSWORD);

    /** Shared LocalStack container exposing S3/SQS/SNS (Testcontainers&nbsp;2.0 module class). */
    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE))
                    .withServices("s3", "sqs", "sns");

    static {
        POSTGRES.start();
        LOCALSTACK.start();
    }

    /**
     * Registers the container-derived Spring properties that cannot be hard-coded in
     * {@code application-test.yml} (the JDBC coordinates and the LocalStack endpoint), plus the region,
     * dummy credentials, and S3 path-style flag &mdash; identical to the batch integration base so the
     * full web context wires against LocalStack and never reaches live AWS.
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

        registry.add("spring.cloud.aws.endpoint", PrometheusMetricsIT::localstackEndpoint);
        registry.add("spring.cloud.aws.s3.endpoint", PrometheusMetricsIT::localstackEndpoint);
        registry.add("spring.cloud.aws.sqs.endpoint", PrometheusMetricsIT::localstackEndpoint);
        registry.add("spring.cloud.aws.sns.endpoint", PrometheusMetricsIT::localstackEndpoint);

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

    /**
     * Auto-configured {@link TestRestTemplate} pre-bound to the embedded server's random port (relative
     * URLs such as {@code /actuator/prometheus} resolve against {@code http://localhost:<randomPort>}).
     */
    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * Boots the whole application, drives one real HTTP login (creating the HTTP timer and incrementing the
     * auth-failure counter), then scrapes {@code /actuator/prometheus} and asserts every metric series the
     * committed Grafana dashboard queries is present in the exposition.
     */
    @Test
    @DisplayName("every dashboard PromQL series is exported by the live application")
    void prometheusEndpointExposesEveryDashboardReferencedSeries() {
        // --- Drive one real end-to-end request so the lazy HTTP timer is created and the auth path runs. ---
        final ResponseEntity<String> login = restTemplate.postForEntity(
                "/api/auth/login",
                new SignonRequest(BOGUS_USER, BOGUS_PASSWORD),
                String.class);
        assertThat(login.getStatusCode())
                .as("A well-formed but non-existent credential must be rejected as HTTP 401 (BadCredentialsException)")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // --- Scrape the live Prometheus exposition exactly as the Prometheus server would. ---
        final ResponseEntity<String> scrape = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(scrape.getStatusCode())
                .as("/actuator/prometheus must be publicly scrapeable (permitAll in SecurityConfig)")
                .isEqualTo(HttpStatus.OK);
        final String body = scrape.getBody();
        assertThat(body).as("Prometheus exposition body must not be empty").isNotNull().isNotEmpty();

        // --- MetricsConfig common tag: proves the registry customizer applied the application tag. ---
        assertThat(body)
                .as("MetricsConfig must stamp the common application=\"carddemo\" tag on every meter")
                .contains("application=\"carddemo\"");

        // --- Panel 4 — HTTP request latency: histogram buckets must be enabled and emitted. ---
        assertThat(body)
                .as("Panel 4 (HTTP p95 latency) requires http_server_requests_seconds_bucket, which only "
                        + "exists when percentiles-histogram is enabled in application.yml")
                .contains("http_server_requests_seconds_bucket");

        // --- Panel 6 — batch throughput: renamed to the series the application actually emits. ---
        assertThat(body)
                .as("Panel 6 (batch throughput) queries carddemo_transactions_posted_total")
                .contains("carddemo_transactions_posted_total");

        // --- Panel 7 — batch rejects: renamed to the series the application actually emits. ---
        assertThat(body)
                .as("Panel 7 (batch rejects) queries carddemo_transactions_rejected_total")
                .contains("carddemo_transactions_rejected_total");

        // --- Panel 13 — auth attempts: BOTH result series must exist, and the failure series must have
        //     actually incremented (value >= 1), proving the counter is wired into the live auth path. ---
        assertThat(body)
                .as("Panel 13 (auth attempts) requires the result=\"success\" series")
                .containsPattern("carddemo_auth_attempts_total\\{[^}]*result=\"success\"");
        assertThat(body)
                .as("Panel 13 (auth attempts) requires the result=\"failure\" series, incremented to >= 1 "
                        + "by the bogus login above")
                .containsPattern("carddemo_auth_attempts_total\\{[^}]*result=\"failure\"[^}]*\\}\\s+[1-9]");

        // --- Panel 14 — average transaction amount: the DistributionSummary's _count and _sum series
        //     (correct Prometheus names, no base-unit suffix) must be exported. ---
        assertThat(body)
                .as("Panel 14 (avg transaction amount) requires the summary _count series")
                .contains("carddemo_transaction_amount_count");
        assertThat(body)
                .as("Panel 14 (avg transaction amount) requires the summary _sum series")
                .contains("carddemo_transaction_amount_sum");
    }
}

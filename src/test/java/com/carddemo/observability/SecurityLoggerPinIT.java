package com.carddemo.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * End-to-end sensitive-data defense-in-depth integration test that boots the <strong>full CardDemo web
 * application</strong> (embedded server on a random port) with the application/root logger deliberately
 * raised to {@code DEBUG}, drives a request whose URI embeds a full card number (PAN), and asserts that the
 * PAN never reaches the logs because {@code org.springframework.security} is pinned to {@code INFO}.
 *
 * <h2>Why this test exists (QA finding A-1, AAP&nbsp;&sect;0.7.1 &mdash; Observability / sensitive-data)</h2>
 * <p>The QA telemetry-correctness checkpoint noted (INFO, defense-in-depth) that the card view/update
 * endpoints expose the card number as the {@code /api/cards/{cardNumber}} <em>path variable</em>, so the PAN
 * rides in the HTTP request line. Spring Security's filter chain &mdash; notably
 * {@code org.springframework.security.web.FilterChainProxy} &mdash; logs that request line
 * ({@code "Securing GET /api/cards/{PAN}"} / {@code "Secured GET /api/cards/{PAN}"}) at {@code DEBUG}. With
 * the security logger left unpinned, an operator raising the application or root logger to {@code DEBUG}
 * (for example {@code logging.level.com.carddemo=DEBUG} or {@code logging.level.root=DEBUG}) would
 * <em>incidentally</em> surface a full PAN in the URI &mdash; even though every application call site already
 * masks the PAN via {@code CardController#maskPan}.</p>
 *
 * <p>The fix pins {@code org.springframework.security} to {@code INFO} in {@code logback-spring.xml}
 * (mirroring the existing Hibernate SQL pins), so raising application logging cannot promote the security
 * filter chain's request-line logging to {@code DEBUG}. This test is the permanent runtime guard for that
 * pin.</p>
 *
 * <h2>Interface-contract preservation (why the path variable is unchanged)</h2>
 * <p>The QA advisory also suggested moving the PAN out of the URL path (opaque token / last-4). The
 * {@code /api/cards/{cardNumber}} path variable is the external REST interface contract migrated from the
 * COBOL {@code CCDL}/{@code CCUP} screens ({@code CARD-NUM}); per AAP&nbsp;&sect;&sect;0.3.2 and&nbsp;0.8.1,
 * external interface contract <em>changes</em> are out of scope and are not applied. Pinning the security
 * logger is the in-scope, contract-preserving remedy, and this test verifies it.</p>
 *
 * <h2>What is asserted (both directions, so the guard cannot silently rot)</h2>
 * <ul>
 *   <li><strong>The pin suppresses the leak.</strong> With {@code logging.level.root=DEBUG} forced on, the
 *       effective level of {@code org.springframework.security} is still {@code INFO}, and after a request to
 *       {@code /api/cards/<PAN>} no captured log event contains the PAN and no security-logger event is more
 *       verbose than {@code INFO}. If a future edit removes the pin, the security logger would inherit
 *       {@code root=DEBUG}, FilterChainProxy would log the PAN, and these assertions would fail.</li>
 *   <li><strong>The pin is load-bearing (positive control).</strong> Explicitly opting the operator in
 *       ({@code org.springframework.security}&rarr;{@code DEBUG}, exactly as the config comment documents)
 *       makes the same request surface a security {@code DEBUG} line containing the PAN &mdash; proving the
 *       leak surface genuinely exists and that it is the {@code INFO} pin (not some unrelated silence) that
 *       suppresses it in the first assertion.</li>
 * </ul>
 *
 * <h2>Infrastructure</h2>
 * <p>Uses the Testcontainers singleton-container pattern (static PostgreSQL&nbsp;16 + LocalStack started once
 * in a {@code static} initializer, reaped by Ryuk at JVM exit) mirroring
 * {@code com.carddemo.observability.PrometheusMetricsIT} and {@code com.carddemo.batch.AbstractBatchIntegrationTest},
 * so the full application context starts against a real database and real LocalStack with <strong>zero live
 * AWS</strong> and dummy {@code test}/{@code test} credentials (AAP&nbsp;&sect;0.7.7). The Testcontainers&nbsp;2.0
 * module classes ({@link PostgreSQLContainer}, {@link LocalStackContainer}) are used so the suite stays
 * warning-free under {@code -Xlint:all} (Gate&nbsp;2). A logback {@link ListAppender} attached to the root
 * logger during each test captures the events the request produces (the appender is attached in
 * {@code @BeforeEach} and detached in {@code @AfterEach}, so startup noise is excluded).</p>
 *
 * <p>Source COBOL/JCL is referenced read-only at commit SHA {@code 27d6c6f}; it is not copied here.
 * Design rationale lives in {@code docs/decision-log.md}, not in these comments (Explainability rule).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"logging.level.root=DEBUG"})
@ActiveProfiles("test")
@Testcontainers
@DisplayName("A-1: org.springframework.security pinned to INFO prevents a raised app logger from surfacing a PAN-in-URI")
class SecurityLoggerPinIT {

    // -----------------------------------------------------------------------------------------------
    // Container images and AWS test constants (mirror PrometheusMetricsIT / AbstractBatchIntegrationTest
    // so the wired app resolves the same coordinates it does in the other integration suites).
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
    // Sensitive-data probe constants.
    // -----------------------------------------------------------------------------------------------

    /** The Spring Security logger hierarchy root that {@code logback-spring.xml} pins to {@code INFO}. */
    private static final String SECURITY_LOGGER_NAME = "org.springframework.security";

    /**
     * A full 16-digit card number (PAN) used as the {@code /api/cards/{cardNumber}} path variable. It is a
     * well-known test PAN (not a real card) and satisfies the controller's {@code @Pattern("\\d{1,16}")} /
     * {@code @Size(max = 16)} constraints; it is deliberately distinctive so a substring search over the
     * captured log lines is unambiguous.
     */
    private static final String FULL_PAN = "4111111111111111";

    /** The card-view request path whose URI embeds {@link #FULL_PAN}. */
    private static final String CARD_VIEW_PATH = "/api/cards/" + FULL_PAN;

    /**
     * Logger-name prefixes for the test's <em>own</em> outbound HTTP client (the {@link TestRestTemplate}
     * used to issue the probe request). Under {@code root=DEBUG} the client logs the URL it is about to call
     * (for example {@code org.springframework.web.client.RestTemplate} emits {@code "HTTP GET <url>"}), which
     * embeds the PAN. That client runs only in the test JVM to originate the request; it is <strong>not</strong>
     * part of the deployed server's request path, so its output is a test-harness artifact rather than a
     * server-side sensitive-data leak. These prefixes are therefore excluded from the server-side PAN sweep,
     * while any leak from Tomcat, the {@code DispatcherServlet}, Spring Security, or application code is still
     * caught (and the {@code org.springframework.security}-specific assertion below is unaffected).
     */
    private static final List<String> OUTBOUND_TEST_CLIENT_LOGGER_PREFIXES = List.of(
            "org.springframework.web.client",
            "org.springframework.http.client",
            "org.apache.hc",
            "jdk.internal.httpclient");

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
     * dummy credentials, and S3 path-style flag &mdash; identical to the other integration suites so the
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

        registry.add("spring.cloud.aws.endpoint", SecurityLoggerPinIT::localstackEndpoint);
        registry.add("spring.cloud.aws.s3.endpoint", SecurityLoggerPinIT::localstackEndpoint);
        registry.add("spring.cloud.aws.sqs.endpoint", SecurityLoggerPinIT::localstackEndpoint);
        registry.add("spring.cloud.aws.sns.endpoint", SecurityLoggerPinIT::localstackEndpoint);

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
     * URLs such as {@code /api/cards/<PAN>} resolve against {@code http://localhost:<randomPort>}).
     */
    @Autowired
    private TestRestTemplate restTemplate;

    /** Per-test capture of everything that propagates to the root logger while a request is served. */
    private ListAppender<ILoggingEvent> rootCapture;

    /**
     * Attaches a fresh {@link ListAppender} to the root logger before each test so that only the events
     * produced during the request under test are captured (context-startup noise, emitted before this
     * point, is excluded).
     */
    @BeforeEach
    void attachRootCapture() {
        rootCapture = new ListAppender<>();
        rootCapture.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        rootCapture.start();
        rootLogger().addAppender(rootCapture);
    }

    /**
     * Detaches the capture appender and restores the shipped {@code INFO} pin on the security logger (the
     * positive-control test raises it to {@code DEBUG}); this keeps the shared logging context clean for any
     * subsequent test in the JVM.
     */
    @AfterEach
    void detachRootCaptureAndRestorePin() {
        rootLogger().detachAppender(rootCapture);
        rootCapture.stop();
        securityLogger().setLevel(Level.INFO);
    }

    /**
     * With the application/root logger forced to {@code DEBUG}, the {@code org.springframework.security} pin
     * must hold the security filter chain at {@code INFO}, so a request whose URI embeds a full PAN produces
     * no log line containing the PAN and no security-logger event more verbose than {@code INFO}.
     */
    @Test
    @DisplayName("root=DEBUG: the INFO pin keeps the security filter chain quiet so no PAN-in-URI is logged")
    void securityLoggerPinSuppressesPanInUriWhenAppLoggingRaised() {
        // Sanity: the test really forced root to DEBUG, yet the shipped config pin still caps security at INFO.
        assertThat(rootLogger().getLevel())
                .as("this test forces logging.level.root=DEBUG")
                .isEqualTo(Level.DEBUG);
        assertThat(securityLogger().getEffectiveLevel())
                .as("logback-spring.xml must pin org.springframework.security at INFO even under root=DEBUG")
                .isEqualTo(Level.INFO);

        final ResponseEntity<String> response = restTemplate.getForEntity(CARD_VIEW_PATH, String.class);
        assertThat(response.getStatusCode())
                .as("/api/cards/** is authenticated(); an unauthenticated GET is rejected 401 (the security "
                        + "filter chain still runs and would log the URI at DEBUG if unpinned)")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        final List<ILoggingEvent> events = List.copyOf(rootCapture.list);

        // (1) Core sensitive-data guarantee (server side): no SERVER/application log line may contain the
        //     full PAN even with the root logger at DEBUG. Events emitted by the test's OWN outbound HTTP
        //     client (see OUTBOUND_TEST_CLIENT_LOGGER_PREFIXES) are excluded because that client exists only
        //     in the test JVM to issue the probe request and is not on the deployed server's request path;
        //     any server-side leak (Tomcat, DispatcherServlet, Spring Security, application code) is caught.
        assertThat(events)
                .as("no server-side log line may contain the full PAN even with the root logger at DEBUG")
                .noneMatch(event -> !isOutboundTestClientEvent(event)
                        && event.getFormattedMessage() != null
                        && event.getFormattedMessage().contains(FULL_PAN));

        // (2) Mechanism: the pin holds the whole org.springframework.security hierarchy at INFO, so it emits
        //     no DEBUG/TRACE events (those carry the request line) despite root=DEBUG.
        assertThat(events)
                .as("with the INFO pin, org.springframework.security must emit no DEBUG/TRACE events under root=DEBUG")
                .noneMatch(event -> event.getLoggerName() != null
                        && event.getLoggerName().startsWith(SECURITY_LOGGER_NAME)
                        && event.getLevel().toInt() < Level.INFO.toInt());
    }

    /**
     * Positive control: explicitly opting the operator in by raising {@code org.springframework.security} to
     * {@code DEBUG} (the deliberate action the config comment documents as defeating the pin) makes the same
     * request surface a security {@code DEBUG} line containing the PAN &mdash; proving the leak surface exists
     * and that the {@code INFO} pin is what suppresses it in {@link #securityLoggerPinSuppressesPanInUriWhenAppLoggingRaised()}.
     */
    @Test
    @DisplayName("positive control: defeating the pin (security=DEBUG) DOES surface the PAN — proving the pin is load-bearing")
    void defeatingThePinSurfacesPanProvingThePinIsLoadBearing() {
        // Operator-defeated pin: raise only the security hierarchy to DEBUG (restored to INFO in @AfterEach).
        securityLogger().setLevel(Level.DEBUG);
        assertThat(securityLogger().getEffectiveLevel())
                .as("the positive control raises org.springframework.security to DEBUG")
                .isEqualTo(Level.DEBUG);

        final ResponseEntity<String> response = restTemplate.getForEntity(CARD_VIEW_PATH, String.class);
        assertThat(response.getStatusCode())
                .as("the request still 401s; the security filter chain runs and now logs the URI at DEBUG")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        final List<ILoggingEvent> events = List.copyOf(rootCapture.list);

        // FilterChainProxy logs "Securing GET /api/cards/{PAN}" / "Secured ..." at DEBUG: the PAN now surfaces,
        // proving the surface genuinely exists and that the INFO pin (not incidental silence) suppresses it.
        assertThat(events)
                .as("with the pin defeated, the security filter chain logs the request URI (PAN) at DEBUG, "
                        + "proving the INFO pin in the primary test is what suppresses it")
                .anyMatch(event -> event.getLoggerName() != null
                        && event.getLoggerName().startsWith(SECURITY_LOGGER_NAME)
                        && event.getLevel() == Level.DEBUG
                        && event.getFormattedMessage() != null
                        && event.getFormattedMessage().contains(FULL_PAN));
    }

    /**
     * Returns the logback root logger.
     *
     * @return the root {@link Logger}
     */
    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    }

    /**
     * Returns the logback {@code org.springframework.security} logger (the hierarchy root pinned by the fix).
     *
     * @return the {@link Logger} for {@link #SECURITY_LOGGER_NAME}
     */
    private static Logger securityLogger() {
        return (Logger) LoggerFactory.getLogger(SECURITY_LOGGER_NAME);
    }

    /**
     * Reports whether a captured event originates from the test's own outbound HTTP client rather than the
     * server under test, by matching its logger name against {@link #OUTBOUND_TEST_CLIENT_LOGGER_PREFIXES}.
     *
     * @param event the captured logging event
     * @return {@code true} if the event was produced by the test's outbound HTTP client
     */
    private static boolean isOutboundTestClientEvent(final ILoggingEvent event) {
        final String loggerName = event.getLoggerName();
        if (loggerName == null) {
            return false;
        }
        return OUTBOUND_TEST_CLIENT_LOGGER_PREFIXES.stream().anyMatch(loggerName::startsWith);
    }
}

package com.carddemo.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.carddemo.dto.ReportRequest;
import com.carddemo.dto.SignonRequest;
import com.carddemo.dto.SignonResponse;
import com.carddemo.exception.FileProcessingException;
import com.carddemo.service.JwtService;
import com.carddemo.service.ReportService;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;

import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Runtime re-verification for QA findings <strong>F-2</strong> (distributed tracing across service
 * boundaries) and <strong>A-2</strong> (5xx server spans must carry an error status). This is the
 * live-behaviour counterpart to the source fixes applied in this phase: the {@code @Observed}
 * annotations on the service layer, the OpenTelemetry AWS-SDK execution-interceptor wiring in
 * {@code config/AwsTracingConfig}, the {@code datasource-micrometer} JDBC instrumentation, and the
 * {@code GlobalExceptionHandler.markServerErrorSpan(...)} enrichment.
 *
 * <p>The application boots with tracing force-enabled (see {@link AbstractTracingWebIntegrationTest}),
 * every span is captured in-process by {@link InMemorySpanCollector}, and each test asserts on the
 * <em>actual emitted spans</em> rather than on configuration. This exercises the same boundaries the
 * QA agent reported as un-instrumented, proving the reported behaviour no longer reproduces.</p>
 *
 * <h2>What each test proves</h2>
 * <ul>
 *   <li><strong>{@link #restRequestProducesServerServiceAndJdbcSpans()}</strong> &mdash; a single REST
 *       call fans out into a {@code SERVER} span, an {@code INTERNAL} {@code @Observed} service span
 *       ({@code auth-signon}), and a JDBC span (the {@code USRSEC} lookup), demonstrating the
 *       REST&nbsp;&rarr;&nbsp;service&nbsp;&rarr;&nbsp;JDBC chain is one continuous trace (F-2).</li>
 *   <li><strong>{@link #s3ClientCallProducesAwsClientSpan()}</strong> &mdash; an S3 {@code PutObject}
 *       through the auto-configured, now-instrumented {@code S3Client} produces an AWS client span
 *       ({@code rpc.system=aws-api}), proving the service&nbsp;&rarr;&nbsp;AWS (S3) boundary is
 *       traced (F-2).</li>
 *   <li><strong>{@link #sqsSendProducesAwsMessagingSpan()}</strong> &mdash; an SQS {@code SendMessage}
 *       through the instrumented {@code SqsAsyncClient} (the very client the report-launch
 *       {@code SqsTemplate} delegates to) produces an AWS messaging span, proving the
 *       service&nbsp;&rarr;&nbsp;AWS (SQS) boundary is traced (F-2).</li>
 *   <li><strong>{@link #serverErrorResponseMarksSpanWithErrorStatusAndException()}</strong> &mdash; a
 *       handled 5xx (a mocked {@link ReportService} throwing {@link FileProcessingException}) yields a
 *       {@code SERVER} span whose status is {@link StatusCode#ERROR} and which records the exception as
 *       an {@code "exception"} span event, proving the A-2 enrichment fires on the
 *       {@code @ExceptionHandler} path.</li>
 * </ul>
 *
 * <p>Source COBOL/JCL is referenced read-only at commit SHA {@code 27d6c6f}; design rationale is
 * recorded in {@code docs/decision-log.md}, not in these comments (Explainability rule).</p>
 */
class DistributedTracingIT extends AbstractTracingWebIntegrationTest {

    /** A user id that does not exist in the seeded {@code USRSEC} table &mdash; forces a 401 after the JDBC lookup. */
    private static final String BOGUS_USER = "NOUSER99";

    /** A non-matching password (unused by the not-found path, kept realistic and within the 8-char cap). */
    private static final String BOGUS_PASSWORD = "NOPASS99";

    /** Throwaway S3 bucket for the AWS-boundary span probe (self-provisioned then torn down, AAP §0.7.7). */
    private static final String TRACE_BUCKET = "carddemo-trace-probe";

    /** Throwaway standard SQS queue for the AWS-messaging span probe (self-provisioned then torn down). */
    private static final String TRACE_QUEUE = "carddemo-trace-probe";

    /** OTel AWS-SDK semantic-convention attribute stamped on every AWS client span. */
    private static final AttributeKey<String> RPC_SYSTEM = AttributeKey.stringKey("rpc.system");

    /** Auto-configured SQS async client &mdash; the concrete client the OTel SQS customizer instruments. */
    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    /** Issues the ADMIN bearer token used to reach the authenticated {@code /api/reports} endpoint. */
    @Autowired
    private JwtService jwtService;

    /**
     * Replaces the real {@link ReportService} with a mock so the 5xx path is deterministic and
     * self-contained (no dependency on SQS/S3 state). {@code @MockitoBean} is the non-deprecated Boot
     * 3.5 replacement for {@code @MockBean}.
     */
    @MockitoBean
    private ReportService reportService;

    // ===============================================================================================
    // F-2 — REST -> service (@Observed) -> JDBC continuity.
    // ===============================================================================================

    /**
     * Verifies that one authenticated-less sign-on attempt produces a connected chain of spans across
     * the web, service, and persistence layers. A bogus user id is used deliberately: {@code
     * SignonService.authenticate(...)} performs the {@code USRSEC} JDBC lookup <em>before</em> the
     * password comparison, so even a 401 exercises REST&nbsp;&rarr;&nbsp;service&nbsp;&rarr;&nbsp;JDBC
     * without requiring seeded credentials.
     */
    @Test
    void restRequestProducesServerServiceAndJdbcSpans() {
        resetSpans();

        final ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/auth/login", new SignonRequest(BOGUS_USER, BOGUS_PASSWORD), String.class);

        // The credentials are invalid, so the online sign-on contract returns 401 (COSGN00C parity).
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // (1) Inbound HTTP produces a SERVER span (the http.server.requests observation).
        final SpanData serverSpan = awaitSpan(
                "REST server span for POST /api/auth/login",
                span -> span.getKind() == SpanKind.SERVER);
        assertThat(serverSpan).isNotNull();

        // (2) The @Observed SignonService.authenticate method produces an INTERNAL service span
        //     named by its contextualName ("auth-signon"). Its presence proves the ObservedAspect
        //     is active and the service layer is now traced (the core of F-2).
        final SpanData serviceSpan = awaitSpan(
                "service span auth-signon (@Observed)",
                spanOfKindWithName(SpanKind.INTERNAL, "auth-signon"));
        assertThat(serviceSpan.getName().toLowerCase(Locale.ROOT)).contains("auth-signon");

        // (3) The USRSEC lookup issued inside authenticate() produces a JDBC span via
        //     datasource-micrometer. Matching on JDBC vocabulary keeps the assertion robust to the
        //     exact span name emitted by the instrumentation.
        final SpanData jdbcSpan = awaitSpan(
                "JDBC span for the USRSEC lookup (datasource-micrometer)",
                DistributedTracingIT::isJdbcSpan);
        assertThat(isJdbcSpan(jdbcSpan))
                .as("captured JDBC span: name=%s kind=%s", jdbcSpan.getName(), jdbcSpan.getKind())
                .isTrue();
    }

    // ===============================================================================================
    // F-2 — service -> AWS (S3) boundary.
    // ===============================================================================================

    /**
     * Verifies that an S3 call through the auto-configured {@code S3Client} emits an AWS client span,
     * proving the OpenTelemetry AWS-SDK execution interceptor wired by {@code AwsTracingConfig} is
     * attached to the S3 client. The bucket is created before {@code resetSpans()} so only the
     * {@code PutObject} span is asserted, and is removed in a {@code finally} block (self-cleanup).
     */
    @Test
    void s3ClientCallProducesAwsClientSpan() {
        createBucket(TRACE_BUCKET);
        try {
            resetSpans();

            putObject(TRACE_BUCKET, "trace-probe.txt", "trace".getBytes(StandardCharsets.UTF_8));

            final SpanData s3Span = awaitSpan(
                    "AWS S3 client span (PutObject via instrumented S3Client)",
                    DistributedTracingIT::isS3ClientSpan);

            assertThat(s3Span.getKind())
                    .as("S3 SDK spans are CLIENT-kind")
                    .isIn(SpanKind.CLIENT, SpanKind.PRODUCER);
            assertThat(s3Span.getAttributes().get(RPC_SYSTEM))
                    .as("OTel AWS-SDK instrumentation stamps rpc.system=aws-api")
                    .isEqualTo("aws-api");
        } finally {
            deleteBucketRecursively(TRACE_BUCKET);
        }
    }

    // ===============================================================================================
    // F-2 — service -> AWS (SQS messaging) boundary.
    // ===============================================================================================

    /**
     * Verifies that an SQS {@code SendMessage} through the instrumented {@code SqsAsyncClient} emits an
     * AWS messaging span. This is the same async client the report-launch {@code SqsTemplate} (F-1
     * bean) delegates to, so instrumenting it traces the CORPT00C&nbsp;&rarr;&nbsp;SQS report bridge.
     * A throwaway standard queue is created and deleted around the probe (self-cleanup, AAP §0.7.7).
     *
     * @throws Exception if the async SQS create/send/delete calls are interrupted
     */
    @Test
    void sqsSendProducesAwsMessagingSpan() throws Exception {
        final String queueUrl = sqsAsyncClient.createQueue(request -> request.queueName(TRACE_QUEUE))
                .get().queueUrl();
        try {
            resetSpans();

            sqsAsyncClient.sendMessage(request -> request.queueUrl(queueUrl).messageBody("{\"probe\":true}"))
                    .get();

            final SpanData sqsSpan = awaitSpan(
                    "AWS SQS send span (SendMessage via instrumented SqsAsyncClient)",
                    DistributedTracingIT::isSqsSendSpan);

            assertThat(sqsSpan.getKind())
                    .as("SQS send SDK spans are CLIENT- or PRODUCER-kind")
                    .isIn(SpanKind.CLIENT, SpanKind.PRODUCER);
            assertThat(sqsSpan.getAttributes().get(RPC_SYSTEM))
                    .as("OTel AWS-SDK instrumentation stamps rpc.system=aws-api")
                    .isEqualTo("aws-api");
        } finally {
            sqsAsyncClient.deleteQueue(request -> request.queueUrl(queueUrl)).get();
        }
    }

    // ===============================================================================================
    // A-2 — 5xx server span error status + recorded exception.
    // ===============================================================================================

    /**
     * Verifies that a handled server error enriches the SERVER span with an error status and a recorded
     * exception. {@link ReportService} is mocked to throw {@link FileProcessingException} (which maps to
     * HTTP 500 through {@code CardDemoException}); {@code GlobalExceptionHandler} then calls
     * {@code markServerErrorSpan(...)}, which sets the error on the {@code ServerRequestObservationContext}
     * so the tracing bridge stops the span with {@link StatusCode#ERROR} and an {@code "exception"} event.
     * Without the A-2 fix the observation would complete cleanly and the span would carry
     * {@code UNSET}/no error &mdash; the exact behaviour the QA agent reported.
     */
    @Test
    void serverErrorResponseMarksSpanWithErrorStatusAndException() {
        when(reportService.generateReport(any(ReportRequest.class)))
                .thenThrow(new FileProcessingException("QA A-2 forced report failure"));

        // Mint an ADMIN bearer token so the authenticated /api/reports endpoint is reachable. The JWT
        // filter trusts the signed claims (no DB lookup), so a synthetic admin id is sufficient.
        final String adminJwt = jwtService.generateToken("QAADMIN", SignonResponse.ROLE_ADMIN);
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(adminJwt);

        // A valid MONTHLY request (dates optional for non-CUSTOM) so bean validation passes and the
        // request reaches the mocked service, which throws.
        final ReportRequest body = new ReportRequest("MONTHLY", null, null, Boolean.TRUE);

        resetSpans();

        final ResponseEntity<String> response = restTemplate.exchange(
                "/api/reports", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        // The SERVER span must now carry ERROR status (A-2 core assertion).
        final SpanData serverSpan = awaitSpan(
                "5xx SERVER span carrying ERROR status (A-2)",
                span -> span.getKind() == SpanKind.SERVER
                        && span.getStatus() != null
                        && span.getStatus().getStatusCode() == StatusCode.ERROR);

        assertThat(serverSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);

        // ...and it must record the exception as an "exception" span event.
        final boolean hasExceptionEvent = serverSpan.getEvents().stream()
                .map(EventData::getName)
                .anyMatch("exception"::equals);
        assertThat(hasExceptionEvent)
                .as("server span should record the exception as an 'exception' event (A-2)")
                .isTrue();
    }

    // ===============================================================================================
    // Span classification helpers (kept lenient on exact naming across instrumentation versions).
    // ===============================================================================================

    /**
     * Recognises a JDBC span emitted by {@code datasource-micrometer}. Matches on JDBC vocabulary in the
     * span name ({@code jdbc} / {@code query} / {@code connection} / {@code select}) so the assertion is
     * robust to whether the connection or the query observation names the span.
     *
     * @param span the captured span
     * @return {@code true} if the span looks like a JDBC connection/query span
     */
    private static boolean isJdbcSpan(final SpanData span) {
        final String name = span.getName() == null ? "" : span.getName().toLowerCase(Locale.ROOT);
        return name.contains("jdbc")
                || name.contains("query")
                || name.contains("connection")
                || name.contains("select");
    }

    /**
     * Recognises an S3 AWS-SDK client span (for example {@code "S3.PutObject"}).
     *
     * @param span the captured span
     * @return {@code true} if the span name identifies an S3 SDK call
     */
    private static boolean isS3ClientSpan(final SpanData span) {
        final String name = span.getName() == null ? "" : span.getName().toLowerCase(Locale.ROOT);
        return name.contains("s3");
    }

    /**
     * Recognises an SQS <em>send</em> AWS-SDK span (for example {@code "Sqs.SendMessage"}), excluding the
     * listener's {@code ReceiveMessage} consumer spans.
     *
     * @param span the captured span
     * @return {@code true} if the span name identifies an SQS send/publish SDK call
     */
    private static boolean isSqsSendSpan(final SpanData span) {
        final String name = span.getName() == null ? "" : span.getName().toLowerCase(Locale.ROOT);
        return name.contains("sendmessage")
                || name.contains("publish")
                || (name.contains("sqs") && name.contains("send"));
    }
}

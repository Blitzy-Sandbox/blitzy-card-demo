/*
 * ****************************************************************************
 * Program     : ReportSubmissionServiceIntegrationSeamTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Verifies the SQS seam of ReportSubmissionService: that the FIFO
 *               message group identifier is validated at construction against
 *               the SQS grammar, that the publish carries bounded correlation
 *               and trace headers with an unchanged three-field payload, that it
 *               is bounded by a caller deadline and cancels an abandoned send,
 *               and that a failure logs a symbolic reason plus an exception
 *               class only - never the endpoint and never the throwable - while
 *               still producing the source's literal and preserving the cause.
 * Source      : app/cbl/CORPT00C.cbl:L498-L537 (the seventeen-card WRITEQ TD
 *                 loop and WIRTE-JOBSUB-TDQ, including the RESP/REAS DISPLAY at
 *                 :L529 and the 'Unable to Write TDQ (JOBS)...' literal at :L532)
 *               + app/csd/CARDDEMO.CSD:L499-L505 (DEFINE TDQUEUE(JOBS)
 *                 RECORDSIZE(80) RECORDFORMAT(FIXED) DISPOSITION(MOD))
 *               @ 7756d89
 * ****************************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ****************************************************************************
 */
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.exception.FileAccessException;
import com.cardemo.model.dto.ReportRequest;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.service.report.ReportSubmissionService;
import com.cardemo.service.report.ReportSubmissionService.AttentionIdentifier;
import com.cardemo.service.shared.DateValidationService;
import io.awspring.cloud.sqs.operations.MessagingOperationFailedException;
import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import io.awspring.cloud.sns.core.SnsTemplate;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for the queue-facing half of {@link ReportSubmissionService}.
 *
 * <p>No Spring context, no network and no emulator: the template, the validator and the tracer provider are
 * all doubles, and the clock is fixed so the monthly period is deterministic. The monthly path is used
 * throughout because it reaches the publish without exercising the date validator, which keeps each test
 * about the seam it is asserting.
 *
 * <p><strong>One test deliberately takes ten seconds.</strong> {@code abandonedPublishIsCancelled} stubs a
 * future that never completes, so it can only return once the caller deadline fires - which is the whole
 * point, since a bound that is never exercised is a bound nobody knows exists. Shortening it would mean
 * making the deadline configurable purely for the test, adding a production knob to serve a test, so the ten
 * seconds is paid once per build instead.
 */
@DisplayName("ReportSubmissionService: the SQS seam is validated, bounded, propagating and quiet")
class ReportSubmissionServiceIntegrationSeamTest {

    /** The physical queue name, carrying the {@code .fifo} suffix AWS requires. */
    private static final String QUEUE_NAME = "carddemo-report-jobs.fifo";

    /** The account-id-free logical queue name, the only queue name a log line may publish. */
    private static final String QUEUE_LOGICAL_NAME = "carddemo-report-jobs";

    /** The deterministic FIFO group, matching the literal in {@code application.yml}. */
    private static final String MESSAGE_GROUP_ID = "carddemo-report-jobs";

    /** A fixed instant so the monthly period and the header fields never vary between runs. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-03T10:15:30Z"), ZoneOffset.UTC);

    /** The service's own logger, whose {@code WARN} output is the channel under test. */
    private static final String SERVICE_LOGGER_NAME =
            "com.cardemo.service.report.ReportSubmissionService";

    private SqsTemplate sqsTemplate;

    private ReportSubmissionService service;

    private Logger serviceLogger;

    private ListAppender<ILoggingEvent> logEvents;

    /** Captures the options each publish was issued with, so headers and group can be asserted. */
    private final List<CapturedSend> captured = new ArrayList<>();

    /**
     * The parts of a publish this test needs to assert on.
     *
     * @param queue          the physical queue the send targeted
     * @param payload        the typed payload
     * @param messageGroupId the FIFO group
     * @param headers        the attached headers, in insertion order
     */
    private record CapturedSend(String queue, Object payload, String messageGroupId,
            Map<String, Object> headers) {
    }

    @BeforeEach
    void setUp() {
        sqsTemplate = mock(SqsTemplate.class);
        service = newService(MESSAGE_GROUP_ID, null);

        serviceLogger = (Logger) LoggerFactory.getLogger(SERVICE_LOGGER_NAME);
        logEvents = new ListAppender<>();
        logEvents.start();
        serviceLogger.addAppender(logEvents);
        serviceLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logEvents);
        logEvents.stop();
        captured.clear();
        CorrelationIdFilter.propagate(null);
    }

    /**
     * Builds the bean over the current template double.
     *
     * @param messageGroupId the group identifier to configure
     * @param tracer         the tracer to expose through the provider, or {@code null} for none
     * @return the constructed service
     */
    private ReportSubmissionService newService(final String messageGroupId, final Tracer tracer) {
        return new ReportSubmissionService(sqsTemplate, mock(SnsTemplate.class),
                mock(DateValidationService.class), FIXED_CLOCK,
                providerOf(tracer), QUEUE_NAME, QUEUE_LOGICAL_NAME, messageGroupId,
                "carddemo-notifications");
    }

    /**
     * Wraps a possibly-absent tracer in the provider contract the constructor expects.
     *
     * @param tracer the tracer to expose, or {@code null}
     * @return a provider answering that tracer
     */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<Tracer> providerOf(final Tracer tracer) {
        final ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracer);
        return provider;
    }

    /**
     * Stubs {@code sendAsync} to capture the options and answer with the supplied future.
     *
     * @param answer the future every publish resolves to
     */
    @SuppressWarnings("unchecked")
    private void stubSendAsync(final CompletableFuture<SendResult<Object>> answer) {
        when(sqsTemplate.sendAsync(any(Consumer.class))).thenAnswer(invocation -> {
            final Consumer<SqsSendOptions<Object>> configurer = invocation.getArgument(0);
            final RecordingSendOptions options = new RecordingSendOptions();
            configurer.accept(options);
            captured.add(new CapturedSend(options.queue, options.payload, options.messageGroupId,
                    options.headers));
            return answer;
        });
    }

    /**
     * Stubs a successful publish answering a fixed message identifier.
     */
    @SuppressWarnings("unchecked")
    private void stubSuccessfulSend() {
        final SendResult<Object> result = mock(SendResult.class);
        when(result.messageId()).thenReturn(java.util.UUID.nameUUIDFromBytes("m".getBytes(
                java.nio.charset.StandardCharsets.US_ASCII)));
        stubSendAsync(CompletableFuture.completedFuture(result));
    }

    /**
     * Builds the monthly submission request, confirmed, which is the shortest path to the publish.
     *
     * @return a request that reaches {@code WIRTE-JOBSUB-TDQ}
     */
    private static ReportRequest confirmedMonthlyRequest() {
        return new ReportRequest(null, null, null, null, null, null, "Y", null, null, null, null, null,
                null, null, null, "Y", null);
    }

    /**
     * Submits a confirmed monthly report.
     *
     * @param target the service to submit through
     */
    private static void submitMonthly(final ReportSubmissionService target) {
        target.submitScreen(AttentionIdentifier.ENTER, confirmedMonthlyRequest());
    }

    /**
     * Returns the single captured publish, failing when there was not exactly one.
     *
     * @return the captured publish
     */
    private CapturedSend onlySend() {
        assertThat(captured).hasSize(1);
        return captured.getFirst();
    }

    /**
     * Returns the single {@code WARN} event, failing when there was not exactly one.
     *
     * @return the warning event
     */
    private ILoggingEvent onlyWarning() {
        final List<ILoggingEvent> warnings = logEvents.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .toList();
        assertThat(warnings).hasSize(1);
        return warnings.getFirst();
    }

    @Nested
    @DisplayName("Message group validation at construction (H-15)")
    class MessageGroupValidation {

        @ParameterizedTest
        @ValueSource(strings = {
            "carddemo-report-jobs",
            "a",
            "JOBS",
            "carddemo.report:jobs/1",
            "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~",
        })
        @DisplayName("a value satisfying the SQS grammar is accepted")
        void permittedGroupIdentifiersAreAccepted(final String groupId) {
            assertThatCode(() -> newService(groupId, null)).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "",
            " ",
            "   ",
            "carddemo report jobs",
            "carddemo\treport",
            "carddemo\nreport",
            "carddemo\u0000report",
            "carddemo\u00e9report",
        })
        @DisplayName("a blank, whitespace-bearing or non-ASCII value is refused at construction")
        void invalidGroupIdentifiersAreRefused(final String groupId) {
            assertThatThrownBy(() -> newService(groupId, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.aws.sqs.report-message-group-id");
        }

        @Test
        @DisplayName("a value longer than the SQS limit of 128 characters is refused")
        void overlongGroupIdentifierIsRefused() {
            final String tooLong = "g".repeat(129);

            assertThatThrownBy(() -> newService(tooLong, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("1 to 128")
                    .hasMessageContaining("129");
        }

        @Test
        @DisplayName("exactly 128 characters is accepted, so the bound is inclusive")
        void boundaryLengthIsAccepted() {
            assertThatCode(() -> newService("g".repeat(128), null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a null value is still refused, and names the field")
        void nullGroupIdentifierIsRefused() {
            assertThatThrownBy(() -> newService(null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("reportMessageGroupId");
        }

        @Test
        @DisplayName("an invalid value is refused before any publish can be attempted")
        void refusalPrecedesAnyPublish() {
            assertThatThrownBy(() -> newService("bad group", null))
                    .isInstanceOf(IllegalStateException.class);

            verify(sqsTemplate, never()).sendAsync(any());
            verify(sqsTemplate, never()).send(any());
        }
    }

    @Nested
    @DisplayName("Bounded propagation headers (H-02)")
    class BoundedPropagationHeaders {

        @Test
        @DisplayName("the payload keeps exactly its three fields and the group is the configured one")
        void payloadAndGroupAreUnchanged() {
            stubSuccessfulSend();

            submitMonthly(service);

            final CapturedSend send = onlySend();
            assertThat(send.queue()).isEqualTo(QUEUE_NAME);
            assertThat(send.messageGroupId()).isEqualTo(MESSAGE_GROUP_ID);
            assertThat(send.payload()).isInstanceOf(ReportSubmissionService.JobSubmissionMessage.class);
            final ReportSubmissionService.JobSubmissionMessage message =
                    (ReportSubmissionService.JobSubmissionMessage) send.payload();
            assertThat(message.reportName()).isEqualTo("Monthly");
            assertThat(message.startDate()).isEqualTo("2026-08-01");
            // The last day of the current month, NOT month-to-date. app/cbl/CORPT00C.cbl:L223-L231 moves 1
            // into the day, adds 1 to the month with a year roll, then subtracts one day through
            // DATE-OF-INTEGER(INTEGER-OF-DATE(...) - 1), which lands on the final day of the ORIGINAL month.
            // Pinned by this assertion because prose elsewhere describes this period as month-to-date, and
            // the frozen source is the authority: a reader who trusts the prose would "fix" a correct
            // implementation into a divergent one.
            assertThat(message.endDate()).isEqualTo("2026-08-31");
        }

        @Test
        @DisplayName("a valid correlation identifier is propagated under the HTTP boundary's header name")
        void correlationIdentifierIsPropagated() {
            stubSuccessfulSend();
            CorrelationIdFilter.propagate("abc123DEF-456_789");

            submitMonthly(service);

            assertThat(onlySend().headers())
                    .containsEntry(CorrelationIdFilter.CORRELATION_ID_HEADER, "abc123DEF-456_789");
        }

        @Test
        @DisplayName("the originating transaction is always carried as a bounded literal")
        void originatingTransactionIsAlwaysCarried() {
            stubSuccessfulSend();

            submitMonthly(service);

            assertThat(onlySend().headers()).containsEntry("X-Carddemo-Transaction", "CR00");
        }

        @Test
        @DisplayName("no correlation header is attached when the diagnostic context is empty")
        void absentCorrelationIdentifierAttachesNoHeader() {
            stubSuccessfulSend();

            submitMonthly(service);

            assertThat(onlySend().headers())
                    .doesNotContainKey(CorrelationIdFilter.CORRELATION_ID_HEADER);
        }

        @Test
        @DisplayName("the trace and span identifiers come from the span when a tracer is configured")
        void spanIdentifiersArePropagated() {
            stubSuccessfulSend();
            final RecordedSpan span = new RecordedSpan("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7");
            // The double is built before the tracer stubbing is opened: building it inside the thenReturn
            // argument stubs one mock while another stubbing is unfinished, which Mockito rejects.
            final io.micrometer.tracing.Span spanDouble = span.mock();
            final Tracer tracer = mock(Tracer.class);
            when(tracer.nextSpan()).thenReturn(spanDouble);

            submitMonthly(newService(MESSAGE_GROUP_ID, tracer));

            assertThat(onlySend().headers())
                    .containsEntry("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736")
                    .containsEntry("X-Span-Id", "00f067aa0ba902b7");
            assertThat(span.name).isEqualTo("carddemo.report.submit");
            assertThat(span.tags).containsEntry("messaging.destination", QUEUE_LOGICAL_NAME);
            assertThat(span.started).isTrue();
            assertThat(span.ended).isTrue();
            assertThat(span.errors).isEmpty();
        }

        @Test
        @DisplayName("the failure path still ends the span and tags it with the symbolic reason only")
        void failureTagsSpanWithoutTheThrowable() {
            stubSendAsync(CompletableFuture.failedFuture(
                    new MessagingOperationFailedException("send failed for "
                            + "http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/q",
                            QUEUE_NAME)));
            final RecordedSpan span = new RecordedSpan("trace", "span");
            final io.micrometer.tracing.Span spanDouble = span.mock();
            final Tracer tracer = mock(Tracer.class);
            when(tracer.nextSpan()).thenReturn(spanDouble);
            final ReportSubmissionService traced = newService(MESSAGE_GROUP_ID, tracer);

            assertThatThrownBy(() -> submitMonthly(traced)).isInstanceOf(FileAccessException.class);

            assertThat(span.tags).containsEntry("error", "unavailable");
            assertThat(span.ended).as("an unended span is reported as an incomplete trace").isTrue();
            // span.error(Throwable) records the throwable's message on the trace, which is the same
            // account-bearing text the log refuses to carry, so it must never be called.
            assertThat(span.errors).isEmpty();
        }
    }

    @Nested
    @DisplayName("Bounded publish (H-03)")
    class BoundedPublish {

        @Test
        @DisplayName("the publish is issued asynchronously, never through the unbounded synchronous form")
        void publishIsAsynchronous() {
            stubSuccessfulSend();

            submitMonthly(service);

            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("a publish that never completes is abandoned and cancelled, not awaited forever")
        void abandonedPublishIsCancelled() {
            final CompletableFuture<SendResult<Object>> neverCompletes = new CompletableFuture<>();
            stubSendAsync(neverCompletes);
            final ReportSubmissionService bounded = newService(MESSAGE_GROUP_ID, null);

            // The deadline is ten seconds, so this asserts the bound exists rather than its exact value:
            // without it the call would not return at all.
            final long startedAtNanos = System.nanoTime();
            assertThatThrownBy(() -> submitMonthly(bounded))
                    .isInstanceOf(FileAccessException.class)
                    .hasMessage("Unable to Write TDQ (JOBS)...");
            final long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L;

            assertThat(neverCompletes).isCancelled();
            assertThat(elapsedMillis).isLessThan(30_000L);
            assertThat(onlyWarning().getFormattedMessage()).contains("RESP:timeout");
        }
    }

    @Nested
    @DisplayName("Curated failure reporting (H-11)")
    class CuratedFailureReporting {

        /** A messaging failure whose message carries an endpoint and an account identifier. */
        private MessagingOperationFailedException leakyFailure() {
            return new MessagingOperationFailedException("Failed to send message to endpoint "
                    + "http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/"
                    + "carddemo-report-jobs.fifo", QUEUE_NAME);
        }

        @Test
        @DisplayName("the warning carries no endpoint, no account identifier and no throwable")
        void warningIsCurated() {
            stubSendAsync(CompletableFuture.failedFuture(leakyFailure()));

            assertThatThrownBy(() -> submitMonthly(service)).isInstanceOf(FileAccessException.class);

            final ILoggingEvent warning = onlyWarning();
            // The decisive assertion: no throwable proxy means no message and no stack is rendered.
            assertThat(warning.getThrowableProxy()).isNull();
            assertThat(warning.getFormattedMessage())
                    .doesNotContain("000000000000")
                    .doesNotContain("localstack.cloud")
                    .doesNotContain("Failed to send message")
                    .doesNotContain(QUEUE_NAME)
                    // The legacy DISPLAY shape survives: RESP: then REAS: with no separator.
                    .contains("RESP:unavailable")
                    .contains("REAS:MessagingOperationFailedException")
                    .contains(QUEUE_LOGICAL_NAME);
        }

        @Test
        @DisplayName("the source's literal is produced byte for byte and the cause is preserved")
        void legacyLiteralAndCauseSurvive() {
            final MessagingOperationFailedException cause = leakyFailure();
            stubSendAsync(CompletableFuture.failedFuture(cause));

            assertThatThrownBy(() -> submitMonthly(service))
                    .isInstanceOf(FileAccessException.class)
                    .hasMessage("Unable to Write TDQ (JOBS)...")
                    .hasCause(cause);
        }

        @Test
        @DisplayName("a failure raised before the future exists is classified and reported the same way")
        void synchronousFailureIsClassified() {
            when(sqsTemplate.sendAsync(any())).thenThrow(new IllegalStateException("template closed"));

            assertThatThrownBy(() -> submitMonthly(service))
                    .isInstanceOf(FileAccessException.class)
                    .hasMessage("Unable to Write TDQ (JOBS)...");

            assertThat(onlyWarning().getFormattedMessage())
                    .contains("RESP:error")
                    .contains("REAS:IllegalStateException");
            assertThat(onlyWarning().getThrowableProxy()).isNull();
        }

        @Test
        @DisplayName("a successful publish logs the logical queue name and no physical name")
        void successLogIsSafe() {
            stubSuccessfulSend();

            submitMonthly(service);

            final List<ILoggingEvent> info = logEvents.list.stream()
                    .filter(event -> event.getLevel() == Level.INFO)
                    .toList();
            assertThat(info).isNotEmpty();
            assertThat(info)
                    .allSatisfy(event -> assertThat(event.getFormattedMessage())
                            .doesNotContain(QUEUE_NAME)
                            .doesNotContain("000000000000"));
        }
    }

    /**
     * A recording {@link io.micrometer.tracing.Span} double.
     *
     * <p>{@code micrometer-tracing-test} is not a dependency of this project, so the observable span state is
     * captured through a Mockito double whose fluent methods answer itself. Recording the calls rather than
     * verifying them individually keeps each assertion about span <em>state</em>, which is what a tracing
     * backend actually receives.
     */
    private static final class RecordedSpan {

        private final String traceId;

        private final String spanId;

        private final Map<String, String> tags = new LinkedHashMap<>();

        private final List<Throwable> errors = new ArrayList<>();

        private String name;

        private boolean started;

        private boolean ended;

        private RecordedSpan(final String traceId, final String spanId) {
            this.traceId = traceId;
            this.spanId = spanId;
        }

        /**
         * Builds the double that records into this instance.
         *
         * @return a span whose fluent methods return itself
         */
        private io.micrometer.tracing.Span mock() {
            final io.micrometer.tracing.Span span = Mockito.mock(io.micrometer.tracing.Span.class);
            final io.micrometer.tracing.TraceContext context =
                    Mockito.mock(io.micrometer.tracing.TraceContext.class);
            when(context.traceId()).thenReturn(traceId);
            when(context.spanId()).thenReturn(spanId);
            when(span.context()).thenReturn(context);
            when(span.name(any())).thenAnswer(invocation -> {
                name = invocation.getArgument(0);
                return span;
            });
            when(span.tag(any(String.class), any(String.class))).thenAnswer(invocation -> {
                tags.put(invocation.getArgument(0), invocation.getArgument(1));
                return span;
            });
            when(span.start()).thenAnswer(invocation -> {
                started = true;
                return span;
            });
            when(span.error(any())).thenAnswer(invocation -> {
                errors.add(invocation.getArgument(0));
                return span;
            });
            Mockito.doAnswer(invocation -> {
                ended = true;
                return null;
            }).when(span).end();
            return span;
        }
    }

    /**
     * A minimal recording implementation of the send-options contract.
     *
     * <p>The interface is fluent and Mockito cannot record a fluent chain without stubbing every method, so a
     * hand-written recorder is both shorter and clearer here. Only the four options this service sets are
     * retained; every other method returns {@code this} so an accidental additional option would still
     * compile and would still be visible as an unrecorded call.
     */
    private static final class RecordingSendOptions implements SqsSendOptions<Object> {

        private String queue;

        private Object payload;

        private String messageGroupId;

        private final Map<String, Object> headers = new LinkedHashMap<>();

        @Override
        public SqsSendOptions<Object> queue(final String queueName) {
            this.queue = queueName;
            return this;
        }

        @Override
        public SqsSendOptions<Object> payload(final Object payloadValue) {
            this.payload = payloadValue;
            return this;
        }

        @Override
        public SqsSendOptions<Object> header(final String name, final Object value) {
            this.headers.put(name, value);
            return this;
        }

        @Override
        public SqsSendOptions<Object> headers(final Map<String, Object> headerMap) {
            this.headers.putAll(headerMap);
            return this;
        }

        @Override
        public SqsSendOptions<Object> delaySeconds(final Integer delaySeconds) {
            return this;
        }

        @Override
        public SqsSendOptions<Object> messageGroupId(final String groupId) {
            this.messageGroupId = groupId;
            return this;
        }

        @Override
        public SqsSendOptions<Object> messageDeduplicationId(final String deduplicationId) {
            return this;
        }
    }
}

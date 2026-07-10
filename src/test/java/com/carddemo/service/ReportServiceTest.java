package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import io.awspring.cloud.sqs.operations.SqsTemplate;

import com.carddemo.dto.ReportRequest;
import com.carddemo.dto.ReportResponse;
import com.carddemo.exception.FileProcessingException;
import com.carddemo.exception.ValidationException;
import com.carddemo.observability.CorrelationIdFilter;
import com.carddemo.service.ReportService.ReportJobMessage;

/**
 * Pure, fast Mockito unit tests for {@link ReportService}, the online
 * <em>Transaction Reports</em> (CR00) service migrated from the COBOL program
 * {@code CORPT00C} ({@code app/cbl/CORPT00C.cbl}, frozen reference SHA
 * {@code 27d6c6f} — read-only, not copied into this repository).
 *
 * <p>The two collaborators {@link SqsTemplate} and {@link DateValidationService}
 * are Mockito mocks; the service is constructed directly (no Spring context, no
 * database, no Testcontainers, no live AWS). The SQS FIFO send is exercised
 * through {@link SqsTemplate#sendAsync(Consumer)}, whose returned
 * {@link CompletableFuture} the tests complete (or leave pending) to drive every
 * success, timeout, and failure branch deterministically.</p>
 *
 * <h2>Findings under test (CP3 code review)</h2>
 * <ul>
 *   <li><b>M10 — input contract.</b> A {@code null} request is rejected with a
 *       typed {@link ValidationException} (HTTP 400) before any dereference,
 *       never a raw {@link NullPointerException} / HTTP 500.</li>
 *   <li><b>M11 — outbound resilience.</b> The send is bounded by a deadline; a
 *       timeout, an SQS/AWS failure surfaced through the future, and an
 *       interruption are all wrapped in a typed {@link FileProcessingException}
 *       (HTTP 500) whose caller-facing message leaks no infrastructure detail,
 *       while the real cause is chained for server-side logs.</li>
 *   <li><b>M12 — correlation propagation.</b> The enqueued
 *       {@link ReportJobMessage} carries the request's MDC {@code correlationId};
 *       when the MDC has none, a fresh id is minted so the field is never
 *       blank.</li>
 * </ul>
 *
 * <h2>Behavioural parity assertions (CORPT00C @ SHA 27d6c6f)</h2>
 * <ul>
 *   <li>MONTHLY / YEARLY / CUSTOM window derivation and the legacy branch
 *       order, with an explicit default rejecting an unknown type.</li>
 *   <li>The {@code SUBMIT-JOB-TO-INTRDR} confirmation gate: an unconfirmed
 *       request enqueues nothing and returns {@code PENDING_CONFIRMATION}.</li>
 *   <li>The stable FIFO delivery contract: one message, the configured queue,
 *       the {@code carddemo-reports} message-group id, and the {@code jobId}
 *       deduplication id.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportService — CORPT00C (CR00) SQS FIFO report launch")
class ReportServiceTest {

    /** Configured FIFO queue name handed to the service under test. */
    private static final String TEST_QUEUE = "carddemo-report-jobs.fifo";

    /** Stable FIFO message-group id reproduced from {@code ReportService}. */
    private static final String EXPECTED_GROUP_ID = "carddemo-reports";

    /** Bounded send deadline; small so the timeout branch resolves quickly. */
    private static final long TEST_TIMEOUT_MS = 200L;

    /** Exact leak-free caller-facing message for a failed/late enqueue (M11). */
    private static final String EXPECTED_ENQUEUE_FAILED =
            "Unable to submit the report job for printing. Please retry.";

    /** Exact rejection message for a null request body (M10). */
    private static final String EXPECTED_REQUEST_REQUIRED = "Report request must be supplied.";

    /** Exact CORPT00C literal for an unrecognized report type. */
    private static final String EXPECTED_UNKNOWN_TYPE = "Select a report type to print report...";

    /** A correlation id representing one established by the request filter. */
    private static final String REQUEST_CORRELATION_ID = "test-correlation-id-1234";

    /**
     * A secret-bearing failure detail used to prove the caller-facing message
     * never leaks the underlying AWS/queue cause.
     */
    private static final String SECRET_LEAK_MARKER =
            "https://sqs.internal.example.com/000000000000/secret-queue-url";

    @Mock
    private SqsTemplate sqsTemplate;

    @Mock
    private DateValidationService dateValidationService;

    /** Mocked send result; only {@code messageId()} may be read at debug level. */
    @Mock
    private SendResult<ReportJobMessage> sendResult;

    /**
     * Fluent SQS send-options mock. {@link Answers#RETURNS_SELF} makes each
     * builder call return this same mock so the service's option chain runs and
     * the {@code payload(...)} argument can be captured.
     */
    @Mock(answer = Answers.RETURNS_SELF)
    private SqsSendOptions<ReportJobMessage> sendOptions;

    /** Captures the fluent options {@link Consumer} passed to {@code sendAsync}. */
    @Captor
    private ArgumentCaptor<Consumer<SqsSendOptions<ReportJobMessage>>> sendConsumerCaptor;

    private ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService(sqsTemplate, dateValidationService, TEST_QUEUE, TEST_TIMEOUT_MS);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Stubs {@code sendAsync} to complete immediately with {@link #sendResult}. */
    private void stubSendSucceeds() {
        doReturn(CompletableFuture.completedFuture(sendResult)).when(sqsTemplate).sendAsync(any());
    }

    /**
     * Replays the captured fluent options {@link Consumer} against
     * {@link #sendOptions} and returns the captured {@link ReportJobMessage}
     * payload, after asserting the stable FIFO delivery attributes.
     */
    private ReportJobMessage captureEnqueuedMessage() {
        verify(sqsTemplate).sendAsync(sendConsumerCaptor.capture());
        sendConsumerCaptor.getValue().accept(sendOptions);

        ArgumentCaptor<ReportJobMessage> payloadCaptor = ArgumentCaptor.forClass(ReportJobMessage.class);
        verify(sendOptions).queue(TEST_QUEUE);
        verify(sendOptions).payload(payloadCaptor.capture());
        verify(sendOptions).messageGroupId(EXPECTED_GROUP_ID);
        ReportJobMessage message = payloadCaptor.getValue();
        // The jobId is also the FIFO deduplication id.
        verify(sendOptions).messageDeduplicationId(message.jobId());
        return message;
    }

    // ------------------------------------------------------------------
    // Window derivation + ACCEPTED submission (parity + M12)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Confirmed submission — window derivation and FIFO enqueue")
    class ConfirmedSubmission {

        @Test
        @DisplayName("MONTHLY: derives the current month and enqueues an ACCEPTED job")
        void monthlyDerivesCurrentMonthAndEnqueues() {
            stubSendSucceeds();
            MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, REQUEST_CORRELATION_ID);

            ReportResponse response =
                    service.generateReport(new ReportRequest("MONTHLY", null, null, Boolean.TRUE));

            LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
            assertThat(response.status()).isEqualTo(ReportResponse.STATUS_ACCEPTED);
            assertThat(response.jobId()).isNotBlank();
            assertThat(response.reportType()).isEqualTo("MONTHLY");
            assertThat(response.reportName()).isEqualTo("Monthly");
            assertThat(response.startDate()).isEqualTo(firstOfMonth);
            assertThat(response.endDate()).isEqualTo(firstOfMonth.withDayOfMonth(firstOfMonth.lengthOfMonth()));
            assertThat(response.message()).isEqualTo("Monthly report submitted for printing ...");

            ReportJobMessage message = captureEnqueuedMessage();
            assertThat(message.jobId()).isEqualTo(response.jobId());
            assertThat(message.reportName()).isEqualTo("Monthly");
            assertThat(message.startDate()).isEqualTo(firstOfMonth.toString());
            // M12: the request's MDC correlation id is propagated onto the message.
            assertThat(message.correlationId()).isEqualTo(REQUEST_CORRELATION_ID);
        }

        @Test
        @DisplayName("YEARLY: derives Jan 1 -> Dec 31 of the current year")
        void yearlyDerivesCurrentYear() {
            stubSendSucceeds();

            ReportResponse response =
                    service.generateReport(new ReportRequest("YEARLY", null, null, Boolean.TRUE));

            int year = LocalDate.now().getYear();
            assertThat(response.status()).isEqualTo(ReportResponse.STATUS_ACCEPTED);
            assertThat(response.reportName()).isEqualTo("Yearly");
            assertThat(response.startDate()).isEqualTo(LocalDate.of(year, 1, 1));
            assertThat(response.endDate()).isEqualTo(LocalDate.of(year, 12, 31));

            ReportJobMessage message = captureEnqueuedMessage();
            assertThat(message.reportName()).isEqualTo("Yearly");
        }

        @Test
        @DisplayName("CUSTOM: validates both dates via CSUTLDTC-equivalent and enqueues the supplied window")
        void customValidatesAndEnqueuesSuppliedWindow() {
            stubSendSucceeds();
            LocalDate start = LocalDate.of(2025, 1, 1);
            LocalDate end = LocalDate.of(2025, 1, 31);

            ReportResponse response =
                    service.generateReport(new ReportRequest("CUSTOM", start, end, Boolean.TRUE));

            assertThat(response.status()).isEqualTo(ReportResponse.STATUS_ACCEPTED);
            assertThat(response.reportName()).isEqualTo("Custom");
            assertThat(response.startDate()).isEqualTo(start);
            assertThat(response.endDate()).isEqualTo(end);

            // Both dates are re-validated (the CORPT00C double CSUTLDTC call).
            verify(dateValidationService).validateDateCcyyMmDd("20250101", "Start Date");
            verify(dateValidationService).validateDateCcyyMmDd("20250131", "End Date");

            ReportJobMessage message = captureEnqueuedMessage();
            assertThat(message.startDate()).isEqualTo("2025-01-01");
            assertThat(message.endDate()).isEqualTo("2025-01-31");
        }

        @Test
        @DisplayName("M12: a fresh correlation id is minted when the MDC carries none")
        void mintsCorrelationIdWhenMdcAbsent() {
            stubSendSucceeds();
            // MDC deliberately empty (cleared in setUp).

            service.generateReport(new ReportRequest("MONTHLY", null, null, Boolean.TRUE));

            ReportJobMessage message = captureEnqueuedMessage();
            assertThat(message.correlationId()).isNotBlank();
            // A minted id is a valid UUID; this must not throw.
            UUID.fromString(message.correlationId());
        }
    }

    // ------------------------------------------------------------------
    // Confirmation gate (SUBMIT-JOB-TO-INTRDR)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Confirmation gate — nothing is enqueued until confirmed")
    class ConfirmationGate {

        @Test
        @DisplayName("confirm=null: returns PENDING_CONFIRMATION and enqueues nothing")
        void nullConfirmReturnsPending() {
            ReportResponse response =
                    service.generateReport(new ReportRequest("MONTHLY", null, null, null));

            assertThat(response.status()).isEqualTo("PENDING_CONFIRMATION");
            assertThat(response.jobId()).isNull();
            assertThat(response.message()).isEqualTo("Please confirm to print the Monthly report...");
            verifyNoInteractions(sqsTemplate);
        }

        @Test
        @DisplayName("confirm=false: returns PENDING_CONFIRMATION and enqueues nothing")
        void falseConfirmReturnsPending() {
            ReportResponse response =
                    service.generateReport(new ReportRequest("YEARLY", null, null, Boolean.FALSE));

            assertThat(response.status()).isEqualTo("PENDING_CONFIRMATION");
            assertThat(response.jobId()).isNull();
            verifyNoInteractions(sqsTemplate);
        }
    }

    // ------------------------------------------------------------------
    // Validation failures (M10 + parity)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Validation failures — typed HTTP 400, nothing enqueued")
    class ValidationFailures {

        @Test
        @DisplayName("M10: a null request is rejected with ValidationException before any dereference")
        void nullRequestRejected() {
            assertThatThrownBy(() -> service.generateReport(null))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(EXPECTED_REQUEST_REQUIRED);
            verifyNoInteractions(sqsTemplate, dateValidationService);
        }

        @Test
        @DisplayName("Unknown report type is rejected with the CORPT00C literal")
        void unknownTypeRejected() {
            assertThatThrownBy(() ->
                    service.generateReport(new ReportRequest("WEEKLY", null, null, Boolean.TRUE)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(EXPECTED_UNKNOWN_TYPE);
            verifyNoInteractions(sqsTemplate);
        }

        @Test
        @DisplayName("CUSTOM with a missing start date is rejected")
        void customMissingStartRejected() {
            assertThatThrownBy(() ->
                    service.generateReport(new ReportRequest("CUSTOM", null, LocalDate.of(2025, 1, 31), Boolean.TRUE)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date - can NOT be empty...");
            verifyNoInteractions(sqsTemplate);
        }

        @Test
        @DisplayName("CUSTOM with a missing end date is rejected")
        void customMissingEndRejected() {
            assertThatThrownBy(() ->
                    service.generateReport(new ReportRequest("CUSTOM", LocalDate.of(2025, 1, 1), null, Boolean.TRUE)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("End Date - can NOT be empty...");
            verifyNoInteractions(sqsTemplate);
        }

        @Test
        @DisplayName("CUSTOM with start after end is rejected by the ordering rule")
        void customStartAfterEndRejected() {
            assertThatThrownBy(() ->
                    service.generateReport(new ReportRequest(
                            "CUSTOM", LocalDate.of(2025, 2, 1), LocalDate.of(2025, 1, 1), Boolean.TRUE)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date must not be after End Date...");
            verifyNoInteractions(sqsTemplate);
        }

        @Test
        @DisplayName("CUSTOM with an invalid date propagates the CSUTLDTC-equivalent ValidationException")
        void customInvalidDatePropagates() {
            when(dateValidationService.validateDateCcyyMmDd(any(), any()))
                    .thenThrow(new ValidationException("Start Date - not a valid date..."));

            assertThatThrownBy(() ->
                    service.generateReport(new ReportRequest(
                            "CUSTOM", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31), Boolean.TRUE)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date - not a valid date...");
            verifyNoInteractions(sqsTemplate);
        }
    }

    // ------------------------------------------------------------------
    // Outbound resilience (M11)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Outbound resilience — bounded, leak-free SQS send (M11)")
    class OutboundResilience {

        @Test
        @DisplayName("An SQS/AWS failure is wrapped in a typed, leak-free FileProcessingException")
        void sqsFailureWrappedLeakFree() {
            CompletableFuture<SendResult<ReportJobMessage>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(SECRET_LEAK_MARKER));
            doReturn(failed).when(sqsTemplate).sendAsync(any());

            assertThatThrownBy(() ->
                    service.generateReport(new ReportRequest("MONTHLY", null, null, Boolean.TRUE)))
                    .isInstanceOf(FileProcessingException.class)
                    .hasMessage(EXPECTED_ENQUEUE_FAILED)
                    // Leak-free: the caller-facing message never carries the AWS cause.
                    .hasMessageNotContaining(SECRET_LEAK_MARKER)
                    // The real cause is chained for server-side diagnostics only.
                    .cause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(SECRET_LEAK_MARKER);
        }

        @Test
        @DisplayName("A send exceeding the bounded deadline is wrapped in a typed FileProcessingException")
        void sendTimeoutWrapped() {
            // A future that never completes forces the bounded get(...) to time out.
            doReturn(new CompletableFuture<SendResult<ReportJobMessage>>())
                    .when(sqsTemplate).sendAsync(any());

            assertThatThrownBy(() ->
                    service.generateReport(new ReportRequest("MONTHLY", null, null, Boolean.TRUE)))
                    .isInstanceOf(FileProcessingException.class)
                    .hasMessage(EXPECTED_ENQUEUE_FAILED);
        }

        @Test
        @DisplayName("HTTP status of the wrapped enqueue failure is 500 (INTERNAL_SERVER_ERROR)")
        void enqueueFailureMapsToHttp500() {
            CompletableFuture<SendResult<ReportJobMessage>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("boom"));
            doReturn(failed).when(sqsTemplate).sendAsync(any());

            assertThatThrownBy(() ->
                    service.generateReport(new ReportRequest("MONTHLY", null, null, Boolean.TRUE)))
                    .isInstanceOfSatisfying(FileProcessingException.class, ex ->
                            assertThat(ex.getHttpStatus().value()).isEqualTo(500));
        }
    }
}

package com.carddemo.unit.service.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.ReportRequest;
import com.carddemo.model.dto.ReportResponse;
import com.carddemo.service.report.ReportSubmissionService;
import com.carddemo.service.report.ReportSubmissionService.ReportJobMessage;
import com.carddemo.service.shared.DateValidationService;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * Unit tests for {@link ReportSubmissionService}, the online-to-batch report-submission bridge
 * translated from {@code app/cbl/CORPT00C.cbl}.
 *
 * <p>These tests exercise the full {@code PROCESS-ENTER-KEY} / {@code SUBMIT-JOB-TO-INTRDR} flow:
 * report-type selection (Monthly / Yearly / Custom / none), the fail-fast custom-range validation
 * cascade in its exact COBOL order, the confirmation gate, and the SQS FIFO publish contract. The
 * <strong>real</strong> {@link DateValidationService} is used (no mock) so the genuine calendar
 * validation runs, and a mocked {@link SqsTemplate} captures the publish lambda so the FIFO
 * message-group / deduplication strategy recorded in {@code DECISION_LOG.md} D-020 can be asserted
 * directly. No payloads or secrets are inspected beyond the byte-stable contract fields.
 */
@DisplayName("ReportSubmissionService — CORPT00C TDQ→SQS bridge")
class ReportSubmissionServiceTest {

    private static final String QUEUE_NAME = "carddemo-report-jobs.fifo";
    private static final String EXPECTED_GROUP_ID = "carddemo-reports";

    private DateValidationService dateValidationService;
    private SqsTemplate sqsTemplate;
    private ReportSubmissionService service;

    @BeforeEach
    void setUp() {
        // Use the real date-validation collaborator so the actual calendar logic (CSUTLDTC
        // replacement) is exercised by the custom-range tests, not a stub.
        dateValidationService = new DateValidationService();
        sqsTemplate = mock(SqsTemplate.class);
        service = new ReportSubmissionService(dateValidationService, sqsTemplate, QUEUE_NAME);
    }

    // ---------------------------------------------------------------------------------------------
    // Report-type selection
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Monthly selection publishes a current-month range and acknowledges submission")
    void monthlySelectionPublishesCurrentMonthRange() {
        RecordingSendOptions captured = stubCapturingSend();
        LocalDate today = LocalDate.now();
        String expectedStart = today.withDayOfMonth(1).toString();
        String expectedEnd = today.with(TemporalAdjusters.lastDayOfMonth()).toString();

        ReportResponse response = service.submitReport(monthlyRequest("Y"));

        assertThat(response.confirmationMessage()).isEqualTo("Monthly report submitted for printing ...");
        assertThat(response.errorMessage()).isNull();
        assertThat(captured.queue).isEqualTo(QUEUE_NAME);
        assertThat(captured.messageGroupId).isEqualTo(EXPECTED_GROUP_ID);
        assertThat(captured.messageDeduplicationId).isNotBlank();
        assertThat(captured.payload).isEqualTo(new ReportJobMessage("Monthly", expectedStart, expectedEnd));
    }

    @Test
    @DisplayName("Yearly selection publishes a full-calendar-year range (lowercase 'y' confirms)")
    void yearlySelectionPublishesCalendarYearRange() {
        RecordingSendOptions captured = stubCapturingSend();
        int year = LocalDate.now().getYear();
        String expectedStart = LocalDate.of(year, 1, 1).toString();
        String expectedEnd = LocalDate.of(year, 12, 31).toString();

        ReportResponse response = service.submitReport(yearlyRequest("y"));

        assertThat(response.confirmationMessage()).isEqualTo("Yearly report submitted for printing ...");
        assertThat(captured.payload).isEqualTo(new ReportJobMessage("Yearly", expectedStart, expectedEnd));
        assertThat(captured.messageGroupId).isEqualTo(EXPECTED_GROUP_ID);
    }

    @Test
    @DisplayName("Custom selection with a valid range publishes the assembled dates")
    void customSelectionWithValidRangePublishes() {
        RecordingSendOptions captured = stubCapturingSend();

        ReportResponse response = service.submitReport(
                customRequest("01", "15", "2024", "03", "20", "2024", "Y"));

        assertThat(response.confirmationMessage()).isEqualTo("Custom report submitted for printing ...");
        assertThat(captured.payload).isEqualTo(new ReportJobMessage("Custom", "2024-01-15", "2024-03-20"));
        assertThat(captured.messageGroupId).isEqualTo(EXPECTED_GROUP_ID);
        assertThat(captured.messageDeduplicationId).isNotBlank();
    }

    @Test
    @DisplayName("No report type selected throws ValidationException and never publishes")
    void noTypeSelectedThrowsAndDoesNotPublish() {
        assertThatThrownBy(() -> service.submitReport(emptyRequest("Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Select a report type to print report...");
        verifyNeverPublished();
    }

    // ---------------------------------------------------------------------------------------------
    // Custom-range validation cascade — six empty checks, in strict COBOL order
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Empty start month fails first with its COBOL message")
    void emptyStartMonthFails() {
        assertCustomValidationFails(customRequest("", "15", "2024", "03", "20", "2024", "Y"),
                "Start Date - Month can NOT be empty...");
    }

    @Test
    @DisplayName("Empty start day fails after the month check")
    void emptyStartDayFails() {
        assertCustomValidationFails(customRequest("01", "", "2024", "03", "20", "2024", "Y"),
                "Start Date - Day can NOT be empty...");
    }

    @Test
    @DisplayName("Empty start year fails after the day check")
    void emptyStartYearFails() {
        assertCustomValidationFails(customRequest("01", "15", "", "03", "20", "2024", "Y"),
                "Start Date - Year can NOT be empty...");
    }

    @Test
    @DisplayName("Empty end month fails after the start fields")
    void emptyEndMonthFails() {
        assertCustomValidationFails(customRequest("01", "15", "2024", "", "20", "2024", "Y"),
                "End Date - Month can NOT be empty...");
    }

    @Test
    @DisplayName("Empty end day fails after the end month check")
    void emptyEndDayFails() {
        assertCustomValidationFails(customRequest("01", "15", "2024", "03", "", "2024", "Y"),
                "End Date - Day can NOT be empty...");
    }

    @Test
    @DisplayName("Empty end year fails after the end day check")
    void emptyEndYearFails() {
        assertCustomValidationFails(customRequest("01", "15", "2024", "03", "20", "", "Y"),
                "End Date - Year can NOT be empty...");
    }

    // ---------------------------------------------------------------------------------------------
    // Custom-range validation cascade — numeric and bound checks
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Start month above 12 is rejected")
    void startMonthAboveBoundFails() {
        assertCustomValidationFails(customRequest("13", "15", "2024", "03", "20", "2024", "Y"),
                "Start Date - Not a valid Month...");
    }

    @Test
    @DisplayName("Non-numeric start month is rejected")
    void startMonthNonNumericFails() {
        assertCustomValidationFails(customRequest("ab", "15", "2024", "03", "20", "2024", "Y"),
                "Start Date - Not a valid Month...");
    }

    @Test
    @DisplayName("Start day above 31 is rejected")
    void startDayAboveBoundFails() {
        assertCustomValidationFails(customRequest("01", "32", "2024", "03", "20", "2024", "Y"),
                "Start Date - Not a valid Day...");
    }

    @Test
    @DisplayName("Non-numeric start year is rejected")
    void startYearNonNumericFails() {
        assertCustomValidationFails(customRequest("01", "15", "20x4", "03", "20", "2024", "Y"),
                "Start Date - Not a valid Year...");
    }

    @Test
    @DisplayName("End month above 12 is rejected")
    void endMonthAboveBoundFails() {
        assertCustomValidationFails(customRequest("01", "15", "2024", "13", "20", "2024", "Y"),
                "End Date - Not a valid Month...");
    }

    @Test
    @DisplayName("End day above 31 is rejected")
    void endDayAboveBoundFails() {
        assertCustomValidationFails(customRequest("01", "15", "2024", "03", "32", "2024", "Y"),
                "End Date - Not a valid Day...");
    }

    @Test
    @DisplayName("Non-numeric end year is rejected")
    void endYearNonNumericFails() {
        assertCustomValidationFails(customRequest("01", "15", "2024", "03", "20", "abcd", "Y"),
                "End Date - Not a valid Year...");
    }

    // ---------------------------------------------------------------------------------------------
    // Custom-range validation cascade — date-service (calendar) validation
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("In-bounds but non-calendar start date (Feb 31) is rejected by the date service")
    void startDateInvalidCalendarFails() {
        assertCustomValidationFails(customRequest("02", "31", "2024", "03", "20", "2024", "Y"),
                "Start Date - Not a valid date...");
    }

    @Test
    @DisplayName("In-bounds but non-calendar end date (Apr 31) is rejected by the date service")
    void endDateInvalidCalendarFails() {
        assertCustomValidationFails(customRequest("01", "10", "2024", "04", "31", "2024", "Y"),
                "End Date - Not a valid date...");
    }

    // ---------------------------------------------------------------------------------------------
    // Confirmation gate
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Blank confirmation raises the 'Please confirm' prompt and does not publish")
    void blankConfirmationPrompts() {
        assertThatThrownBy(() -> service.submitReport(monthlyRequest("")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Please confirm to print the Monthly report...");
        verifyNeverPublished();
    }

    @Test
    @DisplayName("Uppercase 'N' cancels the submission without publishing")
    void upperCaseNoCancels() {
        ReportResponse response = service.submitReport(monthlyRequest("N"));

        assertThat(response.confirmationMessage()).isEqualTo("Report request cancelled.");
        assertThat(response.errorMessage()).isNull();
        verifyNeverPublished();
    }

    @Test
    @DisplayName("Lowercase 'n' cancels the submission without publishing")
    void lowerCaseNoCancels() {
        ReportResponse response = service.submitReport(monthlyRequest("n"));

        assertThat(response.confirmationMessage()).isEqualTo("Report request cancelled.");
        verifyNeverPublished();
    }

    @Test
    @DisplayName("Unrecognised confirmation value is rejected and does not publish")
    void invalidConfirmationRejected() {
        assertThatThrownBy(() -> service.submitReport(monthlyRequest("X")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("\"X\" is not a valid value to confirm...");
        verifyNeverPublished();
    }

    // ---------------------------------------------------------------------------------------------
    // Publish failure path and FIFO deduplication strategy (DECISION_LOG D-020)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("SQS publish failure is wrapped as FileAccessException (WRITEQ TD error path)")
    void publishFailureWrappedAsFileAccessException() {
        RuntimeException cause = new RuntimeException("SQS unavailable");
        when(sqsTemplate.<ReportJobMessage>send(anySendConsumer())).thenThrow(cause);

        assertThatThrownBy(() -> service.submitReport(monthlyRequest("Y")))
                .isInstanceOf(FileAccessException.class)
                .hasMessage("Unable to Write TDQ (JOBS)...")
                .hasCause(cause);
    }

    @Test
    @DisplayName("Repeated submissions reuse one group id but get distinct random dedup ids (D-020)")
    void repeatedSubmissionsUseDistinctDeduplicationIds() {
        RecordingSendOptions first = new RecordingSendOptions();
        RecordingSendOptions second = new RecordingSendOptions();
        // Hand each successive publish a fresh recorder so the two dedup ids can be compared.
        when(sqsTemplate.<ReportJobMessage>send(anySendConsumer()))
                .thenAnswer(invocation -> {
                    Consumer<SqsSendOptions<ReportJobMessage>> consumer = invocation.getArgument(0);
                    consumer.accept(first);
                    return null;
                })
                .thenAnswer(invocation -> {
                    Consumer<SqsSendOptions<ReportJobMessage>> consumer = invocation.getArgument(0);
                    consumer.accept(second);
                    return null;
                });

        service.submitReport(monthlyRequest("Y"));
        service.submitReport(monthlyRequest("Y"));

        assertThat(first.messageGroupId).isEqualTo(EXPECTED_GROUP_ID);
        assertThat(second.messageGroupId).isEqualTo(EXPECTED_GROUP_ID);
        assertThat(first.messageDeduplicationId)
                .isNotBlank()
                .isNotEqualTo(second.messageDeduplicationId);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Stubs {@link SqsTemplate#send(Consumer)} to apply the publish lambda to a fresh
     * {@link RecordingSendOptions}, returning that recorder so the test can assert the resolved
     * queue, message group id, deduplication id, and payload.
     *
     * @return the recorder the captured publish lambda was applied to
     */
    private RecordingSendOptions stubCapturingSend() {
        RecordingSendOptions recorder = new RecordingSendOptions();
        when(sqsTemplate.<ReportJobMessage>send(anySendConsumer()))
                .thenAnswer(invocation -> {
                    Consumer<SqsSendOptions<ReportJobMessage>> consumer = invocation.getArgument(0);
                    consumer.accept(recorder);
                    return null;
                });
        return recorder;
    }

    /**
     * Asserts that submitting a custom-range request fails validation with the supplied COBOL
     * message and that nothing is published.
     *
     * @param request         the custom-range request under test
     * @param expectedMessage the exact COBOL validation message expected
     */
    private void assertCustomValidationFails(ReportRequest request, String expectedMessage) {
        assertThatThrownBy(() -> service.submitReport(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(expectedMessage);
        verifyNeverPublished();
    }

    /** Verifies the SQS publish path was never invoked. */
    private void verifyNeverPublished() {
        verify(sqsTemplate, never()).<ReportJobMessage>send(anySendConsumer());
    }

    /**
     * Typed Mockito matcher for the {@code send(Consumer)} overload. The explicit type argument on
     * {@link ArgumentMatchers#any()} keeps the call free of unchecked-conversion warnings under the
     * project's {@code -Werror} policy.
     *
     * @return a matcher accepting any publish {@link Consumer}
     */
    private static Consumer<SqsSendOptions<ReportJobMessage>> anySendConsumer() {
        return ArgumentMatchers.any();
    }

    private static ReportRequest monthlyRequest(String confirm) {
        return new ReportRequest("Y", null, null, null, null, null, null, null, null, confirm);
    }

    private static ReportRequest yearlyRequest(String confirm) {
        return new ReportRequest(null, "Y", null, null, null, null, null, null, null, confirm);
    }

    private static ReportRequest customRequest(
            String startMonth, String startDay, String startYear,
            String endMonth, String endDay, String endYear, String confirm) {
        return new ReportRequest(null, null, "Y",
                startMonth, startDay, startYear, endMonth, endDay, endYear, confirm);
    }

    private static ReportRequest emptyRequest(String confirm) {
        return new ReportRequest(null, null, null, null, null, null, null, null, null, confirm);
    }

    /**
     * Fully-typed in-memory {@link SqsSendOptions} fake that records the values the production
     * publish lambda sets. Using a concrete fake (rather than a generic Mockito mock) keeps the test
     * free of unchecked-conversion warnings while still letting the lambda execute exactly as in
     * production, which both verifies the FIFO contract and exercises the publish code path.
     */
    private static final class RecordingSendOptions implements SqsSendOptions<ReportJobMessage> {
        private String queue;
        private ReportJobMessage payload;
        private String messageGroupId;
        private String messageDeduplicationId;

        @Override
        public SqsSendOptions<ReportJobMessage> queue(String queue) {
            this.queue = queue;
            return this;
        }

        @Override
        public SqsSendOptions<ReportJobMessage> payload(ReportJobMessage payload) {
            this.payload = payload;
            return this;
        }

        @Override
        public SqsSendOptions<ReportJobMessage> header(String name, Object value) {
            return this;
        }

        @Override
        public SqsSendOptions<ReportJobMessage> headers(Map<String, Object> headers) {
            return this;
        }

        @Override
        public SqsSendOptions<ReportJobMessage> delaySeconds(Integer delaySeconds) {
            return this;
        }

        @Override
        public SqsSendOptions<ReportJobMessage> messageGroupId(String messageGroupId) {
            this.messageGroupId = messageGroupId;
            return this;
        }

        @Override
        public SqsSendOptions<ReportJobMessage> messageDeduplicationId(String messageDeduplicationId) {
            this.messageDeduplicationId = messageDeduplicationId;
            return this;
        }
    }
}

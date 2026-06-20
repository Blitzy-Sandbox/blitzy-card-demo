package com.carddemo.unit.service.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.ReportRequest;
import com.carddemo.model.dto.ReportResponse;
import com.carddemo.service.report.ReportSubmissionService;
import com.carddemo.service.shared.DateValidationService;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Isolated, JVM-only unit tests for {@link ReportSubmissionService}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}): the online
 * report-submission program {@code app/cbl/CORPT00C.cbl} selects a report type, validates the
 * custom date range, gates on an explicit confirmation, and bridges the request to batch through
 * {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}. The service re-platforms that transient-data-queue
 * write to a single SQS FIFO publish. These tests pin behavioral parity with {@code CORPT00C}'s
 * {@code PROCESS-ENTER-KEY} report-type {@code EVALUATE}, the {@code WHEN CUSTOMI} validation
 * cascade, the {@code SUBMIT-JOB-TO-INTRDR} confirmation gate, and the {@code WRITEQ TD} error
 * mapping, using a Mockito-mocked {@link SqsTemplate} (no network, database, or LocalStack).</p>
 */
@DisplayName("ReportSubmissionService - CORPT00C report submission re-platformed to an SQS FIFO publish")
@ExtendWith(MockitoExtension.class)
class ReportSubmissionServiceTest {

    /** Date-validation collaborator ({@code CSUTLDTC} replacement); only exercised on the Custom path. */
    @Mock
    private DateValidationService dateValidationService;

    /** SQS operations collaborator (the TDQ replacement); never touched on a non-publishing path. */
    @Mock
    private SqsTemplate sqsTemplate;

    /** Service under test, built manually because the FIFO queue name is a constructor {@code String}. */
    private ReportSubmissionService service;

    /** Expected FIFO queue name supplied to the constructor (the {@code carddemo.aws.sqs.report-queue} default). */
    private static final String EXPECTED_QUEUE = "carddemo-report-jobs.fifo";

    /** Expected constant FIFO message group id preserving the single source-TDQ ordering guarantee. */
    private static final String EXPECTED_GROUP = "carddemo-reports";

    @BeforeEach
    void setUp() {
        service = new ReportSubmissionService(dateValidationService, sqsTemplate, EXPECTED_QUEUE);
    }

    // --- Request builders (positional 10-arg ReportRequest record) ---------------------------------

    private static ReportRequest monthly(String confirm) {
        return new ReportRequest("Y", null, null, null, null, null, null, null, null, confirm);
    }

    private static ReportRequest yearly(String confirm) {
        return new ReportRequest(null, "Y", null, null, null, null, null, null, null, confirm);
    }

    private static ReportRequest custom(String startMonth, String startDay, String startYear,
            String endMonth, String endDay, String endYear, String confirm) {
        return new ReportRequest(null, null, "Y", startMonth, startDay, startYear, endMonth, endDay, endYear, confirm);
    }

    // --- DateValidationResult builders (only isAcceptable() is consulted by the service) -----------

    private static DateValidationService.DateValidationResult acceptable() {
        return new DateValidationService.DateValidationResult(true, 0, 0, "Date is valid", "2022-07-01", "YYYY-MM-DD");
    }

    private static DateValidationService.DateValidationResult unacceptable() {
        return new DateValidationService.DateValidationResult(false, 3, 2508, "Datevalue error", "2022-07-01", "YYYY-MM-DD");
    }

    // --- SQS publish verification helpers ----------------------------------------------------------

    /**
     * Mockito mocks the generic {@link SqsSendOptions} interface as a raw type; narrowing the
     * {@code mock(...)} result to the parameterized type is the sole unchecked operation in this
     * file (one suppression, far below the Gate 6 audit threshold) and keeps the {@code -Xlint:all
     * -Werror} (Gate 2) build warning-free.
     *
     * @return a self-returning {@link SqsSendOptions} mock for replaying the captured send lambda
     */
    @SuppressWarnings("unchecked")
    private static SqsSendOptions<ReportSubmissionService.ReportJobMessage> selfReturningOptions() {
        return mock(SqsSendOptions.class, RETURNS_SELF);
    }

    /**
     * Verifies exactly one publish occurred, replays the captured fluent send lambda onto a
     * self-returning options mock, asserts the queue, FIFO group, and a supplied deduplication id,
     * and returns the captured payload for type/date assertions.
     *
     * @return the single {@link ReportSubmissionService.ReportJobMessage} that was published
     */
    private ReportSubmissionService.ReportJobMessage verifySinglePublish() {
        ArgumentCaptor<Consumer<SqsSendOptions<ReportSubmissionService.ReportJobMessage>>> consumerCaptor =
                ArgumentCaptor.captor();
        verify(sqsTemplate, times(1)).send(consumerCaptor.capture());

        SqsSendOptions<ReportSubmissionService.ReportJobMessage> options = selfReturningOptions();
        consumerCaptor.getValue().accept(options);

        verify(options).queue(EXPECTED_QUEUE);
        verify(options).messageGroupId(EXPECTED_GROUP);
        verify(options).messageDeduplicationId(anyString());

        ArgumentCaptor<ReportSubmissionService.ReportJobMessage> payloadCaptor = ArgumentCaptor.captor();
        verify(options).payload(payloadCaptor.capture());
        return payloadCaptor.getValue();
    }

    // === A. Report-type paths (happy) ==============================================================

    @Test
    @DisplayName("Monthly report publishes exactly one job for the current month and returns a Monthly acknowledgement")
    void monthlyReportPublishesOnceAndReturnsConfirmation() {
        LocalDate today = LocalDate.now();
        String expectedStart = today.withDayOfMonth(1).toString();
        String expectedEnd = today.with(TemporalAdjusters.lastDayOfMonth()).toString();

        ReportResponse response = service.submitReport(monthly("Y"));

        verifyNoInteractions(dateValidationService);
        assertThat(response.confirmationMessage()).isNotBlank().contains("Monthly").contains("submitted");
        assertThat(response.errorMessage()).isNull();

        ReportSubmissionService.ReportJobMessage message = verifySinglePublish();
        assertThat(message.reportType()).isEqualTo("Monthly");
        assertThat(message.startDate()).isEqualTo(expectedStart);
        assertThat(message.endDate()).isEqualTo(expectedEnd);
    }

    @Test
    @DisplayName("Yearly report publishes exactly one job spanning Jan 1 to Dec 31 of the current year")
    void yearlyReportPublishesOnceAndReturnsConfirmation() {
        int year = LocalDate.now().getYear();
        String expectedStart = LocalDate.of(year, 1, 1).toString();
        String expectedEnd = LocalDate.of(year, 12, 31).toString();

        ReportResponse response = service.submitReport(yearly("Y"));

        verifyNoInteractions(dateValidationService);
        assertThat(response.confirmationMessage()).isNotBlank().contains("Yearly").contains("submitted");
        assertThat(response.errorMessage()).isNull();

        ReportSubmissionService.ReportJobMessage message = verifySinglePublish();
        assertThat(message.reportType()).isEqualTo("Yearly");
        assertThat(message.startDate()).isEqualTo(expectedStart);
        assertThat(message.endDate()).isEqualTo(expectedEnd);
    }

    @Test
    @DisplayName("Report-type precedence: Monthly wins over Yearly and Custom, mirroring the COBOL EVALUATE order")
    void monthlyTakesPrecedenceOverYearlyAndCustom() {
        ReportRequest request = new ReportRequest("Y", "Y", "Y", "13", "99", "abcd", "13", "99", "abcd", "Y");

        ReportResponse response = service.submitReport(request);

        verifyNoInteractions(dateValidationService);
        assertThat(response.confirmationMessage())
                .contains("Monthly")
                .doesNotContain("Yearly")
                .doesNotContain("Custom");

        ReportSubmissionService.ReportJobMessage message = verifySinglePublish();
        assertThat(message.reportType()).isEqualTo("Monthly");
    }

    // === B. Custom happy path ======================================================================

    @Test
    @DisplayName("Custom report with a valid range validates start then end and publishes exactly one Custom job")
    void customReportWithValidRangePublishesOnce() {
        when(dateValidationService.validateDate(eq("2022-07-01"), anyString())).thenReturn(acceptable());
        when(dateValidationService.validateDate(eq("2022-07-31"), anyString())).thenReturn(acceptable());

        ReportResponse response = service.submitReport(custom("07", "01", "2022", "07", "31", "2022", "Y"));

        assertThat(response.confirmationMessage()).contains("Custom").contains("submitted");
        assertThat(response.errorMessage()).isNull();
        verify(dateValidationService).validateDate(eq("2022-07-01"), anyString());
        verify(dateValidationService).validateDate(eq("2022-07-31"), anyString());

        ReportSubmissionService.ReportJobMessage message = verifySinglePublish();
        assertThat(message.reportType()).isEqualTo("Custom");
        assertThat(message.startDate()).isEqualTo("2022-07-01");
        assertThat(message.endDate()).isEqualTo("2022-07-31");
    }

    // === C. Custom empty-field cascade (first-error-wins; no publish, no date-service call) =========

    @Test
    @DisplayName("Custom validation rejects an empty start month first with 'Start Date - Month can NOT be empty'")
    void customEmptyStartMonthRejected() {
        assertThatThrownBy(() -> service.submitReport(custom("", "01", "2022", "07", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Start Date - Month can NOT be empty...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom validation rejects an empty start day with 'Start Date - Day can NOT be empty'")
    void customEmptyStartDayRejected() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "", "2022", "07", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Start Date - Day can NOT be empty...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom validation rejects an empty start year with 'Start Date - Year can NOT be empty'")
    void customEmptyStartYearRejected() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "", "07", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Start Date - Year can NOT be empty...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom validation rejects an empty end month with 'End Date - Month can NOT be empty'")
    void customEmptyEndMonthRejected() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "2022", "", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("End Date - Month can NOT be empty...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom validation rejects an empty end day with 'End Date - Day can NOT be empty'")
    void customEmptyEndDayRejected() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "2022", "07", "", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("End Date - Day can NOT be empty...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom validation rejects an empty end year with 'End Date - Year can NOT be empty'")
    void customEmptyEndYearRejected() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "2022", "07", "31", "", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("End Date - Year can NOT be empty...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    // === D. Custom numeric/range cascade (no publish, no date-service call) =========================

    @Test
    @DisplayName("Custom validation rejects a start month greater than 12 with 'Start Date - Not a valid Month'")
    void customInvalidStartMonthRejected() {
        assertThatThrownBy(() -> service.submitReport(custom("13", "01", "2022", "07", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Start Date - Not a valid Month...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom validation rejects a start day greater than 31 with 'Start Date - Not a valid Day'")
    void customInvalidStartDayRejected() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "32", "2022", "07", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Start Date - Not a valid Day...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom validation rejects a non-numeric start year with 'Start Date - Not a valid Year'")
    void customInvalidStartYearRejected() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "abcd", "07", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Start Date - Not a valid Year...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom validation rejects an end month greater than 12 with 'End Date - Not a valid Month'")
    void customInvalidEndMonthRejected() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "2022", "13", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("End Date - Not a valid Month...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom validation rejects an end day greater than 31 with 'End Date - Not a valid Day'")
    void customInvalidEndDayRejected() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "2022", "07", "32", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("End Date - Not a valid Day...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom validation rejects a non-numeric end year with 'End Date - Not a valid Year'")
    void customInvalidEndYearRejected() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "2022", "07", "31", "abcd", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("End Date - Not a valid Year...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    // === E. Custom date-validation branches ========================================================

    @Test
    @DisplayName("Custom validation: an unacceptable start date short-circuits before the end date or any publish")
    void customUnacceptableStartDateRejected() {
        when(dateValidationService.validateDate(eq("2022-07-01"), anyString())).thenReturn(unacceptable());

        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "2022", "07", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Start Date - Not a valid date...");

        verify(dateValidationService).validateDate(eq("2022-07-01"), anyString());
        verify(dateValidationService, never()).validateDate(eq("2022-07-31"), anyString());
        verifyNoInteractions(sqsTemplate);
    }

    @Test
    @DisplayName("Custom validation: an unacceptable end date is rejected after the start date passes, with no publish")
    void customUnacceptableEndDateRejected() {
        when(dateValidationService.validateDate(eq("2022-07-01"), anyString())).thenReturn(acceptable());
        when(dateValidationService.validateDate(eq("2022-07-31"), anyString())).thenReturn(unacceptable());

        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "2022", "07", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("End Date - Not a valid date...");

        verify(dateValidationService).validateDate(eq("2022-07-01"), anyString());
        verify(dateValidationService).validateDate(eq("2022-07-31"), anyString());
        verifyNoInteractions(sqsTemplate);
    }

    // === F. No report type selected ================================================================

    @Test
    @DisplayName("No report type selected is rejected with 'Select a report type to print report' and nothing is published")
    void noReportTypeSelectedRejected() {
        ReportRequest request = new ReportRequest(null, null, null, null, null, null, null, null, null, "Y");

        assertThatThrownBy(() -> service.submitReport(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Select a report type to print report...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    // === G. Confirmation gate (Monthly type, so the date service is never reached) =================

    @Test
    @DisplayName("Confirmation gate: a null or blank confirmation prompts 'Please confirm to print the Monthly report'")
    void blankConfirmationPrompts() {
        assertThatThrownBy(() -> service.submitReport(monthly("")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Please confirm to print the Monthly report...");
        assertThatThrownBy(() -> service.submitReport(monthly(null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Please confirm to print the Monthly report...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Confirmation gate: a lowercase 'y' confirms and publishes the Monthly report (case-insensitive parity)")
    void lowercaseYConfirmationPublishes() {
        ReportResponse response = service.submitReport(monthly("y"));

        assertThat(response.confirmationMessage()).contains("Monthly").contains("submitted");
        assertThat(response.errorMessage()).isNull();

        ReportSubmissionService.ReportJobMessage message = verifySinglePublish();
        assertThat(message.reportType()).isEqualTo("Monthly");
    }

    @Test
    @DisplayName("Confirmation gate: an uppercase 'N' cancels without throwing, returns the neutral acknowledgement, and never publishes")
    void uppercaseNCancelsWithoutPublishing() {
        assertThatCode(() -> service.submitReport(monthly("N"))).doesNotThrowAnyException();

        ReportResponse response = service.submitReport(monthly("N"));
        assertThat(response.confirmationMessage()).isEqualTo("Report request cancelled.");
        assertThat(response.errorMessage()).isNull();
        verifyNoInteractions(sqsTemplate);
    }

    @Test
    @DisplayName("Confirmation gate: a lowercase 'n' cancels without throwing, returns the neutral acknowledgement, and never publishes")
    void lowercaseNCancelsWithoutPublishing() {
        assertThatCode(() -> service.submitReport(monthly("n"))).doesNotThrowAnyException();

        ReportResponse response = service.submitReport(monthly("n"));
        assertThat(response.confirmationMessage()).isEqualTo("Report request cancelled.");
        assertThat(response.errorMessage()).isNull();
        verifyNoInteractions(sqsTemplate);
    }

    @Test
    @DisplayName("Confirmation gate: an unrecognised confirmation value 'X' is rejected with the COBOL invalid-confirm message")
    void invalidConfirmationValueRejected() {
        assertThatThrownBy(() -> service.submitReport(monthly("X")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("\"X\" is not a valid value to confirm...");
        verifyNoInteractions(sqsTemplate);
    }

    // === H. SQS send failure mapping ===============================================================

    @Test
    @DisplayName("SQS publish failure is wrapped as FileAccessException, mirroring the COBOL non-NORMAL WRITEQ TD path")
    void sqsSendFailureMapsToFileAccessException() {
        RuntimeException sqsOutage = new RuntimeException("simulated SQS outage");
        doThrow(sqsOutage).when(sqsTemplate)
                .send(ArgumentMatchers.<Consumer<SqsSendOptions<ReportSubmissionService.ReportJobMessage>>>any());

        assertThatThrownBy(() -> service.submitReport(monthly("Y")))
                .isInstanceOf(FileAccessException.class)
                .hasMessageContaining("Unable to Write TDQ (JOBS)")
                .hasCause(sqsOutage);
    }
}

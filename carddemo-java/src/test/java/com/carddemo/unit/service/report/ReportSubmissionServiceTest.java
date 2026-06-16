package com.carddemo.unit.service.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
 * Unit tests for {@link ReportSubmissionService}, the online-to-batch report-submission
 * bridge migrated (REFERENCE-ONLY; COBOL not copied) from {@code app/cbl/CORPT00C.cbl}
 * at source commit {@code 27d6c6f}.
 *
 * <p>These fast, JVM-only tests use Mockito mocks for the {@link DateValidationService}
 * and {@link SqsTemplate} collaborators (no Spring context, Testcontainers, LocalStack,
 * network, or database). They pin the {@code PROCESS-ENTER-KEY} report-type selection
 * order, the custom-range validation cascade, the confirmation gate, and the
 * {@code WRITEQ TD}&nbsp;&rarr;&nbsp;SQS FIFO publish (message group {@code carddemo-reports}),
 * including the non-{@code NORMAL} response mapping to {@link FileAccessException}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportSubmissionService - CORPT00C report submission to SQS FIFO TDQ bridge")
class ReportSubmissionServiceTest {

    /** FIFO destination queue name injected into the service under test. */
    private static final String EXPECTED_QUEUE = "carddemo-report-jobs.fifo";

    /** Constant FIFO message group id preserving the single-queue ordering of the source TDQ. */
    private static final String EXPECTED_GROUP = "carddemo-reports";

    @Mock
    private DateValidationService dateValidationService;

    @Mock
    private SqsTemplate sqsTemplate;

    private ReportSubmissionService service;

    @BeforeEach
    void setUp() {
        // The production constructor takes the queue name as a @Value-annotated parameter, so the
        // service is wired manually (a String would not be supplied by @InjectMocks).
        service = new ReportSubmissionService(dateValidationService, sqsTemplate, EXPECTED_QUEUE);
    }

    // ---------------------------------------------------------------------------------------------
    // A. Report-type paths (happy)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Monthly selection publishes exactly one message and returns a Monthly confirmation")
    void monthlyReportPublishesOnceAndReturnsMonthlyConfirmation() {
        LocalDate today = LocalDate.now();
        String expectedStart = today.withDayOfMonth(1).toString();
        String expectedEnd = today.with(TemporalAdjusters.lastDayOfMonth()).toString();

        ReportResponse response = service.submitReport(monthly("Y"));

        verifyNoInteractions(dateValidationService);
        assertThat(response.confirmationMessage()).isNotBlank().contains("Monthly").contains("submitted");
        assertThat(response.errorMessage()).isNull();

        ReportSubmissionService.ReportJobMessage message = verifyPublishedOnceAndCapture();
        assertThat(message.reportType()).isEqualTo("Monthly");
        assertThat(message.startDate()).isEqualTo(expectedStart);
        assertThat(message.endDate()).isEqualTo(expectedEnd);
    }

    @Test
    @DisplayName("Yearly selection publishes exactly one message and returns a Yearly confirmation")
    void yearlyReportPublishesOnceAndReturnsYearlyConfirmation() {
        int year = LocalDate.now().getYear();
        String expectedStart = LocalDate.of(year, 1, 1).toString();
        String expectedEnd = LocalDate.of(year, 12, 31).toString();

        ReportResponse response = service.submitReport(yearly("Y"));

        verifyNoInteractions(dateValidationService);
        assertThat(response.confirmationMessage()).isNotBlank().contains("Yearly").contains("submitted");
        assertThat(response.errorMessage()).isNull();

        ReportSubmissionService.ReportJobMessage message = verifyPublishedOnceAndCapture();
        assertThat(message.reportType()).isEqualTo("Yearly");
        assertThat(message.startDate()).isEqualTo(expectedStart);
        assertThat(message.endDate()).isEqualTo(expectedEnd);
    }

    @Test
    @DisplayName("Report-type precedence: Monthly wins over Yearly and Custom (EVALUATE order parity)")
    void reportTypePrecedenceMonthlyWinsOverYearlyAndCustom() {
        LocalDate today = LocalDate.now();
        String expectedStart = today.withDayOfMonth(1).toString();
        String expectedEnd = today.with(TemporalAdjusters.lastDayOfMonth()).toString();

        // Monthly, Yearly and Custom are all selected and the custom date parts are deliberately
        // invalid; Monthly must still win and the custom cascade must never run.
        ReportRequest request = new ReportRequest("Y", "Y", "Y", "13", "99", "abcd", "13", "99", "abcd", "Y");

        ReportResponse response = service.submitReport(request);

        verifyNoInteractions(dateValidationService);
        assertThat(response.confirmationMessage())
                .contains("Monthly")
                .doesNotContain("Yearly")
                .doesNotContain("Custom");

        ReportSubmissionService.ReportJobMessage message = verifyPublishedOnceAndCapture();
        assertThat(message.reportType()).isEqualTo("Monthly");
        assertThat(message.startDate()).isEqualTo(expectedStart);
        assertThat(message.endDate()).isEqualTo(expectedEnd);
    }

    // ---------------------------------------------------------------------------------------------
    // B. Custom happy path
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Custom selection with a valid range validates both dates and publishes once")
    void customReportWithValidRangePublishesOnce() {
        when(dateValidationService.validateDate(eq("2022-07-01"), any())).thenReturn(acceptable());
        when(dateValidationService.validateDate(eq("2022-07-31"), any())).thenReturn(acceptable());

        ReportResponse response = service.submitReport(custom("07", "01", "2022", "07", "31", "2022", "Y"));

        assertThat(response.confirmationMessage()).isNotBlank().contains("Custom").contains("submitted");
        assertThat(response.errorMessage()).isNull();
        verify(dateValidationService).validateDate(eq("2022-07-01"), any());
        verify(dateValidationService).validateDate(eq("2022-07-31"), any());

        ReportSubmissionService.ReportJobMessage message = verifyPublishedOnceAndCapture();
        assertThat(message.reportType()).isEqualTo("Custom");
        assertThat(message.startDate()).isEqualTo("2022-07-01");
        assertThat(message.endDate()).isEqualTo("2022-07-31");
    }

    // ---------------------------------------------------------------------------------------------
    // C. Custom empty-field cascade (first-error-wins; no publish, no date-service call)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Custom range with empty start month is rejected before any later edit")
    void customStartMonthEmptyThrowsValidationException() {
        assertThatThrownBy(() -> service.submitReport(custom("", "01", "2022", "07", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Start Date - Month can NOT be empty...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom range with empty start day is rejected before any later edit")
    void customStartDayEmptyThrowsValidationException() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "", "2022", "07", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Start Date - Day can NOT be empty...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom range with empty start year is rejected before any later edit")
    void customStartYearEmptyThrowsValidationException() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "", "07", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Start Date - Year can NOT be empty...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom range with empty end month is rejected before any later edit")
    void customEndMonthEmptyThrowsValidationException() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "2022", "", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("End Date - Month can NOT be empty...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom range with empty end day is rejected before any later edit")
    void customEndDayEmptyThrowsValidationException() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "2022", "07", "", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("End Date - Day can NOT be empty...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom range with empty end year is rejected before any later edit")
    void customEndYearEmptyThrowsValidationException() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "2022", "07", "31", "", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("End Date - Year can NOT be empty...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    // ---------------------------------------------------------------------------------------------
    // D. Custom numeric/range cascade (no publish, no date-service call)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Custom range with start month greater than 12 is rejected as not a valid month")
    void customStartMonthOutOfRangeThrowsValidationException() {
        assertThatThrownBy(() -> service.submitReport(custom("13", "01", "2022", "07", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Start Date - Not a valid Month...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom range with start day greater than 31 is rejected as not a valid day")
    void customStartDayOutOfRangeThrowsValidationException() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "32", "2022", "07", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Start Date - Not a valid Day...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom range with non-numeric start year is rejected as not a valid year")
    void customStartYearNonNumericThrowsValidationException() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "abcd", "07", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Start Date - Not a valid Year...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom range with end month greater than 12 is rejected as not a valid month")
    void customEndMonthOutOfRangeThrowsValidationException() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "2022", "13", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("End Date - Not a valid Month...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom range with end day greater than 31 is rejected as not a valid day")
    void customEndDayOutOfRangeThrowsValidationException() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "2022", "07", "32", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("End Date - Not a valid Day...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("Custom range with non-numeric end year is rejected as not a valid year")
    void customEndYearNonNumericThrowsValidationException() {
        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "2022", "07", "31", "abcd", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("End Date - Not a valid Year...");
        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    // ---------------------------------------------------------------------------------------------
    // E. Custom date-validation branches
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Custom range with an unacceptable start date throws and never validates the end date")
    void customStartDateNotAcceptableThrowsAndDoesNotValidateEnd() {
        when(dateValidationService.validateDate(eq("2022-07-01"), any())).thenReturn(unacceptable());

        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "2022", "07", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Start Date - Not a valid date...");

        verifyNoInteractions(sqsTemplate);
        verify(dateValidationService).validateDate(eq("2022-07-01"), any());
        verify(dateValidationService, never()).validateDate(eq("2022-07-31"), any());
    }

    @Test
    @DisplayName("Custom range with an acceptable start but unacceptable end date throws after validating both")
    void customEndDateNotAcceptableThrowsAfterValidatingBoth() {
        when(dateValidationService.validateDate(eq("2022-07-01"), any())).thenReturn(acceptable());
        when(dateValidationService.validateDate(eq("2022-07-31"), any())).thenReturn(unacceptable());

        assertThatThrownBy(() -> service.submitReport(custom("07", "01", "2022", "07", "31", "2022", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("End Date - Not a valid date...");

        verifyNoInteractions(sqsTemplate);
        verify(dateValidationService).validateDate(eq("2022-07-01"), any());
        verify(dateValidationService).validateDate(eq("2022-07-31"), any());
    }

    // ---------------------------------------------------------------------------------------------
    // F. No report type selected
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("No report type selected throws and performs no publish or date validation")
    void noReportTypeSelectedThrowsValidationException() {
        ReportRequest request = new ReportRequest(null, null, null, null, null, null, null, null, null, "Y");

        assertThatThrownBy(() -> service.submitReport(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Select a report type to print report...");

        verifyNoInteractions(sqsTemplate);
        verifyNoInteractions(dateValidationService);
    }

    // ---------------------------------------------------------------------------------------------
    // G. Confirmation gate (Monthly avoids any date-service stubbing)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Blank or missing confirmation flag throws and performs no publish")
    void blankConfirmThrowsValidationException() {
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
    @DisplayName("Lowercase 'y' confirmation publishes the report (case-insensitive parity)")
    void lowercaseYConfirmPublishes() {
        ReportResponse response = service.submitReport(monthly("y"));

        assertThat(response.confirmationMessage()).isNotBlank().contains("Monthly").contains("submitted");
        assertThat(response.errorMessage()).isNull();

        ReportSubmissionService.ReportJobMessage message = verifyPublishedOnceAndCapture();
        assertThat(message.reportType()).isEqualTo("Monthly");
    }

    @Test
    @DisplayName("Uppercase 'N' confirmation cancels without publishing and is not an exception")
    void uppercaseNConfirmCancelsWithoutPublishing() {
        ReportResponse response = service.submitReport(monthly("N"));

        assertThat(response.confirmationMessage()).isEqualTo("Report request cancelled.");
        assertThat(response.errorMessage()).isNull();
        verifyNoInteractions(sqsTemplate);
    }

    @Test
    @DisplayName("Lowercase 'n' confirmation cancels without publishing and is not an exception")
    void lowercaseNConfirmCancelsWithoutPublishing() {
        ReportResponse response = service.submitReport(monthly("n"));

        assertThat(response.confirmationMessage()).isEqualTo("Report request cancelled.");
        assertThat(response.errorMessage()).isNull();
        verifyNoInteractions(sqsTemplate);
    }

    @Test
    @DisplayName("An unrecognised confirmation value throws and performs no publish")
    void invalidConfirmValueThrowsValidationException() {
        assertThatThrownBy(() -> service.submitReport(monthly("X")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("\"X\" is not a valid value to confirm...");
        verifyNoInteractions(sqsTemplate);
    }

    // ---------------------------------------------------------------------------------------------
    // H. SQS send failure mapping
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A failed SQS publish is mapped to a FileAccessException (WRITEQ TD failure parity)")
    void sqsSendFailureIsMappedToFileAccessException() {
        doThrow(new RuntimeException("simulated SQS outage"))
                .when(sqsTemplate)
                .send(ArgumentMatchers.<Consumer<SqsSendOptions<ReportSubmissionService.ReportJobMessage>>>any());

        assertThatThrownBy(() -> service.submitReport(monthly("Y")))
                .isInstanceOf(FileAccessException.class)
                .hasMessageContaining("Unable to Write TDQ (JOBS)")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    // ---------------------------------------------------------------------------------------------
    // Test fixtures and helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds a monthly-report request with the supplied confirmation flag and all other fields
     * unset, matching the canonical {@link ReportRequest} component order.
     *
     * @param confirm the confirmation flag value (may be {@code null})
     * @return a monthly-selection request
     */
    private static ReportRequest monthly(String confirm) {
        return new ReportRequest("Y", null, null, null, null, null, null, null, null, confirm);
    }

    /**
     * Builds a yearly-report request with the supplied confirmation flag and all other fields unset.
     *
     * @param confirm the confirmation flag value (may be {@code null})
     * @return a yearly-selection request
     */
    private static ReportRequest yearly(String confirm) {
        return new ReportRequest(null, "Y", null, null, null, null, null, null, null, confirm);
    }

    /**
     * Builds a custom-range report request from its split month/day/year parts and confirmation flag.
     *
     * @param startMonth the start month part
     * @param startDay   the start day part
     * @param startYear  the start year part
     * @param endMonth   the end month part
     * @param endDay     the end day part
     * @param endYear    the end year part
     * @param confirm    the confirmation flag value
     * @return a custom-selection request
     */
    private static ReportRequest custom(String startMonth, String startDay, String startYear,
            String endMonth, String endDay, String endYear, String confirm) {
        return new ReportRequest(null, null, "Y",
                startMonth, startDay, startYear, endMonth, endDay, endYear, confirm);
    }

    /**
     * Builds an acceptable date-validation result (strictly valid); only
     * {@link DateValidationService.DateValidationResult#isAcceptable()} is consulted by the service.
     *
     * @return an acceptable result
     */
    private static DateValidationService.DateValidationResult acceptable() {
        return new DateValidationService.DateValidationResult(true, 0, 0, "valid", "2022-07-01", "YYYY-MM-DD");
    }

    /**
     * Builds an unacceptable date-validation result (invalid, message number other than the tolerated
     * {@code 2513}); the service treats this as a failed custom-date edit.
     *
     * @return an unacceptable result
     */
    private static DateValidationService.DateValidationResult unacceptable() {
        return new DateValidationService.DateValidationResult(false, 3, 2508, "bad date", "2022-07-01", "YYYY-MM-DD");
    }

    /**
     * Verifies that exactly one message was published via the fluent {@code SqsTemplate.send(Consumer)}
     * overload, replays the captured options consumer onto a self-returning options mock to read back
     * the fluent calls, asserts the queue, FIFO message group, and deduplication-id envelope, and
     * returns the published payload for value assertions.
     *
     * @return the published {@link ReportSubmissionService.ReportJobMessage}
     */
    private ReportSubmissionService.ReportJobMessage verifyPublishedOnceAndCapture() {
        ArgumentCaptor<Consumer<SqsSendOptions<ReportSubmissionService.ReportJobMessage>>> sendCaptor =
                ArgumentCaptor.captor();
        verify(sqsTemplate, times(1)).send(sendCaptor.capture());

        SqsSendOptions<ReportSubmissionService.ReportJobMessage> options = selfReturningOptions();
        sendCaptor.getValue().accept(options);

        verify(options).queue(EXPECTED_QUEUE);
        verify(options).messageGroupId(EXPECTED_GROUP);
        verify(options).messageDeduplicationId(anyString());

        ArgumentCaptor<ReportSubmissionService.ReportJobMessage> payloadCaptor = ArgumentCaptor.captor();
        verify(options).payload(payloadCaptor.capture());
        return payloadCaptor.getValue();
    }

    /**
     * Creates a self-returning {@link SqsSendOptions} mock used to replay and inspect the fluent send
     * options consumer. {@code Mockito.mock(Class, Answer)} of a generic interface yields a raw type;
     * this is the single, justified unchecked suppression in this file (well within the Gate&nbsp;6
     * threshold) and keeps the Gate&nbsp;2 build warning-free.
     *
     * @return a self-returning options mock typed to the report payload
     */
    @SuppressWarnings("unchecked")
    private static SqsSendOptions<ReportSubmissionService.ReportJobMessage> selfReturningOptions() {
        return mock(SqsSendOptions.class, RETURNS_SELF);
    }
}

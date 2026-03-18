package com.cardemo.unit.service;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Map;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.ReportRequest;
import com.cardemo.service.report.ReportSubmissionService;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.DateValidationService.DateValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReportSubmissionService} — the online-to-batch bridge
 * that migrates CORPT00C.cbl CICS TDQ WRITEQ logic to SQS message publishing.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Confirmation flow (CORPT00C EIBAID-based Y/N/other confirmation)</li>
 *   <li>Monthly report date range calculation (1st to last day of month)</li>
 *   <li>Yearly report date range calculation (Jan 1 to Dec 31)</li>
 *   <li>Custom date range validation via DateValidationService (CSUTLDTC subprogram)</li>
 *   <li>SQS FIFO queue publishing with messageGroupId</li>
 *   <li>SQS error handling wrapping SqsException into CardDemoException</li>
 * </ul>
 *
 * <p>Uses Mockito for isolation — NO Spring context loading.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportSubmissionService — Unit tests for CORPT00C.cbl Report Submit to SQS")
class ReportSubmissionServiceTest {

    /** Mock SQS client for verifying message publish operations (CICS TDQ replacement). */
    @Mock
    private SqsClient sqsClient;

    /** Mock date validation service replacing COBOL CSUTLDTC subprogram calls. */
    @Mock
    private DateValidationService dateValidationService;

    /** Mock Jackson ObjectMapper for controlling JSON serialization of SQS message body. */
    @Mock
    private ObjectMapper objectMapper;

    /** Service under test — injected with all three mocks via constructor injection. */
    @InjectMocks
    private ReportSubmissionService reportSubmissionService;

    /** Reusable report request DTO, reset before each test. */
    private ReportRequest request;

    /** Test queue URL simulating the resolved SQS FIFO queue endpoint. */
    private static final String TEST_QUEUE_URL =
            "http://localhost:4566/000000000000/carddemo-report-jobs.fifo";

    /** Padding string to build fullMessage responses matching CEEDAYS format (min 19 chars). */
    private static final String FULL_MSG_PADDING =
            "                                                                            ";

    @BeforeEach
    void setUp() throws Exception {
        request = new ReportRequest();

        // Set the private reportQueueUrl field via reflection to bypass @PostConstruct
        // which would require a live SQS connection for GetQueueUrl. In production,
        // this is resolved during Spring bean initialization via resolveQueueUrl().
        Field queueUrlField = ReportSubmissionService.class.getDeclaredField("reportQueueUrl");
        queueUrlField.setAccessible(true);
        queueUrlField.set(reportSubmissionService, TEST_QUEUE_URL);
    }

    // =========================================================================
    // Confirmation Flow Tests — CORPT00C EIBAID-based confirmation (tests 1-6)
    // In the COBOL source, EIBAID keys drive Y/N confirmation. In Java, the
    // confirm field on ReportRequest replaces EIBAID with 'Y'/'N'/other values.
    // =========================================================================

    @Test
    @DisplayName("1. Null confirmation → ValidationException with 'Please confirm' message")
    void testSubmitReport_nullConfirmation_throwsValidation() {
        request.setMonthly(true);
        request.setConfirm(null);

        assertThatThrownBy(() -> reportSubmissionService.submitReport(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Please confirm");
    }

    @Test
    @DisplayName("2. 'Y' confirmation → proceeds to SQS submission and returns result")
    void testSubmitReport_confirmY_proceeds() throws Exception {
        request.setMonthly(true);
        request.setConfirm("Y");

        when(objectMapper.writeValueAsString(any())).thenReturn("{\"reportType\":\"Monthly\"}");

        String result = reportSubmissionService.submitReport(request);

        assertThat(result).isNotNull();
        assertThat(result).contains("report submitted for printing");
    }

    @Test
    @DisplayName("3. 'y' confirmation → proceeds (case-insensitive per COBOL EVALUATE)")
    void testSubmitReport_confirmSmallY_proceeds() throws Exception {
        request.setMonthly(true);
        request.setConfirm("y");

        when(objectMapper.writeValueAsString(any())).thenReturn("{\"reportType\":\"Monthly\"}");

        String result = reportSubmissionService.submitReport(request);

        assertThat(result).isNotNull();
        assertThat(result).contains("report submitted for printing");
    }

    @Test
    @DisplayName("4. 'N' confirmation → cancels with no SQS publish (CORPT00C cancel path)")
    void testSubmitReport_confirmN_cancels() {
        request.setMonthly(true);
        request.setConfirm("N");

        assertThatThrownBy(() -> reportSubmissionService.submitReport(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cancelled");

        // Verify that SQS was never called — the cancellation short-circuits before publishing
        verify(sqsClient, never()).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    @DisplayName("5. 'n' confirmation → cancels (case-insensitive)")
    void testSubmitReport_confirmSmallN_cancels() {
        request.setMonthly(true);
        request.setConfirm("n");

        assertThatThrownBy(() -> reportSubmissionService.submitReport(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cancelled");

        verify(sqsClient, never()).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    @DisplayName("6. 'X' confirmation → ValidationException for invalid confirmation value")
    void testSubmitReport_confirmOther_throwsValidation() {
        request.setMonthly(true);
        request.setConfirm("X");

        assertThatThrownBy(() -> reportSubmissionService.submitReport(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("is not a valid value to confirm");
    }

    // =========================================================================
    // Monthly Report Tests — Date range from 1st to last day of month (tests 7-9)
    // In CORPT00C.cbl, the COBOL program uses FUNCTION CURRENT-DATE and
    // FUNCTION DATE-OF-INTEGER to compute 1st-of-month and last-of-month.
    // Java uses LocalDate.now(), withDayOfMonth(1), and YearMonth.atEndOfMonth().
    // =========================================================================

    @Test
    @DisplayName("7. Monthly March 2024: startDate=2024-03-01, endDate=2024-03-31")
    void testSubmitReport_monthly_calculatesDateRange() throws Exception {
        request.setMonthly(true);
        request.setConfirm("Y");

        LocalDate marchDate = LocalDate.of(2024, 3, 15);

        try (MockedStatic<LocalDate> mockedLocalDate =
                     Mockito.mockStatic(LocalDate.class, Mockito.CALLS_REAL_METHODS)) {
            mockedLocalDate.when(() -> LocalDate.now()).thenReturn(marchDate);

            when(objectMapper.writeValueAsString(any())).thenReturn("{\"reportType\":\"Monthly\"}");

            reportSubmissionService.submitReport(request);

            // Capture the payload map passed to ObjectMapper to verify date range
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(objectMapper).writeValueAsString(captor.capture());

            @SuppressWarnings("unchecked")
            Map<String, String> payload = (Map<String, String>) captor.getValue();
            assertThat(payload.get("startDate")).isEqualTo("2024-03-01");
            assertThat(payload.get("endDate")).isEqualTo("2024-03-31");
        }
    }

    @Test
    @DisplayName("8. Monthly February leap year (2024): endDate=2024-02-29")
    void testSubmitReport_monthly_february_leapYear() throws Exception {
        request.setMonthly(true);
        request.setConfirm("Y");

        LocalDate febLeapDate = LocalDate.of(2024, 2, 10);

        try (MockedStatic<LocalDate> mockedLocalDate =
                     Mockito.mockStatic(LocalDate.class, Mockito.CALLS_REAL_METHODS)) {
            mockedLocalDate.when(() -> LocalDate.now()).thenReturn(febLeapDate);

            when(objectMapper.writeValueAsString(any())).thenReturn("{\"mock\":true}");

            reportSubmissionService.submitReport(request);

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(objectMapper).writeValueAsString(captor.capture());

            @SuppressWarnings("unchecked")
            Map<String, String> payload = (Map<String, String>) captor.getValue();
            assertThat(payload.get("startDate")).isEqualTo("2024-02-01");
            assertThat(payload.get("endDate")).isEqualTo("2024-02-29");
        }
    }

    @Test
    @DisplayName("9. Monthly February non-leap year (2023): endDate=2023-02-28")
    void testSubmitReport_monthly_february_nonLeapYear() throws Exception {
        request.setMonthly(true);
        request.setConfirm("Y");

        LocalDate febNonLeapDate = LocalDate.of(2023, 2, 10);

        try (MockedStatic<LocalDate> mockedLocalDate =
                     Mockito.mockStatic(LocalDate.class, Mockito.CALLS_REAL_METHODS)) {
            mockedLocalDate.when(() -> LocalDate.now()).thenReturn(febNonLeapDate);

            when(objectMapper.writeValueAsString(any())).thenReturn("{\"mock\":true}");

            reportSubmissionService.submitReport(request);

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(objectMapper).writeValueAsString(captor.capture());

            @SuppressWarnings("unchecked")
            Map<String, String> payload = (Map<String, String>) captor.getValue();
            assertThat(payload.get("startDate")).isEqualTo("2023-02-01");
            assertThat(payload.get("endDate")).isEqualTo("2023-02-28");
        }
    }

    // =========================================================================
    // Yearly Report Test — Date range Jan 1 to Dec 31 (test 10)
    // In CORPT00C.cbl, yearly calculation uses WS-CURDATE-YEAR with
    // hardcoded month/day boundaries. Java uses LocalDate.of(year, 1, 1)
    // and LocalDate.of(year, 12, 31).
    // =========================================================================

    @Test
    @DisplayName("10. Yearly 2024: startDate=2024-01-01, endDate=2024-12-31")
    void testSubmitReport_yearly_calculatesDateRange() throws Exception {
        request.setYearly(true);
        request.setConfirm("Y");

        LocalDate yearDate = LocalDate.of(2024, 6, 15);

        try (MockedStatic<LocalDate> mockedLocalDate =
                     Mockito.mockStatic(LocalDate.class, Mockito.CALLS_REAL_METHODS)) {
            mockedLocalDate.when(() -> LocalDate.now()).thenReturn(yearDate);

            when(objectMapper.writeValueAsString(any())).thenReturn("{\"mock\":true}");

            reportSubmissionService.submitReport(request);

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(objectMapper).writeValueAsString(captor.capture());

            @SuppressWarnings("unchecked")
            Map<String, String> payload = (Map<String, String>) captor.getValue();
            assertThat(payload.get("startDate")).isEqualTo("2024-01-01");
            assertThat(payload.get("endDate")).isEqualTo("2024-12-31");
        }
    }

    // =========================================================================
    // Custom Date Range Tests — Validated via DateValidationService (tests 11-13)
    // In CORPT00C.cbl, custom dates are validated using CALL 'CSUTLDTC'
    // (CEEDAYS date validation). The Java service delegates to
    // DateValidationService.validateWithCeedays() which returns a
    // DateValidationResult record with severityCode and fullMessage.
    // =========================================================================

    @Test
    @DisplayName("11. Custom report with valid start/end dates → proceeds to submission")
    void testSubmitReport_custom_validDates() throws Exception {
        request.setCustom(true);
        request.setStartDate(LocalDate.of(2024, 1, 1));
        request.setEndDate(LocalDate.of(2024, 6, 30));
        request.setConfirm("Y");

        // Both dates pass CEEDAYS validation — severity 0 means valid
        DateValidationResult validResult = new DateValidationResult(
                true, 0, "Date is valid",
                "0000Mesg Code: 0000Date is valid" + FULL_MSG_PADDING,
                true, true, true);
        when(dateValidationService.validateWithCeedays(any(String.class), any(String.class)))
                .thenReturn(validResult);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"reportType\":\"Custom\"}");

        String result = reportSubmissionService.submitReport(request);

        assertThat(result).isNotNull();
        assertThat(result).contains("Custom");
        assertThat(result).contains("report submitted for printing");
    }

    @Test
    @DisplayName("12. Custom report with invalid start date → ValidationException via DateValidationService")
    void testSubmitReport_custom_invalidStartDate_throwsValidation() {
        request.setCustom(true);
        request.setStartDate(LocalDate.of(2024, 1, 1));
        request.setEndDate(LocalDate.of(2024, 6, 30));
        request.setConfirm("Y");

        // Start date fails CEEDAYS validation — severity 3 with non-2513 message number
        // The extractMessageNumber method reads chars at positions 15-18 of fullMessage.
        // "0000" != "2513" so the validation failure is treated as a real error.
        DateValidationResult invalidStartResult = new DateValidationResult(
                false, 3, "Datevalue error",
                "0003Mesg Code: 0000Datevalue error" + FULL_MSG_PADDING,
                false, false, false);
        when(dateValidationService.validateWithCeedays("2024-01-01", "YYYY-MM-DD"))
                .thenReturn(invalidStartResult);

        assertThatThrownBy(() -> reportSubmissionService.submitReport(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Start Date");
    }

    @Test
    @DisplayName("13. Custom report with invalid end date → ValidationException via DateValidationService")
    void testSubmitReport_custom_invalidEndDate_throwsValidation() {
        request.setCustom(true);
        request.setStartDate(LocalDate.of(2024, 1, 1));
        request.setEndDate(LocalDate.of(2024, 6, 30));
        request.setConfirm("Y");

        // Start date passes validation
        DateValidationResult validResult = new DateValidationResult(
                true, 0, "Date is valid",
                "0000Mesg Code: 0000Date is valid" + FULL_MSG_PADDING,
                true, true, true);
        when(dateValidationService.validateWithCeedays("2024-01-01", "YYYY-MM-DD"))
                .thenReturn(validResult);

        // End date fails validation — severity 3 with non-2513 message number
        DateValidationResult invalidEndResult = new DateValidationResult(
                false, 3, "Datevalue error",
                "0003Mesg Code: 0000Datevalue error" + FULL_MSG_PADDING,
                false, false, false);
        when(dateValidationService.validateWithCeedays("2024-06-30", "YYYY-MM-DD"))
                .thenReturn(invalidEndResult);

        assertThatThrownBy(() -> reportSubmissionService.submitReport(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("End Date");
    }

    // =========================================================================
    // No Selection Test — No report type flags set (test 14)
    // In CORPT00C.cbl, if none of the monthly/yearly/custom options
    // are selected, the program displays an error. Java throws
    // ValidationException("Select a report type to print report...").
    // =========================================================================

    @Test
    @DisplayName("14. No report type selected → ValidationException")
    void testSubmitReport_noSelection_throwsValidation() {
        // All report type flags default to false — no selection made
        request.setMonthly(false);
        request.setYearly(false);
        request.setCustom(false);
        request.setConfirm("Y");

        assertThatThrownBy(() -> reportSubmissionService.submitReport(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Select a report type");
    }

    // =========================================================================
    // SQS Publishing Tests — WIRTE-JOBSUB-TDQ paragraph migration (tests 15-17)
    // In CORPT00C.cbl, the WIRTE-JOBSUB-TDQ paragraph writes JCL card images
    // to the CICS Transient Data Queue (TDQ) named 'JOBS'. In Java, this is
    // replaced with SQS FIFO queue publishing via the AWS SDK v2 SqsClient.
    // The FIFO queue requires messageGroupId for ordering guarantees.
    // =========================================================================

    @Test
    @DisplayName("15. Successful submission → SQS sendMessage is called")
    void testSubmitReport_success_publishesSqsMessage() throws Exception {
        request.setMonthly(true);
        request.setConfirm("Y");

        when(objectMapper.writeValueAsString(any())).thenReturn("{\"reportType\":\"Monthly\"}");

        reportSubmissionService.submitReport(request);

        // Verify that the SQS client was invoked exactly once with a SendMessageRequest
        verify(sqsClient).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    @DisplayName("16. Successful submission → SendMessageRequest includes FIFO messageGroupId")
    void testSubmitReport_success_messageGroupId() throws Exception {
        request.setMonthly(true);
        request.setConfirm("Y");

        when(objectMapper.writeValueAsString(any())).thenReturn("{\"reportType\":\"Monthly\"}");

        reportSubmissionService.submitReport(request);

        // Capture the SendMessageRequest to verify FIFO queue parameters
        ArgumentCaptor<SendMessageRequest> sqsCaptor =
                ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(sqsCaptor.capture());

        SendMessageRequest capturedRequest = sqsCaptor.getValue();
        // The service uses MESSAGE_GROUP_ID = "report-submissions" for FIFO ordering
        assertThat(capturedRequest.messageGroupId()).isEqualTo("report-submissions");
        // FIFO queues require a deduplication ID — verify it is present
        assertThat(capturedRequest.messageDeduplicationId()).isNotNull();
        assertThat(capturedRequest.messageDeduplicationId()).isNotBlank();
        // Verify the queue URL matches what we set via reflection
        assertThat(capturedRequest.queueUrl()).isEqualTo(TEST_QUEUE_URL);
    }

    @Test
    @DisplayName("17. Successful submission → returns confirmation string with report name")
    void testSubmitReport_success_returnsConfirmationString() throws Exception {
        request.setMonthly(true);
        request.setConfirm("Y");

        when(objectMapper.writeValueAsString(any())).thenReturn("{\"reportType\":\"Monthly\"}");

        String result = reportSubmissionService.submitReport(request);

        // The service returns "<reportName> report submitted for printing ..."
        assertThat(result).isNotNull();
        assertThat(result).contains("Monthly");
        assertThat(result).contains("report submitted for printing");
    }

    // =========================================================================
    // SQS Error Handling Test — WIRTE-JOBSUB-TDQ error path (test 18)
    // In CORPT00C.cbl lines 515-535, if the TDQ WRITEQ fails, the program
    // displays 'Unable to Write TDQ (JOBS)...' and continues. In Java, the
    // SqsException is caught and re-thrown as CardDemoException preserving
    // the original COBOL error message text for traceability.
    // =========================================================================

    @Test
    @DisplayName("18. SQS send failure → CardDemoException wrapping SqsException")
    void testSubmitReport_sqsError_throwsCardDemoException() throws Exception {
        request.setMonthly(true);
        request.setConfirm("Y");

        when(objectMapper.writeValueAsString(any())).thenReturn("{\"reportType\":\"Monthly\"}");
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(SqsException.builder()
                        .message("Connection refused")
                        .build());

        assertThatThrownBy(() -> reportSubmissionService.submitReport(request))
                .isInstanceOf(CardDemoException.class)
                .hasMessageContaining("Unable to Write TDQ (JOBS)");
    }
}

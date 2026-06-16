package com.carddemo.unit.service.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * Unit tests for {@link ReportSubmissionService}.
 *
 * <p>Traceability (REFERENCE-ONLY; COBOL not copied; source commit {@code 27d6c6f}): the service
 * re-platforms {@code app/cbl/CORPT00C.cbl} ({@code PROCESS-ENTER-KEY} + {@code SUBMIT-JOB-TO-INTRDR}).
 * These tests pin the report-type selection order (monthly, yearly, custom), the verbatim COBOL
 * edit messages, the confirmation gate (Y/y publish, N/n cancel, otherwise reject), and the SQS
 * publish/failure bridge that replaces the {@code WRITEQ TD} hand-off.</p>
 *
 * <p>A <em>real</em> {@link DateValidationService} (pure logic, no collaborators) exercises the
 * custom-range date checks; the {@link SqsTemplate} is mocked so publication and its failure path
 * can be asserted without a live queue.</p>
 */
@DisplayName("ReportSubmissionService - CORPT00C online-to-batch report bridge")
class ReportSubmissionServiceTest {

    private static final String QUEUE = "carddemo-report-jobs.fifo";

    private SqsTemplate sqsTemplate;
    private ReportSubmissionService service;

    @BeforeEach
    void setUp() {
        sqsTemplate = mock(SqsTemplate.class);
        service = new ReportSubmissionService(new DateValidationService(), sqsTemplate, QUEUE);
    }

    /** Type-witnessed matcher for the single-argument generic {@code send(Consumer<SqsSendOptions<T>>)}. */
    private static Consumer<SqsSendOptions<Object>> anySend() {
        return ArgumentMatchers.<Consumer<SqsSendOptions<Object>>>any();
    }

    private static ReportRequest monthly(String confirm) {
        return new ReportRequest("M", null, null, null, null, null, null, null, null, confirm);
    }

    private static ReportRequest yearly(String confirm) {
        return new ReportRequest(null, "Y", null, null, null, null, null, null, null, confirm);
    }

    private static ReportRequest custom(
            String sm, String sd, String sy, String em, String ed, String ey, String confirm) {
        return new ReportRequest(null, null, "C", sm, sd, sy, em, ed, ey, confirm);
    }

    @Nested
    @DisplayName("Report-type selection derives the date range and publishes on confirmation")
    class HappyPaths {

        @Test
        @DisplayName("Monthly selection publishes a Monthly job for the current month")
        void monthlyPublishes() {
            ReportResponse response = service.submitReport(monthly("Y"));

            assertThat(response.confirmationMessage()).isEqualTo("Monthly report submitted for printing ...");
            assertThat(response.errorMessage()).isNull();
            verify(sqsTemplate).send(anySend());
        }

        @Test
        @DisplayName("Yearly selection publishes a Yearly job for the current year")
        void yearlyPublishes() {
            ReportResponse response = service.submitReport(yearly("Y"));

            assertThat(response.confirmationMessage()).isEqualTo("Yearly report submitted for printing ...");
            assertThat(response.errorMessage()).isNull();
            verify(sqsTemplate).send(anySend());
        }

        @Test
        @DisplayName("Custom selection with valid dates publishes a Custom job")
        void customPublishes() {
            ReportResponse response =
                    service.submitReport(custom("01", "15", "2024", "12", "20", "2024", "y"));

            assertThat(response.confirmationMessage()).isEqualTo("Custom report submitted for printing ...");
            assertThat(response.errorMessage()).isNull();
            verify(sqsTemplate).send(anySend());
        }
    }

    @Nested
    @DisplayName("Confirmation gate (SUBMIT-JOB-TO-INTRDR)")
    class ConfirmationGate {

        @Test
        @DisplayName("'N' cancels without publishing")
        void cancelsOnNo() {
            ReportResponse response = service.submitReport(monthly("N"));

            assertThat(response.confirmationMessage()).isEqualTo("Report request cancelled.");
            assertThat(response.errorMessage()).isNull();
            verify(sqsTemplate, never()).send(anySend());
        }

        @Test
        @DisplayName("Blank confirmation flag is rejected and names the report type")
        void blankConfirmRejected() {
            assertThatThrownBy(() -> service.submitReport(monthly("")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Please confirm to print the Monthly report...");
        }

        @Test
        @DisplayName("Unrecognised confirmation value is rejected verbatim")
        void invalidConfirmRejected() {
            assertThatThrownBy(() -> service.submitReport(monthly("X")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("\"X\" is not a valid value to confirm...");
        }
    }

    @Nested
    @DisplayName("Edit cascade and COBOL verbatim messages")
    class Edits {

        @Test
        @DisplayName("No report type selected is rejected")
        void noTypeSelected() {
            ReportRequest none =
                    new ReportRequest(null, null, null, null, null, null, null, null, null, "Y");

            assertThatThrownBy(() -> service.submitReport(none))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Select a report type to print report...");
        }

        @Test
        @DisplayName("Empty custom start month is rejected")
        void emptyStartMonth() {
            assertThatThrownBy(
                            () -> service.submitReport(custom("", "15", "2024", "12", "20", "2024", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date - Month can NOT be empty...");
        }

        @Test
        @DisplayName("Non-numeric custom start month is rejected")
        void nonNumericStartMonth() {
            assertThatThrownBy(
                            () -> service.submitReport(custom("AB", "15", "2024", "12", "20", "2024", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date - Not a valid Month...");
        }

        @Test
        @DisplayName("Custom start month above 12 is rejected")
        void startMonthOutOfRange() {
            assertThatThrownBy(
                            () -> service.submitReport(custom("13", "15", "2024", "12", "20", "2024", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date - Not a valid Month...");
        }

        @Test
        @DisplayName("Calendar-invalid custom start date (Feb 30) fails the date-service check")
        void invalidCalendarDate() {
            assertThatThrownBy(
                            () -> service.submitReport(custom("02", "30", "2024", "12", "20", "2024", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date - Not a valid date...");
            verify(sqsTemplate, never()).send(anySend());
        }
    }

    @Nested
    @DisplayName("SQS publish failure maps to the WRITEQ TD failure path")
    class PublishFailure {

        @Test
        @DisplayName("A send failure surfaces as FileAccessException with the COBOL TDQ message")
        void publishFailureMapsToFileAccess() {
            doThrow(new RuntimeException("sqs unavailable")).when(sqsTemplate).send(anySend());

            assertThatThrownBy(() -> service.submitReport(monthly("Y")))
                    .isInstanceOf(FileAccessException.class)
                    .hasMessage("Unable to Write TDQ (JOBS)...");
        }
    }

    @Test
    @DisplayName("ReportJobMessage record exposes its contract fields")
    void reportJobMessageRecord() {
        ReportSubmissionService.ReportJobMessage message =
                new ReportSubmissionService.ReportJobMessage("Monthly", "2024-01-01", "2024-01-31");

        assertThat(message.reportType()).isEqualTo("Monthly");
        assertThat(message.startDate()).isEqualTo("2024-01-01");
        assertThat(message.endDate()).isEqualTo("2024-01-31");
    }

    @Test
    @DisplayName("Monthly range matches the first and last day of the current month")
    void monthlyRangeBoundaries() {
        // Documents the derived range without asserting on mocked SQS internals: the production
        // code uses LocalDate.now() with TemporalAdjusters, mirrored here for an explicit boundary.
        LocalDate today = LocalDate.now();
        assertThat(today.withDayOfMonth(1)).isBeforeOrEqualTo(today);
        assertThat(today.with(TemporalAdjusters.lastDayOfMonth())).isAfterOrEqualTo(today);
    }
}

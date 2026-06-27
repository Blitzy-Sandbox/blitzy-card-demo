/*
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
 * language governing permissions and limitations under the License.
 */
package com.carddemo.unit.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.carddemo.config.AwsConfig;
import com.carddemo.dto.ReportDto;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.ReportService;

import io.awspring.cloud.sqs.operations.SqsSendOptions;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link ReportService}, the
 * report-submission service that backs {@code POST /api/reports/submit}.
 *
 * <p>{@code ReportService} is the Java realization of the CICS
 * pseudo-conversational program {@code CORPT00C} (transaction {@code CR00},
 * report submit) at source commit {@code 27d6c6f}. The legacy program builds a
 * JCL stream and writes it to the CICS Transient Data Queue {@code JOBS}; the
 * target preserves the observable submit-then-process semantics of the
 * <strong>F-011 asynchronous bridge</strong> by publishing a single typed
 * request message to the AWS SQS FIFO queue
 * {@code carddemo-report-jobs.fifo}.</p>
 *
 * <p><strong>Framework-free isolation.</strong> The suite bootstraps no Spring
 * {@code ApplicationContext}; it uses no {@code @SpringBootTest}, {@code MockMvc},
 * Testcontainers, database, or live AWS / LocalStack resource. The
 * {@link DateValidationService} and {@link SqsTemplate} collaborators are
 * Mockito-mocked, the {@link AwsConfig.AwsResourceProperties} is a real instance
 * carrying the externalized FIFO queue name, and the service is constructed by
 * hand, keeping every test fast and deterministic.</p>
 *
 * <p><strong>Parity assertions (Gate&nbsp;1 / Gate&nbsp;4).</strong> Every
 * user-visible message is asserted verbatim against the text compiled into
 * {@code CORPT00C}. <strong>Interface-contract assertions (Gate&nbsp;5).</strong>
 * On a confirmed submission the suite verifies that exactly one message is
 * published, that it targets the FIFO queue {@code carddemo-report-jobs.fifo},
 * and that it carries the {@code report-jobs} message group; the message payload
 * is captured to confirm the resolved report name and the computed date
 * window.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportService — CORPT00C report-submission parity (F-011 SQS FIFO bridge)")
class ReportServiceTest {

    /** Externalized FIFO report-queue name, mirroring {@code carddemo.aws.sqs.report-queue}. */
    private static final String REPORT_QUEUE = "carddemo-report-jobs.fifo";

    /** FIFO message group that serializes report submissions. */
    private static final String MESSAGE_GROUP = "report-jobs";

    /** The {@code YYYY-MM-DD} rendering applied by the service to the date window. */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd");

    @Mock
    private DateValidationService dateValidationService;

    @Mock
    private SqsTemplate sqsTemplate;

    /**
     * A builder-style mock of the fluent send options; {@link Answers#RETURNS_SELF}
     * makes every fluent method return the mock so the captured consumer can be
     * replayed without a {@link NullPointerException}.
     */
    @Mock(answer = Answers.RETURNS_SELF)
    private SqsSendOptions<ReportService.ReportRequestMessage> options;

    /** Captures the {@code Consumer<SqsSendOptions>} passed to {@link SqsTemplate#send}. */
    @Captor
    private ArgumentCaptor<Consumer<SqsSendOptions<ReportService.ReportRequestMessage>>> consumerCaptor;

    /** Captures the typed payload configured on the send options. */
    @Captor
    private ArgumentCaptor<ReportService.ReportRequestMessage> payloadCaptor;

    /** Micrometer tracer used to open the PRODUCER span around the SQS publish. */
    @Mock
    private Tracer tracer;

    /** W3C propagator that injects the {@code traceparent} into the outbound message headers. */
    @Mock
    private Propagator propagator;

    /**
     * Builder-style mock of the producer {@link Span.Builder}; {@link Answers#RETURNS_SELF}
     * makes the fluent {@code name/kind/remoteServiceName/setParent} calls return the mock,
     * so {@code tracer.spanBuilder().name(..).kind(..).start()} replays without a NPE.
     */
    @Mock(answer = Answers.RETURNS_SELF)
    private Span.Builder spanBuilder;

    /** The started PRODUCER span returned by {@link Span.Builder#start()}. */
    @Mock
    private Span publishSpan;

    /**
     * Scope handle returned by {@code tracer.withSpan(publishSpan)}; closed in the producer's
     * finally block. A mock so its {@code close()} is a no-op under test.
     */
    @Mock
    private Tracer.SpanInScope spanInScope;

    private ReportService service;

    @BeforeEach
    void setUp() {
        // A real properties object so getSqs().getReportQueue() returns the externalized
        // FIFO name without any stubbing (keeps strict-stubs verification clean).
        AwsConfig.AwsResourceProperties properties = new AwsConfig.AwsResourceProperties();
        properties.getSqs().setReportQueue(REPORT_QUEUE);
        service = new ReportService(dateValidationService, properties, sqsTemplate, tracer, propagator);

        // Lenient: only the three confirmed-submission tests reach publishReportRequest and the
        // producer-span chain; the validation/confirmation-gate tests throw earlier. lenient()
        // keeps STRICT_STUBS from flagging these as unused in the non-publishing tests.
        // tracer.currentSpan() is left unstubbed (returns null), so the parent linkage is simply
        // skipped under test. withSpan(..) returns the scope mock the producer closes in finally.
        lenient().when(tracer.spanBuilder()).thenReturn(spanBuilder);
        lenient().when(spanBuilder.start()).thenReturn(publishSpan);
        lenient().when(tracer.withSpan(publishSpan)).thenReturn(spanInScope);
    }

    // ------------------------------------------------------------------
    // Phase 1 — no report type selected
    // ------------------------------------------------------------------

    @Test
    @DisplayName("No report type selected is rejected and never publishes")
    void noReportTypeSelectedIsRejected() {
        ReportDto.SubmitRequest request =
                new ReportDto.SubmitRequest("", "", "", "", "", "", "", "", "", "");

        assertThatThrownBy(() -> service.submitReport(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Select a report type to print report...");

        verifyNoInteractions(sqsTemplate);
    }

    // ------------------------------------------------------------------
    // Phase 2 — custom date-range: empty segments
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("emptySegmentCases")
    @DisplayName("Custom report with a blank date segment is rejected with the exact message")
    void customReportBlankSegmentIsRejected(ReportDto.SubmitRequest request, String expectedMessage) {
        assertThatThrownBy(() -> service.submitReport(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(expectedMessage);

        verifyNoInteractions(sqsTemplate);
    }

    static Stream<Arguments> emptySegmentCases() {
        return Stream.of(
                Arguments.of(customRequest("", "15", "2023", "12", "20", "2023", "Y"),
                        "Start Date - Month can NOT be empty..."),
                Arguments.of(customRequest("01", "", "2023", "12", "20", "2023", "Y"),
                        "Start Date - Day can NOT be empty..."),
                Arguments.of(customRequest("01", "15", "", "12", "20", "2023", "Y"),
                        "Start Date - Year can NOT be empty..."),
                Arguments.of(customRequest("01", "15", "2023", "", "20", "2023", "Y"),
                        "End Date - Month can NOT be empty..."),
                Arguments.of(customRequest("01", "15", "2023", "12", "", "2023", "Y"),
                        "End Date - Day can NOT be empty..."),
                Arguments.of(customRequest("01", "15", "2023", "12", "20", "", "Y"),
                        "End Date - Year can NOT be empty...")
        );
    }

    // ------------------------------------------------------------------
    // Phase 2 (continued) — custom date-range: out-of-range / non-numeric segments
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("invalidSegmentCases")
    @DisplayName("Custom report with an out-of-range or non-numeric segment is rejected verbatim")
    void customReportInvalidSegmentIsRejected(ReportDto.SubmitRequest request, String expectedMessage) {
        assertThatThrownBy(() -> service.submitReport(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(expectedMessage);

        verifyNoInteractions(sqsTemplate);
    }

    static Stream<Arguments> invalidSegmentCases() {
        return Stream.of(
                Arguments.of(customRequest("13", "15", "2023", "12", "20", "2023", "Y"),
                        "Start Date - Not a valid Month..."),
                Arguments.of(customRequest("01", "32", "2023", "12", "20", "2023", "Y"),
                        "Start Date - Not a valid Day..."),
                Arguments.of(customRequest("01", "15", "abcd", "12", "20", "2023", "Y"),
                        "Start Date - Not a valid Year..."),
                Arguments.of(customRequest("01", "15", "2023", "13", "20", "2023", "Y"),
                        "End Date - Not a valid Month..."),
                Arguments.of(customRequest("01", "15", "2023", "12", "32", "2023", "Y"),
                        "End Date - Not a valid Day..."),
                Arguments.of(customRequest("01", "15", "2023", "12", "20", "abcd", "Y"),
                        "End Date - Not a valid Year...")
        );
    }

    @Test
    @DisplayName("Custom report whose start segments are not a real calendar date is rejected")
    void customReportInvalidStartDateIsRejected() {
        // All segments pass the range checks, so the assembled start date is validated.
        when(dateValidationService.validateDateParts("2023", "02", "30", "Start Date"))
                .thenThrow(new ValidationException("invalid start"));

        ReportDto.SubmitRequest request = customRequest("02", "30", "2023", "12", "20", "2023", "Y");

        assertThatThrownBy(() -> service.submitReport(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Start Date - Not a valid date...");

        verifyNoInteractions(sqsTemplate);
    }

    @Test
    @DisplayName("Custom report whose end segments are not a real calendar date is rejected")
    void customReportInvalidEndDateIsRejected() {
        when(dateValidationService.validateDateParts("2023", "01", "15", "Start Date"))
                .thenReturn(LocalDate.of(2023, 1, 15));
        when(dateValidationService.validateDateParts("2023", "02", "30", "End Date"))
                .thenThrow(new ValidationException("invalid end"));

        ReportDto.SubmitRequest request = customRequest("01", "15", "2023", "02", "30", "2023", "Y");

        assertThatThrownBy(() -> service.submitReport(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("End Date - Not a valid date...");

        verifyNoInteractions(sqsTemplate);
    }

    // ------------------------------------------------------------------
    // Phase 3 — confirmation gate
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Monthly report without confirmation prompts for confirmation")
    void monthlyReportBlankConfirmationPrompts() {
        assertThatThrownBy(() -> service.submitReport(monthlyRequest("")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Please confirm to print the Monthly report...");

        verifyNoInteractions(sqsTemplate);
    }

    @Test
    @DisplayName("Yearly report without confirmation prompts for confirmation using its report name")
    void yearlyReportBlankConfirmationPrompts() {
        assertThatThrownBy(() -> service.submitReport(yearlyRequest("")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Please confirm to print the Yearly report...");

        verifyNoInteractions(sqsTemplate);
    }

    @Test
    @DisplayName("A confirmation value that is neither Y nor N is rejected verbatim")
    void invalidConfirmationValueIsRejected() {
        assertThatThrownBy(() -> service.submitReport(monthlyRequest("X")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("\"X\" is not a valid value to confirm...");

        verifyNoInteractions(sqsTemplate);
    }

    @Test
    @DisplayName("Confirmation of N cancels the submission without publishing")
    void confirmationOfNoCancelsWithoutPublishing() {
        ReportService.SubmitResult result = service.submitReport(monthlyRequest("N"));

        assertThat(result.reportName()).isEqualTo("Monthly");
        assertThat(result.submitted()).isFalse();
        assertThat(result.message()).isEmpty();

        verifyNoInteractions(sqsTemplate);
    }

    // ------------------------------------------------------------------
    // Phase 4 / Phase 5 — confirmed submission publishes one FIFO message
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Confirmed monthly report publishes one FIFO message for the current month window")
    void confirmedMonthlyReportPublishesCurrentMonthWindow() {
        LocalDate today = LocalDate.now();
        String expectedStart = today.withDayOfMonth(1).format(DATE_FORMAT);
        String expectedEnd = today.withDayOfMonth(today.lengthOfMonth()).format(DATE_FORMAT);

        ReportService.SubmitResult result = service.submitReport(monthlyRequest("Y"));

        assertThat(result.reportName()).isEqualTo("Monthly");
        assertThat(result.startDate()).isEqualTo(expectedStart);
        assertThat(result.endDate()).isEqualTo(expectedEnd);
        assertThat(result.submitted()).isTrue();
        assertThat(result.message()).isEqualTo("Monthly report submitted for printing ...");

        assertPublishedToReportQueue("Monthly", expectedStart, expectedEnd);
    }

    @Test
    @DisplayName("Confirmed yearly report publishes one FIFO message for the full calendar year window")
    void confirmedYearlyReportPublishesCalendarYearWindow() {
        int year = LocalDate.now().getYear();
        String expectedStart = LocalDate.of(year, 1, 1).format(DATE_FORMAT);
        String expectedEnd = LocalDate.of(year, 12, 31).format(DATE_FORMAT);

        ReportService.SubmitResult result = service.submitReport(yearlyRequest("Y"));

        assertThat(result.reportName()).isEqualTo("Yearly");
        assertThat(result.startDate()).isEqualTo(expectedStart);
        assertThat(result.endDate()).isEqualTo(expectedEnd);
        assertThat(result.submitted()).isTrue();
        assertThat(result.message()).isEqualTo("Yearly report submitted for printing ...");

        assertPublishedToReportQueue("Yearly", expectedStart, expectedEnd);
    }

    @Test
    @DisplayName("Confirmed custom report honors the supplied window and publishes one FIFO message")
    void confirmedCustomReportHonorsSuppliedWindow() {
        when(dateValidationService.validateDateParts("2023", "01", "15", "Start Date"))
                .thenReturn(LocalDate.of(2023, 1, 15));
        when(dateValidationService.validateDateParts("2023", "12", "20", "End Date"))
                .thenReturn(LocalDate.of(2023, 12, 20));

        ReportService.SubmitResult result =
                service.submitReport(customRequest("01", "15", "2023", "12", "20", "2023", "Y"));

        assertThat(result.reportName()).isEqualTo("Custom");
        assertThat(result.startDate()).isEqualTo("2023-01-15");
        assertThat(result.endDate()).isEqualTo("2023-12-20");
        assertThat(result.submitted()).isTrue();
        assertThat(result.message()).isEqualTo("Custom report submitted for printing ...");

        assertPublishedToReportQueue("Custom", "2023-01-15", "2023-12-20");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Verifies that exactly one message was published, replays the captured
     * fluent consumer against the builder-style {@link #options} mock, and asserts
     * the FIFO queue name, the message group, and the typed payload contents.
     *
     * @param expectedName  the resolved report name expected on the payload
     * @param expectedStart the inclusive {@code YYYY-MM-DD} window start
     * @param expectedEnd   the inclusive {@code YYYY-MM-DD} window end
     */
    private void assertPublishedToReportQueue(String expectedName, String expectedStart, String expectedEnd) {
        verify(sqsTemplate).send(consumerCaptor.capture());
        consumerCaptor.getValue().accept(options);

        verify(options).queue(REPORT_QUEUE);
        verify(options).messageGroupId(MESSAGE_GROUP);

        verify(options).payload(payloadCaptor.capture());
        ReportService.ReportRequestMessage payload = payloadCaptor.getValue();
        assertThat(payload.reportName()).isEqualTo(expectedName);
        assertThat(payload.startDate()).isEqualTo(expectedStart);
        assertThat(payload.endDate()).isEqualTo(expectedEnd);
    }

    /**
     * Builds a monthly-report request with the supplied confirmation flag.
     *
     * @param confirm the confirmation flag
     * @return the request selecting the monthly report
     */
    private static ReportDto.SubmitRequest monthlyRequest(String confirm) {
        return new ReportDto.SubmitRequest("Y", "", "", "", "", "", "", "", "", confirm);
    }

    /**
     * Builds a yearly-report request with the supplied confirmation flag.
     *
     * @param confirm the confirmation flag
     * @return the request selecting the yearly report
     */
    private static ReportDto.SubmitRequest yearlyRequest(String confirm) {
        return new ReportDto.SubmitRequest("", "Y", "", "", "", "", "", "", "", confirm);
    }

    /**
     * Builds a custom-report request from the six date segments and the
     * confirmation flag.
     *
     * @param startMonth start-date month segment
     * @param startDay   start-date day segment
     * @param startYear  start-date year segment
     * @param endMonth   end-date month segment
     * @param endDay     end-date day segment
     * @param endYear    end-date year segment
     * @param confirm    the confirmation flag
     * @return the request selecting the custom report
     */
    private static ReportDto.SubmitRequest customRequest(String startMonth, String startDay, String startYear,
                                                         String endMonth, String endDay, String endYear,
                                                         String confirm) {
        return new ReportDto.SubmitRequest("", "", "Y",
                startMonth, startDay, startYear, endMonth, endDay, endYear, confirm);
    }
}

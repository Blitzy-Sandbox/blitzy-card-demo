package com.carddemo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.carddemo.dto.ReportRequest;
import com.carddemo.dto.ReportResponse;
import com.carddemo.exception.DateValidationException;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.ReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Web-layer unit test for {@link ReportController} &mdash; the REST controller migrated from the
 * legacy CICS/BMS program {@code CORPT00C} (transaction {@code CR00}, "Transaction Reports") at
 * frozen source commit SHA {@code 27d6c6f}.
 *
 * <p>The suite drives the controller through {@link MockMvc} with {@link ReportService} supplied as
 * a Mockito mock, so it loads <strong>no</strong> Spring application context, no security
 * configuration, no database, and no AWS/SQS &mdash; keeping it fast, deterministic, and fully
 * decoupled from the other modules assembled in parallel. The shared {@link GlobalExceptionHandler}
 * advice is registered so domain exceptions map to their HTTP statuses (400) exactly as they do in
 * production, and the Jackson converter is configured with the JSR-310 module so {@link LocalDate}
 * fields serialize as ISO-8601 strings, matching the application's message converter.</p>
 *
 * <p>No method-validation AOP proxy is needed here (unlike controllers with {@code @Min}/{@code @Pattern}
 * path or query parameters): the controller's only constraint is {@code @Valid @RequestBody}, which the
 * standalone setup enforces natively &mdash; a bad report type raises
 * {@code MethodArgumentNotValidException} that the advice renders as HTTP&nbsp;400.</p>
 *
 * <p>Behavioural assertions cover the AAP requirements for this file: a valid request returns
 * HTTP&nbsp;{@code 202 Accepted} carrying the {@code ACCEPTED} acknowledgement and its opaque
 * {@code jobId} (never {@code 200}/{@code 201}), the request is delegated to the service exactly
 * once, an invalid report type is rejected by bean validation as HTTP&nbsp;400 before the service is
 * reached, and a service-raised {@code ValidationException} / {@code DateValidationException}
 * (missing or invalid CUSTOM dates) surfaces as HTTP&nbsp;400 through the advice rather than being
 * caught in the controller.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportController — transaction report request (CORPT00C / CR00) REST endpoint")
class ReportControllerTest {

    private static final String JOB_ID = "11111111-2222-3333-4444-555555555555";

    @Mock
    private ReportService reportService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ReportController controller = new ReportController(reportService);

        // Mirror the running application's Jackson configuration: register the JSR-310 module and
        // disable WRITE_DATES_AS_TIMESTAMPS so LocalDate (de)serializes as ISO-8601 strings (e.g.
        // "2025-02-01"), exactly as Spring Boot's auto-configured MVC converter does. ReportResponse's
        // date fields carry no @JsonFormat, so without this they would serialize as numeric arrays.
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));

        // Standalone setup registers a default bean validator, so @Valid @RequestBody is enforced and
        // the shared advice maps every failure exactly as in the running application. No method-
        // validation proxy is required because there are no @Min/@Pattern method parameters here.
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(converter)
                .build();
    }

    @Nested
    @DisplayName("POST /api/reports — accepted (HTTP 202)")
    class Accepted {

        @Test
        @DisplayName("MONTHLY confirmed -> 202 ACCEPTED with jobId; service invoked once with the request")
        void monthlyReturns202AcceptedWithJobId() throws Exception {
            ReportResponse accepted = ReportResponse.accepted(
                    JOB_ID, "MONTHLY",
                    LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31),
                    "Monthly", "Monthly report submitted for printing ...");
            when(reportService.generateReport(any(ReportRequest.class))).thenReturn(accepted);

            mockMvc.perform(post("/api/reports")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reportType\":\"MONTHLY\",\"confirm\":true}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value(ReportResponse.STATUS_ACCEPTED))
                    .andExpect(jsonPath("$.jobId").value(JOB_ID))
                    .andExpect(jsonPath("$.reportType").value("MONTHLY"))
                    .andExpect(jsonPath("$.message").value("Monthly report submitted for printing ..."));

            // The controller must delegate exactly once, forwarding the bound request unchanged.
            ArgumentCaptor<ReportRequest> captor = ArgumentCaptor.forClass(ReportRequest.class);
            verify(reportService).generateReport(captor.capture());
            assertThat(captor.getValue().reportType()).isEqualTo("MONTHLY");
            assertThat(captor.getValue().confirm()).isTrue();
        }

        @Test
        @DisplayName("CUSTOM confirmed with dates -> 202 and the reporting window is echoed as ISO-8601")
        void customAcceptedEchoesDates() throws Exception {
            LocalDate start = LocalDate.of(2025, 2, 1);
            LocalDate end = LocalDate.of(2025, 2, 28);
            ReportResponse accepted = ReportResponse.accepted(
                    JOB_ID, "CUSTOM", start, end,
                    "Custom", "Custom report submitted for printing ...");
            when(reportService.generateReport(any(ReportRequest.class))).thenReturn(accepted);

            mockMvc.perform(post("/api/reports")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reportType\":\"CUSTOM\",\"startDate\":\"2025-02-01\","
                                    + "\"endDate\":\"2025-02-28\",\"confirm\":true}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value(ReportResponse.STATUS_ACCEPTED))
                    .andExpect(jsonPath("$.jobId").value(JOB_ID))
                    .andExpect(jsonPath("$.startDate").value("2025-02-01"))
                    .andExpect(jsonPath("$.endDate").value("2025-02-28"));

            // The CUSTOM dates must be bound from JSON and forwarded to the service unchanged.
            ArgumentCaptor<ReportRequest> captor = ArgumentCaptor.forClass(ReportRequest.class);
            verify(reportService).generateReport(captor.capture());
            assertThat(captor.getValue().startDate()).isEqualTo(start);
            assertThat(captor.getValue().endDate()).isEqualTo(end);
        }
    }

    @Nested
    @DisplayName("POST /api/reports — rejected (HTTP 400)")
    class Rejected {

        @Test
        @DisplayName("bad report type (DAILY) violates @Pattern -> 400, service never called")
        void badReportTypeReturns400() throws Exception {
            mockMvc.perform(post("/api/reports")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reportType\":\"DAILY\",\"confirm\":true}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(reportService);
        }

        @Test
        @DisplayName("blank report type violates @NotBlank -> 400, service never called")
        void blankReportTypeReturns400() throws Exception {
            mockMvc.perform(post("/api/reports")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reportType\":\"\",\"confirm\":true}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(reportService);
        }

        @Test
        @DisplayName("missing report type violates @NotBlank -> 400, service never called")
        void missingReportTypeReturns400() throws Exception {
            mockMvc.perform(post("/api/reports")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"confirm\":true}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(reportService);
        }

        @Test
        @DisplayName("CUSTOM with missing dates -> 400 (service throws ValidationException); service invoked once")
        void customMissingDatesReturns400() throws Exception {
            // reportType=CUSTOM passes @Pattern, so the request reaches the service, which performs the
            // cross-field date check and throws ValidationException (HTTP 400 via GlobalExceptionHandler).
            when(reportService.generateReport(any(ReportRequest.class)))
                    .thenThrow(new ValidationException("Start Date - can NOT be empty..."));

            mockMvc.perform(post("/api/reports")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reportType\":\"CUSTOM\",\"confirm\":true}"))
                    .andExpect(status().isBadRequest());

            verify(reportService).generateReport(any(ReportRequest.class));
        }

        @Test
        @DisplayName("CUSTOM date rejected by the service -> 400 (service throws DateValidationException); service invoked once")
        void customDateValidationExceptionReturns400() throws Exception {
            // Well-formed dates that bind successfully and reach the service, whose CSUTLDTC-equivalent
            // date validator rejects them -> DateValidationException (HTTP 400 via GlobalExceptionHandler).
            when(reportService.generateReport(any(ReportRequest.class)))
                    .thenThrow(new DateValidationException("Start Date - Not a valid date..."));

            mockMvc.perform(post("/api/reports")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reportType\":\"CUSTOM\",\"startDate\":\"2025-02-01\","
                                    + "\"endDate\":\"2025-03-31\",\"confirm\":true}"))
                    .andExpect(status().isBadRequest());

            verify(reportService).generateReport(any(ReportRequest.class));
        }
    }
}

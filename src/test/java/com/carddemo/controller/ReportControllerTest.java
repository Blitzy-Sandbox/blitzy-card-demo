package com.carddemo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.carddemo.config.SecurityConfig;
import com.carddemo.dto.ReportRequest;
import com.carddemo.dto.ReportResponse;
import com.carddemo.exception.DateValidationException;
import com.carddemo.exception.ValidationException;
import com.carddemo.observability.CorrelationIdFilter;
import com.carddemo.service.JwtService;
import com.carddemo.service.ReportService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Web-slice test for {@link ReportController} &mdash; the stateless REST replacement for the legacy
 * CICS/BMS transaction-report program {@code CORPT00C} (transaction {@code CR00}, &quot;Transaction
 * Reports&quot;). The frozen COBOL source is referenced read-only at commit SHA {@code 27d6c6f} and
 * is never copied into the target.
 *
 * <h2>Legacy behaviour under verification</h2>
 * <p>{@code CORPT00C} presented the {@code CORPT00} 3270 map on which an operator chose a
 * <strong>Monthly</strong> ({@code CORPT00C} L214), <strong>Yearly</strong> (L240), or
 * <strong>Custom</strong> (L433) reporting window, was gated on the {@code CONFIRMI} confirm flag
 * (L464, prompt &quot;Please confirm to print the &hellip;&quot; L466), and, once confirmed, wrote a
 * JCL job stream to an extra-partition Transient Data Queue (the TDQ&nbsp;&rarr;&nbsp;JES bridge,
 * paragraph {@code SUBMIT-JOB-TO-INTRDR}). The migration re-expresses that order-preserving,
 * fire-and-forget bridge as an <strong>SQS FIFO</strong>-triggered Spring Batch launch
 * ({@code carddemo-report-jobs.fifo}), so report submission is <strong>asynchronous</strong>:
 * {@code POST /api/reports} returns HTTP&nbsp;{@code 202 Accepted} with an opaque job handle rather
 * than a rendered report. This suite pins that batch-trigger contract (Gate&nbsp;5); the real SQS
 * interaction is exercised separately by the Testcontainers/LocalStack integration tests.</p>
 *
 * <h2>Harness</h2>
 * <p>The test uses a {@link WebMvcTest @WebMvcTest} slice around {@code ReportController} and
 * {@link Import @Import}s the real {@link SecurityConfig} and {@link CorrelationIdFilter}, so the
 * production security filter chain (the {@code anyRequest().authenticated()} rule that guards
 * {@code /api/reports}) and the per-request correlation-id propagation are assembled exactly as they
 * run in production. Because the slice wires Spring Security, {@code spring-security-test}'s
 * {@link WithMockUser @WithMockUser} establishes the caller's authorities (the {@code roles}
 * attribute auto-prefixes {@code ROLE_}); a test with no annotation exercises the anonymous path.
 * The two collaborators are supplied as Mockito test doubles: {@link ReportService} (the behaviour
 * under delegation) and {@link JwtService} (required only to satisfy the {@link SecurityConfig}
 * constructor; the bearer filter is never triggered because these requests carry no
 * {@code Authorization} header). The shared {@link GlobalExceptionHandler}
 * {@code @RestControllerAdvice} is auto-detected by the slice, so a bean-validation failure and a
 * service-raised domain exception map to their production HTTP statuses and JSON bodies.</p>
 *
 * <h2>On {@code @MockitoBean} vs {@code @MockBean}</h2>
 * <p>The AAP harness sketch names {@code @MockBean}, but that Spring Boot annotation is deprecated
 * for removal since Boot&nbsp;3.4; referencing it would raise a {@code [removal]} warning under the
 * project's {@code -Xlint:all} build and breach Gate&nbsp;2 (zero-warning). This test therefore uses
 * the functionally identical, non-deprecated {@link MockitoBean @MockitoBean} (Spring
 * Framework&nbsp;6.2) to register the same mocks in the slice context &mdash; preserving the intended
 * harness (real {@code SecurityConfig} + real {@code CorrelationIdFilter}, mocked {@code ReportService}
 * and {@code JwtService}, autowired {@link MockMvc} and {@link ObjectMapper}) while keeping the
 * compilation warning-free, consistent with the sibling controller web-slice tests.</p>
 *
 * <h2>What is asserted</h2>
 * <ul>
 *   <li>a valid submission (MONTHLY / YEARLY / CUSTOM) returns HTTP&nbsp;{@code 202 Accepted}
 *       &mdash; never {@code 200}/{@code 201} &mdash; carrying {@code status == "ACCEPTED"} and the
 *       opaque {@code jobId}, and the request is delegated to the service exactly once;</li>
 *   <li>an invalid or blank {@code reportType} is rejected by {@code @Valid} bean validation as
 *       HTTP&nbsp;{@code 400} <em>before</em> the service is reached ({@code code == "VALIDATION_ERROR"},
 *       {@code fieldErrors} name {@code reportType});</li>
 *   <li>a service-raised {@link ValidationException} (for example missing CUSTOM dates) surfaces as
 *       HTTP&nbsp;{@code 400} through the advice;</li>
 *   <li>an anonymous caller is refused with HTTP&nbsp;{@code 401} and never reaches the service; and</li>
 *   <li>every error response carries the observability correlation id (MDC {@code correlationId} in
 *       the body and the {@code X-Correlation-Id} response header).</li>
 * </ul>
 */
@WebMvcTest(controllers = ReportController.class)
@Import({SecurityConfig.class, CorrelationIdFilter.class})
@DisplayName("ReportController — transaction report request (CORPT00C / CR00) REST endpoint")
class ReportControllerTest {

    /** The single endpoint under test: the transaction-report submission route. */
    private static final String REPORTS_PATH = "/api/reports";

    /** Regular-user role; {@code /api/reports} is reachable by any authenticated caller. */
    private static final String ROLE_USER = "USER";

    /** Report-type selector migrated from the {@code CORPT00} {@code MONTHLY} map flag. */
    private static final String TYPE_MONTHLY = "MONTHLY";

    /** Report-type selector migrated from the {@code CORPT00} {@code YEARLY} map flag. */
    private static final String TYPE_YEARLY = "YEARLY";

    /** Report-type selector migrated from the {@code CORPT00} {@code CUSTOM} map flag. */
    private static final String TYPE_CUSTOM = "CUSTOM";

    /** Domain error code the advice emits for a Jakarta bean-validation failure. */
    private static final String CODE_VALIDATION_ERROR = "VALIDATION_ERROR";

    /** Stable, opaque job handle returned by the (mocked) service on acceptance. */
    private static final String JOB_ID = "11111111-2222-3333-4444-555555555555";

    /** Inclusive start of the CUSTOM reporting window used by the CUSTOM tests. */
    private static final LocalDate CUSTOM_START = LocalDate.of(2025, 2, 1);

    /** Inclusive end of the CUSTOM reporting window used by the CUSTOM tests. */
    private static final LocalDate CUSTOM_END = LocalDate.of(2025, 2, 28);

    /** MockMvc bound to the {@code ReportController} slice with the real security filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /** Slice-configured Jackson mapper (JSR-310 enabled) used to render request bodies. */
    @Autowired
    private ObjectMapper objectMapper;

    /** The delegated report-launch service; stubbed per test to isolate the controller's HTTP contract. */
    @MockitoBean
    private ReportService reportService;

    /** Present only to satisfy the {@link SecurityConfig} constructor; never invoked by these tests. */
    @MockitoBean
    private JwtService jwtService;

    /**
     * Serializes a {@link ReportRequest} to its JSON wire form using the slice's autowired
     * {@link ObjectMapper}, so {@link LocalDate} fields render as ISO-8601 strings exactly as an HTTP
     * client would send them.
     *
     * @param request the request DTO to serialize (never {@code null})
     * @return the JSON body string
     * @throws Exception if serialization fails
     */
    private String toJson(final ReportRequest request) throws Exception {
        return objectMapper.writeValueAsString(request);
    }

    /**
     * {@code POST /api/reports} accepted paths &mdash; a confirmed, well-formed request is enqueued
     * for asynchronous batch generation and acknowledged with HTTP&nbsp;{@code 202 Accepted}. The
     * controller is a thin delegate, so the (mocked) service supplies the acknowledgement and the
     * controller's only responsibility is to return it under the correct status.
     */
    @Nested
    @DisplayName("POST /api/reports — accepted (HTTP 202, async batch-trigger contract)")
    class Accepted {

        @Test
        @WithMockUser(roles = ROLE_USER)
        @DisplayName("MONTHLY confirmed -> 202 ACCEPTED with jobId; service invoked once with the bound request")
        void monthlyReturns202Accepted() throws Exception {
            final ReportResponse acknowledgement = ReportResponse.accepted(
                    JOB_ID, TYPE_MONTHLY,
                    LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31),
                    "Monthly", "Monthly report submitted for printing ...");
            when(reportService.generateReport(any(ReportRequest.class))).thenReturn(acknowledgement);

            mockMvc.perform(post(REPORTS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(new ReportRequest(TYPE_MONTHLY, null, null, true))))
                    // 202, NOT 200: the report is produced out-of-band by the SQS-triggered batch job.
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value(ReportResponse.STATUS_ACCEPTED))
                    .andExpect(jsonPath("$.jobId").value(JOB_ID))
                    .andExpect(jsonPath("$.reportType").value(TYPE_MONTHLY))
                    .andExpect(jsonPath("$.message").value("Monthly report submitted for printing ..."));

            // The controller must delegate exactly once, forwarding the bound request unchanged.
            final ArgumentCaptor<ReportRequest> captor = ArgumentCaptor.forClass(ReportRequest.class);
            verify(reportService).generateReport(captor.capture());
            assertThat(captor.getValue().reportType()).isEqualTo(TYPE_MONTHLY);
            assertThat(captor.getValue().confirm()).isTrue();
        }

        @Test
        @WithMockUser(roles = ROLE_USER)
        @DisplayName("YEARLY confirmed -> 202 ACCEPTED; service invoked once")
        void yearlyReturns202Accepted() throws Exception {
            final ReportResponse acknowledgement = ReportResponse.accepted(
                    JOB_ID, TYPE_YEARLY,
                    LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                    "Yearly", "Yearly report submitted for printing ...");
            when(reportService.generateReport(any(ReportRequest.class))).thenReturn(acknowledgement);

            mockMvc.perform(post(REPORTS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(new ReportRequest(TYPE_YEARLY, null, null, true))))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value(ReportResponse.STATUS_ACCEPTED))
                    .andExpect(jsonPath("$.jobId").value(JOB_ID))
                    .andExpect(jsonPath("$.reportType").value(TYPE_YEARLY));

            verify(reportService).generateReport(any(ReportRequest.class));
        }

        @Test
        @WithMockUser(roles = ROLE_USER)
        @DisplayName("CUSTOM confirmed with dates -> 202; the reporting window is bound and echoed as ISO-8601")
        void customWithDatesReturns202Accepted() throws Exception {
            final ReportResponse acknowledgement = ReportResponse.accepted(
                    JOB_ID, TYPE_CUSTOM, CUSTOM_START, CUSTOM_END,
                    "Custom", "Custom report submitted for printing ...");
            when(reportService.generateReport(any(ReportRequest.class))).thenReturn(acknowledgement);

            mockMvc.perform(post(REPORTS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(new ReportRequest(TYPE_CUSTOM, CUSTOM_START, CUSTOM_END, true))))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value(ReportResponse.STATUS_ACCEPTED))
                    .andExpect(jsonPath("$.jobId").value(JOB_ID))
                    .andExpect(jsonPath("$.reportType").value(TYPE_CUSTOM))
                    .andExpect(jsonPath("$.startDate").value("2025-02-01"))
                    .andExpect(jsonPath("$.endDate").value("2025-02-28"));

            // The CUSTOM window must be bound from JSON and forwarded to the service unchanged.
            final ArgumentCaptor<ReportRequest> captor = ArgumentCaptor.forClass(ReportRequest.class);
            verify(reportService).generateReport(captor.capture());
            assertThat(captor.getValue().startDate()).isEqualTo(CUSTOM_START);
            assertThat(captor.getValue().endDate()).isEqualTo(CUSTOM_END);
        }
    }

    /**
     * {@code POST /api/reports} rejected paths &mdash; a request that fails the DTO {@code @Pattern} /
     * {@code @NotBlank} edits is refused by bean validation <em>before</em> the controller body runs
     * (the service is never consulted), while the CUSTOM cross-field date rules are enforced inside
     * the service and surface through the advice. Both render HTTP&nbsp;{@code 400} with the shared
     * {@code ErrorResponse} JSON contract.
     */
    @Nested
    @DisplayName("POST /api/reports — rejected (HTTP 400)")
    class Rejected {

        @Test
        @WithMockUser(roles = ROLE_USER)
        @DisplayName("bad report type (WEEKLY) violates @Pattern -> 400 VALIDATION_ERROR naming reportType; service never called")
        void badReportTypeReturns400() throws Exception {
            mockMvc.perform(post(REPORTS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(new ReportRequest("WEEKLY", null, null, true))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CODE_VALIDATION_ERROR))
                    // The rejected field must be named so the client can correct the offending input.
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='reportType')]").isNotEmpty());

            // @Pattern fails during argument binding, so the controller body (and the service) never runs.
            verifyNoInteractions(reportService);
        }

        @Test
        @WithMockUser(roles = ROLE_USER)
        @DisplayName("blank report type violates @NotBlank -> 400; service never called")
        void blankReportTypeReturns400() throws Exception {
            mockMvc.perform(post(REPORTS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(new ReportRequest("", null, null, true))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CODE_VALIDATION_ERROR))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='reportType')]").isNotEmpty());

            verifyNoInteractions(reportService);
        }

        @Test
        @WithMockUser(roles = ROLE_USER)
        @DisplayName("missing report type violates @NotBlank -> 400; service never called")
        void missingReportTypeReturns400() throws Exception {
            mockMvc.perform(post(REPORTS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(new ReportRequest(null, null, null, true))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CODE_VALIDATION_ERROR))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='reportType')]").isNotEmpty());

            verifyNoInteractions(reportService);
        }

        @Test
        @WithMockUser(roles = ROLE_USER)
        @DisplayName("CUSTOM missing dates -> 400 (service raises ValidationException); service invoked once, body mapped")
        void customMissingDatesReturns400() throws Exception {
            // reportType=CUSTOM passes @Pattern, so the request reaches the service. The service performs
            // the CORPT00C custom-date requirement check (both dates mandatory) and raises a
            // ValidationException, which GlobalExceptionHandler maps to HTTP 400 with the ErrorResponse body.
            final String detail = "Custom report requires start and end dates";
            when(reportService.generateReport(any(ReportRequest.class)))
                    .thenThrow(new ValidationException(detail));

            mockMvc.perform(post(REPORTS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(new ReportRequest(TYPE_CUSTOM, null, null, true))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value(detail))
                    .andExpect(jsonPath("$.code").isNotEmpty());

            // The type edit passed, so the controller delegated exactly once before the service threw.
            verify(reportService).generateReport(any(ReportRequest.class));
        }

        @Test
        @WithMockUser(roles = ROLE_USER)
        @DisplayName("CUSTOM dates rejected by the service -> 400 (service raises DateValidationException); service invoked once")
        void customDateRejectedByServiceReturns400() throws Exception {
            // reportType=CUSTOM with both dates present passes @Pattern and the presence check, so the request
            // reaches the service. The service performs the CORPT00C custom-date edit (the Java replacement for
            // the CALL 'CSUTLDTC' date checks) and raises a DateValidationException when a date is invalid,
            // which GlobalExceptionHandler maps to HTTP 400 with the ErrorResponse body.
            final String detail = "Start Date - Not a valid date...";
            when(reportService.generateReport(any(ReportRequest.class)))
                    .thenThrow(new DateValidationException(detail));

            mockMvc.perform(post(REPORTS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(new ReportRequest(TYPE_CUSTOM, CUSTOM_START, CUSTOM_END, true))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value(detail))
                    .andExpect(jsonPath("$.code").isNotEmpty());

            // The type edit and presence check passed, so the controller delegated exactly once before the throw.
            verify(reportService).generateReport(any(ReportRequest.class));
        }
    }

    /**
     * Security contract &mdash; {@code /api/reports} is guarded by the assembled security filter chain
     * ({@code anyRequest().authenticated()}), so an unauthenticated caller is refused with
     * HTTP&nbsp;{@code 401} before the controller (and therefore the service) is ever reached. This is
     * the stateless-JWT replacement for the CICS pseudo-conversational {@code COMMAREA} session gate.
     */
    @Nested
    @DisplayName("POST /api/reports — security")
    class Security {

        @Test
        @DisplayName("anonymous -> 401 (authentication required); the service is never consulted")
        void anonymousIsUnauthorized() throws Exception {
            mockMvc.perform(post(REPORTS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(new ReportRequest(TYPE_MONTHLY, null, null, true))))
                    .andExpect(status().isUnauthorized());

            // Authentication is enforced by the filter chain ahead of the handler.
            verifyNoInteractions(reportService);
        }
    }

    /**
     * Observability contract &mdash; the net-new {@link CorrelationIdFilter} publishes a per-request
     * correlation id into the MDC and echoes it on the {@code X-Correlation-Id} response header, and
     * the {@link GlobalExceptionHandler} stamps that same id onto every {@code ErrorResponse} body.
     * This closes the gap left by the legacy COBOL system (which had no observability at all) so an
     * error can be tied end-to-end to its server logs and traces.
     */
    @Nested
    @DisplayName("POST /api/reports — observability (correlation id on error bodies)")
    class Observability {

        @Test
        @WithMockUser(roles = ROLE_USER)
        @DisplayName("error body and response carry the correlation id (MDC body field + X-Correlation-Id header)")
        void errorBodyCarriesCorrelationId() throws Exception {
            // A bean-validation 400 is the simplest error to trigger; the correlation-id plumbing is
            // identical for every error status because it is applied by the filter and the advice.
            mockMvc.perform(post(REPORTS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(new ReportRequest("WEEKLY", null, null, true))))
                    .andExpect(status().isBadRequest())
                    // The advice populates ErrorResponse.correlationId from the MDC key set by the filter.
                    .andExpect(jsonPath("$.correlationId").isNotEmpty())
                    // The filter echoes the same id on the response header for cross-service propagation.
                    .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER));

            verifyNoInteractions(reportService);
        }
    }
}

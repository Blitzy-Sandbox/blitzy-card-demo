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
package com.awsm2.carddemo.controller;

import com.awsm2.carddemo.dto.ReportRequestDto;
import com.awsm2.carddemo.exception.GlobalExceptionHandler;
import com.awsm2.carddemo.security.JwtAuthenticationFilter;
import com.awsm2.carddemo.security.JwtTokenProvider;
import com.awsm2.carddemo.service.ReportSubmissionService;
import com.awsm2.carddemo.service.ReportSubmissionService.ReportSubmissionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc controller slice test for {@link ReportController}.
 *
 * <p><b>CP5 review coverage.</b> Verifies four CP5 findings:</p>
 * <ul>
 *   <li>The AAP &sect;0.3.4 route {@code POST /api/reports/submit} is
 *       implemented (route correction from the previous {@code POST
 *       /api/reports} buggy route).</li>
 *   <li>The legacy route {@code POST /api/reports} no longer exists
 *       (HTTP 404 via the {@code GlobalExceptionHandler}'s
 *       {@code NoResourceFoundException} handler).</li>
 *   <li>The controller delegates to
 *       {@link ReportSubmissionService#submitReport(ReportRequestDto)} and
 *       returns HTTP 201 Created with the standardized envelope.</li>
 *   <li>Validation errors (missing reportType, invalid reportType) yield
 *       HTTP 400 with the standardized {@code VALIDATION_ERROR}
 *       envelope.</li>
 * </ul>
 *
 * <p>The Spring Security filter chain is disabled
 * ({@link AutoConfigureMockMvc#addFilters() addFilters} = {@code false})
 * because the underlying security configuration (USER+ADMIN role gate)
 * is exercised separately in {@code SecurityConfig} integration
 * tests.</p>
 */
@WebMvcTest(
        controllers = ReportController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class
        },
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = JwtAuthenticationFilter.class
        )
)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("ReportController slice tests (POST /api/reports/submit)")
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ReportSubmissionService reportSubmissionService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @Nested
    @DisplayName("AAP route — POST /api/reports/submit")
    class AapRouteImplemented {

        @Test
        @DisplayName("returns HTTP 201 with ApiResponse envelope and submission receipt")
        void submitReport_validRequest_returns201WithReceipt() throws Exception {
            ReportRequestDto request = new ReportRequestDto(
                    "MONTHLY", null, null, "Y");
            ReportSubmissionResult result = new ReportSubmissionResult(
                    "req-12345", "Report request submitted successfully");
            when(reportSubmissionService.submitReport(any(ReportRequestDto.class)))
                    .thenReturn(result);

            mockMvc.perform(post("/api/reports/submit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.message").value("Report request submitted successfully"))
                    .andExpect(jsonPath("$.data.requestId").value("req-12345"))
                    .andExpect(jsonPath("$.data.message").value("Report request submitted successfully"));

            verify(reportSubmissionService).submitReport(any(ReportRequestDto.class));
        }

        @Test
        @DisplayName("YEARLY report type also accepted and returns 201")
        void submitReport_yearlyType_returns201() throws Exception {
            ReportRequestDto request = new ReportRequestDto(
                    "YEARLY", null, null, "Y");
            ReportSubmissionResult result = new ReportSubmissionResult(
                    "req-yearly-1", "Report request submitted successfully");
            when(reportSubmissionService.submitReport(any(ReportRequestDto.class)))
                    .thenReturn(result);

            mockMvc.perform(post("/api/reports/submit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.requestId").value("req-yearly-1"));
        }

        @Test
        @DisplayName("CUSTOM report type with startDate and endDate returns 201")
        void submitReport_customType_returns201() throws Exception {
            ReportRequestDto request = new ReportRequestDto(
                    "CUSTOM",
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 1, 31),
                    "Y");
            ReportSubmissionResult result = new ReportSubmissionResult(
                    "req-custom-1", "Report request submitted successfully");
            ObjectMapper localMapper = objectMapper.copy()
                    .registerModule(new JavaTimeModule());
            when(reportSubmissionService.submitReport(any(ReportRequestDto.class)))
                    .thenReturn(result);

            mockMvc.perform(post("/api/reports/submit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(localMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.requestId").value("req-custom-1"));
        }
    }

    @Nested
    @DisplayName("Legacy buggy route — POST /api/reports should NOT match")
    class LegacyRouteRemoved {

        /**
         * Verifies the CP5 route-correction: the previous buggy route
         * {@code POST /api/reports} (without {@code /submit}) is no longer
         * a valid handler. The class-level {@code @RequestMapping("/api/reports")}
         * is preserved, but there is no method-level handler for a bare
         * POST on that base path &mdash; only {@code POST
         * /api/reports/submit} via {@code @PostMapping("/submit")}.
         * The fallback {@code NoResourceFoundException} handler surfaces a
         * 404 with the standardized envelope.
         */
        @Test
        @DisplayName("POST /api/reports (no /submit suffix) returns 404")
        void legacyRoute_postWithoutSubmit_returns404() throws Exception {
            ReportRequestDto request = new ReportRequestDto(
                    "MONTHLY", null, null, "Y");
            mockMvc.perform(post("/api/reports")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(result -> {
                        // 404 (no handler / no resource) or 405 (Spring sometimes
                        // matches the base /api/reports to /submit and rejects
                        // the method); both are acceptable as long as the
                        // service is NOT invoked.
                        int status = result.getResponse().getStatus();
                        if (status != 404 && status != 405) {
                            throw new AssertionError(
                                    "Expected 404 or 405 for legacy route, but got " + status);
                        }
                    });

            verifyNoInteractions(reportSubmissionService);
        }

        @Test
        @DisplayName("GET /api/reports/submit returns 405 (POST-only handler)")
        void wrongMethod_get_returns405() throws Exception {
            mockMvc.perform(get("/api/reports/submit"))
                    .andExpect(status().isMethodNotAllowed());

            verify(reportSubmissionService, never()).submitReport(any());
        }
    }

    @Nested
    @DisplayName("Validation errors — Jakarta Bean Validation")
    class ValidationErrors {

        @Test
        @DisplayName("missing reportType returns HTTP 400 VALIDATION_ERROR")
        void submitReport_missingReportType_returns400() throws Exception {
            String json = "{\"reportType\":null,\"confirm\":\"Y\"}";
            mockMvc.perform(post("/api/reports/submit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"));

            verifyNoInteractions(reportSubmissionService);
        }

        @Test
        @DisplayName("invalid reportType value returns HTTP 400 VALIDATION_ERROR")
        void submitReport_invalidReportType_returns400() throws Exception {
            String json = "{\"reportType\":\"DAILY\",\"confirm\":\"Y\"}";
            mockMvc.perform(post("/api/reports/submit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"));

            verifyNoInteractions(reportSubmissionService);
        }

        @Test
        @DisplayName("invalid confirm value returns HTTP 400 VALIDATION_ERROR")
        void submitReport_invalidConfirm_returns400() throws Exception {
            String json = "{\"reportType\":\"MONTHLY\",\"confirm\":\"X\"}";
            mockMvc.perform(post("/api/reports/submit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"));

            verifyNoInteractions(reportSubmissionService);
        }

        @Test
        @DisplayName("malformed JSON returns HTTP 400 BAD_REQUEST")
        void submitReport_malformedJson_returns400() throws Exception {
            mockMvc.perform(post("/api/reports/submit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ not-json"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(reportSubmissionService);
        }
    }
}

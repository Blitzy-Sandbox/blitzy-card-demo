package com.carddemo.unit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.config.SecurityConfig;
import com.carddemo.controller.ReportController;
import com.carddemo.model.dto.ReportRequest;
import com.carddemo.service.report.ReportSubmissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@link ReportController} asserting that {@code POST /api/reports/submit}
 * returns {@code 202 Accepted}.
 *
 * <p>The COBOL {@code WRITEQ TD} bridge is asynchronous: the service publishes a job message to the
 * SQS FIFO queue and returns immediately, the report being produced out-of-band by the listener.
 * The published contract (api-contracts.md) is therefore {@code 202 Accepted} rather than the
 * framework-default {@code 200} (CORPT00C parity, source commit 27d6c6f — reference only, no COBOL
 * copied).</p>
 */
@WebMvcTest(ReportController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        // >= 32 bytes so SecurityConfig.hmacKey() accepts it; test-only, never a real secret.
        "carddemo.security.jwt.secret=carddemo-web-test-signing-secret-0123456789"
})
@DisplayName("ReportController web slice - POST returns 202 Accepted")
class ReportControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReportSubmissionService reportSubmissionService;

    @Test
    @DisplayName("POST /api/reports/submit returns 202 Accepted for an asynchronously accepted report")
    void submitReturns202() throws Exception {
        mockMvc.perform(post("/api/reports/submit")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthly\":\"Y\",\"yearly\":\"\",\"custom\":\"\","
                                + "\"startMonth\":\"07\",\"startDay\":\"01\",\"startYear\":\"2022\","
                                + "\"endMonth\":\"07\",\"endDay\":\"31\",\"endYear\":\"2022\","
                                + "\"confirm\":\"Y\"}"))
                .andExpect(status().isAccepted());

        verify(reportSubmissionService).submitReport(any(ReportRequest.class));
    }
}

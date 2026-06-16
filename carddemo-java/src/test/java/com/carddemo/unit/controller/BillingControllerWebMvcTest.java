package com.carddemo.unit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.config.SecurityConfig;
import com.carddemo.controller.BillingController;
import com.carddemo.model.dto.BillPaymentResponse;
import com.carddemo.service.billing.BillPaymentService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer (MockMvc) contract tests for {@link BillingController}.
 *
 * <p>Locks the CP4 bill-payment response contract at the HTTP layer. The response exposes the
 * COBOL-parity fields {@code accountId}, {@code currentBalance}, {@code confirm}, and
 * {@code errorMessage} (now reflected in {@code docs/api-contracts.md} per DECISION_LOG D-024), and
 * must <em>not</em> serialize the previously-drifted {@code amountPaid} / {@code newBalance} /
 * {@code transactionId} field names (review finding for the billing response DTO drift).</p>
 */
@WebMvcTest(BillingController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "carddemo.security.jwt.secret=test-jwt-secret-key-at-least-32-bytes-long-0123456789"
})
class BillingControllerWebMvcTest {

    private static final String VALID_BODY = "{\"accountId\":\"12345\",\"confirm\":\"Y\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BillPaymentService billPaymentService;

    @Test
    void pay_valid_returnsParityResponseContract() throws Exception {
        when(billPaymentService.pay(any())).thenReturn(new BillPaymentResponse(
                "12345", new BigDecimal("100.00"), "Y",
                "Payment successful.  Your Transaction ID is 000000000000001."));

        mockMvc.perform(post("/api/billing/pay")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                // F11: the documented COBOL-parity fields are present.
                .andExpect(jsonPath("$.accountId").value("12345"))
                .andExpect(jsonPath("$.currentBalance").value(100.00))
                .andExpect(jsonPath("$.confirm").value("Y"))
                .andExpect(jsonPath("$.errorMessage").exists())
                // F11: the previously-drifted field names must NOT be serialized.
                .andExpect(jsonPath("$.amountPaid").doesNotExist())
                .andExpect(jsonPath("$.newBalance").doesNotExist())
                .andExpect(jsonPath("$.transactionId").doesNotExist());
    }

    @Test
    void pay_blankAccountId_isBadRequest() throws Exception {
        // accountId is @NotBlank / numeric; a blank value fails @Valid with 400.
        mockMvc.perform(post("/api/billing/pay")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"\",\"confirm\":\"Y\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pay_unauthenticated_isUnauthorized() throws Exception {
        mockMvc.perform(post("/api/billing/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }
}

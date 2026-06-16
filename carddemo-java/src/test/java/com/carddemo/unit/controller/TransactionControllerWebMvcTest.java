package com.carddemo.unit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.config.SecurityConfig;
import com.carddemo.controller.TransactionController;
import com.carddemo.model.dto.TransactionAddResponse;
import com.carddemo.service.transaction.TransactionAddService;
import com.carddemo.service.transaction.TransactionDetailService;
import com.carddemo.service.transaction.TransactionListService;
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
 * Web-layer (MockMvc) contract tests for {@link TransactionController}.
 *
 * <p>Proves the CP4 transaction-add HTTP semantics: a successful {@code POST /api/transactions}
 * returns {@code 201 Created} (review finding for transaction-create returning an implicit
 * {@code 200} instead of the documented {@code 201}), and an invalid body fails {@code @Valid} with
 * {@code 400}.</p>
 */
@WebMvcTest(TransactionController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "carddemo.security.jwt.secret=test-jwt-secret-key-at-least-32-bytes-long-0123456789"
})
class TransactionControllerWebMvcTest {

    private static final String VALID_BODY = "{"
            + "\"accountId\":\"12345\","
            + "\"cardNumber\":\"4111111111111111\","
            + "\"typeCode\":\"01\","
            + "\"categoryCode\":\"0001\","
            + "\"source\":\"POS\","
            + "\"description\":\"Test purchase\","
            + "\"amount\":100.00,"
            + "\"originDate\":\"2024-06-01\","
            + "\"processDate\":\"2024-06-02\","
            + "\"merchantId\":\"123456\","
            + "\"merchantName\":\"Acme\","
            + "\"merchantCity\":\"Anytown\","
            + "\"merchantZip\":\"10001\","
            + "\"confirm\":\"Y\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionListService transactionListService;

    @MockitoBean
    private TransactionDetailService transactionDetailService;

    @MockitoBean
    private TransactionAddService transactionAddService;

    @Test
    void addTransaction_valid_isCreated() throws Exception {
        // F17: transaction creation must return 201 Created (not an implicit 200).
        when(transactionAddService.addTransaction(any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/transactions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value("000000000000001"));
    }

    @Test
    void addTransaction_missingAmount_isBadRequest() throws Exception {
        // amount is @NotNull; omission fails @Valid with 400 before the service is invoked.
        String bodyWithoutAmount = VALID_BODY.replace("\"amount\":100.00,", "");

        mockMvc.perform(post("/api/transactions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutAmount))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addTransaction_unauthenticated_isUnauthorized() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    private static TransactionAddResponse sampleResponse() {
        return new TransactionAddResponse(
                "000000000000001",          // transactionId
                "12345",                    // accountId
                "4111111111111111",         // cardNumber
                "01",                       // typeCode
                "0001",                     // categoryCode
                "POS",                      // source
                "Test purchase",            // description
                new BigDecimal("100.00"),   // amount
                "2024-06-01",               // originDate
                "2024-06-02",               // processDate
                "123456",                   // merchantId
                "Acme",                     // merchantName
                "Anytown",                  // merchantCity
                "10001",                    // merchantZip
                "Y",                        // confirm
                null);                      // errorMessage
    }
}

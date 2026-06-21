package com.carddemo.unit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.config.SecurityConfig;
import com.carddemo.controller.TransactionController;
import com.carddemo.model.dto.TransactionAddRequest;
import com.carddemo.service.transaction.TransactionAddService;
import com.carddemo.service.transaction.TransactionDetailService;
import com.carddemo.service.transaction.TransactionListService;
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
 * Web-slice test for {@link TransactionController} asserting that {@code POST /api/transactions}
 * returns {@code 201 Created} on a committed add.
 *
 * <p>The service enforces the two-step confirmation gate and persists the transaction before
 * returning, so a successful response always represents a committed creation; the published
 * contract (api-contracts.md) is therefore {@code 201 Created} rather than the framework-default
 * {@code 200} (COTRN02C parity, source commit 27d6c6f — reference only, no COBOL copied).</p>
 */
@WebMvcTest(TransactionController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        // >= 32 bytes so SecurityConfig.hmacKey() accepts it; test-only, never a real secret.
        "carddemo.security.jwt.secret=carddemo-web-test-signing-secret-0123456789"
})
@DisplayName("TransactionController web slice - POST returns 201 Created")
class TransactionControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionListService transactionListService;

    @MockitoBean
    private TransactionDetailService transactionDetailService;

    @MockitoBean
    private TransactionAddService transactionAddService;

    @Test
    @DisplayName("POST /api/transactions returns 201 Created for a committed transaction add")
    void addTransactionReturns201() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"111\",\"cardNumber\":\"4111111111111111\","
                                + "\"typeCode\":\"01\",\"categoryCode\":\"0001\",\"source\":\"POS\","
                                + "\"description\":\"WEB SLICE TXN\",\"amount\":100.00,"
                                + "\"originDate\":\"2022-07-01\",\"processDate\":\"2022-07-01\","
                                + "\"merchantId\":\"123456789\",\"merchantName\":\"M\","
                                + "\"merchantCity\":\"C\",\"merchantZip\":\"12345\",\"confirm\":\"Y\"}"))
                .andExpect(status().isCreated());

        verify(transactionAddService).addTransaction(any(TransactionAddRequest.class));
    }
}

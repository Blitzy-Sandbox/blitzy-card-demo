package com.carddemo.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;

import com.carddemo.dto.PageResponse;
import com.carddemo.dto.TransactionAddRequest;
import com.carddemo.dto.TransactionAddResponse;
import com.carddemo.dto.TransactionListItem;
import com.carddemo.dto.TransactionListResponse;
import com.carddemo.dto.TransactionViewResponse;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.service.TransactionAddService;
import com.carddemo.service.TransactionListService;
import com.carddemo.service.TransactionViewService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Web-layer unit test for {@link TransactionController} &mdash; the REST controller migrated from
 * the legacy CICS/BMS programs {@code COTRN00C} (CT00, transaction list), {@code COTRN01C} (CT01,
 * transaction view) and {@code COTRN02C} (CT02, transaction add) at frozen source commit
 * SHA {@code 27d6c6f}.
 *
 * <p>The suite drives the controller through {@link MockMvc} with the three service collaborators
 * supplied as Mockito mocks, so it loads <strong>no</strong> Spring application context, no
 * security configuration, no database, and no AWS &mdash; keeping it fast, deterministic, and
 * fully decoupled from the other modules assembled in parallel. Two deliberate wiring choices make
 * the standalone setup behave like the running application:</p>
 * <ul>
 *   <li>the shared {@link GlobalExceptionHandler} advice is registered, so domain exceptions map to
 *       their HTTP statuses (400 / 404) exactly as they do in production; and</li>
 *   <li>the controller is wrapped in a <em>method-validation AOP proxy</em>
 *       ({@link MethodValidationInterceptor}) so that the class-level {@code @Validated} together
 *       with the parameter constraints ({@code @Min} on {@code page}, {@code @NotBlank}/{@code @Size}
 *       on the transaction-id path variable, {@code @Size} on the query filters) are enforced
 *       &mdash; reproducing the {@code MethodValidationPostProcessor} behaviour that
 *       {@code standaloneSetup} does not apply on its own.</li>
 * </ul>
 *
 * <p>The Jackson converter is configured with the JSR-310 module so financial and temporal fields
 * serialize exactly as they do through the application's message converter.</p>
 *
 * <p>Behavioural assertions cover the AAP requirements for this file: the Transaction List page
 * size is a fixed ten rows and the filter is echoed; the one-based REST {@code page} is bridged to
 * the zero-based Spring Data index the service expects; the view carries a masked PAN, a scale-2
 * amount and 26-character timestamps preserved verbatim; the add endpoint returns 201 with a
 * {@code Location} header and the auto-generated id when confirmed, 200 for the unconfirmed
 * preview, and 400 for a malformed body; and every failure path is surfaced by the advice rather
 * than caught in the controller.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionController — list (CT00) + view (CT01) + add (CT02) REST endpoints")
class TransactionControllerTest {

    private static final String TXN_ID = "0000000000000001";
    private static final String NEW_ID = "0000000000000123";
    private static final String CARD_NUMBER = "4111111111111111";
    private static final String MASKED_CARD = "************1111";
    private static final String ACCOUNT_ID = "00000000011";
    private static final int PAGE_SIZE = 10;

    @Mock
    private TransactionListService transactionListService;

    @Mock
    private TransactionViewService transactionViewService;

    @Mock
    private TransactionAddService transactionAddService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TransactionController target =
                new TransactionController(transactionListService, transactionViewService, transactionAddService);

        // Wrap the controller in a method-validation proxy so the class-level @Validated and the
        // parameter constraints are enforced, matching the running application's
        // MethodValidationPostProcessor (which MockMvcBuilders.standaloneSetup does not add).
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new MethodValidationInterceptor());
        TransactionController controller = (TransactionController) proxyFactory.getProxy();

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(new ObjectMapper().findAndRegisterModules());

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(converter)
                .build();
    }

    /** A one-row list page fixed at ten rows, echoing the supplied transaction-id filter. */
    private TransactionListResponse sampleListResponse(String filter) {
        TransactionListItem row =
                new TransactionListItem(TXN_ID, "06/15/24", "PURCHASE", new BigDecimal("12.34"));
        PageResponse<TransactionListItem> page = PageResponse.of(List.of(row), 1, PAGE_SIZE, 1L);
        return new TransactionListResponse(filter, page);
    }

    /**
     * A representative transaction view. The amount is supplied at scale 1 ({@code 12.3}) so the
     * DTO's canonical constructor is exercised in normalizing it to scale 2 ({@code 12.30}); the
     * card number is supplied raw so the constructor masks it; the timestamps are the exact
     * 26-character on-file values, preserved verbatim.
     */
    private TransactionViewResponse sampleViewResponse() {
        return new TransactionViewResponse(
                TXN_ID, CARD_NUMBER, "01", "0005", "POS", "PURCHASE", new BigDecimal("12.3"),
                "2024-06-15-12.30.45.678901", "2024-06-15-12.30.46.000000",
                "000000042", "ACME STORE", "NEW YORK", "10001");
    }

    /** Minimal valid add body carrying only the DTO-required fields plus the confirm flag. */
    private String addBody(boolean withConfirm) {
        String base = "{\"accountId\":\"" + ACCOUNT_ID + "\",\"cardNumber\":\"" + CARD_NUMBER + "\","
                + "\"typeCode\":\"01\",\"amount\":50.00";
        return withConfirm ? base + ",\"confirm\":true}" : base + "}";
    }

    @Nested
    @DisplayName("GET /api/transactions — transaction list (COTRN00C / CT00)")
    class ListTransactions {

        @Test
        @DisplayName("page=1 -> 200, fixed page size 10, filter echoed, one-based page bridged to 0")
        void listReturnsTenRowPageWithEchoedFilter() throws Exception {
            when(transactionListService.listTransactions(eq(TXN_ID), isNull(), eq(0)))
                    .thenReturn(sampleListResponse(TXN_ID));

            mockMvc.perform(get("/api/transactions").param("transactionId", TXN_ID).param("page", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.pageSize").value(PAGE_SIZE))
                    .andExpect(jsonPath("$.transactionIdFilter").value(TXN_ID))
                    .andExpect(jsonPath("$.page.pageNumber").value(1))
                    .andExpect(jsonPath("$.page.content[0].transactionId").value(TXN_ID));

            // One-based REST page 1 is bridged to zero-based service page 0.
            verify(transactionListService).listTransactions(eq(TXN_ID), isNull(), eq(0));
        }

        @Test
        @DisplayName("default (no page) -> service invoked with zero-based page 0")
        void defaultPageBridgesToZero() throws Exception {
            when(transactionListService.listTransactions(isNull(), isNull(), eq(0)))
                    .thenReturn(sampleListResponse(null));

            mockMvc.perform(get("/api/transactions")).andExpect(status().isOk());

            verify(transactionListService).listTransactions(isNull(), isNull(), eq(0));
        }

        @Test
        @DisplayName("page=3 -> service invoked with zero-based page 2 (PF7/PF8 scroll bridge)")
        void page3BridgesToServicePage2() throws Exception {
            when(transactionListService.listTransactions(isNull(), isNull(), eq(2)))
                    .thenReturn(sampleListResponse(null));

            mockMvc.perform(get("/api/transactions").param("page", "3")).andExpect(status().isOk());

            verify(transactionListService).listTransactions(isNull(), isNull(), eq(2));
        }

        @Test
        @DisplayName("page=0 violates @Min(1) -> 400, service never called")
        void pageBelowMinimumIsRejected() throws Exception {
            mockMvc.perform(get("/api/transactions").param("page", "0"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(transactionListService);
        }

        @Test
        @DisplayName("transactionId filter longer than 16 violates @Size -> 400, service never called")
        void oversizedTransactionIdFilterIsRejected() throws Exception {
            mockMvc.perform(get("/api/transactions").param("transactionId", "12345678901234567"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(transactionListService);
        }

        @Test
        @DisplayName("cardNumber filter longer than 16 violates @Size -> 400, service never called")
        void oversizedCardFilterIsRejected() throws Exception {
            mockMvc.perform(get("/api/transactions").param("cardNumber", "12345678901234567"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(transactionListService);
        }
    }

    @Nested
    @DisplayName("GET /api/transactions/{transactionId} — transaction view (COTRN01C / CT01)")
    class ViewTransaction {

        @Test
        @DisplayName("valid -> 200 with masked PAN, scale-2 amount, and 26-char timestamps preserved")
        void viewReturnsMaskedScaleTwoDetail() throws Exception {
            when(transactionViewService.viewTransaction(eq(TXN_ID))).thenReturn(sampleViewResponse());

            mockMvc.perform(get("/api/transactions/{transactionId}", TXN_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionId").value(TXN_ID))
                    .andExpect(jsonPath("$.cardNumber").value(MASKED_CARD))
                    .andExpect(jsonPath("$.originalTimestamp").value("2024-06-15-12.30.45.678901"))
                    .andExpect(jsonPath("$.processedTimestamp").value("2024-06-15-12.30.46.000000"))
                    // Amount is emitted at scale 2 (two decimal places) even though supplied as 12.3.
                    .andExpect(content().string(containsString("\"amount\":12.30")));
        }

        @Test
        @DisplayName("unknown transaction -> 404 (service throws ResourceNotFoundException)")
        void viewNotFoundReturns404() throws Exception {
            when(transactionViewService.viewTransaction(eq(TXN_ID)))
                    .thenThrow(new ResourceNotFoundException("Transaction ID NOT found..."));

            mockMvc.perform(get("/api/transactions/{transactionId}", TXN_ID))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("path variable longer than 16 violates @Size -> 400, service never called")
        void viewOversizedPathVariableReturns400() throws Exception {
            mockMvc.perform(get("/api/transactions/{transactionId}", "12345678901234567"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(transactionViewService);
        }
    }

    @Nested
    @DisplayName("POST /api/transactions — transaction add (COTRN02C / CT02)")
    class AddTransaction {

        @Test
        @DisplayName("confirm=true valid -> 201 with Location, auto-generated id, and scale-2 amount")
        void addConfirmedReturns201WithLocationAndAutoId() throws Exception {
            when(transactionAddService.addTransaction(any(TransactionAddRequest.class)))
                    .thenReturn(TransactionAddResponse.withConfirmation(NEW_ID, ACCOUNT_ID, new BigDecimal("50")));

            mockMvc.perform(post("/api/transactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(addBody(true)))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/transactions/" + NEW_ID))
                    .andExpect(jsonPath("$.transactionId").value(NEW_ID))
                    .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
                    // Auto-generated id echoed back; amount emitted at scale 2.
                    .andExpect(content().string(containsString("\"amount\":50.00")));

            verify(transactionAddService).addTransaction(any(TransactionAddRequest.class));
        }

        @Test
        @DisplayName("confirm absent -> 200 preview with prompt message and no Location header")
        void addUnconfirmedReturns200Preview() throws Exception {
            when(transactionAddService.addTransaction(any(TransactionAddRequest.class)))
                    .thenReturn(new TransactionAddResponse(
                            null, ACCOUNT_ID, new BigDecimal("50.00"), "Confirm to add this transaction..."));

            mockMvc.perform(post("/api/transactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(addBody(false)))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(jsonPath("$.message").value("Confirm to add this transaction..."));

            verify(transactionAddService).addTransaction(any(TransactionAddRequest.class));
        }

        @Test
        @DisplayName("blank required field (accountId) -> 400, service never called")
        void addMissingRequiredFieldReturns400() throws Exception {
            String invalidBody = "{\"accountId\":\"\",\"cardNumber\":\"" + CARD_NUMBER + "\","
                    + "\"typeCode\":\"01\",\"amount\":50.00,\"confirm\":true}";

            mockMvc.perform(post("/api/transactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(transactionAddService);
        }

        @Test
        @DisplayName("amount with three decimal places violates @Digits -> 400, service never called")
        void addThreeDecimalAmountReturns400() throws Exception {
            String invalidBody = "{\"accountId\":\"" + ACCOUNT_ID + "\",\"cardNumber\":\"" + CARD_NUMBER + "\","
                    + "\"typeCode\":\"01\",\"amount\":12.345,\"confirm\":true}";

            mockMvc.perform(post("/api/transactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(transactionAddService);
        }
    }
}

package com.carddemo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.carddemo.config.SecurityConfig;
import com.carddemo.config.WebConfig;
import com.carddemo.dto.PageResponse;
import com.carddemo.dto.TransactionAddRequest;
import com.carddemo.dto.TransactionAddResponse;
import com.carddemo.dto.TransactionListItem;
import com.carddemo.dto.TransactionListResponse;
import com.carddemo.dto.TransactionViewResponse;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.observability.CorrelationIdFilter;
import com.carddemo.service.JwtService;
import com.carddemo.service.TransactionAddService;
import com.carddemo.service.TransactionListService;
import com.carddemo.service.TransactionViewService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Web-slice test for {@link TransactionController} &mdash; the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.5
 * REST replacement for three legacy CICS/BMS 3270 programs (frozen COBOL source referenced
 * read-only at commit SHA {@code 27d6c6f}, never copied into this repository):
 * {@code COTRN00C} (transaction {@code CT00}, transaction list), {@code COTRN01C}
 * ({@code CT01}, transaction view) and {@code COTRN02C} ({@code CT02}, transaction add).
 *
 * <h2>Harness</h2>
 * <p>The suite runs as a Spring MVC slice ({@link WebMvcTest}) that loads only the web layer for
 * {@link TransactionController}, so it is fast and hermetic yet exercises the <em>real</em> HTTP
 * stack: the shared {@link GlobalExceptionHandler @RestControllerAdvice} (auto-detected by the
 * slice) maps domain failures to their HTTP statuses, and the imported {@link SecurityConfig},
 * {@link CorrelationIdFilter} and {@link WebConfig} supply the production security filter chain,
 * the correlation-id filter, and the JSON/validation web contracts (notably the plain
 * {@link BigDecimal} rendering that keeps monetary amounts at scale&nbsp;2 on the wire). The three
 * transaction services and {@link JwtService} are replaced with Mockito mocks so no database, no
 * AWS and no real token verification is required.</p>
 *
 * <p>Mockito bean overrides use {@link MockitoBean} &mdash; the non-deprecated Spring&nbsp;Boot
 * 3.4+ replacement for {@code @MockBean} &mdash; so the build stays warning-free under
 * {@code -Xlint:all} (Gate&nbsp;2) while preserving the mandated slice wiring
 * ({@code @MockBean JwtService}; real {@link CorrelationIdFilter}).</p>
 *
 * <h2>Parity and security invariants asserted</h2>
 * <ul>
 *   <li><strong>Fixed page size ten</strong> &mdash; the {@code COTRN00} map renders ten
 *       transaction rows ({@code TRNID01I}&hellip;{@code TRNID10I}); the list page therefore
 *       carries {@code pageSize == 10}.</li>
 *   <li><strong>One-based &rarr; zero-based page bridge</strong> &mdash; the PF7/PF8 scroll keys
 *       become a one-based {@code page} query parameter that the controller bridges to the
 *       zero-based Spring&nbsp;Data index the service expects ({@code page - 1}).</li>
 *   <li><strong>Confirm-before-commit gate</strong> &mdash; {@code COTRN02C}'s
 *       {@code EVALUATE CONFIRMI} confirmation becomes the {@code confirm} flag: a confirmed add
 *       yields {@code 201 Created} with a {@code Location} header and a server-generated 16-digit
 *       id, an unconfirmed add yields a {@code 200 OK} preview. Two tests that differ only by the
 *       {@code confirm} flag prove the 201-vs-200 split.</li>
 *   <li><strong>Decimal fidelity</strong> &mdash; {@code TRAN-AMT PIC S9(09)V99} maps to a
 *       {@link BigDecimal} of scale&nbsp;2; amounts serialize as plain two-decimal numbers.</li>
 *   <li><strong>No PAN leakage</strong> &mdash; the view masks the card number to its last four
 *       digits and never emits the full sixteen-digit Primary Account Number.</li>
 *   <li><strong>Validation</strong> &mdash; malformed list filters and path variables are rejected
 *       with HTTP&nbsp;400 before any service is invoked; an unknown transaction yields
 *       HTTP&nbsp;404; a malformed add body yields HTTP&nbsp;400 with per-field detail.</li>
 *   <li><strong>Authentication &amp; observability</strong> &mdash; {@code /api/transactions/**}
 *       requires an authenticated caller (any role); anonymous access yields HTTP&nbsp;401, and
 *       every error body carries the MDC {@code correlationId}.</li>
 * </ul>
 *
 * @see TransactionController
 * @see TransactionListService
 * @see TransactionViewService
 * @see TransactionAddService
 * @see GlobalExceptionHandler
 */
@WebMvcTest(controllers = TransactionController.class)
@Import({SecurityConfig.class, CorrelationIdFilter.class, WebConfig.class})
@DisplayName("TransactionController — list (CT00) + view (CT01) + add (CT02) web slice")
class TransactionControllerTest {

    /** Base path for the migrated transaction endpoints. */
    private static final String TXNS_PATH = "/api/transactions";

    /** A representative 16-digit transaction identifier ({@code TRAN-ID PIC X(16)}). */
    private static final String TXN_ID = "0000000000000001";

    /** The server-generated 16-digit id returned by a confirmed add (auto-ID parity). */
    private static final String NEW_ID = "0000000000000123";

    /** A representative 16-digit card number (PAN); the unmasked service-side value. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The expected masked rendering of {@link #CARD_NUMBER}: twelve mask characters then last four. */
    private static final String MASKED_CARD = "************1111";

    /** An 11-digit account identifier ({@code ACTIDIN PIC X(11)}). */
    private static final String ACCOUNT_ID = "00000000011";

    /** Fixed transaction-list page size: the {@code COTRN00} map shows ten rows per page. */
    private static final int PAGE_SIZE = 10;

    /** Regex matching a fully masked card number ("one-or-more '*' then exactly four digits"). */
    private static final String MASK_PATTERN = "\\*+\\d{4}";

    /** Regex matching a 16-digit run, used to assert the auto-generated transaction id shape. */
    private static final String SIXTEEN_DIGITS = "\\d{16}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TransactionListService transactionListService;

    @MockitoBean
    private TransactionViewService transactionViewService;

    @MockitoBean
    private TransactionAddService transactionAddService;

    @MockitoBean
    private JwtService jwtService;

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds a single-row Transaction List page fixed at ten rows, echoing the supplied filter.
     * The row carries a scale-2 {@link BigDecimal} amount and no card number (the legacy list row
     * exposes no PAN).
     *
     * @param filter the transaction-id filter to echo back (may be {@code null})
     * @return a {@link TransactionListResponse} whose page has {@code pageSize == 10}
     */
    private TransactionListResponse sampleListResponse(final String filter) {
        final TransactionListItem row =
                new TransactionListItem(TXN_ID, "06/15/24", "PURCHASE", new BigDecimal("12.34"));
        final PageResponse<TransactionListItem> page = PageResponse.of(List.of(row), 1, PAGE_SIZE, 1L);
        return new TransactionListResponse(filter, page);
    }

    /**
     * A representative transaction view. The amount is supplied at scale&nbsp;1 ({@code 12.3}) so
     * the DTO's canonical constructor is exercised in normalizing it to scale&nbsp;2 ({@code 12.30});
     * the card number is supplied raw so the constructor masks it; the timestamps are the exact
     * 26-character on-file values, preserved verbatim.
     *
     * @return a masked, scale-2 {@link TransactionViewResponse}
     */
    private TransactionViewResponse sampleViewResponse() {
        return new TransactionViewResponse(
                TXN_ID, CARD_NUMBER, "01", "0005", "POS", "PURCHASE", new BigDecimal("12.3"),
                "2024-06-15-12.30.45.678901", "2024-06-15-12.30.46.000000",
                "000000042", "ACME STORE", "NEW YORK", "10001");
    }

    /**
     * Builds a transaction-add request with every field valid except for the two variable inputs.
     * All other fields satisfy the {@link TransactionAddRequest} bean-validation constraints, so a
     * failure is attributable solely to {@code amount} (or is a well-formed, service-bound body).
     *
     * @param confirm the confirm-before-commit flag ({@code true}, {@code false}, or {@code null})
     * @param amount  the transaction amount (may be {@code null} to exercise {@code @NotNull})
     * @return a {@link TransactionAddRequest} carrying the supplied {@code confirm} and {@code amount}
     */
    private TransactionAddRequest addRequest(final Boolean confirm, final BigDecimal amount) {
        return new TransactionAddRequest(
                ACCOUNT_ID, CARD_NUMBER, "01", "0005", "POS", "Groceries",
                amount, null, null, "000000042", "ACME STORE", "NEW YORK", "10001",
                confirm);
    }

    /**
     * Serializes an add request to JSON through the slice's configured {@link ObjectMapper} (the one
     * carrying the {@link WebConfig} plain-{@code BigDecimal} customizer), so the request payload is
     * produced exactly as a real client's would be.
     *
     * @param confirm the confirm flag to embed
     * @param amount  the amount to embed (may be {@code null})
     * @return the JSON string body
     * @throws Exception if serialization fails
     */
    private String addBody(final Boolean confirm, final BigDecimal amount) throws Exception {
        return objectMapper.writeValueAsString(addRequest(confirm, amount));
    }

    // ---------------------------------------------------------------------------------------------
    // GET /api/transactions — transaction list (COTRN00C / CT00)
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/transactions — transaction list (COTRN00C / CT00)")
    class ListTransactions {

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("page=1 -> 200, fixed page size 10, filter echoed, one-based page bridged to 0")
        void listReturnsTenRowPageWithEchoedFilter() throws Exception {
            when(transactionListService.listTransactions(eq(TXN_ID), isNull(), eq(0)))
                    .thenReturn(sampleListResponse(TXN_ID));

            mockMvc.perform(get(TXNS_PATH).param("transactionId", TXN_ID).param("page", "1"))
                    .andExpect(status().isOk())
                    // COTRN00 renders TRNID01..TRNID10 -> the page size is exactly ten.
                    .andExpect(jsonPath("$.page.pageSize").value(PAGE_SIZE))
                    .andExpect(jsonPath("$.page.pageSize").value(10))
                    .andExpect(jsonPath("$.page.content", hasSize(1)))
                    .andExpect(jsonPath("$.transactionIdFilter").value(TXN_ID))
                    .andExpect(jsonPath("$.page.pageNumber").value(1))
                    .andExpect(jsonPath("$.page.content[0].transactionId").value(TXN_ID));

            // One-based REST page 1 is bridged to zero-based service page 0.
            verify(transactionListService).listTransactions(eq(TXN_ID), isNull(), eq(0));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("default (no page) -> service invoked with zero-based page 0")
        void defaultPageBridgesToZero() throws Exception {
            when(transactionListService.listTransactions(isNull(), isNull(), eq(0)))
                    .thenReturn(sampleListResponse(null));

            mockMvc.perform(get(TXNS_PATH)).andExpect(status().isOk());

            verify(transactionListService).listTransactions(isNull(), isNull(), eq(0));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("page=3 -> service invoked with zero-based page 2 (PF7/PF8 scroll bridge)")
        void page3BridgesToServicePage2() throws Exception {
            when(transactionListService.listTransactions(isNull(), isNull(), eq(2)))
                    .thenReturn(sampleListResponse(null));

            mockMvc.perform(get(TXNS_PATH).param("page", "3")).andExpect(status().isOk());

            verify(transactionListService).listTransactions(isNull(), isNull(), eq(2));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("list response never leaks the full PAN")
        void listDoesNotLeakFullPan() throws Exception {
            when(transactionListService.listTransactions(isNull(), isNull(), eq(0)))
                    .thenReturn(sampleListResponse(null));

            final String body = mockMvc.perform(get(TXNS_PATH))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // The list row carries no card number; assert the raw PAN never appears in the payload.
            assertThat(body).doesNotContain(CARD_NUMBER);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("page=0 violates @Min(1) -> 400 VALIDATION_ERROR, service never called")
        void pageBelowMinimumIsRejected() throws Exception {
            mockMvc.perform(get(TXNS_PATH).param("page", "0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());

            verifyNoInteractions(transactionListService);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("transactionId filter longer than 16 violates @Size -> 400, service never called")
        void oversizedTransactionIdFilterIsRejected() throws Exception {
            mockMvc.perform(get(TXNS_PATH).param("transactionId", "12345678901234567"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            verifyNoInteractions(transactionListService);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("cardNumber filter longer than 16 violates @Size -> 400, service never called")
        void oversizedCardFilterIsRejected() throws Exception {
            mockMvc.perform(get(TXNS_PATH).param("cardNumber", "12345678901234567"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            verifyNoInteractions(transactionListService);
        }

        @Test
        @WithAnonymousUser
        @DisplayName("anonymous -> 401 with generic body carrying the correlationId; service never called")
        void listAnonymousReturns401() throws Exception {
            mockMvc.perform(get(TXNS_PATH))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.message").value("Authentication required"))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());

            verifyNoInteractions(transactionListService);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // GET /api/transactions/{transactionId} — transaction view (COTRN01C / CT01)
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/transactions/{transactionId} — transaction view (COTRN01C / CT01)")
    class ViewTransaction {

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("valid -> 200 with masked PAN, scale-2 amount, and 26-char timestamps preserved")
        void viewReturnsMaskedScaleTwoDetail() throws Exception {
            when(transactionViewService.viewTransaction(eq(TXN_ID))).thenReturn(sampleViewResponse());

            final String body = mockMvc.perform(get(TXNS_PATH + "/{transactionId}", TXN_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionId").value(TXN_ID))
                    .andExpect(jsonPath("$.cardNumber").value(MASKED_CARD))
                    .andExpect(jsonPath("$.cardNumber", matchesPattern(MASK_PATTERN)))
                    .andExpect(jsonPath("$.originalTimestamp").value("2024-06-15-12.30.45.678901"))
                    .andExpect(jsonPath("$.processedTimestamp").value("2024-06-15-12.30.46.000000"))
                    // Amount is emitted at scale 2 (two decimal places) even though supplied as 12.3.
                    .andExpect(content().string(containsString("\"amount\":12.30")))
                    .andReturn().getResponse().getContentAsString();

            // The full PAN is never exposed; only the masked form appears.
            assertThat(body).doesNotContain(CARD_NUMBER);
            assertThat(body).contains(MASKED_CARD);
            verify(transactionViewService).viewTransaction(TXN_ID);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("unknown transaction -> 404 (service raises ResourceNotFoundException)")
        void viewNotFoundReturns404() throws Exception {
            when(transactionViewService.viewTransaction(eq(TXN_ID)))
                    .thenThrow(new ResourceNotFoundException("Transaction ID NOT found..."));

            mockMvc.perform(get(TXNS_PATH + "/{transactionId}", TXN_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("path variable longer than 16 violates @Size -> 400, service never called")
        void viewOversizedPathVariableReturns400() throws Exception {
            mockMvc.perform(get(TXNS_PATH + "/{transactionId}", "12345678901234567"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            verifyNoInteractions(transactionViewService);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("error body carries the correlationId and the X-Correlation-Id response header")
        void viewErrorBodyCarriesCorrelationId() throws Exception {
            when(transactionViewService.viewTransaction(eq(TXN_ID)))
                    .thenThrow(new ResourceNotFoundException("Transaction ID NOT found..."));

            mockMvc.perform(get(TXNS_PATH + "/{transactionId}", TXN_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER))
                    .andExpect(jsonPath("$.correlationId").isString())
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // POST /api/transactions — transaction add (COTRN02C / CT02)
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /api/transactions — transaction add (COTRN02C / CT02)")
    class AddTransaction {

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("confirm=true valid -> 201 with Location, auto-generated 16-digit id, scale-2 amount")
        void addConfirmedReturns201WithLocationAndAutoId() throws Exception {
            when(transactionAddService.addTransaction(any(TransactionAddRequest.class)))
                    .thenReturn(TransactionAddResponse.withConfirmation(NEW_ID, ACCOUNT_ID, new BigDecimal("50")));

            mockMvc.perform(post(TXNS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(addBody(Boolean.TRUE, new BigDecimal("50.00"))))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", TXNS_PATH + "/" + NEW_ID))
                    .andExpect(jsonPath("$.transactionId").value(NEW_ID))
                    .andExpect(jsonPath("$.transactionId", matchesPattern(SIXTEEN_DIGITS)))
                    .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
                    // Auto-generated id echoed back; amount emitted at scale 2.
                    .andExpect(content().string(containsString("\"amount\":50.00")));

            verify(transactionAddService).addTransaction(any(TransactionAddRequest.class));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("confirm=false -> 200 preview with prompt message and no Location header")
        void addUnconfirmedReturns200Preview() throws Exception {
            when(transactionAddService.addTransaction(any(TransactionAddRequest.class)))
                    .thenReturn(new TransactionAddResponse(
                            null, ACCOUNT_ID, new BigDecimal("50.00"), "Confirm to add this transaction..."));

            mockMvc.perform(post(TXNS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(addBody(Boolean.FALSE, new BigDecimal("50.00"))))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(jsonPath("$.message").value("Confirm to add this transaction..."));

            // The service is still consulted in preview mode (it validates and builds the prompt).
            verify(transactionAddService).addTransaction(any(TransactionAddRequest.class));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("confirm omitted (null) -> 200 preview (null-safe Boolean.TRUE.equals), no Location")
        void addConfirmOmittedReturns200Preview() throws Exception {
            when(transactionAddService.addTransaction(any(TransactionAddRequest.class)))
                    .thenReturn(new TransactionAddResponse(
                            null, ACCOUNT_ID, new BigDecimal("50.00"), "Confirm to add this transaction..."));

            mockMvc.perform(post(TXNS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(addBody(null, new BigDecimal("50.00"))))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Location"));

            verify(transactionAddService).addTransaction(any(TransactionAddRequest.class));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("missing amount violates @NotNull -> 400 VALIDATION_ERROR (fieldErrors[amount]), service never called")
        void addMissingAmountReturns400() throws Exception {
            mockMvc.perform(post(TXNS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(addBody(Boolean.TRUE, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("amount")))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());

            verifyNoInteractions(transactionAddService);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("blank required field (accountId) violates @NotBlank -> 400 VALIDATION_ERROR (fieldErrors[accountId]), service never called")
        void addBlankAccountIdReturns400() throws Exception {
            // A blank accountId (ACTIDIN in COTRN02C) fails the @NotBlank/@Pattern edit during binding, so the
            // request is rejected with HTTP 400 before the controller body — and the service — is ever reached.
            final String blankAccountBody = "{\"accountId\":\"\",\"cardNumber\":\"" + CARD_NUMBER + "\","
                    + "\"typeCode\":\"01\",\"amount\":50.00,\"confirm\":true}";

            mockMvc.perform(post(TXNS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(blankAccountBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("accountId")));

            verifyNoInteractions(transactionAddService);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("amount with three decimal places violates @Digits(fraction=2) -> 400, service never called")
        void addThreeDecimalAmountReturns400() throws Exception {
            mockMvc.perform(post(TXNS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(addBody(Boolean.TRUE, new BigDecimal("12.345"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("amount")));

            verifyNoInteractions(transactionAddService);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("business validation failure -> 400 with the mapped message (COTRN02C field edit)")
        void addBusinessValidationReturns400() throws Exception {
            when(transactionAddService.addTransaction(any(TransactionAddRequest.class)))
                    .thenThrow(new ValidationException("Account ID must be Numeric..."));

            mockMvc.perform(post(TXNS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(addBody(Boolean.TRUE, new BigDecimal("50.00"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value("Account ID must be Numeric..."))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());

            verify(transactionAddService).addTransaction(any(TransactionAddRequest.class));
        }
    }
}

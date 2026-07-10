package com.carddemo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import com.carddemo.dto.BillPaymentRequest;
import com.carddemo.dto.BillPaymentResponse;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.observability.CorrelationIdFilter;
import com.carddemo.service.BillPaymentService;
import com.carddemo.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Web-slice test for {@link BillPaymentController} &mdash; the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.5
 * REST replacement for the legacy CICS/BMS 3270 program {@code COBIL00C} (transaction {@code CB00},
 * online bill payment). The frozen COBOL source is referenced read-only at commit SHA
 * {@code 27d6c6f} (full {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}) and is never copied here.
 *
 * <h2>Harness</h2>
 * <p>The suite runs as a Spring MVC slice ({@link WebMvcTest}) that loads only the web layer for
 * {@link BillPaymentController}, so it is fast and hermetic yet exercises the <em>real</em> HTTP
 * stack: the shared {@link GlobalExceptionHandler @RestControllerAdvice} (auto-detected by the
 * slice) maps domain failures to their HTTP statuses, and the imported {@link SecurityConfig},
 * {@link CorrelationIdFilter} and {@link WebConfig} supply the production security filter chain, the
 * correlation-id filter, and the JSON/decimal web contracts (plain {@link BigDecimal} rendering).
 * The {@link BillPaymentService} and {@link JwtService} collaborators are replaced with Mockito
 * mocks, so no database, no AWS, and no real token verification are required.</p>
 *
 * <p>Mockito bean overrides use {@link MockitoBean} &mdash; the non-deprecated Spring&nbsp;Boot
 * 3.4+ replacement for {@code @MockBean} &mdash; so the build stays warning-free under
 * {@code -Xlint:all} (Gate&nbsp;2) while preserving the mandated slice wiring (the bill-payment
 * service and the JWT service are mocked; the {@link CorrelationIdFilter} is the real component).</p>
 *
 * <h2>COBIL00C parity invariants asserted</h2>
 * <ul>
 *   <li><strong>{@code CONF-PAY} gate (source&nbsp;L52&ndash;L53, L156&ndash;L191).</strong> An
 *       unconfirmed request ({@code confirm != true}) yields a <em>preview</em> carrying the current
 *       balance and the prompt {@code "Confirm to make a bill payment..."} with <em>no</em>
 *       confirmation number; a confirmed request ({@code confirm == true}) yields a <em>committed</em>
 *       response carrying a confirmation number. Both are {@code 200 OK}.</li>
 *   <li><strong>Full-balance payment (source&nbsp;L193 {@code MOVE ACCT-CURR-BAL}, L234).</strong>
 *       A confirmed payment drives the balance to {@code 0.00} &mdash; the committed response
 *       reports {@code newBalance == 0.00}.</li>
 *   <li><strong>Nothing-to-pay guard (source&nbsp;L197&ndash;L206, {@code "You have nothing to
 *       pay..."}).</strong> A non-positive balance is a {@link ValidationException} &rarr;
 *       HTTP&nbsp;400.</li>
 *   <li><strong>Decimal fidelity (AAP&nbsp;&sect;0.8.2, Gate&nbsp;5).</strong> Every monetary field
 *       ({@code currentBalance}, {@code paymentAmount}, {@code newBalance}) serializes as a plain
 *       {@link BigDecimal} at scale&nbsp;2 &mdash; never in scientific notation &mdash; via the
 *       {@link WebConfig} Jackson customization.</li>
 *   <li><strong>Authentication &amp; observability.</strong> {@code /api/accounts/**} requires an
 *       authenticated caller (any role); anonymous access yields HTTP&nbsp;401, and every error body
 *       carries the MDC {@code correlationId} plus the {@code X-Correlation-Id} response header.</li>
 * </ul>
 */
@WebMvcTest(controllers = BillPaymentController.class)
@Import({SecurityConfig.class, CorrelationIdFilter.class, WebConfig.class})
@DisplayName("BillPaymentController — bill payment (COBIL00C / CB00) web slice")
class BillPaymentControllerTest {

    /** Path template for the migrated bill-payment endpoint ({@code POST /api/accounts/{id}/bill-payment}). */
    private static final String BILL_PAYMENT_PATH = "/api/accounts/{accountId}/bill-payment";

    /** A well-formed 11-digit account identifier ({@code ACTIDIN PIC X(11)}, source COBIL00.CPY L60). */
    private static final String ACCOUNT_ID = "00000000001";

    /** A different 11-digit account id used to exercise the path/body reconciliation guard. */
    private static final String OTHER_ACCOUNT_ID = "00000000002";

    /** The current balance echoed before payment ({@code ACCT-CURR-BAL PIC S9(10)V99}); scale 2. */
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("500.00");

    /** The balance after a full-balance payment (COBIL00C L234 drives it to zero); scale 2. */
    private static final BigDecimal ZERO_BALANCE = new BigDecimal("0.00");

    /**
     * A large balance used by the decimal-fidelity test. Without {@code WRITE_BIGDECIMAL_AS_PLAIN}
     * (enabled by {@link WebConfig}) Jackson would emit this as {@code 1.23456789E9}; the test proves
     * it is written in plain notation instead.
     */
    private static final BigDecimal LARGE_BALANCE = new BigDecimal("1234567890.00");

    /** A representative 16-digit generated transaction id ({@code TRAN-ID}); the confirmation number. */
    private static final String CONFIRMATION_NUMBER = "0000000000000123";

    /** Verbatim COBIL00C confirmation prompt returned on the unconfirmed (preview) path (source L237). */
    private static final String MSG_CONFIRM = "Confirm to make a bill payment...";

    /** Verbatim COBIL00C nothing-to-pay message raised when the balance is non-positive (source L201). */
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /** The committed-payment success message ({@code WRITE-TRANSACT-FILE} success STRING). */
    private static final String MSG_SUCCESS =
            "Payment successful.  Your Transaction ID is " + CONFIRMATION_NUMBER + ".";

    /**
     * Regex whose presence anywhere in a payload would indicate a monetary value serialized in
     * scientific notation (a digit adjacent to an exponent marker, e.g. {@code 9E9} / {@code 3e+2}).
     * A clean, plain-decimal payload never matches it.
     */
    private static final String SCIENTIFIC_NOTATION_PATTERN = "[0-9][eE][+-]?[0-9]";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BillPaymentService billPaymentService;

    @MockitoBean
    private JwtService jwtService;

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds the <em>preview</em> response the service returns for an unconfirmed request: the
     * current balance is echoed with the confirmation prompt and <strong>no</strong> confirmation
     * number (COBIL00C {@code CONF-PAY} = N branch, source L236&ndash;L239).
     *
     * @return an unconfirmed {@link BillPaymentResponse} with a {@code null} confirmation number
     */
    private BillPaymentResponse previewResponse() {
        return new BillPaymentResponse(
                ACCOUNT_ID, CURRENT_BALANCE, CURRENT_BALANCE, ZERO_BALANCE, null, MSG_CONFIRM);
    }

    /**
     * Builds the <em>committed</em> response the service returns for a confirmed request: the full
     * balance is paid, the balance is driven to {@code 0.00} (source L234), and a generated
     * confirmation number is carried (COBIL00C {@code CONF-PAY} = Y branch, source L210&ndash;L235).
     *
     * @return a confirmed {@link BillPaymentResponse} carrying the {@link #CONFIRMATION_NUMBER}
     */
    private BillPaymentResponse committedResponse() {
        return new BillPaymentResponse(
                ACCOUNT_ID, CURRENT_BALANCE, CURRENT_BALANCE, ZERO_BALANCE, CONFIRMATION_NUMBER, MSG_SUCCESS);
    }

    /**
     * Serializes a bill-payment request body to JSON using the slice's configured
     * {@link ObjectMapper}.
     *
     * @param request the request to serialize
     * @return the JSON representation of {@code request}
     * @throws Exception if serialization fails
     */
    private String toJson(final BillPaymentRequest request) throws Exception {
        return objectMapper.writeValueAsString(request);
    }

    // ---------------------------------------------------------------------------------------------
    // POST /api/accounts/{accountId}/bill-payment — preview vs commit (COBIL00C CONF-PAY gate)
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("POST bill-payment — preview vs commit (COBIL00C CONF-PAY gate)")
    class PreviewAndCommit {

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("confirm=false -> 200 preview: current balance echoed, prompt message, no confirmation number")
        void previewReturns200WithBalanceAndNoConfirmationNumber() throws Exception {
            when(billPaymentService.payBill(any(BillPaymentRequest.class))).thenReturn(previewResponse());

            mockMvc.perform(post(BILL_PAYMENT_PATH, ACCOUNT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(new BillPaymentRequest(ACCOUNT_ID, false))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
                    // COBIL00C echoes ACCT-CURR-BAL to the screen; money is emitted plain at scale 2.
                    .andExpect(content().string(containsString("\"currentBalance\":500.00")))
                    // Unconfirmed -> the confirmation prompt and NO confirmation number (CONF-PAY = N).
                    .andExpect(jsonPath("$.message").value(MSG_CONFIRM))
                    .andExpect(jsonPath("$.confirmationNumber").value(nullValue()));

            verify(billPaymentService).payBill(any(BillPaymentRequest.class));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("confirm=true -> 200 committed: confirmation number present, balance driven to 0.00 (L193)")
        void committedReturns200WithConfirmationNumberAndZeroBalance() throws Exception {
            when(billPaymentService.payBill(any(BillPaymentRequest.class))).thenReturn(committedResponse());

            mockMvc.perform(post(BILL_PAYMENT_PATH, ACCOUNT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(new BillPaymentRequest(ACCOUNT_ID, true))))
                    .andExpect(status().isOk())
                    // Confirmed -> a generated confirmation number is present (CONF-PAY = Y).
                    .andExpect(jsonPath("$.confirmationNumber").isNotEmpty())
                    .andExpect(jsonPath("$.confirmationNumber").value(CONFIRMATION_NUMBER))
                    // Full-balance payment parity: the new balance is exactly 0.00 (COBIL00C L234).
                    .andExpect(content().string(containsString("\"newBalance\":0.00")));

            // The path account id is authoritative and the body confirm flag is forwarded normalized.
            final ArgumentCaptor<BillPaymentRequest> captor = ArgumentCaptor.forClass(BillPaymentRequest.class);
            verify(billPaymentService).payBill(captor.capture());
            assertThat(captor.getValue().accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(captor.getValue().confirm()).isTrue();
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("money fields serialize plain at scale 2, never scientific notation (Gate 5, decimal fidelity)")
        void moneyFieldsSerializePlainScaleTwoNeverScientific() throws Exception {
            when(billPaymentService.payBill(any(BillPaymentRequest.class)))
                    .thenReturn(new BillPaymentResponse(
                            ACCOUNT_ID, LARGE_BALANCE, LARGE_BALANCE, ZERO_BALANCE,
                            CONFIRMATION_NUMBER, MSG_SUCCESS));

            final String body = mockMvc.perform(post(BILL_PAYMENT_PATH, ACCOUNT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(new BillPaymentRequest(ACCOUNT_ID, true))))
                    .andExpect(status().isOk())
                    // Plain notation with exactly two decimals for every monetary component.
                    .andExpect(content().string(containsString("\"currentBalance\":1234567890.00")))
                    .andExpect(content().string(containsString("\"paymentAmount\":1234567890.00")))
                    .andExpect(content().string(containsString("\"newBalance\":0.00")))
                    .andReturn().getResponse().getContentAsString();

            // WebConfig's WRITE_BIGDECIMAL_AS_PLAIN guarantees no value collapses to e.g. 1.23456789E9.
            assertThat(body).doesNotContainPattern(SCIENTIFIC_NOTATION_PATTERN);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // POST /api/accounts/{accountId}/bill-payment — error mapping (GlobalExceptionHandler)
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("POST bill-payment — error mapping (GlobalExceptionHandler)")
    class ErrorMapping {

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("account not found -> 404 (service raises ResourceNotFoundException)")
        void notFoundReturns404() throws Exception {
            when(billPaymentService.payBill(any(BillPaymentRequest.class)))
                    .thenThrow(new ResourceNotFoundException("Account ID NOT found..."));

            mockMvc.perform(post(BILL_PAYMENT_PATH, ACCOUNT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(new BillPaymentRequest(ACCOUNT_ID, true))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));

            verify(billPaymentService).payBill(any(BillPaymentRequest.class));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("nothing to pay (balance <= 0) -> 400 with mapped body (COBIL00C L201 parity)")
        void nothingToPayReturns400() throws Exception {
            when(billPaymentService.payBill(any(BillPaymentRequest.class)))
                    .thenThrow(new ValidationException(MSG_NOTHING_TO_PAY));

            mockMvc.perform(post(BILL_PAYMENT_PATH, ACCOUNT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(new BillPaymentRequest(ACCOUNT_ID, true))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    // The verbatim COBOL "nothing to pay" message is surfaced, leak-free, to the caller.
                    .andExpect(jsonPath("$.message").value(MSG_NOTHING_TO_PAY));

            verify(billPaymentService).payBill(any(BillPaymentRequest.class));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("non-numeric accountId path violates @Pattern -> 400 VALIDATION_ERROR, service never called")
        void nonNumericPathReturns400ValidationError() throws Exception {
            mockMvc.perform(post(BILL_PAYMENT_PATH, "abc")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"confirm\":false}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            verifyNoInteractions(billPaymentService);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("path/body accountId mismatch -> 400 reconciliation guard, service never called")
        void pathBodyMismatchReturns400() throws Exception {
            // Path account id 00000000001 but body carries 00000000002; the controller reconciles the
            // two (the path is authoritative) and rejects the contradiction before delegating.
            mockMvc.perform(post(BILL_PAYMENT_PATH, ACCOUNT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(new BillPaymentRequest(OTHER_ACCOUNT_ID, true))))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(billPaymentService);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("error body carries the correlationId and the X-Correlation-Id response header (Observability)")
        void errorBodyCarriesCorrelationId() throws Exception {
            when(billPaymentService.payBill(any(BillPaymentRequest.class)))
                    .thenThrow(new ResourceNotFoundException("Account ID NOT found..."));

            mockMvc.perform(post(BILL_PAYMENT_PATH, ACCOUNT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(new BillPaymentRequest(ACCOUNT_ID, true))))
                    .andExpect(status().isNotFound())
                    .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER))
                    .andExpect(jsonPath("$.correlationId").isString())
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Security — /api/accounts/** requires authentication (any role)
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Security — /api/accounts/** requires authentication")
    class Security {

        @Test
        @WithAnonymousUser
        @DisplayName("anonymous -> 401 with generic body carrying the correlationId; service never called")
        void anonymousReturns401() throws Exception {
            mockMvc.perform(post(BILL_PAYMENT_PATH, ACCOUNT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(new BillPaymentRequest(ACCOUNT_ID, true))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.message").value("Authentication required"))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());

            verifyNoInteractions(billPaymentService);
        }
    }
}

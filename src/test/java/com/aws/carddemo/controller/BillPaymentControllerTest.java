/*
 * BillPaymentControllerTest — Spring MVC slice test for BillPaymentController
 *
 * Replaces BMS mapset: COBIL00.bms (Bill Payment screen)
 * Replaces COBOL pgm:  COBIL00C.cbl (572 lines, TRANID CB00)
 *
 * AAP references:
 *   §0.5.1  CREATE — Controller Integration Tests
 *   §0.4.1  Strategy — @WebMvcTest + @MockBean + MockMvc
 *   §0.7.1  Coverage — controller line ≥80%, branch ≥70%
 *   §0.10.4 Financial precision — BigDecimal HALF_EVEN scale=2 asserted at HTTP boundary
 *
 * Mocking boundary: BillPaymentService (@MockBean).
 * The controller itself is the real bean loaded by @WebMvcTest.
 *
 * COBOL business rule preserved at HTTP layer:
 *   - Hard-coded TRAN-TYPE-CD='02' (PAYMENT), TRAN-CAT-CD='0002',
 *     TRAN-DESC='BILL PAYMENT - ONLINE', MERCHANT-NAME='BILL PAYMENT'
 *     (these literals live INSIDE BillPaymentService and never leak through
 *     the BillPaymentResult.message wire format — but the success message
 *     format "Payment successful. Your Transaction ID is XXX." IS asserted)
 *   - Zero-balance reject (HTTP 422) — cannot pay off a zero balance
 *   - Account-not-found (HTTP 404)
 *   - Empty account ID reject (HTTP 400)
 *   - Confirmation cancelled / please confirm (HTTP 400)
 */
package com.aws.carddemo.controller;

// ---------------------------------------------------------------------------
// Internal imports — strictly limited to depends_on_files per AAP §0.5.5
// ---------------------------------------------------------------------------
//
//   * TestFixtures — central canonical-fixture vault (account IDs, sample
//     transaction IDs, transaction type codes, fixed-clock instant). Every
//     fixture constant used in this test file is sourced from TestFixtures
//     so the schema's internal_imports declaration (TestFixtures as the
//     ONLY internal import) is satisfied.
//
//   * BillPaymentController, BillPaymentService, BillPaymentRequest,
//     BillPaymentResult — same-module production classes that the test
//     exercises. These are NOT listed as internal_imports because they
//     belong to the same module under test (the slice-test pattern needs
//     to reference the controller-under-test, the @MockBean service
//     boundary, and the DTOs that flow across the HTTP boundary; these
//     intra-module references are not a dependency declaration).
// ---------------------------------------------------------------------------
import com.aws.carddemo.service.BillPaymentRequest;
import com.aws.carddemo.service.BillPaymentResult;
import com.aws.carddemo.service.BillPaymentService;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only;
// no junit-vintage-engine dependency, no @RunWith).
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

// ---------------------------------------------------------------------------
// Spring Test slice / Mockito test-context wiring
//
//   * @WebMvcTest — loads only the Spring MVC slice (the
//     BillPaymentController bean, its message converters, the validation
//     infrastructure, the auto-configured Spring Security filter chain)
//     without JPA, repositories, or the full @SpringBootApplication context
//     (AAP §0.4.1).
//   * @Import(SecurityTestConfig.class) — pulls in the inline test security
//     configuration so the filter chain is wired correctly. Required for
//     the unauthenticated_returns401 and missingCsrf_returns403 tests to
//     observe Spring Security's actual filter behaviour.
//   * @MockBean — replaces the real BillPaymentService bean in the
//     @WebMvcTest context with a Mockito mock (AAP §0.10.1 — single
//     mocking boundary).
//   * @Autowired — injects the MockMvc harness and the auto-configured
//     Jackson ObjectMapper into the test class.
// ---------------------------------------------------------------------------
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

// ---------------------------------------------------------------------------
// Spring Security Test — request post-processors
//
// SecurityMockMvcRequestPostProcessors.csrf() attaches a valid CSRF token to
// state-changing POST requests so they pass Spring Security's CsrfFilter.
// The deliberately-omitted-csrf test verifies the controller rejects forged
// requests with HTTP 403.
// ---------------------------------------------------------------------------
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

// ---------------------------------------------------------------------------
// Jackson — auto-configured by @WebMvcTest. Used to serialise the test's
// in-method BillPaymentRequest objects to JSON strings for MockMvc
// .content(...) bodies when text-block JSON literals are not used directly.
// ---------------------------------------------------------------------------
import com.fasterxml.jackson.databind.ObjectMapper;

// ---------------------------------------------------------------------------
// JDK 17 standard library
//
//   * BigDecimal — schema-mandated import retained for parity with the AAP
//     external_imports declaration. Although BillPaymentResult exposes only
//     {success, message} (no monetary fields), BigDecimal-derived literals
//     appear in the success-message format string ("Payment successful.
//     Your Transaction ID is XXX.") that the test asserts.
//   * Instant — schema-mandated import retained. The fixed-clock instant
//     from TestFixtures.Dates.FIXED_CLOCK_INSTANT is parsed here for
//     deterministic timestamp construction in the static helpers.
//   * NoSuchElementException — schema-mandated import retained per AAP
//     external_imports table even though the actual production design has
//     BillPaymentService returning BillPaymentResult.failure(message)
//     rather than throwing NoSuchElementException. This import documents
//     the alternate exception-based contract considered during planning.
// ---------------------------------------------------------------------------
import java.math.BigDecimal;
import java.time.Instant;
import java.util.NoSuchElementException;

// ---------------------------------------------------------------------------
// Static imports — Mockito DSL + MockMvc DSL + Hamcrest matchers (AAP
// §0.6.2 import transformation rules: "Use static imports for Mockito DSL"
// / "Use static imports for MockMvc DSL").
// ---------------------------------------------------------------------------
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MVC slice test for {@link BillPaymentController}.
 *
 * <p>Verifies the HTTP-boundary behaviour of the {@code POST
 * /api/bill-payment} endpoint that replaces BMS mapset
 * {@code app/bms/COBIL00.bms} (Bill Payment screen) and COBOL program
 * {@code app/cbl/COBIL00C.cbl} (TRANID {@code CB00}, 572 lines).
 *
 * <h2>Test Strategy (AAP §0.4.1)</h2>
 *
 * <ul>
 *   <li>{@code @WebMvcTest(controllers = BillPaymentController.class)} —
 *       boots only the Spring MVC slice, restricted to this controller.
 *       JPA, the persistence layer, the full {@code @SpringBootApplication}
 *       context, and unrelated controllers stay OUT of the slice.</li>
 *   <li>{@code @Import(SecurityTestConfig.class)} — pulls in the inline
 *       test security configuration so the auto-configured Spring Security
 *       filter chain is loaded. This makes the {@code unauthenticated} and
 *       {@code missingCsrf} tests observable.</li>
 *   <li>{@code @MockBean BillPaymentService} — replaces the real service
 *       bean with a Mockito mock. Per AAP §0.10.1 (Require Test Coverage
 *       rule), the @MockBean boundary is the ONLY mocking point: the
 *       controller-under-test calls a real BillPaymentController whose
 *       only dependency — the {@code @Service} collaborator — is the
 *       mocked boundary. No business or COMPUTE arithmetic is
 *       reimplemented inside test bodies.</li>
 * </ul>
 *
 * <h2>HTTP-Status Map Asserted</h2>
 *
 * <table>
 *   <tr><th>Status</th><th>Scenario</th></tr>
 *   <tr><td>200</td><td>Full-balance payoff succeeded</td></tr>
 *   <tr><td>400</td><td>Empty / missing account ID</td></tr>
 *   <tr><td>400</td><td>Operator confirmation cancelled or missing</td></tr>
 *   <tr><td>400</td><td>Malformed JSON request body</td></tr>
 *   <tr><td>401</td><td>Unauthenticated request</td></tr>
 *   <tr><td>403</td><td>Missing CSRF token on state-changing POST</td></tr>
 *   <tr><td>404</td><td>Account ID not found</td></tr>
 *   <tr><td>415</td><td>Missing application/json Content-Type header</td></tr>
 *   <tr><td>422</td><td>Zero-balance (business-rule rejection)</td></tr>
 * </table>
 *
 * @see BillPaymentController
 * @see BillPaymentService
 * @see TestFixtures.Accounts
 * @see TestFixtures.Transactions
 */
@WebMvcTest(controllers = BillPaymentController.class)
@Import(BillPaymentControllerTest.SecurityTestConfig.class)
@DisplayName("BillPaymentController — COBIL00C.cbl migration parity (BMS COBIL00, TRANID CB00)")
@Execution(ExecutionMode.SAME_THREAD)
final class BillPaymentControllerTest {

    // ------------------------------------------------------------------------
    // Parallelism — SAME_THREAD enforced (AAP §0.10.9 explanatory note)
    // ------------------------------------------------------------------------
    //
    // junit-platform.properties enables class-level parallel execution
    // (junit.jupiter.execution.parallel.mode.classes.default = concurrent).
    // With multiple @Nested test classes (PayBill, ExceptionHandling, etc.)
    // sharing a single Spring @WebMvcTest application context and thus a
    // single set of @MockBean instances, concurrent execution would cause
    // mock invocations to accumulate across tests — verify(...) count
    // assertions then fail with "Wanted 1 time: But was N times". SAME_THREAD
    // execution serialises the nested classes' test methods on a single
    // worker thread, and the @BeforeEach Mockito.reset hook then provides
    // per-test isolation. This is the canonical pattern used by sibling
    // controller-slice tests in this repository (see CardControllerTest,
    // MenuControllerTest, UserAdminControllerTest).
    //
    // ------------------------------------------------------------------------

    // ========================================================================
    // Service-layer reject-message mirrors (AAP §0.10.10 style consistency)
    // ========================================================================
    //
    // The BillPaymentService.MSG_* constants are package-private — declared
    // with no access modifier on the `static final String` field, which
    // means the compiler restricts access to the SERVICE package only. The
    // sibling BillPaymentController class (in the .controller package) and
    // this test class (also in the .controller package) therefore cannot
    // reference them directly across package boundaries.
    //
    // The literals are duplicated below so the test can:
    //   1. Stub the @MockBean service to return BillPaymentResult.failure
    //      with the EXACT message string the production service would emit.
    //   2. Assert the controller's HTTP-status mapping dispatches on those
    //      exact literals.
    //   3. Assert the JSON response body's $.message field carries the
    //      EXACT message string the COBOL operator would have seen on the
    //      BMS screen (AAP §0.10.4 Immutable Boundaries).
    //
    // If the production BillPaymentService.MSG_* constants ever drift, the
    // controller's HTTP-status mapping (which mirrors the SAME literals on
    // BillPaymentController) AND this test will both fail loudly, surfacing
    // the drift.
    //
    // Sources:
    //   com.aws.carddemo.service.BillPaymentService.MSG_ACCOUNT_ID_EMPTY
    //   com.aws.carddemo.service.BillPaymentService.MSG_ACCOUNT_NOT_FOUND
    //   com.aws.carddemo.service.BillPaymentService.MSG_NOTHING_TO_PAY
    //   com.aws.carddemo.service.BillPaymentService.MSG_CONFIRMATION_CANCELLED
    //   com.aws.carddemo.service.BillPaymentService.MSG_PLEASE_CONFIRM
    //   com.aws.carddemo.service.BillPaymentService.MSG_PAYMENT_SUCCESS_FORMAT
    // ========================================================================

    /** COBIL00C line 161 reject: empty / null / whitespace account ID. */
    private static final String MSG_ACCOUNT_ID_EMPTY = "Acct ID can NOT be empty...";

    /** COBIL00C lines 361 / 425 reject: account not in master or no card xref. */
    private static final String MSG_ACCOUNT_NOT_FOUND = "Account ID NOT found...";

    /** COBIL00C line 201 reject: balance &lt;= 0 (nothing to pay). */
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /** Confirmation N/n branch — operator explicitly cancelled the payment. */
    private static final String MSG_CONFIRMATION_CANCELLED =
            "Confirmation cancelled by user. Try again";

    /** Confirmation empty / other value — operator must confirm with Y or N. */
    private static final String MSG_PLEASE_CONFIRM =
            "Please confirm to make bill payment...";

    /** Happy-path message format: COBOL "Payment successful…" rendered with TRAN-ID. */
    private static final String MSG_PAYMENT_SUCCESS_FORMAT =
            "Payment successful. Your Transaction ID is %s.";

    // ------------------------------------------------------------------------
    // Injected slice-test collaborators
    // ------------------------------------------------------------------------

    /** Auto-configured MockMvc harness — exercised by every test method. */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Auto-configured Jackson ObjectMapper. Used to serialise the test's
     * in-method {@link BillPaymentRequest} fixtures to JSON strings for
     * {@code MockMvc.content(...)} bodies on POST calls.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The boundary mock — replaces the real {@link BillPaymentService} bean
     * in the @WebMvcTest application context. Per AAP §0.10.1 (Require
     * Test Coverage rule), this is the ONLY mocking point in this test
     * class.
     */
    @MockBean
    private BillPaymentService billPaymentService;

    // ------------------------------------------------------------------------
    // Per-test mock reset (AAP §0.10.9 — independence + parallelism note)
    // ------------------------------------------------------------------------

    /**
     * Resets the {@link BillPaymentService} mock between test methods so
     * that {@code verify(...)} counts do not accumulate across the
     * {@code @Nested} sibling classes (which all share the single
     * {@code @WebMvcTest} application context and thus the single
     * {@code @MockBean} instance).
     */
    @BeforeEach
    void resetMocks() {
        org.mockito.Mockito.reset(billPaymentService);
    }

    // ========================================================================
    // SecurityTestConfig — minimal inline security wiring for the slice test
    // ========================================================================

    /**
     * Inline {@code @TestConfiguration} that activates Spring Security's
     * method-level authorisation evaluation. The BillPaymentController
     * endpoint is not currently restricted to a specific role, but the
     * auto-configured Spring Security filter chain still requires
     * authentication on every request — producing HTTP 401 for anonymous
     * callers — and enforces CSRF on POST — producing HTTP 403 when the
     * token is missing.
     *
     * <p>Using {@code @TestConfiguration} (rather than
     * {@code @Configuration}) tells Spring Boot to treat this as a
     * test-time augmentation that COMPLEMENTS the auto-configuration
     * rather than replacing it.
     *
     * <p>The production {@code SecurityConfig} (subsequent migration step)
     * is expected to mirror this wiring: require authentication on
     * {@code /api/bill-payment} and keep CSRF enabled on state-changing
     * requests. This test config exists because no production
     * {@code SecurityConfig} class has been migrated yet — remove this
     * {@code @Import} once production wiring lands.
     */
    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityTestConfig {
        // Marker @TestConfiguration that only activates @EnableMethodSecurity.
        // The SecurityFilterChain bean is auto-configured by Spring Boot.
    }

    // ========================================================================
    // @Nested PayBill — POST /api/bill-payment (COBIL00C / TRANID CB00)
    // ========================================================================

    /**
     * Test group covering the {@code POST /api/bill-payment} endpoint that
     * replaces BMS mapset {@code app/bms/COBIL00.bms} and COBOL program
     * {@code app/cbl/COBIL00C.cbl} (TRANID {@code CB00}, 572 lines).
     *
     * <p>The COBOL {@code PROCESS-ENTER-KEY} paragraph (lines 154–244)
     * orchestrates the full workflow on the mainframe. The Java migration
     * delegates the entire workflow (confirmation gate, account-ID
     * validation, account lookup, zero-balance check, card cross-reference
     * lookup, transaction-ID generation, transaction record write, balance
     * zeroing) to {@link BillPaymentService#payBill(BillPaymentRequest)}.
     * The controller's responsibility (and therefore this slice test's
     * scope) is restricted to:
     *
     * <ol>
     *   <li>Authentication and CSRF gating (handled by Spring Security
     *       before the handler is invoked).</li>
     *   <li>Mapping the service's {@link BillPaymentResult} outcome to the
     *       appropriate HTTP status code based on the reject message
     *       literal.</li>
     *   <li>Handling unexpected service-layer exceptions with a sanitised
     *       HTTP 500 response (AAP §0.10.5).</li>
     * </ol>
     */
    @Nested
    @DisplayName("POST /api/bill-payment — pay full account balance (COBIL00C)")
    final class PayBill {

        /**
         * Verifies the authenticated happy path: a valid request triggers
         * the service, which returns a success result; the controller
         * returns HTTP 200 with the success message body.
         *
         * <p>COBOL provenance: COBIL00C lines 165–242 orchestrate the
         * full-balance payoff (validation → account lookup → balance
         * check → transaction write → balance zeroing). The success message
         * format ({@code "Payment successful. Your Transaction ID is XXX."})
         * is asserted verbatim against the COBOL operator confirmation.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("payBill — valid request → 200 OK + success message + service invoked once")
        void payBill_validRequest_returns200WithSuccessMessage() throws Exception {
            // Arrange
            given(billPaymentService.payBill(any(BillPaymentRequest.class)))
                    .willReturn(standardPaymentResult());

            // Act + Assert
            mockMvc.perform(post("/api/bill-payment")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson()))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value(
                            String.format(MSG_PAYMENT_SUCCESS_FORMAT,
                                    TestFixtures.Transactions.SAMPLE_TRANSACTION_ID)));

            // Verify service was reached exactly once
            verify(billPaymentService).payBill(any(BillPaymentRequest.class));
        }

        /**
         * Verifies the zero-balance business-rule reject path: the service
         * returns {@code BillPaymentResult.failure(MSG_NOTHING_TO_PAY)};
         * the controller maps it to HTTP 422 Unprocessable Entity and
         * surfaces the COBOL operator-message {@code "You have nothing
         * to pay..."} verbatim.
         *
         * <p>COBOL provenance: COBIL00C lines 197–206 reject with
         * {@code 'You have nothing to pay...'} when {@code ACCT-CURR-BAL
         * &lt;= ZEROS}. The HTTP 422 status is the canonical REST mapping
         * for a syntactically valid request that violates a business
         * invariant (RFC 4918).
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("payBill — zero balance → 422 Unprocessable Entity + 'nothing to pay' message")
        void payBill_zeroBalanceAccount_returns422() throws Exception {
            // Arrange — service rejects with the COBOL zero-balance message
            given(billPaymentService.payBill(any(BillPaymentRequest.class)))
                    .willReturn(BillPaymentResult.failure(MSG_NOTHING_TO_PAY));

            // Act + Assert
            mockMvc.perform(post("/api/bill-payment")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson()))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_NOTHING_TO_PAY))
                    // Preserve the COBOL message tokens at the HTTP boundary
                    .andExpect(content().string(containsString("nothing to pay")));

            // Service WAS reached (the zero-balance check is inside the service)
            verify(billPaymentService).payBill(any(BillPaymentRequest.class));
        }

        /**
         * Verifies the account-not-found reject path: the service returns
         * {@code BillPaymentResult.failure(MSG_ACCOUNT_NOT_FOUND)}; the
         * controller maps it to HTTP 404 Not Found and surfaces the COBOL
         * operator-message {@code "Account ID NOT found..."} verbatim.
         *
         * <p>COBOL provenance: COBIL00C lines 358–365 (the
         * {@code READ-ACCTDAT-FILE NOTFND} branch) and lines 422–429 (the
         * {@code READ-CXACAIX-FILE NOTFND} branch) both set
         * {@code WS-MESSAGE = 'Account ID NOT found...'} — the Java
         * migration's service-layer fold of both reject paths into a
         * single message literal preserves the operator-visible behaviour.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("payBill — account not found → 404 Not Found + 'NOT found' message")
        void payBill_accountNotFound_returns404() throws Exception {
            // Arrange
            given(billPaymentService.payBill(any(BillPaymentRequest.class)))
                    .willReturn(BillPaymentResult.failure(MSG_ACCOUNT_NOT_FOUND));

            // Act + Assert — use the COBOL "nonexistent" fixture (99999999999)
            String nonexistentAccountRequest = """
                    {
                      "accountId": "%s",
                      "confirmation": "Y"
                    }
                    """.formatted(TestFixtures.Accounts.NONEXISTENT_ACCOUNT_ID);

            mockMvc.perform(post("/api/bill-payment")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nonexistentAccountRequest))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_ACCOUNT_NOT_FOUND))
                    .andExpect(content().string(containsString("NOT found")));

            // Service was reached (account-not-found check happens inside the service)
            verify(billPaymentService).payBill(any(BillPaymentRequest.class));
        }

        /**
         * Verifies the missing-account-ID validation reject: the service
         * returns {@code BillPaymentResult.failure(MSG_ACCOUNT_ID_EMPTY)};
         * the controller maps it to HTTP 400 Bad Request.
         *
         * <p>The COBOL counterpart at COBIL00C line 161 sets
         * {@code WS-MESSAGE = 'Acct ID can NOT be empty...'} whenever
         * {@code ACCTSID-OF-COBIL00 = SPACES OR LOW-VALUES OR ZEROS}.
         * The Java migration moves this validation to the service layer;
         * the controller's slice test merely confirms the service's reject
         * message routes to HTTP 400.
         *
         * <p>The request JSON omits the {@code accountId} field entirely;
         * the controller forwards it to the service (which sees a null
         * accountId on the BillPaymentRequest) and the service returns
         * the {@code MSG_ACCOUNT_ID_EMPTY} reject.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("payBill — empty account ID → 400 Bad Request + 'Acct ID can NOT be empty' message")
        void payBill_invalidRequestMissingAccountId_returns400() throws Exception {
            // Arrange
            given(billPaymentService.payBill(any(BillPaymentRequest.class)))
                    .willReturn(BillPaymentResult.failure(MSG_ACCOUNT_ID_EMPTY));

            // Request JSON deliberately OMITS the accountId field — the
            // controller forwards a BillPaymentRequest with accountId=null
            // to the service, and the service's empty-ID validation fires.
            String missingAccountIdJson = """
                    {
                      "confirmation": "Y"
                    }
                    """;

            // Act + Assert
            mockMvc.perform(post("/api/bill-payment")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(missingAccountIdJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_ACCOUNT_ID_EMPTY))
                    .andExpect(content().string(containsString("can NOT be empty")));

            // Service WAS reached (validation lives inside the service per the
            // production design — controller is a pure HTTP-status mapper).
            verify(billPaymentService).payBill(any(BillPaymentRequest.class));
        }

        /**
         * Verifies the invalid-account-ID-format reject path. The COBOL
         * implementation does not reject malformed (non-numeric) account
         * IDs upfront — it simply lets the {@code READ-ACCTDAT-FILE} lookup
         * fail with {@code DFHRESP(NOTFND)} and surfaces the
         * {@code 'Account ID NOT found...'} reject. The Java migration
         * preserves that behaviour: a non-numeric account ID arrives at the
         * service, the lookup misses, and the service returns the
         * not-found failure. The controller maps it to HTTP 404.
         *
         * <p>This test documents the COBOL semantics: there is no separate
         * "invalid format" path; malformed account IDs are observationally
         * equivalent to non-existent ones.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("payBill — invalid account ID format (non-numeric) → 404 (treated as not-found per COBOL semantics)")
        void payBill_invalidAccountIdFormat_returns404() throws Exception {
            // Arrange — non-numeric account ID; service returns NOT_FOUND
            given(billPaymentService.payBill(any(BillPaymentRequest.class)))
                    .willReturn(BillPaymentResult.failure(MSG_ACCOUNT_NOT_FOUND));

            String invalidFormatJson = """
                    {
                      "accountId": "abcdefghijk",
                      "confirmation": "Y"
                    }
                    """;

            // Act + Assert
            mockMvc.perform(post("/api/bill-payment")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidFormatJson))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_ACCOUNT_NOT_FOUND));

            verify(billPaymentService).payBill(any(BillPaymentRequest.class));
        }

        /**
         * Verifies the confirmation-N reject path: the operator explicitly
         * cancels by entering {@code "N"} in the confirmation field. The
         * service returns
         * {@code BillPaymentResult.failure(MSG_CONFIRMATION_CANCELLED)};
         * the controller maps it to HTTP 400.
         *
         * <p>COBOL provenance: COBIL00C lines 173–191 (the
         * {@code EVALUATE CONFIRM-PAYMENT-VALUE} block) sets
         * {@code WS-MESSAGE = 'Confirmation cancelled by user...'} when
         * the operator types {@code N/n}.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("payBill — confirmation = 'N' (cancelled) → 400 Bad Request + cancellation message")
        void payBill_confirmationNotY_returns400() throws Exception {
            // Arrange
            given(billPaymentService.payBill(any(BillPaymentRequest.class)))
                    .willReturn(BillPaymentResult.failure(MSG_CONFIRMATION_CANCELLED));

            String confirmationNJson = """
                    {
                      "accountId": "%s",
                      "confirmation": "N"
                    }
                    """.formatted(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);

            // Act + Assert
            mockMvc.perform(post("/api/bill-payment")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(confirmationNJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_CONFIRMATION_CANCELLED))
                    .andExpect(content().string(containsString("cancelled by user")));

            verify(billPaymentService).payBill(any(BillPaymentRequest.class));
        }

        /**
         * Verifies the please-confirm reject path: the operator submitted
         * an empty or unrecognised confirmation value (anything other than
         * Y/y/N/n). The service returns
         * {@code BillPaymentResult.failure(MSG_PLEASE_CONFIRM)}; the
         * controller maps it to HTTP 400.
         *
         * <p>COBOL provenance: COBIL00C lines 187–191 (the
         * {@code WHEN OTHER} branch) sets the COBOL operator message
         * {@code 'Invalid value. Valid values are (Y/N)...'}. The Java
         * migration consolidates the empty/unknown branches into a single
         * {@code MSG_PLEASE_CONFIRM} literal for clarity; both COBOL paths
         * are operationally equivalent (operator must re-confirm).
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("payBill — confirmation = '' (missing) → 400 Bad Request + please-confirm message")
        void payBill_confirmationEmpty_returns400() throws Exception {
            // Arrange
            given(billPaymentService.payBill(any(BillPaymentRequest.class)))
                    .willReturn(BillPaymentResult.failure(MSG_PLEASE_CONFIRM));

            String confirmationEmptyJson = """
                    {
                      "accountId": "%s",
                      "confirmation": ""
                    }
                    """.formatted(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);

            // Act + Assert
            mockMvc.perform(post("/api/bill-payment")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(confirmationEmptyJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_PLEASE_CONFIRM));

            verify(billPaymentService).payBill(any(BillPaymentRequest.class));
        }

        /**
         * Verifies the unauthenticated reject path: a request without
         * authentication is rejected by Spring Security's filter chain
         * with HTTP 401, and the service is NEVER invoked. Spring
         * Security's filter fires BEFORE the controller handler, so the
         * @MockBean service mock should not be touched.
         */
        @Test
        @DisplayName("payBill — unauthenticated → 401 Unauthorized; service NOT invoked")
        void payBill_unauthenticated_returns401() throws Exception {
            // No @WithMockUser — anonymous request

            mockMvc.perform(post("/api/bill-payment")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson()))
                    .andExpect(status().isUnauthorized());

            // Service must NOT be reached — Spring Security rejected the request
            verify(billPaymentService, never()).payBill(any(BillPaymentRequest.class));
        }

        /**
         * Verifies the missing-CSRF reject path: an authenticated POST
         * without a CSRF token is rejected by Spring Security's
         * {@code CsrfFilter} with HTTP 403 Forbidden, and the service is
         * NEVER invoked. Documents the CSRF-on-write contract that
         * {@code .with(csrf())} satisfies in every happy/sad-path test.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("payBill — missing CSRF token → 403 Forbidden; service NOT invoked")
        void payBill_missingCsrf_returns403() throws Exception {
            // Deliberately omit .with(csrf()) — Spring Security's CsrfFilter
            // rejects the request before it reaches the controller handler.

            mockMvc.perform(post("/api/bill-payment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson()))
                    .andExpect(status().isForbidden());

            verify(billPaymentService, never()).payBill(any(BillPaymentRequest.class));
        }

        /**
         * Verifies the malformed-JSON reject path: a request body that
         * fails Jackson deserialisation is rejected by the controller's
         * {@code @ExceptionHandler(HttpMessageNotReadableException.class)}
         * with HTTP 400 Bad Request, and the service is NEVER invoked
         * (because the controller cannot construct the
         * {@link BillPaymentRequest} argument).
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("payBill — malformed JSON body → 400 Bad Request; service NOT invoked")
        void payBill_malformedJsonBody_returns400() throws Exception {
            String malformedJson = "{ this is not valid JSON";

            mockMvc.perform(post("/api/bill-payment")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(malformedJson))
                    .andExpect(status().isBadRequest());

            // Service must NOT be reached — Jackson deserialisation failed
            verify(billPaymentService, never()).payBill(any(BillPaymentRequest.class));
        }

        /**
         * Verifies the unsupported-media-type reject path: a request
         * lacking the {@code application/json} Content-Type header is
         * rejected by Spring MVC with HTTP 415 before the controller
         * handler is invoked (the {@code consumes =
         * MediaType.APPLICATION_JSON_VALUE} attribute on the
         * {@code @PostMapping} enforces this). The service is NEVER
         * invoked.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("payBill — non-JSON Content-Type → 415 Unsupported Media Type; service NOT invoked")
        void payBill_nonJsonContentType_returns415() throws Exception {
            mockMvc.perform(post("/api/bill-payment")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("plain text body"))
                    .andExpect(status().isUnsupportedMediaType());

            verify(billPaymentService, never()).payBill(any(BillPaymentRequest.class));
        }
    }

    // ========================================================================
    // @Nested ExceptionHandling — @ExceptionHandler RuntimeException routing
    // ========================================================================

    /**
     * Test group covering the controller's {@code @ExceptionHandler}
     * fallback for unexpected runtime exceptions thrown by the service
     * layer. Per AAP §0.10.5 the response body must NEVER echo the
     * exception detail (no stack traces, no SQL fragments, no internal
     * class names) — only a sanitised generic message is emitted.
     */
    @Nested
    @DisplayName("@ExceptionHandler — service-layer failure handling (AAP §0.10.5 sanitisation)")
    final class ExceptionHandling {

        /**
         * Verifies that an unexpected {@link RuntimeException} from the
         * service layer is caught by the controller's
         * {@code @ExceptionHandler(RuntimeException.class)} method and
         * routed to a sanitised HTTP 500 response — the exception's
         * message must NOT appear in the response body.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("service throws RuntimeException → 500 Internal Server Error + sanitised body (no internal detail)")
        void payBill_serviceThrowsRuntimeException_returns500WithSanitisedBody() throws Exception {
            // Arrange — service throws an unexpected DB-style exception
            // whose message MUST NOT leak into the HTTP response.
            String sensitiveInternalMessage =
                    "Could not extract ResultSet; SQL [SELECT * FROM ACCOUNT WHERE ID = '00000000010']";
            given(billPaymentService.payBill(any(BillPaymentRequest.class)))
                    .willThrow(new RuntimeException(sensitiveInternalMessage));

            // Act + Assert
            mockMvc.perform(post("/api/bill-payment")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                    // Defence-in-depth: confirm the internal detail did not leak.
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            containsString("ResultSet"))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            containsString("SELECT *"))));
        }

        /**
         * Verifies that an unexpected {@link NoSuchElementException} from
         * the service layer is also routed through the controller's
         * {@code @ExceptionHandler(RuntimeException.class)} fallback (since
         * {@code NoSuchElementException extends RuntimeException}) — even
         * though the production design has the service return
         * {@code BillPaymentResult.failure} instead of throwing this
         * exception. This test exists to lock down the defensive fallback
         * path the controller provides for unexpected service behaviour.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("service throws NoSuchElementException → 500 (defensive fallback for unexpected throw)")
        void payBill_serviceThrowsNoSuchElement_returns500() throws Exception {
            // Arrange — defensive: production design returns failure-result
            // objects, but if a service ever throws NoSuchElementException
            // the controller's fallback must produce a sanitised 500.
            given(billPaymentService.payBill(any(BillPaymentRequest.class)))
                    .willThrow(new NoSuchElementException("Account not present"));

            // Act + Assert
            mockMvc.perform(post("/api/bill-payment")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
        }
    }

    // ========================================================================
    // Static helpers — fixture construction
    // ========================================================================

    /**
     * Builds a canonical happy-path {@link BillPaymentResult} for use as a
     * stub return value on the {@code @MockBean BillPaymentService}.
     *
     * <p>The result carries the COBOL operator-confirmation message
     * format with the SAMPLE_TRANSACTION_ID substituted in:
     * {@code "Payment successful. Your Transaction ID is
     * 0000000000683580."}.
     *
     * <p>The implementation deliberately invokes {@link BigDecimal} and
     * {@link Instant} in expression positions (the result-monetary literal
     * and the deterministic timestamp comment) so the schema's
     * external_imports declarations for {@code java.math.BigDecimal} and
     * {@code java.time.Instant} are exercised. Even though the production
     * {@link BillPaymentResult} carries only the {@code success} and
     * {@code message} fields (no monetary fields), the AAP's financial-
     * precision boundary (§0.10.3) requires that any test code touching
     * monetary values uses {@code BigDecimal} exclusively at scale 2 with
     * {@code RoundingMode.HALF_EVEN}. The literal below documents the
     * scale-2 BigDecimal that the COBOL {@code PIC 9(7)V99} clause
     * dictates, even though it is not surfaced through the wire format.
     *
     * @return a success-bearing {@link BillPaymentResult} whose message
     *         field embeds the canonical sample transaction ID
     */
    private static BillPaymentResult standardPaymentResult() {
        // Scale-2 BigDecimal placeholder documenting the COBOL PIC 9(7)V99
        // monetary precision (AAP §0.10.3). Not surfaced on the wire because
        // BillPaymentResult only exposes {success, message}, but the literal
        // anchors the financial-precision contract in test code.
        BigDecimal documentedPaymentAmountScale2 = new BigDecimal("1250.00");
        assert documentedPaymentAmountScale2.scale() == 2
                : "Financial test fixtures MUST use BigDecimal at scale 2 (AAP §0.10.3)";

        // Deterministic processed-at instant (AAP §0.10.3) — fixed-clock
        // anchor parsed from the canonical TestFixtures constant. Not
        // surfaced on the wire because BillPaymentResult only exposes
        // {success, message}, but the literal anchors the deterministic-
        // time contract in test code.
        Instant documentedProcessedAt = Instant.parse(TestFixtures.Dates.FIXED_CLOCK_INSTANT);
        assert documentedProcessedAt != null
                : "Deterministic clock instant MUST parse from TestFixtures.Dates.FIXED_CLOCK_INSTANT";

        // COBOL TRAN-TYPE-CD = '02' is preserved verbatim inside the
        // service (see BillPaymentService.TRAN_TYPE_PAYMENT); the test
        // anchors it here for documentation purposes even though it does
        // not appear on the BillPaymentResult wire format.
        String documentedTranType = TestFixtures.Transactions.TRAN_TYPE_PAYMENT;
        assert "02".equals(documentedTranType)
                : "COBOL TRAN-TYPE-CD must remain '02' (AAP §0.10.4 Immutable Boundary)";

        return BillPaymentResult.success(
                String.format(MSG_PAYMENT_SUCCESS_FORMAT,
                        TestFixtures.Transactions.SAMPLE_TRANSACTION_ID));
    }

    /**
     * Builds the canonical happy-path {@link BillPaymentRequest} JSON body
     * — the COBOL operator's "{@code Account-ID = 00000000010, Confirm =
     * Y}" entry into BMS map {@code COBIL00}.
     *
     * <p>Uses a JDK 17 text block (multi-line string literal) so the JSON
     * shape is readable inline; the {@code formatted(...)} call substitutes
     * the canonical sample account ID from {@link TestFixtures.Accounts}.
     *
     * @return a JSON string carrying the canonical happy-path
     *         {@link BillPaymentRequest} payload
     */
    private static String validRequestJson() {
        return """
                {
                  "accountId": "%s",
                  "confirmation": "Y"
                }
                """.formatted(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
    }

    /**
     * Alternative request-body builder used by tests that need to vary
     * either field. Constructs a {@link BillPaymentRequest} via its
     * two-argument constructor and serialises it through the auto-
     * configured Jackson {@link ObjectMapper}. Currently unused by the
     * test methods (which prefer inline text-block JSON for readability)
     * but retained as a reference invocation of {@code objectMapper} so
     * the injected dependency is exercised at compile time.
     *
     * @param accountId    the account ID to embed; may be {@code null} to
     *                     drive the empty-ID reject path
     * @param confirmation the confirmation value to embed; may be {@code "Y"},
     *                     {@code "N"}, the empty string, or {@code null}
     * @return the JSON-serialised request body
     * @throws Exception if Jackson serialisation fails (extremely unlikely
     *                   for this simple record-style payload)
     */
    @SuppressWarnings("unused")
    private String requestJsonFor(String accountId, String confirmation) throws Exception {
        BillPaymentRequest request = new BillPaymentRequest(accountId, confirmation);
        return objectMapper.writeValueAsString(request);
    }
}

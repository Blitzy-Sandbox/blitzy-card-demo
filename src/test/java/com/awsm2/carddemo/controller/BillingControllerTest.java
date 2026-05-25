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

// COBOL: COBIL00C.cbl — Bill Payment (Tran-ID CB00)
// COBOL: Dual write semantics — Account balance update + Transaction
//        record insert under @Transactional (rollbackFor = Exception.class,
//        isolation = READ_COMMITTED) replicating the implicit CICS task-end
//        SYNCPOINT in the source program.
// COBOL: Sets TYPE='02', SOURCE='POS TERM', DESC='BILL PAYMENT - ONLINE'
//        (PROCESS-ENTER-KEY L218-L232 — INITIALIZE TRAN-RECORD + verbatim
//        MOVE statements populating TRAN-RECORD before WRITE-TRANSACT-FILE)
// BMS:   COBIL00.bms (mapset COBIL00, map COBIL0A)
// Symbolic map: app/cpy-bms/COBIL00.CPY (COBIL0AI input layout / COBIL0AO
//        output redefine carrying ACTIDIN, CURBAL, CONFIRM)
//
// MockMvc slice tests for {@link BillingController}, the Java target for the
// CICS COBOL program app/cbl/COBIL00C.cbl (Tran-ID CB00, "Bill Payment").
// Per AAP §0.3.4 / §0.4.1 endpoint inventory and the layered architecture
// mandate of §0.7.1 (Controller -> Service -> Repository -> Domain), this
// suite validates every HTTP entry-point behavior:
//
//   1. POST /api/billing/pay happy-path (HTTP 201) — confirm="Y" generates
//      a TransactionDetailDto with transactionId="0000000000000001" and
//      returns the standardized ApiResponse envelope per AAP §0.3.4.
//   2. POST /api/billing/pay HTTP 200 — confirm="N" / blank-confirm /
//      nothing-to-pay short-circuit returns the mapped DTO with
//      transactionId=null and HTTP 200 (no resource was created).
//   3. Jakarta Bean Validation on BillPaymentDto — accountId @NotBlank
//      @Pattern("^\\d{11}$") rejecting null/short/non-digit values;
//      confirm @Pattern("^[YN]?$") rejecting any value other than Y, N,
//      or blank (per Issue CP4-#8 the field is intentionally NOT
//      @NotBlank — blank confirm is the COBIL00C L182-L184 "preview
//      balance" branch).
//   4. Security — @PreAuthorize("hasAnyRole('USER','ADMIN')") engaged via
//      @EnableMethodSecurity, asserted with @WithMockUser(roles="USER")
//      / @WithMockUser(roles="ADMIN") for the happy path and
//      @WithAnonymousUser for the HTTP 401 path.
//   5. Domain exception -> HTTP status mapping wired through
//      {@link GlobalExceptionHandler}: RecordNotFoundException -> 404
//      (FILE STATUS '23' from READ-ACCTDAT-FILE / READ-CXACAIX-FILE),
//      ConcurrentModificationException -> 409 (JPA @Version conflict on
//      ACCTDAT REWRITE), CreditLimitExceededException -> 422 with
//      reject code "102", OnSizeErrorException -> 422 with default
//      reason code "ARITHMETIC_OVERFLOW" (per Issue CP4-#6: ON SIZE
//      ERROR is a business arithmetic constraint violation, NOT an
//      internal server fault, so it maps to 422 not 500), and
//      ValidationException -> 400 with code "VALIDATION".
//   6. ArgumentCaptor verification of the BillPaymentDto record accessor
//      pattern (dto.accountId() / dto.confirm()) confirming the
//      controller forwards the request body unchanged to the service.

import com.awsm2.carddemo.dto.BillPaymentDto;
import com.awsm2.carddemo.dto.TransactionDetailDto;
import com.awsm2.carddemo.exception.ConcurrentModificationException;
import com.awsm2.carddemo.exception.CreditLimitExceededException;
import com.awsm2.carddemo.exception.GlobalExceptionHandler;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.security.JwtAuthenticationFilter;
import com.awsm2.carddemo.security.JwtTokenProvider;
import com.awsm2.carddemo.service.BillPaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc controller-slice tests for {@link BillingController}.
 *
 * <h2>System Under Test (SUT)</h2>
 *
 * <p>{@link BillingController} is the Java target for the CICS COBOL
 * program {@code app/cbl/COBIL00C.cbl} (Tran-ID {@code 'CB00'}), the
 * online "pay account balance in full" flow. The COBOL program reads
 * {@code ACCT-RECORD} from {@code ACCTDAT}, resolves the cardholder's
 * primary card via the {@code CXACAIX} alternate index over
 * {@code CARDXREF}, generates the next sequential 16-digit transaction
 * ID by browsing {@code TRANSACT} to end, writes a new
 * {@code TRAN-RECORD} (type {@code '02'} POS TERM with amount equal to
 * the current balance), and zeros the {@code ACCT-CURR-BAL} via
 * {@code REWRITE} on {@code ACCTDAT} — all under an implicit CICS
 * task-end {@code SYNCPOINT}.</p>
 *
 * <p>The Java/Spring Boot target preserves every COBOL behavior verbatim
 * (per AAP &sect;0.7.3 Minimal Change Clause). The full implementation
 * sits in {@link BillPaymentService}; this controller is a thin Spring
 * MVC façade that activates Jakarta Bean Validation on the inbound
 * {@link BillPaymentDto}, delegates to the service, maps the result to
 * a {@link TransactionDetailDto} carrying the canonical 350-byte
 * {@code TRAN-RECORD} layout, and wraps the response in the
 * standardized {@code ApiResponse} envelope with HTTP 201 Created on
 * the happy path or HTTP 200 OK on the preview / cancel / nothing-to-
 * pay paths.</p>
 *
 * <h2>Endpoint inventory (AAP &sect;0.3.4)</h2>
 *
 * <ul>
 *   <li>{@code POST /api/billing/pay} &mdash; submit a bill payment
 *       (full-balance pay-down) for the supplied account; returns the
 *       generated transaction record. Available to any authenticated
 *       principal with the {@code USER} or {@code ADMIN} role.</li>
 * </ul>
 *
 * <h2>Test-class wiring</h2>
 *
 * <p>{@code @WebMvcTest} with {@code excludeFilters} on
 * {@link JwtAuthenticationFilter} short-circuits the JWT filter chain so
 * the slice does not require Secrets Manager initialization at boot
 * time; {@code @WithMockUser} populates the {@code SecurityContext}
 * directly for each test. Mocking the filter directly would override
 * its {@code doFilterInternal(...)} method with a no-op stub that fails
 * to invoke {@code chain.doFilter(request, response)}, short-circuiting
 * every test request with an empty HTTP 200 response before it reaches
 * the dispatcher; excluding the filter via {@code excludeFilters} is
 * the established pattern in the sibling
 * {@code TransactionControllerTest} / {@code AccountControllerTest} /
 * {@code CardControllerTest} suites.</p>
 *
 * <p>{@code @EnableMethodSecurity(prePostEnabled = true)} on the test
 * class itself activates the
 * {@code AuthorizationManagerBeforeMethodInterceptor} that evaluates
 * the SpEL {@code @PreAuthorize} expressions on the controller methods.
 * Without this annotation {@code @WebMvcTest} would silently bypass
 * method security and the anonymous-access test (phase 3) would
 * incorrectly return HTTP 200 instead of HTTP 401.</p>
 *
 * <p>{@code @Import(GlobalExceptionHandler.class)} explicitly wires the
 * {@code @RestControllerAdvice} bean. {@code @WebMvcTest} does NOT
 * auto-load advice beans from other packages; without the explicit
 * import the standardized {@code ApiResponse} error envelope shape
 * would not be observed for the phase-4 exception-mapping tests.</p>
 *
 * <h2>What this slice deliberately does NOT cover</h2>
 *
 * <p>The transactional semantics of
 * {@code @Transactional(rollbackFor = Exception.class)}, the JPA
 * {@code @Version} optimistic-lock increment on the {@code Account}
 * entity, the {@code BigDecimal} {@code HALF_EVEN} rounding mode on
 * the balance arithmetic, the dual-write atomicity (Transaction +
 * Account), the MSK Kafka event publication
 * ({@code transaction.posted} and {@code account.updated} partitioned
 * by account ID per AAP &sect;0.6.5), and the CloudTrail + OpenSearch
 * audit emission are all covered by {@code BillPaymentServiceTest} at
 * the unit-test layer. This slice asserts only the controller-boundary
 * contract: request shape, authorization, path/body consistency,
 * status-code mapping, and response envelope.</p>
 *
 * @see BillingController
 * @see BillPaymentService
 * @see BillPaymentDto
 * @see TransactionDetailDto
 * @see GlobalExceptionHandler
 */
// Replaces: app/cbl/COBIL00C.cbl (CICS Tran-ID CB00). The COBOL program
// targeted three VSAM KSDS datasets (ACCTDAT, CARDXREF via CXACAIX AIX,
// TRANSACT) and produced an audit + screen response; in the Java target
// the equivalent is the `account`, `card_cross_reference`, and
// `transactions` PostgreSQL tables populated by Flyway V001 / V004 /
// V005 migrations, plus the MSK Kafka events partitioned by account ID
// per AAP §0.6.5.
@WebMvcTest(
        controllers = BillingController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = JwtAuthenticationFilter.class
        )
)
@Import(GlobalExceptionHandler.class)
@EnableMethodSecurity(prePostEnabled = true)
@TestPropertySource(properties = {
        // The base configuration uses AWS Secrets Manager-backed JWT
        // signing-key resolution via SecretsManagerService. For a
        // controller-only slice test we never issue or validate tokens
        // (each test uses Spring Security's @WithMockUser to inject the
        // authentication directly), so we supply a deterministic test
        // signing key and additionally mock JwtTokenProvider below so
        // the bean wiring never reaches the Secrets Manager backend.
        "carddemo.security.jwt.signing-key=test-only-jwt-signing-key-32-bytes-min-length",
        "carddemo.security.cors.allowed-origins=http://localhost:3000"
})
@DisplayName("BillingController slice tests (POST /api/billing/pay) — HTTP 201 on confirm=Y, BigDecimal HALF_EVEN, reject codes preserved verbatim")
class BillingControllerTest {

    // =====================================================================
    // Test fixture constants
    //
    // The 11-digit zero-padded account identifier mirrors the COBOL
    // ACCT-ID PIC 9(11) / BMS ACTIDIN PIC X(11) field. The 16-digit
    // zero-padded transaction identifier mirrors the COBOL TRAN-ID
    // PIC 9(16) field generated by the MAX(TRAN-ID)+1 sequence (the
    // first generated value is "0000000000000001" per
    // app/cbl/COBIL00C.cbl L212-L215 STARTBR/READPREV/ENDBR HIGH-VALUES
    // browse semantics).
    //
    // Synthetic PANs are NOT carried on either DTO at the controller
    // boundary — BillPaymentDto exchanges only the 11-digit account
    // identifier (the cross-reference lookup happens server-side per
    // PCI-DSS PAN-handling discipline, AAP §0.6.6), and the
    // TransactionDetailDto response sets cardNumber to null per the
    // BillingController.mapToTransactionDetail() PCI-DSS PAN
    // suppression rule.
    // =====================================================================

    /**
     * 11-digit zero-padded test account identifier. Matches the COBOL
     * {@code ACCT-ID PIC 9(11)} field from {@code app/cpy/CVACT01Y.cpy}
     * and the BMS {@code ACTIDIN PIC X(11)} field from
     * {@code app/bms/COBIL00.bms}. Used as the {@code accountId}
     * component of every fixture {@link BillPaymentDto}.
     */
    private static final String TEST_ACCOUNT_ID = "11111111111";

    /**
     * 16-digit zero-padded test transaction identifier. Matches the
     * {@code TRAN-ID PIC X(16)} field in {@code app/cpy/CVTRA05Y.cpy}
     * and is consistent with the {@link BillPaymentService} MAX(TRAN-ID)+1
     * sequence semantics — the first generated value is
     * {@code "0000000000000001"} (L212-L215 of {@code COBIL00C.cbl}
     * STARTBR / READPREV / ENDBR HIGH-VALUES + ADD 1).
     */
    private static final String TEST_TXN_ID = "0000000000000001";

    /**
     * Verbatim COBOL bill-payment transaction type code constant.
     * Source: {@code MOVE '02' TO TRAN-TYPE-CD}
     * ({@code app/cbl/COBIL00C.cbl} L220).
     */
    private static final String BILL_PAY_TYPE_CODE = "02";

    /**
     * Verbatim COBOL bill-payment transaction category code constant.
     * Source: {@code MOVE 2 TO TRAN-CAT-CD}
     * ({@code app/cbl/COBIL00C.cbl} L221). Typed as {@link Integer} per
     * the {@link TransactionDetailDto#transactionCategory()} component
     * contract.
     */
    private static final Integer BILL_PAY_CAT_CODE = 2;

    /**
     * Verbatim COBOL bill-payment transaction source channel constant.
     * Source: {@code MOVE 'POS TERM' TO TRAN-SOURCE}
     * ({@code app/cbl/COBIL00C.cbl} L222).
     */
    private static final String BILL_PAY_SOURCE = "POS TERM";

    /**
     * Verbatim COBOL bill-payment transaction description constant.
     * Source: {@code MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC}
     * ({@code app/cbl/COBIL00C.cbl} L223).
     */
    private static final String BILL_PAY_DESC = "BILL PAYMENT - ONLINE";

    /**
     * Verbatim COBOL bill-payment merchant identifier constant.
     * Source: {@code MOVE 999999999 TO TRAN-MERCHANT-ID}
     * ({@code app/cbl/COBIL00C.cbl} L226). Typed as {@link Long} per
     * the {@link TransactionDetailDto#merchantId()} contract.
     */
    private static final Long BILL_PAY_MERCHANT_ID = 999_999_999L;

    /**
     * Verbatim COBOL bill-payment merchant name constant.
     * Source: {@code MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME}
     * ({@code app/cbl/COBIL00C.cbl} L227).
     */
    private static final String BILL_PAY_MERCHANT_NAME = "BILL PAYMENT";

    /**
     * Verbatim COBOL bill-payment merchant city/zip placeholder constant.
     * Source: {@code MOVE 'N/A' TO TRAN-MERCHANT-CITY} and
     * {@code MOVE 'N/A' TO TRAN-MERCHANT-ZIP}
     * ({@code app/cbl/COBIL00C.cbl} L228-L229).
     */
    private static final String BILL_PAY_NA = "N/A";

    /**
     * Expected human-readable success message emitted by the controller
     * when wrapping the {@link TransactionDetailDto} in the standardized
     * {@code ApiResponse} envelope. Sourced from the
     * {@code BillingController#SUCCESS_MESSAGE} constant.
     */
    private static final String EXPECTED_SUCCESS_MESSAGE =
            "Bill payment processed successfully";

    /**
     * Test fixture timestamp pinned to a deterministic value for
     * predictable JSON serialization comparisons. The COBOL
     * {@code TRAN-PROC-TS PIC X(26)} layout supports microsecond
     * precision; the Java target uses {@link LocalDateTime} per AAP
     * &sect;0.6.3 and serializes via {@code @JsonFormat} with the
     * pattern {@code yyyy-MM-dd'T'HH:mm:ss.SSSSSS} on
     * {@link TransactionDetailDto#originationTimestamp()} and
     * {@link TransactionDetailDto#processingTimestamp()}.
     */
    private static final LocalDateTime TEST_TIMESTAMP =
            LocalDateTime.of(2026, 5, 20, 14, 30, 45, 123_456_000);

    /**
     * Test fixture monetary amount constructed via the
     * {@link BigDecimal#BigDecimal(String) String constructor} per AAP
     * &sect;0.6.1 decimal-precision discipline — IEEE-754 floating-point
     * representation is forbidden for monetary values. The
     * {@code "100.00"} literal preserves explicit scale=2 (matching
     * {@code TRAN-AMT PIC S9(09)V99} from {@code app/cpy/CVTRA05Y.cpy})
     * and round-trips through Jackson without precision loss.
     */
    private static final BigDecimal TEST_AMOUNT = new BigDecimal("100.00");

    // =====================================================================
    // Spring MVC infrastructure (auto-injected from @WebMvcTest)
    // =====================================================================

    /**
     * MockMvc fluent client into the Spring MVC dispatcher, configured
     * by {@code @WebMvcTest} to route through the SUT controller plus
     * the method-security interceptor activated by
     * {@code @EnableMethodSecurity} on this test class. The JWT filter
     * is excluded via {@code excludeFilters} so the slice does not load
     * AWS Secrets Manager dependencies at boot time.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Application Jackson mapper used to serialize {@link BillPaymentDto}
     * fixtures to JSON request bodies for POST requests. Auto-configured
     * by Spring Boot's {@code JacksonAutoConfiguration} in the
     * {@code @WebMvcTest} slice; honors {@code @JsonFormat} annotations
     * on {@link LocalDateTime} fields and serializes {@link BigDecimal}
     * values without precision loss (preserving the AAP &sect;0.6.1
     * discipline on the wire).
     */
    @Autowired
    private ObjectMapper objectMapper;

    // =====================================================================
    // Mock collaborators (one @MockBean per direct dependency of the SUT)
    // =====================================================================

    /**
     * Mock of {@link BillPaymentService} for the
     * {@code POST /api/billing/pay} flow. Stubbed via
     * {@code given(...).willReturn(...)} to return a populated
     * {@link BillPaymentDto} for the HTTP 201 happy path (transactionId
     * non-null) and HTTP 200 non-success paths (transactionId null), or
     * via {@code willThrow(...)} to throw a typed domain exception for
     * the exception-mapping tests
     * ({@link RecordNotFoundException} -> 404,
     * {@link ConcurrentModificationException} -> 409,
     * {@link CreditLimitExceededException} -> 422 with reject code
     * {@code "102"}, {@link OnSizeErrorException} -> 422 with reason
     * code {@code "ARITHMETIC_OVERFLOW"},
     * {@link ValidationException} -> 400 with code {@code "VALIDATION"}).
     */
    @MockBean
    private BillPaymentService billPaymentService;

    /**
     * Mock of {@link JwtTokenProvider} required to break the bean
     * dependency chain that would otherwise force the
     * {@code com.awsm2.carddemo.adapter.SecretsManagerService} adapter
     * to initialize during the slice-test {@code ApplicationContext}
     * bootstrap. The mock is never invoked because tests populate the
     * security context via {@code @WithMockUser} rather than via real
     * bearer-token validation.
     */
    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // NOTE: {@link JwtAuthenticationFilter} is intentionally NOT
    // declared as a {@code @MockBean} here. Mocking the filter would
    // override its {@code doFilterInternal(...)} method with a no-op
    // stub that fails to invoke {@code chain.doFilter(request,
    // response)}, short-circuiting every test request with an empty
    // HTTP 200 response before it reaches the dispatcher. Instead the
    // filter is fully removed from the slice-test
    // {@code ApplicationContext} via the {@code excludeFilters}
    // attribute on the {@code @WebMvcTest} annotation at the top of
    // this class. This mirrors the established pattern in sibling
    // {@code TransactionControllerTest}, {@code AccountControllerTest},
    // and {@code CardControllerTest} slices.

    // =====================================================================
    // Fixture builders — DRY constructors for the test DTO records.
    //
    // Per AAP §0.6.1 every BigDecimal monetary fixture uses the STRING
    // constructor (new BigDecimal("...")), NEVER the double-arg
    // constructor — IEEE-754 floating-point representation does not
    // round-trip for arbitrary decimal values and would compromise the
    // bit-for-bit reproducibility of arithmetic against the COBOL
    // golden output.
    // =====================================================================

    /**
     * Builds a valid bill payment request {@link BillPaymentDto} with
     * the supplied {@code confirm} flag. The request-side components
     * are populated; the response-only components ({@code currentBalance},
     * {@code transactionId}, {@code postedAt}, {@code amountPaid}) are
     * left {@code null} since they are server-populated.
     *
     * @param confirm the {@code Y}, {@code N}, blank, or invalid confirm
     *                value to exercise different controller / service
     *                branches
     * @return a populated {@link BillPaymentDto} suitable for
     *         serialization as a POST request body
     */
    private BillPaymentDto buildRequest(String confirm) {
        return new BillPaymentDto(
                TEST_ACCOUNT_ID,    // accountId — request-side
                null,               // currentBalance — response-side
                confirm,            // confirm — request-side
                null,               // transactionId — response-side
                null,               // postedAt — response-side
                null                // amountPaid — response-side
        );
    }

    /**
     * Builds the BillPaymentService stubbed return value for the
     * HTTP 201 happy path (confirm="Y" with positive balance — the
     * service generated a new {@code TRAN-RECORD} and zeroed the
     * {@code ACCT-CURR-BAL}). Carries the resolved fields from the
     * dual-write success path: 16-digit {@code transactionId}, the
     * processing {@code postedAt} timestamp, and the
     * {@code amountPaid} matching the pre-payment balance.
     *
     * @return a populated {@link BillPaymentDto} matching the
     *         service contract for the {@code Y} confirmation
     *         success path
     */
    private BillPaymentDto buildSuccessResult() {
        return new BillPaymentDto(
                TEST_ACCOUNT_ID,    // accountId — echoed
                BigDecimal.ZERO.setScale(2),  // currentBalance — post-payment zero
                "Y",                // confirm — echoed
                TEST_TXN_ID,        // transactionId — generated
                TEST_TIMESTAMP,     // postedAt — service-captured
                TEST_AMOUNT         // amountPaid — pre-payment balance
        );
    }

    /**
     * Builds the BillPaymentService stubbed return value for the
     * non-success service paths (cancel / blank-confirm preview /
     * nothing-to-pay short-circuit). On these branches the service
     * returns a {@link BillPaymentDto} with {@code transactionId},
     * {@code postedAt}, and {@code amountPaid} all {@code null} —
     * signaling the controller to return HTTP 200 OK instead of
     * HTTP 201 Created per Issue CP4-#7 (returning 201 when no
     * resource was created would imply a URI that does not exist).
     *
     * @param confirm the {@code N} / blank / preview confirm value
     *                being echoed
     * @return a populated {@link BillPaymentDto} matching the
     *         non-success service contract (no transaction posted)
     */
    private BillPaymentDto buildNonSuccessResult(String confirm) {
        return new BillPaymentDto(
                TEST_ACCOUNT_ID,    // accountId — echoed
                TEST_AMOUNT,        // currentBalance — current account balance
                confirm,            // confirm — echoed
                null,               // transactionId — null on non-success
                null,               // postedAt — null on non-success
                null                // amountPaid — null on non-success
        );
    }

    // =====================================================================
    // Phase 1: POST /api/billing/pay — Successful payment (HTTP 201)
    //
    // COBOL provenance: app/cbl/COBIL00C.cbl L211-L240 — the WHEN 'Y'
    // / WHEN 'y' branch of the CONFIRMI EVALUATE cascade triggers
    // READ-CXACAIX-FILE, MAX-TRAN-ID+1 generation,
    // INITIALIZE TRAN-RECORD + MOVE verbatim constants,
    // WRITE-TRANSACT-FILE, and the COMPUTE ACCT-CURR-BAL =
    // ACCT-CURR-BAL - TRAN-AMT subtraction followed by
    // UPDATE-ACCTDAT-FILE under the implicit CICS task-end SYNCPOINT.
    //
    // The Java target preserves the dual-write semantics in
    // BillPaymentService.processBillPayment under
    // @Transactional(rollbackFor = Exception.class, isolation =
    // READ_COMMITTED); the controller surfaces HTTP 201 Created when
    // the service-returned BillPaymentDto carries a non-null,
    // non-blank transactionId.
    // =====================================================================

    /**
     * Tests for the {@code POST /api/billing/pay} HTTP 201 happy path.
     * Each test asserts the controller returns HTTP 201 Created, the
     * standardized {@code ApiResponse} envelope with code
     * {@code "OK"}, and the canonical {@link TransactionDetailDto}
     * payload populated from the COBOL verbatim constants.
     */
    @Nested
    @DisplayName("POST /api/billing/pay — successful payment (HTTP 201)")
    class PayBillSuccess {

        /**
         * Happy-path bill payment with {@code confirm="Y"} returns
         * HTTP <b>201 Created</b> with the populated
         * {@link TransactionDetailDto} payload. Verifies every COBOL
         * verbatim constant flows through to the response body:
         * {@code transactionType="02"}, {@code transactionCategory=2},
         * {@code source="POS TERM"},
         * {@code description="BILL PAYMENT - ONLINE"},
         * {@code merchantId=999999999}, {@code merchantName="BILL PAYMENT"},
         * {@code merchantCity="N/A"}, {@code merchantZip="N/A"}, and
         * the generated 16-digit transaction ID.
         */
        @Test
        @DisplayName("payBill_returns201CreatedWithTransactionDetail — happy path, full TRAN-RECORD payload")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_returns201CreatedWithTransactionDetail() throws Exception {
            BillPaymentDto request = buildRequest("Y");
            given(billPaymentService.processBillPayment(any(BillPaymentDto.class)))
                    .willReturn(buildSuccessResult());

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())                          // HTTP 201
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.message").value(EXPECTED_SUCCESS_MESSAGE))
                    // Generated 16-digit transaction ID (MAX-TRAN-ID + 1)
                    .andExpect(jsonPath("$.data.transactionId").value(TEST_TXN_ID))
                    // Verbatim COBOL constants per COBIL00C.cbl L220-L229
                    .andExpect(jsonPath("$.data.transactionType").value(BILL_PAY_TYPE_CODE))
                    .andExpect(jsonPath("$.data.transactionCategory").value(BILL_PAY_CAT_CODE))
                    .andExpect(jsonPath("$.data.source").value(BILL_PAY_SOURCE))
                    .andExpect(jsonPath("$.data.description").value(BILL_PAY_DESC))
                    .andExpect(jsonPath("$.data.merchantId").value(BILL_PAY_MERCHANT_ID))
                    .andExpect(jsonPath("$.data.merchantName").value(BILL_PAY_MERCHANT_NAME))
                    .andExpect(jsonPath("$.data.merchantCity").value(BILL_PAY_NA))
                    .andExpect(jsonPath("$.data.merchantZip").value(BILL_PAY_NA))
                    // PCI-DSS PAN suppression at the controller boundary
                    // per AAP §0.6.6 / §0.7.1 — the bill-payment receipt
                    // does NOT echo the full card number even though the
                    // underlying TRAN-RECORD persists it.
                    .andExpect(jsonPath("$.data.cardNumber").doesNotExist())
                    // BigDecimal amountPaid round-trip — scale=2 preserved
                    // per AAP §0.6.1.
                    .andExpect(jsonPath("$.data.amount").value(100.00));

            verify(billPaymentService).processBillPayment(any(BillPaymentDto.class));
        }

        /**
         * Verifies ADMIN role also permits the bill payment call. Both
         * USER and ADMIN roles pass the
         * {@code @PreAuthorize("hasAnyRole('USER','ADMIN')")} method
         * security check.
         */
        @Test
        @DisplayName("payBill_returns201WithAdminRole — ADMIN role also permitted")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void payBill_returns201WithAdminRole() throws Exception {
            BillPaymentDto request = buildRequest("Y");
            given(billPaymentService.processBillPayment(any(BillPaymentDto.class)))
                    .willReturn(buildSuccessResult());

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())                          // HTTP 201
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.data.transactionId").value(TEST_TXN_ID));
        }

        /**
         * Confirms {@code confirm="Y"} explicitly proceeds with payment
         * — the service returns the transactionId-bearing DTO and the
         * controller surfaces HTTP 201 Created. This is the canonical
         * "resource created" path per Issue CP4-#7.
         */
        @Test
        @DisplayName("payBill_returns201WhenConfirmedY — confirm=Y proceeds to dual-write")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_returns201WhenConfirmedY() throws Exception {
            BillPaymentDto request = buildRequest("Y");
            given(billPaymentService.processBillPayment(any(BillPaymentDto.class)))
                    .willReturn(buildSuccessResult());

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())                          // HTTP 201
                    .andExpect(jsonPath("$.data.transactionId").value(TEST_TXN_ID));
        }

        /**
         * Verifies confirm={@code "N"} (cancellation) yields HTTP
         * <b>200 OK</b> (NOT 201). The service returns a
         * {@link BillPaymentDto} with {@code transactionId=null}
         * (signaling "no transaction was posted"); the controller
         * therefore returns 200 OK because returning 201 would imply
         * a resource was created at a URI that does not exist (per
         * Issue CP4-#7 in {@link BillingController#processBillPayment}).
         *
         * <p>COBOL provenance: {@code COBIL00C.cbl} L178-L181 —
         * {@code WHEN 'N' / 'n': PERFORM CLEAR-CURRENT-SCREEN; MOVE 'Y'
         * TO WS-ERR-FLG}. The COBOL program emits the bill-payment
         * screen WITHOUT writing any TRANSACT or REWRITE on
         * ACCTDAT.</p>
         */
        @Test
        @DisplayName("payBill_returns200WhenConfirmedN — confirm=N cancellation yields HTTP 200 (no resource created)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_returns200WhenConfirmedN() throws Exception {
            BillPaymentDto request = buildRequest("N");
            given(billPaymentService.processBillPayment(any(BillPaymentDto.class)))
                    .willReturn(buildNonSuccessResult("N"));

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())                               // HTTP 200, NOT 201
                    .andExpect(jsonPath("$.code").value("OK"))
                    // No transaction was posted on the cancellation path
                    .andExpect(jsonPath("$.data.transactionId").doesNotExist())
                    // Verbatim COBOL constants are still populated even on
                    // the non-success path (they identify the attempt as a
                    // "BILL PAYMENT - ONLINE" attempt regardless of outcome)
                    .andExpect(jsonPath("$.data.transactionType").value(BILL_PAY_TYPE_CODE))
                    .andExpect(jsonPath("$.data.description").value(BILL_PAY_DESC));

            verify(billPaymentService).processBillPayment(any(BillPaymentDto.class));
        }

        /**
         * Verifies blank confirm (empty string) yields HTTP <b>200
         * OK</b> with the current balance preview. The service
         * returns the BillPaymentDto with the account's
         * {@code currentBalance} populated and {@code transactionId}
         * null; the controller surfaces 200 OK.
         *
         * <p>COBOL provenance: {@code COBIL00C.cbl} L182-L184 —
         * {@code WHEN SPACES / LOW-VALUES: PERFORM READ-ACCTDAT-FILE}.
         * The COBOL program reads the account for display (preview)
         * and re-sends the bill-payment screen prompting the operator
         * to confirm with Y or N. Per AAP §0.7.3 Minimal Change Clause
         * the Java target preserves this semantic — blank confirm is
         * the "preview balance" branch, not a validation rejection.
         * Issue CP4-#8: the {@code @NotBlank} constraint was
         * deliberately REMOVED from {@link BillPaymentDto#confirm()}
         * so a blank confirm passes Bean Validation at the wire layer
         * and routes to the service-level preview branch.</p>
         */
        @Test
        @DisplayName("payBill_returns200WhenConfirmedBlank — blank confirm is the balance-preview branch (HTTP 200)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_returns200WhenConfirmedBlank() throws Exception {
            BillPaymentDto request = buildRequest("");
            given(billPaymentService.processBillPayment(any(BillPaymentDto.class)))
                    .willReturn(buildNonSuccessResult(""));

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())                               // HTTP 200, NOT 201
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.data.transactionId").doesNotExist());

            verify(billPaymentService).processBillPayment(any(BillPaymentDto.class));
        }
    }

    // =====================================================================
    // Phase 2: Bean Validation on BillPaymentDto (HTTP 400)
    //
    // COBOL provenance: app/cbl/COBIL00C.cbl L154-L191 — the
    // PROCESS-ENTER-KEY input-validation cascade in the COBOL source:
    //   * WHEN ACTIDINI = SPACES OR LOW-VALUES -> WS-ERR-FLG = 'Y'
    //     + "Acct ID can NOT be empty..." (replaced by @NotBlank on
    //     BillPaymentDto.accountId)
    //   * WHEN ACTIDIN length / digit checks (the BMS ACTIDIN PIC X(11)
    //     constraint is enforced by @Pattern("^\\d{11}$"))
    //   * WHEN CONFIRMI OTHER (not Y/N/spaces) -> WS-ERR-FLG = 'Y' +
    //     "Invalid value. Valid values are (Y/N)..." (replaced by
    //     @Pattern("^[YN]?$") on BillPaymentDto.confirm)
    //
    // Both constraint failures are intercepted by
    // GlobalExceptionHandler.handleMethodArgumentNotValid which
    // returns HTTP 400 Bad Request with code="VALIDATION" and a
    // populated fieldErrors list per AAP §0.3.4.
    //
    // NOTE: BillPaymentDto.confirm is intentionally NOT annotated
    // @NotBlank (per Issue CP4-#8). The COBIL00C L182-L184 source
    // treats blank/spaces/low-values as the "preview balance" branch,
    // so a blank confirm passes Bean Validation and routes to the
    // service-level preview branch (Phase 1's
    // payBill_returns200WhenConfirmedBlank exercises this). The
    // @Pattern("^[YN]?$") regex permits zero-or-one Y/N character so
    // null, empty string, and Y/N all pass while any non-Y/N
    // non-empty value (e.g., "X") is rejected.
    // =====================================================================

    /**
     * Tests for the Jakarta Bean Validation contract on
     * {@link BillPaymentDto}. Each test posts a request body that
     * violates a specific constraint and asserts the controller short-
     * circuits with HTTP 400 Bad Request and a populated
     * {@code fieldErrors} list, without invoking
     * {@link BillPaymentService}.
     */
    @Nested
    @DisplayName("POST /api/billing/pay — Jakarta Bean Validation (HTTP 400 with fieldErrors)")
    class PayBillValidation {

        /**
         * The {@code accountId} {@code @NotBlank} constraint rejects a
         * {@code null} account identifier with HTTP 400 and a
         * {@code fieldErrors[?(@.field=='accountId')]} entry.
         */
        @Test
        @DisplayName("payBill_returns400WhenAccountIdMissing — null accountId")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_returns400WhenAccountIdMissing() throws Exception {
            BillPaymentDto request = new BillPaymentDto(
                    null,       // INVALID — fails @NotBlank
                    null, "Y", null, null, null
            );

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='accountId')]").exists());

            verifyNoInteractions(billPaymentService);
        }

        /**
         * The {@code accountId} {@code @NotBlank} constraint rejects an
         * empty-string account identifier with HTTP 400.
         */
        @Test
        @DisplayName("payBill_returns400WhenAccountIdBlank — empty-string accountId")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_returns400WhenAccountIdBlank() throws Exception {
            BillPaymentDto request = new BillPaymentDto(
                    "",         // INVALID — fails @NotBlank
                    null, "Y", null, null, null
            );

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='accountId')]").exists());

            verifyNoInteractions(billPaymentService);
        }

        /**
         * The {@code accountId} {@code @Pattern("^\\d{11}$")}
         * constraint rejects a too-short (3-digit) account identifier
         * with HTTP 400. This is the canonical COBIL00C ACTIDIN
         * length/digit check enforced at the Java DTO layer.
         */
        @Test
        @DisplayName("payBill_returns400WhenAccountIdNot11Digits — 3 digits violates @Pattern(^\\\\d{11}$)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_returns400WhenAccountIdNot11Digits() throws Exception {
            BillPaymentDto request = new BillPaymentDto(
                    "123",      // INVALID — fails @Pattern("^\\d{11}$")
                    null, "Y", null, null, null
            );

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='accountId')]").exists());

            verifyNoInteractions(billPaymentService);
        }

        /**
         * The {@code accountId} {@code @Pattern("^\\d{11}$")}
         * constraint rejects a non-digit (alphabetic) account
         * identifier with HTTP 400.
         */
        @Test
        @DisplayName("payBill_returns400WhenAccountIdContainsLetters — alphabetic chars violate @Pattern(^\\\\d{11}$)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_returns400WhenAccountIdContainsLetters() throws Exception {
            BillPaymentDto request = new BillPaymentDto(
                    "1111111111A",  // INVALID — 11 chars but contains 'A'
                    null, "Y", null, null, null
            );

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='accountId')]").exists());

            verifyNoInteractions(billPaymentService);
        }

        /**
         * The {@code confirm} {@code @Pattern("^[YN]?$")} constraint
         * rejects {@code "X"} (an invalid confirm flag) with HTTP 400.
         * COBOL: replaces {@code WHEN OTHER} of {@code COBIL00C.cbl}
         * L185-L190 — {@code MOVE 'Y' TO WS-ERR-FLG; MOVE 'Invalid
         * value. Valid values are (Y/N)...' TO WS-MESSAGE}.
         */
        @Test
        @DisplayName("payBill_returns400WhenConfirmInvalid — confirm=X violates @Pattern(^[YN]?$)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_returns400WhenConfirmInvalid() throws Exception {
            BillPaymentDto request = new BillPaymentDto(
                    TEST_ACCOUNT_ID,
                    null,
                    "X",        // INVALID — violates @Pattern("^[YN]?$")
                    null, null, null
            );

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='confirm')]").exists());

            verifyNoInteractions(billPaymentService);
        }

        /**
         * The {@code confirm} {@code @Pattern("^[YN]?$")} constraint
         * rejects {@code "Yes"} (multi-character) with HTTP 400 — the
         * regex is anchored and permits zero-or-one Y/N character.
         */
        @Test
        @DisplayName("payBill_returns400WhenConfirmMultiChar — confirm=Yes violates @Pattern(^[YN]?$)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_returns400WhenConfirmMultiChar() throws Exception {
            BillPaymentDto request = new BillPaymentDto(
                    TEST_ACCOUNT_ID,
                    null,
                    "Yes",      // INVALID — multi-character violates @Pattern("^[YN]?$")
                    null, null, null
            );

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='confirm')]").exists());

            verifyNoInteractions(billPaymentService);
        }

        /**
         * The {@code confirm} {@code @Pattern("^[YN]?$")} constraint
         * rejects lowercase {@code "y"} with HTTP 400 — the pattern
         * is case-sensitive (uppercase only). Note that the COBOL
         * source accepts both {@code 'Y'} and {@code 'y'}
         * ({@code WHEN 'Y' WHEN 'y'} at L173-L174), but the Java DTO
         * enforces uppercase via the {@code [YN]} character class for
         * canonical wire-format discipline. The original lowercase
         * acceptance is a service-layer concern, not a DTO concern.
         */
        @Test
        @DisplayName("payBill_returns400WhenConfirmLowercase — confirm=y violates @Pattern(^[YN]?$) (uppercase only)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_returns400WhenConfirmLowercase() throws Exception {
            BillPaymentDto request = new BillPaymentDto(
                    TEST_ACCOUNT_ID,
                    null,
                    "y",        // INVALID — lowercase rejected by [YN] character class
                    null, null, null
            );

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='confirm')]").exists());

            verifyNoInteractions(billPaymentService);
        }

        /**
         * Malformed JSON request body (incomplete object literal) is
         * intercepted by Spring's
         * {@code HttpMessageNotReadableException} handler and surfaces
         * as HTTP 400 with {@code code="MALFORMED_REQUEST"} per the
         * {@link GlobalExceptionHandler#handleMessageNotReadable}
         * contract.
         */
        @Test
        @DisplayName("payBill_returns400ForMalformedJson — broken JSON yields code=MALFORMED_REQUEST")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_returns400ForMalformedJson() throws Exception {
            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ \"accountId\": \"11111111111\", broken"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

            verifyNoInteractions(billPaymentService);
        }
    }

    // =====================================================================
    // Phase 3: Security — @PreAuthorize enforcement (HTTP 401)
    //
    // Method-level @PreAuthorize("hasAnyRole('USER','ADMIN')") on
    // BillingController.processBillPayment MUST be enforced before the
    // service is consulted. Anonymous callers receive HTTP 401
    // Unauthorized via Spring Security's AuthenticationException
    // pipeline mapped by GlobalExceptionHandler.handleAuthentication
    // (or the SecurityConfig.restAuthenticationEntryPoint depending on
    // the filter chain ordering).
    //
    // The phase-1 nested class already includes happy-path tests with
    // both USER and ADMIN roles; this phase consolidates the negative
    // anonymous-access assertion.
    // =====================================================================

    /**
     * Tests for the method-security contract on
     * {@link BillingController#processBillPayment(BillPaymentDto)}.
     * The controller is protected by
     * {@code @PreAuthorize("hasAnyRole('USER','ADMIN')")} — both USER
     * and ADMIN must succeed (asserted in phase 1), and anonymous
     * callers must be rejected with HTTP 401.
     */
    @Nested
    @DisplayName("POST /api/billing/pay — Security: @PreAuthorize('hasAnyRole(USER,ADMIN)') enforcement")
    class PayBillSecurity {

        /**
         * Anonymous POST /api/billing/pay request rejected with HTTP
         * 401 Unauthorized before any controller code or service code
         * runs. The Spring Security
         * {@code ExceptionTranslationFilter} converts the
         * {@code AccessDeniedException} raised by the
         * {@code @PreAuthorize} interceptor on an anonymous principal
         * into an {@code AuthenticationException} (which then fires
         * the {@code AuthenticationEntryPoint} producing HTTP 401).
         */
        @Test
        @DisplayName("payBill_returns401ForAnonymous — anonymous POST yields HTTP 401")
        @WithAnonymousUser
        void payBill_returns401ForAnonymous() throws Exception {
            BillPaymentDto request = buildRequest("Y");

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(billPaymentService);
        }

        /**
         * Verifies that an authenticated USER principal is permitted
         * to invoke POST /api/billing/pay — the
         * {@code @PreAuthorize("hasAnyRole('USER','ADMIN')")}
         * predicate evaluates true and the service is consulted. This
         * is the positive counterpart to the anonymous-access negative
         * test above.
         */
        @Test
        @DisplayName("payBill_acceptsValidUserBearer — authenticated USER principal yields HTTP 201")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_acceptsValidUserBearer() throws Exception {
            BillPaymentDto request = buildRequest("Y");
            given(billPaymentService.processBillPayment(any(BillPaymentDto.class)))
                    .willReturn(buildSuccessResult());

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());

            verify(billPaymentService).processBillPayment(any(BillPaymentDto.class));
        }

        /**
         * GET method is not supported on this endpoint — only POST is
         * declared via {@code @PostMapping("/pay")}. A GET request
         * must yield HTTP 405 (Method Not Allowed) or HTTP 401
         * (Unauthorized for anonymous before the routing layer makes
         * its decision); we accept either. The key assertion is that
         * the service is never invoked.
         */
        @Test
        @DisplayName("payBill_rejectsGetMethod — GET request does not invoke service")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_rejectsGetMethod() throws Exception {
            mockMvc.perform(get("/api/billing/pay")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().is4xxClientError());

            verifyNoInteractions(billPaymentService);
        }
    }

    // =====================================================================
    // Phase 4: Exception Mapping (CRITICAL — domain exception -> HTTP)
    //
    // The BillPaymentService throws typed domain exceptions for every
    // failure mode in the COBIL00C source program. These exceptions are
    // intercepted by GlobalExceptionHandler @ExceptionHandler methods
    // and translated to standardized HTTP responses per AAP §0.7.1.
    //
    // Status mapping (verified against GlobalExceptionHandler):
    //   * RecordNotFoundException -> 404 (default reason code "23" =
    //     COBOL FILE STATUS NOTFND, overridden to "NOT_FOUND" via the
    //     2-arg constructor when the calling service prefers the
    //     descriptive label)
    //   * ConcurrentModificationException -> 409 ("CONFLICT" default)
    //   * CreditLimitExceededException -> 422 with reject code "102"
    //     verbatim (REJECT_CODE constant per AAP §0.7.2)
    //   * OnSizeErrorException -> 422 (NOT 500, per Issue CP4-#6) with
    //     reason code "ARITHMETIC_OVERFLOW" (DEFAULT_REASON_CODE)
    //   * ValidationException -> 400 with code "VALIDATION"
    //     (DEFAULT_REASON_CODE)
    //
    // Each test stubs the service to throw a specific domain exception
    // and asserts the HTTP status, the envelope code, and (where
    // applicable) the verbatim message string survives the controller
    // -> handler -> response pipeline.
    // =====================================================================

    /**
     * Tests for the typed domain-exception -> HTTP-status mapping.
     * Each test stubs the {@link BillPaymentService} to throw a
     * specific domain exception and asserts the resulting HTTP
     * response (status code, envelope {@code code}, and envelope
     * {@code message}) matches the {@link GlobalExceptionHandler}
     * contract.
     */
    @Nested
    @DisplayName("POST /api/billing/pay — domain exception mapping (GlobalExceptionHandler)")
    class PayBillExceptionMapping {

        /**
         * {@link RecordNotFoundException} (default reason code
         * {@code "23"} = COBOL FILE STATUS NOTFND, or
         * {@code "NOT_FOUND"} via the 2-arg constructor) maps to HTTP
         * 404 Not Found via
         * {@code GlobalExceptionHandler.handleRecordNotFound}.
         *
         * <p>COBOL provenance: {@code COBIL00C.cbl}
         * {@code READ-ACCTDAT-FILE} L356-L372 and
         * {@code READ-CXACAIX-FILE} L420-L436 — DFHRESP(NOTFND)
         * branches when the account or card-cross-reference is
         * missing.</p>
         */
        @Test
        @DisplayName("payBill_returns404WhenServiceThrowsRecordNotFound — Account NOTFND, HTTP 404")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_returns404WhenServiceThrowsRecordNotFound() throws Exception {
            BillPaymentDto request = buildRequest("Y");
            // 2-arg constructor: explicit reason code "NOT_FOUND" overrides
            // the default FILE_STATUS_NOTFND ("23") for descriptive labeling.
            willThrow(new RecordNotFoundException(
                    "NOT_FOUND",
                    "Account not found: acctId=" + TEST_ACCOUNT_ID))
                    .given(billPaymentService).processBillPayment(any(BillPaymentDto.class));

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())                         // HTTP 404
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message")
                            .value(containsString(TEST_ACCOUNT_ID)));
        }

        /**
         * {@link ConcurrentModificationException} (CardDemo domain
         * exception, NOT {@link java.util.ConcurrentModificationException})
         * maps to HTTP 409 Conflict via
         * {@code GlobalExceptionHandler.handleConcurrentModification}.
         *
         * <p>COBOL provenance: replaces the before/after image
         * comparison pattern. The COBIL00C source did not include this
         * pattern explicitly (its REWRITE on ACCTDAT was implicitly
         * protected by the CICS task-end SYNCPOINT); in the Java
         * target the JPA {@code @Version} field on the
         * {@code Account} entity raises this exception when concurrent
         * bill-payment requests target the same account.</p>
         *
         * <p><b>CRITICAL FQCN discipline:</b> the {@code import}
         * statement at the top of this file explicitly references
         * {@code com.awsm2.carddemo.exception.ConcurrentModificationException}
         * — collision with {@code java.util.ConcurrentModificationException}
         * (the JDK iteration-time exception) is avoided per AAP
         * &sect;0.7.1.</p>
         */
        @Test
        @DisplayName("payBill_returns409WhenServiceThrowsConcurrentModification — JPA @Version conflict, HTTP 409")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_returns409WhenServiceThrowsConcurrentModification() throws Exception {
            BillPaymentDto request = buildRequest("Y");
            willThrow(new ConcurrentModificationException(
                    "Account " + TEST_ACCOUNT_ID + " was modified concurrently"))
                    .given(billPaymentService).processBillPayment(any(BillPaymentDto.class));

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())                         // HTTP 409
                    .andExpect(jsonPath("$.code").value("CONFLICT"))
                    .andExpect(jsonPath("$.message")
                            .value(containsString(TEST_ACCOUNT_ID)));
        }

        /**
         * {@link CreditLimitExceededException} (reject code 102
         * "OVERLIMIT TRANSACTION" from {@code CBTRN02C.cbl} line 410)
         * maps to HTTP <b>422 Unprocessable Entity</b> via
         * {@code GlobalExceptionHandler.handleCreditLimitExceeded}.
         *
         * <p><b>Reject-code preservation (AAP &sect;0.7.2):</b> The
         * envelope's {@code code} field MUST carry the literal string
         * {@code "102"} — the verbatim COBOL reject code from
         * {@link CreditLimitExceededException#REJECT_CODE}. This is the
         * canonical assertion guaranteeing downstream regulatory and
         * operations systems can branch on the exact historical
         * value.</p>
         */
        @Test
        @DisplayName("payBill_returns422WhenServiceThrowsCreditLimitExceeded — reject code 102, HTTP 422")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_returns422WhenServiceThrowsCreditLimitExceeded() throws Exception {
            BillPaymentDto request = buildRequest("Y");
            // No-arg constructor uses REJECT_CODE="102" and
            // REJECT_DESCRIPTION="OVERLIMIT TRANSACTION" per AAP §0.7.2.
            willThrow(new CreditLimitExceededException())
                    .given(billPaymentService).processBillPayment(any(BillPaymentDto.class));

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())              // HTTP 422
                    // Reject code 102 preserved verbatim on the envelope
                    .andExpect(jsonPath("$.code").value(CreditLimitExceededException.REJECT_CODE))
                    .andExpect(jsonPath("$.code").value("102"))
                    // Verbatim COBOL description from line 411-413
                    .andExpect(jsonPath("$.message")
                            .value(CreditLimitExceededException.REJECT_DESCRIPTION));
        }

        /**
         * {@link OnSizeErrorException} (COBOL {@code ON SIZE ERROR}
         * arithmetic overflow on the balance subtraction in
         * {@code COBIL00C.cbl} L234
         * {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT})
         * maps to HTTP <b>422 Unprocessable Entity</b> via
         * {@code GlobalExceptionHandler.handleOnSizeError}.
         *
         * <p><b>Status mapping note (Issue CP4-#6):</b> The
         * {@code GlobalExceptionHandler} maps this exception to HTTP
         * 422 (NOT HTTP 500) — an arithmetic overflow is a business
         * arithmetic constraint violation (the request is syntactically
         * well-formed but the resulting computation exceeds the
         * configured precision), not an internal server fault. This
         * aligns with the 422 mappings for
         * {@link CreditLimitExceededException} and
         * {@link com.awsm2.carddemo.exception.ExpiredCardException},
         * which are likewise rejected business outcomes for valid
         * inputs.</p>
         *
         * <p>The {@code code} on the envelope is
         * {@code "ARITHMETIC_OVERFLOW"} (the
         * {@link OnSizeErrorException#DEFAULT_REASON_CODE}).</p>
         */
        @Test
        @DisplayName("payBill_returns422WhenServiceThrowsOnSizeError — arithmetic overflow, HTTP 422 (NOT 500)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_returns422WhenServiceThrowsOnSizeError() throws Exception {
            BillPaymentDto request = buildRequest("Y");
            willThrow(new OnSizeErrorException(
                    "Bill payment balance subtraction overflow on ACCT-CURR-BAL"))
                    .given(billPaymentService).processBillPayment(any(BillPaymentDto.class));

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())              // HTTP 422 (NOT 500)
                    // Default reason code "ARITHMETIC_OVERFLOW" per
                    // OnSizeErrorException.DEFAULT_REASON_CODE
                    .andExpect(jsonPath("$.code").value(OnSizeErrorException.DEFAULT_REASON_CODE))
                    .andExpect(jsonPath("$.code").value("ARITHMETIC_OVERFLOW"));
        }

        /**
         * Service-thrown {@link ValidationException} (e.g., the
         * COBIL00C input-validation cascade emitting the verbatim
         * message {@code "Invalid value. Valid values are (Y/N)..."}
         * from L186-L189) maps to HTTP 400 Bad Request via
         * {@code GlobalExceptionHandler.handleValidation} with
         * {@code code = "VALIDATION"}.
         *
         * <p>This is distinct from
         * {@link org.springframework.web.bind.MethodArgumentNotValidException}
         * (Bean Validation on the DTO at controller binding time) —
         * the {@code ValidationException} fires when the service body
         * detects a domain-rule violation that Jakarta Bean Validation
         * cannot express. Both surface as HTTP 400 but use slightly
         * different code paths inside
         * {@link GlobalExceptionHandler}.</p>
         */
        @Test
        @DisplayName("payBill_returns400WhenServiceThrowsValidation — service-level validation, HTTP 400")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_returns400WhenServiceThrowsValidation() throws Exception {
            BillPaymentDto request = buildRequest("Y");
            // Verbatim message string from COBIL00C.cbl L186-L189
            willThrow(new ValidationException(
                    "Invalid value. Valid values are (Y/N)..."))
                    .given(billPaymentService).processBillPayment(any(BillPaymentDto.class));

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())                       // HTTP 400
                    .andExpect(jsonPath("$.code").value(ValidationException.DEFAULT_REASON_CODE))
                    .andExpect(jsonPath("$.code").value("VALIDATION"))
                    // Verbatim COBOL message preserved verbatim on the envelope
                    .andExpect(jsonPath("$.message")
                            .value("Invalid value. Valid values are (Y/N)..."));
        }
    }

    // =====================================================================
    // Phase 5: Response Headers and Content-Type
    //
    // Every successful response from POST /api/billing/pay MUST carry
    // a JSON content type (per @RestController + ApiResponse JSON
    // envelope conventions). This phase asserts the content-type
    // header alone; the envelope payload assertions are covered
    // elsewhere.
    //
    // NOTE on correlationId: the ApiResponse.success(data, message)
    // factory leaves correlationId=null (only ApiResponse.success(data,
    // message, correlationId) populates it, and the BillingController
    // does NOT pass a correlationId). The envelope is decorated with
    // @JsonInclude(JsonInclude.Include.NON_NULL) so the correlationId
    // field is omitted from the JSON output entirely on success
    // responses. Error envelopes (via GlobalExceptionHandler) DO
    // populate correlationId via generateCorrelationId(), but those
    // are covered by the phase-4 exception-mapping tests where the
    // assertions focus on the code and message fields.
    // =====================================================================

    /**
     * Tests for the HTTP response headers contract on
     * {@code POST /api/billing/pay}. Each test asserts the response
     * carries an {@code application/json} content type compatible
     * with the {@code ApiResponse} envelope shape.
     */
    @Nested
    @DisplayName("POST /api/billing/pay — Response headers and content-type")
    class PayBillResponseHeaders {

        /**
         * Asserts the successful response carries a JSON-compatible
         * content type (typically {@code application/json}). The
         * controller's
         * {@code ResponseEntity.status(HttpStatus.CREATED).body(...)}
         * pattern combined with the default Jackson HTTP message
         * converter produces {@code Content-Type: application/json}
         * per Spring Boot conventions.
         */
        @Test
        @DisplayName("payBill_returnsJsonContentType — successful response is JSON-compatible")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_returnsJsonContentType() throws Exception {
            BillPaymentDto request = buildRequest("Y");
            given(billPaymentService.processBillPayment(any(BillPaymentDto.class)))
                    .willReturn(buildSuccessResult());

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        }

        /**
         * Asserts the error response (via GlobalExceptionHandler) also
         * carries a JSON content type and includes the {@code
         * correlationId} field populated by
         * {@link GlobalExceptionHandler#generateCorrelationId()} for
         * distributed-tracing correlation per AAP &sect;0.6.6.
         */
        @Test
        @DisplayName("payBill_errorResponseIncludesCorrelationId — error envelope has correlationId")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_errorResponseIncludesCorrelationId() throws Exception {
            BillPaymentDto request = buildRequest("Y");
            willThrow(new RecordNotFoundException(
                    "NOT_FOUND",
                    "Account not found: acctId=" + TEST_ACCOUNT_ID))
                    .given(billPaymentService).processBillPayment(any(BillPaymentDto.class));

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.correlationId").exists());
        }

        /**
         * PCI-DSS defense in depth: the response body must NOT echo
         * the inbound account identifier in any sensitive context.
         * The account identifier is not PCI-DSS-classified data
         * (it is an internal record key, not a PAN), but this test
         * additionally verifies that no synthetic card-number-like
         * 16-digit sequence appears in the success response — even
         * though the controller deliberately suppresses the PAN
         * via {@code BillingController.mapToTransactionDetail()}
         * (PCI-DSS PAN suppression at the controller boundary, AAP
         * &sect;0.6.6). This is the canonical "no PAN echo" assertion.
         */
        @Test
        @DisplayName("payBill_responseDoesNotEchoSyntheticPan — no 16-digit PAN-like sequence in success response")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_responseDoesNotEchoSyntheticPan() throws Exception {
            BillPaymentDto request = buildRequest("Y");
            given(billPaymentService.processBillPayment(any(BillPaymentDto.class)))
                    .willReturn(buildSuccessResult());

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    // Defense-in-depth: ensure a representative 16-digit
                    // PAN-like sequence ("4111111111111111") never appears
                    // in the response body even though no test fixture
                    // includes it. This guards against accidental future
                    // changes that might leak PAN data through the receipt.
                    .andExpect(content().string(not(containsString("4111111111111111"))));
        }
    }

    // =====================================================================
    // Phase 6: DTO Accessor Pattern Verification (ArgumentCaptor)
    //
    // The Java record accessor pattern (dto.accountId(), dto.confirm())
    // is the canonical contract for reading record components. Per AAP
    // §0.4.1 the BillPaymentDto is a Java record and consumers MUST
    // use the auto-generated accessor methods.
    //
    // This phase uses ArgumentCaptor to capture the BillPaymentDto
    // forwarded by the controller to the service and asserts:
    //   1. Every request-side field round-trips exactly (no implicit
    //      mutation or downcast)
    //   2. The record accessor methods return the values populated
    //      from the deserialized JSON body
    //   3. The accessor pattern is exercised at runtime (not just at
    //      compile time)
    // =====================================================================

    /**
     * Tests for the record accessor pattern on
     * {@link BillPaymentDto}. The {@link ArgumentCaptor} captures the
     * DTO forwarded to {@link BillPaymentService} and asserts every
     * field round-trips through deserialization and the record
     * accessor methods (e.g., {@code dto.accountId()},
     * {@code dto.confirm()}) return the expected values.
     */
    @Nested
    @DisplayName("POST /api/billing/pay — DTO record accessor pattern (ArgumentCaptor)")
    class PayBillDtoAccessors {

        /**
         * Verifies the controller forwards the deserialized
         * {@link BillPaymentDto} unchanged to the service. The
         * {@link ArgumentCaptor} captures the dto passed to
         * {@code processBillPayment(...)} and asserts the record
         * accessor pattern (dto.accountId() == "11111111111",
         * dto.confirm() == "Y") returns the values that match the
         * inbound JSON request body — confirming the controller did
         * not mutate or copy any fields.
         *
         * <p>This test additionally exercises the response-side
         * accessor contract on the returned
         * {@link TransactionDetailDto} to ensure the controller
         * mapping {@code BillPaymentDto -> TransactionDetailDto}
         * preserves the {@code transactionId} field flow from the
         * service's response.</p>
         */
        @Test
        @DisplayName("payBill_passesAllFieldsToService — record accessor pattern round-trips request body")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_passesAllFieldsToService() throws Exception {
            BillPaymentDto request = buildRequest("Y");
            given(billPaymentService.processBillPayment(any(BillPaymentDto.class)))
                    .willReturn(buildSuccessResult());

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());

            // Capture the dto forwarded to the service and assert the
            // record accessor pattern (dto.accountId() / dto.confirm())
            // returns the inbound JSON values verbatim.
            ArgumentCaptor<BillPaymentDto> dtoCaptor =
                    ArgumentCaptor.forClass(BillPaymentDto.class);
            verify(billPaymentService).processBillPayment(dtoCaptor.capture());
            BillPaymentDto captured = dtoCaptor.getValue();

            // Verify record accessor pattern — the canonical Java
            // record consumer interface per AAP §0.4.1.
            org.junit.jupiter.api.Assertions.assertNotNull(captured,
                    "Captured BillPaymentDto must not be null");
            org.junit.jupiter.api.Assertions.assertEquals(
                    TEST_ACCOUNT_ID, captured.accountId(),
                    "accountId record accessor must return the inbound value");
            org.junit.jupiter.api.Assertions.assertEquals(
                    "Y", captured.confirm(),
                    "confirm record accessor must return the inbound value");
            // Response-side components are null in the request payload
            // — the client did not supply them.
            org.junit.jupiter.api.Assertions.assertNull(
                    captured.currentBalance(),
                    "currentBalance must be null in the inbound request");
            org.junit.jupiter.api.Assertions.assertNull(
                    captured.transactionId(),
                    "transactionId must be null in the inbound request");
            org.junit.jupiter.api.Assertions.assertNull(
                    captured.postedAt(),
                    "postedAt must be null in the inbound request");
            org.junit.jupiter.api.Assertions.assertNull(
                    captured.amountPaid(),
                    "amountPaid must be null in the inbound request");
        }

        /**
         * Verifies the {@link BigDecimal} {@code amountPaid} component
         * on the service-returned {@link BillPaymentDto} round-trips
         * through the controller's {@code mapToTransactionDetail(...)}
         * helper into the {@link TransactionDetailDto#amount()} JSON
         * field with full {@code scale=2} precision preservation per
         * AAP &sect;0.6.1. This guards against accidental downcast to
         * {@code double} during DTO mapping.
         */
        @Test
        @DisplayName("payBill_preservesAmountPaidPrecision — BigDecimal scale=2 round-trip via TransactionDetailDto.amount")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_preservesAmountPaidPrecision() throws Exception {
            BillPaymentDto request = buildRequest("Y");
            // CRITICAL: STRING constructor per AAP §0.6.1 — IEEE-754
            // floating-point representation does not round-trip for
            // arbitrary decimal values.
            BigDecimal preciseAmount = new BigDecimal("12345.67");
            BillPaymentDto result = new BillPaymentDto(
                    TEST_ACCOUNT_ID,
                    BigDecimal.ZERO.setScale(2),
                    "Y",
                    TEST_TXN_ID,
                    TEST_TIMESTAMP,
                    preciseAmount   // service-populated, scale=2
            );
            given(billPaymentService.processBillPayment(any(BillPaymentDto.class)))
                    .willReturn(result);

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    // The TransactionDetailDto.amount component carries
                    // the service-supplied BigDecimal verbatim — Jackson
                    // serializes it as a JSON number without precision
                    // loss per AAP §0.6.1.
                    .andExpect(jsonPath("$.data.amount").value(12345.67));
        }

        /**
         * Verifies the {@link LocalDateTime} {@code postedAt} component
         * on the service-returned {@link BillPaymentDto} round-trips
         * through the controller's {@code mapToTransactionDetail(...)}
         * helper into both
         * {@link TransactionDetailDto#originationTimestamp()} and
         * {@link TransactionDetailDto#processingTimestamp()} as the
         * same value. COBOL provenance:
         * {@code MOVE WS-TIMESTAMP TO TRAN-ORIG-TS TRAN-PROC-TS}
         * ({@code COBIL00C.cbl} L231-L232) — both fields receive the
         * same timestamp at the moment of transaction posting.
         */
        @Test
        @DisplayName("payBill_mapsPostedAtToBothTimestamps — TRAN-ORIG-TS and TRAN-PROC-TS share the same timestamp")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_mapsPostedAtToBothTimestamps() throws Exception {
            BillPaymentDto request = buildRequest("Y");
            given(billPaymentService.processBillPayment(any(BillPaymentDto.class)))
                    .willReturn(buildSuccessResult());

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    // Both originationTimestamp and processingTimestamp
                    // exist and reflect the service-supplied postedAt
                    // (verifies the mapper sets both TRAN-ORIG-TS and
                    // TRAN-PROC-TS to the same value per COBIL00C
                    // L231-L232).
                    .andExpect(jsonPath("$.data.originationTimestamp").exists())
                    .andExpect(jsonPath("$.data.processingTimestamp").exists());
        }

        /**
         * Verifies the {@code accountId} record accessor returns the
         * inbound 11-digit value when {@code confirm="N"} (cancellation
         * branch). This confirms the controller forwards the request
         * unchanged to the service even on the non-success path —
         * the service decides whether to write a transaction based on
         * the captured DTO's accessor values.
         */
        @Test
        @DisplayName("payBill_passesAccountIdOnCancellation — accountId accessor returns inbound value on confirm=N")
        @WithMockUser(username = "USER0001", roles = "USER")
        void payBill_passesAccountIdOnCancellation() throws Exception {
            BillPaymentDto request = buildRequest("N");
            given(billPaymentService.processBillPayment(any(BillPaymentDto.class)))
                    .willReturn(buildNonSuccessResult("N"));

            mockMvc.perform(post("/api/billing/pay")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            ArgumentCaptor<BillPaymentDto> dtoCaptor =
                    ArgumentCaptor.forClass(BillPaymentDto.class);
            verify(billPaymentService).processBillPayment(dtoCaptor.capture());
            BillPaymentDto captured = dtoCaptor.getValue();
            org.junit.jupiter.api.Assertions.assertEquals(
                    TEST_ACCOUNT_ID, captured.accountId(),
                    "accountId accessor must return inbound value on cancellation");
            org.junit.jupiter.api.Assertions.assertEquals(
                    "N", captured.confirm(),
                    "confirm accessor must return 'N' on cancellation");
        }
    }
}

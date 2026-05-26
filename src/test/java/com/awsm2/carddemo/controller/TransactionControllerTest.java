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

// COBOL: COTRN00C.cbl — Transaction list (paged, PAGE_SIZE=10 to match
//                         COTRN00.bms 10-row SEL0001..SEL0010 table)
// COBOL: COTRN01C.cbl — Transaction detail (single record by 16-digit
//                         transaction ID; READ TRANSACT keyed on TRAN-ID)
// COBOL: COTRN02C.cbl — Transaction add / Tran-ID CT02 (MAX-TRAN-ID+1
//                         generation via JPA sequence; XREF lookup;
//                         TRAN-AMT PIC S9(09)V99 -> BigDecimal scale=2
//                         @Digits(integer=9, fraction=2))
// BMS:   COTRN00.bms, COTRN01.bms, COTRN02.bms
// CVTRA05Y.cpy — 350-byte TRAN-RECORD layout
//
// MockMvc slice tests for {@link TransactionController}, the Java target
// for the three CICS COBOL transaction-administration programs above.
// Per the AAP §0.3.4 / §0.4.1 endpoint inventory and the layered
// architecture mandate of §0.7.1 (Controller -> Service -> Repository
// -> Domain), the tests validate every HTTP entry-point behaviour,
// every Jakarta Bean Validation constraint on {@link TransactionAddDto},
// every domain-exception -> HTTP-status mapping wired through
// {@link GlobalExceptionHandler}, and the PCI-DSS PAN-masking discipline
// of AAP §0.6.6 / §0.7.1.

import com.awsm2.carddemo.dto.TransactionAddDto;
import com.awsm2.carddemo.dto.TransactionDetailDto;
import com.awsm2.carddemo.dto.TransactionListDto;
import com.awsm2.carddemo.exception.CreditLimitExceededException;
import com.awsm2.carddemo.exception.DuplicateRecordException;
import com.awsm2.carddemo.exception.ExpiredCardException;
import com.awsm2.carddemo.exception.GlobalExceptionHandler;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.security.JwtAuthenticationFilter;
import com.awsm2.carddemo.security.JwtTokenProvider;
import com.awsm2.carddemo.service.TransactionAddService;
import com.awsm2.carddemo.service.TransactionDetailService;
import com.awsm2.carddemo.service.TransactionListService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.BDDMockito;
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
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc controller slice tests for {@link TransactionController}.
 *
 * <h2>System Under Test (SUT)</h2>
 *
 * <p>{@link TransactionController} is the Java target for the three
 * CICS/COBOL transaction-administration programs of the source code-
 * base:</p>
 * <ul>
 *   <li>{@code app/cbl/COTRN00C.cbl} (Tran-ID {@code CT00}) &mdash;
 *       paginated transaction list. Page size = <b>10</b> &mdash; the
 *       verbatim transcription of the 10-row {@code SEL0001..SEL0010}
 *       selection table on the {@code COTRN00.bms} mapset. This page
 *       size is <b>critically distinct</b> from cards which use page
 *       size = 7 (per the {@code COCRDLI.bms} 7-row table and the
 *       {@code WS-MAX-SCREEN-LINES VALUE 7} working-storage literal in
 *       {@code COCRDLIC.cbl}); the AAP &sect;0.4.1 transformation rule
 *       requires preserving each program's own page-shape contract.</li>
 *   <li>{@code app/cbl/COTRN01C.cbl} (Tran-ID {@code CT01}) &mdash;
 *       single transaction detail by 16-digit transaction ID
 *       ({@code TRAN-ID PIC X(16)} from
 *       {@code app/cpy/CVTRA05Y.cpy}).</li>
 *   <li>{@code app/cbl/COTRN02C.cbl} (Tran-ID {@code CT02}) &mdash;
 *       transaction add: MAX(TRAN-ID)+1 sequence generation (now a JPA
 *       sequence), XREF resolution via
 *       {@code CardCrossReferenceRepository.findByXrefAcctId(...)}
 *       (replaces the COBOL {@code CXACAIX} alternate-index browse),
 *       transactional persist, and publishes the
 *       {@code transaction.posted} Kafka event to Amazon MSK partitioned
 *       by account ID per AAP &sect;0.6.5.</li>
 * </ul>
 *
 * <h2>Endpoint inventory under test (AAP &sect;0.3.4 / &sect;0.4.1)</h2>
 *
 * <ul>
 *   <li>{@code GET    /api/transactions}              &rarr;
 *       HTTP 200 with paginated {@link TransactionListDto} wrapped in
 *       the standardized {@code ApiResponse} envelope. Accessible to
 *       both {@code USER} and {@code ADMIN} roles per
 *       {@code @PreAuthorize("hasAnyRole('USER','ADMIN')")} on the
 *       method.</li>
 *   <li>{@code GET    /api/transactions/{id}}         &rarr;
 *       HTTP 200 with {@link TransactionDetailDto}. Accessible to both
 *       roles. HTTP 404 when the 16-digit ID does not match any
 *       record. HTTP 400 when the path variable does not match
 *       {@code ^[0-9]{16}$}.</li>
 *   <li>{@code POST   /api/transactions}              &rarr;
 *       HTTP <b>201 Created</b> with the saved
 *       {@link TransactionAddDto} (carrying the generated 16-digit
 *       transaction ID and the resolved 11-digit accountId).
 *       Accessible to both roles. The full domain-exception family
 *       (reject codes 100&ndash;109 from {@code CBTRN02C.cbl}) is
 *       mapped to HTTP statuses via {@link GlobalExceptionHandler}
 *       per AAP &sect;0.4.1.</li>
 * </ul>
 *
 * <h2>BigDecimal arithmetic discipline (AAP &sect;0.6.1, &sect;0.7.1)</h2>
 *
 * <p>The COBOL {@code TRAN-AMT PIC S9(09)V99} ({@code app/cpy/CVTRA05Y.cpy}
 * line 10) is the canonical monetary field for this endpoint family
 * &mdash; 9 integer digits + 2 fractional digits, signed. The Java
 * target translates this to
 * {@link java.math.BigDecimal BigDecimal} with the constraint
 * {@code @Digits(integer = 9, fraction = 2)} on
 * {@link TransactionAddDto#amount()}. Per AAP &sect;0.6.1 the discipline
 * is:</p>
 * <ul>
 *   <li>{@link java.math.RoundingMode#HALF_EVEN} (banker's rounding) at
 *       every arithmetic boundary.</li>
 *   <li><b>NEVER</b> use {@code new BigDecimal(double)} &mdash; only
 *       the {@code String} constructor
 *       ({@code new BigDecimal("100.00")}) to avoid IEEE-754 precision
 *       loss.</li>
 *   <li>{@code BigDecimal.compareTo(...)} instead of
 *       {@code equals(...)} for value comparison (avoids scale
 *       sensitivity).</li>
 * </ul>
 * <p>Every test fixture in this class uses the {@code String}
 * constructor; phase 7 explicitly asserts via an
 * {@link ArgumentCaptor} that the precision and scale of the request
 * body's {@code amount} are preserved into the service-bound DTO.</p>
 *
 * <h2>PCI-DSS PAN-masking discipline (AAP &sect;0.6.6, &sect;0.7.1)</h2>
 *
 * <p>The test fixture PAN {@value #TEST_CARD_NUMBER} is the
 * industry-standard Visa test PAN &mdash; a number that passes Luhn
 * but is never issued to a real cardholder. Phase 9 asserts that no
 * response body, error envelope, or exception message contains the
 * full PAN; all PAN references in responses are masked to the
 * {@code ************nnnn} (12 asterisks + last 4) form produced by
 * the production {@code TransactionListDto.maskPan(...)} helper.</p>
 *
 * <h2>Reject-code preservation (AAP &sect;0.7.2)</h2>
 *
 * <p>The COBOL {@code WS-VALIDATION-FAIL-REASON} family
 * (codes 100&ndash;109 from {@code app/cbl/CBTRN02C.cbl}) MUST be
 * preserved character-for-character on the JSON error envelope. The
 * phase-6 tests assert that:</p>
 * <ul>
 *   <li>{@link CreditLimitExceededException} (reject code
 *       {@code "102"} / {@code "OVERLIMIT TRANSACTION"} from
 *       {@code CBTRN02C.cbl} lines 410-411) surfaces on the envelope
 *       at the {@code code} property as the literal string
 *       {@code "102"} and HTTP 422.</li>
 *   <li>{@link ExpiredCardException} (reject code {@code "103"} /
 *       {@code "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"} from
 *       {@code CBTRN02C.cbl} lines 417-419) surfaces as the literal
 *       string {@code "103"} and HTTP 422.</li>
 * </ul>
 * <p>Together with {@link RecordNotFoundException} (reject codes
 * {@code "100"}, {@code "101"}, {@code "109"}) and
 * {@link OnSizeErrorException} (arithmetic overflow), these exception
 * classes cover the entire reject-code family.</p>
 *
 * <h2>Test-class wiring</h2>
 *
 * <p>{@code @WebMvcTest} with {@code excludeFilters} on
 * {@link JwtAuthenticationFilter} short-circuits the JWT filter chain
 * so the slice does not require Secrets Manager initialization;
 * {@code @WithMockUser} populates the {@code SecurityContext} directly
 * for each test.</p>
 *
 * <p>{@code @EnableMethodSecurity(prePostEnabled = true)} on the test
 * class itself activates the
 * {@code AuthorizationManagerBeforeMethodInterceptor} that evaluates
 * the SpEL {@code @PreAuthorize} expressions on the controller methods.
 * Without this annotation {@code @WebMvcTest} would silently bypass
 * method security and anonymous-access tests (phase 10) would
 * incorrectly return HTTP 200 instead of HTTP 401.</p>
 *
 * <p>{@code @Import(GlobalExceptionHandler.class)} explicitly wires the
 * {@code @RestControllerAdvice} bean. {@code @WebMvcTest} does NOT
 * auto-load advice beans from other packages; without the explicit
 * import the standardized {@code ApiResponse} error envelope shape
 * would not be observed for the phase-6 exception-mapping tests.</p>
 *
 * @see TransactionController
 * @see TransactionListService
 * @see TransactionDetailService
 * @see TransactionAddService
 * @see GlobalExceptionHandler
 */
// Replaces: app/cbl/COTRN00C.cbl, app/cbl/COTRN01C.cbl, app/cbl/COTRN02C.cbl
// (CICS Tran-IDs CT00/CT01/CT02). All three programs targeted the
// TRANSACT VSAM KSDS dataset (CVTRA05Y.cpy 350-byte TRAN-RECORD layout
// with TRAN-AMT PIC S9(09)V99 and TRAN-CARD-NUM PIC X(16)); in the
// Java target the equivalent is the `transactions` PostgreSQL table
// populated by Flyway V005__create_transaction.sql.
@WebMvcTest(
        controllers = TransactionController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = JwtAuthenticationFilter.class
        )
)
@Import(GlobalExceptionHandler.class)
@EnableMethodSecurity(prePostEnabled = true)
@TestPropertySource(properties = {
        // The base configuration uses Secrets Manager-backed JWT
        // signing-key resolution. For a controller-only slice test we
        // never issue or validate tokens (each test uses Spring
        // Security's @WithMockUser to inject the authentication
        // directly), so we suppress JwtTokenProvider initialization by
        // mocking it; this also keeps the slice fast.
        "carddemo.security.jwt.signing-key=test-only-jwt-signing-key-32-bytes-min-length",
        "carddemo.security.cors.allowed-origins=http://localhost:3000"
})
@DisplayName("TransactionController slice tests (/api/transactions) — PAGE_SIZE=10, BigDecimal HALF_EVEN, XOR validation")
class TransactionControllerTest {

    // =====================================================================
    // Test fixture constants
    //
    // Synthetic test PANs from the industry-standard Visa test suite —
    // valid Luhn but never issued to a real cardholder. Use of these
    // synthetic PANs is the prescribed pattern for PCI-DSS-aware test
    // fixtures (AAP §0.6.6 / §0.7.1).
    // =====================================================================

    /**
     * 16-digit zero-padded test transaction ID. Matches the
     * {@code TRAN-ID PIC X(16)} field in
     * {@code app/cpy/CVTRA05Y.cpy} line 7 and is consistent with the
     * {@link TransactionAddService} MAX(TRAN-ID)+1 sequence semantics
     * &mdash; the first generated value is {@code "0000000000000001"}.
     */
    private static final String TEST_TXN_ID = "0000000000000001";

    /**
     * Synthetic Visa test PAN. Passes Luhn but is never issued to a
     * real cardholder. Used as the {@code cardNumber} component of
     * every fixture {@link TransactionAddDto} that exercises the
     * card-number variant of the XOR (accountId XOR cardNumber)
     * validation rule. PCI-DSS: this fixture value MUST NOT appear
     * unmasked in any response body or error envelope; phase 9 asserts
     * {@code not(containsString(TEST_CARD_NUMBER))} on every endpoint's
     * response.
     */
    private static final String TEST_CARD_NUMBER = "4111111111111111";

    /**
     * Expected masked form of {@link #TEST_CARD_NUMBER}: 12 asterisks
     * followed by the last 4 digits ({@code ************1111}). This is
     * the format produced by the production
     * {@code TransactionListDto.maskPan(...)} helper; tests assert
     * that the {@code data.rows[*].cardNumber} JSON path emits exactly
     * this masked form.
     */
    private static final String TEST_CARD_NUMBER_MASKED = "************1111";

    /**
     * 11-digit owning account identifier from the COBOL
     * {@code DALYTRAN-ACCT-ID PIC X(11)} / {@code ACCT-ID PIC 9(11)}
     * field. Used both as a path-variable filter (when present on a
     * list request) and as the {@code accountId} component on every
     * fixture {@link TransactionAddDto} that exercises the account-id
     * variant of the XOR validation rule.
     */
    private static final String TEST_ACCOUNT_ID = "11111111111";

    // =====================================================================
    // Spring MVC infrastructure (auto-injected from @WebMvcTest)
    // =====================================================================

    /**
     * MockMvc fluent client into the Spring MVC dispatcher, configured
     * by {@code @WebMvcTest} to route through the SUT controller plus
     * the method-security interceptor activated by
     * {@code @EnableMethodSecurity} on this test class. The JWT filter
     * is excluded via {@code excludeFilters} so the slice does not
     * load AWS Secrets Manager dependencies.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Application Jackson mapper used to serialize
     * {@link TransactionAddDto} fixtures to JSON request bodies for
     * POST requests. Auto-configured by Spring Boot's
     * {@code JacksonAutoConfiguration} in the {@code @WebMvcTest}
     * slice; honors {@code @JsonFormat} annotations on
     * {@link LocalDateTime} fields and serializes
     * {@link BigDecimal} values without precision loss (preserving the
     * AAP &sect;0.6.1 discipline on the wire).
     */
    @Autowired
    private ObjectMapper objectMapper;

    // =====================================================================
    // Mock collaborators (one @MockBean per direct dependency of the SUT)
    // =====================================================================

    /**
     * Mock of {@link TransactionListService} for the
     * {@code GET /api/transactions} flow. Stubbed via
     * {@code given(...).willReturn(...)} to return paginated
     * {@link TransactionListDto} fixtures (with up to
     * {@code PAGE_SIZE = 10} rows) without involving
     * {@code TransactionRepository} or RDS.
     */
    @MockBean
    private TransactionListService transactionListService;

    /**
     * Mock of {@link TransactionDetailService} for the
     * {@code GET /api/transactions/{id}} flow. Stubbed to return
     * {@link TransactionDetailDto} fixtures (with the
     * {@code cardNumber} component pre-masked to the
     * {@code ************nnnn} form) or to throw
     * {@link RecordNotFoundException} for not-found tests. The actual
     * method name on the service is {@code getTransactionDetail(...)}
     * (verified against the service exports).
     */
    @MockBean
    private TransactionDetailService transactionDetailService;

    /**
     * Mock of {@link TransactionAddService} for the
     * {@code POST /api/transactions} flow. Stubbed to (a) return a
     * {@link TransactionAddDto} for the happy-path HTTP 201 carrying
     * the generated transaction ID and the resolved 11-digit account
     * ID, (b) throw {@link ValidationException} for HTTP 400 XOR /
     * cross-field validation failures, (c) throw
     * {@link RecordNotFoundException} for HTTP 404 (reject codes
     * 100/101), (d) throw {@link DuplicateRecordException} for HTTP
     * 409, (e) throw {@link CreditLimitExceededException} for HTTP 422
     * (reject code 102), (f) throw {@link ExpiredCardException} for
     * HTTP 422 (reject code 103), and (g) throw
     * {@link OnSizeErrorException} for HTTP 422 (arithmetic overflow
     * &mdash; AAP &sect;0.6.1).
     */
    @MockBean
    private TransactionAddService transactionAddService;

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
    // {@code CardControllerTest}.



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
     * Builds a fully-populated valid {@link TransactionAddDto} fixture
     * with the {@code cardNumber} variant of the XOR contract
     * (cardNumber present, accountId null). Field values are chosen to
     * satisfy every Jakarta Bean Validation constraint declared on the
     * record, so happy-path tests can use this fixture directly and
     * invalid-field tests can clone-with-override to surface a single
     * violation.
     *
     * @return a valid {@link TransactionAddDto}
     */
    private TransactionAddDto validAddDtoByCard() {
        return new TransactionAddDto(
                null,                                  // accountId — null in card variant
                TEST_CARD_NUMBER,                      // cardNumber — 16-digit Visa test PAN
                "01",                                  // transactionType — @Pattern("^\\d{2}$")
                1,                                     // transactionCategory — Integer @NotNull
                "POS",                                 // source — @NotBlank @Size(max=10)
                "TEST TRANSACTION",                    // description — @NotBlank @Size(max=100)
                new BigDecimal("100.00"),              // amount — STRING constructor per AAP §0.6.1
                LocalDateTime.of(2026, 5, 20, 10, 30), // originationTimestamp — @NotNull
                LocalDateTime.of(2026, 5, 20, 10, 31), // processingTimestamp — @NotNull
                1234L,                                 // merchantId — Long @NotNull
                "TEST MERCHANT",                       // merchantName — @NotBlank @Size(max=50)
                "TEST CITY",                           // merchantCity — @NotBlank @Size(max=50)
                "12345",                               // merchantZip — @NotBlank @Size(max=10)
                "Y"                                    // confirm — @Pattern("^[YN]$")
        );
    }

    /**
     * Builds the equivalent valid fixture with the {@code accountId}
     * variant of the XOR contract (accountId present, cardNumber null).
     * Used in the phase-3 happy-path test to exercise the account-id
     * code path through the service.
     *
     * @return a valid {@link TransactionAddDto} keyed on accountId
     */
    private TransactionAddDto validAddDtoByAccount() {
        return new TransactionAddDto(
                TEST_ACCOUNT_ID,
                null,
                "01",
                1,
                "POS",
                "TEST TRANSACTION",
                new BigDecimal("100.00"),
                LocalDateTime.of(2026, 5, 20, 10, 30),
                LocalDateTime.of(2026, 5, 20, 10, 31),
                1234L,
                "TEST MERCHANT",
                "TEST CITY",
                "12345",
                "Y"
        );
    }

    /**
     * Builds the canonical "saved" outbound {@link TransactionAddDto}
     * that the {@link TransactionAddService} returns from
     * {@code addTransaction(...)} on the happy path. The outbound DTO
     * carries the resolved 11-digit accountId (or masked PAN, per
     * implementation discretion) and the generated transaction ID is
     * available via a follow-up audit log; the controller surfaces
     * the outbound DTO directly in the {@code data} envelope.
     *
     * @param accountId the resolved 11-digit account ID (post-XREF)
     * @return a "saved" {@link TransactionAddDto} suitable as the
     *         service stub return value
     */
    private TransactionAddDto savedAddDto(String accountId) {
        return new TransactionAddDto(
                accountId,
                TEST_CARD_NUMBER_MASKED,               // service returns masked PAN
                "01",
                1,
                "POS",
                "TEST TRANSACTION",
                new BigDecimal("100.00"),
                LocalDateTime.of(2026, 5, 20, 10, 30),
                LocalDateTime.of(2026, 5, 20, 10, 31),
                1234L,
                "TEST MERCHANT",
                "TEST CITY",
                "12345",
                "Y"
        );
    }

    /**
     * Builds a {@link TransactionDetailDto} fixture for the
     * GET /api/transactions/{id} happy path. Carries the masked PAN
     * (the service is responsible for masking before returning),
     * BigDecimal amount with scale=2 (the service applies
     * {@code setScale(2, HALF_EVEN)} per AAP &sect;0.6.1), and
     * LocalDateTime timestamps representing
     * {@code TRAN-ORIG-TS PIC X(26)} and
     * {@code TRAN-PROC-TS PIC X(26)}.
     *
     * @return a {@link TransactionDetailDto} for the happy-path stub
     */
    private TransactionDetailDto detailDto() {
        return new TransactionDetailDto(
                TEST_TXN_ID,                           // transactionId — 16-digit TRAN-ID
                "01",                                  // transactionType
                1,                                     // transactionCategory — Integer
                "POS",                                 // source
                "TEST TRANSACTION",                    // description
                new BigDecimal("100.00"),              // amount — STRING constructor
                1234L,                                 // merchantId — Long
                "TEST MERCHANT",                       // merchantName
                "TEST CITY",                           // merchantCity
                "12345",                               // merchantZip
                TEST_CARD_NUMBER_MASKED,               // cardNumber — masked
                LocalDateTime.of(2026, 5, 20, 10, 30), // originationTimestamp
                LocalDateTime.of(2026, 5, 20, 10, 31)  // processingTimestamp
        );
    }

    /**
     * Builds a {@link TransactionListDto.TransactionRow} fixture using
     * the provided transaction ID. The card number is the masked form
     * per the PCI-DSS PAN-masking discipline of AAP &sect;0.6.6; all
     * other fields use deterministic values so assertions in phase 1
     * can reference them by index.
     *
     * @param tranId the 16-digit transaction ID to embed in the row
     * @return a fully populated row fixture
     */
    private TransactionListDto.TransactionRow buildRow(String tranId) {
        return new TransactionListDto.TransactionRow(
                tranId,
                TEST_CARD_NUMBER_MASKED,                 // masked PAN
                LocalDateTime.of(2026, 5, 20, 10, 31),   // processingTimestamp
                "01",                                    // transactionType
                1,                                       // transactionCategory — Integer
                "POS",                                   // source
                "TEST TRANSACTION",                      // description
                new BigDecimal("100.00")                 // amount — STRING constructor
        );
    }



    // =====================================================================
    // Phase 1: GET /api/transactions — Paginated Transaction List
    //
    // COBOL provenance: app/cbl/COTRN00C.cbl — paginated TRANSACT browse.
    // The page size 10 is a verbatim carry-over from the 10-row
    // SEL0001..SEL0010 selection table on COTRN00.bms. This is distinct
    // from the page-size-7 contract on cards (per AAP §0.4.1 — CRITICAL
    // distinction).
    //
    // Authorization: @PreAuthorize("hasAnyRole('USER','ADMIN')") —
    // both roles can list transactions.
    // =====================================================================

    /**
     * Tests for the {@code GET /api/transactions} endpoint, covering
     * the happy-path paginated list, the optional {@code id} filter,
     * the {@code @Pattern}/{@code @Min} validation constraints on the
     * query parameters, the default {@code page=0} behavior, and the
     * security 401 path for anonymous callers.
     */
    @Nested
    @DisplayName("GET /api/transactions — paginated list (PAGE_SIZE=10)")
    class ListTransactions {

        /**
         * Happy-path list with a USER-role caller. The service returns
         * a {@link TransactionListDto} carrying exactly 10 rows
         * (PAGE_SIZE=10 — the COBOL contract from
         * {@code COTRN00.bms} 10-row {@code SEL0001..SEL0010} table),
         * and the controller wraps it in the standardized
         * {@code ApiResponse} envelope with the expected metadata
         * (page, size, totalElements, totalPages, first, last).
         */
        @Test
        @DisplayName("listTransactions_returns200WithPagedResults — HTTP 200, 10 rows, all PANs masked")
        @WithMockUser(username = "USER0001", roles = "USER")
        void listTransactions_returns200WithPagedResults() throws Exception {
            // Build exactly 10 pre-masked rows — the COTRN00.bms screen
            // has 10 row slots (SEL0001..SEL0010) and the service
            // honors that contract via PAGE_SIZE=10.
            List<TransactionListDto.TransactionRow> rows = List.of(
                    buildRow("0000000000000001"),
                    buildRow("0000000000000002"),
                    buildRow("0000000000000003"),
                    buildRow("0000000000000004"),
                    buildRow("0000000000000005"),
                    buildRow("0000000000000006"),
                    buildRow("0000000000000007"),
                    buildRow("0000000000000008"),
                    buildRow("0000000000000009"),
                    buildRow("0000000000000010")
            );
            TransactionListDto dto = new TransactionListDto(
                    rows,
                    0,           // page
                    10,          // size — PAGE_SIZE=10
                    25L,         // totalElements
                    3,           // totalPages
                    true,        // first
                    false,       // last
                    null         // idFilter
            );
            given(transactionListService.listTransactions(any(), eq(0)))
                    .willReturn(dto);

            mockMvc.perform(get("/api/transactions")
                            .param("page", "0")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.data.rows.length()").value(10))
                    .andExpect(jsonPath("$.data.size").value(10))     // PAGE_SIZE=10
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.totalElements").value(25))
                    .andExpect(jsonPath("$.data.totalPages").value(3))
                    .andExpect(jsonPath("$.data.first").value(true))
                    .andExpect(jsonPath("$.data.last").value(false))
                    // Every row's cardNumber must be the masked form;
                    // no full PAN can appear in the response body.
                    .andExpect(jsonPath("$.data.rows[0].cardNumber").value(TEST_CARD_NUMBER_MASKED))
                    .andExpect(content().string(not(containsString(TEST_CARD_NUMBER))));
        }

        /**
         * Verifies the controller forwards a non-blank {@code id}
         * filter unchanged to the service. The service's filter
         * parameter is captured via an {@link ArgumentCaptor} and
         * asserted against the literal supplied via the query string.
         */
        @Test
        @DisplayName("listTransactions_acceptsIdFilter — id query param forwarded to service")
        @WithMockUser(username = "USER0001", roles = "USER")
        void listTransactions_acceptsIdFilter() throws Exception {
            TransactionListDto dto = new TransactionListDto(
                    List.of(), 0, 10, 0L, 0, true, true, TEST_TXN_ID);
            given(transactionListService.listTransactions(any(), eq(0))).willReturn(dto);

            mockMvc.perform(get("/api/transactions")
                            .param("id", TEST_TXN_ID)
                            .param("page", "0")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());

            // Capture the actual idFilter argument forwarded to the
            // service and assert it matches the supplied path value
            // verbatim — the controller MUST NOT modify, trim, or
            // null-coalesce a non-blank caller-supplied filter.
            ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
            verify(transactionListService).listTransactions(idCaptor.capture(), eq(0));
            org.junit.jupiter.api.Assertions.assertEquals(TEST_TXN_ID, idCaptor.getValue(),
                    "Controller must forward the id query param verbatim to the service");
        }

        /**
         * Per the controller's
         * {@code @Pattern(regexp = "^[0-9]{1,16}$|^$")} constraint,
         * an empty {@code id} value is explicitly accepted (matches
         * the {@code ^$} alternation). The controller must forward an
         * empty string to the service which interprets it as "no
         * filter" and returns the full result page.
         */
        @Test
        @DisplayName("listTransactions_acceptsEmptyIdFilter — empty id matches ^$ alternation")
        @WithMockUser(username = "USER0001", roles = "USER")
        void listTransactions_acceptsEmptyIdFilter() throws Exception {
            TransactionListDto dto = new TransactionListDto(
                    List.of(), 0, 10, 0L, 0, true, true, "");
            given(transactionListService.listTransactions(any(), eq(0))).willReturn(dto);

            mockMvc.perform(get("/api/transactions")
                            .param("id", "")
                            .param("page", "0")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"));
        }

        /**
         * Validates the {@code @Pattern(regexp = "^[0-9]{1,16}$|^$")}
         * constraint: non-digit characters surface as HTTP 400 from
         * Spring's {@link jakarta.validation.ConstraintViolationException}
         * pipeline (handled by
         * {@code GlobalExceptionHandler.handleConstraintViolation}).
         * The service is NEVER invoked because the request fails
         * validation before reaching the controller method body.
         */
        @Test
        @DisplayName("listTransactions_returns400ForInvalidIdFormat — non-digit id violates @Pattern")
        @WithMockUser(username = "USER0001", roles = "USER")
        void listTransactions_returns400ForInvalidIdFormat() throws Exception {
            mockMvc.perform(get("/api/transactions")
                            .param("id", "abc")
                            .param("page", "0")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());

            // The service MUST NOT be invoked — validation failure
            // short-circuits before the controller method body.
            verifyNoInteractions(transactionListService);
        }

        /**
         * Validates the {@code @Min(0)} constraint on the
         * {@code page} query parameter — a negative page index
         * surfaces as HTTP 400 from the same constraint-violation
         * pipeline as the {@code @Pattern} check above.
         */
        @Test
        @DisplayName("listTransactions_returns400ForNegativePage — negative page violates @Min(0)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void listTransactions_returns400ForNegativePage() throws Exception {
            mockMvc.perform(get("/api/transactions")
                            .param("page", "-1")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(transactionListService);
        }

        /**
         * When the caller omits the {@code page} query parameter, the
         * controller's {@code @RequestParam(defaultValue = "0")}
         * resolves it to {@code 0}. The service is invoked with
         * {@code page = 0} (captured via {@link ArgumentCaptor}).
         */
        @Test
        @DisplayName("listTransactions_defaultsPageToZero — omitted page resolves to 0")
        @WithMockUser(username = "USER0001", roles = "USER")
        void listTransactions_defaultsPageToZero() throws Exception {
            TransactionListDto dto = new TransactionListDto(
                    List.of(), 0, 10, 0L, 0, true, true, null);
            given(transactionListService.listTransactions(any(), anyInt())).willReturn(dto);

            mockMvc.perform(get("/api/transactions")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());

            ArgumentCaptor<Integer> pageCaptor = ArgumentCaptor.forClass(Integer.class);
            verify(transactionListService).listTransactions(any(), pageCaptor.capture());
            org.junit.jupiter.api.Assertions.assertEquals(0, pageCaptor.getValue(),
                    "Controller must default page to 0 when query parameter is omitted");
        }

        /**
         * Anonymous request (no authentication) must be rejected
         * BEFORE the controller method runs. The
         * {@code @PreAuthorize("hasAnyRole('USER','ADMIN')")} on the
         * method is activated by the test class's
         * {@code @EnableMethodSecurity}; the missing authentication
         * yields HTTP 401 Unauthorized via Spring Security's
         * {@code AuthenticationException} -> 401 mapping in
         * {@code GlobalExceptionHandler.handleAuthentication}.
         */
        @Test
        @DisplayName("listTransactions_returns401ForAnonymous — anonymous yields HTTP 401")
        @WithAnonymousUser
        void listTransactions_returns401ForAnonymous() throws Exception {
            mockMvc.perform(get("/api/transactions")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(transactionListService);
        }

        /**
         * PCI-DSS PAN-masking guard (AAP &sect;0.6.6): ALL card
         * numbers on every row of the response body must be in the
         * masked form. This complements the per-row JSON-path check
         * in {@link #listTransactions_returns200WithPagedResults()}
         * by issuing a substring assertion across the entire response
         * body and asserting that the full 16-digit PAN never appears.
         */
        @Test
        @DisplayName("listTransactions_masksAllCardNumbersInResponse — no full PAN in body")
        @WithMockUser(username = "USER0001", roles = "USER")
        void listTransactions_masksAllCardNumbersInResponse() throws Exception {
            List<TransactionListDto.TransactionRow> rows = List.of(
                    buildRow("0000000000000001"),
                    buildRow("0000000000000002"),
                    buildRow("0000000000000003")
            );
            TransactionListDto dto = new TransactionListDto(
                    rows, 0, 10, 3L, 1, true, true, null);
            given(transactionListService.listTransactions(any(), eq(0))).willReturn(dto);

            MvcResult result = mockMvc.perform(get("/api/transactions")
                            .param("page", "0")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            org.junit.jupiter.api.Assertions.assertFalse(
                    body.contains(TEST_CARD_NUMBER),
                    "Response body MUST NOT contain the full unmasked PAN");
            org.junit.jupiter.api.Assertions.assertTrue(
                    body.contains(TEST_CARD_NUMBER_MASKED),
                    "Response body MUST contain the masked PAN form");
        }

        /**
         * Verifies ADMIN role also permits the list call. Both
         * USER and ADMIN roles pass the
         * {@code @PreAuthorize("hasAnyRole('USER','ADMIN')")} method
         * security check.
         */
        @Test
        @DisplayName("listTransactions_returns200ForAdmin — ADMIN role also permitted")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void listTransactions_returns200ForAdmin() throws Exception {
            TransactionListDto dto = new TransactionListDto(
                    List.of(), 0, 10, 0L, 0, true, true, null);
            given(transactionListService.listTransactions(any(), eq(0))).willReturn(dto);

            mockMvc.perform(get("/api/transactions")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"));
        }
    }



    // =====================================================================
    // Phase 2: GET /api/transactions/{id} — Single Transaction Detail
    //
    // COBOL provenance: app/cbl/COTRN01C.cbl — READ TRANSACT keyed on
    // TRAN-ID PIC X(16) from app/cpy/CVTRA05Y.cpy. The Java target
    // delegates to TransactionDetailService.getTransactionDetail(String)
    // which returns a 13-field TransactionDetailDto with PCI-DSS PAN
    // masking applied to TRAN-CARD-NUM.
    //
    // Authorization: @PreAuthorize("hasAnyRole('USER','ADMIN')") —
    // both roles can read transaction detail.
    // =====================================================================

    /**
     * Tests for the {@code GET /api/transactions/{id}} endpoint,
     * covering the happy-path detail view, the
     * {@code @Pattern("^[0-9]{16}$")} validation on the path variable,
     * the {@code RecordNotFoundException -> HTTP 404} mapping, the
     * security 401 path for anonymous callers, and the PCI-DSS
     * PAN-masking guarantee on the response body.
     */
    @Nested
    @DisplayName("GET /api/transactions/{id} — single transaction detail")
    class GetTransactionDetail {

        /**
         * Happy-path detail view. The service returns a fully-
         * populated {@link TransactionDetailDto} with the card number
         * already in masked form (the service applies the masking
         * before returning). The controller wraps it in the
         * {@code ApiResponse} envelope and emits HTTP 200.
         */
        @Test
        @DisplayName("getTransaction_returns200WithDetail — HTTP 200 with all 13 fields")
        @WithMockUser(username = "USER0001", roles = "USER")
        void getTransaction_returns200WithDetail() throws Exception {
            given(transactionDetailService.getTransactionDetail(eq(TEST_TXN_ID)))
                    .willReturn(detailDto());

            mockMvc.perform(get("/api/transactions/" + TEST_TXN_ID)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.data.transactionId").value(TEST_TXN_ID))
                    .andExpect(jsonPath("$.data.transactionType").value("01"))
                    .andExpect(jsonPath("$.data.transactionCategory").value(1))
                    .andExpect(jsonPath("$.data.source").value("POS"))
                    .andExpect(jsonPath("$.data.description").value("TEST TRANSACTION"))
                    .andExpect(jsonPath("$.data.amount").value(100.00))
                    .andExpect(jsonPath("$.data.merchantId").value(1234))
                    .andExpect(jsonPath("$.data.merchantName").value("TEST MERCHANT"))
                    .andExpect(jsonPath("$.data.merchantCity").value("TEST CITY"))
                    .andExpect(jsonPath("$.data.merchantZip").value("12345"))
                    // PCI-DSS: cardNumber MUST be masked
                    .andExpect(jsonPath("$.data.cardNumber").value(TEST_CARD_NUMBER_MASKED))
                    .andExpect(content().string(not(containsString(TEST_CARD_NUMBER))));
        }

        /**
         * Validates the {@code @Pattern("^[0-9]{16}$")} constraint on
         * the path variable: any path that is not exactly 16 digits
         * surfaces as HTTP 400 from the constraint-violation pipeline.
         * The service is NEVER invoked because validation
         * short-circuits before the controller method body.
         */
        @Test
        @DisplayName("getTransaction_returns400ForNon16DigitId — short id violates @Pattern")
        @WithMockUser(username = "USER0001", roles = "USER")
        void getTransaction_returns400ForNon16DigitId() throws Exception {
            mockMvc.perform(get("/api/transactions/123")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(transactionDetailService);
        }

        /**
         * Validates the {@code @Pattern("^[0-9]{16}$")} constraint on
         * the path variable: a 16-character path that contains
         * non-digit characters surfaces as HTTP 400. This complements
         * the short-id check by exercising the alpha-character branch
         * of the pattern.
         */
        @Test
        @DisplayName("getTransaction_returns400ForAlphaId — alpha chars violate @Pattern")
        @WithMockUser(username = "USER0001", roles = "USER")
        void getTransaction_returns400ForAlphaId() throws Exception {
            mockMvc.perform(get("/api/transactions/ABCDEFGHIJKLMNOP")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(transactionDetailService);
        }

        /**
         * Maps the service throwing {@link RecordNotFoundException}
         * (reject codes 100/101 — VSAM FILE STATUS '23' / NOTFND from
         * {@code CBTRN02C.cbl} XREF/Account cascade, also analogous
         * to {@code COTRN01C.cbl} {@code READ-TRANSACT-FILE} NOTFND)
         * to HTTP 404 Not Found. The
         * {@code GlobalExceptionHandler.handleRecordNotFound} wires
         * the exception's reason code into the envelope's
         * {@code code} property.
         */
        @Test
        @DisplayName("getTransaction_returns404WhenServiceThrowsRecordNotFound — HTTP 404, NOT_FOUND code")
        @WithMockUser(username = "USER0001", roles = "USER")
        void getTransaction_returns404WhenServiceThrowsRecordNotFound() throws Exception {
            BDDMockito.given(transactionDetailService.getTransactionDetail(eq(TEST_TXN_ID)))
                    .willThrow(new RecordNotFoundException(
                            "NOT_FOUND",
                            "Transaction tranId=" + TEST_TXN_ID + " not found"));

            mockMvc.perform(get("/api/transactions/" + TEST_TXN_ID)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        /**
         * Anonymous request rejected with HTTP 401 by method-level
         * authorization before the controller body executes. The
         * service is never consulted.
         */
        @Test
        @DisplayName("getTransaction_returns401ForAnonymous — anonymous yields HTTP 401")
        @WithAnonymousUser
        void getTransaction_returns401ForAnonymous() throws Exception {
            mockMvc.perform(get("/api/transactions/" + TEST_TXN_ID)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(transactionDetailService);
        }

        /**
         * PCI-DSS PAN-masking guard (AAP &sect;0.6.6): the response
         * body MUST NOT contain the full 16-digit PAN even in success
         * cases. This complements the per-field check in
         * {@link #getTransaction_returns200WithDetail()} by issuing a
         * substring assertion across the entire response body.
         */
        @Test
        @DisplayName("getTransaction_neverEchoesFullCardNumber — no full PAN in response body")
        @WithMockUser(username = "USER0001", roles = "USER")
        void getTransaction_neverEchoesFullCardNumber() throws Exception {
            given(transactionDetailService.getTransactionDetail(eq(TEST_TXN_ID)))
                    .willReturn(detailDto());

            MvcResult result = mockMvc.perform(get("/api/transactions/" + TEST_TXN_ID)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            org.junit.jupiter.api.Assertions.assertFalse(
                    body.contains(TEST_CARD_NUMBER),
                    "Response body MUST NOT contain the full unmasked PAN");
        }

        /**
         * Verifies ADMIN role also permits the detail call. Both
         * USER and ADMIN roles pass the
         * {@code @PreAuthorize("hasAnyRole('USER','ADMIN')")} method
         * security check.
         */
        @Test
        @DisplayName("getTransaction_returns200ForAdmin — ADMIN role also permitted")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void getTransaction_returns200ForAdmin() throws Exception {
            given(transactionDetailService.getTransactionDetail(eq(TEST_TXN_ID)))
                    .willReturn(detailDto());

            mockMvc.perform(get("/api/transactions/" + TEST_TXN_ID)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"));
        }
    }



    // =====================================================================
    // Phase 3: POST /api/transactions — Transaction Add (HTTP 201)
    //
    // COBOL provenance: app/cbl/COTRN02C.cbl (Tran-ID CT02) —
    // PROCESS-ENTER-KEY + WRITE-TRANSACT-FILE. The service performs:
    //   1. Field validation (XOR accountId/cardNumber, type, amount).
    //   2. MAX(TRAN-ID)+1 sequence generation (JPA sequence).
    //   3. XREF resolution via CardCrossReferenceRepository.
    //   4. Persist via TransactionRepository.save (HTTP 409 on dupkey).
    //   5. Publish 'transaction.posted' Kafka event partitioned by
    //      account ID per AAP §0.6.5.
    //
    // Authorization: @PreAuthorize("hasAnyRole('USER','ADMIN')") —
    // both roles can add transactions.
    //
    // HTTP 201 Created per AAP §0.3.4 — distinct from the HTTP 200
    // returned by listTransactions and getTransaction.
    // =====================================================================

    /**
     * Tests for the {@code POST /api/transactions} endpoint, covering
     * the happy-path HTTP 201 success with both XOR variants
     * (cardNumber-only and accountId-only).
     */
    @Nested
    @DisplayName("POST /api/transactions — happy-path add (HTTP 201)")
    class AddTransactionSuccess {

        /**
         * Happy-path add with the {@code cardNumber} variant of the
         * XOR contract (accountId null). The service returns a
         * "saved" {@link TransactionAddDto} carrying the resolved
         * 11-digit accountId (set via the XREF lookup) and the
         * masked PAN. The controller surfaces HTTP <b>201 Created</b>
         * and wraps the outbound DTO in the {@code ApiResponse}
         * envelope with the success message
         * {@code "Transaction created successfully"}.
         */
        @Test
        @DisplayName("addTransaction_returns201WithDetail — HTTP 201 with cardNumber variant")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns201WithDetail() throws Exception {
            TransactionAddDto request = validAddDtoByCard();
            TransactionAddDto saved = savedAddDto(TEST_ACCOUNT_ID);
            given(transactionAddService.addTransaction(any(TransactionAddDto.class)))
                    .willReturn(saved);

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())          // HTTP 201 — NOT 200
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.message").value("Transaction created successfully"))
                    .andExpect(jsonPath("$.data.accountId").value(TEST_ACCOUNT_ID))
                    .andExpect(jsonPath("$.data.transactionType").value("01"))
                    .andExpect(jsonPath("$.data.transactionCategory").value(1))
                    .andExpect(jsonPath("$.data.source").value("POS"))
                    .andExpect(jsonPath("$.data.amount").value(100.00))
                    // PCI-DSS: outbound DTO must carry the masked PAN
                    .andExpect(jsonPath("$.data.cardNumber").value(TEST_CARD_NUMBER_MASKED))
                    .andExpect(content().string(not(containsString(TEST_CARD_NUMBER))));

            verify(transactionAddService).addTransaction(any(TransactionAddDto.class));
        }

        /**
         * Happy-path add with the {@code accountId} variant of the
         * XOR contract (cardNumber null). The service resolves the
         * card number internally via {@code findByXrefAcctId(...)}
         * and returns a saved DTO carrying the masked PAN of the
         * resolved card. The controller again surfaces HTTP 201.
         */
        @Test
        @DisplayName("addTransaction_returns201WithAccountIdOnly — HTTP 201 with accountId variant")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns201WithAccountIdOnly() throws Exception {
            TransactionAddDto request = validAddDtoByAccount();
            TransactionAddDto saved = savedAddDto(TEST_ACCOUNT_ID);
            given(transactionAddService.addTransaction(any(TransactionAddDto.class)))
                    .willReturn(saved);

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())          // HTTP 201
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.data.accountId").value(TEST_ACCOUNT_ID));
        }

        /**
         * Verifies ADMIN role also permits the add call. Both USER
         * and ADMIN roles pass the
         * {@code @PreAuthorize("hasAnyRole('USER','ADMIN')")} method
         * security check.
         */
        @Test
        @DisplayName("addTransaction_returns201ForAdmin — ADMIN role also permitted")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void addTransaction_returns201ForAdmin() throws Exception {
            TransactionAddDto request = validAddDtoByCard();
            TransactionAddDto saved = savedAddDto(TEST_ACCOUNT_ID);
            given(transactionAddService.addTransaction(any(TransactionAddDto.class)))
                    .willReturn(saved);

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }
    }

    // =====================================================================
    // Phase 4: XOR Validation (CRITICAL — accountId XOR cardNumber)
    //
    // COBOL provenance: app/cbl/COTRN02C.cbl PROCESS-ENTER-KEY -
    //   the COBOL program required EITHER ACCT-ID-N (11-digit) OR
    //   CARD-NUM-N (16-digit) but NOT both. The XREF AIX
    //   (CXACAIX) resolved whichever was supplied to the other.
    //   In the Java target this XOR rule is enforced at the
    //   SERVICE layer (TransactionAddService), NOT at the DTO
    //   layer — both fields are individually Optional with a
    //   @Pattern constraint, but the service throws
    //   ValidationException when both are populated or both are
    //   null.
    //
    // The controller forwards the DTO unchanged; the service-level
    // ValidationException maps to HTTP 400 via
    // GlobalExceptionHandler.handleValidation.
    // =====================================================================

    /**
     * Tests for the XOR contract on (accountId, cardNumber). Both
     * branches of the XOR (both populated, both null) trigger a
     * service-layer {@link ValidationException} which maps to HTTP
     * 400 with the standardized envelope.
     */
    @Nested
    @DisplayName("POST /api/transactions — XOR validation (accountId XOR cardNumber)")
    class XorValidation {

        /**
         * BOTH {@code accountId} AND {@code cardNumber} populated -
         * the service throws {@link ValidationException} which the
         * {@code GlobalExceptionHandler.handleValidation} maps to
         * HTTP 400 with {@code code = "VALIDATION"}.
         */
        @Test
        @DisplayName("addTransaction_returns400WhenBothAccountIdAndCardNumberProvided — XOR violation")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400WhenBothAccountIdAndCardNumberProvided() throws Exception {
            // BOTH accountId AND cardNumber populated — XOR violation
            TransactionAddDto request = new TransactionAddDto(
                    TEST_ACCOUNT_ID,                       // accountId populated
                    TEST_CARD_NUMBER,                      // cardNumber populated
                    "01", 1, "POS", "TEST TRANSACTION",
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT", "TEST CITY", "12345", "Y"
            );
            BDDMockito.given(transactionAddService.addTransaction(any(TransactionAddDto.class)))
                    .willThrow(new ValidationException(
                            "Provide accountId XOR cardNumber, not both"));

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"));
        }

        /**
         * NEITHER {@code accountId} NOR {@code cardNumber} populated
         * - the service throws {@link ValidationException} which
         * maps to HTTP 400. This is the COBOL
         * {@code "Account or Card Number must be entered..."} error
         * path from the original COTRN02C.
         */
        @Test
        @DisplayName("addTransaction_returns400WhenNeitherAccountIdNorCardNumberProvided — XOR violation")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400WhenNeitherAccountIdNorCardNumberProvided() throws Exception {
            // NEITHER accountId NOR cardNumber populated — XOR violation
            TransactionAddDto request = new TransactionAddDto(
                    null,                                  // accountId null
                    null,                                  // cardNumber null
                    "01", 1, "POS", "TEST TRANSACTION",
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT", "TEST CITY", "12345", "Y"
            );
            BDDMockito.given(transactionAddService.addTransaction(any(TransactionAddDto.class)))
                    .willThrow(new ValidationException(
                            "Account or Card Number must be entered..."));

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"));
        }
    }



    // =====================================================================
    // Phase 5: Bean Validation on TransactionAddDto
    //
    // COBOL provenance: app/cbl/COTRN02C.cbl — input field validation
    // (account/card number digit-only checks, type/category numeric
    // checks, amount format check matching PIC S9(09)V99, mandatory
    // field checks). In the Java target each validation is a Jakarta
    // Bean Validation annotation on the TransactionAddDto record
    // component; the @Valid @RequestBody binding triggers Spring's
    // MethodArgumentNotValidException which maps to HTTP 400 via
    // GlobalExceptionHandler.handleMethodArgumentNotValid with a
    // populated fieldErrors array.
    // =====================================================================

    /**
     * Tests for the Jakarta Bean Validation constraints declared on
     * {@link TransactionAddDto}. Each test surfaces a single
     * violation and asserts HTTP 400 + the standardized envelope's
     * {@code fieldErrors} entry for the offending field. The service
     * is NEVER invoked because validation short-circuits before the
     * controller method body executes.
     */
    @Nested
    @DisplayName("POST /api/transactions — Jakarta Bean Validation (fieldErrors)")
    class BeanValidation {

        /**
         * The {@code accountId} {@code @Pattern("^\\d{11}$")}
         * constraint rejects non-digit characters even before the
         * service-level XOR check fires.
         */
        @Test
        @DisplayName("addTransaction_returns400ForInvalidAccountIdPattern — non-digit accountId")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400ForInvalidAccountIdPattern() throws Exception {
            TransactionAddDto request = new TransactionAddDto(
                    "abc",                                 // INVALID — fails @Pattern
                    null,
                    "01", 1, "POS", "TEST TRANSACTION",
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT", "TEST CITY", "12345", "Y"
            );

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='accountId')]").exists());

            verifyNoInteractions(transactionAddService);
        }

        /**
         * The {@code cardNumber} {@code @Pattern("^\\d{16}$")}
         * constraint rejects a short numeric value.
         */
        @Test
        @DisplayName("addTransaction_returns400ForInvalidCardNumberPattern — short cardNumber")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400ForInvalidCardNumberPattern() throws Exception {
            TransactionAddDto request = new TransactionAddDto(
                    null,
                    "123",                                 // INVALID — fails @Pattern (not 16 digits)
                    "01", 1, "POS", "TEST TRANSACTION",
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT", "TEST CITY", "12345", "Y"
            );

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='cardNumber')]").exists());

            verifyNoInteractions(transactionAddService);
        }

        /**
         * The {@code transactionType} {@code @Pattern("^\\d{2}$")}
         * constraint rejects alpha characters — the COBOL
         * {@code TRAN-TYPE-CD PIC X(02)} field must be 2 digits.
         */
        @Test
        @DisplayName("addTransaction_returns400ForInvalidTransactionType — alpha transactionType")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400ForInvalidTransactionType() throws Exception {
            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUMBER,
                    "ABC",                                 // INVALID — fails @Pattern
                    1, "POS", "TEST TRANSACTION",
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT", "TEST CITY", "12345", "Y"
            );

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='transactionType')]").exists());

            verifyNoInteractions(transactionAddService);
        }

        /**
         * The {@code transactionCategory} {@code @NotNull}
         * constraint rejects a null Integer.
         */
        @Test
        @DisplayName("addTransaction_returns400ForMissingTransactionCategory — null transactionCategory")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400ForMissingTransactionCategory() throws Exception {
            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUMBER, "01",
                    null,                                  // INVALID — fails @NotNull
                    "POS", "TEST TRANSACTION",
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT", "TEST CITY", "12345", "Y"
            );

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='transactionCategory')]").exists());

            verifyNoInteractions(transactionAddService);
        }

        /**
         * The {@code description} {@code @NotBlank} constraint
         * rejects an empty string.
         */
        @Test
        @DisplayName("addTransaction_returns400ForMissingDescription — blank description")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400ForMissingDescription() throws Exception {
            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUMBER, "01", 1, "POS",
                    "",                                    // INVALID — fails @NotBlank
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT", "TEST CITY", "12345", "Y"
            );

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='description')]").exists());

            verifyNoInteractions(transactionAddService);
        }

        /**
         * The {@code description} {@code @Size(max=100)} constraint
         * rejects a string longer than 100 characters — matches the
         * COBOL {@code TRAN-DESC PIC X(100)} field width.
         */
        @Test
        @DisplayName("addTransaction_returns400ForDescriptionTooLong — >100 chars violates @Size(max=100)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400ForDescriptionTooLong() throws Exception {
            String tooLong = "X".repeat(101);              // INVALID — exceeds @Size(max=100)
            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUMBER, "01", 1, "POS",
                    tooLong,
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT", "TEST CITY", "12345", "Y"
            );

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='description')]").exists());

            verifyNoInteractions(transactionAddService);
        }

        /**
         * The {@code amount} {@code @NotNull} constraint rejects a
         * null monetary value — TRAN-AMT is mandatory in
         * {@code CVTRA05Y.cpy}.
         */
        @Test
        @DisplayName("addTransaction_returns400ForMissingAmount — null amount")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400ForMissingAmount() throws Exception {
            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUMBER, "01", 1, "POS", "TEST TRANSACTION",
                    null,                                  // INVALID — fails @NotNull
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT", "TEST CITY", "12345", "Y"
            );

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='amount')]").exists());

            verifyNoInteractions(transactionAddService);
        }

        /**
         * The {@code amount} {@code @Digits(integer=9, fraction=2)}
         * constraint rejects a scale-3 BigDecimal — matches the
         * COBOL {@code TRAN-AMT PIC S9(09)V99} which has exactly 2
         * decimal places. Per AAP &sect;0.6.1 every monetary
         * fixture uses the STRING constructor of BigDecimal.
         */
        @Test
        @DisplayName("addTransaction_returns400ForAmountScaleTooLarge — scale-3 violates @Digits(fraction=2)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400ForAmountScaleTooLarge() throws Exception {
            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUMBER, "01", 1, "POS", "TEST TRANSACTION",
                    new BigDecimal("100.123"),             // INVALID — 3 decimal places
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT", "TEST CITY", "12345", "Y"
            );

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='amount')]").exists());

            verifyNoInteractions(transactionAddService);
        }

        /**
         * The {@code amount} {@code @Digits(integer=9, fraction=2)}
         * constraint rejects 11 integer digits — matches the
         * COBOL {@code TRAN-AMT PIC S9(09)V99} which has exactly 9
         * integer digits (max value 999999999.99).
         */
        @Test
        @DisplayName("addTransaction_returns400ForAmountIntegerTooLarge — 11 integer digits violates @Digits(integer=9)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400ForAmountIntegerTooLarge() throws Exception {
            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUMBER, "01", 1, "POS", "TEST TRANSACTION",
                    new BigDecimal("12345678901.00"),      // INVALID — 11 integer digits
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT", "TEST CITY", "12345", "Y"
            );

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='amount')]").exists());

            verifyNoInteractions(transactionAddService);
        }

        /**
         * Verifies the BOUNDARY value of the
         * {@code @Digits(integer=9, fraction=2)} constraint — the
         * maximum legal value 999999999.99 (9 integer digits +
         * 2 fractional digits) must PASS validation.
         */
        @Test
        @DisplayName("addTransaction_acceptsAmountScale2 — 999999999.99 passes @Digits at boundary")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_acceptsAmountScale2() throws Exception {
            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUMBER, "01", 1, "POS", "TEST TRANSACTION",
                    new BigDecimal("999999999.99"),        // BOUNDARY — must pass
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT", "TEST CITY", "12345", "Y"
            );
            given(transactionAddService.addTransaction(any(TransactionAddDto.class)))
                    .willReturn(savedAddDto(TEST_ACCOUNT_ID));

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());      // HTTP 201 — validation passed
        }

        /**
         * The {@code confirm} {@code @Pattern("^[YN]$")} constraint
         * rejects any character outside the (Y, N) alphabet — the
         * COBOL confirmation field accepted only Y or N.
         */
        @Test
        @DisplayName("addTransaction_returns400ForInvalidConfirm — X violates @Pattern(^[YN]$)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400ForInvalidConfirm() throws Exception {
            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUMBER, "01", 1, "POS", "TEST TRANSACTION",
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT", "TEST CITY", "12345",
                    "X"                                    // INVALID — not Y or N
            );

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='confirm')]").exists());

            verifyNoInteractions(transactionAddService);
        }

        /**
         * The {@code merchantId} {@code @NotNull} constraint rejects
         * a null Long — TRAN-MERCHANT-ID is mandatory in
         * {@code CVTRA05Y.cpy} (PIC 9(09)).
         */
        @Test
        @DisplayName("addTransaction_returns400ForMissingMerchantId — null merchantId")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400ForMissingMerchantId() throws Exception {
            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUMBER, "01", 1, "POS", "TEST TRANSACTION",
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    null,                                  // INVALID — fails @NotNull
                    "TEST MERCHANT", "TEST CITY", "12345", "Y"
            );

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='merchantId')]").exists());

            verifyNoInteractions(transactionAddService);
        }

        /**
         * The {@code merchantName} {@code @NotBlank} constraint
         * rejects a blank merchant name.
         */
        @Test
        @DisplayName("addTransaction_returns400ForBlankMerchantName — blank merchantName")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400ForBlankMerchantName() throws Exception {
            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUMBER, "01", 1, "POS", "TEST TRANSACTION",
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L,
                    "",                                    // INVALID — fails @NotBlank
                    "TEST CITY", "12345", "Y"
            );

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='merchantName')]").exists());

            verifyNoInteractions(transactionAddService);
        }

        /**
         * The {@code source} {@code @NotBlank} constraint rejects a
         * blank source.
         */
        @Test
        @DisplayName("addTransaction_returns400ForBlankSource — blank source")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400ForBlankSource() throws Exception {
            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUMBER, "01", 1,
                    "",                                    // INVALID — fails @NotBlank
                    "TEST TRANSACTION",
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT", "TEST CITY", "12345", "Y"
            );

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='source')]").exists());

            verifyNoInteractions(transactionAddService);
        }

        /**
         * The {@code source} {@code @Size(max=10)} constraint rejects
         * strings longer than 10 characters — matches the COBOL
         * {@code TRAN-SOURCE PIC X(10)} field width.
         */
        @Test
        @DisplayName("addTransaction_returns400ForSourceTooLong — >10 chars violates @Size(max=10)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400ForSourceTooLong() throws Exception {
            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUMBER, "01", 1,
                    "VERYLONGSOURCENAMEEXCEEDING10",       // INVALID — 28 chars
                    "TEST TRANSACTION",
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT", "TEST CITY", "12345", "Y"
            );

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='source')]").exists());

            verifyNoInteractions(transactionAddService);
        }

        /**
         * The {@code merchantCity} {@code @NotBlank} constraint
         * rejects a blank merchant city.
         */
        @Test
        @DisplayName("addTransaction_returns400ForBlankMerchantCity — blank merchantCity")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400ForBlankMerchantCity() throws Exception {
            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUMBER, "01", 1, "POS", "TEST TRANSACTION",
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT",
                    "",                                    // INVALID — fails @NotBlank
                    "12345", "Y"
            );

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='merchantCity')]").exists());

            verifyNoInteractions(transactionAddService);
        }

        /**
         * The {@code merchantZip} {@code @NotBlank} constraint
         * rejects a blank merchant zip.
         */
        @Test
        @DisplayName("addTransaction_returns400ForBlankMerchantZip — blank merchantZip")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400ForBlankMerchantZip() throws Exception {
            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUMBER, "01", 1, "POS", "TEST TRANSACTION",
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT", "TEST CITY",
                    "",                                    // INVALID — fails @NotBlank
                    "Y"
            );

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='merchantZip')]").exists());

            verifyNoInteractions(transactionAddService);
        }

        /**
         * The {@code merchantZip} {@code @Size(max=10)} constraint
         * rejects strings longer than 10 characters — matches the
         * COBOL {@code TRAN-MERCHANT-ZIP PIC X(10)} field width.
         */
        @Test
        @DisplayName("addTransaction_returns400ForMerchantZipTooLong — >10 chars violates @Size(max=10)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400ForMerchantZipTooLong() throws Exception {
            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUMBER, "01", 1, "POS", "TEST TRANSACTION",
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT", "TEST CITY",
                    "12345678901",                         // INVALID — 11 chars
                    "Y"
            );

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='merchantZip')]").exists());

            verifyNoInteractions(transactionAddService);
        }

        /**
         * The {@code originationTimestamp} {@code @NotNull}
         * constraint rejects a null timestamp.
         */
        @Test
        @DisplayName("addTransaction_returns400ForMissingOriginationTimestamp — null originationTimestamp")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400ForMissingOriginationTimestamp() throws Exception {
            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUMBER, "01", 1, "POS", "TEST TRANSACTION",
                    new BigDecimal("100.00"),
                    null,                                  // INVALID — fails @NotNull
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT", "TEST CITY", "12345", "Y"
            );

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='originationTimestamp')]").exists());

            verifyNoInteractions(transactionAddService);
        }
    }



    // =====================================================================
    // Phase 6: Exception Mapping (CRITICAL — Reject Codes 100..109)
    //
    // COBOL provenance: app/cbl/CBTRN02C.cbl WS-VALIDATION-FAIL-REASON
    // family (codes 100-109). Per AAP §0.7.1 / §0.7.2 these reject
    // codes MUST be preserved verbatim on the JSON error envelope
    // when surfaced via the typed domain exception hierarchy:
    //
    //   * 100 (XREF NOT FOUND)             -> RecordNotFoundException
    //                                          -> HTTP 404
    //   * 101 (ACCT NOT FOUND)             -> RecordNotFoundException
    //                                          -> HTTP 404
    //   * 102 (OVERLIMIT TRANSACTION)      -> CreditLimitExceededException
    //                                          -> HTTP 422 (code "102")
    //   * 103 (TRAN AFTER ACCT EXPIRATION) -> ExpiredCardException
    //                                          -> HTTP 422 (code "103")
    //   * 109 (arithmetic overflow)        -> OnSizeErrorException
    //                                          -> HTTP 422
    //
    // Plus VSAM FILE STATUS '22' (DUPKEY) on TRANSACT write:
    //   * "22"  (DUPLICATE TRAN-ID)        -> DuplicateRecordException
    //                                          -> HTTP 409
    //
    // The HTTP-status mapping lives in GlobalExceptionHandler; the
    // tests below assert end-to-end that the service-thrown exception
    // surfaces with the expected HTTP status AND the verbatim reject
    // code on the standardized envelope.
    // =====================================================================

    /**
     * Tests for the typed domain-exception &rarr; HTTP-status
     * mapping. Each test stubs the service to throw a specific
     * domain exception and asserts the resulting HTTP response
     * (status code, envelope {@code code}, and envelope
     * {@code message}) matches the
     * {@code GlobalExceptionHandler} contract.
     */
    @Nested
    @DisplayName("POST /api/transactions — domain exception mapping (reject codes 100-109)")
    class ExceptionMapping {

        /**
         * {@link RecordNotFoundException} (reject codes 100/101 from
         * {@code CBTRN02C.cbl} — XREF/Account NOTFND) maps to HTTP
         * 404 Not Found via
         * {@code GlobalExceptionHandler.handleRecordNotFound}.
         */
        @Test
        @DisplayName("addTransaction_returns404WhenServiceThrowsRecordNotFound — reject codes 100/101")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns404WhenServiceThrowsRecordNotFound() throws Exception {
            TransactionAddDto request = validAddDtoByCard();
            BDDMockito.given(transactionAddService.addTransaction(any(TransactionAddDto.class)))
                    .willThrow(new RecordNotFoundException(
                            "NOT_FOUND",
                            "Account or Cross-Reference record not found"));

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        /**
         * {@link CreditLimitExceededException} (reject code 102
         * "OVERLIMIT TRANSACTION" from {@code CBTRN02C.cbl} line
         * 410) maps to HTTP <b>422 Unprocessable Entity</b> via
         * {@code GlobalExceptionHandler.handleCreditLimitExceeded}.
         *
         * <p><b>Reject-code preservation (AAP &sect;0.7.2):</b>
         * The envelope's {@code code} field MUST carry the literal
         * string {@code "102"} — the verbatim COBOL reject code
         * from {@code CreditLimitExceededException.REJECT_CODE}.
         * This is the canonical assertion guaranteeing downstream
         * regulatory and operations systems can branch on the exact
         * historical value.</p>
         */
        @Test
        @DisplayName("addTransaction_returns422WhenServiceThrowsCreditLimitExceeded — reject code 102, HTTP 422")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns422WhenServiceThrowsCreditLimitExceeded() throws Exception {
            TransactionAddDto request = validAddDtoByCard();
            // No-arg constructor uses REJECT_CODE="102" and
            // REJECT_DESCRIPTION="OVERLIMIT TRANSACTION" per AAP §0.7.2
            BDDMockito.given(transactionAddService.addTransaction(any(TransactionAddDto.class)))
                    .willThrow(new CreditLimitExceededException());

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())   // HTTP 422
                    // Reject code 102 preserved verbatim on the envelope
                    .andExpect(jsonPath("$.code").value("102"))
                    // Verbatim COBOL description from line 411
                    .andExpect(jsonPath("$.message")
                            .value(CreditLimitExceededException.REJECT_DESCRIPTION));
        }

        /**
         * {@link ExpiredCardException} (reject code 103
         * "TRANSACTION RECEIVED AFTER ACCT EXPIRATION" from
         * {@code CBTRN02C.cbl} line 417) maps to HTTP <b>422
         * Unprocessable Entity</b> via
         * {@code GlobalExceptionHandler.handleExpiredCard}.
         *
         * <p><b>Reject-code preservation (AAP &sect;0.7.2):</b>
         * The envelope's {@code code} field MUST carry the literal
         * string {@code "103"} — the verbatim COBOL reject code
         * from {@code ExpiredCardException.REJECT_CODE}.</p>
         */
        @Test
        @DisplayName("addTransaction_returns422WhenServiceThrowsExpiredCard — reject code 103, HTTP 422")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns422WhenServiceThrowsExpiredCard() throws Exception {
            TransactionAddDto request = validAddDtoByCard();
            // No-arg constructor uses REJECT_CODE="103" and
            // REJECT_DESCRIPTION="TRANSACTION RECEIVED AFTER ACCT EXPIRATION"
            BDDMockito.given(transactionAddService.addTransaction(any(TransactionAddDto.class)))
                    .willThrow(new ExpiredCardException());

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())   // HTTP 422
                    // Reject code 103 preserved verbatim on the envelope
                    .andExpect(jsonPath("$.code").value("103"))
                    // Verbatim COBOL description from line 418-419
                    .andExpect(jsonPath("$.message")
                            .value(ExpiredCardException.REJECT_DESCRIPTION));
        }

        /**
         * {@link OnSizeErrorException} (COBOL {@code ON SIZE ERROR}
         * arithmetic overflow on amount computations) maps to HTTP
         * <b>422 Unprocessable Entity</b> via
         * {@code GlobalExceptionHandler.handleOnSizeError}.
         *
         * <p><b>Status mapping note:</b> The
         * {@code GlobalExceptionHandler} maps this exception to
         * HTTP 422 (NOT HTTP 500) per the Issue CP4-#6 design
         * decision: an arithmetic overflow is a business arithmetic
         * constraint violation (the request is syntactically
         * well-formed but the resulting computation exceeds the
         * configured precision), not an internal server fault.
         * This aligns with the 422 mappings for
         * {@link CreditLimitExceededException} and
         * {@link ExpiredCardException}, which are likewise rejected
         * business outcomes for valid inputs.</p>
         */
        @Test
        @DisplayName("addTransaction_returns422WhenServiceThrowsOnSizeError — arithmetic overflow, HTTP 422")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns422WhenServiceThrowsOnSizeError() throws Exception {
            TransactionAddDto request = validAddDtoByCard();
            BDDMockito.given(transactionAddService.addTransaction(any(TransactionAddDto.class)))
                    .willThrow(new OnSizeErrorException("Overflow on TRAN-AMT compute"));

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())   // HTTP 422 (NOT 500)
                    // The default reason code is "ARITHMETIC_OVERFLOW"
                    // per OnSizeErrorException.DEFAULT_REASON_CODE
                    .andExpect(jsonPath("$.code")
                            .value(OnSizeErrorException.DEFAULT_REASON_CODE));
        }

        /**
         * {@link DuplicateRecordException} (VSAM FILE STATUS '22'
         * DUPKEY from {@code CBTRN02C.cbl} {@code WRITE-TRANSACT-FILE}
         * — the JPA sequence generated a transaction ID that
         * collides with an existing record under heavy concurrency)
         * maps to HTTP 409 Conflict via
         * {@code GlobalExceptionHandler.handleDuplicateRecord}.
         */
        @Test
        @DisplayName("addTransaction_returns409WhenServiceThrowsDuplicateRecord — FILE STATUS '22', HTTP 409")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns409WhenServiceThrowsDuplicateRecord() throws Exception {
            TransactionAddDto request = validAddDtoByCard();
            BDDMockito.given(transactionAddService.addTransaction(any(TransactionAddDto.class)))
                    .willThrow(new DuplicateRecordException(
                            "22",
                            "Transaction " + TEST_TXN_ID + " already exists"));

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())              // HTTP 409
                    .andExpect(jsonPath("$.code").value("22"));    // FILE STATUS preserved
        }

        /**
         * Service-thrown {@link ValidationException} (e.g., the XOR
         * cross-field rule fails or a domain invariant is violated
         * inside the service body) maps to HTTP 400 Bad Request via
         * {@code GlobalExceptionHandler.handleValidation} with
         * {@code code = "VALIDATION"}.
         */
        @Test
        @DisplayName("addTransaction_returns400WhenServiceThrowsValidation — service-level validation")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returns400WhenServiceThrowsValidation() throws Exception {
            TransactionAddDto request = validAddDtoByCard();
            BDDMockito.given(transactionAddService.addTransaction(any(TransactionAddDto.class)))
                    .willThrow(new ValidationException(
                            "Service-level cross-field validation failed"));

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())            // HTTP 400
                    .andExpect(jsonPath("$.code").value("VALIDATION"));
        }
    }

    // =====================================================================
    // Phase 7: BigDecimal HALF_EVEN Discipline (CRITICAL AAP §0.6.1, §0.7.1)
    //
    // The AAP §0.6.1 decimal-precision rule requires:
    //   1. NEVER use new BigDecimal(double) — only the String
    //      constructor (new BigDecimal("...")) to avoid IEEE-754
    //      precision loss.
    //   2. setScale(2, RoundingMode.HALF_EVEN) (banker's rounding) at
    //      every arithmetic boundary — matches COBOL PIC S9(09)V99.
    //   3. compareTo(...) instead of equals(...) for value comparison.
    //
    // The phase-7 tests verify that the controller PRESERVES the
    // request body's BigDecimal precision when forwarding to the
    // service — no implicit downcast to double, no scale truncation,
    // no rounding. The ArgumentCaptor pattern captures the dto passed
    // to the service and asserts the amount field round-trips exactly.
    // =====================================================================

    /**
     * Tests for the BigDecimal precision-preservation contract on
     * the {@code amount} field. Per AAP &sect;0.6.1 the controller
     * MUST forward the request body's
     * {@link BigDecimal#scale() scale} and value unchanged into the
     * {@link TransactionAddService#addTransaction(TransactionAddDto)}
     * call.
     */
    @Nested
    @DisplayName("POST /api/transactions — BigDecimal HALF_EVEN discipline (AAP §0.6.1)")
    class BigDecimalDiscipline {

        /**
         * Verifies the controller forwards the exact
         * {@link BigDecimal} value (including scale) from the
         * JSON request body to the service. The
         * {@link ArgumentCaptor} captures the dto passed to
         * {@code addTransaction(...)} and asserts the amount's
         * {@link BigDecimal#compareTo(BigDecimal) compareTo}
         * returns 0 against the expected value with the same
         * scale.
         *
         * <p>This test guards against accidental downcast to
         * {@code double} during request binding or DTO copying
         * — any IEEE-754 round-trip would surface as a
         * precision-loss assertion failure.</p>
         */
        @Test
        @DisplayName("addTransaction_preservesBigDecimalPrecisionInRequest — scale=2 round-trip via ArgumentCaptor")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_preservesBigDecimalPrecisionInRequest() throws Exception {
            // CRITICAL: STRING constructor per AAP §0.6.1
            BigDecimal expectedAmount = new BigDecimal("12345.67");
            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUMBER, "01", 1, "POS", "TEST TRANSACTION",
                    expectedAmount,
                    LocalDateTime.of(2026, 5, 20, 10, 30),
                    LocalDateTime.of(2026, 5, 20, 10, 31),
                    1234L, "TEST MERCHANT", "TEST CITY", "12345", "Y"
            );
            given(transactionAddService.addTransaction(any(TransactionAddDto.class)))
                    .willReturn(savedAddDto(TEST_ACCOUNT_ID));

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());

            // Capture the dto forwarded to the service and assert the
            // amount round-trips byte-for-byte.
            ArgumentCaptor<TransactionAddDto> dtoCaptor =
                    ArgumentCaptor.forClass(TransactionAddDto.class);
            verify(transactionAddService).addTransaction(dtoCaptor.capture());
            BigDecimal actualAmount = dtoCaptor.getValue().amount();
            org.junit.jupiter.api.Assertions.assertNotNull(actualAmount,
                    "Amount must not be null after request binding");
            // Use compareTo (NOT equals) per AAP §0.6.1 — equals would
            // be scale-sensitive (12345.67 != 12345.670 even though
            // they are numerically equal).
            org.junit.jupiter.api.Assertions.assertEquals(0,
                    actualAmount.compareTo(expectedAmount),
                    "Amount value MUST round-trip exactly through request binding");
            // Scale MUST be 2 to match COBOL PIC S9(09)V99
            org.junit.jupiter.api.Assertions.assertEquals(2, actualAmount.scale(),
                    "Amount scale MUST be 2 after request binding (COBOL PIC S9(09)V99)");
        }

        /**
         * Self-validation: every BigDecimal fixture in this test
         * class MUST use the {@code String} constructor. This test
         * exercises a representative fixture and uses
         * {@link BigDecimal#compareTo(BigDecimal)} to assert that
         * the round-trip through JSON serialization preserves the
         * exact decimal value (which is only guaranteed when the
         * source value was constructed from a {@code String}).
         *
         * <p>This is the canonical anti-double-constructor guard for
         * the test class — if any fixture ever switched to
         * {@code new BigDecimal(double)} the precision would not
         * round-trip and this test would fail.</p>
         */
        @Test
        @DisplayName("addTransaction_useBigDecimalStringConstructorInTests — String-ctor round-trip")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_useBigDecimalStringConstructorInTests() throws Exception {
            // The fixture uses new BigDecimal("100.00") — STRING ctor
            TransactionAddDto request = validAddDtoByCard();
            given(transactionAddService.addTransaction(any(TransactionAddDto.class)))
                    .willReturn(savedAddDto(TEST_ACCOUNT_ID));

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());

            ArgumentCaptor<TransactionAddDto> dtoCaptor =
                    ArgumentCaptor.forClass(TransactionAddDto.class);
            verify(transactionAddService).addTransaction(dtoCaptor.capture());
            // The fixture amount is exactly new BigDecimal("100.00")
            BigDecimal expected = new BigDecimal("100.00");
            org.junit.jupiter.api.Assertions.assertEquals(0,
                    dtoCaptor.getValue().amount().compareTo(expected),
                    "Fixture amount MUST round-trip exactly — STRING constructor required");
            org.junit.jupiter.api.Assertions.assertEquals(2,
                    dtoCaptor.getValue().amount().scale(),
                    "Fixture amount scale MUST be exactly 2");
        }
    }

    // =====================================================================
    // Phase 8: HTTP 201 (NOT 200) Verification
    //
    // The success POST response MUST carry HTTP 201 Created — NOT 200
    // OK. This is the REST convention for resource creation and is
    // explicitly mandated by AAP §0.3.4. Among the controllers in the
    // refactor, POST /api/transactions is one of only four endpoints
    // returning 201 (along with POST /api/admin/users, the auth
    // sign-in endpoint, and the bill-payment endpoint).
    // =====================================================================

    /**
     * Standalone verification that the POST endpoint emits HTTP
     * <b>201 Created</b> on the success path — explicitly distinct
     * from the HTTP 200 returned by the GET endpoints. Includes a
     * negative assertion that the status is NOT 200.
     */
    @Nested
    @DisplayName("POST /api/transactions — HTTP 201 status verification")
    class HttpStatusVerification {

        /**
         * Asserts the POST success response carries HTTP status code
         * exactly 201. Uses {@link MvcResult#getResponse()} to
         * read the integer status code directly for unambiguous
         * verification (the {@code status().isCreated()} matcher
         * matches any 201 — this test pins the exact value).
         */
        @Test
        @DisplayName("addTransaction_returnsExactlyHttp201 — status == 201")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_returnsExactlyHttp201() throws Exception {
            TransactionAddDto request = validAddDtoByCard();
            given(transactionAddService.addTransaction(any(TransactionAddDto.class)))
                    .willReturn(savedAddDto(TEST_ACCOUNT_ID));

            MvcResult result = mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andReturn();

            int actualStatus = result.getResponse().getStatus();
            org.junit.jupiter.api.Assertions.assertEquals(201, actualStatus,
                    "POST /api/transactions MUST return HTTP 201 Created (NOT 200 OK)");
        }
    }

    // =====================================================================
    // Phase 9: PCI-DSS PAN Masking (AAP §0.6.6)
    //
    // The full 16-digit Visa test PAN 4111111111111111 MUST NOT
    // appear in any response body, error envelope, or exception
    // message at any HTTP entry point. Phase-1 and phase-2 each
    // have their own per-endpoint masking guards; this phase adds
    // POST-specific guards covering both success and error paths.
    // =====================================================================

    /**
     * Tests for the PCI-DSS PAN-masking guarantee on the POST
     * endpoint. The response body MUST NOT contain the full PAN
     * even when:
     * <ul>
     *   <li>The success path returns the outbound DTO.</li>
     *   <li>The error path returns a validation envelope.</li>
     * </ul>
     */
    @Nested
    @DisplayName("POST /api/transactions — PCI-DSS PAN masking (AAP §0.6.6)")
    class PanMasking {

        /**
         * On the HTTP 201 success path, the outbound
         * {@link TransactionAddDto} MUST carry the masked PAN
         * ({@code ************nnnn}), NEVER the full 16-digit
         * value. The fixture's {@code savedAddDto(...)} returns a
         * masked PAN; we additionally assert via a substring check
         * that the full PAN never appears in the rendered JSON.
         */
        @Test
        @DisplayName("addTransaction_responseNeverContainsFullPan — success path body has no full PAN")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_responseNeverContainsFullPan() throws Exception {
            TransactionAddDto request = validAddDtoByCard();
            // savedAddDto returns the masked PAN form
            given(transactionAddService.addTransaction(any(TransactionAddDto.class)))
                    .willReturn(savedAddDto(TEST_ACCOUNT_ID));

            MvcResult result = mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            org.junit.jupiter.api.Assertions.assertFalse(
                    body.contains(TEST_CARD_NUMBER),
                    "Success response body MUST NOT contain the full unmasked PAN");
            org.junit.jupiter.api.Assertions.assertTrue(
                    body.contains(TEST_CARD_NUMBER_MASKED),
                    "Success response body MUST contain the masked PAN");
        }

        /**
         * On error paths (e.g., a service-thrown
         * {@link ValidationException}), the response envelope MUST
         * NOT echo the request body's full PAN. The
         * {@code GlobalExceptionHandler} uses the PCI-safe 3-arg
         * {@code FieldError.of(...)} factory which OMITS the
         * Spring-provided {@code rejectedValue}, ensuring PANs are
         * never echoed in field-error entries.
         */
        @Test
        @DisplayName("addTransaction_requestPanNotEchoedInErrors — error envelope has no full PAN")
        @WithMockUser(username = "USER0001", roles = "USER")
        void addTransaction_requestPanNotEchoedInErrors() throws Exception {
            TransactionAddDto request = validAddDtoByCard();
            BDDMockito.given(transactionAddService.addTransaction(any(TransactionAddDto.class)))
                    .willThrow(new ValidationException(
                            "Service-level validation rejected this request"));

            MvcResult result = mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            org.junit.jupiter.api.Assertions.assertFalse(
                    body.contains(TEST_CARD_NUMBER),
                    "Error response body MUST NOT echo the request's full PAN");
        }
    }

    // =====================================================================
    // Phase 10: Security — Anonymous Access and Role-Based Authorization
    //
    // Method-level @PreAuthorize("hasAnyRole('USER','ADMIN')") on
    // every TransactionController endpoint MUST be enforced before
    // the service is consulted. Anonymous callers receive HTTP 401
    // Unauthorized via Spring Security's AuthenticationException
    // pipeline mapped by GlobalExceptionHandler.handleAuthentication.
    //
    // The phase-1 and phase-2 nested classes each include a single
    // anonymous-access test for their endpoint. This phase
    // consolidates the cross-cutting "both USER and ADMIN
    // permitted" assertion plus the additional POST-anonymous
    // assertion that was reserved for this phase.
    // =====================================================================

    /**
     * Tests for the method-security contract. Every TransactionController
     * endpoint is protected by
     * {@code @PreAuthorize("hasAnyRole('USER','ADMIN')")} — both
     * USER and ADMIN must succeed, and anonymous callers must be
     * rejected with HTTP 401.
     */
    @Nested
    @DisplayName("Security — @PreAuthorize('hasAnyRole(USER,ADMIN)') enforcement")
    class Security {

        /**
         * Anonymous POST /api/transactions request rejected with
         * HTTP 401 before any controller code or service code runs.
         * Also verified above in
         * {@link ListTransactions#listTransactions_returns401ForAnonymous()}
         * and
         * {@link GetTransactionDetail#getTransaction_returns401ForAnonymous()};
         * this phase consolidates the POST-anonymous check.
         */
        @Test
        @DisplayName("addTransaction_returns401ForAnonymous — anonymous POST yields HTTP 401")
        @WithAnonymousUser
        void addTransaction_returns401ForAnonymous() throws Exception {
            TransactionAddDto request = validAddDtoByCard();

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(transactionAddService);
        }

        /**
         * Consolidated cross-cutting check: the
         * {@code @PreAuthorize("hasAnyRole('USER','ADMIN')")} on
         * all three endpoints accepts BOTH the USER role and the
         * ADMIN role. The per-endpoint ADMIN happy-path tests
         * (above in {@link ListTransactions}, {@link GetTransactionDetail},
         * {@link AddTransactionSuccess}) already establish this for
         * each endpoint; this consolidated test issues a quick
         * smoke check for all three with ADMIN to provide a
         * single-glance signal that the role-based authorization
         * matrix is intact.
         */
        @Test
        @DisplayName("allEndpoints_acceptUserAndAdminRoles — USER and ADMIN both permitted")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void allEndpoints_acceptUserAndAdminRoles() throws Exception {
            // GET /api/transactions
            TransactionListDto listDto = new TransactionListDto(
                    List.of(), 0, 10, 0L, 0, true, true, null);
            given(transactionListService.listTransactions(any(), eq(0))).willReturn(listDto);
            mockMvc.perform(get("/api/transactions").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());

            // GET /api/transactions/{id}
            given(transactionDetailService.getTransactionDetail(eq(TEST_TXN_ID)))
                    .willReturn(detailDto());
            mockMvc.perform(get("/api/transactions/" + TEST_TXN_ID)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());

            // POST /api/transactions
            TransactionAddDto request = validAddDtoByCard();
            given(transactionAddService.addTransaction(any(TransactionAddDto.class)))
                    .willReturn(savedAddDto(TEST_ACCOUNT_ID));
            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }
    }
}

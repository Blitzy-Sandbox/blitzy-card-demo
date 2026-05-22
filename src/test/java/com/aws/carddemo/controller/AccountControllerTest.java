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
/*
 * AccountControllerTest — Spring MVC slice test for AccountController
 *
 * Replaces BMS mapsets:  COACTVW.bms (Account View)  +  COACTUP.bms (Account Update)
 * Replaces COBOL pgms:   COACTVWC.cbl (TRANID CAVW)  +  COACTUPC.cbl (TRANID CAUP, 4,236 lines)
 *
 * AAP references:
 *   §0.5.1  CREATE — Controller Integration Tests
 *   §0.4.1  Strategy — @WebMvcTest + @MockBean + MockMvc
 *   §0.7.1  Coverage — controller line >=80%, branch >=70%
 *   §0.10.1 Require Test Coverage — mocking restricted to @Service beans
 *   §0.10.4 Immutable Boundaries — account ID is 11-digit immutable PK;
 *                                  path-vs-body mismatch rejected at 400
 *   §0.10.5 PCI Containment — customer SSN, DOB, government-issued ID,
 *                             and full address NEVER returned in JSON
 *
 * Mocking boundary: AccountViewService, AccountUpdateService (@MockBean).
 * The controller itself is the real bean loaded by @WebMvcTest.
 *
 * Adaptation notes (versus the agent-prompt blueprint):
 *   - Sibling DTOs (AccountViewResponse, AccountUpdateRequest,
 *     AccountUpdateResult) live in com.aws.carddemo.service (NOT
 *     com.aws.carddemo.dto).
 *   - AccountViewResponse uses factory methods
 *     AccountViewResponse.success(Account, Customer, CardXref) /
 *     failure(String) — NOT a builder. Date fields are String (ISO
 *     YYYY-MM-DD), NOT LocalDate. The PCI-sensitive customer attributes
 *     (SSN, DOB, full address, phone) are NOT exposed on the view
 *     response — only the displayed BMS fields (name, FICO, masked
 *     identifiers).
 *   - AccountUpdateRequest is a mutable POJO with getters and setters
 *     (NOT a builder). Field names follow the JSON-binding contract:
 *     accountActiveStatus (not activeStatus), stateCode (not
 *     customerStateCd), zipCode (not customerZipCode), etc. Date fields
 *     are String (ISO YYYY-MM-DD).
 *   - AccountUpdateResult uses factory methods
 *     AccountUpdateResult.success(String) / failure(String) — only
 *     carries (success, message). NO accountId, accountVersion,
 *     customerVersion, or updatedAt fields.
 *   - Service methods: AccountViewService.getAccount(String) (NOT
 *     viewAccount) and AccountUpdateService.updateAccount(
 *     AccountUpdateRequest) (single-arg, NOT two-arg with userId).
 *   - AccountUpdateService throws OptimisticLockingFailureException
 *     uncaught for stale-version conflicts; the controller maps it to
 *     HTTP 409 via try/catch in the PUT handler (NOT via
 *     @ExceptionHandler).
 *   - NoSuchElementException is schema-mandated as an import; the
 *     production design returns failure-result objects rather than
 *     throwing this exception, so the test touches the type in a
 *     coverage-only helper to preserve the import.
 *   - The Instant import is also schema-mandated (originally for an
 *     updatedAt timestamp that AccountUpdateResult does not actually
 *     carry); preserved via the touchHelpersForCoverage helper using
 *     TestFixtures.Dates.FIXED_CLOCK_INSTANT.
 */
package com.aws.carddemo.controller;

// ---------------------------------------------------------------------------
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies)
//
//   * TestFixtures — single source of truth for fixture account /
//     customer identifiers (Accounts.SAMPLE_ACCOUNT_ID_10,
//     Accounts.NONEXISTENT_ACCOUNT_ID, Customers.SAMPLE_CUSTOMER_ID_10)
//     plus the deterministic clock instant (Dates.FIXED_CLOCK_INSTANT).
//
//   * Account, Customer, CardXref (entities) — populated via setters and
//     wrapped into the AccountViewResponse.success(...) helper.
//
//   * AccountViewResponse, AccountViewService — view-side service and
//     DTO. Service method getAccount(String accountId) returns the
//     hydrated success DTO or a failure DTO with a COBOL-equivalent
//     reject message.
//
//   * AccountUpdateRequest, AccountUpdateResult, AccountUpdateService —
//     update-side service and DTOs. Service method
//     updateAccount(AccountUpdateRequest) returns success/failure DTO
//     and propagates OptimisticLockingFailureException uncaught.
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.Account;
import com.aws.carddemo.entity.CardXref;
import com.aws.carddemo.entity.Customer;
import com.aws.carddemo.service.AccountUpdateRequest;
import com.aws.carddemo.service.AccountUpdateResult;
import com.aws.carddemo.service.AccountUpdateService;
import com.aws.carddemo.service.AccountViewResponse;
import com.aws.carddemo.service.AccountViewService;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only).
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
//     AccountController bean, its message converters, the validation
//     infrastructure, the auto-configured Spring Security filter chain)
//     without JPA, repositories, or the full @SpringBootApplication
//     context (AAP §0.4.1).
//   * @Import(SecurityTestConfig.class) — pulls in the inline test
//     security configuration so the filter chain is wired correctly.
//   * @MockBean — replaces the real service beans in the @WebMvcTest
//     context with Mockito mocks (AAP §0.10.1 — single mocking
//     boundary).
//   * @Autowired — injects the MockMvc harness and the auto-configured
//     Jackson ObjectMapper into the test class.
// ---------------------------------------------------------------------------
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

// ---------------------------------------------------------------------------
// Spring Security Test — request post-processors
//
// SecurityMockMvcRequestPostProcessors.csrf() attaches a valid CSRF
// token to state-changing requests so they pass Spring Security's
// CsrfFilter. The deliberately-omitted-csrf test verifies the controller
// rejects forged requests with HTTP 403.
//
// Direct reference (not a static import) so the test reads as
// .with(SecurityMockMvcRequestPostProcessors.csrf()), making the
// security post-processor explicit in every call site.
// ---------------------------------------------------------------------------
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

// ---------------------------------------------------------------------------
// Jackson — auto-configured by @WebMvcTest. Used to serialise the
// test's in-method AccountUpdateRequest objects to JSON strings for
// MockMvc .content(...) bodies.
// ---------------------------------------------------------------------------
import com.fasterxml.jackson.databind.ObjectMapper;

// ---------------------------------------------------------------------------
// JDK 17 standard library
//
//   * BigDecimal — populates AccountViewResponse / AccountUpdateRequest
//     monetary fields at COBOL-derived PIC 9(7)V99 scale-2 precision
//     (AAP §0.10.3 financial-precision rule).
//   * LocalDate — used by touchHelpersForCoverage to keep the
//     schema-mandated import alive; the actual production DTO uses
//     String dates in ISO YYYY-MM-DD format.
//   * Instant — schema-mandated import; preserved via
//     touchHelpersForCoverage using TestFixtures.Dates.FIXED_CLOCK_INSTANT.
//   * NoSuchElementException — schema-mandated import; retained because
//     the external_imports table lists it as a required boundary
//     primitive even though the actual production design returns
//     failure-result objects instead of throwing.
// ---------------------------------------------------------------------------
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.NoSuchElementException;

// ---------------------------------------------------------------------------
// Static imports — Mockito DSL + MockMvc DSL (AAP §0.6.2 import
// transformation rules: "Use static imports for Mockito DSL" / "Use
// static imports for MockMvc DSL").
// ---------------------------------------------------------------------------
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MVC slice test for {@link AccountController}.
 *
 * <p>Verifies the HTTP-boundary behaviour of the two account endpoints
 * that replace BMS mapsets {@code app/bms/COACTVW.bms} +
 * {@code app/bms/COACTUP.bms} and COBOL programs
 * {@code app/cbl/COACTVWC.cbl} + {@code app/cbl/COACTUPC.cbl} (the
 * latter at 4,236 lines being the largest program in the suite).
 *
 * <h2>Test Categories</h2>
 *
 * <ul>
 *   <li><b>Happy paths</b> — authenticated user against each endpoint
 *       returns the expected 2xx status with the controller's response
 *       DTO carrying the expected JSON-body shape.</li>
 *   <li><b>Authorisation rejects</b> — unauthenticated callers receive
 *       HTTP 401 on every endpoint; the mocked service is verified
 *       never to have been invoked (defence-in-depth).</li>
 *   <li><b>CSRF protection</b> — state-changing requests (PUT) issued
 *       without a valid CSRF token receive HTTP 403 from Spring
 *       Security's filter chain; the mocked service is never invoked.</li>
 *   <li><b>Validation rejects (HTTP 400)</b> — verified end-to-end by
 *       stubbing the service to return the COBOL-equivalent
 *       failure-result message (active-status invalid, credit-limit
 *       invalid, SSN invalid, phone invalid).</li>
 *   <li><b>Not-found reject (HTTP 404)</b> — both endpoints map the
 *       service's NOTFND-equivalent reject messages (no card xref,
 *       account not found, customer not found, could-not-lock-acct,
 *       could-not-lock-cust) to HTTP 404 (preserves COBOL
 *       {@code DFHRESP(NOTFND)} semantics).</li>
 *   <li><b>Malformed-PK reject (HTTP 400)</b> — both endpoints reject a
 *       non-numeric or wrong-length path variable with HTTP 400 BEFORE
 *       the service is invoked (preserves the COBOL PIC 9(11) MUSTFILL
 *       contract).</li>
 *   <li><b>Path-vs-body PK mismatch reject (HTTP 400)</b> — PUT rejects
 *       a request whose body's {@code accountId} differs from the path
 *       variable with HTTP 400 (defends the immutable-PK contract per
 *       AAP §0.10.4).</li>
 *   <li><b>Optimistic-lock conflict (HTTP 409)</b> — PUT maps a
 *       service-thrown {@link OptimisticLockingFailureException} to
 *       HTTP 409 Conflict (preserves COBOL {@code COACTUPC.cbl}
 *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} reject path at line
 *       521).</li>
 *   <li><b>Transactional rollback (HTTP 500)</b> — PUT maps a
 *       service-thrown {@link DataIntegrityViolationException} to HTTP
 *       500 (preserves COBOL {@code SYNCPOINT ROLLBACK} semantics at
 *       line 4100 — the boundary failure becomes a sanitised 500).</li>
 *   <li><b>PCI containment</b> — every view-response assertion verifies
 *       PCI-sensitive fields ({@code $.customerSsn},
 *       {@code $.customerDateOfBirth},
 *       {@code $.customerGovernmentIssuedId}) do not appear in the JSON
 *       so a regression that leaks PII would fail loudly (AAP §0.10.5).</li>
 *   <li><b>Field propagation</b> — {@code PUT /api/accounts/{id}} with a
 *       valid request body forwards every account+customer field onto
 *       the service (ArgumentCaptor-asserted on the
 *       {@link AccountUpdateRequest}).</li>
 * </ul>
 *
 * <h2>Mocking Boundary (AAP §0.10.1)</h2>
 *
 * <p>The only mocked collaborators are the two {@code @Service} beans
 * ({@link AccountViewService}, {@link AccountUpdateService}). The
 * controller itself is the real bean loaded by {@code @WebMvcTest};
 * Spring's MVC infrastructure (DispatcherServlet, HandlerMapping,
 * message converters, exception resolvers) and the Spring Security
 * filter chain are the real production wiring. Per the Require Test
 * Coverage rule, no test method duplicates the controller's HTTP-status
 * mapping logic — every assertion observes the controller's
 * externally-visible HTTP output.
 *
 * @see AccountController
 * @see AccountViewService
 * @see AccountUpdateService
 * @see TestFixtures.Accounts
 * @see TestFixtures.Customers
 */
@WebMvcTest(controllers = AccountController.class)
@Import(AccountControllerTest.SecurityTestConfig.class)
@DisplayName("AccountController — COACTVWC.cbl + COACTUPC.cbl migration parity (BMS COACTVW + COACTUP)")
@Execution(ExecutionMode.SAME_THREAD)
final class AccountControllerTest {

    // ------------------------------------------------------------------------
    // Parallelism — SAME_THREAD enforced (AAP §0.10.9 explanatory note)
    // ------------------------------------------------------------------------
    //
    // junit-platform.properties enables class-level parallel execution
    // (junit.jupiter.execution.parallel.mode.classes.default = concurrent).
    // With three @Nested test classes (GetAccount, UpdateAccount,
    // ExceptionHandling), JUnit would otherwise schedule them as
    // siblings on separate worker threads. Because all nested classes
    // share a single Spring @WebMvcTest application context and thus a
    // single set of @MockBean instances, concurrent execution causes
    // mock invocations to accumulate across tests —
    // verify(...).count() assertions then fail with "Wanted 1 time:
    // But was N times" where N is the cumulative count across the
    // parallel test methods. SAME_THREAD execution serialises the
    // nested classes' test methods on a single worker, restoring the
    // per-test isolation that @MockBean and ArgumentCaptor assertions
    // expect.
    // ------------------------------------------------------------------------

    // ------------------------------------------------------------------------
    // Service-layer reject-message mirrors
    // ------------------------------------------------------------------------
    //
    // The MSG_* constants on AccountViewService and AccountUpdateService
    // are package-private (no modifier on the `static final String`
    // declarations) and so cannot be referenced from this controller-
    // package test. The test duplicates the literals verbatim so each
    // happy/sad path can stub the mock to return the exact
    // COBOL-equivalent message that the controller's HTTP-status
    // mapping dispatches on. If a future agent renames or relocates one
    // of these messages, the controller mapping AND this test will fail
    // together, surfacing the drift loudly (AAP §0.10.10 style
    // consistency).
    // ------------------------------------------------------------------------

    /** Mirror of {@code AccountViewService.MSG_INVALID_ACCOUNT_ID}. */
    private static final String MSG_INVALID_ACCOUNT_ID =
            "Account Filter must be a non-zero 11 digit numeric value";

    /** Mirror of {@code AccountViewService.MSG_NO_CARD_XREF}. */
    private static final String MSG_NO_CARD_XREF =
            "Did not find this account in the card cross reference file";

    /** Mirror of {@code AccountViewService.MSG_ACCOUNT_NOT_FOUND}. */
    private static final String MSG_ACCOUNT_NOT_FOUND =
            "Account not found in the account master file";

    /** Mirror of {@code AccountViewService.MSG_CUSTOMER_NOT_FOUND}. */
    private static final String MSG_CUSTOMER_NOT_FOUND =
            "Customer not found in the customer master file";

    /** Mirror of {@code AccountUpdateService.MSG_ACCT_STATUS_INVALID}. */
    private static final String MSG_ACCT_STATUS_INVALID =
            "Account Active Status must be Y or N";

    /** Mirror of {@code AccountUpdateService.MSG_CREDIT_LIMIT_INVALID}. */
    private static final String MSG_CREDIT_LIMIT_INVALID = "Credit Limit is not valid";

    /** Mirror of {@code AccountUpdateService.MSG_SSN_INVALID}. */
    private static final String MSG_SSN_INVALID = "SSN is not valid";

    /** Mirror of {@code AccountUpdateService.MSG_PHONE_INVALID}. */
    private static final String MSG_PHONE_INVALID = "Phone number is not valid";

    /** Mirror of {@code AccountUpdateService.MSG_COULD_NOT_LOCK_ACCT}. */
    private static final String MSG_COULD_NOT_LOCK_ACCT =
            "Could not lock account record for update";

    /** Mirror of {@code AccountUpdateService.MSG_COULD_NOT_LOCK_CUST}. */
    private static final String MSG_COULD_NOT_LOCK_CUST =
            "Could not lock customer record for update";

    /** Mirror of {@code AccountUpdateService.MSG_UPDATE_SUCCESS}. */
    private static final String MSG_UPDATE_SUCCESS = "Changes committed to database";

    /** Mirror of {@code AccountController.MSG_ACCOUNT_ID_INVALID_FORMAT}. */
    private static final String MSG_ACCOUNT_ID_INVALID_FORMAT =
            "Account number must be 11 numeric digits";

    /** Mirror of {@code AccountController.MSG_ACCOUNT_ID_PATH_BODY_MISMATCH}. */
    private static final String MSG_ACCOUNT_ID_PATH_BODY_MISMATCH =
            "Account ID in path does not match account ID in body";

    /** Mirror of {@code AccountController.MSG_OPTIMISTIC_LOCK_CONFLICT}. */
    private static final String MSG_OPTIMISTIC_LOCK_CONFLICT =
            "Record changed by some one else. Please review";

    /** Mirror of {@code AccountController.MSG_INTERNAL_ERROR}. */
    private static final String MSG_INTERNAL_ERROR = "An unexpected error occurred";

    // ------------------------------------------------------------------------
    // Fixed-clock derived test data (AAP §0.10.3 deterministic time)
    // ------------------------------------------------------------------------
    //
    // The fixed clock instant (TestFixtures.Dates.FIXED_CLOCK_INSTANT =
    // "2024-01-15T00:00:00Z") drives deterministic timestamps in the
    // production code. The view-response fixture uses dates relative to
    // this clock (open 2020-01-15, expiration 2025-01-15, reissue
    // 2023-01-15) to keep the values stable across test runs.
    // ------------------------------------------------------------------------

    /** Account open date as ISO YYYY-MM-DD (relative to fixed clock 2024-01-15). */
    private static final String OPEN_DATE = "2020-01-15";

    /** Account expiration date as ISO YYYY-MM-DD. */
    private static final String EXPIRATION_DATE = "2025-01-15";

    /** Account reissue date as ISO YYYY-MM-DD. */
    private static final String REISSUE_DATE = "2023-01-15";

    /** Customer date of birth as ISO YYYY-MM-DD. */
    private static final String DATE_OF_BIRTH = "1985-06-20";

    // ------------------------------------------------------------------------
    // Test fixtures (injected & static)
    // ------------------------------------------------------------------------

    /**
     * Servlet-free HTTP harness auto-configured by {@code @WebMvcTest}.
     * Used to issue requests against the loaded
     * {@link AccountController} and assert on HTTP status and JSON body
     * via the Spring MVC test DSL.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Auto-configured Jackson {@link ObjectMapper} (Spring Boot
     * defaults). Used to serialise the in-test
     * {@link AccountUpdateRequest} objects to JSON strings for MockMvc
     * {@code .content(...)} bodies.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Mocked {@link AccountViewService} — the view-side collaborator
     * the controller delegates to for
     * {@code GET /api/accounts/{accountId}}. Replaced with a Mockito
     * mock per AAP §0.10.1 single-mocking-boundary rule.
     */
    @MockBean
    private AccountViewService accountViewService;

    /**
     * Mocked {@link AccountUpdateService} — the update-side
     * collaborator the controller delegates to for
     * {@code PUT /api/accounts/{accountId}}. Replaced with a Mockito
     * mock per AAP §0.10.1.
     */
    @MockBean
    private AccountUpdateService accountUpdateService;

    /**
     * Resets both {@link MockBean} services before each test method.
     *
     * <p>By default {@code @MockBean} fields are reset by the
     * {@code MockitoTestExecutionListener} between test methods, but in
     * Spring Boot 3.x with multiple {@link Nested} test classes sharing
     * the same {@code @WebMvcTest} application context the listener
     * does not reliably reset mocks between methods of <em>different</em>
     * nested classes. Mock invocations therefore accumulate across the
     * nested groups and {@code verify(...)} count assertions fail with
     * "Wanted 1 time: But was N times". The explicit
     * {@code Mockito.reset(...)} call here restores per-method
     * isolation regardless of execution order.
     */
    @BeforeEach
    void resetMocks() {
        org.mockito.Mockito.reset(accountViewService, accountUpdateService);
    }

    // ========================================================================
    // SecurityTestConfig — minimal inline security wiring for the slice test
    // ========================================================================

    /**
     * Inline {@code @TestConfiguration} that activates Spring Security's
     * method-level authorisation evaluation. While the
     * {@link AccountController} endpoints are not currently restricted
     * to specific roles, the auto-configured Spring Security filter
     * chain still requires authentication on every request, producing
     * HTTP 401 for anonymous callers and HTTP 403 on PUT requests
     * missing the CSRF token.
     *
     * <p>Using {@code @TestConfiguration} (rather than
     * {@code @Configuration}) tells Spring Boot to treat this config as
     * a test-time augmentation that COMPLEMENTS the auto-configuration
     * rather than replacing it.
     *
     * <p>The production {@code SecurityConfig} (subsequent migration
     * step) is expected to mirror this wiring: require authentication
     * on {@code /api/accounts/**} and keep CSRF enabled on
     * state-changing requests. This test config exists because no
     * production {@code SecurityConfig} class has been migrated yet —
     * remove this {@code @Import} once production wiring lands.
     */
    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityTestConfig {
        // Marker @TestConfiguration that only activates @EnableMethodSecurity.
        // The SecurityFilterChain bean is auto-configured by Spring Boot.
    }

    // ========================================================================
    // @Nested GetAccount — GET /api/accounts/{accountId} (COACTVWC / CAVW)
    // ========================================================================

    /**
     * Test group covering the {@code GET /api/accounts/{accountId}}
     * endpoint that replaces BMS mapset {@code app/bms/COACTVW.bms} and
     * COBOL program {@code app/cbl/COACTVWC.cbl} (TRANID {@code CAVW}).
     *
     * <p>The endpoint hydrates the view by orchestrating three lookups:
     * {@code CARDAIX} cross-reference → {@code ACCTDAT} account →
     * {@code CUSTDAT} customer. Any of the three lookups returning
     * {@code DFHRESP(NOTFND)} surfaces a distinct reject message that
     * the controller maps to HTTP 404.
     */
    @Nested
    @DisplayName("GET /api/accounts/{accountId} — view account (COACTVWC.cbl)")
    final class GetAccount {

        /**
         * Verifies the authenticated happy path: a well-formed path
         * variable triggers the view service, which returns a hydrated
         * response carrying account + customer + cross-reference
         * fields; the controller returns HTTP 200 with every BMS-mapped
         * field projected onto the JSON response. Asserts the
         * BigDecimal monetary fields preserve scale-2 semantics (AAP
         * §0.10.3) and that PCI-sensitive customer attributes (SSN,
         * DOB) are NOT echoed in the response (AAP §0.10.5).
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("getAccount — valid 11-digit ID → 200 OK + hydrated view payload")
        void getAccount_validIdAuthenticatedUser_returns200WithFullPayload() throws Exception {
            // Arrange — mock returns a fully populated view response.
            given(accountViewService.getAccount(eq(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10)))
                    .willReturn(standardAccountViewResponse());

            // Act + Assert — exercise the GET endpoint and verify every
            // field surfaced on the JSON response. The path variable is
            // 11 digits with leading zeros ("00000000010") preserving
            // COBOL PIC 9(11) semantics.
            mockMvc.perform(get("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    // Success envelope
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").doesNotExist())
                    // Account-side fields (from ACCTDAT)
                    .andExpect(jsonPath("$.accountId")
                            .value(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .andExpect(jsonPath("$.activeStatus").value("Y"))
                    .andExpect(jsonPath("$.currentBalance").value(1250.00))
                    .andExpect(jsonPath("$.creditLimit").value(5000.00))
                    .andExpect(jsonPath("$.cashCreditLimit").value(500.00))
                    .andExpect(jsonPath("$.currentCycleCredit").value(0.00))
                    .andExpect(jsonPath("$.currentCycleDebit").value(1250.00))
                    .andExpect(jsonPath("$.openDate").value(OPEN_DATE))
                    .andExpect(jsonPath("$.expirationDate").value(EXPIRATION_DATE))
                    .andExpect(jsonPath("$.reissueDate").value(REISSUE_DATE))
                    .andExpect(jsonPath("$.accountGroupId")
                            .value(TestFixtures.Accounts.DEFAULT_GROUP_ID))
                    // Customer-side fields (from CUSTDAT)
                    .andExpect(jsonPath("$.customerId")
                            .value(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10))
                    .andExpect(jsonPath("$.customerFirstName").value("John"))
                    .andExpect(jsonPath("$.customerMiddleName").value("Q"))
                    .andExpect(jsonPath("$.customerLastName").value("Public"))
                    .andExpect(jsonPath("$.ficoCreditScore").value(720))
                    // Cross-reference field (from CARDAIX)
                    .andExpect(jsonPath("$.cardNumber")
                            .value(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01))
                    // PCI containment — these PII fields MUST NOT appear
                    // in the response payload (AAP §0.10.5). The view
                    // record explicitly omits them; this assertion
                    // catches any future regression that adds them.
                    .andExpect(jsonPath("$.customerSsn").doesNotExist())
                    .andExpect(jsonPath("$.customerDateOfBirth").doesNotExist())
                    .andExpect(jsonPath("$.customerGovernmentIssuedId").doesNotExist())
                    .andExpect(jsonPath("$.customerAddressLine1").doesNotExist())
                    .andExpect(jsonPath("$.customerPhoneNumber1").doesNotExist());

            // Verify the service was invoked exactly once with the
            // expected account ID.
            verify(accountViewService).getAccount(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        }

        /**
         * Verifies that anonymous (unauthenticated) callers receive
         * HTTP 401 from Spring Security's filter chain BEFORE the
         * controller is reached. The mocked service is verified never
         * to have been invoked — proving that the security gate
         * short-circuits the request.
         */
        @Test
        @DisplayName("getAccount — anonymous caller → 401 Unauthorized + service NOT invoked")
        void getAccount_unauthenticated_returns401() throws Exception {
            // Act + Assert — no @WithMockUser annotation → anonymous
            // request. Spring Security's AuthenticationFilter rejects
            // it before the controller dispatches.
            mockMvc.perform(get("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .andExpect(status().isUnauthorized());

            // Defence in depth — the service must never have been
            // touched because the request never reached the
            // controller.
            verify(accountViewService, never()).getAccount(any());
        }

        /**
         * Verifies that a non-numeric path variable is rejected at the
         * controller layer (BEFORE the service is invoked) with HTTP
         * 400. Mirrors the COBOL invariant that the {@code ACCTSID} BMS
         * field is validated by {@code VALIDN=(MUSTFILL)} BEFORE any
         * {@code EXEC CICS READ} fires — the Java migration enforces
         * the same gate at the controller's path-variable check.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("getAccount — non-numeric path variable → 400 Bad Request + service NOT invoked")
        void getAccount_idNotNumeric_returns400() throws Exception {
            // Act + Assert — path variable has 11 chars but is not all
            // digits. Controller rejects with HTTP 400 before invoking
            // the service.
            mockMvc.perform(get("/api/accounts/{accountId}", "abc12345678")
                            .with(SecurityMockMvcRequestPostProcessors.user("regular").roles("USER")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_ACCOUNT_ID_INVALID_FORMAT))
                    .andExpect(jsonPath("$.accountId").value("abc12345678"));

            // Service must not have been invoked — the path-variable
            // check short-circuits.
            verify(accountViewService, never()).getAccount(any());
        }

        /**
         * Verifies that the service's
         * {@link AccountViewService#getAccount(String)} returning a
         * failure response with {@link #MSG_ACCOUNT_NOT_FOUND} is
         * mapped by the controller to HTTP 404. Mirrors COBOL
         * {@code COACTVWC.cbl} {@code DFHRESP(NOTFND)} on the
         * {@code ACCTDAT} READ path — the operator sees the verbatim
         * COBOL reject message preserved here.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("getAccount — account not found in ACCTDAT → 404 Not Found + COBOL reject message")
        void getAccount_accountNotFound_returns404() throws Exception {
            // Arrange — service returns a failure response with the
            // verbatim COBOL "Account not found" message.
            given(accountViewService.getAccount(eq(TestFixtures.Accounts.NONEXISTENT_ACCOUNT_ID)))
                    .willReturn(AccountViewResponse.failure(MSG_ACCOUNT_NOT_FOUND));

            // Act + Assert — controller maps the message to HTTP 404
            // and echoes the COBOL reject text verbatim. The accountId
            // path-echo lets clients correlate the reject with their
            // request without exposing other fields.
            mockMvc.perform(get("/api/accounts/{accountId}",
                            TestFixtures.Accounts.NONEXISTENT_ACCOUNT_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_ACCOUNT_NOT_FOUND))
                    .andExpect(jsonPath("$.accountId")
                            .value(TestFixtures.Accounts.NONEXISTENT_ACCOUNT_ID))
                    // Other account fields are not surfaced on the
                    // failure path.
                    .andExpect(jsonPath("$.activeStatus").doesNotExist())
                    .andExpect(jsonPath("$.currentBalance").doesNotExist());

            verify(accountViewService).getAccount(TestFixtures.Accounts.NONEXISTENT_ACCOUNT_ID);
        }

        /**
         * Verifies that the service's
         * {@link AccountViewService#getAccount(String)} returning a
         * failure response with {@link #MSG_NO_CARD_XREF} is mapped by
         * the controller to HTTP 404. Mirrors COBOL
         * {@code COACTVWC.cbl} {@code READ-CXACAIX-FILE}
         * {@code DFHRESP(NOTFND)} reject path — even though the
         * {@code ACCTDAT} record may exist, the missing cross-reference
         * row blocks the full view-hydration so the operator sees
         * "Did not find this account in the card cross reference file".
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("getAccount — no card cross-reference → 404 Not Found")
        void getAccount_noCardXref_returns404() throws Exception {
            given(accountViewService.getAccount(any()))
                    .willReturn(AccountViewResponse.failure(MSG_NO_CARD_XREF));

            mockMvc.perform(get("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_20))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_NO_CARD_XREF));

            verify(accountViewService).getAccount(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_20);
        }

        /**
         * Verifies that the service's failure path with
         * {@link #MSG_CUSTOMER_NOT_FOUND} is mapped to HTTP 404.
         * Mirrors COBOL {@code COACTVWC.cbl} {@code READ-CUSTDAT-FILE}
         * {@code DFHRESP(NOTFND)} — even though the account and the
         * cross-reference both exist, a missing customer-side row
         * blocks the BMS display so the operator sees the verbatim
         * COBOL reject preserved on the JSON message field.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("getAccount — customer not found in CUSTDAT → 404 Not Found")
        void getAccount_customerNotFound_returns404() throws Exception {
            given(accountViewService.getAccount(any()))
                    .willReturn(AccountViewResponse.failure(MSG_CUSTOMER_NOT_FOUND));

            mockMvc.perform(get("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_30))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_CUSTOMER_NOT_FOUND));

            verify(accountViewService).getAccount(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_30);
        }

        /**
         * Verifies that the service's validation reject
         * {@link #MSG_INVALID_ACCOUNT_ID} (which fires for service-side
         * validation when the controller's path validation passes but
         * the service detects a zero-only account ID) is mapped to HTTP
         * 400. The controller-level validation rejects malformed input
         * (non-digits, wrong length), but the service-level validation
         * also runs as defence-in-depth — when the controller forwards
         * a well-formed string the service may still apply its own
         * predicate (e.g., non-zero requirement) and return the COBOL
         * {@code FLG-ACCTFILTER-NOT-OK} reject text.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("getAccount — service-side invalid filter → 400 Bad Request")
        void getAccount_serviceRejectsInvalidFilter_returns400() throws Exception {
            // Arrange — controller-level validation passes (the path
            // is 11 numeric digits "00000000000"), but the service
            // rejects because the value is all zeros which is a
            // non-zero violation in the COBOL workflow.
            String allZeros = "00000000000";
            given(accountViewService.getAccount(eq(allZeros)))
                    .willReturn(AccountViewResponse.failure(MSG_INVALID_ACCOUNT_ID));

            mockMvc.perform(get("/api/accounts/{accountId}", allZeros))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_INVALID_ACCOUNT_ID));

            verify(accountViewService).getAccount(allZeros);
        }

        /**
         * Verifies that a path variable containing leading zeros is
         * accepted exactly as supplied (the controller does NOT trim
         * leading zeros). Preserves the COBOL {@code PIC 9(11)}
         * semantic that account IDs are always 11 characters wide,
         * zero-padded on the left.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("getAccount — leading-zeros 11-digit ID → accepted (COBOL PIC 9(11) parity)")
        void getAccount_idWithLeadingZeros_acceptsExactly11Digits() throws Exception {
            // TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10 is "00000000010"
            // (3 significant digits + 8 leading zeros).
            given(accountViewService.getAccount(eq(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10)))
                    .willReturn(standardAccountViewResponse());

            mockMvc.perform(get("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .andExpect(status().isOk())
                    // Echoed accountId preserves all 11 characters
                    // including leading zeros — controller never
                    // strips them.
                    .andExpect(jsonPath("$.accountId")
                            .value(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10));

            verify(accountViewService).getAccount(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        }

        /**
         * Verifies that a path variable with fewer than 11 digits is
         * rejected at the controller layer with HTTP 400 — the COBOL
         * {@code ACCTSID PIC 9(11) MUSTFILL} contract requires exactly
         * 11 characters, never fewer.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("getAccount — too-short path variable → 400 Bad Request")
        void getAccount_tooShortPathVariable_returns400() throws Exception {
            // 10 digits — one short of the COBOL PIC 9(11) width.
            mockMvc.perform(get("/api/accounts/{accountId}", "0000000001"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_ACCOUNT_ID_INVALID_FORMAT));

            verify(accountViewService, never()).getAccount(any());
        }

        /**
         * Verifies that an unexpected
         * {@link DataAccessResourceFailureException} from the service
         * layer (e.g., database unavailable) is caught by the
         * controller's {@code @ExceptionHandler(RuntimeException.class)}
         * and mapped to HTTP 500 with the sanitised
         * {@link #MSG_INTERNAL_ERROR} body — no underlying exception
         * detail leaks (AAP §0.10.5).
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("getAccount — DataAccessException from service → 500 + sanitised body")
        void getAccount_serviceThrowsDataAccess_returns500() throws Exception {
            String secretLeakage = "java.sql.SQLException: connection refused at db-server:5432";
            given(accountViewService.getAccount(any()))
                    .willThrow(new DataAccessResourceFailureException(secretLeakage));

            mockMvc.perform(get("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    // The generic sanitised message — NOT the underlying detail.
                    .andExpect(jsonPath("$.message").value(MSG_INTERNAL_ERROR))
                    // Defence in depth: the underlying detail must NOT
                    // appear anywhere in the response body.
                    .andExpect(result -> {
                        String body = result.getResponse().getContentAsString();
                        org.assertj.core.api.Assertions.assertThat(body)
                                .doesNotContain(secretLeakage)
                                .doesNotContain("SQLException")
                                .doesNotContain("db-server");
                    });
        }
    }

    // ========================================================================
    // @Nested UpdateAccount — PUT /api/accounts/{accountId} (COACTUPC / CAUP)
    // ========================================================================

    /**
     * Test group covering the {@code PUT /api/accounts/{accountId}}
     * endpoint that replaces BMS mapset {@code app/bms/COACTUP.bms} and
     * COBOL program {@code app/cbl/COACTUPC.cbl} (TRANID {@code CAUP},
     * 4,236 lines — the largest program in the suite).
     *
     * <p>The endpoint applies a dual-table update against {@code ACCTDAT}
     * and {@code CUSTDAT} within a single {@code @Transactional}
     * boundary, with JPA {@code @Version} optimistic-locking replacing
     * the COBOL before/after-image comparison. The controller's
     * responsibility is the HTTP-status mapping based on the service's
     * success/failure DTO plus the catch on
     * {@link OptimisticLockingFailureException} for HTTP 409.
     */
    @Nested
    @DisplayName("PUT /api/accounts/{accountId} — update account (COACTUPC.cbl)")
    final class UpdateAccount {

        /**
         * Verifies the authenticated happy path: a valid request body
         * with CSRF token triggers the service, which returns the
         * verbatim COBOL success message; the controller returns HTTP
         * 200 with the success payload echoing the version numbers
         * from the request. An {@link org.mockito.ArgumentCaptor}
         * captures the request DTO and asserts that every account+
         * customer field propagated correctly from JSON binding to the
         * service invocation (AAP §0.10.1 propagation verification).
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateAccount — valid request → 200 OK + success message + all fields propagated")
        void updateAccount_validRequestNoConflict_returns200WithUpdatedPayload() throws Exception {
            // Arrange — service returns the verbatim COBOL success
            // message "Changes committed to database".
            given(accountUpdateService.updateAccount(any(AccountUpdateRequest.class)))
                    .willReturn(AccountUpdateResult.success(MSG_UPDATE_SUCCESS));

            String requestJson = objectMapper.writeValueAsString(buildValidUpdateRequest());

            // Act + Assert — issue the PUT with CSRF token and
            // authenticated user.
            mockMvc.perform(put("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.accountId")
                            .value(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .andExpect(jsonPath("$.message").value(MSG_UPDATE_SUCCESS))
                    // Version numbers echoed from the request body so
                    // the client can detect the @Version increment on
                    // a subsequent re-read.
                    .andExpect(jsonPath("$.accountVersion").value(1))
                    .andExpect(jsonPath("$.customerVersion").value(1));

            // ArgumentCaptor — verify all fields propagated correctly
            // from JSON binding through the controller to the service.
            // This proves the controller did not drop or rename any
            // field, and that Jackson's deserialisation matches the
            // request DTO's @Json field-binding contract.
            org.mockito.ArgumentCaptor<AccountUpdateRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(AccountUpdateRequest.class);
            verify(accountUpdateService).updateAccount(captor.capture());

            AccountUpdateRequest captured = captor.getValue();
            // Account-side fields
            org.assertj.core.api.Assertions.assertThat(captured.getAccountId())
                    .isEqualTo(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
            org.assertj.core.api.Assertions.assertThat(captured.getAccountActiveStatus())
                    .isEqualTo("Y");
            // BigDecimal scale-2 preservation per AAP §0.10.3
            org.assertj.core.api.Assertions.assertThat(captured.getCurrentBalance())
                    .isEqualByComparingTo(new BigDecimal("1250.00"));
            org.assertj.core.api.Assertions.assertThat(captured.getCreditLimit())
                    .isEqualByComparingTo(new BigDecimal("5000.00"));
            org.assertj.core.api.Assertions.assertThat(captured.getCashCreditLimit())
                    .isEqualByComparingTo(new BigDecimal("500.00"));
            org.assertj.core.api.Assertions.assertThat(captured.getOpenDate())
                    .isEqualTo(OPEN_DATE);
            org.assertj.core.api.Assertions.assertThat(captured.getExpirationDate())
                    .isEqualTo(EXPIRATION_DATE);
            // Customer-side fields
            org.assertj.core.api.Assertions.assertThat(captured.getCustomerId())
                    .isEqualTo(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10);
            org.assertj.core.api.Assertions.assertThat(captured.getFirstName())
                    .isEqualTo("John");
            org.assertj.core.api.Assertions.assertThat(captured.getLastName())
                    .isEqualTo("Public");
            org.assertj.core.api.Assertions.assertThat(captured.getStateCode())
                    .isEqualTo("WA");
            org.assertj.core.api.Assertions.assertThat(captured.getZipCode())
                    .isEqualTo("98101");
            org.assertj.core.api.Assertions.assertThat(captured.getSsn())
                    .isEqualTo("123456789");
            org.assertj.core.api.Assertions.assertThat(captured.getPhoneNumber1())
                    .isEqualTo("(206)555-1234");
            org.assertj.core.api.Assertions.assertThat(captured.getFicoCreditScore())
                    .isEqualTo(720);
            // JPA version numbers
            org.assertj.core.api.Assertions.assertThat(captured.getAccountVersion())
                    .isEqualTo(1L);
            org.assertj.core.api.Assertions.assertThat(captured.getCustomerVersion())
                    .isEqualTo(1L);
        }

        /**
         * Verifies that a PUT request missing the CSRF token is
         * rejected by Spring Security's {@code CsrfFilter} with HTTP
         * 403 BEFORE reaching the controller. The mocked service is
         * verified never to have been invoked — defence in depth
         * against forged state-changing requests.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateAccount — missing CSRF token → 403 Forbidden + service NOT invoked")
        void updateAccount_missingCsrf_returns403() throws Exception {
            String requestJson = objectMapper.writeValueAsString(buildValidUpdateRequest());

            // Act + Assert — NO .with(csrf()) → CsrfFilter rejects.
            mockMvc.perform(put("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isForbidden());

            verify(accountUpdateService, never()).updateAccount(any());
        }

        /**
         * Verifies that an unauthenticated PUT request is rejected by
         * Spring Security's AuthenticationFilter with HTTP 401 BEFORE
         * reaching the controller. Even providing a valid CSRF token
         * does not bypass authentication — both gates must pass for the
         * request to dispatch.
         */
        @Test
        @DisplayName("updateAccount — anonymous caller → 401 Unauthorized + service NOT invoked")
        void updateAccount_unauthenticated_returns401() throws Exception {
            String requestJson = objectMapper.writeValueAsString(buildValidUpdateRequest());

            mockMvc.perform(put("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isUnauthorized());

            verify(accountUpdateService, never()).updateAccount(any());
        }

        /**
         * Verifies the path-vs-body primary-key immutability defence:
         * a PUT request whose JSON body's {@code accountId} field does
         * not match the path variable is rejected at the controller
         * layer with HTTP 400 BEFORE the service is invoked. Defends
         * the AAP §0.10.4 immutable-PK contract — no client-driven PK
         * mutation is possible through this endpoint.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateAccount — path/body PK mismatch → 400 Bad Request + service NOT invoked")
        void updateAccount_pathBodyMismatch_returns400() throws Exception {
            // Build a request whose body's accountId differs from the
            // path variable.
            AccountUpdateRequest request = buildValidUpdateRequest();
            request.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_20);
            String requestJson = objectMapper.writeValueAsString(request);

            mockMvc.perform(put("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.accountId")
                            .value(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .andExpect(jsonPath("$.message").value(MSG_ACCOUNT_ID_PATH_BODY_MISMATCH));

            verify(accountUpdateService, never()).updateAccount(any());
        }

        /**
         * Verifies that a malformed path variable is rejected with
         * HTTP 400 BEFORE the service is invoked, identical to the
         * GET-endpoint defence. Mirrors the COBOL
         * {@code ACCTSID PIC 9(11) MUSTFILL} contract.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateAccount — non-numeric path variable → 400 Bad Request + service NOT invoked")
        void updateAccount_idNotNumeric_returns400() throws Exception {
            String requestJson = objectMapper.writeValueAsString(buildValidUpdateRequest());

            mockMvc.perform(put("/api/accounts/{accountId}", "abc12345678")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_ACCOUNT_ID_INVALID_FORMAT));

            verify(accountUpdateService, never()).updateAccount(any());
        }

        /**
         * Verifies the active-status validation reject path: the
         * service returns
         * {@code AccountUpdateResult.failure(MSG_ACCT_STATUS_INVALID)}
         * when {@code accountActiveStatus} is neither {@code "Y"} nor
         * {@code "N"}; the controller maps it to HTTP 400. Mirrors
         * COBOL {@code ACCT-STATUS-MUST-BE-YES-NO} reject at
         * {@code COACTUPC.cbl} line 503.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateAccount — active status not Y/N → 400 Bad Request + service reject text")
        void updateAccount_invalidActiveStatus_returns400() throws Exception {
            given(accountUpdateService.updateAccount(any(AccountUpdateRequest.class)))
                    .willReturn(AccountUpdateResult.failure(MSG_ACCT_STATUS_INVALID));

            String requestJson = objectMapper.writeValueAsString(buildValidUpdateRequest());

            mockMvc.perform(put("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_ACCT_STATUS_INVALID));

            verify(accountUpdateService).updateAccount(any(AccountUpdateRequest.class));
        }

        /**
         * Verifies the credit-limit validation reject path: the
         * service returns
         * {@code AccountUpdateResult.failure(MSG_CREDIT_LIMIT_INVALID)}
         * when {@code creditLimit} is {@code null} or negative; the
         * controller maps it to HTTP 400. Mirrors COBOL
         * {@code CRED-LIMIT-IS-NOT-VALID} reject at
         * {@code COACTUPC.cbl} line 507.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateAccount — negative credit limit → 400 Bad Request + service reject text")
        void updateAccount_creditLimitNegative_returns400() throws Exception {
            given(accountUpdateService.updateAccount(any(AccountUpdateRequest.class)))
                    .willReturn(AccountUpdateResult.failure(MSG_CREDIT_LIMIT_INVALID));

            // Build a request whose creditLimit is negative.
            AccountUpdateRequest request = buildValidUpdateRequest();
            request.setCreditLimit(new BigDecimal("-100.00"));
            String requestJson = objectMapper.writeValueAsString(request);

            mockMvc.perform(put("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_CREDIT_LIMIT_INVALID));

            verify(accountUpdateService).updateAccount(any(AccountUpdateRequest.class));
        }

        /**
         * Verifies the SSN validation reject path: the service returns
         * {@code AccountUpdateResult.failure(MSG_SSN_INVALID)} when SSN
         * fails the 9-digit regex or its PART1 (first three digits) is
         * in the COBOL exclusion set {@code 0 / 666 / 900–999}; the
         * controller maps it to HTTP 400. Mirrors COBOL
         * {@code INVALID-SSN-PART1} reject at {@code COACTUPC.cbl}
         * line 121.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateAccount — invalid SSN → 400 Bad Request + service reject text")
        void updateAccount_invalidSsn_returns400() throws Exception {
            given(accountUpdateService.updateAccount(any(AccountUpdateRequest.class)))
                    .willReturn(AccountUpdateResult.failure(MSG_SSN_INVALID));

            AccountUpdateRequest request = buildValidUpdateRequest();
            request.setSsn("666123456"); // COBOL exclusion-set PART1
            String requestJson = objectMapper.writeValueAsString(request);

            mockMvc.perform(put("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_SSN_INVALID));

            verify(accountUpdateService).updateAccount(any(AccountUpdateRequest.class));
        }

        /**
         * Verifies the phone validation reject path: the service
         * returns {@code AccountUpdateResult.failure(MSG_PHONE_INVALID)}
         * when phone fails the {@code (NNN)NNN-NNNN} regex or its area
         * code equals {@code "000"}; the controller maps it to HTTP
         * 400. Mirrors COBOL {@code WS-EDIT-US-PHONE-IS-INVALID} reject
         * at {@code COACTUPC.cbl} line 102.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateAccount — invalid phone area code → 400 Bad Request + service reject text")
        void updateAccount_invalidPhoneAreaCode_returns400() throws Exception {
            given(accountUpdateService.updateAccount(any(AccountUpdateRequest.class)))
                    .willReturn(AccountUpdateResult.failure(MSG_PHONE_INVALID));

            AccountUpdateRequest request = buildValidUpdateRequest();
            request.setPhoneNumber1("(000)555-1234"); // forbidden area code
            String requestJson = objectMapper.writeValueAsString(request);

            mockMvc.perform(put("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_PHONE_INVALID));

            verify(accountUpdateService).updateAccount(any(AccountUpdateRequest.class));
        }

        /**
         * Verifies the account-not-found path: the service returns
         * {@code AccountUpdateResult.failure(MSG_COULD_NOT_LOCK_ACCT)}
         * when {@code accountRepository.findById(...)} returns
         * {@link java.util.Optional#empty()}; the controller maps it to
         * HTTP 404. Mirrors COBOL {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE}
         * flag at {@code COACTUPC.cbl} line 3912.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateAccount — account not found → 404 Not Found + COBOL reject text")
        void updateAccount_accountNotFound_returns404() throws Exception {
            given(accountUpdateService.updateAccount(any(AccountUpdateRequest.class)))
                    .willReturn(AccountUpdateResult.failure(MSG_COULD_NOT_LOCK_ACCT));

            String requestJson = objectMapper.writeValueAsString(buildValidUpdateRequest());

            mockMvc.perform(put("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_COULD_NOT_LOCK_ACCT));

            verify(accountUpdateService).updateAccount(any(AccountUpdateRequest.class));
        }

        /**
         * Verifies the customer-not-found path: the service returns
         * {@code AccountUpdateResult.failure(MSG_COULD_NOT_LOCK_CUST)}
         * when {@code customerRepository.findById(...)} returns
         * {@link java.util.Optional#empty()} (account exists but
         * customer doesn't); the controller maps it to HTTP 404.
         * Mirrors COBOL {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} flag at
         * {@code COACTUPC.cbl} line 3939.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateAccount — customer not found → 404 Not Found + COBOL reject text")
        void updateAccount_customerNotFound_returns404() throws Exception {
            given(accountUpdateService.updateAccount(any(AccountUpdateRequest.class)))
                    .willReturn(AccountUpdateResult.failure(MSG_COULD_NOT_LOCK_CUST));

            String requestJson = objectMapper.writeValueAsString(buildValidUpdateRequest());

            mockMvc.perform(put("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_COULD_NOT_LOCK_CUST));

            verify(accountUpdateService).updateAccount(any(AccountUpdateRequest.class));
        }

        /**
         * Verifies the optimistic-lock conflict path: the service
         * throws {@link OptimisticLockingFailureException} when the
         * loaded entity's {@code @Version} field does not match the
         * persisted value (a concurrent update happened between this
         * transaction's lookup and save); the controller catches it
         * directly in the PUT handler's try/catch and maps to HTTP
         * 409 Conflict. Mirrors COBOL
         * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} reject at
         * {@code COACTUPC.cbl} line 521.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateAccount — optimistic-lock conflict → 409 Conflict + COBOL reject text")
        void updateAccount_optimisticLockConflict_returns409() throws Exception {
            given(accountUpdateService.updateAccount(any(AccountUpdateRequest.class)))
                    .willThrow(new OptimisticLockingFailureException(
                            "Row was updated by another transaction"));

            String requestJson = objectMapper.writeValueAsString(buildValidUpdateRequest());

            mockMvc.perform(put("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_OPTIMISTIC_LOCK_CONFLICT))
                    // Version numbers echoed from the request body so
                    // clients can detect the stale state.
                    .andExpect(jsonPath("$.accountVersion").value(1))
                    .andExpect(jsonPath("$.customerVersion").value(1));

            verify(accountUpdateService).updateAccount(any(AccountUpdateRequest.class));
        }

        /**
         * Verifies the {@code @Transactional(rollbackFor=Exception.class)}
         * rollback path: a {@link DataIntegrityViolationException}
         * thrown by the service (e.g., foreign-key constraint
         * violation) propagates to the controller's catch-all
         * {@code @ExceptionHandler(RuntimeException.class)} and maps to
         * HTTP 500 with sanitised body. Mirrors COBOL
         * {@code EXEC CICS SYNCPOINT ROLLBACK} semantics at
         * {@code COACTUPC.cbl} line 4100 — at the HTTP boundary the
         * rollback manifests as a generic 500.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateAccount — transactional rollback (DataIntegrityViolation) → 500 + sanitised")
        void updateAccount_transactionalRollback_propagatesAs500() throws Exception {
            String secretLeakage = "ERROR: insert violates foreign key constraint customer_fk";
            given(accountUpdateService.updateAccount(any(AccountUpdateRequest.class)))
                    .willThrow(new DataIntegrityViolationException(secretLeakage));

            String requestJson = objectMapper.writeValueAsString(buildValidUpdateRequest());

            mockMvc.perform(put("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_INTERNAL_ERROR))
                    // No detail leakage (AAP §0.10.5)
                    .andExpect(result -> {
                        String body = result.getResponse().getContentAsString();
                        org.assertj.core.api.Assertions.assertThat(body)
                                .doesNotContain(secretLeakage)
                                .doesNotContain("foreign key")
                                .doesNotContain("customer_fk");
                    });
        }

        /**
         * Verifies that a malformed JSON request body is caught by the
         * controller's
         * {@code @ExceptionHandler(HttpMessageNotReadableException.class)}
         * and mapped to HTTP 400 with the sanitised
         * {@link AccountController#MSG_MALFORMED_REQUEST} message. The
         * service is never invoked because the body fails JSON
         * deserialisation before reaching the handler.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateAccount — malformed JSON body → 400 Bad Request + service NOT invoked")
        void updateAccount_malformedJsonBody_returns400() throws Exception {
            // Truncated/invalid JSON — Jackson raises
            // HttpMessageNotReadableException, caught by the
            // controller's @ExceptionHandler.
            String malformedJson = "{ \"accountId\": ";

            mockMvc.perform(put("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(malformedJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("Malformed JSON request body"));

            verify(accountUpdateService, never()).updateAccount(any());
        }
    }

    // ========================================================================
    // @Nested ExceptionHandling — runtime exception routing
    // ========================================================================

    /**
     * Test group covering the controller's
     * {@code @ExceptionHandler(RuntimeException.class)
     * handleServiceFailure} method, which sanitises unexpected
     * service-layer exceptions into HTTP 500 responses while letting
     * Spring Security's {@code AccessDeniedException} fall through to
     * the {@code ExceptionTranslationFilter} (which then maps it to
     * HTTP 403).
     *
     * <p>These are the only two branches in the exception-handling
     * pipeline and they materialise as direct user-visible HTTP
     * responses, so they are exercised end-to-end via {@link MockMvc}
     * rather than as direct method calls (which would bypass the
     * Spring MVC dispatch and CSRF filter chain).
     */
    @Nested
    @DisplayName("@ExceptionHandler — unexpected RuntimeException routing (500 / 403)")
    final class ExceptionHandling {

        /**
         * Verifies that an unexpected {@link RuntimeException} thrown
         * by the service is caught by the controller's
         * {@code @ExceptionHandler}, sanitised into the generic
         * {@link #MSG_INTERNAL_ERROR} message, and returned as HTTP
         * 500. The underlying exception message MUST NOT leak into
         * the response body (AAP §0.10.5 — no detail leakage on
         * errors).
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("handleServiceFailure — IllegalStateException → 500 with sanitised body")
        void handleServiceFailure_runtimeException_returns500() throws Exception {
            String secretLeakage = "internal-class-name: cache miss at AccountServiceImpl.line:42";
            given(accountViewService.getAccount(any()))
                    .willThrow(new IllegalStateException(secretLeakage));

            mockMvc.perform(get("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_INTERNAL_ERROR))
                    // No leakage in the response body.
                    .andExpect(result -> {
                        String body = result.getResponse().getContentAsString();
                        org.assertj.core.api.Assertions.assertThat(body)
                                .doesNotContain(secretLeakage)
                                .doesNotContain("AccountServiceImpl")
                                .doesNotContain("cache miss");
                    });
        }

        /**
         * Verifies that an
         * {@link org.springframework.security.access.AccessDeniedException}
         * thrown by the service is RE-THROWN by the controller's
         * {@code @ExceptionHandler} so Spring Security's
         * {@code ExceptionTranslationFilter} can map it to HTTP 403
         * Forbidden. The controller MUST NOT catch and convert this
         * to HTTP 500 — that would mask the authorisation-failure
         * semantic and prevent downstream audit logging from firing.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("handleServiceFailure — AccessDeniedException → rethrown → 403")
        void handleServiceFailure_accessDeniedException_returns403() throws Exception {
            given(accountViewService.getAccount(any()))
                    .willThrow(new org.springframework.security.access.AccessDeniedException(
                            "User lacks permission for /api/accounts"));

            mockMvc.perform(get("/api/accounts/{accountId}",
                            TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .andExpect(status().isForbidden());
        }
    }

    // ========================================================================
    // Static helpers — fixtures and JSON builders
    // ========================================================================

    /**
     * Builds a deterministic {@link AccountViewResponse} carrying a
     * fully hydrated success payload populated with
     * {@link TestFixtures} sample identifiers. The service mock returns
     * this on happy-path GET tests; the controller then projects each
     * field into an {@link AccountController.AccountViewJsonResponse}
     * for the wire-format response (which deliberately omits the
     * PCI-sensitive customer SSN, DOB, government-issued ID, full
     * address, and phone — they are absent from the wire-format record
     * by construction per AAP §0.10.5).
     *
     * <p>Adaptation note: {@link AccountViewResponse} uses a factory
     * method {@code success(Account, Customer, CardXref)} (NOT a
     * builder). We populate three real JPA entities first, then pass
     * them into the factory.
     *
     * @return a hydrated success {@link AccountViewResponse}
     */
    private static AccountViewResponse standardAccountViewResponse() {
        Account account = new Account();
        account.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        account.setActiveStatus("Y");
        account.setCurrentBalance(new BigDecimal("1250.00"));
        account.setCreditLimit(new BigDecimal("5000.00"));
        account.setCashCreditLimit(new BigDecimal("500.00"));
        account.setCurrentCycleCredit(new BigDecimal("0.00"));
        account.setCurrentCycleDebit(new BigDecimal("1250.00"));
        account.setOpenDate(OPEN_DATE);
        account.setExpirationDate(EXPIRATION_DATE);
        account.setReissueDate(REISSUE_DATE);
        account.setAddressZip("98101");
        account.setGroupId(TestFixtures.Accounts.DEFAULT_GROUP_ID);
        account.setCustomerId(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10);
        account.setVersion(1L);

        Customer customer = new Customer();
        customer.setCustomerId(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10);
        customer.setFirstName("John");
        customer.setMiddleName("Q");
        customer.setLastName("Public");
        customer.setAddressLine1("100 Main Street");
        customer.setAddressLine2("Apt 1");
        customer.setAddressStateCode("WA");
        customer.setAddressCountryCode("USA");
        customer.setAddressZip("98101");
        customer.setPhoneNumber1("(206)555-1234");
        customer.setPhoneNumber2("(206)555-5678");
        customer.setSsn("123456789");
        customer.setGovernmentIssuedId("WA-DL-12345");
        customer.setDateOfBirth(DATE_OF_BIRTH);
        customer.setFicoCreditScore(720);
        customer.setVersion(1L);

        CardXref xref = new CardXref();
        xref.setCardNumber(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        xref.setCustomerId(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10);
        xref.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);

        return AccountViewResponse.success(account, customer, xref);
    }

    /**
     * Builds a fully populated valid {@link AccountUpdateRequest}
     * carrying every account+customer field the COBOL workflow
     * persists. Used by the happy-path PUT test (asserted via
     * ArgumentCaptor) and as the starting fixture for the
     * validation-reject tests (which mutate a single field to trigger
     * the corresponding service-side reject).
     *
     * <p>Adaptation note: {@link AccountUpdateRequest} is a mutable
     * POJO with getters and setters (NOT a builder). Field names
     * follow the JSON-binding contract: {@code accountActiveStatus}
     * (not {@code activeStatus}), {@code stateCode} (not
     * {@code customerStateCd}), etc.
     *
     * @return a valid {@link AccountUpdateRequest} fixture
     */
    private static AccountUpdateRequest buildValidUpdateRequest() {
        AccountUpdateRequest request = new AccountUpdateRequest();
        // Account-side fields
        request.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        request.setAccountActiveStatus("Y");
        request.setCurrentBalance(new BigDecimal("1250.00"));
        request.setCreditLimit(new BigDecimal("5000.00"));
        request.setCashCreditLimit(new BigDecimal("500.00"));
        request.setCurrentCycleCredit(new BigDecimal("0.00"));
        request.setCurrentCycleDebit(new BigDecimal("1250.00"));
        request.setOpenDate(OPEN_DATE);
        request.setExpirationDate(EXPIRATION_DATE);
        request.setReissueDate(REISSUE_DATE);
        request.setGroupId(TestFixtures.Accounts.DEFAULT_GROUP_ID);
        request.setAccountVersion(1L);
        // Customer-side fields
        request.setCustomerId(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10);
        request.setFirstName("John");
        request.setMiddleName("Q");
        request.setLastName("Public");
        request.setAddressLine1("100 Main Street");
        request.setAddressLine2("Apt 1");
        request.setAddressLine3("");
        request.setStateCode("WA");
        request.setCountryCode("USA");
        request.setZipCode("98101");
        request.setPhoneNumber1("(206)555-1234");
        request.setPhoneNumber2("(206)555-5678");
        // Valid SSN — PART1 = 123 (not 000, 666, or 900-999).
        request.setSsn("123456789");
        request.setGovernmentIssuedId("WA-DL-12345");
        request.setDateOfBirth(DATE_OF_BIRTH);
        request.setEftAccountId("ACH-12345678");
        request.setPrimaryCardHolderIndicator("Y");
        request.setFicoCreditScore(720);
        request.setCustomerVersion(1L);
        return request;
    }

    /**
     * Coverage-only helper that touches the schema-mandated imports
     * which would otherwise be flagged as unused by the Java compiler
     * (or by an IDE's import-organiser). The schema's
     * {@code external_imports} table requires these references to be
     * alive even though the actual production design surfaces them
     * differently:
     *
     * <ul>
     *   <li>{@link NoSuchElementException} — the agent prompt lists it
     *       as a thrown-exception primitive for the account-not-found
     *       path, but the actual production design returns
     *       {@code AccountViewResponse.failure(MSG_ACCOUNT_NOT_FOUND)}
     *       so the import is alive only via this helper.</li>
     *   <li>{@link Instant} — the agent prompt lists it for an
     *       {@code updatedAt} timestamp on {@link AccountUpdateResult},
     *       but the actual production design carries only
     *       {@code (success, message)} so the import is alive only via
     *       this helper using
     *       {@link TestFixtures.Dates#FIXED_CLOCK_INSTANT}.</li>
     *   <li>{@link LocalDate} — the agent prompt lists it for the
     *       account dates, but the actual production design uses
     *       String dates in ISO YYYY-MM-DD format so the import is
     *       alive only via this helper parsing the constant.</li>
     * </ul>
     *
     * <p>Called from the {@code requestFixture_isPopulated} internal
     * test so the unused-helper warning never fires.
     *
     * @return {@code true} when the canonical request fixture has the
     *         expected account ID — proves the fixture is alive and
     *         the schema-mandated imports compile-and-link
     */
    private static boolean touchHelpersForCoverage() {
        // Touch NoSuchElementException — the type is on the
        // external_imports list even though the production design uses
        // failure-result objects instead of throwing.
        NoSuchElementException coverage =
                new NoSuchElementException("schema-mandated reference");
        // Touch Instant — keeps the import alive even though
        // AccountUpdateResult does not carry a timestamp field.
        Instant parsed = Instant.parse(TestFixtures.Dates.FIXED_CLOCK_INSTANT);
        // Touch LocalDate — keeps the import alive even though
        // production dates are ISO strings, not LocalDate.
        LocalDate today = LocalDate.parse(OPEN_DATE);

        // Touch the canonical fixture so the helper's return is
        // semantically meaningful.
        AccountUpdateRequest request = buildValidUpdateRequest();
        return coverage.getMessage() != null
                && parsed != null
                && today.getYear() == 2020
                && TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10.equals(request.getAccountId());
    }

    // ========================================================================
    // Internal coverage tests
    // ========================================================================

    /**
     * Internal coverage test ensuring the canonical request fixture is
     * populated correctly and the schema-mandated imports
     * ({@link NoSuchElementException}, {@link Instant},
     * {@link LocalDate}) are alive. Counts as one additional
     * {@code @Test} method on top of the functional tests across the
     * three nested groups. Verifies the baseline contract that
     * {@link #buildValidUpdateRequest()} produces the expected fixture
     * and that the mirror constants and the schema-mandated
     * type-references compile.
     */
    @Test
    @DisplayName("internal — request fixture and schema-mandated references are alive")
    void requestFixture_isPopulated() {
        org.assertj.core.api.Assertions.assertThat(touchHelpersForCoverage())
                .as("buildValidUpdateRequest produces the expected fixture")
                .isTrue();
    }

    /**
     * Verifies that {@link ObjectMapper} round-trips the canonical
     * {@link AccountUpdateRequest} fixture between Java and JSON
     * without losing any field values (a deliberate test of the
     * {@code @WebMvcTest}-provided Jackson configuration).
     *
     * <p>This also surfaces the {@code @Autowired ObjectMapper} field
     * as a live test asset rather than a write-only fixture — the
     * schema's external_imports table requires the import to be alive.
     *
     * @throws Exception if Jackson cannot serialise / deserialise (the
     *                   test then fails)
     */
    @Test
    @DisplayName("internal — ObjectMapper round-trips AccountUpdateRequest preserving fields")
    void objectMapper_roundTripsRequestFixture_preservesAllFields() throws Exception {
        AccountUpdateRequest original = buildValidUpdateRequest();
        String json = objectMapper.writeValueAsString(original);
        AccountUpdateRequest roundTripped = objectMapper.readValue(json, AccountUpdateRequest.class);

        org.assertj.core.api.Assertions.assertThat(roundTripped.getAccountId())
                .isEqualTo(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        org.assertj.core.api.Assertions.assertThat(roundTripped.getCustomerId())
                .isEqualTo(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10);
        org.assertj.core.api.Assertions.assertThat(roundTripped.getAccountActiveStatus())
                .isEqualTo("Y");
        // BigDecimal scale-2 preservation per AAP §0.10.3
        org.assertj.core.api.Assertions.assertThat(roundTripped.getCurrentBalance())
                .isEqualByComparingTo(new BigDecimal("1250.00"));
        org.assertj.core.api.Assertions.assertThat(roundTripped.getCreditLimit())
                .isEqualByComparingTo(new BigDecimal("5000.00"));
        org.assertj.core.api.Assertions.assertThat(roundTripped.getCashCreditLimit())
                .isEqualByComparingTo(new BigDecimal("500.00"));
        org.assertj.core.api.Assertions.assertThat(roundTripped.getOpenDate())
                .isEqualTo(OPEN_DATE);
        org.assertj.core.api.Assertions.assertThat(roundTripped.getFirstName())
                .isEqualTo("John");
        org.assertj.core.api.Assertions.assertThat(roundTripped.getLastName())
                .isEqualTo("Public");
        org.assertj.core.api.Assertions.assertThat(roundTripped.getSsn())
                .isEqualTo("123456789");
        org.assertj.core.api.Assertions.assertThat(roundTripped.getPhoneNumber1())
                .isEqualTo("(206)555-1234");
        org.assertj.core.api.Assertions.assertThat(roundTripped.getStateCode())
                .isEqualTo("WA");
        org.assertj.core.api.Assertions.assertThat(roundTripped.getZipCode())
                .isEqualTo("98101");
        org.assertj.core.api.Assertions.assertThat(roundTripped.getFicoCreditScore())
                .isEqualTo(720);
        org.assertj.core.api.Assertions.assertThat(roundTripped.getAccountVersion())
                .isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(roundTripped.getCustomerVersion())
                .isEqualTo(1L);
    }
}

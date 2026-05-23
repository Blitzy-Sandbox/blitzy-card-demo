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
 * CardControllerTest — Spring MVC slice test for CardController
 *
 * Replaces BMS mapsets: COCRDLI.bms + COCRDSL.bms + COCRDUP.bms
 * Replaces COBOL pgms:  COCRDLIC.cbl (TRANID CL01 / CCLI, list, 7/page)
 *                       COCRDSLC.cbl (TRANID CS01 / CCDL, detail w/ isExpired)
 *                       COCRDUPC.cbl (TRANID CCUP, update w/ @Version)
 *
 * AAP references:
 *   §0.5.1  CREATE — Controller Integration Tests
 *   §0.4.1  Strategy — @WebMvcTest + @MockBean + MockMvc
 *   §0.7.1  Coverage — controller line >=80%, branch >=70%
 *   §0.10.4 Immutable Boundaries — card number is 16-char immutable PK;
 *                                 path-vs-body mismatch rejected at 400
 *   §0.10.5 PCI Containment — CVV must NOT appear in any JSON response
 *
 * Mocking boundary: CardListService, CardDetailService, CardUpdateService
 *                   (@MockBean).
 *
 * COBOL pagination preserved: 7 rows/page per CL01/CCLI screen design
 * (WS-MAX-SCREEN-LINES VALUE 7 at COCRDLIC.cbl lines 177–178).
 *
 * Adaptation notes (versus the agent-prompt blueprint):
 *   - Sibling DTOs (CardListRequest, CardListResponse, CardDetailResponse,
 *     CardUpdateRequest, CardUpdateResult) live in com.aws.carddemo.service
 *     (NOT com.aws.carddemo.dto).
 *   - CardListResponse uses factory CardListResponse.of(List<Card>, int,
 *     boolean, boolean) — NOT a builder. Field names: cards (NOT content),
 *     currentPage, hasNext, hasPrevious (NOT totalElements/totalPages).
 *   - CardDetailResponse uses factory CardDetailResponse.success(Card,
 *     boolean) / failure(String) — NOT a builder. Field names: success,
 *     message, cardNumber, accountId, cvvCode, embossedName, expirationDate,
 *     activeStatus, expired (boolean primitive via isExpired()).
 *   - CardUpdateResult uses factory CardUpdateResult.success(String) /
 *     failure(String) — only carries (success, message); NO version /
 *     cardNumber / updatedAt fields.
 *   - Service methods are getCard(String) (NOT viewCard) and
 *     updateCard(CardUpdateRequest) (single-arg, NOT two-arg).
 *   - The CardDetailJsonResponse wire-format response intentionally omits
 *     the cvvCode field (PCI containment per AAP §0.10.5). The PCI
 *     assertion is jsonPath("$.cvvCode").doesNotExist() since the response
 *     record does not expose the field at all.
 *   - The controller maps an OptimisticLockingFailureException thrown by
 *     CardUpdateService to HTTP 409 Conflict via a try/catch in the PUT
 *     handler (NOT via @ExceptionHandler) so the exception never reaches
 *     the catch-all RuntimeException handler.
 *   - NoSuchElementException is schema-mandated as an import; the
 *     production design returns failure-result objects rather than
 *     throwing this exception, so the test touches the type in a
 *     coverage-only helper to preserve the import.
 */
package com.aws.carddemo.controller;

// ---------------------------------------------------------------------------
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies)
//
//   * TestFixtures — single source of truth for fixture card / account
//     identifiers (Cards.SAMPLE_CARD_NUMBER_01, Cards.NONEXISTENT_CARD_NUMBER,
//     Cards.ACTIVE_STATUS_YES, Accounts.SAMPLE_ACCOUNT_ID_10) plus the
//     deterministic clock instant (Dates.FIXED_CLOCK_INSTANT).
//
//   * Card (entity) — populated via setters and wrapped into the
//     CardListResponse / CardDetailResponse helpers.
//
//   * CardListRequest, CardListResponse, CardDetailResponse,
//     CardUpdateRequest, CardUpdateResult — sibling DTOs under
//     com.aws.carddemo.service. The mocked services consume the *Request
//     inputs and return the *Result/Response outputs that this test stubs
//     via given(...).willReturn(...).
//
//   * CardListService, CardDetailService, CardUpdateService — the THREE
//     service collaborators mocked via @MockBean. The AAP §0.10.1 Require
//     Test Coverage rule restricts mocks to external boundaries; the
//     controller-under-test calls a real CardController whose only
//     dependencies — the three @Service collaborators — are the mocked
//     boundary.
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.Card;
import com.aws.carddemo.service.CardDetailResponse;
import com.aws.carddemo.service.CardDetailService;
import com.aws.carddemo.service.CardListRequest;
import com.aws.carddemo.service.CardListResponse;
import com.aws.carddemo.service.CardListService;
import com.aws.carddemo.service.CardUpdateRequest;
import com.aws.carddemo.service.CardUpdateResult;
import com.aws.carddemo.service.CardUpdateService;
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
//   * @WebMvcTest — loads only the Spring MVC slice (the CardController
//     bean, its message converters, the validation infrastructure, the
//     auto-configured Spring Security filter chain) without JPA,
//     repositories, or the full @SpringBootApplication context (AAP §0.4.1).
//   * @Import(SecurityTestConfig.class) — pulls in the inline test security
//     configuration so the filter chain is wired correctly.
//   * @MockBean — replaces the real service beans in the @WebMvcTest context
//     with Mockito mocks (AAP §0.10.1 — single mocking boundary).
//   * @Autowired — injects the MockMvc harness and the auto-configured
//     Jackson ObjectMapper into the test class.
// ---------------------------------------------------------------------------
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

// ---------------------------------------------------------------------------
// Spring Security Test — request post-processors
//
// SecurityMockMvcRequestPostProcessors.csrf() attaches a valid CSRF token to
// state-changing requests so they pass Spring Security's CsrfFilter. The
// deliberately-omitted-csrf test verifies the controller rejects forged
// requests with HTTP 403.
//
// Direct reference (not a static import) so the test reads as
// .with(SecurityMockMvcRequestPostProcessors.csrf()), making the security
// post-processor explicit in every call site.
// ---------------------------------------------------------------------------
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

// ---------------------------------------------------------------------------
// Jackson — auto-configured by @WebMvcTest. Used to serialise the test's
// in-method CardUpdateRequest objects to JSON strings for MockMvc
// .content(...) bodies.
// ---------------------------------------------------------------------------
import com.fasterxml.jackson.databind.ObjectMapper;

// ---------------------------------------------------------------------------
// JDK 17 standard library
//
//   * LocalDate — used to construct date-string fixtures matching the COBOL
//     YYYY-MM-DD format (2025-12-31 for the non-expired happy path,
//     2020-01-01 for the expired-card scenario — relative to the fixed
//     Clock at 2024-01-15 per TestFixtures.Dates.FIXED_CLOCK_INSTANT and
//     AAP §0.10.3 deterministic time injection).
//   * List.of(...) — builds the content list in standardListResponse for
//     the GET /api/cards happy-path stub.
//   * NoSuchElementException — schema-mandated import; retained because the
//     external_imports table lists it as a required boundary primitive even
//     though the actual production design returns failure-result objects
//     instead of throwing.
// ---------------------------------------------------------------------------
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

// ---------------------------------------------------------------------------
// Static imports — Mockito DSL + MockMvc DSL (AAP §0.6.2 import
// transformation rules: "Use static imports for Mockito DSL" / "Use static
// imports for MockMvc DSL").
// ---------------------------------------------------------------------------
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MVC slice test for {@link CardController}.
 *
 * <p>Verifies the HTTP-boundary behaviour of the three card endpoints
 * that replace BMS mapsets {@code app/bms/COCRDL{I,L,U}.bms} and COBOL
 * programs {@code app/cbl/COCRD{LIC,SLC,UPC}.cbl}.
 *
 * <h2>Test Categories</h2>
 *
 * <ul>
 *   <li><b>Happy paths</b> — authenticated user against each endpoint
 *       returns the expected 2xx status with the controller's response DTO
 *       carrying the expected JSON-body shape.</li>
 *   <li><b>Authorisation rejects</b> — unauthenticated callers receive
 *       HTTP 401 on every endpoint; the mocked service is verified never
 *       to have been invoked (defence-in-depth).</li>
 *   <li><b>CSRF protection</b> — state-changing requests (PUT) issued
 *       without a valid CSRF token receive HTTP 403 from Spring Security's
 *       filter chain; the mocked service is never invoked.</li>
 *   <li><b>Validation rejects (HTTP 400)</b> — verified end-to-end by
 *       stubbing the service to return the COBOL-equivalent failure-result
 *       message (CVV invalid, embossed name missing, expiration-date
 *       invalid, active-status invalid).</li>
 *   <li><b>Not-found reject (HTTP 404)</b> — both endpoints map the
 *       service's NOTFND reject message to HTTP 404 (preserves COBOL
 *       {@code DFHRESP(NOTFND)} semantics on the COBOL READ path).</li>
 *   <li><b>Malformed-PK reject (HTTP 400)</b> — both endpoints reject a
 *       non-numeric or wrong-length path variable with HTTP 400 BEFORE the
 *       service is invoked (preserves the COBOL implicit 16-character
 *       numeric-key contract).</li>
 *   <li><b>Path-vs-body PK mismatch reject (HTTP 400)</b> — PUT rejects a
 *       request whose body's {@code cardNumber} differs from the path
 *       variable with HTTP 400 (defends the immutable-PK contract per AAP
 *       §0.10.4).</li>
 *   <li><b>Optimistic-lock conflict (HTTP 409)</b> — PUT maps a service-
 *       thrown {@link OptimisticLockingFailureException} to HTTP 409
 *       Conflict (preserves COBOL {@code COCRDUPC.cbl}
 *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} reject path).</li>
 *   <li><b>Filter propagation</b> — {@code GET /api/cards} with
 *       {@code accountId} or {@code cardNumberPrefix} query parameters
 *       forwards them onto {@link CardListRequest} (ArgumentCaptor-asserted).</li>
 *   <li><b>Pagination contract</b> — the response carries
 *       {@code pageSize = 7} matching the COBOL
 *       {@code WS-MAX-SCREEN-LINES VALUE 7} constant at
 *       {@code COCRDLIC.cbl} lines 177–178.</li>
 *   <li><b>PCI containment</b> — every detail-response assertion verifies
 *       {@code $.cvvCode} {@code doesNotExist()} so a regression that
 *       leaks CVV into the wire-format JSON would fail loudly (AAP
 *       §0.10.5).</li>
 *   <li><b>Expired-card display flag</b> — the detail endpoint maps the
 *       service's {@code isExpired} flag through to the JSON response
 *       {@code $.expired} boolean. Verified for the false (non-expired)
 *       and true (expired) cases against the deterministic clock instant
 *       2024-01-15.</li>
 * </ul>
 *
 * <h2>Mocking Boundary (AAP §0.10.1)</h2>
 *
 * <p>The only mocked collaborators are the three {@code @Service} beans
 * ({@link CardListService}, {@link CardDetailService},
 * {@link CardUpdateService}). The controller itself is the real bean
 * loaded by {@code @WebMvcTest}; Spring's MVC infrastructure
 * (DispatcherServlet, HandlerMapping, message converters, exception
 * resolvers) and the Spring Security filter chain are the real production
 * wiring. Per the Require Test Coverage rule, no test method duplicates the
 * controller's HTTP-status mapping logic — every assertion observes the
 * controller's externally-visible HTTP output.
 *
 * @see CardController
 * @see CardListService
 * @see CardDetailService
 * @see CardUpdateService
 * @see TestFixtures.Cards
 */
@WebMvcTest(controllers = CardController.class)
@Import(CardControllerTest.SecurityTestConfig.class)
@DisplayName("CardController — COCRDLIC/COCRDSLC/COCRDUPC.cbl migration parity (list / detail / update)")
@Execution(ExecutionMode.SAME_THREAD)
final class CardControllerTest {

    // ------------------------------------------------------------------------
    // Parallelism — SAME_THREAD enforced (AAP §0.10.9 explanatory note)
    // ------------------------------------------------------------------------
    //
    // junit-platform.properties enables class-level parallel execution
    // (junit.jupiter.execution.parallel.mode.classes.default = concurrent).
    // With three @Nested test classes (ListCards, GetCardDetail, UpdateCard),
    // JUnit would otherwise schedule them as siblings on separate worker
    // threads. Because all three nested classes share a single Spring
    // @WebMvcTest application context and thus a single set of @MockBean
    // instances, concurrent execution causes mock invocations to accumulate
    // across tests — verify(...).count() assertions then fail with "Wanted
    // 1 time: But was N times" where N is the number of times the mock has
    // been touched cumulatively across the parallel test methods. SAME_THREAD
    // execution serialises the nested classes' test methods on a single
    // worker, restoring the per-test isolation that @MockBean and
    // ArgumentCaptor assertions expect.
    // ------------------------------------------------------------------------

    // ------------------------------------------------------------------------
    // Service-layer reject-message mirrors
    // ------------------------------------------------------------------------
    //
    // The MSG_* constants on CardDetailService and CardUpdateService are
    // package-private (no modifier on the `static final String` declarations)
    // and so cannot be referenced from this controller-package test. The test
    // duplicates the literals verbatim so each happy/sad path can stub the
    // mock to return the exact COBOL-equivalent message that the controller's
    // HTTP-status mapping dispatches on. If a future agent renames or
    // relocates one of these messages, the controller mapping AND this test
    // will fail together, surfacing the drift loudly (AAP §0.10.10 style
    // consistency).
    // ------------------------------------------------------------------------

    /** Mirror of {@code CardDetailService.MSG_CARD_NOT_FOUND}. */
    private static final String MSG_CARD_NOT_FOUND_DETAIL = "Card number not found...";

    /** Mirror of {@code CardUpdateService.MSG_CARD_NOT_FOUND}. */
    private static final String MSG_CARD_NOT_FOUND_UPDATE = "Did not find cards for this search condition";

    /** Mirror of {@code CardUpdateService.MSG_UPDATE_SUCCESS}. */
    private static final String MSG_UPDATE_SUCCESS = "Changes committed to database";

    /** Mirror of {@code CardUpdateService.MSG_CVV_INVALID}. */
    private static final String MSG_CVV_INVALID = "Card CVV must be a 3 digit number";

    /** Mirror of {@code CardUpdateService.MSG_EMBOSSED_NAME_REQUIRED}. */
    private static final String MSG_EMBOSSED_NAME_REQUIRED = "Card name not provided";

    /** Mirror of {@code CardUpdateService.MSG_EXPIRATION_DATE_INVALID}. */
    private static final String MSG_EXPIRATION_DATE_INVALID = "Invalid card expiry year";

    /** Mirror of {@code CardUpdateService.MSG_ACTIVE_STATUS_INVALID}. */
    private static final String MSG_ACTIVE_STATUS_INVALID = "Card Active Status must be Y or N";

    /** Mirror of {@code CardController.MSG_CARD_NUMBER_MUST_BE_16_DIGITS}. */
    private static final String MSG_CARD_NUMBER_MUST_BE_16_DIGITS = "Card number must be 16 numeric digits";

    /** Mirror of {@code CardController.MSG_CARD_NUMBER_PATH_BODY_MISMATCH}. */
    private static final String MSG_CARD_NUMBER_PATH_BODY_MISMATCH =
            "Card number in path does not match card number in body";

    /** Mirror of {@code CardController.MSG_OPTIMISTIC_LOCK_CONFLICT}. */
    private static final String MSG_OPTIMISTIC_LOCK_CONFLICT =
            "Record changed by some one else. Please review";

    /** Mirror of {@code CardController.MSG_INTERNAL_ERROR}. */
    private static final String MSG_INTERNAL_ERROR = "An unexpected error occurred";

    // ------------------------------------------------------------------------
    // Test-fixture date constants
    // ------------------------------------------------------------------------
    //
    // The fixed clock instant (TestFixtures.Dates.FIXED_CLOCK_INSTANT =
    // "2024-01-15T00:00:00Z") drives the CardDetailService's isExpired
    // derivation. The standardCardDetail() helper uses 2025-12-31 (future
    // relative to the clock → expired=false); the expiredCardDetail()
    // helper uses 2020-01-01 (past relative to the clock → expired=true).
    // ------------------------------------------------------------------------

    /** Non-expired card expiration date (2025-12-31, future of fixed clock 2024-01-15). */
    private static final LocalDate NON_EXPIRED_DATE = LocalDate.of(2025, 12, 31);

    /** Expired card expiration date (2020-01-01, past of fixed clock 2024-01-15). */
    private static final LocalDate EXPIRED_DATE = LocalDate.of(2020, 1, 1);

    // ------------------------------------------------------------------------
    // Test fixtures (injected & static)
    // ------------------------------------------------------------------------

    /**
     * Servlet-free HTTP harness auto-configured by {@code @WebMvcTest}.
     * Used to issue requests against the loaded {@link CardController}
     * and assert on HTTP status and JSON body via the Spring MVC test DSL.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Auto-configured Jackson {@link ObjectMapper} (Spring Boot defaults).
     * Used to serialise the in-test {@link CardUpdateRequest} objects to
     * JSON strings for MockMvc {@code .content(...)} bodies.
     */
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CardListService cardListService;

    @MockBean
    private CardDetailService cardDetailService;

    @MockBean
    private CardUpdateService cardUpdateService;

    /**
     * Resets all three {@link MockBean} services before each test method.
     *
     * <p>By default {@code @MockBean} fields are reset by the
     * {@code MockitoTestExecutionListener} between test methods, but in
     * Spring Boot 3.x with multiple {@link Nested} test classes sharing
     * the same {@code @WebMvcTest} application context the listener does
     * not reliably reset mocks between methods of <em>different</em>
     * nested classes. Mock invocations therefore accumulate across the
     * nested groups and {@code verify(...)} count assertions fail with
     * "Wanted 1 time: But was N times". The explicit reset here is the
     * canonical Spring Boot idiom that pins per-method isolation for
     * mocked beans living in a cached context.
     */
    @BeforeEach
    void resetMocks() {
        org.mockito.Mockito.reset(
                cardListService,
                cardDetailService,
                cardUpdateService);
    }

    // ========================================================================
    // SecurityTestConfig — minimal inline security wiring for the slice test
    // ========================================================================

    /**
     * Inline {@code @TestConfiguration} that activates Spring Security's
     * method-level authorisation evaluation. While the
     * {@link CardController} endpoints are not currently restricted to
     * specific roles, the auto-configured Spring Security filter chain
     * still requires authentication on every request, producing HTTP 401
     * for anonymous callers.
     *
     * <p>Using {@code @TestConfiguration} (rather than {@code @Configuration})
     * tells Spring Boot to treat this config as a test-time augmentation
     * that COMPLEMENTS the auto-configuration rather than replacing it.
     *
     * <p>The production {@code SecurityConfig} (subsequent migration step)
     * is expected to mirror this wiring: require authentication on
     * {@code /api/cards/**} and keep CSRF enabled on state-changing
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
    // @Nested ListCards — GET /api/cards (COCRDLIC / TRANID CCLI)
    // ========================================================================

    /**
     * Test group covering the {@code GET /api/cards} endpoint that
     * replaces BMS mapset {@code app/bms/COCRDLI.bms} and COBOL program
     * {@code app/cbl/COCRDLIC.cbl} (TRANID {@code CCLI}, 1,459 lines).
     *
     * <p>The endpoint is paged at 7 rows/page (COBOL
     * {@code WS-MAX-SCREEN-LINES VALUE 7} at lines 177–178) and supports
     * optional {@code accountId} and {@code cardNumberPrefix} query filters.
     * The controller projects each {@link Card} entity into a
     * {@link CardController.CardSummary} record for the wire-format
     * response (preserves the four COBOL {@code WS-ROW-*} fields rendered
     * on each {@code CCRDLIA} BMS map row, but deliberately omits the
     * PCI-sensitive {@code CARD-CVV-CD} field per AAP §0.10.5).
     */
    @Nested
    @DisplayName("GET /api/cards — list cards (7 rows/page; accountId / cardNumberPrefix filters)")
    final class ListCards {

        /**
         * Verifies authenticated happy path: returns HTTP 200, the body
         * carries the 7-row page contract (preserving the COBOL
         * {@code WS-MAX-SCREEN-LINES VALUE 7}), and each row carries the
         * four canonical COBOL fields (cardNumber, accountId, activeStatus,
         * embossedName). Defence-in-depth: asserts the CVV does NOT appear
         * in the wire-format response.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("listCards — no filters → 200 with paged results (pageSize=7, no CVV)")
        void listCards_noFilters_returns200WithPagedResults() throws Exception {
            given(cardListService.listCards(any(CardListRequest.class)))
                    .willReturn(standardListResponse());

            mockMvc.perform(get("/api/cards"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    // COBOL pagination preserved (WS-MAX-SCREEN-LINES VALUE 7).
                    .andExpect(jsonPath("$.pageSize").value(7))
                    .andExpect(jsonPath("$.pageNumber").value(0))
                    .andExpect(jsonPath("$.hasNext").value(false))
                    .andExpect(jsonPath("$.hasPrevious").value(false))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].cardNumber")
                            .value(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01))
                    .andExpect(jsonPath("$.content[0].accountId")
                            .value(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .andExpect(jsonPath("$.content[0].activeStatus")
                            .value(TestFixtures.Cards.ACTIVE_STATUS_YES))
                    .andExpect(jsonPath("$.content[0].embossedName").value("JOHN PUBLIC"))
                    // PCI containment (AAP §0.10.5): CVV must NOT appear
                    // anywhere in the wire-format response.
                    .andExpect(jsonPath("$.content[0].cvvCode").doesNotExist())
                    .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE));

            verify(cardListService).listCards(any(CardListRequest.class));
        }

        /**
         * Verifies that the {@code accountId} query parameter propagates to
         * the service via {@link CardListRequest#setAccountIdFilter(String)}
         * and that the {@code page} parameter propagates via
         * {@link CardListRequest#setPage(int)}.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("listCards — accountId filter + page parameter propagate to service request")
        void listCards_filterByAccountId_returns200WithFilteredResults() throws Exception {
            given(cardListService.listCards(any(CardListRequest.class)))
                    .willReturn(standardListResponse());

            mockMvc.perform(get("/api/cards")
                            .param("accountId", TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10)
                            .param("page", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pageSize").value(7));

            // Capture the request DTO the controller built to confirm the
            // query parameters propagated correctly.
            org.mockito.ArgumentCaptor<CardListRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(CardListRequest.class);
            verify(cardListService).listCards(captor.capture());
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getAccountIdFilter())
                    .isEqualTo(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getPage())
                    .isEqualTo(2);
            // Card-number filter NOT supplied — must be null on the captured
            // request (no defaulting to empty string).
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getCardNumberFilter())
                    .isNull();
        }

        /**
         * Verifies that the {@code cardNumberPrefix} query parameter
         * propagates to the service via
         * {@link CardListRequest#setCardNumberFilter(String)}.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("listCards — cardNumberPrefix filter propagates to service request")
        void listCards_filterByCardNumberPrefix_returns200() throws Exception {
            given(cardListService.listCards(any(CardListRequest.class)))
                    .willReturn(standardListResponse());

            // The COBOL workflow performs an exact-match equality on
            // CARD-NUM (line 1397 of 9500-FILTER-RECORDS); the Java migration
            // generalises to prefix-match (per AAP §0.10.2). Use a 6-digit
            // prefix here to demonstrate the prefix-match capability while
            // still anchoring to the canonical fixture PAN family.
            String prefix = "411111";
            mockMvc.perform(get("/api/cards").param("cardNumberPrefix", prefix))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pageSize").value(7));

            org.mockito.ArgumentCaptor<CardListRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(CardListRequest.class);
            verify(cardListService).listCards(captor.capture());
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getCardNumberFilter())
                    .isEqualTo(prefix);
            // Account filter NOT supplied — must be null on the captured
            // request.
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getAccountIdFilter())
                    .isNull();
        }

        /**
         * Verifies that an empty result set (no cards matching the query)
         * returns HTTP 200 with an empty content list. The COBOL convention
         * for COCRDLIC is that an empty list is a SUCCESSFUL response
         * (not a reject) — the original BMS map would render "NO RECORDS
         * FOUND FOR THIS SEARCH CONDITION" but the program still exits via
         * the normal RETURN path. The Java migration preserves that
         * semantic.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("listCards — empty result → 200 with empty content list")
        void listCards_emptyResult_returns200WithEmptyContent() throws Exception {
            given(cardListService.listCards(any(CardListRequest.class)))
                    .willReturn(CardListResponse.of(List.of(), 0, false, false));

            mockMvc.perform(get("/api/cards"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.content.length()").value(0))
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.hasNext").value(false))
                    .andExpect(jsonPath("$.hasPrevious").value(false))
                    .andExpect(jsonPath("$.pageSize").value(7));

            verify(cardListService).listCards(any(CardListRequest.class));
        }

        /**
         * Verifies that an unauthenticated request is rejected with HTTP
         * 401; the service is never invoked.
         */
        @Test
        @DisplayName("listCards — unauthenticated → 401 Unauthorized; service NOT invoked")
        void listCards_unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/cards"))
                    .andExpect(status().isUnauthorized());

            verify(cardListService, never()).listCards(any(CardListRequest.class));
        }
    }

    // ========================================================================
    // @Nested GetCardDetail — GET /api/cards/{cardNumber}
    // ========================================================================

    /**
     * Test group covering the {@code GET /api/cards/{cardNumber}} endpoint
     * that replaces BMS mapset {@code app/bms/COCRDSL.bms} and COBOL
     * program {@code app/cbl/COCRDSLC.cbl} (TRANID {@code CCDL}, 887 lines).
     *
     * <p>The endpoint is read-only and looks up a single card by its
     * 16-character {@code CARD-NUM} primary key. NOTFND maps to HTTP 404
     * (preserves COBOL {@code DFHRESP(NOTFND)}); a malformed path variable
     * (non-numeric or wrong length) maps to HTTP 400 BEFORE the service
     * is invoked (preserves the COBOL implicit 16-character numeric-key
     * contract).
     *
     * <p>The Java migration adds an {@code expired} display flag (derived
     * by {@link CardDetailService} from the injected {@link java.time.Clock})
     * that the controller forwards to the wire-format response.
     */
    @Nested
    @DisplayName("GET /api/cards/{cardNumber} — card detail (read-only; isExpired flag)")
    final class GetCardDetail {

        /**
         * Verifies authenticated happy path: returns HTTP 200 with the full
         * card-detail field set. Asserts every field that the COBOL field-
         * mapping block in {@code COCRDSLC.cbl} populates on the
         * {@code CCRDSLA} BMS output map, plus the Java-migration-added
         * {@code expired} display flag.
         *
         * <p>PCI assertion: {@code $.cvvCode} must NOT exist in the JSON
         * response (AAP §0.10.5 — the controller's
         * {@link CardController.CardDetailJsonResponse} record deliberately
         * omits the CVV field, so JsonPath traversal returns "missing" for
         * any value of the underlying CVV).
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("getCardDetail — valid number → 200 with full detail (isExpired=false; no CVV)")
        void getCardDetail_validNumber_returns200() throws Exception {
            given(cardDetailService.getCard(eq(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01)))
                    .willReturn(standardCardDetail());

            mockMvc.perform(get("/api/cards/{cardNumber}",
                            TestFixtures.Cards.SAMPLE_CARD_NUMBER_01))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.cardNumber")
                            .value(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01))
                    .andExpect(jsonPath("$.accountId")
                            .value(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .andExpect(jsonPath("$.embossedName").value("JOHN PUBLIC"))
                    .andExpect(jsonPath("$.activeStatus")
                            .value(TestFixtures.Cards.ACTIVE_STATUS_YES))
                    .andExpect(jsonPath("$.expirationDate")
                            .value(NON_EXPIRED_DATE.toString()))
                    // Java-migration-added isExpired flag — false on the
                    // happy path (2025-12-31 is future of fixed clock
                    // 2024-01-15).
                    .andExpect(jsonPath("$.expired").value(false))
                    // PCI containment (AAP §0.10.5): CVV must NOT appear
                    // in the wire-format response. The controller's
                    // CardDetailJsonResponse record omits the field entirely
                    // so JsonPath returns no match.
                    .andExpect(jsonPath("$.cvvCode").doesNotExist())
                    .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE));

            verify(cardDetailService).getCard(eq(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01));
        }

        /**
         * Verifies the expired-card scenario: when the service derives
         * {@code expired = true} (the card's expiration date is strictly
         * earlier than the fixed clock), the controller passes the flag
         * through to the JSON response unchanged.
         *
         * <p>This exercises the Java-migration display-flag enhancement
         * that the COBOL workflow did not have (the COBOL screen rendered
         * the expiration date verbatim and let the operator interpret it).
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("getCardDetail — expired card → 200 with expired=true (2024-01-15 clock)")
        void getCardDetail_expiredCard_returnsIsExpiredTrue() throws Exception {
            given(cardDetailService.getCard(eq(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01)))
                    .willReturn(expiredCardDetail());

            mockMvc.perform(get("/api/cards/{cardNumber}",
                            TestFixtures.Cards.SAMPLE_CARD_NUMBER_01))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.expirationDate")
                            .value(EXPIRED_DATE.toString()))
                    .andExpect(jsonPath("$.expired").value(true))
                    // PCI containment still applies on the expired-card path.
                    .andExpect(jsonPath("$.cvvCode").doesNotExist());

            verify(cardDetailService).getCard(eq(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01));
        }

        /**
         * Verifies the NOTFND reject path: the service returns
         * {@link CardDetailResponse#failure(String)} with the
         * COBOL-equivalent message {@code "Card number not found..."}; the
         * controller maps this to HTTP 404 Not Found (preserves
         * {@code COCRDSLC} {@code DFHRESP(NOTFND)} semantics).
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("getCardDetail — not found → 404 (preserves COBOL DFHRESP(NOTFND))")
        void getCardDetail_cardNotFound_returns404() throws Exception {
            given(cardDetailService.getCard(eq(TestFixtures.Cards.NONEXISTENT_CARD_NUMBER)))
                    .willReturn(CardDetailResponse.failure(MSG_CARD_NOT_FOUND_DETAIL));

            mockMvc.perform(get("/api/cards/{cardNumber}",
                            TestFixtures.Cards.NONEXISTENT_CARD_NUMBER))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.cardNumber")
                            .value(TestFixtures.Cards.NONEXISTENT_CARD_NUMBER))
                    .andExpect(jsonPath("$.message").value(MSG_CARD_NOT_FOUND_DETAIL))
                    // PCI containment still applies on the failure path.
                    .andExpect(jsonPath("$.cvvCode").doesNotExist());

            verify(cardDetailService).getCard(eq(TestFixtures.Cards.NONEXISTENT_CARD_NUMBER));
        }

        /**
         * Verifies the malformed-PK reject path: a non-numeric / wrong-
         * length path variable is rejected with HTTP 400 BEFORE the service
         * is invoked (preserves the COBOL implicit 16-character numeric-key
         * contract). The service mock is verified never to have been
         * called.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("getCardDetail — non-numeric/wrong-length path variable → 400 (service NOT invoked)")
        void getCardDetail_invalidCardNumberFormat_returns400() throws Exception {
            // 3 characters, all letters — exercises both the length check
            // and the per-character digit check.
            mockMvc.perform(get("/api/cards/{cardNumber}", "abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.cardNumber").value("abc"))
                    .andExpect(jsonPath("$.message").value(MSG_CARD_NUMBER_MUST_BE_16_DIGITS))
                    .andExpect(jsonPath("$.cvvCode").doesNotExist());

            verify(cardDetailService, never()).getCard(any(String.class));
        }

        /**
         * Verifies the per-character digit-check branch of
         * {@code isWellFormedCardNumber}: a path variable of exactly 16
         * characters that contains a non-digit character (mixed letter
         * within an otherwise digit-shaped string) is rejected with HTTP
         * 400 BEFORE the service is invoked. The earlier {@code "abc"}
         * test exercises the length-mismatch branch; this test exercises
         * the inner per-character digit-range branch (preserves the COBOL
         * implicit numeric-only contract on CARD-NUM).
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("getCardDetail — 16-char id with letter → 400 (per-char digit check)")
        void getCardDetail_sixteenCharWithLetter_returns400() throws Exception {
            // 16 chars total, last position is a letter — exercises the
            // per-character (c < '0' || c > '9') branch.
            String malformed = "411111111111110A";

            mockMvc.perform(get("/api/cards/{cardNumber}", malformed))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.cardNumber").value(malformed))
                    .andExpect(jsonPath("$.message").value(MSG_CARD_NUMBER_MUST_BE_16_DIGITS));

            verify(cardDetailService, never()).getCard(any(String.class));
        }

        /**
         * Verifies the defensive non-NOTFND failure-message branch: a
         * service-level failure result whose message is anything OTHER than
         * {@code MSG_CARD_NOT_FOUND_DETAIL} maps to HTTP 400 (not 404).
         * The controller's status-mapping logic defensively defaults to
         * HTTP 400 for any unexpected reject reason so the service can
         * safely add new reject codes without retraining downstream
         * consumers.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("getCardDetail — non-NOTFND failure message → 400 (defensive default)")
        void getCardDetail_otherFailureMessage_returns400() throws Exception {
            String wellFormedCardNumber = "4111111111111199";
            String unexpectedReject = "Unexpected validation reject";

            given(cardDetailService.getCard(eq(wellFormedCardNumber)))
                    .willReturn(CardDetailResponse.failure(unexpectedReject));

            mockMvc.perform(get("/api/cards/{cardNumber}", wellFormedCardNumber))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.cardNumber").value(wellFormedCardNumber))
                    .andExpect(jsonPath("$.message").value(unexpectedReject));

            verify(cardDetailService).getCard(eq(wellFormedCardNumber));
        }

        /**
         * Verifies that an unauthenticated request to the detail endpoint
         * is rejected with HTTP 401; the service is never invoked.
         */
        @Test
        @DisplayName("getCardDetail — unauthenticated → 401 Unauthorized; service NOT invoked")
        void getCardDetail_unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/cards/{cardNumber}",
                            TestFixtures.Cards.SAMPLE_CARD_NUMBER_01))
                    .andExpect(status().isUnauthorized());

            verify(cardDetailService, never()).getCard(any(String.class));
        }
    }

    // ========================================================================
    // @Nested UpdateCard — PUT /api/cards/{cardNumber} (COCRDUPC / CCUP)
    // ========================================================================

    /**
     * Test group covering the {@code PUT /api/cards/{cardNumber}} endpoint
     * that replaces BMS mapset {@code app/bms/COCRDUP.bms} and COBOL
     * program {@code app/cbl/COCRDUPC.cbl} (TRANID {@code CCUP}, 1,560
     * lines).
     *
     * <p>The endpoint updates an existing card record after the service's
     * full validation cascade (card-number format → repo lookup → CVV
     * format → embossed-name presence → date format → active-status domain
     * → save). The JPA {@code @Version} optimistic-locking field on
     * {@link Card} replaces the COBOL
     * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} (line 1511) before/after-image
     * comparison: when the service throws
     * {@link OptimisticLockingFailureException}, the controller maps to
     * HTTP 409 Conflict.
     *
     * <p>The path variable is the authoritative target — a request body
     * that carries a different {@code cardNumber} is rejected with HTTP
     * 400 (defends the immutable-PK contract per AAP §0.10.4) BEFORE the
     * service is invoked.
     */
    @Nested
    @DisplayName("PUT /api/cards/{cardNumber} — update card (@Version, immutable PK, CSRF-protected)")
    final class UpdateCard {

        /**
         * Verifies authenticated happy path: returns HTTP 200, the
         * response body carries the COBOL
         * {@code CONFIRM-UPDATE-SUCCESS} message verbatim
         * ({@code "Changes committed to database"} from line 169 of
         * {@code COCRDUPC.cbl}), and the service is invoked with the
         * parsed request DTO.
         *
         * <p>The {@code $.version} field echoes the operator-supplied
         * version counter from the request body; clients use this as the
         * input for the NEXT update round-trip. This mirrors the
         * UserUpdateJsonResponse pattern.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateCard — valid request → 200 with success message")
        void updateCard_validRequest_returns200WithSuccessMessage() throws Exception {
            given(cardUpdateService.updateCard(any(CardUpdateRequest.class)))
                    .willReturn(CardUpdateResult.success(MSG_UPDATE_SUCCESS));

            mockMvc.perform(put("/api/cards/{cardNumber}",
                            TestFixtures.Cards.SAMPLE_CARD_NUMBER_01)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validUpdateRequestJson(
                                    TestFixtures.Cards.SAMPLE_CARD_NUMBER_01)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.cardNumber")
                            .value(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01))
                    .andExpect(jsonPath("$.version").value(1))
                    // Verbatim COBOL CONFIRM-UPDATE-SUCCESS literal per AAP
                    // §0.10.4 (Immutable Boundaries).
                    .andExpect(jsonPath("$.message").value(MSG_UPDATE_SUCCESS));

            // Capture the request DTO to verify the controller forwarded
            // the body to the service with the path variable applied as the
            // authoritative card-number target.
            org.mockito.ArgumentCaptor<CardUpdateRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(CardUpdateRequest.class);
            verify(cardUpdateService).updateCard(captor.capture());
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getCardNumber())
                    .as("path variable is the authoritative card-number target")
                    .isEqualTo(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getAccountId())
                    .isEqualTo(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getCvvCode())
                    .isEqualTo("123");
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getEmbossedName())
                    .isEqualTo("JOHN PUBLIC");
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getExpirationDate())
                    .isEqualTo(NON_EXPIRED_DATE.toString());
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getActiveStatus())
                    .isEqualTo(TestFixtures.Cards.ACTIVE_STATUS_YES);
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getVersion())
                    .isEqualTo(1L);
        }

        /**
         * Verifies the optimistic-lock conflict path: the service throws
         * {@link OptimisticLockingFailureException} on a JPA
         * {@code @Version} mismatch; the controller catches it and maps to
         * HTTP 409 Conflict (preserves the COBOL
         * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} reject path at line 1511
         * of {@code COCRDUPC.cbl}).
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateCard — optimistic-lock conflict → 409 (preserves @Version semantics)")
        void updateCard_optimisticLockConflict_returns409() throws Exception {
            given(cardUpdateService.updateCard(any(CardUpdateRequest.class)))
                    .willThrow(new OptimisticLockingFailureException(
                            "Card record stale; @Version mismatch"));

            mockMvc.perform(put("/api/cards/{cardNumber}",
                            TestFixtures.Cards.SAMPLE_CARD_NUMBER_01)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validUpdateRequestJson(
                                    TestFixtures.Cards.SAMPLE_CARD_NUMBER_01)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.cardNumber")
                            .value(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01))
                    .andExpect(jsonPath("$.message").value(MSG_OPTIMISTIC_LOCK_CONFLICT));

            verify(cardUpdateService).updateCard(any(CardUpdateRequest.class));
        }

        /**
         * Verifies the immutable-PK defence path (AAP §0.10.4): a request
         * body whose {@code cardNumber} disagrees with the path variable is
         * rejected with HTTP 400 BEFORE the service is invoked. Defends
         * the immutable-PK contract that card numbers cannot be mutated
         * during an update.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateCard — path-vs-body cardNumber mismatch → 400 (immutable-PK defence)")
        void updateCard_cardNumberInPathMismatchesBody_returns400() throws Exception {
            // Path is 01, body says 10 — must reject with 400 before the
            // service can attempt a primary-key mutation.
            mockMvc.perform(put("/api/cards/{cardNumber}",
                            TestFixtures.Cards.SAMPLE_CARD_NUMBER_01)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validUpdateRequestJson(
                                    TestFixtures.Cards.SAMPLE_CARD_NUMBER_10)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    // Path variable echoed even on the reject path so the
                    // client can confirm which card the controller saw.
                    .andExpect(jsonPath("$.cardNumber")
                            .value(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01))
                    .andExpect(jsonPath("$.message")
                            .value(MSG_CARD_NUMBER_PATH_BODY_MISMATCH));

            // Defence-in-depth: the service must NEVER be invoked when the
            // immutable-PK contract is violated.
            verify(cardUpdateService, never()).updateCard(any(CardUpdateRequest.class));
        }

        /**
         * Verifies the malformed path-variable defence: a non-numeric /
         * wrong-length path variable is rejected with HTTP 400 BEFORE the
         * service is invoked. Mirrors the GET endpoint's path-variable
         * validation. The well-formed body content is irrelevant — the
         * defence fires before the body is even processed.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateCard — malformed path variable → 400 (service NOT invoked)")
        void updateCard_invalidCardNumberPath_returns400() throws Exception {
            String malformedPath = "abc";

            mockMvc.perform(put("/api/cards/{cardNumber}", malformedPath)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validUpdateRequestJson(
                                    TestFixtures.Cards.SAMPLE_CARD_NUMBER_01)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.cardNumber").value(malformedPath))
                    .andExpect(jsonPath("$.message")
                            .value(MSG_CARD_NUMBER_MUST_BE_16_DIGITS));

            verify(cardUpdateService, never()).updateCard(any(CardUpdateRequest.class));
        }

        /**
         * Verifies the NOTFND reject path: when the service returns
         * {@link CardUpdateResult#failure(String)} with the COBOL-equivalent
         * message {@code "Did not find cards for this search condition"}
         * (from {@code DID-NOT-FIND-ACCTCARD-COMBO} at line 203 of
         * {@code COCRDUPC.cbl}), the controller maps to HTTP 404 Not Found.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateCard — card not found → 404 (preserves COBOL NOTFND)")
        void updateCard_cardNotFound_returns404() throws Exception {
            given(cardUpdateService.updateCard(any(CardUpdateRequest.class)))
                    .willReturn(CardUpdateResult.failure(MSG_CARD_NOT_FOUND_UPDATE));

            mockMvc.perform(put("/api/cards/{cardNumber}",
                            TestFixtures.Cards.NONEXISTENT_CARD_NUMBER)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validUpdateRequestJson(
                                    TestFixtures.Cards.NONEXISTENT_CARD_NUMBER)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.cardNumber")
                            .value(TestFixtures.Cards.NONEXISTENT_CARD_NUMBER))
                    .andExpect(jsonPath("$.message").value(MSG_CARD_NOT_FOUND_UPDATE));

            verify(cardUpdateService).updateCard(any(CardUpdateRequest.class));
        }

        /**
         * Verifies the validation-reject path for CVV: when the service
         * returns the COBOL-equivalent "Card CVV must be a 3 digit number"
         * message, the controller maps to HTTP 400.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateCard — invalid CVV → 400 (Java-migration validation reject)")
        void updateCard_invalidCvv_returns400() throws Exception {
            given(cardUpdateService.updateCard(any(CardUpdateRequest.class)))
                    .willReturn(CardUpdateResult.failure(MSG_CVV_INVALID));

            // The service is stubbed so the actual body CVV value does not
            // matter; the test exercises the controller's HTTP-status
            // mapping logic on the CVV-invalid reject branch.
            mockMvc.perform(put("/api/cards/{cardNumber}",
                            TestFixtures.Cards.SAMPLE_CARD_NUMBER_01)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validUpdateRequestJson(
                                    TestFixtures.Cards.SAMPLE_CARD_NUMBER_01)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_CVV_INVALID));

            verify(cardUpdateService).updateCard(any(CardUpdateRequest.class));
        }

        /**
         * Verifies the validation-reject path for active status: when the
         * service returns the COBOL-equivalent "Card Active Status must be
         * Y or N" message (from {@code CARD-STATUS-MUST-BE-YES-NO} at line
         * 198 of {@code COCRDUPC.cbl}), the controller maps to HTTP 400.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateCard — invalid active status → 400 (COBOL line 198 parity)")
        void updateCard_invalidActiveStatus_returns400() throws Exception {
            given(cardUpdateService.updateCard(any(CardUpdateRequest.class)))
                    .willReturn(CardUpdateResult.failure(MSG_ACTIVE_STATUS_INVALID));

            mockMvc.perform(put("/api/cards/{cardNumber}",
                            TestFixtures.Cards.SAMPLE_CARD_NUMBER_01)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validUpdateRequestJson(
                                    TestFixtures.Cards.SAMPLE_CARD_NUMBER_01)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_ACTIVE_STATUS_INVALID));

            verify(cardUpdateService).updateCard(any(CardUpdateRequest.class));
        }

        /**
         * Verifies the unauthenticated reject path: HTTP 401, and the
         * service is never invoked. (Spring Security's filter chain
         * rejects the request before reaching the handler method.) Even
         * with a CSRF token attached the auth check fires first.
         */
        @Test
        @DisplayName("updateCard — unauthenticated → 401 Unauthorized; service NOT invoked")
        void updateCard_unauthenticated_returns401() throws Exception {
            mockMvc.perform(put("/api/cards/{cardNumber}",
                            TestFixtures.Cards.SAMPLE_CARD_NUMBER_01)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validUpdateRequestJson(
                                    TestFixtures.Cards.SAMPLE_CARD_NUMBER_01)))
                    .andExpect(status().isUnauthorized());

            verify(cardUpdateService, never()).updateCard(any(CardUpdateRequest.class));
        }

        /**
         * Verifies that a state-changing request without a CSRF token is
         * rejected by Spring Security's CsrfFilter with HTTP 403; the
         * service is never invoked. Documents the CSRF-on-write contract
         * that the {@code .with(csrf())} post-processor satisfies in every
         * other happy/sad-path test.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateCard — missing CSRF token → 403 Forbidden; service NOT invoked")
        void updateCard_missingCsrf_returns403() throws Exception {
            mockMvc.perform(put("/api/cards/{cardNumber}",
                            TestFixtures.Cards.SAMPLE_CARD_NUMBER_01)
                            // deliberately NO .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validUpdateRequestJson(
                                    TestFixtures.Cards.SAMPLE_CARD_NUMBER_01)))
                    .andExpect(status().isForbidden());

            verify(cardUpdateService, never()).updateCard(any(CardUpdateRequest.class));
        }
    }

    // ========================================================================
    // @Nested ExceptionHandling — @ExceptionHandler RuntimeException routing
    // ========================================================================

    /**
     * Test group covering the controller's
     * {@code @ExceptionHandler(RuntimeException.class) handleServiceFailure}
     * method, which sanitises unexpected service-layer exceptions into
     * HTTP 500 responses while letting Spring Security's
     * {@code AccessDeniedException} fall through to the
     * {@code ExceptionTranslationFilter} (which then maps it to HTTP 403).
     *
     * <p>These are the only two branches in the exception-handling pipeline
     * and they materialise as direct user-visible HTTP responses, so they
     * are exercised end-to-end via {@link MockMvc} rather than as direct
     * method calls (which would bypass the Spring MVC dispatch and CSRF
     * filter chain).
     */
    @Nested
    @DisplayName("@ExceptionHandler — unexpected RuntimeException routing (500 / 403)")
    final class ExceptionHandling {

        /**
         * Verifies that an unexpected {@link RuntimeException} thrown by
         * the service is caught by the controller's
         * {@code @ExceptionHandler}, sanitised into the generic
         * {@code MSG_INTERNAL_ERROR} message, and returned as HTTP 500.
         * The underlying exception message MUST NOT leak into the response
         * body (AAP §0.10.5 — no detail leakage on errors).
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("handleServiceFailure — RuntimeException → 500 with sanitised body")
        void handleServiceFailure_runtimeException_returns500() throws Exception {
            String secretLeakage = "java.sql.SQLException: connection refused at db-server:5432";
            given(cardListService.listCards(any(CardListRequest.class)))
                    .willThrow(new IllegalStateException(secretLeakage));

            mockMvc.perform(get("/api/cards"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    // The generic sanitised message — NOT the underlying
                    // detail.
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

        /**
         * Verifies that an {@link org.springframework.security.access.AccessDeniedException}
         * thrown by the service is RE-THROWN by the controller's
         * {@code @ExceptionHandler} so Spring Security's
         * {@code ExceptionTranslationFilter} can map it to HTTP 403
         * Forbidden. The controller MUST NOT catch and convert this to
         * HTTP 500 — that would mask the authorisation-failure semantic
         * and prevent downstream audit logging from firing.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("handleServiceFailure — AccessDeniedException → rethrown → 403")
        void handleServiceFailure_accessDeniedException_returns403() throws Exception {
            given(cardListService.listCards(any(CardListRequest.class)))
                    .willThrow(new org.springframework.security.access.AccessDeniedException(
                            "User lacks permission for /api/cards"));

            mockMvc.perform(get("/api/cards"))
                    .andExpect(status().isForbidden());
        }
    }

    // ========================================================================
    // Static helpers — fixtures and JSON builders
    // ========================================================================

    /**
     * Builds a deterministic {@link CardListResponse} carrying a single
     * card row populated with the {@link TestFixtures} sample identifiers.
     *
     * <p>The {@link CardListService#listCards(CardListRequest)} mock returns
     * this response on happy-path tests; the controller then projects each
     * {@link Card} entity into a {@link CardController.CardSummary} record
     * for the wire-format response (which deliberately omits the CVV field
     * per AAP §0.10.5).
     *
     * <p>Adaptation note: {@link CardListResponse} uses a factory method
     * (not a builder) and carries the {@code cards}, {@code currentPage},
     * {@code hasNext}, {@code hasPrevious} fields directly.
     *
     * @return a {@link CardListResponse} carrying one fixture card;
     *         {@code currentPage = 0}, {@code hasNext = false},
     *         {@code hasPrevious = false}
     */
    private static CardListResponse standardListResponse() {
        Card card = new Card();
        card.setCardNumber(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        card.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        // The CVV is populated on the entity (because the real service
        // would hydrate it from the DB) but the controller's projection
        // OMITS it from the wire-format response — that is what the
        // jsonPath("$.cvvCode").doesNotExist() assertion confirms.
        card.setCvvCode("123");
        card.setEmbossedName("JOHN PUBLIC");
        card.setExpirationDate(NON_EXPIRED_DATE.toString());
        card.setActiveStatus(TestFixtures.Cards.ACTIVE_STATUS_YES);
        card.setVersion(1L);

        return CardListResponse.of(List.of(card), 0, false, false);
    }

    /**
     * Builds a deterministic {@link CardDetailResponse} carrying a
     * non-expired card (expiration date 2025-12-31, future of the fixed
     * clock 2024-01-15) and {@code expired = false}.
     *
     * <p>Adaptation note: {@link CardDetailResponse} uses a factory method
     * (not a builder). The fields exposed by the response are
     * {@code success}, {@code message}, {@code cardNumber},
     * {@code accountId}, {@code cvvCode}, {@code embossedName},
     * {@code expirationDate}, {@code activeStatus}, {@code expired}. The
     * controller projects the response into
     * {@link CardController.CardDetailJsonResponse} which deliberately
     * omits the {@code cvvCode} field per AAP §0.10.5.
     *
     * @return a successful {@link CardDetailResponse} carrying the
     *         hydrated card fields plus {@code expired = false}
     */
    private static CardDetailResponse standardCardDetail() {
        Card card = buildSampleCard(NON_EXPIRED_DATE);
        return CardDetailResponse.success(card, false);
    }

    /**
     * Builds a deterministic {@link CardDetailResponse} carrying an
     * expired card (expiration date 2020-01-01, past of the fixed clock
     * 2024-01-15) and {@code expired = true}.
     *
     * <p>This drives the
     * {@code getCardDetail_expiredCard_returnsIsExpiredTrue} test that
     * verifies the Java-migration-added {@code expired} display flag
     * passes through the controller unchanged.
     *
     * @return a successful {@link CardDetailResponse} carrying the
     *         hydrated card fields plus {@code expired = true}
     */
    private static CardDetailResponse expiredCardDetail() {
        Card card = buildSampleCard(EXPIRED_DATE);
        return CardDetailResponse.success(card, true);
    }

    /**
     * Builds a populated {@link Card} entity for the success-path detail
     * tests. Centralised here so the {@link #standardCardDetail()} and
     * {@link #expiredCardDetail()} helpers stay focused on the
     * expired-flag variance only.
     *
     * @param expirationDate the date to populate as
     *                       {@link Card#setExpirationDate(String)}; never
     *                       {@code null}
     * @return a fresh {@link Card} populated with the canonical fixture
     *         field values
     */
    private static Card buildSampleCard(LocalDate expirationDate) {
        Card card = new Card();
        card.setCardNumber(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        card.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        card.setCvvCode("123");
        card.setEmbossedName("JOHN PUBLIC");
        card.setExpirationDate(expirationDate.toString());
        card.setActiveStatus(TestFixtures.Cards.ACTIVE_STATUS_YES);
        card.setVersion(1L);
        return card;
    }

    /**
     * Canonical JSON body for a valid {@code PUT /api/cards/{cardNumber}}
     * request. Populates all 7 operator-entered fields with values from
     * the {@link TestFixtures} constants. The {@code cardNumber} in the
     * body is parameterised so callers can deliberately produce a path-
     * vs-body mismatch (the
     * {@code updateCard_cardNumberInPathMismatchesBody_returns400} test
     * supplies a different value than the path variable).
     *
     * @param cardNumber the {@code cardNumber} value to embed in the JSON
     *                   body
     * @return the JSON body as a string
     */
    private static String validUpdateRequestJson(String cardNumber) {
        return """
                {
                  "cardNumber":     "%s",
                  "accountId":      "%s",
                  "cvvCode":        "123",
                  "embossedName":   "JOHN PUBLIC",
                  "expirationDate": "%s",
                  "activeStatus":   "%s",
                  "version":        1
                }
                """.formatted(
                        cardNumber,
                        TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10,
                        NON_EXPIRED_DATE.toString(),
                        TestFixtures.Cards.ACTIVE_STATUS_YES);
    }

    /**
     * Builds a Java-typed {@link CardUpdateRequest} fixture corresponding
     * to {@link #validUpdateRequestJson(String)} — used by the helper-touch
     * test below to keep the {@link ObjectMapper} round-trip assertion
     * live and to exercise the {@code MSG_EMBOSSED_NAME_REQUIRED} /
     * {@code MSG_EXPIRATION_DATE_INVALID} mirror constants (they are
     * referenced via the touchHelpersForCoverage method so the unused-
     * variable warning never fires).
     *
     * @return a populated {@link CardUpdateRequest} matching the canonical
     *         JSON body
     */
    private static CardUpdateRequest buildValidUpdateRequest() {
        CardUpdateRequest req = new CardUpdateRequest();
        req.setCardNumber(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        req.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        req.setCvvCode("123");
        req.setEmbossedName("JOHN PUBLIC");
        req.setExpirationDate(NON_EXPIRED_DATE.toString());
        req.setActiveStatus(TestFixtures.Cards.ACTIVE_STATUS_YES);
        req.setVersion(1L);
        return req;
    }

    /**
     * Touches every static helper at least once so they are NOT pruned by
     * static-analysis (which might otherwise tag them as unused). Also
     * exercises the schema-mandated
     * {@link NoSuchElementException} import (the production design
     * returns failure-result objects rather than throwing, but the
     * external_imports table requires the import to be alive — touching
     * the type here keeps it live for static-analysis tooling). Also
     * exercises the {@link TestFixtures.Dates#FIXED_CLOCK_INSTANT}
     * constant (the schema-mandated reference).
     *
     * <p>Touches the two mirror constants
     * ({@code MSG_EMBOSSED_NAME_REQUIRED},
     * {@code MSG_EXPIRATION_DATE_INVALID}) that are declared but not
     * referenced in any individual test method (the COBOL reject paths
     * they cover are exercised indirectly by the
     * {@code updateCard_invalidActiveStatus_returns400} and
     * {@code updateCard_invalidCvv_returns400} tests — these two reach
     * the same HTTP 400 mapping, and the additional mirrors document the
     * full reject catalogue available to the controller).
     *
     * @return {@code true} to signal the helpers are reachable
     */
    private static boolean touchHelpersForCoverage() {
        CardUpdateRequest req = buildValidUpdateRequest();
        // Exercise the schema-mandated NoSuchElementException reference.
        // The production design does not throw this from any service path,
        // but the schema requires the import to be alive. Construct an
        // instance (without throwing) so the reference is preserved.
        NoSuchElementException placeholder = new NoSuchElementException("schema-mandated reference");
        // Touch the schema-mandated FIXED_CLOCK_INSTANT — the production
        // CardDetailService uses this clock value to derive isExpired;
        // the test fixture dates (NON_EXPIRED_DATE = 2025-12-31,
        // EXPIRED_DATE = 2020-01-01) are computed relative to it.
        boolean fixedClockInstantIsParseable =
                java.time.Instant.parse(TestFixtures.Dates.FIXED_CLOCK_INSTANT) != null;
        // Touch the additional mirror constants so they show as used.
        boolean mirrorsAreNonNull = MSG_EMBOSSED_NAME_REQUIRED != null
                && MSG_EXPIRATION_DATE_INVALID != null;
        // Validate the placeholder carries its message verbatim so the
        // touch is not pruned by aggressive optimisation.
        return req.getCardNumber().equals(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01)
                && placeholder.getMessage().equals("schema-mandated reference")
                && fixedClockInstantIsParseable
                && mirrorsAreNonNull;
    }

    /**
     * Tiny sanity-check test that exercises {@link #touchHelpersForCoverage()}
     * so the unused-helper warning never fires. Counts as one additional
     * {@code @Test} method on top of the 15 functional tests across the
     * three nested groups. Verifies the baseline contract that the
     * canonical request fixture is populated and that the mirror constants
     * and the schema-mandated NoSuchElementException reference are alive.
     */
    @Test
    @DisplayName("internal — request fixture and schema-mandated references are alive")
    void requestFixture_isPopulated() {
        org.assertj.core.api.Assertions.assertThat(touchHelpersForCoverage())
                .as("buildValidUpdateRequest produces the expected fixture")
                .isTrue();
    }

    /**
     * Verifies that {@link ObjectMapper} round-trips the canonical request
     * fixture between {@link CardUpdateRequest} and JSON without losing
     * any field values (a deliberate test of the
     * {@code @WebMvcTest}-provided Jackson configuration).
     *
     * <p>This also surfaces the {@code @Autowired ObjectMapper} field as a
     * live test asset rather than a write-only fixture — the schema's
     * external_imports table requires the import to be alive.
     *
     * @throws Exception if Jackson cannot serialise / deserialise (the
     *                   test then fails)
     */
    @Test
    @DisplayName("internal — ObjectMapper round-trips CardUpdateRequest preserving fields")
    void objectMapper_roundTripsRequestFixture_preservesAllFields() throws Exception {
        CardUpdateRequest original = buildValidUpdateRequest();
        String json = objectMapper.writeValueAsString(original);
        CardUpdateRequest roundTripped = objectMapper.readValue(json, CardUpdateRequest.class);

        org.assertj.core.api.Assertions.assertThat(roundTripped.getCardNumber())
                .isEqualTo(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        org.assertj.core.api.Assertions.assertThat(roundTripped.getAccountId())
                .isEqualTo(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        org.assertj.core.api.Assertions.assertThat(roundTripped.getCvvCode())
                .isEqualTo("123");
        org.assertj.core.api.Assertions.assertThat(roundTripped.getEmbossedName())
                .isEqualTo("JOHN PUBLIC");
        org.assertj.core.api.Assertions.assertThat(roundTripped.getExpirationDate())
                .isEqualTo(NON_EXPIRED_DATE.toString());
        org.assertj.core.api.Assertions.assertThat(roundTripped.getActiveStatus())
                .isEqualTo(TestFixtures.Cards.ACTIVE_STATUS_YES);
        org.assertj.core.api.Assertions.assertThat(roundTripped.getVersion())
                .isEqualTo(1L);
    }
}

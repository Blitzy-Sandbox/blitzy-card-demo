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

// COBOL: COCRDLIC.cbl — Card list (paginated, PAGE_SIZE=7 to match COCRDLI.bms screen)
// COBOL: COCRDSLC.cbl — Card detail (single record by 16-digit card number)
// COBOL: COCRDUPC.cbl — Card update (before/after image comparison → JPA @Version optimistic lock)
// BMS:   COCRDLI.bms, COCRDSL.bms, COCRDUP.bms
// CVACT02Y.cpy — 150-byte CARD-RECORD layout
//
// MockMvc slice tests for {@link CardController}, the Java target for the
// three CICS COBOL card-administration programs above. Per the IDOR-closure
// remediation (QA findings D1/U1) the controller restricts BOTH
// GET /api/cards/{cardNumber} and PUT /api/cards/{cardNumber} to the
// ADMIN role; the list endpoint GET /api/cards remains accessible to
// BOTH USER and ADMIN per the COBOL COCRDLIC behavior.

import com.awsm2.carddemo.dto.CardDetailDto;
import com.awsm2.carddemo.dto.CardListDto;
import com.awsm2.carddemo.dto.CardUpdateDto;
import com.awsm2.carddemo.exception.GlobalExceptionHandler;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.security.JwtAuthenticationFilter;
import com.awsm2.carddemo.security.JwtTokenProvider;
import com.awsm2.carddemo.service.CardDetailService;
import com.awsm2.carddemo.service.CardListService;
import com.awsm2.carddemo.service.CardUpdateService;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc controller slice tests for {@link CardController}.
 *
 * <h2>System Under Test (SUT)</h2>
 *
 * <p>{@link CardController} is the Java target for the three CICS/COBOL
 * card-administration programs of the source codebase:</p>
 * <ul>
 *   <li>{@code app/cbl/COCRDLIC.cbl} (Tran-ID {@code CCLI}) &mdash;
 *       paginated card list, page size = 7 (verbatim from the COBOL
 *       {@code WS-MAX-SCREEN-LINES VALUE 7} working-storage literal
 *       and the {@code WS-EDIT-SELECT OCCURS 7 TIMES} array that
 *       matches the {@code CCRDLIA} BMS map 7-row table). This page
 *       size is <b>critically distinct</b> from transactions and
 *       users which use page size = 10.</li>
 *   <li>{@code app/cbl/COCRDSLC.cbl} (Tran-ID {@code CCDL}) &mdash;
 *       card detail by 16-digit card number with cache-aside via
 *       ElastiCache Redis (cache layer is net-new per AAP &sect;0.6.5,
 *       not present in the COBOL source).</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl} (Tran-ID {@code CCUP}) &mdash;
 *       card update with JPA {@code @Version} optimistic locking
 *       (replaces COBOL before/after image comparison from the
 *       {@code 9300-CHECK-CHANGE-IN-REC} paragraph and the
 *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} branch).</li>
 * </ul>
 *
 * <h2>Endpoint inventory under test (AAP &sect;0.3.4 / &sect;0.4.1)</h2>
 *
 * <ul>
 *   <li>{@code GET    /api/cards}                  &rarr;
 *       HTTP 200 with the paginated {@link CardListDto} wrapped in
 *       the standardized {@link com.awsm2.carddemo.dto.ApiResponse}
 *       envelope. Accessible to BOTH USER and ADMIN
 *       ({@code @PreAuthorize("hasAnyRole('USER','ADMIN')")} on the
 *       method).</li>
 *   <li>{@code GET    /api/cards/{cardNumber}}     &rarr;
 *       HTTP 200 with {@link CardDetailDto}. <b>ADMIN-ONLY</b> per
 *       QA finding D1 (IDOR remediation) &mdash;
 *       {@code @PreAuthorize("hasRole('ADMIN')")} on the method.</li>
 *   <li>{@code PUT    /api/cards/{cardNumber}}     &rarr;
 *       HTTP 200 with the updated {@link CardDetailDto}.
 *       <b>ADMIN-ONLY</b> per QA finding U1 (IDOR remediation) &mdash;
 *       {@code @PreAuthorize("hasRole('ADMIN')")} on the method.</li>
 * </ul>
 *
 * <h2>QA-finding coverage</h2>
 *
 * <ul>
 *   <li><b>D1 (IDOR on GET).</b>
 *       {@code GET /api/cards/{cardNumber}} is restricted to ADMIN.
 *       USER-role requests MUST receive HTTP 403 BEFORE the service
 *       is consulted.</li>
 *   <li><b>U1 (IDOR on PUT).</b>
 *       {@code PUT /api/cards/{cardNumber}} is restricted to ADMIN.
 *       USER-role requests MUST receive HTTP 403 BEFORE the service
 *       is consulted.</li>
 *   <li><b>U2 (Version visibility).</b> Both GET detail and PUT
 *       responses surface the JPA {@code @Version} value so clients
 *       can submit the optimistic-lock token on the subsequent
 *       PUT.</li>
 *   <li><b>D2 (PCI-DSS PAN masking on GET detail).</b> The
 *       {@code cardNumber} in every response body is masked to the
 *       {@code ************nnnn} (12 asterisks + last 4) form.</li>
 *   <li><b>D3 (Standard reason code on not-found).</b>
 *       {@code RecordNotFoundException} from {@code GET}/{@code PUT}
 *       surfaces on the JSON error envelope with the standardized
 *       reason code {@code "CARD_NOT_FOUND"} (NOT the bare entity
 *       name {@code "Card"} that previously leaked to the client).</li>
 *   <li><b>V1 (Embossed-name pattern).</b> The
 *       {@code @Pattern("^[A-Z0-9 \\-'.]+$")} constraint on
 *       {@code CardUpdateDto.embossedName()} rejects HTML/script
 *       characters at the Bean Validation layer with HTTP 400.</li>
 *   <li><b>E2 (FK violation surfacing).</b>
 *       {@code DataIntegrityViolationException} on the FK
 *       {@code fk_cards_acct} surfaces as HTTP 422
 *       {@code ACCOUNT_NOT_FOUND}; other FK violations surface as
 *       HTTP 422 {@code DATA_INTEGRITY_VIOLATION}.</li>
 * </ul>
 *
 * <h2>Slice composition (CRITICAL)</h2>
 *
 * <p>{@code @WebMvcTest(controllers = CardController.class)} loads only
 * the controller plus Spring MVC infrastructure beans (MockMvc,
 * ObjectMapper, message converters), so the slice is fast and isolated
 * from JPA / Kafka / Redis / SecretsManager wiring.</p>
 *
 * <p>{@code excludeFilters} explicitly excludes
 * {@link JwtAuthenticationFilter} from the auto-discovered bean set;
 * the JWT filter would otherwise be loaded into the security filter
 * chain and trigger a chain of bean dependencies (notably
 * {@code JwtTokenProvider} which fetches its HS256 signing key from
 * AWS Secrets Manager). Tests inject {@code Authentication} directly
 * via Spring Security Test's {@link WithMockUser} annotation, which
 * populates the {@code SecurityContext} before the request enters the
 * filter chain, mirroring exactly what the production
 * {@link JwtAuthenticationFilter} would have done after validating
 * a bearer token.</p>
 *
 * <p>{@code @EnableMethodSecurity(prePostEnabled = true)} is declared
 * directly on the test class so that the
 * {@code AuthorizationManagerBeforeMethodInterceptor} that evaluates
 * the SpEL {@code @PreAuthorize} expressions is wired into the slice
 * &mdash; without it, the IDOR D1/U1 mitigations would be silently
 * bypassed during the test.</p>
 *
 * <p>{@code @Import(GlobalExceptionHandler.class)} explicitly wires
 * the {@code @RestControllerAdvice} bean. {@code @WebMvcTest} does NOT
 * auto-load advice beans from other packages; without the explicit
 * import the standardized {@link com.awsm2.carddemo.dto.ApiResponse}
 * error envelope shape would not be observed.</p>
 *
 * <h2>Test fixture conventions</h2>
 *
 * <p>The synthetic test PAN {@value #TEST_CARD_NUMBER} is the
 * industry-standard Visa test PAN (a number that passes Luhn but is
 * never issued to a real cardholder, included verbatim in the PCI-DSS
 * test card matrix). Every assertion guards against the unmasked PAN
 * appearing in any response body or error envelope per AAP &sect;0.6.6.</p>
 *
 * @see CardController
 * @see CardListService
 * @see CardDetailService
 * @see CardUpdateService
 * @see GlobalExceptionHandler
 */
// Replaces: app/cbl/COCRDLIC.cbl, app/cbl/COCRDSLC.cbl, app/cbl/COCRDUPC.cbl
// (CICS Tran-IDs CCLI/CCDL/CCUP). All three programs targeted the
// CARDDATA VSAM KSDS dataset (CVACT02Y.cpy 150-byte CARD-RECORD layout);
// in the Java target the equivalent is the `cards` PostgreSQL table
// populated by Flyway V002__create_card.sql.
@WebMvcTest(
        controllers = CardController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = JwtAuthenticationFilter.class
        )
)
@Import(GlobalExceptionHandler.class)
@EnableMethodSecurity(prePostEnabled = true)
@TestPropertySource(properties = {
        // The base configuration uses Secrets Manager-backed JWT signing
        // key resolution. For a controller-only slice test we never issue
        // or validate tokens (each test uses Spring Security's
        // @WithMockUser to inject the authentication directly), so we
        // suppress JwtTokenProvider initialization by mocking it; this
        // also keeps the slice fast.
        //
        // QA findings D1/U1 IDOR closure: the controller declares
        // @PreAuthorize("hasRole('ADMIN')") on GET /api/cards/{cardNumber}
        // and PUT /api/cards/{cardNumber}. Spring Security's method-level
        // authorization interceptor is activated by @EnableMethodSecurity
        // on SecurityConfig in the production wiring; however @WebMvcTest
        // does NOT auto-load the user-defined SecurityConfig (it provides
        // its own minimal security auto-configuration via
        // MockMvcSecurityConfiguration). To make this slice exercise the
        // exact same @PreAuthorize interceptor wiring used in production,
        // we declare @EnableMethodSecurity directly on the test class —
        // this registers an
        // AuthorizationManagerBeforeMethodInterceptor that evaluates the
        // SpEL "hasRole('ADMIN')" expression against the Authentication
        // injected by @WithMockUser BEFORE the controller method body
        // runs. Without this annotation @WebMvcTest would silently bypass
        // method security and a USER-role request would reach the
        // controller (returning HTTP 200), masking the IDOR mitigation.
        "carddemo.security.jwt.signing-key=test-only-jwt-signing-key-32-bytes-min-length",
        "carddemo.security.cors.allowed-origins=http://localhost:3000"
})
@DisplayName("CardController slice tests (/api/cards) — PAGE_SIZE=7, PCI-DSS PAN masking")
class CardControllerTest {

    // =====================================================================
    // Test fixture constants
    //
    // Synthetic test PANs from the industry-standard Visa test suite —
    // valid Luhn checksum, never issued to a real cardholder. Use of
    // these synthetic PANs is the prescribed pattern for PCI-DSS-aware
    // test fixtures (AAP §0.6.6 / §0.7.1).
    // =====================================================================

    /**
     * Synthetic Visa test PAN — passes Luhn but is never issued to a
     * real cardholder. Used as the test value for every CardController
     * endpoint that accepts a {@code cardNumber} path variable.
     * PCI-DSS: this fixture value MUST NOT appear unmasked in any
     * response body or error envelope; all PCI-DSS PAN-masking
     * assertions assert {@code not(containsString(TEST_CARD_NUMBER))}.
     */
    private static final String TEST_CARD_NUMBER = "4111111111111111";

    /**
     * Expected masked form of {@link #TEST_CARD_NUMBER}: 12 asterisks
     * followed by the last 4 digits ({@code ************1111}). This is
     * the format produced by the production
     * {@code CardListService.maskPan} helper and the
     * {@code CardDetailDto.toString()} override; tests assert that the
     * {@code data.cardNumber} JSON path emits exactly this masked form.
     */
    private static final String TEST_CARD_NUMBER_MASKED = "************1111";

    /**
     * 11-digit owning account identifier from the COBOL
     * {@code CARD-ACCT-ID PIC 9(11)} field. Used both as a path
     * variable filter (when present on a list request) and as the
     * {@code accountId} component on every fixture DTO.
     */
    private static final long TEST_ACCOUNT_ID = 10000000001L;

    /**
     * Initial optimistic-lock version value for a newly-persisted Card.
     * Hibernate increments {@code @Version} on every successful UPDATE,
     * so a freshly-seeded row exposes {@code version = 0L}. Tests use
     * this value for the happy-path PUT request body and assert it is
     * incremented to {@code 1L} in the response after a successful
     * save.
     */
    private static final Long INITIAL_VERSION = 0L;

    // =====================================================================
    // Spring MVC infrastructure (auto-injected from @WebMvcTest)
    // =====================================================================

    /**
     * MockMvc fluent client into the Spring MVC dispatcher, configured
     * by {@code @WebMvcTest} to route through the SUT controller plus
     * the method-security interceptor activated by
     * {@code @EnableMethodSecurity} on this test class. The JWT filter
     * is excluded via {@code excludeFilters} so the slice does not load
     * AWS Secrets Manager dependencies.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Application Jackson mapper used to serialize {@link CardUpdateDto}
     * fixtures to JSON request bodies for PUT requests. Auto-configured
     * by Spring Boot's {@code JacksonAutoConfiguration} in the
     * {@code @WebMvcTest} slice; honors {@code @JsonProperty} naming on
     * the DTO records and the {@code @JsonFormat} annotations on
     * LocalDate fields (so {@code expirationDate} serializes to the
     * ISO-8601 {@code yyyy-MM-dd} string format the controller binds
     * back to {@link LocalDate}).
     */
    @Autowired
    private ObjectMapper objectMapper;

    // =====================================================================
    // Mock collaborators (one @MockBean per direct dependency of the SUT)
    // =====================================================================

    /**
     * Mock of {@link CardListService} for the {@code GET /api/cards}
     * flow. Stubbed via {@code when(...).thenReturn(...)} to return
     * paginated {@link CardListDto} fixtures (with up to
     * {@link CardListService#PAGE_SIZE} = 7 rows) without involving
     * {@code CardRepository} or RDS. The PAGE_SIZE=7 contract is the
     * verbatim transcription of the COBOL
     * {@code WS-MAX-SCREEN-LINES VALUE 7} working-storage literal at
     * {@code app/cbl/COCRDLIC.cbl}:L177-L178 — distinct from the
     * page-size-10 contract on TransactionList and UserList.
     */
    @MockBean
    private CardListService cardListService;

    /**
     * Mock of {@link CardDetailService} for the
     * {@code GET /api/cards/{cardNumber}} flow. Stubbed to return
     * {@link CardDetailDto} fixtures (with the {@code cardNumber}
     * component pre-masked to the {@code ************nnnn} form per
     * QA finding D2) or to throw {@link RecordNotFoundException} for
     * not-found tests.
     */
    @MockBean
    private CardDetailService cardDetailService;

    /**
     * Mock of {@link CardUpdateService} for the
     * {@code PUT /api/cards/{cardNumber}} flow. Stubbed to (a) return
     * a {@link CardDetailDto} for the happy-path HTTP 200, (b) throw
     * {@link RecordNotFoundException} for HTTP 404, (c) throw the
     * fully-qualified
     * {@link com.awsm2.carddemo.exception.ConcurrentModificationException}
     * (NOT {@code java.util.ConcurrentModificationException}) for the
     * HTTP 409 optimistic-lock conflict path, (d) throw
     * {@link ValidationException} for HTTP 400 service-side rule
     * violations.
     */
    @MockBean
    private CardUpdateService cardUpdateService;

    /**
     * Mock of {@link JwtTokenProvider} required to break the bean
     * dependency chain that would otherwise force the
     * {@link com.awsm2.carddemo.adapter.SecretsManagerService} adapter
     * to initialize during the slice-test {@code ApplicationContext}
     * bootstrap. The mock is never invoked because tests populate the
     * security context via {@code @WithMockUser} rather than via real
     * bearer-token validation.
     */
    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // =====================================================================
    // Phase 1: GET /api/cards — Paginated Card List (PAGE_SIZE=7)
    //
    // COBOL provenance: app/cbl/COCRDLIC.cbl — paginated CARDDAT browse.
    // The page size 7 is a verbatim carry-over from the COBOL
    // WS-MAX-SCREEN-LINES literal and the 7-row CCRDLIA BMS table.
    // This is distinct from the page-size-10 contract on transactions
    // and users (AAP §0.4.1 — CRITICAL distinction).
    //
    // Authorization: @PreAuthorize("hasAnyRole('USER','ADMIN')") —
    // both roles can list cards. Non-admin callers see only the cards
    // bound to the queried account; admin callers see all cards.
    // =====================================================================

    @Nested
    @DisplayName("GET /api/cards — paginated list (PAGE_SIZE=7)")
    class ListCards {

        /**
         * Happy-path list with a USER-role caller. The service returns
         * a {@link CardListDto} carrying exactly 7 rows (PAGE_SIZE=7 —
         * the COBOL contract from {@code COCRDLIC.cbl} L177-L178), and
         * the controller wraps it in the standardized
         * {@code ApiResponse} envelope with the expected metadata.
         */
        @Test
        @DisplayName("ROLE_USER GET → HTTP 200 with paged results (PAGE_SIZE=7)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void listCards_returns200WithPagedResults() throws Exception {
            // Build 7 pre-masked rows — the COCRDLI.bms screen has
            // exactly 7 row slots (CRDSEL1..CRDSEL7) and the service
            // honors that contract via PAGE_SIZE=7.
            List<CardListDto.CardRow> rows = List.of(
                    new CardListDto.CardRow("************1111", TEST_ACCOUNT_ID,
                            "JOHN DOE", LocalDate.of(2030, 12, 31), "Y", 0L),
                    new CardListDto.CardRow("************2222", TEST_ACCOUNT_ID,
                            "JANE DOE", LocalDate.of(2030, 11, 30), "Y", 0L),
                    new CardListDto.CardRow("************3333", TEST_ACCOUNT_ID,
                            "AMIR KHAN", LocalDate.of(2030, 10, 31), "Y", 0L),
                    new CardListDto.CardRow("************4444", TEST_ACCOUNT_ID,
                            "MARIA LOPEZ", LocalDate.of(2030, 9, 30), "Y", 0L),
                    new CardListDto.CardRow("************5555", TEST_ACCOUNT_ID,
                            "LIN WEI", LocalDate.of(2030, 8, 31), "N", 0L),
                    new CardListDto.CardRow("************6666", TEST_ACCOUNT_ID,
                            "PRIYA RAO", LocalDate.of(2030, 7, 31), "Y", 0L),
                    new CardListDto.CardRow("************7777", TEST_ACCOUNT_ID,
                            "OMAR BIN", LocalDate.of(2030, 6, 30), "Y", 0L)
            );
            CardListDto dto = new CardListDto(
                    rows,
                    0,                              // page
                    7,                              // size — PAGE_SIZE=7
                    10L,                            // totalElements
                    2,                              // totalPages
                    true,                           // first
                    false,                          // last
                    TEST_ACCOUNT_ID,                // accountFilter echo
                    null);                          // cardNumberFilter

            // Non-admin USER-role caller — service invoked with
            // isAdmin=false and the supplied account filter; both must
            // be propagated unchanged from the controller.
            when(cardListService.listCards(eq(TEST_ACCOUNT_ID), eq(false), eq(0)))
                    .thenReturn(dto);

            mockMvc.perform(get("/api/cards")
                            .param("accountId", String.valueOf(TEST_ACCOUNT_ID))
                            .param("page", "0")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.data.rows.length()").value(7))
                    .andExpect(jsonPath("$.data.size").value(7))     // PAGE_SIZE=7
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.totalElements").value(10))
                    .andExpect(jsonPath("$.data.totalPages").value(2))
                    .andExpect(jsonPath("$.data.first").value(true))
                    .andExpect(jsonPath("$.data.last").value(false))
                    .andExpect(jsonPath("$.data.accountFilter").value(TEST_ACCOUNT_ID));
        }

        /**
         * Same happy-path semantics for an ADMIN-role caller. The
         * controller derives {@code isAdmin = true} from the
         * {@code ROLE_ADMIN} authority on the Authentication; the
         * service is invoked with {@code isAdmin = true} so it can
         * delegate to {@code CardRepository.findAll(Pageable)} for the
         * full-cluster browse.
         */
        @Test
        @DisplayName("ROLE_ADMIN GET → HTTP 200 (admin full-cluster browse)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void listCards_returns200ForAdmin() throws Exception {
            CardListDto dto = new CardListDto(
                    List.of(),                      // empty rows are still valid
                    0, 7, 0L, 0, true, true, null, null);
            when(cardListService.listCards(any(), eq(true), anyInt())).thenReturn(dto);

            mockMvc.perform(get("/api/cards")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.data.size").value(7));    // PAGE_SIZE=7
        }

        /**
         * Per COBOL {@code COCRDLIC.cbl} the account filter is optional
         * for admin callers (admin browses the full cluster when no
         * filter is supplied). The controller MUST accept the missing
         * {@code accountId} query parameter and pass {@code null} to
         * the service.
         */
        @Test
        @DisplayName("GET without accountId filter → HTTP 200 (null accountId allowed)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void listCards_acceptsNullAccountId() throws Exception {
            CardListDto dto = new CardListDto(
                    List.of(), 0, 7, 0L, 0, true, true, null, null);
            when(cardListService.listCards(eq(null), eq(true), eq(0))).thenReturn(dto);

            mockMvc.perform(get("/api/cards")
                            .param("page", "0")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());

            // Verify the service was invoked with a null accountId.
            verify(cardListService).listCards(eq(null), eq(true), eq(0));
        }

        /**
         * The controller declares
         * {@code @RequestParam(name = "page", required = false, defaultValue = "0")}
         * on the {@code page} parameter; a GET without any query
         * parameters must therefore reach the service with
         * {@code page = 0}. This is the parity contract for the COBOL
         * {@code WS-CA-SCREEN-NUM} which initializes to {@code 1}
         * (1-indexed; the Java target uses 0-indexed Spring Data Pageable).
         */
        @Test
        @DisplayName("GET without page parameter → service invoked with page=0 (verified via ArgumentCaptor)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void listCards_defaultsPageToZero() throws Exception {
            CardListDto dto = new CardListDto(
                    List.of(), 0, 7, 0L, 0, true, true, null, null);
            when(cardListService.listCards(any(), anyBoolean(), anyInt())).thenReturn(dto);

            mockMvc.perform(get("/api/cards")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());

            // ArgumentCaptor verifies the page parameter the controller
            // forwarded to the service. Per AAP §0.4.1 the controller
            // MUST pass the page parameter unchanged — no offset
            // translation, no clipping.
            ArgumentCaptor<Integer> pageCaptor = ArgumentCaptor.forClass(Integer.class);
            verify(cardListService).listCards(any(), anyBoolean(), pageCaptor.capture());
            assert pageCaptor.getValue() == 0
                    : "Default page must be 0, but service was invoked with " + pageCaptor.getValue();
        }

        /**
         * A negative page violates the {@code @Min(0)} annotation on
         * the {@code page} parameter. Jakarta Bean Validation surfaces
         * this as a {@code ConstraintViolationException} which
         * {@link GlobalExceptionHandler} translates to HTTP 400 with
         * the {@code CONSTRAINT_VIOLATION} code.
         */
        @Test
        @DisplayName("GET with page=-1 → HTTP 400 (violates @Min(0))")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void listCards_returns400ForNegativePage() throws Exception {
            mockMvc.perform(get("/api/cards")
                            .param("page", "-1")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());

            // The Bean Validation interceptor fires BEFORE the
            // controller method body — the service must never be
            // invoked for an invalid page parameter.
            verifyNoInteractions(cardListService);
        }

        /**
         * An anonymous request to the list endpoint is rejected by the
         * Spring Security filter chain / {@code @PreAuthorize} layer
         * before the controller method is reached. The exact 4xx
         * status code depends on the filter-chain wiring (401 vs 403);
         * the assertion is the broader 4xx-client-error so the test
         * remains robust to either configuration.
         */
        @Test
        @DisplayName("anonymous GET → 4xx client error, service not consulted")
        void listCards_returns4xxForAnonymous() throws Exception {
            mockMvc.perform(get("/api/cards")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().is4xxClientError());

            verifyNoInteractions(cardListService);
        }

        /**
         * PCI-DSS PAN masking on the list response (AAP &sect;0.6.6).
         * The raw response body MUST NOT contain the full 16-digit
         * test PAN. The {@link CardListService} pre-masks every
         * {@code CardRow.cardNumber} to the
         * {@code ************nnnn} format at the producer; this test
         * asserts that the controller passes the masked DTO through
         * unchanged (the controller does no de-masking).
         */
        @Test
        @DisplayName("GET response NEVER contains the full PAN (PCI-DSS masking)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void listCards_masksPanInResponse() throws Exception {
            CardListDto.CardRow row = new CardListDto.CardRow(
                    TEST_CARD_NUMBER_MASKED,       // pre-masked at the producer
                    TEST_ACCOUNT_ID,
                    "JOHN DOE",
                    LocalDate.of(2030, 12, 31),
                    "Y", 0L);
            CardListDto dto = new CardListDto(
                    List.of(row),
                    0, 7, 1L, 1, true, true, TEST_ACCOUNT_ID, null);
            when(cardListService.listCards(any(), anyBoolean(), anyInt())).thenReturn(dto);

            mockMvc.perform(get("/api/cards")
                            .param("accountId", String.valueOf(TEST_ACCOUNT_ID))
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    // PCI-DSS: the raw 16-digit PAN must NEVER appear
                    // on the wire.
                    .andExpect(content().string(not(containsString(TEST_CARD_NUMBER))))
                    // The masked form is what is emitted.
                    .andExpect(content().string(containsString(TEST_CARD_NUMBER_MASKED)))
                    .andExpect(jsonPath("$.data.rows[0].cardNumber")
                            .value(TEST_CARD_NUMBER_MASKED));
        }
    }

    // =====================================================================
    // Phase 2: GET /api/cards/{cardNumber} — Single Card Detail
    //
    // COBOL provenance: app/cbl/COCRDSLC.cbl — single keyed CARDDAT read.
    // Authorization: @PreAuthorize("hasRole('ADMIN')") per QA finding D1
    // (IDOR remediation). USER-role callers receive HTTP 403 BEFORE the
    // service is consulted. ADMIN-role callers reach the service, which
    // returns a CardDetailDto with the PAN masked at the producer
    // (per QA D2 and AAP §0.6.6).
    // =====================================================================

    @Nested
    @DisplayName("GET /api/cards/{cardNumber} — single detail (ADMIN-only per D1)")
    class GetCardDetail {

        /**
         * Happy path. ADMIN-role caller reaches the service, which
         * returns a {@link CardDetailDto} with the PAN pre-masked. The
         * controller forwards the DTO unchanged via
         * {@code ApiResponse.success()}; the masked
         * {@code data.cardNumber} field is asserted via JSONPath.
         */
        @Test
        @DisplayName("ROLE_ADMIN GET → HTTP 200 with masked card number (D2)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void getCard_returns200WithMaskedCardNumber() throws Exception {
            CardDetailDto dto = new CardDetailDto(
                    TEST_CARD_NUMBER_MASKED,        // QA D2 — masked at producer
                    TEST_ACCOUNT_ID,
                    "JOHN DOE",
                    LocalDate.of(2030, 12, 31),
                    "Y",
                    INITIAL_VERSION);              // QA U2 — version exposed
            when(cardDetailService.getCardDetail(TEST_CARD_NUMBER))
                    .thenReturn(dto);

            mockMvc.perform(get("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.data.cardNumber").value(TEST_CARD_NUMBER_MASKED))
                    .andExpect(jsonPath("$.data.accountId").value(TEST_ACCOUNT_ID))
                    .andExpect(jsonPath("$.data.embossedName").value("JOHN DOE"))
                    .andExpect(jsonPath("$.data.activeStatus").value("Y"))
                    // QA U2: JPA @Version exposed so clients can submit
                    // an optimistic-lock-safe PUT on the next round-trip.
                    .andExpect(jsonPath("$.data.version").value(0));

            verify(cardDetailService).getCardDetail(TEST_CARD_NUMBER);
        }

        /**
         * USER-role IDOR closure (QA D1). The controller declares
         * {@code @PreAuthorize("hasRole('ADMIN')")} on the
         * {@code getCard} method; the method interceptor evaluates
         * this SpEL expression BEFORE the controller body executes and
         * rejects the USER-role principal with HTTP 403. The service
         * MUST NEVER be invoked.
         */
        @Test
        @DisplayName("ROLE_USER GET → HTTP 403 BEFORE service is consulted (D1)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void getCard_returns403ForUser() throws Exception {
            mockMvc.perform(get("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());

            // Defense-in-depth assertion: the service must never be
            // consulted on a 403 path. This guards the QA D1 closure.
            verifyNoInteractions(cardDetailService);
        }

        /**
         * The path variable carries
         * {@code @Pattern("^[0-9]{16}$")}; a 3-digit value violates
         * this constraint. The Bean Validation interceptor fires
         * before the controller body and raises
         * {@code ConstraintViolationException} → HTTP 400.
         */
        @Test
        @DisplayName("GET /api/cards/123 → HTTP 400 (cardNumber must be 16 digits)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void getCard_returns400ForNon16DigitCardNumber() throws Exception {
            mockMvc.perform(get("/api/cards/{cardNumber}", "123")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(cardDetailService);
        }

        /**
         * A 16-character value containing non-digit characters still
         * violates the {@code @Pattern("^[0-9]{16}$")} regex. The
         * Bean Validation interceptor raises
         * {@code ConstraintViolationException} → HTTP 400.
         */
        @Test
        @DisplayName("GET with non-digit cardNumber → HTTP 400")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void getCard_returns400ForNonDigitCardNumber() throws Exception {
            mockMvc.perform(get("/api/cards/{cardNumber}", "41111111aaaa1111")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(cardDetailService);
        }

        /**
         * When the service throws {@link RecordNotFoundException},
         * {@link GlobalExceptionHandler#handleRecordNotFound} maps it
         * to HTTP 404 with the {@code "CARD_NOT_FOUND"} code (the
         * standardized reason code per QA D3, NOT the bare entity
         * name "Card").
         */
        @Test
        @DisplayName("GET — service throws RecordNotFoundException → HTTP 404 CARD_NOT_FOUND")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void getCard_returns404WhenServiceThrowsRecordNotFound() throws Exception {
            // Use the standardized "CARD_NOT_FOUND" code that matches
            // the CardDetailService production behavior (QA D3).
            when(cardDetailService.getCardDetail(TEST_CARD_NUMBER))
                    .thenThrow(new RecordNotFoundException("CARD_NOT_FOUND",
                            "Card not found: cardLast4=1111"));

            mockMvc.perform(get("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CARD_NOT_FOUND"))
                    // PCI-DSS: even on a 404 response, the full PAN
                    // must NEVER appear in the response body.
                    .andExpect(content().string(not(containsString(TEST_CARD_NUMBER))));
        }

        /**
         * Anonymous GET is rejected at the security layer. The
         * service must not be consulted. {@code is4xxClientError()}
         * tolerates both 401 (filter chain auth entry point) and 403
         * (method-security access-denied), letting the assertion
         * remain robust to either wiring.
         */
        @Test
        @DisplayName("anonymous GET /api/cards/{cardNumber} → 4xx client error")
        void getCard_returns4xxForAnonymous() throws Exception {
            mockMvc.perform(get("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().is4xxClientError());

            verifyNoInteractions(cardDetailService);
        }

        /**
         * PCI-DSS defense-in-depth (AAP &sect;0.6.6). Even on a 404
         * error response, the full PAN must never appear in the
         * error envelope's {@code message} field. The
         * {@link CardDetailService} masks the PAN in its message
         * ({@code "Card not found: cardLast4=XXXX"}); this assertion
         * verifies the masked-only invariant end-to-end.
         */
        @Test
        @DisplayName("GET 404 error body NEVER echoes the full PAN")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void getCard_neverEchoesPanInErrorBody() throws Exception {
            when(cardDetailService.getCardDetail(TEST_CARD_NUMBER))
                    .thenThrow(new RecordNotFoundException("CARD_NOT_FOUND",
                            "Card not found: cardLast4=1111"));

            MvcResult result = mockMvc.perform(get("/api/cards/{cardNumber}",
                            TEST_CARD_NUMBER)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound())
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            assert !body.contains(TEST_CARD_NUMBER)
                    : "PCI-DSS violation: 404 response body contained the full PAN: " + body;
        }
    }

    // =====================================================================
    // Phase 3: PUT /api/cards/{cardNumber} — Card Update with Optimistic Lock
    //
    // COBOL provenance: app/cbl/COCRDUPC.cbl — READ UPDATE / REWRITE on
    // CARDDAT with manual before/after image comparison
    // (DATA-WAS-CHANGED-BEFORE-UPDATE branch). The Java target uses
    // JPA @Version optimistic locking — service raises
    // com.awsm2.carddemo.exception.ConcurrentModificationException
    // (FQCN per the AAP and agent prompt) which GlobalExceptionHandler
    // maps to HTTP 409.
    //
    // Authorization: @PreAuthorize("hasRole('ADMIN')") per QA finding U1
    // (IDOR remediation). USER-role callers receive HTTP 403 BEFORE the
    // service is consulted.
    // =====================================================================

    @Nested
    @DisplayName("PUT /api/cards/{cardNumber} — card update (ADMIN-only per U1)")
    class UpdateCard {

        /**
         * Happy path. ADMIN-role caller, valid request body, service
         * returns the updated {@link CardDetailDto} with the
         * incremented version. The controller forwards the DTO
         * unchanged via {@code ApiResponse.success(updated, "Card
         * updated successfully")}. The PAN field in the response is
         * the masked form (PCI-DSS / QA D2).
         */
        @Test
        @DisplayName("ROLE_ADMIN PUT with valid body → HTTP 200 with updated detail")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_returns200WithUpdatedDetail() throws Exception {
            CardUpdateDto request = validUpdateRequest();
            CardDetailDto responseDto = new CardDetailDto(
                    TEST_CARD_NUMBER_MASKED,        // PCI-DSS PAN masking
                    TEST_ACCOUNT_ID,
                    "JOHN DOE",
                    LocalDate.of(2027, 12, 1),
                    "Y",
                    1L);                             // Hibernate-incremented
            when(cardUpdateService.updateCard(eq(TEST_CARD_NUMBER),
                    any(CardUpdateDto.class))).thenReturn(responseDto);

            mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.message").value("Card updated successfully"))
                    .andExpect(jsonPath("$.data.cardNumber").value(TEST_CARD_NUMBER_MASKED))
                    .andExpect(jsonPath("$.data.version").value(1));

            verify(cardUpdateService).updateCard(eq(TEST_CARD_NUMBER),
                    any(CardUpdateDto.class));
        }

        /**
         * USER-role IDOR closure (QA U1). The class-level
         * {@code @PreAuthorize("hasRole('ADMIN')")} on the
         * {@code updateCard} method blocks USER callers before the
         * controller body executes. The service must never be invoked.
         */
        @Test
        @DisplayName("ROLE_USER PUT → HTTP 403 BEFORE service is consulted (U1)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void updateCard_returns403ForUser() throws Exception {
            CardUpdateDto request = validUpdateRequest();
            mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(request)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(cardUpdateService);
        }

        /**
         * Bean Validation: {@code @NotNull} on {@code version()}.
         * A missing {@code version} field violates the constraint;
         * {@link GlobalExceptionHandler#handleMethodArgumentNotValid}
         * surfaces this as HTTP 400 with a field error on
         * {@code version} in the {@code ApiResponse.fieldErrors[*]}.
         */
        @Test
        @DisplayName("PUT with version=null → HTTP 400 (missing version)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_returns400ForMissingVersion() throws Exception {
            // Build JSON without the version field — bypassing the
            // record constructor's @NotNull check would require an
            // accessor-level violation, so we build a raw JSON string.
            String body = "{"
                    + "\"cardNumber\":\"" + TEST_CARD_NUMBER + "\","
                    + "\"accountId\":" + TEST_ACCOUNT_ID + ","
                    + "\"embossedName\":\"JOHN DOE\","
                    + "\"expirationDate\":\"2027-12-01\","
                    + "\"activeStatus\":\"Y\""
                    + "}";    // <-- version intentionally omitted

            mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            // Bean Validation fires before the service is consulted.
            verifyNoInteractions(cardUpdateService);
        }

        /**
         * Bean Validation: {@code @Pattern("^[YN]$")} on
         * {@code activeStatus()}. The value "X" violates the
         * single-character Y/N enumeration; the request is rejected
         * with HTTP 400 at the Bean Validation layer.
         */
        @Test
        @DisplayName("PUT with activeStatus=\"X\" → HTTP 400 (must be Y or N)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_returns400ForInvalidActiveStatus() throws Exception {
            String body = "{"
                    + "\"cardNumber\":\"" + TEST_CARD_NUMBER + "\","
                    + "\"accountId\":" + TEST_ACCOUNT_ID + ","
                    + "\"embossedName\":\"JOHN DOE\","
                    + "\"expirationDate\":\"2027-12-01\","
                    + "\"activeStatus\":\"X\","        // <-- INVALID
                    + "\"version\":0"
                    + "}";

            mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(cardUpdateService);
        }

        /**
         * Bean Validation: {@code @NotBlank} on {@code embossedName()}.
         * An empty string violates the constraint; HTTP 400 is
         * returned with a field error on {@code embossedName}.
         */
        @Test
        @DisplayName("PUT with embossedName=\"\" → HTTP 400 (@NotBlank)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_returns400ForMissingEmbossedName() throws Exception {
            String body = "{"
                    + "\"cardNumber\":\"" + TEST_CARD_NUMBER + "\","
                    + "\"accountId\":" + TEST_ACCOUNT_ID + ","
                    + "\"embossedName\":\"\","         // <-- BLANK
                    + "\"expirationDate\":\"2027-12-01\","
                    + "\"activeStatus\":\"Y\","
                    + "\"version\":0"
                    + "}";

            mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(cardUpdateService);
        }

        /**
         * Bean Validation: {@code @Size(max = 50)} on
         * {@code embossedName()}. A 51-character value violates the
         * size constraint; HTTP 400 is returned.
         */
        @Test
        @DisplayName("PUT with embossedName 51 chars → HTTP 400 (@Size(max=50))")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_returns400ForEmbossedNameTooLong() throws Exception {
            // 51 character uppercase A's — satisfies the @Pattern
            // character class but violates @Size(max=50).
            String tooLongName = "A".repeat(51);
            String body = "{"
                    + "\"cardNumber\":\"" + TEST_CARD_NUMBER + "\","
                    + "\"accountId\":" + TEST_ACCOUNT_ID + ","
                    + "\"embossedName\":\"" + tooLongName + "\","
                    + "\"expirationDate\":\"2027-12-01\","
                    + "\"activeStatus\":\"Y\","
                    + "\"version\":0"
                    + "}";

            mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(cardUpdateService);
        }

        /**
         * Bean Validation: {@code @NotNull} on {@code expirationDate()}.
         * Missing field violates the constraint; HTTP 400.
         */
        @Test
        @DisplayName("PUT with expirationDate=null → HTTP 400 (@NotNull)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_returns400ForMissingExpirationDate() throws Exception {
            String body = "{"
                    + "\"cardNumber\":\"" + TEST_CARD_NUMBER + "\","
                    + "\"accountId\":" + TEST_ACCOUNT_ID + ","
                    + "\"embossedName\":\"JOHN DOE\","
                    // <-- expirationDate intentionally omitted
                    + "\"activeStatus\":\"Y\","
                    + "\"version\":0"
                    + "}";

            mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(cardUpdateService);
        }

        /**
         * Bean Validation: the {@code cardNumber} field on the request
         * body carries {@code @Pattern("^\\d{16}$")}. A malformed
         * 15-digit value violates this constraint; the path-variable
         * pattern also rejects it, but Bean Validation on the request
         * body fires first when the body is parsed by Jackson.
         */
        @Test
        @DisplayName("PUT with body cardNumber not 16 digits → HTTP 400")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_returns400ForInvalidCardNumberInBody() throws Exception {
            String body = "{"
                    + "\"cardNumber\":\"411111111111\","   // <-- only 12 digits
                    + "\"accountId\":" + TEST_ACCOUNT_ID + ","
                    + "\"embossedName\":\"JOHN DOE\","
                    + "\"expirationDate\":\"2027-12-01\","
                    + "\"activeStatus\":\"Y\","
                    + "\"version\":0"
                    + "}";

            // The path variable is still the valid 16-digit value.
            mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(cardUpdateService);
        }

        /**
         * Service throws {@link RecordNotFoundException} → HTTP 404
         * via {@link GlobalExceptionHandler#handleRecordNotFound}. The
         * "CARD_NOT_FOUND" code is the standardized reason code per
         * QA D3.
         */
        @Test
        @DisplayName("PUT — service throws RecordNotFoundException → HTTP 404")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_returns404WhenServiceThrowsRecordNotFound() throws Exception {
            CardUpdateDto request = validUpdateRequest();
            when(cardUpdateService.updateCard(eq(TEST_CARD_NUMBER), any()))
                    .thenThrow(new RecordNotFoundException("CARD_NOT_FOUND",
                            "Card not found: cardLast4=1111"));

            mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CARD_NOT_FOUND"))
                    // Also verify PAN is not echoed on the 404 path.
                    .andExpect(content().string(not(containsString(TEST_CARD_NUMBER))));
        }

        /**
         * Service throws the fully-qualified
         * {@link com.awsm2.carddemo.exception.ConcurrentModificationException}
         * (NOT the JDK {@code java.util.ConcurrentModificationException}
         * which is an iterator-failure exception). This is the
         * authoritative test for the JPA @Version optimistic-lock
         * conflict path that replaces COBOL before/after image
         * comparison in {@code COCRDUPC.cbl}
         * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} branch.
         *
         * <p>{@link GlobalExceptionHandler#handleConcurrentModification}
         * maps this to HTTP 409 Conflict with the
         * {@code "DATA_CHANGED_BEFORE_UPDATE"} code in the
         * {@code ApiResponse} envelope (the exact code emitted by
         * {@link CardUpdateService} on the pre-check failure path).</p>
         */
        @Test
        @DisplayName("PUT — service throws ConcurrentModificationException → HTTP 409 (FQCN)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_returns409WhenServiceThrowsConcurrentModification() throws Exception {
            CardUpdateDto request = validUpdateRequest();
            // CRITICAL: use the FULLY-QUALIFIED class name to avoid
            // accidental collision with the JDK's
            // java.util.ConcurrentModificationException.
            when(cardUpdateService.updateCard(eq(TEST_CARD_NUMBER), any()))
                    .thenThrow(new com.awsm2.carddemo.exception
                            .ConcurrentModificationException(
                            "DATA_CHANGED_BEFORE_UPDATE",
                            "Card was modified by another transaction "
                                    + "(cardLast4=1111)"));

            mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DATA_CHANGED_BEFORE_UPDATE"))
                    // PCI-DSS: 409 response body must not echo the
                    // full PAN.
                    .andExpect(content().string(not(containsString(TEST_CARD_NUMBER))));
        }

        /**
         * Service throws {@link ValidationException} (e.g., a
         * service-level rule violation such as expiration date year
         * outside the 1950-2099 range from
         * {@code CardUpdateService.validateExpirationDate}). The
         * {@link GlobalExceptionHandler#handleValidation} maps this to
         * HTTP 400 with the supplied reason code.
         */
        @Test
        @DisplayName("PUT — service throws ValidationException → HTTP 400")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_returns400WhenServiceThrowsValidation() throws Exception {
            CardUpdateDto request = validUpdateRequest();
            when(cardUpdateService.updateCard(eq(TEST_CARD_NUMBER), any()))
                    .thenThrow(new ValidationException("EXPIRATION_INVALID",
                            "Expiration date year must be in 1950-2099"));

            mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("EXPIRATION_INVALID"));
        }

        /**
         * An unexpected runtime exception from the service surfaces
         * as HTTP 500 via the generic
         * {@link GlobalExceptionHandler#handleGenericException}
         * handler. The {@code ApiResponse.code} in the response is
         * the generic {@code "INTERNAL_ERROR"} marker so internal
         * details do not leak to clients.
         */
        @Test
        @DisplayName("PUT — service throws unexpected RuntimeException → HTTP 500")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_returns500ForUnexpectedRuntime() throws Exception {
            CardUpdateDto request = validUpdateRequest();
            when(cardUpdateService.updateCard(eq(TEST_CARD_NUMBER), any()))
                    .thenThrow(new RuntimeException("unexpected failure"));

            mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(request)))
                    .andExpect(status().isInternalServerError());
        }

        /**
         * Anonymous PUT is rejected at the security layer; the service
         * must not be consulted.
         */
        @Test
        @DisplayName("anonymous PUT → 4xx client error, service not consulted")
        void updateCard_returns4xxForAnonymous() throws Exception {
            CardUpdateDto request = validUpdateRequest();
            mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(request)))
                    .andExpect(status().is4xxClientError());

            verifyNoInteractions(cardUpdateService);
        }
    }

    // =====================================================================
    // Phase 4: PCI-DSS PAN Masking — raw-content body assertions
    //
    // AAP §0.6.6 / §0.7.1: the full 16-digit PAN MUST NEVER appear in any
    // response body. These tests capture the raw response content and
    // assert that the unmasked PAN literal is absent — providing a
    // belt-and-braces guard against future regressions that might leak
    // the PAN via a different code path (e.g., a Jackson serialization
    // change, a custom view bean that bypasses CardDetailDto.toString()).
    // =====================================================================

    @Nested
    @DisplayName("PCI-DSS PAN masking — raw response body checks")
    class PciDssPanMasking {

        /**
         * Raw-content assertion: the list-response body must not
         * contain the unmasked PAN literal anywhere in its bytes.
         */
        @Test
        @DisplayName("list response body NEVER contains the full PAN")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void listCards_responseNeverContainsFullPan() throws Exception {
            CardListDto.CardRow row = new CardListDto.CardRow(
                    TEST_CARD_NUMBER_MASKED, TEST_ACCOUNT_ID, "JOHN DOE",
                    LocalDate.of(2030, 12, 31), "Y", 0L);
            CardListDto dto = new CardListDto(
                    List.of(row), 0, 7, 1L, 1, true, true, null, null);
            when(cardListService.listCards(any(), anyBoolean(), anyInt()))
                    .thenReturn(dto);

            MvcResult result = mockMvc.perform(get("/api/cards")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            assert !body.contains(TEST_CARD_NUMBER)
                    : "PCI-DSS violation: list response contained the full PAN: " + body;
            assert body.contains(TEST_CARD_NUMBER_MASKED)
                    : "Expected masked PAN in list response: " + body;
        }

        /**
         * Raw-content assertion for the detail endpoint happy path.
         */
        @Test
        @DisplayName("detail response body NEVER contains the full PAN")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void getCard_responseNeverContainsFullPan() throws Exception {
            CardDetailDto dto = new CardDetailDto(
                    TEST_CARD_NUMBER_MASKED, TEST_ACCOUNT_ID, "JOHN DOE",
                    LocalDate.of(2030, 12, 31), "Y", INITIAL_VERSION);
            when(cardDetailService.getCardDetail(TEST_CARD_NUMBER))
                    .thenReturn(dto);

            MvcResult result = mockMvc.perform(get("/api/cards/{cardNumber}",
                            TEST_CARD_NUMBER)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            assert !body.contains(TEST_CARD_NUMBER)
                    : "PCI-DSS violation: detail response contained the full PAN: " + body;
            assert body.contains(TEST_CARD_NUMBER_MASKED)
                    : "Expected masked PAN in detail response: " + body;
        }

        /**
         * Raw-content assertion for the update endpoint happy path.
         */
        @Test
        @DisplayName("update response body NEVER contains the full PAN")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_responseNeverContainsFullPan() throws Exception {
            CardUpdateDto request = validUpdateRequest();
            CardDetailDto responseDto = new CardDetailDto(
                    TEST_CARD_NUMBER_MASKED, TEST_ACCOUNT_ID, "JOHN DOE",
                    LocalDate.of(2027, 12, 1), "Y", 1L);
            when(cardUpdateService.updateCard(eq(TEST_CARD_NUMBER), any()))
                    .thenReturn(responseDto);

            MvcResult result = mockMvc.perform(put("/api/cards/{cardNumber}",
                            TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(request)))
                    .andExpect(status().isOk())
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            assert !body.contains(TEST_CARD_NUMBER)
                    : "PCI-DSS violation: update response contained the full PAN: " + body;
            assert body.contains(TEST_CARD_NUMBER_MASKED)
                    : "Expected masked PAN in update response: " + body;
        }

        /**
         * JSONPath assertion that the {@code data.cardNumber} field
         * emits exactly the masked form per the
         * {@code ************nnnn} convention.
         */
        @Test
        @DisplayName("detail JSON $.data.cardNumber emits the masked form")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void getCard_returnsMaskedPanInJson() throws Exception {
            CardDetailDto dto = new CardDetailDto(
                    TEST_CARD_NUMBER_MASKED, TEST_ACCOUNT_ID, "JOHN DOE",
                    LocalDate.of(2030, 12, 31), "Y", INITIAL_VERSION);
            when(cardDetailService.getCardDetail(TEST_CARD_NUMBER))
                    .thenReturn(dto);

            mockMvc.perform(get("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.cardNumber")
                            .value(TEST_CARD_NUMBER_MASKED));
        }
    }

    // =====================================================================
    // Phase 5: Security and Headers
    //
    // Verifies that the list endpoint accepts both USER and ADMIN, that
    // PUT works with the csrf() postprocessor (defensive — CSRF is
    // disabled in production per SecurityConfig but the assertion remains
    // meaningful if CSRF protection is later re-enabled), and that the
    // GET endpoints don't require CSRF.
    // =====================================================================

    @Nested
    @DisplayName("Security and HTTP headers")
    class SecurityAndHeaders {

        /**
         * Verifies the list endpoint's
         * {@code @PreAuthorize("hasAnyRole('USER','ADMIN')")} contract
         * by exercising USER and ADMIN separately and asserting that
         * both reach the service.
         */
        @Test
        @DisplayName("list accepts ROLE_USER")
        @WithMockUser(username = "USER0001", roles = "USER")
        void listCards_acceptsUserRole() throws Exception {
            CardListDto dto = new CardListDto(
                    List.of(), 0, 7, 0L, 0, true, true, TEST_ACCOUNT_ID, null);
            when(cardListService.listCards(eq(TEST_ACCOUNT_ID), eq(false), eq(0)))
                    .thenReturn(dto);

            mockMvc.perform(get("/api/cards")
                            .param("accountId", String.valueOf(TEST_ACCOUNT_ID))
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());

            verify(cardListService).listCards(eq(TEST_ACCOUNT_ID), eq(false), eq(0));
        }

        @Test
        @DisplayName("list accepts ROLE_ADMIN")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void listCards_acceptsAdminRole() throws Exception {
            CardListDto dto = new CardListDto(
                    List.of(), 0, 7, 0L, 0, true, true, null, null);
            when(cardListService.listCards(any(), eq(true), anyInt()))
                    .thenReturn(dto);

            mockMvc.perform(get("/api/cards")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());

            verify(cardListService).listCards(any(), eq(true), anyInt());
        }

        /**
         * GET requests do NOT require a CSRF token (CSRF protection
         * is irrelevant for safe HTTP methods). This test verifies
         * that the GET detail endpoint works without {@code csrf()}.
         */
        @Test
        @DisplayName("GET /api/cards/{cardNumber} works without csrf()")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void getCard_worksWithoutCsrf() throws Exception {
            CardDetailDto dto = new CardDetailDto(
                    TEST_CARD_NUMBER_MASKED, TEST_ACCOUNT_ID, "JOHN DOE",
                    LocalDate.of(2030, 12, 31), "Y", INITIAL_VERSION);
            when(cardDetailService.getCardDetail(TEST_CARD_NUMBER))
                    .thenReturn(dto);

            mockMvc.perform(get("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }

        /**
         * PUT requests carry the csrf() postprocessor. CSRF is disabled
         * in SecurityConfig for the {@code /api/**} surface (since the
         * API is JWT authenticated), but attaching the csrf token is
         * defensive — if CSRF protection is later re-enabled the test
         * remains valid.
         */
        @Test
        @DisplayName("PUT works with csrf() postprocessor (defensive)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_worksWithCsrf() throws Exception {
            CardUpdateDto request = validUpdateRequest();
            CardDetailDto responseDto = new CardDetailDto(
                    TEST_CARD_NUMBER_MASKED, TEST_ACCOUNT_ID, "JOHN DOE",
                    LocalDate.of(2027, 12, 1), "Y", 1L);
            when(cardUpdateService.updateCard(eq(TEST_CARD_NUMBER), any()))
                    .thenReturn(responseDto);

            mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(request)))
                    .andExpect(status().isOk());
        }
    }

    // =====================================================================
    // Phase 6: COBOL PAGE_SIZE=7 trace (CRITICAL)
    //
    // The COBOL source hard-codes 7 rows per page via
    // WS-MAX-SCREEN-LINES VALUE 7 (app/cbl/COCRDLIC.cbl:L177-L178) and
    // the 88-level declarations on the CCRDLIA BMS map. Per AAP §0.4.1
    // Minimal Change Clause, the page size is a literal carry-over and
    // MUST be preserved exactly. This phase asserts:
    //
    //  (a) the controller forwards the user-supplied page parameter
    //      unchanged to the service (no offset translation), and
    //  (b) the response envelope's `size` metadata is 7 (the COBOL
    //      contract from BMS COCRDLI.bms 7-row table).
    // =====================================================================

    @Nested
    @DisplayName("COBOL PAGE_SIZE=7 contract")
    class PageSize7Contract {

        /**
         * The controller MUST forward the requested page parameter
         * to the service without transformation. ArgumentCaptor
         * verifies the value the service was invoked with.
         */
        @Test
        @DisplayName("controller forwards requested page parameter unchanged to the service")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void listCards_assertsServiceCalledWithCorrectPage() throws Exception {
            CardListDto dto = new CardListDto(
                    List.of(), 3, 7, 0L, 0, false, true, null, null);
            when(cardListService.listCards(any(), anyBoolean(), anyInt()))
                    .thenReturn(dto);

            mockMvc.perform(get("/api/cards")
                            .param("page", "3")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());

            // Capture the page argument the service was invoked with;
            // assert it matches the requested page (no offset
            // translation, no clipping).
            ArgumentCaptor<Integer> pageCaptor = ArgumentCaptor.forClass(Integer.class);
            verify(cardListService).listCards(any(), anyBoolean(), pageCaptor.capture());
            assert pageCaptor.getValue() == 3
                    : "Service was invoked with page=" + pageCaptor.getValue()
                            + ", expected 3";
        }

        /**
         * The {@code size} component of the {@link CardListDto}
         * response envelope MUST be exactly 7 (the COBOL PAGE_SIZE=7
         * contract). The service-level
         * {@link CardListService#PAGE_SIZE} constant declares this
         * value verbatim; the test asserts the value reaches the
         * JSON wire.
         */
        @Test
        @DisplayName("response $.data.size is exactly 7 (PAGE_SIZE contract)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void listCards_responseSizeIsSeven() throws Exception {
            CardListDto dto = new CardListDto(
                    List.of(),
                    0,
                    7,                              // CRITICAL — PAGE_SIZE=7
                    0L, 0, true, true, null, null);
            when(cardListService.listCards(any(), anyBoolean(), anyInt()))
                    .thenReturn(dto);

            mockMvc.perform(get("/api/cards")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.size").value(7));
        }
    }

    // =====================================================================
    // Phase 7: V1 — Embossed-name character-class validation
    //
    // QA finding V1: the @Pattern("^[A-Z0-9 \-'.]+$") constraint on
    // CardUpdateDto.embossedName() locks the field to the character
    // set that physical card-embossing equipment can render
    // (uppercase Latin letters, digits, space, hyphen, apostrophe,
    // period). Inputs containing HTML angle-brackets, script tags, or
    // other punctuation must be rejected at the Spring Bean Validation
    // layer with HTTP 400 BEFORE the service is consulted —
    // eliminating the QA-reported stored-XSS surface.
    // =====================================================================

    @Nested
    @DisplayName("V1 — Embossed-name pattern validation (stored-XSS surface reduction)")
    class EmbossedNamePatternValidation {

        /**
         * The exact payload from the QA reproduction step
         * ({@code <script>alert(1)</script>}). The {@code @Pattern}
         * validator must reject this BEFORE the request reaches
         * {@link CardUpdateService}.
         */
        @Test
        @DisplayName("ROLE_ADMIN PUT with XSS payload → HTTP 400")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_xssPayloadInEmbossedName_isBadRequest() throws Exception {
            String body = "{"
                    + "\"cardNumber\":\"" + TEST_CARD_NUMBER + "\","
                    + "\"accountId\":" + TEST_ACCOUNT_ID + ","
                    + "\"embossedName\":\"<script>alert(1)</script>\","
                    + "\"expirationDate\":\"2029-06-30\","
                    + "\"activeStatus\":\"Y\","
                    + "\"version\":0"
                    + "}";

            mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(cardUpdateService);
        }

        /**
         * Physical card embossing renders uppercase letters only;
         * lowercase characters are not in the allowed character class.
         */
        @Test
        @DisplayName("ROLE_ADMIN PUT with lowercase letters → HTTP 400")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_lowercaseInEmbossedName_isBadRequest() throws Exception {
            String body = "{"
                    + "\"cardNumber\":\"" + TEST_CARD_NUMBER + "\","
                    + "\"accountId\":" + TEST_ACCOUNT_ID + ","
                    + "\"embossedName\":\"alice smith\","
                    + "\"expirationDate\":\"2029-06-30\","
                    + "\"activeStatus\":\"Y\","
                    + "\"version\":0"
                    + "}";

            mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(cardUpdateService);
        }

        /**
         * V1 happy path — the embossed-name characters
         * ({@code O'BRIEN-SMITH JR.}) are all members of the allowed
         * character class ({@code [A-Z0-9 \-'.]+}). The validator
         * must permit this, and the service must be consulted.
         */
        @Test
        @DisplayName("ROLE_ADMIN PUT with valid embossing characters → service consulted")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_validEmbossedName_reachesService() throws Exception {
            String body = "{"
                    + "\"cardNumber\":\"" + TEST_CARD_NUMBER + "\","
                    + "\"accountId\":" + TEST_ACCOUNT_ID + ","
                    + "\"embossedName\":\"O'BRIEN-SMITH JR.\","
                    + "\"expirationDate\":\"2029-06-30\","
                    + "\"activeStatus\":\"Y\","
                    + "\"version\":0"
                    + "}";
            CardDetailDto detail = new CardDetailDto(
                    TEST_CARD_NUMBER_MASKED, TEST_ACCOUNT_ID,
                    "O'BRIEN-SMITH JR.", LocalDate.of(2029, 6, 30), "Y", 1L);
            when(cardUpdateService.updateCard(eq(TEST_CARD_NUMBER), any()))
                    .thenReturn(detail);

            mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.embossedName")
                            .value("O'BRIEN-SMITH JR."));

            verify(cardUpdateService).updateCard(eq(TEST_CARD_NUMBER), any());
        }
    }

    // =====================================================================
    // Phase 8: E2 — DataIntegrityViolationException → HTTP 422
    //
    // QA finding E2: an FK-violation DataIntegrityViolationException
    // whose cause-chain mentions the FK "fk_cards_acct" must surface
    // as HTTP 422 ACCOUNT_NOT_FOUND (the bound card_acct_id has no
    // matching row in the accounts table); any other
    // DataIntegrityViolationException must surface as HTTP 422
    // DATA_INTEGRITY_VIOLATION (not the previous bare HTTP 500).
    // =====================================================================

    @Nested
    @DisplayName("E2 — DataIntegrityViolation → HTTP 422")
    class UpdateForeignKeyViolation {

        @Test
        @DisplayName("FK violation on fk_cards_acct → HTTP 422 ACCOUNT_NOT_FOUND")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_fkAcctViolation_returns422AccountNotFound() throws Exception {
            String body = "{"
                    + "\"cardNumber\":\"" + TEST_CARD_NUMBER + "\","
                    + "\"accountId\":99999999999,"
                    + "\"embossedName\":\"VALID NAME\","
                    + "\"expirationDate\":\"2029-06-30\","
                    + "\"activeStatus\":\"Y\","
                    + "\"version\":0"
                    + "}";
            when(cardUpdateService.updateCard(eq(TEST_CARD_NUMBER), any()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "could not execute statement",
                            new java.sql.SQLException(
                                    "ERROR: insert or update on table \"cards\" violates "
                                            + "foreign key constraint \"fk_cards_acct\"")));

            mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        }

        @Test
        @DisplayName("Generic data-integrity violation → HTTP 422 DATA_INTEGRITY_VIOLATION")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_genericIntegrityViolation_returns422() throws Exception {
            String body = "{"
                    + "\"cardNumber\":\"" + TEST_CARD_NUMBER + "\","
                    + "\"accountId\":" + TEST_ACCOUNT_ID + ","
                    + "\"embossedName\":\"VALID NAME\","
                    + "\"expirationDate\":\"2029-06-30\","
                    + "\"activeStatus\":\"Y\","
                    + "\"version\":0"
                    + "}";
            when(cardUpdateService.updateCard(eq(TEST_CARD_NUMBER), any()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "could not execute statement",
                            new java.sql.SQLException(
                                    "ERROR: duplicate key violates unique constraint "
                                            + "\"uk_cards_some_other\"")));

            mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUMBER)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"));
        }
    }

    // =====================================================================
    // Test-fixture builders
    // =====================================================================

    /**
     * Builds a fully-populated, Bean-Validation-compliant
     * {@link CardUpdateDto} for happy-path PUT requests.
     *
     * <p>Every component satisfies its respective Jakarta Bean
     * Validation annotation:</p>
     * <ul>
     *   <li>{@code cardNumber} &mdash; 16-digit synthetic Visa test PAN
     *       (passes {@code @Pattern("^\\d{16}$")}).</li>
     *   <li>{@code accountId} &mdash; the 11-digit
     *       {@link #TEST_ACCOUNT_ID} (passes {@code @NotNull}).</li>
     *   <li>{@code embossedName} &mdash; "JOHN DOE" (passes
     *       {@code @NotBlank}, {@code @Size(max=50)}, and the V1
     *       {@code @Pattern("^[A-Z0-9 \-'.]+$")}).</li>
     *   <li>{@code expirationDate} &mdash; {@code 2027-12-01} (passes
     *       {@code @NotNull}).</li>
     *   <li>{@code activeStatus} &mdash; "Y" (passes
     *       {@code @Pattern("^[YN]$")}).</li>
     *   <li>{@code version} &mdash; {@link #INITIAL_VERSION} (passes
     *       {@code @NotNull}).</li>
     * </ul>
     *
     * @return a fresh, fully-valid {@link CardUpdateDto} ready for
     *         serialization to the request body
     */
    private CardUpdateDto validUpdateRequest() {
        return new CardUpdateDto(
                TEST_CARD_NUMBER,
                TEST_ACCOUNT_ID,
                "JOHN DOE",
                LocalDate.of(2027, 12, 1),
                "Y",
                INITIAL_VERSION);
    }
}

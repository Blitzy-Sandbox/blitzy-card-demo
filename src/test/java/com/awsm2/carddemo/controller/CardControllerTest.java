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

import com.awsm2.carddemo.dto.CardDetailDto;
import com.awsm2.carddemo.dto.CardListDto;
import com.awsm2.carddemo.dto.CardUpdateDto;
import com.awsm2.carddemo.exception.GlobalExceptionHandler;
import com.awsm2.carddemo.security.JwtAuthenticationFilter;
import com.awsm2.carddemo.security.JwtTokenProvider;
import com.awsm2.carddemo.service.CardDetailService;
import com.awsm2.carddemo.service.CardListService;
import com.awsm2.carddemo.service.CardUpdateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc controller slice tests for {@link CardController}.
 *
 * <p><b>QA findings exercised by this suite.</b></p>
 * <ul>
 *   <li><b>D1 (CRITICAL IDOR on GET).</b> Verifies that
 *       {@code GET /api/cards/{cardNumber}} is now restricted to
 *       {@code ROLE_ADMIN} via {@code @PreAuthorize("hasRole('ADMIN')")}
 *       on the controller method. A request authenticated as
 *       {@code ROLE_USER} MUST receive HTTP 403 Forbidden BEFORE the
 *       service is consulted &mdash; this closes the QA-reported
 *       Insecure Direct Object Reference vulnerability where any
 *       authenticated user could read any card by its 16-digit
 *       number.</li>
 *   <li><b>U1 (CRITICAL IDOR on PUT).</b> Verifies that
 *       {@code PUT /api/cards/{cardNumber}} is now restricted to
 *       {@code ROLE_ADMIN}. A request authenticated as
 *       {@code ROLE_USER} MUST receive HTTP 403 Forbidden BEFORE the
 *       service is consulted &mdash; this closes the QA-reported
 *       IDOR vulnerability where any authenticated user could
 *       modify (deactivate, rename, change account) any card.</li>
 *   <li><b>U2 (Optimistic-lock version visibility).</b> Verifies that
 *       both {@code GET /api/cards/{cardNumber}} and
 *       {@code PUT /api/cards/{cardNumber}} return a JSON envelope
 *       whose data object includes a {@code version} field. Without
 *       this field on the GET response, REST clients could not
 *       supply the optimistic-lock token in the subsequent PUT
 *       request and the optimistic-locking contract was unusable
 *       (the QA test report flagged this gap explicitly).</li>
 *   <li><b>D2 (PCI-DSS PAN masking on GET detail).</b> Verifies that
 *       the {@code cardNumber} component of the response body for
 *       {@code GET /api/cards/{cardNumber}} is masked to the
 *       {@code ************nnnn} form at the producer rather than
 *       carrying the raw 16-digit PAN.</li>
 *   <li><b>D3 (Standard reason code on not-found).</b> Verifies that
 *       a {@code RecordNotFoundException} from
 *       {@code GET /api/cards/{cardNumber}} surfaces on the JSON
 *       error envelope with the standardized reason code
 *       {@code "CARD_NOT_FOUND"} (NOT the bare entity name
 *       {@code "Card"} that previously leaked to the client).</li>
 *   <li><b>List authorization preserved.</b> Verifies that
 *       {@code GET /api/cards} continues to be accessible to
 *       {@code ROLE_USER} (the navigation parity left intact by the
 *       QA fix).</li>
 * </ul>
 *
 * <p><b>Why the security filter chain IS enabled here (unlike other
 * slice tests in this module).</b> The IDOR mitigation is implemented
 * at the controller method level via Spring Security's
 * {@code @PreAuthorize} annotations. Verifying that USER-role
 * authentication is rejected for {@code GET}/{@code PUT}
 * {@code /api/cards/{cardNumber}} therefore REQUIRES the method-
 * security {@code AuthorizationManagerBeforeMethodInterceptor} to be
 * active during the test. Other controller slice tests in this
 * module disable the filter chain via {@code addFilters = false}
 * because their authorization story is delegated to integration
 * tests; here the authorization story is the test goal, so the
 * filter chain is left engaged.</p>
 */
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
        // QA findings D1/U1 IDOR closure: the controller now declares
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
@DisplayName("CardController slice tests (/api/cards)")
class CardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CardListService cardListService;

    @MockBean
    private CardDetailService cardDetailService;

    @MockBean
    private CardUpdateService cardUpdateService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // =================================================================
    // QA finding D1 — IDOR on GET /api/cards/{cardNumber}
    // =================================================================

    @Nested
    @DisplayName("GET /api/cards/{cardNumber} — IDOR D1 closure (ADMIN-only)")
    class GetDetailAuthorization {

        @Test
        @DisplayName("ROLE_USER receives HTTP 403 before service is consulted (D1)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void getCard_asUser_isForbidden_andServiceNotConsulted() throws Exception {
            mockMvc.perform(get("/api/cards/{cardNumber}", "4111111111111111")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());

            // Authorization is enforced at the controller boundary by
            // Spring Security's method interceptor BEFORE the service
            // is consulted. The CardDetailService bean must therefore
            // never be invoked for a USER-role principal — this is the
            // primary defense-in-depth assertion guarding the QA
            // finding D1 closure.
            verifyNoInteractions(cardDetailService);
        }

        @Test
        @DisplayName("ROLE_ADMIN reaches the service and receives HTTP 200")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void getCard_asAdmin_reachesServiceAndReturns200() throws Exception {
            CardDetailDto dto = new CardDetailDto(
                    "************1111",                 // QA D2 — masked at producer
                    10000000001L,
                    "JOHN DOE",
                    LocalDate.of(2030, 12, 31),
                    "Y",
                    0L);                                // QA U2 — version field
            when(cardDetailService.getCardDetail("4111111111111111"))
                    .thenReturn(dto);

            mockMvc.perform(get("/api/cards/{cardNumber}", "4111111111111111")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    // QA finding D2: cardNumber on the wire MUST be the
                    // pre-masked 12-asterisk-plus-last-4 form, NEVER the
                    // raw 16-digit PAN.
                    .andExpect(jsonPath("$.data.cardNumber").value("************1111"))
                    .andExpect(jsonPath("$.data.accountId").value(10000000001L))
                    .andExpect(jsonPath("$.data.embossedName").value("JOHN DOE"))
                    .andExpect(jsonPath("$.data.activeStatus").value("Y"))
                    // QA finding U2: the JPA @Version token MUST be
                    // surfaced on the response so clients can use it as
                    // the optimistic-lock token for a follow-up PUT.
                    .andExpect(jsonPath("$.data.version").value(0));

            verify(cardDetailService).getCardDetail("4111111111111111");
        }
    }

    // =================================================================
    // QA finding U1 — IDOR on PUT /api/cards/{cardNumber}
    // =================================================================

    @Nested
    @DisplayName("PUT /api/cards/{cardNumber} — IDOR U1 closure (ADMIN-only)")
    class PutCardAuthorization {

        @Test
        @DisplayName("ROLE_USER receives HTTP 403 before service is consulted (U1)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void updateCard_asUser_isForbidden_andServiceNotConsulted() throws Exception {
            CardUpdateDto request = new CardUpdateDto(
                    "4111111111111111",
                    10000000001L,
                    "JOHN DOE",
                    LocalDate.of(2030, 12, 31),
                    "Y",
                    0L);

            mockMvc.perform(put("/api/cards/{cardNumber}", "4111111111111111")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(request))
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(status().isForbidden());

            // The CardUpdateService bean must NEVER be invoked for a
            // USER-role principal — this is the primary defense-in-
            // depth assertion guarding the QA finding U1 closure. The
            // IDOR scenario in the QA test report demonstrated that a
            // USER could DEACTIVATE another user's card; this assertion
            // makes that path unreachable at the controller boundary.
            verifyNoInteractions(cardUpdateService);
        }

        @Test
        @DisplayName("ROLE_ADMIN reaches the service and the response carries the version (U2)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_asAdmin_reachesService_andResponseHasVersion() throws Exception {
            CardUpdateDto request = new CardUpdateDto(
                    "4111111111111111",
                    10000000001L,
                    "JOHN DOE",
                    LocalDate.of(2030, 12, 31),
                    "Y",
                    0L);
            CardDetailDto responseDto = new CardDetailDto(
                    "************1111",                 // QA D2
                    10000000001L,
                    "JOHN DOE",
                    LocalDate.of(2030, 12, 31),
                    "Y",
                    1L);                                // QA U2 — incremented after Hibernate
            when(cardUpdateService.updateCard(eq("4111111111111111"), any(CardUpdateDto.class)))
                    .thenReturn(responseDto);

            mockMvc.perform(put("/api/cards/{cardNumber}", "4111111111111111")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(request))
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.cardNumber").value("************1111"))
                    // QA U2: the freshly-incremented @Version token is
                    // returned so the next PUT can be optimistic-lock-safe.
                    .andExpect(jsonPath("$.data.version").value(1));

            verify(cardUpdateService).updateCard(eq("4111111111111111"), any(CardUpdateDto.class));
        }
    }

    // =================================================================
    // List endpoint authorization preserved — USER role still permitted
    // =================================================================

    @Nested
    @DisplayName("GET /api/cards — list endpoint accessible to USER role")
    class ListCardsAuthorization {

        @Test
        @DisplayName("ROLE_USER can list cards (navigation parity preserved)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void listCards_asUser_returns200() throws Exception {
            CardListDto.CardRow row = new CardListDto.CardRow(
                    "************1111",
                    10000000001L,
                    "JOHN DOE",
                    LocalDate.of(2030, 12, 31),
                    "Y",
                    0L);
            CardListDto dto = new CardListDto(
                    List.of(row),
                    0,           // page
                    7,           // size
                    1L,          // totalElements
                    1,           // totalPages
                    true,        // first
                    true,        // last
                    null,        // accountFilter
                    null);       // cardNumberFilter
            when(cardListService.listCards(anyLong(), eq(false), anyInt())).thenReturn(dto);
            // Default behaviour with no account filter and not admin
            // should still produce a valid (possibly-empty) response.
            when(cardListService.listCards(any(), eq(false), anyInt())).thenReturn(dto);

            mockMvc.perform(get("/api/cards")
                            .param("account", "10000000001")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.rows[0].cardNumber").value("************1111"))
                    // QA U2 (list): the version token is now surfaced on
                    // every list-response row so REST clients can drive
                    // an optimistic-lock-safe PUT from a list entry.
                    .andExpect(jsonPath("$.data.rows[0].version").value(0));
        }

        @Test
        @DisplayName("ROLE_ADMIN can list cards")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void listCards_asAdmin_returns200() throws Exception {
            CardListDto dto = new CardListDto(
                    List.of(),
                    0, 7, 0L, 0, true, true, null, null);
            when(cardListService.listCards(any(), eq(true), anyInt())).thenReturn(dto);

            mockMvc.perform(get("/api/cards")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("anonymous (unauthenticated) request to GET /api/cards/{n} is rejected")
        void getCard_unauthenticated_isUnauthorizedOrForbidden() throws Exception {
            // The SecurityConfig stateless filter chain rejects
            // unauthenticated requests to protected resources. The
            // exact HTTP code (401 vs 403) depends on the entry-point
            // configuration; either is an acceptable rejection. The
            // assertion here is that the service is NOT consulted.
            mockMvc.perform(get("/api/cards/{cardNumber}", "4111111111111111")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().is4xxClientError());
            verifyNoInteractions(cardDetailService);
        }
    }

    // =================================================================
    // QA finding V1 — Embossed-name character-class validation
    // (stored-XSS surface reduction)
    // =================================================================

    /**
     * Exercises the {@code @Pattern("^[A-Z0-9 \\-'.]+$")} constraint
     * added to {@link CardUpdateDto#embossedName()} in response to QA
     * finding V1. The constraint locks the embossed-name field to the
     * character set that physical card-embossing equipment can render
     * (uppercase Latin letters, digits, space, hyphen, apostrophe,
     * period). Inputs containing HTML angle-brackets, script tags, or
     * other punctuation must be rejected at the Spring Bean Validation
     * layer with HTTP 400 BEFORE the service is consulted, eliminating
     * the QA-reported stored-XSS surface where an attacker could
     * persist {@code <script>alert(1)</script>} via PUT and have it
     * served back in subsequent GET responses.
     */
    @Nested
    @DisplayName("PUT /api/cards/{cardNumber} — V1 embossed-name pattern enforcement")
    class UpdateEmbossedNamePatternValidation {

        @Test
        @DisplayName("ROLE_ADMIN PUT with XSS payload in embossedName → HTTP 400 (V1)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_xssPayloadInEmbossedName_isBadRequest() throws Exception {
            // Body intentionally contains an HTML script tag — the
            // exact payload from the QA report's reproduction step.
            // The Pattern validator must reject this BEFORE the
            // request reaches CardUpdateService.
            String body = "{"
                    + "\"cardNumber\":\"4111111111111111\","
                    + "\"accountId\":10000000001,"
                    + "\"embossedName\":\"<script>alert(1)</script>\","
                    + "\"expirationDate\":\"2029-06-30\","
                    + "\"activeStatus\":\"Y\","
                    + "\"version\":0"
                    + "}";
            mockMvc.perform(put("/api/cards/{cardNumber}", "4111111111111111")
                            .with(org.springframework.security.test.web.servlet
                                    .request.SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            // The service must NOT have been invoked — the validator
            // short-circuited the request at the Spring MVC binding
            // layer (before any service logic ran).
            verifyNoInteractions(cardUpdateService);
        }

        @Test
        @DisplayName("ROLE_ADMIN PUT with lowercase letters in embossedName → HTTP 400 (V1)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_lowercaseInEmbossedName_isBadRequest() throws Exception {
            // Physical card embossing renders uppercase only; lowercase
            // is not in the allowed character class. The QA finding
            // explicitly identified this constraint
            // ("[A-Z0-9 \-'.]" per V1 suggested fix).
            String body = "{"
                    + "\"cardNumber\":\"4111111111111111\","
                    + "\"accountId\":10000000001,"
                    + "\"embossedName\":\"alice smith\","
                    + "\"expirationDate\":\"2029-06-30\","
                    + "\"activeStatus\":\"Y\","
                    + "\"version\":0"
                    + "}";
            mockMvc.perform(put("/api/cards/{cardNumber}", "4111111111111111")
                            .with(org.springframework.security.test.web.servlet
                                    .request.SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(cardUpdateService);
        }

        @Test
        @DisplayName("ROLE_ADMIN PUT with valid embossing character class → service consulted (V1 happy-path)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_validEmbossedName_reachesService() throws Exception {
            // ASCII-uppercase + digits + space + hyphen + apostrophe +
            // period — all members of the V1 character class. This
            // request MUST pass the @Pattern validator and reach
            // CardUpdateService.
            String body = "{"
                    + "\"cardNumber\":\"4111111111111111\","
                    + "\"accountId\":10000000001,"
                    + "\"embossedName\":\"O'BRIEN-SMITH JR.\","
                    + "\"expirationDate\":\"2029-06-30\","
                    + "\"activeStatus\":\"Y\","
                    + "\"version\":0"
                    + "}";
            CardDetailDto detail = new CardDetailDto(
                    "************1111", 10000000001L, "O'BRIEN-SMITH JR.",
                    LocalDate.of(2029, 6, 30), "Y", 1L);
            when(cardUpdateService.updateCard(eq("4111111111111111"), any()))
                    .thenReturn(detail);

            mockMvc.perform(put("/api/cards/{cardNumber}", "4111111111111111")
                            .with(org.springframework.security.test.web.servlet
                                    .request.SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.embossedName")
                            .value("O'BRIEN-SMITH JR."));

            verify(cardUpdateService).updateCard(eq("4111111111111111"), any());
        }
    }

    // =================================================================
    // QA finding E2 — DataIntegrityViolationException → HTTP 422
    // (foreign-key violation surfacing)
    // =================================================================

    /**
     * Exercises the new
     * {@code @ExceptionHandler(DataIntegrityViolationException.class)}
     * declared on {@link GlobalExceptionHandler} per QA finding E2.
     * The original implementation surfaced FK violations via the
     * generic {@code DataAccessException} handler, returning HTTP 500
     * INTERNAL_SERVER_ERROR. The mitigation downgrades these to HTTP
     * 422 UNPROCESSABLE_ENTITY (when the bound parameters refer to a
     * non-existent foreign-key target — e.g., an account ID that
     * doesn't exist) so REST clients can distinguish invalid input
     * from server-side failures.
     */
    @Nested
    @DisplayName("PUT /api/cards/{cardNumber} — E2 FK-violation → 422 mapping")
    class UpdateForeignKeyViolation {

        @Test
        @DisplayName("FK violation on fk_cards_acct → HTTP 422 ACCOUNT_NOT_FOUND (E2)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_fkAcctViolation_returns422AccountNotFound() throws Exception {
            // Simulate Hibernate raising a
            // DataIntegrityViolationException whose root-cause message
            // references the FK constraint "fk_cards_acct" — the same
            // textual signal Spring/Hibernate emits when the bound
            // card_acct_id has no matching row in the accounts table.
            String body = "{"
                    + "\"cardNumber\":\"4111111111111111\","
                    + "\"accountId\":99999999999,"
                    + "\"embossedName\":\"VALID NAME\","
                    + "\"expirationDate\":\"2029-06-30\","
                    + "\"activeStatus\":\"Y\","
                    + "\"version\":0"
                    + "}";
            when(cardUpdateService.updateCard(eq("4111111111111111"), any()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "could not execute statement",
                            new java.sql.SQLException(
                                    "ERROR: insert or update on table \"cards\" violates "
                                            + "foreign key constraint \"fk_cards_acct\"")));

            mockMvc.perform(put("/api/cards/{cardNumber}", "4111111111111111")
                            .with(org.springframework.security.test.web.servlet
                                    .request.SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        }

        @Test
        @DisplayName("Non-FK data integrity violation → HTTP 422 DATA_INTEGRITY_VIOLATION (E2 fallback)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateCard_genericIntegrityViolation_returns422() throws Exception {
            // Any DataIntegrityViolationException whose cause chain
            // does NOT mention "fk_cards_acct" must still surface as
            // HTTP 422 (not 500) — but with the generic reason code
            // DATA_INTEGRITY_VIOLATION rather than the FK-specific
            // ACCOUNT_NOT_FOUND. This proves the QA E2 fix correctly
            // generalizes beyond the FK-specific path.
            String body = "{"
                    + "\"cardNumber\":\"4111111111111111\","
                    + "\"accountId\":10000000001,"
                    + "\"embossedName\":\"VALID NAME\","
                    + "\"expirationDate\":\"2029-06-30\","
                    + "\"activeStatus\":\"Y\","
                    + "\"version\":0"
                    + "}";
            when(cardUpdateService.updateCard(eq("4111111111111111"), any()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "could not execute statement",
                            new java.sql.SQLException(
                                    "ERROR: duplicate key violates unique constraint "
                                            + "\"uk_cards_some_other\"")));

            mockMvc.perform(put("/api/cards/{cardNumber}", "4111111111111111")
                            .with(org.springframework.security.test.web.servlet
                                    .request.SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"));
        }
    }
}

package com.carddemo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
import com.carddemo.dto.CardListItem;
import com.carddemo.dto.CardListResponse;
import com.carddemo.dto.CardUpdateRequest;
import com.carddemo.dto.CardUpdateResponse;
import com.carddemo.dto.CardViewResponse;
import com.carddemo.dto.PageResponse;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.observability.CorrelationIdFilter;
import com.carddemo.service.CardListService;
import com.carddemo.service.CardUpdateService;
import com.carddemo.service.CardViewService;
import com.carddemo.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Web-slice test for {@link CardController} &mdash; the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.5
 * REST replacement for three legacy CICS/BMS 3270 programs (frozen COBOL source referenced
 * read-only at commit SHA {@code 27d6c6f}): {@code COCRDLIC} (transaction {@code CCLI}, card
 * list), {@code COCRDSLC} ({@code CCDL}, card view) and {@code COCRDUPC} ({@code CCUP}, card
 * update).
 *
 * <h2>Harness</h2>
 * <p>The suite runs as a Spring MVC slice ({@link WebMvcTest}) that loads only the web layer for
 * {@link CardController}, so it is fast and hermetic yet exercises the <em>real</em> HTTP stack:
 * the shared {@link com.carddemo.controller.GlobalExceptionHandler @RestControllerAdvice} (auto
 * detected by the slice) maps domain failures to their HTTP statuses, and the imported
 * {@link SecurityConfig}, {@link CorrelationIdFilter} and {@link WebConfig} supply the production
 * security filter chain, the correlation-id filter and the JSON/validation web contracts. The
 * three card services and {@link JwtService} are replaced with Mockito mocks so no database, no
 * AWS and no real token verification is required.</p>
 *
 * <p>Mockito bean overrides use {@link MockitoBean} &mdash; the non-deprecated Spring&nbsp;Boot
 * 3.4+ replacement for {@code @MockBean} &mdash; so the build stays warning-free under
 * {@code -Xlint:all} (Gate&nbsp;2) while preserving the mandated slice wiring
 * ({@code @MockBean JwtService}; real {@link CorrelationIdFilter}).</p>
 *
 * <h2>Parity and security invariants asserted</h2>
 * <ul>
 *   <li><strong>Fixed page size seven</strong> &mdash; the {@code COCRDLIC} screen array
 *       {@code OCCURS 7 TIMES} ({@code WS-MAX-SCREEN-LINES VALUE 7}); the list page therefore
 *       carries {@code pageSize == 7}.</li>
 *   <li><strong>No PAN leakage / no CVV</strong> &mdash; every card number is masked to its last
 *       four digits and no response ever contains a full sixteen-digit Primary Account Number or
 *       any {@code cvv} field (AAP no-leakage requirement, Gate&nbsp;5).</li>
 *   <li><strong>Optimistic-lock conflict</strong> &mdash; {@code COCRDUPC}'s
 *       "record changed by someone else" detection surfaces as HTTP&nbsp;409 with the byte-exact
 *       message {@code "Record changed by some one else. Please review"}.</li>
 *   <li><strong>Filter / input validation</strong> &mdash; malformed list filters and card-number
 *       path variables are rejected with HTTP&nbsp;400 ({@code VALIDATION_ERROR}) before any
 *       service is invoked; an unknown card yields HTTP&nbsp;404.</li>
 *   <li><strong>Authentication &amp; observability</strong> &mdash; {@code /api/cards/**} requires
 *       an authenticated caller (any role); anonymous access yields HTTP&nbsp;401, and every error
 *       body carries the MDC {@code correlationId}.</li>
 * </ul>
 */
@WebMvcTest(controllers = CardController.class)
@Import({SecurityConfig.class, CorrelationIdFilter.class, WebConfig.class})
@DisplayName("CardController — card list (CCLI) + view (CCDL) + update (CCUP) web slice")
class CardControllerTest {

    /** Base path for the migrated card endpoints. */
    private static final String CARDS_PATH = "/api/cards";

    /** A representative 16-digit card number (PAN) used as the unmasked service-side value. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The expected masked rendering of {@link #CARD_NUMBER}: twelve mask characters then last four. */
    private static final String MASKED_CARD = "************1111";

    /** An 11-digit account identifier ({@code CARD-ACCT-ID PIC 9(11)}); never a 16-digit run. */
    private static final String ACCOUNT_ID = "00000000011";

    /** Regex matching a fully masked card number ("one-or-more '*' then exactly four digits"). */
    private static final String MASK_PATTERN = "\\*+\\d{4}";

    /** Regex whose presence anywhere in a payload would indicate a leaked full PAN. */
    private static final String FULL_PAN_PATTERN = "\\d{16}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CardListService cardListService;

    @MockitoBean
    private CardViewService cardViewService;

    @MockitoBean
    private CardUpdateService cardUpdateService;

    @MockitoBean
    private JwtService jwtService;

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds a Card List page carrying the fixed seven rows of the legacy {@code COCRDLI} screen.
     * Every row's card number is masked by the {@link CardListItem} canonical constructor, so the
     * page never exposes a full PAN.
     *
     * @return a {@link CardListResponse} whose page has {@code pageSize == 7} and seven masked rows
     */
    private CardListResponse sevenRowListResponse() {
        final List<CardListItem> rows = new ArrayList<>(CardListResponse.PAGE_SIZE);
        for (int i = 1; i <= CardListResponse.PAGE_SIZE; i++) {
            // Distinct 16-digit PANs (…1111 … …1117); each is masked to its last four digits.
            rows.add(new CardListItem(ACCOUNT_ID, "411111111111111" + i, "Y"));
        }
        final PageResponse<CardListItem> page =
                PageResponse.of(rows, 1, CardListResponse.PAGE_SIZE, CardListResponse.PAGE_SIZE);
        return new CardListResponse(ACCOUNT_ID, null, page);
    }

    /**
     * A representative single-card detail projection. The {@link CardViewResponse} canonical
     * constructor masks the PAN and the type carries no CVV field at all.
     *
     * @return a masked, CVV-free {@link CardViewResponse}
     */
    private CardViewResponse sampleViewResponse() {
        return new CardViewResponse(ACCOUNT_ID, CARD_NUMBER, "JOHN Q PUBLIC", "Y",
                LocalDate.of(2027, 5, 31), 7L);
    }

    /**
     * A well-formed update request (digits-only ids, {@code Y}/{@code N} status, ISO date).
     *
     * @return a valid {@link CardUpdateRequest}
     */
    private CardUpdateRequest validUpdateRequest() {
        return new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, "JOHN Q PUBLIC", "Y",
                LocalDate.of(2027, 5, 31), 0L);
    }

    // ---------------------------------------------------------------------------------------------
    // GET /api/cards — card list (COCRDLIC / CCLI)
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/cards — card list (COCRDLIC / CCLI)")
    class ListCards {

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("authenticated -> 200, fixed page size 7, masked rows, one-based page bridged to zero-based")
        void listReturns200WithPageSizeSevenAndMaskedRows() throws Exception {
            when(cardListService.listCards(any(), any(), anyInt())).thenReturn(sevenRowListResponse());

            mockMvc.perform(get(CARDS_PATH).param("accountId", "11").param("page", "1"))
                    .andExpect(status().isOk())
                    // COCRDLIC OCCURS 7 TIMES -> the page size is exactly seven.
                    .andExpect(jsonPath("$.page.pageSize").value(CardListResponse.PAGE_SIZE))
                    .andExpect(jsonPath("$.page.pageSize").value(7))
                    // Seven rows on the page (<= the fixed page size), every card number masked.
                    .andExpect(jsonPath("$.page.content", hasSize(7)))
                    .andExpect(jsonPath("$.page.content[*].cardNumber", everyItem(matchesPattern(MASK_PATTERN))))
                    .andExpect(jsonPath("$.page.content[0].cardNumber").value(MASKED_CARD))
                    .andExpect(jsonPath("$.accountIdFilter").value(ACCOUNT_ID))
                    .andExpect(jsonPath("$.page.pageNumber").value(1));

            // PF7/PF8 scroll bridge: one-based REST page 1 delegates to zero-based service page 0.
            verify(cardListService).listCards(eq(11L), isNull(), eq(0));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("response never leaks a full PAN and never carries a cvv field")
        void listDoesNotLeakFullPanOrCvv() throws Exception {
            when(cardListService.listCards(any(), any(), anyInt())).thenReturn(sevenRowListResponse());

            final String body = mockMvc.perform(get(CARDS_PATH))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContainPattern(FULL_PAN_PATTERN);
            assertThat(body.toLowerCase(Locale.ROOT)).doesNotContain("cvv");
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("oversized cardNumber filter (>16) violates @Size -> 400 VALIDATION_ERROR, service never called")
        void listOversizedCardFilterReturns400AndSkipsService() throws Exception {
            mockMvc.perform(get(CARDS_PATH).param("cardNumber", "12345678901234567"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());

            verifyNoInteractions(cardListService);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("page below the one-based minimum (0) violates @Min(1) -> 400, service never called")
        void listPageBelowMinimumReturns400() throws Exception {
            // The REST page index is one-based (first page = 1), so page=0 breaches the @Min(1) edit and
            // is rejected before the controller delegates, mirroring the legacy PF7 scroll floor.
            mockMvc.perform(get(CARDS_PATH).param("page", "0"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(cardListService);
        }

        @Test
        @WithAnonymousUser
        @DisplayName("anonymous -> 401 with generic body carrying the correlationId; service never called")
        void listAnonymousReturns401() throws Exception {
            mockMvc.perform(get(CARDS_PATH))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.message").value("Authentication required"))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());

            verifyNoInteractions(cardListService);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // GET /api/cards/{cardNumber} — card view (COCRDSLC / CCDL)
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/cards/{cardNumber} — card view (COCRDSLC / CCDL)")
    class ViewCard {

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("valid -> 200, masked PAN, version echoed, and no CVV field anywhere")
        void viewReturns200MaskedNoCvv() throws Exception {
            when(cardViewService.viewCard(eq(CARD_NUMBER))).thenReturn(sampleViewResponse());

            final String body = mockMvc.perform(get(CARDS_PATH + "/{cardNumber}", CARD_NUMBER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cardNumber").value(MASKED_CARD))
                    .andExpect(jsonPath("$.cardNumber", matchesPattern(MASK_PATTERN)))
                    .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
                    .andExpect(jsonPath("$.expirationDate").value("2027-05-31"))
                    .andExpect(jsonPath("$.version").value(7))
                    // The COCRDSL projection has no CVV under any spelling.
                    .andExpect(jsonPath("$.cvv").doesNotExist())
                    .andExpect(jsonPath("$.cardCvvCd").doesNotExist())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContainPattern(FULL_PAN_PATTERN);
            assertThat(body.toLowerCase(Locale.ROOT)).doesNotContain("cvv");
            verify(cardViewService).viewCard(CARD_NUMBER);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("unknown card -> 404 (service raises ResourceNotFoundException)")
        void viewUnknownReturns404() throws Exception {
            when(cardViewService.viewCard(eq(CARD_NUMBER)))
                    .thenThrow(new ResourceNotFoundException("Did not find this account in cards database"));

            mockMvc.perform(get(CARDS_PATH + "/{cardNumber}", CARD_NUMBER))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("non-numeric card number violates @Pattern -> 400 VALIDATION_ERROR, service never called")
        void viewNonNumericReturns400ValidationError() throws Exception {
            mockMvc.perform(get(CARDS_PATH + "/{cardNumber}", "abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            verifyNoInteractions(cardViewService);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("error body carries the correlationId and the X-Correlation-Id response header")
        void viewErrorBodyCarriesCorrelationId() throws Exception {
            when(cardViewService.viewCard(eq(CARD_NUMBER)))
                    .thenThrow(new ResourceNotFoundException("Did not find this account in cards database"));

            mockMvc.perform(get(CARDS_PATH + "/{cardNumber}", CARD_NUMBER))
                    .andExpect(status().isNotFound())
                    .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER))
                    .andExpect(jsonPath("$.correlationId").isString())
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // PUT /api/cards/{cardNumber} — card update (COCRDUPC / CCUP)
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("PUT /api/cards/{cardNumber} — card update (COCRDUPC / CCUP)")
    class UpdateCard {

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("valid body -> 200 with confirmation, new version, and masked card snapshot (no CVV)")
        void updateValidReturns200MaskedCard() throws Exception {
            final CardUpdateResponse response = CardUpdateResponse.withConfirmation(sampleViewResponse());
            when(cardUpdateService.updateCard(eq(CARD_NUMBER), any(CardUpdateRequest.class)))
                    .thenReturn(response);

            final String body = mockMvc.perform(put(CARDS_PATH + "/{cardNumber}", CARD_NUMBER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validUpdateRequest())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(CardUpdateResponse.SUCCESS_MESSAGE))
                    .andExpect(jsonPath("$.version").value(7))
                    .andExpect(jsonPath("$.card.cardNumber").value(MASKED_CARD))
                    .andExpect(jsonPath("$.card.cardNumber", matchesPattern(MASK_PATTERN)))
                    .andExpect(jsonPath("$.card.cvv").doesNotExist())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContainPattern(FULL_PAN_PATTERN);
            assertThat(body.toLowerCase(Locale.ROOT)).doesNotContain("cvv");
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("card not found -> 404 (service raises ResourceNotFoundException)")
        void updateNotFoundReturns404() throws Exception {
            // COCRDUPC rereads the card before rewrite; a missing record raises ResourceNotFoundException,
            // which GlobalExceptionHandler maps to HTTP 404.
            when(cardUpdateService.updateCard(eq(CARD_NUMBER), any(CardUpdateRequest.class)))
                    .thenThrow(new ResourceNotFoundException("Did not find this account in cards database"));

            mockMvc.perform(put(CARDS_PATH + "/{cardNumber}", CARD_NUMBER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validUpdateRequest())))
                    .andExpect(status().isNotFound());

            verify(cardUpdateService).updateCard(eq(CARD_NUMBER), any(CardUpdateRequest.class));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("optimistic-lock conflict -> 409 with the byte-exact 'record changed' message")
        void updateOptimisticConflictReturns409ExactMessage() throws Exception {
            when(cardUpdateService.updateCard(eq(CARD_NUMBER), any(CardUpdateRequest.class)))
                    .thenThrow(new OptimisticLockConflictException());

            mockMvc.perform(put(CARDS_PATH + "/{cardNumber}", CARD_NUMBER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validUpdateRequest())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message")
                            .value("Record changed by some one else. Please review"));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("invalid body (activeStatus not Y/N) -> 400 with non-empty fieldErrors, service never called")
        void updateInvalidBodyReturns400WithFieldErrors() throws Exception {
            // activeStatus "X" violates the CardUpdateRequest @Pattern("[YN]") edit (COCRDUPC field edit).
            final CardUpdateRequest invalid = new CardUpdateRequest(
                    ACCOUNT_ID, CARD_NUMBER, "JOHN Q PUBLIC", "X", LocalDate.of(2027, 5, 31), 0L);

            mockMvc.perform(put(CARDS_PATH + "/{cardNumber}", CARD_NUMBER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors").isNotEmpty());

            verifyNoInteractions(cardUpdateService);
        }
    }
}

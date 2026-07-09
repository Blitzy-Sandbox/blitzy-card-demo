package com.carddemo.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;

import com.carddemo.dto.CardListItem;
import com.carddemo.dto.CardListResponse;
import com.carddemo.dto.CardUpdateRequest;
import com.carddemo.dto.CardUpdateResponse;
import com.carddemo.dto.CardViewResponse;
import com.carddemo.dto.PageResponse;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.service.CardListService;
import com.carddemo.service.CardUpdateService;
import com.carddemo.service.CardViewService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Web-layer unit test for {@link CardController} &mdash; the REST controller migrated from the
 * legacy CICS/BMS programs {@code COCRDLIC} (CCLI, card list), {@code COCRDSLC} (CCDL, card view)
 * and {@code COCRDUPC} (CCUP, card update) at frozen source commit SHA {@code 27d6c6f}.
 *
 * <p>The suite drives the controller through {@link MockMvc} with the three service collaborators
 * supplied as Mockito mocks, so it loads <strong>no</strong> Spring application context, no
 * security configuration, no database, and no AWS &mdash; keeping it fast, deterministic, and
 * fully decoupled from the other modules assembled in parallel. Two deliberate wiring choices make
 * the standalone setup behave like the running application:</p>
 * <ul>
 *   <li>the shared {@link GlobalExceptionHandler} advice is registered, so domain exceptions map to
 *       their HTTP statuses (400 / 404 / 409) exactly as they do in production; and</li>
 *   <li>the controller is wrapped in a <em>method-validation AOP proxy</em>
 *       ({@link MethodValidationInterceptor}) so that the class-level {@code @Validated} together
 *       with the parameter constraints ({@code @Min} on {@code page}, {@code @Pattern}/{@code @Size}
 *       /{@code @NotBlank} on the card-number path variable) are enforced &mdash; reproducing the
 *       {@code MethodValidationPostProcessor} behaviour that {@code standaloneSetup} does not apply
 *       on its own.</li>
 * </ul>
 *
 * <p>The Jackson converter is configured with the JSR-310 module so {@link LocalDate} request and
 * response fields serialize as ISO-8601 strings, matching the application's message converter.</p>
 *
 * <p>Behavioural assertions cover the AAP requirements for this file: the Card List page size is a
 * fixed seven rows and the filters are echoed; the one-based REST {@code page} is bridged to the
 * zero-based Spring Data index the service expects; card numbers are always masked and no CVV is
 * ever emitted; and every failure path (validation 400, not-found 404, optimistic-lock conflict
 * 409) is surfaced by the advice rather than caught in the controller.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardController — card list (CCLI) + view (CCDL) + update (CCUP) REST endpoints")
class CardControllerTest {

    private static final String CARD_NUMBER = "4111111111111111";
    private static final String MASKED_CARD = "************1111";
    private static final String ACCOUNT_ID = "00000000011";

    @Mock
    private CardListService cardListService;

    @Mock
    private CardViewService cardViewService;

    @Mock
    private CardUpdateService cardUpdateService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CardController target = new CardController(cardListService, cardViewService, cardUpdateService);

        // Wrap the controller in a method-validation proxy so the class-level @Validated and the
        // parameter constraints are enforced, matching the running application's
        // MethodValidationPostProcessor (which MockMvcBuilders.standaloneSetup does not add).
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new MethodValidationInterceptor());
        CardController controller = (CardController) proxyFactory.getProxy();

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(new ObjectMapper().findAndRegisterModules());

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(converter)
                .build();
    }

    /** A one-row list page with the fixed size of seven, carrying an already-masked card row. */
    private CardListResponse sampleListResponse() {
        CardListItem row = new CardListItem(ACCOUNT_ID, CARD_NUMBER, "Y");
        PageResponse<CardListItem> page =
                PageResponse.of(List.of(row), 1, CardListResponse.PAGE_SIZE, 1L);
        return new CardListResponse(ACCOUNT_ID, null, page);
    }

    /** A representative card view (the DTO canonical constructor masks the PAN and omits any CVV). */
    private CardViewResponse sampleViewResponse() {
        return new CardViewResponse(ACCOUNT_ID, CARD_NUMBER, "JOHN Q PUBLIC", "Y",
                LocalDate.of(2027, 5, 31), 0L);
    }

    /** A valid update request body as JSON (digits-only ids, Y/N status, ISO date, echoed version). */
    private String validUpdateBody() {
        return "{\"accountId\":\"" + ACCOUNT_ID + "\",\"cardNumber\":\"" + CARD_NUMBER + "\","
                + "\"embossedName\":\"JOHN Q PUBLIC\",\"activeStatus\":\"Y\","
                + "\"expirationDate\":\"2027-05-31\",\"version\":0}";
    }

    @Nested
    @DisplayName("GET /api/cards — card list (COCRDLIC / CCLI)")
    class ListCards {

        @Test
        @DisplayName("page=1 -> 200, page size 7, filters echoed, masked card row")
        void listReturnsPageOfSevenWithEchoedFilters() throws Exception {
            when(cardListService.listCards(any(), any(), eq(0))).thenReturn(sampleListResponse());

            mockMvc.perform(get("/api/cards").param("page", "1").param("accountId", "11"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.pageSize").value(7))
                    .andExpect(jsonPath("$.accountIdFilter").value(ACCOUNT_ID))
                    .andExpect(jsonPath("$.page.pageNumber").value(1))
                    .andExpect(jsonPath("$.page.content[0].cardNumber").value(MASKED_CARD));

            // One-based REST page 1 is bridged to zero-based service page 0.
            verify(cardListService).listCards(eq(11L), isNull(), eq(0));
        }

        @Test
        @DisplayName("default (no page) -> service invoked with zero-based page 0")
        void defaultPageBridgesToZero() throws Exception {
            when(cardListService.listCards(any(), any(), eq(0))).thenReturn(sampleListResponse());

            mockMvc.perform(get("/api/cards")).andExpect(status().isOk());

            verify(cardListService).listCards(isNull(), isNull(), eq(0));
        }

        @Test
        @DisplayName("page=3 -> service invoked with zero-based page 2 (PF7/PF8 scroll bridge)")
        void page3BridgesToServicePage2() throws Exception {
            when(cardListService.listCards(any(), any(), eq(2))).thenReturn(sampleListResponse());

            mockMvc.perform(get("/api/cards").param("page", "3")).andExpect(status().isOk());

            verify(cardListService).listCards(isNull(), isNull(), eq(2));
        }

        @Test
        @DisplayName("page=0 violates @Min(1) -> 400, service never called")
        void pageBelowMinimumIsRejected() throws Exception {
            mockMvc.perform(get("/api/cards").param("page", "0"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(cardListService);
        }

        @Test
        @DisplayName("cardNumber filter longer than 16 violates @Size -> 400, service never called")
        void oversizedCardFilterIsRejected() throws Exception {
            mockMvc.perform(get("/api/cards").param("cardNumber", "12345678901234567"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(cardListService);
        }
    }

    @Nested
    @DisplayName("GET /api/cards/{cardNumber} — card view (COCRDSLC / CCDL)")
    class ViewCard {

        @Test
        @DisplayName("valid -> 200 with masked PAN, version echoed, and no CVV field")
        void viewReturnsMaskedCardWithoutCvv() throws Exception {
            when(cardViewService.viewCard(eq(CARD_NUMBER))).thenReturn(sampleViewResponse());

            mockMvc.perform(get("/api/cards/{cardNumber}", CARD_NUMBER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cardNumber").value(MASKED_CARD))
                    .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
                    .andExpect(jsonPath("$.expirationDate").value("2027-05-31"))
                    .andExpect(jsonPath("$.version").value(0))
                    .andExpect(jsonPath("$.cvv").doesNotExist())
                    .andExpect(jsonPath("$.cardCvvCd").doesNotExist());
        }

        @Test
        @DisplayName("unknown card -> 404 (service throws ResourceNotFoundException)")
        void viewNotFoundReturns404() throws Exception {
            when(cardViewService.viewCard(eq(CARD_NUMBER)))
                    .thenThrow(new ResourceNotFoundException("Did not find this account in cards database"));

            mockMvc.perform(get("/api/cards/{cardNumber}", CARD_NUMBER))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("non-numeric path variable violates @Pattern -> 400, service never called")
        void viewNonNumericPathVariableReturns400() throws Exception {
            mockMvc.perform(get("/api/cards/{cardNumber}", "abc"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(cardViewService);
        }
    }

    @Nested
    @DisplayName("PUT /api/cards/{cardNumber} — card update (COCRDUPC / CCUP)")
    class UpdateCard {

        @Test
        @DisplayName("valid body -> 200 with confirmation, new version, and masked card snapshot")
        void updateValidReturns200() throws Exception {
            CardUpdateResponse response =
                    new CardUpdateResponse(sampleViewResponse(), "Changes committed to database", 1L);
            when(cardUpdateService.updateCard(eq(CARD_NUMBER), any(CardUpdateRequest.class)))
                    .thenReturn(response);

            mockMvc.perform(put("/api/cards/{cardNumber}", CARD_NUMBER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validUpdateBody()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Changes committed to database"))
                    .andExpect(jsonPath("$.version").value(1))
                    .andExpect(jsonPath("$.card.cardNumber").value(MASKED_CARD))
                    .andExpect(jsonPath("$.card.cvv").doesNotExist());
        }

        @Test
        @DisplayName("optimistic-lock conflict -> 409 with record-changed message")
        void updateOptimisticConflictReturns409() throws Exception {
            when(cardUpdateService.updateCard(eq(CARD_NUMBER), any(CardUpdateRequest.class)))
                    .thenThrow(new OptimisticLockConflictException());

            mockMvc.perform(put("/api/cards/{cardNumber}", CARD_NUMBER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validUpdateBody()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Record changed by some one else. Please review"));
        }

        @Test
        @DisplayName("unknown card -> 404 (service throws ResourceNotFoundException)")
        void updateNotFoundReturns404() throws Exception {
            when(cardUpdateService.updateCard(eq(CARD_NUMBER), any(CardUpdateRequest.class)))
                    .thenThrow(new ResourceNotFoundException("Did not find this account in cards database"));

            mockMvc.perform(put("/api/cards/{cardNumber}", CARD_NUMBER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validUpdateBody()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("invalid body (blank accountId, bad status) -> 400, service never called")
        void updateInvalidBodyReturns400() throws Exception {
            String invalidBody = "{\"accountId\":\"\",\"cardNumber\":\"" + CARD_NUMBER + "\","
                    + "\"embossedName\":\"JOHN Q PUBLIC\",\"activeStatus\":\"X\","
                    + "\"expirationDate\":\"2027-05-31\",\"version\":0}";

            mockMvc.perform(put("/api/cards/{cardNumber}", CARD_NUMBER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(cardUpdateService);
        }
    }
}

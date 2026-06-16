package com.carddemo.unit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.config.SecurityConfig;
import com.carddemo.controller.CardController;
import com.carddemo.exception.ConcurrencyException;
import com.carddemo.model.dto.CardUpdateResponse;
import com.carddemo.service.card.CardDetailService;
import com.carddemo.service.card.CardListService;
import com.carddemo.service.card.CardUpdateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer (MockMvc) contract tests for {@link CardController}.
 *
 * <p>Proves the CP4 card-update contract fixes at the HTTP layer: the {@code PUT
 * /api/cards/{cardNumber}} path variable is now bound and validated against the request body
 * (path/body mismatch is rejected with {@code 400}), the optimistic-concurrency {@code version}
 * field is required ({@code 400} when omitted), and a stale-version conflict raised by the service
 * surfaces as {@code 409 Conflict} through the global exception handler (review findings for the
 * card update path-variable, DTO {@code version}, and conflict semantics).</p>
 */
@WebMvcTest(CardController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "carddemo.security.jwt.secret=test-jwt-secret-key-at-least-32-bytes-long-0123456789"
})
class CardControllerWebMvcTest {

    private static final String PATH_CARD = "4111111111111111";

    /** Valid body whose {@code cardNumber} matches {@link #PATH_CARD}; {@code version} is required. */
    private static final String VALID_BODY = "{"
            + "\"version\":0,"
            + "\"accountId\":\"12345\","
            + "\"cardNumber\":\"" + PATH_CARD + "\","
            + "\"cardholderName\":\"John Doe\","
            + "\"cardStatus\":\"Y\","
            + "\"expiryMonth\":\"12\","
            + "\"expiryYear\":\"2030\","
            + "\"expiryDay\":\"31\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CardListService cardListService;

    @MockitoBean
    private CardDetailService cardDetailService;

    @MockitoBean
    private CardUpdateService cardUpdateService;

    @Test
    void updateCard_matchingPathAndBody_isOk() throws Exception {
        when(cardUpdateService.updateCard(any())).thenReturn(new CardUpdateResponse(
                "12345", PATH_CARD, "John Doe", "Y", "12", "2030", "31",
                "Card updated successfully.", null));

        mockMvc.perform(put("/api/cards/" + PATH_CARD)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardNumber").value(PATH_CARD));
    }

    @Test
    void updateCard_pathBodyMismatch_isBadRequest() throws Exception {
        // F3: path identifies a different card than the body → ambiguous write rejected with 400.
        mockMvc.perform(put("/api/cards/9999999999999999")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateCard_staleVersion_isConflict() throws Exception {
        // F4/F6: a stale optimistic-concurrency token surfaces as 409 via GlobalExceptionHandler.
        when(cardUpdateService.updateCard(any()))
                .thenThrow(new ConcurrencyException("Card was updated by another transaction"));

        mockMvc.perform(put("/api/cards/" + PATH_CARD)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void updateCard_missingVersion_isBadRequest() throws Exception {
        // F4/F6: version is a required contract field (@NotNull) — omission fails @Valid with 400.
        String bodyWithoutVersion = "{"
                + "\"accountId\":\"12345\","
                + "\"cardNumber\":\"" + PATH_CARD + "\","
                + "\"cardholderName\":\"John Doe\","
                + "\"cardStatus\":\"Y\","
                + "\"expiryMonth\":\"12\","
                + "\"expiryYear\":\"2030\","
                + "\"expiryDay\":\"31\"}";

        mockMvc.perform(put("/api/cards/" + PATH_CARD)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutVersion))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateCard_unauthenticated_isUnauthorized() throws Exception {
        mockMvc.perform(put("/api/cards/" + PATH_CARD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }
}

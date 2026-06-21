package com.carddemo.unit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.config.SecurityConfig;
import com.carddemo.controller.CardController;
import com.carddemo.model.dto.CardUpdateRequest;
import com.carddemo.service.card.CardDetailService;
import com.carddemo.service.card.CardListService;
import com.carddemo.service.card.CardUpdateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@link CardController} asserting that {@code PUT /api/cards/{cardNumber}}
 * binds and enforces the path identifier against the request-body card number.
 *
 * <p>Before the fix the handler ignored the {@code {cardNumber}} path variable and updated whatever
 * card number the body carried. The handler now compares the two exactly and rejects a mismatch
 * with {@code 400} before {@code CardUpdateService} is invoked (api-contracts.md card-update
 * contract; COCRDUPC parity, source commit 27d6c6f — reference only, no COBOL copied).</p>
 */
@WebMvcTest(CardController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        // >= 32 bytes so SecurityConfig.hmacKey() accepts it; test-only, never a real secret.
        "carddemo.security.jwt.secret=carddemo-web-test-signing-secret-0123456789"
})
@DisplayName("CardController web slice - PUT path/body identifier binding")
class CardControllerWebTest {

    // A fully-valid CardUpdateRequest body; only the cardNumber differs between the two tests.
    private static final String BODY_TEMPLATE =
            "{\"accountId\":\"111\",\"cardNumber\":\"%s\",\"nameOnCard\":\"JOHN DOE\","
                    + "\"cardStatus\":\"Y\",\"expirationMonth\":\"12\",\"expirationYear\":\"2030\","
                    + "\"version\":0}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CardListService cardListService;

    @MockitoBean
    private CardDetailService cardDetailService;

    @MockitoBean
    private CardUpdateService cardUpdateService;

    @Test
    @DisplayName("matching path and body card number updates the targeted card (service invoked)")
    void matchingPathAndBodyInvokesService() throws Exception {
        mockMvc.perform(put("/api/cards/4111111111111111")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(BODY_TEMPLATE, "4111111111111111")))
                .andExpect(status().isOk());

        verify(cardUpdateService).updateCard(any(CardUpdateRequest.class));
    }

    @Test
    @DisplayName("mismatched path and body card number is rejected with 400 and the service is never called")
    void mismatchedPathAndBodyIsRejected() throws Exception {
        mockMvc.perform(put("/api/cards/4111111111111111")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(BODY_TEMPLATE, "4222222222222222")))
                .andExpect(status().isBadRequest());

        verify(cardUpdateService, never()).updateCard(any());
    }
}

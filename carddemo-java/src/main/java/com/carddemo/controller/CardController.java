package com.carddemo.controller;

import com.carddemo.model.dto.CardDetailResponse;
import com.carddemo.model.dto.CardListResponse;
import com.carddemo.model.dto.CardUpdateRequest;
import com.carddemo.model.dto.CardUpdateResponse;
import com.carddemo.service.card.CardDetailService;
import com.carddemo.service.card.CardListService;
import com.carddemo.service.card.CardUpdateService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Card REST controller. Re-platforms the CICS card-list (COCRDLIC), card-detail
 * (COCRDSLC), and card-update (COCRDUPC) programs and their BMS screens COCRDLI /
 * COCRDSL / COCRDUP (reference only, lineage commit 27d6c6f). Stateless JSON
 * endpoints replace the pseudo-conversational COMMAREA flow.
 */
@RestController
@RequestMapping("/api/cards")
public class CardController {

    private final CardListService cardListService;
    private final CardDetailService cardDetailService;
    private final CardUpdateService cardUpdateService;

    public CardController(CardListService cardListService,
                         CardDetailService cardDetailService,
                         CardUpdateService cardUpdateService) {
        this.cardListService = cardListService;
        this.cardDetailService = cardDetailService;
        this.cardUpdateService = cardUpdateService;
    }

    @GetMapping
    public CardListResponse listCards(@RequestParam(required = false) String accountId,
                                      @RequestParam(required = false) String cardNumber,
                                      @RequestParam(defaultValue = "0") int page) {
        return cardListService.getCardList(accountId, cardNumber, page);
    }

    @GetMapping("/{cardNumber}")
    public CardDetailResponse getCard(@PathVariable String cardNumber,
                                      @RequestParam(required = false) String accountId) {
        return cardDetailService.getCardDetail(accountId, cardNumber);
    }

    @PutMapping("/{cardNumber}")
    public CardUpdateResponse updateCard(@Valid @RequestBody CardUpdateRequest request) {
        return cardUpdateService.updateCard(request);
    }
}

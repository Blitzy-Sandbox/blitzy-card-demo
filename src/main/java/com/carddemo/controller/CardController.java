package com.carddemo.controller;

import com.carddemo.dto.CardDto;
import com.carddemo.service.CardDetailService;
import com.carddemo.service.CardListService;
import com.carddemo.service.CardUpdateService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stateless REST surface for the card management screens of the AWS CardDemo
 * application, rooted at {@code /api/cards}.
 *
 * <p>This controller is the HTTP entry point for the three CICS
 * pseudo-conversational card programs translated at source commit
 * {@code 27d6c6f}:</p>
 *
 * <ul>
 *   <li>{@code GET /api/cards} &larr; {@code COCRDLIC} (transaction {@code CCLI},
 *       card list), delegating to {@link CardListService}. The legacy PF7/PF8
 *       browse navigation is exposed as a single zero-based {@code page} request
 *       parameter; the page size is fixed inside the service.</li>
 *   <li>{@code GET /api/cards/{cardNum}} &larr; {@code COCRDSLC} (transaction
 *       {@code CCDL}, card detail), delegating to {@link CardDetailService}.</li>
 *   <li>{@code PUT /api/cards/{cardNum}} &larr; {@code COCRDUPC} (transaction
 *       {@code CCUP}, card update), delegating to {@link CardUpdateService},
 *       whose optimistic-locking guard reproduces the legacy
 *       {@code 9300-CHECK-CHANGE-IN-REC} concurrency check.</li>
 * </ul>
 *
 * <p>The controller is a thin HTTP adapter: it carries no business logic and no
 * data access. Request validation is declarative ({@link Min} on the page
 * parameter, enforced by the class-level {@link Validated}, and {@link Valid} on
 * the update body); domain errors raised by the services are translated to HTTP
 * status codes by the centralized {@code GlobalExceptionHandler}. All routes
 * require an authenticated caller, enforced by the application security
 * configuration.</p>
 */
@RestController
@RequestMapping("/api/cards")
@Validated
public class CardController {

    private final CardListService cardListService;
    private final CardDetailService cardDetailService;
    private final CardUpdateService cardUpdateService;

    /**
     * Creates the controller with its three collaborating services.
     *
     * @param cardListService   service backing the paginated card-list endpoint
     * @param cardDetailService service backing the card-detail endpoint
     * @param cardUpdateService service backing the card-update endpoint
     */
    public CardController(CardListService cardListService,
                          CardDetailService cardDetailService,
                          CardUpdateService cardUpdateService) {
        this.cardListService = cardListService;
        this.cardDetailService = cardDetailService;
        this.cardUpdateService = cardUpdateService;
    }

    /**
     * Returns one page of cards, optionally constrained by account and/or card
     * number ({@code COCRDLIC} / {@code CCLI}).
     *
     * @param accountId  optional account-id filter; {@code null} means no account
     *                   filter
     * @param cardNumber optional card-number filter; {@code null} means no card
     *                   filter
     * @param page       the zero-based page index; must not be negative
     * @return {@code 200 OK} with the requested page of card summaries
     */
    @GetMapping
    public ResponseEntity<CardDto.ListResponse> listCards(
            @RequestParam(name = "accountId", required = false) Long accountId,
            @RequestParam(name = "cardNumber", required = false) String cardNumber,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page) {
        return ResponseEntity.ok(cardListService.listCards(accountId, cardNumber, page));
    }

    /**
     * Returns the detail view of a single card ({@code COCRDSLC} / {@code CCDL}).
     *
     * @param cardNum   the sixteen-digit card number from the request path
     * @param accountId optional owning-account identifier; {@code null} means the
     *                  card number alone identifies the card
     * @return {@code 200 OK} with the card detail
     */
    @GetMapping("/{cardNum}")
    public ResponseEntity<CardDto.Detail> getCard(
            @PathVariable("cardNum") String cardNum,
            @RequestParam(name = "accountId", required = false) Long accountId) {
        return ResponseEntity.ok(cardDetailService.getCard(accountId, cardNum));
    }

    /**
     * Validates and applies an update to a single card ({@code COCRDUPC} /
     * {@code CCUP}).
     *
     * @param cardNum the sixteen-digit card number from the request path that
     *                identifies the record to update
     * @param request the validated update body carrying the new card values
     * @return {@code 200 OK} with the refreshed card detail
     */
    @PutMapping("/{cardNum}")
    public ResponseEntity<CardDto.Detail> updateCard(
            @PathVariable("cardNum") String cardNum,
            @Valid @RequestBody CardDto.UpdateRequest request) {
        return ResponseEntity.ok(
                cardUpdateService.updateCard(toAccountId(request.accountId()), cardNum, request));
    }

    /**
     * Bridges the update body's textual account identifier to the {@link Long}
     * required by {@link CardUpdateService}. The body field is validated as a
     * one-to-eleven digit string before this method runs, so the value always
     * parses into a {@link Long}.
     *
     * @param accountId the account identifier from the update body; may be
     *                  {@code null}
     * @return the parsed account identifier, or {@code null} when none is supplied
     */
    private static Long toAccountId(String accountId) {
        return accountId == null ? null : Long.valueOf(accountId);
    }
}

package com.cardemo.controller;

import com.cardemo.model.dto.CardDto;
import com.cardemo.service.card.CardDetailService;
import com.cardemo.service.card.CardListService;
import com.cardemo.service.card.CardUpdateService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST replacement for the three AWS CardDemo CICS BMS 3270 <strong>credit-card
 * screens</strong>: <strong>Card List</strong>, <strong>Card Detail / View</strong> and
 * <strong>Card Update</strong>. It exposes the three routes under
 * <strong>{@code /api/cards/*}</strong> (list, detail, update) and is a thin adapter over
 * {@link CardListService}, {@link CardDetailService} and {@link CardUpdateService}.
 *
 * <p>This controller is the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x realization of
 * AAP&nbsp;&sect;0.4.1 (tech-spec&nbsp;L648: <em>{@code controller/CardController.java}
 * CREATE &larr; {@code app/bms/COCRDLI.bms}, {@code COCRDSL.bms}, {@code COCRDUP.bms} &mdash;
 * "GET/PUT /api/cards/*"</em>) and of AAP&nbsp;&sect;0.3.4 (BMS&nbsp;&rarr;&nbsp;REST contract
 * translation). It preserves features <strong>F-006</strong> (Card List),
 * <strong>F-007</strong> (Card Detail) and <strong>F-008</strong> (Card Update) without
 * expansion.</p>
 *
 * <h2>Authoritative source artifacts (read-only reference, never copied)</h2>
 * <ul>
 *   <li><strong>{@code app/bms/COCRDLI.bms}</strong> &mdash; the Card List mapset
 *       ({@code COCRDLI} / map {@code CCRDLIA}), driven by CICS program {@code COCRDLIC},
 *       transaction <strong>{@code CCLI}</strong>. The browse painted a fixed
 *       {@code OCCURS 7} row array &mdash; <strong>exactly seven rows per page</strong>.</li>
 *   <li><strong>{@code app/bms/COCRDSL.bms}</strong> &mdash; the Card Detail / View mapset
 *       ({@code COCRDSL} / map {@code CCRDSLA}), driven by CICS program {@code COCRDSLC},
 *       transaction <strong>{@code CCDL}</strong>. A single keyed read by card number.</li>
 *   <li><strong>{@code app/bms/COCRDUP.bms}</strong> &mdash; the Card Update mapset
 *       ({@code COCRDUP} / map {@code CCRDUPA}), driven by CICS program {@code COCRDUPC},
 *       transaction <strong>{@code CCUP}</strong>. A keyed update guarded by optimistic
 *       concurrency.</li>
 * </ul>
 * <p>Their symbolic maps live under {@code app/cpy-bms/} and were migrated into
 * {@link CardDto} (the {@code COPY CSSETATY} field contract). This controller never
 * re-declares those structures and never copies COBOL/BMS text &mdash; only the screen
 * <em>behavior</em> is reproduced, by delegation to the three card services.</p>
 *
 * <h2>Key insight &mdash; card programs key on the CARD NUMBER</h2>
 * <p>{@code COCRDSLC} and {@code COCRDUPC} read {@code CARDDAT} keyed solely on the
 * sixteen-character card number; the account id is supplementary and is
 * <em>format-validated</em> by the services, never used as the read key (AAP
 * &sect;0.6.2). The routes therefore make the card number the authoritative
 * <strong>path</strong> key ({@code /api/cards/{cardNumber}}) and carry the account id as a
 * query parameter (detail) or in the request body (update).</p>
 *
 * <h2>COBOL &rarr; REST substitutions (documented per the Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>{@code SEND MAP} / {@code RECEIVE MAP} &rarr; request/response DTO.</strong>
 *       The 3270 map paint and field harvest collapse into Jackson (de)serialization of
 *       {@link CardDto} (and, for the list, its nested {@link CardDto.CardListItem} rows).
 *       Screen chrome, message lines and BMS control bytes have no REST analogue and are
 *       not modeled (AAP &sect;0.4.2).</li>
 *   <li><strong>{@code RETURN TRANSID(...) COMMAREA} &rarr; stateless REST.</strong> The
 *       pseudo-conversational hand-off is replaced by stateless HTTP; this controller holds
 *       no conversational state (AAP &sect;0.1.2).</li>
 *   <li><strong>PF07 (page-up) / PF08 (page-down) &rarr; {@code page&plusmn;1} query
 *       parameter.</strong> The {@code COCRDLIC} backward/forward browse keys map to the
 *       client re-calling {@code GET /api/cards?page=N-1} / {@code ?page=N+1}; paging is
 *       stateless navigation, not server-held cursor state. The page size is fixed at
 *       <strong>seven rows</strong> and {@code page} is <strong>one-based</strong>.</li>
 *   <li><strong>Row-selection {@code 'S'}/{@code 'U'} and PF03 (back) / PF05 (save) /
 *       PF12 (cancel) &rarr; distinct client-driven REST calls.</strong> Choosing a list
 *       row to view or update, and the back/save/cancel navigation, are client concerns:
 *       the client simply calls the detail or update endpoint for the chosen card (AAP
 *       &sect;0.1.2). They are not endpoints on this controller.</li>
 * </ul>
 *
 * <h2>Thin-adapter contract (AAP &sect;0.3.3)</h2>
 * <p>This controller contains <strong>no business logic and no data access</strong>: the
 * card-number keyed reads, the account/card format validation with verbatim ordered
 * messages, the seven-rows-per-page pagination, and the {@code @Version} optimistic-lock
 * update all live in the services. Each handler is a pure delegation that returns
 * {@code 200 OK} with the service's {@link CardDto}.</p>
 *
 * <h2>Error handling &mdash; centralized advice, exceptions propagate</h2>
 * <p>This controller defines <strong>no</strong> {@code @ExceptionHandler} /
 * {@code @RestControllerAdvice} and catches no domain exception. The services throw and
 * this controller lets propagate the three typed exceptions translated by the centralized
 * {@code @RestControllerAdvice} in {@code config/WebConfig}:</p>
 * <ul>
 *   <li>{@code com.cardemo.exception.ValidationException} &rarr; HTTP&nbsp;<strong>400 Bad
 *       Request</strong> (the verbatim COBOL edit messages).</li>
 *   <li>{@code com.cardemo.exception.RecordNotFoundException} &rarr;
 *       HTTP&nbsp;<strong>404 Not Found</strong> ("Did not find cards for this search
 *       condition").</li>
 *   <li>{@code com.cardemo.exception.ConcurrentModificationException} &rarr;
 *       HTTP&nbsp;<strong>409 Conflict</strong> on a {@code @Version} optimistic-lock
 *       conflict during update. This is deliberately the {@code com.cardemo.exception}
 *       type, <strong>never</strong> {@link java.util.ConcurrentModificationException}.</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>All {@code /api/cards/*} endpoints require authentication; access is governed by the
 * Spring Security configuration in {@code config/SecurityConfig}. No security
 * infrastructure is introduced in this thin adapter.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL/BMS baseline at commit
 * SHA {@code 27d6c6f}. The COBOL and BMS sources are read-only reference material and are
 * never copied into this repository (AAP &sect;0.7.2).</p>
 *
 * @see CardListService
 * @see CardDetailService
 * @see CardUpdateService
 * @see CardDto
 */
@RestController
@RequestMapping("/api/cards")
public class CardController {

    /** Service owning the paginated card browse &mdash; seven rows/page ({@code COCRDLIC}). */
    private final CardListService cardListService;

    /** Service owning the single keyed card read ({@code COCRDSLC}). */
    private final CardDetailService cardDetailService;

    /** Service owning the {@code @Version} optimistic-lock card update ({@code COCRDUPC}). */
    private final CardUpdateService cardUpdateService;

    /**
     * Constructs the controller with the three card services injected by Spring
     * (constructor injection; all fields are {@code final}; no field {@code @Autowired}).
     *
     * @param cardListService   the paginated card-list browse service ({@code COCRDLIC})
     * @param cardDetailService the single-card detail/view service ({@code COCRDSLC})
     * @param cardUpdateService the optimistic-lock card-update service ({@code COCRDUPC})
     */
    public CardController(final CardListService cardListService,
                          final CardDetailService cardDetailService,
                          final CardUpdateService cardUpdateService) {
        this.cardListService = cardListService;
        this.cardDetailService = cardDetailService;
        this.cardUpdateService = cardUpdateService;
    }

    /**
     * Returns one page of the card-list browse, reproducing {@code COCRDLIC}.
     *
     * <p><strong>Endpoint:</strong> {@code GET /api/cards}.</p>
     *
     * <p>The optional {@code accountId} and {@code cardNumber} query parameters are the
     * browse <em>filters</em> the operator typed into the {@code ACCTSID}/{@code CARDSID}
     * key fields; when omitted the browse is not narrowed. The {@code page} query parameter
     * is the <strong>one-based</strong> page index (default {@code 1}); the service caps
     * each page at <strong>seven rows</strong> ({@link CardDto#ROWS_PER_PAGE}) and normalizes
     * out-of-range page numbers. The controller passes the one-based page straight through
     * &mdash; it performs no page arithmetic itself.</p>
     *
     * @param accountId optional account-number filter ({@code COCRDLIC CC-ACCT-ID}); when
     *                  supplied it must be all digits or the service raises a 400
     * @param cardNumber optional card-number filter ({@code COCRDLIC CC-CARD-NUM}); when
     *                   supplied it must be all digits or the service raises a 400
     * @param page the one-based page index to return (default {@code 1}, seven rows/page)
     * @return {@code 200 OK} carrying a {@link CardDto} whose {@code cards} list holds up to
     *         seven {@link CardDto.CardListItem} rows for the requested {@code pageNumber}
     *         (possibly empty when the search yields no records)
     * @throws com.cardemo.exception.ValidationException if a supplied filter is not numeric
     *         (rendered as HTTP&nbsp;400 by {@code config/WebConfig}); propagated, not caught
     */
    // COBOL substitution: COCRDLIC SEND MAP('CCRDLIA') painted a fixed OCCURS 7 row array;
    // its STARTBR/READNEXT browse + PF07 (page-up) / PF08 (page-down) keys map to a stateless
    // GET with a one-based `page` query param at 7 rows/page -- the client requests page-1 /
    // page+1 (AAP §0.1.2). RECEIVE MAP field harvest -> the query params bound below. No
    // server-held conversational state (RETURN TRANSID COMMAREA -> stateless REST).
    @GetMapping
    public ResponseEntity<CardDto> listCards(@RequestParam(required = false) final String accountId,
                                             @RequestParam(required = false) final String cardNumber,
                                             @RequestParam(defaultValue = "1") final int page) {
        // Pure delegation: the 1-based page is passed through unchanged; CardListService owns
        // the 7-rows/page cap, the ascending card-number browse order and the filter edits.
        return ResponseEntity.ok(cardListService.listCards(accountId, cardNumber, page));
    }

    /**
     * Returns the detail of a single card, reproducing {@code COCRDSLC}.
     *
     * <p><strong>Endpoint:</strong> {@code GET /api/cards/{cardNumber}}.</p>
     *
     * <p>The card number is the authoritative read key (path variable); the account id is
     * supplied as a query parameter and is <em>format-validated</em> by the service but is
     * not part of the read key (COCRDSLC parity). {@code accountId} is intentionally
     * {@code required = false} so that a missing account reaches the service, which then
     * raises the verbatim COBOL edit message ("Account number not provided") rather than a
     * generic framework "missing parameter" error &mdash; preserving the ordered validation
     * messages (AAP &sect;0.7.2).</p>
     *
     * @param cardNumber the sixteen-digit card number to read ({@code COCRDSLC CC-CARD-NUM});
     *                   the sole read key
     * @param accountId  the account number from the request ({@code COCRDSLC CC-ACCT-ID});
     *                   required and validated for format by the service, not a read key
     * @return {@code 200 OK} carrying the populated single-card {@link CardDto}
     * @throws com.cardemo.exception.ValidationException     if the account or card input fails
     *         its edit (HTTP&nbsp;400); propagated, not caught
     * @throws com.cardemo.exception.RecordNotFoundException if no card exists for the supplied
     *         card number (HTTP&nbsp;404); propagated, not caught
     */
    // COBOL substitution: COCRDSLC EXEC CICS READ FILE('CARDDAT') RIDFLD(card-number) ->
    // CardDetailService.getCardDetail keyed on the 16-char card number (the path variable).
    // RECEIVE MAP('CCRDSLA') field harvest -> path/query binding; SEND MAP -> CardDto JSON.
    @GetMapping("/{cardNumber}")
    public ResponseEntity<CardDto> getCard(@PathVariable final String cardNumber,
                                           @RequestParam(required = false) final String accountId) {
        // Pure delegation: the service validates both inputs (verbatim ordered messages) and
        // performs the card-number keyed read; absence surfaces as RecordNotFoundException (404).
        return ResponseEntity.ok(cardDetailService.getCardDetail(accountId, cardNumber));
    }

    /**
     * Updates a single card, reproducing the server-side core of {@code COCRDUPC}.
     *
     * <p><strong>Endpoint:</strong> {@code PUT /api/cards/{cardNumber}}.</p>
     *
     * <p>The pseudo-conversational "view&nbsp;&rarr;&nbsp;edit&nbsp;&rarr;&nbsp;confirm&nbsp;
     * &rarr;&nbsp;rewrite" choreography (PF05 confirm) collapses into a single
     * {@code PUT}. The card number is the authoritative key (path variable); the account id
     * and the editable fields (embossed name, active status, expiry month/year) travel in the
     * request body. The body is bound as {@code @RequestBody} <strong>without</strong>
     * {@code @Valid}: the service owns the ordered verbatim COBOL edit messages, so applying
     * bean validation here would pre-empt them and break message parity (AAP &sect;0.7.2,
     * consistent with the other CardDemo controllers). The {@link CardDto} Jakarta
     * constraints remain the documented field contract.</p>
     *
     * @param cardNumber the sixteen-digit card number to update ({@code COCRDUPC CC-CARD-NUM});
     *                   the authoritative key (path variable)
     * @param request    the edited card payload &mdash; carries the owning {@code accountId}
     *                   (read via {@link CardDto#getAccountId()}) plus the editable fields
     * @return {@code 200 OK} carrying the updated single-card {@link CardDto}
     * @throws com.cardemo.exception.ValidationException             if any input edit fails
     *         (HTTP&nbsp;400); propagated, not caught
     * @throws com.cardemo.exception.RecordNotFoundException         if no card exists for the
     *         supplied card number (HTTP&nbsp;404); propagated, not caught
     * @throws com.cardemo.exception.ConcurrentModificationException if the record was changed
     *         concurrently (JPA {@code @Version} optimistic-lock conflict, HTTP&nbsp;409);
     *         propagated, not caught &mdash; this is the {@code com.cardemo.exception} type,
     *         never {@link java.util.ConcurrentModificationException}
     */
    // COBOL substitution: COCRDUPC PF05-confirmed READ ... UPDATE + REWRITE (with the manual
    // before/after image compare of 9300-CHECK-CHANGE-IN-REC) -> a single PUT delegating to
    // CardUpdateService, whose @Version optimistic lock carries the concurrency contract.
    // RECEIVE MAP('CCRDUPA') field harvest -> @RequestBody CardDto; the account id is taken
    // from the body (request.getAccountId()) while the card number stays the path key.
    @PutMapping("/{cardNumber}")
    public ResponseEntity<CardDto> updateCard(@PathVariable final String cardNumber,
                                              @RequestBody final CardDto request) {
        // Pure delegation: card number from the path is authoritative; the account id is taken
        // from the body. The service validates, reads, detects no-change, applies and saves;
        // a @Version conflict surfaces as com.cardemo.exception.ConcurrentModificationException (409).
        return ResponseEntity.ok(cardUpdateService.updateCard(request.getAccountId(), cardNumber, request));
    }
}

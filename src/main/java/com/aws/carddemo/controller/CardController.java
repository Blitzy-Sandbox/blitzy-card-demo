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
package com.aws.carddemo.controller;

import com.aws.carddemo.entity.Card;
import com.aws.carddemo.service.CardDetailResponse;
import com.aws.carddemo.service.CardDetailService;
import com.aws.carddemo.service.CardListRequest;
import com.aws.carddemo.service.CardListResponse;
import com.aws.carddemo.service.CardListService;
import com.aws.carddemo.service.CardUpdateRequest;
import com.aws.carddemo.service.CardUpdateResult;
import com.aws.carddemo.service.CardUpdateService;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST controller exposing the CardDemo credit-card endpoints — the Java
 * migration of the THREE CICS BMS screens and THREE COBOL programs that
 * collectively own the credit-card list / detail / update lifecycle:
 *
 * <ul>
 *   <li>{@code app/bms/COCRDLI.bms} + {@code app/cbl/COCRDLIC.cbl}
 *       (TRANID {@code CCLI}, 1,459 lines) — paged card list (7 rows/page;
 *       supports optional {@code accountId} and {@code cardNumberPrefix}
 *       filters).</li>
 *   <li>{@code app/bms/COCRDSL.bms} + {@code app/cbl/COCRDSLC.cbl}
 *       (TRANID {@code CCDL}, 887 lines) — single-card detail with the
 *       Java-migration-added {@code isExpired} display flag.</li>
 *   <li>{@code app/bms/COCRDUP.bms} + {@code app/cbl/COCRDUPC.cbl}
 *       (TRANID {@code CCUP}, 1,560 lines) — card-record update with
 *       JPA {@code @Version} optimistic locking (replaces the COBOL
 *       READ UPDATE / REWRITE before-image / after-image comparison at
 *       lines 1453–1483).</li>
 * </ul>
 *
 * <h2>HTTP Contract</h2>
 *
 * <p>All endpoints require authentication; unauthenticated callers receive
 * HTTP 401 from Spring Security's filter chain. The endpoints are not
 * admin-restricted: any authenticated user may list / view / update cards,
 * mirroring the COBOL workflow where regular operators (USR-TYPE = "U") had
 * access to the {@code CCLI / CCDL / CCUP} TRANIDs (with the COBOL workflow
 * narrowing the visible card set to the operator's account for non-admin
 * users — that narrowing is expressed here as the {@code accountId} query
 * filter on the list endpoint). State-changing requests (the PUT) require a
 * valid CSRF token; missing tokens produce HTTP 403.
 *
 * <ul>
 *   <li>{@code GET /api/cards} — list cards (7 rows/page); optional query
 *       parameters {@code page} (zero-based, default 0),
 *       {@code accountId} (11-character filter), and
 *       {@code cardNumberPrefix} (1–16 character prefix filter — the COBOL
 *       workflow performs an exact-match check; the Java migration
 *       generalises to prefix-match per AAP §0.10.2 for richer REST
 *       semantics, with a 16-character argument still matching at most
 *       one card). Returns a {@link CardListJsonResponse} carrying a list
 *       of {@link CardSummary} rows plus the page navigation flags.</li>
 *   <li>{@code GET /api/cards/{cardNumber}} — view a single card by its
 *       16-character {@code CARD-NUM} primary key. Returns a
 *       {@link CardDetailJsonResponse} on success (HTTP 200), HTTP 400 on
 *       a malformed path variable (BEFORE the service is invoked — defends
 *       the COBOL implicit 16-character numeric-key contract), or HTTP 404
 *       when the service reports {@code "Card number not found..."}
 *       (preserves COBOL {@code COCRDSLC} {@code DFHRESP(NOTFND)}
 *       semantics).</li>
 *   <li>{@code PUT /api/cards/{cardNumber}} — update an existing card. The
 *       request body is a JSON-serialised {@link CardUpdateRequest}; the
 *       path variable is the authoritative card-number target (a body that
 *       carries a different {@code cardNumber} is rejected with HTTP 400
 *       — defends the immutable-PK contract per AAP §0.10.4). On the happy
 *       path returns HTTP 200 with a {@link CardUpdateJsonResponse}
 *       carrying the {@code "Changes committed to database"} confirmation
 *       (verbatim COBOL {@code CONFIRM-UPDATE-SUCCESS} from
 *       {@code COCRDUPC.cbl} line 169). On validation failure returns
 *       HTTP 400 with the COBOL-equivalent reject message verbatim (the
 *       service's full cascade of card-number / CVV / name / date / status
 *       checks). On card-not-found returns HTTP 404. On JPA
 *       {@code @Version} mismatch ({@link OptimisticLockingFailureException}
 *       thrown by the service) returns HTTP 409 Conflict.</li>
 * </ul>
 *
 * <h2>COBOL Pagination Constraint</h2>
 *
 * <p>The list endpoint is paged at {@link #PAGE_SIZE} = 7 rows per page —
 * a direct port of the COBOL {@code WS-MAX-SCREEN-LINES VALUE 7} constant
 * declared at lines 177–178 of {@code COCRDLIC.cbl}. This is smaller than
 * the 10-rows-per-page contract used by {@link TransactionController}
 * (which mirrors the COBOL {@code COTRN00C} {@code TRAN-REC OCCURS 10
 * TIMES} table). The mismatch is intentional and preserves AAP §0.10.4
 * (Immutable Boundaries: downstream consumers depend on the same row
 * counts the COBOL baseline emitted).
 *
 * <h2>PCI / CVV Containment (AAP §0.10.5)</h2>
 *
 * <p>The {@link Card} entity exposes a 3-digit CVV value via
 * {@link Card#getCvvCode()} (replacement of the COBOL
 * {@code CARD-CVV-CD PIC 9(03)} field). The controller deliberately
 * <strong>omits</strong> the CVV field from every wire-format response
 * (it is NOT projected into {@link CardSummary} or
 * {@link CardDetailJsonResponse}). This preserves AAP §0.10.5
 * ("No financial data written to logs at any level") at the HTTP boundary:
 * the JSON payload that crosses the wire never contains a CVV value, and
 * the {@code logback-test.xml} turbofilter masks any accidental log
 * disclosure. The {@link CardController} accepts a CVV on the
 * {@link CardUpdateRequest} body (the operator may rotate the CVV via the
 * update flow per the COBOL {@code COCRDUPC.cbl} field-edit semantics) but
 * never echoes it back in the response.
 *
 * <h2>Immutable Primary Key Defence (AAP §0.10.4)</h2>
 *
 * <p>The card number is a 16-character immutable primary key in both the
 * COBOL workflow (the {@code COCRDUPC.cbl} {@code INITIALIZE CARD-UPDATE-
 * RECORD} block at lines 1461–1474 preserves {@code CARD-NUM} from the
 * loaded record before mutating any other field) and in the Java migration
 * (the {@link CardUpdateService} never calls
 * {@code card.setCardNumber(...)}). The controller's
 * {@link #updateCard(String, CardUpdateRequest)} method ADDITIONALLY
 * defends this contract by rejecting any request body whose
 * {@link CardUpdateRequest#getCardNumber()} disagrees with the
 * {@code {cardNumber}} path variable — this catches client-side bugs
 * before they reach the service.
 *
 * <h2>Cross-Cutting Concerns</h2>
 *
 * <ul>
 *   <li><b>Service-layer reject-message mirrors.</b> The service constants
 *       on {@link CardDetailService} and {@link CardUpdateService} are
 *       package-private and cannot be referenced from a sibling package.
 *       The relevant literals are duplicated here so the HTTP-status
 *       mapping can dispatch on them. The matching test
 *       {@code CardControllerTest} asserts every mapping against the same
 *       literals so drift surfaces loudly (AAP §0.10.10 style
 *       consistency).</li>
 *   <li><b>BigDecimal financial precision.</b> The {@link Card} entity has
 *       no monetary field (card balances live on {@code Account}). The
 *       controller therefore does not handle any {@link java.math.BigDecimal}
 *       boundary; AAP §0.10.3 financial-precision constraints apply only
 *       transitively when a future migration step joins
 *       {@code Card → Account} on the wire-format response.</li>
 * </ul>
 *
 * @see CardListService
 * @see CardDetailService
 * @see CardUpdateService
 */
@RestController
@RequestMapping("/api/cards")
public class CardController {

    // ------------------------------------------------------------------------
    // Service-layer reject-message mirrors
    // ------------------------------------------------------------------------
    //
    // The MSG_* constants on CardDetailService and CardUpdateService are
    // package-private (no modifier on the `static final String` declarations),
    // so they cannot be referenced from a sibling package. The controller
    // duplicates the literals here so the HTTP-status mapping can dispatch
    // on them. CardControllerTest asserts every mapped status against the
    // SAME literals — drift will therefore fail the controller test and the
    // service test together, surfacing the issue loudly.
    //
    // Sources:
    //   com.aws.carddemo.service.CardDetailService.MSG_CARD_NOT_FOUND
    //   com.aws.carddemo.service.CardUpdateService.MSG_CARD_NOT_FOUND
    //   com.aws.carddemo.service.CardUpdateService.MSG_UPDATE_SUCCESS
    // ------------------------------------------------------------------------

    /** CardDetailService reject when the card is absent on GET; HTTP 404. */
    static final String MSG_CARD_NOT_FOUND_DETAIL = "Card number not found...";

    /** CardUpdateService reject when the card is absent on PUT; HTTP 404. */
    static final String MSG_CARD_NOT_FOUND_UPDATE = "Did not find cards for this search condition";

    /** HTTP 400 message when the path variable is not a 16-digit numeric string. */
    static final String MSG_CARD_NUMBER_MUST_BE_16_DIGITS = "Card number must be 16 numeric digits";

    /** HTTP 400 message when the request body's cardNumber does not match the path variable. */
    static final String MSG_CARD_NUMBER_PATH_BODY_MISMATCH =
            "Card number in path does not match card number in body";

    /** HTTP 409 message returned on JPA @Version optimistic-locking conflict. */
    static final String MSG_OPTIMISTIC_LOCK_CONFLICT =
            "Record changed by some one else. Please review";

    /** HTTP 500 message for unexpected service-layer exceptions. Never echoes the cause. */
    static final String MSG_INTERNAL_ERROR = "An unexpected error occurred";

    // ------------------------------------------------------------------------
    // COBOL parity constants
    // ------------------------------------------------------------------------

    /**
     * Fixed page size for the list endpoint — direct port of the COBOL
     * {@code WS-MAX-SCREEN-LINES VALUE 7} constant declared at lines 177–178
     * of {@code app/cbl/COCRDLIC.cbl} and tested inside
     * {@code 9000-READ-FORWARD} via {@code IF WS-SCRN-COUNTER =
     * WS-MAX-SCREEN-LINES} at line 1191 (the loop-exit condition).
     * Preserved in the Java migration via {@link CardListService}'s
     * package-private {@code PAGE_SIZE} field.
     */
    static final int PAGE_SIZE = 7;

    /**
     * Expected character width of the {@code CARD-NUM} path-variable from
     * the {@code CARD-NUM PIC X(16)} field in {@code app/cpy/CVACT02Y.cpy}.
     * Used in the path-variable validation that rejects malformed card
     * numbers with HTTP 400 — preserving the COBOL implicit "16-byte key"
     * contract.
     */
    static final int CARD_NUMBER_WIDTH = 16;

    // ------------------------------------------------------------------------
    // Collaborators
    // ------------------------------------------------------------------------

    private final CardListService cardListService;
    private final CardDetailService cardDetailService;
    private final CardUpdateService cardUpdateService;

    /**
     * Constructs the controller with constructor-injected service collaborators.
     *
     * @param cardListService   the card-list service (TRANID {@code CCLI})
     * @param cardDetailService the card-detail service (TRANID {@code CCDL})
     * @param cardUpdateService the card-update service (TRANID {@code CCUP})
     */
    public CardController(CardListService cardListService,
                          CardDetailService cardDetailService,
                          CardUpdateService cardUpdateService) {
        this.cardListService = cardListService;
        this.cardDetailService = cardDetailService;
        this.cardUpdateService = cardUpdateService;
    }

    // ========================================================================
    // GET /api/cards — list cards (COCRDLIC / TRANID CCLI)
    // ========================================================================

    /**
     * Lists cards, 7 per page (COBOL {@code WS-MAX-SCREEN-LINES VALUE 7}).
     * Optional account-ID and card-number-prefix filters are supported as
     * URL query parameters; card-number-prefix wins if both are populated
     * (mirrors {@link CardListService}'s dispatcher contract).
     *
     * @param page              zero-based page index (default {@code 0})
     * @param accountId         optional 11-character account-ID filter;
     *                          {@code null} or empty means no account filter
     * @param cardNumberPrefix  optional 1–16 character card-number prefix
     *                          filter; {@code null} or empty means no card
     *                          filter
     * @return HTTP 200 with a {@link CardListJsonResponse} carrying the
     *         page of {@link CardSummary} rows plus the page navigation
     *         flags
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CardListJsonResponse> listCards(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "accountId", required = false) String accountId,
            @RequestParam(value = "cardNumberPrefix", required = false) String cardNumberPrefix) {

        CardListRequest req = new CardListRequest();
        req.setPage(page);
        req.setAccountIdFilter(accountId);
        req.setCardNumberFilter(cardNumberPrefix);

        CardListResponse result = cardListService.listCards(req);

        // Project each Card entity into a CardSummary record so the
        // wire-format response is governed by the controller's projection,
        // not the entity. The CVV is intentionally OMITTED from the
        // projection per AAP §0.10.5 (PCI containment).
        List<CardSummary> content = result.getCards().stream()
                .map(CardController::toSummary)
                .collect(Collectors.toUnmodifiableList());

        return ResponseEntity.ok(new CardListJsonResponse(
                true,
                content,
                result.getCurrentPage(),
                PAGE_SIZE,
                result.isHasNext(),
                result.isHasPrevious(),
                content.size(),
                null));
    }

    // ========================================================================
    // GET /api/cards/{cardNumber} — view single card (COCRDSLC / CCDL)
    // ========================================================================

    /**
     * Look up a single card by its 16-character {@code CARD-NUM} primary
     * key. The path variable is validated for exact 16-character width
     * AND digit-only content (preserves the COBOL implicit "16-digit key"
     * contract); a malformed card number produces HTTP 400 BEFORE the
     * service is invoked.
     *
     * @param cardNumber the 16-character {@code CARD-NUM} path variable
     * @return HTTP 200 with the populated {@link CardDetailJsonResponse}
     *         on success; HTTP 400 when the path variable is malformed;
     *         HTTP 404 when the service reports
     *         {@link #MSG_CARD_NOT_FOUND_DETAIL}
     */
    @GetMapping(path = "/{cardNumber}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CardDetailJsonResponse> getCard(
            @PathVariable("cardNumber") String cardNumber) {

        // Defensive path-variable validation — reject malformed card
        // numbers at the boundary so the service does not receive obviously
        // bad inputs. Mirrors the COBOL implicit contract that CARD-NUM is
        // exactly 16 numeric digits (PIC X(16) carrying numeric PANs).
        if (!isWellFormedCardNumber(cardNumber)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(CardDetailJsonResponse.failure(cardNumber,
                            MSG_CARD_NUMBER_MUST_BE_16_DIGITS));
        }

        CardDetailResponse result = cardDetailService.getCard(cardNumber);

        if (result.isSuccess()) {
            return ResponseEntity.ok(CardDetailJsonResponse.success(result));
        }

        // Failure path — map the reject message to HTTP status. The only
        // service-level reject path is the NOTFND branch which maps to
        // HTTP 404 Not Found (preserving COBOL COCRDSLC DFHRESP(NOTFND)).
        // Any other reject text falls through to HTTP 400 by default
        // (defensive — no other reject text is expected from the service).
        String msg = result.getMessage();
        HttpStatus status = MSG_CARD_NOT_FOUND_DETAIL.equals(msg)
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(CardDetailJsonResponse.failure(cardNumber, msg));
    }

    // ========================================================================
    // PUT /api/cards/{cardNumber} — update card (COCRDUPC / TRANID CCUP)
    // ========================================================================

    /**
     * Updates an existing card record. The path variable is the authoritative
     * card-number target; a request body that carries a different
     * {@code cardNumber} is rejected with HTTP 400 (defends the immutable-PK
     * contract per AAP §0.10.4).
     *
     * <p>On JPA {@code @Version} mismatch the
     * {@link CardUpdateService#updateCard(CardUpdateRequest)} call throws
     * {@link OptimisticLockingFailureException}; this controller catches it
     * and maps to HTTP 409 Conflict, mirroring the COBOL
     * {@code COCRDUPC.cbl} {@code DATA-WAS-CHANGED-BEFORE-UPDATE} (line
     * 1511) before/after-image-comparison reject path.
     *
     * @param cardNumber the 16-character {@code CARD-NUM} path variable
     *                   (authoritative target)
     * @param request    the JSON-serialised {@link CardUpdateRequest} carrying
     *                   the operator-modified field values
     * @return HTTP 200 on success; HTTP 400 on a validation reject (including
     *         malformed path variable and path-vs-body mismatch); HTTP 404
     *         on card-not-found; HTTP 409 on optimistic-lock conflict
     */
    @PutMapping(path = "/{cardNumber}",
                consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CardUpdateJsonResponse> updateCard(
            @PathVariable("cardNumber") String cardNumber,
            @RequestBody CardUpdateRequest request) {

        // Step 1 — defensive path-variable validation (same idiom as the
        // GET endpoint). A malformed path variable is rejected with HTTP
        // 400 BEFORE any service invocation.
        if (!isWellFormedCardNumber(cardNumber)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new CardUpdateJsonResponse(
                            false,
                            cardNumber,
                            (request == null) ? null : request.getVersion(),
                            MSG_CARD_NUMBER_MUST_BE_16_DIGITS));
        }

        // Step 2 — immutable-PK defence (AAP §0.10.4). A body that carries
        // a different cardNumber than the path variable is rejected with
        // HTTP 400 before the service is invoked. This catches client-side
        // bugs (e.g., a frontend that forgets to keep path and body in
        // sync) before they can attempt a primary-key mutation.
        if (request != null
                && request.getCardNumber() != null
                && !cardNumber.equals(request.getCardNumber())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new CardUpdateJsonResponse(
                            false,
                            cardNumber,
                            request.getVersion(),
                            MSG_CARD_NUMBER_PATH_BODY_MISMATCH));
        }

        // Step 3 — honour the path variable as the authoritative card number.
        // The mutable setter is the field-binding contract documented on
        // CardUpdateRequest. This handles the case where the body omits the
        // field (null) — the service still needs to know which card to look
        // up.
        if (request != null) {
            request.setCardNumber(cardNumber);
        }

        try {
            CardUpdateResult result = cardUpdateService.updateCard(request);

            Long requestVersion = (request == null) ? null : request.getVersion();

            if (result.isSuccess()) {
                return ResponseEntity.ok(new CardUpdateJsonResponse(
                        true,
                        cardNumber,
                        requestVersion,
                        result.getMessage()));
            }

            // Failure path — map the reject message to HTTP status.
            String msg = result.getMessage();
            HttpStatus status;
            if (MSG_CARD_NOT_FOUND_UPDATE.equals(msg)) {
                // COBOL COCRDUPC: EXEC CICS READ NOTFND →
                // 'Did not find cards for this search condition'.
                // Maps to HTTP 404 Not Found.
                status = HttpStatus.NOT_FOUND;
            } else {
                // Validation rejects (card-number invalid, CVV invalid,
                // embossed-name missing, expiration-date invalid, active-
                // status invalid) all map to HTTP 400.
                status = HttpStatus.BAD_REQUEST;
            }
            return ResponseEntity.status(status)
                    .body(new CardUpdateJsonResponse(
                            false,
                            cardNumber,
                            requestVersion,
                            msg));
        } catch (OptimisticLockingFailureException ex) {
            // JPA @Version mismatch — another session updated the row
            // between our load and our save. Preserves the COBOL COCRDUPC
            // {@code DATA-WAS-CHANGED-BEFORE-UPDATE} (line 1511)
            // before-image / after-image comparison semantics. Maps to
            // HTTP 409 Conflict per REST convention.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new CardUpdateJsonResponse(
                            false,
                            cardNumber,
                            (request == null) ? null : request.getVersion(),
                            MSG_OPTIMISTIC_LOCK_CONFLICT));
        }
    }

    // ========================================================================
    // @ExceptionHandler — unexpected service failures
    // ========================================================================

    /**
     * Handles unexpected {@link RuntimeException}s thrown by the service
     * layer. Returns HTTP 500 with a sanitised body — the underlying
     * exception detail must never leak into the HTTP response (AAP §0.10.5
     * applied to error paths).
     *
     * <p>{@link AccessDeniedException} (and its Spring Security 6.1+
     * subtype {@code AuthorizationDeniedException}) is RE-THROWN so Spring
     * Security's {@code ExceptionTranslationFilter} can map it to HTTP 403.
     * {@link OptimisticLockingFailureException} is handled directly in the
     * PUT method (so it never reaches this handler).
     *
     * @param ex the caught exception; not echoed in the response
     * @return HTTP 500 with a generic error message
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorJsonResponse> handleServiceFailure(RuntimeException ex) {
        if (ex instanceof AccessDeniedException) {
            throw (AccessDeniedException) ex;
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorJsonResponse(false, MSG_INTERNAL_ERROR));
    }

    // ========================================================================
    // Private helpers
    // ========================================================================

    /**
     * Validates that a candidate card-number identifier matches the COBOL
     * {@code CARD-NUM PIC X(16)} numeric-key contract: exactly 16 ASCII
     * digits.
     *
     * @param cardNumber the candidate identifier; may be {@code null}
     * @return {@code true} when the identifier is non-null, exactly 16
     *         characters, and contains only ASCII digits 0-9; {@code false}
     *         otherwise
     */
    private static boolean isWellFormedCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() != CARD_NUMBER_WIDTH) {
            return false;
        }
        for (int i = 0; i < cardNumber.length(); i++) {
            char c = cardNumber.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Projects a {@link Card} entity into a {@link CardSummary} record for
     * the list endpoint's wire-format response. The projection carries the
     * four fields that the COBOL workflow renders on each row of the
     * {@code CCRDLIA} BMS map (the {@code WS-SCREEN-ROWS OCCURS 7 TIMES}
     * table fields):
     * <ul>
     *   <li>{@code CARD-NUM} → {@code cardNumber}</li>
     *   <li>{@code CARD-ACCT-ID} → {@code accountId}</li>
     *   <li>{@code CARD-ACTIVE-STATUS} → {@code activeStatus}</li>
     *   <li>{@code CARD-EMBOSSED-NAME} → {@code embossedName}</li>
     * </ul>
     *
     * <p>The {@code CARD-CVV-CD} and {@code CARD-EXPIRAION-DATE} fields
     * are intentionally OMITTED from the list projection: the COBOL
     * workflow does not render either on the {@code CCRDLIA} list map (CVV
     * is PCI-sensitive; expiration date appears only on the detail screen).
     *
     * @param card the hydrated {@link Card} entity; never {@code null}
     * @return a fresh {@link CardSummary} carrying the per-row fields
     */
    private static CardSummary toSummary(Card card) {
        return new CardSummary(
                card.getCardNumber(),
                card.getAccountId(),
                card.getActiveStatus(),
                card.getEmbossedName());
    }

    // ========================================================================
    // Response DTOs — inner records governing the wire-format contract
    // ========================================================================

    /**
     * Per-row projection of a {@link Card} entity for the list endpoint.
     * Carries the four fields rendered on each {@code WS-SCREEN-ROWS
     * OCCURS 7 TIMES} row of the {@code CCRDLIA} BMS map. CVV and
     * expiration date are deliberately omitted (PCI containment / list
     * scope).
     *
     * @param cardNumber   16-character {@code CARD-NUM}
     * @param accountId    11-character {@code CARD-ACCT-ID}
     * @param activeStatus 1-character {@code CARD-ACTIVE-STATUS}
     *                     ({@code 'Y'} / {@code 'N'})
     * @param embossedName 50-character {@code CARD-EMBOSSED-NAME}
     */
    public static record CardSummary(
            String cardNumber,
            String accountId,
            String activeStatus,
            String embossedName) {
    }

    /**
     * Wire-format response for {@code GET /api/cards}. Carries the paged
     * list of {@link CardSummary} rows plus the page navigation flags from
     * the underlying {@link CardListResponse}.
     *
     * @param success       always {@code true} on the happy path (the
     *                      card-list service has no reject path)
     * @param content       the page of summaries (empty list for empty result)
     * @param pageNumber    the zero-based page index from the service response
     * @param pageSize      the page size (always {@link #PAGE_SIZE} = 7)
     * @param hasNext       {@code true} when there is at least one record
     *                      beyond the end of the current page
     * @param hasPrevious   {@code true} when there is at least one record
     *                      before the start of the current page
     * @param totalElements the count of summaries on the current page (used
     *                      by clients to render "showing X of Y" labels;
     *                      {@code 0} for empty result)
     * @param message       reserved for future failure paths; {@code null}
     *                      on success
     */
    public static record CardListJsonResponse(
            boolean success,
            List<CardSummary> content,
            int pageNumber,
            int pageSize,
            boolean hasNext,
            boolean hasPrevious,
            long totalElements,
            String message) {
    }

    /**
     * Wire-format response for {@code GET /api/cards/{cardNumber}}. Carries
     * the full card-detail field set on success or only the failure
     * envelope on the NOTFND reject / malformed-PK reject paths.
     *
     * <p>The {@code cvvCode} field is intentionally omitted — the controller
     * never echoes the CVV in any response per AAP §0.10.5 (PCI
     * containment).
     *
     * @param success        {@code true} on the happy path; {@code false}
     *                       on the NOTFND reject or malformed-PK reject
     * @param cardNumber     the 16-character {@code CARD-NUM} echoed from
     *                       the path variable; populated even on the failure
     *                       branch so clients can correlate
     * @param accountId      11-character {@code CARD-ACCT-ID}; null on the
     *                       failure path
     * @param embossedName   50-character {@code CARD-EMBOSSED-NAME}; null
     *                       on the failure path
     * @param activeStatus   1-character {@code CARD-ACTIVE-STATUS}
     *                       ({@code 'Y'} / {@code 'N'}); null on the
     *                       failure path
     * @param expirationDate 10-character {@code CARD-EXPIRAION-DATE} (ISO
     *                       {@code YYYY-MM-DD}); null on the failure path
     * @param expired        Java-migration display flag — {@code true} when
     *                       the card's expiration date is strictly earlier
     *                       than "today" per the service's injected
     *                       {@code Clock}; {@code false} otherwise (and
     *                       {@code false} on any failure outcome)
     * @param message        reject message on failure; null on success
     */
    public static record CardDetailJsonResponse(
            boolean success,
            String cardNumber,
            String accountId,
            String embossedName,
            String activeStatus,
            String expirationDate,
            boolean expired,
            String message) {

        /**
         * Builds a populated success response from a hydrated
         * {@link CardDetailResponse}.
         *
         * @param r the service response with all card fields populated
         * @return a success-flagged JSON response with all fields hydrated;
         *         the CVV field on the underlying response is intentionally
         *         not surfaced (PCI containment per AAP §0.10.5)
         */
        static CardDetailJsonResponse success(CardDetailResponse r) {
            return new CardDetailJsonResponse(
                    true,
                    r.getCardNumber(),
                    r.getAccountId(),
                    r.getEmbossedName(),
                    r.getActiveStatus(),
                    r.getExpirationDate(),
                    r.isExpired(),
                    null);
        }

        /**
         * Builds a failure response carrying only the card-number echo and
         * the reject message; all other fields are {@code null} /
         * {@code false}.
         *
         * @param cardNumber the requested card number (path variable)
         * @param message    the reject message
         * @return a failure-flagged JSON response
         */
        static CardDetailJsonResponse failure(String cardNumber, String message) {
            return new CardDetailJsonResponse(
                    false,
                    cardNumber,
                    null, null, null, null,
                    false,
                    message);
        }
    }

    /**
     * Wire-format response for {@code PUT /api/cards/{cardNumber}}. Carries
     * the success or reject outcome of the card-update operation along with
     * the JPA optimistic-locking version (echoed from the request body).
     *
     * <p>The {@code cvvCode}, {@code embossedName}, {@code expirationDate},
     * and {@code activeStatus} fields are intentionally NOT echoed back in
     * this response — the client just sent them, so re-emitting them carries
     * no additional information and would re-expose the operator-supplied
     * CVV (AAP §0.10.5 PCI containment).
     *
     * @param success    {@code true} on the happy path; {@code false} on
     *                   any reject path (validation, NOTFND, optimistic-
     *                   lock conflict)
     * @param cardNumber the 16-character {@code CARD-NUM} echoed from the
     *                   path variable (always populated)
     * @param version    the JPA optimistic-locking version echoed from the
     *                   request body; null when the request body omitted it
     * @param message    the COBOL-equivalent success or reject message
     */
    public static record CardUpdateJsonResponse(
            boolean success,
            String cardNumber,
            Long version,
            String message) {
    }

    /**
     * Wire-format response for unexpected service-layer failures. Returned
     * by the {@link #handleServiceFailure(RuntimeException)} exception
     * handler.
     *
     * @param success always {@code false}
     * @param message a sanitised generic message ({@link #MSG_INTERNAL_ERROR})
     */
    public static record ErrorJsonResponse(boolean success, String message) {
    }
}

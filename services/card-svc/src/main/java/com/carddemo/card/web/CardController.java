package com.carddemo.card.web;

import com.carddemo.card.api.CardsApi;
import com.carddemo.card.model.Card;
import com.carddemo.card.model.CardListResponse;
import com.carddemo.card.model.CardUpdateRequest;
import com.carddemo.card.service.CardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

/**
 * HTTP entrypoint for the card bounded context (CardDemo walking skeleton,
 * Spring Boot 3.5 / Java 21, Oracle 23ai).
 *
 * <p>This {@code @RestController} <strong>implements the build-time generated</strong>
 * OpenAPI interface {@link CardsApi}. The interface and its DTOs
 * ({@link Card}, {@link CardListResponse}, {@link CardUpdateRequest}, and the list-row
 * summary/error models) are produced by {@code openapi-generator-maven-plugin}
 * (generator {@code spring}, {@code interfaceOnly=true}, {@code useResponseEntity=true})
 * from the frozen single-source-of-truth contract
 * {@code contracts/card-svc.openapi.yaml} into
 * {@code target/generated-sources/openapi}. Those generated sources are NEVER
 * hand-written, duplicated, or edited here &mdash; this controller only implements /
 * imports / uses them, freezing the contract into the compiler via the
 * {@code implements} relationship.</p>
 *
 * <p><strong>Operations.</strong></p>
 * <ul>
 *   <li>{@link #getCardByNumber(String, String)} &mdash; <strong>THE ONE LIVE TRACER</strong>
 *       ({@code GET /cards/{cardNumber}}): a REAL Oracle read of the single Flyway-seeded
 *       row, keyed on the natural 16-digit card number. Yields {@code 200} (found),
 *       {@code 404} (empty state), {@code 400} (malformed input), or {@code 500}
 *       (read error).</li>
 *   <li>{@link #listCards(String, Integer, Integer, String)} &mdash; a typed
 *       {@code [DEFERRED]} placeholder ({@code GET /cards}) returning an empty page.
 *       Provenance: [SRC: COCRDLIC | CARDDAT] (transaction {@code CCLI}).</li>
 *   <li>{@link #updateCard(String, CardUpdateRequest, String)} &mdash; a typed
 *       {@code [DEFERRED]} placeholder ({@code PUT /cards/{cardNumber}}) returning a
 *       placeholder card. Provenance: [SRC: COCRDUPC | CARDDAT] (transaction
 *       {@code CCUP}).</li>
 * </ul>
 *
 * <p><strong>Runtime chain (tracer).</strong> React Card Detail UI &rarr; BFF
 * {@code GET /api/cards/{cardNumber}} &rarr; card-svc {@code GET /cards/{cardNumber}}
 * &rarr; {@link #getCardByNumber(String, String)} &rarr; {@link CardService#getCard(String)}
 * &rarr; {@code CardRepository.findById(cardNumber)} &rarr;
 * {@code SELECT ... FROM CARD WHERE CARD_NUM = ?} against PDB {@code FREEPDB1} &rarr;
 * seeded row &rarr; typed {@link Card} payload &rarr; rendered with real loading / empty /
 * error states.</p>
 *
 * <p><strong>Health.</strong> The contract's {@code /actuator/health} operation lands in
 * a separate generated {@code HealthApi}; it is intentionally NOT implemented here &mdash;
 * Spring Boot Actuator serves {@code /actuator/health} at runtime. Implementing it would
 * create a duplicate request-mapping conflict.</p>
 *
 * <p><strong>Error mapping.</strong> The empty ({@code 404}) and malformed ({@code 400})
 * outcomes are signalled by throwing {@link NotFoundException} / {@link BadRequestException}
 * (nested here) rather than by returning a body-less {@link ResponseEntity}; the sibling
 * {@code GlobalExceptionHandler} advice maps them to RFC&nbsp;7807
 * {@code application/problem+json} bodies. Data-access failures are deliberately NOT caught
 * &mdash; they propagate uncaught to that advice's fallback handler and become {@code 500}.</p>
 *
 * <p>Provenance: [SRC: COCRDSLC | CARDDAT] &mdash; app/cbl/COCRDSLC.cbl (transaction
 * {@code CCDL}, file {@code CARDDAT}), registered in app/csd/CARDDEMO.CSD; the legacy
 * paragraph {@code 9100-GETCARD-BYACCTCARD} evaluates the VSAM read response
 * ({@code NORMAL}/{@code NOTFND}/{@code OTHER} &rarr; {@code 200}/{@code 404}/{@code 500})
 * and {@code 2220-EDIT-CARD} validates the 16-digit card number ({@code 400}).</p>
 */
@RestController
public class CardController implements CardsApi {

    /**
     * The live-tracer read service. Injected by constructor and held {@code final} for
     * immutability and thread safety. As the sole constructor, Spring autowires it
     * automatically without an explicit {@code @Autowired} annotation.
     */
    private final CardService cardService;

    /**
     * Constructor injection of the card service.
     *
     * @param cardService the service performing the live tracer read (entity &rarr; DTO)
     */
    public CardController(CardService cardService) {
        this.cardService = cardService;
    }

    /**
     * <strong>THE LIVE TRACER</strong> &mdash; {@code GET /cards/{cardNumber}}.
     *
     * <p>Behaviour mirrors the legacy {@code EVALUATE WS-RESP-CD} in
     * {@code 9100-GETCARD-BYACCTCARD}:</p>
     * <ul>
     *   <li><strong>400</strong> &mdash; a defensive guard rejects a {@code null} or
     *       non-16-digit card number with {@link BadRequestException} (mirrors
     *       {@code 2220-EDIT-CARD}: "Card number if supplied must be a 16 digit number").
     *       The generated interface parameter may also carry bean-validation constraints
     *       that yield {@code 400}; this guard guarantees the {@code 400} regardless.</li>
     *   <li><strong>200</strong> &mdash; a present {@link java.util.Optional} from
     *       {@link CardService#getCard(String)} is returned as {@code ResponseEntity.ok(dto)}.</li>
     *   <li><strong>404</strong> &mdash; {@link java.util.Optional#empty()} throws
     *       {@link NotFoundException} ("Did not find this card"), the UI empty state.
     *       Throwing (rather than a body-less {@code 404}) lets the advice attach the
     *       RFC&nbsp;7807 body.</li>
     *   <li><strong>500</strong> &mdash; any data-access exception thrown by the service is
     *       deliberately NOT caught; it propagates to the advice's fallback handler (the UI
     *       error state).</li>
     * </ul>
     *
     * <p>The {@code xCorrelationID} header parameter is declared to match the generated
     * signature but intentionally unused here: correlation-ID capture and propagation are
     * handled by the sibling {@code config.CorrelationIdFilter} via the SLF4J MDC.</p>
     *
     * @param cardNumber    the natural 16-digit card number (path variable)
     * @param xCorrelationID the optional correlation-ID header (handled by the filter; unused here)
     * @return {@code 200} with the found {@link Card}; otherwise {@code 400}/{@code 404}/{@code 500}
     *         are signalled via thrown/propagated exceptions
     */
    @Override
    public ResponseEntity<Card> getCardByNumber(String cardNumber, String xCorrelationID) {
        if (cardNumber == null || !cardNumber.matches("^[0-9]{16}$")) {
            throw new BadRequestException("Card number if supplied must be a 16 digit number");
        }
        return cardService.getCard(cardNumber)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NotFoundException("Did not find this card"));
    }

    /**
     * {@code [DEFERRED]} typed stub &mdash; {@code GET /cards}. Provenance:
     * [SRC: COCRDLIC | CARDDAT] (transaction {@code CCLI}).
     *
     * <p>Returns an empty {@link CardListResponse} with {@code 200}: an empty {@code items}
     * list and zeroed pagination counters. No database access and no list service &mdash; a
     * typed placeholder is the correct skeleton outcome. Note the counter types:
     * {@code page}/{@code size}/{@code totalPages} are {@code Integer} and {@code totalItems}
     * is {@code Long} ({@code 0L}) per the contract's {@code int64} shape.</p>
     *
     * @param accountId     optional owning-account filter (unused in the stub)
     * @param page          zero-based page index (unused in the stub)
     * @param size          page size (unused in the stub)
     * @param xCorrelationID the optional correlation-ID header (handled by the filter; unused here)
     * @return {@code 200} with an empty {@link CardListResponse}
     */
    @Override
    public ResponseEntity<CardListResponse> listCards(String accountId, Integer page, Integer size, String xCorrelationID) {
        CardListResponse body = new CardListResponse()
                .items(Collections.emptyList())
                .page(0)
                .size(0)
                .totalItems(0L)
                .totalPages(0);
        return ResponseEntity.ok(body);
    }

    /**
     * {@code [DEFERRED]} typed stub &mdash; {@code PUT /cards/{cardNumber}}. Provenance:
     * [SRC: COCRDUPC | CARDDAT] (transaction {@code CCUP}).
     *
     * <p>Returns a typed placeholder {@link Card} with {@code 200} that satisfies the DTO's
     * required fields. The path {@code cardNumber} is echoed; the remaining fields carry
     * benign placeholder values. No persistence is performed. The active-status flag is set
     * to {@link Card.ActiveStatusEnum#Y} directly rather than copied from
     * {@code cardUpdateRequest} &mdash; {@code CardUpdateRequest} declares its own distinct
     * nested {@code ActiveStatusEnum}, so a direct assignment would not compile.</p>
     *
     * @param cardNumber        the natural 16-digit card number (path variable, echoed)
     * @param cardUpdateRequest the writable fields (unused in the stub)
     * @param xCorrelationID     the optional correlation-ID header (handled by the filter; unused here)
     * @return {@code 200} with a placeholder {@link Card}
     */
    @Override
    public ResponseEntity<Card> updateCard(String cardNumber, CardUpdateRequest cardUpdateRequest, String xCorrelationID) {
        Card placeholder = new Card()
                .cardNumber(cardNumber)
                .accountId("0")
                .embossedName("")
                .expiryDate("")
                .activeStatus(Card.ActiveStatusEnum.Y);
        return ResponseEntity.ok(placeholder);
    }

    /**
     * Signals the tracer's empty state ({@code 404}). Mapped to an RFC&nbsp;7807
     * {@code application/problem+json} body by the sibling {@code GlobalExceptionHandler}
     * advice. Intentionally NOT annotated with {@code @ResponseStatus} &mdash; the advice owns
     * both the status and the response body; {@code @ResponseStatus} would bypass the
     * problem+json body.
     */
    public static class NotFoundException extends RuntimeException {

        /**
         * @param message the human-readable detail (mirrors the legacy "Did not find" message)
         */
        public NotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Signals a malformed card number ({@code 400}). Mapped to an RFC&nbsp;7807
     * {@code application/problem+json} body by the sibling {@code GlobalExceptionHandler}
     * advice. Intentionally NOT annotated with {@code @ResponseStatus} &mdash; the advice owns
     * both the status and the response body.
     */
    public static class BadRequestException extends RuntimeException {

        /**
         * @param message the human-readable detail (mirrors the legacy {@code 2220-EDIT-CARD} message)
         */
        public BadRequestException(String message) {
            super(message);
        }
    }
}

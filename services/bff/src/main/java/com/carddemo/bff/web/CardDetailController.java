package com.carddemo.bff.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.carddemo.bff.aggregation.CardDetailAggregator;
import com.carddemo.bff.api.CardDetailApi;
import com.carddemo.bff.model.CardDetail;

/**
 * <strong>THE LIVE TRACER controller</strong> &mdash; the backend-for-frontend HTTP edge of
 * the single fully-wired vertical slice in the CardDemo walking skeleton
 * (Spring Boot 3.5 / Java 21).
 *
 * <p>This {@code @RestController} <strong>implements the build-time generated</strong>
 * OpenAPI interface {@link CardDetailApi} (OpenAPI tag {@code CardDetail}, operation
 * {@code getCardDetail}, {@code GET /api/cards/{cardNumber}}). The interface and its
 * response DTO {@link CardDetail} are produced by {@code openapi-generator-maven-plugin}
 * (generator {@code spring}, {@code interfaceOnly=true}, {@code useResponseEntity=true},
 * {@code useTags=true}, {@code useJakartaEe=true}) from the frozen single-source-of-truth
 * contract {@code contracts/bff.openapi.yaml} (vendored at
 * {@code src/main/resources/openapi/bff.openapi.yaml}) into
 * {@code target/generated-sources/openapi}. Those generated sources are NEVER hand-written,
 * duplicated, or edited here &mdash; this controller only {@code implements} / imports / uses
 * them, freezing the contract into the compiler via the {@code implements} relationship.</p>
 *
 * <p><strong>The one genuinely live file in this package.</strong> Every sibling controller in
 * {@code com.carddemo.bff.web} is a typed {@code [DEFERRED]} placeholder. This class is the
 * deliberate exception: it fronts a <em>real</em> downstream read. An authenticated request
 * flows UI &rarr; BFF {@code GET /api/cards/{cardNumber}} &rarr; {@link CardDetailAggregator}
 * &rarr; card-svc {@code GET /cards/{cardNumber}} &rarr; Oracle {@code FREEPDB1} (the single
 * Flyway-seeded row) and back, rendered by the React Card Detail screen with real loading /
 * empty / error states.</p>
 *
 * <p><strong>Thin HTTP layer only.</strong> This controller performs NO domain logic, NO
 * persistence (no JPA entity, no repository, no JDBC), and issues NO downstream HTTP call of
 * its own: the {@link CardDetailAggregator} owns the synchronous {@code RestClient} hop to
 * card-svc. The controller's sole responsibility is to translate the aggregator's
 * {@link java.util.Optional} result into the contract's HTTP status codes.</p>
 *
 * <p><strong>Path served verbatim.</strong> The HTTP method, the {@code /api/cards/{cardNumber}}
 * path, and every binding annotation ({@code @PathVariable}, {@code @RequestHeader}, and the
 * bean-validation {@code @Pattern}/{@code @Size} on {@code cardNumber}) live on the generated
 * {@link CardDetailApi} default method; this controller declares NO {@code @RequestMapping} /
 * {@code @GetMapping} of its own and does NOT strip the {@code /api} prefix (the nginx production
 * proxy and the Vite dev proxy forward {@code /api/**} unchanged).</p>
 *
 * <p><strong>Status mapping</strong> (mirrors the legacy {@code EVALUATE WS-RESP-CD} in
 * {@code COCRDSLC} paragraph {@code 9100-GETCARD-BYACCTCARD}):</p>
 * <ul>
 *   <li><strong>200</strong> &mdash; card-svc {@code DFHRESP(NORMAL)}: the aggregator returns a
 *       present {@link java.util.Optional} &rarr; {@code ResponseEntity.ok(cardDetail)} &rarr;
 *       UI <em>data</em> state.</li>
 *   <li><strong>404</strong> &mdash; card-svc {@code DFHRESP(NOTFND)} ("Did not find this card"):
 *       the aggregator returns {@link java.util.Optional#empty()} &rarr; {@link NotFoundException}
 *       is thrown &rarr; the sibling {@code GlobalExceptionHandler} renders an RFC&nbsp;7807
 *       {@code application/problem+json} body &rarr; UI <em>empty</em> state.</li>
 *   <li><strong>400</strong> &mdash; a malformed card number (mirrors {@code 2220-EDIT-CARD}:
 *       "Card number if supplied must be a 16 digit number"): {@link BadRequestException} is
 *       thrown by the defensive guard below. The generated interface is {@code @Validated} and
 *       already constrains the path variable with {@code @Pattern}/{@code @Size}, so this guard
 *       is a deterministic backstop that also keeps the outcome testable in isolation.</li>
 *   <li><strong>5xx</strong> &mdash; card-svc {@code WHEN OTHER} (a {@code 4xx}/{@code 5xx} other
 *       than 404, or a connection failure): the aggregator lets the exception propagate uncaught;
 *       it bubbles through this method to the {@code GlobalExceptionHandler} fallback &rarr; UI
 *       <em>error</em> state. Such errors are deliberately NOT caught or swallowed here.</li>
 * </ul>
 *
 * <p><strong>Health.</strong> The contract's {@code /actuator/health} operation lands in a
 * separate generated {@code HealthApi}; it is intentionally NOT implemented here &mdash; Spring
 * Boot Actuator serves {@code /actuator/health} at runtime, and implementing it would create a
 * duplicate request-mapping conflict that fails startup.</p>
 *
 * <p>Provenance: {@code [SRC: COCRDSLC | CARDDAT]} &mdash; the legacy Credit Card View transaction
 * {@code CCDL} runs program {@code COCRDSLC} (registered in {@code app/csd/CARDDEMO.CSD}), reading
 * the {@code CARDDAT} VSAM KSDS keyed on the 16-character card number. Rendered fields derive from
 * the Card Detail BMS map [{@code app/bms/COCRDSL.bms}: {@code ACCTSID}, {@code CARDSID},
 * {@code CRDNAME}, {@code CRDSTCD}, {@code EXPMON}/{@code EXPYEAR}].</p>
 *
 * @see CardDetailApi the build-time generated OpenAPI server interface (never hand-edited)
 * @see CardDetailAggregator the LIVE aggregation collaborator that performs the card-svc read
 * @see CardDetail the generated contract response DTO (never hand-edited)
 */
@RestController
public class CardDetailController implements CardDetailApi {

    /**
     * The LIVE Card Detail aggregation collaborator. Injected via the constructor and held
     * {@code final} for immutability and thread safety. As the sole constructor, Spring
     * autowires it automatically without an explicit {@code @Autowired} annotation
     * (constructor injection is the enforced convention; no field or setter injection).
     */
    private final CardDetailAggregator cardDetailAggregator;

    /**
     * Creates the controller with its single required collaborator.
     *
     * @param cardDetailAggregator the aggregator that performs the real card-svc read and
     *                             returns the card wrapped in an {@link java.util.Optional};
     *                             Spring supplies the managed bean, so this is never {@code null}
     */
    public CardDetailController(CardDetailAggregator cardDetailAggregator) {
        this.cardDetailAggregator = cardDetailAggregator;
    }

    /**
     * <strong>THE LIVE TRACER</strong> &mdash; {@code GET /api/cards/{cardNumber}}.
     *
     * <p>Delegates to {@link CardDetailAggregator#getCardDetail(String)} and maps the
     * {@link java.util.Optional} result onto the contract's HTTP status codes exactly as the
     * legacy {@code EVALUATE WS-RESP-CD} dictates: a present value becomes {@code 200 OK} (UI
     * data state), {@link java.util.Optional#empty()} throws {@link NotFoundException} for
     * {@code 404} (UI empty state), and a malformed card number throws {@link BadRequestException}
     * for {@code 400}. Any downstream failure other than a card-svc {@code 404} is propagated
     * uncaught to the {@code GlobalExceptionHandler} fallback ({@code 5xx}, UI error state).</p>
     *
     * <p>The {@code xCorrelationID} header parameter is declared to match the generated
     * {@link CardDetailApi} signature but is intentionally unused here: {@code config.CorrelationIdFilter}
     * has already placed the correlation id into the SLF4J MDC, and the {@code RestClient}
     * interceptor configured in {@code config.OpenApiConfig} forwards it to card-svc. This layer
     * neither reads nor sets it.</p>
     *
     * @param cardNumber     the natural 16-digit card number (path variable; already constrained
     *                       by the generated interface's {@code @Pattern}/{@code @Size})
     * @param xCorrelationID the optional {@code X-Correlation-ID} header, bound as a free-form
     *                       {@code String} (not a strict {@code UUID}) so a non-canonical value is
     *                       not rejected before the filter runs; owned by the filter and the
     *                       RestClient interceptor and declared-and-ignored here
     * @return {@code 200 OK} carrying the found {@link CardDetail}; otherwise {@code 400}/{@code 404}
     *         are signalled via thrown exceptions and {@code 5xx} via propagated exceptions
     */
    @Override
    public ResponseEntity<CardDetail> getCardDetail(String cardNumber, String xCorrelationID) {
        if (cardNumber == null || !cardNumber.matches("^[0-9]{16}$")) {
            throw new BadRequestException("Card number if supplied must be a 16 digit number");
        }
        return cardDetailAggregator.getCardDetail(cardNumber)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NotFoundException("Did not find this card"));
    }

    /**
     * Signals the tracer's empty state ({@code 404}) &mdash; the requested card was not found
     * (legacy {@code DFHRESP(NOTFND)}, "Did not find this card"). Mapped to an RFC&nbsp;7807
     * {@code application/problem+json} body by the sibling {@code GlobalExceptionHandler} advice.
     * Intentionally NOT annotated with {@code @ResponseStatus} &mdash; the advice owns both the
     * status and the response body; {@code @ResponseStatus} would bypass the problem+json body.
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
     * Signals a malformed request ({@code 400}) &mdash; e.g. a card number that is not exactly
     * 16 digits (mirrors the legacy {@code 2220-EDIT-CARD} edit). Mapped to an RFC&nbsp;7807
     * {@code application/problem+json} body by the sibling {@code GlobalExceptionHandler} advice.
     * Intentionally NOT annotated with {@code @ResponseStatus} &mdash; the advice owns both the
     * status and the response body.
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

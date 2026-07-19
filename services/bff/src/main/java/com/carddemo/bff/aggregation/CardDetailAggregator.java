package com.carddemo.bff.aggregation;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.carddemo.bff.model.CardDetail;

/**
 * LIVE tracer aggregator &mdash; the BFF half of the single fully-wired vertical slice.
 *
 * <p><strong>The one genuinely live file in this package.</strong> Every sibling
 * aggregator in {@code com.carddemo.bff.aggregation} is a typed {@code [DEFERRED]}
 * stub that returns an empty, well-formed placeholder. This class is the deliberate
 * exception: it performs a <em>real</em> synchronous HTTP {@code GET} to
 * {@code card-svc} at {@code GET /cards/{cardNumber}} via the injected
 * {@link RestClient} bean ({@code cardServiceRestClient} from {@code config/OpenApiConfig}),
 * whose {@code ClientHttpRequestInterceptor} propagates the {@code X-Correlation-ID}
 * header from the SLF4J MDC automatically. The seeded card row flows
 * UI &rarr; BFF &rarr; card-svc &rarr; Oracle {@code FREEPDB1} and back, rendered by the
 * React Card Detail screen with real loading / empty / error states.</p>
 *
 * <p><strong>Aggregation only.</strong> The BFF owns no domain logic and no
 * persistence. There is no JPA entity, no repository, and no JDBC here; the sole
 * downstream access is the HTTP hop through the injected {@code RestClient}. The
 * downstream path is {@code /cards/{cardNumber}} at card-svc's <em>root</em> &mdash;
 * the {@code /api} prefix belongs only to the BFF's own inbound contract, never to
 * card-svc.</p>
 *
 * <p><strong>Contract is the single source of truth (SSoT).</strong> The response
 * type {@link com.carddemo.bff.model.CardDetail} is generated at build time from the
 * frozen OpenAPI 3.1 contract ({@code contracts/bff.openapi.yaml}) and is never
 * hand-edited. Its five fields ({@code cardNumber}, {@code accountId},
 * {@code embossedName}, {@code expiryDate}, {@code activeStatus}) are field-identical
 * to card-svc's {@code Card} schema, so card-svc's JSON response body deserializes
 * directly into {@code CardDetail} via Spring Boot's autoconfigured Jackson message
 * converters &mdash; no intermediate or local record is required.</p>
 *
 * <p><strong>Error &rarr; UI-state mapping</strong> (mirrors the legacy
 * {@code EVALUATE WS-RESP-CD} in {@code 9100-GETCARD-BYACCTCARD}):</p>
 * <ul>
 *   <li>card-svc {@code 200} ({@code DFHRESP(NORMAL)}) &rarr; the populated card is
 *       returned &rarr; UI <em>data</em> state.</li>
 *   <li>card-svc {@code 404} (legacy {@code DFHRESP(NOTFND)} &mdash; "Did not find this
 *       card") &rarr; {@link Optional#empty()} &rarr; UI <em>empty</em> state.</li>
 *   <li>card-svc {@code 400} / {@code 5xx} / connection failure (legacy
 *       {@code WHEN OTHER} file-error path) &rarr; the exception is <em>propagated</em>
 *       untouched to the web layer &rarr; UI <em>error</em> state.</li>
 * </ul>
 *
 * <p>Provenance: {@code [SRC: COCRDSLC | CARDDAT]} &mdash; the legacy Credit Card View
 * transaction {@code CCDL} runs program {@code COCRDSLC}, reading the {@code CARDDAT}
 * VSAM KSDS keyed on the 16-character card number. Field shape derives from
 * {@code CARD-RECORD} [CVACT02Y.cpy] and the Card Detail BMS map [COCRDSL.bms].</p>
 *
 * @see RestClient the synchronous HTTP client bean supplied by {@code config/OpenApiConfig}
 * @see com.carddemo.bff.model.CardDetail the build-time generated contract DTO (never hand-edited)
 */
@Service
public class CardDetailAggregator {

    /**
     * Synchronous HTTP client for the LIVE Card Detail tracer, pre-configured with
     * card-svc's base URL and the correlation-ID forwarding interceptor by
     * {@code config/OpenApiConfig}. Injected by constructor and held {@code final}
     * for immutability and thread safety. The constructor parameter is named to match
     * the {@code cardServiceRestClient} bean name, keeping injection unambiguous even
     * if additional {@code RestClient} beans are introduced later.
     */
    private final RestClient cardServiceRestClient;

    /**
     * Constructor injection of the card-svc {@link RestClient}. As the sole
     * constructor, Spring autowires it automatically without an explicit
     * {@code @Autowired} annotation (constructor injection is the enforced
     * convention; no field or setter injection).
     *
     * @param cardServiceRestClient the synchronous client bean for card-svc; Spring
     *                              supplies the managed {@code cardServiceRestClient}
     *                              bean, so this is never {@code null}
     */
    public CardDetailAggregator(RestClient cardServiceRestClient) {
        this.cardServiceRestClient = cardServiceRestClient;
    }

    /**
     * Retrieve a single card by its natural 16-digit card number from card-svc.
     *
     * <p>Issues a real synchronous {@code GET /cards/{cardNumber}} to card-svc (served
     * at card-svc's root; no {@code /api} prefix), expanding {@code cardNumber} through
     * the {@link RestClient} URI template rather than string concatenation. The
     * correlation id is <em>not</em> set here: the client interceptor configured in
     * {@code config/OpenApiConfig} forwards the MDC {@code correlationId} onto the
     * outbound request automatically.</p>
     *
     * <p>The aggregation layer stays HTTP-agnostic: this method returns a domain-shaped
     * {@link Optional} rather than a {@code ResponseEntity}. The sibling web controller
     * (which implements the generated {@code CardDetailApi}) delegates to this method and
     * maps the result &mdash; {@code map(ResponseEntity::ok)} for a present card and a
     * 404 for {@link Optional#empty()}.</p>
     *
     * @param cardNumber the 16-digit natural key of the card to fetch
     * @return the card wrapped in an {@link Optional} when card-svc returns {@code 200};
     *         {@link Optional#empty()} when card-svc returns {@code 404} (driving the UI
     *         empty state). Any other failure ({@code 400}, {@code 5xx}, or a connection
     *         error such as {@code ResourceAccessException}) is propagated unchanged so
     *         the web layer surfaces the UI error state.
     * @throws HttpClientErrorException for non-404 {@code 4xx} responses (e.g. {@code 400})
     * @throws org.springframework.web.client.HttpServerErrorException for {@code 5xx} responses
     * @throws org.springframework.web.client.ResourceAccessException when card-svc is
     *         unreachable or the request times out
     */
    public Optional<CardDetail> getCardDetail(String cardNumber) {
        try {
            CardDetail card = cardServiceRestClient.get()
                    .uri("/cards/{cardNumber}", cardNumber)
                    .retrieve()
                    .body(CardDetail.class);
            return Optional.ofNullable(card);
        } catch (HttpClientErrorException.NotFound ex) {
            // Legacy COCRDSLC NOTFND -> "Did not find this card" -> UI empty state.
            // Only 404 is mapped to empty; every other failure propagates untouched.
            return Optional.empty();
        }
    }
}

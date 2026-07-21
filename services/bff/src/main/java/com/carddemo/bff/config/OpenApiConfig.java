package com.carddemo.bff.config;

import java.time.Duration;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * HTTP-client configuration for the bff (CardDemo walking skeleton).
 *
 * <p><strong>BFF-specific divergence.</strong> In the domain services (card-svc, auth-svc)
 * {@code OpenApiConfig} is an empty {@link Configuration} marker. Here it additionally
 * declares the synchronous {@link RestClient} beans the BFF uses to call its downstream
 * services: {@code cardServiceRestClient} for the one LIVE Card Detail tracer
 * (UI -&gt; BFF -&gt; card-svc -&gt; Oracle FREEPDB1 seeded row), and {@code authServiceRestClient}
 * for the real sign-on hop (UI -&gt; BFF -&gt; auth-svc), so auth-svc is the single authority that
 * issues the bearer token (AAP §0.1.3 / §0.4). The BFF performs aggregation only: no domain logic
 * and no persistence; every other aggregator remains a typed {@code [DEFERRED]} stub.</p>
 *
 * <p><strong>Contract is the single source of truth (SSoT).</strong> The frozen OpenAPI 3.1
 * contracts under {@code /contracts} drive build-time generation of the API interfaces
 * ({@code com.carddemo.bff.api.*}) and models ({@code com.carddemo.bff.model.*}); those
 * generated sources are never hand-edited. No springdoc/swagger beans are declared here and
 * none are on the classpath.</p>
 *
 * <p><strong>Real outbound correlation-ID and token hops.</strong> Two request interceptors are
 * attached to the downstream client, realizing the BFF -&gt; card-svc leg of the two real hops the
 * AAP designates (AAP 0.4 / 0.8), even though auth is a permissive stub:</p>
 * <ul>
 *   <li><strong>Correlation-ID:</strong> forwards the MDC {@code correlationId} (established inbound
 *       by {@code CorrelationIdFilter}) onto every downstream card-svc request.</li>
 *   <li><strong>Authorization (bearer token):</strong> forwards the inbound {@code Authorization}
 *       header (read from the current request via {@code RequestContextHolder}) onto the downstream
 *       request, so the token the UI carries reaches card-svc and any future card-svc
 *       authorization/RBAC seam is wired end-to-end. This is the "carried on subsequent requests"
 *       token seam the AAP calls real; it is forwarded even though card-svc currently accepts public
 *       requests in the walking skeleton.</li>
 * </ul>
 *
 * <p><strong>Bounded downstream failure.</strong> The client is built with an explicit
 * {@link ClientHttpRequestFactory} carrying a connect timeout and a read timeout, so a slow or
 * hanging card-svc yields a bounded failure (surfaced by the web layer as an
 * {@code application/problem+json} error with the correlation id) rather than tying up a Tomcat
 * worker thread indefinitely. Retries and circuit breakers are intentionally out of scope for the
 * skeleton; a bounded timeout is the minimum required resilience control. Both timeouts are
 * overridable via configuration for operational tuning.</p>
 *
 * <p>Provenance: [SRC: CARDDEMO.CSD (topology) | COCRDSLC | CARDDAT (downstream card-svc target)].</p>
 */
@Configuration
public class OpenApiConfig {

    /** MDC key shared with {@code CorrelationIdFilter} and the {@code %X{correlationId}} log pattern. */
    private static final String MDC_KEY = "correlationId";

    /** card-svc base URL; bound to application.yml {@code carddemo.services.card-svc-url}. */
    @Value("${carddemo.services.card-svc-url:http://card-svc:8080}")
    private String cardServiceBaseUrl;

    /** auth-svc base URL; bound to application.yml {@code carddemo.services.auth-svc-url}. */
    @Value("${carddemo.services.auth-svc-url:http://auth-svc:8080}")
    private String authServiceBaseUrl;

    /** Correlation-ID header name; bound to application.yml {@code carddemo.correlation.header}. */
    @Value("${carddemo.correlation.header:X-Correlation-ID}")
    private String correlationHeader;

    /**
     * Connect timeout (milliseconds) for the downstream card-svc client; bound to
     * {@code carddemo.services.card-svc-connect-timeout-ms} (default 2000&nbsp;ms). Bounds how long
     * the BFF waits to establish a TCP connection to card-svc before failing fast.
     */
    @Value("${carddemo.services.card-svc-connect-timeout-ms:2000}")
    private long connectTimeoutMs;

    /**
     * Read timeout (milliseconds) for the downstream card-svc client; bound to
     * {@code carddemo.services.card-svc-read-timeout-ms} (default 5000&nbsp;ms). Bounds how long the
     * BFF waits for card-svc to respond once connected, so a slow or hanging card-svc yields a
     * bounded error instead of an indefinite wait that would exhaust Tomcat worker threads.
     */
    @Value("${carddemo.services.card-svc-read-timeout-ms:5000}")
    private long readTimeoutMs;

    /**
     * Connect timeout (milliseconds) for the downstream auth-svc client; bound to
     * {@code carddemo.services.auth-svc-connect-timeout-ms} (default 2000&nbsp;ms). Bounds how long
     * the BFF waits to establish a TCP connection to auth-svc before failing fast, mirroring the
     * card-svc client so the sign-on hop is subject to the same bounded-failure discipline.
     */
    @Value("${carddemo.services.auth-svc-connect-timeout-ms:2000}")
    private long authConnectTimeoutMs;

    /**
     * Read timeout (milliseconds) for the downstream auth-svc client; bound to
     * {@code carddemo.services.auth-svc-read-timeout-ms} (default 5000&nbsp;ms). Bounds how long the
     * BFF waits for auth-svc to respond once connected, so a slow or hanging auth-svc yields a
     * bounded error (surfaced as an {@code application/problem+json} with the correlation id)
     * instead of an indefinite wait that would exhaust Tomcat worker threads.
     */
    @Value("${carddemo.services.auth-svc-read-timeout-ms:5000}")
    private long authReadTimeoutMs;

    /**
     * Synchronous RestClient for the LIVE Card Detail tracer. Injected by type into the
     * aggregation layer, which calls card-svc {@code GET /cards/{cardNumber}} (served at
     * card-svc's root; no {@code /api} prefix). Created at context load but makes no network
     * call until invoked, so the context-load smoke test stays green without a running card-svc.
     *
     * <p>Configured with (a) an explicit {@link ClientHttpRequestFactory} carrying connect and read
     * timeouts so a slow/hanging card-svc fails within a bounded time; (b) the correlation-ID
     * forwarding interceptor; (c) the Authorization (bearer token) forwarding interceptor (the two
     * interceptors are independent &mdash; they copy distinct headers &mdash; so their relative order
     * is immaterial); and (d) a strict JSON message converter (QA finding API-05) that replaces the
     * default lenient one so a card-svc {@code 2xx} body whose scalar shapes violate the frozen
     * contract fails deserialization instead of being silently coerced.</p>
     *
     * <p><strong>Strict downstream deserialization (API-05).</strong> The default RestClient Jackson
     * converter is lenient: it would coerce a JSON number sent for a {@code String} field (e.g.
     * {@code "cardNumber": 123}) into its string form and accept it, so a contract-invalid downstream
     * body surfaced to the UI as a {@code 200}. This client instead uses
     * {@link #strictDownstreamJacksonConverter()}, whose {@link ObjectMapper} disables
     * {@code ALLOW_COERCION_OF_SCALARS} (rejecting string&nbsp;-&gt;&nbsp;boolean/integer/float) and
     * applies {@link JacksonCoercionConfig#applyStrictTextualScalarCoercion(ObjectMapper)} (rejecting
     * number/boolean&nbsp;-&gt;&nbsp;String) &mdash; the same single-source-of-truth policy the bff
     * enforces on its inbound bodies. A body that cannot be deserialized under this policy raises a
     * {@code RestClientException} during extraction, which {@code CardDetailAggregator} translates to a
     * {@code DownstreamResponseException} mapped to {@code 502 Bad Gateway}. Structural violations that
     * still deserialize (e.g. a missing required field, leaving it {@code null}) are caught by the
     * complementary bean-validation pass in {@code CardDetailAggregator}, which maps them to the same
     * {@code 502}. Valid numeric widening (integer for a {@code double}) remains accepted.</p>
     *
     * @return a synchronous {@link RestClient} pre-configured with the card-svc base URL, bounded
     *         connect/read timeouts, the correlation-ID + Authorization forwarding interceptors, and
     *         the strict downstream JSON converter
     */
    @Bean
    RestClient cardServiceRestClient() {
        return RestClient.builder()
                .baseUrl(cardServiceBaseUrl)
                .requestFactory(cardServiceRequestFactory())
                .requestInterceptor(correlationIdForwardingInterceptor())
                .requestInterceptor(authorizationForwardingInterceptor())
                .messageConverters(converters -> {
                    // Replace the default lenient Jackson converter with the strict one so downstream
                    // response bodies are held to the frozen contract's scalar types (API-05). Removing
                    // the default first keeps exactly one JSON POJO converter, avoiding ambiguity over
                    // which converter reads the card-svc application/json body.
                    converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                    converters.add(0, strictDownstreamJacksonConverter());
                })
                .build();
    }

    /**
     * Synchronous RestClient for the sign-on hop. Injected by type into {@code AuthAggregator},
     * which forwards the UI's Sign-On {@code POST /auth/login} to auth-svc so that <em>auth-svc</em>
     * &mdash; not the BFF &mdash; is the single authority that issues the bearer token. This realizes
     * the auth seam the AAP designates real: AAP §0.1.3 ("an {@code auth-svc} permissive login stub
     * that issues a real token") and §0.4 ("the UI Sign-On posts through the BFF to {@code auth-svc},
     * which returns a token"). Auth remains a <em>permissive</em> stub (auth-svc performs no credential
     * validation, hashing, RBAC, or session management), but the token hop is genuinely wired, exactly
     * like the correlation-ID hop. Created at context load but makes no network call until invoked, so
     * the context-load smoke test stays green without a running auth-svc.
     *
     * <p>Configured with (a) an explicit {@link ClientHttpRequestFactory} carrying connect and read
     * timeouts so a slow/hanging auth-svc fails within a bounded time; and (b) the correlation-ID
     * forwarding interceptor, so the sign-on request carries the same {@code X-Correlation-ID} through
     * UI &rarr; BFF &rarr; auth-svc and is logged via MDC in every tier. The Authorization-forwarding
     * interceptor is deliberately <em>not</em> attached here: {@code POST /auth/login} is the
     * unauthenticated entry point that <em>mints</em> the token, so there is no inbound bearer token to
     * forward on this leg (unlike the card-svc client, whose calls carry the token the UI already
     * holds).</p>
     *
     * @return a synchronous {@link RestClient} pre-configured with the auth-svc base URL, bounded
     *         connect/read timeouts, and the correlation-ID forwarding interceptor
     */
    @Bean
    RestClient authServiceRestClient() {
        return RestClient.builder()
                .baseUrl(authServiceBaseUrl)
                .requestFactory(authServiceRequestFactory())
                .requestInterceptor(correlationIdForwardingInterceptor())
                .build();
    }

    /**
     * Builds the downstream {@link ClientHttpRequestFactory} with explicit connect and read
     * timeouts (finding F2: bounded downstream failure). {@link ClientHttpRequestFactoryBuilder#detect()}
     * auto-selects the best factory available on the classpath &mdash; here the JDK
     * {@code HttpClient}-backed factory (no Apache HttpComponents / Jetty / Reactor dependency is
     * present) &mdash; preserving the existing client implementation while adding the timeouts. A
     * read timeout is the key control: without it the JDK client waits indefinitely for a response,
     * so a hanging card-svc would tie up a Tomcat worker thread until the socket eventually closes.
     *
     * @return a {@link ClientHttpRequestFactory} whose connect/read timeouts are bounded by
     *         {@code connectTimeoutMs} / {@code readTimeoutMs}
     */
    private ClientHttpRequestFactory cardServiceRequestFactory() {
        return ClientHttpRequestFactoryBuilder.detect()
                .build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                        .withReadTimeout(Duration.ofMillis(readTimeoutMs)));
    }

    /**
     * Builds the strict JSON message converter used to deserialize the downstream card-svc response
     * body (QA finding API-05), replacing the RestClient's default lenient Jackson converter.
     *
     * <p>The backing {@link ObjectMapper} is a dedicated, self-contained instance &mdash; deliberately
     * <em>not</em> the shared MVC {@code ObjectMapper} bean &mdash; so tightening it for outbound
     * downstream reads never perturbs the bff's inbound request/response handling. It applies both
     * halves of the "no cross-type scalar coercion" policy:</p>
     * <ul>
     *   <li>{@link MapperFeature#ALLOW_COERCION_OF_SCALARS} is disabled at build time, rejecting
     *       coercion of a JSON <em>string</em> into a non-textual scalar
     *       (string&nbsp;-&gt;&nbsp;boolean/integer/float); and</li>
     *   <li>{@link JacksonCoercionConfig#applyStrictTextualScalarCoercion(ObjectMapper)} rejects
     *       coercion of a non-textual JSON scalar into a {@code String}
     *       (number/boolean&nbsp;-&gt;&nbsp;String) &mdash; reusing the bff's single source of truth
     *       for that rule so inbound and outbound strictness can never drift apart.</li>
     * </ul>
     *
     * <p>All five generated {@code CardDetail} fields are textual/enum, so this policy makes any
     * numeric-or-boolean-for-string mismatch in a card-svc {@code 2xx} body fail extraction (raising a
     * {@code RestClientException} the aggregator maps to {@code 502}). The mapper registers no extra
     * modules because the contract DTO carries none (all {@code String}/enum fields with the enum's own
     * {@code @JsonCreator}); valid numeric widening is preserved because only the {@code Textual}
     * logical type is constrained.</p>
     *
     * @return a {@link MappingJackson2HttpMessageConverter} backed by the strict, coercion-disabled
     *         {@link ObjectMapper}
     */
    private MappingJackson2HttpMessageConverter strictDownstreamJacksonConverter() {
        ObjectMapper strictMapper = JsonMapper.builder()
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
        JacksonCoercionConfig.applyStrictTextualScalarCoercion(strictMapper);
        return new MappingJackson2HttpMessageConverter(strictMapper);
    }

    /**
     * Builds the downstream auth-svc {@link ClientHttpRequestFactory} with explicit connect and read
     * timeouts, mirroring {@link #cardServiceRequestFactory()} so the sign-on hop is bounded by the
     * same failure discipline. {@link ClientHttpRequestFactoryBuilder#detect()} auto-selects the JDK
     * {@code HttpClient}-backed factory available on the classpath. The read timeout is the key
     * control: without it a hanging auth-svc would tie up a Tomcat worker thread indefinitely.
     *
     * @return a {@link ClientHttpRequestFactory} whose connect/read timeouts are bounded by
     *         {@code authConnectTimeoutMs} / {@code authReadTimeoutMs}
     */
    private ClientHttpRequestFactory authServiceRequestFactory() {
        return ClientHttpRequestFactoryBuilder.detect()
                .build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(Duration.ofMillis(authConnectTimeoutMs))
                        .withReadTimeout(Duration.ofMillis(authReadTimeoutMs)));
    }

    /**
     * Interceptor that forwards the inbound correlation id (from MDC) onto downstream
     * card-svc requests, realizing the real BFF -&gt; card-svc correlation hop. The MDC value
     * is read per request at call time, so the id established by {@code CorrelationIdFilter}
     * on the current request thread is always the one forwarded. The header is set only when
     * a correlation id is actually present (non-null and non-blank).
     *
     * @return a {@link ClientHttpRequestInterceptor} that copies the MDC {@code correlationId}
     *         onto the outbound request header named by {@code carddemo.correlation.header}
     */
    private ClientHttpRequestInterceptor correlationIdForwardingInterceptor() {
        return (request, body, execution) -> {
            String correlationId = MDC.get(MDC_KEY);
            if (correlationId != null && !correlationId.isBlank()) {
                request.getHeaders().set(correlationHeader, correlationId);
            }
            return execution.execute(request, body);
        };
    }

    /**
     * Interceptor that forwards the inbound {@code Authorization} header onto downstream card-svc
     * requests, realizing the real BFF -&gt; card-svc token hop (finding F1). The bearer token the
     * UI carries (issued by the permissive auth stub) is read from the CURRENT servlet request via
     * {@link RequestContextHolder} &mdash; which Spring MVC binds to the same Tomcat worker thread
     * that the synchronous {@link RestClient} call runs on &mdash; and copied verbatim onto the
     * outbound request. The header is forwarded only when an inbound {@code Authorization} value is
     * actually present (non-null and non-blank); when absent (for example, no request context, as
     * during a context-load test) nothing is added and the call proceeds unauthenticated. This
     * wires the token seam end-to-end even though card-svc does not yet validate it, so a future
     * card-svc authorization/RBAC control requires no BFF change.
     *
     * @return a {@link ClientHttpRequestInterceptor} that copies the inbound {@code Authorization}
     *         header onto the outbound downstream request when present
     */
    private ClientHttpRequestInterceptor authorizationForwardingInterceptor() {
        return (request, body, execution) -> {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servletAttributes) {
                String authorization = servletAttributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
                if (authorization != null && !authorization.isBlank()) {
                    request.getHeaders().set(HttpHeaders.AUTHORIZATION, authorization);
                }
            }
            return execution.execute(request, body);
        };
    }
}

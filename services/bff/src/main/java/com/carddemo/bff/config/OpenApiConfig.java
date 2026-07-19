package com.carddemo.bff.config;

import java.time.Duration;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * HTTP-client configuration for the bff (CardDemo walking skeleton).
 *
 * <p><strong>BFF-specific divergence.</strong> In the domain services (card-svc, auth-svc)
 * {@code OpenApiConfig} is an empty {@link Configuration} marker. Here it additionally
 * declares the synchronous {@link RestClient} the BFF uses to call {@code card-svc} for the
 * one LIVE Card Detail tracer (UI -&gt; BFF -&gt; card-svc -&gt; Oracle FREEPDB1 seeded row).
 * The BFF performs aggregation only: no domain logic and no persistence.</p>
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
     * Synchronous RestClient for the LIVE Card Detail tracer. Injected by type into the
     * aggregation layer, which calls card-svc {@code GET /cards/{cardNumber}} (served at
     * card-svc's root; no {@code /api} prefix). Created at context load but makes no network
     * call until invoked, so the context-load smoke test stays green without a running card-svc.
     *
     * <p>Configured with (a) an explicit {@link ClientHttpRequestFactory} carrying connect and read
     * timeouts so a slow/hanging card-svc fails within a bounded time; (b) the correlation-ID
     * forwarding interceptor; and (c) the Authorization (bearer token) forwarding interceptor. The
     * two interceptors are independent (they copy distinct headers), so their relative order is
     * immaterial.</p>
     *
     * @return a synchronous {@link RestClient} pre-configured with the card-svc base URL, bounded
     *         connect/read timeouts, and the correlation-ID + Authorization forwarding interceptors
     */
    @Bean
    RestClient cardServiceRestClient() {
        return RestClient.builder()
                .baseUrl(cardServiceBaseUrl)
                .requestFactory(cardServiceRequestFactory())
                .requestInterceptor(correlationIdForwardingInterceptor())
                .requestInterceptor(authorizationForwardingInterceptor())
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

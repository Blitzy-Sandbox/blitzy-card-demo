package com.carddemo.bff.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

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
 * <p><strong>Real outbound correlation-ID hop.</strong> The attached interceptor forwards the
 * MDC {@code correlationId} (established inbound by {@code CorrelationIdFilter}) onto every
 * downstream card-svc request, realizing the BFF -&gt; card-svc leg of the real hop
 * (AAP 0.4 / 0.8) even though auth is a permissive stub.</p>
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
     * Synchronous RestClient for the LIVE Card Detail tracer. Injected by type into the
     * aggregation layer, which calls card-svc {@code GET /cards/{cardNumber}} (served at
     * card-svc's root; no {@code /api} prefix). Created at context load but makes no network
     * call until invoked, so the context-load smoke test stays green without a running card-svc.
     *
     * @return a synchronous {@link RestClient} pre-configured with the card-svc base URL and
     *         the correlation-ID forwarding interceptor
     */
    @Bean
    RestClient cardServiceRestClient() {
        return RestClient.builder()
                .baseUrl(cardServiceBaseUrl)
                .requestInterceptor(correlationIdForwardingInterceptor())
                .build();
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
}

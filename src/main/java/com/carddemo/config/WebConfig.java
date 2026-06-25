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
package com.carddemo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web-layer configuration for the headless, REST-only CardDemo service.
 *
 * <p>The legacy presentation tier (the 17 BMS mapsets and the pseudo-conversational
 * COMMAREA navigation) was replaced by stateless REST endpoints, so this class
 * renders nothing: it registers no view resolver, no view controller, and no
 * template engine. It contributes only the thin cross-cutting posture the REST
 * surface requires &mdash; cross-origin resource sharing (CORS) and JSON-first
 * content negotiation.</p>
 *
 * <p>It implements {@link WebMvcConfigurer}, the additive customization hook, so
 * that Spring Boot's MVC auto-configuration stays active and is merely extended
 * here rather than being switched off by a full manual MVC takeover.</p>
 *
 * <p>Request correlation is owned elsewhere: the
 * {@code com.carddemo.observability.CorrelationIdFilter} is a {@code @Component}
 * and is therefore auto-registered into the servlet filter chain by Spring Boot.
 * This class must not re-register it &mdash; doing so would run the filter twice
 * per request &mdash; so it only exposes the response header that filter emits,
 * allowing browser clients to read the generated correlation id.</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Path pattern covering every REST resource served by the controllers
     * (accounts, cards, transactions, billing, reports, users, menu, and auth).
     */
    private static final String API_PATH_PATTERN = "/api/**";

    /**
     * Response header carrying the per-request correlation id. The value mirrors
     * the header emitted by the auto-registered observability filter so that the
     * id generated server-side remains visible to cross-origin browser clients.
     */
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /** Seconds a CORS preflight (OPTIONS) response may be cached by the browser. */
    private static final long CORS_MAX_AGE_SECONDS = 3600L;

    /**
     * Origins permitted to call the API, supplied by the
     * {@code carddemo.web.cors.allowed-origins} property (comma-separated) and
     * defaulted to the conventional local front-end ports. Explicit origins are
     * mandatory because credentials are allowed; a bare {@code "*"} wildcard is
     * intentionally never combined with credentials.
     */
    private final String[] allowedOrigins;

    /**
     * Builds the web configuration with the resolved set of CORS origins.
     *
     * @param allowedOrigins the comma-separated origins resolved from
     *                       {@code carddemo.web.cors.allowed-origins}, defaulting
     *                       to {@code http://localhost:3000,http://localhost:8080}
     *                       for local development when the property is absent;
     *                       defensively copied so the stored value is immutable
     */
    public WebConfig(
            @Value("${carddemo.web.cors.allowed-origins:http://localhost:3000,http://localhost:8080}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins.clone();
    }

    /**
     * Registers the CORS policy for the REST surface.
     *
     * <p>The mapping permits the REST verbs exercised by the controllers
     * (GET for list/detail, POST for create/sign-in/pay/submit, PUT for update,
     * DELETE for user removal, and OPTIONS for preflight), reflects any requested
     * headers, exposes the correlation-id header, and allows credentials against
     * the explicitly configured origins. Spring Security's {@code http.cors(...)}
     * delegates to this MVC CORS configuration, making this the single source of
     * CORS truth; no separate {@code CorsConfigurationSource} bean is defined.</p>
     *
     * @param registry the registry to which the CORS mapping is added
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping(API_PATH_PATTERN)
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(CORRELATION_ID_HEADER)
                .allowCredentials(true)
                .maxAge(CORS_MAX_AGE_SECONDS);
    }

    /**
     * Configures JSON-first content negotiation for the headless API.
     *
     * <p>The {@code Accept} header is honoured, the request-parameter strategy is
     * disabled, and {@link MediaType#APPLICATION_JSON} is the default when a
     * client expresses no preference (or a wildcard). Path-extension negotiation
     * is already off by default in Spring Boot 3 and is left disabled here.
     * No additional message converters are registered: the auto-configured
     * Jackson converter handles JSON, including {@code BigDecimal} monetary
     * fields and {@code java.time} types.</p>
     *
     * @param configurer the content-negotiation configurer to customize
     */
    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.favorParameter(false)
                .ignoreAcceptHeader(false)
                .defaultContentType(MediaType.APPLICATION_JSON);
    }
}

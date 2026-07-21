package com.carddemo.bff.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import org.springframework.context.annotation.Configuration;

/**
 * Strict scalar-coercion policy for the bff's inbound JSON request bodies (QA finding API-04).
 *
 * <p><strong>Problem.</strong> With Jackson's default lenient coercion, a contract-invalid request
 * body was silently accepted instead of rejected with {@code 400 Bad Request}: a JSON number sent
 * for a {@code String} field was coerced to its string form, and a JSON string {@code "true"} sent
 * for a {@code boolean} field was coerced to {@code true}. The aggregation endpoint then echoed a
 * {@code 200}/{@code 201} for a body that violates the frozen OpenAPI 3.1 contract's declared scalar
 * types.</p>
 *
 * <p><strong>Fix.</strong> Two complementary controls, applied to the single autoconfigured
 * {@link ObjectMapper} that backs Spring MVC's request-body deserialization:</p>
 * <ul>
 *   <li>{@code spring.jackson.mapper.allow-coercion-of-scalars: false} (in {@code application.yml})
 *       rejects coercion of a JSON <em>string</em> into a non-textual scalar
 *       (string&nbsp;-&gt;&nbsp;boolean, string&nbsp;-&gt;&nbsp;integer, string&nbsp;-&gt;&nbsp;float).</li>
 *   <li>The {@link CoercionInputShape} rules below reject coercion of a non-textual JSON scalar
 *       (integer/float/boolean) into a {@code String} field
 *       (number&nbsp;-&gt;&nbsp;String, boolean&nbsp;-&gt;&nbsp;String) &mdash; the case the
 *       {@code allow-coercion-of-scalars} flag alone does <em>not</em> cover.</li>
 * </ul>
 *
 * <p>Together they make every cross-type scalar coercion a {@code 400}, while deliberately leaving
 * valid numeric widening intact (e.g. the integer {@code 2} supplied for a decimal/{@code double}
 * field remains accepted). Only the {@code Textual} logical type is constrained here; numeric and
 * boolean logical types keep their default (lenient) numeric handling so legitimate JSON number
 * shapes are not rejected.</p>
 *
 * <p>This governs the bff's <em>inbound</em> request bodies. The complementary strictness on the
 * bff's <em>outbound</em> downstream card-svc response (QA finding API-05) is configured separately
 * on the {@code cardServiceRestClient} in {@code OpenApiConfig}, which owns a private strict message
 * converter so a contract-invalid downstream body is surfaced as {@code 502 Bad Gateway}.</p>
 *
 * <p>The rules are applied once at context startup (constructor time), before the web server accepts
 * any request; the MVC Jackson message converter reads the coercion configuration per request, so a
 * startup-time mutation of the shared {@link ObjectMapper} is sufficient and thread-safe.</p>
 */
@Configuration
public class JacksonCoercionConfig {

    /**
     * @param objectMapper the autoconfigured Jackson {@link ObjectMapper} that backs Spring MVC
     *                     request-body deserialization; its {@code Textual} coercion rules are
     *                     tightened to reject number/boolean&nbsp;-&gt;&nbsp;String coercion.
     */
    public JacksonCoercionConfig(ObjectMapper objectMapper) {
        applyStrictTextualScalarCoercion(objectMapper);
    }

    /**
     * Applies the "no non-textual scalar may be coerced into a {@code String}" rules to the supplied
     * {@link ObjectMapper}, failing (rather than silently coercing)
     * number&nbsp;-&gt;&nbsp;String and boolean&nbsp;-&gt;&nbsp;String. This is the single source of
     * truth for that policy in the bff, reused by two collaborators so their behavior can never drift:
     * <ul>
     *   <li>the {@link #JacksonCoercionConfig(ObjectMapper) constructor} tightens the bff's
     *       <em>inbound</em> MVC request-body mapper (QA finding API-04); and</li>
     *   <li>{@code OpenApiConfig} tightens the dedicated <em>outbound</em> mapper backing the strict
     *       {@code cardServiceRestClient} converter (QA finding API-05), so a card-svc response whose
     *       scalar shapes violate the frozen contract surfaces as {@code 502 Bad Gateway}.</li>
     * </ul>
     *
     * <p>Only the {@code Textual} logical type is constrained; numeric and boolean logical types keep
     * their default handling so legitimate JSON numeric widening (e.g. integer&nbsp;{@code 2} supplied
     * for a {@code double} field) is preserved. The complementary control that rejects coercion of a
     * JSON <em>string</em> into a non-textual scalar (string&nbsp;-&gt;&nbsp;boolean/integer/float) is
     * {@code MapperFeature.ALLOW_COERCION_OF_SCALARS}, disabled inbound via
     * {@code spring.jackson.mapper.allow-coercion-of-scalars: false} and outbound at build time on the
     * {@code OpenApiConfig} mapper; it is intentionally not set here so this helper is a pure,
     * side-effect-scoped coercion-rule application usable against any mapper instance.</p>
     *
     * @param objectMapper the mapper whose {@code Textual} coercion rules are tightened; never
     *                     {@code null}. Returned for fluent call-site chaining.
     * @return the same {@code objectMapper} instance, after the rules are applied
     */
    static ObjectMapper applyStrictTextualScalarCoercion(ObjectMapper objectMapper) {
        objectMapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return objectMapper;
    }
}

package com.carddemo.card.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import org.springframework.context.annotation.Configuration;

/**
 * Strict scalar-coercion policy for inbound JSON request bodies (QA finding API-04).
 *
 * <p><strong>Problem.</strong> With Jackson's default lenient coercion, a contract-invalid request
 * body was silently accepted instead of rejected with {@code 400 Bad Request}: a JSON number sent
 * for a {@code String} field (e.g. {@code {"embossedName": 12345}}) was coerced to {@code "12345"},
 * and a JSON string {@code "true"} sent for a {@code boolean} field was coerced to {@code true}. The
 * typed stub then echoed a {@code 200}/{@code 201} for a body that violates the frozen OpenAPI 3.1
 * contract's declared scalar types.</p>
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
 * field remains accepted, because {@code 2} and {@code 2.0} denote the same JSON number). Only the
 * {@code Textual} logical type is constrained here; numeric and boolean logical types keep their
 * default (lenient) numeric handling so legitimate JSON number shapes are not rejected.</p>
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
        objectMapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }
}

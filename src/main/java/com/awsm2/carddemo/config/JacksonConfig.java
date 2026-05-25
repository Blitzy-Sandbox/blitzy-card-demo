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
package com.awsm2.carddemo.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;

/**
 * Application-wide Jackson hardening configuration.
 *
 * <p><b>QA CR-16:</b> Jackson's default scalar coercion can convert numeric
 * JSON values into Java {@link String} fields (for example
 * {@code "userId":123} becomes {@code "123"}). The COBOL/BMS source fields
 * are character fields and the REST contract requires JSON strings, so
 * non-string scalar values must be rejected before DTO validation. This
 * customizer disables coercion from integer, floating-point, and boolean
 * inputs into textual fields; Spring MVC then surfaces the failure through
 * {@code HttpMessageNotReadableException}, which
 * {@code GlobalExceptionHandler} maps to a TYPE_MISMATCH envelope.</p>
 *
 * <p><b>QA Final-CP6 Finding C1 (strict date deserialization):</b> Jackson's
 * default JSR-310 deserializers (which back {@code java.time.LocalDate},
 * {@code java.time.LocalDateTime}, and {@code java.time.LocalTime} record
 * components on DTOs such as {@code AccountUpdateDto}, {@code TransactionAddDto},
 * and {@code ReportRequestDto}) use the JDK's default
 * {@link ResolverStyle#SMART SMART resolver style}, which silently coerces
 * out-of-range day values within a month (e.g. {@code "2024-02-30"} resolves
 * to {@code 2024-02-29}; {@code "2023-02-30"} resolves to {@code 2023-02-28}).
 * Per AAP &sect;0.6.1 (COBOL/CSUTLDTC fidelity) and AAP &sect;0.7.1
 * ("preserve existing functionality"), the Java target MUST reject impossible
 * calendar dates exactly as the COBOL date validator does &mdash; never
 * coerce them.</p>
 *
 * <p>This configuration replaces the default JSR-310 deserializers with
 * {@link ResolverStyle#STRICT STRICT} formatters that throw
 * {@link java.time.format.DateTimeParseException} (translated to HTTP 400 by
 * {@code GlobalExceptionHandler#handleHttpMessageNotReadable}) whenever an
 * impossible day-of-month is supplied. The fix is global (one configuration
 * change) and applies uniformly to every DTO field across the application
 * &mdash; not just the three DTOs flagged in CP6 &mdash; preserving the
 * parallel-run COBOL parity contract end-to-end.</p>
 */
@Configuration
public class JacksonConfig {

    /**
     * The strict ISO-8601 date formatter used for every {@code LocalDate}
     * deserialization. Uses {@code uuuu} (proleptic ISO year, four digits)
     * plus {@code MM} (month-of-year, two digits) plus {@code dd}
     * (day-of-month, two digits) with literal hyphen separators &mdash;
     * matching the canonical {@code yyyy-MM-dd} contract declared on every
     * DTO field. STRICT resolver style rejects out-of-range day values
     * (e.g. February 30) instead of silently coercing them per the SMART
     * default.
     *
     * <p>The {@code uuuu} pattern (rather than {@code yyyy}) is required by
     * the STRICT resolver: {@code yyyy} is "year-of-era" which mandates a
     * separate {@code G} era marker under STRICT resolution; {@code uuuu}
     * is "year" (proleptic ISO) which works standalone.</p>
     */
    private static final DateTimeFormatter STRICT_LOCAL_DATE_FORMATTER =
            new DateTimeFormatterBuilder()
                    .appendPattern("uuuu-MM-dd")
                    .toFormatter()
                    .withResolverStyle(ResolverStyle.STRICT);

    /**
     * The strict ISO-8601 local-date-time formatter used for every
     * {@code LocalDateTime} deserialization. Mirrors
     * {@link #STRICT_LOCAL_DATE_FORMATTER} for the date portion and uses
     * {@code HH:mm:ss} for the time portion (24-hour clock, two-digit
     * hours/minutes/seconds, literal colon separators). A trailing
     * fractional-second suffix (1-9 digits) is accepted but optional.
     *
     * <p>Used by every DTO that declares an ISO-8601 timestamp component
     * such as {@code TransactionAddDto.originationTimestamp} and
     * {@code TransactionAddDto.processingTimestamp}.</p>
     */
    private static final DateTimeFormatter STRICT_LOCAL_DATE_TIME_FORMATTER =
            new DateTimeFormatterBuilder()
                    .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
                    .optionalStart()
                    .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 1, 9, true)
                    .optionalEnd()
                    .toFormatter()
                    .withResolverStyle(ResolverStyle.STRICT);

    /**
     * The strict local-time formatter used for every
     * {@code LocalTime} deserialization. Provided for completeness so
     * that any future DTO with a {@code LocalTime} component is also
     * subject to the strict-resolution contract.
     */
    private static final DateTimeFormatter STRICT_LOCAL_TIME_FORMATTER =
            new DateTimeFormatterBuilder()
                    .appendPattern("HH:mm:ss")
                    .optionalStart()
                    .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 1, 9, true)
                    .optionalEnd()
                    .toFormatter()
                    .withResolverStyle(ResolverStyle.STRICT);

    /**
     * Rejects non-string scalar values for Java {@link String} properties
     * and installs strict JSR-310 deserializers that fail on impossible
     * calendar dates.
     *
     * <p>The two concerns are merged into a single customizer because Spring
     * Boot's auto-configuration applies all
     * {@link Jackson2ObjectMapperBuilderCustomizer} beans in order; running
     * the strict-coercion configuration and the strict-temporal-deserializer
     * configuration as one bean guarantees they cannot be applied out of
     * order or to different ObjectMapper instances.</p>
     *
     * @return Jackson builder customizer applied to the MVC ObjectMapper and
     *         any injected ObjectMapper (including SecurityConfig's JSON
     *         envelope writer)
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictStringCoercionCustomizer() {
        return builder -> {
            // QA CR-16: reject non-string scalars for String properties.
            builder.postConfigurer(objectMapper -> objectMapper
                    .coercionConfigFor(LogicalType.Textual)
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));

            // QA Final-CP6 C1: install a JavaTimeModule whose deserializers
            // resolve every date and timestamp under ResolverStyle.STRICT,
            // matching COBOL CSUTLDTC calendar-validity semantics. The
            // module is registered via modulesToInstall() so that Spring
            // Boot's auto-configured JavaTimeModule (which uses the SMART
            // default) is replaced with the strict-resolution variants
            // for LocalDate / LocalDateTime / LocalTime.
            JavaTimeModule strictTimeModule = new JavaTimeModule();
            strictTimeModule.addDeserializer(java.time.LocalDate.class,
                    new LocalDateDeserializer(STRICT_LOCAL_DATE_FORMATTER));
            strictTimeModule.addDeserializer(java.time.LocalDateTime.class,
                    new LocalDateTimeDeserializer(STRICT_LOCAL_DATE_TIME_FORMATTER));
            strictTimeModule.addDeserializer(java.time.LocalTime.class,
                    new LocalTimeDeserializer(STRICT_LOCAL_TIME_FORMATTER));
            builder.modulesToInstall(strictTimeModule);
        };
    }
}

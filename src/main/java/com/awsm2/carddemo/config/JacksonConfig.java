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
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 */
@Configuration
public class JacksonConfig {

    /**
     * Rejects non-string scalar values for Java {@link String} properties.
     *
     * @return Jackson builder customizer applied to the MVC ObjectMapper and
     *         any injected ObjectMapper (including SecurityConfig's JSON
     *         envelope writer)
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictStringCoercionCustomizer() {
        return builder -> builder.postConfigurer(objectMapper -> objectMapper
                .coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
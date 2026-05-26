/*
 * Copyright 2025 Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.awsm2.carddemo.dto.serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

/**
 * Jackson deserializer for the {@code customerSsn} field that pairs
 * with {@link MaskedSsnSerializer} to support graceful round-trip of
 * a masked SSN payload.
 *
 * <h2>Motivation &mdash; QA Final-CP6 Finding M4 follow-up</h2>
 * <p>{@link MaskedSsnSerializer} renders the {@code Long customerSsn}
 * record component as a masked JSON string ({@code "***-**-XXXX"}).
 * Callers that read a {@code GET /api/accounts/{id}} response and
 * then deserialize the JSON body back into an
 * {@link com.awsm2.carddemo.dto.AccountViewDto} (for example, the
 * {@code EndToEndAccountWorkflowIT} integration suite that asserts on
 * the {@code toString()} masking behavior of the returned DTO) would
 * otherwise hit a {@code com.fasterxml.jackson.databind.exc.InvalidFormatException}
 * from Jackson's default {@code NumberDeserializers$LongDeserializer}
 * because the masked string is not a parseable {@link Long}.</p>
 *
 * <p>This deserializer accepts both shapes:</p>
 * <ul>
 *   <li><b>JSON number</b> (e.g., {@code 123456789}) &mdash; parsed as
 *       a normal {@link Long}. This path covers
 *       {@code AccountUpdateDto.customerSsn} on inbound
 *       {@code PUT /api/accounts/{id}} requests, where the operator
 *       supplies the unmasked 9-digit SSN.</li>
 *   <li><b>JSON string containing a numeric value</b> (e.g.,
 *       {@code "123456789"}) &mdash; parsed via
 *       {@link Long#parseLong(String)}. Some clients emit JSON-string
 *       wrapping for large integers; this branch tolerates that
 *       without forcing a contract change.</li>
 *   <li><b>JSON string in the masked form</b> (starts with
 *       {@code "***-**-"}) &mdash; resolved to {@code null}. The
 *       unmasked digits are <i>not</i> recoverable from the mask, so
 *       returning {@code null} is the only safe choice. Downstream
 *       callers that need the original SSN must read it from the
 *       primary persistence layer (the JPA {@code Customer} entity),
 *       not via a JSON round-trip.</li>
 *   <li><b>JSON null</b> &mdash; resolved to {@code null}.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * <p>This deserializer holds no mutable state and is safe for use by
 * the default {@code ObjectMapper} singleton produced by Spring Boot.</p>
 */
public final class MaskedSsnDeserializer extends StdDeserializer<Long> {

    private static final long serialVersionUID = 1L;

    /** The literal mask prefix produced by {@link MaskedSsnSerializer}. */
    private static final String MASK_PREFIX = "***-**-";

    /**
     * Public no-arg constructor required by Jackson's
     * {@code @JsonDeserialize(using = ...)} machinery so the framework
     * can instantiate this deserializer via reflection.
     */
    public MaskedSsnDeserializer() {
        super(Long.class);
    }

    /**
     * Reads the next JSON token and returns the SSN as a {@link Long},
     * or {@code null} if the token is JSON null or a masked-form string.
     *
     * @param p    the JSON parser positioned on the value token
     * @param ctxt the active deserialization context
     * @return the unmasked SSN as a {@link Long}, or {@code null}
     * @throws IOException if the underlying parser fails
     */
    @Override
    public Long deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token == JsonToken.VALUE_NUMBER_INT) {
            return p.getLongValue();
        }
        if (token == JsonToken.VALUE_STRING) {
            String raw = p.getValueAsString();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            // Masked form — no inverse is possible; return null.
            if (raw.startsWith(MASK_PREFIX)) {
                return null;
            }
            // Numeric form wrapped in a JSON string.
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException nfe) {
                // Fall through to Jackson's default error handling.
                return (Long) ctxt.handleWeirdStringValue(
                        Long.class, raw,
                        "expected an unsigned 9-digit SSN, the masked "
                                + "form ***-**-XXXX, or JSON null");
            }
        }
        // Any other token type — float, boolean, etc. — is invalid.
        return (Long) ctxt.handleUnexpectedToken(Long.class, p);
    }
}

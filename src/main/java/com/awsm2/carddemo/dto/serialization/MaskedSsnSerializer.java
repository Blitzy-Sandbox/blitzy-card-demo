/*
 * Copyright 2025 Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.awsm2.carddemo.dto.serialization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;

/**
 * Jackson serializer that emits a US Social Security Number (SSN) as a
 * masked string in the form {@code "***-**-XXXX"} where {@code XXXX} is
 * the last four digits.
 *
 * <h2>Motivation &mdash; QA Final-CP6 Finding M4 (MINOR)</h2>
 * <p>The legacy COBOL {@code CVCUS01Y.cpy} record stored
 * {@code CUST-SSN PIC 9(09)} as a 9-digit numeric field that the BMS
 * COACTVW account-inquiry screen displayed in plaintext. The Java REST
 * target accordingly exposed {@link Long} {@code customerSsn} on
 * {@code AccountViewDto}, and the {@code toString()} override masked it
 * for log/audit safety. However, the JSON wire response continued to
 * carry the unmasked 9-digit value, which QA flagged as a PCI-DSS
 * posture gap on PAN-style masking parity (M4).
 *
 * <p>This serializer applies the same {@code ***-**-XXXX} mask to the
 * JSON output that {@code toString()} produces for logs. Authorized
 * callers receive only the last four digits over the wire; the unmasked
 * value remains in JVM memory for the duration of the request and is
 * available to any internal code path that explicitly reads the
 * underlying record component.</p>
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>If the value is {@code null}, emit a JSON {@code null} (preserves
 *       the existing &quot;no data&quot; semantic when a Customer row has
 *       not yet been hydrated).</li>
 *   <li>Otherwise, zero-pad the value to 9 digits using
 *       {@code String.format(&quot;%09d&quot;, value)} (handles SSNs that
 *       begin with {@code 0}, which a boxed {@code Long} would otherwise
 *       elide).</li>
 *   <li>Concatenate the literal prefix {@code "***-**-"} with the last
 *       four digits ({@code substring(5)}).</li>
 *   <li>Emit the masked value as a JSON string. The on-wire field
 *       changes shape from a JSON number ({@code 123456789}) to a JSON
 *       string ({@code "***-**-6789"}); the {@code @Schema(type=string,
 *       example="***-**-6789")} declaration on the field documents this
 *       for clients and OpenAPI consumers.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * <p>This serializer holds no mutable state and is safe for use by the
 * default {@code ObjectMapper} singleton produced by Spring Boot.</p>
 *
 * <h2>See also</h2>
 * <ul>
 *   <li>{@link com.awsm2.carddemo.dto.AccountViewDto#customerSsn()} &mdash;
 *       the only field currently using this serializer.</li>
 *   <li>AAP &sect;0.6.6 &mdash; PCI-DSS PAN/PII masking policy.</li>
 * </ul>
 */
public final class MaskedSsnSerializer extends StdSerializer<Long> {

    private static final long serialVersionUID = 1L;

    /** Literal prefix; the substring(5) of a 9-digit zero-padded SSN
     *  carries the last 4 digits. */
    private static final String MASK_PREFIX = "***-**-";

    /**
     * Public no-arg constructor required by Jackson's
     * {@code @JsonSerialize(using = ...)} machinery so the framework can
     * instantiate this serializer via reflection.
     */
    public MaskedSsnSerializer() {
        super(Long.class);
    }

    /**
     * Renders the supplied SSN as a masked JSON string.
     *
     * @param value    the SSN value to mask (may be {@code null})
     * @param gen      the JSON generator to write to
     * @param provider the active serializer provider
     * @throws IOException if the underlying generator fails
     */
    @Override
    public void serialize(Long value,
                          JsonGenerator gen,
                          SerializerProvider provider) throws IOException {
        if (value == null) {
            // Mirrors the toString() behavior: a null SSN is emitted as
            // a JSON null rather than the literal "***-**-null" or the
            // mask "***-**-    " — clients should distinguish "absent"
            // from "masked-but-present".
            gen.writeNull();
            return;
        }
        // Zero-pad to 9 digits so the substring(5,9) slice always yields
        // a well-defined last-four-digit segment regardless of the
        // actual decimal width of the boxed Long value (e.g., a leading-
        // zero SSN such as 023456789 which has 9 digits but a boxed
        // decimal width of 8).
        final String ssnStr = String.format("%09d", value);
        gen.writeString(MASK_PREFIX + ssnStr.substring(5));
    }
}

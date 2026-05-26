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
package com.awsm2.carddemo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Abend (abnormal end) data DTO mirroring the COBOL {@code ABEND-DATA}
 * 01-level structure defined in {@code app/cpy/CSMSG02Y.cpy} (whose
 * internal header label is {@code CABENDD.CPY} &mdash; the filename and the
 * structure name diverge by design in the source repository).
 *
 * <p><b>COBOL source layout</b> (preserved verbatim from
 * {@code app/cpy/CSMSG02Y.cpy} lines 21&ndash;29):</p>
 * <pre>{@code
 *   01  ABEND-DATA.
 *       05  ABEND-CODE      PIC X(4)   VALUE SPACES.
 *       05  ABEND-CULPRIT   PIC X(8)   VALUE SPACES.
 *       05  ABEND-REASON    PIC X(50)  VALUE SPACES.
 *       05  ABEND-MSG       PIC X(72)  VALUE SPACES.
 * }</pre>
 *
 * <p>In the original CardDemo COBOL programs, every {@code 9999-ABEND-PROGRAM}
 * paragraph populated this structure with a 4-character abend code (e.g.,
 * {@code ASRA} for program-check, {@code AEY9} for storage-violation, or a
 * 4-character custom reason code), the 8-character "culprit" program name
 * (e.g., {@code COSGN00C}), a 50-character human-readable reason, and a
 * 72-character informational message &mdash; before invoking either the
 * Language Environment service {@code CEE3ABD} (controlled abend) or
 * {@code EXEC CICS ABEND}.
 *
 * <p>In the Java/Spring Boot target, this DTO is populated by
 * {@code com.awsm2.carddemo.exception.GlobalExceptionHandler} when an
 * unhandled exception (or any {@code CardDemoException} subclass) escapes
 * a controller. It provides a traceability anchor between the
 * {@code CardDemoException.reasonCode} value (which preserves the COBOL
 * {@code RETURN-CODE} / {@code FILE STATUS} numeric value verbatim per AAP
 * &sect;0.7.2) and the originating service class. The DTO is typically
 * embedded inside an {@code ApiResponse} envelope when surfacing system
 * errors to clients.
 *
 * <p><b>PCI-DSS / production sanitization (AAP &sect;0.6.6):</b> In the
 * {@code prod} profile, {@code GlobalExceptionHandler} MUST sanitize this
 * DTO before returning it to clients &mdash; typically stripping
 * {@link #culprit()} and {@link #message()} (which can leak internal
 * class names, file paths, or sensitive operational data) and leaving
 * only {@link #abendCode()} and a generic {@link #reason()}. The
 * unredacted payload remains available in CloudWatch and OpenSearch
 * indexes for post-incident investigation by authorized operators.
 *
 * <p><b>Wire-format stability:</b> The {@code @JsonProperty} bindings on
 * each component freeze the on-wire JSON contract independently of any
 * future record component renames. {@code @JsonInclude(NON_NULL)} at the
 * record level suppresses fields whose runtime value is {@code null}
 * &mdash; the abend data may be sparse because not every error path
 * populates every field (e.g., a {@code RecordNotFoundException} from a
 * repository layer typically leaves {@link #message()} null).
 *
 * <p><b>COBOL Provenance:</b></p>
 * <ul>
 *   <li>Copybook: {@code app/cpy/CSMSG02Y.cpy} (header label
 *       {@code CABENDD.CPY}, structure name {@code ABEND-DATA})</li>
 *   <li>Used by: every COBOL program's {@code 9999-ABEND-PROGRAM} paragraph
 *       (e.g., {@code COSGN00C}, {@code COACTUPC}, {@code CBTRN02C})</li>
 *   <li>Replaces: {@code CEE3ABD} (LE controlled-abend service) and
 *       {@code EXEC CICS ABEND} call sites &mdash; the populated structure
 *       previously preceded those control-flow terminators; in the Java
 *       target, the same data is surfaced via the REST error response.</li>
 *   <li>Related Java types: {@code com.awsm2.carddemo.exception.CardDemoException}
 *       (base exception with {@code reasonCode} field) and
 *       {@code com.awsm2.carddemo.exception.GlobalExceptionHandler}
 *       ({@code @RestControllerAdvice} that produces this DTO).</li>
 * </ul>
 *
 * <p><b>Distinction from {@code CSMSG01Y.cpy}:</b> Although the filenames
 * are visually similar, {@code CSMSG02Y.cpy} (this DTO) carries
 * <em>abend</em> data; user-facing message constants live in
 * {@code CSMSG01Y.cpy}, which maps to
 * {@code com.awsm2.carddemo.util.AppMessages} per AAP &sect;0.4.1.</p>
 *
 * @param abendCode 4-character abend code &mdash; mirrors COBOL
 *                  {@code ABEND-CODE PIC X(4)}. Typically populated with a
 *                  System z abend mnemonic (e.g., {@code ASRA},
 *                  {@code AEY9}, {@code S0C7}, {@code S806}) or a custom
 *                  4-character application reason code. Aligned with
 *                  {@code CardDemoException.reasonCode} for end-to-end
 *                  traceability (AAP &sect;0.7.2). May be {@code null}
 *                  when an exception's reason code is not classified.
 * @param culprit   Originating program/service name &mdash; mirrors COBOL
 *                  {@code ABEND-CULPRIT PIC X(8)}. In COBOL, this was the
 *                  8-character program-id of the abending module. In the
 *                  Java target, this is typically the
 *                  {@code Class#getSimpleName()} of the originating
 *                  {@code @Service} bean (e.g., {@code "AccountUpdateService"});
 *                  the {@link Schema} {@code maxLength} is widened to 64
 *                  to accommodate fully-qualified Java identifiers while
 *                  keeping the COBOL-style 8-character program names
 *                  representable. MUST be redacted in production responses
 *                  per the PCI-DSS sanitization guidance above.
 * @param reason    Human-readable abend reason &mdash; mirrors COBOL
 *                  {@code ABEND-REASON PIC X(50)}. Typical COBOL values
 *                  were short fixed-format strings such as
 *                  {@code "FILE STATUS 35 ON DALYTRAN"}. The {@link Schema}
 *                  {@code maxLength} is widened to 250 to allow richer
 *                  Java exception messages while keeping the COBOL-style
 *                  50-character reasons fully representable.
 * @param message   Free-form informational message &mdash; mirrors COBOL
 *                  {@code ABEND-MSG PIC X(72)}. Typically carries a longer
 *                  diagnostic sentence in COBOL. The {@link Schema}
 *                  {@code maxLength} is widened to 500 to allow richer
 *                  Java diagnostic content (e.g., partial stack traces in
 *                  non-production profiles); MUST be redacted in
 *                  production responses per the PCI-DSS sanitization
 *                  guidance above.
 *
 * @see com.fasterxml.jackson.annotation.JsonInclude
 * @see io.swagger.v3.oas.annotations.media.Schema
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "AbendData",
        description = "Abend (abnormal end) data carrying the abend code, "
                + "culprit program/service, reason, and informational message. "
                + "Mirrors COBOL ABEND-DATA from CSMSG02Y.cpy (header label "
                + "CABENDD.CPY). Populated by GlobalExceptionHandler when an "
                + "unhandled exception escapes a controller. Sanitized in "
                + "production profile per PCI-DSS guidance (AAP \u00a70.6.6).")
public record AbendDataDto(

        /**
         * 4-character abend code &mdash; preserves COBOL
         * {@code ABEND-CODE PIC X(4)} verbatim.
         *
         * <p>Typical values are System z abend mnemonics
         * ({@code ASRA}, {@code AEY9}, {@code S0C7}, {@code S806}) or
         * custom 4-character application reason codes. The Java target
         * keeps the 4-character width to preserve COBOL
         * {@code RETURN-CODE} parity for downstream consumers (AAP
         * &sect;0.7.2 &mdash; "Error codes and condition handling
         * surfaced to downstream consumers must be preserved verbatim").
         *
         * <p>The on-wire JSON key is bound to {@code "abendCode"} by
         * {@link JsonProperty} so the contract remains stable across any
         * future component renames.
         */
        @Schema(description = "Abend code (4 chars, e.g., 'ASRA', 'AEY9', "
                + "or a custom 4-char application reason code). Preserves "
                + "COBOL ABEND-CODE PIC X(4) verbatim for downstream "
                + "consumer parity (AAP \u00a70.7.2).",
                example = "ASRA",
                maxLength = 4,
                nullable = true)
        @JsonProperty("abendCode")
        String abendCode,

        /**
         * Originating program/service &mdash; mirrors COBOL
         * {@code ABEND-CULPRIT PIC X(8)}.
         *
         * <p>In the COBOL source, this was the 8-character program-id of
         * the module that detected the abend condition (e.g.,
         * {@code "COSGN00C"}, {@code "COACTUPC"}, {@code "CBTRN02C"}). In
         * the Java target, callers typically populate this with the
         * simple class name of the originating {@code @Service} bean
         * (e.g., {@code "AccountUpdateService"}); the
         * {@link Schema#maxLength()} is widened to 64 to accommodate
         * Java-style identifiers while keeping the COBOL 8-character
         * program-ids fully representable.
         *
         * <p><b>PCI-DSS:</b> in production responses this field MUST be
         * redacted (set to {@code null}) by {@code GlobalExceptionHandler}
         * to avoid leaking internal class names; the unredacted value
         * remains available in CloudWatch / OpenSearch for authorized
         * operators (AAP &sect;0.6.6).
         */
        @Schema(description = "Originating program/service identifier. In "
                + "COBOL: 8-character program-id (PIC X(8)). In Java: "
                + "Class#getSimpleName() of the originating @Service. "
                + "Redacted in production responses (AAP \u00a70.6.6).",
                example = "COSGN00C",
                maxLength = 64,
                nullable = true)
        @JsonProperty("culprit")
        String culprit,

        /**
         * Human-readable abend reason &mdash; mirrors COBOL
         * {@code ABEND-REASON PIC X(50)}.
         *
         * <p>COBOL typically populated this with a short fixed-format
         * string such as {@code "FILE STATUS 35 ON DALYTRAN"} or
         * {@code "RECORD NOT FOUND IN CARDXREF"}. The Java target widens
         * {@link Schema#maxLength()} to 250 to accommodate richer
         * exception messages while preserving the COBOL 50-character
         * reasons exactly.
         */
        @Schema(description = "Human-readable abend reason. COBOL: "
                + "ABEND-REASON PIC X(50) &mdash; typically a short fixed "
                + "diagnostic phrase. Widened to 250 chars in Java to "
                + "accommodate richer exception messages.",
                example = "FILE STATUS 35 ON DAILY TRANSACT.DALYTRAN",
                maxLength = 250,
                nullable = true)
        @JsonProperty("reason")
        String reason,

        /**
         * Free-form informational message &mdash; mirrors COBOL
         * {@code ABEND-MSG PIC X(72)}.
         *
         * <p>COBOL populated this with a longer diagnostic sentence
         * (e.g., {@code "Abnormal termination occurred during file open"}).
         * The Java target widens {@link Schema#maxLength()} to 500 to
         * accommodate richer diagnostic content in non-production
         * profiles (e.g., a partial stack-trace or contextual data).
         *
         * <p><b>PCI-DSS:</b> in production responses this field MUST be
         * redacted (set to {@code null}) by {@code GlobalExceptionHandler}
         * to avoid leaking internal diagnostics; the unredacted value
         * remains available in CloudWatch / OpenSearch for authorized
         * operators (AAP &sect;0.6.6).
         */
        @Schema(description = "Free-form informational message. COBOL: "
                + "ABEND-MSG PIC X(72) &mdash; longer diagnostic sentence. "
                + "Widened to 500 chars in Java. Redacted in production "
                + "responses (AAP \u00a70.6.6).",
                example = "Abnormal termination occurred during file open",
                maxLength = 500,
                nullable = true)
        @JsonProperty("message")
        String message
) {
}

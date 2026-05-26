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
package com.awsm2.carddemo.util;

/**
 * Documentation-parity helper for the COBOL CICS BMS field-error-styling
 * template defined in {@code app/cpy/CSSETATY.cpy} (lines 17-27).
 *
 * <p>This class exists for documentation parity with the source COBOL
 * field-styling template; the equivalent runtime behavior in the REST target
 * is provided by {@code GlobalExceptionHandler} translating
 * {@code ValidationException.fieldErrors} into {@code ApiResponse.FieldError}
 * entries.</p>
 *
 * <h2>Source COBOL semantics</h2>
 * <p>The {@code CSSETATY.cpy} copybook is included by every online CICS
 * program ({@code app/cbl/CO*.cbl}) using {@code COPY ... REPLACING}
 * substitutions to apply the following 3270-terminal styling rules during
 * pseudo-conversational re-entry of an input screen:</p>
 *
 * <ol>
 *   <li>If a field's validation flag is set to "not OK" <em>or</em> "blank"
 *       <strong>and</strong> the program context is {@code CDEMO-PGM-REENTER}
 *       (re-entering after a previous validation failure), apply the
 *       {@code DFHRED} color attribute to the corresponding BMS map output
 *       buffer field so the user sees the offending field highlighted in red.
 *       </li>
 *   <li>If the field is blank specifically, additionally write a single
 *       asterisk ({@code '*'}) into the field's output buffer as a visible
 *       "must fill in" marker.</li>
 * </ol>
 *
 * <p>The verbatim COBOL excerpt (lines 17-27 of {@code CSSETATY.cpy}) is:</p>
 *
 * <pre>
 * *    Set (TESTVAR1) to red if in error and * if blankACSHLIM
 *      IF (FLG-(TESTVAR1)-NOT-OK
 *      OR  FLG-(TESTVAR1)-BLANK)
 *      AND CDEMO-PGM-REENTER
 *          MOVE DFHRED             TO
 *               (SCRNVAR2)C OF (MAPNAME3)O
 *          IF  FLG-(TESTVAR1)-BLANK
 *              MOVE '*'            TO
 *               (SCRNVAR2)O OF (MAPNAME3)O
 *          END-IF
 *      END-IF
 * </pre>
 *
 * <p>The {@code (TESTVAR1)}, {@code (SCRNVAR2)}, and {@code (MAPNAME3)}
 * placeholders are replaced by the including program (for example
 * {@code COACTUPC.cbl} substitutes {@code ACSHLIM}, {@code ACSTCRDI}, and
 * {@code CACTUPA} to highlight the cash-credit-limit field on the account
 * update screen). The flag {@code CDEMO-PGM-REENTER} is defined in
 * {@code app/cpy/COCOM01Y.cpy} lines 30-31 as the value-1 condition of the
 * {@code CDEMO-PGM-CONTEXT PIC 9(01)} flag held in the CICS COMMAREA.</p>
 *
 * <h2>REST/JSON target equivalent</h2>
 * <p>In the target Spring Boot REST architecture, 3270 terminal screens are
 * replaced by JSON request/response DTOs and the {@code DFHRED} attribute has
 * no analogue. Instead, field-level validation failures flow through the
 * exception hierarchy: a {@code ValidationException} carries one or more
 * {@code FieldError} records (field name + reason + rejected value); the
 * {@code @RestControllerAdvice} {@code GlobalExceptionHandler} catches the
 * exception and emits an {@code ApiResponse} JSON envelope with the
 * {@code fieldErrors[]} array populated. Clients render the error styling
 * themselves based on that metadata.</p>
 *
 * <p>This class is therefore <strong>informational only</strong> &mdash; it
 * is not invoked at runtime in the REST production flow. It is preserved
 * solely so that the Java target offers an unbroken provenance chain from
 * every COBOL field-styling site back to the original CSSETATY copybook, as
 * required by AAP &sect;0.7.3 (Minimal Change Clause).</p>
 *
 * <h2>Architectural constraints (AAP &sect;0.7)</h2>
 * <ul>
 *   <li>No business logic &mdash; all methods are pure functions over their
 *       inputs.</li>
 *   <li>No AWS SDK calls, no Spring beans, no {@code @Service}, no
 *       {@code @Component} &mdash; pure static utility class.</li>
 *   <li>No mutable state &mdash; all constants are
 *       {@code public static final}.</li>
 *   <li>No {@code javax.*} imports &mdash; uses only {@code java.lang.*}
 *       (Spring Boot 3.x mandates Jakarta; this class has zero imports).</li>
 *   <li>Final class with private constructor &mdash; utility-class
 *       enforcement.</li>
 *   <li>No Lombok, no SLF4J logger, no instance fields.</li>
 * </ul>
 *
 * <h2>Provenance</h2>
 * <ul>
 *   <li>{@code app/cpy/CSSETATY.cpy} (entire copybook, lines 17-27) &mdash;
 *       the field-error-styling template.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy} (lines 29-31) &mdash; the
 *       {@code CDEMO-PGM-CONTEXT} flag and its level-88 condition names
 *       {@code CDEMO-PGM-ENTER} (value 0) and {@code CDEMO-PGM-REENTER}
 *       (value 1) consumed by the styling predicate.</li>
 * </ul>
 *
 * <p>AAP cross-references:</p>
 * <ul>
 *   <li>AAP &sect;0.4.1 Copybooks &rarr; Utility Classes table:
 *       <em>"FieldStylingHelper.java &larr; CSSETATY.cpy &mdash; Field
 *       highlight/error attribute helpers (informational &mdash; no terminal
 *       output in REST world)"</em>.</li>
 *   <li>AAP &sect;0.7.3 Minimal Change Clause: preserve COBOL semantics for
 *       documentation/traceability; do not eliminate, simplify, or "improve"
 *       the helpers beyond what is necessary to make them valid Java.</li>
 *   <li>AAP &sect;0.3.4 User Interface Design: BMS terminal screens are
 *       replaced by REST endpoints; field-error metadata flows via
 *       {@code ApiResponse.FieldError} entries.</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.exception.GlobalExceptionHandler
 * @see com.awsm2.carddemo.dto.ApiResponse
 * @see com.awsm2.carddemo.exception.ValidationException
 */
// COBOL: app/cpy/CSSETATY.cpy (entire copybook, lines 17-27) — field-error-styling template
public final class FieldStylingHelper {

    /**
     * CICS-provided 3270 attribute character constant for the "red text"
     * styling applied to BMS map output fields when validation fails.
     *
     * <p>In the source COBOL programs the literal symbol {@code DFHRED} is
     * resolved by the CICS translator/copybook {@code DFHBMSCA} to a 1-byte
     * 3270 buffer-control character. The Java target carries the symbolic
     * name {@code "DFHRED"} as a documentation marker only &mdash; the REST
     * target does not write any 3270 attribute byte.</p>
     *
     * <p>Documentation-only constant; not consumed at runtime in the REST
     * production flow.</p>
     */
    // COBOL: CSSETATY.cpy:L21 — CICS-provided 3270 attribute character constant for "red text" on a BMS map field
    public static final String DFHRED_ATTRIBUTE = "DFHRED";

    /**
     * Single-character marker placed in blank required fields during
     * pseudo-conversational reentry &mdash; literally the COBOL constant
     * {@code '*'} on line 24 of {@code CSSETATY.cpy}.
     *
     * <p>In the source CICS application, this asterisk is written into the
     * output buffer of any blank required field so the user, after re-entry,
     * sees an asterisk-prefilled marker indicating which fields still need
     * input. In the REST target this concept maps to a {@code FieldError}
     * entry with reason {@code "must not be blank"}.</p>
     *
     * <p>Documentation-only constant; not consumed at runtime in the REST
     * production flow.</p>
     */
    // COBOL: CSSETATY.cpy:L24 — single-character marker placed in blank required fields during pseudo-conversational reentry
    public static final String BLANK_MARKER = "*";

    /**
     * Numeric value of the {@code CDEMO-PGM-ENTER} 88-level condition name
     * defined in the CICS COMMAREA copybook {@code COCOM01Y.cpy} (line 30),
     * representing the <em>first entry</em> into a pseudo-conversational
     * program (the user has just navigated to the screen and not yet pressed
     * Enter/PF on it).
     *
     * <p>In the source COBOL the field is declared as
     * {@code 10 CDEMO-PGM-CONTEXT PIC 9(01)} (a single-digit numeric) with
     * two named conditions: {@code 88 CDEMO-PGM-ENTER VALUE 0.} and
     * {@code 88 CDEMO-PGM-REENTER VALUE 1.}. The COBOL field-styling logic
     * in {@code CSSETATY.cpy} predicates on {@code CDEMO-PGM-REENTER} so the
     * "red + asterisk" treatment is only applied when the user is re-entering
     * a screen after a previous validation failure, not on first entry.</p>
     *
     * <p>Documentation-only constant; not consumed at runtime in the REST
     * production flow (REST endpoints are inherently stateless and
     * {@code GlobalExceptionHandler} returns error responses on every
     * validation failure regardless of "entry" vs "re-entry").</p>
     */
    // COBOL: COCOM01Y.cpy:L30 — CDEMO-PGM-ENTER VALUE 0 (first entry into the pseudo-conversational program)
    public static final int CDEMO_PGM_ENTER = 0;

    /**
     * Numeric value of the {@code CDEMO-PGM-REENTER} 88-level condition name
     * defined in the CICS COMMAREA copybook {@code COCOM01Y.cpy} (line 31),
     * representing <em>re-entry</em> into a pseudo-conversational program
     * after a user-correctable validation failure on a prior round trip.
     *
     * <p>In the source COBOL programs, after the validation paragraph sets
     * field-level {@code FLG-...-NOT-OK} or {@code FLG-...-BLANK} flags and
     * sets {@code CDEMO-PGM-CONTEXT} to {@code 1}, the program issues a
     * {@code EXEC CICS RETURN TRANSID(...) COMMAREA(...)} to redisplay the
     * same screen. On re-display, the {@code CSSETATY.cpy} template fires
     * the {@link #DFHRED_ATTRIBUTE} treatment and asterisk-marker placement
     * specifically because {@code CDEMO-PGM-REENTER} is true.</p>
     *
     * <p>Documentation-only constant; not consumed at runtime in the REST
     * production flow.</p>
     */
    // COBOL: COCOM01Y.cpy:L31 — CDEMO-PGM-REENTER VALUE 1 (re-entry after a user-correctable validation failure)
    public static final int CDEMO_PGM_REENTER = 1;

    /**
     * Private constructor &mdash; utility class is non-instantiable and is
     * consumed via its public static members only.
     */
    private FieldStylingHelper() {
        /* utility class - prevent instantiation */
    }

    /**
     * Java analogue of the COBOL {@code MOVE DFHRED TO (SCRNVAR2)C OF
     * (MAPNAME3)O} statement &mdash; produces a "styled" textual
     * representation of an error field.
     *
     * <p>In the source CICS application, the corresponding COBOL line writes
     * the 1-byte {@code DFHRED} attribute character into the 3270 buffer
     * field's color-attribute slot so the field renders in red on the
     * physical terminal. In the REST target there is no terminal buffer; the
     * equivalent metadata appears in {@code ApiResponse.error(...)}
     * envelopes produced by {@code GlobalExceptionHandler}, so this method
     * is documentation-only.</p>
     *
     * <p><strong>Behavior:</strong> for a null or blank input, returns the
     * {@link #BLANK_MARKER} constant ({@code "*"}) to mirror the inner
     * asterisk-marker step of the COBOL template; for any non-blank input,
     * returns the original {@code fieldValue} unchanged (the COBOL original
     * preserves the user's typed value while overlaying the red attribute on
     * the screen). The method is null-safe and side-effect-free.</p>
     *
     * <p>Documentation-only helper; not invoked at runtime in the REST
     * production flow.</p>
     *
     * @param fieldValue the field value that failed validation, or
     *                   {@code null} / blank if the user left the field
     *                   empty
     * @return {@link #BLANK_MARKER} when {@code fieldValue} is {@code null}
     *         or blank (per {@link String#isBlank()}); otherwise the
     *         original {@code fieldValue} unchanged
     */
    // COBOL: CSSETATY.cpy:L21-L22 — MOVE DFHRED ... (red highlighting of error fields)
    public static String markAsError(String fieldValue) {
        if (fieldValue == null || fieldValue.isBlank()) {
            return BLANK_MARKER;
        }
        return fieldValue;
    }

    /**
     * Pure predicate mirroring the COBOL outer condition
     * {@code IF (FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK) AND
     * CDEMO-PGM-REENTER}.
     *
     * <p>In the source CICS application this predicate gates the entire
     * "red highlighting" treatment so that a field is only highlighted as an
     * error when (a) its validation flag is set to "not OK" or "blank"
     * <em>and</em> (b) the user is re-entering the screen after a previous
     * validation failure (a first-time visitor has not yet had a chance to
     * enter values, so no error styling is applied on first entry).</p>
     *
     * <p>This method is informational only and is not consumed at runtime in
     * the REST flow; REST endpoints return validation errors on every
     * failing round trip regardless of an "entry" vs "re-entry" distinction
     * because REST is inherently stateless.</p>
     *
     * <p>Documentation-only helper; not invoked at runtime in the REST
     * production flow.</p>
     *
     * @param flagNotOk    {@code true} when the corresponding COBOL
     *                     {@code FLG-(TESTVAR1)-NOT-OK} validation flag is
     *                     set
     * @param flagBlank    {@code true} when the corresponding COBOL
     *                     {@code FLG-(TESTVAR1)-BLANK} validation flag is
     *                     set
     * @param programState the current value of {@code CDEMO-PGM-CONTEXT}
     *                     from the CICS COMMAREA &mdash; equal to
     *                     {@link #CDEMO_PGM_REENTER} ({@code 1}) when the
     *                     screen is being redisplayed after a prior
     *                     validation failure; equal to
     *                     {@link #CDEMO_PGM_ENTER} ({@code 0}) on first
     *                     entry
     * @return {@code (flagNotOk || flagBlank) && programState ==
     *         CDEMO_PGM_REENTER}
     */
    // COBOL: CSSETATY.cpy:L18-L20 — predicate for showing red error attribute during pseudo-conversational reentry
    public static boolean shouldHighlightAsError(boolean flagNotOk,
                                                 boolean flagBlank,
                                                 int programState) {
        return (flagNotOk || flagBlank) && programState == CDEMO_PGM_REENTER;
    }

    /**
     * Java analogue of the inner COBOL step
     * {@code IF FLG-(TESTVAR1)-BLANK MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O}.
     *
     * <p>In the source CICS application this inner step writes the
     * single-character asterisk marker into the BMS map output buffer of any
     * required field that the user left blank during the prior round trip,
     * so the user sees an asterisk-prefilled marker on the redisplayed
     * screen indicating which fields still need input.</p>
     *
     * <p>This method is informational only and is not consumed at runtime in
     * the REST flow; the equivalent runtime information in REST is the
     * {@code FieldError} record with a {@code "must not be blank"} reason
     * inside the {@code ApiResponse.fieldErrors[]} array.</p>
     *
     * <p>Documentation-only helper; not invoked at runtime in the REST
     * production flow.</p>
     *
     * @param fieldValue the original field value, returned unchanged when
     *                   {@code flagBlank} is {@code false}
     * @param flagBlank  {@code true} when the corresponding COBOL
     *                   {@code FLG-(TESTVAR1)-BLANK} validation flag is set,
     *                   indicating the field was left blank by the user
     * @return {@link #BLANK_MARKER} when {@code flagBlank} is {@code true};
     *         otherwise the original {@code fieldValue} unchanged
     */
    // COBOL: CSSETATY.cpy:L23-L26 — IF blank, set field to '*' marker
    public static String applyBlankMarker(String fieldValue, boolean flagBlank) {
        if (flagBlank) {
            return BLANK_MARKER;
        }
        return fieldValue;
    }
}

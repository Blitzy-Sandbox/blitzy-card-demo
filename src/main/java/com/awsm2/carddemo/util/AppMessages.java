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
 * Centralized user-message and screen-title constants ported verbatim from the
 * COBOL copybooks {@code app/cpy/CSMSG01Y.cpy} and {@code app/cpy/COTTL01Y.cpy}.
 *
 * <p>This utility class is a one-to-one translation of two COBOL copybooks that
 * defined fixed-length user-facing literals used throughout the source CICS
 * online programs:</p>
 *
 * <ul>
 *   <li>{@code CSMSG01Y.cpy} — {@code CCDA-COMMON-MESSAGES} group with two
 *       50-byte {@code PIC X(50)} message fields displayed in the message area
 *       of the 3270 screens.</li>
 *   <li>{@code COTTL01Y.cpy} — {@code CCDA-SCREEN-TITLE} group with three
 *       40-byte {@code PIC X(40)} title/banner fields displayed in the header
 *       area of every screen.</li>
 * </ul>
 *
 * <p>All constant values are preserved <strong>byte-for-byte</strong> including
 * trailing space padding to match the original COBOL {@code PIC X(50)} and
 * {@code PIC X(40)} fixed-length contracts. The COBOL compiler implicitly
 * right-pads a shorter string literal with ASCII spaces to fill the declared
 * field width; the constants here carry the fully-padded 50/40 byte form so
 * that golden-output diff tests (see AAP &sect;0.6.2) can be performed without
 * additional padding logic at the call site. Callers that need a trimmed
 * display value should call {@link String#trim()} at the call site; callers
 * that need the original 50/40-byte form should consume the constant as-is.</p>
 *
 * <p>Per the AAP &sect;0.7.3 Minimal Change Clause, the strings are reproduced
 * exactly — they are NOT rephrased, reformatted, recapitalized, or otherwise
 * "improved". The historical commented-out value
 * {@code '  Credit Card Demo Application (CCDA)   '} on
 * {@code COTTL01Y.cpy} line 21 (the prior application long-title) is preserved
 * here as JavaDoc text only and is intentionally <em>not</em> exposed as a
 * Java constant; only the active short title {@code CardDemo} is published as
 * {@link #TITLE_LINE_2}.</p>
 *
 * <p>This class is purely static — it has no instance fields, no mutable
 * state, no static initializer side-effects, no AWS SDK calls, no Spring
 * dependencies, and no logging. It is safe to consume from any layer
 * (controller, service, repository, batch job, configuration class, exception
 * handler) and is the foundational utility for the {@code util} package.</p>
 *
 * <h2>Length contracts</h2>
 * <p>Two integer constants document the fixed-byte length contracts so callers
 * may validate buffer sizes against the COBOL field widths without literal
 * "magic numbers":</p>
 * <ul>
 *   <li>{@link #MESSAGE_LENGTH} = 50 — corresponds to {@code PIC X(50)}</li>
 *   <li>{@link #TITLE_LENGTH} = 40 — corresponds to {@code PIC X(40)}</li>
 * </ul>
 *
 * <h2>Padding helper</h2>
 * <p>The {@link #padRight(String, int)} static method right-pads an arbitrary
 * runtime-built string with ASCII spaces to a target length, matching COBOL's
 * implicit {@code MOVE shorter-string TO PIC X(n) field} behavior. It is the
 * Java analogue of the COBOL fixed-length padding semantic and is intended for
 * use by golden-output diff tests, report formatters, and statement
 * generators that must emit byte-identical COBOL-fixed-length output.</p>
 *
 * <h2>Provenance</h2>
 * <p>COBOL source files referenced (preserved frozen under {@code app/cpy/}):
 * </p>
 * <ul>
 *   <li>{@code app/cpy/CSMSG01Y.cpy} — {@code CCDA-COMMON-MESSAGES} group, 50-byte messages</li>
 *   <li>{@code app/cpy/COTTL01Y.cpy} — {@code CCDA-SCREEN-TITLE} group, 40-byte titles</li>
 * </ul>
 *
 * <p>AAP cross-references:</p>
 * <ul>
 *   <li>AAP &sect;0.4.1 Copybooks &rarr; Utility Classes table:
 *       <em>"AppMessages.java &larr; CSMSG01Y.cpy, COTTL01Y.cpy — User message
 *       and title constants"</em>.</li>
 *   <li>AAP &sect;0.7.3 Minimal Change Clause: byte-for-byte preservation of
 *       all user-facing literals; no reformatting permitted.</li>
 *   <li>AAP &sect;0.6.2 Golden-output diff testing: the
 *       {@link #padRight(String, int)} helper supports byte-identical
 *       parallel-run validation against the original COBOL output.</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.exception.GlobalExceptionHandler
 * @see com.awsm2.carddemo.dto.ApiResponse
 */
// COBOL: CSMSG01Y.cpy (CCDA-COMMON-MESSAGES, 50-byte messages), COTTL01Y.cpy (CCDA-SCREEN-TITLE, 40-byte titles)
public final class AppMessages {

    /**
     * Fixed-byte length contract for all common user messages, mirroring the
     * COBOL {@code PIC X(50)} declaration on
     * {@link #MSG_THANK_YOU} and {@link #MSG_INVALID_KEY}.
     *
     * <p>Equal to {@code 50}. Callers should prefer this constant over the
     * literal value when validating buffer sizes for COBOL-equivalent fixed
     * message output.</p>
     */
    // COBOL: CSMSG01Y.cpy:L18,L20 — PIC X(50) fixed-length contract for all common user messages
    public static final int MESSAGE_LENGTH = 50;

    /**
     * Fixed-byte length contract for all screen titles/banners, mirroring the
     * COBOL {@code PIC X(40)} declaration on
     * {@link #TITLE_LINE_1}, {@link #TITLE_LINE_2}, and
     * {@link #TITLE_THANK_YOU}.
     *
     * <p>Equal to {@code 40}. Callers should prefer this constant over the
     * literal value when validating buffer sizes for COBOL-equivalent fixed
     * title output.</p>
     */
    // COBOL: COTTL01Y.cpy:L18,L20,L23 — PIC X(40) fixed-length contract for all screen titles/banners
    public static final int TITLE_LENGTH = 40;

    /**
     * Thank-you message displayed after successful sign-off or session
     * completion (50-byte common-message variant).
     *
     * <p>This is the message-area variant from the {@code CCDA-COMMON-MESSAGES}
     * group in {@code CSMSG01Y.cpy}. A complementary 40-byte title-area
     * variant is published as {@link #TITLE_THANK_YOU} — both coexist in the
     * source CICS application and are preserved verbatim for documentation
     * parity per AAP &sect;0.7.3.</p>
     *
     * <p>Length: exactly {@value #MESSAGE_LENGTH} characters (43-character
     * body {@code "Thank you for using CardDemo application..."} followed by
     * 7 ASCII spaces to fill the COBOL {@code PIC X(50)} field).</p>
     */
    // COBOL: CSMSG01Y.cpy:L18-L19 — CCDA-MSG-THANK-YOU PIC X(50)
    public static final String MSG_THANK_YOU =
            "Thank you for using CardDemo application...       ";

    /**
     * Generic message displayed when the user presses an unrecognized or
     * unsupported PF/AID key on a 3270 screen (50-byte common-message
     * variant).
     *
     * <p>In the REST target this maps to an HTTP 400 "Invalid input" response
     * and is rarely shown directly to the end user; the constant is preserved
     * verbatim for documentation parity and for any legacy consumer that still
     * references the original COBOL field name.</p>
     *
     * <p>Length: exactly {@value #MESSAGE_LENGTH} characters (40-character
     * body {@code "Invalid key pressed. Please see below..."} followed by 10
     * ASCII spaces to fill the COBOL {@code PIC X(50)} field).</p>
     */
    // COBOL: CSMSG01Y.cpy:L20-L21 — CCDA-MSG-INVALID-KEY PIC X(50)
    public static final String MSG_INVALID_KEY =
            "Invalid key pressed. Please see below...          ";

    /**
     * First-line banner displayed on every CICS screen — the vendor product
     * name. Centered within a 40-byte title field via 6 leading and 7
     * trailing ASCII spaces.
     *
     * <p>Length: exactly {@value #TITLE_LENGTH} characters (6 leading spaces +
     * 27-character body {@code "AWS Mainframe Modernization"} + 7 trailing
     * spaces).</p>
     */
    // COBOL: COTTL01Y.cpy:L18-L19 — CCDA-TITLE01 PIC X(40)
    public static final String TITLE_LINE_1 =
            "      AWS Mainframe Modernization       ";

    /**
     * Second-line banner displayed on every CICS screen — the application
     * short name. Centered within a 40-byte title field via 14 leading and
     * 18 trailing ASCII spaces.
     *
     * <p>Length: exactly {@value #TITLE_LENGTH} characters (14 leading spaces
     * + 8-character body {@code "CardDemo"} + 18 trailing spaces).</p>
     *
     * <p><strong>Historical note:</strong> {@code COTTL01Y.cpy} line 21
     * contains a commented-out earlier value
     * {@code '  Credit Card Demo Application (CCDA)   '} representing the
     * prior application long-title before the COBOL source was updated to
     * the shorter {@code CardDemo} form. The shorter form is the active
     * value; the longer form is preserved as documentation only and is not
     * exposed as a Java constant.</p>
     */
    // COBOL: COTTL01Y.cpy:L20,L22 — CCDA-TITLE02 PIC X(40) (current value; L21 historical value commented out in source)
    public static final String TITLE_LINE_2 =
            "              CardDemo                  ";

    /**
     * Banner-style 40-byte thank-you displayed at sign-off (title-area
     * variant).
     *
     * <p>NOTE: this is the 40-byte title-line variant distinct from the
     * 50-byte {@link #MSG_THANK_YOU} defined in {@code CSMSG01Y.cpy}. The
     * two coexist in the source CICS application — the banner version (this
     * constant) appears in the title area of the goodbye screen; the message
     * version ({@code MSG_THANK_YOU}) appears in the message area. Both are
     * preserved verbatim per AAP &sect;0.7.3 Minimal Change Clause; renaming
     * or consolidating either would alter the COBOL public-API surface
     * referenced by consumer programs.</p>
     *
     * <p>Length: exactly {@value #TITLE_LENGTH} characters (39-character
     * body {@code "Thank you for using CCDA application..."} followed by 1
     * trailing ASCII space).</p>
     */
    // COBOL: COTTL01Y.cpy:L23-L24 — CCDA-THANK-YOU PIC X(40)
    public static final String TITLE_THANK_YOU =
            "Thank you for using CCDA application... ";

    /**
     * Private no-arg constructor that prevents external instantiation of this
     * utility class. {@code AppMessages} is a pure-static constant holder
     * and is never expected to be instantiated.
     *
     * <p>This constructor is intentionally private and is never invoked.
     * It exists solely to enforce the no-instantiation utility-class
     * contract and to satisfy static-analysis tools that flag classes
     * with implicit public default constructors.</p>
     */
    private AppMessages() {
        // utility class — prevent instantiation
    }

    /**
     * Right-pads {@code value} with ASCII spaces to the specified
     * {@code length}, matching the COBOL {@code PIC X(n)} fixed-length
     * contract.
     *
     * <p>This is the Java analogue of COBOL's implicit
     * {@code MOVE shorter-string TO PIC X(n) field} behavior, where any
     * source string shorter than the declared field width is automatically
     * right-padded with ASCII spaces (0x20) to fill the field. It is used
     * by golden-output diff tests (see AAP &sect;0.6.2), report formatters,
     * statement generators, and any other code path that must emit
     * byte-identical COBOL-fixed-length output for parallel-run validation
     * against the original mainframe output.</p>
     *
     * <h4>Behavior</h4>
     * <ul>
     *   <li>If {@code length} is negative, throws
     *       {@link IllegalArgumentException}.</li>
     *   <li>If {@code value} is {@code null}, returns a string of
     *       {@code length} ASCII spaces (the COBOL initial-value semantic
     *       for an uninitialized {@code PIC X(n)} field).</li>
     *   <li>If {@code value.length() == length}, returns {@code value}
     *       unchanged.</li>
     *   <li>If {@code value.length() > length}, returns
     *       {@code value.substring(0, length)} (truncation to fit). This
     *       matches COBOL's truncating behavior when a longer string is
     *       MOVEd to a shorter {@code PIC X(n)} field.</li>
     *   <li>If {@code value.length() < length}, returns {@code value}
     *       concatenated with {@code (length - value.length())} ASCII
     *       spaces.</li>
     * </ul>
     *
     * <p>The method is pure (no side effects), deterministic, and
     * thread-safe — it allocates only a single result string on each call
     * via {@link String#repeat(int)} and {@link String#concat(String)}-
     * style concatenation.</p>
     *
     * @param value  the source string to pad, possibly {@code null}; treated
     *               as the empty string {@code ""} (i.e. {@code length}
     *               spaces) when {@code null}
     * @param length the target byte/character length; must be non-negative
     * @return a new {@link String} of exactly {@code length} characters,
     *         right-padded with ASCII spaces if necessary or truncated if
     *         {@code value} was longer than {@code length}
     * @throws IllegalArgumentException if {@code length} is negative
     */
    // COBOL: implicit MOVE behavior — when a shorter string is MOVEd to a PIC X(n) field, COBOL right-pads with spaces
    public static String padRight(String value, int length) {
        if (length < 0) {
            throw new IllegalArgumentException(
                    "length must be non-negative, got: " + length);
        }
        if (value == null) {
            return " ".repeat(length);
        }
        final int currentLength = value.length();
        if (currentLength == length) {
            return value;
        }
        if (currentLength > length) {
            return value.substring(0, length);
        }
        return value + " ".repeat(length - currentLength);
    }
}

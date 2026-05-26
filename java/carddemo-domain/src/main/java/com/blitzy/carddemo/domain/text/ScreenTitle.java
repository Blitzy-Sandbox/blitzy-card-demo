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
package com.blitzy.carddemo.domain.text;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * Screen-title constants translated from the COBOL copybook
 * {@code app/cpy/COTTL01Y.cpy}. Holds the three 40-character title strings
 * that every CardDemo online program writes to the top of a BMS map: two
 * banner titles ({@link #TITLE_01}, {@link #TITLE_02}) and the goodbye line
 * ({@link #THANK_YOU}).
 *
 * <h2>COBOL source</h2>
 * The translation source is the {@code 01 CCDA-SCREEN-TITLE} group from
 * {@code app/cpy/COTTL01Y.cpy}:
 * <pre>{@code
 *  01 CCDA-SCREEN-TITLE.
 *    05 CCDA-TITLE01    PIC X(40) VALUE
 *       '      AWS Mainframe Modernization       '.
 *    05 CCDA-TITLE02    PIC X(40) VALUE
 * *     '  Credit Card Demo Application (CCDA)   '.
 *       '              CardDemo                  '.
 *    05 CCDA-THANK-YOU  PIC X(40) VALUE
 *       'Thank you for using CCDA application... '.
 * }</pre>
 *
 * <h2>Translation strategy</h2>
 * {@code CCDA-SCREEN-TITLE} is a <em>constants copybook</em>: every
 * 05-level entry has a {@code PIC X(40) VALUE '...'} clause whose literal
 * string is never modified at runtime. The idiomatic Java translation is
 * therefore a {@code final class} with {@code public static final String}
 * constants &mdash; <strong>not</strong> a {@code record} with
 * {@code parse(byte[])} / {@code encode()} methods, because these strings
 * are never serialized to or deserialized from a fixed-width binary file in
 * this form. Online programs simply reference the constants when populating
 * a screen's title field.
 *
 * <h2>40-character width invariant</h2>
 * Each title is COBOL {@code PIC X(40)} &mdash; exactly 40 characters,
 * space-padded. The {@link #FIELD_LENGTH} constant records the per-field
 * width and {@link #RECORD_LENGTH} records the total group width
 * ({@code 3 * 40 = 120}). A {@code static} initializer block asserts that
 * each constant is exactly {@code FIELD_LENGTH} characters; this provides
 * fail-fast at class-load time should anyone ever accidentally edit a
 * constant in a way that changes its width. See AAP &sect;0.6.5
 * (byte-for-byte file fidelity is non-negotiable).
 *
 * <h2>Commented-out alternative for CCDA-TITLE02</h2>
 * The COBOL source contains a commented-out previous value for
 * {@code CCDA-TITLE02}: {@code '  Credit Card Demo Application (CCDA)   '}.
 * This historical value is documented on {@link #TITLE_02} for traceability
 * per AAP &sect;0.7.1 (idiom-for-idiom translation with full provenance) but
 * is <strong>NOT</strong> exposed as a constant &mdash; only the active
 * (uncommented) COBOL value is exposed.
 *
 * <h2>Relationship to SystemMessages.THANK_YOU_MSG</h2>
 * The COBOL source repository contains <em>two distinct</em> "thank you"
 * strings:
 * <ul>
 *   <li>{@link #THANK_YOU} (this class) &mdash; 40 characters, derived from
 *       {@code CCDA-THANK-YOU} in {@code COTTL01Y.cpy}, with the wording
 *       "Thank you for using <strong>CCDA</strong> application...".</li>
 *   <li>{@code SystemMessages.THANK_YOU_MSG} (sibling class) &mdash; 50
 *       characters, derived from {@code CSMSG01Y.cpy} / {@code CSMSG02Y.cpy},
 *       with the wording "Thank you for using <strong>CardDemo</strong>
 *       application...".</li>
 * </ul>
 * The wording difference ("CCDA" vs "CardDemo") and the width difference
 * (40 vs 50) are both present in the COBOL source and are preserved
 * verbatim per AAP &sect;0.7.1 idiom-for-idiom translation. They are
 * <strong>not</strong> consolidated.
 *
 * <h2>Instantiation</h2>
 * This class is a pure constants holder. The private constructor throws
 * {@link AssertionError} to enforce non-instantiability at runtime (in
 * addition to the {@code final} keyword which prevents subclassing).
 *
 * <h2>Provenance</h2>
 * Original COBOL footer stamp:
 * {@code Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:58 CDT}.
 *
 * @see com.blitzy.carddemo.domain.annotation.CobolProgram
 * @since 1.0.0
 */
@CobolProgram(
        value = "COTTL01Y",
        sourcePath = "app/cpy/COTTL01Y.cpy",
        notes = "Screen title constants (CCDA-SCREEN-TITLE group). Three PIC X(40) "
                + "literal strings; idiom-for-idiom translated as static final "
                + "Strings, not as a parse/encode record. Active value of "
                + "CCDA-TITLE02 is 'CardDemo'; the commented-out alternative "
                + "'Credit Card Demo Application (CCDA)' is preserved in Javadoc "
                + "for historical traceability only. Source footer version: "
                + "CardDemo_v1.0-15-g27d6c6f-68 (2022-07-19 23:15:58 CDT)."
)
public final class ScreenTitle {

    /**
     * Width of each individual title field, in characters. Mirrors the COBOL
     * declaration {@code PIC X(40)} on each 05-level entry of the
     * {@code CCDA-SCREEN-TITLE} group. Every {@link String} constant in this
     * class is required to be exactly this many characters long; the
     * {@code static} initializer block enforces this invariant at class load.
     *
     * <p>External consumers (BMS-to-DTO translation, online programs that
     * populate screen title fields) should use this constant when computing
     * offsets or padding lengths rather than hard-coding the literal
     * {@code 40}.
     */
    public static final int FIELD_LENGTH = 40;

    /**
     * Total width of the {@code CCDA-SCREEN-TITLE} group, in characters.
     * Computed as {@code 3 * FIELD_LENGTH = 120}, matching the COBOL
     * 01-level group's three 05-level {@code PIC X(40)} entries laid out
     * contiguously. Although this group is never serialized to disk as a
     * record (it is a constants copybook), the total width is recorded for
     * documentation symmetry with the byte-layout records in the
     * {@code com.blitzy.carddemo.domain.record} package.
     */
    public static final int RECORD_LENGTH = 3 * FIELD_LENGTH;

    /**
     * COBOL field {@code CCDA-TITLE01}, declared in {@code COTTL01Y.cpy}
     * line 18 as:
     * <pre>{@code 05 CCDA-TITLE01    PIC X(40) VALUE
     *    '      AWS Mainframe Modernization       '.}</pre>
     *
     * <p>Layout: 6 leading spaces + {@code "AWS Mainframe Modernization"}
     * (27 chars) + 7 trailing spaces = {@value FIELD_LENGTH} characters.
     *
     * <p>This is the first of the two banner title lines rendered at the
     * top of every online BMS map; it identifies the program family.
     */
    public static final String TITLE_01 = "      AWS Mainframe Modernization       ";

    /**
     * COBOL field {@code CCDA-TITLE02}, declared in {@code COTTL01Y.cpy}
     * lines 20&ndash;22. The COBOL source contains BOTH a commented-out
     * previous value and the current active value; only the active value is
     * exposed here:
     * <pre>{@code 05 CCDA-TITLE02    PIC X(40) VALUE
     * *    '  Credit Card Demo Application (CCDA)   '.   <-- COMMENTED OUT
     *      '              CardDemo                  '.   <-- ACTIVE VALUE
     * }</pre>
     *
     * <p>Active layout: 14 leading spaces + {@code "CardDemo"} (8 chars) +
     * 18 trailing spaces = {@value FIELD_LENGTH} characters.
     *
     * <p><strong>Historical note (preserved per AAP &sect;0.7.1):</strong>
     * The previous banner text was
     * {@code "  Credit Card Demo Application (CCDA)   "} (2 leading spaces +
     * "Credit Card Demo Application (CCDA)" + 3 trailing spaces, also 40
     * characters). It was replaced in the COBOL source by the shorter
     * "CardDemo" banner. This historical value is documented for
     * traceability but is intentionally NOT exposed as a constant &mdash;
     * the active COBOL value is the single source of truth.
     */
    public static final String TITLE_02 = "              CardDemo                  ";

    /**
     * COBOL field {@code CCDA-THANK-YOU}, declared in {@code COTTL01Y.cpy}
     * lines 23&ndash;24 as:
     * <pre>{@code 05 CCDA-THANK-YOU  PIC X(40) VALUE
     *    'Thank you for using CCDA application... '.}</pre>
     *
     * <p>Layout: {@code "Thank you for using CCDA application..."} (39
     * chars) + 1 trailing space = {@value FIELD_LENGTH} characters.
     *
     * <p>Rendered on screen exit (e.g., when the operator presses PF3 to
     * sign off) by online programs that complete a transaction.
     *
     * <p><strong>Not to be confused with {@code SystemMessages.THANK_YOU_MSG}.</strong>
     * That sibling constant is 50 characters wide and reads
     * "Thank you for using CardDemo application..." &mdash; with
     * "CardDemo", not "CCDA". Both wordings exist in the COBOL source
     * (COTTL01Y vs CSMSG01Y/CSMSG02Y) and are preserved verbatim per
     * AAP &sect;0.7.1 idiom-for-idiom translation. They are
     * <strong>not</strong> consolidated.
     */
    public static final String THANK_YOU = "Thank you for using CCDA application... ";

    /*
     * Static initializer: fail-fast width check.
     *
     * Each constant above MUST be exactly FIELD_LENGTH (40) characters; this
     * is the Java equivalent of the COBOL `PIC X(40)` width declaration. If
     * any future edit accidentally changes a constant's length, the JVM will
     * refuse to load this class with an AssertionError that names the
     * offending constant. This guards byte-for-byte file fidelity (AAP
     * Section 0.6.5) at the earliest possible point in the program lifecycle.
     */
    static {
        if (TITLE_01.length() != FIELD_LENGTH) {
            throw new AssertionError(
                    "TITLE_01 must be exactly " + FIELD_LENGTH
                            + " characters (COBOL PIC X(40)); actual length is "
                            + TITLE_01.length());
        }
        if (TITLE_02.length() != FIELD_LENGTH) {
            throw new AssertionError(
                    "TITLE_02 must be exactly " + FIELD_LENGTH
                            + " characters (COBOL PIC X(40)); actual length is "
                            + TITLE_02.length());
        }
        if (THANK_YOU.length() != FIELD_LENGTH) {
            throw new AssertionError(
                    "THANK_YOU must be exactly " + FIELD_LENGTH
                            + " characters (COBOL PIC X(40)); actual length is "
                            + THANK_YOU.length());
        }
    }

    /**
     * Private constructor: this class is a pure static-constants holder and
     * is never instantiated. Throws {@link AssertionError} unconditionally
     * to defend against reflective instantiation attempts; the {@code final}
     * class modifier already prevents subclassing.
     *
     * @throws AssertionError always; this class must not be instantiated
     */
    private ScreenTitle() {
        throw new AssertionError(
                "ScreenTitle is a static constants holder; do not instantiate.");
    }
}

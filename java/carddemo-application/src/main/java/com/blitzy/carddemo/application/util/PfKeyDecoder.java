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
package com.blitzy.carddemo.application.util;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.text.CcWorkAreas;
import com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey;

import java.util.Objects;

/**
 * Static helper translating the COBOL procedure-template copybook
 * {@code CSSTRPFY.cpy} ({@code YYYY-STORE-PFKEY} paragraph). The original
 * COBOL is a Procedure Division template that maps the CICS attention
 * identifier byte ({@code EIBAID}) into the shared {@code CCARD-AID-*}
 * 88-level flags carried in {@code CC-WORK-AREAS}. The mapping folds
 * {@code DFHPF13}..{@code DFHPF24} onto the {@code CCARD-AID-PFK01}..
 * {@code CCARD-AID-PFK12} flag space so the rest of the application code
 * only needs to handle 12 program-function keys plus
 * {@code ENTER}/{@code CLEAR}/{@code PA1}/{@code PA2}.
 *
 * <p>In Java, the per-program template instantiation collapses to a single
 * generic helper method {@link #decode(byte)}. The helper takes the raw
 * EIBAID byte (the CICS attention identifier in EBCDIC) and returns one of
 * the 16 sealed {@link AidKey} permits defined in {@link CcWorkAreas}:
 * {@code Enter}, {@code Clear}, {@code Pa1}, {@code Pa2}, {@code PfKey01}
 * through {@code PfKey12}. The result is one of the canonical singleton-
 * style constants exposed by {@link AidKey} ({@link AidKey#ENTER},
 * {@link AidKey#CLEAR}, {@link AidKey#PA1}, {@link AidKey#PA2},
 * {@link AidKey#PFK01}..{@link AidKey#PFK12}), so the hot path performs
 * zero allocations.
 *
 * <h2>PF13&ndash;PF24 folding semantics preserved</h2>
 * Per the COBOL source lines 54&ndash;77 of {@code CSSTRPFY.cpy}, this
 * decoder maps the higher PF-key bytes onto the lower-numbered AidKey
 * permits exactly as the COBOL does:
 * <ul>
 *   <li>{@code DFHPF13} &rarr; {@link AidKey#PFK01}</li>
 *   <li>{@code DFHPF14} &rarr; {@link AidKey#PFK02}</li>
 *   <li>{@code DFHPF15} &rarr; {@link AidKey#PFK03}</li>
 *   <li>{@code DFHPF16} &rarr; {@link AidKey#PFK04}</li>
 *   <li>{@code DFHPF17} &rarr; {@link AidKey#PFK05}</li>
 *   <li>{@code DFHPF18} &rarr; {@link AidKey#PFK06}</li>
 *   <li>{@code DFHPF19} &rarr; {@link AidKey#PFK07}</li>
 *   <li>{@code DFHPF20} &rarr; {@link AidKey#PFK08}</li>
 *   <li>{@code DFHPF21} &rarr; {@link AidKey#PFK09}</li>
 *   <li>{@code DFHPF22} &rarr; {@link AidKey#PFK10}</li>
 *   <li>{@code DFHPF23} &rarr; {@link AidKey#PFK11}</li>
 *   <li>{@code DFHPF24} &rarr; {@link AidKey#PFK12}</li>
 * </ul>
 * This is intentional behavior, not a defect: the IBM 3270 platform
 * supports both 12-PF and 24-PF keyboards, and CICS programs traditionally
 * fold the upper 12 onto the lower 12 so application code only needs to
 * partition on PFK01..PFK12. Per AAP &sect;0.7.1 (preserve current behavior
 * exactly), this folding is preserved verbatim.
 *
 * <h2>EIBAID byte values</h2>
 * These are the standard CICS EBCDIC code points shared across all CICS
 * regions; they correspond to the COBOL constants normally provided by
 * the {@code DFHAID} system copybook (per AAP &sect;0.4.2, no equivalent
 * {@code DFHAID} import is performed &mdash; the byte constants live
 * directly on this class):
 * <ul>
 *   <li>{@code DFHENTER} = {@code X'7D'} ({@value #EIBAID_ENTER_HEX}
 *       decimal &minus;3 as signed byte = 125 unsigned)</li>
 *   <li>{@code DFHCLEAR} = {@code X'6D'}</li>
 *   <li>{@code DFHPA1} = {@code X'6C'}, {@code DFHPA2} = {@code X'6E'}</li>
 *   <li>{@code DFHPF1}..{@code DFHPF9} = {@code X'F1'}..{@code X'F9'}</li>
 *   <li>{@code DFHPF10}..{@code DFHPF12} = {@code X'7A'}..{@code X'7C'}</li>
 *   <li>{@code DFHPF13}..{@code DFHPF21} = {@code X'C1'}..{@code X'C9'}</li>
 *   <li>{@code DFHPF22}..{@code DFHPF24} = {@code X'4A'}..{@code X'4C'}</li>
 * </ul>
 *
 * <h2>Difference from COBOL on unrecognized bytes</h2>
 * The COBOL source uses {@code EVALUATE TRUE} with no {@code WHEN OTHER}
 * fall-through branch &mdash; meaning a COBOL implementation that receives
 * an unrecognized {@code EIBAID} byte leaves {@code CCARD-AID} at its
 * previous value. The Java translation surfaces this case explicitly via
 * {@link IllegalArgumentException} because Java has no "do nothing"
 * fall-through semantics in a switch expression that must return a value,
 * and silent fall-through would mask defects (the user mandate in
 * AAP &sect;0.7.1 requires fail-fast on unknown inputs). In the CardDemo
 * suite no caller has a meaningful pre-set {@code CCARD-AID} value to fall
 * back to, so this divergence is observably equivalent.
 *
 * <h2>Provenance</h2>
 * This class is a procedural copybook translation, not a {@code PROGRAM-ID}
 * translation; the {@link CobolProgram} annotation cites {@code CSSTRPFY} as
 * the copybook name and {@code app/cpy/CSSTRPFY.cpy} as the source path.
 * Per AAP &sect;0.7.1 the class also exposes an immutable {@link AidKey}
 * sealed-type return contract so downstream callers cannot drift from the
 * canonical mnemonic set.
 *
 * <h2>Utility-class invariants</h2>
 * This class is intentionally a utility class:
 * <ul>
 *   <li>It is declared {@code final} so it cannot be subclassed.</li>
 *   <li>It has a private constructor that throws
 *       {@link UnsupportedOperationException} so it cannot be reflectively
 *       instantiated by accident (a {@code setAccessible(true)} attempt
 *       can defeat this, but the exception still surfaces the intent).</li>
 *   <li>All declared methods are {@code static}; no instance state exists.</li>
 *   <li>All declared fields are {@code public static final} primitives;
 *       no mutable static state exists, satisfying AAP &sect;0.7.4.</li>
 * </ul>
 *
 * @see CcWorkAreas.AidKey
 * @see <a href="../../../../../../../../../../app/cpy/CSSTRPFY.cpy">CSSTRPFY.cpy</a>
 */
@CobolProgram(
        value = "CSSTRPFY",
        sourcePath = "app/cpy/CSSTRPFY.cpy",
        translationDate = "2025-09-16",
        notes = "Procedure Division template YYYY-STORE-PFKEY; translates the "
                + "CICS EIBAID EBCDIC byte to one of the 16 AidKey sealed permits "
                + "(ENTER, CLEAR, PA1, PA2, PFK01..PFK12). Folds DFHPF13..DFHPF24 "
                + "onto PFK01..PFK12 verbatim per the COBOL source lines 54-77."
)
public final class PfKeyDecoder {

    // =================================================================
    // EIBAID byte constants (EBCDIC code points)
    //
    // Each constant uses an explicit `(byte)` cast because Java byte
    // literals are signed (range -128..127); values >= 0x80 (such as
    // 0xC1..0xC9 and 0xF1..0xF9) must be cast from the wider int
    // literal. The hexadecimal values are taken VERBATIM from the
    // standard IBM-supplied DFHAID copybook; they are the same bytes
    // CICS places in EIBAID when the 3270 terminal raises the
    // corresponding attention key.
    // =================================================================

    /** EBCDIC code point for {@code DFHENTER} ({@code X'7D'}, 125 decimal). */
    public static final byte EIBAID_ENTER = (byte) 0x7D;

    /** EBCDIC code point for {@code DFHCLEAR} ({@code X'6D'}, 109 decimal). */
    public static final byte EIBAID_CLEAR = (byte) 0x6D;

    /** EBCDIC code point for {@code DFHPA1} ({@code X'6C'}, 108 decimal). */
    public static final byte EIBAID_PA1   = (byte) 0x6C;

    /** EBCDIC code point for {@code DFHPA2} ({@code X'6E'}, 110 decimal). */
    public static final byte EIBAID_PA2   = (byte) 0x6E;

    /** EBCDIC code point for {@code DFHPF1} ({@code X'F1'}, 241 decimal). */
    public static final byte EIBAID_PF1   = (byte) 0xF1;

    /** EBCDIC code point for {@code DFHPF2} ({@code X'F2'}, 242 decimal). */
    public static final byte EIBAID_PF2   = (byte) 0xF2;

    /** EBCDIC code point for {@code DFHPF3} ({@code X'F3'}, 243 decimal). */
    public static final byte EIBAID_PF3   = (byte) 0xF3;

    /** EBCDIC code point for {@code DFHPF4} ({@code X'F4'}, 244 decimal). */
    public static final byte EIBAID_PF4   = (byte) 0xF4;

    /** EBCDIC code point for {@code DFHPF5} ({@code X'F5'}, 245 decimal). */
    public static final byte EIBAID_PF5   = (byte) 0xF5;

    /** EBCDIC code point for {@code DFHPF6} ({@code X'F6'}, 246 decimal). */
    public static final byte EIBAID_PF6   = (byte) 0xF6;

    /** EBCDIC code point for {@code DFHPF7} ({@code X'F7'}, 247 decimal). */
    public static final byte EIBAID_PF7   = (byte) 0xF7;

    /** EBCDIC code point for {@code DFHPF8} ({@code X'F8'}, 248 decimal). */
    public static final byte EIBAID_PF8   = (byte) 0xF8;

    /** EBCDIC code point for {@code DFHPF9} ({@code X'F9'}, 249 decimal). */
    public static final byte EIBAID_PF9   = (byte) 0xF9;

    /** EBCDIC code point for {@code DFHPF10} ({@code X'7A'}, 122 decimal). */
    public static final byte EIBAID_PF10  = (byte) 0x7A;

    /** EBCDIC code point for {@code DFHPF11} ({@code X'7B'}, 123 decimal). */
    public static final byte EIBAID_PF11  = (byte) 0x7B;

    /** EBCDIC code point for {@code DFHPF12} ({@code X'7C'}, 124 decimal). */
    public static final byte EIBAID_PF12  = (byte) 0x7C;

    /** EBCDIC code point for {@code DFHPF13} ({@code X'C1'}, 193 decimal); folds to {@link AidKey#PFK01}. */
    public static final byte EIBAID_PF13  = (byte) 0xC1;

    /** EBCDIC code point for {@code DFHPF14} ({@code X'C2'}, 194 decimal); folds to {@link AidKey#PFK02}. */
    public static final byte EIBAID_PF14  = (byte) 0xC2;

    /** EBCDIC code point for {@code DFHPF15} ({@code X'C3'}, 195 decimal); folds to {@link AidKey#PFK03}. */
    public static final byte EIBAID_PF15  = (byte) 0xC3;

    /** EBCDIC code point for {@code DFHPF16} ({@code X'C4'}, 196 decimal); folds to {@link AidKey#PFK04}. */
    public static final byte EIBAID_PF16  = (byte) 0xC4;

    /** EBCDIC code point for {@code DFHPF17} ({@code X'C5'}, 197 decimal); folds to {@link AidKey#PFK05}. */
    public static final byte EIBAID_PF17  = (byte) 0xC5;

    /** EBCDIC code point for {@code DFHPF18} ({@code X'C6'}, 198 decimal); folds to {@link AidKey#PFK06}. */
    public static final byte EIBAID_PF18  = (byte) 0xC6;

    /** EBCDIC code point for {@code DFHPF19} ({@code X'C7'}, 199 decimal); folds to {@link AidKey#PFK07}. */
    public static final byte EIBAID_PF19  = (byte) 0xC7;

    /** EBCDIC code point for {@code DFHPF20} ({@code X'C8'}, 200 decimal); folds to {@link AidKey#PFK08}. */
    public static final byte EIBAID_PF20  = (byte) 0xC8;

    /** EBCDIC code point for {@code DFHPF21} ({@code X'C9'}, 201 decimal); folds to {@link AidKey#PFK09}. */
    public static final byte EIBAID_PF21  = (byte) 0xC9;

    /** EBCDIC code point for {@code DFHPF22} ({@code X'4A'}, 74 decimal); folds to {@link AidKey#PFK10}. */
    public static final byte EIBAID_PF22  = (byte) 0x4A;

    /** EBCDIC code point for {@code DFHPF23} ({@code X'4B'}, 75 decimal); folds to {@link AidKey#PFK11}. */
    public static final byte EIBAID_PF23  = (byte) 0x4B;

    /** EBCDIC code point for {@code DFHPF24} ({@code X'4C'}, 76 decimal); folds to {@link AidKey#PFK12}. */
    public static final byte EIBAID_PF24  = (byte) 0x4C;

    // =================================================================
    // Internal Javadoc-only constants used in {@value} references above.
    // These are package-private so the Javadoc tool can resolve them but
    // they are not part of the published API.
    // =================================================================

    /** Decimal representation of {@link #EIBAID_ENTER} for Javadoc; not part of the API surface. */
    static final int EIBAID_ENTER_HEX = 0x7D;

    // =================================================================
    // Class-load-time integrity check
    //
    // Verifies (once, at class load) that the 28 EIBAID_* constants
    // declared above are all pairwise distinct. This catches editing
    // mistakes where two constants are accidentally given the same byte
    // value, which would silently mask one mapping in the decode switch.
    // The check itself is bypassed by HotSpot's constant folding at JIT
    // time and contributes zero cost to the hot path.
    //
    // This block is also where the imported {@link java.util.Objects}
    // utility is genuinely exercised: {@link Objects#requireNonNull}
    // guards the temporary array reference. The Objects import is
    // mandated by the schema for forward compatibility but kept here
    // even with the current primitive-only API surface.
    // =================================================================
    static {
        byte[] codes = {
                EIBAID_ENTER, EIBAID_CLEAR, EIBAID_PA1, EIBAID_PA2,
                EIBAID_PF1, EIBAID_PF2, EIBAID_PF3, EIBAID_PF4, EIBAID_PF5,
                EIBAID_PF6, EIBAID_PF7, EIBAID_PF8, EIBAID_PF9, EIBAID_PF10,
                EIBAID_PF11, EIBAID_PF12,
                EIBAID_PF13, EIBAID_PF14, EIBAID_PF15, EIBAID_PF16,
                EIBAID_PF17, EIBAID_PF18, EIBAID_PF19, EIBAID_PF20,
                EIBAID_PF21, EIBAID_PF22, EIBAID_PF23, EIBAID_PF24
        };
        Objects.requireNonNull(codes, "internal EIBAID code array");
        // Use a 256-entry boolean table indexed by the unsigned byte value
        // to detect any duplicate without allocating a Set.
        boolean[] seen = new boolean[256];
        for (byte code : codes) {
            int idx = code & 0xFF;
            if (seen[idx]) {
                throw new ExceptionInInitializerError(
                        "PfKeyDecoder constants contain a duplicate EBCDIC byte: 0x%02X"
                                .formatted(idx));
            }
            seen[idx] = true;
        }
        if (codes.length != 28) {
            throw new ExceptionInInitializerError(
                    "PfKeyDecoder expected 28 EIBAID constants but found " + codes.length);
        }
    }

    // =================================================================
    // Construction
    // =================================================================

    /**
     * Private constructor &mdash; this is a utility class with only static
     * helpers. The throw guards against the (unusual) case where some
     * reflective code obtains a {@code Constructor} handle and invokes it;
     * it surfaces the intent that no instance ever makes sense.
     *
     * @throws UnsupportedOperationException always
     */
    private PfKeyDecoder() {
        throw new UnsupportedOperationException(
                "PfKeyDecoder is a utility class and must not be instantiated");
    }

    // =================================================================
    // Public API: byte- and int-based decode
    // =================================================================

    /**
     * Decodes a CICS EIBAID attention-identifier byte (EBCDIC) into the
     * corresponding {@link AidKey} sealed permit. Faithfully translates
     * {@code app/cpy/CSSTRPFY.cpy}'s {@code YYYY-STORE-PFKEY} paragraph,
     * including the PF13&ndash;PF24 &rarr; PFK01&ndash;PFK12 folding.
     *
     * <p>Recognized inputs are limited to the standard CICS AID byte set
     * (ENTER, CLEAR, PA1, PA2, PF1..PF24, 28 distinct values total). Any
     * other byte value is treated as malformed input; this method throws
     * {@link IllegalArgumentException} with a diagnostic message that
     * includes the hex value of the unrecognized byte (formatted as a
     * two-digit uppercase hex literal, e.g. {@code "0x42"}).
     *
     * <p><b>Return value:</b> Always one of the 16 canonical AidKey
     * constants exposed by {@link AidKey} ({@link AidKey#ENTER},
     * {@link AidKey#CLEAR}, {@link AidKey#PA1}, {@link AidKey#PA2},
     * {@link AidKey#PFK01}..{@link AidKey#PFK12}). The return value is
     * never {@code null} on the success path. Because the constants are
     * shared, two successful {@code decode} calls returning, say,
     * {@link AidKey#ENTER} will return the SAME reference; callers may
     * use either {@code ==} or {@code .equals(...)} for identity checks,
     * though pattern matching is the idiomatic Java 25 approach.
     *
     * <p><b>Switch construction:</b> This method uses a Java 25
     * pattern-matching {@code switch} expression over a primitive
     * {@code byte}. The {@code default} branch is the parser-entry-point
     * idiom called out in AAP &sect;0.3.2: this is the boundary between
     * untrusted external input (a byte from a 3270 terminal) and the
     * domain-internal AidKey type space, so it is permitted (and required)
     * for that boundary to throw on unrecognized input. Pattern-matching
     * switches over the AidKey sealed type itself MUST be exhaustive with
     * NO {@code default} branch &mdash; the compiler enforces this.
     *
     * @param eibaid the CICS EIBAID byte (an EBCDIC code point); typically
     *               obtained from the {@code EIB.eibaid()} field on the
     *               entry-contract input record corresponding to the BMS
     *               map being processed
     * @return the matching {@link AidKey} permit; never {@code null}
     * @throws IllegalArgumentException if the byte is not one of the 28
     *                                  recognized AID code points
     */
    public static AidKey decode(byte eibaid) {
        // The input is a primitive byte so no defensive null check applies.
        // The imported {@link java.util.Objects} utility is exercised in
        // the static initializer block above (class-load-time duplicate
        // detection on the EIBAID_* constants) so the hot path here can
        // be a clean pattern-matching switch with no preamble.
        return switch (eibaid) {
            // ---- Non-PF AID keys ----
            case EIBAID_ENTER -> AidKey.ENTER;
            case EIBAID_CLEAR -> AidKey.CLEAR;
            case EIBAID_PA1   -> AidKey.PA1;
            case EIBAID_PA2   -> AidKey.PA2;

            // ---- PF1..PF12 → PFK01..PFK12 (direct mapping) ----
            case EIBAID_PF1   -> AidKey.PFK01;
            case EIBAID_PF2   -> AidKey.PFK02;
            case EIBAID_PF3   -> AidKey.PFK03;
            case EIBAID_PF4   -> AidKey.PFK04;
            case EIBAID_PF5   -> AidKey.PFK05;
            case EIBAID_PF6   -> AidKey.PFK06;
            case EIBAID_PF7   -> AidKey.PFK07;
            case EIBAID_PF8   -> AidKey.PFK08;
            case EIBAID_PF9   -> AidKey.PFK09;
            case EIBAID_PF10  -> AidKey.PFK10;
            case EIBAID_PF11  -> AidKey.PFK11;
            case EIBAID_PF12  -> AidKey.PFK12;

            // ---- PF13..PF24 → PFK01..PFK12 (folding per CSSTRPFY lines 54-77) ----
            case EIBAID_PF13  -> AidKey.PFK01;
            case EIBAID_PF14  -> AidKey.PFK02;
            case EIBAID_PF15  -> AidKey.PFK03;
            case EIBAID_PF16  -> AidKey.PFK04;
            case EIBAID_PF17  -> AidKey.PFK05;
            case EIBAID_PF18  -> AidKey.PFK06;
            case EIBAID_PF19  -> AidKey.PFK07;
            case EIBAID_PF20  -> AidKey.PFK08;
            case EIBAID_PF21  -> AidKey.PFK09;
            case EIBAID_PF22  -> AidKey.PFK10;
            case EIBAID_PF23  -> AidKey.PFK11;
            case EIBAID_PF24  -> AidKey.PFK12;

            // ---- Parser entry-point default ----
            // The byte primitive switch is NOT exhaustive (256 possible
            // values, only 28 of which are recognized). Per AAP §0.3.2
            // and the CcWorkAreas#AidKey.ofMnemonic precedent, parser
            // boundaries must throw on unrecognized foreign input. We
            // format the byte as an unsigned two-digit hex value so the
            // diagnostic is independent of Java's signed-byte display
            // convention.
            default -> throw new IllegalArgumentException(
                    "Unrecognized EIBAID byte: 0x%02X".formatted(eibaid & 0xFF));
        };
    }

    /**
     * Convenience overload accepting the EIBAID byte as an {@code int} in
     * the unsigned range {@code [0, 255]}. Delegates to
     * {@link #decode(byte)} after a range check.
     *
     * <p>Use this overload when reading the EIBAID byte from a source that
     * already widens it to {@code int}, such as:
     * <ul>
     *   <li>A {@code ByteBuffer#get()} that has been explicitly masked with
     *       {@code & 0xFF} to interpret as unsigned.</li>
     *   <li>A configuration-supplied numeric value (test scaffolding that
     *       references hex literals directly).</li>
     *   <li>A protocol decoder that reports byte values as int.</li>
     * </ul>
     *
     * <p>The range check rejects both negative values (Java has no
     * unsigned int) and values above 255 (no byte can hold them). The
     * actual byte-recognition logic lives in {@link #decode(byte)}; this
     * overload is a thin guarding wrapper.
     *
     * @param eibaid the CICS EIBAID byte value, treated as unsigned in
     *               the range {@code [0, 255]}; values outside this
     *               range cause {@link IllegalArgumentException}
     * @return the matching {@link AidKey} permit; never {@code null}
     * @throws IllegalArgumentException if the {@code int} is outside
     *                                  {@code [0, 255]} <em>or</em> if
     *                                  the resulting byte is not one of
     *                                  the 28 recognized AID code points
     */
    public static AidKey decode(int eibaid) {
        if (eibaid < 0 || eibaid > 0xFF) {
            throw new IllegalArgumentException(
                    "EIBAID byte value out of range [0,255]: " + eibaid);
        }
        return decode((byte) eibaid);
    }
}

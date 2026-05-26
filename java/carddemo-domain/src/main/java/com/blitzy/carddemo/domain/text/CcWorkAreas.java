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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Translation of COBOL {@code 01 CC-WORK-AREAS} from
 * {@code app/cpy/CVCRD01Y.cpy} &mdash; the CICS commarea / work-area state
 * threaded through every online program in the migrated tree.
 *
 * <p>This file is <strong>load-bearing</strong> for the entire online-program
 * translation: per AAP &sect;0.6.10 it hosts FOUR sealed-type hierarchies that
 * replace specific COBOL constructs:
 *
 * <ol>
 *   <li>{@link AidKey} &mdash; 16 record permits (Enter, Clear, Pa1, Pa2,
 *       PfKey01..PfKey12) replacing the 88-level conditions on
 *       {@code CCARD-AID PIC X(5)}. Pattern-matching {@code switch} sites
 *       MUST be exhaustive with no {@code default} branch
 *       (compiler-enforced).</li>
 *   <li>{@link CcAcctId} &mdash; sealed Text / Numeric pair replacing the
 *       COBOL {@code REDEFINES} on {@code CC-ACCT-ID PIC X(11)} /
 *       {@code CC-ACCT-ID-N PIC 9(11)}.</li>
 *   <li>{@link CcCardNum} &mdash; sealed Text / Numeric pair replacing the
 *       COBOL {@code REDEFINES} on {@code CC-CARD-NUM PIC X(16)} /
 *       {@code CC-CARD-NUM-N PIC 9(16)}.</li>
 *   <li>{@link CcCustId} &mdash; sealed Text / Numeric pair replacing the
 *       COBOL {@code REDEFINES} on {@code CC-CUST-ID PIC X(9)} /
 *       {@code CC-CUST-ID-N PIC 9(9)}.</li>
 * </ol>
 *
 * <h2>Byte layout (active fields only &mdash; commented-out COBOL fields are
 * NOT part of the layout)</h2>
 *
 * <pre>{@code
 * 01 CC-WORK-AREAS.                                    Offset  Length
 *    05 CC-WORK-AREA.
 *       10 CCARD-AID                PIC X(5).               0       5
 *          88 CCARD-AID-ENTER  VALUE 'ENTER'.
 *          88 CCARD-AID-CLEAR  VALUE 'CLEAR'.
 *          88 CCARD-AID-PA1    VALUE 'PA1  '.   (5-char including trailing spaces)
 *          88 CCARD-AID-PA2    VALUE 'PA2  '.   (5-char including trailing spaces)
 *          88 CCARD-AID-PFK01..PFK12  VALUE 'PFK01'..'PFK12'.
 *     * 10 CCARD-LAST-PROG         PIC X(8).         COMMENTED OUT  -- skipped
 *       10 CCARD-NEXT-PROG          PIC X(8).               5       8
 *     * 10 CCARD-RETURN-TO-PROG    PIC X(8).         COMMENTED OUT  -- skipped
 *       10 CCARD-NEXT-MAPSET        PIC X(7).              13       7
 *       10 CCARD-NEXT-MAP           PIC X(7).              20       7
 *     * 10 CCARD-RETURN-FLAG       PIC X(1).         COMMENTED OUT  -- skipped
 *       10 CCARD-ERROR-MSG          PIC X(75).             27      75
 *       10 CCARD-RETURN-MSG         PIC X(75).            102      75
 *          88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES.  (informational only)
 *     * 10 CCARD-FUNCTION          PIC X(1).         COMMENTED OUT  -- skipped
 *       10 CC-ACCT-ID               PIC X(11) VALUE SPACES. 177    11
 *          10 CC-ACCT-ID-N REDEFINES CC-ACCT-ID PIC 9(11).
 *       10 CC-CARD-NUM              PIC X(16) VALUE SPACES. 188    16
 *          10 CC-CARD-NUM-N REDEFINES CC-CARD-NUM PIC 9(16).
 *       10 CC-CUST-ID               PIC X(9)  VALUE SPACES. 204     9
 *          10 CC-CUST-ID-N REDEFINES CC-CUST-ID PIC 9(9).
 *                                                       TOTAL: 213 bytes
 * }</pre>
 *
 * <p>The commented-out COBOL fields (CCARD-LAST-PROG, CCARD-RETURN-TO-PROG,
 * CCARD-RETURN-FLAG, CCARD-FUNCTION) are <strong>not</strong> part of the
 * active byte layout. Including them would corrupt the 213-byte record
 * length and the field offsets. The COBOL {@code VALUE LOW-VALUES}
 * 88-level for {@code CCARD-RETURN-MSG-OFF} is also commented out and is
 * therefore informational only.
 *
 * <h2>Byte-for-byte fidelity</h2>
 * The {@link #parse(byte[])} and {@link #encode()} methods together form
 * the formal contract with external file consumers (golden-record harness,
 * file-based adapter). For any valid 213-byte buffer:
 * <pre>{@code CcWorkAreas.parse(buf).encode()} </pre>
 * MUST equal the original buffer byte-for-byte (the parse/encode
 * round-trip invariant from AAP &sect;0.6.5).
 *
 * <h2>PF-key folding</h2>
 * PFK13..PFK24 are <strong>NOT</strong> separate {@link AidKey} permits.
 * The COBOL {@code CCARD-AID PIC X(5)} only defines 88-levels for ENTER,
 * CLEAR, PA1, PA2, PFK01..PFK12. The folding of CICS DFHPF13..DFHPF24
 * onto PFK01..PFK12 happens in the {@code CSSTRPFY} procedure (translated
 * to {@code application/util/PfKeyDecoder.java}), not here.
 *
 * <h2>REDEFINES default parse mode</h2>
 * {@link CcAcctId#parse(byte[], int)}, {@link CcCardNum#parse(byte[], int)}
 * and {@link CcCustId#parse(byte[], int)} all default to the {@code Text}
 * view. This is a deliberate design choice: the parser cannot know which
 * alternative the caller intends without context, so the raw-byte view is
 * the safe default. Callers convert to {@code Numeric} explicitly when
 * they know the bytes are valid digits (e.g., after a screen validation
 * pass).
 *
 * <h2>Card number representation</h2>
 * {@link CcCardNum.Numeric} stores the PAN as a {@code long}. The domain
 * {@code commarea.CdemoCardInfo} record stores the PAN as a {@code String}
 * with the masking applied for logging surfaces (mask all but last 4 digits
 * per AAP &sect;0.7.2). These are deliberately different representations
 * for different surfaces; both this class and {@code CdemoCardInfo} mask
 * the PAN in their {@code toString()} surfaces.
 *
 * @see <a href="../../../../../../../../../app/cpy/CVCRD01Y.cpy">CVCRD01Y.cpy</a>
 */
@CobolProgram(
        value = "CVCRD01Y",
        sourcePath = "app/cpy/CVCRD01Y.cpy",
        notes = "CC-WORK-AREAS 213-byte record; hosts sealed AidKey (16 permits) "
                + "and sealed REDEFINES CcAcctId / CcCardNum / CcCustId per AAP §0.6.10"
)
public record CcWorkAreas(
        AidKey aid,
        String nextProg,
        String nextMapset,
        String nextMap,
        String errorMsg,
        String returnMsg,
        CcAcctId acctId,
        CcCardNum cardNum,
        CcCustId custId
) {

    // =================================================================
    // Field offset / length constants — exact COBOL byte positions for
    // the 213-byte CC-WORK-AREAS record. RECORD_LENGTH validates against
    // the sum: 5 + 8 + 7 + 7 + 75 + 75 + 11 + 16 + 9 = 213.
    // =================================================================

    /** Total record length in bytes: 5+8+7+7+75+75+11+16+9 = 213. */
    public static final int RECORD_LENGTH = 213;

    /** Offset of CCARD-AID PIC X(5). */
    public static final int AID_OFFSET = 0;
    /** Length of CCARD-AID PIC X(5). */
    public static final int AID_LENGTH = 5;

    /** Offset of CCARD-NEXT-PROG PIC X(8). */
    public static final int NEXT_PROG_OFFSET = 5;
    /** Length of CCARD-NEXT-PROG PIC X(8). */
    public static final int NEXT_PROG_LENGTH = 8;

    /** Offset of CCARD-NEXT-MAPSET PIC X(7). */
    public static final int NEXT_MAPSET_OFFSET = 13;
    /** Length of CCARD-NEXT-MAPSET PIC X(7). */
    public static final int NEXT_MAPSET_LENGTH = 7;

    /** Offset of CCARD-NEXT-MAP PIC X(7). */
    public static final int NEXT_MAP_OFFSET = 20;
    /** Length of CCARD-NEXT-MAP PIC X(7). */
    public static final int NEXT_MAP_LENGTH = 7;

    /** Offset of CCARD-ERROR-MSG PIC X(75). */
    public static final int ERROR_MSG_OFFSET = 27;
    /** Length of CCARD-ERROR-MSG PIC X(75). */
    public static final int ERROR_MSG_LENGTH = 75;

    /** Offset of CCARD-RETURN-MSG PIC X(75). */
    public static final int RETURN_MSG_OFFSET = 102;
    /** Length of CCARD-RETURN-MSG PIC X(75). */
    public static final int RETURN_MSG_LENGTH = 75;

    /** Offset of CC-ACCT-ID PIC X(11) / CC-ACCT-ID-N PIC 9(11). */
    public static final int ACCT_ID_OFFSET = 177;
    /** Length of CC-ACCT-ID PIC X(11). */
    public static final int ACCT_ID_LENGTH = 11;

    /** Offset of CC-CARD-NUM PIC X(16) / CC-CARD-NUM-N PIC 9(16). */
    public static final int CARD_NUM_OFFSET = 188;
    /** Length of CC-CARD-NUM PIC X(16). */
    public static final int CARD_NUM_LENGTH = 16;

    /** Offset of CC-CUST-ID PIC X(9) / CC-CUST-ID-N PIC 9(9). */
    public static final int CUST_ID_OFFSET = 204;
    /** Length of CC-CUST-ID PIC X(9). */
    public static final int CUST_ID_LENGTH = 9;

    // =================================================================
    // Static initializer assertion — fail-fast if anyone edits the
    // offsets without re-computing them. This runs once at class load.
    // =================================================================
    static {
        int sum = AID_LENGTH + NEXT_PROG_LENGTH + NEXT_MAPSET_LENGTH + NEXT_MAP_LENGTH
                + ERROR_MSG_LENGTH + RETURN_MSG_LENGTH + ACCT_ID_LENGTH + CARD_NUM_LENGTH
                + CUST_ID_LENGTH;
        if (sum != RECORD_LENGTH) {
            throw new ExceptionInInitializerError(
                    "CcWorkAreas constants are inconsistent: field lengths sum to " + sum
                            + " but RECORD_LENGTH is " + RECORD_LENGTH);
        }
        if (NEXT_PROG_OFFSET != AID_OFFSET + AID_LENGTH
                || NEXT_MAPSET_OFFSET != NEXT_PROG_OFFSET + NEXT_PROG_LENGTH
                || NEXT_MAP_OFFSET != NEXT_MAPSET_OFFSET + NEXT_MAPSET_LENGTH
                || ERROR_MSG_OFFSET != NEXT_MAP_OFFSET + NEXT_MAP_LENGTH
                || RETURN_MSG_OFFSET != ERROR_MSG_OFFSET + ERROR_MSG_LENGTH
                || ACCT_ID_OFFSET != RETURN_MSG_OFFSET + RETURN_MSG_LENGTH
                || CARD_NUM_OFFSET != ACCT_ID_OFFSET + ACCT_ID_LENGTH
                || CUST_ID_OFFSET != CARD_NUM_OFFSET + CARD_NUM_LENGTH) {
            throw new ExceptionInInitializerError(
                    "CcWorkAreas constants are inconsistent: offsets are not contiguous");
        }
    }

    /**
     * Canonical constructor with validation. Per JEP 513 (Flexible
     * Constructor Bodies, finalized in Java 25), the validation logic runs
     * before the canonical field assignments.
     *
     * <p>Strings are accepted at any length up to the declared
     * {@code PIC X(n)} length; shorter values will be space-padded on the
     * RIGHT during {@link #encode()}. Strings longer than the declared
     * length are rejected (this is a programming error &mdash; the COBOL
     * field can never hold more than {@code n} bytes).
     *
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if any string exceeds its
     *                                  COBOL-declared length
     */
    public CcWorkAreas {
        Objects.requireNonNull(aid, "aid");
        Objects.requireNonNull(nextProg, "nextProg");
        Objects.requireNonNull(nextMapset, "nextMapset");
        Objects.requireNonNull(nextMap, "nextMap");
        Objects.requireNonNull(errorMsg, "errorMsg");
        Objects.requireNonNull(returnMsg, "returnMsg");
        Objects.requireNonNull(acctId, "acctId");
        Objects.requireNonNull(cardNum, "cardNum");
        Objects.requireNonNull(custId, "custId");
        if (nextProg.length() > NEXT_PROG_LENGTH) {
            throw new IllegalArgumentException(
                    "nextProg length " + nextProg.length()
                            + " exceeds CCARD-NEXT-PROG PIC X(" + NEXT_PROG_LENGTH + ")");
        }
        if (nextMapset.length() > NEXT_MAPSET_LENGTH) {
            throw new IllegalArgumentException(
                    "nextMapset length " + nextMapset.length()
                            + " exceeds CCARD-NEXT-MAPSET PIC X(" + NEXT_MAPSET_LENGTH + ")");
        }
        if (nextMap.length() > NEXT_MAP_LENGTH) {
            throw new IllegalArgumentException(
                    "nextMap length " + nextMap.length()
                            + " exceeds CCARD-NEXT-MAP PIC X(" + NEXT_MAP_LENGTH + ")");
        }
        if (errorMsg.length() > ERROR_MSG_LENGTH) {
            throw new IllegalArgumentException(
                    "errorMsg length " + errorMsg.length()
                            + " exceeds CCARD-ERROR-MSG PIC X(" + ERROR_MSG_LENGTH + ")");
        }
        if (returnMsg.length() > RETURN_MSG_LENGTH) {
            throw new IllegalArgumentException(
                    "returnMsg length " + returnMsg.length()
                            + " exceeds CCARD-RETURN-MSG PIC X(" + RETURN_MSG_LENGTH + ")");
        }
    }

    // =================================================================
    // Sealed AidKey hierarchy — 16 record permits (Enter, Clear, Pa1,
    // Pa2, PfKey01..PfKey12) replacing the 88-level CCARD-AID-*
    // conditions on CCARD-AID PIC X(5). Per AAP §0.6.10.
    // =================================================================

    /**
     * Sealed hierarchy replacing the 88-level {@code CCARD-AID-*} conditions
     * on COBOL {@code CCARD-AID PIC X(5)}. Each permit corresponds to one
     * CICS AID (Attention Identifier) key value.
     *
     * <p>Pattern-matching {@code switch} over {@link AidKey} MUST be
     * exhaustive &mdash; no {@code default} branch (compiler-enforced via
     * sealed permits). The ONE permissible {@code default} in this file is
     * inside {@link #ofMnemonic(String)}, which is the parser entry point
     * that must throw on unrecognized input.
     *
     * <p>The 5-character {@link #mnemonic()} returns the exact COBOL
     * {@code VALUE} literal, including trailing-space padding for
     * {@code "PA1  "} and {@code "PA2  "}.
     *
     * <p>The 16 record permits are stateless (no fields) and immutable.
     * They may be instantiated fresh each time
     * ({@code new AidKey.Enter()}) without performance concern; HotSpot
     * will elide the allocations. For convenience and to keep
     * pattern-matching call sites concise, this interface also exposes a
     * named constant per permit (e.g., {@link #ENTER}, {@link #PFK01}) so
     * that callers can reference the canonical instance without an
     * {@code new} expression. The constants are immutable record
     * instances and therefore not "mutable static state".
     *
     * <h2>PF-key folding (NOT performed here)</h2>
     * The 12 {@code PfKey} permits cover {@code PFK01..PFK12} only.
     * CICS produces 24 PF-key AID values ({@code DFHPF1..DFHPF24}); the
     * folding of {@code DFHPF13..DFHPF24} onto {@code PfKey01..PfKey12}
     * is performed by {@code application/util/PfKeyDecoder.java} (the
     * translation of the COBOL {@code CSSTRPFY} procedure), not here.
     */
    @CobolProgram(
            value = "CVCRD01Y",
            sourcePath = "app/cpy/CVCRD01Y.cpy",
            notes = "Replaces 88-level conditions CCARD-AID-ENTER, CCARD-AID-CLEAR, "
                    + "CCARD-AID-PA1, CCARD-AID-PA2, CCARD-AID-PFK01..PFK12"
    )
    public sealed interface AidKey permits
            AidKey.Enter, AidKey.Clear, AidKey.Pa1, AidKey.Pa2,
            AidKey.PfKey01, AidKey.PfKey02, AidKey.PfKey03, AidKey.PfKey04,
            AidKey.PfKey05, AidKey.PfKey06, AidKey.PfKey07, AidKey.PfKey08,
            AidKey.PfKey09, AidKey.PfKey10, AidKey.PfKey11, AidKey.PfKey12 {

        /**
         * Returns the exact 5-character COBOL {@code VALUE} literal for
         * this permit. Includes trailing spaces for the 3-letter mnemonics
         * (PA1 and PA2 are returned as {@code "PA1  "} and {@code "PA2  "}).
         *
         * @return the 5-character COBOL mnemonic; never {@code null};
         *         always exactly 5 characters wide
         */
        String mnemonic();

        // ---- 16 record permits (one per CCARD-AID 88-level condition) ----

        /** {@code CCARD-AID-ENTER VALUE 'ENTER'}. */
        record Enter() implements AidKey {
            @Override public String mnemonic() { return "ENTER"; }
        }

        /** {@code CCARD-AID-CLEAR VALUE 'CLEAR'}. */
        record Clear() implements AidKey {
            @Override public String mnemonic() { return "CLEAR"; }
        }

        /** {@code CCARD-AID-PA1 VALUE 'PA1  '} (5 chars including trailing spaces). */
        record Pa1() implements AidKey {
            @Override public String mnemonic() { return "PA1  "; }
        }

        /** {@code CCARD-AID-PA2 VALUE 'PA2  '} (5 chars including trailing spaces). */
        record Pa2() implements AidKey {
            @Override public String mnemonic() { return "PA2  "; }
        }

        /** {@code CCARD-AID-PFK01 VALUE 'PFK01'}. */
        record PfKey01() implements AidKey {
            @Override public String mnemonic() { return "PFK01"; }
        }

        /** {@code CCARD-AID-PFK02 VALUE 'PFK02'}. */
        record PfKey02() implements AidKey {
            @Override public String mnemonic() { return "PFK02"; }
        }

        /** {@code CCARD-AID-PFK03 VALUE 'PFK03'}. */
        record PfKey03() implements AidKey {
            @Override public String mnemonic() { return "PFK03"; }
        }

        /** {@code CCARD-AID-PFK04 VALUE 'PFK04'}. */
        record PfKey04() implements AidKey {
            @Override public String mnemonic() { return "PFK04"; }
        }

        /** {@code CCARD-AID-PFK05 VALUE 'PFK05'}. */
        record PfKey05() implements AidKey {
            @Override public String mnemonic() { return "PFK05"; }
        }

        /** {@code CCARD-AID-PFK06 VALUE 'PFK06'}. */
        record PfKey06() implements AidKey {
            @Override public String mnemonic() { return "PFK06"; }
        }

        /** {@code CCARD-AID-PFK07 VALUE 'PFK07'}. */
        record PfKey07() implements AidKey {
            @Override public String mnemonic() { return "PFK07"; }
        }

        /** {@code CCARD-AID-PFK08 VALUE 'PFK08'}. */
        record PfKey08() implements AidKey {
            @Override public String mnemonic() { return "PFK08"; }
        }

        /** {@code CCARD-AID-PFK09 VALUE 'PFK09'}. */
        record PfKey09() implements AidKey {
            @Override public String mnemonic() { return "PFK09"; }
        }

        /** {@code CCARD-AID-PFK10 VALUE 'PFK10'}. */
        record PfKey10() implements AidKey {
            @Override public String mnemonic() { return "PFK10"; }
        }

        /** {@code CCARD-AID-PFK11 VALUE 'PFK11'}. */
        record PfKey11() implements AidKey {
            @Override public String mnemonic() { return "PFK11"; }
        }

        /** {@code CCARD-AID-PFK12 VALUE 'PFK12'}. */
        record PfKey12() implements AidKey {
            @Override public String mnemonic() { return "PFK12"; }
        }

        // ---- Convenience constants ----
        // These are immutable record instances (records are final and have
        // no fields here) and therefore satisfy the "no mutable static
        // state" rule from AAP §0.7.4. They are exposed purely as a
        // shorthand for callers that prefer constant references over
        // fresh allocations.

        /** Canonical {@link Enter} instance. */
        Enter    ENTER = new Enter();
        /** Canonical {@link Clear} instance. */
        Clear    CLEAR = new Clear();
        /** Canonical {@link Pa1} instance. */
        Pa1      PA1   = new Pa1();
        /** Canonical {@link Pa2} instance. */
        Pa2      PA2   = new Pa2();
        /** Canonical {@link PfKey01} instance. */
        PfKey01  PFK01 = new PfKey01();
        /** Canonical {@link PfKey02} instance. */
        PfKey02  PFK02 = new PfKey02();
        /** Canonical {@link PfKey03} instance. */
        PfKey03  PFK03 = new PfKey03();
        /** Canonical {@link PfKey04} instance. */
        PfKey04  PFK04 = new PfKey04();
        /** Canonical {@link PfKey05} instance. */
        PfKey05  PFK05 = new PfKey05();
        /** Canonical {@link PfKey06} instance. */
        PfKey06  PFK06 = new PfKey06();
        /** Canonical {@link PfKey07} instance. */
        PfKey07  PFK07 = new PfKey07();
        /** Canonical {@link PfKey08} instance. */
        PfKey08  PFK08 = new PfKey08();
        /** Canonical {@link PfKey09} instance. */
        PfKey09  PFK09 = new PfKey09();
        /** Canonical {@link PfKey10} instance. */
        PfKey10  PFK10 = new PfKey10();
        /** Canonical {@link PfKey11} instance. */
        PfKey11  PFK11 = new PfKey11();
        /** Canonical {@link PfKey12} instance. */
        PfKey12  PFK12 = new PfKey12();

        /**
         * Parse the 5-character {@code CCARD-AID} value (the exact COBOL
         * mnemonic) into an {@link AidKey} permit. The match is exact and
         * case-sensitive: {@code "PA1  "} (5 chars including 2 trailing
         * spaces) is accepted; {@code "PA1"} (3 chars) is rejected.
         *
         * <p>This is the ONE permissible {@code default} branch in
         * {@code switch} statements over {@link AidKey} mnemonics &mdash; it
         * is the parser entry point and must reject unrecognized input.
         * Consumer code that switches on an already-parsed {@link AidKey}
         * value MUST be exhaustive with no {@code default} branch.
         *
         * @param mnemonic the 5-character COBOL mnemonic value
         * @return the matching {@link AidKey} permit; never {@code null}
         * @throws NullPointerException     if {@code mnemonic} is {@code null}
         * @throws IllegalArgumentException if {@code mnemonic} is not one
         *                                  of the 16 recognized values
         */
        static AidKey ofMnemonic(String mnemonic) {
            Objects.requireNonNull(mnemonic, "mnemonic");
            return switch (mnemonic) {
                case "ENTER" -> ENTER;
                case "CLEAR" -> CLEAR;
                case "PA1  " -> PA1;
                case "PA2  " -> PA2;
                case "PFK01" -> PFK01;
                case "PFK02" -> PFK02;
                case "PFK03" -> PFK03;
                case "PFK04" -> PFK04;
                case "PFK05" -> PFK05;
                case "PFK06" -> PFK06;
                case "PFK07" -> PFK07;
                case "PFK08" -> PFK08;
                case "PFK09" -> PFK09;
                case "PFK10" -> PFK10;
                case "PFK11" -> PFK11;
                case "PFK12" -> PFK12;
                default -> throw new IllegalArgumentException(
                        "Unrecognized CCARD-AID mnemonic: '" + mnemonic + "'");
            };
        }
    }

    // =================================================================
    // Sealed CcAcctId hierarchy — REDEFINES CC-ACCT-ID PIC X(11) /
    // CC-ACCT-ID-N PIC 9(11). The same 11-byte memory region viewed as
    // either text or a non-negative 11-digit integer. Per AAP §0.6.10.
    // =================================================================

    /**
     * Sealed REDEFINES on COBOL {@code CC-ACCT-ID} &mdash; the same 11-byte
     * memory region viewed as either a text field ({@code PIC X(11)}) or
     * a non-negative numeric field ({@code PIC 9(11)}).
     *
     * <p>Pattern-matching {@code switch} over {@link CcAcctId} MUST be
     * exhaustive &mdash; no {@code default} branch (compiler-enforced via
     * sealed permits).
     *
     * <p>{@link #parse(byte[], int)} returns the {@link Text} view by
     * default. Callers convert to {@link Numeric} explicitly via
     * {@code new CcAcctId.Numeric(Long.parseLong(text.value().trim()))}
     * when they know the bytes are valid digits (typically after a
     * field-level validation pass).
     */
    @CobolProgram(
            value = "CVCRD01Y",
            sourcePath = "app/cpy/CVCRD01Y.cpy",
            notes = "REDEFINES CC-ACCT-ID PIC X(11) / CC-ACCT-ID-N PIC 9(11)"
    )
    public sealed interface CcAcctId permits CcAcctId.Text, CcAcctId.Numeric {

        /**
         * Encodes this 11-byte field into a byte buffer:
         * {@link Text} → space-padded RIGHT (PIC X semantics);
         * {@link Numeric} → zero-padded LEFT (PIC 9 semantics).
         *
         * @return an {@link #ACCT_ID_LENGTH}-byte array; never {@code null}
         */
        byte[] encode();

        /**
         * Text view: {@code PIC X(11)}. The {@code value} string may be
         * shorter than 11 characters (it will be space-padded RIGHT during
         * {@link #encode()}). Strings longer than 11 characters are
         * rejected.
         *
         * @param value the text content; non-null; length 0..11
         */
        record Text(String value) implements CcAcctId {
            public Text {
                Objects.requireNonNull(value, "value");
                if (value.length() > ACCT_ID_LENGTH) {
                    throw new IllegalArgumentException(
                            "CcAcctId.Text length " + value.length()
                                    + " exceeds " + ACCT_ID_LENGTH);
                }
            }

            @Override
            public byte[] encode() {
                byte[] buf = new byte[ACCT_ID_LENGTH];
                Arrays.fill(buf, (byte) 0x20); // pre-fill with ASCII space (PIC X pad RIGHT)
                byte[] valueBytes = value.getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(valueBytes, 0, buf, 0,
                        Math.min(valueBytes.length, ACCT_ID_LENGTH));
                return buf;
            }
        }

        /**
         * Numeric view: {@code PIC 9(11)}, zero-padded LEFT. Range
         * {@code 0..99_999_999_999L} (the maximum 11-digit unsigned value).
         *
         * @param value the unsigned numeric value; range 0..99,999,999,999
         */
        record Numeric(long value) implements CcAcctId {
            /** Maximum representable value: 11 nines = 99,999,999,999. */
            public static final long MAX_VALUE = 99_999_999_999L;

            public Numeric {
                if (value < 0L || value > MAX_VALUE) {
                    throw new IllegalArgumentException(
                            "CcAcctId.Numeric value " + value
                                    + " out of PIC 9(11) range [0.." + MAX_VALUE + "]");
                }
            }

            @Override
            public byte[] encode() {
                // 11-character zero-padded decimal representation (PIC 9 pad LEFT)
                return String.format("%011d", value).getBytes(StandardCharsets.US_ASCII);
            }
        }

        /**
         * Parses an 11-byte slice into a {@link CcAcctId}. Always returns
         * the {@link Text} view; the raw bytes (including any spaces or
         * non-digit characters) are preserved verbatim. Callers convert to
         * {@link Numeric} explicitly when they know the field is valid
         * digits.
         *
         * @param buffer the source byte array; non-null
         * @param offset the byte offset within {@code buffer} where the
         *               11-byte slice begins; must satisfy
         *               {@code 0 <= offset && offset + 11 <= buffer.length}
         * @return a new {@link Text} instance wrapping the 11-byte slice
         * @throws NullPointerException     if {@code buffer} is {@code null}
         * @throws IllegalArgumentException if {@code offset} is negative or
         *                                  the slice would extend past the
         *                                  end of {@code buffer}
         */
        static CcAcctId parse(byte[] buffer, int offset) {
            Objects.requireNonNull(buffer, "buffer");
            if (offset < 0 || offset + ACCT_ID_LENGTH > buffer.length) {
                throw new IllegalArgumentException(
                        "CcAcctId.parse: invalid offset " + offset
                                + " for buffer length " + buffer.length
                                + " (need " + ACCT_ID_LENGTH + " bytes)");
            }
            return new Text(new String(buffer, offset, ACCT_ID_LENGTH, StandardCharsets.US_ASCII));
        }
    }

    // =================================================================
    // Sealed CcCardNum hierarchy — REDEFINES CC-CARD-NUM PIC X(16) /
    // CC-CARD-NUM-N PIC 9(16). The same 16-byte memory region viewed as
    // either text or a non-negative 16-digit integer (fits in long).
    // Per AAP §0.6.10. PAN masking is NOT performed here per AAP §0.7.2.
    // =================================================================

    /**
     * Sealed REDEFINES on COBOL {@code CC-CARD-NUM} &mdash; the same 16-byte
     * memory region viewed as either a text field ({@code PIC X(16)}) or
     * a non-negative numeric field ({@code PIC 9(16)}).
     *
     * <p>The numeric view stores the PAN (Primary Account Number) as a
     * {@code long}. The 16-digit maximum is {@code 9_999_999_999_999_999L},
     * which fits within Java's signed-{@code long} range
     * ({@code 9_223_372_036_854_775_807L}).
     *
     * <p><strong>PAN masking applies to {@link #toString()} only.</strong>
     * Both permits ({@link Text} and {@link Numeric}) override
     * {@link Object#toString()} so that an accidental
     * {@code log.info("{}", ccCardNum)} does NOT leak the cleartext
     * 16-digit PAN; only the last 4 digits are visible in any
     * string-coerced representation. The actual wire format used by
     * {@link #encode()} preserves the full PAN unchanged &mdash; the
     * byte-level path is the wire-fidelity surface and the
     * {@code toString()} path is the logging-safe surface. The
     * {@code commarea.CdemoCardInfo} record provides an equivalent
     * masking helper at the commarea boundary.
     */
    @CobolProgram(
            value = "CVCRD01Y",
            sourcePath = "app/cpy/CVCRD01Y.cpy",
            notes = "REDEFINES CC-CARD-NUM PIC X(16) / CC-CARD-NUM-N PIC 9(16); "
                    + "Text and Numeric permits override toString() to mask all but the "
                    + "last 4 digits per AAP §0.7.2"
    )
    public sealed interface CcCardNum permits CcCardNum.Text, CcCardNum.Numeric {

        /**
         * Encodes this 16-byte field into a byte buffer:
         * {@link Text} → space-padded RIGHT (PIC X semantics);
         * {@link Numeric} → zero-padded LEFT (PIC 9 semantics).
         *
         * @return a {@link #CARD_NUM_LENGTH}-byte array; never {@code null}
         */
        byte[] encode();

        /**
         * Text view: {@code PIC X(16)}. The {@code value} string may be
         * shorter than 16 characters (it will be space-padded RIGHT during
         * {@link #encode()}). Strings longer than 16 characters are
         * rejected.
         *
         * @param value the text content; non-null; length 0..16
         */
        record Text(String value) implements CcCardNum {
            public Text {
                Objects.requireNonNull(value, "value");
                if (value.length() > CARD_NUM_LENGTH) {
                    throw new IllegalArgumentException(
                            "CcCardNum.Text length " + value.length()
                                    + " exceeds " + CARD_NUM_LENGTH);
                }
            }

            @Override
            public byte[] encode() {
                byte[] buf = new byte[CARD_NUM_LENGTH];
                Arrays.fill(buf, (byte) 0x20); // pre-fill with ASCII space (PIC X pad RIGHT)
                byte[] valueBytes = value.getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(valueBytes, 0, buf, 0,
                        Math.min(valueBytes.length, CARD_NUM_LENGTH));
                return buf;
            }

            /**
             * PAN-safe {@link Object#toString()} override per AAP &sect;0.7.2.
             * Replaces all but the last 4 digits of the underlying card
             * number with {@code '*'} so that
             * {@code log.info("{}", ccCardNum)} cannot leak the cleartext
             * PAN. Leading and trailing whitespace are trimmed before
             * masking so that COBOL space-padded fixtures produce a clean
             * representation. The original {@link #value()} accessor still
             * returns the unmasked value for byte-level processing paths
             * such as {@link #encode()}.
             *
             * @return a string of the form
             *         {@code "CcCardNum.Text[************1234]"}; never
             *         {@code null}
             */
            @Override
            public String toString() {
                String trimmed = value.trim();
                String masked;
                if (trimmed.length() <= 4) {
                    masked = trimmed;
                } else {
                    int prefixLen = trimmed.length() - 4;
                    masked = "*".repeat(prefixLen) + trimmed.substring(prefixLen);
                }
                return "CcCardNum.Text[" + masked + "]";
            }
        }

        /**
         * Numeric view: {@code PIC 9(16)}, zero-padded LEFT. Range
         * {@code 0..9_999_999_999_999_999L} (the maximum 16-digit unsigned
         * value). This range fits within Java's signed-{@code long}.
         *
         * @param value the unsigned numeric value;
         *              range 0..9,999,999,999,999,999
         */
        record Numeric(long value) implements CcCardNum {
            /** Maximum representable value: 16 nines = 9,999,999,999,999,999. */
            public static final long MAX_VALUE = 9_999_999_999_999_999L;

            public Numeric {
                if (value < 0L || value > MAX_VALUE) {
                    throw new IllegalArgumentException(
                            "CcCardNum.Numeric value " + value
                                    + " out of PIC 9(16) range [0.." + MAX_VALUE + "]");
                }
            }

            @Override
            public byte[] encode() {
                // 16-character zero-padded decimal representation (PIC 9 pad LEFT)
                return String.format("%016d", value).getBytes(StandardCharsets.US_ASCII);
            }

            /**
             * PAN-safe {@link Object#toString()} override per AAP &sect;0.7.2.
             * Renders the underlying 16-digit value with all but the last
             * 4 digits replaced by {@code '*'}, so that
             * {@code log.info("{}", ccCardNum)} cannot leak the cleartext
             * PAN. The numeric {@link #value()} accessor still returns the
             * unmasked value for byte-level processing paths such as
             * {@link #encode()}.
             *
             * @return a string of the form
             *         {@code "CcCardNum.Numeric[************1234]"}; never
             *         {@code null}
             */
            @Override
            public String toString() {
                // 16-character zero-padded canonical view, then mask
                // the first 12 digits to match PIC 9(16) semantics.
                String padded = String.format("%016d", value);
                String masked = "*".repeat(12) + padded.substring(12);
                return "CcCardNum.Numeric[" + masked + "]";
            }
        }

        /**
         * Parses a 16-byte slice into a {@link CcCardNum}. Always returns
         * the {@link Text} view; the raw bytes are preserved verbatim.
         * Callers convert to {@link Numeric} explicitly when they know
         * the field is valid digits.
         *
         * @param buffer the source byte array; non-null
         * @param offset the byte offset within {@code buffer} where the
         *               16-byte slice begins; must satisfy
         *               {@code 0 <= offset && offset + 16 <= buffer.length}
         * @return a new {@link Text} instance wrapping the 16-byte slice
         * @throws NullPointerException     if {@code buffer} is {@code null}
         * @throws IllegalArgumentException if {@code offset} is negative or
         *                                  the slice would extend past the
         *                                  end of {@code buffer}
         */
        static CcCardNum parse(byte[] buffer, int offset) {
            Objects.requireNonNull(buffer, "buffer");
            if (offset < 0 || offset + CARD_NUM_LENGTH > buffer.length) {
                throw new IllegalArgumentException(
                        "CcCardNum.parse: invalid offset " + offset
                                + " for buffer length " + buffer.length
                                + " (need " + CARD_NUM_LENGTH + " bytes)");
            }
            return new Text(new String(buffer, offset, CARD_NUM_LENGTH, StandardCharsets.US_ASCII));
        }
    }

    // =================================================================
    // Sealed CcCustId hierarchy — REDEFINES CC-CUST-ID PIC X(9) /
    // CC-CUST-ID-N PIC 9(9). The same 9-byte memory region viewed as
    // either text or a non-negative 9-digit integer. Per AAP §0.6.10.
    // =================================================================

    /**
     * Sealed REDEFINES on COBOL {@code CC-CUST-ID} &mdash; the same 9-byte
     * memory region viewed as either a text field ({@code PIC X(9)}) or
     * a non-negative numeric field ({@code PIC 9(9)}).
     *
     * <p>The numeric view uses {@code long} for consistency with
     * {@link CcAcctId.Numeric} and {@link CcCardNum.Numeric} (all three
     * REDEFINES sealed types expose a numeric view with the same
     * primitive type so call-site pattern matching is uniform).
     */
    @CobolProgram(
            value = "CVCRD01Y",
            sourcePath = "app/cpy/CVCRD01Y.cpy",
            notes = "REDEFINES CC-CUST-ID PIC X(9) / CC-CUST-ID-N PIC 9(9)"
    )
    public sealed interface CcCustId permits CcCustId.Text, CcCustId.Numeric {

        /**
         * Encodes this 9-byte field into a byte buffer:
         * {@link Text} → space-padded RIGHT (PIC X semantics);
         * {@link Numeric} → zero-padded LEFT (PIC 9 semantics).
         *
         * @return a {@link #CUST_ID_LENGTH}-byte array; never {@code null}
         */
        byte[] encode();

        /**
         * Text view: {@code PIC X(9)}. The {@code value} string may be
         * shorter than 9 characters (it will be space-padded RIGHT during
         * {@link #encode()}). Strings longer than 9 characters are
         * rejected.
         *
         * @param value the text content; non-null; length 0..9
         */
        record Text(String value) implements CcCustId {
            public Text {
                Objects.requireNonNull(value, "value");
                if (value.length() > CUST_ID_LENGTH) {
                    throw new IllegalArgumentException(
                            "CcCustId.Text length " + value.length()
                                    + " exceeds " + CUST_ID_LENGTH);
                }
            }

            @Override
            public byte[] encode() {
                byte[] buf = new byte[CUST_ID_LENGTH];
                Arrays.fill(buf, (byte) 0x20); // pre-fill with ASCII space (PIC X pad RIGHT)
                byte[] valueBytes = value.getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(valueBytes, 0, buf, 0,
                        Math.min(valueBytes.length, CUST_ID_LENGTH));
                return buf;
            }
        }

        /**
         * Numeric view: {@code PIC 9(9)}, zero-padded LEFT. Range
         * {@code 0..999_999_999L} (the maximum 9-digit unsigned value).
         *
         * @param value the unsigned numeric value; range 0..999,999,999
         */
        record Numeric(long value) implements CcCustId {
            /** Maximum representable value: 9 nines = 999,999,999. */
            public static final long MAX_VALUE = 999_999_999L;

            public Numeric {
                if (value < 0L || value > MAX_VALUE) {
                    throw new IllegalArgumentException(
                            "CcCustId.Numeric value " + value
                                    + " out of PIC 9(9) range [0.." + MAX_VALUE + "]");
                }
            }

            @Override
            public byte[] encode() {
                // 9-character zero-padded decimal representation (PIC 9 pad LEFT)
                return String.format("%09d", value).getBytes(StandardCharsets.US_ASCII);
            }
        }

        /**
         * Parses a 9-byte slice into a {@link CcCustId}. Always returns
         * the {@link Text} view; the raw bytes are preserved verbatim.
         * Callers convert to {@link Numeric} explicitly when they know
         * the field is valid digits.
         *
         * @param buffer the source byte array; non-null
         * @param offset the byte offset within {@code buffer} where the
         *               9-byte slice begins; must satisfy
         *               {@code 0 <= offset && offset + 9 <= buffer.length}
         * @return a new {@link Text} instance wrapping the 9-byte slice
         * @throws NullPointerException     if {@code buffer} is {@code null}
         * @throws IllegalArgumentException if {@code offset} is negative or
         *                                  the slice would extend past the
         *                                  end of {@code buffer}
         */
        static CcCustId parse(byte[] buffer, int offset) {
            Objects.requireNonNull(buffer, "buffer");
            if (offset < 0 || offset + CUST_ID_LENGTH > buffer.length) {
                throw new IllegalArgumentException(
                        "CcCustId.parse: invalid offset " + offset
                                + " for buffer length " + buffer.length
                                + " (need " + CUST_ID_LENGTH + " bytes)");
            }
            return new Text(new String(buffer, offset, CUST_ID_LENGTH, StandardCharsets.US_ASCII));
        }
    }

    // =================================================================
    // Top-level parse / encode / empty factory methods.
    // =================================================================

    /**
     * Parses a {@value #RECORD_LENGTH}-byte buffer into a
     * {@link CcWorkAreas} record. Uses US-ASCII charset (the
     * development-fixture encoding). Production EBCDIC transcoding is
     * handled at the file adapter layer, NOT here, per AAP &sect;0.6.5.
     *
     * <p>{@code PIC X} fields are read verbatim, preserving any space
     * padding. {@link CcAcctId#parse(byte[], int)},
     * {@link CcCardNum#parse(byte[], int)} and
     * {@link CcCustId#parse(byte[], int)} default to the {@code Text}
     * view; callers convert to {@code Numeric} explicitly when they know
     * the bytes are valid digits.
     *
     * <p>The {@code CCARD-AID} bytes MUST match one of the 16 recognized
     * COBOL mnemonics; otherwise an {@link IllegalArgumentException} is
     * thrown.
     *
     * @param buffer the source byte array; must be exactly
     *               {@value #RECORD_LENGTH} bytes long
     * @return a new {@link CcWorkAreas} record populated from the buffer
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code buffer.length} is not
     *                                  {@value #RECORD_LENGTH}, or if the
     *                                  {@code CCARD-AID} bytes are not a
     *                                  recognized mnemonic
     */
    public static CcWorkAreas parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "CcWorkAreas buffer length must be " + RECORD_LENGTH
                            + ", got " + buffer.length);
        }
        String aidMnemonic = new String(buffer, AID_OFFSET, AID_LENGTH, StandardCharsets.US_ASCII);
        AidKey aid = AidKey.ofMnemonic(aidMnemonic);
        String nextProg = new String(buffer, NEXT_PROG_OFFSET, NEXT_PROG_LENGTH,
                StandardCharsets.US_ASCII);
        String nextMapset = new String(buffer, NEXT_MAPSET_OFFSET, NEXT_MAPSET_LENGTH,
                StandardCharsets.US_ASCII);
        String nextMap = new String(buffer, NEXT_MAP_OFFSET, NEXT_MAP_LENGTH,
                StandardCharsets.US_ASCII);
        String errorMsg = new String(buffer, ERROR_MSG_OFFSET, ERROR_MSG_LENGTH,
                StandardCharsets.US_ASCII);
        String returnMsg = new String(buffer, RETURN_MSG_OFFSET, RETURN_MSG_LENGTH,
                StandardCharsets.US_ASCII);
        CcAcctId acctId = CcAcctId.parse(buffer, ACCT_ID_OFFSET);
        CcCardNum cardNum = CcCardNum.parse(buffer, CARD_NUM_OFFSET);
        CcCustId custId = CcCustId.parse(buffer, CUST_ID_OFFSET);
        return new CcWorkAreas(aid, nextProg, nextMapset, nextMap, errorMsg, returnMsg,
                acctId, cardNum, custId);
    }

    /**
     * Encodes this {@link CcWorkAreas} to a {@value #RECORD_LENGTH}-byte
     * fixed-width buffer. The buffer is pre-filled with ASCII space
     * ({@code 0x20}) so that any unwritten tail bytes within
     * {@code PIC X} fields are correctly space-padded RIGHT (matching
     * the COBOL {@code MOVE} semantics for fixed-width text fields).
     *
     * <p>The {@code CCARD-AID} field is encoded via the AID's
     * {@link AidKey#mnemonic()}. Each REDEFINES field
     * ({@link #acctId}, {@link #cardNum}, {@link #custId}) is encoded
     * by its own {@code encode()} method (so {@code Text} views are
     * space-padded and {@code Numeric} views are zero-padded).
     *
     * <p>Byte-for-byte round-trip invariant: for any value produced by
     * {@link #parse(byte[])}, calling {@code parse(w.encode())} returns a
     * value structurally equal to {@code w} (records have structural
     * equality, and sealed-permit records are themselves records).
     *
     * @return a new {@value #RECORD_LENGTH}-byte array; never {@code null}
     */
    public byte[] encode() {
        byte[] buffer = new byte[RECORD_LENGTH];
        Arrays.fill(buffer, (byte) 0x20); // ASCII space pre-fill (PIC X pad RIGHT)

        // CCARD-AID — exactly 5 bytes, written verbatim from the mnemonic
        byte[] aidBytes = aid.mnemonic().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(aidBytes, 0, buffer, AID_OFFSET, AID_LENGTH);

        // PIC X(n) text fields — space-padded RIGHT (any unwritten tail
        // bytes are already 0x20 from the Arrays.fill above)
        writeStringField(buffer, nextProg,   NEXT_PROG_OFFSET,   NEXT_PROG_LENGTH);
        writeStringField(buffer, nextMapset, NEXT_MAPSET_OFFSET, NEXT_MAPSET_LENGTH);
        writeStringField(buffer, nextMap,    NEXT_MAP_OFFSET,    NEXT_MAP_LENGTH);
        writeStringField(buffer, errorMsg,   ERROR_MSG_OFFSET,   ERROR_MSG_LENGTH);
        writeStringField(buffer, returnMsg,  RETURN_MSG_OFFSET,  RETURN_MSG_LENGTH);

        // REDEFINES fields — each sealed permit handles its own encoding
        // (Text → space-padded RIGHT; Numeric → zero-padded LEFT)
        System.arraycopy(acctId.encode(),  0, buffer, ACCT_ID_OFFSET,  ACCT_ID_LENGTH);
        System.arraycopy(cardNum.encode(), 0, buffer, CARD_NUM_OFFSET, CARD_NUM_LENGTH);
        System.arraycopy(custId.encode(),  0, buffer, CUST_ID_OFFSET,  CUST_ID_LENGTH);

        return buffer;
    }

    /**
     * Writes a PIC X(length) text field into {@code buffer} starting at
     * {@code offset}. The buffer is assumed to be pre-filled with ASCII
     * space; only the value's bytes are written, leaving any unwritten
     * tail as the pre-filled space (PIC X pad RIGHT semantics).
     *
     * <p>The canonical-constructor validation already guarantees
     * {@code value.length() <= length}, so no truncation logic is needed
     * here.
     */
    private static void writeStringField(byte[] buffer, String value, int offset, int length) {
        byte[] valueBytes = value.getBytes(StandardCharsets.US_ASCII);
        // copyLength is bounded by both the value bytes and the field length;
        // the canonical constructor already rejects oversized values, but
        // Math.min keeps this helper defensive against future changes.
        int copyLength = Math.min(valueBytes.length, length);
        System.arraycopy(valueBytes, 0, buffer, offset, copyLength);
        // Remaining bytes within [offset+copyLength, offset+length) are
        // already 0x20 from the caller's pre-fill (PIC X pad RIGHT).
    }

    /**
     * Returns an "empty" {@link CcWorkAreas} matching the COBOL
     * {@code VALUE SPACES} initialization for the data items that declare
     * one (CC-ACCT-ID, CC-CARD-NUM, CC-CUST-ID) and the implicit "no
     * initial value" state for the others (CCARD-NEXT-PROG, etc., which
     * have no {@code VALUE} clause and would be initialized by the CICS
     * runtime to spaces under {@code WORKING-STORAGE SECTION}).
     *
     * <p>The {@link AidKey} defaults to {@link AidKey#ENTER}, the
     * canonical "no key pressed yet" sentinel. The actual AID value is
     * populated by {@code PfKeyDecoder} (the translation of the COBOL
     * {@code CSSTRPFY} procedure) after the {@code EXEC CICS RECEIVE}
     * call has determined which AID byte CICS produced.
     *
     * <p>All {@code String} fields are initialized to all-spaces (length
     * equal to their COBOL-declared {@code PIC X(n)} length). The
     * REDEFINES fields default to the {@code Text} view, each holding an
     * all-spaces string of the appropriate length (preserving COBOL
     * {@code VALUE SPACES} semantics).
     *
     * @return a fresh {@link CcWorkAreas} with all-space text fields and
     *         {@link AidKey#ENTER} as the AID; never {@code null}
     */
    public static CcWorkAreas empty() {
        return new CcWorkAreas(
                AidKey.ENTER,
                " ".repeat(NEXT_PROG_LENGTH),
                " ".repeat(NEXT_MAPSET_LENGTH),
                " ".repeat(NEXT_MAP_LENGTH),
                " ".repeat(ERROR_MSG_LENGTH),
                " ".repeat(RETURN_MSG_LENGTH),
                new CcAcctId.Text(" ".repeat(ACCT_ID_LENGTH)),
                new CcCardNum.Text(" ".repeat(CARD_NUM_LENGTH)),
                new CcCustId.Text(" ".repeat(CUST_ID_LENGTH))
        );
    }
}

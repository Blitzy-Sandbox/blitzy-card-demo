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
package com.blitzy.carddemo.domain.record;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.util.Decimals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable domain record translated from the COBOL {@code DIS-GROUP-RECORD}
 * 01-level group at {@code app/cpy/CVTRA02Y.cpy} (total record length
 * {@value #RECORD_LENGTH} bytes).
 *
 * <p>One {@code DisGroupRecord} instance corresponds to one VSAM KSDS
 * record in the {@code DISCGRP} dataset on z/OS &mdash; the per-account-group
 * / per-transaction-type / per-category interest-rate table consulted by
 * the monthly interest-calculation engine ({@code CBACT04C} translated as
 * {@code com.blitzy.carddemo.application.account.CbAct04C}) to determine
 * the disclosure interest rate that should apply to a given category
 * balance in a given account group, per AAP &sect;0.4.1.
 *
 * <h2>COBOL source layout (verbatim from {@code app/cpy/CVTRA02Y.cpy})</h2>
 * <pre>{@code
 * 01 DIS-GROUP-RECORD.
 *    05 DIS-GROUP-KEY.
 *       10 DIS-ACCT-GROUP-ID         PIC X(10).      (10 bytes — offset  0..9)
 *       10 DIS-TRAN-TYPE-CD          PIC X(02).      ( 2 bytes — offset 10..11)
 *       10 DIS-TRAN-CAT-CD           PIC 9(04).      ( 4 bytes — offset 12..15)
 *    05 DIS-INT-RATE                 PIC S9(04)V99.  ( 6 bytes — offset 16..21)
 *    05 FILLER                       PIC X(28).      (28 bytes — offset 22..49)
 * Total: 50 bytes.
 * }</pre>
 *
 * <h2>Java field mapping</h2>
 * <table>
 *   <caption>Byte-offset and type mapping</caption>
 *   <tr><th>COBOL field</th><th>PIC</th><th>Len</th><th>Offset</th>
 *       <th>Java component</th></tr>
 *   <tr><td>DIS-GROUP-KEY</td><td>(group)</td><td>16</td><td>0</td>
 *       <td>{@link #disGroupKey() disGroupKey} : {@link DisGroupKey}</td></tr>
 *   <tr><td>&nbsp;&nbsp;DIS-ACCT-GROUP-ID</td><td>X(10)</td><td>10</td><td>0</td>
 *       <td>{@link DisGroupKey#disAcctGroupId() disAcctGroupId} : String</td></tr>
 *   <tr><td>&nbsp;&nbsp;DIS-TRAN-TYPE-CD</td><td>X(02)</td><td>2</td><td>10</td>
 *       <td>{@link DisGroupKey#disTranTypeCd() disTranTypeCd} : String</td></tr>
 *   <tr><td>&nbsp;&nbsp;DIS-TRAN-CAT-CD</td><td>9(04)</td><td>4</td><td>12</td>
 *       <td>{@link DisGroupKey#disTranCatCd() disTranCatCd} : int</td></tr>
 *   <tr><td>DIS-INT-RATE</td><td>S9(04)V99</td><td>6</td><td>16</td>
 *       <td>{@link #disIntRate() disIntRate} : BigDecimal (scale=2)</td></tr>
 *   <tr><td>FILLER</td><td>X(28)</td><td>28</td><td>22</td>
 *       <td>{@link #filler() filler} : byte[28]</td></tr>
 * </table>
 *
 * <h2>Composite key &mdash; {@link DisGroupKey}</h2>
 * The COBOL {@code DIS-GROUP-KEY} group (16 bytes) is faithfully translated
 * as a nested {@link DisGroupKey} record per AAP &sect;0.3.2 (Records
 * pattern) and AAP &sect;0.6.3 (composite key as nested record). The
 * three-component key uniquely identifies an interest-rate tier:
 * <ul>
 *   <li>{@code DIS-ACCT-GROUP-ID PIC X(10)} &mdash; an alphanumeric tag
 *       binding an account to a disclosure-group cohort (e.g.,
 *       {@code "A000000000"}, {@code "ZEROAPR   "}); 10 bytes,
 *       right-space-padded when shorter than the field width.</li>
 *   <li>{@code DIS-TRAN-TYPE-CD PIC X(02)} &mdash; a 2-character
 *       transaction-type code (e.g., {@code "01"} = Purchase,
 *       {@code "02"} = Cash Advance) loaded from
 *       {@code app/data/ASCII/trantype.txt}.</li>
 *   <li>{@code DIS-TRAN-CAT-CD PIC 9(04)} &mdash; a 4-digit unsigned
 *       transaction-category code (range {@code 0..9999}) loaded from
 *       {@code app/data/ASCII/trancatg.txt}.</li>
 * </ul>
 *
 * <h2>Decimal encoding &mdash; DIS-INT-RATE</h2>
 * The signed-rate {@code DIS-INT-RATE PIC S9(04)V99} field is encoded in
 * COBOL {@code USAGE DISPLAY} (zoned-decimal) format on disk: each of the
 * 6 bytes is an ASCII digit character {@code '0'}-{@code '9'} except the
 * rightmost byte, which combines the rightmost digit with the sign via
 * overpunch (per AAP &sect;0.6.5). For example, the
 * {@code app/data/ASCII/discgrp.txt} fixture's first record
 * <code>"A00000000001000100150{0000000000000000000000000000"</code> carries
 * {@code DIS-INT-RATE = +15.00} &mdash; the substring <code>"00150{"</code> at
 * offset 16 decodes via the overpunch table as digits {@code "001500"}
 * with positive sign (<code>'{'</code> = positive zero at the trailing
 * position), then scaled to two decimal places yields {@code 15.00}. The
 * codec is delegated to
 * {@link Decimals#parseZonedDecimal(byte[], int, int, int)} and
 * {@link Decimals#encodeZonedDecimal(BigDecimal, int, int)}, consistent
 * with the sibling {@link TranRecord} / {@link TranCatBalRecord} /
 * {@link AccountRecord} translations of the same COBOL
 * {@code PIC S9(n)V99} construct (the central {@link Decimals} facade is
 * the single point of control for sign-overpunch conventions and scale
 * preservation per AAP &sect;0.3.3, &sect;0.6.1).
 *
 * <p>The {@link BigDecimal} value carried by this record always has
 * exactly {@link #DIS_INT_RATE_SCALE} ({@code = 2}) decimal places. The
 * canonical constructor normalizes the scale via {@code setScale(2,
 * RoundingMode.HALF_EVEN)} so that a rate of {@code 1.20%} is preserved
 * as {@code 1.20} (NOT normalized to {@code 1.2}) per AAP &sect;0.1.3
 * scale-preservation requirement.
 *
 * <h2>Byte-for-byte fidelity contract (AAP &sect;0.6.5)</h2>
 * For every valid {@value #RECORD_LENGTH}-byte buffer {@code b}:
 * <pre>{@code
 *   Arrays.equals(b, DisGroupRecord.parse(b).encode()) == true
 * }</pre>
 * This invariant is the formal contract with external file consumers and
 * is asserted by the golden-record harness on every PR. Subject only to
 * the canonicalization notes on
 * {@link Decimals#encodeZonedDecimal(BigDecimal, int, int)} (plain-digit
 * unsigned-style inputs in the last byte of {@code DIS-INT-RATE} are
 * decoded as positive and re-encoded with the canonical <code>'{'</code>-style
 * overpunch).
 *
 * <h2>Encoding rules (per AAP &sect;0.6.5 and binding agent-prompt
 * constraints)</h2>
 * <ul>
 *   <li>Charset: {@link StandardCharsets#US_ASCII}. EBCDIC transcoding,
 *       when required, is performed by the adapter layer
 *       ({@code carddemo-adapter-file.EbcdicTranscoder}) before the byte
 *       buffer reaches {@link #parse(byte[])}.</li>
 *   <li>Numeric fields ({@code PIC 9(n)} &mdash; {@code DIS-TRAN-CAT-CD}):
 *       decoded with strict digit validation; encoded with zero-padding
 *       on the LEFT.</li>
 *   <li>String fields ({@code PIC X(n)} &mdash; {@code DIS-ACCT-GROUP-ID}
 *       and {@code DIS-TRAN-TYPE-CD}): decoded verbatim (no trimming) so
 *       that round-trip fidelity is preserved; encoded with space-padding
 *       on the RIGHT.</li>
 *   <li>Signed numeric ({@code S9(04)V99} &mdash; {@code DIS-INT-RATE}):
 *       delegated to the {@link Decimals} zoned-decimal codec.</li>
 *   <li>{@code FILLER}: opaque 28-byte trailer preserved verbatim as
 *       {@code byte[28]} for byte-for-byte fidelity. Defensive copies
 *       are taken on construction and on the {@link #filler()}
 *       accessor.</li>
 * </ul>
 *
 * <h2>Consumers</h2>
 * <ul>
 *   <li>{@code com.blitzy.carddemo.application.account.CbAct04C}
 *       (monthly interest-calculation engine, translated from
 *       {@code app/cbl/CBACT04C.cbl} driven by JCL
 *       {@code app/jcl/INTCALC.jcl}) &mdash; looks up the disclosure
 *       interest rate for each {@code (account-group, transaction-type,
 *       transaction-category)} tuple as it walks the {@code TCATBAL}
 *       balances and accumulates monthly interest.</li>
 *   <li>{@code com.blitzy.carddemo.domain.port.DiscountGroupRepository}
 *       (port interface) &mdash; provides typed lookup by composite key
 *       ({@code findByKey(DisGroupKey)}).</li>
 *   <li>{@code com.blitzy.carddemo.app.DefineDiscountGroupApp}
 *       (translated from JCL {@code app/jcl/DISCGRP.jcl}) &mdash; loads
 *       the rate table from {@code app/data/ASCII/discgrp.txt} fixture
 *       per AAP &sect;0.4.1.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * This record is immutable (defensive copy of {@code filler} on
 * construction and on accessor). Safe for unrestricted concurrent use,
 * including virtual-thread fan-out per AAP &sect;0.6.6.
 *
 * @since 1.0.0
 */
@CobolProgram(
        value = "CVTRA02Y",
        sourcePath = "app/cpy/CVTRA02Y.cpy",
        notes = "50-byte DIS-GROUP-RECORD; 16-byte composite DIS-GROUP-KEY "
                + "(DIS-ACCT-GROUP-ID X(10) + DIS-TRAN-TYPE-CD X(02) + "
                + "DIS-TRAN-CAT-CD 9(04)) + S9(04)V99 zoned-decimal "
                + "DIS-INT-RATE (6 ASCII bytes via Decimals.parseZonedDecimal "
                + "per AAP §0.6.5) + 28-byte FILLER. Loaded from "
                + "app/data/ASCII/discgrp.txt fixture; consumed by "
                + "CBACT04C monthly interest calculation."
)
public record DisGroupRecord(
        DisGroupKey disGroupKey,
        BigDecimal disIntRate,
        byte[] filler) {

    // ---------------------------------------------------------------------
    // Layout constants — exposed for use by readers, writers, and tests.
    // Offsets are 0-based; lengths are byte counts. All values come
    // directly from the COBOL 01-level group in app/cpy/CVTRA02Y.cpy.
    // These are part of the public schema per the file's exports listing.
    // ---------------------------------------------------------------------

    /** Total fixed-record length in bytes. */
    public static final int RECORD_LENGTH = 50;

    /** Byte offset of {@code DIS-ACCT-GROUP-ID} within the record. */
    public static final int DIS_ACCT_GROUP_ID_OFFSET = 0;
    /** Byte length of {@code DIS-ACCT-GROUP-ID PIC X(10)}. */
    public static final int DIS_ACCT_GROUP_ID_LENGTH = 10;

    /** Byte offset of {@code DIS-TRAN-TYPE-CD} within the record. */
    public static final int DIS_TRAN_TYPE_CD_OFFSET = 10;
    /** Byte length of {@code DIS-TRAN-TYPE-CD PIC X(02)}. */
    public static final int DIS_TRAN_TYPE_CD_LENGTH = 2;

    /** Byte offset of {@code DIS-TRAN-CAT-CD} within the record. */
    public static final int DIS_TRAN_CAT_CD_OFFSET = 12;
    /** Byte length of {@code DIS-TRAN-CAT-CD PIC 9(04)}. */
    public static final int DIS_TRAN_CAT_CD_LENGTH = 4;

    /** Byte offset of {@code DIS-INT-RATE} within the record. */
    public static final int DIS_INT_RATE_OFFSET = 16;
    /**
     * Byte length of {@code DIS-INT-RATE PIC S9(04)V99} encoded in
     * {@code USAGE DISPLAY} zoned-decimal: 4 integer + 2 decimal digits =
     * 6 ASCII bytes. The rightmost byte combines the rightmost digit with
     * the sign overpunch (per {@link Decimals#encodeZonedDecimal}).
     */
    public static final int DIS_INT_RATE_LENGTH = 6;
    /**
     * Implicit decimal places ({@code scale}) for {@code DIS-INT-RATE},
     * per the COBOL {@code V99} suffix.
     */
    public static final int DIS_INT_RATE_SCALE = 2;

    /** Byte offset of the trailing {@code FILLER} within the record. */
    public static final int FILLER_OFFSET = 22;
    /** Byte length of the trailing {@code FILLER PIC X(28)}. */
    public static final int FILLER_LENGTH = 28;

    // ---------------------------------------------------------------------
    // Internal constants — not part of the public schema.
    // ---------------------------------------------------------------------

    /**
     * Maximum numeric value representable in {@code DIS-TRAN-CAT-CD PIC 9(04)}.
     * Equal to {@code 10^DIS_TRAN_CAT_CD_LENGTH - 1} = {@code 9999}.
     */
    private static final int DIS_TRAN_CAT_CD_MAX = 9999;

    /** ASCII space byte used for {@code PIC X(n)} right-padding. */
    private static final byte SPACE = (byte) ' ';

    // ---------------------------------------------------------------------
    // Nested record DisGroupKey — 16-byte composite key.
    //
    // Faithful translation of the COBOL DIS-GROUP-KEY nested group per
    // AAP §0.3.2 (Records pattern) and §0.6.3 (composite key as nested
    // record). The 16-byte key is the natural primary key for the DISCGRP
    // VSAM KSDS dataset in the COBOL source — it identifies one interest-
    // rate tier in a three-dimensional lookup table.
    // ---------------------------------------------------------------------

    /**
     * Immutable composite key {@code DIS-GROUP-KEY} (16 bytes total)
     * derived from the COBOL group of the same name within
     * {@code CVTRA02Y.cpy}.
     *
     * <p>Layout:
     * <pre>{@code
     * 05 DIS-GROUP-KEY.
     *    10 DIS-ACCT-GROUP-ID    PIC X(10).
     *    10 DIS-TRAN-TYPE-CD     PIC X(02).
     *    10 DIS-TRAN-CAT-CD      PIC 9(04).
     * }</pre>
     *
     * <p>The three components together uniquely identify a row in the
     * disclosure-group table; the corresponding {@code DIS-INT-RATE}
     * value is the monthly periodic interest rate (as a percentage) that
     * applies to balances within the indicated category for accounts
     * tagged with the indicated group.
     *
     * @param disAcctGroupId the 10-character account-group identifier;
     *                       must not exceed 10 characters; may be
     *                       all-spaces or contain trailing spaces (the
     *                       COBOL convention for an uninitialized
     *                       alphanumeric field)
     * @param disTranTypeCd  the 2-character transaction-type code; must
     *                       not exceed 2 characters; may be all-spaces
     *                       (the COBOL convention for an uninitialized
     *                       alphanumeric field)
     * @param disTranCatCd   the 4-digit unsigned category code (range
     *                       {@code 0..9999})
     */
    public record DisGroupKey(
            String disAcctGroupId,
            String disTranTypeCd,
            int disTranCatCd) {

        /**
         * Canonical constructor performing COBOL-style input validation
         * before the record fields are bound, per AAP &sect;0.6.3 (JEP
         * 513 Flexible Constructor Bodies, finalized in Java 25).
         *
         * @throws NullPointerException     if {@code disAcctGroupId} or
         *                                  {@code disTranTypeCd} is
         *                                  {@code null}
         * @throws IllegalArgumentException if {@code disAcctGroupId}
         *                                  exceeds 10 characters,
         *                                  {@code disTranTypeCd} exceeds
         *                                  2 characters, or
         *                                  {@code disTranCatCd} is out
         *                                  of range
         */
        public DisGroupKey {
            Objects.requireNonNull(disAcctGroupId, "disAcctGroupId");
            Objects.requireNonNull(disTranTypeCd, "disTranTypeCd");
            if (disAcctGroupId.length() > DIS_ACCT_GROUP_ID_LENGTH) {
                throw new IllegalArgumentException(
                        "disAcctGroupId length " + disAcctGroupId.length()
                                + " exceeds " + DIS_ACCT_GROUP_ID_LENGTH);
            }
            if (disTranTypeCd.length() > DIS_TRAN_TYPE_CD_LENGTH) {
                throw new IllegalArgumentException(
                        "disTranTypeCd length " + disTranTypeCd.length()
                                + " exceeds " + DIS_TRAN_TYPE_CD_LENGTH);
            }
            if (disTranCatCd < 0 || disTranCatCd > DIS_TRAN_CAT_CD_MAX) {
                throw new IllegalArgumentException(
                        "disTranCatCd must be in range 0.." + DIS_TRAN_CAT_CD_MAX
                                + ", got " + disTranCatCd);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Compact canonical constructor — validation + normalization.
    //
    // Uses JEP 513 (finalized in Java 25) Flexible Constructor Bodies: the
    // body runs before the canonical field assignments, which is exactly
    // the right place for COBOL-style input validation (per AAP §0.6.3).
    //
    // Normalization steps:
    //   • Scale-normalize disIntRate to DIS_INT_RATE_SCALE so that values
    //     like 1.20 are preserved as 1.20 (not 1.2) per AAP §0.1.3
    //     scale-preservation requirement. Uses banker's rounding
    //     (RoundingMode.HALF_EVEN) — equivalent to Decimals.ROUNDED_MODE
    //     — per the AAP-mandated default for COBOL ROUNDED clause; the
    //     scale change here is a no-op when the caller supplies a value
    //     already at scale 2 (the typical path from parse(byte[])).
    //   • Defensive-copy the mutable byte[] filler so the record is truly
    //     immutable (record components otherwise bind references
    //     directly).
    // ---------------------------------------------------------------------

    /**
     * Canonical constructor performing argument validation, scale
     * normalization on the monetary rate, and defensive copying of the
     * mutable {@code filler} byte array.
     *
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code filler.length} differs
     *                                  from {@link #FILLER_LENGTH}
     */
    public DisGroupRecord {
        Objects.requireNonNull(disGroupKey, "disGroupKey");
        Objects.requireNonNull(disIntRate, "disIntRate");
        Objects.requireNonNull(filler, "filler");
        if (filler.length != FILLER_LENGTH) {
            throw new IllegalArgumentException(
                    "filler must be exactly " + FILLER_LENGTH + " bytes, got "
                            + filler.length);
        }

        // Normalize the rate to the canonical V99 scale using banker's
        // rounding per AAP §0.6.1. setScale guarantees that a value of
        // "1.20" remains "1.20" (not "1.2") to preserve byte-identical
        // encode output per AAP §0.1.3.
        disIntRate = disIntRate.setScale(DIS_INT_RATE_SCALE, RoundingMode.HALF_EVEN);

        // Defensive copy of the mutable byte array so the record is truly
        // immutable (records normally bind references directly).
        filler = filler.clone();
    }

    // ---------------------------------------------------------------------
    // Defensive accessor for the mutable byte[] component.
    // ---------------------------------------------------------------------

    /**
     * Returns a defensive copy of the 28-byte {@code FILLER} payload so
     * that the record remains immutable even if a caller mutates the
     * returned array.
     *
     * @return a fresh {@code byte[FILLER_LENGTH]} copy of the filler bytes
     */
    @Override
    public byte[] filler() {
        return filler.clone();
    }

    // ---------------------------------------------------------------------
    // parse(byte[]) — static factory producing a DisGroupRecord from a
    // 50-byte buffer that follows the CVTRA02Y layout. The byte buffer is
    // expected to use ASCII encoding (production EBCDIC is transcoded by
    // the EbcdicTranscoder adapter in carddemo-adapter-file before
    // reaching this method).
    // ---------------------------------------------------------------------

    /**
     * Parses a 50-byte ASCII buffer laid out per
     * {@code app/cpy/CVTRA02Y.cpy} and returns a corresponding
     * {@link DisGroupRecord}.
     *
     * <p>Field decoding:
     * <ul>
     *   <li>{@code DIS-ACCT-GROUP-ID} (offset 0, length 10) &rarr;
     *       verbatim 10-character ASCII slice (preserves trailing spaces
     *       for round-trip fidelity).</li>
     *   <li>{@code DIS-TRAN-TYPE-CD} (offset 10, length 2) &rarr;
     *       verbatim 2-character ASCII slice (preserves trailing spaces
     *       for round-trip fidelity).</li>
     *   <li>{@code DIS-TRAN-CAT-CD} (offset 12, length 4) &rarr; strict
     *       ASCII digit parse into {@code int}.</li>
     *   <li>{@code DIS-INT-RATE} (offset 16, length 6) &rarr; via
     *       {@link Decimals#parseZonedDecimal(byte[], int, int, int)}
     *       ({@code USAGE DISPLAY} zoned-decimal with sign overpunch on
     *       the rightmost byte; see {@link Decimals} for the full
     *       overpunch table <code>'{'</code>/{@code 'A'}-{@code 'I'} for
     *       non-negative, <code>'}'</code>/{@code 'J'}-{@code 'R'} for
     *       negative).</li>
     *   <li>{@code FILLER} (offset 22, length 28) &rarr;
     *       {@link Arrays#copyOfRange(byte[], int, int)} (opaque trailer
     *       preserved verbatim).</li>
     * </ul>
     *
     * <p>This method is the inverse of {@link #encode()}: for any
     * {@code DisGroupRecord} {@code r} produced by parsing a
     * COBOL-emitted buffer {@code b}, {@code r.encode()} equals {@code b}
     * byte-for-byte (subject to the canonicalization notes on
     * {@link Decimals#encodeZonedDecimal(BigDecimal, int, int)}).
     *
     * @param buffer ASCII-encoded fixed-width record (must be exactly
     *               {@link #RECORD_LENGTH} bytes)
     * @return decoded {@link DisGroupRecord}
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code buffer.length} differs
     *         from {@link #RECORD_LENGTH}, the {@code DIS-TRAN-CAT-CD}
     *         field contains a non-digit, or the {@code DIS-INT-RATE}
     *         zoned-decimal slice is malformed
     */
    public static DisGroupRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "DisGroupRecord buffer must be exactly " + RECORD_LENGTH
                            + " bytes; got " + buffer.length);
        }

        String disAcctGroupId = readAscii(
                buffer, DIS_ACCT_GROUP_ID_OFFSET, DIS_ACCT_GROUP_ID_LENGTH);
        String disTranTypeCd = readAscii(
                buffer, DIS_TRAN_TYPE_CD_OFFSET, DIS_TRAN_TYPE_CD_LENGTH);
        int disTranCatCd = readUnsignedInt(
                buffer, DIS_TRAN_CAT_CD_OFFSET, DIS_TRAN_CAT_CD_LENGTH);

        BigDecimal disIntRate = Decimals.parseZonedDecimal(
                buffer, DIS_INT_RATE_OFFSET, DIS_INT_RATE_LENGTH, DIS_INT_RATE_SCALE);

        byte[] filler = Arrays.copyOfRange(
                buffer, FILLER_OFFSET, FILLER_OFFSET + FILLER_LENGTH);

        return new DisGroupRecord(
                new DisGroupKey(disAcctGroupId, disTranTypeCd, disTranCatCd),
                disIntRate,
                filler);
    }

    // ---------------------------------------------------------------------
    // encode() — produces a 50-byte ASCII buffer following the CVTRA02Y
    // layout. All PIC X(n) fields are right-space-padded; PIC 9(n) fields
    // are left-zero-padded; PIC S9(04)V99 (DIS-INT-RATE) is delegated to
    // the Decimals zoned-decimal codec.
    // ---------------------------------------------------------------------

    /**
     * Encodes this record as a 50-byte ASCII buffer laid out per
     * {@code app/cpy/CVTRA02Y.cpy}.
     *
     * <p>Padding rules (AAP &sect;0.6.5 byte-for-byte fidelity):
     * <ul>
     *   <li>{@code PIC X(n)} alphanumeric fields ({@code DIS-ACCT-GROUP-ID}
     *       and {@code DIS-TRAN-TYPE-CD}) are right-padded with ASCII
     *       space ({@code 0x20}) when shorter than {@code n}.</li>
     *   <li>{@code PIC 9(n)} unsigned numeric fields
     *       ({@code DIS-TRAN-CAT-CD}) are left-padded with ASCII zero
     *       ({@code '0'}).</li>
     *   <li>{@code PIC S9(04)V99} ({@code DIS-INT-RATE}) is encoded via
     *       {@link Decimals#encodeZonedDecimal(BigDecimal, int, int)}
     *       ({@code USAGE DISPLAY} zoned-decimal, sign overpunch on the
     *       last byte).</li>
     *   <li>{@code FILLER} is copied byte-for-byte from the record.</li>
     * </ul>
     *
     * <p>Per AAP &sect;0.6.5 byte-for-byte fidelity invariant:
     * {@code parse(record).encode()} equals the original buffer (subject
     * to the canonicalization notes on
     * {@link Decimals#encodeZonedDecimal(BigDecimal, int, int)}).
     *
     * @return freshly allocated 50-byte buffer
     */
    public byte[] encode() {
        byte[] out = new byte[RECORD_LENGTH];
        // Pre-fill with ASCII spaces so any narrow PIC X(n) value is
        // naturally right-space-padded.
        Arrays.fill(out, SPACE);

        // DIS-ACCT-GROUP-ID — PIC X(10), space-padded RIGHT.
        writeAscii(out, disGroupKey.disAcctGroupId(),
                DIS_ACCT_GROUP_ID_OFFSET, DIS_ACCT_GROUP_ID_LENGTH);

        // DIS-TRAN-TYPE-CD — PIC X(02), space-padded RIGHT.
        writeAscii(out, disGroupKey.disTranTypeCd(),
                DIS_TRAN_TYPE_CD_OFFSET, DIS_TRAN_TYPE_CD_LENGTH);

        // DIS-TRAN-CAT-CD — PIC 9(04), zero-padded LEFT.
        writeUnsignedLong(out, disGroupKey.disTranCatCd(),
                DIS_TRAN_CAT_CD_OFFSET, DIS_TRAN_CAT_CD_LENGTH);

        // DIS-INT-RATE — PIC S9(04)V99, USAGE DISPLAY zoned-decimal,
        // sign overpunch on the rightmost byte. Codec delegated to
        // Decimals to keep MathContext / RoundingMode centralized per
        // AAP §0.3.3.
        byte[] rateBytes = Decimals.encodeZonedDecimal(
                disIntRate, DIS_INT_RATE_LENGTH, DIS_INT_RATE_SCALE);
        System.arraycopy(rateBytes, 0, out,
                DIS_INT_RATE_OFFSET, DIS_INT_RATE_LENGTH);

        // FILLER — opaque 28-byte trailer preserved verbatim.
        System.arraycopy(filler, 0, out, FILLER_OFFSET, FILLER_LENGTH);

        return out;
    }

    // ---------------------------------------------------------------------
    // Convenience helpers retained for in-scope consumers.
    // ---------------------------------------------------------------------

    /**
     * Returns a fresh 28-byte FILLER initialized to ASCII spaces, suitable
     * for constructing a brand-new {@link DisGroupRecord} that does not
     * inherit FILLER bytes from an existing record.
     *
     * <p>Used by callers that synthesize a {@code DISCGRP} record from
     * scratch (for example, an admin tool that adds a new disclosure
     * group rate row without an upstream COBOL-emitted byte buffer).
     *
     * @return a fresh {@code byte[FILLER_LENGTH]} filled with ASCII space
     *         ({@code 0x20})
     */
    public static byte[] emptyFiller() {
        byte[] empty = new byte[FILLER_LENGTH];
        Arrays.fill(empty, SPACE);
        return empty;
    }

    // ---------------------------------------------------------------------
    // equals / hashCode — value semantics with BigDecimal numeric equality
    // (compareTo, not Object.equals — scale-insensitive) and byte-array
    // content equality (Arrays.equals — element-wise). The record-
    // generated defaults would use BigDecimal.equals (scale-sensitive) and
    // reference equality on the byte[], which is wrong for our value-type
    // contract per AAP §0.6.5.
    // ---------------------------------------------------------------------

    /**
     * Value equality comparing each component by content.
     *
     * <p>The {@link BigDecimal} rate is compared via
     * {@link BigDecimal#compareTo(BigDecimal)} so that {@code 1.20} and
     * {@code 1.2} are considered equal (the canonical constructor
     * normalizes scale, so divergence is rarely observable, but the
     * override is defensive). The {@code filler} byte array is compared
     * by element via {@link Arrays#equals(byte[], byte[])}.
     *
     * @param o the object to compare against
     * @return {@code true} iff {@code o} is a {@code DisGroupRecord}
     *         with the same component values
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DisGroupRecord that)) {
            return false;
        }
        return disGroupKey.equals(that.disGroupKey)
                && disIntRate.compareTo(that.disIntRate) == 0
                && Arrays.equals(filler, that.filler);
    }

    /**
     * Hash code consistent with {@link #equals(Object)}. The
     * {@link BigDecimal} rate uses
     * {@link BigDecimal#stripTrailingZeros()} hashing so that values that
     * compare equal via {@code compareTo} also share a hash bucket.
     *
     * @return content-based hash code
     */
    @Override
    public int hashCode() {
        int result = disGroupKey.hashCode();
        result = 31 * result + disIntRate.stripTrailingZeros().hashCode();
        result = 31 * result + Arrays.hashCode(filler);
        return result;
    }

    // ---------------------------------------------------------------------
    // toString — value semantics summary; FILLER bytes summarized rather
    // than enumerated to keep log lines readable.
    // ---------------------------------------------------------------------

    /**
     * Returns a human-readable representation that summarizes the FILLER
     * as {@code <28 bytes>} rather than enumerating its content.
     *
     * @return human-readable description
     */
    @Override
    public String toString() {
        return "DisGroupRecord["
                + "disGroupKey=" + disGroupKey
                + ", disIntRate=" + disIntRate
                + ", filler=<" + FILLER_LENGTH + " bytes>]";
    }

    // ---------------------------------------------------------------------
    // Private helpers — fixed-width codec primitives.
    // These mirror the helpers in sibling record translations
    // (TranCatBalRecord, TranRecord) for visual and behavioral
    // consistency across the record package.
    // ---------------------------------------------------------------------

    /**
     * Reads {@code length} ASCII bytes from {@code buffer} starting at
     * {@code offset} and returns them as a {@link String}. Trailing
     * spaces are preserved so that re-encoding yields the original byte
     * layout.
     *
     * @param buffer source buffer (caller is responsible for null check)
     * @param offset starting byte offset (must be in range)
     * @param length number of bytes to read
     * @return ASCII-decoded slice
     */
    private static String readAscii(byte[] buffer, int offset, int length) {
        return new String(buffer, offset, length, StandardCharsets.US_ASCII);
    }

    /**
     * Reads {@code length} ASCII digit characters from {@code buffer}
     * starting at {@code offset} and parses them as an unsigned integer.
     * All characters must be {@code '0'..'9'} (the COBOL
     * {@code PIC 9(n)} contract); any non-digit raises
     * {@link IllegalArgumentException}.
     *
     * <p>Used for {@code DIS-TRAN-CAT-CD} which fits in {@code int}.
     *
     * @param buffer source buffer
     * @param offset starting byte offset
     * @param length number of digits
     * @return parsed unsigned integer
     * @throws IllegalArgumentException if any byte is not an ASCII digit
     *         or the parsed value exceeds {@link Integer#MAX_VALUE}
     */
    private static int readUnsignedInt(byte[] buffer, int offset, int length) {
        long value = readUnsignedLong(buffer, offset, length);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "numeric value " + value + " at offset " + offset
                            + " exceeds int range");
        }
        return (int) value;
    }

    /**
     * Reads {@code length} ASCII digit characters from {@code buffer}
     * starting at {@code offset} and parses them as an unsigned
     * {@code long}.
     *
     * @param buffer source buffer
     * @param offset starting byte offset
     * @param length number of digits
     * @return parsed unsigned long
     * @throws IllegalArgumentException if any byte is not an ASCII digit
     */
    private static long readUnsignedLong(byte[] buffer, int offset, int length) {
        long value = 0L;
        for (int i = 0; i < length; i++) {
            int b = buffer[offset + i] & 0xFF;
            if (b < '0' || b > '9') {
                throw new IllegalArgumentException(String.format(
                        "non-digit byte 0x%02X ('%c') at offset %d while reading "
                                + "PIC 9(%d) field starting at offset %d",
                        b, (char) b, offset + i, length, offset));
            }
            value = value * 10L + (b - '0');
        }
        return value;
    }

    /**
     * Writes a {@link String} into {@code out} at {@code offset}, copying
     * up to {@code length} ASCII bytes. Shorter strings are left in place
     * with any pre-existing bytes following (callers must pre-fill the
     * region with spaces when right-space-padding is required).
     *
     * <p>Longer strings are rejected by the canonical constructor of
     * {@link DisGroupKey}; this method uses {@link Math#min(int, int)} as
     * a defense-in-depth measure to avoid overflowing the destination
     * window even if a string slipped past validation.
     *
     * @param out    destination buffer
     * @param value  value to write (must not be {@code null})
     * @param offset starting byte offset
     * @param length destination field width in bytes
     */
    private static void writeAscii(byte[] out, String value, int offset, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int copyLen = Math.min(bytes.length, length);
        System.arraycopy(bytes, 0, out, offset, copyLen);
        // Any remaining bytes in [offset+copyLen, offset+length) keep the
        // value placed there by the caller's Arrays.fill(SPACE) pre-pass.
    }

    /**
     * Writes a non-negative {@code long} into {@code out} as {@code length}
     * ASCII digit characters, left-zero-padded. Models the COBOL
     * {@code PIC 9(n)} encoding.
     *
     * @param out    destination buffer
     * @param value  unsigned numeric value to encode
     * @param offset starting byte offset
     * @param length destination field width in digits
     * @throws IllegalArgumentException if {@code value} is negative or
     *         requires more than {@code length} digits
     */
    private static void writeUnsignedLong(byte[] out, long value, int offset, int length) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    "PIC 9(" + length + ") at offset " + offset
                            + " cannot encode negative value " + value);
        }
        long remaining = value;
        for (int i = length - 1; i >= 0; i--) {
            out[offset + i] = (byte) ('0' + (int) (remaining % 10L));
            remaining /= 10L;
        }
        if (remaining != 0L) {
            throw new IllegalArgumentException(
                    "value " + value + " overflows PIC 9(" + length
                            + ") at offset " + offset);
        }
    }
}

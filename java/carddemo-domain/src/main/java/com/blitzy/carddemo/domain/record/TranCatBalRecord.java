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
 * Immutable domain record translated from the COBOL
 * {@code TRAN-CAT-BAL-RECORD} 01-level group at
 * {@code app/cpy/CVTRA01Y.cpy} (total record length 50 bytes).
 *
 * <p>One {@code TranCatBalRecord} instance corresponds to one VSAM KSDS
 * record in the {@code TCATBAL} dataset on z/OS &mdash; the per-account /
 * per-transaction-type / per-category running balance table consulted and
 * updated by the daily transaction posting engine ({@code CBTRN02C}) and
 * the monthly interest engine ({@code CBACT04C}) per AAP &sect;0.4.1.
 *
 * <h2>COBOL source layout (verbatim from {@code app/cpy/CVTRA01Y.cpy})</h2>
 * <pre>{@code
 * 01 TRAN-CAT-BAL-RECORD.
 *    05 TRAN-CAT-KEY.
 *       10 TRANCAT-ACCT-ID            PIC 9(11).     (11 bytes — offset  0..10)
 *       10 TRANCAT-TYPE-CD            PIC X(02).     ( 2 bytes — offset 11..12)
 *       10 TRANCAT-CD                 PIC 9(04).     ( 4 bytes — offset 13..16)
 *    05 TRAN-CAT-BAL                  PIC S9(09)V99. (11 bytes — offset 17..27)
 *    05 FILLER                        PIC X(22).     (22 bytes — offset 28..49)
 * Total: 50 bytes.
 * }</pre>
 *
 * <h2>Java field mapping</h2>
 * <table>
 *   <caption>Byte-offset and type mapping</caption>
 *   <tr><th>COBOL field</th><th>PIC</th><th>Len</th><th>Offset</th>
 *       <th>Java component</th></tr>
 *   <tr><td>TRAN-CAT-KEY</td><td>(group)</td><td>17</td><td>0</td>
 *       <td>{@link #tranCatKey() tranCatKey} : {@link TranCatKey}</td></tr>
 *   <tr><td>&nbsp;&nbsp;TRANCAT-ACCT-ID</td><td>9(11)</td><td>11</td><td>0</td>
 *       <td>{@link TranCatKey#trancatAcctId() trancatAcctId} : long</td></tr>
 *   <tr><td>&nbsp;&nbsp;TRANCAT-TYPE-CD</td><td>X(02)</td><td>2</td><td>11</td>
 *       <td>{@link TranCatKey#trancatTypeCd() trancatTypeCd} : String</td></tr>
 *   <tr><td>&nbsp;&nbsp;TRANCAT-CD</td><td>9(04)</td><td>4</td><td>13</td>
 *       <td>{@link TranCatKey#trancatCd() trancatCd} : int</td></tr>
 *   <tr><td>TRAN-CAT-BAL</td><td>S9(09)V99</td><td>11</td><td>17</td>
 *       <td>{@link #tranCatBal() tranCatBal} : BigDecimal (scale=2)</td></tr>
 *   <tr><td>FILLER</td><td>X(22)</td><td>22</td><td>28</td>
 *       <td>{@link #filler() filler} : byte[22]</td></tr>
 * </table>
 *
 * <h2>Composite key &mdash; {@link TranCatKey}</h2>
 * The COBOL {@code TRAN-CAT-KEY} group (17 bytes) is faithfully translated
 * as a nested {@link TranCatKey} record per AAP &sect;0.3.2 (Records
 * pattern) and the binding agent prompt "Phase 3: Nested TranCatKey
 * Record (17-byte composite key)".
 *
 * <p><strong>Important &mdash; do NOT consolidate with
 * {@code TranCatRecord.TranCatKey}.</strong> Both {@link TranCatRecord}
 * (translated from {@code CVTRA04Y.cpy}) and this {@code TranCatBalRecord}
 * declare a nested group called {@code TRAN-CAT-KEY}, but the two layouts
 * are <em>different</em>:
 * <ul>
 *   <li>{@code TranCatRecord.TranCatKey}: 6 bytes
 *       ({@code TRAN-TYPE-CD X(02) + TRAN-CAT-CD 9(04)}).</li>
 *   <li>{@code TranCatBalRecord.TranCatKey} (this nested type): 17 bytes
 *       ({@code TRANCAT-ACCT-ID 9(11) + TRANCAT-TYPE-CD X(02) +
 *       TRANCAT-CD 9(04)}).</li>
 * </ul>
 * The two are deliberately kept as separate nested records so each lives
 * next to its outer record and carries its own COBOL-faithful field names
 * and lengths (key insight per the binding agent prompt).
 *
 * <h2>Decimal encoding &mdash; TRAN-CAT-BAL</h2>
 * The signed monetary {@code TRAN-CAT-BAL PIC S9(09)V99} field is encoded
 * in COBOL {@code USAGE DISPLAY} (zoned-decimal) format on disk: each of
 * the 11 bytes is an ASCII digit character {@code '0'}-{@code '9'} except
 * the rightmost byte, which combines the rightmost digit with the sign
 * via overpunch (per AAP &sect;0.6.5). For example, the
 * {@code app/data/ASCII/tcatbal.txt} fixture's first record
 * <code>"000000000010100010000000000{0000000000000000000000"</code> carries
 * {@code TRAN-CAT-BAL = +0.00} with the trailing <code>'{'</code> overpunch
 * indicating positive zero. The codec is delegated to
 * {@link Decimals#parseZonedDecimal(byte[], int, int, int)} and
 * {@link Decimals#encodeZonedDecimal(BigDecimal, int, int)}, consistent
 * with the sibling {@link TranRecord} and {@link DisGroupRecord}
 * translations of the same COBOL {@code PIC S9(n)V99} construct.
 *
 * <p>The {@link BigDecimal} value carried by this record always has
 * exactly {@link #TRAN_CAT_BAL_SCALE} ({@code = 2}) decimal places. The
 * canonical constructor normalizes the scale via {@code setScale(2,
 * RoundingMode.HALF_EVEN)} so that a value of {@code 1.20} is preserved
 * as {@code 1.20} (NOT normalized to {@code 1.2}) per AAP &sect;0.1.3
 * scale-preservation requirement.
 *
 * <h2>Byte-for-byte fidelity contract (AAP &sect;0.6.5)</h2>
 * For every valid {@value #RECORD_LENGTH}-byte buffer {@code b}:
 * <pre>{@code
 *   Arrays.equals(b, TranCatBalRecord.parse(b).encode()) == true
 * }</pre>
 * This invariant is the formal contract with external file consumers and
 * is asserted by the golden-record harness on every PR. Subject only to
 * the canonicalization notes on
 * {@link Decimals#encodeZonedDecimal(BigDecimal, int, int)} (plain-digit
 * unsigned-style inputs in the last byte of {@code TRAN-CAT-BAL} are
 * decoded as positive and re-encoded with the canonical <code>'{'</code>/...
 * overpunch).
 *
 * <h2>Encoding rules (per AAP &sect;0.6.5 and binding agent-prompt
 * constraints)</h2>
 * <ul>
 *   <li>Charset: {@link StandardCharsets#US_ASCII}. EBCDIC transcoding,
 *       when required, is performed by the adapter layer
 *       ({@code carddemo-adapter-file.EbcdicTranscoder}) before the byte
 *       buffer reaches {@link #parse(byte[])}.</li>
 *   <li>Numeric fields ({@code PIC 9(n)} &mdash;
 *       {@code TRANCAT-ACCT-ID} and {@code TRANCAT-CD}): decoded with
 *       strict digit validation; encoded with zero-padding on the LEFT
 *       (binding constraint #5).</li>
 *   <li>String fields ({@code PIC X(n)} &mdash;
 *       {@code TRANCAT-TYPE-CD}): decoded verbatim (no trimming) so that
 *       round-trip fidelity is preserved; encoded with space-padding on
 *       the RIGHT (binding constraint #6).</li>
 *   <li>Signed monetary ({@code S9(n)V99} &mdash; {@code TRAN-CAT-BAL}):
 *       delegated to the {@link Decimals} codec (binding constraint
 *       #7).</li>
 *   <li>{@code FILLER}: opaque 22-byte trailer preserved verbatim as
 *       {@code byte[22]} for byte-for-byte fidelity. Defensive copies are
 *       taken on construction and on the {@link #filler()} accessor.</li>
 * </ul>
 *
 * <h2>Consumers</h2>
 * <ul>
 *   <li>{@code com.blitzy.carddemo.application.account.CbAct04C}
 *       (monthly interest engine) &mdash; walks {@code TCATBAL}
 *       sequentially and computes per-category interest.</li>
 *   <li>{@code com.blitzy.carddemo.application.transaction.CbTrn02C}
 *       (transaction posting engine) &mdash; reads, updates, and
 *       inserts {@code TCATBAL} records as it posts daily
 *       transactions.</li>
 *   <li>{@code com.blitzy.carddemo.domain.port.TransactionCategoryBalanceRepository}
 *       (port interface).</li>
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
        value = "CVTRA01Y",
        sourcePath = "app/cpy/CVTRA01Y.cpy",
        notes = "50-byte TRAN-CAT-BAL-RECORD; 17-byte composite TRAN-CAT-KEY "
                + "(distinct from the 6-byte TRAN-CAT-KEY in CVTRA04Y) + "
                + "S9(09)V99 zoned-decimal TRAN-CAT-BAL (11 ASCII bytes via "
                + "Decimals.parseZonedDecimal per AAP §0.6.5) + 22-byte FILLER. "
                + "Loaded from app/data/ASCII/tcatbal.txt fixture; "
                + "consumed by CBTRN02C posting and CBACT04C interest."
)
public record TranCatBalRecord(
        TranCatKey tranCatKey,
        BigDecimal tranCatBal,
        byte[] filler) {

    // ---------------------------------------------------------------------
    // Layout constants — exposed for use by readers, writers, and tests.
    // Offsets are 0-based; lengths are byte counts. All values come
    // directly from the COBOL 01-level group in app/cpy/CVTRA01Y.cpy.
    // ---------------------------------------------------------------------

    /** Total fixed-record length in bytes. */
    public static final int RECORD_LENGTH = 50;

    /** Byte offset of {@code TRANCAT-ACCT-ID} within the record. */
    public static final int TRANCAT_ACCT_ID_OFFSET = 0;
    /** Byte length of {@code TRANCAT-ACCT-ID PIC 9(11)}. */
    public static final int TRANCAT_ACCT_ID_LENGTH = 11;

    /** Byte offset of {@code TRANCAT-TYPE-CD} within the record. */
    public static final int TRANCAT_TYPE_CD_OFFSET = 11;
    /** Byte length of {@code TRANCAT-TYPE-CD PIC X(02)}. */
    public static final int TRANCAT_TYPE_CD_LENGTH = 2;

    /** Byte offset of {@code TRANCAT-CD} within the record. */
    public static final int TRANCAT_CD_OFFSET = 13;
    /** Byte length of {@code TRANCAT-CD PIC 9(04)}. */
    public static final int TRANCAT_CD_LENGTH = 4;

    /** Byte offset of {@code TRAN-CAT-BAL} within the record. */
    public static final int TRAN_CAT_BAL_OFFSET = 17;
    /**
     * Byte length of {@code TRAN-CAT-BAL PIC S9(09)V99} encoded in
     * {@code USAGE DISPLAY} zoned-decimal: 9 integer + 2 decimal digits =
     * 11 ASCII bytes. The rightmost byte combines the rightmost digit with
     * the sign overpunch (per {@link Decimals#encodeZonedDecimal}).
     */
    public static final int TRAN_CAT_BAL_LENGTH = 11;
    /**
     * Implicit decimal places ({@code scale}) for {@code TRAN-CAT-BAL},
     * per the COBOL {@code V99} suffix.
     */
    public static final int TRAN_CAT_BAL_SCALE = 2;

    /** Byte offset of the trailing {@code FILLER} within the record. */
    public static final int FILLER_OFFSET = 28;
    /** Byte length of the trailing {@code FILLER PIC X(22)}. */
    public static final int FILLER_LENGTH = 22;

    // ---------------------------------------------------------------------
    // Internal constants — not part of the public schema.
    // ---------------------------------------------------------------------

    /**
     * Maximum numeric value representable in {@code TRANCAT-CD PIC 9(04)}.
     * Equal to {@code 10^TRANCAT_CD_LENGTH - 1} = {@code 9999}.
     */
    private static final int TRANCAT_CD_MAX = 9999;

    /** ASCII space byte used for {@code PIC X(n)} right-padding. */
    private static final byte SPACE = (byte) ' ';

    // ---------------------------------------------------------------------
    // Nested record TranCatKey — 17-byte composite key.
    //
    // NOTE: This nested type is DIFFERENT from TranCatRecord.TranCatKey
    // (which is 6 bytes, TYPE-CD + CAT-CD only). Both records use the same
    // group name "TRAN-CAT-KEY" in COBOL but with different field
    // memberships. Do NOT consolidate; each declares its own nested key
    // type to preserve COBOL-faithful field names and the corresponding
    // exact byte length.
    // ---------------------------------------------------------------------

    /**
     * Immutable composite key {@code TRAN-CAT-KEY} (17 bytes total) derived
     * from the COBOL group of the same name within {@code CVTRA01Y.cpy}.
     *
     * <p>Layout:
     * <pre>{@code
     * 05 TRAN-CAT-KEY.
     *    10 TRANCAT-ACCT-ID   PIC 9(11).
     *    10 TRANCAT-TYPE-CD   PIC X(02).
     *    10 TRANCAT-CD        PIC 9(04).
     * }</pre>
     *
     * <p><strong>Distinct from {@code TranCatRecord.TranCatKey}.</strong>
     * The sibling record translates a 6-byte {@code TRAN-CAT-KEY}
     * containing only {@code TRAN-TYPE-CD} and {@code TRAN-CAT-CD}; this
     * 17-byte key additionally carries the {@code TRANCAT-ACCT-ID}. The
     * compiler distinguishes the two via the enclosing record type.
     *
     * @param trancatAcctId the 11-digit non-negative account ID (range
     *                      {@code 0..99_999_999_999L})
     * @param trancatTypeCd the 2-character transaction-type code; must
     *                      not exceed 2 characters; may be all-spaces
     *                      (the COBOL convention for an uninitialized
     *                      type code)
     * @param trancatCd     the 4-digit unsigned category code (range
     *                      {@code 0..9999})
     */
    public record TranCatKey(long trancatAcctId, String trancatTypeCd, int trancatCd) {

        /**
         * Canonical constructor performing COBOL-style input validation
         * before the record fields are bound, per AAP &sect;0.6.3 (JEP 513
         * Flexible Constructor Bodies).
         *
         * @throws NullPointerException     if {@code trancatTypeCd} is
         *                                  {@code null}
         * @throws IllegalArgumentException if {@code trancatAcctId} is
         *                                  negative, {@code trancatTypeCd}
         *                                  exceeds 2 characters, or
         *                                  {@code trancatCd} is out of
         *                                  range
         */
        public TranCatKey {
            Objects.requireNonNull(trancatTypeCd, "trancatTypeCd");
            if (trancatAcctId < 0L) {
                throw new IllegalArgumentException(
                        "trancatAcctId must be non-negative, got " + trancatAcctId);
            }
            if (trancatTypeCd.length() > TRANCAT_TYPE_CD_LENGTH) {
                throw new IllegalArgumentException(
                        "trancatTypeCd length " + trancatTypeCd.length()
                                + " exceeds " + TRANCAT_TYPE_CD_LENGTH);
            }
            if (trancatCd < 0 || trancatCd > TRANCAT_CD_MAX) {
                throw new IllegalArgumentException(
                        "trancatCd must be in range 0.." + TRANCAT_CD_MAX
                                + ", got " + trancatCd);
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
    //   • Scale-normalize tranCatBal to TRAN_CAT_BAL_SCALE so that values
    //     like 1.20 are preserved as 1.20 (not 1.2) per AAP §0.1.3
    //     scale-preservation requirement.
    //   • Defensive-copy the mutable byte[] filler so the record is truly
    //     immutable (record components otherwise bind references directly).
    // ---------------------------------------------------------------------

    /**
     * Canonical constructor performing argument validation, scale
     * normalization on the monetary amount, and defensive copying of the
     * mutable {@code filler} byte array.
     *
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code filler.length} differs
     *                                  from {@link #FILLER_LENGTH}
     */
    public TranCatBalRecord {
        Objects.requireNonNull(tranCatKey, "tranCatKey");
        Objects.requireNonNull(tranCatBal, "tranCatBal");
        Objects.requireNonNull(filler, "filler");
        if (filler.length != FILLER_LENGTH) {
            throw new IllegalArgumentException(
                    "filler must be exactly " + FILLER_LENGTH + " bytes, got "
                            + filler.length);
        }

        // Normalize the monetary amount to the canonical V99 scale using
        // banker's rounding per AAP §0.6.1. setScale guarantees that a
        // value of "1.20" remains "1.20" (not "1.2") to preserve byte-
        // identical encode output per AAP §0.1.3.
        tranCatBal = tranCatBal.setScale(TRAN_CAT_BAL_SCALE, RoundingMode.HALF_EVEN);

        // Defensive copy of the mutable byte array so the record is truly
        // immutable (records normally bind references directly).
        filler = filler.clone();
    }

    // ---------------------------------------------------------------------
    // Defensive accessor for the mutable byte[] component.
    // ---------------------------------------------------------------------

    /**
     * Returns a defensive copy of the 22-byte {@code FILLER} payload so
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
    // parse(byte[]) — static factory producing a TranCatBalRecord from a
    // 50-byte buffer that follows the CVTRA01Y layout. The byte buffer is
    // expected to use ASCII encoding (production EBCDIC is transcoded by
    // the EbcdicTranscoder adapter in carddemo-adapter-file before
    // reaching this method).
    // ---------------------------------------------------------------------

    /**
     * Parses a 50-byte ASCII buffer laid out per
     * {@code app/cpy/CVTRA01Y.cpy} and returns a corresponding
     * {@link TranCatBalRecord}.
     *
     * <p>Field decoding:
     * <ul>
     *   <li>{@code TRANCAT-ACCT-ID} (offset 0, length 11) &rarr; strict
     *       ASCII digit parse into {@code long}.</li>
     *   <li>{@code TRANCAT-TYPE-CD} (offset 11, length 2) &rarr; verbatim
     *       2-character ASCII slice (preserves trailing spaces for
     *       round-trip fidelity).</li>
     *   <li>{@code TRANCAT-CD} (offset 13, length 4) &rarr; strict ASCII
     *       digit parse into {@code int}.</li>
     *   <li>{@code TRAN-CAT-BAL} (offset 17, length 11) &rarr; via
     *       {@link Decimals#parseZonedDecimal(byte[], int, int, int)}
     *       ({@code USAGE DISPLAY} zoned-decimal with sign overpunch on
     *       the rightmost byte; see {@link Decimals} for the full
     *       overpunch table).</li>
     *   <li>{@code FILLER} (offset 28, length 22) &rarr;
     *       {@link Arrays#copyOfRange(byte[], int, int)} (opaque trailer
     *       preserved verbatim).</li>
     * </ul>
     *
     * <p>This method is the inverse of {@link #encode()}: for any
     * {@code TranCatBalRecord} {@code r} produced by parsing a
     * COBOL-emitted buffer {@code b}, {@code r.encode()} equals {@code b}
     * byte-for-byte (subject to the canonicalization notes on
     * {@link Decimals#encodeZonedDecimal(BigDecimal, int, int)}).
     *
     * @param buffer ASCII-encoded fixed-width record (must be exactly
     *               {@link #RECORD_LENGTH} bytes)
     * @return decoded {@link TranCatBalRecord}
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code buffer.length} differs
     *         from {@link #RECORD_LENGTH}, a numeric field contains a
     *         non-digit, or the {@code TRAN-CAT-BAL} zoned-decimal slice
     *         is malformed
     */
    public static TranCatBalRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "TranCatBalRecord buffer must be exactly " + RECORD_LENGTH
                            + " bytes; got " + buffer.length);
        }

        long trancatAcctId = readUnsignedLong(
                buffer, TRANCAT_ACCT_ID_OFFSET, TRANCAT_ACCT_ID_LENGTH);
        String trancatTypeCd = readAscii(
                buffer, TRANCAT_TYPE_CD_OFFSET, TRANCAT_TYPE_CD_LENGTH);
        int trancatCd = readUnsignedInt(
                buffer, TRANCAT_CD_OFFSET, TRANCAT_CD_LENGTH);

        BigDecimal tranCatBal = Decimals.parseZonedDecimal(
                buffer, TRAN_CAT_BAL_OFFSET, TRAN_CAT_BAL_LENGTH, TRAN_CAT_BAL_SCALE);

        byte[] filler = Arrays.copyOfRange(
                buffer, FILLER_OFFSET, FILLER_OFFSET + FILLER_LENGTH);

        return new TranCatBalRecord(
                new TranCatKey(trancatAcctId, trancatTypeCd, trancatCd),
                tranCatBal,
                filler);
    }

    // ---------------------------------------------------------------------
    // encode() — produces a 50-byte ASCII buffer following the CVTRA01Y
    // layout. All PIC X(n) fields are right-space-padded; PIC 9(n) fields
    // are left-zero-padded; PIC S9(09)V99 (TRAN-CAT-BAL) is delegated to
    // the Decimals zoned-decimal codec.
    // ---------------------------------------------------------------------

    /**
     * Encodes this record as a 50-byte ASCII buffer laid out per
     * {@code app/cpy/CVTRA01Y.cpy}.
     *
     * <p>Padding rules (AAP &sect;0.6.5 byte-for-byte fidelity):
     * <ul>
     *   <li>{@code PIC 9(n)} unsigned numeric fields
     *       ({@code TRANCAT-ACCT-ID}, {@code TRANCAT-CD}) are left-padded
     *       with ASCII zero ({@code '0'}).</li>
     *   <li>{@code PIC X(n)} alphanumeric fields ({@code TRANCAT-TYPE-CD})
     *       are right-padded with ASCII space ({@code 0x20}) when
     *       shorter than {@code n}.</li>
     *   <li>{@code PIC S9(09)V99} ({@code TRAN-CAT-BAL}) is encoded via
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

        // TRANCAT-ACCT-ID — PIC 9(11), zero-padded LEFT.
        writeUnsignedLong(out, tranCatKey.trancatAcctId(),
                TRANCAT_ACCT_ID_OFFSET, TRANCAT_ACCT_ID_LENGTH);

        // TRANCAT-TYPE-CD — PIC X(02), space-padded RIGHT.
        writeAscii(out, tranCatKey.trancatTypeCd(),
                TRANCAT_TYPE_CD_OFFSET, TRANCAT_TYPE_CD_LENGTH);

        // TRANCAT-CD — PIC 9(04), zero-padded LEFT.
        writeUnsignedLong(out, tranCatKey.trancatCd(),
                TRANCAT_CD_OFFSET, TRANCAT_CD_LENGTH);

        // TRAN-CAT-BAL — PIC S9(09)V99, USAGE DISPLAY zoned-decimal,
        // sign overpunch on the rightmost byte. Codec delegated to
        // Decimals to keep MathContext / RoundingMode centralized per
        // AAP §0.3.3.
        byte[] balBytes = Decimals.encodeZonedDecimal(
                tranCatBal, TRAN_CAT_BAL_LENGTH, TRAN_CAT_BAL_SCALE);
        System.arraycopy(balBytes, 0, out,
                TRAN_CAT_BAL_OFFSET, TRAN_CAT_BAL_LENGTH);

        // FILLER — opaque 22-byte trailer preserved verbatim.
        System.arraycopy(filler, 0, out, FILLER_OFFSET, FILLER_LENGTH);

        return out;
    }

    // ---------------------------------------------------------------------
    // Convenience helpers retained for in-scope consumers
    // (CbTrn02C — transaction posting).
    // ---------------------------------------------------------------------

    /**
     * Returns a new {@link TranCatBalRecord} whose balance is adjusted by
     * the supplied {@code delta}, preserving the composite key and the
     * filler bytes. The new balance is normalized to
     * {@link #TRAN_CAT_BAL_SCALE} with COBOL-default truncation
     * ({@link Decimals#DEFAULT_MODE}) per AAP &sect;0.3.3 (the absence of
     * a COBOL {@code ROUNDED} clause on the {@code ADD} translation).
     *
     * <p>Used by {@code CbTrn02C.updateExistingTcatBal} (translation of
     * the COBOL paragraph {@code 2700-B-UPDATE-TCATBAL-REC} in
     * {@code CBTRN02C.cbl}).
     *
     * @param delta the signed amount to add to the current balance (must
     *              not be {@code null})
     * @return a new immutable record carrying the adjusted balance
     * @throws NullPointerException if {@code delta} is {@code null}
     */
    public TranCatBalRecord withBalanceAdjustment(BigDecimal delta) {
        Objects.requireNonNull(delta, "delta");
        BigDecimal newBal = Decimals.add(
                tranCatBal, delta, TRAN_CAT_BAL_SCALE, Decimals.DEFAULT_MODE);
        return new TranCatBalRecord(tranCatKey, newBal, filler);
    }

    /**
     * Returns a fresh 22-byte FILLER initialized to ASCII spaces, suitable
     * for constructing a brand-new {@link TranCatBalRecord} that does not
     * inherit FILLER bytes from an existing record.
     *
     * <p>Used by {@code CbTrn02C.createTcatBal} (translation of the COBOL
     * paragraph {@code 2700-A-CREATE-TCATBAL-REC} in
     * {@code CBTRN02C.cbl}) when a posted transaction's
     * {@code (acctId, typeCd, catCd)} combination has no existing TCATBAL
     * row.
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
     * <p>The {@link BigDecimal} balance is compared via
     * {@link BigDecimal#compareTo(BigDecimal)} so that {@code 1.20} and
     * {@code 1.2} are considered equal (the canonical constructor
     * normalizes scale, so divergence is rarely observable, but the
     * override is defensive). The {@code filler} byte array is compared
     * by element via {@link Arrays#equals(byte[], byte[])}.
     *
     * @param o the object to compare against
     * @return {@code true} iff {@code o} is a {@code TranCatBalRecord}
     *         with the same component values
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TranCatBalRecord that)) {
            return false;
        }
        return tranCatKey.equals(that.tranCatKey)
                && tranCatBal.compareTo(that.tranCatBal) == 0
                && Arrays.equals(filler, that.filler);
    }

    /**
     * Hash code consistent with {@link #equals(Object)}. The
     * {@link BigDecimal} balance uses
     * {@link BigDecimal#stripTrailingZeros()} hashing so that values that
     * compare equal via {@code compareTo} also share a hash bucket.
     *
     * @return content-based hash code
     */
    @Override
    public int hashCode() {
        int result = tranCatKey.hashCode();
        result = 31 * result + tranCatBal.stripTrailingZeros().hashCode();
        result = 31 * result + Arrays.hashCode(filler);
        return result;
    }

    // ---------------------------------------------------------------------
    // toString — value semantics summary; FILLER bytes summarized rather
    // than enumerated to keep log lines readable.
    // ---------------------------------------------------------------------

    /**
     * Returns a human-readable representation that summarizes the FILLER
     * as {@code <22 bytes>} rather than enumerating its content.
     *
     * @return human-readable description
     */
    @Override
    public String toString() {
        return "TranCatBalRecord["
                + "tranCatKey=" + tranCatKey
                + ", tranCatBal=" + tranCatBal
                + ", filler=<" + FILLER_LENGTH + " bytes>]";
    }

    // ---------------------------------------------------------------------
    // Private helpers — fixed-width codec primitives.
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
     * <p>Used for {@code TRANCAT-CD} which fits in {@code int}; for
     * larger fields ({@code TRANCAT-ACCT-ID}), see
     * {@link #readUnsignedLong(byte[], int, int)}.
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
     * {@link TranCatKey}; this method uses {@link Math#min(int, int)} as
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

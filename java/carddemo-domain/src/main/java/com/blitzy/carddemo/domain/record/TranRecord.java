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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable domain record translated from the COBOL {@code TRAN-RECORD}
 * 01-level group at {@code app/cpy/CVTRA05Y.cpy} (total record length
 * 350 bytes).
 *
 * <h2>COBOL source layout</h2>
 * <pre>{@code
 * 01 TRAN-RECORD.
 *   05 TRAN-ID                PIC X(16).     (16 bytes — offset   0..15)
 *   05 TRAN-TYPE-CD           PIC X(02).     ( 2 bytes — offset  16..17)
 *   05 TRAN-CAT-CD            PIC 9(04).     ( 4 bytes — offset  18..21)
 *   05 TRAN-SOURCE            PIC X(10).     (10 bytes — offset  22..31)
 *   05 TRAN-DESC              PIC X(100).    (100 bytes — offset 32..131)
 *   05 TRAN-AMT               PIC S9(09)V99. (11 bytes — offset 132..142)
 *   05 TRAN-MERCHANT-ID       PIC 9(09).     ( 9 bytes — offset 143..151)
 *   05 TRAN-MERCHANT-NAME     PIC X(50).     (50 bytes — offset 152..201)
 *   05 TRAN-MERCHANT-CITY     PIC X(50).     (50 bytes — offset 202..251)
 *   05 TRAN-MERCHANT-ZIP      PIC X(10).     (10 bytes — offset 252..261)
 *   05 TRAN-CARD-NUM          PIC X(16).     (16 bytes — offset 262..277)
 *   05 TRAN-ORIG-TS           PIC X(26).     (26 bytes — offset 278..303)
 *   05 TRAN-PROC-TS           PIC X(26).     (26 bytes — offset 304..329)
 *   05 FILLER                 PIC X(20).     (20 bytes — offset 330..349)
 * Total: 350 bytes.
 * }</pre>
 *
 * <h2>Java field mapping</h2>
 * <ul>
 *   <li>{@code TRAN-ID}, {@code TRAN-TYPE-CD}, {@code TRAN-SOURCE},
 *       {@code TRAN-DESC}, {@code TRAN-MERCHANT-NAME},
 *       {@code TRAN-MERCHANT-CITY}, {@code TRAN-MERCHANT-ZIP},
 *       {@code TRAN-CARD-NUM} &mdash; alphanumeric {@code PIC X(n)} fields
 *       carried as {@link String} with right-space padding semantics on
 *       encode.</li>
 *   <li>{@code TRAN-CAT-CD} &mdash; numeric {@code PIC 9(04)} carried as
 *       {@code int} (range {@code 0..9999}); zero-left-padded on encode.</li>
 *   <li>{@code TRAN-AMT} &mdash; signed numeric {@code PIC S9(09)V99}
 *       (USAGE DISPLAY zoned-decimal, sign overpunch on the rightmost byte)
 *       carried as {@link BigDecimal} with fixed {@code scale=2} per AAP
 *       &sect;0.6.1. Codec is delegated to
 *       {@link Decimals#parseZonedDecimal(byte[], int, int, int)} and
 *       {@link Decimals#encodeZonedDecimal(BigDecimal, int, int)}.</li>
 *   <li>{@code TRAN-MERCHANT-ID} &mdash; unsigned numeric {@code PIC 9(09)}
 *       carried as {@code long} (range {@code 0..999_999_999});
 *       zero-left-padded on encode.</li>
 *   <li>{@code TRAN-ORIG-TS}, {@code TRAN-PROC-TS} &mdash; {@code PIC X(26)}
 *       timestamp strings with pattern {@code yyyy-MM-dd HH:mm:ss.SSSSSS},
 *       carried as {@link LocalDateTime} per AAP &sect;0.6.4. {@code null}
 *       represents the COBOL all-spaces sentinel for an unset timestamp
 *       (commonly seen for {@code TRAN-PROC-TS} on incoming daily
 *       transactions that have not yet been posted). Round-trip behavior:
 *       parse of 26 spaces produces {@code null}; encode of {@code null}
 *       emits 26 spaces.</li>
 *   <li>{@code FILLER} &mdash; opaque 20-byte trailer preserved verbatim as
 *       {@code byte[20]} for byte-for-byte fidelity per AAP &sect;0.1.3.
 *       Defensive copies are taken on construction and on the
 *       {@link #filler()} accessor.</li>
 * </ul>
 *
 * <h2>Byte-for-byte fidelity invariant (AAP &sect;0.6.5)</h2>
 * <p>For every {@code 350}-byte buffer {@code b} produced by COBOL,
 * {@code parse(b).encode()} produces a byte sequence equal to {@code b}
 * up to the canonical encodings produced by
 * {@link Decimals#encodeZonedDecimal(BigDecimal, int, int)} for
 * {@code TRAN-AMT} (see that method's Javadoc for the canonicalization
 * note on plain-digit vs. overpunched sign).
 *
 * <h2>PAN-masking</h2>
 * <p>The full Primary Account Number ({@code tranCardNum}) is intentionally
 * NEVER printed by {@link #toString()}; the {@link #maskedPan()} helper
 * returns {@code "************1234"} style masking and is the only form
 * that appears in logs from {@code carddemo-application} per AAP
 * &sect;0.7.2.
 *
 * <h2>Identity with DalyTranRecord</h2>
 * <p>{@link DalyTranRecord} (translated from {@code CVTRA06Y.cpy}) has the
 * identical byte layout. The two records are kept as separate types per AAP
 * &sect;0.1.3 one-to-one program-to-class mapping &mdash; the compiler
 * distinguishes incoming daily transactions from already-posted
 * transactions.
 *
 * <h2>Consumers</h2>
 * <p>Read by {@code CbTrn02C} (posting engine), {@code CbTrn03C}
 * (paginated report writer), {@code CoTrn00C} (online list),
 * {@code CoTrn01C} (online view), and {@code CoTrn02C} (online add).
 *
 * <h2>Thread safety</h2>
 * <p>This record is immutable (defensive copy of {@code filler} on
 * construction and on accessor). Safe for unrestricted concurrent use,
 * including virtual-thread fan-out per AAP &sect;0.6.6.
 *
 * @since 1.0.0
 */
@CobolProgram(
        value = "CVTRA05Y",
        sourcePath = "app/cpy/CVTRA05Y.cpy",
        notes = "350-byte TRAN-RECORD; TRAN-AMT decoded via Decimals.parseZonedDecimal "
                + "(USAGE DISPLAY zoned-decimal, 11 ASCII bytes per AAP §0.6.5). "
                + "TRAN-ORIG-TS / TRAN-PROC-TS map PIC X(26) ↔ LocalDateTime per AAP §0.6.4 "
                + "with null representing the all-spaces sentinel for an unset timestamp "
                + "to preserve byte-for-byte fidelity on dailytran.txt fixture inputs. "
                + "PAN is masked in toString() per AAP §0.7.2."
)
public record TranRecord(
        String tranId,
        String tranTypeCd,
        int tranCatCd,
        String tranSource,
        String tranDesc,
        BigDecimal tranAmt,
        long tranMerchantId,
        String tranMerchantName,
        String tranMerchantCity,
        String tranMerchantZip,
        String tranCardNum,
        LocalDateTime tranOrigTs,
        LocalDateTime tranProcTs,
        byte[] filler) {

    // ---------------------------------------------------------------------
    // Layout constants — exposed for use by readers, writers, and tests.
    // Offsets are 0-based; lengths are byte counts. All values come directly
    // from the COBOL 01-level group in app/cpy/CVTRA05Y.cpy.
    // ---------------------------------------------------------------------

    /** Total fixed-record length in bytes. */
    public static final int RECORD_LENGTH = 350;

    /** Byte offset of {@code TRAN-ID} within the record. */
    public static final int TRAN_ID_OFFSET = 0;
    /** Byte length of {@code TRAN-ID PIC X(16)}. */
    public static final int TRAN_ID_LENGTH = 16;

    /** Byte offset of {@code TRAN-TYPE-CD} within the record. */
    public static final int TRAN_TYPE_CD_OFFSET = 16;
    /** Byte length of {@code TRAN-TYPE-CD PIC X(02)}. */
    public static final int TRAN_TYPE_CD_LENGTH = 2;

    /** Byte offset of {@code TRAN-CAT-CD} within the record. */
    public static final int TRAN_CAT_CD_OFFSET = 18;
    /** Byte length of {@code TRAN-CAT-CD PIC 9(04)}. */
    public static final int TRAN_CAT_CD_LENGTH = 4;

    /** Byte offset of {@code TRAN-SOURCE} within the record. */
    public static final int TRAN_SOURCE_OFFSET = 22;
    /** Byte length of {@code TRAN-SOURCE PIC X(10)}. */
    public static final int TRAN_SOURCE_LENGTH = 10;

    /** Byte offset of {@code TRAN-DESC} within the record. */
    public static final int TRAN_DESC_OFFSET = 32;
    /** Byte length of {@code TRAN-DESC PIC X(100)}. */
    public static final int TRAN_DESC_LENGTH = 100;

    /** Byte offset of {@code TRAN-AMT} within the record. */
    public static final int TRAN_AMT_OFFSET = 132;
    /** Byte length of {@code TRAN-AMT PIC S9(09)V99} (zoned-decimal, 9 integer + 2 decimal digits). */
    public static final int TRAN_AMT_LENGTH = 11;
    /** Implicit decimal places ({@code scale}) for {@code TRAN-AMT}, per the {@code V99} suffix. */
    public static final int TRAN_AMT_SCALE = 2;

    /** Byte offset of {@code TRAN-MERCHANT-ID} within the record. */
    public static final int TRAN_MERCHANT_ID_OFFSET = 143;
    /** Byte length of {@code TRAN-MERCHANT-ID PIC 9(09)}. */
    public static final int TRAN_MERCHANT_ID_LENGTH = 9;

    /** Byte offset of {@code TRAN-MERCHANT-NAME} within the record. */
    public static final int TRAN_MERCHANT_NAME_OFFSET = 152;
    /** Byte length of {@code TRAN-MERCHANT-NAME PIC X(50)}. */
    public static final int TRAN_MERCHANT_NAME_LENGTH = 50;

    /** Byte offset of {@code TRAN-MERCHANT-CITY} within the record. */
    public static final int TRAN_MERCHANT_CITY_OFFSET = 202;
    /** Byte length of {@code TRAN-MERCHANT-CITY PIC X(50)}. */
    public static final int TRAN_MERCHANT_CITY_LENGTH = 50;

    /** Byte offset of {@code TRAN-MERCHANT-ZIP} within the record. */
    public static final int TRAN_MERCHANT_ZIP_OFFSET = 252;
    /** Byte length of {@code TRAN-MERCHANT-ZIP PIC X(10)}. */
    public static final int TRAN_MERCHANT_ZIP_LENGTH = 10;

    /** Byte offset of {@code TRAN-CARD-NUM} within the record. */
    public static final int TRAN_CARD_NUM_OFFSET = 262;
    /** Byte length of {@code TRAN-CARD-NUM PIC X(16)}. */
    public static final int TRAN_CARD_NUM_LENGTH = 16;

    /** Byte offset of {@code TRAN-ORIG-TS} within the record. */
    public static final int TRAN_ORIG_TS_OFFSET = 278;
    /** Byte length of {@code TRAN-ORIG-TS PIC X(26)} (timestamp string). */
    public static final int TRAN_ORIG_TS_LENGTH = 26;

    /** Byte offset of {@code TRAN-PROC-TS} within the record. */
    public static final int TRAN_PROC_TS_OFFSET = 304;
    /** Byte length of {@code TRAN-PROC-TS PIC X(26)} (timestamp string). */
    public static final int TRAN_PROC_TS_LENGTH = 26;

    /** Byte offset of the trailing {@code FILLER} within the record. */
    public static final int FILLER_OFFSET = 330;
    /** Byte length of the trailing {@code FILLER PIC X(20)}. */
    public static final int FILLER_LENGTH = 20;

    // ---------------------------------------------------------------------
    // Internal constants — not part of the public schema.
    // ---------------------------------------------------------------------

    /**
     * Maximum numeric value representable in {@code TRAN-CAT-CD PIC 9(04)}.
     * Equal to {@code 10^TRAN_CAT_CD_LENGTH - 1} = {@code 9999}.
     */
    private static final int TRAN_CAT_CD_MAX = 9999;

    /**
     * Maximum numeric value representable in {@code TRAN-MERCHANT-ID PIC 9(09)}.
     * Equal to {@code 10^TRAN_MERCHANT_ID_LENGTH - 1} = {@code 999_999_999L}.
     */
    private static final long TRAN_MERCHANT_ID_MAX = 999_999_999L;

    /**
     * COBOL {@code PIC X(26)} timestamp pattern: ISO-like with six-digit
     * fractional seconds, e.g. {@code "2022-06-10 19:27:53.000000"}. This
     * is the canonical format emitted by the COBOL {@code TRAN-ORIG-TS}
     * and {@code TRAN-PROC-TS} fields per AAP &sect;0.6.4.
     */
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /** ASCII space byte (0x20) used for right-padding {@code PIC X(n)} fields. */
    private static final byte SPACE = (byte) 0x20;

    /** Number of trailing digits revealed by {@link #maskedPan()}. */
    private static final int PAN_VISIBLE_TAIL = 4;

    // ---------------------------------------------------------------------
    // Convenience factory helpers — supporting construction from
    // upstream code paths that already work with String timestamps.
    // ---------------------------------------------------------------------

    /**
     * Returns a freshly allocated, space-filled byte array of exactly
     * {@link #FILLER_LENGTH} bytes, suitable for the {@code filler}
     * component of a newly constructed {@code TranRecord} that has no
     * preserved trailer bytes (e.g., a record materialized inside a
     * batch posting paragraph rather than parsed from a COBOL buffer).
     *
     * @return ASCII-space-filled 20-byte array
     */
    public static byte[] emptyFiller() {
        byte[] filler = new byte[FILLER_LENGTH];
        Arrays.fill(filler, SPACE);
        return filler;
    }

    /**
     * Parses a {@code PIC X(26)} timestamp string into a
     * {@link LocalDateTime}. {@code null} or all-blank input returns
     * {@code null} (the COBOL all-spaces sentinel for an unset
     * timestamp).
     *
     * <p>This helper is exposed for upstream application code that
     * receives the timestamp in {@link String} form (e.g., from a BMS
     * input map or from a sibling record whose schema still carries the
     * field as a {@link String}). The expected pattern is
     * {@code yyyy-MM-dd HH:mm:ss.SSSSSS}.
     *
     * @param raw raw timestamp string (may be {@code null} or blank)
     * @return parsed {@link LocalDateTime} or {@code null}
     * @throws IllegalArgumentException if {@code raw} is non-blank and
     *         does not match the expected pattern
     */
    public static LocalDateTime parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw, TS_FMT);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "invalid timestamp string '" + raw
                            + "' (expected pattern 'yyyy-MM-dd HH:mm:ss.SSSSSS')",
                    ex);
        }
    }

    /**
     * Formats a {@link LocalDateTime} into the canonical COBOL
     * {@code PIC X(26)} timestamp string. {@code null} input returns
     * an all-spaces 26-byte string, matching the COBOL blank sentinel.
     *
     * @param ts {@link LocalDateTime} or {@code null}
     * @return 26-character timestamp string
     */
    public static String formatTimestamp(LocalDateTime ts) {
        if (ts == null) {
            return " ".repeat(TRAN_ORIG_TS_LENGTH);
        }
        return ts.format(TS_FMT);
    }

    // ---------------------------------------------------------------------
    // Compact canonical constructor (JEP 513 flexible constructor bodies).
    // Validation runs before binding; defensive copy of mutable inputs is
    // performed in the same block to preserve record immutability.
    // ---------------------------------------------------------------------

    /**
     * Compact canonical constructor. Validates field constraints and
     * normalizes the monetary {@code tranAmt} to {@link #TRAN_AMT_SCALE}
     * with banker's rounding ({@link RoundingMode#HALF_EVEN}) per the
     * COBOL {@code ROUNDED} clause convention (AAP &sect;0.6.1).
     *
     * <p>Constraints enforced:
     * <ul>
     *   <li>All non-timestamp {@link String} components are non-null and
     *       no longer than their {@code PIC X(n)} byte budget; longer
     *       strings throw {@link IllegalArgumentException} to surface the
     *       encoding error early rather than silently truncating.</li>
     *   <li>{@code tranCatCd} is in {@code [0, 9999]} (the
     *       {@code PIC 9(04)} range).</li>
     *   <li>{@code tranMerchantId} is in {@code [0, 999_999_999]} (the
     *       {@code PIC 9(09)} range).</li>
     *   <li>{@code tranAmt} is non-null and normalized to scale 2 with
     *       banker's rounding.</li>
     *   <li>{@code filler} is exactly {@link #FILLER_LENGTH} bytes; a
     *       defensive copy is taken.</li>
     *   <li>{@code tranOrigTs} and {@code tranProcTs} may be {@code null}
     *       (representing the COBOL all-spaces sentinel for an unset
     *       timestamp); no other validation applies.</li>
     * </ul>
     *
     * @throws NullPointerException if any required non-timestamp component
     *         is {@code null}
     * @throws IllegalArgumentException if any length, range, or scale
     *         constraint is violated
     */
    public TranRecord {
        Objects.requireNonNull(tranId, "tranId");
        Objects.requireNonNull(tranTypeCd, "tranTypeCd");
        Objects.requireNonNull(tranSource, "tranSource");
        Objects.requireNonNull(tranDesc, "tranDesc");
        Objects.requireNonNull(tranAmt, "tranAmt");
        Objects.requireNonNull(tranMerchantName, "tranMerchantName");
        Objects.requireNonNull(tranMerchantCity, "tranMerchantCity");
        Objects.requireNonNull(tranMerchantZip, "tranMerchantZip");
        Objects.requireNonNull(tranCardNum, "tranCardNum");
        Objects.requireNonNull(filler, "filler");

        if (tranId.length() > TRAN_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "tranId length " + tranId.length() + " exceeds " + TRAN_ID_LENGTH);
        }
        if (tranTypeCd.length() > TRAN_TYPE_CD_LENGTH) {
            throw new IllegalArgumentException(
                    "tranTypeCd length " + tranTypeCd.length() + " exceeds " + TRAN_TYPE_CD_LENGTH);
        }
        if (tranCatCd < 0 || tranCatCd > TRAN_CAT_CD_MAX) {
            throw new IllegalArgumentException(
                    "tranCatCd must be 0.." + TRAN_CAT_CD_MAX + ", got " + tranCatCd);
        }
        if (tranSource.length() > TRAN_SOURCE_LENGTH) {
            throw new IllegalArgumentException(
                    "tranSource length " + tranSource.length() + " exceeds " + TRAN_SOURCE_LENGTH);
        }
        if (tranDesc.length() > TRAN_DESC_LENGTH) {
            throw new IllegalArgumentException(
                    "tranDesc length " + tranDesc.length() + " exceeds " + TRAN_DESC_LENGTH);
        }
        if (tranMerchantId < 0L || tranMerchantId > TRAN_MERCHANT_ID_MAX) {
            throw new IllegalArgumentException(
                    "tranMerchantId must be 0.." + TRAN_MERCHANT_ID_MAX
                            + ", got " + tranMerchantId);
        }
        if (tranMerchantName.length() > TRAN_MERCHANT_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "tranMerchantName length " + tranMerchantName.length()
                            + " exceeds " + TRAN_MERCHANT_NAME_LENGTH);
        }
        if (tranMerchantCity.length() > TRAN_MERCHANT_CITY_LENGTH) {
            throw new IllegalArgumentException(
                    "tranMerchantCity length " + tranMerchantCity.length()
                            + " exceeds " + TRAN_MERCHANT_CITY_LENGTH);
        }
        if (tranMerchantZip.length() > TRAN_MERCHANT_ZIP_LENGTH) {
            throw new IllegalArgumentException(
                    "tranMerchantZip length " + tranMerchantZip.length()
                            + " exceeds " + TRAN_MERCHANT_ZIP_LENGTH);
        }
        if (tranCardNum.length() > TRAN_CARD_NUM_LENGTH) {
            throw new IllegalArgumentException(
                    "tranCardNum length " + tranCardNum.length()
                            + " exceeds " + TRAN_CARD_NUM_LENGTH);
        }
        if (filler.length != FILLER_LENGTH) {
            throw new IllegalArgumentException(
                    "filler must be exactly " + FILLER_LENGTH + " bytes, got " + filler.length);
        }

        // Normalize the monetary amount to the canonical V99 scale using
        // banker's rounding per AAP §0.6.1. setScale guarantees that a
        // value of "1.20" remains "1.20" (not "1.2") to preserve byte-
        // identical encode output per the AAP §0.1.3 scale-preservation
        // requirement.
        tranAmt = tranAmt.setScale(TRAN_AMT_SCALE, RoundingMode.HALF_EVEN);

        // Defensive copy of the mutable byte array so the record is
        // truly immutable (records normally bind references directly).
        filler = filler.clone();
    }

    // ---------------------------------------------------------------------
    // Defensive accessor for the mutable byte[] component.
    // ---------------------------------------------------------------------

    /**
     * Returns a defensive copy of the 20-byte {@code FILLER} payload so
     * that the record remains immutable.
     *
     * @return a fresh {@code byte[FILLER_LENGTH]} copy of the filler bytes
     */
    @Override
    public byte[] filler() {
        return filler.clone();
    }

    // ---------------------------------------------------------------------
    // PAN masking and toString — PCI-aware logging per AAP §0.7.2.
    // ---------------------------------------------------------------------

    /**
     * Returns the Primary Account Number ({@code TRAN-CARD-NUM}) masked
     * to show only the last {@value #PAN_VISIBLE_TAIL} characters. Used
     * by {@link #toString()} and intended for any log output produced by
     * the {@code carddemo-application} layer.
     *
     * <p>Behavior:
     * <ul>
     *   <li>If the trimmed PAN is at most {@value #PAN_VISIBLE_TAIL}
     *       characters, the entire PAN is returned (already short
     *       enough that masking carries no benefit).</li>
     *   <li>Otherwise the leading characters are replaced with
     *       {@code '*'} and the trailing {@value #PAN_VISIBLE_TAIL}
     *       characters are preserved (e.g.,
     *       {@code "4111111111111234"} becomes
     *       {@code "************1234"}).</li>
     * </ul>
     *
     * @return masked PAN suitable for logs and stringification
     */
    public String maskedPan() {
        String trimmed = tranCardNum.trim();
        if (trimmed.length() <= PAN_VISIBLE_TAIL) {
            return trimmed;
        }
        int maskLen = trimmed.length() - PAN_VISIBLE_TAIL;
        return "*".repeat(maskLen) + trimmed.substring(maskLen);
    }

    /**
     * Returns a PCI-aware string representation. The full PAN is NEVER
     * printed; only {@link #maskedPan()} appears. The 20-byte
     * {@code FILLER} is summarized as {@code <20 bytes>} to keep the
     * representation human-readable while still indicating the field is
     * present.
     *
     * @return a masked, human-readable description of this record
     */
    @Override
    public String toString() {
        return "TranRecord[tranId=" + tranId
                + ", tranTypeCd=" + tranTypeCd
                + ", tranCatCd=" + tranCatCd
                + ", tranSource=" + tranSource
                + ", tranAmt=" + tranAmt
                + ", tranMerchantId=" + tranMerchantId
                + ", tranCardNum=" + maskedPan()
                + ", tranOrigTs=" + tranOrigTs
                + ", tranProcTs=" + tranProcTs
                + ", filler=<" + FILLER_LENGTH + " bytes>]";
    }

    // ---------------------------------------------------------------------
    // equals / hashCode — value semantics with BigDecimal numeric equality
    // and byte-array content equality (the record-generated defaults would
    // use BigDecimal.equals (scale-sensitive) and reference equality on
    // the byte[], which is wrong for our value-type contract).
    // ---------------------------------------------------------------------

    /**
     * Value equality comparing each component by content. {@link BigDecimal}
     * is compared via {@link BigDecimal#compareTo(BigDecimal)} so that
     * {@code 1.20} and {@code 1.2} are considered equal (the record's
     * canonical constructor normalizes scale, so this is rarely
     * observable, but the override is defensive). The {@code filler}
     * byte array is compared by element via
     * {@link Arrays#equals(byte[], byte[])}. {@link LocalDateTime}
     * components use {@link Objects#equals(Object, Object)} to handle
     * the all-spaces {@code null} sentinel safely.
     *
     * @param o the object to compare against
     * @return {@code true} iff {@code o} is a {@code TranRecord} with the
     *         same component values
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TranRecord that)) {
            return false;
        }
        return tranCatCd == that.tranCatCd
                && tranMerchantId == that.tranMerchantId
                && tranId.equals(that.tranId)
                && tranTypeCd.equals(that.tranTypeCd)
                && tranSource.equals(that.tranSource)
                && tranDesc.equals(that.tranDesc)
                && tranAmt.compareTo(that.tranAmt) == 0
                && tranMerchantName.equals(that.tranMerchantName)
                && tranMerchantCity.equals(that.tranMerchantCity)
                && tranMerchantZip.equals(that.tranMerchantZip)
                && tranCardNum.equals(that.tranCardNum)
                && Objects.equals(tranOrigTs, that.tranOrigTs)
                && Objects.equals(tranProcTs, that.tranProcTs)
                && Arrays.equals(filler, that.filler);
    }

    /**
     * Hash code consistent with {@link #equals(Object)}. {@link BigDecimal}
     * uses {@link BigDecimal#stripTrailingZeros()} hashing so that values
     * that compare equal via {@code compareTo} also share a hash bucket.
     *
     * @return content-based hash code
     */
    @Override
    public int hashCode() {
        int result = tranId.hashCode();
        result = 31 * result + tranTypeCd.hashCode();
        result = 31 * result + Integer.hashCode(tranCatCd);
        result = 31 * result + tranSource.hashCode();
        result = 31 * result + tranDesc.hashCode();
        result = 31 * result + tranAmt.stripTrailingZeros().hashCode();
        result = 31 * result + Long.hashCode(tranMerchantId);
        result = 31 * result + tranMerchantName.hashCode();
        result = 31 * result + tranMerchantCity.hashCode();
        result = 31 * result + tranMerchantZip.hashCode();
        result = 31 * result + tranCardNum.hashCode();
        result = 31 * result + Objects.hashCode(tranOrigTs);
        result = 31 * result + Objects.hashCode(tranProcTs);
        result = 31 * result + Arrays.hashCode(filler);
        return result;
    }

    // ---------------------------------------------------------------------
    // parse(byte[]) — static factory producing a TranRecord from a
    // 350-byte buffer that follows the CVTRA05Y layout. The byte buffer is
    // expected to use ASCII encoding (production EBCDIC is transcoded by
    // the EbcdicTranscoder adapter in carddemo-adapter-file before reaching
    // this method).
    // ---------------------------------------------------------------------

    /**
     * Parses a 350-byte ASCII buffer laid out per
     * {@code app/cpy/CVTRA05Y.cpy} and returns a corresponding
     * {@link TranRecord}.
     *
     * <p>The signed monetary {@code TRAN-AMT} is decoded via
     * {@link Decimals#parseZonedDecimal(byte[], int, int, int)} (USAGE
     * DISPLAY zoned-decimal with sign overpunch on the last byte). The
     * timestamp fields are decoded with
     * {@link DateTimeFormatter#ofPattern(String)} using pattern
     * {@code yyyy-MM-dd HH:mm:ss.SSSSSS}; a field containing only ASCII
     * spaces (the COBOL all-blanks sentinel) decodes to {@code null}.
     *
     * <p>This method is the inverse of {@link #encode()}: for any
     * {@code TranRecord} {@code r} produced by parsing a COBOL-emitted
     * buffer {@code b}, {@code r.encode()} equals {@code b} byte-for-byte
     * (subject to the canonicalization notes in
     * {@link Decimals#encodeZonedDecimal(BigDecimal, int, int)}).
     *
     * @param buffer ASCII-encoded fixed-width record (must be exactly
     *               {@link #RECORD_LENGTH} bytes)
     * @return decoded {@link TranRecord}
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code buffer.length !=
     *         RECORD_LENGTH}, a numeric field contains a non-digit, the
     *         TRAN-AMT zoned-decimal is malformed, or a timestamp string
     *         is non-blank and does not match the expected pattern
     */
    public static TranRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "TranRecord buffer must be exactly " + RECORD_LENGTH
                            + " bytes; got " + buffer.length);
        }

        String tranId = readAscii(buffer, TRAN_ID_OFFSET, TRAN_ID_LENGTH);
        String tranTypeCd = readAscii(buffer, TRAN_TYPE_CD_OFFSET, TRAN_TYPE_CD_LENGTH);
        int tranCatCd = readUnsignedInt(buffer, TRAN_CAT_CD_OFFSET, TRAN_CAT_CD_LENGTH);
        String tranSource = readAscii(buffer, TRAN_SOURCE_OFFSET, TRAN_SOURCE_LENGTH);
        String tranDesc = readAscii(buffer, TRAN_DESC_OFFSET, TRAN_DESC_LENGTH);

        BigDecimal tranAmt = Decimals.parseZonedDecimal(
                buffer, TRAN_AMT_OFFSET, TRAN_AMT_LENGTH, TRAN_AMT_SCALE);

        long tranMerchantId = readUnsignedLong(
                buffer, TRAN_MERCHANT_ID_OFFSET, TRAN_MERCHANT_ID_LENGTH);
        String tranMerchantName = readAscii(
                buffer, TRAN_MERCHANT_NAME_OFFSET, TRAN_MERCHANT_NAME_LENGTH);
        String tranMerchantCity = readAscii(
                buffer, TRAN_MERCHANT_CITY_OFFSET, TRAN_MERCHANT_CITY_LENGTH);
        String tranMerchantZip = readAscii(
                buffer, TRAN_MERCHANT_ZIP_OFFSET, TRAN_MERCHANT_ZIP_LENGTH);
        String tranCardNum = readAscii(
                buffer, TRAN_CARD_NUM_OFFSET, TRAN_CARD_NUM_LENGTH);

        LocalDateTime tranOrigTs = parseTimestamp(
                buffer, TRAN_ORIG_TS_OFFSET, TRAN_ORIG_TS_LENGTH, "tranOrigTs");
        LocalDateTime tranProcTs = parseTimestamp(
                buffer, TRAN_PROC_TS_OFFSET, TRAN_PROC_TS_LENGTH, "tranProcTs");

        byte[] filler = Arrays.copyOfRange(
                buffer, FILLER_OFFSET, FILLER_OFFSET + FILLER_LENGTH);

        return new TranRecord(
                tranId, tranTypeCd, tranCatCd, tranSource, tranDesc,
                tranAmt, tranMerchantId, tranMerchantName, tranMerchantCity,
                tranMerchantZip, tranCardNum, tranOrigTs, tranProcTs, filler);
    }

    // ---------------------------------------------------------------------
    // encode() — produces a 350-byte ASCII buffer following the CVTRA05Y
    // layout. All PIC X(n) fields are right-space-padded; PIC 9(n) fields
    // are left-zero-padded; PIC S9(09)V99 (TRAN-AMT) is delegated to the
    // Decimals codec.
    // ---------------------------------------------------------------------

    /**
     * Encodes this record as a 350-byte ASCII buffer laid out per
     * {@code app/cpy/CVTRA05Y.cpy}.
     *
     * <p>Padding rules (AAP &sect;0.6.5 byte-for-byte fidelity):
     * <ul>
     *   <li>{@code PIC X(n)} alphanumeric fields are right-padded with
     *       ASCII space ({@code 0x20}) when shorter than {@code n}.</li>
     *   <li>{@code PIC 9(n)} unsigned numeric fields are left-padded with
     *       ASCII zero ({@code '0'}).</li>
     *   <li>{@code PIC S9(09)V99} ({@code TRAN-AMT}) is encoded via
     *       {@link Decimals#encodeZonedDecimal(BigDecimal, int, int)}
     *       (USAGE DISPLAY zoned-decimal, sign overpunch on the last
     *       byte).</li>
     *   <li>{@link LocalDateTime} timestamps are formatted via the
     *       {@code yyyy-MM-dd HH:mm:ss.SSSSSS} pattern; a {@code null}
     *       timestamp emits 26 ASCII spaces (preserving the COBOL
     *       all-blanks sentinel).</li>
     *   <li>{@code FILLER} is copied byte-for-byte from the record.</li>
     * </ul>
     *
     * @return freshly allocated 350-byte buffer
     */
    public byte[] encode() {
        byte[] out = new byte[RECORD_LENGTH];
        // Pre-fill with ASCII spaces so any field we leave un-written
        // (e.g., null timestamps) is naturally space-padded.
        Arrays.fill(out, SPACE);

        writeAscii(out, tranId, TRAN_ID_OFFSET, TRAN_ID_LENGTH);
        writeAscii(out, tranTypeCd, TRAN_TYPE_CD_OFFSET, TRAN_TYPE_CD_LENGTH);
        writeUnsignedNumeric(out, tranCatCd, TRAN_CAT_CD_OFFSET, TRAN_CAT_CD_LENGTH);
        writeAscii(out, tranSource, TRAN_SOURCE_OFFSET, TRAN_SOURCE_LENGTH);
        writeAscii(out, tranDesc, TRAN_DESC_OFFSET, TRAN_DESC_LENGTH);

        byte[] amtBytes = Decimals.encodeZonedDecimal(tranAmt, TRAN_AMT_LENGTH, TRAN_AMT_SCALE);
        System.arraycopy(amtBytes, 0, out, TRAN_AMT_OFFSET, TRAN_AMT_LENGTH);

        writeUnsignedNumeric(out, tranMerchantId,
                TRAN_MERCHANT_ID_OFFSET, TRAN_MERCHANT_ID_LENGTH);
        writeAscii(out, tranMerchantName,
                TRAN_MERCHANT_NAME_OFFSET, TRAN_MERCHANT_NAME_LENGTH);
        writeAscii(out, tranMerchantCity,
                TRAN_MERCHANT_CITY_OFFSET, TRAN_MERCHANT_CITY_LENGTH);
        writeAscii(out, tranMerchantZip,
                TRAN_MERCHANT_ZIP_OFFSET, TRAN_MERCHANT_ZIP_LENGTH);
        writeAscii(out, tranCardNum,
                TRAN_CARD_NUM_OFFSET, TRAN_CARD_NUM_LENGTH);

        if (tranOrigTs != null) {
            writeAscii(out, tranOrigTs.format(TS_FMT),
                    TRAN_ORIG_TS_OFFSET, TRAN_ORIG_TS_LENGTH);
        }
        if (tranProcTs != null) {
            writeAscii(out, tranProcTs.format(TS_FMT),
                    TRAN_PROC_TS_OFFSET, TRAN_PROC_TS_LENGTH);
        }

        System.arraycopy(filler, 0, out, FILLER_OFFSET, FILLER_LENGTH);
        return out;
    }

    // ---------------------------------------------------------------------
    // Private helpers — fixed-width codec primitives.
    // ---------------------------------------------------------------------

    /**
     * Reads {@code length} ASCII bytes from {@code buffer} starting at
     * {@code offset} and returns them as a {@link String}. The returned
     * string preserves trailing spaces so that re-encoding yields the
     * original byte layout.
     */
    private static String readAscii(byte[] buffer, int offset, int length) {
        return new String(buffer, offset, length, StandardCharsets.US_ASCII);
    }

    /**
     * Reads {@code length} ASCII digit characters from {@code buffer}
     * starting at {@code offset} and parses them as an unsigned integer.
     * All characters must be {@code '0'..'9'} (the COBOL {@code PIC 9(n)}
     * contract); any non-digit raises {@link IllegalArgumentException}.
     *
     * <p>Used only for {@code TRAN-CAT-CD} which fits in {@code int}; for
     * larger fields, see {@link #readUnsignedLong(byte[], int, int)}.
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
     * starting at {@code offset} and parses them as an unsigned long.
     *
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
     * Parses a {@code PIC X(26)} timestamp slice. A field consisting
     * entirely of ASCII spaces decodes to {@code null} (the COBOL
     * all-blanks sentinel for an unset timestamp). Otherwise the slice
     * is decoded with {@link #TS_FMT}.
     *
     * @param buffer    source buffer
     * @param offset    starting byte offset of the timestamp
     * @param length    fixed length (always {@code 26})
     * @param fieldName field name for diagnostic messages
     * @return a {@link LocalDateTime} or {@code null} if the field is blank
     * @throws IllegalArgumentException if the field is non-blank but does
     *         not parse as {@link #TS_FMT}
     */
    private static LocalDateTime parseTimestamp(
            byte[] buffer, int offset, int length, String fieldName) {
        String raw = readAscii(buffer, offset, length);
        if (raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw, TS_FMT);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "invalid " + fieldName + " timestamp '" + raw
                            + "' at offset " + offset
                            + " (expected pattern 'yyyy-MM-dd HH:mm:ss.SSSSSS')",
                    ex);
        }
    }

    /**
     * Writes a {@link String} into {@code out} at {@code offset}, copying
     * up to {@code length} ASCII bytes. Shorter strings are left in place
     * with any pre-existing bytes following (callers must pre-fill the
     * region with spaces when right-space-padding is required).
     *
     * <p>Longer strings would have been rejected by the canonical
     * constructor; this method is defensive and uses
     * {@link Math#min(int, int)} to avoid overflowing the destination
     * window even if a string slipped past validation.
     */
    private static void writeAscii(byte[] out, String value, int offset, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int copyLen = Math.min(bytes.length, length);
        System.arraycopy(bytes, 0, out, offset, copyLen);
        // Any remaining bytes in [offset+copyLen, offset+length) keep the
        // value placed there by the caller's Arrays.fill(SPACE) pre-pass.
    }

    /**
     * Writes a non-negative {@code long} into {@code out} as
     * {@code length} ASCII digit characters, left-zero-padded. Models the
     * COBOL {@code PIC 9(n)} encoding.
     *
     * @throws IllegalArgumentException if {@code value} is negative or
     *         requires more than {@code length} digits
     */
    private static void writeUnsignedNumeric(
            byte[] out, long value, int offset, int length) {
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

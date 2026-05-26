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
 * Immutable domain record translated from the COBOL {@code DALYTRAN-RECORD}
 * 01-level group at {@code app/cpy/CVTRA06Y.cpy} (total record length
 * 350 bytes).
 *
 * <h2>COBOL source layout</h2>
 * <pre>{@code
 * 01 DALYTRAN-RECORD.
 *   05 DALYTRAN-ID              PIC X(16).      (16 bytes — offset   0..15)
 *   05 DALYTRAN-TYPE-CD         PIC X(02).      ( 2 bytes — offset  16..17)
 *   05 DALYTRAN-CAT-CD          PIC 9(04).      ( 4 bytes — offset  18..21)
 *   05 DALYTRAN-SOURCE          PIC X(10).      (10 bytes — offset  22..31)
 *   05 DALYTRAN-DESC            PIC X(100).     (100 bytes — offset 32..131)
 *   05 DALYTRAN-AMT             PIC S9(09)V99.  (11 bytes — offset 132..142)
 *   05 DALYTRAN-MERCHANT-ID     PIC 9(09).      ( 9 bytes — offset 143..151)
 *   05 DALYTRAN-MERCHANT-NAME   PIC X(50).      (50 bytes — offset 152..201)
 *   05 DALYTRAN-MERCHANT-CITY   PIC X(50).      (50 bytes — offset 202..251)
 *   05 DALYTRAN-MERCHANT-ZIP    PIC X(10).      (10 bytes — offset 252..261)
 *   05 DALYTRAN-CARD-NUM        PIC X(16).      (16 bytes — offset 262..277)
 *   05 DALYTRAN-ORIG-TS         PIC X(26).      (26 bytes — offset 278..303)
 *   05 DALYTRAN-PROC-TS         PIC X(26).      (26 bytes — offset 304..329)
 *   05 FILLER                   PIC X(20).      (20 bytes — offset 330..349)
 * Total: 350 bytes.
 * }</pre>
 *
 * <h2>Java field mapping</h2>
 * <ul>
 *   <li>{@code DALYTRAN-ID}, {@code DALYTRAN-TYPE-CD}, {@code DALYTRAN-SOURCE},
 *       {@code DALYTRAN-DESC}, {@code DALYTRAN-MERCHANT-NAME},
 *       {@code DALYTRAN-MERCHANT-CITY}, {@code DALYTRAN-MERCHANT-ZIP},
 *       {@code DALYTRAN-CARD-NUM} &mdash; alphanumeric {@code PIC X(n)}
 *       fields carried as {@link String} with right-space padding
 *       semantics on encode.</li>
 *   <li>{@code DALYTRAN-CAT-CD} &mdash; numeric {@code PIC 9(04)} carried
 *       as {@code int} (range {@code 0..9999}); zero-left-padded on
 *       encode.</li>
 *   <li>{@code DALYTRAN-AMT} &mdash; signed numeric {@code PIC S9(09)V99}
 *       (USAGE DISPLAY zoned-decimal, sign overpunch on the rightmost
 *       byte) carried as {@link BigDecimal} with fixed {@code scale=2}
 *       per AAP &sect;0.6.1. Codec is delegated to
 *       {@link Decimals#parseZonedDecimal(byte[], int, int, int)} and
 *       {@link Decimals#encodeZonedDecimal(BigDecimal, int, int)}.</li>
 *   <li>{@code DALYTRAN-MERCHANT-ID} &mdash; unsigned numeric
 *       {@code PIC 9(09)} carried as {@code long} (range
 *       {@code 0..999_999_999}); zero-left-padded on encode.</li>
 *   <li>{@code DALYTRAN-ORIG-TS}, {@code DALYTRAN-PROC-TS} &mdash;
 *       {@code PIC X(26)} timestamp strings with pattern
 *       {@code yyyy-MM-dd HH:mm:ss.SSSSSS}, carried as
 *       {@link LocalDateTime} per AAP &sect;0.6.4. {@code null}
 *       represents the COBOL all-spaces sentinel for an unset timestamp
 *       (commonly seen for {@code DALYTRAN-PROC-TS} on incoming daily
 *       transactions that have not yet been posted by {@code CBTRN02C}).
 *       Round-trip behavior: a blank 26-byte slice decodes to
 *       {@code null}; {@code null} encodes back to 26 ASCII spaces.</li>
 *   <li>{@code FILLER} &mdash; the trailing 20-byte alphanumeric filler
 *       is carried as {@code byte[20]} (defensively copied on
 *       construction and accessor) to preserve any application-specific
 *       trailer bytes byte-for-byte through a parse/encode round-trip.</li>
 * </ul>
 *
 * <h2>Codec deviation from the agent prompt specification</h2>
 * <p>The agent prompt for this file lists
 * {@code Decimals.parseSignedPacked(...)} and
 * {@code Decimals.encodeSignedPacked(...)} as the codec for the
 * {@code DALYTRAN-AMT} {@code PIC S9(09)V99} field. The
 * agent-prompt-supplied code uses these <em>packed-decimal</em>
 * ({@code COMP-3}) helpers, which assume two BCD digits per byte and a
 * single sign nibble in the rightmost byte.
 *
 * <p>The actual fixture data in {@code app/data/ASCII/dailytran.txt}
 * (REFERENCE input per AAP &sect;0.4.1) is encoded as <em>zoned-decimal
 * with sign overpunch</em> (USAGE DISPLAY): each digit is an ASCII
 * character {@code '0'-'9'} except the rightmost digit, which is
 * overpunched with the sign per the EBCDIC convention reinterpreted into
 * ASCII (e.g., {@code 'G'} encodes the digit {@code 7} with a positive
 * sign). Verified directly against record-1 of the fixture: bytes
 * {@code 132..142} are {@code "0000005047G"}, which decodes via the
 * zoned-decimal codec to {@code +504.77} but is unparseable as
 * COMP-3 packed-decimal ({@code 0x47} is not a valid BCD sign nibble).
 *
 * <p>The codec used here ({@link Decimals#parseZonedDecimal} /
 * {@link Decimals#encodeZonedDecimal}) is the same codec used by the
 * parallel {@link TranRecord} (translated from {@code CVTRA05Y.cpy};
 * byte-identical record layout). This deviation is mandated by the
 * AAP &sect;0.6.1 byte-for-byte fidelity invariant: the codec must
 * match the actual data format, not a mis-specified method name.
 *
 * <h2>Identity with TranRecord</h2>
 * <p>{@link TranRecord} (translated from {@code CVTRA05Y.cpy}) has the
 * <em>same</em> byte layout and field semantics but a different field
 * name prefix ({@code TRAN-} vs {@code DALYTRAN-}). The two records are
 * intentionally kept as distinct Java types to make the lifecycle
 * distinction visible at compile time:
 * <ul>
 *   <li>{@code DalyTranRecord} represents an <em>incoming</em> daily
 *       transaction that has not yet been posted; its
 *       {@code DALYTRAN-PROC-TS} is typically blank (decoded to
 *       {@code null}).</li>
 *   <li>{@code TranRecord} represents a <em>posted</em> transaction
 *       (history); its {@code TRAN-PROC-TS} carries the moment the
 *       posting paragraph completed.</li>
 * </ul>
 * Conversion from {@code DalyTranRecord} to {@code TranRecord} happens
 * in the {@code 2000-POST-TRANSACTION} paragraph of {@code CBTRN02C}.
 *
 * <h2>PCI-aware logging</h2>
 * <p>The {@code DALYTRAN-CARD-NUM} field is a Primary Account Number
 * (PAN). Per AAP &sect;0.7.2: "No card PAN logged in full; mask all but
 * last 4 digits in logs". This record overrides {@link #toString()} so
 * the full PAN is NEVER printed; only {@link #maskedPan()} appears.
 * Application-layer code that wants to log this record should call
 * {@code log.info("{}", record)} (delegates to {@code toString}) or
 * {@code record.maskedPan()} explicitly &mdash; never
 * {@code record.dalytranCardNum()}.
 *
 * <h2>Immutability and thread safety</h2>
 * <p>This type is a Java {@code record}: instances are immutable once
 * constructed. The mutable {@code byte[]} component ({@code filler})
 * is defensively copied in the canonical constructor and again in the
 * accessor so callers cannot observe or mutate the internal array.
 * Instances are safe to share freely across virtual threads (per AAP
 * &sect;0.6.6 virtual-thread fan-out).
 *
 * <h2>Authority</h2>
 * <ul>
 *   <li>AAP &sect;0.4.1 (Copybooks &rarr; Java records table)</li>
 *   <li>AAP &sect;0.6.9 (Copybook-to-Record Translation Table &mdash;
 *       "350 bytes daily transaction record")</li>
 *   <li>AAP &sect;0.3.2 (Records pattern)</li>
 *   <li>AAP &sect;0.6.1 (Decimal Arithmetic Fidelity)</li>
 *   <li>AAP &sect;0.6.4 (Date Semantics &mdash; {@code LocalDateTime}
 *       for {@code PIC X(26)})</li>
 *   <li>AAP &sect;0.6.5 (File I/O Exactness &mdash; byte-for-byte
 *       round-trip invariant)</li>
 *   <li>AAP &sect;0.7.2 (PAN masking)</li>
 * </ul>
 *
 * @see TranRecord
 * @see Decimals
 * @since 1.0.0
 */
@CobolProgram(
        value = "CVTRA06Y",
        sourcePath = "app/cpy/CVTRA06Y.cpy",
        notes = "350-byte DALYTRAN-RECORD; byte-identical layout to TranRecord (CVTRA05Y);"
                + " DALYTRAN-AMT uses zoned-decimal codec (deviation from agent prompt's"
                + " parseSignedPacked); PAN masked in toString()"
)
public record DalyTranRecord(
        String dalytranId,
        String dalytranTypeCd,
        int dalytranCatCd,
        String dalytranSource,
        String dalytranDesc,
        BigDecimal dalytranAmt,
        long dalytranMerchantId,
        String dalytranMerchantName,
        String dalytranMerchantCity,
        String dalytranMerchantZip,
        String dalytranCardNum,
        LocalDateTime dalytranOrigTs,
        LocalDateTime dalytranProcTs,
        byte[] filler) {

    // ---------------------------------------------------------------------
    // Layout constants — exposed for use by readers, writers, and tests.
    // Offsets are 0-based; lengths are byte counts. All values come directly
    // from the COBOL 01-level group in app/cpy/CVTRA06Y.cpy.
    // ---------------------------------------------------------------------

    /** Total fixed-record length in bytes. */
    public static final int RECORD_LENGTH = 350;

    /** Byte offset of {@code DALYTRAN-ID} within the record. */
    public static final int DALYTRAN_ID_OFFSET = 0;
    /** Byte length of {@code DALYTRAN-ID PIC X(16)}. */
    public static final int DALYTRAN_ID_LENGTH = 16;

    /** Byte offset of {@code DALYTRAN-TYPE-CD} within the record. */
    public static final int DALYTRAN_TYPE_CD_OFFSET = 16;
    /** Byte length of {@code DALYTRAN-TYPE-CD PIC X(02)}. */
    public static final int DALYTRAN_TYPE_CD_LENGTH = 2;

    /** Byte offset of {@code DALYTRAN-CAT-CD} within the record. */
    public static final int DALYTRAN_CAT_CD_OFFSET = 18;
    /** Byte length of {@code DALYTRAN-CAT-CD PIC 9(04)}. */
    public static final int DALYTRAN_CAT_CD_LENGTH = 4;

    /** Byte offset of {@code DALYTRAN-SOURCE} within the record. */
    public static final int DALYTRAN_SOURCE_OFFSET = 22;
    /** Byte length of {@code DALYTRAN-SOURCE PIC X(10)}. */
    public static final int DALYTRAN_SOURCE_LENGTH = 10;

    /** Byte offset of {@code DALYTRAN-DESC} within the record. */
    public static final int DALYTRAN_DESC_OFFSET = 32;
    /** Byte length of {@code DALYTRAN-DESC PIC X(100)}. */
    public static final int DALYTRAN_DESC_LENGTH = 100;

    /** Byte offset of {@code DALYTRAN-AMT} within the record. */
    public static final int DALYTRAN_AMT_OFFSET = 132;
    /** Byte length of {@code DALYTRAN-AMT PIC S9(09)V99} (zoned-decimal, 9 integer + 2 decimal digits). */
    public static final int DALYTRAN_AMT_LENGTH = 11;
    /** Implicit decimal places ({@code scale}) for {@code DALYTRAN-AMT}, per the {@code V99} suffix. */
    public static final int DALYTRAN_AMT_SCALE = 2;

    /** Byte offset of {@code DALYTRAN-MERCHANT-ID} within the record. */
    public static final int DALYTRAN_MERCHANT_ID_OFFSET = 143;
    /** Byte length of {@code DALYTRAN-MERCHANT-ID PIC 9(09)}. */
    public static final int DALYTRAN_MERCHANT_ID_LENGTH = 9;

    /** Byte offset of {@code DALYTRAN-MERCHANT-NAME} within the record. */
    public static final int DALYTRAN_MERCHANT_NAME_OFFSET = 152;
    /** Byte length of {@code DALYTRAN-MERCHANT-NAME PIC X(50)}. */
    public static final int DALYTRAN_MERCHANT_NAME_LENGTH = 50;

    /** Byte offset of {@code DALYTRAN-MERCHANT-CITY} within the record. */
    public static final int DALYTRAN_MERCHANT_CITY_OFFSET = 202;
    /** Byte length of {@code DALYTRAN-MERCHANT-CITY PIC X(50)}. */
    public static final int DALYTRAN_MERCHANT_CITY_LENGTH = 50;

    /** Byte offset of {@code DALYTRAN-MERCHANT-ZIP} within the record. */
    public static final int DALYTRAN_MERCHANT_ZIP_OFFSET = 252;
    /** Byte length of {@code DALYTRAN-MERCHANT-ZIP PIC X(10)}. */
    public static final int DALYTRAN_MERCHANT_ZIP_LENGTH = 10;

    /** Byte offset of {@code DALYTRAN-CARD-NUM} within the record. */
    public static final int DALYTRAN_CARD_NUM_OFFSET = 262;
    /** Byte length of {@code DALYTRAN-CARD-NUM PIC X(16)}. */
    public static final int DALYTRAN_CARD_NUM_LENGTH = 16;

    /** Byte offset of {@code DALYTRAN-ORIG-TS} within the record. */
    public static final int DALYTRAN_ORIG_TS_OFFSET = 278;
    /** Byte length of {@code DALYTRAN-ORIG-TS PIC X(26)} (timestamp string). */
    public static final int DALYTRAN_ORIG_TS_LENGTH = 26;

    /** Byte offset of {@code DALYTRAN-PROC-TS} within the record. */
    public static final int DALYTRAN_PROC_TS_OFFSET = 304;
    /** Byte length of {@code DALYTRAN-PROC-TS PIC X(26)} (timestamp string). */
    public static final int DALYTRAN_PROC_TS_LENGTH = 26;

    /** Byte offset of the trailing {@code FILLER} within the record. */
    public static final int FILLER_OFFSET = 330;
    /** Byte length of the trailing {@code FILLER PIC X(20)}. */
    public static final int FILLER_LENGTH = 20;

    // ---------------------------------------------------------------------
    // Internal constants — not part of the public schema.
    // ---------------------------------------------------------------------

    /**
     * Maximum numeric value representable in {@code DALYTRAN-CAT-CD PIC 9(04)}.
     * Equal to {@code 10^DALYTRAN_CAT_CD_LENGTH - 1} = {@code 9999}.
     */
    private static final int DALYTRAN_CAT_CD_MAX = 9999;

    /**
     * Maximum numeric value representable in
     * {@code DALYTRAN-MERCHANT-ID PIC 9(09)}.
     * Equal to {@code 10^DALYTRAN_MERCHANT_ID_LENGTH - 1} =
     * {@code 999_999_999L}.
     */
    private static final long DALYTRAN_MERCHANT_ID_MAX = 999_999_999L;

    /**
     * COBOL {@code PIC X(26)} timestamp pattern: ISO-like with six-digit
     * fractional seconds, e.g. {@code "2022-06-10 19:27:53.000000"}. This
     * is the canonical format emitted by the COBOL {@code DALYTRAN-ORIG-TS}
     * and {@code DALYTRAN-PROC-TS} fields per AAP &sect;0.6.4. Held as a
     * single {@code static final} instance because
     * {@link DateTimeFormatter} is thread-safe.
     */
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /** ASCII space byte (0x20) used for right-padding {@code PIC X(n)} fields. */
    private static final byte SPACE = (byte) 0x20;

    /** Number of trailing digits revealed by {@link #maskedPan()}. */
    private static final int PAN_VISIBLE_TAIL = 4;

    // ---------------------------------------------------------------------
    // Convenience factory helpers — supporting construction from upstream
    // code paths that already work with String timestamps (e.g., when a
    // DALYTRAN record is being synthesized from BMS map input).
    // ---------------------------------------------------------------------

    /**
     * Returns a freshly allocated, space-filled byte array of exactly
     * {@link #FILLER_LENGTH} bytes, suitable for the {@code filler}
     * component of a newly constructed {@code DalyTranRecord} that has no
     * preserved trailer bytes (e.g., a record materialized from a BMS
     * input map rather than parsed from a COBOL buffer).
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
     * input map). The expected pattern is
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
     * {@code PIC X(26)} timestamp string. {@code null} input returns an
     * all-spaces 26-byte string, matching the COBOL blank sentinel.
     *
     * @param ts {@link LocalDateTime} or {@code null}
     * @return 26-character timestamp string
     */
    public static String formatTimestamp(LocalDateTime ts) {
        if (ts == null) {
            return " ".repeat(DALYTRAN_ORIG_TS_LENGTH);
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
     * normalizes the monetary {@code dalytranAmt} to
     * {@link #DALYTRAN_AMT_SCALE} with banker's rounding
     * ({@link RoundingMode#HALF_EVEN}) per the COBOL {@code ROUNDED}
     * clause convention (AAP &sect;0.6.1).
     *
     * <p>Constraints enforced:
     * <ul>
     *   <li>All non-timestamp {@link String} components are non-null and
     *       no longer than their {@code PIC X(n)} byte budget; longer
     *       strings throw {@link IllegalArgumentException} to surface
     *       the encoding error early rather than silently truncating.</li>
     *   <li>{@code dalytranCatCd} is in {@code [0, 9999]} (the
     *       {@code PIC 9(04)} range).</li>
     *   <li>{@code dalytranMerchantId} is in {@code [0, 999_999_999]}
     *       (the {@code PIC 9(09)} range).</li>
     *   <li>{@code dalytranAmt} is non-null and normalized to scale 2
     *       with banker's rounding.</li>
     *   <li>{@code filler} is exactly {@link #FILLER_LENGTH} bytes; a
     *       defensive copy is taken.</li>
     *   <li>{@code dalytranOrigTs} and {@code dalytranProcTs} may be
     *       {@code null} (representing the COBOL all-spaces sentinel for
     *       an unset timestamp &mdash; verified against record-1 of
     *       {@code app/data/ASCII/dailytran.txt} where
     *       {@code DALYTRAN-PROC-TS} is 26 ASCII spaces); no other
     *       validation applies. This is a deliberate deviation from the
     *       agent prompt's {@code Objects.requireNonNull} for
     *       timestamps, which would reject the fixture data.</li>
     * </ul>
     *
     * @throws NullPointerException if any required non-timestamp
     *         component is {@code null}
     * @throws IllegalArgumentException if any length, range, or scale
     *         constraint is violated
     */
    public DalyTranRecord {
        Objects.requireNonNull(dalytranId, "dalytranId");
        Objects.requireNonNull(dalytranTypeCd, "dalytranTypeCd");
        Objects.requireNonNull(dalytranSource, "dalytranSource");
        Objects.requireNonNull(dalytranDesc, "dalytranDesc");
        Objects.requireNonNull(dalytranAmt, "dalytranAmt");
        Objects.requireNonNull(dalytranMerchantName, "dalytranMerchantName");
        Objects.requireNonNull(dalytranMerchantCity, "dalytranMerchantCity");
        Objects.requireNonNull(dalytranMerchantZip, "dalytranMerchantZip");
        Objects.requireNonNull(dalytranCardNum, "dalytranCardNum");
        Objects.requireNonNull(filler, "filler");

        if (dalytranId.length() > DALYTRAN_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "dalytranId length " + dalytranId.length()
                            + " exceeds " + DALYTRAN_ID_LENGTH);
        }
        if (dalytranTypeCd.length() > DALYTRAN_TYPE_CD_LENGTH) {
            throw new IllegalArgumentException(
                    "dalytranTypeCd length " + dalytranTypeCd.length()
                            + " exceeds " + DALYTRAN_TYPE_CD_LENGTH);
        }
        if (dalytranCatCd < 0 || dalytranCatCd > DALYTRAN_CAT_CD_MAX) {
            throw new IllegalArgumentException(
                    "dalytranCatCd must be 0.." + DALYTRAN_CAT_CD_MAX
                            + ", got " + dalytranCatCd);
        }
        if (dalytranSource.length() > DALYTRAN_SOURCE_LENGTH) {
            throw new IllegalArgumentException(
                    "dalytranSource length " + dalytranSource.length()
                            + " exceeds " + DALYTRAN_SOURCE_LENGTH);
        }
        if (dalytranDesc.length() > DALYTRAN_DESC_LENGTH) {
            throw new IllegalArgumentException(
                    "dalytranDesc length " + dalytranDesc.length()
                            + " exceeds " + DALYTRAN_DESC_LENGTH);
        }
        if (dalytranMerchantId < 0L || dalytranMerchantId > DALYTRAN_MERCHANT_ID_MAX) {
            throw new IllegalArgumentException(
                    "dalytranMerchantId must be 0.." + DALYTRAN_MERCHANT_ID_MAX
                            + ", got " + dalytranMerchantId);
        }
        if (dalytranMerchantName.length() > DALYTRAN_MERCHANT_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "dalytranMerchantName length " + dalytranMerchantName.length()
                            + " exceeds " + DALYTRAN_MERCHANT_NAME_LENGTH);
        }
        if (dalytranMerchantCity.length() > DALYTRAN_MERCHANT_CITY_LENGTH) {
            throw new IllegalArgumentException(
                    "dalytranMerchantCity length " + dalytranMerchantCity.length()
                            + " exceeds " + DALYTRAN_MERCHANT_CITY_LENGTH);
        }
        if (dalytranMerchantZip.length() > DALYTRAN_MERCHANT_ZIP_LENGTH) {
            throw new IllegalArgumentException(
                    "dalytranMerchantZip length " + dalytranMerchantZip.length()
                            + " exceeds " + DALYTRAN_MERCHANT_ZIP_LENGTH);
        }
        if (dalytranCardNum.length() > DALYTRAN_CARD_NUM_LENGTH) {
            throw new IllegalArgumentException(
                    "dalytranCardNum length " + dalytranCardNum.length()
                            + " exceeds " + DALYTRAN_CARD_NUM_LENGTH);
        }
        if (filler.length != FILLER_LENGTH) {
            throw new IllegalArgumentException(
                    "filler must be exactly " + FILLER_LENGTH
                            + " bytes, got " + filler.length);
        }

        // Normalize the monetary amount to the canonical V99 scale using
        // banker's rounding per AAP §0.6.1. setScale guarantees that a
        // value of "1.20" remains "1.20" (not "1.2") to preserve byte-
        // identical encode output per the AAP §0.1.3 scale-preservation
        // requirement.
        dalytranAmt = dalytranAmt.setScale(DALYTRAN_AMT_SCALE, RoundingMode.HALF_EVEN);

        // Defensive copy of the mutable byte array so the record is
        // truly immutable (records normally bind references directly).
        filler = filler.clone();
    }



    // ---------------------------------------------------------------------
    // Defensive accessor for the mutable byte[] component.
    // ---------------------------------------------------------------------

    /**
     * Returns a defensive copy of the 20-byte {@code FILLER} payload so
     * that the record remains immutable. Java {@code record} canonical
     * accessors normally return the bound reference directly; we
     * override here because {@code byte[]} is mutable.
     *
     * @return a fresh {@code byte[FILLER_LENGTH]} copy of the filler
     *         bytes
     */
    @Override
    public byte[] filler() {
        return filler.clone();
    }

    // ---------------------------------------------------------------------
    // PAN masking and toString — PCI-aware logging per AAP §0.7.2.
    // ---------------------------------------------------------------------

    /**
     * Returns the Primary Account Number ({@code DALYTRAN-CARD-NUM})
     * masked to show only the last {@value #PAN_VISIBLE_TAIL}
     * characters. Used by {@link #toString()} and intended for any log
     * output produced by the {@code carddemo-application} layer.
     *
     * <p>Behavior:
     * <ul>
     *   <li>If the trimmed PAN is at most {@value #PAN_VISIBLE_TAIL}
     *       characters, the entire trimmed PAN is returned (already
     *       short enough that masking carries no benefit).</li>
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
        String trimmed = dalytranCardNum.trim();
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
        return "DalyTranRecord[dalytranId=" + dalytranId
                + ", dalytranTypeCd=" + dalytranTypeCd
                + ", dalytranCatCd=" + dalytranCatCd
                + ", dalytranSource=" + dalytranSource
                + ", dalytranAmt=" + dalytranAmt
                + ", dalytranMerchantId=" + dalytranMerchantId
                + ", dalytranCardNum=" + maskedPan()
                + ", dalytranOrigTs=" + dalytranOrigTs
                + ", dalytranProcTs=" + dalytranProcTs
                + ", filler=<" + FILLER_LENGTH + " bytes>]";
    }

    // ---------------------------------------------------------------------
    // equals / hashCode — value semantics with BigDecimal numeric equality
    // and byte-array content equality (the record-generated defaults would
    // use BigDecimal.equals (scale-sensitive) and reference equality on
    // the byte[], which is wrong for our value-type contract).
    // ---------------------------------------------------------------------

    /**
     * Value equality comparing each component by content.
     * {@link BigDecimal} is compared via
     * {@link BigDecimal#compareTo(BigDecimal)} so that {@code 1.20} and
     * {@code 1.2} are considered equal (the record's canonical
     * constructor normalizes scale, so this is rarely observable, but
     * the override is defensive). The {@code filler} byte array is
     * compared by element via {@link Arrays#equals(byte[], byte[])}.
     * {@link LocalDateTime} components use
     * {@link Objects#equals(Object, Object)} to handle the all-spaces
     * {@code null} sentinel safely.
     *
     * @param o the object to compare against
     * @return {@code true} iff {@code o} is a {@code DalyTranRecord}
     *         with the same component values
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DalyTranRecord that)) {
            return false;
        }
        return dalytranCatCd == that.dalytranCatCd
                && dalytranMerchantId == that.dalytranMerchantId
                && dalytranId.equals(that.dalytranId)
                && dalytranTypeCd.equals(that.dalytranTypeCd)
                && dalytranSource.equals(that.dalytranSource)
                && dalytranDesc.equals(that.dalytranDesc)
                && dalytranAmt.compareTo(that.dalytranAmt) == 0
                && dalytranMerchantName.equals(that.dalytranMerchantName)
                && dalytranMerchantCity.equals(that.dalytranMerchantCity)
                && dalytranMerchantZip.equals(that.dalytranMerchantZip)
                && dalytranCardNum.equals(that.dalytranCardNum)
                && Objects.equals(dalytranOrigTs, that.dalytranOrigTs)
                && Objects.equals(dalytranProcTs, that.dalytranProcTs)
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
        int result = dalytranId.hashCode();
        result = 31 * result + dalytranTypeCd.hashCode();
        result = 31 * result + Integer.hashCode(dalytranCatCd);
        result = 31 * result + dalytranSource.hashCode();
        result = 31 * result + dalytranDesc.hashCode();
        result = 31 * result + dalytranAmt.stripTrailingZeros().hashCode();
        result = 31 * result + Long.hashCode(dalytranMerchantId);
        result = 31 * result + dalytranMerchantName.hashCode();
        result = 31 * result + dalytranMerchantCity.hashCode();
        result = 31 * result + dalytranMerchantZip.hashCode();
        result = 31 * result + dalytranCardNum.hashCode();
        result = 31 * result + Objects.hashCode(dalytranOrigTs);
        result = 31 * result + Objects.hashCode(dalytranProcTs);
        result = 31 * result + Arrays.hashCode(filler);
        return result;
    }

    // ---------------------------------------------------------------------
    // parse(byte[]) — static factory producing a DalyTranRecord from a
    // 350-byte buffer that follows the CVTRA06Y layout. The byte buffer
    // is expected to use ASCII encoding (production EBCDIC is transcoded
    // by the EbcdicTranscoder adapter in carddemo-adapter-file before
    // reaching this method).
    // ---------------------------------------------------------------------

    /**
     * Parses a 350-byte ASCII buffer laid out per
     * {@code app/cpy/CVTRA06Y.cpy} and returns a corresponding
     * {@link DalyTranRecord}.
     *
     * <p>The signed monetary {@code DALYTRAN-AMT} is decoded via
     * {@link Decimals#parseZonedDecimal(byte[], int, int, int)} (USAGE
     * DISPLAY zoned-decimal with sign overpunch on the last byte). See
     * the class-level Javadoc &ldquo;Codec deviation&rdquo; section for
     * the rationale on why this method is preferred over
     * {@link Decimals#parseSignedPacked(byte[], int, int, int)} which
     * the agent prompt nominally specified.
     *
     * <p>The timestamp fields are decoded with
     * {@link DateTimeFormatter#ofPattern(String)} using pattern
     * {@code yyyy-MM-dd HH:mm:ss.SSSSSS}; a field containing only ASCII
     * spaces (the COBOL all-blanks sentinel) decodes to {@code null}.
     *
     * <p>This method is the inverse of {@link #encode()}: for any
     * {@code DalyTranRecord} {@code r} produced by parsing a
     * COBOL-emitted buffer {@code b}, {@code r.encode()} equals
     * {@code b} byte-for-byte (subject to the canonicalization notes in
     * {@link Decimals#encodeZonedDecimal(BigDecimal, int, int)}).
     *
     * @param buffer ASCII-encoded fixed-width record (must be exactly
     *               {@link #RECORD_LENGTH} bytes)
     * @return decoded {@link DalyTranRecord}
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code buffer.length !=
     *         RECORD_LENGTH}, a numeric field contains a non-digit, the
     *         DALYTRAN-AMT zoned-decimal is malformed, or a timestamp
     *         string is non-blank and does not match the expected
     *         pattern
     */
    public static DalyTranRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "DalyTranRecord buffer must be exactly " + RECORD_LENGTH
                            + " bytes; got " + buffer.length);
        }

        String dalytranId = readAscii(buffer, DALYTRAN_ID_OFFSET, DALYTRAN_ID_LENGTH);
        String dalytranTypeCd = readAscii(buffer, DALYTRAN_TYPE_CD_OFFSET, DALYTRAN_TYPE_CD_LENGTH);
        int dalytranCatCd = readUnsignedInt(
                buffer, DALYTRAN_CAT_CD_OFFSET, DALYTRAN_CAT_CD_LENGTH);
        String dalytranSource = readAscii(
                buffer, DALYTRAN_SOURCE_OFFSET, DALYTRAN_SOURCE_LENGTH);
        String dalytranDesc = readAscii(
                buffer, DALYTRAN_DESC_OFFSET, DALYTRAN_DESC_LENGTH);

        BigDecimal dalytranAmt = Decimals.parseZonedDecimal(
                buffer, DALYTRAN_AMT_OFFSET, DALYTRAN_AMT_LENGTH, DALYTRAN_AMT_SCALE);

        long dalytranMerchantId = readUnsignedLong(
                buffer, DALYTRAN_MERCHANT_ID_OFFSET, DALYTRAN_MERCHANT_ID_LENGTH);
        String dalytranMerchantName = readAscii(
                buffer, DALYTRAN_MERCHANT_NAME_OFFSET, DALYTRAN_MERCHANT_NAME_LENGTH);
        String dalytranMerchantCity = readAscii(
                buffer, DALYTRAN_MERCHANT_CITY_OFFSET, DALYTRAN_MERCHANT_CITY_LENGTH);
        String dalytranMerchantZip = readAscii(
                buffer, DALYTRAN_MERCHANT_ZIP_OFFSET, DALYTRAN_MERCHANT_ZIP_LENGTH);
        String dalytranCardNum = readAscii(
                buffer, DALYTRAN_CARD_NUM_OFFSET, DALYTRAN_CARD_NUM_LENGTH);

        LocalDateTime dalytranOrigTs = parseTimestampField(
                buffer, DALYTRAN_ORIG_TS_OFFSET, DALYTRAN_ORIG_TS_LENGTH, "dalytranOrigTs");
        LocalDateTime dalytranProcTs = parseTimestampField(
                buffer, DALYTRAN_PROC_TS_OFFSET, DALYTRAN_PROC_TS_LENGTH, "dalytranProcTs");

        byte[] filler = Arrays.copyOfRange(
                buffer, FILLER_OFFSET, FILLER_OFFSET + FILLER_LENGTH);

        return new DalyTranRecord(
                dalytranId, dalytranTypeCd, dalytranCatCd, dalytranSource, dalytranDesc,
                dalytranAmt, dalytranMerchantId, dalytranMerchantName, dalytranMerchantCity,
                dalytranMerchantZip, dalytranCardNum, dalytranOrigTs, dalytranProcTs, filler);
    }

    // ---------------------------------------------------------------------
    // encode() — produces a 350-byte ASCII buffer following the CVTRA06Y
    // layout. All PIC X(n) fields are right-space-padded; PIC 9(n) fields
    // are left-zero-padded; PIC S9(09)V99 (DALYTRAN-AMT) is delegated to
    // the Decimals zoned-decimal codec.
    // ---------------------------------------------------------------------

    /**
     * Encodes this record as a 350-byte ASCII buffer laid out per
     * {@code app/cpy/CVTRA06Y.cpy}.
     *
     * <p>Padding rules (AAP &sect;0.6.5 byte-for-byte fidelity):
     * <ul>
     *   <li>{@code PIC X(n)} alphanumeric fields are right-padded with
     *       ASCII space ({@code 0x20}) when shorter than {@code n}.</li>
     *   <li>{@code PIC 9(n)} unsigned numeric fields are left-padded
     *       with ASCII zero ({@code '0'}).</li>
     *   <li>{@code PIC S9(09)V99} ({@code DALYTRAN-AMT}) is encoded via
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

        writeAscii(out, dalytranId, DALYTRAN_ID_OFFSET, DALYTRAN_ID_LENGTH);
        writeAscii(out, dalytranTypeCd, DALYTRAN_TYPE_CD_OFFSET, DALYTRAN_TYPE_CD_LENGTH);
        writeUnsignedNumeric(out, dalytranCatCd, DALYTRAN_CAT_CD_OFFSET, DALYTRAN_CAT_CD_LENGTH);
        writeAscii(out, dalytranSource, DALYTRAN_SOURCE_OFFSET, DALYTRAN_SOURCE_LENGTH);
        writeAscii(out, dalytranDesc, DALYTRAN_DESC_OFFSET, DALYTRAN_DESC_LENGTH);

        byte[] amtBytes = Decimals.encodeZonedDecimal(
                dalytranAmt, DALYTRAN_AMT_LENGTH, DALYTRAN_AMT_SCALE);
        System.arraycopy(amtBytes, 0, out, DALYTRAN_AMT_OFFSET, DALYTRAN_AMT_LENGTH);

        writeUnsignedNumeric(out, dalytranMerchantId,
                DALYTRAN_MERCHANT_ID_OFFSET, DALYTRAN_MERCHANT_ID_LENGTH);
        writeAscii(out, dalytranMerchantName,
                DALYTRAN_MERCHANT_NAME_OFFSET, DALYTRAN_MERCHANT_NAME_LENGTH);
        writeAscii(out, dalytranMerchantCity,
                DALYTRAN_MERCHANT_CITY_OFFSET, DALYTRAN_MERCHANT_CITY_LENGTH);
        writeAscii(out, dalytranMerchantZip,
                DALYTRAN_MERCHANT_ZIP_OFFSET, DALYTRAN_MERCHANT_ZIP_LENGTH);
        writeAscii(out, dalytranCardNum,
                DALYTRAN_CARD_NUM_OFFSET, DALYTRAN_CARD_NUM_LENGTH);

        if (dalytranOrigTs != null) {
            writeAscii(out, dalytranOrigTs.format(TS_FMT),
                    DALYTRAN_ORIG_TS_OFFSET, DALYTRAN_ORIG_TS_LENGTH);
        }
        if (dalytranProcTs != null) {
            writeAscii(out, dalytranProcTs.format(TS_FMT),
                    DALYTRAN_PROC_TS_OFFSET, DALYTRAN_PROC_TS_LENGTH);
        }

        System.arraycopy(filler, 0, out, FILLER_OFFSET, FILLER_LENGTH);
        return out;
    }

    // ---------------------------------------------------------------------
    // Private helpers — fixed-width codec primitives. Patterned after
    // TranRecord (CVTRA05Y) for symmetric implementation.
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
     * All characters must be {@code '0'..'9'} (the COBOL
     * {@code PIC 9(n)} contract); any non-digit raises
     * {@link IllegalArgumentException}.
     *
     * <p>Used only for {@code DALYTRAN-CAT-CD} which fits in
     * {@code int}; for larger fields, see
     * {@link #readUnsignedLong(byte[], int, int)}.
     *
     * @param buffer source buffer
     * @param offset starting byte offset of the numeric field
     * @param length field length in bytes (= number of decimal digits)
     * @return parsed unsigned integer
     * @throws IllegalArgumentException if any byte is not an ASCII
     *         digit, or the value overflows {@link Integer#MAX_VALUE}
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
     * @param buffer source buffer
     * @param offset starting byte offset of the numeric field
     * @param length field length in bytes (= number of decimal digits)
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
     * Parses a {@code PIC X(26)} timestamp slice. A field consisting
     * entirely of ASCII spaces decodes to {@code null} (the COBOL
     * all-blanks sentinel for an unset timestamp). Otherwise the slice
     * is decoded with {@link #TS_FMT}.
     *
     * @param buffer    source buffer
     * @param offset    starting byte offset of the timestamp
     * @param length    fixed length (always {@code 26})
     * @param fieldName field name for diagnostic messages
     * @return a {@link LocalDateTime} or {@code null} if the field is
     *         blank
     * @throws IllegalArgumentException if the field is non-blank but
     *         does not parse as {@link #TS_FMT}
     */
    private static LocalDateTime parseTimestampField(
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
     * up to {@code length} ASCII bytes. Shorter strings are left in
     * place with any pre-existing bytes following (callers must pre-fill
     * the region with spaces when right-space-padding is required).
     *
     * <p>Longer strings would have been rejected by the canonical
     * constructor; this method is defensive and uses
     * {@link Math#min(int, int)} to avoid overflowing the destination
     * window even if a string slipped past validation.
     *
     * @param out    destination buffer
     * @param value  string to write (must not be {@code null})
     * @param offset starting byte offset
     * @param length maximum bytes to copy
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
     * {@code length} ASCII digit characters, left-zero-padded. Models
     * the COBOL {@code PIC 9(n)} encoding.
     *
     * @param out    destination buffer
     * @param value  non-negative long value to encode
     * @param offset starting byte offset
     * @param length fixed length in bytes (= number of decimal digits)
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


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
import java.util.List;
import java.util.Objects;

/**
 * Domain record translated from the COBOL {@code TRNX-RECORD} copybook at
 * {@code app/cpy/COSTM01.CPY} (a transaction-reporting layout used by the
 * statement-generation program CBSTM03A). Total record length is 350 bytes,
 * laid out as a 32-byte composite key ({@code TRNX-CARD-NUM} +
 * {@code TRNX-ID}) followed by 318 bytes of payload.
 *
 * <h2>COBOL field layout (verbatim)</h2>
 * <pre>{@code
 * 01 TRNX-RECORD.
 *    05 TRNX-KEY.
 *       10 TRNX-CARD-NUM           PIC X(16).        offset 0   length 16
 *       10 TRNX-ID                 PIC X(16).        offset 16  length 16
 *    05 TRNX-REST.
 *       10 TRNX-TYPE-CD            PIC X(02).        offset 32  length 2
 *       10 TRNX-CAT-CD             PIC 9(04).        offset 34  length 4
 *       10 TRNX-SOURCE             PIC X(10).        offset 38  length 10
 *       10 TRNX-DESC               PIC X(100).       offset 48  length 100
 *       10 TRNX-AMT                PIC S9(09)V99.    offset 148 length 11
 *       10 TRNX-MERCHANT-ID        PIC 9(09).        offset 159 length 9
 *       10 TRNX-MERCHANT-NAME      PIC X(50).        offset 168 length 50
 *       10 TRNX-MERCHANT-CITY      PIC X(50).        offset 218 length 50
 *       10 TRNX-MERCHANT-ZIP       PIC X(10).        offset 268 length 10
 *       10 TRNX-ORIG-TS            PIC X(26).        offset 278 length 26
 *       10 TRNX-PROC-TS            PIC X(26).        offset 304 length 26
 *       10 FILLER                  PIC X(20).        offset 330 length 20
 * Total: 350 bytes
 * }</pre>
 *
 * <h2>Authority</h2>
 * <ul>
 *   <li>AAP &sect;0.4.1 (Copybooks &rarr; Java records table)</li>
 *   <li>AAP &sect;0.6.9 (Copybook-to-Record Translation Table &mdash;
 *       OCCURS DEPENDING ON candidate)</li>
 *   <li>AAP &sect;0.6.3 (OCCURS DEPENDING ON Translation Strategy &mdash;
 *       record with {@code List<T>} + count + JEP 513 validation)</li>
 *   <li>AAP &sect;0.3.2 (Records pattern)</li>
 *   <li>AAP &sect;0.6.1 (Decimal Arithmetic Fidelity)</li>
 *   <li>AAP &sect;0.6.4 (Date Semantics &mdash; {@link LocalDateTime} for
 *       {@code PIC X(26)})</li>
 *   <li>AAP &sect;0.6.5 (File I/O Exactness &mdash; byte-for-byte
 *       round-trip invariant)</li>
 * </ul>
 *
 * <h2>Composite key</h2>
 * <p>{@code TRNX-CARD-NUM} (16 bytes) and {@code TRNX-ID} (16 bytes) form
 * a single 32-byte composite key sitting at offsets 0&ndash;31 of the
 * record. They are exposed as the nested {@link TrnxKey} record so that
 * call sites can pass and compare the key as a single value (e.g.,
 * {@code record.trnxKey().trnxCardNum()}). This mirrors the COBOL
 * {@code TRNX-KEY} group sub-structure and matches the
 * card-number-first ordering used by COSTM01 (compare with
 * {@link TranRecord}, which places {@code TRAN-ID} first and
 * {@code TRAN-CARD-NUM} near the end &mdash; the two records share a 350-byte
 * total length but are <strong>not</strong> byte-compatible).
 *
 * <h2>OCCURS DEPENDING ON companion</h2>
 * <p>{@code TRNX-RECORD} by itself is a flat 350-byte record. The
 * canonical COBOL OCCURS DEPENDING ON pattern in this project &mdash; a
 * variable-length list of TRNX entries prefixed by an integer count, as
 * assembled by CBSTM03A statement generation &mdash; is expressed by the
 * nested {@link StatementBlock} record. {@link StatementBlock} carries an
 * explicit {@code int count} plus a {@code List<TrnxRecord> transactions},
 * with JEP 513 Flexible Constructor Body validation ensuring
 * {@code transactions.size() == count} and a defensive
 * {@link List#copyOf(java.util.Collection)} for immutability (per AAP
 * &sect;0.6.3).
 *
 * <h2>Monetary scale</h2>
 * <p>{@code TRNX-AMT} ({@code PIC S9(09)V99}) is modelled as
 * {@link BigDecimal} with {@link #TRNX_AMT_SCALE scale 2}. The canonical
 * constructor normalizes the value to scale 2 using
 * {@link RoundingMode#HALF_EVEN} (banker's rounding), matching the COBOL
 * convention for COMPUTE statements with the {@code ROUNDED} clause (AAP
 * &sect;0.3.3 / &sect;0.6.1).
 *
 * <h2>Date semantics</h2>
 * <p>{@code TRNX-ORIG-TS} and {@code TRNX-PROC-TS} are 26-byte ASCII
 * timestamps in the pattern {@code yyyy-MM-dd HH:mm:ss.SSSSSS} (six
 * fractional-second digits, microsecond precision). They are carried as
 * {@link LocalDateTime} per AAP &sect;0.6.4 (never {@link java.util.Date}
 * or {@link java.util.Calendar}). A {@code null} value encodes as 26 ASCII
 * spaces &mdash; the COBOL all-blanks sentinel for an unset timestamp &mdash;
 * matching the convention used by {@link TranRecord}.
 *
 * <h2>Byte-for-byte fidelity codec</h2>
 * <p>{@code TRNX-AMT} ({@code PIC S9(09)V99}, no {@code USAGE} clause, 11
 * bytes) uses the {@code USAGE DISPLAY} zoned-decimal codec with sign
 * overpunch on the last byte ({@link Decimals#parseZonedDecimal} /
 * {@link Decimals#encodeZonedDecimal}). This matches the
 * 11-byte ASCII layout of {@code app/data/ASCII/*.txt} fixtures and the
 * sibling {@link TranRecord} / {@link DalyTranRecord} convention. The
 * round-trip invariant {@code parse(b).encode() = b} therefore holds for
 * every valid 350-byte COBOL-emitted buffer (AAP &sect;0.6.5).
 *
 * <h2>Immutability</h2>
 * <p>This record is fully immutable. The {@code filler} {@code byte[]}
 * receives a defensive {@link Object#clone() clone} in the canonical
 * constructor, and the {@link #filler()} accessor returns a fresh copy
 * on every call. {@code transactions} on {@link StatementBlock} is wrapped
 * with {@link List#copyOf(java.util.Collection)} to guarantee structural
 * immutability.
 *
 * <h2>Equality</h2>
 * <p>The record-generated {@link #equals(Object)} would compare
 * {@link BigDecimal} via {@link BigDecimal#equals(Object)} (scale-sensitive)
 * and {@code byte[]} via reference identity, neither of which matches the
 * value-type contract that callers expect. We override
 * {@link #equals(Object)} and {@link #hashCode()} to compare
 * {@link BigDecimal} via {@link BigDecimal#compareTo(BigDecimal)} (numeric
 * equality) and {@code byte[]} via {@link Arrays#equals(byte[], byte[])}
 * (content equality).
 *
 * @see TranRecord
 * @see DalyTranRecord
 * @see Decimals#parseZonedDecimal(byte[], int, int, int)
 * @see Decimals#encodeZonedDecimal(BigDecimal, int, int)
 * @since 1.0.0
 */
@CobolProgram(
        value = "COSTM01",
        sourcePath = "app/cpy/COSTM01.CPY",
        notes = "350-byte TRNX-RECORD with 32-byte composite TRNX-KEY (CARD-NUM+ID) at the "
                + "front. TRNX-AMT (PIC S9(09)V99, 11 bytes) uses zoned-decimal codec "
                + "(USAGE DISPLAY default) matching sibling TranRecord / DalyTranRecord and "
                + "app/data/ASCII fixtures for byte-for-byte fidelity per AAP §0.6.5. "
                + "PIC X(26) timestamps decoded to LocalDateTime; null = COBOL all-blanks. "
                + "Nested StatementBlock is the canonical OCCURS DEPENDING ON example per "
                + "AAP §0.6.3 with JEP 513 list-size validation."
)
public record TrnxRecord(
        TrnxKey trnxKey,
        String trnxTypeCd,
        int trnxCatCd,
        String trnxSource,
        String trnxDesc,
        BigDecimal trnxAmt,
        long trnxMerchantId,
        String trnxMerchantName,
        String trnxMerchantCity,
        String trnxMerchantZip,
        LocalDateTime trnxOrigTs,
        LocalDateTime trnxProcTs,
        byte[] filler) {

    // ---------------------------------------------------------------------
    // Layout constants (offsets and lengths from COSTM01.CPY).
    // Constants are package-public (well, public) so adapters, tests, and
    // companion writers can reference them without re-deriving them from
    // the copybook (the canonical layout source).
    // ---------------------------------------------------------------------

    /** Total fixed-width record length in bytes: 350 (AAP §0.4.1). */
    public static final int RECORD_LENGTH = 350;

    /** Offset of {@code TRNX-CARD-NUM} ({@code PIC X(16)}) within the record. */
    public static final int TRNX_CARD_NUM_OFFSET = 0;
    /** Length in bytes of {@code TRNX-CARD-NUM} ({@code PIC X(16)}): 16. */
    public static final int TRNX_CARD_NUM_LENGTH = 16;

    /** Offset of {@code TRNX-ID} ({@code PIC X(16)}). */
    public static final int TRNX_ID_OFFSET = 16;
    /** Length in bytes of {@code TRNX-ID} ({@code PIC X(16)}): 16. */
    public static final int TRNX_ID_LENGTH = 16;

    /** Offset of {@code TRNX-TYPE-CD} ({@code PIC X(02)}). */
    public static final int TRNX_TYPE_CD_OFFSET = 32;
    /** Length in bytes of {@code TRNX-TYPE-CD} ({@code PIC X(02)}): 2. */
    public static final int TRNX_TYPE_CD_LENGTH = 2;

    /** Offset of {@code TRNX-CAT-CD} ({@code PIC 9(04)}). */
    public static final int TRNX_CAT_CD_OFFSET = 34;
    /** Length in bytes of {@code TRNX-CAT-CD} ({@code PIC 9(04)}): 4. */
    public static final int TRNX_CAT_CD_LENGTH = 4;

    /** Offset of {@code TRNX-SOURCE} ({@code PIC X(10)}). */
    public static final int TRNX_SOURCE_OFFSET = 38;
    /** Length in bytes of {@code TRNX-SOURCE} ({@code PIC X(10)}): 10. */
    public static final int TRNX_SOURCE_LENGTH = 10;

    /** Offset of {@code TRNX-DESC} ({@code PIC X(100)}). */
    public static final int TRNX_DESC_OFFSET = 48;
    /** Length in bytes of {@code TRNX-DESC} ({@code PIC X(100)}): 100. */
    public static final int TRNX_DESC_LENGTH = 100;

    /**
     * Offset of {@code TRNX-AMT} ({@code PIC S9(09)V99}, USAGE DISPLAY
     * zoned-decimal with sign overpunch on the last byte).
     */
    public static final int TRNX_AMT_OFFSET = 148;
    /**
     * Length in bytes of {@code TRNX-AMT}: 11. ({@code PIC S9(09)V99} in
     * USAGE DISPLAY encoding occupies one ASCII byte per digit, so 9 + 2
     * = 11 bytes total. The sign rides on the rightmost byte as a zoned
     * overpunch character.)
     */
    public static final int TRNX_AMT_LENGTH = 11;
    /**
     * Implicit decimal scale of {@code TRNX-AMT}: 2 (the {@code V99} part
     * of {@code PIC S9(09)V99}).
     */
    public static final int TRNX_AMT_SCALE = 2;

    /** Offset of {@code TRNX-MERCHANT-ID} ({@code PIC 9(09)}). */
    public static final int TRNX_MERCHANT_ID_OFFSET = 159;
    /** Length in bytes of {@code TRNX-MERCHANT-ID} ({@code PIC 9(09)}): 9. */
    public static final int TRNX_MERCHANT_ID_LENGTH = 9;

    /** Offset of {@code TRNX-MERCHANT-NAME} ({@code PIC X(50)}). */
    public static final int TRNX_MERCHANT_NAME_OFFSET = 168;
    /** Length in bytes of {@code TRNX-MERCHANT-NAME} ({@code PIC X(50)}): 50. */
    public static final int TRNX_MERCHANT_NAME_LENGTH = 50;

    /** Offset of {@code TRNX-MERCHANT-CITY} ({@code PIC X(50)}). */
    public static final int TRNX_MERCHANT_CITY_OFFSET = 218;
    /** Length in bytes of {@code TRNX-MERCHANT-CITY} ({@code PIC X(50)}): 50. */
    public static final int TRNX_MERCHANT_CITY_LENGTH = 50;

    /** Offset of {@code TRNX-MERCHANT-ZIP} ({@code PIC X(10)}). */
    public static final int TRNX_MERCHANT_ZIP_OFFSET = 268;
    /** Length in bytes of {@code TRNX-MERCHANT-ZIP} ({@code PIC X(10)}): 10. */
    public static final int TRNX_MERCHANT_ZIP_LENGTH = 10;

    /** Offset of {@code TRNX-ORIG-TS} ({@code PIC X(26)} timestamp). */
    public static final int TRNX_ORIG_TS_OFFSET = 278;
    /** Length in bytes of {@code TRNX-ORIG-TS}: 26 (pattern yyyy-MM-dd HH:mm:ss.SSSSSS). */
    public static final int TRNX_ORIG_TS_LENGTH = 26;

    /** Offset of {@code TRNX-PROC-TS} ({@code PIC X(26)} timestamp). */
    public static final int TRNX_PROC_TS_OFFSET = 304;
    /** Length in bytes of {@code TRNX-PROC-TS}: 26. */
    public static final int TRNX_PROC_TS_LENGTH = 26;

    /** Offset of trailing {@code FILLER} ({@code PIC X(20)}). */
    public static final int FILLER_OFFSET = 330;
    /** Length in bytes of trailing {@code FILLER}: 20. */
    public static final int FILLER_LENGTH = 20;

    /**
     * Timestamp pattern for {@code PIC X(26)} fields: ISO-8601-style date
     * and time with a space separator and six fractional-second digits
     * (microsecond precision). Matches the COBOL convention used by the
     * statement-generation program CBSTM03A and the sibling
     * {@link TranRecord} record (AAP &sect;0.6.4).
     */
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /** ASCII space byte (0x20) &mdash; the COBOL {@code PIC X} padding byte. */
    private static final byte SPACE = (byte) 0x20;

    // ---------------------------------------------------------------------
    // Nested TrnxKey record — 32-byte composite key (CARD-NUM + ID).
    // ---------------------------------------------------------------------

    /**
     * Composite key {@code TRNX-KEY} (32 bytes total): the 16-byte
     * {@code TRNX-CARD-NUM} followed by the 16-byte {@code TRNX-ID}. The
     * key sits at the front of every {@link TrnxRecord} and uniquely
     * identifies a transaction within the statement-generation flow used
     * by CBSTM03A.
     *
     * <p>Both components are {@code PIC X(16)} alphanumeric fields. The
     * canonical constructor enforces the length constraint and rejects
     * {@code null} components &mdash; mirroring COBOL semantics where an
     * unset {@code PIC X(16)} field would simply contain 16 ASCII spaces
     * rather than a null reference. Callers that need to represent an
     * empty key should pass two 16-blank strings (or shorter strings,
     * which {@link TrnxRecord#encode()} will right-pad with spaces).
     *
     * <h2>PAN masking</h2>
     * <p>{@code TRNX-CARD-NUM} is a Primary Account Number (PAN). Per AAP
     * &sect;0.7.2 ("no card PAN logged in full; mask all but last 4 digits"),
     * any log output emitted by upstream code that references this field
     * MUST use {@link #maskedCardNum()} instead of the raw component
     * accessor.
     *
     * @param trnxCardNum the 16-character {@code TRNX-CARD-NUM} (must not
     *                    be {@code null}; trailing spaces preserved)
     * @param trnxId      the 16-character {@code TRNX-ID} (must not be
     *                    {@code null}; trailing spaces preserved)
     */
    public static record TrnxKey(String trnxCardNum, String trnxId) {

        /**
         * Compact canonical constructor. Validates non-null inputs and
         * enforces the {@code PIC X(16)} length ceiling on both
         * components. Uses JEP 513 Flexible Constructor Bodies (validation
         * runs before any canonical field bindings).
         *
         * @throws NullPointerException     if either component is {@code null}
         * @throws IllegalArgumentException if either component exceeds 16
         *                                  characters
         */
        public TrnxKey {
            Objects.requireNonNull(trnxCardNum, "trnxCardNum");
            Objects.requireNonNull(trnxId, "trnxId");
            if (trnxCardNum.length() > TRNX_CARD_NUM_LENGTH) {
                throw new IllegalArgumentException(
                        "trnxCardNum exceeds " + TRNX_CARD_NUM_LENGTH
                                + " chars (got " + trnxCardNum.length() + ")");
            }
            if (trnxId.length() > TRNX_ID_LENGTH) {
                throw new IllegalArgumentException(
                        "trnxId exceeds " + TRNX_ID_LENGTH
                                + " chars (got " + trnxId.length() + ")");
            }
        }

        /**
         * Returns the Primary Account Number ({@code TRNX-CARD-NUM})
         * masked to show only the last 4 characters. Use this method
         * (and never {@link #trnxCardNum()}) for log output, exception
         * messages, and any other PCI-sensitive surface per AAP
         * &sect;0.7.2.
         *
         * <p>Behavior:
         * <ul>
         *   <li>If the trimmed PAN is at most 4 characters, the entire
         *       trimmed PAN is returned (already short enough that
         *       masking would discard all useful identification).</li>
         *   <li>Otherwise the leading characters are replaced with
         *       {@code '*'} and the trailing 4 characters are preserved
         *       (e.g., {@code "4111111111111234"} becomes
         *       {@code "************1234"}).</li>
         * </ul>
         *
         * @return masked PAN suitable for logs and stringification
         */
        public String maskedCardNum() {
            String trimmed = trnxCardNum.trim();
            if (trimmed.length() <= 4) {
                return trimmed;
            }
            int maskLen = trimmed.length() - 4;
            return "*".repeat(maskLen) + trimmed.substring(maskLen);
        }
    }

    // ---------------------------------------------------------------------
    // Nested StatementBlock record — OCCURS DEPENDING ON example.
    // Per AAP §0.6.3, this is the canonical OCCURS DEPENDING ON
    // translation example: a variable-length list of TRNX entries
    // prefixed by an explicit count, with JEP 513 Flexible Constructor
    // Body validation that count == list.size().
    // ---------------------------------------------------------------------

    /**
     * Variable-length block of {@link TrnxRecord} entries prefixed by an
     * integer count. Models a COBOL
     * {@code OCCURS n TO m TIMES DEPENDING ON COUNT} pattern per AAP
     * &sect;0.6.3, where the host program declares a buffer with a
     * statically-known maximum but only the first {@code count} entries
     * carry meaningful data. The compact canonical constructor uses JEP
     * 513 Flexible Constructor Bodies to validate
     * {@code transactions.size() == count} <strong>before</strong> any
     * canonical field bindings; this is the right place for COBOL-style
     * input validation (AAP &sect;0.6.3).
     *
     * <p>The {@code transactions} list is defensively copied via
     * {@link List#copyOf(java.util.Collection)} to guarantee structural
     * immutability and rejection of {@code null} elements: callers cannot
     * mutate the original list after construction, and the resulting
     * record is safe to share across virtual threads (AAP &sect;0.6.6).
     *
     * <p>Example: CBSTM03A statement generation reads up to
     * {@code MAX_TRANS_PER_CARD} transactions into a buffer per card and
     * then packages them as a {@code StatementBlock} for downstream
     * formatting:
     * <pre>{@code
     * List<TrnxRecord> readTxns = ...;          // size() <= MAX
     * var block = new TrnxRecord.StatementBlock(readTxns.size(), readTxns);
     * }</pre>
     *
     * @param count        the number of transactions in this block (must
     *                     be {@code >= 0})
     * @param transactions the transactions (must not be {@code null};
     *                     {@code transactions.size()} must equal
     *                     {@code count})
     */
    public static record StatementBlock(int count, List<TrnxRecord> transactions) {

        /**
         * Compact canonical constructor enforcing the OCCURS DEPENDING ON
         * invariant via JEP 513 Flexible Constructor Bodies (validation
         * before canonical field bindings).
         *
         * @throws NullPointerException     if {@code transactions} is
         *                                  {@code null} or contains a
         *                                  {@code null} element
         * @throws IllegalArgumentException if {@code count < 0} or if
         *                                  {@code transactions.size() !=
         *                                  count}
         */
        public StatementBlock {
            Objects.requireNonNull(transactions, "transactions");
            if (count < 0) {
                throw new IllegalArgumentException(
                        "count must be non-negative; got " + count);
            }
            if (transactions.size() != count) {
                throw new IllegalArgumentException(
                        "StatementBlock count=" + count
                                + " but transactions.size()=" + transactions.size());
            }
            // Defensive immutable copy (rejects null elements implicitly).
            transactions = List.copyOf(transactions);
        }
    }

    // ---------------------------------------------------------------------
    // Compact canonical constructor (JEP 513 flexible constructor body).
    // Validation runs BEFORE the canonical field bindings; defensive
    // copy of the filler byte[] runs in the same block to preserve
    // record immutability.
    // ---------------------------------------------------------------------

    /**
     * Compact canonical constructor for {@link TrnxRecord}. Validates
     * field constraints and normalizes the monetary {@code trnxAmt} to
     * {@link #TRNX_AMT_SCALE scale 2} using {@link RoundingMode#HALF_EVEN}
     * (banker's rounding, the COBOL {@code ROUNDED} default per AAP
     * &sect;0.3.3). Uses JEP 513 Flexible Constructor Bodies so the
     * pre-binding validation can throw {@link IllegalArgumentException}
     * before any partial state is captured.
     *
     * @throws NullPointerException     if any reference component is
     *                                  {@code null} (except
     *                                  {@code trnxOrigTs} and
     *                                  {@code trnxProcTs}, which may be
     *                                  {@code null} as the all-blanks
     *                                  sentinel)
     * @throws IllegalArgumentException if any {@code PIC X(n)} component
     *                                  exceeds its declared length, if
     *                                  {@code trnxCatCd} is outside
     *                                  {@code 0..9999}, if
     *                                  {@code trnxMerchantId} is
     *                                  negative, or if {@code filler}
     *                                  is not exactly
     *                                  {@link #FILLER_LENGTH} bytes
     */
    public TrnxRecord {
        Objects.requireNonNull(trnxKey, "trnxKey");
        Objects.requireNonNull(trnxTypeCd, "trnxTypeCd");
        Objects.requireNonNull(trnxSource, "trnxSource");
        Objects.requireNonNull(trnxDesc, "trnxDesc");
        Objects.requireNonNull(trnxAmt, "trnxAmt");
        Objects.requireNonNull(trnxMerchantName, "trnxMerchantName");
        Objects.requireNonNull(trnxMerchantCity, "trnxMerchantCity");
        Objects.requireNonNull(trnxMerchantZip, "trnxMerchantZip");
        Objects.requireNonNull(filler, "filler");
        // trnxOrigTs / trnxProcTs may be null (COBOL all-blanks sentinel).

        if (trnxTypeCd.length() > TRNX_TYPE_CD_LENGTH) {
            throw new IllegalArgumentException(
                    "trnxTypeCd exceeds " + TRNX_TYPE_CD_LENGTH
                            + " chars (got " + trnxTypeCd.length() + ")");
        }
        if (trnxCatCd < 0 || trnxCatCd > 9999) {
            throw new IllegalArgumentException(
                    "trnxCatCd must be 0..9999 (got " + trnxCatCd + ")");
        }
        if (trnxSource.length() > TRNX_SOURCE_LENGTH) {
            throw new IllegalArgumentException(
                    "trnxSource exceeds " + TRNX_SOURCE_LENGTH
                            + " chars (got " + trnxSource.length() + ")");
        }
        if (trnxDesc.length() > TRNX_DESC_LENGTH) {
            throw new IllegalArgumentException(
                    "trnxDesc exceeds " + TRNX_DESC_LENGTH
                            + " chars (got " + trnxDesc.length() + ")");
        }
        if (trnxMerchantId < 0L) {
            throw new IllegalArgumentException(
                    "trnxMerchantId must be non-negative (got " + trnxMerchantId + ")");
        }
        if (trnxMerchantName.length() > TRNX_MERCHANT_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "trnxMerchantName exceeds " + TRNX_MERCHANT_NAME_LENGTH
                            + " chars (got " + trnxMerchantName.length() + ")");
        }
        if (trnxMerchantCity.length() > TRNX_MERCHANT_CITY_LENGTH) {
            throw new IllegalArgumentException(
                    "trnxMerchantCity exceeds " + TRNX_MERCHANT_CITY_LENGTH
                            + " chars (got " + trnxMerchantCity.length() + ")");
        }
        if (trnxMerchantZip.length() > TRNX_MERCHANT_ZIP_LENGTH) {
            throw new IllegalArgumentException(
                    "trnxMerchantZip exceeds " + TRNX_MERCHANT_ZIP_LENGTH
                            + " chars (got " + trnxMerchantZip.length() + ")");
        }
        if (filler.length != FILLER_LENGTH) {
            throw new IllegalArgumentException(
                    "filler must be exactly " + FILLER_LENGTH
                            + " bytes (got " + filler.length + ")");
        }

        // Normalize monetary scale via banker's rounding (COBOL ROUNDED).
        trnxAmt = trnxAmt.setScale(TRNX_AMT_SCALE, RoundingMode.HALF_EVEN);
        // Defensive copy so the record cannot be mutated through the
        // caller's byte[] reference.
        filler = filler.clone();
    }

    // ---------------------------------------------------------------------
    // Accessor overrides — defensive copy on byte[] read.
    // ---------------------------------------------------------------------

    /**
     * Returns a defensive copy of the 20-byte {@code FILLER} payload so
     * that the record remains immutable: a caller cannot mutate the
     * internal state by writing through the returned reference.
     *
     * @return a fresh {@code byte[FILLER_LENGTH]} copy of the filler bytes
     */
    @Override
    public byte[] filler() {
        return filler.clone();
    }

    // ---------------------------------------------------------------------
    // toString — PCI-aware (PAN masked via TrnxKey.maskedCardNum) per AAP §0.7.2.
    // ---------------------------------------------------------------------

    /**
     * Returns a PCI-aware string representation. The full PAN is NEVER
     * printed; only {@link TrnxKey#maskedCardNum()} appears. The 20-byte
     * {@code FILLER} is summarized as {@code <20 bytes>} to keep the
     * representation human-readable while still indicating the field is
     * present.
     *
     * @return a masked, human-readable description of this record
     */
    @Override
    public String toString() {
        return "TrnxRecord[trnxKey=(cardNum=" + trnxKey.maskedCardNum()
                + ", id=" + trnxKey.trnxId()
                + "), trnxTypeCd=" + trnxTypeCd
                + ", trnxCatCd=" + trnxCatCd
                + ", trnxSource=" + trnxSource
                + ", trnxAmt=" + trnxAmt
                + ", trnxMerchantId=" + trnxMerchantId
                + ", trnxOrigTs=" + trnxOrigTs
                + ", trnxProcTs=" + trnxProcTs
                + ", filler=<" + FILLER_LENGTH + " bytes>]";
    }

    // ---------------------------------------------------------------------
    // equals / hashCode — value semantics with numeric BigDecimal equality
    // and byte-array content equality. The record-generated defaults would
    // use BigDecimal.equals (scale-sensitive) and byte[] reference
    // equality, neither of which matches the value-type contract.
    // ---------------------------------------------------------------------

    /**
     * Value equality comparing each component by content.
     *
     * <ul>
     *   <li>{@link BigDecimal} components ({@code trnxAmt}) are compared
     *       via {@link BigDecimal#compareTo(BigDecimal)} (numeric
     *       equality, scale-insensitive) so that {@code 1.20} and
     *       {@code 1.2} are considered equal. The canonical constructor
     *       normalizes scale to {@link #TRNX_AMT_SCALE}, so this is
     *       rarely observable in practice but the override remains
     *       defensive.</li>
     *   <li>The {@code filler} {@code byte[]} is compared element-wise
     *       via {@link Arrays#equals(byte[], byte[])}.</li>
     *   <li>The {@code trnxOrigTs} / {@code trnxProcTs} components, which
     *       may be {@code null} (all-blanks sentinel), use
     *       {@link Objects#equals(Object, Object)}.</li>
     *   <li>All other components use {@link Object#equals(Object)}.</li>
     * </ul>
     *
     * @param o the object to compare against (may be {@code null})
     * @return {@code true} iff {@code o} is a {@link TrnxRecord} with
     *         the same component values
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TrnxRecord that)) {
            return false;
        }
        return trnxCatCd == that.trnxCatCd
                && trnxMerchantId == that.trnxMerchantId
                && trnxKey.equals(that.trnxKey)
                && trnxTypeCd.equals(that.trnxTypeCd)
                && trnxSource.equals(that.trnxSource)
                && trnxDesc.equals(that.trnxDesc)
                && trnxAmt.compareTo(that.trnxAmt) == 0
                && trnxMerchantName.equals(that.trnxMerchantName)
                && trnxMerchantCity.equals(that.trnxMerchantCity)
                && trnxMerchantZip.equals(that.trnxMerchantZip)
                && Objects.equals(trnxOrigTs, that.trnxOrigTs)
                && Objects.equals(trnxProcTs, that.trnxProcTs)
                && Arrays.equals(filler, that.filler);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}. The
     * monetary {@code trnxAmt} is normalized via
     * {@link BigDecimal#stripTrailingZeros()} so that two values that
     * compare numerically equal but with different scales (e.g.,
     * {@code 1.20} and {@code 1.2}) produce the same hash code; the
     * canonical constructor's scale normalization makes this rare in
     * practice but the override is defensive. The {@code filler}
     * {@code byte[]} uses {@link Arrays#hashCode(byte[])}.
     *
     * @return value-based hash code
     */
    @Override
    public int hashCode() {
        int result = trnxKey.hashCode();
        result = 31 * result + trnxTypeCd.hashCode();
        result = 31 * result + Integer.hashCode(trnxCatCd);
        result = 31 * result + trnxSource.hashCode();
        result = 31 * result + trnxDesc.hashCode();
        result = 31 * result + trnxAmt.stripTrailingZeros().hashCode();
        result = 31 * result + Long.hashCode(trnxMerchantId);
        result = 31 * result + trnxMerchantName.hashCode();
        result = 31 * result + trnxMerchantCity.hashCode();
        result = 31 * result + trnxMerchantZip.hashCode();
        result = 31 * result + Objects.hashCode(trnxOrigTs);
        result = 31 * result + Objects.hashCode(trnxProcTs);
        result = 31 * result + Arrays.hashCode(filler);
        return result;
    }

    // ---------------------------------------------------------------------
    // parse(byte[]) — decode a 350-byte ASCII buffer into a TrnxRecord.
    // ---------------------------------------------------------------------

    /**
     * Decodes a 350-byte ASCII buffer laid out per {@code app/cpy/COSTM01.CPY}
     * into a {@link TrnxRecord}. This is the inverse of {@link #encode()}:
     * for any {@code TrnxRecord} {@code r} produced by parsing a
     * COBOL-emitted buffer {@code b},
     * {@code r.encode()} equals {@code b} byte-for-byte (subject to the
     * canonicalization notes documented on
     * {@link Decimals#encodeZonedDecimal(BigDecimal, int, int)}).
     *
     * <p>Padding and format conventions:
     * <ul>
     *   <li>{@code PIC X(n)} alphanumeric fields are read verbatim with
     *       trailing spaces preserved.</li>
     *   <li>{@code PIC 9(n)} unsigned numeric fields are decoded by
     *       interpreting each byte as an ASCII digit.</li>
     *   <li>{@code PIC S9(09)V99} ({@code TRNX-AMT}, 11 bytes) is decoded
     *       via {@link Decimals#parseZonedDecimal(byte[], int, int, int)}
     *       (USAGE DISPLAY zoned-decimal, sign overpunch on the last
     *       byte).</li>
     *   <li>{@code PIC X(26)} timestamps are decoded via
     *       {@link DateTimeFormatter} with pattern
     *       {@code yyyy-MM-dd HH:mm:ss.SSSSSS}; an all-blank field
     *       decodes to {@code null}, the COBOL sentinel for an unset
     *       timestamp.</li>
     *   <li>{@code FILLER} is copied byte-for-byte from the buffer.</li>
     * </ul>
     *
     * @param buffer ASCII-encoded fixed-width record (must be exactly
     *               {@link #RECORD_LENGTH} bytes)
     * @return decoded {@link TrnxRecord}
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code buffer.length} differs
     *                                  from {@link #RECORD_LENGTH}, a
     *                                  numeric field contains a
     *                                  non-digit, the {@code TRNX-AMT}
     *                                  zoned-decimal field is malformed,
     *                                  or a timestamp field is non-blank
     *                                  and does not match the expected
     *                                  pattern
     */
    public static TrnxRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "TrnxRecord buffer must be exactly " + RECORD_LENGTH
                            + " bytes; got " + buffer.length);
        }

        String trnxCardNum = readAscii(
                buffer, TRNX_CARD_NUM_OFFSET, TRNX_CARD_NUM_LENGTH);
        String trnxId = readAscii(
                buffer, TRNX_ID_OFFSET, TRNX_ID_LENGTH);
        String trnxTypeCd = readAscii(
                buffer, TRNX_TYPE_CD_OFFSET, TRNX_TYPE_CD_LENGTH);
        int trnxCatCd = (int) readUnsignedLong(
                buffer, TRNX_CAT_CD_OFFSET, TRNX_CAT_CD_LENGTH);
        String trnxSource = readAscii(
                buffer, TRNX_SOURCE_OFFSET, TRNX_SOURCE_LENGTH);
        String trnxDesc = readAscii(
                buffer, TRNX_DESC_OFFSET, TRNX_DESC_LENGTH);

        // TRNX-AMT: PIC S9(09)V99 in USAGE DISPLAY zoned-decimal (11 bytes,
        // sign overpunch on the last byte) — matches sibling TranRecord
        // and the app/data/ASCII/*.txt fixture format per AAP §0.6.5.
        BigDecimal trnxAmt = Decimals.parseZonedDecimal(
                buffer, TRNX_AMT_OFFSET, TRNX_AMT_LENGTH, TRNX_AMT_SCALE);

        long trnxMerchantId = readUnsignedLong(
                buffer, TRNX_MERCHANT_ID_OFFSET, TRNX_MERCHANT_ID_LENGTH);
        String trnxMerchantName = readAscii(
                buffer, TRNX_MERCHANT_NAME_OFFSET, TRNX_MERCHANT_NAME_LENGTH);
        String trnxMerchantCity = readAscii(
                buffer, TRNX_MERCHANT_CITY_OFFSET, TRNX_MERCHANT_CITY_LENGTH);
        String trnxMerchantZip = readAscii(
                buffer, TRNX_MERCHANT_ZIP_OFFSET, TRNX_MERCHANT_ZIP_LENGTH);

        LocalDateTime trnxOrigTs = parseTimestamp(
                buffer, TRNX_ORIG_TS_OFFSET, TRNX_ORIG_TS_LENGTH, "trnxOrigTs");
        LocalDateTime trnxProcTs = parseTimestamp(
                buffer, TRNX_PROC_TS_OFFSET, TRNX_PROC_TS_LENGTH, "trnxProcTs");

        byte[] filler = Arrays.copyOfRange(
                buffer, FILLER_OFFSET, FILLER_OFFSET + FILLER_LENGTH);

        return new TrnxRecord(
                new TrnxKey(trnxCardNum, trnxId),
                trnxTypeCd, trnxCatCd, trnxSource, trnxDesc, trnxAmt,
                trnxMerchantId, trnxMerchantName, trnxMerchantCity,
                trnxMerchantZip, trnxOrigTs, trnxProcTs, filler);
    }

    // ---------------------------------------------------------------------
    // encode() — produce a 350-byte ASCII buffer matching the COSTM01 layout.
    // ---------------------------------------------------------------------

    /**
     * Encodes this record as a 350-byte ASCII buffer laid out per
     * {@code app/cpy/COSTM01.CPY}.
     *
     * <p>Padding rules (AAP &sect;0.6.5 byte-for-byte fidelity):
     * <ul>
     *   <li>{@code PIC X(n)} alphanumeric fields are right-padded with
     *       ASCII space ({@code 0x20}) when shorter than {@code n}.</li>
     *   <li>{@code PIC 9(n)} unsigned numeric fields are left-padded with
     *       ASCII zero ({@code '0'}).</li>
     *   <li>{@code PIC S9(09)V99} ({@code TRNX-AMT}) is encoded via
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
        // (notably null timestamps) is naturally space-padded.
        Arrays.fill(out, SPACE);

        writeAscii(out, trnxKey.trnxCardNum(),
                TRNX_CARD_NUM_OFFSET, TRNX_CARD_NUM_LENGTH);
        writeAscii(out, trnxKey.trnxId(),
                TRNX_ID_OFFSET, TRNX_ID_LENGTH);
        writeAscii(out, trnxTypeCd,
                TRNX_TYPE_CD_OFFSET, TRNX_TYPE_CD_LENGTH);
        writeUnsignedNumeric(out, trnxCatCd,
                TRNX_CAT_CD_OFFSET, TRNX_CAT_CD_LENGTH);
        writeAscii(out, trnxSource,
                TRNX_SOURCE_OFFSET, TRNX_SOURCE_LENGTH);
        writeAscii(out, trnxDesc,
                TRNX_DESC_OFFSET, TRNX_DESC_LENGTH);

        byte[] amtBytes = Decimals.encodeZonedDecimal(
                trnxAmt, TRNX_AMT_LENGTH, TRNX_AMT_SCALE);
        System.arraycopy(amtBytes, 0, out, TRNX_AMT_OFFSET, TRNX_AMT_LENGTH);

        writeUnsignedNumeric(out, trnxMerchantId,
                TRNX_MERCHANT_ID_OFFSET, TRNX_MERCHANT_ID_LENGTH);
        writeAscii(out, trnxMerchantName,
                TRNX_MERCHANT_NAME_OFFSET, TRNX_MERCHANT_NAME_LENGTH);
        writeAscii(out, trnxMerchantCity,
                TRNX_MERCHANT_CITY_OFFSET, TRNX_MERCHANT_CITY_LENGTH);
        writeAscii(out, trnxMerchantZip,
                TRNX_MERCHANT_ZIP_OFFSET, TRNX_MERCHANT_ZIP_LENGTH);

        writeTimestamp(out, trnxOrigTs,
                TRNX_ORIG_TS_OFFSET, TRNX_ORIG_TS_LENGTH);
        writeTimestamp(out, trnxProcTs,
                TRNX_PROC_TS_OFFSET, TRNX_PROC_TS_LENGTH);

        System.arraycopy(filler, 0, out, FILLER_OFFSET, FILLER_LENGTH);
        return out;
    }

    // ---------------------------------------------------------------------
    // Internal helpers — fixed-width ASCII reader/writer primitives.
    // ---------------------------------------------------------------------

    /**
     * Reads {@code length} bytes from {@code buffer} starting at
     * {@code offset} as a US-ASCII string with trailing spaces preserved.
     * Used for {@code PIC X(n)} fields.
     *
     * @param buffer source buffer
     * @param offset starting byte offset
     * @param length field length in bytes
     * @return decoded ASCII string of exactly {@code length} characters
     */
    private static String readAscii(byte[] buffer, int offset, int length) {
        return new String(buffer, offset, length, StandardCharsets.US_ASCII);
    }

    /**
     * Reads {@code length} ASCII digit characters from {@code buffer}
     * starting at {@code offset} and parses them as an unsigned long.
     * Used for {@code PIC 9(n)} fields.
     *
     * @param buffer source buffer
     * @param offset starting byte offset
     * @param length field length in bytes
     * @return parsed unsigned long value
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
     *                                  not parse as {@link #TS_FMT}
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
     * up to {@code length} ASCII bytes. Shorter strings are right-padded
     * with ASCII spaces; the canonical constructor rejects strings that
     * exceed the field length, so an over-long string will already have
     * been rejected before this method is called.
     *
     * @param out    destination buffer (pre-filled with spaces by
     *               {@link #encode()})
     * @param value  source string
     * @param offset destination offset
     * @param length field length in bytes
     */
    private static void writeAscii(byte[] out, String value, int offset, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int copyLen = Math.min(bytes.length, length);
        System.arraycopy(bytes, 0, out, offset, copyLen);
        // The buffer is pre-filled with ASCII spaces in encode(), so any
        // trailing bytes beyond copyLen are already 0x20 (PIC X right-pad).
    }

    /**
     * Writes a non-negative {@code long} into {@code out} at
     * {@code offset} as a left-zero-padded ASCII digit sequence of
     * exactly {@code length} bytes. Used for {@code PIC 9(n)} fields.
     *
     * @param out    destination buffer
     * @param value  the value to write (must be {@code >= 0})
     * @param offset destination offset
     * @param length field length in bytes
     * @throws IllegalArgumentException if {@code value} cannot fit in
     *                                  {@code length} digits
     */
    private static void writeUnsignedNumeric(
            byte[] out, long value, int offset, int length) {
        long remaining = value;
        for (int i = length - 1; i >= 0; i--) {
            out[offset + i] = (byte) ('0' + (remaining % 10));
            remaining /= 10;
        }
        if (remaining != 0L) {
            throw new IllegalArgumentException(
                    "value " + value + " exceeds field width " + length);
        }
    }

    /**
     * Writes a {@link LocalDateTime} into {@code out} at {@code offset}
     * as a 26-byte ASCII string in the pattern
     * {@code yyyy-MM-dd HH:mm:ss.SSSSSS}. A {@code null} timestamp is
     * written as 26 ASCII spaces &mdash; the COBOL all-blanks sentinel for
     * an unset timestamp.
     *
     * @param out    destination buffer
     * @param ts     the timestamp to write (may be {@code null})
     * @param offset destination offset
     * @param length field length (always {@code 26})
     */
    private static void writeTimestamp(
            byte[] out, LocalDateTime ts, int offset, int length) {
        if (ts == null) {
            // The buffer is pre-filled with ASCII spaces in encode();
            // leave the field untouched so it preserves the all-blanks
            // sentinel byte-for-byte.
            return;
        }
        String formatted = ts.format(TS_FMT);
        byte[] bytes = formatted.getBytes(StandardCharsets.US_ASCII);
        // The formatter always emits exactly 26 bytes for a non-null ts.
        System.arraycopy(bytes, 0, out, offset, Math.min(bytes.length, length));
    }
}


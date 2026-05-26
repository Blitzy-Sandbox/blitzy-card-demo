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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable domain record translated from the COBOL {@code TRAN-CAT-RECORD}
 * copybook at {@code app/cpy/CVTRA04Y.cpy} (record length 60 bytes). One
 * {@code TranCatRecord} instance corresponds to one VSAM KSDS record in the
 * {@code TRANCATG} dataset on z/OS &mdash; the transaction-category lookup
 * table that maps {@code (TRAN-TYPE-CD, TRAN-CAT-CD)} composite keys to
 * human-readable category descriptions.
 *
 * <h2>COBOL source layout (verbatim from {@code app/cpy/CVTRA04Y.cpy})</h2>
 * <pre>{@code
 * 01 TRAN-CAT-RECORD.
 *    05 TRAN-CAT-KEY.
 *       10 TRAN-TYPE-CD            PIC X(02).
 *       10 TRAN-CAT-CD             PIC 9(04).
 *    05 TRAN-CAT-TYPE-DESC         PIC X(50).
 *    05 FILLER                     PIC X(04).
 * }</pre>
 *
 * <h2>Java field mapping</h2>
 * <table>
 *   <caption>Byte-offset and type mapping</caption>
 *   <tr><th>COBOL field</th>          <th>PIC</th>   <th>Len</th> <th>Offset</th>
 *       <th>Java component</th></tr>
 *   <tr><td>TRAN-CAT-KEY</td>         <td>(group)</td><td>6</td>  <td>0</td>
 *       <td>{@link #tranCatKey() tranCatKey} : {@link TranCatKey}</td></tr>
 *   <tr><td>&nbsp;&nbsp;TRAN-TYPE-CD</td><td>X(02)</td> <td>2</td> <td>0</td>
 *       <td>{@link TranCatKey#tranTypeCd() tranTypeCd} : String</td></tr>
 *   <tr><td>&nbsp;&nbsp;TRAN-CAT-CD</td><td>9(04)</td> <td>4</td>  <td>2</td>
 *       <td>{@link TranCatKey#tranCatCd() tranCatCd} : int</td></tr>
 *   <tr><td>TRAN-CAT-TYPE-DESC</td>   <td>X(50)</td>   <td>50</td> <td>6</td>
 *       <td>{@link #tranCatTypeDesc() tranCatTypeDesc} : String</td></tr>
 *   <tr><td>FILLER</td>               <td>X(04)</td>   <td>4</td>  <td>56</td>
 *       <td>{@link #filler() filler} : byte[4]</td></tr>
 * </table>
 *
 * <h2>Composite key &mdash; {@link TranCatKey}</h2>
 * The COBOL {@code TRAN-CAT-KEY} group (6 bytes) is faithfully translated as a
 * nested {@link TranCatKey} record per AAP &sect;0.3.2 (Records pattern) and
 * &sect;0.6.2 (composite-key pattern &mdash; nested record for COBOL group).
 *
 * <p><strong>Important &mdash; do NOT consolidate with {@code TranCatBalRecord.TranCatKey}.</strong>
 * Both {@link TranCatRecord} and {@code TranCatBalRecord} (sibling, from
 * {@code CVTRA01Y.cpy}) declare a nested group called {@code TRAN-CAT-KEY},
 * but the two layouts are <em>different</em>:
 * <ul>
 *   <li>{@code TranCatRecord.TranCatKey}: 6 bytes
 *       ({@code TRAN-TYPE-CD X(02) + TRAN-CAT-CD 9(04)}).</li>
 *   <li>{@code TranCatBalRecord.TranCatKey}: 17 bytes
 *       ({@code TRANCAT-ACCT-ID 9(11) + TRANCAT-TYPE-CD X(02) + TRANCAT-CD 9(04)}).</li>
 * </ul>
 * The two are deliberately kept as separate nested records so each lives next
 * to its outer record and carries its own COBOL-faithful field names and
 * lengths (key insight per the binding agent prompt).
 *
 * <h2>Byte-for-byte fidelity contract (AAP &sect;0.6.5)</h2>
 * For every valid {@value #RECORD_LENGTH}-byte buffer {@code b}:
 * <pre>{@code
 *   Arrays.equals(b, TranCatRecord.parse(b).encode()) == true
 * }</pre>
 * This invariant is the formal contract with external file consumers and is
 * asserted by the golden-record harness on every PR. Per AAP &sect;0.6.5 any
 * departure from this invariant is a defect.
 *
 * <h2>Encoding rules (per AAP &sect;0.6.5 and binding agent-prompt constraints)</h2>
 * <ul>
 *   <li>Charset: {@link StandardCharsets#US_ASCII}. EBCDIC transcoding, when
 *       required, is performed by the adapter layer
 *       ({@code carddemo-adapter-file.EbcdicTranscoder}) before the byte
 *       buffer reaches {@link #parse(byte[])}.</li>
 *   <li>Numeric fields (PIC 9(n) &mdash; here {@code TRAN-CAT-CD}): decoded
 *       with strict digit validation; encoded with zero-padding on the LEFT
 *       per the binding agent prompt constraint #4.</li>
 *   <li>String fields (PIC X(n) &mdash; here {@code TRAN-TYPE-CD} and
 *       {@code TRAN-CAT-TYPE-DESC}): decoded verbatim (no trimming) so that
 *       round-trip fidelity is preserved; encoded with space-padding on the
 *       RIGHT per the binding agent prompt constraint #5.</li>
 *   <li>FILLER: preserved verbatim (defensive copy on construction and
 *       access).</li>
 * </ul>
 *
 * <h2>Data-driven taxonomy (AAP &sect;0.6.10)</h2>
 * Transaction categories are <strong>not</strong> a closed sealed hierarchy:
 * they are loaded dynamically from the {@code TRANCATG} dataset at runtime.
 * This record is therefore a plain value-record, not a sealed-type permit.
 * The closed set of category combinations materializes as the in-memory
 * collection of {@code TranCatRecord} instances loaded by
 * {@code TransactionCategoryRepository.loadAll()}.
 *
 * <h2>Immutability (AAP &sect;0.1.2)</h2>
 * Records are inherently immutable, but the {@link #filler()} component is a
 * {@code byte[]} &mdash; an inherently mutable type. To preserve true
 * immutability:
 * <ul>
 *   <li>The compact canonical constructor defensively clones the incoming
 *       {@code filler} array.</li>
 *   <li>The {@link #filler()} accessor is overridden to return a fresh clone
 *       on every call so callers cannot mutate the record's internal buffer.</li>
 * </ul>
 *
 * <h2>Equality and hashing (AAP &sect;0.6.5)</h2>
 * The default record-generated {@code equals(Object)} and {@code hashCode()}
 * use {@code Object.equals} / {@code Object.hashCode()} on the {@code byte[]}
 * {@code filler} component &mdash; that is, identity comparison, not content
 * comparison. Both methods are explicitly overridden here so that two
 * {@code TranCatRecord} instances with logically equal contents compare equal
 * and hash to the same value.
 *
 * <h2>Consumers (per AAP &sect;0.4.1)</h2>
 * <ul>
 *   <li>{@code CbTrn02C} &mdash; full posting engine, category-balance maintenance.</li>
 *   <li>{@code CbTrn03C} &mdash; paginated transaction detail report.</li>
 *   <li>{@code CbAct04C} &mdash; interest calculation (joins TRANCATG with DISCGRP).</li>
 *   <li>{@code FileTransactionCategoryRepository} &mdash; file-based adapter.</li>
 * </ul>
 *
 * <h2>Fixture source</h2>
 * The reference test fixture for this record is the ASCII file
 * {@code app/data/ASCII/trancatg.txt} (REFERENCE per AAP &sect;0.4.1).
 *
 * @see com.blitzy.carddemo.domain.annotation.CobolProgram
 * @see TranTypeRecord
 * @since 1.0.0
 */
@CobolProgram(
        value = "CVTRA04Y",
        sourcePath = "app/cpy/CVTRA04Y.cpy",
        notes = "60-byte TRAN-CAT-RECORD: 6-byte composite key (TRAN-TYPE-CD X(02) + TRAN-CAT-CD 9(04)) "
                + "+ 50-byte description + 4-byte FILLER. Loaded from TRANCATG dataset. "
                + "Composite key is the nested TranCatKey record; do NOT consolidate with "
                + "TranCatBalRecord.TranCatKey (different layout, different length)."
)
public record TranCatRecord(
        TranCatKey tranCatKey,
        String tranCatTypeDesc,
        byte[] filler) {

    // ------------------------------------------------------------------
    // Layout constants (BINDING per file schema — names form part of the
    // public contract; do NOT rename without updating the file schema).
    // ------------------------------------------------------------------

    /** Total TRAN-CAT-RECORD length in bytes per {@code CVTRA04Y}. */
    public static final int RECORD_LENGTH = 60;

    /**
     * Byte offset of {@code TRAN-TYPE-CD} within the
     * {@value #RECORD_LENGTH}-byte record. Position 0 of the composite key.
     */
    public static final int TRAN_TYPE_CD_OFFSET = 0;

    /** Byte length of {@code TRAN-TYPE-CD} (PIC X(02)). */
    public static final int TRAN_TYPE_CD_LENGTH = 2;

    /**
     * Byte offset of {@code TRAN-CAT-CD} within the
     * {@value #RECORD_LENGTH}-byte record (immediately after TRAN-TYPE-CD
     * inside the composite key).
     */
    public static final int TRAN_CAT_CD_OFFSET = 2;

    /** Byte length of {@code TRAN-CAT-CD} (PIC 9(04)). */
    public static final int TRAN_CAT_CD_LENGTH = 4;

    /**
     * Byte offset of {@code TRAN-CAT-TYPE-DESC} within the
     * {@value #RECORD_LENGTH}-byte record (immediately after the 6-byte
     * composite key).
     */
    public static final int TRAN_CAT_TYPE_DESC_OFFSET = 6;

    /** Byte length of {@code TRAN-CAT-TYPE-DESC} (PIC X(50)). */
    public static final int TRAN_CAT_TYPE_DESC_LENGTH = 50;

    /**
     * Byte offset of trailing {@code FILLER} within the
     * {@value #RECORD_LENGTH}-byte record.
     */
    public static final int FILLER_OFFSET = 56;

    /** Byte length of trailing {@code FILLER} (PIC X(04)). */
    public static final int FILLER_LENGTH = 4;

    /**
     * Inclusive upper bound for {@link TranCatKey#tranCatCd()} so that the
     * value fits in the {@value #TRAN_CAT_CD_LENGTH}-digit
     * {@code TRAN-CAT-CD} field (PIC 9(04)). {@code 10^4 - 1 = 9_999}.
     * Values outside {@code 0..9999} cannot be encoded into the 4-byte slot
     * without corrupting the record layout; the
     * {@link TranCatKey#TranCatKey(String, int) TranCatKey} canonical
     * constructor rejects them at construction time.
     */
    public static final int TRAN_CAT_CD_MAX = 9_999;

    /** ASCII space byte (0x20) used for right-padding string fields. */
    private static final byte ASCII_SPACE = (byte) 0x20;

    /** ASCII digit '0' (0x30) &mdash; first digit code point in US-ASCII. */
    private static final byte ASCII_ZERO = (byte) '0';

    /** ASCII digit '9' (0x39) &mdash; last digit code point in US-ASCII. */
    private static final byte ASCII_NINE = (byte) '9';

    // ==================================================================
    // Nested record: TranCatKey
    // ==================================================================

    /**
     * Composite key {@code TRAN-CAT-KEY} (6 bytes total) corresponding to
     * the COBOL group {@code 05 TRAN-CAT-KEY} inside {@code TRAN-CAT-RECORD}.
     *
     * <pre>{@code
     * 05 TRAN-CAT-KEY.
     *    10 TRAN-TYPE-CD     PIC X(02).
     *    10 TRAN-CAT-CD      PIC 9(04).
     * }</pre>
     *
     * <p>Layout: {@code TRAN-TYPE-CD} (X(02), 2 bytes, offset 0) +
     * {@code TRAN-CAT-CD} (9(04), 4 bytes, offset 2). Total length 6 bytes.
     *
     * <p>This nested record is a faithful translation of the COBOL group per
     * AAP &sect;0.3.2 (Records pattern) and &sect;0.6.2 (composite-key
     * pattern). The same group name &mdash; {@code TRAN-CAT-KEY} &mdash;
     * appears in {@code TranCatBalRecord} (sibling) but with a different
     * 17-byte layout; the two are deliberately <strong>not</strong>
     * consolidated.
     *
     * <h2>Validation</h2>
     * <ul>
     *   <li>{@code tranTypeCd} must be non-null and at most
     *       {@value #TRAN_TYPE_CD_LENGTH} characters long.</li>
     *   <li>{@code tranCatCd} must be in the range
     *       {@code 0..}{@value #TRAN_CAT_CD_MAX} (inclusive) so it fits the
     *       4-digit PIC 9(04) field.</li>
     * </ul>
     *
     * @param tranTypeCd 2-character transaction-type code (PIC X(02))
     * @param tranCatCd  4-digit transaction-category code (PIC 9(04))
     */
    public record TranCatKey(String tranTypeCd, int tranCatCd) {

        /**
         * Compact canonical constructor. Validates each component against
         * its COBOL PIC clause before the implicit field assignments. Uses
         * JEP 513 Flexible Constructor Bodies (finalized in Java 25) for the
         * validation-before-binding pattern.
         *
         * @throws NullPointerException     if {@code tranTypeCd} is {@code null}
         * @throws IllegalArgumentException if {@code tranTypeCd.length()} is
         *                                  greater than
         *                                  {@value #TRAN_TYPE_CD_LENGTH}, or
         *                                  if {@code tranCatCd} is outside
         *                                  {@code 0..}{@value #TRAN_CAT_CD_MAX}
         */
        public TranCatKey {
            Objects.requireNonNull(tranTypeCd, "tranTypeCd");
            if (tranTypeCd.length() > TRAN_TYPE_CD_LENGTH) {
                throw new IllegalArgumentException(
                        "tranTypeCd exceeds " + TRAN_TYPE_CD_LENGTH + " chars, got "
                                + tranTypeCd.length());
            }
            if (tranCatCd < 0 || tranCatCd > TRAN_CAT_CD_MAX) {
                throw new IllegalArgumentException(
                        "tranCatCd must be in range 0.." + TRAN_CAT_CD_MAX
                                + ", got " + tranCatCd);
            }
        }
    }

    // ==================================================================
    // Compact canonical constructor — TranCatRecord
    // ==================================================================

    /**
     * Compact canonical constructor. Validates every component against its
     * COBOL PIC clause and defensively clones the mutable {@code byte[]}
     * filler. Uses JEP 513 Flexible Constructor Bodies (finalized in Java 25)
     * to run validation before the implicit field assignments.
     *
     * @throws NullPointerException     if {@code tranCatKey},
     *                                  {@code tranCatTypeDesc}, or
     *                                  {@code filler} is {@code null}
     * @throws IllegalArgumentException if {@code tranCatTypeDesc} exceeds
     *                                  {@value #TRAN_CAT_TYPE_DESC_LENGTH}
     *                                  characters, or if {@code filler.length}
     *                                  is not exactly {@value #FILLER_LENGTH}
     */
    public TranCatRecord {
        Objects.requireNonNull(tranCatKey, "tranCatKey");
        Objects.requireNonNull(tranCatTypeDesc, "tranCatTypeDesc");
        Objects.requireNonNull(filler, "filler");
        if (tranCatTypeDesc.length() > TRAN_CAT_TYPE_DESC_LENGTH) {
            throw new IllegalArgumentException(
                    "tranCatTypeDesc exceeds " + TRAN_CAT_TYPE_DESC_LENGTH
                            + " chars, got " + tranCatTypeDesc.length());
        }
        if (filler.length != FILLER_LENGTH) {
            throw new IllegalArgumentException(
                    "filler must be exactly " + FILLER_LENGTH + " bytes, got "
                            + filler.length);
        }
        // Defensive copy — preserves immutability of this record per AAP §0.1.2.
        filler = filler.clone();
    }

    // ------------------------------------------------------------------
    // Accessor override for mutable byte[] component (defensive clone).
    // ------------------------------------------------------------------

    /**
     * Returns a defensive copy of the FILLER byte array. The default
     * accessor auto-generated for record components of type {@code byte[]}
     * exposes the internal array reference, which would allow callers to
     * mutate the record's internal state. This override returns a fresh
     * clone on every call so the record remains effectively immutable.
     *
     * @return a defensive copy of the trailing {@value #FILLER_LENGTH}-byte
     *         FILLER field
     */
    @Override
    public byte[] filler() {
        return filler.clone();
    }

    // ==================================================================
    // parse(byte[]) — fixed-width buffer → record factory
    // ==================================================================

    /**
     * Parses a {@value #RECORD_LENGTH}-byte fixed-width buffer into a
     * {@code TranCatRecord}. The buffer layout is the canonical 60-byte
     * COBOL {@code TRAN-CAT-RECORD} per copybook {@code CVTRA04Y}.
     *
     * <p>Strict invariants:
     * <ul>
     *   <li>{@code buffer.length} must equal {@value #RECORD_LENGTH}.</li>
     *   <li>The 4 bytes at offset {@value #TRAN_CAT_CD_OFFSET} must all be
     *       US-ASCII digits {@code '0'..'9'}; any non-digit byte causes
     *       {@link IllegalArgumentException}.</li>
     *   <li>The 2-byte {@code TRAN-TYPE-CD} and 50-byte
     *       {@code TRAN-CAT-TYPE-DESC} fields are decoded verbatim as
     *       US-ASCII (no trimming) so that round-trip fidelity is
     *       preserved.</li>
     *   <li>The 4-byte FILLER is sliced verbatim and stored under defensive
     *       copy semantics.</li>
     * </ul>
     *
     * @param buffer fixed-width byte buffer of exactly
     *               {@value #RECORD_LENGTH} bytes
     * @return a new {@code TranCatRecord} populated from the buffer
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code buffer.length} is not
     *                                  exactly {@value #RECORD_LENGTH}, or
     *                                  if the {@code TRAN-CAT-CD} field
     *                                  contains a non-digit byte
     */
    public static TranCatRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "TranCatRecord buffer must be exactly " + RECORD_LENGTH
                            + " bytes; got " + buffer.length);
        }
        String tranTypeCd = new String(
                buffer, TRAN_TYPE_CD_OFFSET, TRAN_TYPE_CD_LENGTH,
                StandardCharsets.US_ASCII);
        int tranCatCd = parseAsciiDigits(
                buffer, TRAN_CAT_CD_OFFSET, TRAN_CAT_CD_LENGTH, "tranCatCd");
        String tranCatTypeDesc = new String(
                buffer, TRAN_CAT_TYPE_DESC_OFFSET, TRAN_CAT_TYPE_DESC_LENGTH,
                StandardCharsets.US_ASCII);
        byte[] fillerSlice = Arrays.copyOfRange(
                buffer, FILLER_OFFSET, FILLER_OFFSET + FILLER_LENGTH);
        return new TranCatRecord(
                new TranCatKey(tranTypeCd, tranCatCd),
                tranCatTypeDesc,
                fillerSlice);
    }

    // ==================================================================
    // encode() — record → fixed-width buffer
    // ==================================================================

    /**
     * Encodes this record as a canonical {@value #RECORD_LENGTH}-byte COBOL
     * {@code TRAN-CAT-RECORD} buffer. The output is byte-identical to the
     * input of {@link #parse(byte[])} for any record obtained from
     * {@code parse} on a valid buffer (round-trip identity).
     *
     * <p>Encoding rules (per AAP &sect;0.6.5 and binding agent-prompt
     * constraints #4 and #5):
     * <ul>
     *   <li>The buffer is first initialised with ASCII spaces (0x20) so
     *       unwritten regions remain printable and right-padded by default.</li>
     *   <li>{@code TRAN-TYPE-CD} (PIC X(02)) is encoded as US-ASCII bytes
     *       with right-padding using ASCII spaces.</li>
     *   <li>{@code TRAN-CAT-CD} (PIC 9(04)) is encoded as ASCII digits with
     *       left-padding using ASCII zeros &mdash; e.g., {@code 42} renders
     *       as {@code "0042"}.</li>
     *   <li>{@code TRAN-CAT-TYPE-DESC} (PIC X(50)) is encoded as US-ASCII
     *       bytes with right-padding using ASCII spaces.</li>
     *   <li>FILLER is copied verbatim from the record's filler array.</li>
     * </ul>
     *
     * @return a fresh {@value #RECORD_LENGTH}-byte buffer encoding this
     *         record
     */
    public byte[] encode() {
        byte[] out = new byte[RECORD_LENGTH];
        // Initialise everything to ASCII space so trailing unwritten bytes of
        // variable-length string fields remain space-padded.
        Arrays.fill(out, ASCII_SPACE);

        // TRAN-TYPE-CD — string, space-padded RIGHT.
        writeAsciiString(out, tranCatKey.tranTypeCd(),
                TRAN_TYPE_CD_OFFSET, TRAN_TYPE_CD_LENGTH);

        // TRAN-CAT-CD — numeric, zero-padded LEFT.
        writeAsciiDigits(out, tranCatKey.tranCatCd(),
                TRAN_CAT_CD_OFFSET, TRAN_CAT_CD_LENGTH);

        // TRAN-CAT-TYPE-DESC — string, space-padded RIGHT.
        writeAsciiString(out, tranCatTypeDesc,
                TRAN_CAT_TYPE_DESC_OFFSET, TRAN_CAT_TYPE_DESC_LENGTH);

        // FILLER — preserve verbatim.
        System.arraycopy(filler, 0, out, FILLER_OFFSET, FILLER_LENGTH);

        return out;
    }

    // ==================================================================
    // equals / hashCode overrides for byte[] field handling
    // ==================================================================

    /**
     * Value-equality comparison. Overrides the default record-generated
     * implementation so that the {@code byte[] filler} component is compared
     * by content via {@link Arrays#equals(byte[], byte[])} instead of by
     * reference identity (which is what {@code Object.equals} would do).
     *
     * @param o the object to compare against
     * @return {@code true} iff {@code o} is a {@code TranCatRecord} with
     *         equal {@code tranCatKey}, {@code tranCatTypeDesc}, and
     *         {@code filler} content
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TranCatRecord that)) {
            return false;
        }
        return tranCatKey.equals(that.tranCatKey)
                && tranCatTypeDesc.equals(that.tranCatTypeDesc)
                && Arrays.equals(filler, that.filler);
    }

    /**
     * Hash code consistent with {@link #equals(Object)}. Uses
     * {@link Arrays#hashCode(byte[])} for the {@code filler} component
     * (content hash, not identity hash).
     *
     * @return a hash code consistent with {@link #equals(Object)}
     */
    @Override
    public int hashCode() {
        int result = tranCatKey.hashCode();
        result = 31 * result + tranCatTypeDesc.hashCode();
        result = 31 * result + Arrays.hashCode(filler);
        return result;
    }

    // ==================================================================
    // Internal helpers
    // ==================================================================

    /**
     * Parses a fixed-width run of US-ASCII digit bytes as an unsigned
     * {@code int}. Strict: any byte outside {@code '0'..'9'} causes
     * {@link IllegalArgumentException}.
     *
     * <p>This helper returns {@code int} (not {@code long}) because the only
     * numeric field in this record is {@code TRAN-CAT-CD} (PIC 9(04)) whose
     * maximum value is {@value #TRAN_CAT_CD_MAX}, comfortably within
     * {@code int} range. The helper uses {@code int} accumulation directly
     * to match the field's natural Java type without needing a narrowing
     * cast at the call site.
     *
     * @param buffer    the source byte buffer
     * @param offset    starting offset within {@code buffer}
     * @param length    number of digits to consume
     * @param fieldName name of the field for error reporting
     * @return the decoded non-negative integer value
     * @throws IllegalArgumentException on a non-digit byte
     */
    private static int parseAsciiDigits(byte[] buffer, int offset, int length, String fieldName) {
        int value = 0;
        for (int i = 0; i < length; i++) {
            byte b = buffer[offset + i];
            if (b < ASCII_ZERO || b > ASCII_NINE) {
                throw new IllegalArgumentException(String.format(
                        "Non-digit byte 0x%02X at offset %d while parsing %s",
                        (b & 0xFF), (offset + i), fieldName));
            }
            value = (value * 10) + (b - ASCII_ZERO);
        }
        return value;
    }

    /**
     * Writes a non-negative integer into
     * {@code buffer[offset..offset+length)} as fixed-width ASCII digits with
     * left zero-padding. Mirrors COBOL {@code PIC 9(n)} DISPLAY
     * representation.
     *
     * @param buffer destination byte buffer
     * @param value  non-negative integer value (range was already validated
     *               by {@link TranCatKey#TranCatKey(String, int)})
     * @param offset starting offset within {@code buffer}
     * @param length number of digit positions to write
     */
    private static void writeAsciiDigits(byte[] buffer, int value, int offset, int length) {
        int remaining = value;
        for (int i = length - 1; i >= 0; i--) {
            buffer[offset + i] = (byte) (ASCII_ZERO + (remaining % 10));
            remaining /= 10;
        }
        // The range was enforced by TranCatKey's canonical constructor; an
        // assertion here documents the invariant for static analysis and
        // catches any future bug that allows out-of-range values past
        // validation.
        assert remaining == 0 : "value overflows " + length + "-digit field";
    }

    /**
     * Writes a String into {@code buffer[offset..offset+length)} as
     * fixed-width US-ASCII bytes. The first {@code min(value.length, length)}
     * bytes are copied; any remaining positions are left untouched (and
     * therefore remain at whatever {@code encode()} pre-filled them with,
     * i.e. ASCII space 0x20).
     *
     * @param buffer destination byte buffer
     * @param value  the source string (may be shorter than {@code length};
     *               length cap enforced by the canonical constructor of the
     *               relevant outer record)
     * @param offset starting offset within {@code buffer}
     * @param length number of byte positions reserved for the field
     */
    private static void writeAsciiString(byte[] buffer, String value, int offset, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int copyLen = Math.min(bytes.length, length);
        System.arraycopy(bytes, 0, buffer, offset, copyLen);
    }
}

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
 * Immutable domain record translated from the COBOL {@code CARD-XREF-RECORD}
 * copybook at {@code app/cpy/CVACT03Y.cpy} (record length 50 bytes). One
 * {@code CardXrefRecord} instance corresponds to one VSAM KSDS record in the
 * {@code CARDXREF} dataset on z/OS.
 *
 * <h2>COBOL source layout (verbatim from {@code app/cpy/CVACT03Y.cpy})</h2>
 * <pre>{@code
 * 01 CARD-XREF-RECORD.
 *    05 XREF-CARD-NUM  PIC X(16).
 *    05 XREF-CUST-ID   PIC 9(09).
 *    05 XREF-ACCT-ID   PIC 9(11).
 *    05 FILLER         PIC X(14).
 * }</pre>
 *
 * <h2>Java field mapping</h2>
 * <table>
 *   <caption>Byte-offset and type mapping</caption>
 *   <tr><th>COBOL field</th>  <th>PIC</th>     <th>Len</th> <th>Offset</th>
 *       <th>Java component</th></tr>
 *   <tr><td>XREF-CARD-NUM</td><td>X(16)</td>   <td>16</td>  <td>0</td>
 *       <td>{@link #xrefCardNum() xrefCardNum} : String</td></tr>
 *   <tr><td>XREF-CUST-ID</td> <td>9(09)</td>   <td>9</td>   <td>16</td>
 *       <td>{@link #xrefCustId() xrefCustId} : long</td></tr>
 *   <tr><td>XREF-ACCT-ID</td> <td>9(11)</td>   <td>11</td>  <td>25</td>
 *       <td>{@link #xrefAcctId() xrefAcctId} : long</td></tr>
 *   <tr><td>FILLER</td>       <td>X(14)</td>   <td>14</td>  <td>36</td>
 *       <td>{@link #filler() filler} : byte[14]</td></tr>
 * </table>
 *
 * <h2>Byte-for-byte fidelity contract (AAP &sect;0.6.5)</h2>
 * For every valid {@value #RECORD_LENGTH}-byte buffer {@code b}:
 * <pre>{@code
 *   Arrays.equals(b, CardXrefRecord.parse(b).encode()) == true
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
 *   <li>Numeric fields (PIC 9(n)): decoded with strict digit validation;
 *       encoded with zero-padding on the LEFT.</li>
 *   <li>String fields (PIC X(n)): decoded verbatim (no trimming) so that
 *       round-trip fidelity is preserved; encoded with space-padding on
 *       the RIGHT.</li>
 *   <li>FILLER: preserved verbatim (defensive copy on construction and
 *       access).</li>
 * </ul>
 *
 * <h2>Card number ({@code XREF-CARD-NUM}) is a {@link String}, not a number</h2>
 * The card number is declared {@code PIC X(16)} in COBOL (alphanumeric), not
 * {@code PIC 9(16)} (numeric). It is therefore mapped to a Java {@code String}
 * so that leading zeros, embedded spaces, and any test patterns the source
 * data may contain are preserved bit-for-bit. Modelling it as a {@code long}
 * would silently lose leading zeros and reject any non-digit byte the source
 * fixture might contain.
 *
 * <h2>PAN-masking is the carddemo-application layer's responsibility (AAP &sect;0.7.2)</h2>
 * Per the binding agent-prompt constraint #8, this record uses the
 * <strong>default</strong> {@code toString()} implementation auto-generated
 * by the record compiler, which renders the full PAN. The
 * {@code carddemo-application} layer is responsible for masking PANs (all
 * but the last 4 digits) before any log line, exception message, or report
 * row is emitted. The domain record is a pure data carrier; applying
 * masking here would be a presentation concern leaking into the domain ring
 * and would also break round-trip parity tests that need to inspect the
 * full card number.
 *
 * <h2>Immutability (AAP &sect;0.1.2)</h2>
 * Records are inherently immutable, but the {@link #filler()} component is
 * a {@code byte[]} &mdash; an inherently mutable type. To preserve true
 * immutability:
 * <ul>
 *   <li>The compact canonical constructor defensively clones the incoming
 *       {@code filler} array.</li>
 *   <li>The {@link #filler()} accessor is overridden to return a fresh
 *       clone on every call so callers cannot mutate the internal buffer.</li>
 * </ul>
 *
 * <h2>Equality and hashing (AAP &sect;0.6.5)</h2>
 * The default record-generated {@code equals(Object)} and {@code hashCode()}
 * use {@code Object.equals} / {@code Object.hashCode()} on the {@code byte[]}
 * {@code filler} component &mdash; that is, identity comparison, not content
 * comparison. Both methods are explicitly overridden here so that two
 * {@code CardXrefRecord} instances with logically equal contents compare
 * equal and hash to the same value.
 *
 * <h2>Consumers (per AAP &sect;0.4.1)</h2>
 * <ul>
 *   <li>{@code CbAct03C} &mdash; sequential CARDXREF reader</li>
 *   <li>{@code CbTrn02C} &mdash; full posting engine
 *       (paragraph {@code 1500-A-LOOKUP-XREF})</li>
 *   <li>{@code CoActVwC}, {@code CoActUpC} &mdash; account view/update
 *       (cross-reference lookup)</li>
 *   <li>{@code CoBil00C}, {@code CoTrn02C} &mdash; bill payment, transaction add</li>
 *   <li>{@code FileCardXrefRepository} &mdash; file-based adapter</li>
 * </ul>
 *
 * @see com.blitzy.carddemo.domain.annotation.CobolProgram
 * @since 1.0.0
 */
@CobolProgram(
        value = "CVACT03Y",
        sourcePath = "app/cpy/CVACT03Y.cpy",
        notes = "50-byte CARD-XREF-RECORD: 16-byte PAN + 9-digit cust id + 11-digit acct id + 14-byte FILLER. "
                + "PAN-masking is the carddemo-application layer's responsibility (AAP §0.7.2)."
)
public record CardXrefRecord(
        String xrefCardNum,
        long xrefCustId,
        long xrefAcctId,
        byte[] filler) {

    // ------------------------------------------------------------------
    // Layout constants (BINDING per file schema — names form part of the
    // public contract; do NOT rename without updating the file schema)
    // ------------------------------------------------------------------

    /** Total CARD-XREF-RECORD length in bytes per {@code CVACT03Y}. */
    public static final int RECORD_LENGTH = 50;

    /** Byte offset of {@code XREF-CARD-NUM} within the 50-byte record. */
    public static final int XREF_CARD_NUM_OFFSET = 0;

    /** Byte length of {@code XREF-CARD-NUM} (PIC X(16)). */
    public static final int XREF_CARD_NUM_LENGTH = 16;

    /** Byte offset of {@code XREF-CUST-ID} within the 50-byte record. */
    public static final int XREF_CUST_ID_OFFSET = 16;

    /** Byte length of {@code XREF-CUST-ID} (PIC 9(09)). */
    public static final int XREF_CUST_ID_LENGTH = 9;

    /** Byte offset of {@code XREF-ACCT-ID} within the 50-byte record. */
    public static final int XREF_ACCT_ID_OFFSET = 25;

    /** Byte length of {@code XREF-ACCT-ID} (PIC 9(11)). */
    public static final int XREF_ACCT_ID_LENGTH = 11;

    /** Byte offset of trailing {@code FILLER} within the 50-byte record. */
    public static final int FILLER_OFFSET = 36;

    /** Byte length of trailing {@code FILLER} (PIC X(14)). */
    public static final int FILLER_LENGTH = 14;

    /** ASCII space byte (0x20) used for right-padding string fields. */
    private static final byte ASCII_SPACE = (byte) 0x20;

    /** ASCII digit '0' (0x30) — first digit code point in US-ASCII. */
    private static final byte ASCII_ZERO = (byte) '0';

    /** ASCII digit '9' (0x39) — last digit code point in US-ASCII. */
    private static final byte ASCII_NINE = (byte) '9';

    /**
     * Inclusive upper bound for {@link #xrefCustId()} so that the value fits
     * in the {@value #XREF_CUST_ID_LENGTH}-digit XREF-CUST-ID field
     * (PIC 9(09)). {@code 10^9 - 1 = 999_999_999}. Values above this cannot
     * be encoded into the 9-byte slot without corrupting the record layout;
     * the canonical constructor rejects them at construction time.
     */
    public static final long XREF_CUST_ID_MAX = 999_999_999L;

    /**
     * Inclusive upper bound for {@link #xrefAcctId()} so that the value fits
     * in the {@value #XREF_ACCT_ID_LENGTH}-digit XREF-ACCT-ID field
     * (PIC 9(11)). {@code 10^11 - 1 = 99_999_999_999}.
     */
    public static final long XREF_ACCT_ID_MAX = 99_999_999_999L;

    // ------------------------------------------------------------------
    // Compact canonical constructor (JEP 513 Flexible Constructor Bodies —
    // validation logic runs before the implicit field assignments).
    // ------------------------------------------------------------------

    /**
     * Compact canonical constructor. Validates every component against its
     * COBOL PIC clause and defensively clones the mutable {@code byte[]}
     * filler. Uses JEP 513 Flexible Constructor Bodies (finalized in
     * Java 25) to run validation before the implicit field assignments.
     *
     * @throws NullPointerException     if {@code xrefCardNum} or {@code filler}
     *                                  is {@code null}
     * @throws IllegalArgumentException if any component exceeds its COBOL
     *                                  PIC range or length, or if
     *                                  {@code filler.length} is not exactly
     *                                  {@value #FILLER_LENGTH}
     */
    public CardXrefRecord {
        Objects.requireNonNull(xrefCardNum, "xrefCardNum");
        Objects.requireNonNull(filler, "filler");
        if (xrefCardNum.length() > XREF_CARD_NUM_LENGTH) {
            throw new IllegalArgumentException(
                    "xrefCardNum exceeds " + XREF_CARD_NUM_LENGTH + " chars, got "
                            + xrefCardNum.length());
        }
        if (xrefCustId < 0L) {
            throw new IllegalArgumentException(
                    "xrefCustId must be non-negative, got " + xrefCustId);
        }
        if (xrefCustId > XREF_CUST_ID_MAX) {
            throw new IllegalArgumentException(
                    "xrefCustId exceeds " + XREF_CUST_ID_LENGTH + "-digit range (max "
                            + XREF_CUST_ID_MAX + "), got " + xrefCustId);
        }
        if (xrefAcctId < 0L) {
            throw new IllegalArgumentException(
                    "xrefAcctId must be non-negative, got " + xrefAcctId);
        }
        if (xrefAcctId > XREF_ACCT_ID_MAX) {
            throw new IllegalArgumentException(
                    "xrefAcctId exceeds " + XREF_ACCT_ID_LENGTH + "-digit range (max "
                            + XREF_ACCT_ID_MAX + "), got " + xrefAcctId);
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

    // ------------------------------------------------------------------
    // parse(byte[]) — fixed-width buffer → record factory
    // ------------------------------------------------------------------

    /**
     * Parses a {@value #RECORD_LENGTH}-byte fixed-width buffer into a
     * {@code CardXrefRecord}. The buffer layout is the canonical 50-byte
     * COBOL {@code CARD-XREF-RECORD} per copybook {@code CVACT03Y}.
     *
     * <p>The 9 ASCII fixture files under {@code app/data/ASCII/} use a
     * 36-byte line format (no trailing FILLER). Code that consumes the
     * fixture form must pad the line to {@value #RECORD_LENGTH} bytes with
     * ASCII spaces (0x20) before calling this factory; see
     * {@code java/MIGRATION_NOTES.md §1.4.9} for the rationale.
     *
     * @param buffer fixed-width byte buffer of exactly
     *               {@value #RECORD_LENGTH} bytes
     * @return a new {@code CardXrefRecord} populated from the buffer
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code buffer.length} is not
     *                                  exactly {@value #RECORD_LENGTH}, or
     *                                  if a numeric field contains a
     *                                  non-digit byte
     */
    public static CardXrefRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "CardXrefRecord buffer must be exactly " + RECORD_LENGTH
                            + " bytes; got " + buffer.length);
        }
        String xrefCardNum = new String(
                buffer, XREF_CARD_NUM_OFFSET, XREF_CARD_NUM_LENGTH, StandardCharsets.US_ASCII);
        long xrefCustId = parseAsciiDigits(
                buffer, XREF_CUST_ID_OFFSET, XREF_CUST_ID_LENGTH, "xrefCustId");
        long xrefAcctId = parseAsciiDigits(
                buffer, XREF_ACCT_ID_OFFSET, XREF_ACCT_ID_LENGTH, "xrefAcctId");
        byte[] filler = Arrays.copyOfRange(
                buffer, FILLER_OFFSET, FILLER_OFFSET + FILLER_LENGTH);
        return new CardXrefRecord(xrefCardNum, xrefCustId, xrefAcctId, filler);
    }

    // ------------------------------------------------------------------
    // encode() — record → fixed-width buffer
    // ------------------------------------------------------------------

    /**
     * Encodes this record as a canonical {@value #RECORD_LENGTH}-byte
     * COBOL {@code CARD-XREF-RECORD} buffer. The output is byte-identical
     * to the input of {@link #parse(byte[])} for any record obtained from
     * {@code parse} on a valid buffer (round-trip identity).
     *
     * <p>Encoding rules (per AAP &sect;0.6.5):
     * <ul>
     *   <li>The buffer is first initialised with ASCII spaces (0x20)
     *       so unwritten regions remain printable.</li>
     *   <li>{@code XREF-CARD-NUM} (PIC X(16)) is encoded as US-ASCII bytes
     *       with right-padding using ASCII spaces.</li>
     *   <li>{@code XREF-CUST-ID} (PIC 9(09)) is encoded as ASCII digits
     *       with left-padding using ASCII zeros.</li>
     *   <li>{@code XREF-ACCT-ID} (PIC 9(11)) is encoded as ASCII digits
     *       with left-padding using ASCII zeros.</li>
     *   <li>FILLER is copied verbatim from the record's filler array.</li>
     * </ul>
     *
     * @return a fresh {@value #RECORD_LENGTH}-byte buffer encoding this
     *         record
     */
    public byte[] encode() {
        byte[] out = new byte[RECORD_LENGTH];
        // Initialise everything to ASCII space so trailing unwritten bytes
        // of variable-length string fields remain space-padded.
        Arrays.fill(out, ASCII_SPACE);

        // XREF-CARD-NUM — string, space-padded RIGHT.
        writeAsciiString(out, xrefCardNum, XREF_CARD_NUM_OFFSET, XREF_CARD_NUM_LENGTH);

        // XREF-CUST-ID — numeric, zero-padded LEFT.
        writeAsciiDigits(out, xrefCustId, XREF_CUST_ID_OFFSET, XREF_CUST_ID_LENGTH);

        // XREF-ACCT-ID — numeric, zero-padded LEFT.
        writeAsciiDigits(out, xrefAcctId, XREF_ACCT_ID_OFFSET, XREF_ACCT_ID_LENGTH);

        // FILLER — preserve verbatim.
        System.arraycopy(filler, 0, out, FILLER_OFFSET, FILLER_LENGTH);

        return out;
    }

    // ------------------------------------------------------------------
    // equals / hashCode overrides for byte[] field handling
    // ------------------------------------------------------------------

    /**
     * Value-equality comparison. Overrides the default record-generated
     * implementation so that the {@code byte[] filler} component is
     * compared by content via {@link Arrays#equals(byte[], byte[])}
     * instead of by reference identity (which is what {@code Object.equals}
     * would do).
     *
     * @param o the object to compare against
     * @return {@code true} iff {@code o} is a {@code CardXrefRecord} with
     *         equal {@code xrefCardNum}, {@code xrefCustId},
     *         {@code xrefAcctId}, and {@code filler} content
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CardXrefRecord that)) {
            return false;
        }
        return xrefCustId == that.xrefCustId
                && xrefAcctId == that.xrefAcctId
                && xrefCardNum.equals(that.xrefCardNum)
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
        int result = xrefCardNum.hashCode();
        result = 31 * result + Long.hashCode(xrefCustId);
        result = 31 * result + Long.hashCode(xrefAcctId);
        result = 31 * result + Arrays.hashCode(filler);
        return result;
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Parses a fixed-width run of US-ASCII digit bytes as an unsigned long.
     * Strict: any byte outside {@code '0'..'9'} causes
     * {@link IllegalArgumentException}.
     *
     * @param buffer    the source byte buffer
     * @param offset    starting offset within {@code buffer}
     * @param length    number of digits to consume
     * @param fieldName name of the field for error reporting
     * @return the decoded non-negative integer value
     * @throws IllegalArgumentException on a non-digit byte
     */
    private static long parseAsciiDigits(byte[] buffer, int offset, int length, String fieldName) {
        long value = 0L;
        for (int i = 0; i < length; i++) {
            byte b = buffer[offset + i];
            if (b < ASCII_ZERO || b > ASCII_NINE) {
                throw new IllegalArgumentException(String.format(
                        "Non-digit byte 0x%02X at offset %d while parsing %s",
                        (b & 0xFF), (offset + i), fieldName));
            }
            value = (value * 10L) + (b - ASCII_ZERO);
        }
        return value;
    }

    /**
     * Writes an unsigned long into {@code buffer[offset..offset+length)} as
     * fixed-width ASCII digits with left zero-padding. Mirrors COBOL
     * {@code PIC 9(n)} DISPLAY representation.
     *
     * @param buffer destination byte buffer
     * @param value  non-negative integer value (validated by caller)
     * @param offset starting offset within {@code buffer}
     * @param length number of digit positions to write
     */
    private static void writeAsciiDigits(byte[] buffer, long value, int offset, int length) {
        long remaining = value;
        for (int i = length - 1; i >= 0; i--) {
            buffer[offset + i] = (byte) (ASCII_ZERO + (int) (remaining % 10L));
            remaining /= 10L;
        }
        // Range was already enforced by the canonical constructor; an assertion
        // here documents the invariant for static analysis.
        assert remaining == 0L : "value overflows " + length + "-digit field";
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
     *               length cap enforced by the canonical constructor)
     * @param offset starting offset within {@code buffer}
     * @param length number of byte positions reserved for the field
     */
    private static void writeAsciiString(byte[] buffer, String value, int offset, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int copyLen = Math.min(bytes.length, length);
        System.arraycopy(bytes, 0, buffer, offset, copyLen);
    }
}

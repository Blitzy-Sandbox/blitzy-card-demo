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
 * Immutable domain record translated from the COBOL {@code TRAN-TYPE-RECORD}
 * copybook at {@code app/cpy/CVTRA03Y.cpy} (record length 60 bytes). One
 * {@code TranTypeRecord} instance corresponds to one VSAM KSDS record in the
 * {@code TRANTYPE} dataset on z/OS &mdash; the transaction-type lookup table
 * that maps {@code TRAN-TYPE} codes (2-byte primary key) to human-readable
 * type descriptions.
 *
 * <h2>COBOL source layout (verbatim from {@code app/cpy/CVTRA03Y.cpy})</h2>
 * <pre>{@code
 * 01 TRAN-TYPE-RECORD.
 *    05 TRAN-TYPE                  PIC X(02).
 *    05 TRAN-TYPE-DESC             PIC X(50).
 *    05 FILLER                     PIC X(08).
 * }</pre>
 *
 * <h2>Java field mapping</h2>
 * <table>
 *   <caption>Byte-offset and type mapping</caption>
 *   <tr><th>COBOL field</th>      <th>PIC</th>  <th>Len</th> <th>Offset</th>
 *       <th>Java component</th></tr>
 *   <tr><td>TRAN-TYPE</td>        <td>X(02)</td><td>2</td>   <td>0</td>
 *       <td>{@link #tranType() tranType} : String</td></tr>
 *   <tr><td>TRAN-TYPE-DESC</td>   <td>X(50)</td><td>50</td>  <td>2</td>
 *       <td>{@link #tranTypeDesc() tranTypeDesc} : String</td></tr>
 *   <tr><td>FILLER</td>           <td>X(08)</td><td>8</td>   <td>52</td>
 *       <td>{@link #filler() filler} : byte[8]</td></tr>
 * </table>
 *
 * <h2>Byte-for-byte fidelity contract (AAP &sect;0.6.5)</h2>
 * For every valid {@value #RECORD_LENGTH}-byte buffer {@code b}:
 * <pre>{@code
 *   Arrays.equals(b, TranTypeRecord.parse(b).encode()) == true
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
 *   <li>String fields (PIC X(n)): decoded verbatim (no trimming) so that
 *       round-trip fidelity is preserved; encoded with space-padding (ASCII
 *       0x20) on the RIGHT.</li>
 *   <li>FILLER (8 bytes): preserved verbatim. The ASCII reference fixture
 *       {@code app/data/ASCII/trantype.txt} happens to populate FILLER with
 *       {@code "00000000"} (eight ASCII zero characters, NOT NUL bytes); the
 *       record holds whatever bytes the source file actually contains under
 *       defensive copy semantics so the round-trip contract holds for any
 *       byte pattern.</li>
 * </ul>
 *
 * <h2>Data-driven taxonomy (AAP &sect;0.6.10)</h2>
 * Transaction types are <strong>not</strong> a closed sealed hierarchy: they
 * are loaded dynamically from the {@code TRANTYPE} dataset at runtime (the
 * dataset is small &mdash; 7 records as of the reference fixture). This
 * record is therefore a plain value-record, not a sealed-type permit. The
 * closed set of type codes materializes as the in-memory collection of
 * {@code TranTypeRecord} instances loaded by
 * {@code TransactionTypeRepository.findAll()}.
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
 * {@code TranTypeRecord} instances with logically equal contents compare
 * equal and hash to the same value. This is essential for collection-based
 * lookups (e.g., {@code List.contains}, {@code Set} membership, {@code Map}
 * keys) used by the {@code TransactionTypeRepository.findByCode} call path.
 *
 * <h2>Consumers (per AAP &sect;0.4.1)</h2>
 * <ul>
 *   <li>{@code CbTrn02C} &mdash; full posting engine (transaction-type
 *       validation in paragraph 1500-VALIDATE-TRAN).</li>
 *   <li>{@code CbTrn03C} &mdash; paginated transaction detail report
 *       (paragraph 1500-B-LOOKUP-TRANTYPE).</li>
 *   <li>{@code FileTransactionTypeRepository} &mdash; file-based adapter.</li>
 * </ul>
 *
 * <h2>Fixture source</h2>
 * The reference test fixture for this record is the ASCII file
 * {@code app/data/ASCII/trantype.txt} (REFERENCE per AAP &sect;0.4.1).
 *
 * @see com.blitzy.carddemo.domain.annotation.CobolProgram
 * @see TranCatRecord
 * @see com.blitzy.carddemo.domain.port.TransactionTypeRepository
 * @since 1.0.0
 */
@CobolProgram(
        value = "CVTRA03Y",
        sourcePath = "app/cpy/CVTRA03Y.cpy",
        notes = "60-byte TRAN-TYPE-RECORD: 2-byte TRAN-TYPE primary key + 50-byte description "
                + "+ 8-byte FILLER. Loaded from TRANTYPE dataset (7 records). Data-driven "
                + "taxonomy per AAP §0.6.10 — not a sealed hierarchy."
)
public record TranTypeRecord(
        String tranType,
        String tranTypeDesc,
        byte[] filler) {

    // ------------------------------------------------------------------
    // Layout constants (BINDING per file schema — names form part of the
    // public contract; do NOT rename without updating the file schema).
    // ------------------------------------------------------------------

    /** Total TRAN-TYPE-RECORD length in bytes per {@code CVTRA03Y}. */
    public static final int RECORD_LENGTH = 60;

    /**
     * Byte offset of {@code TRAN-TYPE} within the {@value #RECORD_LENGTH}-byte
     * record. This is the first field; offset 0.
     */
    public static final int TRAN_TYPE_OFFSET = 0;

    /** Byte length of {@code TRAN-TYPE} (PIC X(02)). */
    public static final int TRAN_TYPE_LENGTH = 2;

    /**
     * Byte offset of {@code TRAN-TYPE-DESC} within the
     * {@value #RECORD_LENGTH}-byte record (immediately after TRAN-TYPE).
     */
    public static final int TRAN_TYPE_DESC_OFFSET = 2;

    /** Byte length of {@code TRAN-TYPE-DESC} (PIC X(50)). */
    public static final int TRAN_TYPE_DESC_LENGTH = 50;

    /**
     * Byte offset of trailing {@code FILLER} within the
     * {@value #RECORD_LENGTH}-byte record (immediately after TRAN-TYPE-DESC).
     */
    public static final int FILLER_OFFSET = 52;

    /** Byte length of trailing {@code FILLER} (PIC X(08)). */
    public static final int FILLER_LENGTH = 8;

    /** ASCII space byte (0x20) used for right-padding string fields. */
    private static final byte ASCII_SPACE = (byte) 0x20;

    // ==================================================================
    // Compact canonical constructor — TranTypeRecord
    // ==================================================================

    /**
     * Compact canonical constructor. Validates every component against its
     * COBOL PIC clause and defensively clones the mutable {@code byte[]}
     * filler. Uses JEP 513 Flexible Constructor Bodies (finalized in Java 25)
     * to run validation before the implicit field assignments.
     *
     * @throws NullPointerException     if {@code tranType},
     *                                  {@code tranTypeDesc}, or {@code filler}
     *                                  is {@code null}
     * @throws IllegalArgumentException if {@code tranType} exceeds
     *                                  {@value #TRAN_TYPE_LENGTH} characters,
     *                                  if {@code tranTypeDesc} exceeds
     *                                  {@value #TRAN_TYPE_DESC_LENGTH}
     *                                  characters, or if {@code filler.length}
     *                                  is not exactly {@value #FILLER_LENGTH}
     */
    public TranTypeRecord {
        Objects.requireNonNull(tranType, "tranType");
        Objects.requireNonNull(tranTypeDesc, "tranTypeDesc");
        Objects.requireNonNull(filler, "filler");
        if (tranType.length() > TRAN_TYPE_LENGTH) {
            throw new IllegalArgumentException(
                    "tranType exceeds " + TRAN_TYPE_LENGTH + " chars, got "
                            + tranType.length());
        }
        if (tranTypeDesc.length() > TRAN_TYPE_DESC_LENGTH) {
            throw new IllegalArgumentException(
                    "tranTypeDesc exceeds " + TRAN_TYPE_DESC_LENGTH
                            + " chars, got " + tranTypeDesc.length());
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
     * {@code TranTypeRecord}. The buffer layout is the canonical 60-byte
     * COBOL {@code TRAN-TYPE-RECORD} per copybook {@code CVTRA03Y}.
     *
     * <p>Strict invariants:
     * <ul>
     *   <li>{@code buffer.length} must equal {@value #RECORD_LENGTH}.</li>
     *   <li>The 2-byte {@code TRAN-TYPE} and 50-byte {@code TRAN-TYPE-DESC}
     *       fields are decoded verbatim as US-ASCII (no trimming) so that
     *       round-trip fidelity is preserved.</li>
     *   <li>The 8-byte FILLER is sliced verbatim and stored under defensive
     *       copy semantics.</li>
     * </ul>
     *
     * @param buffer fixed-width byte buffer of exactly
     *               {@value #RECORD_LENGTH} bytes
     * @return a new {@code TranTypeRecord} populated from the buffer
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code buffer.length} is not
     *                                  exactly {@value #RECORD_LENGTH}
     */
    public static TranTypeRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "TranTypeRecord buffer must be exactly " + RECORD_LENGTH
                            + " bytes; got " + buffer.length);
        }
        String tranType = new String(
                buffer, TRAN_TYPE_OFFSET, TRAN_TYPE_LENGTH,
                StandardCharsets.US_ASCII);
        String tranTypeDesc = new String(
                buffer, TRAN_TYPE_DESC_OFFSET, TRAN_TYPE_DESC_LENGTH,
                StandardCharsets.US_ASCII);
        byte[] fillerSlice = Arrays.copyOfRange(
                buffer, FILLER_OFFSET, FILLER_OFFSET + FILLER_LENGTH);
        return new TranTypeRecord(tranType, tranTypeDesc, fillerSlice);
    }

    // ==================================================================
    // encode() — record → fixed-width buffer
    // ==================================================================

    /**
     * Encodes this record as a canonical {@value #RECORD_LENGTH}-byte COBOL
     * {@code TRAN-TYPE-RECORD} buffer. The output is byte-identical to the
     * input of {@link #parse(byte[])} for any record obtained from
     * {@code parse} on a valid buffer (round-trip identity).
     *
     * <p>Encoding rules (per AAP &sect;0.6.5 and binding agent-prompt
     * constraint #4):
     * <ul>
     *   <li>The buffer is first initialised with ASCII spaces (0x20) so
     *       unwritten regions of variable-length string fields remain
     *       printable and right-padded by default.</li>
     *   <li>{@code TRAN-TYPE} (PIC X(02)) is encoded as US-ASCII bytes with
     *       right-padding using ASCII spaces.</li>
     *   <li>{@code TRAN-TYPE-DESC} (PIC X(50)) is encoded as US-ASCII bytes
     *       with right-padding using ASCII spaces.</li>
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

        // TRAN-TYPE — string, space-padded RIGHT.
        writeAsciiString(out, tranType, TRAN_TYPE_OFFSET, TRAN_TYPE_LENGTH);

        // TRAN-TYPE-DESC — string, space-padded RIGHT.
        writeAsciiString(out, tranTypeDesc, TRAN_TYPE_DESC_OFFSET, TRAN_TYPE_DESC_LENGTH);

        // FILLER — preserve verbatim from the record's internal buffer.
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
     * @return {@code true} iff {@code o} is a {@code TranTypeRecord} with
     *         equal {@code tranType}, {@code tranTypeDesc}, and
     *         {@code filler} content
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TranTypeRecord that)) {
            return false;
        }
        return tranType.equals(that.tranType)
                && tranTypeDesc.equals(that.tranTypeDesc)
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
        int result = tranType.hashCode();
        result = 31 * result + tranTypeDesc.hashCode();
        result = 31 * result + Arrays.hashCode(filler);
        return result;
    }

    // ==================================================================
    // Internal helpers
    // ==================================================================

    /**
     * Writes a String into {@code buffer[offset..offset+length)} as
     * fixed-width US-ASCII bytes. The first {@code min(value.length, length)}
     * bytes are copied; any remaining positions are left untouched (and
     * therefore remain at whatever {@code encode()} pre-filled them with,
     * i.e. ASCII space 0x20).
     *
     * <p>Length cap is enforced by the canonical constructor of
     * {@link TranTypeRecord}, so {@code value.getBytes(US_ASCII).length} can
     * never exceed {@code length} for a valid record. The {@code Math.min}
     * is a defensive guard that documents the invariant.
     *
     * @param buffer destination byte buffer
     * @param value  the source string (may be shorter than {@code length};
     *               length cap enforced by the record's canonical constructor)
     * @param offset starting offset within {@code buffer}
     * @param length number of byte positions reserved for the field
     */
    private static void writeAsciiString(byte[] buffer, String value, int offset, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int copyLen = Math.min(bytes.length, length);
        System.arraycopy(bytes, 0, buffer, offset, copyLen);
    }
}

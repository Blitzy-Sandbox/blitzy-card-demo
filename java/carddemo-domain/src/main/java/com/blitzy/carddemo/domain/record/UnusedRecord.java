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
 * Faithful translation of the COBOL {@code UNUSED-DATA} copybook at
 * {@code app/cpy/UNUSED1Y.cpy} (fixed record length 80 bytes). Although this
 * copybook is structurally identical to {@link SecUserData} (both are 80 bytes,
 * with the same field offsets and lengths), it is preserved as a separate
 * record type because no COBOL program references {@code UNUSED1Y}; it is
 * dead code in the original system.
 *
 * <p>The verbatim COBOL layout (offsets are byte-aligned and zero-based):
 *
 * <pre>{@code
 * 01 UNUSED-DATA.
 *    05 UNUSED-ID                  PIC X(08).   (8  bytes — offset 0..7)
 *    05 UNUSED-FNAME               PIC X(20).   (20 bytes — offset 8..27)
 *    05 UNUSED-LNAME               PIC X(20).   (20 bytes — offset 28..47)
 *    05 UNUSED-PWD                 PIC X(08).   (8  bytes — offset 48..55)
 *    05 UNUSED-TYPE                PIC X(01).   (1  byte  — offset 56)
 *    05 UNUSED-FILLER              PIC X(23).   (23 bytes — offset 57..79)
 * }</pre>
 *
 * <h2>Why this record exists (faithful dead-code translation)</h2>
 * Per AAP &sect;0.2.1 the {@code UNUSED1Y} copybook is enumerated in the
 * in-scope copybook inventory; per AAP &sect;0.7.1 (Refactor Discipline)
 * the project must <em>"translate it faithfully even if dead code, and
 * flag it in {@code MIGRATION_NOTES.md}"</em>. No COBOL program in the
 * 28-program inventory issues a {@code COPY UNUSED1Y} directive, but the
 * binding rule is one-record-per-copybook (AAP &sect;0.3.2 Records pattern),
 * so this type is materialised purely for structural completeness and
 * traceability.
 *
 * <p>Callers should treat this record as a structural placeholder: it has
 * no associated repository port, no associated use case, and no associated
 * golden-record fixture under {@code app/data/ASCII/}. Its presence
 * guarantees that any future COBOL maintenance which begins to reference
 * {@code UNUSED1Y} has a ready-made Java counterpart waiting.
 *
 * <h2>Companion record</h2>
 * The layout exactly matches {@link SecUserData} (also 80 bytes with
 * parallel field structure). The two records are deliberately NOT
 * consolidated &mdash; preserving the COBOL one-copybook-one-Java-record
 * traceability mandated by AAP &sect;0.3.2 and the cardinality preservation
 * rule from AAP &sect;0.1.3 ("CBACT01C remains CbAct01C, ...").
 *
 * <h2>Byte-for-byte fidelity contract</h2>
 * Per AAP &sect;0.6.5 the {@link #parse(byte[])} factory and
 * {@link #encode()} method are inverses. For every valid 80-byte buffer
 * {@code b}, {@code Arrays.equals(UnusedRecord.parse(b).encode(), b)}
 * returns {@code true}. Symmetrically, for every record {@code r} that
 * this class can produce,
 * {@code UnusedRecord.parse(r.encode()).equals(r)} holds.
 *
 * <p>US-ASCII ({@link StandardCharsets#US_ASCII}) is the canonical
 * charset because the golden-record fixtures at
 * {@code app/data/ASCII/*.txt} are pre-transcoded ASCII. Production
 * EBCDIC inputs are transcoded by a separate {@code EbcdicTranscoder}
 * adapter in the {@code carddemo-adapter-file} module before reaching
 * this domain record (AAP &sect;0.6.5).
 *
 * <h2>Field padding semantics</h2>
 * COBOL {@code PIC X(n)} fields are right-padded with spaces ({@code 0x20})
 * to their declared length. The {@link #encode()} method preserves this
 * convention by initialising the output buffer to ASCII spaces before
 * writing each field. String values shorter than the field length are
 * written verbatim followed by trailing spaces; values longer than the
 * field length are rejected by the compact canonical constructor.
 *
 * <h2>Immutability and defensive copying</h2>
 * This is a Java {@code record}, so the instance is shallowly immutable.
 * The 23-byte {@code unusedFiller} byte array is defensively cloned in
 * the compact constructor so that callers cannot mutate the record's
 * internal state via the array reference they supplied. The default
 * record accessor for {@code unusedFiller()} does not clone on read;
 * callers who intend to mutate the returned array should clone it
 * themselves, but in practice every caller is expected to treat the
 * result as read-only.
 *
 * <h2>{@code equals}/{@code hashCode}/{@code toString} overrides</h2>
 * The default record-generated {@code equals} would compare the
 * {@code unusedFiller} component by reference identity (because the
 * runtime does not know that a record component holds a byte array),
 * which would defeat byte-for-byte parity testing in the golden-record
 * harness. This record therefore overrides {@code equals},
 * {@code hashCode}, and {@code toString} to use
 * {@link Arrays#equals(byte[], byte[])} and
 * {@link Arrays#hashCode(byte[])} for the FILLER component.
 *
 * @param unusedId      placeholder id (PIC X(08)); space-padded on the right to 8 characters
 * @param unusedFname   placeholder first name (PIC X(20)); space-padded on the right to 20 characters
 * @param unusedLname   placeholder last name (PIC X(20)); space-padded on the right to 20 characters
 * @param unusedPwd     placeholder password (PIC X(08)); space-padded on the right to 8 characters
 * @param unusedType    placeholder type code (PIC X(01)); a single 8-bit character
 * @param unusedFiller  trailing 23-byte FILLER preserved verbatim for byte-fidelity;
 *                      defensively cloned by the compact constructor
 * @see SecUserData the structurally identical (and actively used) 80-byte counterpart
 * @see CobolProgram the type-level traceability annotation cite policy
 */
@CobolProgram(
        value = "UNUSED1Y",
        sourcePath = "app/cpy/UNUSED1Y.cpy",
        notes = "80-byte UNUSED-DATA; structurally identical to SEC-USER-DATA but unreferenced "
              + "by any COBOL program. Translated for completeness per AAP §0.7.1 "
              + "(\"Translate it faithfully even if dead code, flag in MIGRATION_NOTES.md\")."
)
public record UnusedRecord(
        String unusedId,
        String unusedFname,
        String unusedLname,
        String unusedPwd,
        char unusedType,
        byte[] unusedFiller) {

    // -----------------------------------------------------------------------
    // Field offset and length constants (binary layout — DO NOT REORDER)
    // -----------------------------------------------------------------------

    /** Total fixed record length in bytes (sum of all field lengths). */
    public static final int RECORD_LENGTH = 80;

    /** Zero-based byte offset of {@link #unusedId()} within the record buffer. */
    public static final int UNUSED_ID_OFFSET = 0;

    /** Byte length of {@link #unusedId()} (COBOL PIC X(08)). */
    public static final int UNUSED_ID_LENGTH = 8;

    /** Zero-based byte offset of {@link #unusedFname()} within the record buffer. */
    public static final int UNUSED_FNAME_OFFSET = 8;

    /** Byte length of {@link #unusedFname()} (COBOL PIC X(20)). */
    public static final int UNUSED_FNAME_LENGTH = 20;

    /** Zero-based byte offset of {@link #unusedLname()} within the record buffer. */
    public static final int UNUSED_LNAME_OFFSET = 28;

    /** Byte length of {@link #unusedLname()} (COBOL PIC X(20)). */
    public static final int UNUSED_LNAME_LENGTH = 20;

    /** Zero-based byte offset of {@link #unusedPwd()} within the record buffer. */
    public static final int UNUSED_PWD_OFFSET = 48;

    /** Byte length of {@link #unusedPwd()} (COBOL PIC X(08)). */
    public static final int UNUSED_PWD_LENGTH = 8;

    /** Zero-based byte offset of {@link #unusedType()} within the record buffer. */
    public static final int UNUSED_TYPE_OFFSET = 56;

    /** Byte length of {@link #unusedType()} (COBOL PIC X(01)). */
    public static final int UNUSED_TYPE_LENGTH = 1;

    /** Zero-based byte offset of {@link #unusedFiller()} within the record buffer. */
    public static final int UNUSED_FILLER_OFFSET = 57;

    /** Byte length of {@link #unusedFiller()} (COBOL PIC X(23)). */
    public static final int UNUSED_FILLER_LENGTH = 23;

    // -----------------------------------------------------------------------
    // Internal constants
    // -----------------------------------------------------------------------

    /** ASCII space byte (0x20) — the COBOL space-fill character. */
    private static final byte ASCII_SPACE = (byte) ' ';

    // -----------------------------------------------------------------------
    // Compact canonical constructor (validation + defensive cloning)
    // -----------------------------------------------------------------------

    /**
     * Compact canonical constructor (JEP 513-style flexible constructor body
     * per AAP &sect;0.6.3 / &sect;0.7.3) that:
     * <ul>
     *   <li>Rejects {@code null} for any non-primitive component.</li>
     *   <li>Rejects oversized String components (longer than the matching
     *       COBOL {@code PIC X(n)} field length).</li>
     *   <li>Rejects a FILLER byte array with a length other than exactly
     *       {@value #UNUSED_FILLER_LENGTH} bytes.</li>
     *   <li>Defensively clones the FILLER byte array so the caller cannot
     *       mutate the record's internal state via the array reference
     *       they supplied.</li>
     * </ul>
     *
     * <p>Shorter String components are tolerated and are right-padded with
     * ASCII spaces at {@link #encode()} time; this mirrors COBOL's
     * {@code MOVE} semantics where a shorter source is space-padded to the
     * declared length of the receiving field.
     *
     * @throws NullPointerException     if any String component or
     *                                  {@code unusedFiller} is {@code null}
     * @throws IllegalArgumentException if a String component exceeds the
     *                                  COBOL field length, or if
     *                                  {@code unusedFiller.length !=
     *                                  UNUSED_FILLER_LENGTH}
     */
    public UnusedRecord {
        Objects.requireNonNull(unusedId, "unusedId");
        Objects.requireNonNull(unusedFname, "unusedFname");
        Objects.requireNonNull(unusedLname, "unusedLname");
        Objects.requireNonNull(unusedPwd, "unusedPwd");
        Objects.requireNonNull(unusedFiller, "unusedFiller");
        if (unusedId.length() > UNUSED_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "unusedId exceeds " + UNUSED_ID_LENGTH + " chars: length=" + unusedId.length());
        }
        if (unusedFname.length() > UNUSED_FNAME_LENGTH) {
            throw new IllegalArgumentException(
                    "unusedFname exceeds " + UNUSED_FNAME_LENGTH + " chars: length=" + unusedFname.length());
        }
        if (unusedLname.length() > UNUSED_LNAME_LENGTH) {
            throw new IllegalArgumentException(
                    "unusedLname exceeds " + UNUSED_LNAME_LENGTH + " chars: length=" + unusedLname.length());
        }
        if (unusedPwd.length() > UNUSED_PWD_LENGTH) {
            throw new IllegalArgumentException(
                    "unusedPwd exceeds " + UNUSED_PWD_LENGTH + " chars: length=" + unusedPwd.length());
        }
        if (unusedFiller.length != UNUSED_FILLER_LENGTH) {
            throw new IllegalArgumentException(
                    "unusedFiller must be exactly " + UNUSED_FILLER_LENGTH
                            + " bytes, got " + unusedFiller.length);
        }
        // Defensive clone so the caller cannot mutate the record's internal state
        // via the array reference they supplied (records do not auto-copy components).
        unusedFiller = unusedFiller.clone();
    }

    // -----------------------------------------------------------------------
    // parse(byte[]) factory
    // -----------------------------------------------------------------------

    /**
     * Parses an 80-byte fixed-width buffer into an {@code UnusedRecord} record.
     * The buffer is interpreted as ASCII (US-7) per the golden-record fixture
     * convention (AAP &sect;0.6.5). Trailing spaces in COBOL {@code PIC X(n)}
     * fields are preserved verbatim in the returned String components so that
     * {@link #encode()} can produce a byte-identical round-trip.
     *
     * <p>The {@code unusedType} byte is unpacked into a {@code char} via the
     * standard widening conversion {@code (char) (b & 0xFF)} so that 8-bit
     * byte values (including non-ASCII overpunch characters that COBOL may
     * emit for unusual TYPE values) survive the round trip intact.
     *
     * @param buffer the 80-byte input buffer (typically read from a file at a
     *               specific offset via {@code Files.readAllBytes} or
     *               {@code Files.newByteChannel})
     * @return a fully-populated {@code UnusedRecord} record
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code buffer.length != RECORD_LENGTH}
     */
    public static UnusedRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "UnusedRecord buffer must be exactly " + RECORD_LENGTH
                            + " bytes; got " + buffer.length);
        }
        String unusedId    = new String(buffer, UNUSED_ID_OFFSET,    UNUSED_ID_LENGTH,    StandardCharsets.US_ASCII);
        String unusedFname = new String(buffer, UNUSED_FNAME_OFFSET, UNUSED_FNAME_LENGTH, StandardCharsets.US_ASCII);
        String unusedLname = new String(buffer, UNUSED_LNAME_OFFSET, UNUSED_LNAME_LENGTH, StandardCharsets.US_ASCII);
        String unusedPwd   = new String(buffer, UNUSED_PWD_OFFSET,   UNUSED_PWD_LENGTH,   StandardCharsets.US_ASCII);
        // Widen the single TYPE byte to a char preserving the full unsigned 8-bit value.
        char unusedType = (char) (buffer[UNUSED_TYPE_OFFSET] & 0xFF);
        byte[] unusedFiller = Arrays.copyOfRange(
                buffer, UNUSED_FILLER_OFFSET, UNUSED_FILLER_OFFSET + UNUSED_FILLER_LENGTH);
        return new UnusedRecord(unusedId, unusedFname, unusedLname, unusedPwd, unusedType, unusedFiller);
    }

    // -----------------------------------------------------------------------
    // encode() — byte-for-byte serialization
    // -----------------------------------------------------------------------

    /**
     * Serializes this record into a fresh 80-byte buffer suitable for writing
     * to a file. The output buffer is first filled with ASCII spaces
     * ({@code 0x20}) so that shorter String components are automatically
     * right-padded to their COBOL {@code PIC X(n)} length. The FILLER byte
     * array is copied verbatim.
     *
     * <p>The {@code unusedType} char is downcast to a byte via {@code (byte) c};
     * this is the inverse of the {@code (char) (b & 0xFF)} widening done in
     * {@link #parse(byte[])} and is lossless for any char in the 0..255 range
     * (which is the only range that round-trips through a single COBOL
     * {@code PIC X(01)} byte).
     *
     * @return a new 80-byte buffer; never {@code null}. The caller owns the
     *         returned array and may mutate it without affecting the record.
     */
    public byte[] encode() {
        byte[] out = new byte[RECORD_LENGTH];
        Arrays.fill(out, ASCII_SPACE); // pad all positions with ASCII space first
        writeString(out, unusedId,    UNUSED_ID_OFFSET,    UNUSED_ID_LENGTH);
        writeString(out, unusedFname, UNUSED_FNAME_OFFSET, UNUSED_FNAME_LENGTH);
        writeString(out, unusedLname, UNUSED_LNAME_OFFSET, UNUSED_LNAME_LENGTH);
        writeString(out, unusedPwd,   UNUSED_PWD_OFFSET,   UNUSED_PWD_LENGTH);
        out[UNUSED_TYPE_OFFSET] = (byte) unusedType;
        System.arraycopy(unusedFiller, 0, out, UNUSED_FILLER_OFFSET, UNUSED_FILLER_LENGTH);
        return out;
    }

    /**
     * Writes a String value into {@code out} at the given offset using
     * US-ASCII encoding. Bytes beyond {@code value.length()} (up to the field
     * {@code length}) are left untouched; the caller is responsible for
     * pre-filling those positions with the desired pad character (ASCII space
     * in this record). The number of bytes written equals
     * {@code min(value.getBytes(US_ASCII).length, length)} &mdash; oversized
     * values are silently truncated by the {@code Math.min} clamp, although
     * the compact constructor rejects them up-front, so in practice this
     * truncation path is dead code reachable only via reflection.
     */
    private static void writeString(byte[] out, String value, int offset, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int copyLen = Math.min(bytes.length, length);
        System.arraycopy(bytes, 0, out, offset, copyLen);
    }

    // -----------------------------------------------------------------------
    // Object identity / equality / debugging
    // -----------------------------------------------------------------------

    /**
     * Value-based equality. Two {@code UnusedRecord} instances are equal iff
     * every component is element-wise equal, where the {@link #unusedFiller()}
     * byte array is compared with {@link Arrays#equals(byte[], byte[])}.
     *
     * <p>The default record-generated {@code equals} would compare
     * {@link #unusedFiller()} by reference identity (because the runtime
     * does not know that a record component holds a byte array), which
     * would defeat byte-for-byte parity testing in the golden-record
     * harness. This override restores deep value-equality semantics.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UnusedRecord that)) {
            return false;
        }
        return unusedType == that.unusedType
                && unusedId.equals(that.unusedId)
                && unusedFname.equals(that.unusedFname)
                && unusedLname.equals(that.unusedLname)
                && unusedPwd.equals(that.unusedPwd)
                && Arrays.equals(unusedFiller, that.unusedFiller);
    }

    /**
     * Hash code consistent with {@link #equals(Object)}; uses
     * {@link Arrays#hashCode(byte[])} for the FILLER byte array to mirror the
     * deep-equality semantics.
     */
    @Override
    public int hashCode() {
        int result = unusedId.hashCode();
        result = 31 * result + unusedFname.hashCode();
        result = 31 * result + unusedLname.hashCode();
        result = 31 * result + unusedPwd.hashCode();
        result = 31 * result + Character.hashCode(unusedType);
        result = 31 * result + Arrays.hashCode(unusedFiller);
        return result;
    }

    /**
     * Returns a debug-friendly string representation of this record. The
     * default record-generated {@code toString} would render the FILLER byte
     * array via its identity hashcode-style array address (e.g.,
     * {@code [B@1a2b3c}), which is useless for fixture debugging. This
     * override uses {@link Arrays#toString(byte[])} so the FILLER contents
     * are visible.
     *
     * <p>Unlike {@link SecUserData}, {@code UnusedRecord} carries no
     * credential-class field, so no masking is required: there is no
     * defensive {@code PASSWORD_MASK} substitution. The {@code unusedPwd}
     * field is included verbatim because it is a placeholder per the
     * dead-code translation rationale documented at the type Javadoc.
     */
    @Override
    public String toString() {
        return "UnusedRecord["
                + "unusedId=" + unusedId
                + ", unusedFname=" + unusedFname
                + ", unusedLname=" + unusedLname
                + ", unusedPwd=" + unusedPwd
                + ", unusedType=" + unusedType
                + ", unusedFiller=" + Arrays.toString(unusedFiller)
                + "]";
    }
}

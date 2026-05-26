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
 * Faithful translation of the COBOL {@code SEC-USER-DATA} copybook at
 * {@code app/cpy/CSUSR01Y.cpy} (fixed record length 80 bytes). This record
 * is the in-memory representation of one row of the {@code USRSEC} VSAM
 * KSDS dataset, keyed by {@link #secUsrId() SEC-USR-ID}, used by the
 * signon and user-administration flows (COSGN00C, COUSR00C, COUSR01C,
 * COUSR02C, COUSR03C).
 *
 * <p>The verbatim COBOL layout (offsets are byte-aligned and zero-based):
 *
 * <pre>{@code
 * 01 SEC-USER-DATA.
 *    05 SEC-USR-ID                 PIC X(08).   (8 bytes  — offset 0..7)
 *    05 SEC-USR-FNAME              PIC X(20).   (20 bytes — offset 8..27)
 *    05 SEC-USR-LNAME              PIC X(20).   (20 bytes — offset 28..47)
 *    05 SEC-USR-PWD                PIC X(08).   (8 bytes  — offset 48..55)  ** PLAINTEXT **
 *    05 SEC-USR-TYPE               PIC X(01).   (1 byte   — offset 56)
 *    05 SEC-USR-FILLER             PIC X(23).   (23 bytes — offset 57..79)
 * }</pre>
 *
 * <h2>Plaintext password preservation (security note)</h2>
 * Per AAP &sect;0.1.3 and &sect;0.7.2 the COBOL system stores user
 * passwords in plaintext as {@code PIC X(08)}. This refactor preserves
 * that behavior verbatim for byte-for-byte parity with the COBOL
 * baseline. Moving to a password-hashing scheme (BCrypt / Argon2 / KDF
 * via JEP 510) is a separate enhancement that has been deliberately
 * placed OUT OF SCOPE for this migration and is tracked in
 * {@code java/MIGRATION_NOTES.md}.
 *
 * <p>The plaintext password value is masked as {@code "********"} in
 * {@link #toString()} to defend against accidental credential leakage
 * via logs, stack traces, debugger displays, and exception messages.
 * The underlying stored value is unchanged and remains accessible via
 * {@link #secUsrPwd()} for the COSGN00C credential-comparison path. The
 * same no-credential-in-logs principle that the AAP applies to card PANs
 * applies here: callers MUST NOT emit {@link #secUsrPwd()} to any log
 * sink.
 *
 * <h2>Byte-for-byte fidelity contract</h2>
 * Per AAP &sect;0.6.5 the {@link #parse(byte[])} factory and
 * {@link #encode()} method are inverses. For every valid 80-byte buffer
 * {@code b} produced by the COBOL implementation,
 * {@code Arrays.equals(SecUserData.parse(b).encode(), b)} returns
 * {@code true}. Symmetrically, for every record {@code r} that this
 * class can produce, {@code SecUserData.parse(r.encode()).equals(r)}
 * holds. ASCII US-7 ({@link StandardCharsets#US_ASCII}) is the canonical
 * charset because the golden-record fixtures at
 * {@code app/data/ASCII/*.txt} are pre-transcoded ASCII. Production
 * EBCDIC inputs are transcoded by a separate {@code EbcdicTranscoder}
 * adapter in the {@code carddemo-adapter-file} module before reaching
 * this domain record.
 *
 * <h2>Field padding semantics</h2>
 * COBOL {@code PIC X(n)} fields are right-padded with spaces to their
 * declared length. The {@link #encode()} method preserves this
 * convention by initialising the output buffer to ASCII spaces
 * ({@code 0x20}) before writing each field. String values shorter than
 * the field length are written verbatim followed by trailing spaces;
 * values longer than the field length are rejected by the compact
 * canonical constructor.
 *
 * <h2>Companion record</h2>
 * The layout exactly matches {@code UnusedRecord} (also 80 bytes with
 * parallel field structure) but the field names differ. The two records
 * are deliberately NOT consolidated &mdash; preserving COBOL
 * one-copybook-one-Java-record traceability per AAP &sect;0.3.2.
 *
 * <h2>Immutability</h2>
 * This is a Java {@code record}, so the instance is shallowly immutable.
 * The {@link #secUsrFiller()} accessor returns the stored array
 * directly because the array is defensively cloned in the compact
 * constructor; callers receive a private array each time they receive
 * a {@code SecUserData} instance, but the default record accessor does
 * not clone on every read. Mutations to the returned array WILL leak
 * back into the record &mdash; treat the result as read-only.
 *
 * @param secUsrId      user id, the primary VSAM key (PIC X(08)); space-padded
 *                      on the right to 8 characters
 * @param secUsrFname   first name (PIC X(20)); space-padded on the right to 20 characters
 * @param secUsrLname   last name (PIC X(20)); space-padded on the right to 20 characters
 * @param secUsrPwd     plaintext password (PIC X(08)); preserved as-is per AAP &sect;0.1.3,
 *                      masked in {@link #toString()}
 * @param secUsrType    user-type code (PIC X(01)); typically {@code 'A'} for administrator
 *                      or {@code 'U'} for regular user; space ({@code ' '}) when unknown
 * @param secUsrFiller  trailing 23-byte FILLER preserved verbatim for byte-fidelity;
 *                      defensively cloned by the compact constructor
 * @see com.blitzy.carddemo.domain.port.UserSecurityRepository the port that reads/writes this record
 */
@CobolProgram(
        value = "CSUSR01Y",
        sourcePath = "app/cpy/CSUSR01Y.cpy",
        notes = "80-byte SEC-USER-DATA; plaintext password preserved (masked in toString); 23-byte trailing FILLER. "
              + "Companion to UnusedRecord (same shape, different field names). Per AAP §0.1.3 and §0.7.2."
)
public record SecUserData(
        String secUsrId,
        String secUsrFname,
        String secUsrLname,
        String secUsrPwd,
        char secUsrType,
        byte[] secUsrFiller) {

    // -----------------------------------------------------------------------
    // Field offset and length constants (binary layout — DO NOT REORDER)
    // -----------------------------------------------------------------------

    /** Total fixed record length in bytes (sum of all field lengths). */
    public static final int RECORD_LENGTH = 80;

    /** Zero-based byte offset of {@link #secUsrId()} within the record buffer. */
    public static final int SEC_USR_ID_OFFSET = 0;

    /** Byte length of {@link #secUsrId()} (COBOL PIC X(08)). */
    public static final int SEC_USR_ID_LENGTH = 8;

    /** Zero-based byte offset of {@link #secUsrFname()} within the record buffer. */
    public static final int SEC_USR_FNAME_OFFSET = 8;

    /** Byte length of {@link #secUsrFname()} (COBOL PIC X(20)). */
    public static final int SEC_USR_FNAME_LENGTH = 20;

    /** Zero-based byte offset of {@link #secUsrLname()} within the record buffer. */
    public static final int SEC_USR_LNAME_OFFSET = 28;

    /** Byte length of {@link #secUsrLname()} (COBOL PIC X(20)). */
    public static final int SEC_USR_LNAME_LENGTH = 20;

    /** Zero-based byte offset of {@link #secUsrPwd()} within the record buffer. */
    public static final int SEC_USR_PWD_OFFSET = 48;

    /** Byte length of {@link #secUsrPwd()} (COBOL PIC X(08)) — plaintext per AAP §0.1.3. */
    public static final int SEC_USR_PWD_LENGTH = 8;

    /** Zero-based byte offset of {@link #secUsrType()} within the record buffer. */
    public static final int SEC_USR_TYPE_OFFSET = 56;

    /** Byte length of {@link #secUsrType()} (COBOL PIC X(01)). */
    public static final int SEC_USR_TYPE_LENGTH = 1;

    /** Zero-based byte offset of {@link #secUsrFiller()} within the record buffer. */
    public static final int SEC_USR_FILLER_OFFSET = 57;

    /** Byte length of {@link #secUsrFiller()} (COBOL PIC X(23)). */
    public static final int SEC_USR_FILLER_LENGTH = 23;

    // -----------------------------------------------------------------------
    // Internal constants
    // -----------------------------------------------------------------------

    /**
     * Defense-in-depth mask used by {@link #toString()} so the plaintext
     * password never leaks into logs, stack traces, or debugger output.
     * The mask preserves the COBOL field length ({@value #SEC_USR_PWD_LENGTH}
     * characters) and uses the conventional asterisk glyph.
     */
    private static final String PASSWORD_MASK = "********";

    /** ASCII space byte (0x20) — the COBOL space-fill character. */
    private static final byte ASCII_SPACE = (byte) ' ';

    // -----------------------------------------------------------------------
    // Compact canonical constructor (validation + defensive cloning)
    // -----------------------------------------------------------------------

    /**
     * Compact canonical constructor (JEP 513-style flexible constructor body)
     * that:
     * <ul>
     *   <li>Rejects {@code null} for any non-primitive component.</li>
     *   <li>Rejects oversized String components (longer than the matching
     *       COBOL {@code PIC X(n)} field length).</li>
     *   <li>Rejects a FILLER byte array with a length other than exactly
     *       {@value #SEC_USR_FILLER_LENGTH} bytes.</li>
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
     *                                  {@code secUsrFiller} is {@code null}
     * @throws IllegalArgumentException if a String component exceeds the
     *                                  COBOL field length, or if
     *                                  {@code secUsrFiller.length !=
     *                                  SEC_USR_FILLER_LENGTH}
     */
    public SecUserData {
        Objects.requireNonNull(secUsrId, "secUsrId");
        Objects.requireNonNull(secUsrFname, "secUsrFname");
        Objects.requireNonNull(secUsrLname, "secUsrLname");
        Objects.requireNonNull(secUsrPwd, "secUsrPwd");
        Objects.requireNonNull(secUsrFiller, "secUsrFiller");
        if (secUsrId.length() > SEC_USR_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "secUsrId exceeds " + SEC_USR_ID_LENGTH + " chars: length=" + secUsrId.length());
        }
        if (secUsrFname.length() > SEC_USR_FNAME_LENGTH) {
            throw new IllegalArgumentException(
                    "secUsrFname exceeds " + SEC_USR_FNAME_LENGTH + " chars: length=" + secUsrFname.length());
        }
        if (secUsrLname.length() > SEC_USR_LNAME_LENGTH) {
            throw new IllegalArgumentException(
                    "secUsrLname exceeds " + SEC_USR_LNAME_LENGTH + " chars: length=" + secUsrLname.length());
        }
        if (secUsrPwd.length() > SEC_USR_PWD_LENGTH) {
            throw new IllegalArgumentException(
                    "secUsrPwd exceeds " + SEC_USR_PWD_LENGTH + " chars: length=" + secUsrPwd.length());
        }
        if (secUsrFiller.length != SEC_USR_FILLER_LENGTH) {
            throw new IllegalArgumentException(
                    "secUsrFiller must be exactly " + SEC_USR_FILLER_LENGTH
                            + " bytes, got " + secUsrFiller.length);
        }
        secUsrFiller = secUsrFiller.clone();
    }

    // -----------------------------------------------------------------------
    // parse(byte[]) factory
    // -----------------------------------------------------------------------

    /**
     * Parses an 80-byte fixed-width buffer into a {@code SecUserData} record.
     * The buffer is interpreted as ASCII (US-7) per the golden-record fixture
     * convention. Trailing spaces in COBOL {@code PIC X(n)} fields are
     * preserved verbatim in the returned String components so that
     * {@link #encode()} can produce a byte-identical round-trip.
     *
     * <p>The {@code secUsrType} byte is unpacked into a {@code char} via the
     * standard widening conversion {@code (char) (b & 0xFF)} so that
     * 8-bit byte values (including non-ASCII overpunch characters that
     * COBOL may emit for unusual TYPE values) survive the round trip
     * intact.
     *
     * @param buffer the 80-byte input buffer (typically read from a VSAM
     *               file or an ASCII fixture via {@code Files.readAllBytes}
     *               at a specific offset)
     * @return a fully-populated {@code SecUserData} record
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code buffer.length != RECORD_LENGTH}
     */
    public static SecUserData parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "SecUserData buffer must be exactly " + RECORD_LENGTH
                            + " bytes; got " + buffer.length);
        }
        String secUsrId    = new String(buffer, SEC_USR_ID_OFFSET,    SEC_USR_ID_LENGTH,    StandardCharsets.US_ASCII);
        String secUsrFname = new String(buffer, SEC_USR_FNAME_OFFSET, SEC_USR_FNAME_LENGTH, StandardCharsets.US_ASCII);
        String secUsrLname = new String(buffer, SEC_USR_LNAME_OFFSET, SEC_USR_LNAME_LENGTH, StandardCharsets.US_ASCII);
        String secUsrPwd   = new String(buffer, SEC_USR_PWD_OFFSET,   SEC_USR_PWD_LENGTH,   StandardCharsets.US_ASCII);
        // Widen the single TYPE byte to a char preserving the full unsigned 8-bit value.
        char secUsrType = (char) (buffer[SEC_USR_TYPE_OFFSET] & 0xFF);
        byte[] secUsrFiller = Arrays.copyOfRange(
                buffer, SEC_USR_FILLER_OFFSET, SEC_USR_FILLER_OFFSET + SEC_USR_FILLER_LENGTH);
        return new SecUserData(secUsrId, secUsrFname, secUsrLname, secUsrPwd, secUsrType, secUsrFiller);
    }

    // -----------------------------------------------------------------------
    // encode() — byte-for-byte serialization
    // -----------------------------------------------------------------------

    /**
     * Serializes this record into a fresh 80-byte buffer suitable for writing
     * to a VSAM dataset or an ASCII fixture file. The output buffer is first
     * filled with ASCII spaces ({@code 0x20}) so that shorter String
     * components are automatically right-padded to their COBOL {@code PIC X(n)}
     * length. The FILLER byte array is copied verbatim.
     *
     * <p>The {@code secUsrType} char is downcast to a byte via {@code (byte) c};
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
        writeString(out, secUsrId,    SEC_USR_ID_OFFSET,    SEC_USR_ID_LENGTH);
        writeString(out, secUsrFname, SEC_USR_FNAME_OFFSET, SEC_USR_FNAME_LENGTH);
        writeString(out, secUsrLname, SEC_USR_LNAME_OFFSET, SEC_USR_LNAME_LENGTH);
        writeString(out, secUsrPwd,   SEC_USR_PWD_OFFSET,   SEC_USR_PWD_LENGTH);
        out[SEC_USR_TYPE_OFFSET] = (byte) secUsrType;
        System.arraycopy(secUsrFiller, 0, out, SEC_USR_FILLER_OFFSET, SEC_USR_FILLER_LENGTH);
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
     * Value-based equality. Two {@code SecUserData} instances are equal iff
     * every component is element-wise equal, where the {@link #secUsrFiller()}
     * byte array is compared with {@link Arrays#equals(byte[], byte[])}.
     *
     * <p>The default record-generated {@code equals} would compare
     * {@link #secUsrFiller()} by reference identity (because the runtime
     * does not know that a record component holds a byte array), which would
     * defeat byte-for-byte parity testing in the golden-record harness.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SecUserData that)) {
            return false;
        }
        return secUsrType == that.secUsrType
                && secUsrId.equals(that.secUsrId)
                && secUsrFname.equals(that.secUsrFname)
                && secUsrLname.equals(that.secUsrLname)
                && secUsrPwd.equals(that.secUsrPwd)
                && Arrays.equals(secUsrFiller, that.secUsrFiller);
    }

    /**
     * Hash code consistent with {@link #equals(Object)}; uses
     * {@link Arrays#hashCode(byte[])} for the FILLER byte array to mirror the
     * deep-equality semantics.
     */
    @Override
    public int hashCode() {
        int result = secUsrId.hashCode();
        result = 31 * result + secUsrFname.hashCode();
        result = 31 * result + secUsrLname.hashCode();
        result = 31 * result + secUsrPwd.hashCode();
        result = 31 * result + Character.hashCode(secUsrType);
        result = 31 * result + Arrays.hashCode(secUsrFiller);
        return result;
    }

    /**
     * Returns a debug-friendly string representation of this record with the
     * plaintext password MASKED. This override is critical &mdash; the
     * default record-generated {@code toString} would emit the cleartext
     * password value, which would leak credentials into any sink that
     * consumes {@code Object.toString} (loggers, AssertJ failure messages,
     * IDE debugger displays, exception {@code getMessage()}, etc.).
     *
     * <p>The masking is purely a defense-in-depth measure; it does not
     * alter the stored password value, which remains accessible via
     * {@link #secUsrPwd()} for the COSGN00C credential-comparison path.
     *
     * <p>The FILLER byte array is rendered via {@link Arrays#toString(byte[])}
     * so its contents are visible for fixture debugging, which is harmless
     * because FILLER does not carry credentials.
     */
    @Override
    public String toString() {
        return "SecUserData["
                + "secUsrId=" + secUsrId
                + ", secUsrFname=" + secUsrFname
                + ", secUsrLname=" + secUsrLname
                + ", secUsrPwd=" + PASSWORD_MASK
                + ", secUsrType=" + secUsrType
                + ", secUsrFiller=" + Arrays.toString(secUsrFiller)
                + "]";
    }
}

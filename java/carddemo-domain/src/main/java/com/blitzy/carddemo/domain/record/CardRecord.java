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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable domain record representing the {@code CARD-RECORD} fixed-width
 * structure from the COBOL copybook {@code CVACT02Y} (record length 150
 * bytes). One {@code CardRecord} instance corresponds to one VSAM KSDS
 * record in the {@code CARDDATA} dataset on z/OS, and to one 150-byte line
 * in the migrated file fixture at {@code app/data/ASCII/carddata.txt}.
 *
 * <h2>COBOL source layout (verbatim from {@code app/cpy/CVACT02Y.cpy})</h2>
 * <pre>{@code
 * 01 CARD-RECORD.
 *    05 CARD-NUM             PIC X(16).
 *    05 CARD-ACCT-ID         PIC 9(11).
 *    05 CARD-CVV-CD          PIC 9(03).
 *    05 CARD-EMBOSSED-NAME   PIC X(50).
 *    05 CARD-EXPIRAION-DATE  PIC X(10).
 *    05 CARD-ACTIVE-STATUS   PIC X(01).
 *    05 FILLER               PIC X(59).
 * }</pre>
 *
 * <h2>Java field mapping</h2>
 * <table>
 *   <caption>Byte-offset and type mapping</caption>
 *   <tr><th>COBOL field</th>          <th>PIC</th>    <th>Len</th> <th>Offset</th> <th>Java component</th></tr>
 *   <tr><td>CARD-NUM</td>             <td>X(16)</td>  <td>16</td>  <td>0</td>      <td>{@link #cardNum() cardNum} : String</td></tr>
 *   <tr><td>CARD-ACCT-ID</td>         <td>9(11)</td>  <td>11</td>  <td>16</td>     <td>{@link #cardAcctId() cardAcctId} : long</td></tr>
 *   <tr><td>CARD-CVV-CD</td>          <td>9(03)</td>  <td>3</td>   <td>27</td>     <td>{@link #cardCvvCd() cardCvvCd} : int</td></tr>
 *   <tr><td>CARD-EMBOSSED-NAME</td>   <td>X(50)</td>  <td>50</td>  <td>30</td>     <td>{@link #cardEmbossedName() cardEmbossedName} : String</td></tr>
 *   <tr><td>CARD-EXPIRAION-DATE</td>  <td>X(10)</td>  <td>10</td>  <td>80</td>     <td>{@link #cardExpiraionDate() cardExpiraionDate} : LocalDate</td></tr>
 *   <tr><td>CARD-ACTIVE-STATUS</td>   <td>X(01)</td>  <td>1</td>   <td>90</td>     <td>{@link #cardActiveStatus() cardActiveStatus} : char</td></tr>
 *   <tr><td>FILLER</td>               <td>X(59)</td>  <td>59</td>  <td>91</td>     <td>{@link #filler() filler} : byte[]</td></tr>
 * </table>
 *
 * <h2>{@code CARD-EXPIRAION-DATE} typo preservation (AAP &sect;0.7.1)</h2>
 * The COBOL copybook contains the typographical error {@code EXPIRAION}
 * (missing 'T'). Per the AAP &sect;0.7.1 Refactor Discipline Guidelines
 * (<em>"do not 'fix' it in this refactor"</em>), the Java component name
 * {@link #cardExpiraionDate()} preserves the misspelling verbatim. Do NOT
 * rename to {@code cardExpirationDate}; doing so silently desynchronizes
 * the Java code from the COBOL source.
 *
 * <h2>PAN handling (AAP &sect;0.7.2)</h2>
 * The {@link #cardNum()} component carries the full 16-digit Primary
 * Account Number (PAN). Per AAP &sect;0.7.2, the PAN MUST NOT appear in
 * logs or error messages in plaintext: only the last 4 digits may be
 * visible. The {@link #toString()} override on this record applies that
 * mask automatically (via {@link #maskedPan()}), so accidental logging of
 * a {@code CardRecord} cannot leak the PAN. Application code that needs to
 * log the PAN MUST explicitly call {@link #maskedPan()} or compute its own
 * masked representation; it MUST NOT log {@link #cardNum()} directly.
 *
 * <h2>Byte-for-byte fidelity contract</h2>
 * For every valid 150-byte buffer {@code b}:
 * <pre>{@code
 *   Arrays.equals(b, CardRecord.parse(b).encode()) == true
 * }</pre>
 * This invariant is the formal contract with external file consumers and
 * is asserted by the golden-record harness on every PR. Per AAP &sect;0.3.3
 * any departure from this invariant is a defect.
 *
 * <h2>Encoding rules (per AAP &sect;0.6.5)</h2>
 * <ul>
 *   <li>Charset: {@link StandardCharsets#US_ASCII} (matches the codepage of
 *       the ASCII fixtures under {@code app/data/ASCII/}). EBCDIC
 *       transcoding, when required, is performed by the adapter layer
 *       before the byte buffer reaches {@link #parse(byte[])}.</li>
 *   <li>Numeric fields (PIC 9(n)): zero-padded on the LEFT (e.g.
 *       {@code cardCvvCd=42} encodes as {@code "042"}).</li>
 *   <li>Alphanumeric fields (PIC X(n)): space-padded on the RIGHT.</li>
 *   <li>Date field (CARD-EXPIRAION-DATE): ISO 8601 {@code yyyy-MM-dd}
 *       via {@link DateTimeFormatter#ISO_LOCAL_DATE}.</li>
 * </ul>
 *
 * <h2>Immutability</h2>
 * This record is immutable. The {@code byte[]} {@link #filler()} component
 * is defensively cloned on construction and on every access so that
 * callers cannot mutate the internal array. All other components are
 * either primitive ({@code long}, {@code int}, {@code char}) or already
 * immutable (String, LocalDate).
 *
 * <h2>Consumers</h2>
 * Used by (per AAP &sect;0.4.1):
 * <ul>
 *   <li>{@code CbAct01C}/{@code CbAct02C} — sequential card reader</li>
 *   <li>{@code CoCrdLiC} — online card list</li>
 *   <li>{@code CoCrdSlC} — online card view</li>
 *   <li>{@code CoCrdUpC} — online card update</li>
 *   <li>{@code CbTrn02C} — full posting engine (cross-reference lookup)</li>
 *   <li>{@code FileCardRepository} — file-based adapter</li>
 * </ul>
 *
 * @see com.blitzy.carddemo.domain.annotation.CobolProgram
 * @since 1.0.0
 */
@CobolProgram(
        value = "CVACT02Y",
        sourcePath = "app/cpy/CVACT02Y.cpy",
        notes = "150-byte CARD-RECORD; CARD-EXPIRAION-DATE COBOL typo preserved per AAP §0.7.1"
)
public record CardRecord(
        String cardNum,
        long cardAcctId,
        int cardCvvCd,
        String cardEmbossedName,
        LocalDate cardExpiraionDate,
        char cardActiveStatus,
        byte[] filler) {

    // ------------------------------------------------------------------
    // Layout constants (mandated by file schema — names are part of the
    // public contract; do NOT rename without updating the file schema)
    // ------------------------------------------------------------------

    /** Total CARD-RECORD length in bytes per CVACT02Y. */
    public static final int RECORD_LENGTH = 150;

    /** CARD-NUM (PIC X(16)) byte offset within the record. */
    public static final int CARD_NUM_OFFSET = 0;
    /** CARD-NUM (PIC X(16)) byte length. */
    public static final int CARD_NUM_LENGTH = 16;

    /** CARD-ACCT-ID (PIC 9(11)) byte offset within the record. */
    public static final int CARD_ACCT_ID_OFFSET = 16;
    /** CARD-ACCT-ID (PIC 9(11)) byte length. */
    public static final int CARD_ACCT_ID_LENGTH = 11;

    /** CARD-CVV-CD (PIC 9(03)) byte offset within the record. */
    public static final int CARD_CVV_CD_OFFSET = 27;
    /** CARD-CVV-CD (PIC 9(03)) byte length. */
    public static final int CARD_CVV_CD_LENGTH = 3;

    /** CARD-EMBOSSED-NAME (PIC X(50)) byte offset within the record. */
    public static final int CARD_EMBOSSED_NAME_OFFSET = 30;
    /** CARD-EMBOSSED-NAME (PIC X(50)) byte length. */
    public static final int CARD_EMBOSSED_NAME_LENGTH = 50;

    /** CARD-EXPIRAION-DATE (PIC X(10)) byte offset within the record. */
    public static final int CARD_EXPIRAION_DATE_OFFSET = 80;
    /** CARD-EXPIRAION-DATE (PIC X(10)) byte length. */
    public static final int CARD_EXPIRAION_DATE_LENGTH = 10;

    /** CARD-ACTIVE-STATUS (PIC X(01)) byte offset within the record. */
    public static final int CARD_ACTIVE_STATUS_OFFSET = 90;
    /** CARD-ACTIVE-STATUS (PIC X(01)) byte length. */
    public static final int CARD_ACTIVE_STATUS_LENGTH = 1;

    /** FILLER (PIC X(59)) byte offset within the record. */
    public static final int FILLER_OFFSET = 91;
    /** FILLER (PIC X(59)) byte length. */
    public static final int FILLER_LENGTH = 59;

    /**
     * Inclusive upper bound for {@link #cardAcctId()} so that a value fits
     * in the {@value #CARD_ACCT_ID_LENGTH}-digit CARD-ACCT-ID field
     * (PIC 9(11)). 10^11 - 1 = 99_999_999_999. Values above this cannot
     * be encoded into the 11-byte slot and would corrupt the 150-byte
     * record layout in {@link #encode()}; the canonical constructor
     * rejects them at construction time.
     */
    public static final long CARD_ACCT_ID_MAX = 99_999_999_999L;

    /**
     * Inclusive upper bound for {@link #cardCvvCd()} so that a value fits
     * in the {@value #CARD_CVV_CD_LENGTH}-digit CARD-CVV-CD field
     * (PIC 9(03)). 10^3 - 1 = 999.
     */
    public static final int CARD_CVV_CD_MAX = 999;

    /**
     * ISO 8601 date formatter used to parse / format the
     * {@link #cardExpiraionDate()} component. Matches the
     * {@code yyyy-MM-dd} encoding of {@code app/data/ASCII/carddata.txt}.
     */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** ASCII space byte (0x20) used for blank padding. */
    private static final byte SPACE = (byte) 0x20;

    // ------------------------------------------------------------------
    // Compact canonical constructor (JEP 513 Flexible Constructor Bodies
    // — validation logic runs before the implicit field assignments)
    // ------------------------------------------------------------------

    /**
     * Compact canonical constructor. Validates every component against
     * its COBOL PIC clause and defensively clones the mutable
     * {@code byte[]} filler. Uses JEP 513 Flexible Constructor Bodies to
     * run validation logic before the implicit field assignments.
     *
     * @throws NullPointerException     if any reference component is {@code null}
     * @throws IllegalArgumentException if any component exceeds its COBOL
     *                                  PIC range or length
     */
    public CardRecord {
        Objects.requireNonNull(cardNum, "cardNum");
        Objects.requireNonNull(cardEmbossedName, "cardEmbossedName");
        Objects.requireNonNull(cardExpiraionDate, "cardExpiraionDate");
        Objects.requireNonNull(filler, "filler");

        if (cardNum.length() > CARD_NUM_LENGTH) {
            throw new IllegalArgumentException(
                    "cardNum exceeds " + CARD_NUM_LENGTH + " chars, got " + cardNum.length());
        }
        if (cardEmbossedName.length() > CARD_EMBOSSED_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "cardEmbossedName exceeds " + CARD_EMBOSSED_NAME_LENGTH
                            + " chars, got " + cardEmbossedName.length());
        }
        if (cardAcctId < 0L) {
            throw new IllegalArgumentException(
                    "cardAcctId must be non-negative, got " + cardAcctId);
        }
        if (cardAcctId > CARD_ACCT_ID_MAX) {
            throw new IllegalArgumentException(
                    "cardAcctId exceeds " + CARD_ACCT_ID_LENGTH
                            + "-digit range (max " + CARD_ACCT_ID_MAX + "), got " + cardAcctId);
        }
        if (cardCvvCd < 0 || cardCvvCd > CARD_CVV_CD_MAX) {
            throw new IllegalArgumentException(
                    "cardCvvCd must be 0.." + CARD_CVV_CD_MAX + ", got " + cardCvvCd);
        }
        if (filler.length != FILLER_LENGTH) {
            throw new IllegalArgumentException(
                    "filler must be exactly " + FILLER_LENGTH + " bytes, got " + filler.length);
        }

        // Defensive copy: byte[] is mutable and the canonical parameter is
        // user-supplied. Subsequent mutation of the caller's reference
        // must not affect this record's state.
        filler = filler.clone();
    }

    // ------------------------------------------------------------------
    // Defensive accessor override for the mutable byte[] component
    // ------------------------------------------------------------------

    /**
     * Defensive accessor for the {@value #FILLER_LENGTH}-byte FILLER
     * component. Returns a freshly cloned array so that callers cannot
     * mutate this record's internal state.
     *
     * @return a clone of the FILLER bytes; never {@code null}; always
     *         exactly {@value #FILLER_LENGTH} bytes
     */
    @Override
    public byte[] filler() {
        return filler.clone();
    }

    // ------------------------------------------------------------------
    // Byte-level contract: parse(byte[]) and encode()
    // ------------------------------------------------------------------

    /**
     * Decodes a {@value #RECORD_LENGTH}-byte fixed-width buffer into a
     * new {@link CardRecord}. The buffer is interpreted under the
     * {@link StandardCharsets#US_ASCII US_ASCII} codepage; callers MUST
     * transcode EBCDIC input upstream of this call.
     *
     * <p>Field decoding rules:
     * <ul>
     *   <li>PIC X(n) → {@code String} (no trim; spaces are preserved)</li>
     *   <li>PIC 9(n) → {@code long} or {@code int} via decimal parse</li>
     *   <li>CARD-EXPIRAION-DATE → {@link LocalDate} via ISO_LOCAL_DATE</li>
     *   <li>CARD-ACTIVE-STATUS → single {@code char} from the byte value</li>
     *   <li>FILLER → 59-byte slice ({@link Arrays#copyOfRange})</li>
     * </ul>
     *
     * @param buffer the raw 150-byte record from the underlying file or
     *               cross-platform input source; not {@code null}; must be
     *               exactly {@value #RECORD_LENGTH} bytes
     * @return a fully validated, immutable {@code CardRecord}
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code buffer} is not exactly
     *                                  {@value #RECORD_LENGTH} bytes, or
     *                                  if any numeric field contains
     *                                  non-digit characters, or if the
     *                                  CARD-EXPIRAION-DATE field cannot be
     *                                  parsed as ISO yyyy-MM-dd
     */
    public static CardRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "CardRecord buffer must be exactly " + RECORD_LENGTH
                            + " bytes; got " + buffer.length);
        }

        String cardNum = new String(buffer, CARD_NUM_OFFSET, CARD_NUM_LENGTH, StandardCharsets.US_ASCII);

        String acctIdStr = new String(
                buffer, CARD_ACCT_ID_OFFSET, CARD_ACCT_ID_LENGTH, StandardCharsets.US_ASCII);
        long cardAcctId;
        try {
            cardAcctId = Long.parseLong(acctIdStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "CARD-ACCT-ID is not a valid " + CARD_ACCT_ID_LENGTH
                            + "-digit number: '" + acctIdStr + "'", e);
        }

        String cvvStr = new String(
                buffer, CARD_CVV_CD_OFFSET, CARD_CVV_CD_LENGTH, StandardCharsets.US_ASCII);
        int cardCvvCd;
        try {
            cardCvvCd = Integer.parseInt(cvvStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "CARD-CVV-CD is not a valid " + CARD_CVV_CD_LENGTH
                            + "-digit number: '" + cvvStr + "'", e);
        }

        String cardEmbossedName = new String(
                buffer, CARD_EMBOSSED_NAME_OFFSET, CARD_EMBOSSED_NAME_LENGTH, StandardCharsets.US_ASCII);

        String dateStr = new String(
                buffer, CARD_EXPIRAION_DATE_OFFSET, CARD_EXPIRAION_DATE_LENGTH, StandardCharsets.US_ASCII);
        LocalDate cardExpiraionDate;
        try {
            cardExpiraionDate = LocalDate.parse(dateStr, DATE_FMT);
        } catch (java.time.format.DateTimeParseException e) {
            // Fully-qualified — the import whitelist for this file is
            // {DateTimeFormatter} only; DateTimeParseException is named
            // here without an import statement.
            throw new IllegalArgumentException(
                    "CARD-EXPIRAION-DATE is not a valid ISO yyyy-MM-dd date: '"
                            + dateStr + "'", e);
        }

        char cardActiveStatus = (char) (buffer[CARD_ACTIVE_STATUS_OFFSET] & 0xFF);

        byte[] fillerBytes = Arrays.copyOfRange(
                buffer, FILLER_OFFSET, FILLER_OFFSET + FILLER_LENGTH);

        return new CardRecord(
                cardNum,
                cardAcctId,
                cardCvvCd,
                cardEmbossedName,
                cardExpiraionDate,
                cardActiveStatus,
                fillerBytes);
    }

    /**
     * Encodes this {@link CardRecord} as a new {@value #RECORD_LENGTH}-byte
     * buffer ready to be written to the underlying file or cross-platform
     * output target. The output is in the {@link StandardCharsets#US_ASCII
     * US_ASCII} codepage; callers MUST transcode to EBCDIC downstream of
     * this call if the target system requires it.
     *
     * <p>Field encoding rules:
     * <ul>
     *   <li>PIC X(n) → ASCII bytes, space-padded on the RIGHT</li>
     *   <li>PIC 9(n) → ASCII digits, zero-padded on the LEFT</li>
     *   <li>CARD-EXPIRAION-DATE → ISO {@code yyyy-MM-dd}</li>
     *   <li>CARD-ACTIVE-STATUS → single byte (cast from {@code char})</li>
     *   <li>FILLER → 59-byte slice copied verbatim from the record</li>
     * </ul>
     *
     * <p>The returned buffer is freshly allocated and may be freely
     * mutated by the caller without affecting this record's state.
     *
     * <p>The {@code parse(buf).encode()} round-trip is byte-for-byte
     * exact for every valid 150-byte input buffer; see AAP &sect;0.3.3.
     *
     * @return a new {@value #RECORD_LENGTH}-byte buffer encoding this
     *         record
     */
    public byte[] encode() {
        byte[] out = new byte[RECORD_LENGTH];
        // Pre-fill with ASCII spaces so any sub-length string component
        // automatically right-pads to its declared width.
        Arrays.fill(out, SPACE);

        // CARD-NUM (PIC X(16)) — ASCII, space-padded right (via pre-fill).
        writeAscii(out, CARD_NUM_OFFSET, CARD_NUM_LENGTH, cardNum);

        // CARD-ACCT-ID (PIC 9(11)) — ASCII digits, zero-padded left.
        writeZeroPaddedLong(out, CARD_ACCT_ID_OFFSET, CARD_ACCT_ID_LENGTH, cardAcctId);

        // CARD-CVV-CD (PIC 9(03)) — ASCII digits, zero-padded left.
        writeZeroPaddedLong(out, CARD_CVV_CD_OFFSET, CARD_CVV_CD_LENGTH, cardCvvCd);

        // CARD-EMBOSSED-NAME (PIC X(50)) — ASCII, space-padded right.
        writeAscii(out, CARD_EMBOSSED_NAME_OFFSET, CARD_EMBOSSED_NAME_LENGTH, cardEmbossedName);

        // CARD-EXPIRAION-DATE (PIC X(10)) — ISO yyyy-MM-dd, no padding
        // required because the formatter always produces exactly 10 chars.
        byte[] dateBytes = cardExpiraionDate.format(DATE_FMT).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(dateBytes, 0, out,
                CARD_EXPIRAION_DATE_OFFSET, CARD_EXPIRAION_DATE_LENGTH);

        // CARD-ACTIVE-STATUS (PIC X(01)) — single byte cast from char.
        out[CARD_ACTIVE_STATUS_OFFSET] = (byte) cardActiveStatus;

        // FILLER (PIC X(59)) — verbatim 59-byte copy from the (already
        // cloned and validated) field.
        System.arraycopy(filler, 0, out, FILLER_OFFSET, FILLER_LENGTH);

        return out;
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Writes an ASCII string into the buffer at the given offset, copying
     * at most {@code length} bytes. Bytes beyond the string's encoded
     * length are left untouched (the caller is expected to have pre-filled
     * the slot with spaces).
     *
     * @param out    the destination buffer
     * @param offset the starting offset in {@code out}
     * @param length the maximum number of bytes to write
     * @param value  the string to encode; must not be {@code null}
     */
    private static void writeAscii(byte[] out, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int copyLen = Math.min(bytes.length, length);
        System.arraycopy(bytes, 0, out, offset, copyLen);
    }

    /**
     * Writes a non-negative {@code long} into the buffer as
     * {@code length} ASCII digits, zero-padded on the LEFT.
     *
     * <p>The caller's compact-constructor validation guarantees the value
     * fits in {@code length} digits; this method does not re-check.
     *
     * @param out    the destination buffer
     * @param offset the starting offset in {@code out}
     * @param length the number of digit bytes to write
     * @param value  the non-negative value to encode
     */
    private static void writeZeroPaddedLong(byte[] out, int offset, int length, long value) {
        long remaining = value;
        for (int i = length - 1; i >= 0; i--) {
            out[offset + i] = (byte) ('0' + (int) (remaining % 10L));
            remaining /= 10L;
        }
    }

    // ------------------------------------------------------------------
    // equals / hashCode — overridden because the default record contract
    // uses reference equality on the byte[] filler component, which would
    // make logically equal records compare unequal.
    // ------------------------------------------------------------------

    /**
     * Value-based equality. Two {@code CardRecord} instances are equal if
     * and only if all seven components are equal. The {@code byte[]}
     * filler is compared element-wise via {@link Arrays#equals(byte[], byte[])}.
     *
     * @param o the other object
     * @return {@code true} if {@code o} is a {@code CardRecord} with
     *         element-wise equal components
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CardRecord that)) {
            return false;
        }
        return cardAcctId == that.cardAcctId
                && cardCvvCd == that.cardCvvCd
                && cardActiveStatus == that.cardActiveStatus
                && cardNum.equals(that.cardNum)
                && cardEmbossedName.equals(that.cardEmbossedName)
                && cardExpiraionDate.equals(that.cardExpiraionDate)
                && Arrays.equals(filler, that.filler);
    }

    /**
     * Hash code consistent with {@link #equals(Object)}: includes a
     * content-based hash for the {@code byte[]} filler via
     * {@link Arrays#hashCode(byte[])}.
     *
     * @return a hash code combining all seven components
     */
    @Override
    public int hashCode() {
        int result = cardNum.hashCode();
        result = 31 * result + Long.hashCode(cardAcctId);
        result = 31 * result + Integer.hashCode(cardCvvCd);
        result = 31 * result + cardEmbossedName.hashCode();
        result = 31 * result + cardExpiraionDate.hashCode();
        result = 31 * result + Character.hashCode(cardActiveStatus);
        result = 31 * result + Arrays.hashCode(filler);
        return result;
    }

    // ------------------------------------------------------------------
    // PAN masking and PAN-safe toString override (AAP §0.7.2)
    // ------------------------------------------------------------------

    /**
     * Returns the PAN ({@link #cardNum()}) masked so that all but the last
     * 4 digits are replaced with {@code '*'}. Leading/trailing whitespace
     * is trimmed before masking.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "4111111111111234" → "************1234"}</li>
     *   <li>{@code "1234"             → "1234"} (already &le; 4 digits)</li>
     *   <li>{@code "  "               → ""}</li>
     * </ul>
     *
     * <p>Use this method whenever the cleartext PAN must be referenced in
     * logs, error messages, or debug output. Per AAP &sect;0.7.2 the
     * cleartext PAN MUST NOT appear in any log line.
     *
     * @return the PAN with all but the last 4 digits replaced by
     *         {@code '*'}; never {@code null}
     */
    public String maskedPan() {
        String trimmed = cardNum.trim();
        if (trimmed.length() <= 4) {
            return trimmed;
        }
        int prefixLen = trimmed.length() - 4;
        return "*".repeat(prefixLen) + trimmed.substring(prefixLen);
    }

    /**
     * String representation that masks the PAN and CVV. This override is
     * REQUIRED so that an accidental {@code log.info("{}", cardRecord)}
     * does NOT leak the cleartext PAN or CVV. Per AAP &sect;0.7.2 the
     * cleartext PAN MUST NOT appear in any log line.
     *
     * <p>Format:
     * <pre>{@code
     *   CardRecord[cardNum=************1234, cardAcctId=00000000050,
     *              cardCvvCd=***, cardEmbossedName=Aniya Von,
     *              cardExpiraionDate=2023-03-09, cardActiveStatus=Y,
     *              filler=<59 bytes>]
     * }</pre>
     *
     * @return a PAN-safe and CVV-safe diagnostic string
     */
    @Override
    public String toString() {
        return "CardRecord[cardNum=" + maskedPan()
                + ", cardAcctId=" + cardAcctId
                + ", cardCvvCd=***"
                + ", cardEmbossedName=" + cardEmbossedName.trim()
                + ", cardExpiraionDate=" + cardExpiraionDate
                + ", cardActiveStatus=" + cardActiveStatus
                + ", filler=<" + FILLER_LENGTH + " bytes>]";
    }

    // ------------------------------------------------------------------
    // Convenience factory for callers that need an empty FILLER
    // ------------------------------------------------------------------

    /**
     * Returns a freshly allocated {@value #FILLER_LENGTH}-byte array
     * filled with ASCII spaces. Convenient for callers constructing a
     * {@code CardRecord} from non-file sources (e.g. unit tests or
     * application-layer code that synthesizes a new card) when the FILLER
     * has no semantic content.
     *
     * @return a new {@value #FILLER_LENGTH}-byte all-spaces array
     */
    public static byte[] emptyFiller() {
        byte[] f = new byte[FILLER_LENGTH];
        Arrays.fill(f, SPACE);
        return f;
    }
}

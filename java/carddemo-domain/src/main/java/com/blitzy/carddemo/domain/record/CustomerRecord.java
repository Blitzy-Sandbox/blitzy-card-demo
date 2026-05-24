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
 * Domain record translated from the COBOL {@code CUSTOMER-RECORD} copybook at
 * {@code app/cpy/CVCUS01Y.cpy} (record length 500 bytes).
 *
 * <p>The COBOL layout is:
 *
 * <pre>{@code
 * 01 CUSTOMER-RECORD.
 *    05 CUST-ID                       PIC 9(09).   (9 bytes — offset 0..8)
 *    05 CUST-FIRST-NAME               PIC X(25).   (25 bytes — offset 9..33)
 *    05 CUST-MIDDLE-NAME              PIC X(25).   (25 bytes — offset 34..58)
 *    05 CUST-LAST-NAME                PIC X(25).   (25 bytes — offset 59..83)
 *    05 CUST-ADDR-LINE-1              PIC X(50).   (50 bytes — offset 84..133)
 *    05 CUST-ADDR-LINE-2              PIC X(50).   (50 bytes — offset 134..183)
 *    05 CUST-ADDR-LINE-3              PIC X(50).   (50 bytes — offset 184..233)
 *    05 CUST-ADDR-STATE-CD            PIC X(02).   (2 bytes — offset 234..235)
 *    05 CUST-ADDR-COUNTRY-CD          PIC X(03).   (3 bytes — offset 236..238)
 *    05 CUST-ADDR-ZIP                 PIC X(10).   (10 bytes — offset 239..248)
 *    05 CUST-PHONE-NUM-1              PIC X(15).   (15 bytes — offset 249..263)
 *    05 CUST-PHONE-NUM-2              PIC X(15).   (15 bytes — offset 264..278)
 *    05 CUST-SSN                      PIC 9(09).   (9 bytes — offset 279..287)
 *    05 CUST-GOVT-ISSUED-ID           PIC X(20).   (20 bytes — offset 288..307)
 *    05 CUST-DOB-YYYY-MM-DD           PIC X(10).   (10 bytes — offset 308..317)
 *    05 CUST-EFT-ACCOUNT-ID           PIC X(10).   (10 bytes — offset 318..327)
 *    05 CUST-PRI-CARD-HOLDER-IND      PIC X(01).   (1 byte — offset 328)
 *    05 CUST-FICO-CREDIT-SCORE        PIC 9(03).   (3 bytes — offset 329..331)
 *    05 FILLER                        PIC X(168).  (168 bytes — offset 332..499)
 *    Total: 500 bytes
 * }</pre>
 *
 * <h2>{@code CUST-DOB-YYYY-MM-DD} as {@link LocalDate}</h2>
 * Per AAP &sect;0.6.4 (Date Semantics), every COBOL date {@code PIC X(10)}
 * in {@code YYYY-MM-DD} ISO format is translated to {@link LocalDate};
 * {@link java.util.Date} and {@link java.util.Calendar} are explicitly
 * forbidden in new code. The field name {@code CUST-DOB-YYYY-MM-DD}
 * explicitly indicates ISO format (with dashes) and the 10-byte width
 * matches the {@code "yyyy-MM-dd"} pattern exactly.
 *
 * <p>This is the key SEMANTIC difference from {@link CustomerLegacyRecord}
 * (translated from {@code app/cpy/CUSTREC.cpy}): the BYTE layout and the
 * record length are byte-identical, but the legacy copybook names the field
 * {@code CUST-DOB-YYYYMMDD} (no dashes); per AAP &sect;0.4.1 both records
 * are retained because the on-disk fixtures actually store the dashed form
 * in both cases.
 *
 * <h2>Byte fidelity ({@code parse} &harr; {@code encode})</h2>
 * Per AAP &sect;0.6.5 the {@link #parse(byte[])} factory and the
 * {@link #encode()} method are inverses for every well-formed 500-byte
 * buffer: {@code parse(b).encode()} equals {@code b} byte-for-byte.
 * Numeric fields ({@code CUST-ID}, {@code CUST-SSN}, {@code CUST-FICO-CREDIT-SCORE})
 * are left-zero-padded ASCII digits per COBOL {@code PIC 9(n)}; alphanumeric
 * fields ({@code PIC X(n)}) are right-space-padded ASCII per COBOL
 * {@code DISPLAY} convention; the 168-byte trailing {@code FILLER} is
 * preserved verbatim as a defensive byte-array copy.
 *
 * <h2>SSN and PII handling in {@link #toString()} (AAP &sect;0.7.2)</h2>
 * The SSN is stored verbatim as a 9-digit unsigned {@code long} so that
 * byte-for-byte fidelity is preserved at the {@link #parse(byte[])} /
 * {@link #encode()} contract. However, the auto-generated record
 * {@link #toString()} is <strong>overridden</strong> to redact the SSN,
 * government-issued ID, date of birth, EFT account id, and address
 * components so that an accidental {@code log.info("{}", customerRecord)}
 * cannot leak Personally Identifiable Information (PII). The name
 * components are still rendered (trimmed) since the customer name is
 * not considered confidential in the COBOL design, but the more
 * sensitive identifiers are replaced with non-reversible masks.
 *
 * <p>Callers that need to render the record for legitimate non-logging
 * purposes (reports, audit trails, screens) must access the raw
 * components ({@link #custSsn()}, {@link #custGovtIssuedId()}, etc.)
 * directly and apply context-appropriate masking at the rendering
 * boundary.
 *
 * <h2>Immutability</h2>
 * Java {@code record} components are implicitly final and assigned exactly
 * once by the canonical constructor. The byte-array {@link #filler()}
 * component is defensively cloned at construction (see compact canonical
 * constructor) and on access (see overridden accessor) so that callers
 * cannot mutate the internal state of this record.
 *
 * @see CustomerLegacyRecord
 */
@CobolProgram(
        value = "CVCUS01Y",
        sourcePath = "app/cpy/CVCUS01Y.cpy",
        notes = "500-byte CUSTOMER-RECORD; CUST-DOB-YYYY-MM-DD parsed as LocalDate per AAP §0.6.4; "
                + "sensitive PII (SSN, govt-issued id, DOB, EFT account, addresses) redacted in "
                + "toString() per AAP §0.7.2"
)
public record CustomerRecord(
        long custId,
        String custFirstName,
        String custMiddleName,
        String custLastName,
        String custAddrLine1,
        String custAddrLine2,
        String custAddrLine3,
        String custAddrStateCd,
        String custAddrCountryCd,
        String custAddrZip,
        String custPhoneNum1,
        String custPhoneNum2,
        long custSsn,
        String custGovtIssuedId,
        LocalDate custDobYyyyMmDd,
        String custEftAccountId,
        char custPriCardHolderInd,
        int custFicoCreditScore,
        byte[] filler) {

    // ========================================================================
    // Layout constants — derived directly from app/cpy/CVCUS01Y.cpy.
    // RECORD_LENGTH and each CUST_*_OFFSET / CUST_*_LENGTH constant are public
    // so that adapter code (carddemo-adapter-file readers/writers, the
    // golden-record harness, and any external integration) can introspect the
    // field positions without hard-coding magic numbers. The trailing FILLER
    // is named simply {@code FILLER_OFFSET} / {@code FILLER_LENGTH} to mirror
    // the {@code FILLER} clause in the copybook (no field-name prefix).
    // ========================================================================

    /** Total record length in bytes per copybook. */
    public static final int RECORD_LENGTH = 500;

    /** Offset of {@code CUST-ID} (PIC 9(09)). */
    public static final int CUST_ID_OFFSET = 0;
    /** Length of {@code CUST-ID}. */
    public static final int CUST_ID_LENGTH = 9;

    /** Offset of {@code CUST-FIRST-NAME} (PIC X(25)). */
    public static final int CUST_FIRST_NAME_OFFSET = 9;
    /** Length of {@code CUST-FIRST-NAME}. */
    public static final int CUST_FIRST_NAME_LENGTH = 25;

    /** Offset of {@code CUST-MIDDLE-NAME} (PIC X(25)). */
    public static final int CUST_MIDDLE_NAME_OFFSET = 34;
    /** Length of {@code CUST-MIDDLE-NAME}. */
    public static final int CUST_MIDDLE_NAME_LENGTH = 25;

    /** Offset of {@code CUST-LAST-NAME} (PIC X(25)). */
    public static final int CUST_LAST_NAME_OFFSET = 59;
    /** Length of {@code CUST-LAST-NAME}. */
    public static final int CUST_LAST_NAME_LENGTH = 25;

    /** Offset of {@code CUST-ADDR-LINE-1} (PIC X(50)). */
    public static final int CUST_ADDR_LINE_1_OFFSET = 84;
    /** Length of {@code CUST-ADDR-LINE-1}. */
    public static final int CUST_ADDR_LINE_1_LENGTH = 50;

    /** Offset of {@code CUST-ADDR-LINE-2} (PIC X(50)). */
    public static final int CUST_ADDR_LINE_2_OFFSET = 134;
    /** Length of {@code CUST-ADDR-LINE-2}. */
    public static final int CUST_ADDR_LINE_2_LENGTH = 50;

    /** Offset of {@code CUST-ADDR-LINE-3} (PIC X(50)). */
    public static final int CUST_ADDR_LINE_3_OFFSET = 184;
    /** Length of {@code CUST-ADDR-LINE-3}. */
    public static final int CUST_ADDR_LINE_3_LENGTH = 50;

    /** Offset of {@code CUST-ADDR-STATE-CD} (PIC X(02)). */
    public static final int CUST_ADDR_STATE_CD_OFFSET = 234;
    /** Length of {@code CUST-ADDR-STATE-CD}. */
    public static final int CUST_ADDR_STATE_CD_LENGTH = 2;

    /** Offset of {@code CUST-ADDR-COUNTRY-CD} (PIC X(03)). */
    public static final int CUST_ADDR_COUNTRY_CD_OFFSET = 236;
    /** Length of {@code CUST-ADDR-COUNTRY-CD}. */
    public static final int CUST_ADDR_COUNTRY_CD_LENGTH = 3;

    /** Offset of {@code CUST-ADDR-ZIP} (PIC X(10)). */
    public static final int CUST_ADDR_ZIP_OFFSET = 239;
    /** Length of {@code CUST-ADDR-ZIP}. */
    public static final int CUST_ADDR_ZIP_LENGTH = 10;

    /** Offset of {@code CUST-PHONE-NUM-1} (PIC X(15)). */
    public static final int CUST_PHONE_NUM_1_OFFSET = 249;
    /** Length of {@code CUST-PHONE-NUM-1}. */
    public static final int CUST_PHONE_NUM_1_LENGTH = 15;

    /** Offset of {@code CUST-PHONE-NUM-2} (PIC X(15)). */
    public static final int CUST_PHONE_NUM_2_OFFSET = 264;
    /** Length of {@code CUST-PHONE-NUM-2}. */
    public static final int CUST_PHONE_NUM_2_LENGTH = 15;

    /** Offset of {@code CUST-SSN} (PIC 9(09)). */
    public static final int CUST_SSN_OFFSET = 279;
    /** Length of {@code CUST-SSN}. */
    public static final int CUST_SSN_LENGTH = 9;

    /** Offset of {@code CUST-GOVT-ISSUED-ID} (PIC X(20)). */
    public static final int CUST_GOVT_ISSUED_ID_OFFSET = 288;
    /** Length of {@code CUST-GOVT-ISSUED-ID}. */
    public static final int CUST_GOVT_ISSUED_ID_LENGTH = 20;

    /** Offset of {@code CUST-DOB-YYYY-MM-DD} (PIC X(10)). */
    public static final int CUST_DOB_YYYY_MM_DD_OFFSET = 308;
    /** Length of {@code CUST-DOB-YYYY-MM-DD}. */
    public static final int CUST_DOB_YYYY_MM_DD_LENGTH = 10;

    /** Offset of {@code CUST-EFT-ACCOUNT-ID} (PIC X(10)). */
    public static final int CUST_EFT_ACCOUNT_ID_OFFSET = 318;
    /** Length of {@code CUST-EFT-ACCOUNT-ID}. */
    public static final int CUST_EFT_ACCOUNT_ID_LENGTH = 10;

    /** Offset of {@code CUST-PRI-CARD-HOLDER-IND} (PIC X(01)). */
    public static final int CUST_PRI_CARD_HOLDER_IND_OFFSET = 328;
    /** Length of {@code CUST-PRI-CARD-HOLDER-IND}. */
    public static final int CUST_PRI_CARD_HOLDER_IND_LENGTH = 1;

    /** Offset of {@code CUST-FICO-CREDIT-SCORE} (PIC 9(03)). */
    public static final int CUST_FICO_CREDIT_SCORE_OFFSET = 329;
    /** Length of {@code CUST-FICO-CREDIT-SCORE}. */
    public static final int CUST_FICO_CREDIT_SCORE_LENGTH = 3;

    /** Offset of trailing {@code FILLER}. */
    public static final int FILLER_OFFSET = 332;
    /** Length of trailing {@code FILLER} (PIC X(168)). */
    public static final int FILLER_LENGTH = 168;

    /**
     * ISO-8601 date formatter for the {@code CUST-DOB-YYYY-MM-DD} field.
     * Uses {@link DateTimeFormatter#ISO_LOCAL_DATE} so the on-disk
     * representation is the canonical {@code "yyyy-MM-dd"} 10-byte form.
     * Package-private so unit tests can verify formatter identity.
     */
    static final DateTimeFormatter DOB_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    // ========================================================================
    // Compact canonical constructor (JEP 513 Flexible Constructor Bodies).
    // Validates required invariants before assigning fields:
    //   * All non-primitive components must be non-null
    //   * Unsigned numeric components must be non-negative
    //   * FICO score must lie in 0..999 (PIC 9(03) value space)
    //   * Variable-length string components must fit their declared width
    //   * FILLER must be exactly FILLER_LENGTH bytes; it is then cloned to
    //     preserve record immutability against subsequent caller mutation
    // ========================================================================

    /**
     * Compact canonical constructor — validates and normalizes inputs.
     *
     * @throws NullPointerException if any non-primitive component is null
     * @throws IllegalArgumentException if {@code custId} or {@code custSsn} is
     *         negative, if {@code custFicoCreditScore} is outside {@code 0..999},
     *         if any string exceeds its declared maximum width, or if
     *         {@code filler.length != FILLER_LENGTH}
     */
    public CustomerRecord {
        Objects.requireNonNull(custFirstName, "custFirstName");
        Objects.requireNonNull(custMiddleName, "custMiddleName");
        Objects.requireNonNull(custLastName, "custLastName");
        Objects.requireNonNull(custAddrLine1, "custAddrLine1");
        Objects.requireNonNull(custAddrLine2, "custAddrLine2");
        Objects.requireNonNull(custAddrLine3, "custAddrLine3");
        Objects.requireNonNull(custAddrStateCd, "custAddrStateCd");
        Objects.requireNonNull(custAddrCountryCd, "custAddrCountryCd");
        Objects.requireNonNull(custAddrZip, "custAddrZip");
        Objects.requireNonNull(custPhoneNum1, "custPhoneNum1");
        Objects.requireNonNull(custPhoneNum2, "custPhoneNum2");
        Objects.requireNonNull(custGovtIssuedId, "custGovtIssuedId");
        Objects.requireNonNull(custDobYyyyMmDd, "custDobYyyyMmDd");
        Objects.requireNonNull(custEftAccountId, "custEftAccountId");
        Objects.requireNonNull(filler, "filler");

        if (custId < 0L) {
            throw new IllegalArgumentException("custId must be non-negative, got " + custId);
        }
        if (custSsn < 0L) {
            throw new IllegalArgumentException("custSsn must be non-negative, got " + custSsn);
        }
        if (custFicoCreditScore < 0 || custFicoCreditScore > 999) {
            throw new IllegalArgumentException(
                    "custFicoCreditScore must be 0..999, got " + custFicoCreditScore);
        }

        if (custFirstName.length() > CUST_FIRST_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "custFirstName exceeds " + CUST_FIRST_NAME_LENGTH + " chars: " + custFirstName.length());
        }
        if (custMiddleName.length() > CUST_MIDDLE_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "custMiddleName exceeds " + CUST_MIDDLE_NAME_LENGTH + " chars: " + custMiddleName.length());
        }
        if (custLastName.length() > CUST_LAST_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "custLastName exceeds " + CUST_LAST_NAME_LENGTH + " chars: " + custLastName.length());
        }
        if (custAddrLine1.length() > CUST_ADDR_LINE_1_LENGTH) {
            throw new IllegalArgumentException(
                    "custAddrLine1 exceeds " + CUST_ADDR_LINE_1_LENGTH + " chars: " + custAddrLine1.length());
        }
        if (custAddrLine2.length() > CUST_ADDR_LINE_2_LENGTH) {
            throw new IllegalArgumentException(
                    "custAddrLine2 exceeds " + CUST_ADDR_LINE_2_LENGTH + " chars: " + custAddrLine2.length());
        }
        if (custAddrLine3.length() > CUST_ADDR_LINE_3_LENGTH) {
            throw new IllegalArgumentException(
                    "custAddrLine3 exceeds " + CUST_ADDR_LINE_3_LENGTH + " chars: " + custAddrLine3.length());
        }
        if (custAddrStateCd.length() > CUST_ADDR_STATE_CD_LENGTH) {
            throw new IllegalArgumentException(
                    "custAddrStateCd exceeds " + CUST_ADDR_STATE_CD_LENGTH + " chars: " + custAddrStateCd.length());
        }
        if (custAddrCountryCd.length() > CUST_ADDR_COUNTRY_CD_LENGTH) {
            throw new IllegalArgumentException(
                    "custAddrCountryCd exceeds " + CUST_ADDR_COUNTRY_CD_LENGTH + " chars: " + custAddrCountryCd.length());
        }
        if (custAddrZip.length() > CUST_ADDR_ZIP_LENGTH) {
            throw new IllegalArgumentException(
                    "custAddrZip exceeds " + CUST_ADDR_ZIP_LENGTH + " chars: " + custAddrZip.length());
        }
        if (custPhoneNum1.length() > CUST_PHONE_NUM_1_LENGTH) {
            throw new IllegalArgumentException(
                    "custPhoneNum1 exceeds " + CUST_PHONE_NUM_1_LENGTH + " chars: " + custPhoneNum1.length());
        }
        if (custPhoneNum2.length() > CUST_PHONE_NUM_2_LENGTH) {
            throw new IllegalArgumentException(
                    "custPhoneNum2 exceeds " + CUST_PHONE_NUM_2_LENGTH + " chars: " + custPhoneNum2.length());
        }
        if (custGovtIssuedId.length() > CUST_GOVT_ISSUED_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "custGovtIssuedId exceeds " + CUST_GOVT_ISSUED_ID_LENGTH + " chars: " + custGovtIssuedId.length());
        }
        if (custEftAccountId.length() > CUST_EFT_ACCOUNT_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "custEftAccountId exceeds " + CUST_EFT_ACCOUNT_ID_LENGTH + " chars: " + custEftAccountId.length());
        }
        if (filler.length != FILLER_LENGTH) {
            throw new IllegalArgumentException(
                    "filler must be exactly " + FILLER_LENGTH + " bytes, got " + filler.length);
        }

        // Defensive copy of mutable byte[] component to preserve record immutability.
        filler = filler.clone();
    }

    // ========================================================================
    // Defensive accessor override for the byte[] filler component.
    // Java records expose accessors that return the underlying component
    // reference; for mutable component types (here, byte[]) this would allow
    // a caller to mutate the record's internal state via the accessor.
    // Cloning on access preserves the immutability contract.
    // ========================================================================

    /**
     * Returns a defensive copy of the {@code FILLER} bytes. The internal
     * array is cloned on each access so that callers cannot mutate the
     * record's state through this accessor.
     *
     * @return a new {@code byte[FILLER_LENGTH]} copy of the FILLER bytes
     */
    @Override
    public byte[] filler() {
        return filler.clone();
    }

    // ========================================================================
    // parse(byte[]) — static factory decoding a 500-byte fixed-width buffer.
    // Uses US-ASCII for byte→String conversion per AAP §0.6.5 (the
    // app/data/ASCII/ fixtures are pre-transcoded from EBCDIC to US-ASCII).
    // ========================================================================

    /**
     * Factory: decodes a {@value #RECORD_LENGTH}-byte buffer into a
     * {@code CustomerRecord}. Per AAP &sect;0.6.5 byte-fidelity:
     * {@code parse(b).encode()} equals {@code b} byte-for-byte for every
     * well-formed buffer.
     *
     * <p>Field decoding rules:
     * <ul>
     *   <li>{@code CUST-ID}, {@code CUST-SSN}, {@code CUST-FICO-CREDIT-SCORE}
     *       (PIC 9(n)): ASCII digits parsed via
     *       {@link Long#parseLong(String)} / {@link Integer#parseInt(String)};
     *       leading zeros are stripped by the parser.</li>
     *   <li>{@code PIC X(n)} alphanumeric fields: US-ASCII bytes converted
     *       to {@link String} verbatim; trailing spaces are preserved (not
     *       trimmed) to maintain byte fidelity.</li>
     *   <li>{@code CUST-DOB-YYYY-MM-DD}: parsed via
     *       {@link LocalDate#parse(CharSequence, DateTimeFormatter)} with
     *       {@link DateTimeFormatter#ISO_LOCAL_DATE}.</li>
     *   <li>{@code CUST-PRI-CARD-HOLDER-IND} (PIC X(01)): a single ASCII
     *       byte cast to {@code char}.</li>
     *   <li>{@code FILLER}: 168 bytes copied verbatim via
     *       {@link Arrays#copyOfRange(byte[], int, int)}.</li>
     * </ul>
     *
     * @param buffer fixed-width input bytes (must be exactly
     *               {@value #RECORD_LENGTH} bytes)
     * @return parsed customer record
     * @throws NullPointerException if {@code buffer} is null
     * @throws IllegalArgumentException if {@code buffer.length} is not
     *         {@value #RECORD_LENGTH}, if any numeric field contains a
     *         non-digit byte, or if the DOB cannot be parsed as ISO date
     */
    public static CustomerRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "CustomerRecord buffer must be exactly " + RECORD_LENGTH
                            + " bytes; got " + buffer.length);
        }

        final long custId               = Long.parseLong(str(buffer, CUST_ID_OFFSET, CUST_ID_LENGTH));
        final String custFirstName      = str(buffer, CUST_FIRST_NAME_OFFSET, CUST_FIRST_NAME_LENGTH);
        final String custMiddleName     = str(buffer, CUST_MIDDLE_NAME_OFFSET, CUST_MIDDLE_NAME_LENGTH);
        final String custLastName       = str(buffer, CUST_LAST_NAME_OFFSET, CUST_LAST_NAME_LENGTH);
        final String custAddrLine1      = str(buffer, CUST_ADDR_LINE_1_OFFSET, CUST_ADDR_LINE_1_LENGTH);
        final String custAddrLine2      = str(buffer, CUST_ADDR_LINE_2_OFFSET, CUST_ADDR_LINE_2_LENGTH);
        final String custAddrLine3      = str(buffer, CUST_ADDR_LINE_3_OFFSET, CUST_ADDR_LINE_3_LENGTH);
        final String custAddrStateCd    = str(buffer, CUST_ADDR_STATE_CD_OFFSET, CUST_ADDR_STATE_CD_LENGTH);
        final String custAddrCountryCd  = str(buffer, CUST_ADDR_COUNTRY_CD_OFFSET, CUST_ADDR_COUNTRY_CD_LENGTH);
        final String custAddrZip        = str(buffer, CUST_ADDR_ZIP_OFFSET, CUST_ADDR_ZIP_LENGTH);
        final String custPhoneNum1      = str(buffer, CUST_PHONE_NUM_1_OFFSET, CUST_PHONE_NUM_1_LENGTH);
        final String custPhoneNum2      = str(buffer, CUST_PHONE_NUM_2_OFFSET, CUST_PHONE_NUM_2_LENGTH);
        final long custSsn              = Long.parseLong(str(buffer, CUST_SSN_OFFSET, CUST_SSN_LENGTH));
        final String custGovtIssuedId   = str(buffer, CUST_GOVT_ISSUED_ID_OFFSET, CUST_GOVT_ISSUED_ID_LENGTH);
        final LocalDate custDobYyyyMmDd = LocalDate.parse(
                str(buffer, CUST_DOB_YYYY_MM_DD_OFFSET, CUST_DOB_YYYY_MM_DD_LENGTH), DOB_FMT);
        final String custEftAccountId   = str(buffer, CUST_EFT_ACCOUNT_ID_OFFSET, CUST_EFT_ACCOUNT_ID_LENGTH);
        final char custPriCardHolderInd = (char) (buffer[CUST_PRI_CARD_HOLDER_IND_OFFSET] & 0xFF);
        final int custFicoCreditScore   = Integer.parseInt(
                str(buffer, CUST_FICO_CREDIT_SCORE_OFFSET, CUST_FICO_CREDIT_SCORE_LENGTH));
        final byte[] filler             = Arrays.copyOfRange(
                buffer, FILLER_OFFSET, FILLER_OFFSET + FILLER_LENGTH);

        return new CustomerRecord(
                custId, custFirstName, custMiddleName, custLastName,
                custAddrLine1, custAddrLine2, custAddrLine3,
                custAddrStateCd, custAddrCountryCd, custAddrZip,
                custPhoneNum1, custPhoneNum2,
                custSsn, custGovtIssuedId, custDobYyyyMmDd, custEftAccountId,
                custPriCardHolderInd, custFicoCreditScore, filler);
    }

    // ========================================================================
    // encode() — serializes this record as a 500-byte fixed-width buffer.
    // The buffer is initialized to spaces (0x20) so unfilled trailing bytes
    // in PIC X(n) fields default to ASCII space per COBOL DISPLAY convention.
    // Numeric fields are left-zero-padded; alphanumeric fields are
    // right-space-padded by writeString's no-op padding behavior plus the
    // initial space fill.
    // ========================================================================

    /**
     * Encodes this record as a {@value #RECORD_LENGTH}-byte fixed-width
     * ASCII buffer suitable for sequential I/O via {@code java.nio.file}.
     *
     * <p>Encoding rules:
     * <ul>
     *   <li>Numeric fields (PIC 9(n)): zero-padded LEFT, e.g.
     *       {@code custFicoCreditScore = 750} → {@code "750"}.</li>
     *   <li>Alphanumeric fields (PIC X(n)): space-padded RIGHT, e.g.
     *       {@code custAddrStateCd = "NC"} → {@code "NC"} (no padding here
     *       since width = 2).</li>
     *   <li>{@code CUST-DOB-YYYY-MM-DD}: formatted via
     *       {@link LocalDate#format(DateTimeFormatter)} with
     *       {@link DateTimeFormatter#ISO_LOCAL_DATE}.</li>
     *   <li>{@code CUST-PRI-CARD-HOLDER-IND}: the {@code char} is written
     *       as a single ASCII byte (the low 8 bits of the char value).</li>
     *   <li>{@code FILLER}: 168 bytes copied verbatim from the internal
     *       byte array.</li>
     * </ul>
     *
     * @return a new {@code byte[}{@value #RECORD_LENGTH}{@code ]} containing
     *         the encoded record
     */
    public byte[] encode() {
        final byte[] out = new byte[RECORD_LENGTH];
        Arrays.fill(out, (byte) 0x20);     // ASCII space default per COBOL DISPLAY convention

        writeNumeric(out, custId,             CUST_ID_OFFSET, CUST_ID_LENGTH);
        writeString (out, custFirstName,      CUST_FIRST_NAME_OFFSET, CUST_FIRST_NAME_LENGTH);
        writeString (out, custMiddleName,     CUST_MIDDLE_NAME_OFFSET, CUST_MIDDLE_NAME_LENGTH);
        writeString (out, custLastName,       CUST_LAST_NAME_OFFSET, CUST_LAST_NAME_LENGTH);
        writeString (out, custAddrLine1,      CUST_ADDR_LINE_1_OFFSET, CUST_ADDR_LINE_1_LENGTH);
        writeString (out, custAddrLine2,      CUST_ADDR_LINE_2_OFFSET, CUST_ADDR_LINE_2_LENGTH);
        writeString (out, custAddrLine3,      CUST_ADDR_LINE_3_OFFSET, CUST_ADDR_LINE_3_LENGTH);
        writeString (out, custAddrStateCd,    CUST_ADDR_STATE_CD_OFFSET, CUST_ADDR_STATE_CD_LENGTH);
        writeString (out, custAddrCountryCd,  CUST_ADDR_COUNTRY_CD_OFFSET, CUST_ADDR_COUNTRY_CD_LENGTH);
        writeString (out, custAddrZip,        CUST_ADDR_ZIP_OFFSET, CUST_ADDR_ZIP_LENGTH);
        writeString (out, custPhoneNum1,      CUST_PHONE_NUM_1_OFFSET, CUST_PHONE_NUM_1_LENGTH);
        writeString (out, custPhoneNum2,      CUST_PHONE_NUM_2_OFFSET, CUST_PHONE_NUM_2_LENGTH);
        writeNumeric(out, custSsn,            CUST_SSN_OFFSET, CUST_SSN_LENGTH);
        writeString (out, custGovtIssuedId,   CUST_GOVT_ISSUED_ID_OFFSET, CUST_GOVT_ISSUED_ID_LENGTH);
        writeString (out, custDobYyyyMmDd.format(DOB_FMT),
                CUST_DOB_YYYY_MM_DD_OFFSET, CUST_DOB_YYYY_MM_DD_LENGTH);
        writeString (out, custEftAccountId,   CUST_EFT_ACCOUNT_ID_OFFSET, CUST_EFT_ACCOUNT_ID_LENGTH);
        out[CUST_PRI_CARD_HOLDER_IND_OFFSET] = (byte) custPriCardHolderInd;
        writeNumeric(out, custFicoCreditScore, CUST_FICO_CREDIT_SCORE_OFFSET, CUST_FICO_CREDIT_SCORE_LENGTH);
        System.arraycopy(filler, 0, out, FILLER_OFFSET, FILLER_LENGTH);

        return out;
    }

    // ========================================================================
    // Private encode/decode helpers.
    // These are deliberately small and inlinable: each does one thing
    // (read N bytes as ASCII, write a numeric or alphanumeric field) so the
    // public methods read top-to-bottom as a field-by-field translation of
    // the COBOL copybook layout.
    // ========================================================================

    /** Decodes {@code length} bytes starting at {@code offset} as US-ASCII. */
    private static String str(byte[] buf, int offset, int length) {
        return new String(buf, offset, length, StandardCharsets.US_ASCII);
    }

    /**
     * Writes {@code value} into {@code out} as US-ASCII at the given offset,
     * truncating to {@code length} if the value exceeds the field width.
     * Trailing bytes within {@code length} are left untouched (the caller
     * is responsible for initializing the buffer with the desired pad byte;
     * {@link #encode()} fills with ASCII space 0x20 before writing).
     */
    private static void writeString(byte[] out, String value, int offset, int length) {
        final byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        final int copyLen = Math.min(bytes.length, length);
        System.arraycopy(bytes, 0, out, offset, copyLen);
    }

    /**
     * Writes {@code value} into {@code out} as zero-padded ASCII digits at
     * the given offset. Mirrors COBOL {@code PIC 9(n)} encoding: a
     * {@code length}-byte field with the integer value left-zero-padded to
     * fill the entire width.
     *
     * @throws IllegalArgumentException if {@code value} has more digits than
     *         {@code length} can represent
     */
    private static void writeNumeric(byte[] out, long value, int offset, int length) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    "writeNumeric requires non-negative value, got " + value);
        }
        final String s = String.format("%0" + length + "d", value);
        if (s.length() != length) {
            // String.format produced more digits than the field can hold;
            // this indicates the value overflows the COBOL PIC 9(n) width
            // and must be flagged rather than silently truncated.
            throw new IllegalArgumentException(
                    "Numeric value " + value + " exceeds " + length + "-digit field width");
        }
        System.arraycopy(s.getBytes(StandardCharsets.US_ASCII), 0, out, offset, length);
    }

    // ========================================================================
    // equals / hashCode override.
    // The default record-generated equals/hashCode compares the byte[] filler
    // component by REFERENCE (==), which is incorrect for value semantics.
    // We override both to compare filler by content (Arrays.equals/hashCode)
    // so that two records with identical bytes — including FILLER — are
    // equal regardless of which buffer the bytes were copied from.
    // ========================================================================

    /**
     * Value equality: two {@code CustomerRecord}s are equal iff every
     * field is equal AND their {@code filler} byte arrays are element-wise
     * equal (via {@link Arrays#equals(byte[], byte[])}). Overrides the
     * default record-generated implementation, which would compare the
     * byte-array component by reference identity rather than by content.
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CustomerRecord that)) {
            return false;
        }
        return custId == that.custId
                && custSsn == that.custSsn
                && custPriCardHolderInd == that.custPriCardHolderInd
                && custFicoCreditScore == that.custFicoCreditScore
                && custFirstName.equals(that.custFirstName)
                && custMiddleName.equals(that.custMiddleName)
                && custLastName.equals(that.custLastName)
                && custAddrLine1.equals(that.custAddrLine1)
                && custAddrLine2.equals(that.custAddrLine2)
                && custAddrLine3.equals(that.custAddrLine3)
                && custAddrStateCd.equals(that.custAddrStateCd)
                && custAddrCountryCd.equals(that.custAddrCountryCd)
                && custAddrZip.equals(that.custAddrZip)
                && custPhoneNum1.equals(that.custPhoneNum1)
                && custPhoneNum2.equals(that.custPhoneNum2)
                && custGovtIssuedId.equals(that.custGovtIssuedId)
                && custDobYyyyMmDd.equals(that.custDobYyyyMmDd)
                && custEftAccountId.equals(that.custEftAccountId)
                && Arrays.equals(filler, that.filler);
    }

    /**
     * Consistent with {@link #equals(Object)}: incorporates
     * {@link Arrays#hashCode(byte[])} of the filler bytes rather than the
     * array's identity hashCode.
     */
    @Override
    public int hashCode() {
        int result = Long.hashCode(custId);
        result = 31 * result + custFirstName.hashCode();
        result = 31 * result + custMiddleName.hashCode();
        result = 31 * result + custLastName.hashCode();
        result = 31 * result + custAddrLine1.hashCode();
        result = 31 * result + custAddrLine2.hashCode();
        result = 31 * result + custAddrLine3.hashCode();
        result = 31 * result + custAddrStateCd.hashCode();
        result = 31 * result + custAddrCountryCd.hashCode();
        result = 31 * result + custAddrZip.hashCode();
        result = 31 * result + custPhoneNum1.hashCode();
        result = 31 * result + custPhoneNum2.hashCode();
        result = 31 * result + Long.hashCode(custSsn);
        result = 31 * result + custGovtIssuedId.hashCode();
        result = 31 * result + custDobYyyyMmDd.hashCode();
        result = 31 * result + custEftAccountId.hashCode();
        result = 31 * result + Character.hashCode(custPriCardHolderInd);
        result = 31 * result + Integer.hashCode(custFicoCreditScore);
        result = 31 * result + Arrays.hashCode(filler);
        return result;
    }

    // ========================================================================
    // PII-safe toString override (AAP §0.7.2).
    //
    // The default record-generated toString() would emit every component,
    // including SSN, government-issued id, date of birth, EFT account id,
    // address lines, phone numbers, and FICO credit score — collectively a
    // PII surface that must not leak into logs, exception messages, debugger
    // displays, or any other Object.toString() consumer.
    //
    // This override redacts the sensitive components while keeping the
    // customer id and trimmed name visible for diagnostic correlation. The
    // SSN renders as "***-**-1234" (last 4 visible) so that operators can
    // disambiguate records during incident triage without seeing the full
    // 9-digit identifier. All other sensitive fields render as "[REDACTED]"
    // markers.
    //
    // The masking is purely a presentation/logging concern; it does NOT
    // alter the stored values, which remain accessible via the canonical
    // record accessors for byte-level processing and legitimate report
    // generation. The byte-for-byte round-trip invariant
    // (parse(b).encode() == b) is therefore unaffected.
    // ========================================================================

    /**
     * PII-redaction placeholder used in {@link #toString()} for sensitive
     * customer fields (government-issued id, date of birth, EFT account
     * id, address lines, phone numbers). The marker is deliberately
     * fixed-length and obviously non-data so log readers can distinguish
     * redaction from missing data.
     */
    private static final String REDACTED = "[REDACTED]";

    /**
     * Returns the SSN with all but the last 4 digits masked in standard
     * US format ({@code "***-**-1234"}). The full 9-digit value is
     * preserved in {@link #custSsn()} for legitimate processing paths.
     *
     * @return the masked SSN string; never {@code null}
     */
    public String maskedSsn() {
        long last4 = custSsn % 10000L;
        return String.format("***-**-%04d", last4);
    }

    /**
     * Returns a PII-safe diagnostic string representation of this customer
     * record. The customer id and (trimmed) name components are emitted in
     * cleartext; the SSN is rendered as {@link #maskedSsn()}; and the
     * date-of-birth, government-issued id, EFT account id, address lines,
     * phone numbers, FICO score, and FILLER are replaced with the
     * {@value #REDACTED} placeholder. This is the required override per
     * AAP &sect;0.7.2 to prevent accidental PII disclosure via
     * {@code log.info("{}", customerRecord)} and any other
     * {@link Object#toString()} consumer.
     *
     * @return a PII-safe diagnostic string
     */
    @Override
    public String toString() {
        return "CustomerRecord["
                + "custId=" + custId
                + ", custFirstName=" + custFirstName.trim()
                + ", custMiddleName=" + custMiddleName.trim()
                + ", custLastName=" + custLastName.trim()
                + ", custAddrLine1=" + REDACTED
                + ", custAddrLine2=" + REDACTED
                + ", custAddrLine3=" + REDACTED
                + ", custAddrStateCd=" + custAddrStateCd
                + ", custAddrCountryCd=" + custAddrCountryCd
                + ", custAddrZip=" + REDACTED
                + ", custPhoneNum1=" + REDACTED
                + ", custPhoneNum2=" + REDACTED
                + ", custSsn=" + maskedSsn()
                + ", custGovtIssuedId=" + REDACTED
                + ", custDobYyyyMmDd=" + REDACTED
                + ", custEftAccountId=" + REDACTED
                + ", custPriCardHolderInd=" + custPriCardHolderInd
                + ", custFicoCreditScore=" + REDACTED
                + ", filler=<" + FILLER_LENGTH + " bytes>]";
    }
}

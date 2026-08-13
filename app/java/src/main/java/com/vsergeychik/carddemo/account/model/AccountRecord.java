package com.vsergeychik.carddemo.account.model;

import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;

/**
 * The account master record, {@code 01 ACCOUNT-RECORD} of copybook {@code app/cpy/CVACT01Y.cpy}, exactly
 * 300 bytes wide.
 *
 * <p>{@code PIC S9(10)V99} occupies exactly {@code p + s} = 12 bytes.
 */
public final class AccountRecord {
    /**
     * The declared record width in bytes, from {@code CVACT01Y.cpy:L2} ({@code RECLN 300}) and
     * independently from {@code CBACT04C.cbl:L85-L87} (11 + 289).
     */
    public static final int RECORD_LENGTH = 300;

    /**
     * {@code p} in the {@code PIC S9(p)V99} of all five monetary fields: 10 integer digit positions.
     */
    public static final int MONETARY_INTEGER_DIGITS = 10;

    /**
     * {@code s} in the {@code PIC S9(10)V(s)} of all five monetary fields: 2 fraction digit positions.
     */
    public static final int MONETARY_SCALE = CobolDecimal.MONETARY_SCALE;

    /**
     * Copybook item name of the primary key, verbatim: {@code ACCT-ID}.
     */
    public static final String ACCT_ID_NAME = "ACCT-ID";

    public static final int ACCT_ID_OFFSET = 0;

    /**
     * Declared width of {@link #ACCT_ID_NAME}: 11 bytes, from {@code PIC 9(11)}.
     */
    public static final int ACCT_ID_LENGTH = 11;

    public static final FieldSpan SPAN_ACCT_ID =
            FieldSpan.unsignedNumeric(ACCT_ID_NAME, ACCT_ID_OFFSET, ACCT_ID_LENGTH);

    /**
     * The width in bytes of the VSAM primary key, which is the width of {@link #ACCT_ID_NAME}: 11.
     */
    public static final int KEY_LENGTH = ACCT_ID_LENGTH;

    /**
     * Copybook item name, verbatim: {@code ACCT-ACTIVE-STATUS}.
     */
    public static final String ACCT_ACTIVE_STATUS_NAME = "ACCT-ACTIVE-STATUS";

    public static final int ACCT_ACTIVE_STATUS_OFFSET = 11;

    /**
     * Declared width of {@link #ACCT_ACTIVE_STATUS_NAME}: 1 byte, from {@code PIC X(01)}.
     */
    public static final int ACCT_ACTIVE_STATUS_LENGTH = 1;

    public static final FieldSpan SPAN_ACCT_ACTIVE_STATUS = FieldSpan.alphanumeric(
            ACCT_ACTIVE_STATUS_NAME, ACCT_ACTIVE_STATUS_OFFSET, ACCT_ACTIVE_STATUS_LENGTH);

    /**
     * Copybook item name, verbatim: {@code ACCT-CURR-BAL}.
     */
    public static final String ACCT_CURR_BAL_NAME = "ACCT-CURR-BAL";

    public static final int ACCT_CURR_BAL_OFFSET = 12;

    public static final int ACCT_CURR_BAL_LENGTH = MONETARY_INTEGER_DIGITS + MONETARY_SCALE;

    /**
     * Descriptor for {@link #ACCT_CURR_BAL_NAME}: signed zoned scale 2, bytes 12 through 23.
     */
    public static final FieldSpan SPAN_ACCT_CURR_BAL = FieldSpan.signedScaled(
            ACCT_CURR_BAL_NAME, ACCT_CURR_BAL_OFFSET, MONETARY_INTEGER_DIGITS, MONETARY_SCALE);

    /**
     * Copybook item name, verbatim: {@code ACCT-CREDIT-LIMIT}.
     */
    public static final String ACCT_CREDIT_LIMIT_NAME = "ACCT-CREDIT-LIMIT";

    public static final int ACCT_CREDIT_LIMIT_OFFSET = 24;

    public static final int ACCT_CREDIT_LIMIT_LENGTH = MONETARY_INTEGER_DIGITS + MONETARY_SCALE;

    /**
     * Descriptor for {@link #ACCT_CREDIT_LIMIT_NAME}: signed zoned scale 2, bytes 24 through 35.
     */
    public static final FieldSpan SPAN_ACCT_CREDIT_LIMIT = FieldSpan.signedScaled(
            ACCT_CREDIT_LIMIT_NAME, ACCT_CREDIT_LIMIT_OFFSET, MONETARY_INTEGER_DIGITS, MONETARY_SCALE);

    /**
     * Copybook item name, verbatim: {@code ACCT-CASH-CREDIT-LIMIT}.
     */
    public static final String ACCT_CASH_CREDIT_LIMIT_NAME = "ACCT-CASH-CREDIT-LIMIT";

    public static final int ACCT_CASH_CREDIT_LIMIT_OFFSET = 36;

    public static final int ACCT_CASH_CREDIT_LIMIT_LENGTH = MONETARY_INTEGER_DIGITS + MONETARY_SCALE;

    /**
     * Descriptor for {@link #ACCT_CASH_CREDIT_LIMIT_NAME}: signed zoned scale 2, bytes 36 to 47.
     */
    public static final FieldSpan SPAN_ACCT_CASH_CREDIT_LIMIT = FieldSpan.signedScaled(
            ACCT_CASH_CREDIT_LIMIT_NAME, ACCT_CASH_CREDIT_LIMIT_OFFSET,
            MONETARY_INTEGER_DIGITS, MONETARY_SCALE);

    /**
     * Copybook item name, verbatim: {@code ACCT-OPEN-DATE}.
     */
    public static final String ACCT_OPEN_DATE_NAME = "ACCT-OPEN-DATE";

    public static final int ACCT_OPEN_DATE_OFFSET = 48;

    /**
     * Declared width of {@link #ACCT_OPEN_DATE_NAME}: 10 bytes, from {@code PIC X(10)}.
     */
    public static final int ACCT_OPEN_DATE_LENGTH = 10;

    public static final FieldSpan SPAN_ACCT_OPEN_DATE = FieldSpan.alphanumeric(
            ACCT_OPEN_DATE_NAME, ACCT_OPEN_DATE_OFFSET, ACCT_OPEN_DATE_LENGTH);

    /**
     * Copybook item name, verbatim: {@code ACCT-EXPIRAION-DATE}.
     */
    public static final String ACCT_EXPIRAION_DATE_NAME = "ACCT-EXPIRAION-DATE";

    public static final int ACCT_EXPIRAION_DATE_OFFSET = 58;

    /**
     * Declared width of {@link #ACCT_EXPIRAION_DATE_NAME}: 10 bytes, from {@code PIC X(10)}.
     */
    public static final int ACCT_EXPIRAION_DATE_LENGTH = 10;

    public static final FieldSpan SPAN_ACCT_EXPIRAION_DATE = FieldSpan.alphanumeric(
            ACCT_EXPIRAION_DATE_NAME, ACCT_EXPIRAION_DATE_OFFSET, ACCT_EXPIRAION_DATE_LENGTH);

    /**
     * Copybook item name, verbatim: {@code ACCT-REISSUE-DATE}.
     */
    public static final String ACCT_REISSUE_DATE_NAME = "ACCT-REISSUE-DATE";

    public static final int ACCT_REISSUE_DATE_OFFSET = 68;

    /**
     * Declared width of {@link #ACCT_REISSUE_DATE_NAME}: 10 bytes, from {@code PIC X(10)}.
     */
    public static final int ACCT_REISSUE_DATE_LENGTH = 10;

    public static final FieldSpan SPAN_ACCT_REISSUE_DATE = FieldSpan.alphanumeric(
            ACCT_REISSUE_DATE_NAME, ACCT_REISSUE_DATE_OFFSET, ACCT_REISSUE_DATE_LENGTH);

    /**
     * Copybook item name, verbatim: {@code ACCT-CURR-CYC-CREDIT}.
     */
    public static final String ACCT_CURR_CYC_CREDIT_NAME = "ACCT-CURR-CYC-CREDIT";

    public static final int ACCT_CURR_CYC_CREDIT_OFFSET = 78;

    public static final int ACCT_CURR_CYC_CREDIT_LENGTH = MONETARY_INTEGER_DIGITS + MONETARY_SCALE;

    /**
     * Descriptor for {@link #ACCT_CURR_CYC_CREDIT_NAME}: signed zoned scale 2, bytes 78 to 89.
     */
    public static final FieldSpan SPAN_ACCT_CURR_CYC_CREDIT = FieldSpan.signedScaled(
            ACCT_CURR_CYC_CREDIT_NAME, ACCT_CURR_CYC_CREDIT_OFFSET,
            MONETARY_INTEGER_DIGITS, MONETARY_SCALE);

    /**
     * Copybook item name, verbatim: {@code ACCT-CURR-CYC-DEBIT}.
     */
    public static final String ACCT_CURR_CYC_DEBIT_NAME = "ACCT-CURR-CYC-DEBIT";

    public static final int ACCT_CURR_CYC_DEBIT_OFFSET = 90;

    public static final int ACCT_CURR_CYC_DEBIT_LENGTH = MONETARY_INTEGER_DIGITS + MONETARY_SCALE;

    /**
     * Descriptor for {@link #ACCT_CURR_CYC_DEBIT_NAME}: signed zoned scale 2, bytes 90 to 101.
     */
    public static final FieldSpan SPAN_ACCT_CURR_CYC_DEBIT = FieldSpan.signedScaled(
            ACCT_CURR_CYC_DEBIT_NAME, ACCT_CURR_CYC_DEBIT_OFFSET,
            MONETARY_INTEGER_DIGITS, MONETARY_SCALE);

    /**
     * Copybook item name, verbatim: {@code ACCT-ADDR-ZIP}.
     */
    public static final String ACCT_ADDR_ZIP_NAME = "ACCT-ADDR-ZIP";

    public static final int ACCT_ADDR_ZIP_OFFSET = 102;

    /**
     * Declared width of {@link #ACCT_ADDR_ZIP_NAME}: 10 bytes, from {@code PIC X(10)}.
     */
    public static final int ACCT_ADDR_ZIP_LENGTH = 10;

    public static final FieldSpan SPAN_ACCT_ADDR_ZIP = FieldSpan.alphanumeric(
            ACCT_ADDR_ZIP_NAME, ACCT_ADDR_ZIP_OFFSET, ACCT_ADDR_ZIP_LENGTH);

    /**
     * Copybook item name, verbatim: {@code ACCT-GROUP-ID}.
     */
    public static final String ACCT_GROUP_ID_NAME = "ACCT-GROUP-ID";

    public static final int ACCT_GROUP_ID_OFFSET = 112;

    /**
     * Declared width of {@link #ACCT_GROUP_ID_NAME}: 10 bytes, from {@code PIC X(10)}.
     */
    public static final int ACCT_GROUP_ID_LENGTH = 10;

    public static final FieldSpan SPAN_ACCT_GROUP_ID = FieldSpan.alphanumeric(
            ACCT_GROUP_ID_NAME, ACCT_GROUP_ID_OFFSET, ACCT_GROUP_ID_LENGTH);

    public static final int FILLER_OFFSET = 122;

    /**
     * Declared width of the trailing {@code FILLER}: 178 bytes, from {@code PIC X(178)}.
     */
    public static final int FILLER_LENGTH = 178;

    /**
     * Descriptor for the trailing {@code FILLER}, bytes 122 through 299.
     */
    public static final FieldSpan SPAN_FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    /**
     * The complete layout of {@code 01 ACCOUNT-RECORD}: thirteen spans, in copybook declaration order,
     * totalling {@link #RECORD_LENGTH} bytes.
     */
    public static final RecordLayout LAYOUT = RecordLayout.of(RECORD_LENGTH,
            SPAN_ACCT_ID,
            SPAN_ACCT_ACTIVE_STATUS,
            SPAN_ACCT_CURR_BAL,
            SPAN_ACCT_CREDIT_LIMIT,
            SPAN_ACCT_CASH_CREDIT_LIMIT,
            SPAN_ACCT_OPEN_DATE,
            SPAN_ACCT_EXPIRAION_DATE,
            SPAN_ACCT_REISSUE_DATE,
            SPAN_ACCT_CURR_CYC_CREDIT,
            SPAN_ACCT_CURR_CYC_DEBIT,
            SPAN_ACCT_ADDR_ZIP,
            SPAN_ACCT_GROUP_ID,
            SPAN_FILLER);

    private final FixedWidthRecord record;

    private final FixedWidthCodec codec;

    /**
     * Allocates an initialised, empty account record in the given code page.
     *
     * @param charset the code page the record's bytes are in - {@code US-ASCII} for the ASCII fixtures, an
     *     EBCDIC charset such as {@code IBM037} for mainframe data
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} does not encode every digit, sign overpunch
     *     character and the space to exactly one byte, since a zoned {@code DISPLAY} field of {@code n} digits
     *     occupies exactly {@code n} bytes
     */
    public AccountRecord(Charset charset) {
        this.codec = new FixedWidthCodec(charset);
        this.record = codec.newRecord(LAYOUT);
    }

    private AccountRecord(byte[] image, Charset charset) {
        this.codec = new FixedWidthCodec(charset);
        this.record = codec.wrap(image, LAYOUT);
    }

    public static AccountRecord decode(byte[] image, Charset charset) {
        Objects.requireNonNull(image, "Stored bytes are required to decode an account record; use "
                + "new AccountRecord(Charset) to allocate an empty one");
        Objects.requireNonNull(charset, "A charset is required to decode an account record: the "
                + "stored bytes are in a specific code page, which is never assumed");
        return new AccountRecord(image, charset);
    }

    /**
     * Decodes a stored account record given as text, for the character-oriented ASCII fixtures.
     *
     * <p>The text is encoded with {@code charset} and then decoded exactly as
     * {@link #decode(byte[], Charset)} does, so a row from {@code app/data/ASCII/acctdata.txt} can be read
     * as the line of text it is.
     *
     * @param image exactly {@link #RECORD_LENGTH} characters as stored, trailing spaces included
     * @param charset the code page to encode {@code image} in
     * @return a record over the encoded bytes
     * @throws NullPointerException if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} does not encode to exactly {@link #RECORD_LENGTH}
     *     bytes
     */
    public static AccountRecord decode(String image, Charset charset) {
        Objects.requireNonNull(image, "Stored text is required to decode an account record");
        Objects.requireNonNull(charset, "A charset is required to decode an account record: the "
                + "stored characters become bytes in a specific code page, which is never assumed");
        return new AccountRecord(FixedWidthRecord.encodeText(image, charset,
                "an ACCOUNT-RECORD image"), charset);
    }

    // This is the group item: COBOL addresses ACCOUNT-RECORD itself as often as it addresses the fields
    // inside it, and the 300-byte image is what parity is judged on.

    /**
     * The declared record width in bytes, always {@link #RECORD_LENGTH}.
     *
     * @return 300
     */
    public int recordLength() {
        return record.recordLength();
    }

    /**
     * The code page this record's bytes are in, as supplied at construction.
     *
     * @return the charset, never {@code null}
     */
    public Charset charset() {
        return record.charset();
    }

    /**
     * The complete 300-byte image, ready to be written back to the dataset.
     *
     * @return a copy of all {@link #RECORD_LENGTH} bytes
     */
    public byte[] toByteArray() {
        return record.toByteArray();
    }

    /**
     * The complete record rendered as its {@link #RECORD_LENGTH} characters.
     *
     * @return exactly {@link #RECORD_LENGTH} characters
     */
    public String toFixedWidthString() {
        return record.readString(0, RECORD_LENGTH);
    }

    /**
     * Reads any declared span as its stored characters, without interpretation.
     *
     * <p>Exactly {@code field.length()} characters are returned: nothing is trimmed, and a zoned numeric
     * span retains its sign overpunch.
     *
     * @param field one of this class's {@code SPAN_} descriptors
     * @return the span's stored characters
     * @throws NullPointerException if {@code field} is {@code null}
     * @throws IndexOutOfBoundsException if {@code field} does not lie within this record, which can only
     *     happen if a descriptor from another record type is passed
     */
    public String raw(FieldSpan field) {
        Objects.requireNonNull(field, "A field descriptor is required; pass one of AccountRecord's "
                + "SPAN_ constants so the offset comes from the declared layout");
        return record.readSpan(field);
    }

    public byte[] rawBytes(FieldSpan field) {
        Objects.requireNonNull(field, "A field descriptor is required; pass one of AccountRecord's "
                + "SPAN_ constants so the offset comes from the declared layout");
        return record.readSpanBytes(field);
    }

    /**
     * {@code ACCT-ID} as stored: 11 zero-filled digits, for example {@code 00000000001}.
     *
     * @return exactly {@link #ACCT_ID_LENGTH} characters
     */
    public String rawAcctId() {
        return raw(SPAN_ACCT_ID);
    }

    /**
     * {@code ACCT-ACTIVE-STATUS} as stored: 1 character.
     *
     * @return exactly {@link #ACCT_ACTIVE_STATUS_LENGTH} character
     */
    public String rawAcctActiveStatus() {
        return raw(SPAN_ACCT_ACTIVE_STATUS);
    }

    /**
     * {@code ACCT-CURR-BAL} as stored: 12 characters including the sign overpunch, for example
     * 00000001940&#123; for {@code +1940.00}.
     *
     * @return exactly {@link #ACCT_CURR_BAL_LENGTH} characters
     */
    public String rawAcctCurrBal() {
        return raw(SPAN_ACCT_CURR_BAL);
    }

    /**
     * {@code ACCT-CREDIT-LIMIT} as stored: 12 characters including the sign overpunch.
     *
     * @return exactly {@link #ACCT_CREDIT_LIMIT_LENGTH} characters
     */
    public String rawAcctCreditLimit() {
        return raw(SPAN_ACCT_CREDIT_LIMIT);
    }

    /**
     * {@code ACCT-CASH-CREDIT-LIMIT} as stored: 12 characters including the sign overpunch.
     *
     * @return exactly {@link #ACCT_CASH_CREDIT_LIMIT_LENGTH} characters
     */
    public String rawAcctCashCreditLimit() {
        return raw(SPAN_ACCT_CASH_CREDIT_LIMIT);
    }

    /**
     * {@code ACCT-OPEN-DATE} as stored: 10 characters.
     *
     * @return exactly {@link #ACCT_OPEN_DATE_LENGTH} characters
     */
    public String rawAcctOpenDate() {
        return raw(SPAN_ACCT_OPEN_DATE);
    }

    /**
     * {@code ACCT-EXPIRAION-DATE} as stored: 10 characters.
     *
     * @return exactly {@link #ACCT_EXPIRAION_DATE_LENGTH} characters
     */
    public String rawAcctExpiraionDate() {
        return raw(SPAN_ACCT_EXPIRAION_DATE);
    }

    /**
     * {@code ACCT-REISSUE-DATE} as stored: 10 characters.
     *
     * @return exactly {@link #ACCT_REISSUE_DATE_LENGTH} characters
     */
    public String rawAcctReissueDate() {
        return raw(SPAN_ACCT_REISSUE_DATE);
    }

    /**
     * {@code ACCT-CURR-CYC-CREDIT} as stored: 12 characters including the sign overpunch.
     *
     * @return exactly {@link #ACCT_CURR_CYC_CREDIT_LENGTH} characters
     */
    public String rawAcctCurrCycCredit() {
        return raw(SPAN_ACCT_CURR_CYC_CREDIT);
    }

    /**
     * {@code ACCT-CURR-CYC-DEBIT} as stored: 12 characters including the sign overpunch.
     *
     * @return exactly {@link #ACCT_CURR_CYC_DEBIT_LENGTH} characters
     */
    public String rawAcctCurrCycDebit() {
        return raw(SPAN_ACCT_CURR_CYC_DEBIT);
    }

    /**
     * {@code ACCT-ADDR-ZIP} as stored: 10 characters.
     *
     * @return exactly {@link #ACCT_ADDR_ZIP_LENGTH} characters
     */
    public String rawAcctAddrZip() {
        return raw(SPAN_ACCT_ADDR_ZIP);
    }

    /**
     * {@code ACCT-GROUP-ID} as stored: 10 characters, right-space-padded.
     *
     * @return exactly {@link #ACCT_GROUP_ID_LENGTH} characters
     */
    public String rawAcctGroupId() {
        return raw(SPAN_ACCT_GROUP_ID);
    }

    // Reads decode the stored span; writes encode into it at the field's declared width, so a value that
    // does not fit is truncated where COBOL truncates it.

    /**
     * {@code ACCT-ID}, the primary key, as a number.
     *
     * <p>{@code PIC 9(11)} is an unsigned integer of 11 digits, which fits a {@code long} exactly.
     *
     * @return the account identifier
     */
    public long getAcctId() {
        return codec.readPic9(record, SPAN_ACCT_ID);
    }

    /**
     * Stores {@code ACCT-ID}, zero-filling to 11 digits.
     *
     * <p>A value with more than 11 digits is truncated on the left, which is the COBOL rule for a numeric
     * receiver and the opposite of the rule for character data.
     *
     * @param acctId the account identifier; must not be negative, since {@code PIC 9} declares no sign
     *     position and therefore has no representation for one
     * @throws IllegalArgumentException if {@code acctId} is negative
     */
    public void setAcctId(long acctId) {
        codec.writePic9(record, SPAN_ACCT_ID, acctId);
    }

    /**
     * {@code ACCT-ACTIVE-STATUS} as stored, untrimmed.
     *
     * <p>Deliberately a raw {@code PIC X(01)} character and not an enumeration or a boolean.
     *
     * @return exactly 1 character, a space in a freshly allocated record
     */
    public String getAcctActiveStatus() {
        return codec.readPicX(record, SPAN_ACCT_ACTIVE_STATUS);
    }

    /**
     * Stores {@code ACCT-ACTIVE-STATUS} into its 1-character span.
     *
     * <p>Longer input is truncated on the right and shorter input is space-padded on the right, per the
     * COBOL alphanumeric move rule.
     *
     * @param acctActiveStatus the status character; may be empty, which stores a space
     * @throws NullPointerException if {@code acctActiveStatus} is {@code null}
     */
    public void setAcctActiveStatus(String acctActiveStatus) {
        writePicX(SPAN_ACCT_ACTIVE_STATUS, acctActiveStatus, "acctActiveStatus");
    }

    /**
     * {@code ACCT-CURR-BAL}, the current balance, at scale exactly 2.
     *
     * @return the balance, with {@link BigDecimal#scale()} of exactly {@link #MONETARY_SCALE}
     */
    public BigDecimal getAcctCurrBal() {
        return codec.readMonetary(record, SPAN_ACCT_CURR_BAL);
    }

    /**
     * Stores {@code ACCT-CURR-BAL} at its declared {@code PIC S9(10)V99}.
     *
     * @param acctCurrBal the balance to store, of any scale
     * @throws NullPointerException if {@code acctCurrBal} is {@code null}
     */
    public void setAcctCurrBal(BigDecimal acctCurrBal) {
        writeMonetary(SPAN_ACCT_CURR_BAL, acctCurrBal, "acctCurrBal");
    }

    /**
     * {@code ACCT-CREDIT-LIMIT} at scale exactly 2.
     *
     * @return the credit limit, with {@link BigDecimal#scale()} of exactly {@link #MONETARY_SCALE}
     */
    public BigDecimal getAcctCreditLimit() {
        return codec.readMonetary(record, SPAN_ACCT_CREDIT_LIMIT);
    }

    /**
     * Stores {@code ACCT-CREDIT-LIMIT} at its declared {@code PIC S9(10)V99}.
     *
     * @param acctCreditLimit the credit limit to store, of any scale
     * @throws NullPointerException if {@code acctCreditLimit} is {@code null}
     */
    public void setAcctCreditLimit(BigDecimal acctCreditLimit) {
        writeMonetary(SPAN_ACCT_CREDIT_LIMIT, acctCreditLimit, "acctCreditLimit");
    }

    /**
     * {@code ACCT-CASH-CREDIT-LIMIT} at scale exactly 2.
     *
     * @return the cash credit limit, with {@link BigDecimal#scale()} of exactly {@link #MONETARY_SCALE}
     */
    public BigDecimal getAcctCashCreditLimit() {
        return codec.readMonetary(record, SPAN_ACCT_CASH_CREDIT_LIMIT);
    }

    /**
     * Stores {@code ACCT-CASH-CREDIT-LIMIT} at its declared {@code PIC S9(10)V99}.
     *
     * @param acctCashCreditLimit the cash credit limit to store, of any scale
     * @throws NullPointerException if {@code acctCashCreditLimit} is {@code null}
     */
    public void setAcctCashCreditLimit(BigDecimal acctCashCreditLimit) {
        writeMonetary(SPAN_ACCT_CASH_CREDIT_LIMIT, acctCashCreditLimit, "acctCashCreditLimit");
    }

    /**
     * {@code ACCT-OPEN-DATE} as stored, untrimmed: 10 characters in {@code YYYY-MM-DD} shape.
     *
     * @return exactly {@link #ACCT_OPEN_DATE_LENGTH} characters, all spaces in a fresh record
     */
    public String getAcctOpenDate() {
        return codec.readPicX(record, SPAN_ACCT_OPEN_DATE);
    }

    /**
     * Stores {@code ACCT-OPEN-DATE}, space-padding or right-truncating to 10 characters.
     *
     * @param acctOpenDate the date text; may be blank or partial, which the COBOL date-edit engine relies
     *     on being storable
     * @throws NullPointerException if {@code acctOpenDate} is {@code null}
     */
    public void setAcctOpenDate(String acctOpenDate) {
        writePicX(SPAN_ACCT_OPEN_DATE, acctOpenDate, "acctOpenDate");
    }

    /**
     * {@code ACCT-EXPIRAION-DATE} as stored, untrimmed: 10 characters in {@code YYYY-MM-DD} shape.
     *
     * <p>The name reproduces the copybook's misspelling deliberately.
     *
     * @return exactly {@link #ACCT_EXPIRAION_DATE_LENGTH} characters, all spaces in a fresh record
     */
    public String getAcctExpiraionDate() {
        return codec.readPicX(record, SPAN_ACCT_EXPIRAION_DATE);
    }

    /**
     * Stores {@code ACCT-EXPIRAION-DATE}, space-padding or right-truncating to 10 characters.
     *
     * @param acctExpiraionDate the date text; may be blank or partial
     * @throws NullPointerException if {@code acctExpiraionDate} is {@code null}
     */
    public void setAcctExpiraionDate(String acctExpiraionDate) {
        writePicX(SPAN_ACCT_EXPIRAION_DATE, acctExpiraionDate, "acctExpiraionDate");
    }

    /**
     * {@code ACCT-REISSUE-DATE} as stored, untrimmed: 10 characters in {@code YYYY-MM-DD} shape.
     *
     * @return exactly {@link #ACCT_REISSUE_DATE_LENGTH} characters, all spaces in a fresh record
     */
    public String getAcctReissueDate() {
        return codec.readPicX(record, SPAN_ACCT_REISSUE_DATE);
    }

    /**
     * Stores {@code ACCT-REISSUE-DATE}, space-padding or right-truncating to 10 characters.
     *
     * @param acctReissueDate the date text; may be blank or partial
     * @throws NullPointerException if {@code acctReissueDate} is {@code null}
     */
    public void setAcctReissueDate(String acctReissueDate) {
        writePicX(SPAN_ACCT_REISSUE_DATE, acctReissueDate, "acctReissueDate");
    }

    /**
     * {@code ACCT-CURR-CYC-CREDIT} at scale exactly 2.
     *
     * @return the cycle credit, with {@link BigDecimal#scale()} of exactly {@link #MONETARY_SCALE}
     */
    public BigDecimal getAcctCurrCycCredit() {
        return codec.readMonetary(record, SPAN_ACCT_CURR_CYC_CREDIT);
    }

    /**
     * Stores {@code ACCT-CURR-CYC-CREDIT} at its declared {@code PIC S9(10)V99}.
     *
     * @param acctCurrCycCredit the cycle credit to store, of any scale
     * @throws NullPointerException if {@code acctCurrCycCredit} is {@code null}
     */
    public void setAcctCurrCycCredit(BigDecimal acctCurrCycCredit) {
        writeMonetary(SPAN_ACCT_CURR_CYC_CREDIT, acctCurrCycCredit, "acctCurrCycCredit");
    }

    /**
     * {@code ACCT-CURR-CYC-DEBIT} at scale exactly 2.
     *
     * @return the cycle debit, with {@link BigDecimal#scale()} of exactly {@link #MONETARY_SCALE}
     */
    public BigDecimal getAcctCurrCycDebit() {
        return codec.readMonetary(record, SPAN_ACCT_CURR_CYC_DEBIT);
    }

    /**
     * Stores {@code ACCT-CURR-CYC-DEBIT} at its declared {@code PIC S9(10)V99}.
     *
     * @param acctCurrCycDebit the cycle debit to store, of any scale
     * @throws NullPointerException if {@code acctCurrCycDebit} is {@code null}
     */
    public void setAcctCurrCycDebit(BigDecimal acctCurrCycDebit) {
        writeMonetary(SPAN_ACCT_CURR_CYC_DEBIT, acctCurrCycDebit, "acctCurrCycDebit");
    }

    /**
     * Reproduces {@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} ({@code app/cbl/CBACT04C.cbl:L353}).
     */
    public void zeroAcctCurrCycCredit() {
        writeMonetary(SPAN_ACCT_CURR_CYC_CREDIT, CobolDecimal.monetaryZero(), "acctCurrCycCredit");
    }

    /**
     * Reproduces {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT} ({@code app/cbl/CBACT04C.cbl:L354}).
     */
    public void zeroAcctCurrCycDebit() {
        writeMonetary(SPAN_ACCT_CURR_CYC_DEBIT, CobolDecimal.monetaryZero(), "acctCurrCycDebit");
    }

    /**
     * {@code ACCT-ADDR-ZIP} as stored, untrimmed: 10 characters.
     *
     * @return exactly {@link #ACCT_ADDR_ZIP_LENGTH} characters
     */
    public String getAcctAddrZip() {
        return codec.readPicX(record, SPAN_ACCT_ADDR_ZIP);
    }

    /**
     * Stores {@code ACCT-ADDR-ZIP}, space-padding or right-truncating to 10 characters.
     *
     * @param acctAddrZip the value to store
     * @throws NullPointerException if {@code acctAddrZip} is {@code null}
     */
    public void setAcctAddrZip(String acctAddrZip) {
        writePicX(SPAN_ACCT_ADDR_ZIP, acctAddrZip, "acctAddrZip");
    }

    /**
     * {@code ACCT-GROUP-ID} as stored, untrimmed and right-space-padded to 10 characters.
     *
     * @return exactly {@link #ACCT_GROUP_ID_LENGTH} characters
     */
    public String getAcctGroupId() {
        return codec.readPicX(record, SPAN_ACCT_GROUP_ID);
    }

    /**
     * Stores {@code ACCT-GROUP-ID}, space-padding or right-truncating to 10 characters.
     *
     * @param acctGroupId the group identifier
     * @throws NullPointerException if {@code acctGroupId} is {@code null}
     */
    public void setAcctGroupId(String acctGroupId) {
        writePicX(SPAN_ACCT_GROUP_ID, acctGroupId, "acctGroupId");
    }

    /**
     * The trailing {@code FILLER} as stored: 178 characters.
     *
     * @return exactly {@link #FILLER_LENGTH} characters
     */
    public String getFiller() {
        return raw(SPAN_FILLER);
    }

    /**
     * One-based start of the year within a {@code YYYY-MM-DD} value, as COBOL writes it: {@code 1}.
     */
    public static final int YEAR_START = 1;

    public static final int YEAR_LENGTH = 4;

    /**
     * One-based start of the month within a {@code YYYY-MM-DD} value: {@code 6}, skipping the dash.
     */
    public static final int MONTH_START = 6;

    public static final int MONTH_LENGTH = 2;

    /**
     * One-based start of the day within a {@code YYYY-MM-DD} value: {@code 9}.
     */
    public static final int DAY_START = 9;

    public static final int DAY_LENGTH = 2;

    /**
     * Applies COBOL reference modification to a value: {@code value(oneBasedStart:length)}.
     *
     * @param value the sending value; {@code null} is treated as entirely blank
     * @param oneBasedStart the one-based character position to start at, as written in the COBOL; at least
     *     1
     * @param length the number of characters to take; at least 1
     * @return exactly {@code length} characters, space-padded on the right if {@code value} does not reach
     *     that far
     * @throws IllegalArgumentException if {@code oneBasedStart} is below 1 or {@code length} is below 1
     */
    public static String referenceModify(String value, int oneBasedStart, int length) {
        if (oneBasedStart < 1) {
            throw new IllegalArgumentException("Reference modification starts at position "
                    + oneBasedStart + "; COBOL positions are one-based, so the first character is 1");
        }
        if (length < 1) {
            throw new IllegalArgumentException("Reference modification requests " + length
                    + " character(s); a reference-modified span covers at least 1");
        }
        int from = oneBasedStart - 1;
        if (value == null || from >= value.length()) {
            return " ".repeat(length);
        }
        int to = Math.min(value.length(), from + length);
        String slice = value.substring(from, to);
        if (slice.length() < length) {
            return slice + " ".repeat(length - slice.length());
        }
        return slice;
    }

    /**
     * The year component of {@code ACCT-OPEN-DATE}, reproducing {@code ACCT-OPEN-DATE(1:4)}
     * ({@code app/cbl/COACTUPC.cbl:L3831}).
     *
     * @return 4 characters; spaces where the date is blank
     */
    public String getAcctOpenDateYear() {
        return referenceModify(getAcctOpenDate(), YEAR_START, YEAR_LENGTH);
    }

    /**
     * The month component of {@code ACCT-OPEN-DATE}, reproducing {@code ACCT-OPEN-DATE(6:2)}
     * ({@code app/cbl/COACTUPC.cbl:L3833}).
     *
     * @return 2 characters; spaces where the date is blank
     */
    public String getAcctOpenDateMonth() {
        return referenceModify(getAcctOpenDate(), MONTH_START, MONTH_LENGTH);
    }

    /**
     * The day component of {@code ACCT-OPEN-DATE}, reproducing {@code ACCT-OPEN-DATE(9:2)}
     * ({@code app/cbl/COACTUPC.cbl:L3834}).
     *
     * @return 2 characters; spaces where the date is blank
     */
    public String getAcctOpenDateDay() {
        return referenceModify(getAcctOpenDate(), DAY_START, DAY_LENGTH);
    }

    /**
     * The year component of {@code ACCT-EXPIRAION-DATE}, reproducing {@code ACCT-EXPIRAION-DATE(1:4)}
     * ({@code app/cbl/COACTUPC.cbl:L3838}).
     *
     * @return 4 characters; spaces where the date is blank
     */
    public String getAcctExpiraionDateYear() {
        return referenceModify(getAcctExpiraionDate(), YEAR_START, YEAR_LENGTH);
    }

    /**
     * The month component of {@code ACCT-EXPIRAION-DATE}, reproducing {@code ACCT-EXPIRAION-DATE(6:2)}
     * ({@code app/cbl/COACTUPC.cbl:L3839}).
     *
     * @return 2 characters; spaces where the date is blank
     */
    public String getAcctExpiraionDateMonth() {
        return referenceModify(getAcctExpiraionDate(), MONTH_START, MONTH_LENGTH);
    }

    /**
     * The day component of {@code ACCT-EXPIRAION-DATE}, reproducing {@code ACCT-EXPIRAION-DATE(9:2)}
     * ({@code app/cbl/COACTUPC.cbl:L3840}).
     *
     * @return 2 characters; spaces where the date is blank
     */
    public String getAcctExpiraionDateDay() {
        return referenceModify(getAcctExpiraionDate(), DAY_START, DAY_LENGTH);
    }

    /**
     * The year component of {@code ACCT-REISSUE-DATE}, reproducing {@code ACCT-REISSUE-DATE(1:4)}
     * ({@code app/cbl/COACTUPC.cbl:L3843}).
     *
     * @return 4 characters; spaces where the date is blank
     */
    public String getAcctReissueDateYear() {
        return referenceModify(getAcctReissueDate(), YEAR_START, YEAR_LENGTH);
    }

    /**
     * The month component of {@code ACCT-REISSUE-DATE}, reproducing {@code ACCT-REISSUE-DATE(6:2)}.
     *
     * @return 2 characters; spaces where the date is blank
     */
    public String getAcctReissueDateMonth() {
        return referenceModify(getAcctReissueDate(), MONTH_START, MONTH_LENGTH);
    }

    /**
     * The day component of {@code ACCT-REISSUE-DATE}, reproducing {@code ACCT-REISSUE-DATE(9:2)}.
     *
     * @return 2 characters; spaces where the date is blank
     */
    public String getAcctReissueDateDay() {
        return referenceModify(getAcctReissueDate(), DAY_START, DAY_LENGTH);
    }

    /**
     * This record's primary key as its stored 11-character image, zero-filled.
     *
     * @return exactly {@link #KEY_LENGTH} characters
     */
    public String keyImage() {
        return raw(SPAN_ACCT_ID);
    }

    /**
     * The 11-character zero-filled key image for an account identifier, without needing a record.
     *
     * <p>Providing it here keeps the {@code PIC 9(11)} move rule - zero-fill on the left, truncate on the
     * left - in one place rather than letting each caller pad a string itself.
     *
     * @param acctId the account identifier; must not be negative, as {@code PIC 9} has no sign position
     * @param charset the code page the key will be written in; required rather than assumed, because the
     *     digits become bytes in a specific code page
     * @return exactly {@link #KEY_LENGTH} characters
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code acctId} is negative, or {@code charset} cannot encode
     *     digits as single bytes
     */
    public static String keyImage(long acctId, Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to build a key image: the digits become "
                + "bytes in a specific code page, which is never assumed");
        return new FixedWidthCodec(charset).movePic9(acctId, KEY_LENGTH);
    }

    private void writePicX(FieldSpan field, String value, String parameter) {
        Objects.requireNonNull(value, () -> parameter + " must not be null; " + field.name()
                + " is PIC X(" + field.length() + ") and has no null representation - store an empty "
                + "or blank string to blank the field");
        codec.writePicX(record, field, value);
    }

    private void writeMonetary(FieldSpan field, BigDecimal value, String parameter) {
        Objects.requireNonNull(value, () -> parameter + " must not be null; " + field.name()
                + " is PIC S9(" + MONETARY_INTEGER_DIGITS + ")V" + "9".repeat(MONETARY_SCALE)
                + " and has no null representation - store zero to clear the field");
        codec.writeMonetary(record, field,
                CobolDecimal.storeAtPicture(value, MONETARY_INTEGER_DIGITS, MONETARY_SCALE));
    }

    /**
     * Compares two account records by their complete stored bytes.
     *
     * @param other the object to compare with
     * @return {@code true} if {@code other} is an {@code AccountRecord} with identical stored bytes
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccountRecord that)) {
            return false;
        }
        return Arrays.equals(record.toByteArray(), that.record.toByteArray());
    }

    /**
     * A hash consistent with {@link #equals(Object)}, derived from the stored bytes.
     *
     * @return the hash of the 300-byte image
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(record.toByteArray());
    }

    /**
     * A diagnostic rendering, field by field, showing each span's stored characters.
     *
     * @return a single-line description of every declared field
     */
    @Override
    public String toString() {
        String filler = getFiller();
        String fillerSummary = filler.isBlank()
                ? FILLER_LENGTH + " spaces"
                : FILLER_LENGTH + " bytes, not blank";
        return "AccountRecord["
                + ACCT_ID_NAME + "=" + DiagnosticText.masked(rawAcctId())
                + ", " + ACCT_ACTIVE_STATUS_NAME + "='"
                + DiagnosticText.singleLine(rawAcctActiveStatus()) + "'"
                + ", " + ACCT_CURR_BAL_NAME + "=" + DiagnosticText.omitted(rawAcctCurrBal())
                + ", " + ACCT_CREDIT_LIMIT_NAME + "=" + DiagnosticText.omitted(rawAcctCreditLimit())
                + ", " + ACCT_CASH_CREDIT_LIMIT_NAME + "="
                + DiagnosticText.omitted(rawAcctCashCreditLimit())
                + ", " + ACCT_OPEN_DATE_NAME + "=" + DiagnosticText.omitted(rawAcctOpenDate())
                + ", " + ACCT_EXPIRAION_DATE_NAME + "="
                + DiagnosticText.omitted(rawAcctExpiraionDate())
                + ", " + ACCT_REISSUE_DATE_NAME + "=" + DiagnosticText.omitted(rawAcctReissueDate())
                + ", " + ACCT_CURR_CYC_CREDIT_NAME + "="
                + DiagnosticText.omitted(rawAcctCurrCycCredit())
                + ", " + ACCT_CURR_CYC_DEBIT_NAME + "="
                + DiagnosticText.omitted(rawAcctCurrCycDebit())
                + ", " + ACCT_ADDR_ZIP_NAME + "=" + DiagnosticText.omitted(rawAcctAddrZip())
                + ", " + ACCT_GROUP_ID_NAME + "='" + DiagnosticText.singleLine(rawAcctGroupId()) + "'"
                + ", FILLER=" + fillerSummary
                + ", charset=" + charset().name()
                + "]";
    }
}

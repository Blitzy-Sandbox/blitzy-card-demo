package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * The transaction category balance record: the single Java type for {@code app/cpy/CVTRA01Y.cpy}, whose own
 * header comment reads "Data-structure for transaction category balance (RECLN = 50)".
 *
 * <p>A signed {@code PIC S9(p)V99} field occupies exactly {@code p + 2} bytes - 11 here - because the sign
 * is overpunched into the trailing byte and consumes no byte of its own.
 */
public final class TranCatBalRecord {
    // Stated as constants so a diagnostic, a parity fingerprint or a repository log line can name the
    // copybook and the record group without a caller writing either string out again.

    /**
     * The copybook this type transcribes: {@code app/cpy/CVTRA01Y.cpy}.
     */
    public static final String COPYBOOK = "CVTRA01Y";

    /**
     * The COBOL group name of the record itself, verbatim: {@code TRAN-CAT-BAL-RECORD}.
     */
    public static final String RECORD_NAME = "TRAN-CAT-BAL-RECORD";

    /**
     * The dataset this record is stored in, as the {@code SELECT ... ASSIGN TO} clauses of both consumers
     * name it: {@code TCATBALF} at {@code app/cbl/CBTRN02C.cbl:57} and {@code app/cbl/CBACT04C.cbl:28}.
     */
    public static final String DD_NAME = "TCATBALF";

    // The parity differ compares field by field BY NAME, so these strings are part of the migration
    // contract and are never tidied, expanded or disambiguated - TRAN-CAT-KEY collides with a 6-byte group
    // of the same name in CVTRA04Y and stays as it is.

    /**
     * {@code 05 TRAN-CAT-KEY} - the composite key group, 17 bytes.
     */
    public static final String TRAN_CAT_KEY_NAME = "TRAN-CAT-KEY";

    /**
     * {@code 10 TRANCAT-ACCT-ID PIC 9(11)}.
     */
    public static final String TRANCAT_ACCT_ID_NAME = "TRANCAT-ACCT-ID";

    /**
     * {@code 10 TRANCAT-TYPE-CD PIC X(02)}.
     */
    public static final String TRANCAT_TYPE_CD_NAME = "TRANCAT-TYPE-CD";

    /**
     * {@code 10 TRANCAT-CD PIC 9(04)}.
     */
    public static final String TRANCAT_CD_NAME = "TRANCAT-CD";

    /**
     * {@code 05 TRAN-CAT-BAL PIC S9(09)V99}.
     */
    public static final String TRAN_CAT_BAL_NAME = "TRAN-CAT-BAL";

    public static final int RECORD_LENGTH = 50;

    /**
     * {@code TRAN-CAT-KEY} begins the record, at 1-based position 1.
     */
    public static final int TRAN_CAT_KEY_OFFSET = 0;

    /**
     * {@code TRAN-CAT-KEY} is 17 bytes: {@code 9(11) + X(02) + 9(04)}.
     */
    public static final int TRAN_CAT_KEY_LENGTH = 17;

    /**
     * {@code TRANCAT-ACCT-ID}: 1-based 1-11, so 0-based 0.
     */
    public static final int TRANCAT_ACCT_ID_OFFSET = 0;

    /**
     * {@code TRANCAT-ACCT-ID PIC 9(11)}: 11 digits, one byte each.
     */
    public static final int TRANCAT_ACCT_ID_LENGTH = 11;

    /**
     * {@code TRANCAT-TYPE-CD}: 1-based 12-13, so 0-based 11.
     */
    public static final int TRANCAT_TYPE_CD_OFFSET = 11;

    /**
     * {@code TRANCAT-TYPE-CD PIC X(02)}: 2 characters.
     */
    public static final int TRANCAT_TYPE_CD_LENGTH = 2;

    /**
     * {@code TRANCAT-CD}: 1-based 14-17, so 0-based 13.
     */
    public static final int TRANCAT_CD_OFFSET = 13;

    /**
     * {@code TRANCAT-CD PIC 9(04)}: 4 digits.
     */
    public static final int TRANCAT_CD_LENGTH = 4;

    /**
     * {@code TRAN-CAT-BAL}: 1-based 18-28, so 0-based 17 - immediately after the 17-byte key.
     */
    public static final int TRAN_CAT_BAL_OFFSET = 17;

    /**
     * {@code TRAN-CAT-BAL PIC S9(09)V99} occupies 11 bytes: {@code p + s} with {@code p} of 9 and {@code s}
     * of 2.
     */
    public static final int TRAN_CAT_BAL_LENGTH = 11;

    /**
     * {@code p} in {@code PIC S9(09)V99}: 9 digit positions left of the implied decimal point.
     */
    public static final int TRAN_CAT_BAL_INTEGER_DIGITS = 9;

    /**
     * {@code s} in {@code PIC S9(09)V99}: 2 digit positions right of the implied decimal point.
     */
    public static final int TRAN_CAT_BAL_SCALE = CobolDecimal.MONETARY_SCALE;

    public static final int FILLER_OFFSET = 28;

    /**
     * {@code FILLER PIC X(22)}: 22 reserved bytes, and a first-class span rather than an inferred gap.
     */
    public static final int FILLER_LENGTH = 22;

    /**
     * {@code 10 TRANCAT-ACCT-ID PIC 9(11)} - unsigned zoned {@code DISPLAY}, right justified and
     * zero-filled on the left.
     */
    public static final FieldSpan TRANCAT_ACCT_ID_SPAN = FieldSpan.unsignedNumeric(
            TRANCAT_ACCT_ID_NAME, TRANCAT_ACCT_ID_OFFSET, TRANCAT_ACCT_ID_LENGTH);

    /**
     * {@code 10 TRANCAT-TYPE-CD PIC X(02)} - alphanumeric, left justified and space-padded, despite every
     * value in the fixture looking numeric ({@code 01}).
     */
    public static final FieldSpan TRANCAT_TYPE_CD_SPAN = FieldSpan.alphanumeric(
            TRANCAT_TYPE_CD_NAME, TRANCAT_TYPE_CD_OFFSET, TRANCAT_TYPE_CD_LENGTH);

    /**
     * {@code 10 TRANCAT-CD PIC 9(04)} - unsigned zoned {@code DISPLAY}.
     */
    public static final FieldSpan TRANCAT_CD_SPAN = FieldSpan.unsignedNumeric(
            TRANCAT_CD_NAME, TRANCAT_CD_OFFSET, TRANCAT_CD_LENGTH);

    /**
     * {@code 05 TRAN-CAT-BAL PIC S9(09)V99} - signed zoned {@code DISPLAY}, 11 bytes.
     */
    public static final FieldSpan TRAN_CAT_BAL_SPAN = FieldSpan.signedScaled(
            TRAN_CAT_BAL_NAME, TRAN_CAT_BAL_OFFSET, TRAN_CAT_BAL_INTEGER_DIGITS, TRAN_CAT_BAL_SCALE);

    /**
     * {@code 05 FILLER PIC X(22)} - the trailing reserved span, declared explicitly and carrying no
     * {@code VALUE} literal, so a freshly allocated record space-fills it.
     */
    public static final FieldSpan FILLER_SPAN = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    /**
     * The complete 50-byte layout, in copybook declaration order.
     */
    public static final RecordLayout LAYOUT = RecordLayout.of(RECORD_LENGTH,
            TRANCAT_ACCT_ID_SPAN,
            TRANCAT_TYPE_CD_SPAN,
            TRANCAT_CD_SPAN,
            TRAN_CAT_BAL_SPAN,
            FILLER_SPAN);

    /**
     * The 17-byte composite key as a layout of its own, over the same three descriptors that
     * {@link #LAYOUT} uses.
     */
    public static final RecordLayout TRAN_CAT_KEY_LAYOUT = RecordLayout.of(TRAN_CAT_KEY_LENGTH,
            TRANCAT_ACCT_ID_SPAN,
            TRANCAT_TYPE_CD_SPAN,
            TRANCAT_CD_SPAN);

    static {
        verifyGeometry();
    }

    /**
     * Proves this record's geometry, and is called once from the static initialiser.
     *
     * @return {@link #RECORD_LENGTH}, so a test can assert on the verified width in one expression
     * @throws IllegalStateException if any declared constant contradicts the layout
     */
    public static int verifyGeometry() {
        verifyRecordLength(RECORD_LENGTH, LAYOUT.recordLength());
        verifyKeyGeometry(TRAN_CAT_KEY_LENGTH, TRAN_CAT_BAL_OFFSET);
        return RECORD_LENGTH;
    }

    /**
     * Confirms that the declared record width and the width the layout actually accounts for agree.
     *
     * @param declaredRecordLength the width the copybook declares, normally {@link #RECORD_LENGTH}
     * @param layoutRecordLength the width the layout accounts for, normally {@code LAYOUT.recordLength()}
     * @return {@code declaredRecordLength}
     * @throws IllegalStateException if the two differ
     */
    public static int verifyRecordLength(int declaredRecordLength, int layoutRecordLength) {
        if (declaredRecordLength != layoutRecordLength) {
            throw new IllegalStateException("CVTRA01Y declares RECLN = " + RECORD_LENGTH
                    + " (17-byte TRAN-CAT-KEY + 11-byte TRAN-CAT-BAL + 22-byte FILLER), but the "
                    + "declared record length " + declaredRecordLength + " and the layout's "
                    + layoutRecordLength + " disagree. A dropped FILLER leaves the layout short; a "
                    + "sign byte reserved for TRAN-CAT-BAL PIC S9(09)V99 leaves it one byte long, "
                    + "because the sign is overpunched into the trailing byte and occupies none of "
                    + "its own");
        }
        return declaredRecordLength;
    }

    /**
     * Confirms that {@code TRAN-CAT-BAL} begins exactly where the 17-byte {@code TRAN-CAT-KEY} ends - the
     * single most valuable assertion in this file.
     *
     * @param declaredKeyLength the key width to check, normally {@link #TRAN_CAT_KEY_LENGTH}
     * @param declaredBalanceOffset the balance's 0-based offset, normally {@link #TRAN_CAT_BAL_OFFSET}
     * @return {@code declaredKeyLength}
     * @throws IllegalStateException if the key does not end exactly where the balance begins
     */
    public static int verifyKeyGeometry(int declaredKeyLength, int declaredBalanceOffset) {
        if (declaredKeyLength != declaredBalanceOffset) {
            throw new IllegalStateException("CVTRA01Y's TRAN-CAT-KEY is " + TRAN_CAT_KEY_LENGTH
                    + " bytes (TRANCAT-ACCT-ID 9(11) + TRANCAT-TYPE-CD X(02) + TRANCAT-CD 9(04)) and "
                    + "TRAN-CAT-BAL therefore begins at 0-based " + TRAN_CAT_BAL_OFFSET
                    + ", but a key length of " + declaredKeyLength + " was checked against a balance "
                    + "offset of " + declaredBalanceOffset + ". BEWARE THE CVTRA02Y TRAP: "
                    + "DIS-GROUP-RECORD also totals 50 bytes, but its DIS-GROUP-KEY is 16 bytes and "
                    + "its DIS-INT-RATE S9(04)V99 is 6 bytes at 0-based 16, so a total-width check "
                    + "cannot distinguish the two layouts and a one-byte shift silently corrupts every "
                    + "balance");
        }
        return declaredKeyLength;
    }

    /**
     * {@code 05 TRAN-CAT-KEY} - the 17-byte composite key of {@code TCATBALF}, as an immutable value.
     *
     * @param trancatAcctId {@code TRANCAT-ACCT-ID PIC 9(11)}; never negative, since an unsigned
     *     {@code PIC 9} picture has no sign position
     * @param trancatTypeCd {@code TRANCAT-TYPE-CD PIC X(02)}; alphanumeric, so {@code "01"} is two
     *     significant characters and never the number 1
     * @param trancatCd {@code TRANCAT-CD PIC 9(04)}; never negative
     */
    public record TranCatKey(long trancatAcctId, String trancatTypeCd, int trancatCd) {
        /**
         * Validates only that the alphanumeric component is present.
         */
        public TranCatKey {
            Objects.requireNonNull(trancatTypeCd, "TRANCAT-TYPE-CD PIC X(02) is required; move SPACES "
                    + "explicitly to blank it, as COBOL would");
        }

        /**
         * Renders this key as its raw 17-character image: 11 zero-filled digits, then the two-character
         * type code space-padded on the right, then 4 zero-filled digits.
         *
         * <p>The numeric components go through the {@code PIC 9} move rule - zero-filled and truncated on
         * the left - and the alphanumeric component through the {@code PIC X} rule - space-padded and
         * truncated on the right - so each direction is the deliberate one for its picture.
         *
         * @param charset the code page of the key's bytes, stated explicitly by the caller and never
         *     derived from the platform
         * @return exactly {@link #TRAN_CAT_KEY_LENGTH} characters
         * @throws NullPointerException if {@code charset} is {@code null}
         * @throws IllegalArgumentException if {@code charset} is not single-byte for the digits and space,
         *     or if either numeric component is negative
         */
        public String image(Charset charset) {
            return keyArea(charset).readString(TRAN_CAT_KEY_OFFSET, TRAN_CAT_KEY_LENGTH);
        }

        /**
         * Renders this key as its raw 17 bytes, for a repository that addresses the KSDS by key image.
         *
         * @param charset the code page of the key's bytes, stated explicitly
         * @return exactly {@link #TRAN_CAT_KEY_LENGTH} bytes
         * @throws NullPointerException if {@code charset} is {@code null}
         * @throws IllegalArgumentException if {@code charset} is not single-byte for the digits and space,
         *     or if either numeric component is negative
         */
        public byte[] toByteArray(Charset charset) {
            return keyArea(charset).toByteArray();
        }

        private FixedWidthRecord keyArea(Charset charset) {
            FixedWidthCodec codec = new FixedWidthCodec(charset);
            FixedWidthRecord area = codec.newRecord(TRAN_CAT_KEY_LAYOUT);
            codec.writePic9(area, TRANCAT_ACCT_ID_SPAN, trancatAcctId);
            codec.writePicX(area, TRANCAT_TYPE_CD_SPAN, trancatTypeCd);
            codec.writePic9(area, TRANCAT_CD_SPAN, trancatCd);
            return area;
        }

        public static TranCatKey decode(byte[] keyBytes, Charset charset) {
            FixedWidthCodec codec = new FixedWidthCodec(charset);
            FixedWidthRecord area = codec.wrap(keyBytes, TRAN_CAT_KEY_LAYOUT);
            return new TranCatKey(
                    codec.readPic9(area, TRANCAT_ACCT_ID_SPAN),
                    codec.readPicX(area, TRANCAT_TYPE_CD_SPAN),
                    codec.readPic9AsInt(area, TRANCAT_CD_SPAN));
        }

        /**
         * Decodes a stored 17-character key image, for a caller that has read the key as text - a parity
         * case fixture, or the {@code app/data/ASCII} datasets.
         *
         * @param keyImage exactly {@link #TRAN_CAT_KEY_LENGTH} characters
         * @param charset the code page the characters are encoded under, stated explicitly
         * @return the key the image denotes, with its type code left untrimmed
         * @throws NullPointerException if either argument is {@code null}
         * @throws IllegalArgumentException if the image does not encode to exactly 17 bytes, or a numeric
         *     component does not hold digits
         */
        public static TranCatKey decode(String keyImage, Charset charset) {
            Objects.requireNonNull(keyImage, "A 17-character TRAN-CAT-KEY image is required");
            Objects.requireNonNull(charset, "A charset is required to decode a key image: fixed-width "
                    + "mainframe data is bytes in a specific code page, never a platform default");
            return decode(FixedWidthRecord.encodeText(keyImage, charset, "a TRAN-CAT-KEY image"),
                    charset);
        }
    }

    // The 50-byte record area is the SINGLE SOURCE OF TRUTH, exactly as a COBOL record area is: every typed
    // accessor decodes from it and every mutator encodes into it.

    private final FixedWidthRecord area;

    private final FixedWidthCodec codec;

    private TranCatBalRecord(FixedWidthRecord area, FixedWidthCodec codec) {
        this.area = area;
        this.codec = codec;
    }

    /**
     * Allocates a record and initialises it, for the create-from-nothing case.
     *
     * @param charset the code page of the record's bytes, stated explicitly by the caller and never derived
     *     from the platform
     * @return a new, initialised 50-byte record
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not single-byte for the digits, the sign
     *     overpunch characters and the space
     */
    public static TranCatBalRecord newInstance(Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        TranCatBalRecord record = new TranCatBalRecord(codec.newRecord(LAYOUT), codec);
        return record.initialize();
    }

    /**
     * Decodes a stored 50-byte record, taking a defensive copy of its bytes.
     *
     * @param row exactly {@link #RECORD_LENGTH} bytes
     * @param charset the code page of those bytes, stated explicitly
     * @return a record over a copy of {@code row}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code row} is not exactly 50 bytes long, or {@code charset} is
     *     not single-byte for the required characters
     */
    public static TranCatBalRecord decode(byte[] row, Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return new TranCatBalRecord(codec.wrap(row, LAYOUT), codec);
    }

    /**
     * Decodes a stored 50-character record image, for a caller that has read the row as text - the
     * {@code app/data/ASCII} datasets and the parity case fixtures both arrive that way.
     *
     * @param row exactly {@link #RECORD_LENGTH} characters
     * @param charset the code page the characters are encoded under, stated explicitly
     * @return a record over the encoded bytes
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if the image does not encode to exactly 50 bytes
     */
    public static TranCatBalRecord decode(String row, Charset charset) {
        Objects.requireNonNull(row, "A 50-character TRAN-CAT-BAL-RECORD image is required");
        Objects.requireNonNull(charset, "A charset is required to decode a record image: fixed-width "
                + "mainframe data is bytes in a specific code page, never a platform default");
        return decode(FixedWidthRecord.encodeText(row, charset, "a TRAN-CAT-BAL-RECORD image"),
                charset);
    }

    /**
     * The code page of this record's bytes, as supplied at construction.
     *
     * @return the charset, never {@code null} and never a platform default
     */
    public Charset charset() {
        return codec.charset();
    }

    /**
     * This record's width in bytes, which is always {@link #RECORD_LENGTH}.
     *
     * @return {@code 50}
     */
    public int recordLength() {
        return area.recordLength();
    }

    /**
     * Serialises this record as exactly 50 bytes, {@code FILLER} included and unchanged.
     *
     * @return a fresh array of exactly {@link #RECORD_LENGTH} bytes
     */
    public byte[] encode() {
        return area.toByteArray();
    }

    /**
     * This record's raw 50-character image, untrimmed and undecoded.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:193} is {@code DISPLAY TRAN-CAT-BAL-RECORD} - the whole group, not a
     * field of it - so the {@code SYSOUT} fingerprint of the interest calculator is exactly this string.
     *
     * @return exactly {@link #RECORD_LENGTH} characters
     */
    public String rawImage() {
        return area.readString(0, RECORD_LENGTH);
    }

    /**
     * This record's four named fields as raw, untrimmed images, keyed by their verbatim COBOL names and in
     * copybook declaration order.
     *
     * <p>{@code FILLER} is absent, because {@code FILLER} is not a referable COBOL name and so cannot be a
     * map key; it is separately available through {@link #fillerImage()} and is in any case proven present
     * by {@link #LAYOUT}'s width self-check.
     *
     * @return an unmodifiable, insertion-ordered map of 4 entries: {@code TRANCAT-ACCT-ID},
     *     {@code TRANCAT-TYPE-CD}, {@code TRANCAT-CD} and {@code TRAN-CAT-BAL}
     */
    public Map<String, String> fieldImages() {
        return Collections.unmodifiableMap(codec.deserialise(LAYOUT, area.toByteArray()));
    }

    /**
     * This record's {@code TRAN-CAT-KEY} as a typed value.
     *
     * @return the 17-byte key's three components, with {@code TRANCAT-TYPE-CD} untrimmed
     * @throws IllegalArgumentException if either numeric component of the key does not hold digits, which
     *     means the record's bytes and this layout disagree
     */
    public TranCatKey tranCatKey() {
        return new TranCatKey(trancatAcctId(), trancatTypeCd(), trancatCd());
    }

    /**
     * This record's {@code TRAN-CAT-KEY} as its raw 17-character image, read straight from the record area
     * rather than rebuilt from decoded values.
     *
     * @return exactly {@link #TRAN_CAT_KEY_LENGTH} characters
     */
    public String tranCatKeyImage() {
        return area.readString(TRAN_CAT_KEY_OFFSET, TRAN_CAT_KEY_LENGTH);
    }

    /**
     * This record's {@code TRAN-CAT-KEY} as its raw 17 bytes, for a repository addressing the KSDS by key
     * image.
     *
     * @return a fresh array of exactly {@link #TRAN_CAT_KEY_LENGTH} bytes
     */
    public byte[] tranCatKeyBytes() {
        return area.readBytes(TRAN_CAT_KEY_OFFSET, TRAN_CAT_KEY_LENGTH);
    }

    /**
     * Writes all three key components, reproducing the three consecutive {@code MOVE} statements of
     * {@code app/cbl/CBTRN02C.cbl:505-507} in one call.
     *
     * @param key the key to store
     * @return this record, so a create path can chain the moves as the COBOL sequences them
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IllegalArgumentException if a numeric component of {@code key} is negative
     */
    public TranCatBalRecord tranCatKey(TranCatKey key) {
        Objects.requireNonNull(key, "A TRAN-CAT-KEY is required to write the key group");
        trancatAcctId(key.trancatAcctId());
        trancatTypeCd(key.trancatTypeCd());
        trancatCd(key.trancatCd());
        return this;
    }

    /**
     * {@code TRANCAT-ACCT-ID PIC 9(11)} as a number.
     *
     * @return the account identifier the 11 digits denote
     * @throws IllegalArgumentException if the span does not hold digits
     */
    public long trancatAcctId() {
        return codec.readPic9(area, TRANCAT_ACCT_ID_SPAN);
    }

    /**
     * {@code TRANCAT-ACCT-ID} as its raw 11-character zero-filled image.
     *
     * @return exactly {@link #TRANCAT_ACCT_ID_LENGTH} characters
     */
    public String trancatAcctIdImage() {
        return codec.readPicX(area, TRANCAT_ACCT_ID_SPAN);
    }

    /**
     * Stores {@code TRANCAT-ACCT-ID}, reproducing {@code MOVE XREF-ACCT-ID TO TRANCAT-ACCT-ID}
     * [{@code app/cbl/CBTRN02C.cbl:505}].
     *
     * <p>No {@code ON SIZE ERROR} phrase exists anywhere in this codebase, so that loss is silent here
     * exactly as it is in COBOL.
     *
     * @param value the account identifier; must not be negative, since {@code PIC 9} has no sign position
     * @return this record, for chaining
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public TranCatBalRecord trancatAcctId(long value) {
        codec.writePic9(area, TRANCAT_ACCT_ID_SPAN, value);
        return this;
    }

    /**
     * {@code TRANCAT-TYPE-CD PIC X(02)}, untrimmed.
     *
     * @return exactly {@link #TRANCAT_TYPE_CD_LENGTH} characters
     */
    public String trancatTypeCd() {
        return codec.readPicX(area, TRANCAT_TYPE_CD_SPAN);
    }

    /**
     * Stores {@code TRANCAT-TYPE-CD}, reproducing {@code MOVE DALYTRAN-TYPE-CD TO TRANCAT-TYPE-CD}
     * [{@code app/cbl/CBTRN02C.cbl:506}].
     *
     * <p>The {@code PIC X} move rule applies, which is the mirror image of the numeric one: the value is
     * space-padded on the right if short and truncated on the right if long, so {@code "0"} becomes
     * {@code "0 "} and {@code "012"} becomes {@code "01"}.
     *
     * @param value the two-character type code; move an empty string or spaces to blank it
     * @return this record, for chaining
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public TranCatBalRecord trancatTypeCd(String value) {
        codec.writePicX(area, TRANCAT_TYPE_CD_SPAN, value);
        return this;
    }

    /**
     * {@code TRANCAT-CD PIC 9(04)} as a number.
     *
     * @return the transaction category code the 4 digits denote
     * @throws IllegalArgumentException if the span does not hold digits
     */
    public int trancatCd() {
        return codec.readPic9AsInt(area, TRANCAT_CD_SPAN);
    }

    /**
     * {@code TRANCAT-CD} as its raw 4-character zero-filled image, which is what
     * {@code MOVE TRANCAT-CD TO FD-DIS-TRAN-CAT-CD} [{@code app/cbl/CBACT04C.cbl:211}] transfers into the
     * {@code DISCGRP} key.
     *
     * @return exactly {@link #TRANCAT_CD_LENGTH} characters
     */
    public String trancatCdImage() {
        return codec.readPicX(area, TRANCAT_CD_SPAN);
    }

    /**
     * Stores {@code TRANCAT-CD}, reproducing {@code MOVE DALYTRAN-CAT-CD TO TRANCAT-CD}
     * [{@code app/cbl/CBTRN02C.cbl:507}].
     *
     * @param value the category code; must not be negative
     * @return this record, for chaining
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public TranCatBalRecord trancatCd(int value) {
        codec.writePic9(area, TRANCAT_CD_SPAN, value);
        return this;
    }

    /**
     * {@code TRAN-CAT-BAL PIC S9(09)V99} as a {@link BigDecimal} of scale exactly
     * {@link #TRAN_CAT_BAL_SCALE}.
     *
     * @return the balance, at scale 2
     * @throws IllegalArgumentException if the span does not hold a valid signed zoned image
     */
    public BigDecimal tranCatBal() {
        return codec.readSignedScaled(area, TRAN_CAT_BAL_SPAN, TRAN_CAT_BAL_SCALE);
    }

    /**
     * {@code TRAN-CAT-BAL} as its raw 11-character image, sign overpunch character included and undecoded -
     * the form the parity differ compares and the form {@code app/cbl/CBACT04C.cbl:193} displays as part of
     * the whole record.
     *
     * @return exactly {@link #TRAN_CAT_BAL_LENGTH} characters
     */
    public String tranCatBalImage() {
        return area.readSpan(TRAN_CAT_BAL_SPAN);
    }

    /**
     * Whether {@code TRAN-CAT-BAL} is zero, decided by sign rather than by equality.
     *
     * @return {@code true} when the balance is zero, whatever its stored sign zone
     * @throws IllegalArgumentException if the span does not hold a valid signed zoned image
     */
    public boolean tranCatBalIsZero() {
        return tranCatBal().signum() == 0;
    }

    /**
     * Stores {@code TRAN-CAT-BAL}, applying the receiving field's full {@code PICTURE} discipline.
     *
     * @param value the balance to store
     * @return this record, for chaining
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public TranCatBalRecord tranCatBal(BigDecimal value) {
        BigDecimal stored = CobolDecimal.storeAtPicture(value, TRAN_CAT_BAL_INTEGER_DIGITS,
                TRAN_CAT_BAL_SCALE);
        codec.writeSignedScaled(area, TRAN_CAT_BAL_SPAN, stored, TRAN_CAT_BAL_SCALE);
        return this;
    }

    /**
     * Adds an amount to {@code TRAN-CAT-BAL}, reproducing {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} - a
     * statement that appears twice, at both ends of the poster's branch: {@code app/cbl/CBTRN02C.cbl:508},
     * the create path.
     *
     * @param addend the amount to add, typically {@code DALYTRAN-AMT}
     * @return this record, for chaining
     * @throws NullPointerException if {@code addend} is {@code null}
     * @throws IllegalArgumentException if the stored balance is not a valid signed zoned image
     */
    public TranCatBalRecord addToTranCatBal(BigDecimal addend) {
        Objects.requireNonNull(addend, "An addend is required for ADD ... TO TRAN-CAT-BAL");
        return tranCatBal(CobolDecimal.add(tranCatBal(), addend, TRAN_CAT_BAL_SCALE));
    }

    /**
     * The trailing {@code FILLER}'s raw 22-character content, exactly as stored.
     *
     * <p>Exposed for verification, never for modification: {@code FILLER} is unnamed in COBOL and no
     * statement can reference it, so this class offers no way to set it.
     *
     * @return exactly {@link #FILLER_LENGTH} characters
     */
    public String fillerImage() {
        return area.readSpan(FILLER_SPAN);
    }

    /**
     * The trailing {@code FILLER}'s raw 22 bytes, as a copy.
     *
     * @return a fresh array of exactly {@link #FILLER_LENGTH} bytes
     */
    public byte[] fillerBytes() {
        return area.readSpanBytes(FILLER_SPAN);
    }

    /**
     * Reproduces {@code INITIALIZE TRAN-CAT-BAL-RECORD} [{@code app/cbl/CBTRN02C.cbl:504}] exactly,
     * including its treatment of {@code FILLER}.
     *
     * @return this record, so the create path can chain the {@code MOVE} statements that follow
     */
    public TranCatBalRecord initialize() {
        codec.writePic9(area, TRANCAT_ACCT_ID_SPAN, 0L);
        codec.writePicX(area, TRANCAT_TYPE_CD_SPAN, "");
        codec.writePic9(area, TRANCAT_CD_SPAN, 0L);
        codec.writeSignedScaled(area, TRAN_CAT_BAL_SPAN, CobolDecimal.zero(TRAN_CAT_BAL_SCALE),
                TRAN_CAT_BAL_SCALE);
        return this;
    }

    /**
     * Whether another object is a {@code TranCatBalRecord} holding the same 50 bytes under the same code
     * page.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a record with identical bytes and charset
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TranCatBalRecord that)) {
            return false;
        }
        return charset().equals(that.charset()) && Arrays.equals(encode(), that.encode());
    }

    /**
     * A hash consistent with {@link #equals(Object)}, over the record's bytes and its code page.
     *
     * @return the hash of the 50 bytes combined with the charset's
     */
    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(encode()) + charset().hashCode();
    }

    /**
     * A deterministic, single-line rendering built from the raw field images rather than from decoded
     * values, so that it is safe to call on a record whose bytes do not decode - which is precisely when a
     * diagnostic is wanted.
     *
     * <p>Rendering both put an account holder's financial position, attributable to that account, into any
     * log line or assertion failure that touched a {@code TCATBALF} row - and nothing in {@code CBACT04C}
     * or {@code CBTRN02C} requires it, because COBOL has no {@code toString}.
     *
     * @return for example TRAN-CAT-BAL-RECORD[TRANCAT-ACCT-ID=*******0001, TRANCAT-TYPE-CD=01,
     *     TRANCAT-CD=0001, TRAN-CAT-BAL=&lt;omitted&gt;, FILLER=[blank]]
     */
    @Override
    public String toString() {
        return RECORD_NAME
                + "[" + TRANCAT_ACCT_ID_NAME + "="
                + SensitiveDiagnostics.maskIdentifier(area.readSpan(TRANCAT_ACCT_ID_SPAN))
                + ", " + TRANCAT_TYPE_CD_NAME + "="
                + SensitiveDiagnostics.plain(area.readSpan(TRANCAT_TYPE_CD_SPAN))
                + ", " + TRANCAT_CD_NAME + "="
                + SensitiveDiagnostics.plain(area.readSpan(TRANCAT_CD_SPAN))
                + ", " + TRAN_CAT_BAL_NAME + "=" + DiagnosticText.omitted()
                + ", FILLER=" + SensitiveDiagnostics.describeText(area.readSpan(FILLER_SPAN))
                + "]";
    }
}

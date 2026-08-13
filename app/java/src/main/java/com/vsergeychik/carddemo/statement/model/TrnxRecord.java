package com.vsergeychik.carddemo.statement.model;

import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The 350-byte {@code TRNX-RECORD} of {@code app/cpy/COSTM01.CPY}: the reshaped transaction layout that the
 * statement job reads, keyed by card number and transaction id.
 *
 * <p>Every offset below was re-derived by summation from the copybook and totals exactly 350.
 */
public final class TrnxRecord {
    /**
     * The declared width of {@code TRNX-RECORD} in bytes, independently confirmed by
     * {@code RECORDSIZE(350 350)} in {@code app/jcl/CREASTMT.JCL:32} and by the {@code 16 + 16 + 318}
     * {@code FD} split at {@code app/cbl/CBSTM03B.CBL:58-63}.
     */
    public static final int RECORD_LENGTH = 350;

    /**
     * Absolute 0-based offset of the {@code TRNX-KEY} group: the KSDS key position, {@code KEYS(32 0)}.
     */
    public static final int TRNX_KEY_OFFSET = 0;

    /**
     * Width of the {@code TRNX-KEY} group in bytes: the KSDS key length, {@code KEYS(32 0)}.
     */
    public static final int TRNX_KEY_LENGTH = 32;

    /**
     * Absolute 0-based offset of {@code TRNX-CARD-NUM PIC X(16)}, the high-order half of the key.
     */
    public static final int TRNX_CARD_NUM_OFFSET = 0;

    /**
     * Width of {@code TRNX-CARD-NUM PIC X(16)} in bytes.
     */
    public static final int TRNX_CARD_NUM_LENGTH = 16;

    /**
     * Absolute 0-based offset of {@code TRNX-ID PIC X(16)}, the low-order half of the key.
     */
    public static final int TRNX_ID_OFFSET = 16;

    /**
     * Width of {@code TRNX-ID PIC X(16)} in bytes.
     */
    public static final int TRNX_ID_LENGTH = 16;

    /**
     * Absolute 0-based offset of the {@code TRNX-REST} group, which is also the offset of
     * {@code FD-ACCT-DATA X(318)} in {@code CBSTM03B}'s {@code FD} for this file.
     */
    public static final int TRNX_REST_OFFSET = 32;

    /**
     * Width of the {@code TRNX-REST} group in bytes.
     */
    public static final int TRNX_REST_LENGTH = 318;

    /**
     * Absolute 0-based offset of {@code TRNX-TYPE-CD PIC X(02)}.
     */
    public static final int TRNX_TYPE_CD_OFFSET = 32;

    /**
     * Width of {@code TRNX-TYPE-CD PIC X(02)} in bytes.
     */
    public static final int TRNX_TYPE_CD_LENGTH = 2;

    /**
     * Absolute 0-based offset of {@code TRNX-CAT-CD PIC 9(04)}.
     */
    public static final int TRNX_CAT_CD_OFFSET = 34;

    /**
     * Width of {@code TRNX-CAT-CD PIC 9(04)} in bytes, one zoned {@code DISPLAY} digit per byte.
     */
    public static final int TRNX_CAT_CD_LENGTH = 4;

    /**
     * Absolute 0-based offset of {@code TRNX-SOURCE PIC X(10)}.
     */
    public static final int TRNX_SOURCE_OFFSET = 38;

    /**
     * Width of {@code TRNX-SOURCE PIC X(10)} in bytes.
     */
    public static final int TRNX_SOURCE_LENGTH = 10;

    /**
     * Absolute 0-based offset of {@code TRNX-DESC PIC X(100)}.
     */
    public static final int TRNX_DESC_OFFSET = 48;

    /**
     * Width of {@code TRNX-DESC PIC X(100)} in bytes.
     */
    public static final int TRNX_DESC_LENGTH = 100;

    /**
     * Absolute 0-based offset of {@code TRNX-AMT PIC S9(09)V99}, the record's only scaled field.
     */
    public static final int TRNX_AMT_OFFSET = 148;

    /**
     * {@code p} in {@code TRNX-AMT PIC S9(09)V99}: the digit positions left of the implied decimal point.
     */
    public static final int TRNX_AMT_INTEGER_DIGITS = 9;

    /**
     * {@code s} in {@code TRNX-AMT PIC S9(09)V99}: the digit positions right of the implied decimal point.
     */
    public static final int TRNX_AMT_SCALE = CobolDecimal.MONETARY_SCALE;

    /**
     * Width of {@code TRNX-AMT PIC S9(09)V99} in bytes, computed as {@code p + s} rather than written as a
     * literal 11 so that a phantom sign byte cannot be reintroduced by hand.
     */
    public static final int TRNX_AMT_LENGTH = TRNX_AMT_INTEGER_DIGITS + TRNX_AMT_SCALE;

    /**
     * Absolute 0-based offset of {@code TRNX-MERCHANT-ID PIC 9(09)}.
     */
    public static final int TRNX_MERCHANT_ID_OFFSET = 159;

    /**
     * Width of {@code TRNX-MERCHANT-ID PIC 9(09)} in bytes, one zoned {@code DISPLAY} digit per byte.
     */
    public static final int TRNX_MERCHANT_ID_LENGTH = 9;

    /**
     * Absolute 0-based offset of {@code TRNX-MERCHANT-NAME PIC X(50)}.
     */
    public static final int TRNX_MERCHANT_NAME_OFFSET = 168;

    /**
     * Width of {@code TRNX-MERCHANT-NAME PIC X(50)} in bytes.
     */
    public static final int TRNX_MERCHANT_NAME_LENGTH = 50;

    /**
     * Absolute 0-based offset of {@code TRNX-MERCHANT-CITY PIC X(50)}.
     */
    public static final int TRNX_MERCHANT_CITY_OFFSET = 218;

    /**
     * Width of {@code TRNX-MERCHANT-CITY PIC X(50)} in bytes.
     */
    public static final int TRNX_MERCHANT_CITY_LENGTH = 50;

    /**
     * Absolute 0-based offset of {@code TRNX-MERCHANT-ZIP PIC X(10)}.
     */
    public static final int TRNX_MERCHANT_ZIP_OFFSET = 268;

    /**
     * Width of {@code TRNX-MERCHANT-ZIP PIC X(10)} in bytes.
     */
    public static final int TRNX_MERCHANT_ZIP_LENGTH = 10;

    /**
     * Absolute 0-based offset of {@code TRNX-ORIG-TS PIC X(26)}.
     */
    public static final int TRNX_ORIG_TS_OFFSET = 278;

    /**
     * Width of {@code TRNX-ORIG-TS PIC X(26)} in bytes.
     */
    public static final int TRNX_ORIG_TS_LENGTH = 26;

    /**
     * Absolute 0-based offset of {@code TRNX-PROC-TS PIC X(26)}.
     */
    public static final int TRNX_PROC_TS_OFFSET = 304;

    /**
     * Width of {@code TRNX-PROC-TS PIC X(26)} in bytes, as the copybook declares it.
     */
    public static final int TRNX_PROC_TS_LENGTH = 26;

    /**
     * The number of {@code TRNX-PROC-TS} characters that {@code app/jcl/CREASTMT.JCL:54} actually
     * populates: {@code OUTREC FIELDS=(..., 279:279,50)} copies 50 of the 52 bytes spanned by
     * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} together, so the last two characters of the process
     * timestamp are dropped and arrive as spaces.
     */
    public static final int TRNX_PROC_TS_SORT_DERIVED_LENGTH = 24;

    /**
     * Absolute 0-based offset of the trailing {@code FILLER PIC X(20)}.
     */
    public static final int FILLER_OFFSET = 330;

    /**
     * Width of the trailing {@code FILLER PIC X(20)} in bytes.
     */
    public static final int FILLER_LENGTH = 20;

    // Names are carried VERBATIM as COBOL spells them, hyphens and all, because the parity differ compares
    // field by field BY NAME - silently tidying a name would make a real difference invisible.

    /**
     * {@code 10 TRNX-CARD-NUM PIC X(16)} - the high-order 16 bytes of the composite key.
     */
    public static final FieldSpan TRNX_CARD_NUM =
            FieldSpan.alphanumeric("TRNX-CARD-NUM", TRNX_CARD_NUM_OFFSET, TRNX_CARD_NUM_LENGTH);

    /**
     * {@code 10 TRNX-ID PIC X(16)} - the low-order 16 bytes of the composite key.
     */
    public static final FieldSpan TRNX_ID =
            FieldSpan.alphanumeric("TRNX-ID", TRNX_ID_OFFSET, TRNX_ID_LENGTH);

    /**
     * {@code 10 TRNX-TYPE-CD PIC X(02)}.
     */
    public static final FieldSpan TRNX_TYPE_CD =
            FieldSpan.alphanumeric("TRNX-TYPE-CD", TRNX_TYPE_CD_OFFSET, TRNX_TYPE_CD_LENGTH);

    /**
     * {@code 10 TRNX-CAT-CD PIC 9(04)} - unsigned zoned {@code DISPLAY}, so zero-filled on the left.
     */
    public static final FieldSpan TRNX_CAT_CD =
            FieldSpan.unsignedNumeric("TRNX-CAT-CD", TRNX_CAT_CD_OFFSET, TRNX_CAT_CD_LENGTH);

    /**
     * {@code 10 TRNX-SOURCE PIC X(10)}.
     */
    public static final FieldSpan TRNX_SOURCE =
            FieldSpan.alphanumeric("TRNX-SOURCE", TRNX_SOURCE_OFFSET, TRNX_SOURCE_LENGTH);

    /**
     * {@code 10 TRNX-DESC PIC X(100)}.
     */
    public static final FieldSpan TRNX_DESC =
            FieldSpan.alphanumeric("TRNX-DESC", TRNX_DESC_OFFSET, TRNX_DESC_LENGTH);

    /**
     * {@code 10 TRNX-AMT PIC S9(09)V99} - the record's only scaled field.
     */
    public static final FieldSpan TRNX_AMT = FieldSpan.signedScaled(
            "TRNX-AMT", TRNX_AMT_OFFSET, TRNX_AMT_INTEGER_DIGITS, TRNX_AMT_SCALE);

    /**
     * {@code 10 TRNX-MERCHANT-ID PIC 9(09)} - unsigned zoned {@code DISPLAY}.
     */
    public static final FieldSpan TRNX_MERCHANT_ID = FieldSpan.unsignedNumeric(
            "TRNX-MERCHANT-ID", TRNX_MERCHANT_ID_OFFSET, TRNX_MERCHANT_ID_LENGTH);

    /**
     * {@code 10 TRNX-MERCHANT-NAME PIC X(50)}.
     */
    public static final FieldSpan TRNX_MERCHANT_NAME = FieldSpan.alphanumeric(
            "TRNX-MERCHANT-NAME", TRNX_MERCHANT_NAME_OFFSET, TRNX_MERCHANT_NAME_LENGTH);

    /**
     * {@code 10 TRNX-MERCHANT-CITY PIC X(50)}.
     */
    public static final FieldSpan TRNX_MERCHANT_CITY = FieldSpan.alphanumeric(
            "TRNX-MERCHANT-CITY", TRNX_MERCHANT_CITY_OFFSET, TRNX_MERCHANT_CITY_LENGTH);

    /**
     * {@code 10 TRNX-MERCHANT-ZIP PIC X(10)}.
     */
    public static final FieldSpan TRNX_MERCHANT_ZIP = FieldSpan.alphanumeric(
            "TRNX-MERCHANT-ZIP", TRNX_MERCHANT_ZIP_OFFSET, TRNX_MERCHANT_ZIP_LENGTH);

    /**
     * {@code 10 TRNX-ORIG-TS PIC X(26)} - fully populated by the sort.
     */
    public static final FieldSpan TRNX_ORIG_TS =
            FieldSpan.alphanumeric("TRNX-ORIG-TS", TRNX_ORIG_TS_OFFSET, TRNX_ORIG_TS_LENGTH);

    /**
     * {@code 10 TRNX-PROC-TS PIC X(26)} - declared at its full copybook width of 26 even though
     * {@code app/jcl/CREASTMT.JCL:54} populates only the first {@link #TRNX_PROC_TS_SORT_DERIVED_LENGTH}
     * characters.
     */
    public static final FieldSpan TRNX_PROC_TS =
            FieldSpan.alphanumeric("TRNX-PROC-TS", TRNX_PROC_TS_OFFSET, TRNX_PROC_TS_LENGTH);

    /**
     * {@code 10 FILLER PIC X(20)} - a real, positioned, space-emitting span.
     */
    public static final FieldSpan FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    /**
     * {@code 05 TRNX-KEY} - the 32-byte composite key as one contiguous span.
     */
    public static final FieldSpan TRNX_KEY = FieldSpan.redefining(
            "TRNX-KEY", TRNX_KEY_OFFSET, TRNX_KEY_LENGTH, PictureKind.ALPHANUMERIC);

    /**
     * {@code 05 TRNX-REST} - the 318-byte remainder as one contiguous span, and the group that
     * {@code app/cbl/CBSTM03A.CBL} moves wholesale in both directions against its
     * {@code WS-TRAN-REST PIC X(318)} table slot: out at {@code :829} and back in at {@code :426-427}.
     */
    public static final FieldSpan TRNX_REST = FieldSpan.redefining(
            "TRNX-REST", TRNX_REST_OFFSET, TRNX_REST_LENGTH, PictureKind.ALPHANUMERIC);

    private static final RecordLayout LAYOUT = RecordLayout.of(RECORD_LENGTH,
            TRNX_CARD_NUM,
            TRNX_ID,
            TRNX_TYPE_CD,
            TRNX_CAT_CD,
            TRNX_SOURCE,
            TRNX_DESC,
            TRNX_AMT,
            TRNX_MERCHANT_ID,
            TRNX_MERCHANT_NAME,
            TRNX_MERCHANT_CITY,
            TRNX_MERCHANT_ZIP,
            TRNX_ORIG_TS,
            TRNX_PROC_TS,
            FILLER,
            TRNX_KEY,
            TRNX_REST);

    private static final List<FieldSpan> FIELD_SPANS = List.copyOf(LAYOUT.spans());

    // Exactly two fields, both final and both per instance: the byte area that IS the record, and the codec
    // that gives its bytes their PICTURE meaning.

    private final FixedWidthRecord area;

    private final FixedWidthCodec codec;

    private TrnxRecord(FixedWidthRecord area) {
        this.area = area;
        this.codec = new FixedWidthCodec(area.charset());
    }

    // Two decoding entry points, deliberately distinguished: decode() reproduces COBOL's alphanumeric group
    // MOVE and adjusts the width, wrap() insists the width is already right.

    /**
     * Allocates a new, initialised record: every {@code PIC X} span and the trailing {@code FILLER}
     * space-filled, and the two numeric {@code DISPLAY} spans zero-filled, following the COBOL
     * {@code INITIALIZE} convention.
     *
     * @param charset the code page of the record's data, stated explicitly and never defaulted
     * @return a newly allocated, initialised record
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} does not encode the space and zero characters to
     *     exactly one byte each, since a fixed-width area cannot be addressed by absolute offset under a
     *     multi-byte code page
     */
    public static TrnxRecord newRecord(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to allocate a TRNX-RECORD area; name "
                + "the code page explicitly - IBM037 for EBCDIC data or US-ASCII for the text "
                + "fixtures - and never rely on the platform default");
        return new TrnxRecord(FixedWidthRecord.forLayout(LAYOUT, charset));
    }

    /**
     * Decodes a record by performing COBOL's alphanumeric group {@code MOVE} into a 350-byte receiver,
     * which means an oversized source is accepted and its leading 350 bytes are taken.
     *
     * <p>The sending field there is the {@code PIC X(1000)} response span declared at
     * {@code app/cbl/CBSTM03B.CBL:112}: {@code CBSTM03B} hands back raw, space-padded 1000-byte spans and
     * deliberately never decodes, so the truncation belongs here.
     *
     * @param source the sending bytes; may be longer than, shorter than or exactly {@link #RECORD_LENGTH},
     *     and is never modified
     * @param charset the code page of the record's data, stated explicitly
     * @return a record over exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code source} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code source} is empty, or {@code charset} is not a single-byte
     *     code page for the space and zero characters
     */
    public static TrnxRecord decode(byte[] source, Charset charset) {
        Objects.requireNonNull(source, "Sending bytes are required to decode a TRNX-RECORD; call "
                + "newRecord(Charset) to allocate an initialised record area instead");
        Objects.requireNonNull(charset, "A charset is required to decode a TRNX-RECORD; name the code "
                + "page explicitly and never rely on the platform default");
        if (source.length == 0) {
            throw new IllegalArgumentException("Cannot decode a TRNX-RECORD from an empty span. A "
                    + "sending field has at least one byte; to obtain a blank record call "
                    + "newRecord(Charset)");
        }

        FixedWidthRecord target = new FixedWidthRecord(RECORD_LENGTH, charset);
        byte[] moved = source.length > RECORD_LENGTH
                // Stated as an explicit, commented truncation rather than an incidental substring, because
                // the opposite direction is what COBOL would do for a PIC 9 receiver and a silent choice
                // here would be indistinguishable from correct behaviour.
                ? Arrays.copyOf(source, RECORD_LENGTH)
                : source;
        target.writeBytes(0, moved);
        return new TrnxRecord(target);
    }

    /**
     * Wraps stored bytes whose width must already be exactly {@link #RECORD_LENGTH}, taking a defensive
     * copy.
     *
     * @param record the stored bytes, exactly {@link #RECORD_LENGTH} of them; defensively copied
     * @param charset the code page of the record's data, stated explicitly
     * @return a record over a copy of {@code record}
     * @throws NullPointerException if {@code record} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code record.length} differs from {@link #RECORD_LENGTH}, or
     *     {@code charset} is not a single-byte code page for the space and zero characters
     */
    public static TrnxRecord wrap(byte[] record, Charset charset) {
        Objects.requireNonNull(record, "Stored bytes are required to wrap a TRNX-RECORD");
        Objects.requireNonNull(charset, "A charset is required to wrap a TRNX-RECORD; name the code "
                + "page explicitly and never rely on the platform default");
        return new TrnxRecord(new FixedWidthCodec(charset).wrap(record, LAYOUT));
    }

    /**
     * The self-checked layout of this record: the fourteen elementary spans followed by the
     * {@code TRNX-KEY} and {@code TRNX-REST} group overlays.
     *
     * @return the record's layout, never {@code null}
     */
    public static RecordLayout layout() {
        return LAYOUT;
    }

    /**
     * Every descriptor of this record, in declaration order: the fourteen elementary spans that sum to
     * {@link #RECORD_LENGTH}, then the two group overlays that contribute nothing to that sum.
     *
     * @return an immutable list of descriptors
     */
    public static List<FieldSpan> fieldSpans() {
        return FIELD_SPANS;
    }

    /**
     * The code page this record's bytes are held in, fixed for the instance's lifetime.
     *
     * @return the charset supplied when this record was created
     */
    public Charset charset() {
        return area.charset();
    }

    /**
     * The record's width in bytes, always {@link #RECORD_LENGTH}.
     *
     * @return {@code 350}
     */
    public int recordLength() {
        return area.recordLength();
    }

    /**
     * The complete 350-byte record image: the serialised form written to a dataset and the form the parity
     * harness fingerprints.
     *
     * @return a copy of all {@link #RECORD_LENGTH} bytes
     */
    public byte[] encode() {
        return area.toByteArray();
    }

    /**
     * The complete 350-byte record image, with the destination's code page stated explicitly at the write
     * site.
     *
     * @param charset the code page the caller expects these bytes to be in
     * @return a copy of all {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} differs from {@link #charset()}
     */
    public byte[] encode(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to serialise a TRNX-RECORD; name the "
                + "destination code page explicitly and never rely on the platform default");
        if (!charset.equals(area.charset())) {
            throw new IllegalArgumentException("This TRNX-RECORD holds its bytes in "
                    + area.charset().name() + " but serialisation was requested in " + charset.name()
                    + ". A fixed-width record is not transcoded on the way out, because doing so "
                    + "silently would be a behaviour change; decode the record under "
                    + charset.name() + " in the first place, or call encode() to accept "
                    + area.charset().name());
        }
        return area.toByteArray();
    }

    /**
     * The complete 350-character record image as text, decoded under this record's charset and untrimmed.
     *
     * <p>Every space is significant: the trailing {@code FILLER} and the right-hand padding of each
     * {@code PIC X} field are part of the record's value, and the parity differ compares them.
     *
     * @return exactly {@link #RECORD_LENGTH} characters
     */
    public String recordImage() {
        return area.readString(0, RECORD_LENGTH);
    }

    /**
     * The 32-byte {@code TRNX-KEY} group as one contiguous, untrimmed image: the KSDS key, card number
     * followed by transaction id.
     *
     * @return exactly {@link #TRNX_KEY_LENGTH} characters
     */
    public String readTrnxKey() {
        return codec.readPicX(area, TRNX_KEY);
    }

    /**
     * Writes the whole 32-byte {@code TRNX-KEY} group, applying the {@code PIC X} move rule: padded on the
     * right when the value is shorter and truncated on the right when it is longer.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxKey(String value) {
        codec.writePicX(area, TRNX_KEY, value);
    }

    /**
     * The 32-byte {@code TRNX-KEY} group as raw bytes, defensively copied.
     *
     * @return a copy of {@link #TRNX_KEY_LENGTH} bytes at offset {@link #TRNX_KEY_OFFSET}
     */
    public byte[] readTrnxKeyBytes() {
        return area.readSpanBytes(TRNX_KEY);
    }

    /**
     * Replaces the 32-byte {@code TRNX-KEY} group from raw bytes, which must be exactly
     * {@link #TRNX_KEY_LENGTH} long.
     *
     * @param source the replacement bytes, exactly {@link #TRNX_KEY_LENGTH} of them
     * @throws NullPointerException if {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code source.length} differs from {@link #TRNX_KEY_LENGTH}
     */
    public void writeTrnxKeyBytes(byte[] source) {
        area.writeSpanBytes(TRNX_KEY, source);
    }

    /**
     * The 318-byte {@code TRNX-REST} group as one contiguous, untrimmed image.
     *
     * @return exactly {@link #TRNX_REST_LENGTH} characters
     */
    public String readTrnxRest() {
        return codec.readPicX(area, TRNX_REST);
    }

    /**
     * Writes the whole 318-byte {@code TRNX-REST} group, applying the {@code PIC X} move rule: padded on
     * the right when the value is shorter and truncated on the right when it is longer.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxRest(String value) {
        codec.writePicX(area, TRNX_REST, value);
    }

    /**
     * The 318-byte {@code TRNX-REST} group as raw bytes, defensively copied.
     *
     * @return a copy of {@link #TRNX_REST_LENGTH} bytes at offset {@link #TRNX_REST_OFFSET}
     */
    public byte[] readTrnxRestBytes() {
        return area.readSpanBytes(TRNX_REST);
    }

    /**
     * Replaces the 318-byte {@code TRNX-REST} group from raw bytes, which must be exactly
     * {@link #TRNX_REST_LENGTH} long.
     *
     * @param source the replacement bytes, exactly {@link #TRNX_REST_LENGTH} of them
     * @throws NullPointerException if {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code source.length} differs from {@link #TRNX_REST_LENGTH}
     */
    public void writeTrnxRestBytes(byte[] source) {
        area.writeSpanBytes(TRNX_REST, source);
    }

    // A caller that needs to see a field's bytes exactly as stored - to fingerprint them, to diff them, or
    // to inspect a span whose contents will not decode - reaches them here without any PICTURE
    // interpretation in the way.

    /**
     * Any declared span of this record as an untrimmed image, with no PICTURE interpretation applied.
     *
     * @param field one of this record's descriptors, elementary or group
     * @return exactly {@code field.length()} characters
     * @throws NullPointerException if {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code field} is not a descriptor of this record's layout
     */
    public String readRawImage(FieldSpan field) {
        return area.readSpan(requireOwnSpan(field));
    }

    /**
     * Any declared span of this record as raw bytes, defensively copied.
     *
     * @param field one of this record's descriptors, elementary or group
     * @return a copy of the span's bytes
     * @throws NullPointerException if {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code field} is not a descriptor of this record's layout
     */
    public byte[] readRawBytes(FieldSpan field) {
        return area.readSpanBytes(requireOwnSpan(field));
    }

    /**
     * Rejects a descriptor that does not belong to this record's layout, so a span borrowed from another
     * copybook cannot silently read a plausible-looking value out of the wrong offset - which is exactly
     * the class of defect that is hardest to trace back from a parity diff.
     *
     * @param field the descriptor to validate against {@link #fieldSpans()}
     * @return {@code field} unchanged, so this can be applied inline at the point of use
     * @throws NullPointerException if {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code field} is not one of this record's own descriptors
     */
    private static FieldSpan requireOwnSpan(FieldSpan field) {
        Objects.requireNonNull(field, "A field descriptor is required to read a span of TRNX-RECORD");
        if (!FIELD_SPANS.contains(field)) {
            throw new IllegalArgumentException("Descriptor " + field.describe() + " is not part of the "
                    + "TRNX-RECORD layout of app/cpy/COSTM01.CPY. Use one of this class's own span "
                    + "constants; reading a foreign descriptor would address the wrong offset and "
                    + "return a plausible but wrong value");
        }
        return field;
    }

    // A COBOL alphanumeric field is space-padded to its full declared width and that padding is part of the
    // field's value: the parity differ compares it byte for byte, so trimming here would discard bytes it
    // is meant to compare.

    /**
     * {@code TRNX-CARD-NUM PIC X(16)}, untrimmed - the high-order half of the composite key.
     *
     * @return exactly {@link #TRNX_CARD_NUM_LENGTH} characters, space padding included
     */
    public String readTrnxCardNum() {
        return codec.readPicX(area, TRNX_CARD_NUM);
    }

    /**
     * Writes {@code TRNX-CARD-NUM PIC X(16)}, padded on the right when shorter and truncated on the right
     * when longer.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxCardNum(String value) {
        codec.writePicX(area, TRNX_CARD_NUM, value);
    }

    /**
     * {@code TRNX-ID PIC X(16)}, untrimmed - the low-order half of the composite key.
     *
     * @return exactly {@link #TRNX_ID_LENGTH} characters, space padding included
     */
    public String readTrnxId() {
        return codec.readPicX(area, TRNX_ID);
    }

    /**
     * Writes {@code TRNX-ID PIC X(16)}, padded or truncated on the right.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxId(String value) {
        codec.writePicX(area, TRNX_ID, value);
    }

    /**
     * {@code TRNX-TYPE-CD PIC X(02)}, untrimmed.
     *
     * @return exactly {@link #TRNX_TYPE_CD_LENGTH} characters
     */
    public String readTrnxTypeCd() {
        return codec.readPicX(area, TRNX_TYPE_CD);
    }

    /**
     * Writes {@code TRNX-TYPE-CD PIC X(02)}, padded or truncated on the right.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxTypeCd(String value) {
        codec.writePicX(area, TRNX_TYPE_CD, value);
    }

    /**
     * {@code TRNX-CAT-CD PIC 9(04)} as an {@code int}.
     *
     * <p>Four unsigned zoned {@code DISPLAY} digits, so an {@code int} holds every representable value with
     * room to spare, and there is no scale and no sign to preserve.
     *
     * @return the category code the four digits denote
     * @throws IllegalArgumentException if the span does not hold four digits
     */
    public int readTrnxCatCd() {
        return codec.readPic9AsInt(area, TRNX_CAT_CD);
    }

    /**
     * Writes {@code TRNX-CAT-CD PIC 9(04)}, zero-filled on the left to four digits - so {@code 7} is stored
     * as {@code 0007}.
     *
     * @param value the category code; must not be negative, since {@code PIC 9} has no sign position
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public void writeTrnxCatCd(int value) {
        codec.writePic9(area, TRNX_CAT_CD, value);
    }

    /**
     * {@code TRNX-SOURCE PIC X(10)}, untrimmed.
     *
     * @return exactly {@link #TRNX_SOURCE_LENGTH} characters
     */
    public String readTrnxSource() {
        return codec.readPicX(area, TRNX_SOURCE);
    }

    /**
     * Writes {@code TRNX-SOURCE PIC X(10)}, padded or truncated on the right.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxSource(String value) {
        codec.writePicX(area, TRNX_SOURCE, value);
    }

    /**
     * {@code TRNX-DESC PIC X(100)}, untrimmed.
     *
     * @return exactly {@link #TRNX_DESC_LENGTH} characters, space padding included
     */
    public String readTrnxDesc() {
        return codec.readPicX(area, TRNX_DESC);
    }

    /**
     * Writes {@code TRNX-DESC PIC X(100)}, padded or truncated on the right.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxDesc(String value) {
        codec.writePicX(area, TRNX_DESC, value);
    }

    /**
     * {@code TRNX-AMT PIC S9(09)V99} as a {@link BigDecimal} of scale exactly {@link #TRNX_AMT_SCALE}.
     *
     * @return the amount at scale exactly {@link #TRNX_AMT_SCALE}, never {@code null}
     * @throws IllegalArgumentException if the span does not hold a valid signed zoned image
     */
    public BigDecimal readTrnxAmt() {
        return codec.readMonetary(area, TRNX_AMT);
    }

    /**
     * Writes {@code TRNX-AMT PIC S9(09)V99}.
     *
     * <p>Excess fractional digits are truncated toward zero, never rounded, because the keyword
     * {@code ROUNDED} appears zero times in all 28 COBOL programs: the store routes through
     * {@link CobolDecimal}, whose only rounding mode is {@link CobolDecimal#COBOL_ROUNDING}.
     *
     * @param value the amount to store; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxAmt(BigDecimal value) {
        codec.writeMonetary(area, TRNX_AMT, value);
    }

    /**
     * The raw eleven-character zoned {@code DISPLAY} image of {@code TRNX-AMT}, exactly as stored, with the
     * sign overpunch still in the trailing character and no decimal point.
     *
     * @return exactly {@link #TRNX_AMT_LENGTH} characters
     */
    public String readTrnxAmtImage() {
        return area.readSpan(TRNX_AMT);
    }

    /**
     * {@code TRNX-MERCHANT-ID PIC 9(09)} as an {@code int}.
     *
     * @return the merchant identifier the nine digits denote
     * @throws IllegalArgumentException if the span does not hold nine digits, or denotes a value outside
     *     the {@code int} range - which nine digits cannot
     */
    public int readTrnxMerchantId() {
        return codec.readPic9AsInt(area, TRNX_MERCHANT_ID);
    }

    /**
     * Writes {@code TRNX-MERCHANT-ID PIC 9(09)}, zero-filled on the left to nine digits.
     *
     * @param value the merchant identifier; must not be negative, since {@code PIC 9} has no sign position
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public void writeTrnxMerchantId(int value) {
        codec.writePic9(area, TRNX_MERCHANT_ID, value);
    }

    /**
     * {@code TRNX-MERCHANT-NAME PIC X(50)}, untrimmed.
     *
     * @return exactly {@link #TRNX_MERCHANT_NAME_LENGTH} characters
     */
    public String readTrnxMerchantName() {
        return codec.readPicX(area, TRNX_MERCHANT_NAME);
    }

    /**
     * Writes {@code TRNX-MERCHANT-NAME PIC X(50)}, padded or truncated on the right.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxMerchantName(String value) {
        codec.writePicX(area, TRNX_MERCHANT_NAME, value);
    }

    /**
     * {@code TRNX-MERCHANT-CITY PIC X(50)}, untrimmed.
     *
     * @return exactly {@link #TRNX_MERCHANT_CITY_LENGTH} characters
     */
    public String readTrnxMerchantCity() {
        return codec.readPicX(area, TRNX_MERCHANT_CITY);
    }

    /**
     * Writes {@code TRNX-MERCHANT-CITY PIC X(50)}, padded or truncated on the right.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxMerchantCity(String value) {
        codec.writePicX(area, TRNX_MERCHANT_CITY, value);
    }

    /**
     * {@code TRNX-MERCHANT-ZIP PIC X(10)}, untrimmed.
     *
     * <p>Alphanumeric in the copybook, so a ZIP keeps any leading zero and is never parsed as a number.
     *
     * @return exactly {@link #TRNX_MERCHANT_ZIP_LENGTH} characters
     */
    public String readTrnxMerchantZip() {
        return codec.readPicX(area, TRNX_MERCHANT_ZIP);
    }

    /**
     * Writes {@code TRNX-MERCHANT-ZIP PIC X(10)}, padded or truncated on the right.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxMerchantZip(String value) {
        codec.writePicX(area, TRNX_MERCHANT_ZIP, value);
    }

    /**
     * {@code TRNX-ORIG-TS PIC X(26)}, untrimmed.
     *
     * @return exactly {@link #TRNX_ORIG_TS_LENGTH} characters
     */
    public String readTrnxOrigTs() {
        return codec.readPicX(area, TRNX_ORIG_TS);
    }

    /**
     * Writes {@code TRNX-ORIG-TS PIC X(26)}, padded or truncated on the right.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxOrigTs(String value) {
        codec.writePicX(area, TRNX_ORIG_TS, value);
    }

    /**
     * {@code TRNX-PROC-TS PIC X(26)}, untrimmed - and the field the legacy sort under-fills.
     *
     * @return exactly {@link #TRNX_PROC_TS_LENGTH} characters, the sort's trailing blanks included
     */
    public String readTrnxProcTs() {
        return codec.readPicX(area, TRNX_PROC_TS);
    }

    /**
     * Writes {@code TRNX-PROC-TS PIC X(26)}, padded or truncated on the right.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxProcTs(String value) {
        codec.writePicX(area, TRNX_PROC_TS, value);
    }

    /**
     * The trailing {@code FILLER PIC X(20)}, untrimmed.
     *
     * <p>Exposed for parity fingerprinting and width verification, and read-only by design: {@code FILLER}
     * is unnamed and non-referable in COBOL, so no program can move a value into it.
     *
     * @return exactly {@link #FILLER_LENGTH} characters
     */
    public String readFiller() {
        return area.readSpan(FILLER);
    }

    /**
     * A short, non-throwing description naming the record's key, its raw amount image and its code page.
     *
     * @return for example
     *     {@code TrnxRecord[TRNX-CARD-NUM= 1111, TRNX-ID=0000********0001, TRNX-AMT=0000005047G, charset=US-ASCII]}
     */
    @Override
    public String toString() {
        return "TrnxRecord[TRNX-CARD-NUM="
                + SensitiveDiagnostics.maskPan(area.readSpan(TRNX_CARD_NUM))
                + ", TRNX-ID=" + DiagnosticText.singleLine(area.readSpan(TRNX_ID))
                + ", TRNX-AMT=" + DiagnosticText.omitted(area.readSpan(TRNX_AMT))
                + ", charset=" + area.charset().name()
                + "]";
    }
}

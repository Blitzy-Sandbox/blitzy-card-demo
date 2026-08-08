package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;

/**
 * The {@code TRANTYPE} transaction-type record: an immutable, 60-byte fixed-width value type
 * translated field for field from {@code app/cpy/CVTRA03Y.cpy}.
 *
 * <h2>The copybook, verbatim</h2>
 * {@code app/cpy/CVTRA03Y.cpy} is 11 lines long and its header comment declares the width outright -
 * <em>"Data-structure for transaction type (RECLN = 60)"</em>:
 * <pre>
 *   01  TRAN-TYPE-RECORD.
 *       05  TRAN-TYPE                               PIC X(02).
 *       05  TRAN-TYPE-DESC                          PIC X(50).
 *       05  FILLER                                  PIC X(08).
 * </pre>
 * which yields exactly one layout, with no ambiguity anywhere in it:
 * <table border="1">
 *   <caption>{@code CVTRA03Y} - 60 bytes</caption>
 *   <tr><th>COBOL field</th><th>PICTURE</th><th>1-based</th><th>0-based offset</th><th>Length</th>
 *       <th>Java</th></tr>
 *   <tr><td>{@code TRAN-TYPE}</td><td>{@code X(02)}</td><td>1-2</td><td>0</td><td>2</td>
 *       <td>{@link String} - the KSDS key</td></tr>
 *   <tr><td>{@code TRAN-TYPE-DESC}</td><td>{@code X(50)}</td><td>3-52</td><td>2</td><td>50</td>
 *       <td>{@link String}, <strong>untrimmed</strong></td></tr>
 *   <tr><td>{@code FILLER}</td><td>{@code X(08)}</td><td>53-60</td><td>52</td><td>8</td>
 *       <td>reserved span, carried verbatim</td></tr>
 * </table>
 * 2 + 50 + 8 = <strong>60</strong>. {@link #LAYOUT} declares all three spans and
 * {@link #verifyDeclaredWidth()} proves the total independently of that declaration.
 *
 * <h2>Exactly one consumer</h2>
 * {@code app/cbl/CBTRN03C.cbl} - the transaction detail report - is the <strong>only</strong> program
 * in the repository that copies {@code CVTRA03Y}, and it touches the record in only two places:
 * {@code READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD} at line 495 and
 * {@code MOVE TRAN-TYPE-DESC TO TRAN-REPORT-TYPE-DESC} at line 366.
 *
 * <h2>Three independent confirmations of this layout</h2>
 * The expectations encoded here are <strong>statically derived</strong>: the COBOL cannot be executed
 * in this environment - there is no z/OS runtime, the available compiler has its indexed-file handler
 * disabled, and no Language Environment {@code CEE*} service is present - so the layout was
 * cross-checked against three independent sources rather than captured from a live run. This is the
 * documented baseline-provenance deviation, and recording it here is part of it.
 * <ol>
 *   <li><strong>The consuming program's own {@code FD}.</strong>
 *       {@code app/cbl/CBTRN03C.cbl:73-75} splits the record as
 *       {@code FD-TRAN-TYPE PIC X(02)} + {@code FD-TRAN-DATA PIC X(58)}, which both totals 60 and
 *       confirms that the key is the leading 2 bytes. Line 42 names that same item in
 *       {@code RECORD KEY IS FD-TRAN-TYPE}.</li>
 *   <li><strong>The JCL binding.</strong> {@code app/jcl/TRANREPT.jcl:69-70} declares the
 *       {@code TRANTYPE} DD with {@code DISP=SHR} as an input to
 *       {@code STEP10R EXEC PGM=CBTRN03C}, and the {@code TRANTYPE} entry of
 *       {@code carddemo.datasets} in {@code src/main/resources/application.yml} carries
 *       {@code record-length: 60} for it. The dataset name that DD carries is deliberately
 *       <em>not</em> reproduced here: it lives in that configuration entry behind an environment
 *       placeholder and in no Java source at all (gate G46), so the JCL line and the binding key
 *       are cited instead and the name keeps a single authority.</li>
 *   <li><strong>The shipped fixture, decoded at these offsets.</strong>
 *       {@code app/data/ASCII/trantype.txt} measures 7 records of exactly 60 bytes and decodes
 *       cleanly:
 *       <table border="1">
 *         <caption>All seven {@code TRANTYPE} records</caption>
 *         <tr><th>{@code TRAN-TYPE}</th><th>{@code TRAN-TYPE-DESC} (trimmed for display)</th>
 *             <th>{@code FILLER}</th></tr>
 *         <tr><td>{@code 01}</td><td>{@code Purchase}</td><td>{@code 00000000}</td></tr>
 *         <tr><td>{@code 02}</td><td>{@code Payment}</td><td>{@code 00000000}</td></tr>
 *         <tr><td>{@code 03}</td><td>{@code Credit}</td><td>{@code 00000000}</td></tr>
 *         <tr><td>{@code 04}</td><td>{@code Authorization}</td><td>{@code 00000000}</td></tr>
 *         <tr><td>{@code 05}</td><td>{@code Refund}</td><td>{@code 00000000}</td></tr>
 *         <tr><td>{@code 06}</td><td>{@code Reversal}</td><td>{@code 00000000}</td></tr>
 *         <tr><td>{@code 07}</td><td>{@code Adjustment}</td><td>{@code 00000000}</td></tr>
 *       </table>
 *       Each description occupies all 50 bytes once its right-hand space padding is counted.</li>
 * </ol>
 *
 * <h2>Why {@code TRAN-TYPE-DESC} is decoded untrimmed</h2>
 * {@code app/cbl/CBTRN03C.cbl:366} moves this {@code PIC X(50)} field into
 * {@code TRAN-REPORT-TYPE-DESC}, which {@code app/cpy/CVTRA07Y.cpy:22} declares as
 * {@code PIC X(15)}. COBOL fills an alphanumeric receiver from its leftmost character position and
 * discards whatever does not fit, so the report line carries the <strong>first 15 characters</strong>
 * of the 50-byte field - {@code Authorization} followed by two spaces, not {@code Authorization}
 * alone. Two things follow, and both are honoured here:
 * <ul>
 *   <li>{@link #tranTypeDesc()} returns all 50 characters, padding included. Trimming would make the
 *       50-byte field indistinguishable from its trimmed form, break the byte-identical
 *       decode-then-encode round trip, and cost the record its 60-byte width.</li>
 *   <li>The truncation itself is performed at the point of use by
 *       {@link #tranTypeDescMovedTo(int)}, which delegates to
 *       {@link FixedWidthCodec#movePicX(String, int)} so that the <em>direction</em> of truncation is
 *       an explicit, named decision rather than an incidental {@code substring}. COBOL truncates a
 *       {@code PIC X} move on the right and a {@code PIC 9} move on the left, and a plain Java
 *       assignment would do neither.</li>
 * </ul>
 * None of the seven shipped descriptions exceeds 15 characters today, so no data currently loses a
 * character; the mechanism still has to be right, because a shorter description reaching the report
 * correctly by luck is not the same as reaching it correctly by rule.
 *
 * <h2>{@code TRAN-TYPE}, not {@code TRAN-TYPE-CD}</h2>
 * This copybook names its key {@code TRAN-TYPE}. The two-byte type code is called
 * <strong>{@code TRAN-TYPE-CD}</strong> in {@code app/cpy/CVTRA04Y.cpy:6} and
 * {@code app/cpy/CVTRA05Y.cpy:6}, and {@code app/cbl/CBTRN03C.cbl:189} moves one into the other:
 * {@code MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE}. The names differ by three characters and
 * denote the same two bytes, so they are easy to conflate - and conflating them would rename a field
 * that the parity differ compares <em>by name</em>. Each name is therefore carried exactly as its own
 * copybook spells it, and never harmonised.
 *
 * <h2>The key is a {@code String}, never an {@code int}</h2>
 * {@code TRAN-TYPE} is {@code PIC X(02)} - character data. Its values happen to look numeric
 * ({@code 01} through {@code 07}), but the leading zero is significant, the field is compared as
 * characters, and the KSDS is keyed on those two bytes. Decoding {@code "01"} to {@code 1} would
 * re-encode as {@code "1 "} or {@code " 1"} depending on the padding rule applied and would not find
 * its record. {@link #TRAN_TYPE_KEY_LENGTH} names the key width for the same reason.
 *
 * <h2>{@code FILLER} is a first-class span, and its bytes are carried verbatim</h2>
 * The trailing {@code FILLER X(08)} is declared in {@link #LAYOUT} with its own offset and length. It
 * is not an inferred gap: were it omitted, the layout would total 52 rather than 60 and
 * {@link RecordLayout} would reject it outright, which is exactly how a dropped {@code FILLER} is
 * turned from silent corruption of every following byte into an immediate, located failure.
 *
 * <p>Its <em>content</em> is a separate matter and is deliberately not assumed. A record built from
 * nothing by {@link #of(String, String, Charset)} space-fills the span, which is the COBOL
 * convention for a {@code FILLER} that declares no {@code VALUE}. A record decoded from stored bytes
 * keeps whatever the dataset held: the measured fixture carries <strong>eight ASCII zeros</strong>
 * there, not spaces, and {@link #toByteArray()} reproduces them byte for byte because this class
 * retains the whole 60-byte image rather than rebuilding it from field values. In practice the
 * distinction never has to be resolved for this dataset, because {@code TRANTYPE} is
 * <strong>read-only</strong> throughout the system: {@code CBTRN03C} issues exactly three verbs
 * against it - {@code OPEN INPUT} at line 432, {@code READ ... INTO} at line 495 and {@code CLOSE} at
 * line 571 - and no {@code WRITE}, {@code REWRITE}, {@code DELETE} or {@code STARTBR} anywhere. That
 * is why this class exposes no mutator: a rewrite path would be behaviour the legacy system does not
 * have.
 *
 * <h2>What this class deliberately does not contain</h2>
 * <ul>
 *   <li><strong>No {@code OCCURS} table, no {@code REDEFINES} overlay and no {@code 88}-level
 *       predicate.</strong> {@code CVTRA03Y} declares none of them - nor any {@code COMP},
 *       {@code COMP-3}, {@code SIGN} or {@code USAGE} clause - so none is invented here. The 1-based
 *       to 0-based conversion still governs the offsets in the table above.</li>
 *   <li><strong>No decimal arithmetic.</strong> Every field is {@code PIC X}; the copybook declares no
 *       signed or scaled item at all. Neither the shared fixed-point helper nor Java's
 *       arbitrary-precision decimal type is imported here, and no primitive floating-point type
 *       appears anywhere - an unused numeric import would weaken exactly the audit trail that makes
 *       those imports meaningful in the models that genuinely need them.</li>
 *   <li><strong>No I/O, no file-status handling and no abend logic.</strong>
 *       {@code CBTRN03C:496-500} handles a missing key by displaying
 *       {@code 'INVALID TRANSACTION TYPE : '}, moving 23 into {@code IO-STATUS} and abending; that
 *       belongs to the repository and the report job. This type is a record, not a reader.</li>
 *   <li><strong>No persistence or framework annotation.</strong> The {@code TRANTYPE} KSDS is reached
 *       over JDBC with no schema change, so there is no entity, table, identifier or version
 *       mapping - and no component annotation either, since this is a value, not a bean.</li>
 * </ul>
 *
 * <h2>Encoding is always the caller's explicit choice</h2>
 * Every factory takes a {@link Charset} or an already-configured {@link FixedWidthCodec}. No method
 * falls back to the platform default, and this class names no code page of its own:
 * {@code IBM037} for the EBCDIC datasets and {@code US-ASCII} for the text fixtures are both supplied
 * from outside. The overloads that accept a codec exist so a repository holding one can reuse it
 * across every row it reads instead of building one per record.
 *
 * <h2>Immutability and thread safety</h2>
 * Instances are deeply immutable: the 60-byte image is copied on the way in and on the way out, so no
 * caller can reach the backing array. There is no mutable static state - only {@code static final}
 * constants and the two immutable descriptors and layout - which makes instances freely shareable
 * across threads.
 *
 * @see FixedWidthRecord
 * @see FixedWidthCodec
 */
public final class TranTypeRecord {

    /**
     * The declared record width in bytes, from {@code app/cpy/CVTRA03Y.cpy}'s own header comment
     * <em>"RECLN = 60"</em>: {@code TRAN-TYPE} 2 + {@code TRAN-TYPE-DESC} 50 + {@code FILLER} 8.
     * Corroborated by {@code CBTRN03C}'s {@code FD} split of 2 + 58 and by the measured 60-byte rows
     * of {@code app/data/ASCII/trantype.txt}.
     */
    public static final int RECORD_LENGTH = 60;

    /** The copybook name of the key item, verbatim: {@code TRAN-TYPE} - not {@code TRAN-TYPE-CD}. */
    public static final String TRAN_TYPE_FIELD = "TRAN-TYPE";

    /** The copybook name of the description item, verbatim: {@code TRAN-TYPE-DESC}. */
    public static final String TRAN_TYPE_DESC_FIELD = "TRAN-TYPE-DESC";

    /** {@code TRAN-TYPE PIC X(02)} begins the record - 1-based position 1, 0-based offset 0. */
    public static final int TRAN_TYPE_OFFSET = 0;

    /** {@code TRAN-TYPE PIC X(02)} is 2 bytes wide. */
    public static final int TRAN_TYPE_LENGTH = 2;

    /**
     * The width of the {@code TRANTYPE} KSDS key in bytes. It is the same 2 bytes as
     * {@link #TRAN_TYPE_LENGTH}, named separately because {@code CBTRN03C:42} declares
     * {@code RECORD KEY IS FD-TRAN-TYPE} over precisely this span and a keyed read must supply a key
     * image of exactly this width.
     */
    public static final int TRAN_TYPE_KEY_LENGTH = 2;

    /** {@code TRAN-TYPE-DESC PIC X(50)} - 1-based positions 3 to 52, 0-based offset 2. */
    public static final int TRAN_TYPE_DESC_OFFSET = 2;

    /** {@code TRAN-TYPE-DESC PIC X(50)} is 50 bytes wide, and is read at all 50. */
    public static final int TRAN_TYPE_DESC_LENGTH = 50;

    /** The trailing {@code FILLER PIC X(08)} - 1-based positions 53 to 60, 0-based offset 52. */
    public static final int FILLER_OFFSET = 52;

    /** The trailing {@code FILLER PIC X(08)} is 8 bytes wide, and is always emitted. */
    public static final int FILLER_LENGTH = 8;

    /**
     * The width of {@code TRAN-REPORT-TYPE-DESC PIC X(15)}, the receiver of this record's description
     * on the detail report [{@code app/cpy/CVTRA07Y.cpy:22}], as moved by
     * {@code app/cbl/CBTRN03C.cbl:366}. Published as a constant so a caller can name the receiver
     * rather than write 15 into a call site, and so the {@code X(50)} to {@code X(15)} narrowing stays
     * traceable to its copybook. It is documentation of the receiver, not a property of this record:
     * the truncation happens in {@link #tranTypeDescMovedTo(int)}, which takes the receiver width as
     * an argument.
     */
    public static final int REPORT_TYPE_DESC_LENGTH = 15;

    /** Descriptor for {@code TRAN-TYPE PIC X(02)} at offset 0. */
    public static final FieldSpan TRAN_TYPE =
            FieldSpan.alphanumeric(TRAN_TYPE_FIELD, TRAN_TYPE_OFFSET, TRAN_TYPE_LENGTH);

    /** Descriptor for {@code TRAN-TYPE-DESC PIC X(50)} at offset 2. */
    public static final FieldSpan TRAN_TYPE_DESC = FieldSpan.alphanumeric(
            TRAN_TYPE_DESC_FIELD, TRAN_TYPE_DESC_OFFSET, TRAN_TYPE_DESC_LENGTH);

    /**
     * Descriptor for the trailing {@code FILLER PIC X(08)} at offset 52. The copybook declares no
     * {@code VALUE} for it, so a record built from nothing space-fills it; a record decoded from
     * stored bytes keeps whatever those bytes hold.
     */
    public static final FieldSpan FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    /**
     * The complete {@code CVTRA03Y} layout: three contiguous spans from offset 0, summing to exactly
     * {@link #RECORD_LENGTH}. {@link RecordLayout} runs its own self-check on construction, so this
     * constant cannot come into existence with a gap, an overlap, a duplicate name or a wrong total -
     * dropping the trailing {@code FILLER} would leave it 8 bytes short of 60 and fail here, at class
     * initialisation, rather than in the data.
     */
    public static final RecordLayout LAYOUT =
            RecordLayout.of(RECORD_LENGTH, TRAN_TYPE, TRAN_TYPE_DESC, FILLER);

    static {
        // Prove the declared width a second time, independently of RecordLayout's own arithmetic, and
        // do it at class-initialisation time so a transcription error can never reach a caller.
        verifyDeclaredWidth(LAYOUT);
    }

    /**
     * The whole 60-byte record, retained rather than reduced to field values so that reserved bytes -
     * the eight the fixture holds as zeros - survive a decode-then-encode round trip untouched.
     * Copied on construction and never handed out.
     */
    private final byte[] image;

    /** The codec, and therefore the code page, this record was decoded under. Never derived. */
    private final FixedWidthCodec codec;

    /**
     * Wraps a validated 60-byte image. Private because every entry point is a named factory that
     * states how the bytes were obtained: decoded from a dataset row, or built from field values.
     *
     * @param image a private, already length-checked 60-byte array that this instance takes ownership
     *              of
     * @param codec the codec whose charset the image is encoded in
     */
    private TranTypeRecord(byte[] image, FixedWidthCodec codec) {
        this.image = image;
        this.codec = codec;
    }

    /**
     * Sums the layout's storage spans and confirms the total is exactly {@link #RECORD_LENGTH} - the
     * total-width self-check, expressed so that it can be exercised rather than merely asserted.
     *
     * <p>This is deliberately <em>not</em> a restatement of {@link RecordLayout}'s internal check. It
     * re-derives the width from the span lengths and compares it against this class's own declared
     * constant, so a layout that is internally consistent but describes the wrong record - a 50-byte
     * layout handed to a 60-byte record type, say - is caught too. {@code REDEFINES} overlays are
     * excluded because an overlay is an alternative view of storage that is already counted;
     * {@code CVTRA03Y} declares none in any case.
     *
     * @param layout the layout to measure; normally {@link #LAYOUT}
     * @return the measured total, always {@link #RECORD_LENGTH} when it returns at all
     * @throws NullPointerException  if {@code layout} is {@code null}
     * @throws IllegalStateException if the storage spans do not sum to {@link #RECORD_LENGTH},
     *                               naming both the measured total and the shortfall or excess
     */
    public static int verifyDeclaredWidth(RecordLayout layout) {
        Objects.requireNonNull(layout, "A record layout is required to verify the declared width of "
                + "TRAN-TYPE-RECORD (CVTRA03Y, RECLN = 60)");
        int total = 0;
        for (FieldSpan span : layout.storageSpans()) {
            total += span.length();
        }
        if (total != RECORD_LENGTH) {
            throw new IllegalStateException("CVTRA03Y declares TRAN-TYPE-RECORD as "
                    + RECORD_LENGTH + " byte(s) - TRAN-TYPE 2 + TRAN-TYPE-DESC 50 + FILLER 8 - but "
                    + "the supplied layout's storage spans total " + total + " byte(s), which is "
                    + Math.abs(RECORD_LENGTH - total) + " byte(s) "
                    + (total < RECORD_LENGTH ? "short. A dropped trailing FILLER X(08) is the usual "
                            + "cause" : "too many"));
        }
        return total;
    }

    /**
     * Confirms this type's own {@link #LAYOUT} sums to {@link #RECORD_LENGTH}. The no-argument form of
     * {@link #verifyDeclaredWidth(RecordLayout)}, for a test or caller that wants the width proved on
     * demand.
     *
     * @return {@link #RECORD_LENGTH}, always 60
     * @throws IllegalStateException if the layout has been changed to describe any other width
     */
    public static int verifyDeclaredWidth() {
        return verifyDeclaredWidth(LAYOUT);
    }

    /**
     * The validated {@code CVTRA03Y} layout, for a repository or parity harness that serialises,
     * deserialises or diffs this record field by field.
     *
     * @return {@link #LAYOUT}; {@link RecordLayout} is immutable and copies its span list, so this is
     *         safe to hand out
     */
    public static RecordLayout layout() {
        return LAYOUT;
    }

    /**
     * Decodes a stored {@code TRANTYPE} row.
     *
     * <p>The row must be exactly {@link #RECORD_LENGTH} bytes. It is not padded and not truncated: the
     * measured fixture already carries 60 bytes per record, so a differently sized row means the
     * layout and the data disagree, and widening or trimming one here would hide that rather than
     * report it.
     *
     * @param record the stored 60 bytes, copied by this call and never retained by the caller's
     *               reference
     * @param codec  the codec whose charset the bytes are encoded in - {@code IBM037} for the EBCDIC
     *               dataset, {@code US-ASCII} for the text fixture
     * @return the decoded record
     * @throws NullPointerException     if {@code record} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code record.length} is not {@link #RECORD_LENGTH}
     */
    public static TranTypeRecord decode(byte[] record, FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to decode a TRANTYPE row: the code page of "
                + "fixed-width mainframe data is always stated explicitly, never assumed");
        Objects.requireNonNull(record, "Stored bytes are required to decode a TRANTYPE row; call "
                + "of(String, String, Charset) to build a record from field values instead");
        requireDeclaredWidth(record.length, "row");
        // wrap() copies the bytes and re-checks the width against the layout, so the array this
        // instance owns can never be reached through the caller's reference.
        return new TranTypeRecord(codec.wrap(record, LAYOUT).toByteArray(), codec);
    }

    /**
     * Decodes a stored {@code TRANTYPE} row under an explicitly named code page.
     *
     * @param record  the stored 60 bytes
     * @param charset the code page of the bytes, named by the caller and never defaulted
     * @return the decoded record
     * @throws NullPointerException     if {@code record} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code record.length} is not {@link #RECORD_LENGTH}, or if
     *                                  {@code charset} is not a single-byte code page for the digits,
     *                                  the sign overpunch characters and the space
     */
    public static TranTypeRecord decode(byte[] record, Charset charset) {
        return decode(record, new FixedWidthCodec(charset));
    }

    /**
     * Decodes a {@code TRANTYPE} row that has already been read as text - one 60-character line of
     * {@code app/data/ASCII/trantype.txt}, for instance.
     *
     * @param row   the 60-character row image, trailing padding included and nothing trimmed
     * @param codec the codec whose charset the row is encoded under
     * @return the decoded record
     * @throws NullPointerException     if {@code row} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code row} is not {@link #RECORD_LENGTH} characters long,
     *                                  or does not encode to exactly that many bytes
     */
    public static TranTypeRecord decode(String row, FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to decode a TRANTYPE row image");
        Objects.requireNonNull(row, "A row image is required; a null row cannot be distinguished "
                + "from a 60-byte record of spaces, and the two mean different things");
        requireDeclaredWidth(row.length(), "row image");
        return decode(codec.encodeImage(row, "a TRANTYPE row image"), codec);
    }

    /**
     * Decodes a {@code TRANTYPE} row image under an explicitly named code page.
     *
     * @param row     the 60-character row image
     * @param charset the code page of the row, named by the caller and never defaulted
     * @return the decoded record
     * @throws NullPointerException     if {@code row} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code row} is not {@link #RECORD_LENGTH} characters long
     */
    public static TranTypeRecord decode(String row, Charset charset) {
        return decode(row, new FixedWidthCodec(charset));
    }

    /**
     * Builds a {@code TRANTYPE} record from field values.
     *
     * <p>The record is initialised from {@link #LAYOUT} first, so the trailing {@code FILLER X(08)} is
     * space-filled - the COBOL convention for a reserved span that declares no {@code VALUE} - and
     * the two named fields are then written with the {@code PIC X} move rule: padded on the right when
     * short, truncated on the right when long, exactly as a COBOL alphanumeric {@code MOVE} behaves.
     * The result is always exactly {@link #RECORD_LENGTH} bytes.
     *
     * <p>Use this to construct an expected record for a parity case or a test. A record read from the
     * dataset should come through {@link #decode(byte[], FixedWidthCodec)} instead, because that
     * carries the stored reserved bytes across verbatim - the shipped fixture holds zeros there, not
     * spaces.
     *
     * @param tranType     the two-character type code, {@code 01} through {@code 07} in the shipped
     *                     data. Supplied as characters because {@code TRAN-TYPE} is {@code PIC X(02)}
     *                     and its leading zero is significant
     * @param tranTypeDesc the description, at its natural length; it is padded to 50 characters here
     * @param codec        the codec whose charset the record is encoded under
     * @return a record of exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException if any argument is {@code null}
     */
    public static TranTypeRecord of(String tranType, String tranTypeDesc, FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to build a TRANTYPE record");
        Objects.requireNonNull(tranType, "TRAN-TYPE is required; move SPACES explicitly to build a "
                + "record with a blank key");
        Objects.requireNonNull(tranTypeDesc, "TRAN-TYPE-DESC is required; move SPACES explicitly to "
                + "build a record with a blank description");
        FixedWidthRecord area = codec.newRecord(LAYOUT);
        codec.writePicX(area, TRAN_TYPE, tranType);
        codec.writePicX(area, TRAN_TYPE_DESC, tranTypeDesc);
        return new TranTypeRecord(area.toByteArray(), codec);
    }

    /**
     * Builds a {@code TRANTYPE} record from field values under an explicitly named code page.
     *
     * @param tranType     the two-character type code
     * @param tranTypeDesc the description, padded to 50 characters here
     * @param charset      the code page of the record, named by the caller and never defaulted
     * @return a record of exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException if any argument is {@code null}
     */
    public static TranTypeRecord of(String tranType, String tranTypeDesc, Charset charset) {
        return of(tranType, tranTypeDesc, new FixedWidthCodec(charset));
    }

    /**
     * An initialised, empty {@code TRANTYPE} record: 60 bytes, every one of them the charset's space
     * byte, with the {@code FILLER} span present and space-filled. This is the state a COBOL
     * {@code INITIALIZE} of {@code TRAN-TYPE-RECORD} leaves, since neither named field nor the
     * {@code FILLER} declares a {@code VALUE}.
     *
     * @param codec the codec whose charset the record is encoded under
     * @return a space-filled record of exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public static TranTypeRecord empty(FixedWidthCodec codec) {
        return of("", "", codec);
    }

    /**
     * An initialised, empty {@code TRANTYPE} record under an explicitly named code page.
     *
     * @param charset the code page of the record, named by the caller and never defaulted
     * @return a space-filled record of exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public static TranTypeRecord empty(Charset charset) {
        return empty(new FixedWidthCodec(charset));
    }

    /**
     * Rejects any width but the copybook's, naming the copybook, the expected width and what was
     * actually supplied, so a mismatch is diagnosable from the message alone.
     *
     * @param actual what the caller supplied
     * @param what   the noun for the message - {@code "row"} or {@code "row image"}
     */
    private static void requireDeclaredWidth(int actual, String what) {
        if (actual != RECORD_LENGTH) {
            throw new IllegalArgumentException("A TRANTYPE " + what + " is " + actual
                    + " unit(s) wide but CVTRA03Y declares TRAN-TYPE-RECORD as " + RECORD_LENGTH
                    + " (TRAN-TYPE 2 + TRAN-TYPE-DESC 50 + FILLER 8), and CBTRN03C:73-75 splits it as "
                    + "2 + 58. A fixed-width record is exactly its declared width: neither pad nor "
                    + "truncate it here, because a differently sized row means the layout and the "
                    + "data disagree");
        }
    }

    /**
     * {@code TRAN-TYPE PIC X(02)} - the transaction type code, exactly 2 characters and untrimmed.
     *
     * <p>A {@link String} rather than a number, because the PICTURE is {@code X(02)}: the values look
     * numeric but the leading zero is part of the key and the comparison is character-based.
     *
     * @return the 2-character type code, {@code 01} through {@code 07} in the shipped data
     */
    public String tranType() {
        return codec.readPicX(area(), TRAN_TYPE);
    }

    /**
     * The {@code TRANTYPE} KSDS key, which is {@code TRAN-TYPE} viewed in its role as the record key.
     *
     * <p>Identical bytes to {@link #tranType()}, exposed under the name a keyed lookup uses:
     * {@code CBTRN03C:189} moves {@code TRAN-TYPE-CD OF TRAN-RECORD} into {@code FD-TRAN-TYPE} and
     * then performs {@code 1500-B-LOOKUP-TRANTYPE}, so the key travels as a two-character string and
     * this accessor's type has to line up with it exactly.
     *
     * @return the 2-character key image, always {@link #TRAN_TYPE_KEY_LENGTH} characters
     */
    public String tranTypeKey() {
        return tranType();
    }

    /**
     * {@code TRAN-TYPE-DESC PIC X(50)} - the description, <strong>untrimmed</strong>: all 50
     * characters, trailing space padding included.
     *
     * <p>The padding is part of the field's value. {@code CBTRN03C:366} moves this field into a
     * {@code PIC X(15)} receiver and COBOL discards what does not fit on the right, so the report
     * carries the first 15 of these 50 characters - see {@link #tranTypeDescMovedTo(int)}. Nothing is
     * trimmed here because {@code CBTRN03C} itself never trims, and because trimming would break both
     * the byte-identical round trip and the record's 60-byte width.
     *
     * @return exactly {@link #TRAN_TYPE_DESC_LENGTH} characters
     */
    public String tranTypeDesc() {
        return codec.readPicX(area(), TRAN_TYPE_DESC);
    }

    /**
     * Performs a COBOL alphanumeric {@code MOVE} of {@code TRAN-TYPE-DESC} into a receiver of the
     * given width: padded on the right when the receiver is wider, and
     * <strong>truncated on the right</strong> when it is narrower.
     *
     * <p>This is {@code app/cbl/CBTRN03C.cbl:366},
     * {@code MOVE TRAN-TYPE-DESC TO TRAN-REPORT-TYPE-DESC}, whose receiver
     * {@code app/cpy/CVTRA07Y.cpy:22} declares as {@code PIC X(15)} - so
     * {@code tranTypeDescMovedTo(REPORT_TYPE_DESC_LENGTH)} yields exactly what the detail report
     * shows: {@code "Authorization  "} for type {@code 04}, {@code "Purchase       "} for type
     * {@code 01}. The receiver width is a parameter rather than a hard-coded 15 so this record type
     * states no report geometry of its own, and the truncation is delegated to
     * {@link FixedWidthCodec#movePicX(String, int)} so its direction is named at the point it happens
     * rather than implied by a {@code substring}.
     *
     * @param receiverLength the receiving field's declared width in characters; at least 1
     * @return an image of exactly {@code receiverLength} characters
     * @throws IllegalArgumentException if {@code receiverLength} is below 1
     */
    public String tranTypeDescMovedTo(int receiverLength) {
        return codec.movePicX(tranTypeDesc(), receiverLength);
    }

    /**
     * The trailing {@code FILLER PIC X(08)} as text, exactly as this record carries it.
     *
     * <p>Exposed because the content is genuinely data-dependent and worth being able to see: a
     * record built by {@link #of(String, String, Charset)} holds eight spaces, while every row of
     * {@code app/data/ASCII/trantype.txt} holds {@code "00000000"} - eight ASCII zeros. Both are
     * reproduced faithfully rather than normalised, which is why this class keeps the whole image.
     *
     * @return exactly {@link #FILLER_LENGTH} characters
     */
    public String filler() {
        return codec.readPicX(area(), FILLER);
    }

    /**
     * The raw bytes of {@code TRAN-TYPE}, for a caller reproducing a {@code DISPLAY} or diffing byte
     * for byte.
     *
     * @return a copy of the {@link #TRAN_TYPE_LENGTH} bytes at offset {@link #TRAN_TYPE_OFFSET}
     */
    public byte[] tranTypeBytes() {
        return area().readSpanBytes(TRAN_TYPE);
    }

    /**
     * The raw bytes of {@code TRAN-TYPE-DESC}, all {@link #TRAN_TYPE_DESC_LENGTH} of them.
     *
     * @return a copy of the 50 bytes at offset {@link #TRAN_TYPE_DESC_OFFSET}
     */
    public byte[] tranTypeDescBytes() {
        return area().readSpanBytes(TRAN_TYPE_DESC);
    }

    /**
     * The raw bytes of the trailing {@code FILLER}, carried verbatim from wherever this record came
     * from.
     *
     * @return a copy of the {@link #FILLER_LENGTH} bytes at offset {@link #FILLER_OFFSET}
     */
    public byte[] fillerBytes() {
        return area().readSpanBytes(FILLER);
    }

    /**
     * The complete record as it would be written to the dataset: exactly {@link #RECORD_LENGTH}
     * bytes, {@code FILLER} included and unaltered.
     *
     * <p>This is the encode side of the round trip, and it is byte-identical to the row that
     * {@link #decode(byte[], FixedWidthCodec)} was given - reserved bytes included, because they are
     * carried rather than rebuilt.
     *
     * @return a fresh copy of all 60 bytes; the internal array is never handed out
     */
    public byte[] toByteArray() {
        return image.clone();
    }

    /**
     * The complete record as a 60-character image, for reproducing a COBOL {@code DISPLAY} of
     * {@code TRAN-TYPE-RECORD} exactly.
     *
     * @return exactly {@link #RECORD_LENGTH} characters, untrimmed
     */
    public String image() {
        return area().readString(0, RECORD_LENGTH);
    }

    /**
     * The record width in bytes, always {@link #RECORD_LENGTH}. Present so a caller can assert the
     * width against the copybook without reaching for the constant.
     *
     * @return 60
     */
    public int recordLength() {
        return image.length;
    }

    /**
     * The code page this record's bytes are encoded in, as supplied by the caller that created it.
     *
     * @return the charset, never {@code null} and never a platform default
     */
    public Charset charset() {
        return codec.charset();
    }

    /**
     * Wraps this record's bytes as an addressable record area. A fresh area over a fresh copy on every
     * call, so no accessor can mutate the instance and no two callers share a buffer.
     *
     * @return a record area of exactly {@link #RECORD_LENGTH} bytes
     */
    private FixedWidthRecord area() {
        return FixedWidthRecord.copyOf(image, RECORD_LENGTH, codec.charset());
    }

    /**
     * Two records are equal when their 60 bytes and their code page are equal.
     *
     * <p>The comparison is over the whole image rather than the two named fields, so a difference in
     * the reserved span - spaces where the dataset holds zeros - is a difference here too. That is
     * deliberate: those eight bytes are written to the dataset like any others, and a comparison that
     * ignored them would report two records as identical while they serialise differently.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a {@code TranTypeRecord} with the same bytes and
     *         charset
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TranTypeRecord that)) {
            return false;
        }
        return Arrays.equals(image, that.image) && codec.charset().equals(that.codec.charset());
    }

    /**
     * A hash consistent with {@link #equals(Object)}, over the whole image and the code page.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(image) + codec.charset().hashCode();
    }

    /**
     * A diagnostic rendering naming the record, its key, its description trimmed of trailing padding
     * for legibility, its reserved bytes and its charset.
     *
     * <p>The trimming is for the log line only. It is emphatically not how the field is read -
     * {@link #tranTypeDesc()} returns all 50 characters - and nothing derived from this string may be
     * written to a dataset or compared for parity.
     *
     * @return for example
     *         {@code TRAN-TYPE-RECORD[TRAN-TYPE=04, TRAN-TYPE-DESC='Authorization' (50 bytes),
     *         FILLER='00000000', 60 bytes, US-ASCII]}
     */
    @Override
    public String toString() {
        return "TRAN-TYPE-RECORD[TRAN-TYPE=" + tranType()
                + ", TRAN-TYPE-DESC='" + codec.readPicXTrimmed(area(), TRAN_TYPE_DESC)
                + "' (" + TRAN_TYPE_DESC_LENGTH + " bytes), FILLER='" + filler()
                + "', " + recordLength() + " bytes, " + charset().name() + "]";
    }
}

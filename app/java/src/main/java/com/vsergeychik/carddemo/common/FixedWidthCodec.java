package com.vsergeychik.carddemo.common;

import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * COBOL {@code PICTURE} semantics over a fixed-width record area: the one place in this module where
 * a cross-width {@code MOVE} is implemented, and the only place a zoned {@code DISPLAY} field is
 * converted to and from a decimal value.
 *
 * <h2>Position in the module</h2>
 * This is the <strong>upper</strong> of the two fixed-width layers. It sits on top of
 * {@link FixedWidthRecord}, which knows <em>where</em> a field sits and <em>how wide</em> it is, and
 * it adds the knowledge of what a field's bytes <em>mean</em>. Scaled arithmetic is delegated to
 * {@link CobolDecimal}, so no rounding decision is ever taken here. The dependency direction is
 * strictly one-way:
 * <pre>
 *   FixedWidthCodec  ->  FixedWidthRecord   (spans, offsets, the charset boundary)
 *                    ->  CobolDecimal       (scale, and the truncating rounding policy)
 * </pre>
 * Nothing is imported from the sibling configuration package and nothing from Spring. The
 * {@code common} package is the root of this module's dependency graph, and importing the
 * configuration package that resolves the code pages would invert that root and close a cycle around
 * it. This class carries no framework annotation of any kind; where a container-managed instance is
 * wanted, the configuration package declares one with {@code @Bean} and passes the resolved
 * {@link Charset} to the constructor below.
 *
 * <h2>Hand-written by mandate</h2>
 * Every pad, truncate and offset decision is an explicit, reviewable statement. No copybook parser,
 * no annotation-and-reflection mapper and no schema-driven binder is used, so a reviewer holding the
 * copybook open beside a layout declaration can confirm each field by eye and diff it line by line.
 *
 * <h2>The charset is supplied by the caller and never assumed</h2>
 * A {@link Charset} is a mandatory constructor argument. No method derives one from the platform and
 * none consults a platform-wide setting, because a fixed-width mainframe record is bytes in a
 * specific code page and guessing that code page corrupts every one of them. Two code pages are used
 * in this system, both named explicitly at the point of use: {@code IBM037} for the EBCDIC datasets
 * and {@code US-ASCII} for the text fixtures under {@code app/data/ASCII}.
 *
 * <p>Note where the conversion actually happens. This class works almost entirely in
 * <em>characters</em>: a field <em>image</em> is a {@link String} of exactly the field's declared
 * width, and {@link FixedWidthRecord} performs the single byte-to-character conversion at the
 * boundary. That is deliberate. It means the pad and overpunch characters are named once, as
 * characters, and the code page decides their bytes exactly once, so no byte constant is duplicated
 * between the two layers where the two copies could later disagree.
 *
 * <h2>Rule 1 - {@code MOVE} truncates alphanumeric data on the right and numeric data on the left</h2>
 * This asymmetry is the reason this class exists. Java's assignment operator does neither, so a
 * plain assignment across differing widths is silently wrong in one direction or the other. The
 * legacy source contains <strong>2,795 {@code MOVE} statements</strong> against 94 named arithmetic
 * statements, which makes {@code MOVE} - not arithmetic - the dominant source of silent divergence
 * in this migration. Every cross-width move therefore routes through
 * {@link #movePicX(String, int)} or {@link #movePic9(String, int)}, whose names state the direction
 * at the call site.
 * <table border="1">
 *   <caption>Width rules, and the direction each truncates</caption>
 *   <tr><th>{@code PICTURE}</th><th>Java form</th><th>Width</th><th>Pad</th><th>Truncates</th></tr>
 *   <tr><td>{@code PIC X(n)}</td><td>{@link String}</td><td>{@code n}</td>
 *       <td>spaces on the right</td><td>on the <strong>right</strong></td></tr>
 *   <tr><td>{@code PIC 9(n)}</td><td>{@code int} / {@code long}</td><td>{@code n}</td>
 *       <td>zeros on the left</td><td>on the <strong>left</strong></td></tr>
 *   <tr><td>{@code PIC S9(p)V99}</td><td>{@link BigDecimal} scale 2</td><td>{@code p + 2}</td>
 *       <td>zeros on the left</td><td>fraction, then high-order digits</td></tr>
 * </table>
 * The numeric rule is proven by the source rather than asserted. {@code app/cbl/CBACT04C.cbl:483}
 * reads {@code MOVE '05' TO TRAN-CAT-CD}, and {@code app/cpy/CVTRA05Y.cpy:7} declares that receiver
 * {@code PIC 9(04)}. A two-character literal into a four-digit numeric-display field yields
 * {@code 0005} - zero-filled on the <em>left</em>. Right-padding would give {@code 0500} and
 * right-truncation of an over-wide value would keep the high-order digits instead of the low-order
 * ones; both are the classic errors, and both are asserted against in the tests.
 *
 * <h2>Rule 2 - a signed field has no sign byte of its own</h2>
 * {@code PIC S9(p)V99} occupies exactly {@code p + 2} bytes. The sign is <em>overpunched</em> into
 * the trailing byte, whose zone nibble carries it, and consumes no byte. Proven twice by arithmetic:
 * {@code app/cpy/CVACT01Y.cpy} sums to its documented {@code RECLN 300} only when each of its five
 * {@code S9(10)V99} fields is 12 bytes wide - reserving a sign byte for each would give 305 - and
 * {@code app/cpy/CVTRA05Y.cpy} sums to its documented {@code RECLN = 350} only when
 * {@code TRAN-AMT PIC S9(09)V99} is 11 bytes wide.
 *
 * <p>The overpunch alphabet was <strong>measured from the fixtures</strong> rather than assumed. The
 * trailing character of a signed span is:
 * <table border="1">
 *   <caption>Trailing-character sign overpunch, as measured</caption>
 *   <tr><th>Digit</th><th>0</th><th>1</th><th>2</th><th>3</th><th>4</th><th>5</th><th>6</th>
 *       <th>7</th><th>8</th><th>9</th></tr>
 *   <tr><td>positive</td><td><code>&#123;</code></td><td>A</td><td>B</td><td>C</td><td>D</td><td>E</td>
 *       <td>F</td><td>G</td><td>H</td><td>I</td></tr>
 *   <tr><td>negative</td><td><code>&#125;</code></td><td>J</td><td>K</td><td>L</td><td>M</td><td>N</td>
 *       <td>O</td><td>P</td><td>Q</td><td>R</td></tr>
 * </table>
 * All five {@code S9(10)V99} spans of all 50 records of {@code app/data/ASCII/acctdata.txt} end in
 * <code>&#123;</code>, as do every {@code TRAN-CAT-BAL} in {@code tcatbal.txt} and every
 * {@code DIS-INT-RATE} in {@code discgrp.txt}; {@code dailytran.txt} exercises the complete
 * alphabet, both signs. Three of those byte images decode as follows, and re-encoding each returns
 * the identical bytes:
 * <pre>
 *   "00000001940{"  S9(10)V99  ->    194.00     app/data/ASCII/acctdata.txt,  ACCT-CURR-BAL
 *   "0000005047G"   S9(09)V99  ->    504.77     app/data/ASCII/dailytran.txt, DALYTRAN-AMT
 *   "0000009190}"   S9(09)V99  ->   -919.00     app/data/ASCII/dailytran.txt, DALYTRAN-AMT
 * </pre>
 * That alphabet is the EBCDIC zoned-decimal sign convention - zone {@code C} positive, zone
 * {@code D} negative - and it is <em>the same characters</em> whichever of the two code pages is in
 * play, because {@code IBM037} decodes {@code 0xC0}-{@code 0xC9} to <code>&#123;ABCDEFGHI</code> and
 * {@code 0xD0}-{@code 0xD9} to <code>&#125;JKLMNOPQR</code>, while the ASCII fixtures spell those same
 * characters directly. Handling the overpunch as characters therefore needs one code path rather
 * than one per code page. A trailing plain digit, which is the unsigned zone {@code F} form, is also
 * accepted on read and taken as positive.
 *
 * <h2>Rule 3 - every scale is 2, and rounding is always truncation</h2>
 * A scan of every {@code PICTURE} in {@code app/cbl} and {@code app/cpy} finds exactly one scaled
 * form, {@code V99}. Every scaled numeric in this system is therefore scale 2. The keyword
 * {@code ROUNDED} appears <strong>zero</strong> times in all 28 programs, so COBOL truncates excess
 * fractional digits on store, and truncation toward zero is the only faithful choice. No rescaling
 * call and no rounding-policy selection appears anywhere below: every scale adjustment is delegated
 * to {@link CobolDecimal#store(BigDecimal, int)} and
 * {@link CobolDecimal#storeAtPicture(BigDecimal, int, int)}, so the policy that governs all
 * fixed-point arithmetic in this system can be audited by reading that one class. A search of this
 * file for a rescaling call or a rounding mode is expected to find nothing, and that is the point.
 *
 * <h2>Rule 4 - no packed-decimal support, by design</h2>
 * {@code COMP-3} and {@code PACKED-DECIMAL} appear <strong>zero</strong> times in all 28 copybooks
 * under {@code app/cpy}. Packed decimal occurs only in {@code WORKING-STORAGE}, in five programs,
 * for counters and intermediates that are never persisted. Every persisted numeric field is
 * consequently zoned {@code DISPLAY} - one digit per byte - and this class contains no nibble
 * packing or unpacking whatsoever. The omission is a considered response to verified evidence rather
 * than an oversight: speculative packed-decimal handling would be unreachable code that dilutes the
 * branch-coverage ratio while buying no behaviour.
 *
 * <h2>Rule 5 - a {@code FILLER} emits its declared {@code VALUE}, and a pad only otherwise</h2>
 * {@code FILLER} is always present and never inferred; dropping one makes the record width wrong,
 * which shifts every subsequent byte in the dataset. The general guidance that {@code FILLER} is
 * "emitted as spaces" holds for the persisted record copybooks - {@code CVACT01Y} {@code X(178)},
 * {@code CVACT02Y} {@code X(59)}, {@code CVACT03Y} {@code X(14)}, {@code CVCUS01Y} {@code X(168)},
 * {@code CVTRA05Y} {@code X(20)} - but it is <strong>not</strong> universally true, and the
 * exception is supported here rather than normalised away. {@code app/cpy/CSDAT01Y.cpy} declares
 * separator fillers carrying explicit non-space literals: {@code FILLER PIC X(01) VALUE '/'} twice
 * in {@code WS-CURDATE-MM-DD-YY}, {@code ':'} twice in {@code WS-CURTIME-HH-MM-SS}, and
 * {@code '-'}, {@code ' '}, {@code ':'} and {@code '.'} in {@code WS-TIMESTAMP}. Blanket
 * space-filling would blank every date and time separator in the system, turning {@code 12/25/24}
 * into {@code 12 25 24}. The implemented rule is therefore: <em>a span emits its declared literal
 * when one is present, and a pad byte only when none is</em>. See
 * {@link #writeDeclaredValue(FixedWidthRecord, FieldSpan)}.
 *
 * <h2>Rule 6 - widths are always supplied by the caller</h2>
 * No dataset record length and no dataset name is written in this file. Record lengths reach a
 * caller from the {@code carddemo.datasets.*} bindings in {@code application.yml}, or from the
 * copybook a model type represents, and arrive here as a {@link RecordLayout} or an explicit
 * {@code int}. That gives the system one auditable width source. The same applies to the two
 * measured fixture-width deviations: {@code app/data/ASCII/cardxref.txt} carries 36 bytes per record
 * where {@code app/cpy/CVACT03Y.cpy} declares 50, because the fixture omits the trailing
 * {@code FILLER X(14)}, and the {@code USRSEC} test seed carries 57 bytes where
 * {@code app/cpy/CSUSR01Y.cpy} declares 80, omitting {@code SEC-USR-FILLER X(23)}. Both are the same
 * operation, and both are served by {@link #padToDeclaredWidth(byte[], int)} with the target width
 * passed in - neither pair of numbers appears in this file. The deviations are recorded here as
 * findings; the fixtures themselves are read-only and are never rewritten to match.
 *
 * <h2>Out of scope here</h2>
 * Edited report pictures - {@code app/cpy/CVTRA07Y.cpy}'s {@code -ZZZ,ZZZ,ZZZ.ZZ} and
 * {@code +ZZZ,ZZZ,ZZZ.ZZ} masks, its {@code ALL '-'} separator line and its {@code ALL '.'} leaders
 * - belong to the report layout type in the transaction package, not here. This class supplies only
 * the primitive pad and truncate operations a mask formatter builds on.
 *
 * <h2>Thread safety</h2>
 * Instances are immutable and hold only the {@link Charset}, so one instance is safe to share across
 * threads. There is no static mutable state and no cache: the two overpunch tables are immutable
 * {@link String} constants. The {@link FixedWidthRecord} instances passed to and returned from these
 * methods are mutable per-instance record areas and must be confined to the request, step or chunk
 * that owns them.
 *
 * @see FixedWidthRecord
 * @see CobolDecimal
 */
public final class FixedWidthCodec {

    /**
     * The positive sign overpunch, indexed by the trailing digit: zone {@code C} of the zoned
     * decimal representation. {@code POSITIVE_OVERPUNCH.charAt(7)} is {@code 'G'}, which is why
     * {@code +504.77} in an {@code S9(09)V99} field is written {@code "0000005047G"}.
     *
     * <p>The alphabet itself is declared once, in {@link FixedWidthRecord.ZonedSign}, and referenced
     * here rather than restated. Where the sign lives is a fact about storage geometry that the byte
     * layer owns - it is why a signed field occupies {@code p + s} bytes and not one more - while what
     * the digits mean is this layer's concern. Two copies of the alphabet could drift apart, and a
     * drift in the sign alphabet would corrupt every signed field in the system.
     */
    private static final String POSITIVE_OVERPUNCH = FixedWidthRecord.ZonedSign.POSITIVE_DIGITS;

    /**
     * The negative sign overpunch, indexed by the trailing digit: zone {@code D} of the zoned
     * decimal representation. Its element for digit 0 is the right curly bracket, which is why
     * {@code -919.00} in an {@code S9(09)V99} field is written {@code 0000009190&#125;}.
     */
    private static final String NEGATIVE_OVERPUNCH = FixedWidthRecord.ZonedSign.NEGATIVE_DIGITS;

    /**
     * Every character this codec places into a numeric {@code DISPLAY} span, plus the space that
     * pads an alphanumeric one. The constructor requires the supplied charset to encode each of them
     * to exactly one byte, because a zoned field of {@code p + s} digits occupies {@code p + s}
     * bytes and a multi-byte encoding of any digit or overpunch character would silently widen it.
     */
    private static final String SINGLE_BYTE_REPERTOIRE =
            "0123456789" + POSITIVE_OVERPUNCH + NEGATIVE_OVERPUNCH + " ";

    /** The character COBOL pads an alphanumeric field with, and the {@code FILLER} default. */
    private static final char SPACE = ' ';

    /** The character COBOL pads a numeric {@code DISPLAY} field with, on the left. */
    private static final char ZERO = '0';

    /**
     * The code page of the data this codec reads and writes. Supplied by the caller, never derived
     * from the platform, and immutable for the life of the instance so a single record cannot be
     * built from two code pages.
     */
    private final Charset charset;

    /**
     * The strict single-byte text seam for {@link #charset}. Every conversion between stored bytes and
     * characters that this codec performs, and every conversion its callers perform on a whole record
     * image, goes through it, so a malformed stored byte and an unrepresentable character are each
     * refused where they occur instead of being substituted with a character that looks like data.
     */
    private final FixedWidthRecord.Transcoder transcoder;

    /**
     * Creates a codec for one code page.
     *
     * @param charset the code page of the fixed-width data, named explicitly by the caller -
     *                {@code IBM037} for the EBCDIC datasets, {@code US-ASCII} for the text fixtures.
     *                It must encode each digit, each sign overpunch character and the space to
     *                exactly one byte
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} cannot encode, or does not encode to
     *                                  exactly one byte, any digit, sign overpunch character or the
     *                                  space
     */
    public FixedWidthCodec(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required: fixed-width mainframe data is bytes "
                + "in a specific code page, so the code page must be stated explicitly and is never "
                + "derived from the platform");
        FixedWidthRecord.Transcoder measure = new FixedWidthRecord.Transcoder(charset);
        for (int index = 0; index < SINGLE_BYTE_REPERTOIRE.length(); index++) {
            char required = SINGLE_BYTE_REPERTOIRE.charAt(index);
            int width = measure.measuredWidthOf(required);
            if (width != 1) {
                throw new IllegalArgumentException("Charset " + charset.name() + " encodes '"
                        + required + "' to " + width + " byte(s); a zoned DISPLAY field of "
                        + "n digits occupies exactly n bytes, so every digit, every sign overpunch "
                        + "character and the space must encode to exactly one byte");
            }
        }
        this.charset = charset;
        this.transcoder = measure;
    }

    /**
     * The code page this codec reads and writes, as supplied at construction.
     *
     * @return the charset, never {@code null}
     */
    public Charset charset() {
        return charset;
    }

    /**
     * Encodes a record image under this codec's code page, refusing any character the code page cannot
     * represent.
     *
     * <p>This is the entry point for a model or repository holding a row as text and needing the bytes
     * of it - the counterpart of {@link #decodeImage(byte[], String)}. It exists so that nothing in the
     * module has a reason to call {@code String.getBytes(Charset)}, whose defined behaviour is to
     * substitute {@code ?} for an unrepresentable character and so to write a value into a dataset that
     * no COBOL program could have produced.
     *
     * @param image   the row image
     * @param subject what is being encoded, named in any diagnostic - a record or field name. Never
     *                the content itself, because these spans carry card numbers, government
     *                identifiers and passwords
     * @return the encoded bytes, one per character
     * @throws NullPointerException     if {@code image} or {@code subject} is {@code null}
     * @throws IllegalArgumentException if a character is unrepresentable in this code page
     */
    public byte[] encodeImage(String image, String subject) {
        return transcoder.encode(image, subject);
    }

    /**
     * Decodes a record image under this codec's code page, refusing any byte that is not a character
     * in it.
     *
     * @param image   the stored bytes, decoded in full
     * @param subject what is being decoded, named in any diagnostic. Never the content itself
     * @return the decoded image, one character per byte
     * @throws NullPointerException  if {@code image} or {@code subject} is {@code null}
     * @throws IllegalStateException if a stored byte is not valid data in this code page
     */
    public String decodeImage(byte[] image, String subject) {
        Objects.requireNonNull(image, "Stored bytes are required to decode an image");
        return transcoder.decode(image, 0, image.length, subject);
    }

    // =================================================================================================
    // Record plumbing. Both entry points run the layout's self-check before any byte is touched, so a
    // layout whose spans do not account for exactly its declared record length fails immediately and
    // names the offending descriptor, rather than producing a record that is quietly the wrong width.
    // =================================================================================================

    /**
     * Allocates a record over a layout and initialises it, so every {@code FILLER} and every span
     * declaring a {@code VALUE} is already correct before the caller writes a single field.
     *
     * <p>The layout's self-check has already run - a {@link RecordLayout} cannot be constructed
     * without it - so the returned record's width is guaranteed to equal the sum of its declared
     * spans, {@code FILLER} included.
     *
     * @param layout the record's layout, transcribed from its copybook
     * @return a newly allocated record of the layout's declared length, established per
     *         {@link FixedWidthRecord#forLayout(RecordLayout, Charset)}
     * @throws NullPointerException if {@code layout} is {@code null}
     */
    public FixedWidthRecord newRecord(RecordLayout layout) {
        Objects.requireNonNull(layout, "A record layout is required to allocate a record; declare "
                + "every field and every FILLER from the copybook");
        return FixedWidthRecord.forLayout(layout, charset);
    }

    /**
     * Wraps stored bytes as a record of a layout, for reading.
     *
     * <p>The byte count must equal the layout's declared record length exactly. A dataset row that
     * is short because its fixture omits a trailing {@code FILLER} must be widened first with
     * {@link #padToDeclaredWidth(byte[], int)}; silently tolerating a short row here would let every
     * subsequent field offset drift.
     *
     * @param record the stored bytes, defensively copied by the record
     * @param layout the record's layout, transcribed from its copybook
     * @return a record over a copy of {@code record}
     * @throws NullPointerException     if {@code record} or {@code layout} is {@code null}
     * @throws IllegalArgumentException if {@code record.length} differs from the layout's declared
     *                                  record length
     */
    public FixedWidthRecord wrap(byte[] record, RecordLayout layout) {
        Objects.requireNonNull(record, "Stored bytes are required to wrap a record");
        Objects.requireNonNull(layout, "A record layout is required to wrap stored bytes");
        if (record.length != layout.recordLength()) {
            throw new IllegalArgumentException("Supplied " + record.length + " byte(s) for a layout "
                    + "declaring a record length of " + layout.recordLength() + "; a fixed-width "
                    + "record must match its declared width exactly. If the row is short because a "
                    + "trailing FILLER is absent from the source data, widen it first with "
                    + "padToDeclaredWidth(byte[], int)");
        }
        return FixedWidthRecord.copyOf(record, layout.recordLength(), charset);
    }

    // =================================================================================================
    // PIC X(n) - alphanumeric. Left justified, padded on the right with spaces, truncated on the RIGHT.
    // =================================================================================================

    /**
     * Performs a COBOL alphanumeric {@code MOVE}: returns {@code source} as an image of exactly
     * {@code targetLength} characters, padded on the right with spaces when it is shorter and
     * <strong>truncated on the right</strong> when it is longer.
     *
     * <p>Right truncation is the COBOL rule for a {@code PIC X} receiver: the receiving field is
     * filled from its leftmost character position and any sending character that does not fit is
     * discarded. So {@code "ABCDEF"} into a {@code PIC X(04)} field yields {@code "ABCD"}, never
     * {@code "CDEF"}. This is the opposite of {@link #movePic9(String, int)}, and stating the
     * direction in the method name is the whole point: a plain Java assignment would neither pad nor
     * truncate, and the resulting parity defect is invisible at the call site.
     *
     * @param source       the sending value; may be shorter or longer than the receiver, and may be
     *                     empty
     * @param targetLength the receiving field's declared width in characters; at least 1
     * @return an image of exactly {@code targetLength} characters
     * @throws NullPointerException     if {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code targetLength} is below 1
     */
    public String movePicX(String source, int targetLength) {
        Objects.requireNonNull(source, "A sending value is required for an alphanumeric MOVE; to "
                + "blank a field move an empty string or SPACES explicitly");
        requirePositiveLength(targetLength, "an alphanumeric receiver");
        if (source.length() == targetLength) {
            return source;
        }
        if (source.length() > targetLength) {
            // COBOL fills a PIC X receiver from the left and discards the overflow, so the surviving
            // characters are the leading ones.
            return source.substring(0, targetLength);
        }
        return source + repeat(SPACE, targetLength - source.length());
    }

    /**
     * Writes a value into an alphanumeric span, applying the {@code PIC X} move rule first so an
     * over-wide value is truncated on the right rather than rejected.
     *
     * @param record the record area to write into
     * @param field  the descriptor naming the span; its length is the receiver's declared width
     * @param value  the sending value
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if the span is not wide enough to be a receiver, that is if
     *                                  its declared length is below 1
     */
    public void writePicX(FixedWidthRecord record, FieldSpan field, String value) {
        Objects.requireNonNull(record, "A record area is required to write a field");
        Objects.requireNonNull(field, "A field descriptor is required to write a named span");
        record.writeSpan(field, movePicX(value, field.length()));
    }

    /**
     * Reads an alphanumeric span, <strong>untrimmed</strong>.
     *
     * <p>A {@code PIC X} field is space-padded to its full declared width and that padding is part
     * of the field's value: the parity differ compares the field byte for byte, so trimming here
     * would discard bytes it is meant to compare. Trimming is available, but only as the separate
     * and deliberately-named {@link #readPicXTrimmed(FixedWidthRecord, FieldSpan)}, so that every
     * trim in the system is a visible choice made where the COBOL itself trims.
     *
     * @param record the record area to read from
     * @param field  the descriptor naming the span
     * @return the span's characters, exactly {@code field.length()} of them
     * @throws NullPointerException if {@code record} or {@code field} is {@code null}
     */
    public String readPicX(FixedWidthRecord record, FieldSpan field) {
        Objects.requireNonNull(record, "A record area is required to read a field");
        Objects.requireNonNull(field, "A field descriptor is required to read a named span");
        return record.readSpan(field);
    }

    /**
     * Reads an alphanumeric span and removes its trailing spaces.
     *
     * <p>Trailing spaces only. COBOL left-justifies a {@code PIC X} field and pads it on the right,
     * so trailing spaces are padding while a leading space is data - a name field genuinely
     * beginning with a space would be corrupted by a two-sided trim. Use this where the COBOL itself
     * trims, for example before concatenating a name into a display line, and use
     * {@link #readPicX(FixedWidthRecord, FieldSpan)} everywhere else.
     *
     * @param record the record area to read from
     * @param field  the descriptor naming the span
     * @return the span's characters with trailing spaces removed; empty when the span is all spaces
     * @throws NullPointerException if {@code record} or {@code field} is {@code null}
     */
    public String readPicXTrimmed(FixedWidthRecord record, FieldSpan field) {
        String image = readPicX(record, field);
        int end = image.length();
        while (end > 0 && image.charAt(end - 1) == SPACE) {
            end--;
        }
        return image.substring(0, end);
    }

    // =================================================================================================
    // PIC 9(n) - unsigned zoned DISPLAY. Right justified, padded on the LEFT with zeros, truncated on
    // the LEFT. This is the mirror image of the alphanumeric rule above and the direction that is most
    // often got wrong.
    // =================================================================================================

    /**
     * Performs a COBOL numeric {@code MOVE}: returns {@code source} as an image of exactly
     * {@code targetLength} digits, padded on the left with zeros when it is shorter and
     * <strong>truncated on the left</strong> when it is longer, so the receiver keeps the low-order
     * digits.
     *
     * <p>The direction is proven by the source. {@code app/cbl/CBACT04C.cbl:483} reads
     * {@code MOVE '05' TO TRAN-CAT-CD} and {@code app/cpy/CVTRA05Y.cpy:7} declares that receiver
     * {@code PIC 9(04)}: the two-character literal lands in a four-digit numeric-display field as
     * {@code 0005}, zero-filled on the left. And because a numeric receiver is aligned on its
     * <em>implied decimal point</em>, an over-wide value loses its high-order digits, not its
     * low-order ones - {@code 123456} into a {@code PIC 9(04)} field yields {@code 3456}, never
     * {@code 1234}. COBOL does not report that loss unless the program asks for it with
     * {@code ON SIZE ERROR}, and no program in this codebase does, so no exception is thrown here
     * either.
     *
     * <p>The sending value is accepted as a {@link String} rather than a number precisely so the
     * {@code MOVE '05'} case is expressible: in COBOL that statement moves an alphanumeric literal
     * into a numeric field. Only digits are accepted, because every unsigned numeric span of every
     * fixture under {@code app/data/ASCII} was measured to contain digits and nothing else; letting
     * spaces through as zero would convert a data defect into a plausible-looking value.
     *
     * @param source       the sending digits; must be non-empty and consist only of {@code '0'} to
     *                     {@code '9'}
     * @param targetLength the receiving field's declared digit count; at least 1
     * @return an image of exactly {@code targetLength} digits
     * @throws NullPointerException     if {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code targetLength} is below 1, or {@code source} is empty
     *                                  or contains a character other than a digit
     */
    public String movePic9(String source, int targetLength) {
        Objects.requireNonNull(source, "A sending value is required for a numeric MOVE");
        requirePositiveLength(targetLength, "a numeric receiver");
        requireDigits(source, "numeric MOVE source");
        if (source.length() == targetLength) {
            return source;
        }
        if (source.length() > targetLength) {
            // A numeric receiver is aligned on its implied decimal point, so the digits that survive
            // are the LOW-order ones. Keeping the leading digits instead is the classic defect.
            return source.substring(source.length() - targetLength);
        }
        return repeat(ZERO, targetLength - source.length()) + source;
    }

    /**
     * Performs a COBOL numeric {@code MOVE} from an integral value, zero-filling on the left and
     * truncating on the left, exactly as {@link #movePic9(String, int)} does.
     *
     * <p>{@code PIC 9(n)} is an <em>unsigned</em> picture, so a negative sending value has no
     * unsigned representation and is rejected rather than silently stored as its magnitude. Signed
     * values belong in a {@code PIC S9} field and go through
     * {@link #encodeSignedScaled(BigDecimal, int, int)}.
     *
     * @param source       the sending value; must not be negative
     * @param targetLength the receiving field's declared digit count; at least 1
     * @return an image of exactly {@code targetLength} digits
     * @throws IllegalArgumentException if {@code targetLength} is below 1 or {@code source} is
     *                                  negative
     */
    public String movePic9(long source, int targetLength) {
        if (source < 0) {
            throw new IllegalArgumentException("Cannot store " + source + " in an unsigned PIC 9 "
                    + "field of " + targetLength + " digit(s); PIC 9 has no sign position, so a "
                    + "signed value belongs in a PIC S9 field and must be encoded as a signed "
                    + "zoned field instead");
        }
        return movePic9(Long.toString(source), targetLength);
    }

    /**
     * Writes an integral value into an unsigned numeric span, applying the {@code PIC 9} move rule.
     *
     * @param record the record area to write into
     * @param field  the descriptor naming the span; its length is the receiver's digit count
     * @param value  the sending value; must not be negative
     * @throws NullPointerException     if {@code record} or {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public void writePic9(FixedWidthRecord record, FieldSpan field, long value) {
        Objects.requireNonNull(record, "A record area is required to write a field");
        Objects.requireNonNull(field, "A field descriptor is required to write a named span");
        record.writeSpan(field, movePic9(value, field.length()));
    }

    /**
     * Writes a digit string into an unsigned numeric span, applying the {@code PIC 9} move rule. This
     * is the {@code MOVE '05' TO TRAN-CAT-CD} shape, where the sending item is an alphanumeric
     * literal.
     *
     * @param record the record area to write into
     * @param field  the descriptor naming the span; its length is the receiver's digit count
     * @param digits the sending digits
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code digits} is empty or contains a non-digit
     */
    public void writePic9(FixedWidthRecord record, FieldSpan field, String digits) {
        Objects.requireNonNull(record, "A record area is required to write a field");
        Objects.requireNonNull(field, "A field descriptor is required to write a named span");
        record.writeSpan(field, movePic9(digits, field.length()));
    }

    /**
     * Decodes an unsigned zoned {@code DISPLAY} image as a {@code long}.
     *
     * <p>Only digits are accepted. Every unsigned numeric span of every fixture under
     * {@code app/data/ASCII} was measured to hold digits and nothing else, so a non-digit here is a
     * genuine data or offset defect and failing loudly localises it. Silently yielding zero would
     * hide a misaligned span behind a plausible value, which is the hardest class of parity defect to
     * find.
     *
     * @param image the span's characters
     * @return the value the digits denote
     * @throws NullPointerException     if {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is empty, contains a non-digit, or denotes a
     *                                  value too large for a {@code long}, which a
     *                                  {@code PIC 9} field of more than 18 digits can
     */
    public long decodePic9(String image) {
        Objects.requireNonNull(image, "An image is required to decode an unsigned numeric field");
        requireDigits(image, "unsigned numeric field");
        try {
            return Long.parseLong(image);
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException("An unsigned numeric image of " + image.length()
                    + " digit(s) does not fit a long; a PIC 9 field of more than 18 digits must be "
                    + "decoded as a BigInteger by its owning model type", overflow);
        }
    }

    /**
     * Decodes an unsigned zoned {@code DISPLAY} image as an {@code int}, for the {@code PIC 9(n)}
     * fields with {@code n} of 9 or fewer that the migration maps to {@code int}.
     *
     * @param image the span's characters
     * @return the value the digits denote
     * @throws NullPointerException     if {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is empty, contains a non-digit, or denotes a
     *                                  value outside the {@code int} range
     */
    public int decodePic9AsInt(String image) {
        long value = decodePic9(image);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("An unsigned numeric image of " + image.length()
                    + " digit(s) denotes a value that exceeds Integer.MAX_VALUE; a PIC 9 field this "
                    + "wide maps to long, not int");
        }
        return (int) value;
    }

    /**
     * Reads an unsigned numeric span as a {@code long}.
     *
     * @param record the record area to read from
     * @param field  the descriptor naming the span
     * @return the value the span's digits denote
     * @throws NullPointerException     if {@code record} or {@code field} is {@code null}
     * @throws IllegalArgumentException if the span does not hold digits
     */
    public long readPic9(FixedWidthRecord record, FieldSpan field) {
        Objects.requireNonNull(record, "A record area is required to read a field");
        Objects.requireNonNull(field, "A field descriptor is required to read a named span");
        return decodePic9(record.readSpan(field));
    }

    /**
     * Reads an unsigned numeric span as an {@code int}.
     *
     * @param record the record area to read from
     * @param field  the descriptor naming the span
     * @return the value the span's digits denote
     * @throws NullPointerException     if {@code record} or {@code field} is {@code null}
     * @throws IllegalArgumentException if the span does not hold digits, or denotes a value outside
     *                                  the {@code int} range
     */
    public int readPic9AsInt(FixedWidthRecord record, FieldSpan field) {
        Objects.requireNonNull(record, "A record area is required to read a field");
        Objects.requireNonNull(field, "A field descriptor is required to read a named span");
        return decodePic9AsInt(record.readSpan(field));
    }

    // =================================================================================================
    // PIC S9(p)V(s) - signed zoned DISPLAY, exactly p + s bytes, sign overpunched into the trailing
    // byte. No sign byte is reserved, and no rounding mode is named: all scaling goes to CobolDecimal.
    // =================================================================================================

    /**
     * A signed zoned quantity as the storage actually holds it: an unsigned magnitude and a sign that
     * is recorded separately from it.
     *
     * <h2>Why the sign cannot ride on the magnitude</h2>
     * A zoned {@code DISPLAY} field stores its digits and its sign in different places - the digits in
     * the bytes, the sign as an overpunch on the last of them - so the two are independent, and a
     * field whose digits are all zero can still be marked negative. {@code app/cpy/CVTRA01Y.cpy}'s
     * {@code TRAN-CAT-BAL} holding {@code 0000000000&#125;} is a real, distinct stored value: eleven
     * bytes that are not the eleven bytes of {@code 0000000000&#123;}.
     *
     * <p>{@link BigDecimal} cannot express that distinction. It has no negative zero -
     * {@code new BigDecimal("-0.00").signum()} is {@code 0} - so a sign carried as
     * {@code value.signum() < 0} is lost the moment the magnitude is zero, and a negative zero read
     * from a dataset would be written back as a positive zero. In a migration whose acceptance test is
     * a byte-for-byte comparison against the legacy output, that is a silent parity failure in the one
     * place no arithmetic assertion would catch it.
     *
     * <p>So this type carries the sign as its own component, and the encode and decode pair built on
     * it round-trips every stored form exactly. The {@link BigDecimal} entry points remain, are
     * expressed in terms of this type, and are documented as magnitude-only.
     *
     * @param magnitude the unsigned quantity, at the field's declared scale; never negative
     * @param negative  whether the field's trailing byte marks the value negative
     */
    public record SignedZoned(BigDecimal magnitude, boolean negative) {

        /**
         * @throws NullPointerException     if {@code magnitude} is {@code null}
         * @throws IllegalArgumentException if {@code magnitude} is negative, which would carry the
         *                                  sign twice
         */
        public SignedZoned {
            Objects.requireNonNull(magnitude, "A magnitude is required; a signed zoned quantity is a "
                    + "magnitude plus a separately recorded sign");
            if (magnitude.signum() < 0) {
                throw new IllegalArgumentException("A signed zoned magnitude of scale "
                        + magnitude.scale() + " is negative. The sign is recorded in its own "
                        + "component precisely so it is held once; a negative magnitude would carry "
                        + "it twice and make the negative-zero case ambiguous again");
            }
        }

        /**
         * Splits a signed {@link BigDecimal} into a magnitude and a sign.
         *
         * <p>Because {@link BigDecimal} has no negative zero, a value of zero always yields a
         * positive zero here. A caller that must express a negative zero has to say so, either
         * through the canonical constructor or through {@link #ofLiteral(String)}.
         *
         * @param signedValue the value to split
         * @return the equivalent signed zoned quantity
         * @throws NullPointerException if {@code signedValue} is {@code null}
         */
        public static SignedZoned of(BigDecimal signedValue) {
            Objects.requireNonNull(signedValue, "A value is required to split into magnitude and "
                    + "sign");
            return new SignedZoned(signedValue.abs(), signedValue.signum() < 0);
        }

        /**
         * Reads a decimal literal, honouring a leading minus sign even when every digit is zero.
         *
         * <p>This is the entry point for an expectation written as text rather than measured from
         * storage: {@code "-0.00"} is the negative zero that {@link #of(BigDecimal)} cannot produce,
         * and a parity expectation stated that way has to mean it.
         *
         * @param literal a decimal literal, optionally signed
         * @return the quantity the literal denotes, negative zero included
         * @throws NullPointerException     if {@code literal} is {@code null}
         * @throws NumberFormatException    if {@code literal} is not a decimal literal
         */
        public static SignedZoned ofLiteral(String literal) {
            Objects.requireNonNull(literal, "A literal is required to read a signed zoned quantity");
            String trimmed = literal.trim();
            boolean explicitlyNegative = trimmed.startsWith("-");
            BigDecimal parsed = new BigDecimal(trimmed);
            return new SignedZoned(parsed.abs(), explicitlyNegative);
        }

        /**
         * The quantity as a single signed {@link BigDecimal}.
         *
         * <p><strong>Lossy for a negative zero</strong>, unavoidably: the result of negating a zero is
         * a zero. Use {@link #negativeZero()} where the distinction matters, and compare stored images
         * rather than values where byte fidelity is the question.
         *
         * @return the magnitude, negated when the sign is negative
         */
        public BigDecimal signedValue() {
            return negative ? magnitude.negate() : magnitude;
        }

        /**
         * Whether every digit is zero, irrespective of sign.
         *
         * @return {@code true} when the magnitude is zero
         */
        public boolean zero() {
            return magnitude.signum() == 0;
        }

        /**
         * Whether this is the negative zero that a {@link BigDecimal} cannot represent.
         *
         * @return {@code true} only when the magnitude is zero and the sign is negative
         */
        public boolean negativeZero() {
            return negative && zero();
        }
    }

    /**
     * Encodes a signed zoned quantity, preserving its sign independently of its magnitude.
     *
     * <p>This is the canonical signed encoder; {@link #encodeSignedScaled(BigDecimal, int, int)} is
     * expressed in terms of it. The magnitude is truncated to the receiver's picture by
     * {@link CobolDecimal#storeAtPicture(BigDecimal, int, int)} - fraction first, then integer digits,
     * keeping the low-order ones - and the sign is then overpunched onto the trailing character
     * whatever the magnitude turned out to be. A zero magnitude with a negative sign therefore encodes
     * to {@code 0000000000&#125;} and not to {@code 0000000000&#123;}.
     *
     * @param value          the quantity to store
     * @param integerDigits  {@code p}, the digit positions left of the implied decimal point; at
     *                       least 1
     * @param fractionDigits {@code s}, the digit positions right of it; never negative
     * @return an image of exactly {@code integerDigits + fractionDigits} characters
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code integerDigits} is below 1 or {@code fractionDigits}
     *                                  is negative
     */
    public String encodeSignedZoned(SignedZoned value, int integerDigits, int fractionDigits) {
        Objects.requireNonNull(value, "A signed zoned quantity is required to encode");
        if (integerDigits < 1) {
            throw new IllegalArgumentException("A signed zoned field declares " + integerDigits
                    + " integer digit(s); PIC S9(p)V(s) requires p of at least 1");
        }
        if (fractionDigits < 0) {
            throw new IllegalArgumentException("A signed zoned field declares " + fractionDigits
                    + " fraction digit(s); s must not be negative");
        }

        // Truncation happens on the magnitude, in the one class that names a rounding mode. Truncation
        // toward zero is sign-symmetric, so truncating the magnitude and applying the sign afterwards
        // is the same arithmetic as truncating a signed value - and it keeps the sign reachable when
        // the magnitude truncates to zero.
        BigDecimal stored = CobolDecimal.storeAtPicture(value.magnitude(), integerDigits,
                fractionDigits);

        int width = integerDigits + fractionDigits;
        String digits = stored.unscaledValue().toString();
        if (digits.length() < width) {
            digits = repeat(ZERO, width - digits.length()) + digits;
        }
        return overpunch(digits, value.negative());
    }

    /**
     * Decodes a signed zoned image into its magnitude and its sign, so that a negative zero survives.
     *
     * <p>This is the canonical signed decoder; {@link #decodeSignedScaled(String, int)} is expressed
     * in terms of it and discards the sign of a zero because a {@link BigDecimal} cannot hold it.
     *
     * @param image the span's characters; at least 1, and at least {@code scale} of them
     * @param scale {@code s}, the digit positions right of the implied decimal point; never negative
     * @return the quantity the image denotes, its magnitude at exactly {@code scale}
     * @throws NullPointerException     if {@code image} is {@code null}
     * @throws IllegalArgumentException if the image is empty, the scale is negative or wider than the
     *                                  image, a leading character is not a digit, or the trailing
     *                                  character is neither a digit nor a recognised sign overpunch
     */
    public SignedZoned decodeSignedZoned(String image, int scale) {
        Objects.requireNonNull(image, "An image is required to decode a signed zoned field");
        if (image.isEmpty()) {
            throw new IllegalArgumentException("A signed zoned field occupies at least one character, "
                    + "which carries the low-order digit and the sign overpunch");
        }
        if (scale < 0) {
            throw new IllegalArgumentException("Declared scale " + scale + " is negative; s in "
                    + "PIC S9(p)V(s) counts digit positions and cannot be below zero");
        }
        if (scale > image.length()) {
            // The width is shape and is reported; the image itself is the caller's data and is not.
            // This message reaches a log and can reach an HTTP error body, so a monetary image
            // quoted here would be disclosed by a defect in the caller rather than by any decision
            // taken here.
            throw new IllegalArgumentException("Declared scale " + scale + " exceeds the "
                    + image.length() + "-character image; a signed zoned field is "
                    + "p + s characters wide, so s can never exceed its width");
        }

        int lastIndex = image.length() - 1;
        String leadingDigits = image.substring(0, lastIndex);
        if (!leadingDigits.isEmpty()) {
            requireDigits(leadingDigits, "signed zoned field of " + image.length()
                    + " character(s)");
        }

        char trailing = image.charAt(lastIndex);
        int lowOrderDigit = FixedWidthRecord.ZonedSign.digitOf(trailing);
        if (lowOrderDigit < 0) {
            // Reported by category rather than by the offending character, and without the image:
            // what a caller needs is which rule was broken and what the alphabet is, and both are
            // stated. Naming the character would put a byte of a monetary field into the message.
            throw new IllegalArgumentException("A signed zoned field of " + image.length()
                    + " character(s) ends in a character that is neither a digit nor a sign "
                    + "overpunch character. The trailing character carries the low-order digit and "
                    + "the sign: '"
                    + POSITIVE_OVERPUNCH + "' for digits 0-9 positive and '" + NEGATIVE_OVERPUNCH
                    + "' for digits 0-9 negative");
        }
        // Zone F - a plain digit - is the unsigned zoned form and is positive by definition, having no
        // sign position of its own.
        boolean negative = FixedWidthRecord.ZonedSign.isNegative(trailing);

        BigInteger magnitude = new BigInteger(leadingDigits + (char) (ZERO + lowOrderDigit));
        // The scale is applied through CobolDecimal so that the guarantee "every decoded scaled value
        // reports exactly its declared scale" is enforced by the same class that owns the rounding
        // policy, and can be audited by reading that one file.
        return new SignedZoned(CobolDecimal.store(new BigDecimal(magnitude, scale), scale), negative);
    }

    /**
     * Writes a signed zoned quantity into a span, deriving the integer digit count from the span.
     *
     * @param record the record area to write into
     * @param field  the descriptor naming the span; its length must exceed {@code scale}
     * @param value  the quantity to store
     * @param scale  {@code s}, the receiving field's declared scale
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code scale} leaves no integer digit position
     */
    public void writeSignedZoned(FixedWidthRecord record, FieldSpan field, SignedZoned value,
                                 int scale) {
        Objects.requireNonNull(record, "A record area is required to write a field");
        Objects.requireNonNull(field, "A field descriptor is required to write a named span");
        record.writeSpan(field, encodeSignedZoned(value, integerDigitsOf(field, scale), scale));
    }

    /**
     * Reads a signed zoned span as a magnitude and a sign, so a negative zero is reported as one.
     *
     * @param record the record area to read from
     * @param field  the descriptor naming the span
     * @param scale  {@code s}, the field's declared scale
     * @return the quantity the span denotes
     * @throws NullPointerException     if {@code record} or {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code scale} leaves no integer digit position, or the span
     *                                  does not hold a valid signed zoned image
     */
    public SignedZoned readSignedZoned(FixedWidthRecord record, FieldSpan field, int scale) {
        Objects.requireNonNull(record, "A record area is required to read a field");
        Objects.requireNonNull(field, "A field descriptor is required to read a named span");
        integerDigitsOf(field, scale);
        return decodeSignedZoned(record.readSpan(field), scale);
    }

    /**
     * Encodes a value as a signed zoned {@code DISPLAY} image of exactly {@code integerDigits +
     * fractionDigits} characters, with the sign overpunched into the trailing character.
     *
     * <p>Three things happen, in this order, and each mirrors what COBOL does on store:
     * <ol>
     *   <li>excess fractional digits are <em>truncated toward zero</em>, because {@code ROUNDED}
     *       appears zero times in all 28 programs - {@code 1.239} becomes {@code 1.23} and
     *       {@code -1.239} becomes {@code -1.23};</li>
     *   <li>integer digits beyond {@code integerDigits} are discarded, the field keeping its low-order
     *       digits and the value's sign, because {@code ON SIZE ERROR} likewise appears nowhere -
     *       so this method <strong>never throws on an oversized value</strong>;</li>
     *   <li>the digits are zero-filled on the left to the field's full width and the trailing digit
     *       is replaced by its sign overpunch character.</li>
     * </ol>
     * Both truncations are delegated to {@link CobolDecimal#storeAtPicture(BigDecimal, int, int)},
     * which is the single place in this module where a scale and a rounding mode are named.
     *
     * <p>A {@link BigDecimal} has no negative zero, so this entry point can never produce a negative
     * zero image: {@code new BigDecimal("-0.00")} has {@code signum() == 0} and encodes to
     * {@code 0000000000&#123;}. Where a negative zero must be written - reproducing a stored value that
     * carries one - encode a {@link SignedZoned} through
     * {@link #encodeSignedZoned(SignedZoned, int, int)} instead.
     *
     * <p>No byte is reserved for the sign: the returned image is exactly
     * {@code integerDigits + fractionDigits} characters wide whether the value is positive or
     * negative. That is what makes {@code app/cpy/CVACT01Y.cpy} sum to 300 and
     * {@code app/cpy/CVTRA05Y.cpy} sum to 350. Worked examples, all three verified against real
     * fixture bytes:
     * <pre>
     *   encodeSignedScaled( 194.00, 10, 2)  ->  "00000001940{"   12 characters
     *   encodeSignedScaled( 504.77,  9, 2)  ->  "0000005047G"    11 characters
     *   encodeSignedScaled(-919.00,  9, 2)  ->  "0000009190}"    11 characters, still 11
     * </pre>
     *
     * @param value          the value to store
     * @param integerDigits  {@code p}, the digit positions left of the implied decimal point; at
     *                       least 1
     * @param fractionDigits {@code s}, the digit positions right of it; never negative, and 0 for a
     *                       scaleless signed field
     * @return an image of exactly {@code integerDigits + fractionDigits} characters
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code integerDigits} is below 1 or {@code fractionDigits}
     *                                  is negative
     */
    public String encodeSignedScaled(BigDecimal value, int integerDigits, int fractionDigits) {
        Objects.requireNonNull(value, "A value is required to encode a signed zoned field");
        if (integerDigits < 1) {
            throw new IllegalArgumentException("A signed zoned field declares " + integerDigits
                    + " integer digit(s); PIC S9(p)V(s) requires p of at least 1");
        }
        if (fractionDigits < 0) {
            throw new IllegalArgumentException("A signed zoned field declares " + fractionDigits
                    + " fraction digit(s); s must not be negative");
        }

        // Split the sign off the magnitude and encode through the canonical pair, so there is exactly
        // one implementation of the picture truncation and the overpunch. Both truncations - fraction
        // to s, then integer part to p keeping the low-order digits - happen inside it, in the one
        // class that names a rounding mode, and storeAtPicture is documented never to throw on
        // overflow, matching COBOL without ON SIZE ERROR.
        return encodeSignedZoned(SignedZoned.of(value), integerDigits, fractionDigits);
    }

    /**
     * Decodes a signed zoned {@code DISPLAY} image as a {@link BigDecimal} of exactly {@code scale}.
     *
     * <p>Every character but the last must be a digit. The last carries both the low-order digit and
     * the sign:
     * <ul>
     *   <li><code>&#123;ABCDEFGHI</code> - zone {@code C}, positive, digits 0 to 9;</li>
     *   <li><code>&#125;JKLMNOPQR</code> - zone {@code D}, negative, digits 0 to 9;</li>
     *   <li>{@code 0} to {@code 9} - zone {@code F}, the unsigned form, taken as positive.</li>
     * </ul>
     * The zone {@code F} case is accepted because a field written by a program that treated the
     * picture as unsigned still has to be readable; the two overpunched forms are what the fixtures
     * actually contain.
     *
     * <p>The returned value's {@link BigDecimal#scale()} is exactly {@code scale}, guaranteed by
     * routing the result through {@link CobolDecimal#store(BigDecimal, int)} rather than by
     * construction, so the scale contract of every decode in this system is asserted in one place.
     *
     * <p><strong>The sign of a zero is not preserved by this entry point</strong>, because a
     * {@link BigDecimal} cannot hold it: an image of {@code 0000000000&#125;} and one of
     * {@code 0000000000&#123;} both decode to {@code 0.00}. Those are two distinct stored values, so a
     * caller that compares storage rather than quantity - the parity differ above all - must use
     * {@link #decodeSignedZoned(String, int)} and compare the sign as well.
     *
     * @param image the span's characters; at least 1, and at least {@code scale} of them
     * @param scale {@code s}, the digit positions right of the implied decimal point; never negative.
     *              Every scaled field in this system uses {@link CobolDecimal#MONETARY_SCALE}
     * @return the value the image denotes, at exactly {@code scale}
     * @throws NullPointerException     if {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is empty, {@code scale} is negative or
     *                                  greater than the image's width, a leading character is not a
     *                                  digit, or the trailing character is neither a digit nor a
     *                                  recognised sign overpunch
     */
    public BigDecimal decodeSignedScaled(String image, int scale) {
        return decodeSignedZoned(image, scale).signedValue();
    }

    /**
     * Writes a value into a signed zoned span, deriving the integer digit count from the span itself.
     *
     * <p>The span's declared length is {@code p + s}, so {@code p} is {@code field.length() - scale}.
     * Deriving it rather than accepting it is what makes a phantom sign byte impossible to
     * reintroduce here: the width the descriptor states is the width that gets written.
     *
     * @param record the record area to write into
     * @param field  the descriptor naming the span; its length must exceed {@code scale}
     * @param value  the value to store; truncated toward zero to {@code scale}, and wrapped to the
     *               derived integer digit count if it overflows
     * @param scale  {@code s}, the receiving field's declared scale
     * @throws NullPointerException     if {@code record}, {@code field} or {@code value} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative or leaves no integer digit
     *                                  position, that is if it is not below {@code field.length()}
     */
    public void writeSignedScaled(FixedWidthRecord record, FieldSpan field, BigDecimal value,
                                 int scale) {
        Objects.requireNonNull(record, "A record area is required to write a field");
        Objects.requireNonNull(field, "A field descriptor is required to write a named span");
        record.writeSpan(field, encodeSignedScaled(value, integerDigitsOf(field, scale), scale));
    }

    /**
     * Reads a signed zoned span as a {@link BigDecimal} of exactly {@code scale}.
     *
     * @param record the record area to read from
     * @param field  the descriptor naming the span
     * @param scale  {@code s}, the field's declared scale
     * @return the value the span denotes, at exactly {@code scale}
     * @throws NullPointerException     if {@code record} or {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code scale} leaves no integer digit position, or the span
     *                                  does not hold a valid signed zoned image
     */
    public BigDecimal readSignedScaled(FixedWidthRecord record, FieldSpan field, int scale) {
        Objects.requireNonNull(record, "A record area is required to read a field");
        Objects.requireNonNull(field, "A field descriptor is required to read a named span");
        // Validate p >= 1 on the read path too, so a wrong scale is reported against the descriptor
        // rather than surfacing later as an implausible value.
        integerDigitsOf(field, scale);
        return decodeSignedScaled(record.readSpan(field), scale);
    }

    /**
     * Writes a monetary value into a signed zoned span at {@link CobolDecimal#MONETARY_SCALE}.
     *
     * <p>Every scaled numeric in this system is scale 2 - a scan of every {@code PICTURE} in
     * {@code app/cbl} and {@code app/cpy} finds only {@code V99} - so this covers every monetary
     * field, and the {@code p} it derives is {@code field.length() - 2}: 10 for the five
     * {@code S9(10)V99} fields of {@code CVACT01Y} and 9 for {@code TRAN-AMT} in {@code CVTRA05Y}.
     *
     * @param record the record area to write into
     * @param field  the descriptor naming the span; at least 3 characters wide
     * @param value  the monetary value to store
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if the span leaves no integer digit position at scale 2
     */
    public void writeMonetary(FixedWidthRecord record, FieldSpan field, BigDecimal value) {
        writeSignedScaled(record, field, value, CobolDecimal.MONETARY_SCALE);
    }

    /**
     * Reads a monetary span as a {@link BigDecimal} of scale {@link CobolDecimal#MONETARY_SCALE}.
     *
     * @param record the record area to read from
     * @param field  the descriptor naming the span
     * @return the monetary value the span denotes, at scale 2
     * @throws NullPointerException     if {@code record} or {@code field} is {@code null}
     * @throws IllegalArgumentException if the span leaves no integer digit position at scale 2, or
     *                                  does not hold a valid signed zoned image
     */
    public BigDecimal readMonetary(FixedWidthRecord record, FieldSpan field) {
        return readSignedScaled(record, field, CobolDecimal.MONETARY_SCALE);
    }

    // =================================================================================================
    // FILLER and declared VALUE literals.
    // =================================================================================================

    /**
     * Emits a span's declared {@code VALUE} literal, or its pad character when it declares none.
     *
     * <p>This is where the {@code app/cpy/CSDAT01Y.cpy} exception is honoured rather than normalised
     * away. A {@code FILLER PIC X(01) VALUE '/'} emits a slash; a {@code FILLER PIC X(178)} carrying
     * no literal emits 178 spaces; and a numeric span carrying no literal emits zeros, which is what
     * makes an initialised {@code WS-CURDATE-MM-DD-YY} read {@code 00/00/00} rather than
     * {@code   /  /  }. Blanket space-filling every {@code FILLER} would blank every date and time
     * separator in the system.
     *
     * <p>A declared literal shorter than its span is aligned by the span's
     * {@link PictureKind}: left for character data and {@code FILLER}, right for numeric
     * {@code DISPLAY} data, exactly as COBOL aligns a {@code VALUE} clause. A literal longer than its
     * span is rejected, because a copybook cannot declare one and a truncated literal would be a
     * transcription error worth surfacing rather than absorbing.
     *
     * @param record the record area to write into
     * @param field  the descriptor naming the span
     * @throws NullPointerException     if {@code record} or {@code field} is {@code null}
     * @throws IllegalArgumentException if the declared literal is wider than the span
     */
    public void writeDeclaredValue(FixedWidthRecord record, FieldSpan field) {
        Objects.requireNonNull(record, "A record area is required to emit a declared VALUE");
        Objects.requireNonNull(field, "A field descriptor is required to emit its declared VALUE");
        if (field.hasInitialValue()) {
            record.writeSpan(field, field.initialValue());
        } else {
            record.fill(field.offset(), field.length(), record.padByteFor(field.kind()));
        }
    }

    // =================================================================================================
    // Right-padding a short row to its copybook-declared width.
    // =================================================================================================

    /**
     * Widens a short row to a declared record width by padding it on the right with spaces, and
     * rejects a row that is already too long.
     *
     * <p>Two measured deviations in this repository need exactly this operation, and the width is a
     * parameter so that neither pair of numbers is written into this file: the fixture
     * {@code app/data/ASCII/cardxref.txt} carries 36 bytes per record where
     * {@code app/cpy/CVACT03Y.cpy} declares 50, omitting the trailing {@code FILLER X(14)}, and the
     * {@code USRSEC} test seed carries 57 bytes where {@code app/cpy/CSUSR01Y.cpy} declares 80,
     * omitting {@code SEC-USR-FILLER X(23)}. Right-padding with spaces is the faithful repair in both
     * cases, because the absent bytes are a trailing {@code FILLER} and a {@code FILLER} carrying no
     * literal holds spaces. The reference data itself is never rewritten - it is the parity oracle -
     * so the widening happens here, on the way in.
     *
     * <p>An over-long row is <strong>rejected, not truncated</strong>. A row wider than its copybook
     * declares means the layout and the data disagree, and quietly discarding the excess would let
     * that disagreement through as plausible-looking output.
     *
     * @param row            the stored row, which may be shorter than {@code declaredWidth}
     * @param declaredWidth  the copybook's declared record width; at least 1
     * @return a row of exactly {@code declaredWidth} bytes: {@code row} followed by spaces
     * @throws NullPointerException     if {@code row} is {@code null}
     * @throws IllegalArgumentException if {@code declaredWidth} is below 1, or {@code row} is longer
     *                                  than {@code declaredWidth}
     */
    public byte[] padToDeclaredWidth(byte[] row, int declaredWidth) {
        Objects.requireNonNull(row, "A row is required to pad it to its declared width");
        requirePositiveLength(declaredWidth, "a declared record width");
        if (row.length > declaredWidth) {
            throw new IllegalArgumentException("Row of " + row.length + " byte(s) is wider than the "
                    + "declared width of " + declaredWidth + "; this operation only widens a short "
                    + "row and never truncates, because an over-long row means the layout and the "
                    + "data disagree");
        }
        // A bare FixedWidthRecord allocation is space-filled throughout in the record's own charset,
        // so the pad bytes are correct for the code page without this class naming a byte constant.
        FixedWidthRecord padded = new FixedWidthRecord(declaredWidth, charset);
        padded.writeBytes(0, row);
        return padded.toByteArray();
    }

    /**
     * Widens a short row image to a declared width by padding it on the right with spaces, and
     * rejects an image that is already too long. The character-level counterpart of
     * {@link #padToDeclaredWidth(byte[], int)}, for a caller that has read the row as text.
     *
     * @param row           the row image, which may be shorter than {@code declaredWidth}
     * @param declaredWidth the copybook's declared record width in characters; at least 1
     * @return an image of exactly {@code declaredWidth} characters
     * @throws NullPointerException     if {@code row} is {@code null}
     * @throws IllegalArgumentException if {@code declaredWidth} is below 1, or {@code row} is longer
     *                                  than {@code declaredWidth}
     */
    public String padToDeclaredWidth(String row, int declaredWidth) {
        Objects.requireNonNull(row, "A row image is required to pad it to its declared width");
        requirePositiveLength(declaredWidth, "a declared record width");
        if (row.length() > declaredWidth) {
            throw new IllegalArgumentException("Row image of " + row.length() + " character(s) is "
                    + "wider than the declared width of " + declaredWidth + "; this operation only "
                    + "widens a short row and never truncates");
        }
        return row + repeat(SPACE, declaredWidth - row.length());
    }

    // =================================================================================================
    // STRING ... DELIMITED BY SIZE.
    // =================================================================================================

    /**
     * Concatenates operands at their full declared widths, which is what {@code DELIMITED BY SIZE}
     * means: every operand contributes every one of its characters, including its padding.
     *
     * <p>This is the primitive behind {@code app/cbl/CBACT04C.cbl:476-480}:
     * <pre>
     *   STRING PARM-DATE,
     *          WS-TRANID-SUFFIX
     *     DELIMITED BY SIZE
     *     INTO TRAN-ID
     *   END-STRING.
     * </pre>
     * {@code PARM-DATE} is {@code PIC X(10)} and {@code WS-TRANID-SUFFIX} is {@code PIC 9(06)}, so
     * the result is exactly 10 + 6 = 16 characters and fills {@code TRAN-ID PIC X(16)} precisely. The
     * numeric operand must already be at its full declared width, which is what
     * {@link #movePic9(long, int)} produces: suffix 1 becomes {@code "000001"}, so parm date
     * {@code "2022071800"} yields the transaction identifier {@code "2022071800000001"}.
     *
     * <p>Each operand is taken exactly as given. Nothing is trimmed, because a trimmed operand would
     * shorten the result and shift every character after it - the defect this method exists to
     * prevent.
     *
     * @param operands the sending items, each already at its full declared width; at least one, and
     *                 none {@code null}
     * @return the concatenation of every operand's full width
     * @throws NullPointerException     if {@code operands} or any operand is {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public String concatenateDelimitedBySize(String... operands) {
        Objects.requireNonNull(operands, "At least one sending item is required for STRING ... "
                + "DELIMITED BY SIZE");
        if (operands.length == 0) {
            throw new IllegalArgumentException("STRING ... DELIMITED BY SIZE requires at least one "
                    + "sending item");
        }
        StringBuilder concatenated = new StringBuilder();
        for (int index = 0; index < operands.length; index++) {
            String operand = operands[index];
            Objects.requireNonNull(operand, "Sending item " + (index + 1) + " of "
                    + operands.length + " is null; every operand of STRING ... DELIMITED BY SIZE "
                    + "contributes its full declared width and so must be present");
            concatenated.append(operand);
        }
        return concatenated.toString();
    }

    /**
     * Performs {@code STRING ... DELIMITED BY SIZE INTO} a span: overlays the concatenated operands
     * at the <em>start</em> of the receiving span and <strong>leaves the remainder of the span
     * unchanged</strong>.
     *
     * <p>Leaving the remainder alone is the part that is easy to get wrong. COBOL's {@code STRING}
     * statement transfers characters into the receiving field from its leftmost position and stops
     * when the sending items are exhausted; it does <em>not</em> space-fill what it did not reach.
     * That is why {@code app/cbl/CBACT04C.cbl} can build {@code TRAN-DESC PIC X(100)} from
     * {@code STRING 'Int. for a/c ', ACCT-ID DELIMITED BY SIZE} - 24 characters - and leave the
     * remaining 76 exactly as the record area already held them. A helper that blanked the tail would
     * be a different program.
     *
     * <p>If the concatenation is wider than the span, the transfer stops at the span's last character
     * position. That is COBOL's overflow condition, and because no {@code STRING} statement in this
     * codebase declares {@code ON OVERFLOW}, the statement simply ends and the excess is discarded.
     *
     * @param record   the record area to write into
     * @param field    the descriptor naming the receiving span
     * @param operands the sending items, each already at its full declared width
     * @throws NullPointerException     if {@code record}, {@code field}, {@code operands} or any
     *                                  operand is {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public void stringIntoDelimitedBySize(FixedWidthRecord record, FieldSpan field,
                                          String... operands) {
        Objects.requireNonNull(record, "A record area is required for STRING ... INTO");
        Objects.requireNonNull(field, "A receiving field descriptor is required for STRING ... INTO");
        String concatenated = concatenateDelimitedBySize(operands);
        int transferred = Math.min(concatenated.length(), field.length());
        if (transferred > 0) {
            record.writeString(field.offset(), transferred, concatenated.substring(0, transferred));
        }
    }

    // =================================================================================================
    // Whole-record serialise and deserialise. This is the entry point every model type and every
    // repository uses, and the one the fixture round-trip test drives.
    // =================================================================================================

    /**
     * Builds a complete record from field images, keyed by copybook field name.
     *
     * <p>The record is first initialised from the layout, so every {@code FILLER} and every span
     * declaring a {@code VALUE} is already correct; the supplied images then overwrite the named
     * spans. A name absent from the map therefore keeps its initialised content, which is what makes
     * a partially-populated record still exactly the declared width with correct padding rather than
     * a record with holes in it.
     *
     * <p>Each image is written with the move rule for its span's {@link PictureKind}: character spans
     * pad and truncate on the right, numeric {@code DISPLAY} spans on the left. A signed span's image
     * is written verbatim once padded, because its trailing character is a sign overpunch rather than
     * a digit and must survive untouched - build that image with
     * {@link #encodeSignedScaled(BigDecimal, int, int)}.
     *
     * <p>An unknown field name is rejected rather than ignored. A misspelled key that was silently
     * dropped would leave the field at its initialised value, which is a plausible-looking record and
     * the hardest kind of parity defect to trace.
     *
     * @param layout the record's layout, transcribed from its copybook
     * @param values field name to field image; names are the copybook's own, verbatim and
     *               case-sensitive, and {@code FILLER} is not among them because it is not referable
     * @return exactly {@code layout.recordLength()} bytes
     * @throws NullPointerException     if {@code layout}, {@code values}, or any key or value in
     *                                  {@code values} is {@code null}
     * @throws IllegalArgumentException if a key names no span in the layout, or an image is not valid
     *                                  for its span's kind
     * @see #serialise(RecordLayout, Map, byte[])
     */
    public byte[] serialise(RecordLayout layout, Map<String, String> values) {
        Objects.requireNonNull(layout, "A record layout is required to serialise a record");
        FixedWidthRecord record = newRecord(layout);
        applyImages(record, layout, values);
        return record.toByteArray();
    }

    /**
     * Rewrites an existing record from field images, preserving every byte the images do not name -
     * {@code FILLER} and reserved spans included.
     *
     * <p>This is the faithful equivalent of COBOL's read-then-rewrite shape. A program issues
     * {@code READ ... INTO} a {@code WORKING-STORAGE} group, changes some fields, and issues
     * {@code REWRITE ... FROM} that same group: the bytes it never touched, including every
     * {@code FILLER}, travel from the stored record straight back to it. Use this overload wherever
     * a repository updates a record it has just read, and use
     * {@link #serialise(RecordLayout, Map)} only where a record is being built from nothing.
     *
     * <p>The distinction is not academic, and it is not a matter of taste. The reserved bytes of the
     * shipped fixtures were measured, and they <strong>disagree with each other</strong> even though
     * every one of these copybooks declares its reserved span as {@code PIC X(n)}:
     * <table border="1">
     *   <caption>Measured content of the trailing reserved span</caption>
     *   <tr><th>Fixture</th><th>Declared</th><th>Actual content</th></tr>
     *   <tr><td>{@code acctdata.txt}</td><td>{@code FILLER X(178)}</td><td>spaces</td></tr>
     *   <tr><td>{@code carddata.txt}</td><td>{@code FILLER X(59)}</td><td>spaces</td></tr>
     *   <tr><td>{@code custdata.txt}</td><td>{@code FILLER X(168)}</td><td>spaces</td></tr>
     *   <tr><td>{@code dailytran.txt}</td><td>{@code FILLER X(20)}</td><td>spaces</td></tr>
     *   <tr><td>{@code tcatbal.txt}</td><td>{@code FILLER X(22)}</td><td><strong>zeros</strong></td></tr>
     *   <tr><td>{@code discgrp.txt}</td><td>{@code FILLER X(28)}</td><td><strong>zeros</strong></td></tr>
     *   <tr><td>{@code trantype.txt}</td><td>{@code FILLER X(08)}</td><td><strong>zeros</strong></td></tr>
     *   <tr><td>{@code trancatg.txt}</td><td>{@code FILLER X(04)}</td><td><strong>zeros</strong></td></tr>
     * </table>
     * That divergence is a property of the shipped data, not of the copybooks, and it is recorded here
     * rather than corrected: the fixtures are the parity oracle and are never rewritten. Its
     * consequence is concrete. A repository that read a {@code tcatbal} row, updated
     * {@code TRAN-CAT-BAL} and rebuilt the record from its layout would emit 22 spaces where the
     * dataset holds 22 zeros - a 22-byte difference per record that no field-level comparison would
     * report, because the differing bytes belong to no field. Going through this overload instead
     * carries them across untouched.
     *
     * @param layout the record's layout, transcribed from its copybook
     * @param values field name to field image; only the named spans are overwritten
     * @param record the stored record to rewrite from, exactly {@code layout.recordLength()} bytes.
     *               It is copied, not modified
     * @return exactly {@code layout.recordLength()} bytes: {@code record} with the named spans
     *         replaced
     * @throws NullPointerException     if {@code layout}, {@code values}, {@code record}, or any key
     *                                  or value in {@code values} is {@code null}
     * @throws IllegalArgumentException if {@code record.length} differs from the layout's declared
     *                                  record length, a key names no span in the layout, or an image
     *                                  is not valid for its span's kind
     */
    public byte[] serialise(RecordLayout layout, Map<String, String> values, byte[] record) {
        Objects.requireNonNull(layout, "A record layout is required to rewrite a record");
        FixedWidthRecord area = wrap(record, layout);
        applyImages(area, layout, values);
        return area.toByteArray();
    }

    /**
     * Decomposes a complete record into raw field images, keyed by copybook field name and returned
     * in copybook declaration order.
     *
     * <p>Each image is the span's characters exactly as stored: <strong>untrimmed</strong>, with a
     * signed span's trailing sign overpunch character intact and a numeric span's leading zeros
     * intact. Raw images are what the parity differ compares, and they are what
     * {@link #serialise(RecordLayout, Map)} writes back unchanged, which is what makes a
     * decode-then-encode round trip byte-identical. Interpreting an image as a number is a separate,
     * deliberate step through {@link #decodePic9(String)} or
     * {@link #decodeSignedScaled(String, int)}.
     *
     * <p>{@code FILLER} spans are omitted, because {@code FILLER} is not a referable COBOL name and a
     * layout may declare many of them, so they cannot be distinct map keys. They are not thereby
     * unverified: the layout's self-check proves they are present and account for their bytes.
     * {@code REDEFINES} overlays <em>are</em> included, since an overlay is a named alternative view
     * that a caller may legitimately want.
     *
     * <p>Because reserved bytes are not in the map, a byte-identical round trip needs the overload
     * that keeps them. {@code serialise(layout, deserialise(layout, row), row)} reproduces
     * {@code row} exactly for any row; {@code serialise(layout, deserialise(layout, row))} reproduces
     * it only when the row's reserved bytes already hold what the layout initialises them to, which
     * the measured fixtures show is true of some datasets and false of others - see
     * {@link #serialise(RecordLayout, Map, byte[])}.
     *
     * @param layout the record's layout, transcribed from its copybook
     * @param record exactly {@code layout.recordLength()} bytes
     * @return an insertion-ordered map from field name to raw image, {@code FILLER} excluded
     * @throws NullPointerException     if {@code layout} or {@code record} is {@code null}
     * @throws IllegalArgumentException if {@code record.length} differs from the layout's declared
     *                                  record length
     */
    public Map<String, String> deserialise(RecordLayout layout, byte[] record) {
        Objects.requireNonNull(layout, "A record layout is required to deserialise a record");
        FixedWidthRecord area = wrap(record, layout);
        Map<String, String> images = new LinkedHashMap<>();
        for (FieldSpan field : layout.spans()) {
            if (field.kind().filler()) {
                continue;
            }
            images.put(field.name(), area.readSpan(field));
        }
        return images;
    }

    // =================================================================================================
    // Private helpers. Each guard is a separate, individually reachable branch.
    // =================================================================================================

    /**
     * Overwrites the named spans of a record from their images, shared by both {@code serialise}
     * overloads so that the only difference between them is the record they start from.
     *
     * <p>An unknown name is rejected rather than ignored: a misspelled key that was silently dropped
     * would leave the field at whatever it already held, which is a plausible-looking record and the
     * hardest kind of parity defect to trace back to its cause.
     */
    private void applyImages(FixedWidthRecord record, RecordLayout layout,
                             Map<String, String> values) {
        Objects.requireNonNull(values, "A field image map is required; pass an empty map to emit a "
                + "record holding only the bytes it already has");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(),
                    "A null field name cannot be resolved against the layout");
            String image = Objects.requireNonNull(entry.getValue(),
                    "Field '" + name + "' has a null image; to blank a field supply spaces, and to "
                            + "leave it as it stands omit it from the map");
            writeImage(record, layout.span(name), image);
        }
    }

    /**
     * Writes a raw image into a span using the move rule for the span's kind, which is what makes
     * both {@code serialise} overloads kind-aware without the caller having to be.
     *
     * <p>A signed span's image is padded on the left with zeros if short, but is otherwise written
     * verbatim: its trailing character is a sign overpunch, not a digit, so it must not pass through
     * the digit-validating numeric move.
     */
    private void writeImage(FixedWidthRecord record, FieldSpan field, String image) {
        PictureKind kind = field.kind();
        if (kind == PictureKind.UNSIGNED_NUMERIC) {
            record.writeSpan(field, movePic9(image, field.length()));
        } else if (kind == PictureKind.SIGNED_SCALED) {
            record.writeSpan(field, padSignedImage(field, image));
        } else {
            record.writeSpan(field, movePicX(image, field.length()));
        }
    }

    /**
     * Brings a signed zoned image to its span's declared width by zero-filling on the left, having
     * first confirmed it is a valid signed zoned image at that width. Over-wide images are rejected
     * rather than truncated, because truncating a signed field would discard its high-order digits
     * while leaving its sign, and that is a value change rather than a formatting choice.
     */
    private String padSignedImage(FieldSpan field, String image) {
        Objects.requireNonNull(image, "A signed zoned image is required for span '" + field.name()
                + "'");
        if (image.length() > field.length()) {
            throw new IllegalArgumentException("A signed zoned image of " + image.length()
                    + " character(s) is wider than " + field.describe() + ", which holds "
                    + field.length() + "; encode the value to the span's width with "
                    + "encodeSignedScaled(BigDecimal, int, int) rather than truncating an image, "
                    + "whose trailing character is a sign overpunch");
        }
        if (image.isEmpty()) {
            throw new IllegalArgumentException("An empty image cannot be stored in "
                    + field.describe() + "; a signed zoned field's trailing character carries its "
                    + "low-order digit and its sign");
        }
        String padded = image.length() == field.length()
                ? image
                : repeat(ZERO, field.length() - image.length()) + image;
        // Validating with scale 0 checks only the shape - leading digits plus a recognised trailing
        // character - which is exactly what has to hold before the image is stored.
        decodeSignedScaled(padded, 0);
        return padded;
    }

    /**
     * Replaces an image's trailing digit with the sign overpunch character carrying that digit and
     * the sign, so the sign occupies no character position of its own.
     */
    private String overpunch(String digits, boolean negative) {
        int lastIndex = digits.length() - 1;
        int lowOrderDigit = digits.charAt(lastIndex) - ZERO;
        String table = negative ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        return digits.substring(0, lastIndex) + table.charAt(lowOrderDigit);
    }

    /**
     * Derives {@code p} from a signed span's declared width, since that width is {@code p + s}, and
     * rejects a scale that would leave the field no integer digit position at all.
     */
    private int integerDigitsOf(FieldSpan field, int scale) {
        if (scale < 0) {
            throw new IllegalArgumentException("Declared scale " + scale + " for " + field.describe()
                    + " is negative; s in PIC S9(p)V(s) counts digit positions");
        }
        int integerDigits = field.length() - scale;
        if (integerDigits < 1) {
            throw new IllegalArgumentException("Declared scale " + scale + " leaves "
                    + integerDigits + " integer digit position(s) in " + field.describe()
                    + "; a signed zoned span is p + s characters wide with p of at least 1, so the "
                    + "scale must be below the span's declared length");
        }
        return integerDigits;
    }

    /** Rejects a receiving width that cannot hold a field. */
    private void requirePositiveLength(int length, String what) {
        if (length < 1) {
            throw new IllegalArgumentException("Declared length " + length + " is not valid for "
                    + what + "; a field occupies at least one character position");
        }
    }

    /**
     * Rejects a numeric image that is empty or holds anything but digits. Strictness is
     * evidence-based: every unsigned numeric span of every fixture under {@code app/data/ASCII} holds
     * digits and nothing else, so a non-digit is a real defect and reporting it beats returning a
     * plausible zero.
     */
    private void requireDigits(String value, String what) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("An empty value is not a valid " + what
                    + "; a zoned DISPLAY field holds at least one digit");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < ZERO || character > '9') {
                throw new IllegalArgumentException("A " + what + " of " + value.length()
                        + " character(s) is not valid: character " + (index + 1) + " is not a digit, "
                        + "and a zoned DISPLAY field holds only the digits 0 to 9");
            }
        }
    }

    /**
     * Builds a run of one repeated character. Present so the pad characters are named once, as
     * characters, and the code page decides their bytes exactly once - inside
     * {@link FixedWidthRecord} - rather than a byte constant being duplicated across the two layers.
     */
    private String repeat(char character, int count) {
        return String.valueOf(character).repeat(count);
    }
}

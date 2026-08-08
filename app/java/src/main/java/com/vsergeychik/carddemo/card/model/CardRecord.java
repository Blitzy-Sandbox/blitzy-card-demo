package com.vsergeychik.carddemo.card.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * The one Java type for {@code app/cpy/CVACT02Y.cpy}: the CardDemo {@code CARD-RECORD}, a
 * fixed-width value of exactly {@value #RECORD_LENGTH} bytes.
 *
 * <h2>The copybook is the contract</h2>
 * This is a like-for-like translation of a data structure, not a re-design of one. Field names,
 * widths, order, alignment and the trailing reserved span are all part of the migration contract,
 * because the parity differ compares a written record <em>field by field, by name</em>. The
 * copybook, reproduced in full, is fourteen lines:
 * <pre>
 * *    Data-structure for card entity (RECLN 150)
 *  01  CARD-RECORD.
 *      05  CARD-NUM                          PIC X(16).
 *      05  CARD-ACCT-ID                      PIC 9(11).
 *      05  CARD-CVV-CD                       PIC 9(03).
 *      05  CARD-EMBOSSED-NAME                PIC X(50).
 *      05  CARD-EXPIRAION-DATE               PIC X(10).
 *      05  CARD-ACTIVE-STATUS                PIC X(01).
 *      05  FILLER                            PIC X(59).
 * </pre>
 * which yields this layout. Every offset below was confirmed twice: by arithmetic over the
 * {@code PICTURE} widths, and by slicing all fifty rows of {@code app/data/ASCII/carddata.txt} at
 * absolute offsets.
 * <table border="1">
 *   <caption>{@code CVACT02Y} byte layout, 0-based and half-open</caption>
 *   <tr><th>Field</th><th>PICTURE</th><th>Offset</th><th>Length</th><th>End</th><th>Java</th></tr>
 *   <tr><td>{@code CARD-NUM}</td><td>{@code X(16)}</td><td>0</td><td>16</td><td>16</td>
 *       <td>{@link String}</td></tr>
 *   <tr><td>{@code CARD-ACCT-ID}</td><td>{@code 9(11)}</td><td>16</td><td>11</td><td>27</td>
 *       <td>{@code long}</td></tr>
 *   <tr><td>{@code CARD-CVV-CD}</td><td>{@code 9(03)}</td><td>27</td><td>3</td><td>30</td>
 *       <td>{@code int}</td></tr>
 *   <tr><td>{@code CARD-EMBOSSED-NAME}</td><td>{@code X(50)}</td><td>30</td><td>50</td><td>80</td>
 *       <td>{@link String}</td></tr>
 *   <tr><td>{@code CARD-EXPIRAION-DATE}</td><td>{@code X(10)}</td><td>80</td><td>10</td><td>90</td>
 *       <td>{@link String}</td></tr>
 *   <tr><td>{@code CARD-ACTIVE-STATUS}</td><td>{@code X(01)}</td><td>90</td><td>1</td><td>91</td>
 *       <td>{@link String}</td></tr>
 *   <tr><td>{@code FILLER}</td><td>{@code X(59)}</td><td>91</td><td>59</td><td>150</td>
 *       <td>reserved span</td></tr>
 * </table>
 * {@code 16 + 11 + 3 + 50 + 10 + 1 + 59 = 150}, matching the copybook's own {@code (RECLN 150)}
 * header comment. {@link #LAYOUT} refuses to be constructed unless that sum holds, so a dropped or
 * mis-sized span fails at class initialisation rather than corrupting a dataset.
 *
 * <h2>{@code CARD-EXPIRAION-DATE} is misspelled, and stays misspelled</h2>
 * {@code app/cpy/CVACT02Y.cpy:9} declares {@code CARD-EXPIRAION-DATE} - {@code EXPIRAION}, missing
 * the {@code T}. The component here is spelled {@link #cardExpiraionDate()} to match, and
 * <strong>must not be "corrected"</strong> to {@code Expiration}. Renaming it would make a genuine
 * field-level difference invisible to the differ, which compares by name.
 *
 * <p>The misspelling is systemic rather than a stray typo, and the evidence is worth recording so
 * that no future reader mistakes it for an oversight in this file:
 * <ul>
 *   <li>it occurs twice, independently, in the schema itself - {@code ACCT-EXPIRAION-DATE} at
 *       {@code app/cpy/CVACT01Y.cpy:11} and {@code CARD-EXPIRAION-DATE} at
 *       {@code app/cpy/CVACT02Y.cpy:9};</li>
 *   <li>no correctly-spelled COBOL field named {@code *EXPIRATION*} exists anywhere in the
 *       repository. The only {@code EXPIRATION} text repository-wide is in
 *       {@code app/catlg/LISTCAT.txt}, which is VSAM catalogue output about <em>dataset</em>
 *       expiration, not a field name. There is therefore no "correct" form to prefer;</li>
 *   <li>it propagates into derived identifiers across the estate:
 *       {@code CARD-EXPIRAION-DATE-X} (7 occurrences), {@code CARD-EXPIRAION-DATE-N} (2),
 *       {@code CARD-UPDATE-EXPIRAION-DATE} (2), {@code CCUP-OLD-EXPIRAION-DATE} (1) and
 *       {@code CCUP-NEW-EXPIRAION-DATE} (1);</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl} alone spells it seventeen times, at lines 115, 116, 122, 123,
 *       297, 309, 319, 1361, 1363, 1365, 1473, 1505, 1506, 1507, 1514, 1515 and 1516.</li>
 * </ul>
 * Preserving a source defect and documenting it, rather than silently repairing it, is the standing
 * rule for this migration.
 *
 * <h2>{@code CARD-NUM} is alphanumeric, never a number</h2>
 * The {@code PICTURE} is {@code X(16)}. The data looks numeric - the first fixture row holds
 * {@code 0500024453765740} - and that is precisely the trap: holding it as a {@code long} or a
 * {@code BigInteger} would destroy the <strong>leading zero</strong>, which five of the fifty
 * fixture rows carry, and would silently break every keyed read and every field-level comparison.
 * {@code CARD-NUM} is the most-referenced field in the estate, so the error would propagate
 * everywhere. Consumer-side numeric views such as {@code COCRDSLC}'s
 * {@code CARD-CARD-NUM-N REDEFINES CARD-CARD-NUM-X PIC 9(16)} live in <em>that program's</em>
 * working storage and are deliberately not reproduced here.
 *
 * <h2>Two keys over one dataset, and the offset that is easy to confuse</h2>
 * {@code CARDDAT} is keyed on {@code CARD-NUM} at offset {@value #CARD_NUM_OFFSET}, and the
 * {@code CARDAIX} alternate-index path is keyed on {@code CARD-ACCT-ID} at offset
 * {@value #CARD_ACCT_ID_OFFSET} - verified from {@code app/cbl/COCRDLIC.cbl:217} and
 * {@code app/cbl/COACTVWC.cbl:191}, both of which declare
 * {@code LIT-CARD-FILE-ACCT-PATH VALUE 'CARDAIX '}. The alternate index is an access <em>path</em>
 * over the same base data, never a second table, so both keys belong to this one type.
 *
 * <p><strong>Cross-type trap.</strong> The sibling {@code CardXrefRecord}
 * ({@code app/cpy/CVACT03Y.cpy}) also carries an eleven-byte account id, but at offset
 * <strong>25</strong>, not {@value #CARD_ACCT_ID_OFFSET}. Confusing the two compiles cleanly and
 * yields silently wrong lookups that no compiler can catch, which is why {@link #CARD_NUM},
 * {@link #CARD_ACCT_ID}, {@link #cardDatPrimaryKeySpan()} and {@link #cardAixAlternateKeySpan()}
 * are named unmistakably and are the only sanctioned way to bind either key.
 *
 * <h2>{@code FILLER X(59)} is emitted, always</h2>
 * The trailing reserved span is a real, written span, never optimised away as "unused padding":
 * {@link #toFixedWidthRecord(FixedWidthCodec)} fills bytes {@value #FILLER_OFFSET} through
 * {@value #RECORD_LENGTH} (exclusive) with {@value #FILLER_LENGTH} spaces on every single encode.
 * Omitting it would produce a 91-byte record, and in a fixed-width dataset that shifts every
 * subsequent byte offset. Two independent facts confirm spaces are correct:
 * <ul>
 *   <li>measured: bytes {@code [91, 150)} are {@value #FILLER_LENGTH} spaces on all fifty rows of
 *       {@code app/data/ASCII/carddata.txt};</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl:1461} issues {@code INITIALIZE CARD-UPDATE-RECORD} before
 *       moving each field and rewriting the record at
 *       {@code LENGTH(LENGTH OF CARD-UPDATE-RECORD)} (line 1483), and COBOL {@code INITIALIZE}
 *       space-fills an alphanumeric {@code FILLER}. Emitting spaces is therefore what the COBOL
 *       itself does on the one path that writes this record.</li>
 * </ul>
 * {@code FILLER} is unnamed and unreferenceable in COBOL, so it is not a component of this type -
 * no program can read or write it - but it is a first-class entry in {@link #LAYOUT}.
 *
 * <h2>No decimal arithmetic lives here</h2>
 * {@code CVACT02Y} declares <strong>zero</strong> {@code COMP-3} or {@code PACKED-DECIMAL} items
 * and <strong>zero</strong> signed or {@code V}-scaled pictures. Both numeric fields are unsigned,
 * scale-free zoned {@code DISPLAY}, so they map to {@code long} and {@code int}; no arbitrary
 * precision decimal type, no rounding mode and no monetary helper is imported, and there is nothing
 * here for one to do. The contrast is instructive: {@code app/cpy/CVACT01Y.cpy}, behind
 * {@code account/model/AccountRecord}, carries five {@code PIC S9(10)V99} fields and genuinely needs
 * the module's fixed-point helper. A reference to that helper, or to any scaled decimal type, in
 * <em>this</em> file would be a defect rather than an improvement. Neither field carries a zoned
 * sign overpunch either: all one hundred numeric spans measured across the fixture are pure digits.
 *
 * <h2>Every character component is exactly its declared width</h2>
 * The constructor pads each {@code PIC X} value on the right with spaces to its declared width -
 * the lossless half of a COBOL alphanumeric {@code MOVE} - and <strong>rejects</strong> an
 * over-wide value rather than quietly truncating it. The direction of truncation is a per-picture
 * decision ({@code PIC X} truncates on the right, {@code PIC 9} on the left) and must be taken
 * deliberately, so a caller that genuinely intends COBOL truncation uses
 * {@link #moving(String, long, int, String, String, String, FixedWidthCodec)}, which routes every
 * field through the codec's explicit move helpers.
 *
 * <p>The resulting invariant - {@link #cardNum()} is always 16 characters,
 * {@link #cardEmbossedName()} always 50, {@link #cardExpiraionDate()} always 10 and
 * {@link #cardActiveStatus()} always 1 - is what makes {@link #equals(Object)} a true field-level
 * comparison and makes the expiry slices total. Trailing spaces are <strong>data</strong>, not
 * noise: they are never trimmed on read, because the differ compares the padding too. The first
 * fixture row's name reads {@code "Aniya Von"} followed by 41 spaces, and that is what this type
 * returns.
 *
 * <h2>The charset is always the caller's</h2>
 * Every entry point that crosses the byte boundary takes an explicit {@link Charset}, or a
 * {@link FixedWidthCodec} that already carries one - {@code IBM037} for the EBCDIC datasets,
 * {@code US-ASCII} for the text fixtures. The platform default is never consulted, and the
 * configuration class that resolves those code pages is deliberately not imported, so this model
 * stays as dependency-light as the copybook it mirrors.
 *
 * <h2>Immutability, value semantics and dependency weight</h2>
 * A record: immutable, with component-wise {@code equals} and {@code hashCode}. That is exactly
 * what {@code COCRDUPC}'s and {@code COACTUPC}'s {@code 9300-CHECK-CHANGE-IN-REC} optimistic
 * concurrency check needs - it re-reads the record and compares it field by field before rewriting
 * - and it is why no version column, and no persistence annotation of any kind, appears here.
 * There is no static mutable state: the only static members are the immutable layout descriptors.
 *
 * <p>Six programs copy {@code CVACT02Y}, and three of them sit outside the {@code card} package:
 * {@code COCRDLIC}, {@code COCRDSLC} and {@code COCRDUPC} here, plus {@code CBACT02C} (which reads
 * the <em>card</em> file despite its migrated name), {@code COACTVWC} and {@code CBTRN01C}. One
 * shared type serves all six, and it imports nothing from any domain package, so no cycle can form
 * across the three domains that depend on it.
 *
 * <h2>What this type deliberately does not do</h2>
 * <ul>
 *   <li>it does not validate. {@code CARD-EXPIRAION-DATE} is an unvalidated ten-byte string in
 *       COBOL and stays one here - parsing it to a date type would reject values the COBOL accepts,
 *       which is a behaviour change;</li>
 *   <li>it does not convert {@code CARD-ACTIVE-STATUS} to a {@code boolean} or an {@code enum}. It
 *       is {@code PIC X(01)} and its byte value is part of the contract;</li>
 *   <li>it declares no {@code REDEFINES} accessors, because {@code CVACT02Y} declares no
 *       {@code REDEFINES}. The overlays in {@code COCRDSLC:84-92} and {@code COCRDUPC:107-109}
 *       belong to those consumers' working storage;</li>
 *   <li>it holds no Spring stereotype. It is a value, not a bean.</li>
 * </ul>
 *
 * @param cardNum           {@code CARD-NUM PIC X(16)} at offset {@value #CARD_NUM_OFFSET}: the
 *                          {@code CARDDAT} base key, alphanumeric and leading-zero bearing, held
 *                          space-padded to exactly {@value #CARD_NUM_LENGTH} characters
 * @param cardAcctId        {@code CARD-ACCT-ID PIC 9(11)} at offset
 *                          {@value #CARD_ACCT_ID_OFFSET}: the {@code CARDAIX} alternate-index key,
 *                          an unsigned scale-free integer below
 *                          {@value #CARD_ACCT_ID_EXCLUSIVE_LIMIT}
 * @param cardCvvCd         {@code CARD-CVV-CD PIC 9(03)} at offset {@value #CARD_CVV_CD_OFFSET}:
 *                          an unsigned scale-free integer below
 *                          {@value #CARD_CVV_CD_EXCLUSIVE_LIMIT}
 * @param cardEmbossedName  {@code CARD-EMBOSSED-NAME PIC X(50)} at offset
 *                          {@value #CARD_EMBOSSED_NAME_OFFSET}, held space-padded to exactly
 *                          {@value #CARD_EMBOSSED_NAME_LENGTH} characters and never trimmed
 * @param cardExpiraionDate {@code CARD-EXPIRAION-DATE PIC X(10)} at offset
 *                          {@value #CARD_EXPIRAION_DATE_OFFSET}, an unvalidated
 *                          {@code YYYY-MM-DD} span of exactly
 *                          {@value #CARD_EXPIRAION_DATE_LENGTH} characters. The name's misspelling
 *                          is the copybook's and is intentional
 * @param cardActiveStatus  {@code CARD-ACTIVE-STATUS PIC X(01)} at offset
 *                          {@value #CARD_ACTIVE_STATUS_OFFSET}: one character, {@code 'Y'} or
 *                          {@code 'N'} in practice, carried verbatim
 * @see FixedWidthCodec
 * @see FixedWidthRecord
 */
public record CardRecord(String cardNum,
                         long cardAcctId,
                         int cardCvvCd,
                         String cardEmbossedName,
                         String cardExpiraionDate,
                         String cardActiveStatus) {

    // =================================================================================================
    // Declared geometry. Every offset and every length is a named constant traceable line by line to
    // app/cpy/CVACT02Y.cpy, so no byte position is ever written as a bare number at a call site.
    // =================================================================================================

    /**
     * The declared total width of {@code CARD-RECORD} in bytes: {@code 150}, stated by the
     * copybook's own {@code (RECLN 150)} header comment at {@code app/cpy/CVACT02Y.cpy:2} and
     * confirmed by the fixture, every row of which is exactly this wide.
     */
    public static final int RECORD_LENGTH = 150;

    /** Absolute 0-based offset of {@code CARD-NUM} ({@code app/cpy/CVACT02Y.cpy:5}). */
    public static final int CARD_NUM_OFFSET = 0;

    /** Declared width of {@code CARD-NUM PIC X(16)}, in characters. */
    public static final int CARD_NUM_LENGTH = 16;

    /** Absolute 0-based offset of {@code CARD-ACCT-ID} ({@code app/cpy/CVACT02Y.cpy:6}). */
    public static final int CARD_ACCT_ID_OFFSET = 16;

    /** Declared digit count of {@code CARD-ACCT-ID PIC 9(11)}, one digit per byte. */
    public static final int CARD_ACCT_ID_LENGTH = 11;

    /** Absolute 0-based offset of {@code CARD-CVV-CD} ({@code app/cpy/CVACT02Y.cpy:7}). */
    public static final int CARD_CVV_CD_OFFSET = 27;

    /** Declared digit count of {@code CARD-CVV-CD PIC 9(03)}, one digit per byte. */
    public static final int CARD_CVV_CD_LENGTH = 3;

    /** Absolute 0-based offset of {@code CARD-EMBOSSED-NAME} ({@code app/cpy/CVACT02Y.cpy:8}). */
    public static final int CARD_EMBOSSED_NAME_OFFSET = 30;

    /** Declared width of {@code CARD-EMBOSSED-NAME PIC X(50)}, in characters. */
    public static final int CARD_EMBOSSED_NAME_LENGTH = 50;

    /**
     * Absolute 0-based offset of {@code CARD-EXPIRAION-DATE} ({@code app/cpy/CVACT02Y.cpy:9}). The
     * misspelling is the copybook's and is preserved deliberately.
     */
    public static final int CARD_EXPIRAION_DATE_OFFSET = 80;

    /** Declared width of {@code CARD-EXPIRAION-DATE PIC X(10)}, in characters. */
    public static final int CARD_EXPIRAION_DATE_LENGTH = 10;

    /** Absolute 0-based offset of {@code CARD-ACTIVE-STATUS} ({@code app/cpy/CVACT02Y.cpy:10}). */
    public static final int CARD_ACTIVE_STATUS_OFFSET = 90;

    /** Declared width of {@code CARD-ACTIVE-STATUS PIC X(01)}, in characters. */
    public static final int CARD_ACTIVE_STATUS_LENGTH = 1;

    /**
     * Absolute 0-based offset of the trailing {@code FILLER} ({@code app/cpy/CVACT02Y.cpy:11}).
     * Everything from here to {@value #RECORD_LENGTH} is reserved and is emitted as spaces.
     */
    public static final int FILLER_OFFSET = 91;

    /** Declared width of {@code FILLER PIC X(59)}, in characters. */
    public static final int FILLER_LENGTH = 59;

    /**
     * One past the largest value {@code CARD-ACCT-ID PIC 9(11)} can hold: {@code 100000000000}.
     *
     * <p>Written as an explicit constant rather than derived from a power-of-ten table, because a
     * static array would be mutable static state however it were declared, and because two literal
     * limits are easier to check against the copybook than a computation is.
     */
    public static final long CARD_ACCT_ID_EXCLUSIVE_LIMIT = 100_000_000_000L;

    /** One past the largest value {@code CARD-CVV-CD PIC 9(03)} can hold: {@code 1000}. */
    public static final int CARD_CVV_CD_EXCLUSIVE_LIMIT = 1_000;

    /**
     * 0-based begin index of the year within {@code CARD-EXPIRAION-DATE}, translating COBOL's
     * 1-based reference modification {@code CARD-EXPIRAION-DATE(1:4)}
     * ({@code app/cbl/COCRDUPC.cbl:1361}).
     */
    public static final int EXPIRAION_YEAR_BEGIN_INDEX = 0;

    /** 0-based end index, exclusive, of the year within {@code CARD-EXPIRAION-DATE}. */
    public static final int EXPIRAION_YEAR_END_INDEX = 4;

    /**
     * 0-based begin index of the month, translating {@code CARD-EXPIRAION-DATE(6:2)}
     * ({@code app/cbl/COCRDUPC.cbl:1363}). 1-based position 5 - 0-based index 4 - is the
     * {@code '-'} separator and is skipped.
     */
    public static final int EXPIRAION_MONTH_BEGIN_INDEX = 5;

    /** 0-based end index, exclusive, of the month within {@code CARD-EXPIRAION-DATE}. */
    public static final int EXPIRAION_MONTH_END_INDEX = 7;

    /**
     * 0-based begin index of the day, translating {@code CARD-EXPIRAION-DATE(9:2)}
     * ({@code app/cbl/COCRDUPC.cbl:1365}). 1-based position 8 - 0-based index 7 - is the second
     * {@code '-'} separator and is skipped.
     */
    public static final int EXPIRAION_DAY_BEGIN_INDEX = 8;

    /**
     * 0-based end index, exclusive, of the day within {@code CARD-EXPIRAION-DATE}, which is also
     * the span's full width.
     */
    public static final int EXPIRAION_DAY_END_INDEX = 10;

    /** The character COBOL pads a {@code PIC X} field with, on the right. */
    private static final String PIC_X_PAD = " ";

    // =================================================================================================
    // Span descriptors, in copybook declaration order. FILLER is a first-class descriptor: the layout
    // below cannot be built unless the seven spans are contiguous from 0 and sum to exactly 150.
    // =================================================================================================

    /**
     * {@code CARD-NUM PIC X(16)} - the {@code CARDDAT} base KSDS key, at offset
     * {@value #CARD_NUM_OFFSET}. Bind a keyed read to this descriptor, never to a hand-written
     * offset. See also {@link #cardDatPrimaryKeySpan()}.
     */
    public static final FieldSpan CARD_NUM =
            FieldSpan.alphanumeric("CARD-NUM", CARD_NUM_OFFSET, CARD_NUM_LENGTH);

    /**
     * {@code CARD-ACCT-ID PIC 9(11)} - the {@code CARDAIX} alternate-index key, at offset
     * {@value #CARD_ACCT_ID_OFFSET}. The sibling {@code CardXrefRecord} carries an eleven-byte
     * account id too, but at offset 25; bind through this descriptor, or through
     * {@link #cardAixAlternateKeySpan()}, so the two can never be transposed.
     */
    public static final FieldSpan CARD_ACCT_ID =
            FieldSpan.unsignedNumeric("CARD-ACCT-ID", CARD_ACCT_ID_OFFSET, CARD_ACCT_ID_LENGTH);

    /** {@code CARD-CVV-CD PIC 9(03)} at offset {@value #CARD_CVV_CD_OFFSET}. */
    public static final FieldSpan CARD_CVV_CD =
            FieldSpan.unsignedNumeric("CARD-CVV-CD", CARD_CVV_CD_OFFSET, CARD_CVV_CD_LENGTH);

    /** {@code CARD-EMBOSSED-NAME PIC X(50)} at offset {@value #CARD_EMBOSSED_NAME_OFFSET}. */
    public static final FieldSpan CARD_EMBOSSED_NAME = FieldSpan.alphanumeric(
            "CARD-EMBOSSED-NAME", CARD_EMBOSSED_NAME_OFFSET, CARD_EMBOSSED_NAME_LENGTH);

    /**
     * {@code CARD-EXPIRAION-DATE PIC X(10)} at offset {@value #CARD_EXPIRAION_DATE_OFFSET}, carrying
     * the copybook's misspelled name <strong>verbatim</strong>: the parity differ keys on
     * {@link FieldSpan#name()}, so this string is part of the contract.
     */
    public static final FieldSpan CARD_EXPIRAION_DATE = FieldSpan.alphanumeric(
            "CARD-EXPIRAION-DATE", CARD_EXPIRAION_DATE_OFFSET, CARD_EXPIRAION_DATE_LENGTH);

    /** {@code CARD-ACTIVE-STATUS PIC X(01)} at offset {@value #CARD_ACTIVE_STATUS_OFFSET}. */
    public static final FieldSpan CARD_ACTIVE_STATUS = FieldSpan.alphanumeric(
            "CARD-ACTIVE-STATUS", CARD_ACTIVE_STATUS_OFFSET, CARD_ACTIVE_STATUS_LENGTH);

    /**
     * {@code FILLER PIC X(59)} at offset {@value #FILLER_OFFSET} - reserved, unreferenceable from
     * COBOL, and nonetheless emitted on every encode. It declares no {@code VALUE}, so
     * {@link FixedWidthRecord#initialise(RecordLayout)} space-fills it, and
     * {@link #toFixedWidthRecord(FixedWidthCodec)} space-fills it again explicitly.
     */
    public static final FieldSpan FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    /**
     * The complete {@code CVACT02Y} layout: seven contiguous spans totalling
     * {@value #RECORD_LENGTH} bytes.
     *
     * <p>{@link RecordLayout} runs its own geometry self-check on construction - contiguous from
     * offset 0, no gaps, no overlaps, and a total equal to the declared record length - so a
     * transcription error in this file surfaces as a class-initialisation failure naming the
     * offending descriptor, long before any dataset byte is read or written.
     */
    public static final RecordLayout LAYOUT = RecordLayout.of(RECORD_LENGTH,
            CARD_NUM,
            CARD_ACCT_ID,
            CARD_CVV_CD,
            CARD_EMBOSSED_NAME,
            CARD_EXPIRAION_DATE,
            CARD_ACTIVE_STATUS,
            FILLER);

    /**
     * Normalises and checks every component, so an instance can never exist in a shape the copybook
     * cannot hold.
     *
     * <p>Each {@code PIC X} value is padded on the right with spaces to its declared width, which is
     * the lossless half of a COBOL alphanumeric {@code MOVE}. An over-wide value is
     * <strong>rejected</strong>: truncating it here would silently pick a direction, and the
     * direction differs by picture, so a caller that intends COBOL truncation must say so through
     * {@link #moving(String, long, int, String, String, String, FixedWidthCodec)}.
     *
     * @throws NullPointerException     if any character component is {@code null}
     * @throws IllegalArgumentException if a character component is wider than its declared width, or
     *                                  a numeric component is negative or too large for its declared
     *                                  digit count
     */
    public CardRecord {
        cardNum = alignedAlphanumeric(cardNum, CARD_NUM);
        requireUnsigned(cardAcctId, CARD_ACCT_ID, CARD_ACCT_ID_EXCLUSIVE_LIMIT);
        requireUnsigned(cardCvvCd, CARD_CVV_CD, CARD_CVV_CD_EXCLUSIVE_LIMIT);
        cardEmbossedName = alignedAlphanumeric(cardEmbossedName, CARD_EMBOSSED_NAME);
        cardExpiraionDate = alignedAlphanumeric(cardExpiraionDate, CARD_EXPIRAION_DATE);
        cardActiveStatus = alignedAlphanumeric(cardActiveStatus, CARD_ACTIVE_STATUS);
    }

    /**
     * Right-pads a {@code PIC X} value to its span's declared width, rejecting an over-wide value.
     *
     * <p>Padding is applied in character space and needs no charset: the pad character is the space,
     * and which <em>byte</em> represents it is decided later, by the code page the caller supplies.
     *
     * @param value the sending value; may be empty, which yields an all-spaces span exactly as COBOL
     *              {@code MOVE SPACES} does
     * @param field the receiving span, whose length is the declared width
     * @return {@code value} padded on the right to exactly {@code field.length()} characters
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is wider than the span
     */
    private static String alignedAlphanumeric(String value, FieldSpan field) {
        Objects.requireNonNull(value, "A value is required for " + field.describe()
                + "; move an empty string to blank the field, as COBOL MOVE SPACES does");
        int width = field.length();
        if (value.length() > width) {
            throw new IllegalArgumentException("Value of " + value.length() + " character(s) does "
                    + "not fit " + field.describe() + " declared by app/cpy/CVACT02Y.cpy. This "
                    + "constructor never truncates, because COBOL truncates a PIC X receiver on the "
                    + "right and a PIC 9 receiver on the left, so the direction has to be chosen "
                    + "deliberately: use CardRecord.moving(...) for COBOL MOVE semantics");
        }
        if (value.length() == width) {
            return value;
        }
        return value + PIC_X_PAD.repeat(width - value.length());
    }

    /**
     * Checks that a value fits an unsigned {@code PIC 9(n)} span.
     *
     * <p>{@code PIC 9} has no sign position, so a negative value has no representation at all and is
     * rejected rather than stored as its magnitude. A value with more digits than the span declares
     * is rejected too, for the same reason an over-wide character value is: COBOL would keep the
     * low-order digits, and that truncation must be requested explicitly.
     *
     * @param value          the value to store
     * @param field          the receiving span, whose length is the declared digit count
     * @param exclusiveLimit one past the largest value the span can hold
     * @throws IllegalArgumentException if {@code value} is negative or at least
     *                                  {@code exclusiveLimit}
     */
    private static void requireUnsigned(long value, FieldSpan field, long exclusiveLimit) {
        if (value < 0) {
            throw new IllegalArgumentException("Cannot store " + value + " in " + field.describe()
                    + ": app/cpy/CVACT02Y.cpy declares it PIC 9, an unsigned picture with no sign "
                    + "position, so a negative value has no representation in it");
        }
        if (value >= exclusiveLimit) {
            throw new IllegalArgumentException("Cannot store " + value + " in " + field.describe()
                    + ": app/cpy/CVACT02Y.cpy declares only " + field.length() + " digit(s), so the "
                    + "value must be below " + exclusiveLimit + ". COBOL would keep the low-order "
                    + "digits; use CardRecord.moving(...) if that truncation is what you intend");
        }
    }

    // =================================================================================================
    // Reading. Every entry point names its code page, or takes a codec that already carries one.
    // =================================================================================================

    /**
     * Decodes a stored {@value #RECORD_LENGTH}-byte {@code CARD-RECORD}.
     *
     * <p>Leading zeros of {@code CARD-NUM} survive, because it is decoded as the alphanumeric field
     * it is declared to be, and trailing spaces of every {@code PIC X} field survive too, because
     * the differ compares them.
     *
     * @param record  the stored bytes; exactly {@value #RECORD_LENGTH} of them
     * @param charset the code page of those bytes, named explicitly by the caller - {@code IBM037}
     *                for an EBCDIC dataset, {@code US-ASCII} for a text fixture
     * @return the decoded record
     * @throws NullPointerException     if {@code record} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not exactly {@value #RECORD_LENGTH}
     *                                  bytes, if {@code charset} is not single-byte for the digits
     *                                  and the space, or if a numeric span does not hold digits
     */
    public static CardRecord decode(byte[] record, Charset charset) {
        return decode(record, new FixedWidthCodec(charset));
    }

    /**
     * Decodes a stored {@value #RECORD_LENGTH}-byte {@code CARD-RECORD} with a codec the caller
     * already holds, which is the form a repository uses so the code page is validated once per
     * component rather than once per row.
     *
     * @param record the stored bytes; exactly {@value #RECORD_LENGTH} of them
     * @param codec  the codec carrying the code page of those bytes
     * @return the decoded record
     * @throws NullPointerException     if {@code record} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not exactly {@value #RECORD_LENGTH}
     *                                  bytes, or a numeric span does not hold digits
     */
    public static CardRecord decode(byte[] record, FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec carrying the record's code page is required to "
                + "decode a CARD-RECORD; the platform default is never assumed");
        return decode(codec.wrap(record, LAYOUT), codec);
    }

    /**
     * Decodes a {@code CARD-RECORD} from a record area the caller already holds - for instance one
     * lifted out of a larger buffer, or one being inspected span by span.
     *
     * @param area  the record area, whose width must match {@link #LAYOUT}
     * @param codec the codec carrying the area's code page
     * @return the decoded record
     * @throws NullPointerException      if {@code area} or {@code codec} is {@code null}
     * @throws IllegalArgumentException  if a numeric span does not hold digits
     * @throws IndexOutOfBoundsException if {@code area} is narrower than {@value #RECORD_LENGTH}
     *                                   bytes
     */
    public static CardRecord decode(FixedWidthRecord area, FixedWidthCodec codec) {
        Objects.requireNonNull(area, "A record area is required to decode a CARD-RECORD");
        Objects.requireNonNull(codec, "A codec carrying the area's code page is required to decode "
                + "a CARD-RECORD");
        return new CardRecord(
                codec.readPicX(area, CARD_NUM),
                readUnsignedLong(area, codec, CARD_ACCT_ID),
                readUnsignedInt(area, codec, CARD_CVV_CD),
                codec.readPicX(area, CARD_EMBOSSED_NAME),
                codec.readPicX(area, CARD_EXPIRAION_DATE),
                codec.readPicX(area, CARD_ACTIVE_STATUS));
    }

    /**
     * Decodes a {@code CARD-RECORD} from a {@value #RECORD_LENGTH}-character row image, which is the
     * shape a fixed-width text fixture row and a fixed-width {@code CHAR} column both arrive in.
     *
     * @param recordImage the row image; exactly {@value #RECORD_LENGTH} characters under a
     *                    single-byte code page
     * @param charset     the code page to encode the image with, named explicitly by the caller
     * @return the decoded record
     * @throws NullPointerException     if {@code recordImage} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if the image does not encode to exactly
     *                                  {@value #RECORD_LENGTH} bytes, or a numeric span does not
     *                                  hold digits
     */
    public static CardRecord decodeImage(String recordImage, Charset charset) {
        Objects.requireNonNull(recordImage, "A record image is required to decode a CARD-RECORD");
        Objects.requireNonNull(charset, "A charset is required to encode a CARD-RECORD image into "
                + "the bytes it stands for; the platform default is never assumed");
        return decode(recordImage.getBytes(charset), charset);
    }

    /**
     * Reads an unsigned numeric span as a {@code long}, re-reporting a non-numeric span with its
     * field name, its offsets and the offending characters.
     *
     * <p>The codec already fails loudly on a non-digit, and deliberately so: silently yielding zero
     * would hide a misaligned row behind a plausible value. This wrapper only makes the diagnosis
     * faster by naming <em>which</em> field of <em>which</em> copybook is wrong.
     *
     * @param area  the record area to read from
     * @param codec the codec carrying the area's code page
     * @param field the numeric span to read
     * @return the value the span's digits denote
     * @throws IllegalArgumentException if the span does not hold unsigned digits
     */
    private static long readUnsignedLong(FixedWidthRecord area, FixedWidthCodec codec,
                                         FieldSpan field) {
        try {
            return codec.readPic9(area, field);
        } catch (IllegalArgumentException notNumeric) {
            throw numericSpanFailure(area, field, notNumeric);
        }
    }

    /**
     * Reads an unsigned numeric span as an {@code int}, with the same diagnosis as its sibling.
     *
     * @param area  the record area to read from
     * @param codec the codec carrying the area's code page
     * @param field the numeric span to read
     * @return the value the span's digits denote
     * @throws IllegalArgumentException if the span does not hold unsigned digits, or denotes a value
     *                                  outside the {@code int} range
     */
    private static int readUnsignedInt(FixedWidthRecord area, FixedWidthCodec codec,
                                       FieldSpan field) {
        try {
            return codec.readPic9AsInt(area, field);
        } catch (IllegalArgumentException notNumeric) {
            throw numericSpanFailure(area, field, notNumeric);
        }
    }

    /**
     * Builds the diagnosis shared by both numeric readers, preserving the codec's cause.
     *
     * @param area  the record area whose span read back wrong, quoted into the message
     * @param field the offending span, named with its offsets
     * @param cause the codec's own exception, retained so the low-level detail is not lost
     * @return the exception to throw
     */
    private static IllegalArgumentException numericSpanFailure(FixedWidthRecord area,
                                                               FieldSpan field,
                                                               IllegalArgumentException cause) {
        return new IllegalArgumentException("Span " + field.describe() + " of a " + RECORD_LENGTH
                + "-byte CARD-RECORD reads '" + area.readSpan(field) + "', which is not the "
                + "unsigned digits app/cpy/CVACT02Y.cpy declares there. Either the row is "
                + "misaligned - every row of app/data/ASCII/carddata.txt is exactly " + RECORD_LENGTH
                + " bytes - or the code page is wrong for this data", cause);
    }

    // =================================================================================================
    // Construction from screen or program values, and the COBOL INITIALIZE equivalent.
    // =================================================================================================

    /**
     * Builds a record by applying full COBOL {@code MOVE} semantics to every field, including
     * truncation.
     *
     * <p>This is the deliberate-truncation counterpart to the constructor. Each character field goes
     * through {@link FixedWidthCodec#movePicX(String, int)}, which pads on the right and truncates on
     * the <strong>right</strong>; each numeric field goes through
     * {@link FixedWidthCodec#movePic9(long, int)}, which zero-fills on the left and truncates on the
     * <strong>left</strong>, keeping the low-order digits exactly as a COBOL numeric receiver does.
     * Routing through the codec rather than a hand-written format string is what keeps the direction
     * of truncation visible: {@code MOVE} occurs 2,795 times in this estate and is a larger parity
     * risk than its arithmetic.
     *
     * @param cardNum           the sending value for {@code CARD-NUM}
     * @param cardAcctId        the sending value for {@code CARD-ACCT-ID}; must not be negative
     * @param cardCvvCd         the sending value for {@code CARD-CVV-CD}; must not be negative
     * @param cardEmbossedName  the sending value for {@code CARD-EMBOSSED-NAME}
     * @param cardExpiraionDate the sending value for {@code CARD-EXPIRAION-DATE}
     * @param cardActiveStatus  the sending value for {@code CARD-ACTIVE-STATUS}
     * @param codec             the codec supplying the move helpers
     * @return the record, with every field at its declared width
     * @throws NullPointerException     if {@code codec} or any character argument is {@code null}
     * @throws IllegalArgumentException if a numeric argument is negative
     */
    public static CardRecord moving(String cardNum,
                                    long cardAcctId,
                                    int cardCvvCd,
                                    String cardEmbossedName,
                                    String cardExpiraionDate,
                                    String cardActiveStatus,
                                    FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to apply COBOL MOVE semantics; it owns "
                + "the pad and truncate rules for PIC X and PIC 9 so no call site re-implements them");
        return new CardRecord(
                codec.movePicX(cardNum, CARD_NUM_LENGTH),
                codec.decodePic9(codec.movePic9(cardAcctId, CARD_ACCT_ID_LENGTH)),
                codec.decodePic9AsInt(codec.movePic9(cardCvvCd, CARD_CVV_CD_LENGTH)),
                codec.movePicX(cardEmbossedName, CARD_EMBOSSED_NAME_LENGTH),
                codec.movePicX(cardExpiraionDate, CARD_EXPIRAION_DATE_LENGTH),
                codec.movePicX(cardActiveStatus, CARD_ACTIVE_STATUS_LENGTH));
    }

    /**
     * The COBOL {@code INITIALIZE CARD-RECORD} equivalent: every {@code PIC X} field all spaces and
     * every {@code PIC 9} field zero.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:1461} opens its update path with
     * {@code INITIALIZE CARD-UPDATE-RECORD} before moving each field, so this is the starting point
     * of the one program that rewrites this record.
     *
     * @return an initialised record: sixteen spaces, account id 0, CVV 0, fifty spaces, ten spaces
     *         and one space
     */
    public static CardRecord initialised() {
        return new CardRecord("", 0L, 0, "", "", "");
    }

    // =================================================================================================
    // Writing. The image is always the full 150 bytes, FILLER included.
    // =================================================================================================

    /**
     * Serialises this record to its full {@value #RECORD_LENGTH}-byte image, the trailing
     * {@code FILLER} included.
     *
     * <p>The full width matters twice over: {@code app/cbl/COCRDUPC.cbl:1478} rewrites the record at
     * {@code LENGTH(LENGTH OF CARD-UPDATE-RECORD)}, and {@code app/cbl/CBACT02C.cbl:78} issues
     * {@code DISPLAY CARD-RECORD}, sending the entire image to {@code SYSOUT}. A short image would
     * fail both on length before any field was even compared.
     *
     * @param charset the code page to write in, named explicitly by the caller
     * @return exactly {@value #RECORD_LENGTH} bytes
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not single-byte for the digits and the
     *                                  space
     */
    public byte[] encode(Charset charset) {
        return encode(new FixedWidthCodec(charset));
    }

    /**
     * Serialises this record to its full {@value #RECORD_LENGTH}-byte image using a codec the caller
     * already holds.
     *
     * @param codec the codec carrying the code page to write in
     * @return exactly {@value #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public byte[] encode(FixedWidthCodec codec) {
        return toFixedWidthRecord(codec).toByteArray();
    }

    /**
     * Serialises this record to its {@value #RECORD_LENGTH}-character row image - the form a
     * fixed-width text row, a fixed-width {@code CHAR} column and {@code CBACT02C}'s
     * {@code DISPLAY CARD-RECORD} line all take.
     *
     * <p>The image is derived from {@link #encode(Charset)} rather than assembled from the fields
     * directly, so that the characters and the bytes can never disagree about the record's width.
     *
     * @param charset the code page to write in, named explicitly by the caller
     * @return the row image; exactly {@value #RECORD_LENGTH} characters under a single-byte code page
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not single-byte for the digits and the
     *                                  space
     */
    public String encodeToImage(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to render a CARD-RECORD image; the "
                + "platform default is never assumed");
        return new String(encode(charset), charset);
    }

    /**
     * Writes this record into a freshly allocated record area over {@link #LAYOUT}.
     *
     * <p>The area is allocated through {@link FixedWidthCodec#newRecord(RecordLayout)}, which
     * initialises every span first, and the trailing {@code FILLER} is then space-filled again
     * <em>explicitly</em>. The second fill is redundant by construction and kept deliberately: it
     * states at this call site, where a reader is looking for it, that bytes
     * {@value #FILLER_OFFSET} to {@value #RECORD_LENGTH} are emitted as
     * {@value #FILLER_LENGTH} spaces, which is what {@code INITIALIZE CARD-UPDATE-RECORD} does in
     * {@code app/cbl/COCRDUPC.cbl:1461} before the rewrite.
     *
     * <p>Each field is written through the codec's picture-aware helpers, so {@code PIC X} fields are
     * left justified and space-padded while {@code PIC 9} fields are right justified and zero-filled:
     * account id {@code 50} becomes {@code 00000000050} and CVV {@code 747} stays {@code 747}, both
     * as measured in the fixture.
     *
     * @param codec the codec carrying the code page to write in
     * @return a record area of exactly {@value #RECORD_LENGTH} bytes holding this record
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public FixedWidthRecord toFixedWidthRecord(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec carrying the target code page is required to "
                + "serialise a CARD-RECORD; the platform default is never assumed");
        FixedWidthRecord area = codec.newRecord(LAYOUT);
        codec.writePicX(area, CARD_NUM, cardNum);
        codec.writePic9(area, CARD_ACCT_ID, cardAcctId);
        codec.writePic9(area, CARD_CVV_CD, cardCvvCd);
        codec.writePicX(area, CARD_EMBOSSED_NAME, cardEmbossedName);
        codec.writePicX(area, CARD_EXPIRAION_DATE, cardExpiraionDate);
        codec.writePicX(area, CARD_ACTIVE_STATUS, cardActiveStatus);
        area.fill(FILLER_OFFSET, FILLER_LENGTH, area.spacePadByte());
        return area;
    }

    // =================================================================================================
    // Views over the expiry span. COBOL slices it with 1-based reference modification; these are the
    // 0-based translations, and they are views over the stored X(10) span, never separate state.
    // =================================================================================================

    /**
     * The year of the expiry span: {@code CARD-EXPIRAION-DATE(1:4)} in COBOL, characters
     * {@code [0, 4)} here. The first fixture row holds {@code "2023-03-09"}, so this returns
     * {@code "2023"}.
     *
     * <p>Sliced from the stored span rather than held separately, so the raw ten bytes stay the single
     * source of truth - which is what {@code 9300-CHECK-CHANGE-IN-REC} compares and what
     * {@code app/cbl/COCRDUPC.cbl:1467-1475} reassembles with
     * {@code STRING ... DELIMITED BY SIZE}. No parsing and no validation is performed: the span is an
     * unvalidated ten-byte string in COBOL and stays one here.
     *
     * @return four characters
     */
    public String cardExpiraionDateYear() {
        return cardExpiraionDate.substring(EXPIRAION_YEAR_BEGIN_INDEX, EXPIRAION_YEAR_END_INDEX);
    }

    /**
     * The month of the expiry span: {@code CARD-EXPIRAION-DATE(6:2)} in COBOL, characters
     * {@code [5, 7)} here - {@code "03"} for the first fixture row. 0-based index 4 is the
     * {@code '-'} separator and is skipped; it is a {@code '-'} on all fifty fixture rows.
     *
     * @return two characters
     */
    public String cardExpiraionDateMonth() {
        return cardExpiraionDate.substring(EXPIRAION_MONTH_BEGIN_INDEX, EXPIRAION_MONTH_END_INDEX);
    }

    /**
     * The day of the expiry span: {@code CARD-EXPIRAION-DATE(9:2)} in COBOL, characters
     * {@code [8, 10)} here - {@code "09"} for the first fixture row. 0-based index 7 is the second
     * {@code '-'} separator and is skipped.
     *
     * @return two characters
     */
    public String cardExpiraionDateDay() {
        return cardExpiraionDate.substring(EXPIRAION_DAY_BEGIN_INDEX, EXPIRAION_DAY_END_INDEX);
    }

    // =================================================================================================
    // Key images and key descriptors. VSAM is keyed on bytes, so a keyed read needs the field's image
    // rather than its parsed value.
    // =================================================================================================

    /**
     * The {@code CARDAIX} key image: {@link #cardAcctId()} as {@value #CARD_ACCT_ID_LENGTH} zoned
     * digits, zero-filled on the left - account id {@code 50} renders {@code "00000000050"}, exactly
     * as the fixture stores it.
     *
     * <p>Produced by the codec's {@code PIC 9} move helper rather than a format string, so the
     * zero-fill and truncation rules stay in one place.
     *
     * @param codec the codec supplying the move helper
     * @return exactly {@value #CARD_ACCT_ID_LENGTH} digits
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public String cardAcctIdImage(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to render the CARD-ACCT-ID key image");
        return codec.movePic9(cardAcctId, CARD_ACCT_ID_LENGTH);
    }

    /**
     * {@link #cardCvvCd()} as {@value #CARD_CVV_CD_LENGTH} zoned digits, zero-filled on the left -
     * CVV {@code 747} renders {@code "747"}. This is the shape
     * {@code app/cbl/COCRDUPC.cbl:1464} needs when it moves the value through the alphanumeric view
     * {@code CARD-CVV-CD-X PIC X(03)}.
     *
     * @param codec the codec supplying the move helper
     * @return exactly {@value #CARD_CVV_CD_LENGTH} digits
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public String cardCvvCdImage(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to render the CARD-CVV-CD image");
        return codec.movePic9(cardCvvCd, CARD_CVV_CD_LENGTH);
    }

    /**
     * The descriptor of the {@code CARDDAT} base KSDS key: {@code CARD-NUM}, sixteen characters at
     * offset {@value #CARD_NUM_OFFSET}.
     *
     * <p>Named for the dataset rather than the field so that a keyed read cannot bind to the wrong
     * span: this is the key a read by card number uses.
     *
     * @return the {@link #CARD_NUM} descriptor
     */
    public static FieldSpan cardDatPrimaryKeySpan() {
        return CARD_NUM;
    }

    /**
     * The descriptor of the {@code CARDAIX} alternate-index key: {@code CARD-ACCT-ID}, eleven digits
     * at offset {@value #CARD_ACCT_ID_OFFSET}.
     *
     * <p>This is the key a read by account id uses. It is <strong>not</strong> the account id of
     * {@code CardXrefRecord}, which sits at offset 25 of a different record; naming the access path
     * here is what keeps the two apart.
     *
     * @return the {@link #CARD_ACCT_ID} descriptor
     */
    public static FieldSpan cardAixAlternateKeySpan() {
        return CARD_ACCT_ID;
    }

    // =================================================================================================
    // Copy-with accessors. Uniform across all six components: COCRDUPC rebuilds the whole record, and
    // a service that changes a subset of fields expresses that change once, here.
    // =================================================================================================

    /**
     * A copy of this record with {@code CARD-NUM} replaced.
     *
     * @param replacement the new value, no wider than {@value #CARD_NUM_LENGTH} characters
     * @return a new record; this one is unchanged
     * @throws NullPointerException     if {@code replacement} is {@code null}
     * @throws IllegalArgumentException if {@code replacement} is too wide
     */
    public CardRecord withCardNum(String replacement) {
        return new CardRecord(replacement, cardAcctId, cardCvvCd, cardEmbossedName,
                cardExpiraionDate, cardActiveStatus);
    }

    /**
     * A copy of this record with {@code CARD-ACCT-ID} replaced.
     *
     * @param replacement the new value; not negative and below
     *                    {@value #CARD_ACCT_ID_EXCLUSIVE_LIMIT}
     * @return a new record; this one is unchanged
     * @throws IllegalArgumentException if {@code replacement} is out of range
     */
    public CardRecord withCardAcctId(long replacement) {
        return new CardRecord(cardNum, replacement, cardCvvCd, cardEmbossedName, cardExpiraionDate,
                cardActiveStatus);
    }

    /**
     * A copy of this record with {@code CARD-CVV-CD} replaced.
     *
     * @param replacement the new value; not negative and below
     *                    {@value #CARD_CVV_CD_EXCLUSIVE_LIMIT}
     * @return a new record; this one is unchanged
     * @throws IllegalArgumentException if {@code replacement} is out of range
     */
    public CardRecord withCardCvvCd(int replacement) {
        return new CardRecord(cardNum, cardAcctId, replacement, cardEmbossedName, cardExpiraionDate,
                cardActiveStatus);
    }

    /**
     * A copy of this record with {@code CARD-EMBOSSED-NAME} replaced.
     *
     * <p>The value is stored exactly as given, right-padded to
     * {@value #CARD_EMBOSSED_NAME_LENGTH} characters. Note that {@code COCRDUPC} upper-cases this
     * field with {@code INSPECT ... CONVERTING} before it compares or writes it; that transformation
     * belongs to the update service, not to this model, so nothing is folded here.
     *
     * @param replacement the new value, no wider than {@value #CARD_EMBOSSED_NAME_LENGTH} characters
     * @return a new record; this one is unchanged
     * @throws NullPointerException     if {@code replacement} is {@code null}
     * @throws IllegalArgumentException if {@code replacement} is too wide
     */
    public CardRecord withCardEmbossedName(String replacement) {
        return new CardRecord(cardNum, cardAcctId, cardCvvCd, replacement, cardExpiraionDate,
                cardActiveStatus);
    }

    /**
     * A copy of this record with {@code CARD-EXPIRAION-DATE} replaced - the misspelling is the
     * copybook's and is intentional.
     *
     * <p>The replacement is stored verbatim and is not validated, matching
     * {@code app/cbl/COCRDUPC.cbl:1467-1475}, which composes the span by concatenating year,
     * {@code '-'}, month, {@code '-'} and day with {@code STRING ... DELIMITED BY SIZE} and stores
     * the result without re-checking it.
     *
     * @param replacement the new value, no wider than {@value #CARD_EXPIRAION_DATE_LENGTH}
     *                    characters
     * @return a new record; this one is unchanged
     * @throws NullPointerException     if {@code replacement} is {@code null}
     * @throws IllegalArgumentException if {@code replacement} is too wide
     */
    public CardRecord withCardExpiraionDate(String replacement) {
        return new CardRecord(cardNum, cardAcctId, cardCvvCd, cardEmbossedName, replacement,
                cardActiveStatus);
    }

    /**
     * A copy of this record with {@code CARD-ACTIVE-STATUS} replaced. The value stays a single
     * character; it is never widened into a {@code boolean} or an {@code enum}, because its byte is
     * part of the record contract.
     *
     * @param replacement the new value, no wider than {@value #CARD_ACTIVE_STATUS_LENGTH} character
     * @return a new record; this one is unchanged
     * @throws NullPointerException     if {@code replacement} is {@code null}
     * @throws IllegalArgumentException if {@code replacement} is too wide
     */
    public CardRecord withCardActiveStatus(String replacement) {
        return new CardRecord(cardNum, cardAcctId, cardCvvCd, cardEmbossedName, cardExpiraionDate,
                replacement);
    }

    /**
     * A diagnostic rendering that names each field as its copybook spells it and quotes every
     * character field so its padding is visible.
     *
     * <p>Nothing is truncated or elided: when a parity case fails, the whole stored value has to be
     * readable in the failure message. The generated record {@code toString} would print the padding
     * unquoted, leaving a reader unable to tell a 9-character name from a 50-character one.
     *
     * @return the rendering, always naming all six fields and the reserved span
     */
    @Override
    public String toString() {
        return "CARD-RECORD[" + RECORD_LENGTH + " bytes]{"
                + "CARD-NUM='" + cardNum
                + "', CARD-ACCT-ID=" + cardAcctId
                + ", CARD-CVV-CD=" + cardCvvCd
                + ", CARD-EMBOSSED-NAME='" + cardEmbossedName
                + "', CARD-EXPIRAION-DATE='" + cardExpiraionDate
                + "', CARD-ACTIVE-STATUS='" + cardActiveStatus
                + "', FILLER=" + FILLER_LENGTH + " space(s)}";
    }

}

package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * The transaction category balance record: the single Java type for {@code app/cpy/CVTRA01Y.cpy},
 * whose own header comment reads <em>"Data-structure for transaction category balance (RECLN =
 * 50)"</em>. This is the {@code TCATBALF} record - <strong>50 bytes with a 17-byte composite
 * key</strong>.
 *
 * <h2>The copybook, transcribed</h2>
 * <pre>
 *  01  TRAN-CAT-BAL-RECORD.
 *      05  TRAN-CAT-KEY.
 *         10 TRANCAT-ACCT-ID                       PIC 9(11).
 *         10 TRANCAT-TYPE-CD                       PIC X(02).
 *         10 TRANCAT-CD                            PIC 9(04).
 *      05  TRAN-CAT-BAL                            PIC S9(09)V99.
 *      05  FILLER                                  PIC X(22).
 * </pre>
 * <table border="1">
 *   <caption>Byte layout, 1-based COBOL positions converted to the 0-based offsets used here</caption>
 *   <tr><th>COBOL item</th><th>Level</th><th>PICTURE</th><th>1-based</th><th>0-based offset</th>
 *       <th>Length</th><th>Java type</th></tr>
 *   <tr><td>{@code TRAN-CAT-KEY}</td><td>05 group</td><td>-</td><td>1-17</td><td>0</td>
 *       <td><strong>17</strong></td><td>{@link TranCatKey}</td></tr>
 *   <tr><td>&nbsp;{@code TRANCAT-ACCT-ID}</td><td>10</td><td>{@code 9(11)}</td><td>1-11</td><td>0</td>
 *       <td>11</td><td>{@code long}</td></tr>
 *   <tr><td>&nbsp;{@code TRANCAT-TYPE-CD}</td><td>10</td><td>{@code X(02)}</td><td>12-13</td>
 *       <td>11</td><td>2</td><td>{@link String}</td></tr>
 *   <tr><td>&nbsp;{@code TRANCAT-CD}</td><td>10</td><td>{@code 9(04)}</td><td>14-17</td><td>13</td>
 *       <td>4</td><td>{@code int}</td></tr>
 *   <tr><td>{@code TRAN-CAT-BAL}</td><td>05</td><td>{@code S9(09)V99}</td><td>18-28</td>
 *       <td><strong>17</strong></td><td><strong>11</strong></td>
 *       <td>{@link BigDecimal} scale 2</td></tr>
 *   <tr><td>{@code FILLER}</td><td>05</td><td>{@code X(22)}</td><td>29-50</td><td>28</td><td>22</td>
 *       <td>reserved span</td></tr>
 * </table>
 * The spans sum to {@code 17 + 11 + 22 = 50}. A signed {@code PIC S9(p)V99} field occupies exactly
 * {@code p + 2} bytes - 11 here - because the sign is <em>overpunched into the trailing byte</em> and
 * consumes no byte of its own. Reserving a twelfth byte for it would make this record 51 bytes and is
 * rejected outright by {@link #LAYOUT}'s own self-check.
 *
 * <h2>Consumers</h2>
 * Two COBOL programs copy this copybook, and nothing else does:
 * <ul>
 *   <li>{@code app/cbl/CBTRN02C.cbl:126} - the daily transaction poster. It opens {@code TCATBALF}
 *       {@code I-O} at {@code :329}, builds the read key at {@code :469-471}, reads at {@code :474},
 *       and then either <em>creates</em> a record at {@code :503-510} or <em>updates</em> one at
 *       {@code :526-528}.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl:97} - the interest calculator. It browses {@code TCATBALF}
 *       sequentially ({@code :30} {@code ACCESS MODE IS SEQUENTIAL}), reads at {@code :326}, displays
 *       the whole record at {@code :193}, breaks on a change of {@code TRANCAT-ACCT-ID} at
 *       {@code :194}, feeds the {@code DISCGRP} key from {@code TRANCAT-CD} and
 *       {@code TRANCAT-TYPE-CD} at {@code :211-212}, and consumes {@code TRAN-CAT-BAL} in the
 *       interest formula at {@code :464-465}.</li>
 * </ul>
 * Both jobs bind the same dataset through the same DD name, {@link #DD_NAME}: see the
 * {@code //TCATBALF DD DISP=SHR} statements at {@code app/jcl/POSTTRAN.jcl:41-42} and
 * {@code app/jcl/INTCALC.jcl:27-28}. The dataset's own name is deliberately <em>not</em> written here,
 * not even in a comment: it is resolved from {@code src/main/resources/application.yml} under the
 * {@code TCATBALF} binding, so no dataset name appears anywhere in Java source (gate G46).
 *
 * <h2>Provenance of this layout - three independent confirmations</h2>
 * COBOL cannot be executed in this environment, so every expectation encoded here is
 * <strong>statically derived</strong> rather than captured from a live run. The derivation is
 * corroborated three ways, deliberately, because a single reading of a copybook is exactly the kind of
 * evidence a misreading survives:
 * <ol>
 *   <li><strong>The consuming {@code FD} in {@code CBTRN02C}.</strong>
 *       {@code app/cbl/CBTRN02C.cbl:92-97} declares {@code FD-TRAN-CAT-KEY} as
 *       {@code 9(11) + X(02) + 9(04)} followed by {@code FD-FD-TRAN-CAT-DATA PIC X(33)}, and
 *       {@code 17 + 33 = 50}. {@code RECORD KEY IS FD-TRAN-CAT-KEY} at {@code :60} independently
 *       confirms that the key is the first 17 bytes. The doubled {@code FD-FD-} prefix is a quirk of
 *       that one program, not of this copybook, and is not reproduced here.</li>
 *   <li><strong>The consuming {@code FD} in {@code CBACT04C}.</strong>
 *       {@code app/cbl/CBACT04C.cbl:62-67} declares the identical three-part key split and the same
 *       {@code X(33)} data remainder.</li>
 *   <li><strong>A decode of the shipped fixture.</strong> {@code app/data/ASCII/tcatbal.txt} was
 *       measured at 50 records of exactly 50 bytes. Row 1 decodes cleanly at these offsets:
 *       {@code TRANCAT-ACCT-ID} {@code 00000000001}, {@code TRANCAT-TYPE-CD} {@code 01},
 *       {@code TRANCAT-CD} {@code 0001}, {@code TRAN-CAT-BAL} raw <code>0000000000&#123;</code> - the
 *       <code>&#123;</code> is the zoned overpunch for <strong>+0</strong>, so the value is
 *       {@code 0.00} - and {@code FILLER} 22 ASCII <strong>zeros</strong>. All 50 rows carry
 *       <code>&#123;</code>, so every balance in the fixture is {@code +0.00}.</li>
 * </ol>
 * The dataset binding in {@code src/main/resources/application.yml} states the same two numbers
 * independently: {@code record-length: 50} and {@code key-length: 17}.
 *
 * <h2>Collision trap 1 - {@code CVTRA02Y} also totals 50, but its key is 16</h2>
 * {@code app/cpy/CVTRA02Y.cpy} ({@code 01 DIS-GROUP-RECORD}, modelled by
 * {@code account/model/DisclosureGroupRecord}) carries the <em>same</em> header comment,
 * {@code RECLN = 50}, and the same three-part-key-then-signed-amount-then-{@code FILLER} shape. Every
 * number in it is nonetheless different:
 * <table border="1">
 *   <caption>{@code CVTRA01Y} against {@code CVTRA02Y} - same total, no shared offset after byte 11</caption>
 *   <tr><th></th><th>{@code CVTRA01Y} (this record)</th><th>{@code CVTRA02Y}</th></tr>
 *   <tr><td>key group</td><td>{@code TRAN-CAT-KEY}, <strong>17</strong> bytes</td>
 *       <td>{@code DIS-GROUP-KEY}, <strong>16</strong> bytes</td></tr>
 *   <tr><td>first key item</td><td>{@code TRANCAT-ACCT-ID PIC 9(11)} - numeric, 11 bytes</td>
 *       <td>{@code DIS-ACCT-GROUP-ID PIC X(10)} - alphanumeric, 10 bytes</td></tr>
 *   <tr><td>signed amount</td><td>{@code TRAN-CAT-BAL PIC S9(09)V99} - <strong>11 bytes at 0-based
 *       17</strong></td><td>{@code DIS-INT-RATE PIC S9(04)V99} - <strong>6 bytes at 0-based
 *       16</strong></td></tr>
 *   <tr><td>{@code FILLER}</td><td>{@code X(22)} at 28</td><td>{@code X(28)} at 22</td></tr>
 *   <tr><td>total</td><td>50</td><td>50</td></tr>
 * </table>
 * Because <strong>both records total exactly 50</strong>, a total-width assertion passes either way:
 * the 16-versus-17 slip is <em>invisible</em> to acceptance gate G19. A one-byte shift would
 * nonetheless move every byte of the amount and corrupt gate G25, the interest formula. The confusion
 * is live rather than theoretical - {@code CBACT04C} reads a {@code TCATBALF} record and then keys
 * {@code DISCGRP} from its fields, so the two layouts are worked side by side in one loop. The
 * fixtures show the difference plainly: {@code discgrp.txt} row 1 is {@code A000000000} / {@code 01} /
 * {@code 0001} / <code>00150&#123;</code> - a <strong>6</strong>-byte rate meaning {@code 0015.00} -
 * followed by 28 zeros, against this record's <strong>11</strong>-byte balance at offset 17.
 * {@link #TRAN_CAT_KEY_LENGTH} and {@link #verifyKeyGeometry(int, int)} exist to make that slip
 * impossible to introduce silently.
 *
 * <h2>Collision trap 2 - {@code CVTRA04Y} declares the same name for a 6-byte group</h2>
 * {@code app/cpy/CVTRA04Y.cpy} ({@code 01 TRAN-CAT-RECORD}, modelled by
 * {@code TranCategoryRecord}) declares a group whose COBOL name is <strong>also
 * {@code TRAN-CAT-KEY}</strong> and which is {@code TRAN-TYPE-CD PIC X(02)} plus
 * {@code TRAN-CAT-CD PIC 9(04)} - <strong>6 bytes</strong>, with no account identifier at all.
 * Identical group name; 17 bytes here against 6 bytes there.
 *
 * <p>Both names are reproduced verbatim rather than disambiguated, because the parity differ compares
 * field by field <em>by name</em> and renaming either one would make a real difference invisible. The
 * consequence for callers is a hard rule: {@link TranCatKey} and this record's 17-byte key image are
 * <strong>never</strong> interchangeable with the 6-byte key of {@code CVTRA04Y}. Each key is a
 * distinct Java type, and {@code TranCatBalRepository} (17-byte key) and
 * {@code TranCategoryRepository} (6-byte key) declare their own key value type on that basis. This
 * class is the <em>authoritative</em> byte layout for the 17-byte key: a repository serialises the key
 * <em>through</em> {@link TranCatKey#toByteArray(Charset)} or {@link #tranCatKeyBytes()} rather than
 * re-deriving the offsets for itself.
 *
 * <h2>{@code INITIALIZE} skips {@code FILLER}, and that is reproduced rather than corrected</h2>
 * {@code app/cbl/CBTRN02C.cbl:503-510} is the create-on-miss path, and it is the subtlest behaviour
 * this class carries:
 * <pre>
 *   INITIALIZE TRAN-CAT-BAL-RECORD
 *   MOVE XREF-ACCT-ID TO TRANCAT-ACCT-ID
 *   MOVE DALYTRAN-TYPE-CD TO TRANCAT-TYPE-CD
 *   MOVE DALYTRAN-CAT-CD TO TRANCAT-CD
 *   ADD DALYTRAN-AMT TO TRAN-CAT-BAL
 *
 *   WRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD
 * </pre>
 * COBOL's {@code INITIALIZE} without {@code REPLACING} implies {@code SPACE} for alphanumeric items
 * and {@code ZERO} for numeric items, and it <strong>skips {@code FILLER} entirely</strong>. A newly
 * created record's {@code FILLER X(22)} therefore retains whatever bytes the record area already held
 * - which, in this paragraph, is what the immediately preceding {@code READ} at {@code :474} left
 * there. {@link #initialize()} reproduces exactly that: the four named items are reset to their
 * category defaults and the {@code FILLER} span is left untouched. It is not "improved" into a clean
 * space-fill or zero-fill.
 *
 * <h2>{@code FILLER} content must be carried through, never assumed</h2>
 * Acceptance gate G21's space-fill is right for a record built from nothing, which is why
 * {@link #newInstance(Charset)} space-fills the span. It is <em>wrong</em> for a record read from the
 * dataset: the measured fixture holds 22 ASCII <strong>zeros</strong> there, whereas
 * {@code dailytran.txt} holds spaces in its {@code FILLER}. Since these records are read and then
 * rewritten - {@code app/cbl/CBTRN02C.cbl:527-528} adds to the balance and issues {@code REWRITE} -
 * the {@code FILLER} bytes that came in have to go back out <em>verbatim</em>. This class therefore
 * retains the whole 50-byte record area on the instance and treats it as the single source of truth,
 * so the reserved bytes survive a read, an {@link #initialize()} and an {@link #encode()} unchanged.
 * Emitting spaces over an input record's zero-filled {@code FILLER} would be a 22-byte difference per
 * record that the parity differ reports (gates G17 and G18) even though the width is right and no
 * field-level comparison could see it.
 *
 * <h2>Numeric policy</h2>
 * {@code TRAN-CAT-BAL} is a {@link BigDecimal} of scale <strong>exactly 2</strong>, and every store
 * routes through {@link CobolDecimal}, which is the one place in this module where a rounding mode is
 * named: {@link CobolDecimal#COBOL_ROUNDING}, that is {@code RoundingMode.DOWN}. The keyword
 * {@code ROUNDED} appears <strong>zero</strong> times across all 28 programs, so COBOL truncates
 * excess fractional digits on store; {@code HALF_UP}, {@code HALF_EVEN}, {@code CEILING} and
 * {@code FLOOR} are all wrong here and are named in this paragraph only in order to prohibit them -
 * not one of them appears in an expression anywhere in this file. Neither {@code double} nor
 * {@code float} is used for any value derived from a {@code PICTURE} clause (gates G22, G23, G24).
 * Every comparison of a decimal value uses {@link BigDecimal#compareTo(BigDecimal)} or
 * {@link BigDecimal#signum()}, never {@link BigDecimal#equals(Object)}, which is scale-sensitive and
 * would judge {@code 0.00} and {@code 0} unequal.
 *
 * <h2>What this class deliberately does not contain</h2>
 * {@code CVTRA01Y} declares no {@code OCCURS}, no {@code REDEFINES}, no {@code COMP} or
 * {@code COMP-3}, no {@code SIGN} clause, no {@code USAGE} clause and no {@code 88}-level condition
 * name - all verified by inspection of the whole 13-line copybook. So there is no table to index, no
 * overlay accessor and no condition-name predicate here: gate G33's 1-based-to-0-based rule is
 * satisfied vacuously, and inventing any of those constructs would be scope creep. There is likewise
 * no persistence annotation, no generated table definition, no identifier annotation and no version
 * column: the {@code TCATBALF} KSDS is reached by plain JDBC with no schema change whatsoever (gate
 * G44). This type carries no Spring annotation and holds no static mutable state (gate G53).
 *
 * <h2>Thread safety</h2>
 * An instance wraps a mutable byte span, because that is what a COBOL record area is. Instances are
 * fully independent - no static mutable state exists - but a single instance is not safe for
 * concurrent mutation. Confine one to the step, chunk or request that owns it, exactly as a COBOL
 * program confines its record area to one run unit.
 *
 * @see TranCatKey the 17-byte composite key, which is never the 6-byte key of {@code CVTRA04Y}
 * @see CobolDecimal#monthlyInterest(BigDecimal, BigDecimal) the formula that consumes this balance
 */
public final class TranCatBalRecord {

    // =================================================================================================
    // Identity. Stated as constants so a diagnostic, a parity fingerprint or a repository log line can
    // name the copybook and the record group without a caller writing either string out again.
    // =================================================================================================

    /** The copybook this type transcribes: {@code app/cpy/CVTRA01Y.cpy}. */
    public static final String COPYBOOK = "CVTRA01Y";

    /** The COBOL group name of the record itself, verbatim: {@code TRAN-CAT-BAL-RECORD}. */
    public static final String RECORD_NAME = "TRAN-CAT-BAL-RECORD";

    /**
     * The dataset this record is stored in, as the {@code SELECT ... ASSIGN TO} clauses of both
     * consumers name it: {@code TCATBALF} at {@code app/cbl/CBTRN02C.cbl:57} and
     * {@code app/cbl/CBACT04C.cbl:28}. The dataset <em>name</em> is never written here - it is resolved
     * from configuration - but the DD name is part of the copybook's contract with its two programs.
     */
    public static final String DD_NAME = "TCATBALF";

    // =================================================================================================
    // COBOL item names, carried verbatim. The parity differ compares field by field BY NAME, so these
    // strings are part of the migration contract and are never tidied, expanded or disambiguated -
    // TRAN-CAT-KEY collides with a 6-byte group of the same name in CVTRA04Y and stays as it is.
    // =================================================================================================

    /** {@code 05 TRAN-CAT-KEY} - the composite key group, 17 bytes. */
    public static final String TRAN_CAT_KEY_NAME = "TRAN-CAT-KEY";

    /** {@code 10 TRANCAT-ACCT-ID PIC 9(11)}. */
    public static final String TRANCAT_ACCT_ID_NAME = "TRANCAT-ACCT-ID";

    /** {@code 10 TRANCAT-TYPE-CD PIC X(02)}. */
    public static final String TRANCAT_TYPE_CD_NAME = "TRANCAT-TYPE-CD";

    /** {@code 10 TRANCAT-CD PIC 9(04)}. */
    public static final String TRANCAT_CD_NAME = "TRANCAT-CD";

    /** {@code 05 TRAN-CAT-BAL PIC S9(09)V99}. */
    public static final String TRAN_CAT_BAL_NAME = "TRAN-CAT-BAL";

    // =================================================================================================
    // Offsets and lengths. Every one is an explicitly named constant transcribed from the copybook, so a
    // reviewer holding CVTRA01Y open beside this block can confirm each number by eye (practice B11).
    // COBOL positions are 1-based and these are 0-based, so each offset is one less than the copybook's
    // starting position.
    // =================================================================================================

    /** The declared record width: {@code RECLN = 50}. {@code 17 + 11 + 22}. */
    public static final int RECORD_LENGTH = 50;

    /** {@code TRAN-CAT-KEY} begins the record, at 1-based position 1. */
    public static final int TRAN_CAT_KEY_OFFSET = 0;

    /**
     * {@code TRAN-CAT-KEY} is <strong>17</strong> bytes: {@code 9(11) + X(02) + 9(04)}. Confirmed
     * independently by {@code RECORD KEY IS FD-TRAN-CAT-KEY} over the same three-part split at
     * {@code app/cbl/CBTRN02C.cbl:60,92-96} and by {@code app/cbl/CBACT04C.cbl:63-66}.
     *
     * <p>This is <em>not</em> 16. {@code app/cpy/CVTRA02Y.cpy}'s analogous {@code DIS-GROUP-KEY} is 16
     * bytes and that record also totals 50, so a width check cannot tell the two apart - see the
     * class documentation, and see {@link #verifyKeyGeometry(int, int)}, which exists to catch exactly
     * that substitution.
     */
    public static final int TRAN_CAT_KEY_LENGTH = 17;

    /** {@code TRANCAT-ACCT-ID}: 1-based 1-11, so 0-based 0. */
    public static final int TRANCAT_ACCT_ID_OFFSET = 0;

    /** {@code TRANCAT-ACCT-ID PIC 9(11)}: 11 digits, one byte each. */
    public static final int TRANCAT_ACCT_ID_LENGTH = 11;

    /** {@code TRANCAT-TYPE-CD}: 1-based 12-13, so 0-based 11. */
    public static final int TRANCAT_TYPE_CD_OFFSET = 11;

    /** {@code TRANCAT-TYPE-CD PIC X(02)}: 2 characters. */
    public static final int TRANCAT_TYPE_CD_LENGTH = 2;

    /** {@code TRANCAT-CD}: 1-based 14-17, so 0-based 13. */
    public static final int TRANCAT_CD_OFFSET = 13;

    /** {@code TRANCAT-CD PIC 9(04)}: 4 digits. */
    public static final int TRANCAT_CD_LENGTH = 4;

    /**
     * {@code TRAN-CAT-BAL}: 1-based 18-28, so 0-based <strong>17</strong> - immediately after the
     * 17-byte key. In {@code CVTRA02Y} the analogous amount sits at 0-based 16; that one-byte
     * difference is the trap this constant is named against.
     */
    public static final int TRAN_CAT_BAL_OFFSET = 17;

    /**
     * {@code TRAN-CAT-BAL PIC S9(09)V99} occupies <strong>11</strong> bytes: {@code p + s} with
     * {@code p} of 9 and {@code s} of 2. No byte is reserved for the sign, which is overpunched into
     * the trailing byte. Twelve bytes would make the record 51 and be rejected by {@link #LAYOUT}.
     */
    public static final int TRAN_CAT_BAL_LENGTH = 11;

    /** {@code p} in {@code PIC S9(09)V99}: 9 digit positions left of the implied decimal point. */
    public static final int TRAN_CAT_BAL_INTEGER_DIGITS = 9;

    /**
     * {@code s} in {@code PIC S9(09)V99}: 2 digit positions right of the implied decimal point. Equal
     * to {@link CobolDecimal#MONETARY_SCALE}, since every scaled field in this system is scale 2.
     */
    public static final int TRAN_CAT_BAL_SCALE = CobolDecimal.MONETARY_SCALE;

    /** The trailing {@code FILLER}: 1-based 29-50, so 0-based 28. */
    public static final int FILLER_OFFSET = 28;

    /**
     * {@code FILLER PIC X(22)}: 22 reserved bytes, and a first-class span rather than an inferred gap.
     * Dropping it would leave the layout 22 bytes short of 50 and shift every subsequent record in the
     * dataset - which is why {@link #LAYOUT} refuses to be constructed without it (gate G21).
     *
     * <p>Its <em>content</em> is not assumed: the measured fixture holds 22 ASCII zeros here. See the
     * class documentation on carry-through.
     */
    public static final int FILLER_LENGTH = 22;

    // =================================================================================================
    // Field descriptors. One per elementary item plus the FILLER, each pinned to the offset and length
    // constants above so a descriptor and its constants can never drift apart. FieldSpan is an
    // immutable record, so exposing these is safe (gate G53).
    // =================================================================================================

    /**
     * {@code 10 TRANCAT-ACCT-ID PIC 9(11)} - unsigned zoned {@code DISPLAY}, right justified and
     * zero-filled on the left. The account identifier, and the leading component of the composite key.
     */
    public static final FieldSpan TRANCAT_ACCT_ID_SPAN = FieldSpan.unsignedNumeric(
            TRANCAT_ACCT_ID_NAME, TRANCAT_ACCT_ID_OFFSET, TRANCAT_ACCT_ID_LENGTH);

    /**
     * {@code 10 TRANCAT-TYPE-CD PIC X(02)} - <strong>alphanumeric</strong>, left justified and
     * space-padded, despite every value in the fixture looking numeric ({@code 01}). The picture is
     * {@code X}, so the leading zero is significant data rather than numeric padding, and the field is
     * modelled as a {@link String}. {@code app/cpy/CVTRA02Y.cpy}'s {@code DIS-TRAN-TYPE-CD PIC X(02)}
     * is declared the same way and is the precedent this follows.
     */
    public static final FieldSpan TRANCAT_TYPE_CD_SPAN = FieldSpan.alphanumeric(
            TRANCAT_TYPE_CD_NAME, TRANCAT_TYPE_CD_OFFSET, TRANCAT_TYPE_CD_LENGTH);

    /**
     * {@code 10 TRANCAT-CD PIC 9(04)} - unsigned zoned {@code DISPLAY}. Scale-free, so it maps to an
     * {@code int} rather than a {@link BigDecimal} (rule R4).
     */
    public static final FieldSpan TRANCAT_CD_SPAN = FieldSpan.unsignedNumeric(
            TRANCAT_CD_NAME, TRANCAT_CD_OFFSET, TRANCAT_CD_LENGTH);

    /**
     * {@code 05 TRAN-CAT-BAL PIC S9(09)V99} - signed zoned {@code DISPLAY}, 11 bytes. The width is
     * computed by {@link FieldSpan#signedScaled(String, int, int, int)} from the digit counts, not
     * passed in, so a phantom sign byte cannot be reintroduced here.
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
     *
     * <p>{@link RecordLayout}'s constructor self-check runs here, at class initialisation, and proves
     * three things before this class can be used at all: the five spans are contiguous from offset 0
     * with no gap and no overlap; they sum to <strong>exactly</strong> {@link #RECORD_LENGTH}; and no
     * referable name repeats. That is the mechanical form of acceptance gates G19 and G21 - drop the
     * {@code FILLER} and the layout is 22 bytes short, reserve a sign byte for the balance and it is
     * one byte long, shift the balance to the {@code CVTRA02Y} offset of 16 and the spans overlap. Each
     * of those mistakes throws immediately and names the offending descriptor, rather than silently
     * corrupting every record in the dataset.
     */
    public static final RecordLayout LAYOUT = RecordLayout.of(RECORD_LENGTH,
            TRANCAT_ACCT_ID_SPAN,
            TRANCAT_TYPE_CD_SPAN,
            TRANCAT_CD_SPAN,
            TRAN_CAT_BAL_SPAN,
            FILLER_SPAN);

    /**
     * The 17-byte composite key as a layout of its own, over the <strong>same three descriptors</strong>
     * that {@link #LAYOUT} uses.
     *
     * <p>This is how the key group is modelled: as a sub-span over its three elementary items, never as
     * a fourth field overlapping them and never as an invented {@code REDEFINES} overlay - the copybook
     * declares neither, and {@code CVTRA01Y} contains no {@code REDEFINES} at all. Reusing the very
     * same {@link FieldSpan} constants is what makes it impossible for the key's offsets and the
     * record's to diverge, and declaring the key length as 17 gives {@link RecordLayout} a second,
     * independent arithmetic check: {@code 11 + 2 + 4} must total 17, or this class does not initialise.
     * That is the defence against substituting {@code CVTRA02Y}'s 16-byte key, which a whole-record
     * width check cannot detect because both records are 50 bytes wide.
     */
    public static final RecordLayout TRAN_CAT_KEY_LAYOUT = RecordLayout.of(TRAN_CAT_KEY_LENGTH,
            TRANCAT_ACCT_ID_SPAN,
            TRANCAT_TYPE_CD_SPAN,
            TRANCAT_CD_SPAN);

    static {
        // Runs once, at class initialisation, so a transcription error in the constants above can never
        // reach a caller. The two checks it performs are also individually callable from a test, with
        // both outcomes reachable, so the trap messages themselves are covered rather than asserted.
        verifyGeometry();
    }

    // =================================================================================================
    // Geometry self-checks. Both take their operands as parameters rather than reading the constants
    // directly, for two reasons: the failure path is then reachable from a test, so the trap message is
    // proven rather than merely written; and a caller that has transcribed the layout for itself - a
    // repository, or a parity case - can put its own numbers through the same check.
    // =================================================================================================

    /**
     * Proves this record's geometry, and is called once from the static initialiser.
     *
     * <p>Three facts are established, two of them by {@link RecordLayout}'s own constructor before this
     * method is even entered: the five spans sum to exactly 50, and the three key spans sum to exactly
     * 17. This method then confirms the two remaining relationships between the declared constants -
     * that the record length and the layout agree, and that the balance begins exactly where the key
     * ends.
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
     * <p>The layout already guarantees that its spans sum to the length it was declared with; this check
     * closes the remaining gap, which is that {@link #RECORD_LENGTH} - the number every caller,
     * repository and parity case works from - is that same number. It is the {@code RECLN = 50} of the
     * copybook's own header comment, and acceptance gate G19 in its most direct form.
     *
     * @param declaredRecordLength the width the copybook declares, normally {@link #RECORD_LENGTH}
     * @param layoutRecordLength   the width the layout accounts for, normally
     *                             {@code LAYOUT.recordLength()}
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
     * Confirms that {@code TRAN-CAT-BAL} begins exactly where the 17-byte {@code TRAN-CAT-KEY} ends -
     * the single most valuable assertion in this file.
     *
     * <p>Its failure message names the trap explicitly, so a future regression is diagnosed in one
     * reading rather than investigated. {@code app/cpy/CVTRA02Y.cpy} declares a record that <em>also</em>
     * totals 50 bytes but whose key is <strong>16</strong>, placing its signed amount at 0-based 16 and
     * giving it 6 bytes rather than 11. A whole-record width check passes for either layout, so nothing
     * else in the system can catch the substitution: it would surface only as wrong money, through the
     * interest formula at {@code app/cbl/CBACT04C.cbl:464-465}, in the one program that works both
     * layouts side by side.
     *
     * @param declaredKeyLength    the key width to check, normally {@link #TRAN_CAT_KEY_LENGTH}
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

    // =================================================================================================
    // The composite key as a value type.
    // =================================================================================================

    /**
     * {@code 05 TRAN-CAT-KEY} - the 17-byte composite key of {@code TCATBALF}, as an immutable value.
     *
     * <p>This is the type that {@code RECORD KEY IS FD-TRAN-CAT-KEY}
     * [{@code app/cbl/CBTRN02C.cbl:60}] names, and the type a repository passes to a keyed read. It is
     * built from its three components in copybook order and serialises through
     * {@link #TRAN_CAT_KEY_LAYOUT}, so its byte image is produced by the same descriptors that address
     * the key inside a whole record.
     *
     * <p><strong>Never interchangeable with {@code CVTRA04Y}'s key.</strong>
     * {@code app/cpy/CVTRA04Y.cpy} declares a group with the identical COBOL name
     * {@code TRAN-CAT-KEY} that is only 6 bytes - {@code TRAN-TYPE-CD X(02)} plus
     * {@code TRAN-CAT-CD 9(04)}, with no account identifier. The two are distinct Java types precisely
     * so that no repository, no service and no parity case can pass one where the other is expected.
     *
     * <p>The COBOL that builds this key is {@code app/cbl/CBTRN02C.cbl:469-471}:
     * <pre>
     *   MOVE XREF-ACCT-ID TO FD-TRANCAT-ACCT-ID
     *   MOVE DALYTRAN-TYPE-CD TO FD-TRANCAT-TYPE-CD
     *   MOVE DALYTRAN-CAT-CD TO FD-TRANCAT-CD
     * </pre>
     * and {@code :476-477} then displays the key image itself when the read misses:
     * {@code DISPLAY 'TCATBAL record not found for key : ' FD-TRAN-CAT-KEY '.. Creating.'}. That message
     * is why {@link #image(Charset)} exists and why it returns the raw 17-character image rather than a
     * formatted rendering.
     *
     * @param trancatAcctId  {@code TRANCAT-ACCT-ID PIC 9(11)}; never negative, since an unsigned
     *                       {@code PIC 9} picture has no sign position
     * @param trancatTypeCd  {@code TRANCAT-TYPE-CD PIC X(02)}; alphanumeric, so {@code "01"} is two
     *                       significant characters and never the number 1. Padded and truncated on the
     *                       right, as {@code PIC X} requires
     * @param trancatCd      {@code TRANCAT-CD PIC 9(04)}; never negative
     */
    public record TranCatKey(long trancatAcctId, String trancatTypeCd, int trancatCd) {

        /**
         * Validates only that the alphanumeric component is present. Its width is not policed here:
         * {@code PIC X} pads and truncates on the right, and that adjustment is applied when the key is
         * rendered, so that the direction of truncation is the codec's documented rule rather than a
         * rejection at construction.
         *
         * @throws NullPointerException if {@code trancatTypeCd} is {@code null}
         */
        public TranCatKey {
            Objects.requireNonNull(trancatTypeCd, "TRANCAT-TYPE-CD PIC X(02) is required; move SPACES "
                    + "explicitly to blank it, as COBOL would");
        }

        /**
         * Renders this key as its raw 17-character image: 11 zero-filled digits, then the two-character
         * type code space-padded on the right, then 4 zero-filled digits.
         *
         * <p>This is the image {@code app/cbl/CBTRN02C.cbl:477} displays. The numeric components go
         * through the {@code PIC 9} move rule - zero-filled and truncated on the <em>left</em> - and the
         * alphanumeric component through the {@code PIC X} rule - space-padded and truncated on the
         * <em>right</em> - so each direction is the deliberate one for its picture.
         *
         * @param charset the code page of the key's bytes, stated explicitly by the caller and never
         *                derived from the platform
         * @return exactly {@link #TRAN_CAT_KEY_LENGTH} characters
         * @throws NullPointerException     if {@code charset} is {@code null}
         * @throws IllegalArgumentException if {@code charset} is not single-byte for the digits and
         *                                  space, or if either numeric component is negative
         */
        public String image(Charset charset) {
            return keyArea(charset).readString(TRAN_CAT_KEY_OFFSET, TRAN_CAT_KEY_LENGTH);
        }

        /**
         * Renders this key as its raw 17 bytes, for a repository that addresses the KSDS by key image.
         *
         * @param charset the code page of the key's bytes, stated explicitly
         * @return exactly {@link #TRAN_CAT_KEY_LENGTH} bytes
         * @throws NullPointerException     if {@code charset} is {@code null}
         * @throws IllegalArgumentException if {@code charset} is not single-byte for the digits and
         *                                  space, or if either numeric component is negative
         */
        public byte[] toByteArray(Charset charset) {
            return keyArea(charset).toByteArray();
        }

        /**
         * Builds the key's byte area from the three descriptors {@link #TRAN_CAT_KEY_LAYOUT} declares -
         * the same descriptors that address these fields inside a whole 50-byte record, which is what
         * keeps the two views from ever drifting apart.
         */
        private FixedWidthRecord keyArea(Charset charset) {
            FixedWidthCodec codec = new FixedWidthCodec(charset);
            FixedWidthRecord area = codec.newRecord(TRAN_CAT_KEY_LAYOUT);
            codec.writePic9(area, TRANCAT_ACCT_ID_SPAN, trancatAcctId);
            codec.writePicX(area, TRANCAT_TYPE_CD_SPAN, trancatTypeCd);
            codec.writePic9(area, TRANCAT_CD_SPAN, trancatCd);
            return area;
        }

        /**
         * Decodes a stored 17-byte key image.
         *
         * @param keyBytes exactly {@link #TRAN_CAT_KEY_LENGTH} bytes
         * @param charset  the code page of those bytes, stated explicitly
         * @return the key the bytes denote, with its type code left <strong>untrimmed</strong>
         * @throws NullPointerException     if either argument is {@code null}
         * @throws IllegalArgumentException if {@code keyBytes} is not exactly 17 bytes long, or a
         *                                  numeric component does not hold digits
         */
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
         * @param charset  the code page the characters are encoded under, stated explicitly
         * @return the key the image denotes, with its type code left <strong>untrimmed</strong>
         * @throws NullPointerException     if either argument is {@code null}
         * @throws IllegalArgumentException if the image does not encode to exactly 17 bytes, or a
         *                                  numeric component does not hold digits
         */
        public static TranCatKey decode(String keyImage, Charset charset) {
            Objects.requireNonNull(keyImage, "A 17-character TRAN-CAT-KEY image is required");
            Objects.requireNonNull(charset, "A charset is required to decode a key image: fixed-width "
                    + "mainframe data is bytes in a specific code page, never a platform default");
            return decode(FixedWidthRecord.encodeText(keyImage, charset, "a TRAN-CAT-KEY image"),
                    charset);
        }
    }

    // =================================================================================================
    // Instance state. The 50-byte record area is the SINGLE SOURCE OF TRUTH, exactly as a COBOL record
    // area is: every typed accessor decodes from it and every mutator encodes into it. Nothing is
    // cached alongside it, so a field value and the record's bytes can never disagree - and the
    // reserved FILLER bytes survive a read, an initialize() and a write untouched, which is precisely
    // what the REWRITE path at app/cbl/CBTRN02C.cbl:527-528 requires.
    // =================================================================================================

    /** The record area: 50 mutable bytes, never handed out except as a copy. */
    private final FixedWidthRecord area;

    /**
     * The PICTURE-aware codec over the same code page as {@link #area}. Held per instance rather than
     * created per call so that one record can never be read under two code pages.
     */
    private final FixedWidthCodec codec;

    /**
     * Wraps an area and the codec that addresses it. Private because every entry point states its
     * charset explicitly, and because the two arguments must agree on that charset.
     */
    private TranCatBalRecord(FixedWidthRecord area, FixedWidthCodec codec) {
        this.area = area;
        this.codec = codec;
    }

    /**
     * Allocates a record and initialises it, for the create-from-nothing case.
     *
     * <p>The allocation fills every span from {@link #LAYOUT} - which is what puts spaces in the
     * {@code FILLER}, satisfying acceptance gate G21 for a record that has no stored predecessor - and
     * {@link #initialize()} then applies the COBOL {@code INITIALIZE} categories to the four named
     * items. The resulting image is 11 zeros, 2 spaces, 4 zeros, <code>0000000000&#123;</code> and 22
     * spaces.
     *
     * <p>Use this only where a record is genuinely being built from nothing. Where a record has been
     * read from the dataset, go through {@link #decode(byte[], Charset)} so its reserved bytes are
     * preserved.
     *
     * @param charset the code page of the record's bytes, stated explicitly by the caller and never
     *                derived from the platform
     * @return a new, initialised 50-byte record
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not single-byte for the digits, the sign
     *                                  overpunch characters and the space
     */
    public static TranCatBalRecord newInstance(Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        TranCatBalRecord record = new TranCatBalRecord(codec.newRecord(LAYOUT), codec);
        return record.initialize();
    }

    /**
     * Decodes a stored 50-byte record, taking a defensive copy of its bytes.
     *
     * <p>This is the {@code READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD} of
     * {@code app/cbl/CBTRN02C.cbl:474} and {@code app/cbl/CBACT04C.cbl:326}. The width must be exactly
     * 50: a short or over-long row means the layout and the data disagree, and tolerating it would let
     * every subsequent field offset drift. Unlike {@code cardxref.txt}, the {@code tcatbal} fixture
     * needs no widening - all 50 of its rows were measured at exactly 50 bytes.
     *
     * @param row     exactly {@link #RECORD_LENGTH} bytes
     * @param charset the code page of those bytes, stated explicitly
     * @return a record over a copy of {@code row}
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code row} is not exactly 50 bytes long, or {@code charset}
     *                                  is not single-byte for the required characters
     */
    public static TranCatBalRecord decode(byte[] row, Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return new TranCatBalRecord(codec.wrap(row, LAYOUT), codec);
    }

    /**
     * Decodes a stored 50-character record image, for a caller that has read the row as text - the
     * {@code app/data/ASCII} datasets and the parity case fixtures both arrive that way.
     *
     * @param row     exactly {@link #RECORD_LENGTH} characters
     * @param charset the code page the characters are encoded under, stated explicitly
     * @return a record over the encoded bytes
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if the image does not encode to exactly 50 bytes
     */
    public static TranCatBalRecord decode(String row, Charset charset) {
        Objects.requireNonNull(row, "A 50-character TRAN-CAT-BAL-RECORD image is required");
        Objects.requireNonNull(charset, "A charset is required to decode a record image: fixed-width "
                + "mainframe data is bytes in a specific code page, never a platform default");
        return decode(FixedWidthRecord.encodeText(row, charset, "a TRAN-CAT-BAL-RECORD image"),
                charset);
    }

    // =================================================================================================
    // Whole-record accessors.
    // =================================================================================================

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
     * <p>This is the {@code WRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD} of
     * {@code app/cbl/CBTRN02C.cbl:510} and the {@code REWRITE} of {@code :528}. Because the instance
     * holds the whole record area rather than a set of decoded fields, the reserved bytes that arrived
     * on the read leave on the write byte for byte - 22 ASCII zeros in the shipped dataset, not the 22
     * spaces a rebuilt-from-fields record would emit.
     *
     * @return a fresh array of exactly {@link #RECORD_LENGTH} bytes
     */
    public byte[] encode() {
        return area.toByteArray();
    }

    /**
     * This record's raw 50-character image, untrimmed and undecoded.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:193} is {@code DISPLAY TRAN-CAT-BAL-RECORD} - the whole group, not
     * a field of it - so the {@code SYSOUT} fingerprint of the interest calculator is exactly this
     * string. It is returned as stored, including the balance's sign overpunch character and the
     * {@code FILLER}, because that is what the program writes to the log.
     *
     * @return exactly {@link #RECORD_LENGTH} characters
     */
    public String rawImage() {
        return area.readString(0, RECORD_LENGTH);
    }

    /**
     * This record's four named fields as raw, untrimmed images, keyed by their verbatim COBOL names and
     * in copybook declaration order.
     *
     * <p>Raw images are what the parity differ compares - field by field, never as one whole string -
     * so a difference is reported against the field that carries it. {@code FILLER} is absent, because
     * {@code FILLER} is not a referable COBOL name and so cannot be a map key; it is separately
     * available through {@link #fillerImage()} and is in any case proven present by {@link #LAYOUT}'s
     * width self-check.
     *
     * @return an unmodifiable, insertion-ordered map of 4 entries:
     *         {@code TRANCAT-ACCT-ID}, {@code TRANCAT-TYPE-CD}, {@code TRANCAT-CD} and
     *         {@code TRAN-CAT-BAL}
     */
    public Map<String, String> fieldImages() {
        return Collections.unmodifiableMap(codec.deserialise(LAYOUT, area.toByteArray()));
    }

    // =================================================================================================
    // The composite key, inside this record.
    // =================================================================================================

    /**
     * This record's {@code TRAN-CAT-KEY} as a typed value.
     *
     * @return the 17-byte key's three components, with {@code TRANCAT-TYPE-CD} untrimmed
     * @throws IllegalArgumentException if either numeric component of the key does not hold digits,
     *                                  which means the record's bytes and this layout disagree
     */
    public TranCatKey tranCatKey() {
        return new TranCatKey(trancatAcctId(), trancatTypeCd(), trancatCd());
    }

    /**
     * This record's {@code TRAN-CAT-KEY} as its raw 17-character image, read straight from the record
     * area rather than rebuilt from decoded values.
     *
     * <p>This is the operand of {@code DISPLAY 'TCATBAL record not found for key : ' FD-TRAN-CAT-KEY
     * '.. Creating.'} at {@code app/cbl/CBTRN02C.cbl:476-477}. Reading it from the area means the image
     * is byte-faithful even when a component would not decode as a number, which is exactly the
     * situation in which such a diagnostic is most needed.
     *
     * @return exactly {@link #TRAN_CAT_KEY_LENGTH} characters
     */
    public String tranCatKeyImage() {
        return area.readString(TRAN_CAT_KEY_OFFSET, TRAN_CAT_KEY_LENGTH);
    }

    /**
     * This record's {@code TRAN-CAT-KEY} as its raw 17 bytes, for a repository addressing the KSDS by
     * key image. Serialising through this accessor - rather than re-deriving the offsets - is what keeps
     * this class the single authority for the 17-byte layout.
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
     * @throws NullPointerException     if {@code key} is {@code null}
     * @throws IllegalArgumentException if a numeric component of {@code key} is negative
     */
    public TranCatBalRecord tranCatKey(TranCatKey key) {
        Objects.requireNonNull(key, "A TRAN-CAT-KEY is required to write the key group");
        trancatAcctId(key.trancatAcctId());
        trancatTypeCd(key.trancatTypeCd());
        trancatCd(key.trancatCd());
        return this;
    }

    // =================================================================================================
    // TRANCAT-ACCT-ID - 10, PIC 9(11), 1-based 1-11, 0-based offset 0, length 11.
    // =================================================================================================

    /**
     * {@code TRANCAT-ACCT-ID PIC 9(11)} as a number.
     *
     * <p>Eleven digits exceed the {@code int} range, so this is a {@code long}. The picture is scale-free
     * and unsigned, so it is an integral type and never a {@link BigDecimal} (rule R4).
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
     * <p>Required, not merely convenient. {@code app/cbl/CBACT04C.cbl:194} compares this field against
     * {@code WS-LAST-ACCT-NUM PIC X(11) VALUE SPACES} [{@code :167}] and {@code :201} moves it into that
     * same alphanumeric item. A numeric-display item compared with, or moved to, an alphanumeric item is
     * handled by COBOL as the <em>characters</em> of the field, so the account-break driver of the
     * interest calculator works on this image and not on the decoded number. On the first iteration the
     * comparison is against 11 spaces, which no digit image can equal - that is how the very first
     * record triggers a break.
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
     * <p>The {@code PIC 9} move rule applies: the value is zero-filled on the left to 11 digits, and a
     * value of more than 11 digits keeps its <em>low-order</em> 11, because a numeric receiver is aligned
     * on its implied decimal point. No {@code ON SIZE ERROR} phrase exists anywhere in this codebase, so
     * that loss is silent here exactly as it is in COBOL.
     *
     * @param value the account identifier; must not be negative, since {@code PIC 9} has no sign position
     * @return this record, for chaining
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public TranCatBalRecord trancatAcctId(long value) {
        codec.writePic9(area, TRANCAT_ACCT_ID_SPAN, value);
        return this;
    }

    // =================================================================================================
    // TRANCAT-TYPE-CD - 10, PIC X(02), 1-based 12-13, 0-based offset 11, length 2.
    // =================================================================================================

    /**
     * {@code TRANCAT-TYPE-CD PIC X(02)}, <strong>untrimmed</strong>.
     *
     * <p>The picture is {@code X}, so this is character data even though every value in the shipped
     * fixture reads {@code 01}: the leading zero is significant and the field is never decoded as the
     * number 1. It is returned with its padding intact, because a {@code PIC X} field is space-padded to
     * its full width and the parity differ compares those bytes. After {@link #initialize()} the value
     * is two spaces, which is what COBOL's {@code INITIALIZE} puts in an alphanumeric item.
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
     * space-padded on the <em>right</em> if short and truncated on the <em>right</em> if long, so
     * {@code "0"} becomes {@code "0 "} and {@code "012"} becomes {@code "01"}.
     *
     * @param value the two-character type code; move an empty string or spaces to blank it
     * @return this record, for chaining
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public TranCatBalRecord trancatTypeCd(String value) {
        codec.writePicX(area, TRANCAT_TYPE_CD_SPAN, value);
        return this;
    }

    // =================================================================================================
    // TRANCAT-CD - 10, PIC 9(04), 1-based 14-17, 0-based offset 13, length 4.
    // =================================================================================================

    /**
     * {@code TRANCAT-CD PIC 9(04)} as a number. Four digits fit an {@code int}, and the picture is
     * scale-free, so this is an {@code int} and not a {@link BigDecimal} (rule R4).
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
     * [{@code app/cbl/CBTRN02C.cbl:507}]. Zero-filled on the left to 4 digits, truncated on the left if
     * wider.
     *
     * @param value the category code; must not be negative
     * @return this record, for chaining
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public TranCatBalRecord trancatCd(int value) {
        codec.writePic9(area, TRANCAT_CD_SPAN, value);
        return this;
    }

    // =================================================================================================
    // TRAN-CAT-BAL - 05, PIC S9(09)V99, 1-based 18-28, 0-based offset 17, length 11.
    // =================================================================================================

    /**
     * {@code TRAN-CAT-BAL PIC S9(09)V99} as a {@link BigDecimal} of scale exactly
     * {@link #TRAN_CAT_BAL_SCALE}.
     *
     * <p>The stored image is 11 zoned {@code DISPLAY} characters whose last one carries both the
     * low-order digit and the sign: <code>&#123;ABCDEFGHI</code> for a positive digit 0 to 9,
     * <code>&#125;JKLMNOPQR</code> for a negative one, and a plain digit for the unsigned zone-{@code F}
     * form, which is read as positive. Every row of the shipped fixture ends <code>&#123;</code>, so
     * every balance in it is {@code +0.00}.
     *
     * <p>This value is the left operand of the only division in the entire system:
     * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
     * [{@code app/cbl/CBACT04C.cbl:464-465}], which carries no {@code ROUNDED} phrase and therefore
     * truncates. The arithmetic itself belongs to the interest calculator and to
     * {@link CobolDecimal#monthlyInterest(BigDecimal, BigDecimal)}, not here; this accessor's
     * responsibility is to hand over a value of the right scale and sign.
     *
     * @return the balance, at scale 2
     * @throws IllegalArgumentException if the span does not hold a valid signed zoned image
     */
    public BigDecimal tranCatBal() {
        return codec.readSignedScaled(area, TRAN_CAT_BAL_SPAN, TRAN_CAT_BAL_SCALE);
    }

    /**
     * {@code TRAN-CAT-BAL} as its raw 11-character image, sign overpunch character included and
     * undecoded - the form the parity differ compares and the form
     * {@code app/cbl/CBACT04C.cbl:193} displays as part of the whole record.
     *
     * @return exactly {@link #TRAN_CAT_BAL_LENGTH} characters
     */
    public String tranCatBalImage() {
        return area.readSpan(TRAN_CAT_BAL_SPAN);
    }

    /**
     * Whether {@code TRAN-CAT-BAL} is zero, decided by sign rather than by equality.
     *
     * <p>{@link BigDecimal#equals(Object)} compares scale as well as value, so it judges {@code 0.00}
     * and {@code 0} unequal; {@link BigDecimal#signum()} and
     * {@link BigDecimal#compareTo(BigDecimal)} do not. Every decimal comparison in this class and in
     * this module goes through one of those two, and this predicate exists so that a caller never has to
     * reach for {@code equals} to ask the question.
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
     * <p>The store goes through {@link CobolDecimal#storeAtPicture(BigDecimal, int, int)}, which is the
     * only place in this module where a scale and a rounding mode are named, and it applies both of
     * COBOL's silent truncations in the order COBOL applies them:
     * <ol>
     *   <li>excess fractional digits are dropped toward zero to
     *       {@link #TRAN_CAT_BAL_SCALE} - {@link CobolDecimal#COBOL_ROUNDING} is
     *       {@code RoundingMode.DOWN}, because {@code ROUNDED} appears zero times in all 28 programs, so
     *       {@code 1.239} stores as {@code 1.23} and {@code -1.239} as {@code -1.23};</li>
     *   <li>integer digits beyond {@link #TRAN_CAT_BAL_INTEGER_DIGITS} are discarded, the field keeping
     *       its low-order 9 and the value's sign, because no {@code ON SIZE ERROR} phrase exists anywhere
     *       in this codebase for the condition to raise.</li>
     * </ol>
     * Neither truncation throws, which is faithful: COBOL reports neither unless asked.
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
     * Adds an amount to {@code TRAN-CAT-BAL}, reproducing
     * {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} - a statement that appears twice, at both ends of the
     * poster's branch:
     * <ul>
     *   <li>{@code app/cbl/CBTRN02C.cbl:508}, the <strong>create</strong> path. It follows
     *       {@code INITIALIZE} at {@code :504}, so the augend is {@code 0.00} and the result is the
     *       amount itself;</li>
     *   <li>{@code app/cbl/CBTRN02C.cbl:527}, the <strong>update</strong> path. It follows the
     *       {@code READ} at {@code :474}, so the augend is the balance just read and the record is then
     *       rewritten at {@code :528}.</li>
     * </ul>
     * Both operands are {@code S9(09)V99}, so an addition of two in-range values loses nothing. The store
     * nonetheless goes through {@link CobolDecimal} at scale 2 with {@code RoundingMode.DOWN} and through
     * the 9-integer-digit bound, so a sum that does overflow the receiver wraps silently rather than
     * throwing - which is what COBOL does in the absence of {@code ON SIZE ERROR}.
     *
     * @param addend the amount to add, typically {@code DALYTRAN-AMT}
     * @return this record, for chaining
     * @throws NullPointerException     if {@code addend} is {@code null}
     * @throws IllegalArgumentException if the stored balance is not a valid signed zoned image
     */
    public TranCatBalRecord addToTranCatBal(BigDecimal addend) {
        Objects.requireNonNull(addend, "An addend is required for ADD ... TO TRAN-CAT-BAL");
        return tranCatBal(CobolDecimal.add(tranCatBal(), addend, TRAN_CAT_BAL_SCALE));
    }

    // =================================================================================================
    // FILLER - 05, PIC X(22), 1-based 29-50, 0-based offset 28, length 22. Read-only: FILLER is not a
    // referable COBOL name, so nothing may write it by name. It is exposed only so that its
    // carry-through can be asserted.
    // =================================================================================================

    /**
     * The trailing {@code FILLER}'s raw 22-character content, exactly as stored.
     *
     * <p>Exposed for verification, never for modification: {@code FILLER} is unnamed in COBOL and no
     * statement can reference it, so this class offers no way to set it. Its content is a property of the
     * data rather than of the copybook - the measured {@code tcatbal} fixture holds 22 ASCII zeros here
     * while {@code dailytran} holds spaces in its own {@code FILLER} - which is exactly why the bytes are
     * carried through a read-modify-write untouched instead of being regenerated.
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

    // =================================================================================================
    // INITIALIZE.
    // =================================================================================================

    /**
     * Reproduces {@code INITIALIZE TRAN-CAT-BAL-RECORD} [{@code app/cbl/CBTRN02C.cbl:504}] exactly,
     * <strong>including its treatment of {@code FILLER}</strong>.
     *
     * <p>COBOL's {@code INITIALIZE} without a {@code REPLACING} phrase sets each elementary item to the
     * figurative constant for its category - {@code ZERO} for a numeric item, {@code SPACE} for an
     * alphanumeric one - and <strong>skips {@code FILLER} entirely</strong>. So this method:
     * <ul>
     *   <li>writes 11 zeros to {@code TRANCAT-ACCT-ID PIC 9(11)};</li>
     *   <li>writes 2 spaces to {@code TRANCAT-TYPE-CD PIC X(02)};</li>
     *   <li>writes 4 zeros to {@code TRANCAT-CD PIC 9(04)};</li>
     *   <li>writes {@code +0.00} to {@code TRAN-CAT-BAL PIC S9(09)V99} through the signed encoder, giving
     *       the zoned image <code>0000000000&#123;</code> - the positive-zero overpunch that every row of
     *       the shipped fixture carries;</li>
     *   <li>and <strong>leaves the 22 bytes of {@code FILLER} exactly as they were</strong>.</li>
     * </ul>
     *
     * <p>That last point is the behaviour worth being careful about, and it is preserved rather than
     * tidied. In {@code 2700-A-CREATE-TCATBAL-REC} the {@code INITIALIZE} at {@code :504} runs
     * immediately after the failed {@code READ} at {@code :474}, so the record area still holds whatever
     * that read left in it, and the {@code WRITE} at {@code :510} sends those reserved bytes to the
     * dataset. Blanking them here would be a different program and a 22-byte parity difference per
     * created record.
     *
     * @return this record, so the create path can chain the {@code MOVE} statements that follow
     */
    public TranCatBalRecord initialize() {
        codec.writePic9(area, TRANCAT_ACCT_ID_SPAN, 0L);
        codec.writePicX(area, TRANCAT_TYPE_CD_SPAN, "");
        codec.writePic9(area, TRANCAT_CD_SPAN, 0L);
        codec.writeSignedScaled(area, TRAN_CAT_BAL_SPAN, CobolDecimal.zero(TRAN_CAT_BAL_SCALE),
                TRAN_CAT_BAL_SCALE);
        // FILLER is deliberately NOT touched. See this method's documentation.
        return this;
    }

    // =================================================================================================
    // Value semantics. Two records are equal when their 50 bytes and their code page are equal, which is
    // the comparison the dataset itself makes - and it takes in the FILLER, so a difference in the
    // reserved bytes is never mistaken for equality.
    // =================================================================================================

    /**
     * Whether another object is a {@code TranCatBalRecord} holding the same 50 bytes under the same code
     * page.
     *
     * <p>The comparison is over the raw bytes rather than over decoded field values, deliberately: two
     * records whose reserved {@code FILLER} differs are not interchangeable, because writing one where
     * the other belongs is a real 22-byte difference in the dataset. Comparing bytes also means this
     * method never throws on a record whose numeric span holds something other than digits.
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
     * A deterministic, single-line rendering built from the <em>raw</em> field images rather than from
     * decoded values, so that it is safe to call on a record whose bytes do not decode - which is
     * precisely when a diagnostic is wanted. It depends on no locale, no time zone and no default
     * charset.
     *
     * @return for example
     *         <code>TRAN-CAT-BAL-RECORD[TRANCAT-ACCT-ID=00000000001, TRANCAT-TYPE-CD=01,
     *         TRANCAT-CD=0001, TRAN-CAT-BAL=0000000000&#123;,
     *         FILLER=0000000000000000000000]</code>
     */
    @Override
    public String toString() {
        return RECORD_NAME
                + "[" + TRANCAT_ACCT_ID_NAME + "=" + area.readSpan(TRANCAT_ACCT_ID_SPAN)
                + ", " + TRANCAT_TYPE_CD_NAME + "=" + area.readSpan(TRANCAT_TYPE_CD_SPAN)
                + ", " + TRANCAT_CD_NAME + "=" + area.readSpan(TRANCAT_CD_SPAN)
                + ", " + TRAN_CAT_BAL_NAME + "=" + area.readSpan(TRAN_CAT_BAL_SPAN)
                + ", FILLER=" + area.readSpan(FILLER_SPAN)
                + "]";
    }
}

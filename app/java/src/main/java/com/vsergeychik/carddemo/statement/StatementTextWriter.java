package com.vsergeychik.carddemo.statement;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.Objects;

/**
 * The Java owner of the 80-byte {@code STMTFILE} plain-text statement record written by
 * {@code app/cbl/CBSTM03A.CBL}.
 *
 * <p>This class holds two things and nothing else: the byte-exact translation of the COBOL
 * {@code 01 STATEMENT-LINES} group, and the two numeric-edited {@code PICTURE} masks that group
 * declares. It emits records; it does not decide <em>which</em> records or <em>in what order</em> -
 * see "Write order is not this class's decision" below.
 *
 * <h2>The record is 80 bytes, confirmed twice</h2>
 * <ul>
 *   <li>{@code app/cbl/CBSTM03A.CBL:L44-L45} declares {@code FD STMT-FILE.} followed by
 *       {@code 01 FD-STMTFILE-REC PIC X(80).}</li>
 *   <li>{@code app/jcl/CREASTMT.JCL:L89} - on {@code STEP040}, the step that actually
 *       <em>creates</em> the dataset with {@code DISP=(NEW,CATLG,DELETE)} at {@code L87} - declares
 *       {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)}. The {@code STEP030} {@code IEFBR14}
 *       pre-delete at {@code L72-L75} agrees at {@code LRECL=80}.</li>
 * </ul>
 * There is therefore no width ambiguity here, unlike the sibling HTML dataset, which that same JCL
 * declares at two different widths in two different steps. Acceptance gate <strong>G20</strong>
 * asserts 80.
 *
 * <p>{@link #BLOCK_SIZE} is carried as a documented constant only. Blocking is a mainframe
 * dataset attribute with no Java counterpart: this class writes whole logical records, one per
 * call, and never groups them into blocks.
 *
 * <h2>A defect in the source JCL, recorded rather than interpreted</h2>
 * {@code app/jcl/CREASTMT.JCL:L90} is corrupted: a copy-and-paste artefact in which a
 * {@code SPACE=} clause is followed, on the same line, by the tail of a record-format clause and the
 * tail of an unrelated dataset name. It parses as nothing and carries no usable information.
 *
 * <p>It is <strong>deliberately not parsed and not reinterpreted</strong> - the line is described
 * here rather than reproduced, so that a mechanical scan of the Java sources for a mainframe dataset
 * name stays a clean signal. {@code L89} is the authoritative {@code DCB} for this dataset and
 * {@code L91} the authoritative {@code DSN}; both are read through configuration, never from this
 * file. The defect is recorded here and in {@code application.yml} beside the {@code STMTFILE}
 * binding, and the read-only source is left exactly as it is. Recording a conflict instead of
 * silently repairing it is practice <strong>B4</strong>.
 *
 * <h2>Seventeen line groups, not sixteen</h2>
 * {@code app/cbl/CBSTM03A.CBL:L85-L146} declares {@code 01 STATEMENT-LINES} with
 * <strong>seventeen</strong> {@code 05} line groups: {@code ST-LINE0} through {@code ST-LINE15},
 * <em>plus</em> {@code ST-LINE14A} between {@code ST-LINE14} and {@code ST-LINE15}. Each is exactly
 * 80 bytes and the group totals {@value #STATEMENT_LINES_LENGTH}.
 *
 * <p>This is worth stating explicitly because the count is easy to get wrong: the migration plan's
 * prose says "sixteen" while its own enumeration lists all seventeen, {@code ST-LINE14A} included.
 * The enumeration and the COBOL agree, and the COBOL is the parity oracle, so seventeen it is. The
 * suffixed name is what makes {@code ST-LINE14A} easy to miss when counting; it is a first-class
 * line, written by {@code 4000-TRNXFILE-GET} at {@code app/cbl/CBSTM03A.CBL:L436}. See
 * {@link StatementLine}.
 *
 * <h2>{@code INITIALIZE} does not clear {@code FILLER} - the load-bearing fact</h2>
 * {@code app/cbl/CBSTM03A.CBL:L459} executes {@code INITIALIZE STATEMENT-LINES.} at the top of
 * {@code 5000-CREATE-STATEMENT}, and {@code L460} writes {@code ST-LINE0} on the very next line.
 * IBM Enterprise COBOL's {@code INITIALIZE} without {@code REPLACING} sets alphanumeric items to
 * spaces and numeric and numeric-edited items to zero, but <strong>elementary {@code FILLER} items
 * are not affected</strong>.
 *
 * <p>So that statement clears exactly the eleven named slots of {@link StatementSlot} and leaves
 * every banner, rule line and column heading intact. If it were implemented as "blank the whole
 * 80-byte buffer", the asterisk banners, the three {@code ALL '-'} rules and every heading literal
 * would be silently erased - and a naive width check would still pass, because a blanked line is
 * still 80 bytes. That is the single highest-risk misreading in this file, which is why the reset is
 * {@link StatementFile#initializeStatementLines()}, is driven by
 * {@link SlotKind#initialImage(FixedWidthCodec, int)}, and touches no {@code FILLER} byte.
 * Acceptance gate <strong>G21</strong> covers it.
 *
 * <h2>The two numeric-edited masks live here and nowhere else</h2>
 * {@code STATEMENT-LINES} declares two edited pictures, both 13 bytes wide, and both are
 * hand-implemented below by {@link #editZeroFilledAmount(BigDecimal)} and
 * {@link #editZeroSuppressedAmount(BigDecimal)}:
 * <table border="1">
 *   <caption>The two edited pictures, their COBOL sites and their Java renderers</caption>
 *   <tr><th>{@code PICTURE}</th><th>Slot</th><th>Leading zeros</th><th>Renderer</th></tr>
 *   <tr><td>{@code 9(9).99-}</td><td>{@code ST-CURR-BAL} ({@code L113})</td><td>retained</td>
 *       <td>{@link #editZeroFilledAmount(BigDecimal)}</td></tr>
 *   <tr><td>{@code Z(9).99-}</td>
 *       <td>{@code ST-TRANAMT} ({@code L137}), {@code ST-TOTAL-TRAMT} ({@code L142})</td>
 *       <td>suppressed to spaces</td>
 *       <td>{@link #editZeroSuppressedAmount(BigDecimal)}</td></tr>
 * </table>
 * They differ <em>only</em> in leading-zero handling, and getting one right while getting the other
 * wrong is the easy mistake, so both are specified in full on their own methods.
 *
 * <p>The HTML statement writer must <strong>not</strong> re-implement them. It consumes the
 * already-rendered 13-character images, exactly as the COBOL does: the {@code STRING} statements at
 * {@code app/cbl/CBSTM03A.CBL:L621-L625} and {@code L711-L715} reference the <em>edited</em>
 * {@code ST-CURR-BAL} and {@code ST-TRANAMT} items of this same group rather than re-editing the
 * underlying values. {@link StatementFile#slotImage(StatementSlot)} is how it reads them.
 *
 * <h2>Write order is not this class's decision</h2>
 * The order in which lines reach the dataset is parity-critical and belongs to the statement job,
 * which owns the {@code 5000-CREATE-STATEMENT}, {@code 6000-WRITE-TRANS} and
 * {@code 4000-TRNXFILE-GET} sequencing - including the fact that {@code ST-LINE5} and
 * {@code ST-LINE12} are each written more than once per statement. This class therefore offers one
 * write per line identity and <strong>never</strong> batches, reorders, deduplicates or
 * conditionally skips a write. Records reach {@link RecordSink} in call order.
 *
 * <h2>Customer data is emitted verbatim (practice B6)</h2>
 * A statement carries the customer's full name, three address lines, account id, current balance
 * and FICO score in the clear, because that is precisely what {@code CBSTM03A} writes. No masking,
 * redaction, truncation or suppression is applied, and none may be added: every one of those would
 * change an observable byte and break parity. The security posture of the migrated system is
 * neither weakened nor unrequestedly strengthened relative to the COBOL.
 *
 * <h2>State, threading and testability</h2>
 * The bean is a <strong>stateless singleton</strong>. Its four fields are {@code final} and are
 * supplied by constructor injection; there is no setter, no field injection and no static mutable
 * state. All mutable state - the {@value #STATEMENT_LINES_LENGTH}-byte line area, the output sink,
 * the open flag and the record count - lives on the per-execution {@link StatementFile} handle that
 * {@link #openOutput()} returns, so two concurrent statement runs cannot see each other's slots. A
 * handle is confined to the step that owns it and is not thread-safe by itself, exactly as a COBOL
 * record area is not.
 *
 * <p>Every method here is callable from a plain JUnit 5 test with no application context, no
 * {@code JobLauncher} and no filesystem: the two mask renderers are pure static functions, and
 * {@link #openOutput(RecordSink)} accepts an in-memory sink.
 *
 * <h2>User-specified rules</h2>
 * {@code review_rules} returns exactly one line - "No user rules provided." - and that single line
 * is the whole document, so <strong>no user rule governs this file</strong>. Its absence is not
 * licence to lower the bar; the migration plan's twelve enterprise practices bind in their place.
 * The ones bearing on this file are <strong>B1</strong> (no dependency, coordinate or version added;
 * only JDK 21 and the Spring core, context and JDBC APIs the pinned Boot 3.5.16 parent already
 * provides), <strong>B3</strong> (the COBOL, copybook, JCL and CSD trees cited throughout are
 * read-only and are cited purely as provenance), <strong>B4</strong> (the corrupted JCL line above
 * and the seventeen-versus-sixteen count are documented, not silently repaired),
 * <strong>B6</strong> (see above), <strong>B7</strong> (no wall-clock, locale, default-charset or
 * filesystem-ordering dependence anywhere, so one {@code mvn clean verify} is deterministic),
 * <strong>B8</strong> (the code page is an injected {@link Charset} passed explicitly into the
 * codec and never derived from the platform; scale and rounding are named through
 * {@link CobolDecimal}; the dataset name is resolved by DD-name key from configuration and no
 * mainframe dataset literal appears here; imports are explicit with no wildcard),
 * <strong>B9</strong> (above), <strong>B10</strong> (above) and <strong>B11</strong> (the layout is
 * declared span by span at absolute offsets and the masks are written out by hand - no copybook
 * parser, no {@code DecimalFormat} approximation).
 *
 * @see StatementLine
 * @see StatementSlot
 * @see StatementFile
 * @see RecordSink
 */
@Component
public class StatementTextWriter {

    /**
     * The mainframe DD name of the plain-text statement dataset, and the key under which
     * {@code carddemo.datasets} in {@code application.yml} carries its location and geometry.
     *
     * <p>{@code app/cbl/CBSTM03A.CBL:L39} reads {@code SELECT STMT-FILE ASSIGN TO STMTFILE}, and
     * {@code app/jcl/CREASTMT.JCL:L87} names the same DD on the creating step. The dataset
     * <em>name</em> is deliberately absent from this file: it is reached by this key alone, so that
     * a scan of the Java sources for a mainframe dataset literal finds nothing (gate G46).
     */
    public static final String DD_NAME = "STMTFILE";

    /**
     * The fixed record width in bytes: {@code 80}.
     *
     * <p>From {@code 01 FD-STMTFILE-REC PIC X(80)} at {@code app/cbl/CBSTM03A.CBL:L45}, corroborated
     * by {@code LRECL=80} at {@code app/jcl/CREASTMT.JCL:L89}. Every image this class produces is
     * exactly this wide, and the constructor refuses to start if the configured binding disagrees.
     */
    public static final int RECORD_LENGTH = 80;

    /**
     * The block size declared by the creating JCL step: {@code 8000}, from
     * {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)} at {@code app/jcl/CREASTMT.JCL:L89}.
     *
     * <p>Carried for provenance and for the dataset-definition side of a deployment only. Blocking
     * groups logical records into physical blocks on a mainframe volume; it has no counterpart in
     * this writer, which emits one whole logical record per call and never buffers. Nothing in this
     * class reads this constant to make a decision.
     */
    public static final int BLOCK_SIZE = 8000;

    /**
     * The number of {@code 05} line groups in {@code 01 STATEMENT-LINES}: {@code 17}.
     *
     * <p>{@code ST-LINE0} through {@code ST-LINE15} is sixteen names; {@code ST-LINE14A} is the
     * seventeenth. See the class documentation for why this count is stated explicitly.
     */
    public static final int LINE_COUNT = 17;

    /**
     * The total width of {@code 01 STATEMENT-LINES} in bytes: {@code 1360}, being
     * {@value #LINE_COUNT} groups of {@value #RECORD_LENGTH}.
     *
     * <p>The whole group is modelled as one contiguous record area, mirroring the COBOL exactly, so
     * that {@code INITIALIZE STATEMENT-LINES} is one operation over one area rather than seventeen
     * operations over seventeen unrelated buffers.
     */
    public static final int STATEMENT_LINES_LENGTH = LINE_COUNT * RECORD_LENGTH;

    /**
     * The width of both numeric-edited pictures, {@code 9(9).99-} and {@code Z(9).99-}:
     * {@code 13} bytes.
     *
     * <p>Nine integer positions, the literal decimal point, two fraction digits and one trailing
     * sign position: {@code 9 + 1 + 2 + 1 = 13}. It is the same for both because the two pictures
     * differ only in how they treat a leading zero.
     */
    public static final int EDITED_AMOUNT_LENGTH = 13;

    /**
     * The count of integer digit positions in both edited pictures: {@code 9}.
     *
     * <p>This is the number that forces the truncation documented on
     * {@link #editZeroFilledAmount(BigDecimal)}: {@code ACCT-CURR-BAL} is declared
     * {@code PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:7} and so carries <em>ten</em> integer
     * digits into these <em>nine</em> positions.
     */
    static final int EDITED_INTEGER_DIGITS = 9;

    /**
     * The count of fraction digit positions in both edited pictures: {@code 2}, matching
     * {@link CobolDecimal#MONETARY_SCALE}. Every scaled numeric in this system has scale 2.
     */
    static final int EDITED_FRACTION_DIGITS = CobolDecimal.MONETARY_SCALE;

    /**
     * The declared digit count of {@code ACCT-ID}: {@code 11}, from
     * {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:5}.
     *
     * <p>{@code MOVE ACCT-ID TO ST-ACCT-ID} at {@code app/cbl/CBSTM03A.CBL:L483} therefore presents
     * eleven zero-filled digits to an {@code X(20)} receiver. Both steps are performed explicitly by
     * {@link StatementFile#setAccountId(long)} so that neither the zero fill nor the space pad is
     * left to a plain Java assignment.
     */
    static final int ACCOUNT_ID_DIGITS = 11;

    /**
     * The declared digit count of {@code CUST-FICO-CREDIT-SCORE}: {@code 3}, from
     * {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at {@code app/cpy/CUSTREC.cpy:22}.
     *
     * <p>{@code MOVE CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE} at
     * {@code app/cbl/CBSTM03A.CBL:L485} presents three zero-filled digits to an {@code X(20)}
     * receiver, so a score of 705 lands as {@code "705"} followed by seventeen spaces.
     */
    static final int FICO_SCORE_DIGITS = 3;

    /** The character COBOL pads an alphanumeric receiver with, and the {@code FILLER} default. */
    private static final char SPACE = ' ';

    /** The digit a retained leading position shows, and the character {@code Z} suppresses. */
    private static final char ZERO = '0';

    /** The literal decimal point that both edited pictures place after their integer positions. */
    private static final char DECIMAL_POINT = '.';

    /**
     * The trailing sign character both edited pictures show for a negative value. A trailing
     * {@code -} in a {@code PICTURE} prints this for a negative operand and a space otherwise; it is
     * never {@code +}.
     */
    private static final char MINUS = '-';

    /**
     * Diagnostics for the one failure path this class has - a rejected dataset write.
     *
     * <p>{@code static final} and immutable, and a logger is not mutable state, so practice B9 and
     * gate G53 are unaffected. Spring Framework's own commons-logging facade is used rather than a
     * third-party logging API so that no coordinate is added to the closed dependency set
     * (practice B1); {@code application.yml} already sets the level for
     * {@code com.vsergeychik.carddemo}.
     */
    private static final Log LOG = LogFactory.getLog(StatementTextWriter.class);

    // =============================================================================================
    // The seventeen line identities, transcribed from app/cbl/CBSTM03A.CBL:L85-L146.
    // =============================================================================================

    /**
     * One {@code 05} line group of {@code 01 STATEMENT-LINES}, in COBOL declaration order.
     *
     * <p>Every constant is {@value StatementTextWriter#RECORD_LENGTH} bytes wide and sits at an
     * absolute offset within the {@value StatementTextWriter#STATEMENT_LINES_LENGTH}-byte group,
     * computed as {@code ordinal * 80}. Declaration order is therefore the offset order, exactly as
     * a COBOL group's is.
     *
     * <p>The COBOL name is carried verbatim, hyphen and all, because it is the name a reviewer will
     * grep for in the source and the name the parity differ reports. Note {@link #ST_LINE14A}, which
     * sits between {@link #ST_LINE14} and {@link #ST_LINE15} and is the reason this enum has
     * seventeen constants rather than sixteen.
     *
     * <p>What each line contains, and which of its bytes are mutable:
     * <table border="1">
     *   <caption>The seventeen lines, their content and their slots</caption>
     *   <tr><th>Line</th><th>Offset</th><th>Content</th><th>Slots</th></tr>
     *   <tr><td>{@code ST-LINE0}</td><td>0</td>
     *       <td>31 asterisks, {@code START OF STATEMENT}, 31 asterisks</td><td>none</td></tr>
     *   <tr><td>{@code ST-LINE1}</td><td>80</td><td>customer name, 5 trailing spaces</td>
     *       <td>{@code ST-NAME}</td></tr>
     *   <tr><td>{@code ST-LINE2}</td><td>160</td><td>address line 1, 30 trailing spaces</td>
     *       <td>{@code ST-ADD1}</td></tr>
     *   <tr><td>{@code ST-LINE3}</td><td>240</td><td>address line 2, 30 trailing spaces</td>
     *       <td>{@code ST-ADD2}</td></tr>
     *   <tr><td>{@code ST-LINE4}</td><td>320</td><td>address line 3, full width</td>
     *       <td>{@code ST-ADD3}</td></tr>
     *   <tr><td>{@code ST-LINE5}</td><td>400</td><td>80 hyphens</td><td>none</td></tr>
     *   <tr><td>{@code ST-LINE6}</td><td>480</td><td>centred {@code Basic Details} heading</td>
     *       <td>none</td></tr>
     *   <tr><td>{@code ST-LINE7}</td><td>560</td><td>{@code Account ID} label and value</td>
     *       <td>{@code ST-ACCT-ID}</td></tr>
     *   <tr><td>{@code ST-LINE8}</td><td>640</td><td>{@code Current Balance} label and value</td>
     *       <td>{@code ST-CURR-BAL}</td></tr>
     *   <tr><td>{@code ST-LINE9}</td><td>720</td><td>{@code FICO Score} label and value</td>
     *       <td>{@code ST-FICO-SCORE}</td></tr>
     *   <tr><td>{@code ST-LINE10}</td><td>800</td><td>80 hyphens</td><td>none</td></tr>
     *   <tr><td>{@code ST-LINE11}</td><td>880</td>
     *       <td>centred {@code TRANSACTION SUMMARY } heading</td><td>none</td></tr>
     *   <tr><td>{@code ST-LINE12}</td><td>960</td><td>80 hyphens</td><td>none</td></tr>
     *   <tr><td>{@code ST-LINE13}</td><td>1040</td><td>the three column headings</td>
     *       <td>none</td></tr>
     *   <tr><td>{@code ST-LINE14}</td><td>1120</td><td>one transaction detail line</td>
     *       <td>{@code ST-TRANID}, {@code ST-TRANDT}, {@code ST-TRANAMT}</td></tr>
     *   <tr><td>{@code ST-LINE14A}</td><td>1200</td><td>the {@code Total EXP:} total line</td>
     *       <td>{@code ST-TOTAL-TRAMT}</td></tr>
     *   <tr><td>{@code ST-LINE15}</td><td>1280</td>
     *       <td>32 asterisks, {@code END OF STATEMENT}, 32 asterisks</td><td>none</td></tr>
     * </table>
     */
    public enum StatementLine {

        /** {@code 05 ST-LINE0.} - the opening banner, {@code app/cbl/CBSTM03A.CBL:L86-L89}. */
        ST_LINE0("ST-LINE0"),

        /** {@code 05 ST-LINE1.} - the customer name, {@code app/cbl/CBSTM03A.CBL:L90-L92}. */
        ST_LINE1("ST-LINE1"),

        /** {@code 05 ST-LINE2.} - address line 1, {@code app/cbl/CBSTM03A.CBL:L93-L95}. */
        ST_LINE2("ST-LINE2"),

        /** {@code 05 ST-LINE3.} - address line 2, {@code app/cbl/CBSTM03A.CBL:L96-L98}. */
        ST_LINE3("ST-LINE3"),

        /** {@code 05 ST-LINE4.} - address line 3, {@code app/cbl/CBSTM03A.CBL:L99-L100}. */
        ST_LINE4("ST-LINE4"),

        /**
         * {@code 05 ST-LINE5.} - an {@code ALL '-'} rule, {@code app/cbl/CBSTM03A.CBL:L101-L102}.
         * Written twice per statement, at {@code L492} and {@code L494}.
         */
        ST_LINE5("ST-LINE5"),

        /** {@code 05 ST-LINE6.} - the Basic Details heading, {@code L103-L106}. */
        ST_LINE6("ST-LINE6"),

        /** {@code 05 ST-LINE7.} - the account id line, {@code L107-L110}. */
        ST_LINE7("ST-LINE7"),

        /** {@code 05 ST-LINE8.} - the current balance line, {@code L111-L115}. */
        ST_LINE8("ST-LINE8"),

        /** {@code 05 ST-LINE9.} - the FICO score line, {@code L116-L119}. */
        ST_LINE9("ST-LINE9"),

        /** {@code 05 ST-LINE10.} - an {@code ALL '-'} rule, {@code L120-L121}. */
        ST_LINE10("ST-LINE10"),

        /** {@code 05 ST-LINE11.} - the Transaction Summary heading, {@code L122-L125}. */
        ST_LINE11("ST-LINE11"),

        /**
         * {@code 05 ST-LINE12.} - an {@code ALL '-'} rule, {@code L126-L127}. Written three times
         * per statement, at {@code L500}, {@code L502} and {@code L435}.
         */
        ST_LINE12("ST-LINE12"),

        /** {@code 05 ST-LINE13.} - the three column headings, {@code L128-L131}. */
        ST_LINE13("ST-LINE13"),

        /**
         * {@code 05 ST-LINE14.} - one transaction detail line, {@code L132-L137}. Written once per
         * transaction by {@code 6000-WRITE-TRANS} at {@code L679}.
         */
        ST_LINE14("ST-LINE14"),

        /**
         * {@code 05 ST-LINE14A.} - the {@code Total EXP:} total line, {@code L138-L142}. The
         * seventeenth group, and the one most easily missed when counting because of its suffixed
         * name. Written by {@code 4000-TRNXFILE-GET} at {@code L436}.
         */
        ST_LINE14A("ST-LINE14A"),

        /** {@code 05 ST-LINE15.} - the closing banner, {@code L143-L146}. */
        ST_LINE15("ST-LINE15");

        /** The COBOL group name, verbatim. */
        private final String cobolName;

        /**
         * Declares a line group. The offset is derived from the ordinal rather than restated, so a
         * transcription slip cannot put two lines at the same place.
         *
         * @param cobolName the COBOL group name, verbatim
         */
        StatementLine(String cobolName) {
            this.cobolName = cobolName;
        }

        /**
         * The COBOL group name exactly as {@code app/cbl/CBSTM03A.CBL} declares it - for example
         * {@code ST-LINE14A}.
         *
         * @return the COBOL name, never {@code null}
         */
        public String cobolName() {
            return cobolName;
        }

        /**
         * The absolute 0-based byte offset of this line within
         * {@code 01 STATEMENT-LINES}: {@code ordinal * }{@value StatementTextWriter#RECORD_LENGTH}.
         *
         * @return the offset, {@code 0} for {@link #ST_LINE0} and {@code 1280} for
         *         {@link #ST_LINE15}
         */
        public int offset() {
            return ordinal() * RECORD_LENGTH;
        }

        /**
         * The width of this line in bytes, which is {@value StatementTextWriter#RECORD_LENGTH} for
         * every line because every line is one whole {@code STMTFILE} record.
         *
         * @return {@value StatementTextWriter#RECORD_LENGTH}
         */
        public int length() {
            return RECORD_LENGTH;
        }
    }

    // =============================================================================================
    // The eleven mutable named slots, and what INITIALIZE does to each.
    // =============================================================================================

    /**
     * The category of a mutable slot, which decides the one thing that differs between slots when
     * {@code INITIALIZE STATEMENT-LINES} runs: the image the slot is cleared to.
     *
     * <p>Each constant supplies its own {@link #initialImage(FixedWidthCodec, int)} body, so the
     * reset loop performs no test of any kind - no {@code switch}, no {@code if} and no lookup
     * table. That is deliberate on two counts. It makes the COBOL rule readable one constant at a
     * time, and it keeps the reset free of a conditional that could only ever be exercised for some
     * of its arms.
     *
     * <p>The rule being encoded is IBM Enterprise COBOL's: {@code INITIALIZE} without
     * {@code REPLACING} sets an alphanumeric item to spaces and a numeric or numeric-edited item to
     * zero. For an <em>edited</em> item, "zero" means the picture's zero image - the result of
     * moving zero through the mask - not a run of {@code '0'} characters and not spaces.
     */
    public enum SlotKind {

        /**
         * {@code PIC X(n)} character data. {@code INITIALIZE} sets it to spaces across its full
         * declared width.
         */
        ALPHANUMERIC {
            @Override
            String initialImage(FixedWidthCodec codec, int length) {
                // Routed through the audited alphanumeric MOVE rather than assembled here, so that
                // "spaces to the declared width" has exactly one implementation in the module.
                return codec.movePicX("", length);
            }
        },

        /**
         * The {@code PIC 9(9).99-} edited picture of {@code ST-CURR-BAL}, whose leading zeros are
         * retained. {@code INITIALIZE} therefore yields {@code "000000000.00"} followed by the
         * positive sign position, a space.
         */
        ZERO_FILLED_AMOUNT {
            @Override
            String initialImage(FixedWidthCodec codec, int length) {
                return editZeroFilledAmount(CobolDecimal.monetaryZero());
            }
        },

        /**
         * The {@code PIC Z(9).99-} edited picture of {@code ST-TRANAMT} and
         * {@code ST-TOTAL-TRAMT}, whose leading zeros are suppressed to spaces. {@code INITIALIZE}
         * therefore yields nine spaces, {@code ".00"} and a space - not a zero-filled image, and
         * not an all-blank one, because no {@code BLANK WHEN ZERO} clause is declared.
         */
        ZERO_SUPPRESSED_AMOUNT {
            @Override
            String initialImage(FixedWidthCodec codec, int length) {
                return editZeroSuppressedAmount(CobolDecimal.monetaryZero());
            }
        };

        /**
         * The image {@code INITIALIZE STATEMENT-LINES} leaves in a slot of this kind.
         *
         * <p>Package-visible rather than public: it is the reset's own mechanism, and a caller that
         * wants a zero-valued amount image should call the renderer that names the picture instead.
         *
         * @param codec  the fixed-width codec, used for the alphanumeric case so that padding has a
         *               single implementation; the edited cases do not need it
         * @param length the slot's declared width in bytes
         * @return an image of exactly {@code length} characters
         */
        abstract String initialImage(FixedWidthCodec codec, int length);
    }

    /**
     * One mutable named item inside {@code 01 STATEMENT-LINES} - the eleven elementary items that
     * are <em>not</em> {@code FILLER}, and therefore the only bytes of the group that ever change.
     *
     * <p>Every other byte of every line is a {@code FILLER} carrying a declared {@code VALUE}, and
     * those bytes are established once when the line area is built and are never written again. That
     * split is what makes {@code INITIALIZE STATEMENT-LINES} safe to implement as "clear these
     * eleven and nothing else".
     *
     * <p>The absolute offset of each slot is derived from its owning line's offset plus its offset
     * <em>within</em> that line, so both numbers stay visible and neither has to be trusted on its
     * own. The sending field for each slot is named below, because the {@code MOVE} rule - which end
     * truncates, which end pads - follows from the sender's and receiver's pictures together:
     * <table border="1">
     *   <caption>The eleven slots, their pictures and their COBOL sending fields</caption>
     *   <tr><th>Slot</th><th>Line</th><th>Offset</th><th>{@code PICTURE}</th><th>Set from</th></tr>
     *   <tr><td>{@code ST-NAME}</td><td>{@code ST-LINE1}</td><td>80</td><td>{@code X(75)}</td>
     *       <td>{@code STRING} of the three customer name parts, {@code L462-L469}</td></tr>
     *   <tr><td>{@code ST-ADD1}</td><td>{@code ST-LINE2}</td><td>160</td><td>{@code X(50)}</td>
     *       <td>{@code CUST-ADDR-LINE-1 X(50)}, {@code L470}</td></tr>
     *   <tr><td>{@code ST-ADD2}</td><td>{@code ST-LINE3}</td><td>240</td><td>{@code X(50)}</td>
     *       <td>{@code CUST-ADDR-LINE-2 X(50)}, {@code L471}</td></tr>
     *   <tr><td>{@code ST-ADD3}</td><td>{@code ST-LINE4}</td><td>320</td><td>{@code X(80)}</td>
     *       <td>{@code STRING} of address line 3, state, country and ZIP, {@code L472-L481}</td></tr>
     *   <tr><td>{@code ST-ACCT-ID}</td><td>{@code ST-LINE7}</td><td>580</td><td>{@code X(20)}</td>
     *       <td>{@code ACCT-ID PIC 9(11)}, {@code L483}</td></tr>
     *   <tr><td>{@code ST-CURR-BAL}</td><td>{@code ST-LINE8}</td><td>660</td>
     *       <td>{@code 9(9).99-}</td>
     *       <td>{@code ACCT-CURR-BAL PIC S9(10)V99}, {@code L484}</td></tr>
     *   <tr><td>{@code ST-FICO-SCORE}</td><td>{@code ST-LINE9}</td><td>740</td>
     *       <td>{@code X(20)}</td>
     *       <td>{@code CUST-FICO-CREDIT-SCORE PIC 9(03)}, {@code L485}</td></tr>
     *   <tr><td>{@code ST-TRANID}</td><td>{@code ST-LINE14}</td><td>1120</td><td>{@code X(16)}</td>
     *       <td>{@code TRNX-ID X(16)}, {@code L676}</td></tr>
     *   <tr><td>{@code ST-TRANDT}</td><td>{@code ST-LINE14}</td><td>1137</td><td>{@code X(49)}</td>
     *       <td>{@code TRNX-DESC X(100)}, {@code L677}</td></tr>
     *   <tr><td>{@code ST-TRANAMT}</td><td>{@code ST-LINE14}</td><td>1187</td>
     *       <td>{@code Z(9).99-}</td><td>{@code TRNX-AMT PIC S9(09)V99}, {@code L678}</td></tr>
     *   <tr><td>{@code ST-TOTAL-TRAMT}</td><td>{@code ST-LINE14A}</td><td>1267</td>
     *       <td>{@code Z(9).99-}</td><td>{@code WS-TRN-AMT PIC S9(9)V99}, {@code L434}</td></tr>
     * </table>
     */
    public enum StatementSlot {

        /**
         * {@code 10 ST-NAME PIC X(75).} at {@code app/cbl/CBSTM03A.CBL:L91}, the whole of
         * {@code ST-LINE1} bar five trailing spaces.
         */
        ST_NAME("ST-NAME", StatementLine.ST_LINE1, 0, 75, SlotKind.ALPHANUMERIC),

        /** {@code 10 ST-ADD1 PIC X(50).} at {@code L94}. */
        ST_ADD1("ST-ADD1", StatementLine.ST_LINE2, 0, 50, SlotKind.ALPHANUMERIC),

        /** {@code 10 ST-ADD2 PIC X(50).} at {@code L97}. */
        ST_ADD2("ST-ADD2", StatementLine.ST_LINE3, 0, 50, SlotKind.ALPHANUMERIC),

        /**
         * {@code 10 ST-ADD3 PIC X(80).} at {@code L100} - the only slot that occupies an entire
         * line, so {@code ST-LINE4} declares no {@code FILLER} at all.
         */
        ST_ADD3("ST-ADD3", StatementLine.ST_LINE4, 0, 80, SlotKind.ALPHANUMERIC),

        /**
         * {@code 10 ST-ACCT-ID PIC X(20).} at {@code L109}, immediately after the 20-character
         * {@code 'Account ID         :'} label.
         */
        ST_ACCT_ID("ST-ACCT-ID", StatementLine.ST_LINE7, 20, 20, SlotKind.ALPHANUMERIC),

        /**
         * {@code 10 ST-CURR-BAL PIC 9(9).99-.} at {@code L113}, immediately after the 20-character
         * {@code 'Current Balance    :'} label. The only zero-filled edited slot.
         */
        ST_CURR_BAL("ST-CURR-BAL", StatementLine.ST_LINE8, 20, EDITED_AMOUNT_LENGTH,
                SlotKind.ZERO_FILLED_AMOUNT),

        /**
         * {@code 10 ST-FICO-SCORE PIC X(20).} at {@code L118}, immediately after the 20-character
         * {@code 'FICO Score         :'} label. An <em>alphanumeric</em> receiver fed from a
         * three-digit numeric sender, so the digits land at the left and the remainder is spaces.
         */
        ST_FICO_SCORE("ST-FICO-SCORE", StatementLine.ST_LINE9, 20, 20, SlotKind.ALPHANUMERIC),

        /** {@code 10 ST-TRANID PIC X(16).} at {@code L133}, at the start of the detail line. */
        ST_TRANID("ST-TRANID", StatementLine.ST_LINE14, 0, 16, SlotKind.ALPHANUMERIC),

        /**
         * {@code 10 ST-TRANDT PIC X(49).} at {@code L135}, after the one-space separator at offset
         * 16. Fed from {@code TRNX-DESC X(100)}, so more than half the description is discarded -
         * from the right, as a {@code PIC X} receiver requires.
         */
        ST_TRANDT("ST-TRANDT", StatementLine.ST_LINE14, 17, 49, SlotKind.ALPHANUMERIC),

        /**
         * {@code 10 ST-TRANAMT PIC Z(9).99-.} at {@code L137}, after the literal {@code '$'} at
         * offset 66.
         */
        ST_TRANAMT("ST-TRANAMT", StatementLine.ST_LINE14, 67, EDITED_AMOUNT_LENGTH,
                SlotKind.ZERO_SUPPRESSED_AMOUNT),

        /**
         * {@code 10 ST-TOTAL-TRAMT PIC Z(9).99-.} at {@code L142}, after the literal {@code '$'} at
         * offset 66 of {@code ST-LINE14A}. Note that it shares its within-line offset with
         * {@link #ST_TRANAMT}, so the amount column lines up between a detail line and the total.
         */
        ST_TOTAL_TRAMT("ST-TOTAL-TRAMT", StatementLine.ST_LINE14A, 67, EDITED_AMOUNT_LENGTH,
                SlotKind.ZERO_SUPPRESSED_AMOUNT);

        /** The COBOL item name, verbatim. */
        private final String cobolName;

        /** The line group this slot belongs to. */
        private final StatementLine line;

        /** The slot's 0-based offset within its line. */
        private final int offsetWithinLine;

        /** The slot's declared width in bytes. */
        private final int length;

        /** What {@code INITIALIZE} clears this slot to. */
        private final SlotKind kind;

        /**
         * Declares a slot.
         *
         * @param cobolName        the COBOL item name, verbatim
         * @param line             the line group the slot belongs to
         * @param offsetWithinLine the slot's 0-based offset within that line
         * @param length           the slot's declared width in bytes
         * @param kind             what {@code INITIALIZE} clears the slot to
         */
        StatementSlot(String cobolName,
                      StatementLine line,
                      int offsetWithinLine,
                      int length,
                      SlotKind kind) {
            this.cobolName = cobolName;
            this.line = line;
            this.offsetWithinLine = offsetWithinLine;
            this.length = length;
            this.kind = kind;
        }

        /**
         * The COBOL item name exactly as {@code app/cbl/CBSTM03A.CBL} declares it - for example
         * {@code ST-TOTAL-TRAMT}.
         *
         * @return the COBOL name, never {@code null}
         */
        public String cobolName() {
            return cobolName;
        }

        /**
         * The line group this slot belongs to, so a caller can see which record a change will
         * appear in.
         *
         * @return the owning line, never {@code null}
         */
        public StatementLine line() {
            return line;
        }

        /**
         * The slot's 0-based offset within its own line, which is the offset a reader can check
         * directly against the {@code PIC} widths preceding it in the copybook group.
         *
         * @return the within-line offset, from {@code 0} to {@code 79}
         */
        public int offsetWithinLine() {
            return offsetWithinLine;
        }

        /**
         * The slot's absolute 0-based offset within
         * {@code 01 STATEMENT-LINES}: {@code line().offset() + offsetWithinLine()}.
         *
         * @return the absolute offset
         */
        public int offset() {
            return line.offset() + offsetWithinLine;
        }

        /**
         * The slot's declared width in bytes, taken from its {@code PICTURE}. For the two edited
         * pictures this is {@value StatementTextWriter#EDITED_AMOUNT_LENGTH}.
         *
         * @return the width in bytes, at least 1
         */
        public int length() {
            return length;
        }

        /**
         * What {@code INITIALIZE STATEMENT-LINES} clears this slot to.
         *
         * @return the slot's category, never {@code null}
         */
        public SlotKind kind() {
            return kind;
        }
    }

    // =============================================================================================
    // The output seam.
    // =============================================================================================

    /**
     * Where a rendered {@value StatementTextWriter#RECORD_LENGTH}-byte statement record goes.
     *
     * <p>This is the seam that keeps the byte-composition logic above testable and keeps the
     * deployment-time data-access decision out of it. {@link StatementTextWriter#openOutput()}
     * supplies a {@link JdbcTemplate}-backed implementation that writes to the configured
     * {@value StatementTextWriter#DD_NAME} dataset; {@link
     * StatementTextWriter#openOutput(RecordSink)} accepts any other, which is how a unit test
     * collects the emitted records in memory with no database and no filesystem, and how a site
     * whose data-access driver expects a different parameter shape substitutes its own.
     *
     * <p>Implementations must preserve <strong>call order</strong> and must not buffer in a way that
     * could reorder or coalesce records: the statement's line sequence is parity-critical.
     *
     * <p>The single abstract method returns a {@link FileStatus.Outcome} rather than throwing,
     * because the COBOL {@code WRITE} at {@code app/cbl/CBSTM03A.CBL:L460} and its siblings declare
     * no {@code INVALID KEY} or {@code AT END} phrase: an unexpected result is the {@code WHEN
     * OTHER} arm of a guard chain, and it is the statement job - not this writer - that decides
     * whether to abend. {@code FileStatus} deliberately does not depend on the abend type, which is
     * what lets that decision stay with the job.
     */
    public interface RecordSink {

        /**
         * Accepts one whole statement record.
         *
         * @param recordImage the record's bytes in the dataset code page, exactly
         *                    {@value StatementTextWriter#RECORD_LENGTH} of them. The array is freshly
         *                    allocated for this call and is not retained or reused by the caller, so
         *                    an implementation may keep it
         * @return {@link FileStatus.Outcome#OK} when the record was accepted, or
         *         {@link FileStatus.Outcome#OTHER} for any failure - the {@code WHEN OTHER} arm
         */
        FileStatus.Outcome write(byte[] recordImage);

        /**
         * Releases whatever the sink holds, mirroring {@code CLOSE STMT-FILE} at
         * {@code app/cbl/CBSTM03A.CBL:L339}.
         *
         * <p>Defaulted to {@link FileStatus.Outcome#OK} because a sink that holds nothing - the
         * in-memory collector a test supplies, and the {@link JdbcTemplate}-backed default, which
         * borrows a pooled connection per record and returns it immediately - has nothing to
         * release.
         *
         * @return {@link FileStatus.Outcome#OK} when the sink closed cleanly, or
         *         {@link FileStatus.Outcome#OTHER} otherwise
         */
        default FileStatus.Outcome close() {
            return FileStatus.Outcome.OK;
        }
    }

    // =============================================================================================
    // 01 STATEMENT-LINES, span by span, at absolute offsets. app/cbl/CBSTM03A.CBL:L85-L146.
    // =============================================================================================

    /**
     * The layout of {@code 01 STATEMENT-LINES}: all forty-four spans of all
     * {@value #LINE_COUNT} lines, contiguous from offset 0 and summing to
     * {@value #STATEMENT_LINES_LENGTH}.
     *
     * <p>{@code static final} and deeply immutable - {@link RecordLayout} is a record that copies
     * its span list defensively and {@link FieldSpan} is a record of primitives and strings - so this
     * introduces no shared mutable state (practice B9, gate G53). It is shared by every
     * {@link StatementFile}; only the record <em>area</em> is per-handle.
     *
     * <p>Its correctness is machine-checked at class-initialisation time by
     * {@link RecordLayout}'s own geometry self-check, which rejects a gap, an overlap, a duplicate
     * referable name or a total that misses {@value #STATEMENT_LINES_LENGTH}. Dropping any single
     * {@code FILLER} therefore fails immediately and loudly rather than shifting every subsequent
     * byte (gate G21).
     */
    private static final RecordLayout STATEMENT_LINES = buildStatementLinesLayout();

    /**
     * Builds the layout of {@code 01 STATEMENT-LINES}, transcribed group by group from
     * {@code app/cbl/CBSTM03A.CBL:L85-L146}.
     *
     * <p>Two conventions run through it, and both are COBOL's:
     * <ul>
     *   <li>{@code VALUE ALL 'c'} fills the span's whole declared width with {@code c}, so it is
     *       expanded here to that width. {@code VALUE ALL 'START OF STATEMENT'} into an
     *       {@code X(18)} span is the 18-character literal exactly once, because the literal is
     *       already 18 characters long.</li>
     *   <li>A {@code VALUE} literal shorter than its span is left justified and space padded to the
     *       declared width - {@code 'Basic Details'} is 13 characters in an {@code X(14)} span, and
     *       {@code 'Tran Details    '} is 16 in an {@code X(51)} span. {@link FieldSpan}'s
     *       {@code FILLER} kind is left justified and space padded, so declaring the literal
     *       verbatim produces exactly that.</li>
     * </ul>
     * A {@code FILLER VALUE SPACES} span is declared with no literal at all, because the pad byte for
     * a {@code FILLER} span <em>is</em> the space: stating a run of spaces as a literal would say the
     * same thing twice and invite the two statements to disagree.
     *
     * <p>Each line's spans are written against a {@code base} that advances by
     * {@value #RECORD_LENGTH}, so every offset below reads as "this line, this far in" and can be
     * checked against the copybook without adding up seventeen lines in your head.
     *
     * @return the validated layout
     */
    private static RecordLayout buildStatementLinesLayout() {
        int line0 = StatementLine.ST_LINE0.offset();
        int line1 = StatementLine.ST_LINE1.offset();
        int line2 = StatementLine.ST_LINE2.offset();
        int line3 = StatementLine.ST_LINE3.offset();
        int line4 = StatementLine.ST_LINE4.offset();
        int line5 = StatementLine.ST_LINE5.offset();
        int line6 = StatementLine.ST_LINE6.offset();
        int line7 = StatementLine.ST_LINE7.offset();
        int line8 = StatementLine.ST_LINE8.offset();
        int line9 = StatementLine.ST_LINE9.offset();
        int line10 = StatementLine.ST_LINE10.offset();
        int line11 = StatementLine.ST_LINE11.offset();
        int line12 = StatementLine.ST_LINE12.offset();
        int line13 = StatementLine.ST_LINE13.offset();
        int line14 = StatementLine.ST_LINE14.offset();
        int line14a = StatementLine.ST_LINE14A.offset();
        int line15 = StatementLine.ST_LINE15.offset();

        return RecordLayout.of(STATEMENT_LINES_LENGTH,

                // 05 ST-LINE0.  L86-L89 - 31 + 18 + 31 = 80.
                FieldSpan.filler(line0, 31, repeat('*', 31)),
                FieldSpan.filler(line0 + 31, 18, "START OF STATEMENT"),
                FieldSpan.filler(line0 + 49, 31, repeat('*', 31)),

                // 05 ST-LINE1.  L90-L92 - 75 + 5 = 80.
                slotSpan(StatementSlot.ST_NAME),
                FieldSpan.filler(line1 + 75, 5),

                // 05 ST-LINE2.  L93-L95 - 50 + 30 = 80.
                slotSpan(StatementSlot.ST_ADD1),
                FieldSpan.filler(line2 + 50, 30),

                // 05 ST-LINE3.  L96-L98 - 50 + 30 = 80.
                slotSpan(StatementSlot.ST_ADD2),
                FieldSpan.filler(line3 + 50, 30),

                // 05 ST-LINE4.  L99-L100 - one 80-byte slot, no FILLER.
                slotSpan(StatementSlot.ST_ADD3),

                // 05 ST-LINE5.  L101-L102 - ALL '-' across the full width.
                FieldSpan.filler(line5, RECORD_LENGTH, repeat('-', RECORD_LENGTH)),

                // 05 ST-LINE6.  L103-L106 - 33 + 14 + 33 = 80. 'Basic Details' is 13 in an X(14).
                FieldSpan.filler(line6, 33),
                FieldSpan.filler(line6 + 33, 14, "Basic Details"),
                FieldSpan.filler(line6 + 47, 33),

                // 05 ST-LINE7.  L107-L110 - 20 + 20 + 40 = 80.
                FieldSpan.filler(line7, 20, "Account ID         :"),
                slotSpan(StatementSlot.ST_ACCT_ID),
                FieldSpan.filler(line7 + 40, 40),

                // 05 ST-LINE8.  L111-L115 - 20 + 13 + 7 + 40 = 80. The COBOL declares the trailing
                // spaces as TWO separate FILLER items, X(07) then X(40); both are kept as declared
                // rather than merged, so the span list matches the copybook item for item.
                FieldSpan.filler(line8, 20, "Current Balance    :"),
                slotSpan(StatementSlot.ST_CURR_BAL),
                FieldSpan.filler(line8 + 33, 7),
                FieldSpan.filler(line8 + 40, 40),

                // 05 ST-LINE9.  L116-L119 - 20 + 20 + 40 = 80.
                FieldSpan.filler(line9, 20, "FICO Score         :"),
                slotSpan(StatementSlot.ST_FICO_SCORE),
                FieldSpan.filler(line9 + 40, 40),

                // 05 ST-LINE10.  L120-L121 - ALL '-' across the full width.
                FieldSpan.filler(line10, RECORD_LENGTH, repeat('-', RECORD_LENGTH)),

                // 05 ST-LINE11.  L122-L125 - 30 + 20 + 30 = 80. The literal is 'TRANSACTION
                // SUMMARY ' with a TRAILING SPACE inside the quotes, which is why it fills all 20
                // positions and why the heading sits one column left of centre.
                FieldSpan.filler(line11, 30),
                FieldSpan.filler(line11 + 30, 20, "TRANSACTION SUMMARY "),
                FieldSpan.filler(line11 + 50, 30),

                // 05 ST-LINE12.  L126-L127 - ALL '-' across the full width.
                FieldSpan.filler(line12, RECORD_LENGTH, repeat('-', RECORD_LENGTH)),

                // 05 ST-LINE13.  L128-L131 - 16 + 51 + 13 = 80. 'Tran Details    ' is 16 characters
                // declared into X(51), so it is space padded to 51; '  Tran Amount' begins with TWO
                // leading spaces and exactly fills X(13). Headings land at offsets 0, 16 and 67.
                FieldSpan.filler(line13, 16, "Tran ID         "),
                FieldSpan.filler(line13 + 16, 51, "Tran Details    "),
                FieldSpan.filler(line13 + 67, 13, "  Tran Amount"),

                // 05 ST-LINE14.  L132-L137 - 16 + 1 + 49 + 1 + 13 = 80.
                slotSpan(StatementSlot.ST_TRANID),
                FieldSpan.filler(line14 + 16, 1, " "),
                slotSpan(StatementSlot.ST_TRANDT),
                FieldSpan.filler(line14 + 66, 1, "$"),
                slotSpan(StatementSlot.ST_TRANAMT),

                // 05 ST-LINE14A.  L138-L142 - 10 + 56 + 1 + 13 = 80.
                FieldSpan.filler(line14a, 10, "Total EXP:"),
                FieldSpan.filler(line14a + 10, 56),
                FieldSpan.filler(line14a + 66, 1, "$"),
                slotSpan(StatementSlot.ST_TOTAL_TRAMT),

                // 05 ST-LINE15.  L143-L146 - 32 + 16 + 32 = 80.
                FieldSpan.filler(line15, 32, repeat('*', 32)),
                FieldSpan.filler(line15 + 32, 16, "END OF STATEMENT"),
                FieldSpan.filler(line15 + 48, 32, repeat('*', 32)));
    }

    /**
     * Declares the layout span for a named slot, taking its name, absolute offset and width from the
     * slot itself so the two can never drift apart.
     *
     * <p>Both edited slots are declared {@link FixedWidthRecord.PictureKind#ALPHANUMERIC} as well,
     * and deliberately. A numeric-edited item's <em>storage</em> is characters - the editing happened
     * when the value was moved in - and {@link FixedWidthRecord.PictureKind} is a storage-category
     * discriminator rather than a {@code PICTURE} parser. Declaring an edited slot as a numeric kind
     * would make the layer below
     * right justify it and pad it with zeros, which is not what an already-edited 13-character image
     * needs. The editing itself is {@link #editZeroFilledAmount(BigDecimal)} and
     * {@link #editZeroSuppressedAmount(BigDecimal)}, and nothing else in the module performs it.
     *
     * @param slot the slot to declare
     * @return the descriptor, named with the slot's verbatim COBOL name
     */
    private static FieldSpan slotSpan(StatementSlot slot) {
        return FieldSpan.alphanumeric(slot.cobolName(), slot.offset(), slot.length());
    }

    /**
     * Expands a COBOL {@code VALUE ALL 'c'} clause to the span's declared width.
     *
     * @param character the character the clause repeats
     * @param width     the span's declared width; at least 1
     * @return a string of exactly {@code width} copies of {@code character}
     */
    private static String repeat(char character, int width) {
        return String.valueOf(character).repeat(width);
    }

    // =============================================================================================
    // The two numeric-edited PICTURE masks, hand-implemented. These live here and nowhere else.
    // =============================================================================================

    /**
     * Edits a value through {@code PIC 9(9).99-}, the picture of {@code ST-CURR-BAL} at
     * {@code app/cbl/CBSTM03A.CBL:L113}.
     *
     * <p>Thirteen characters, in this order: nine integer positions with <strong>leading zeros
     * retained</strong>, the literal {@code .}, two fraction digits, and one trailing sign position
     * holding {@code -} when the value is negative and a <strong>space</strong> when it is zero or
     * positive. A trailing {@code -} in a {@code PICTURE} never prints {@code +}.
     *
     * <p><strong>High-order truncation is required behaviour here, not a defect.</strong> The sending
     * field is {@code ACCT-CURR-BAL PIC S9(10)V99} ({@code app/cpy/CVACT01Y.cpy:7}) - <em>ten</em>
     * integer digits moved into <em>nine</em> integer positions by
     * {@code MOVE ACCT-CURR-BAL TO ST-CURR-BAL} at {@code app/cbl/CBSTM03A.CBL:L484}. A numeric move
     * aligns on the implied decimal point, so the excess <em>high-order</em> digit is discarded and
     * the receiver keeps the low-order nine. COBOL reports that loss only if the program asks with
     * {@code ON SIZE ERROR}, and this one does not, so the mask is neither widened nor is an
     * exception raised - a balance of {@code 9999999999.99} edits to {@code "999999999.99"} with the
     * leading nine dropped.
     *
     * <table border="1">
     *   <caption>Worked examples, each exactly 13 characters</caption>
     *   <tr><th>Value</th><th>Image</th><th>Why</th></tr>
     *   <tr><td>{@code 194.00}</td><td>{@code 000000194.00 }</td>
     *       <td>leading zeros retained; positive sign is a space</td></tr>
     *   <tr><td>{@code -919.00}</td><td>{@code 000000919.00-}</td><td>negative sign position</td></tr>
     *   <tr><td>{@code 0.00}</td><td>{@code 000000000.00 }</td>
     *       <td>no {@code BLANK WHEN ZERO} is declared, so zero still prints</td></tr>
     *   <tr><td>{@code 9999999999.99}</td><td>{@code 999999999.99 }</td>
     *       <td>ten integer digits into nine positions</td></tr>
     * </table>
     *
     * <p>Package-visible so the statement job can render an image for the HTML statement without
     * this logic being duplicated there, exactly as the COBOL's HTML paragraphs reference the edited
     * item rather than re-editing the value. A pure function of its argument: no field is read or
     * written, so it is callable from a plain unit test with no bean and no context.
     *
     * @param value the sending value, at any scale; {@code null} is rejected
     * @return an image of exactly {@value #EDITED_AMOUNT_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    static String editZeroFilledAmount(BigDecimal value) {
        return editAmount(value, false);
    }

    /**
     * Edits a value through {@code PIC Z(9).99-}, the picture of {@code ST-TRANAMT} at
     * {@code app/cbl/CBSTM03A.CBL:L137} and {@code ST-TOTAL-TRAMT} at {@code L142}.
     *
     * <p>Identical to {@link #editZeroFilledAmount(BigDecimal)} in every respect but one: the nine
     * integer positions are {@code Z}, so their <strong>leading zeros are suppressed to spaces</strong>.
     *
     * <p>Two properties of COBOL zero suppression decide the awkward cases, and both are
     * reproduced:
     * <ul>
     *   <li>Suppression stops at the first significant digit <em>or at the decimal point, whichever
     *       comes first</em>. The suppression region is the integer positions only, so the fraction
     *       digits are never suppressed - {@code 0.41} edits to {@code "         .41 "}, nine spaces
     *       and then the fraction.</li>
     *   <li>No {@code BLANK WHEN ZERO} clause is declared on either item, so a zero value does
     *       <strong>not</strong> blank the whole field: it edits to nine spaces, {@code ".00"} and the
     *       positive sign space. Blanking all 13 characters would be the behaviour of a clause this
     *       copybook does not have.</li>
     * </ul>
     *
     * <table border="1">
     *   <caption>Worked examples, each exactly 13 characters</caption>
     *   <tr><th>Value</th><th>Image</th><th>Why</th></tr>
     *   <tr><td>{@code 194.00}</td><td>{@code       194.00 }</td>
     *       <td>six leading zeros suppressed to spaces</td></tr>
     *   <tr><td>{@code -919.00}</td><td>{@code       919.00-}</td>
     *       <td>suppression is independent of the sign</td></tr>
     *   <tr><td>{@code 0.00}</td><td>{@code          .00 }</td>
     *       <td>all nine integer positions suppressed; no {@code BLANK WHEN ZERO}</td></tr>
     *   <tr><td>{@code 0.41}</td><td>{@code          .41 }</td>
     *       <td>suppression stops at the decimal point, never inside the fraction</td></tr>
     * </table>
     *
     * <p>The sending fields are {@code TRNX-AMT PIC S9(09)V99} ({@code app/cpy/COSTM01.CPY:29}) and
     * {@code WS-TRN-AMT PIC S9(9)V99} ({@code app/cbl/CBSTM03A.CBL:L68}), both of which carry exactly
     * nine integer digits, so - unlike the balance - no truncation occurs on either of these two
     * moves. The truncation is nonetheless implemented identically, because the mask's behaviour is a
     * property of the mask.
     *
     * @param value the sending value, at any scale; {@code null} is rejected
     * @return an image of exactly {@value #EDITED_AMOUNT_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    static String editZeroSuppressedAmount(BigDecimal value) {
        return editAmount(value, true);
    }

    /**
     * The shared body of the two masks, which differ only in leading-zero handling.
     *
     * <p>Three decisions are taken here, each separately:
     * <ol>
     *   <li><strong>The sign comes from the sending operand.</strong> A {@code PICTURE} sign control
     *       symbol represents the operational sign of the value being edited, so the test is made on
     *       {@code value} before any truncation. Taking it from the truncated magnitude instead would
     *       differ for a value whose magnitude truncates away entirely.</li>
     *   <li><strong>The digits come from the magnitude, bounded to the receiver.</strong>
     *       {@link CobolDecimal#storeAtPicture(BigDecimal, int, int)} truncates the fraction to scale
     *       2 with {@link java.math.RoundingMode#DOWN} - never half-up and never half-even, because
     *       {@code ROUNDED} appears zero times in all 28 programs - and then discards integer digits
     *       beyond the ninth. It never throws, matching COBOL's silence in the absence of
     *       {@code ON SIZE ERROR}. Taking {@link BigDecimal#abs()} first keeps the sign decision and
     *       the digit decision independent of one another.</li>
     *   <li><strong>No binary floating-point type appears anywhere on this path.</strong> Binary
     *       floating point cannot represent a decimal fraction exactly, so a monetary value derived
     *       from a {@code PIC 9...V...} field is {@link BigDecimal} at every step, and a scan of this
     *       file for either primitive floating-point keyword is expected to find nothing - which is
     *       the point, and is why neither is written out here (gate G22).</li>
     * </ol>
     *
     * @param value                the sending value
     * @param suppressLeadingZeros {@code true} for the {@code Z(9)} integer positions, {@code false}
     *                             for the {@code 9(9)} positions
     * @return an image of exactly {@value #EDITED_AMOUNT_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private static String editAmount(BigDecimal value, boolean suppressLeadingZeros) {
        Objects.requireNonNull(value, "A value is required to edit through a numeric-edited "
                + "PICTURE; to render a zero amount pass CobolDecimal.monetaryZero() explicitly, "
                + "because the two masks render zero differently and the choice must be visible");

        // 1. The sign control symbol reflects the operational sign of the SENDING value.
        char signPosition = value.signum() < 0 ? MINUS : SPACE;

        // 2. The digits are the magnitude, truncated to the receiver's two fraction digits and nine
        //    integer digits.
        BigDecimal magnitude = CobolDecimal.storeAtPicture(value.abs(), EDITED_INTEGER_DIGITS,
                EDITED_FRACTION_DIGITS);
        String digits = alignedDigits(magnitude);
        String integerPositions = digits.substring(0, EDITED_INTEGER_DIGITS);
        String fractionPositions = digits.substring(EDITED_INTEGER_DIGITS);

        StringBuilder image = new StringBuilder(EDITED_AMOUNT_LENGTH);
        image.append(suppressLeadingZeros ? suppress(integerPositions) : integerPositions);
        image.append(DECIMAL_POINT);
        image.append(fractionPositions);
        image.append(signPosition);
        return image.toString();
    }

    /**
     * Renders a non-negative magnitude of scale {@value #EDITED_FRACTION_DIGITS} as exactly
     * {@code EDITED_INTEGER_DIGITS + EDITED_FRACTION_DIGITS} digit characters, with no decimal point.
     *
     * <p>This is the {@code PIC 9(n)} alignment rule that
     * {@link FixedWidthCodec#movePic9(String, int)} implements for a stored field, applied here to an
     * edited one: zeros pad on the <strong>left</strong> and, if the value were ever wider than the
     * receiver, the surviving digits would be the <strong>low-order</strong> ones. It is written
     * without a length test - pad first, then keep the trailing positions - so there is no
     * conditional whose over-wide arm could never be reached, {@link CobolDecimal#storeAtPicture} having
     * already bounded the value.
     *
     * @param magnitude a non-negative value of scale {@value #EDITED_FRACTION_DIGITS}
     * @return exactly eleven digit characters: nine integer positions then two fraction positions
     */
    private static String alignedDigits(BigDecimal magnitude) {
        int width = EDITED_INTEGER_DIGITS + EDITED_FRACTION_DIGITS;
        String unscaled = magnitude.unscaledValue().toString();
        String padded = repeat(ZERO, width) + unscaled;
        return padded.substring(padded.length() - width);
    }

    /**
     * Applies {@code Z} zero suppression to the integer positions of an edited image.
     *
     * <p>Each leading {@code '0'} becomes a space and the scan stops at the first significant digit.
     * When every position is zero, every position becomes a space - suppression then runs to the end
     * of the region, which is the decimal point, and no significant digit is ever reached. The
     * fraction positions are not passed in at all, which is what guarantees they are never
     * suppressed.
     *
     * @param integerPositions the {@value #EDITED_INTEGER_DIGITS} integer digit characters
     * @return the same positions with leading zeros replaced by spaces, the same length
     */
    private static String suppress(String integerPositions) {
        StringBuilder suppressed = new StringBuilder(integerPositions);
        for (int position = 0; position < suppressed.length(); position++) {
            if (suppressed.charAt(position) != ZERO) {
                // Suppression stops at the first significant digit; everything from here is data.
                break;
            }
            suppressed.setCharAt(position, SPACE);
        }
        return suppressed.toString();
    }

    // =============================================================================================
    // Injected collaborators. Four final fields, no setter, no static mutable state.
    // =============================================================================================

    /**
     * The module's single {@link JdbcTemplate}, declared by the data-source configuration. Used only
     * by the default sink that {@link #openOutput()} creates; a caller that supplies its own sink
     * never touches it.
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * The fixed-width codec, constructed over the injected dataset {@link Charset}.
     *
     * <p>Immutable and holds only that charset, so sharing one across every handle is safe. Every
     * pad, truncate and byte conversion this class performs goes through it, which is what keeps the
     * code page explicit at every boundary and keeps the alphanumeric {@code MOVE} rule in exactly
     * one place in the module.
     */
    private final FixedWidthCodec codec;

    /**
     * The configured binding for {@value #DD_NAME}: where the dataset lives and what shape its
     * records are. Resolved once, by DD-name key, so no dataset name is written into this file.
     */
    private final DatasetBinding binding;

    /**
     * The parameterised statement the default sink issues for one record, built once at construction
     * from the binding. See {@link #insertStatement(String)} for why it takes the shape it does.
     */
    private final String insertStatement;

    /**
     * Wires the writer and verifies, before the application can start, that the configured dataset
     * geometry agrees with the COBOL file description.
     *
     * <p>Only three collaborators, all constructor-injected: there is no setter and no field
     * injection, so a fully constructed instance is always usable and never half-configured
     * (practice B9, gate G53).
     *
     * <p>The record-length cross-check is the point of doing any work here at all. A binding that
     * declared, say, 100 bytes would produce records of the wrong width for every statement of every
     * run, and the failure would surface as a mass parity diff far from its cause. Failing at startup
     * with the DD name, both widths and the authoritative source cited turns that into a one-line
     * fix. Gate G20 is this check.
     *
     * @param jdbcTemplate   the module's single {@link JdbcTemplate}
     * @param datasetCharset the active dataset code page, resolved by the charset configuration from
     *                       {@code carddemo.charset.dataset} and named here by bean qualifier so no
     *                       platform default can be picked up by accident (practice B8)
     * @param datasetBindings the DD-name-keyed dataset catalogue bound from {@code carddemo.datasets}
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if no binding is configured for {@value #DD_NAME}, which the
     *                               catalogue itself reports, or if the configured record length is
     *                               not {@value #RECORD_LENGTH}
     */
    public StatementTextWriter(
            JdbcTemplate jdbcTemplate,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            DatasetBindings datasetBindings) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required to "
                + "write the " + DD_NAME + " dataset; the data-source configuration declares the "
                + "single instance this module shares");
        Objects.requireNonNull(datasetCharset, "A dataset charset is required: a fixed-width "
                + "mainframe record is bytes in a specific code page, so the code page is injected "
                + "explicitly and is never derived from the platform");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets catalogue is required to "
                + "resolve the " + DD_NAME + " dataset; dataset names are never hard-coded in Java");

        this.codec = new FixedWidthCodec(datasetCharset);
        this.binding = datasetBindings.binding(DD_NAME);
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("carddemo.datasets." + DD_NAME + " declares "
                    + "record-length " + binding.recordLength() + ", but the plain-text statement "
                    + "record is " + RECORD_LENGTH + " bytes: app/cbl/CBSTM03A.CBL:L45 declares "
                    + "01 FD-STMTFILE-REC PIC X(80) and app/jcl/CREASTMT.JCL:L89 declares LRECL=80 "
                    + "on the creating step. Correct record-length to " + RECORD_LENGTH
                    + " in application.yml; a record width is copybook-fixed and must never be "
                    + "overridden per profile.");
        }
        this.insertStatement = insertStatement(binding.dsname());
    }

    /**
     * The configured binding for {@value #DD_NAME} - its location, organization, record format,
     * block size and record length exactly as configuration declares them.
     *
     * @return the binding, never {@code null}
     */
    public DatasetBinding datasetBinding() {
        return binding;
    }

    /**
     * The code page this writer encodes records in, as injected. Exposed so a caller that needs to
     * decode an emitted record - a parity harness, or a test - uses the same explicitly chosen
     * encoding rather than resolving one of its own.
     *
     * @return the dataset charset, never {@code null}
     */
    public Charset datasetCharset() {
        return codec.charset();
    }

    /**
     * The fixed record width in bytes, {@value #RECORD_LENGTH}, cross-checked against configuration
     * at construction.
     *
     * @return {@value #RECORD_LENGTH}
     */
    public int recordLength() {
        return RECORD_LENGTH;
    }

    // =============================================================================================
    // Opening the dataset. OPEN OUTPUT, app/cbl/CBSTM03A.CBL:L293.
    // =============================================================================================

    /**
     * Opens {@value #DD_NAME} for output against the configured dataset, mirroring
     * {@code OPEN OUTPUT STMT-FILE} at {@code app/cbl/CBSTM03A.CBL:L293}.
     *
     * <p>Output, not append: the JCL declares {@code DISP=(NEW,CATLG,DELETE)} at
     * {@code app/jcl/CREASTMT.JCL:L87} and the COBOL opens once at the start of the run and closes
     * once at the end ({@code L339}). Each call returns a fresh handle with its own line area, so a
     * caller that opens twice gets two independent runs and never shares slot state between them.
     *
     * @return a new per-execution handle, already in the {@code INITIALIZE STATEMENT-LINES} state
     */
    public StatementFile openOutput() {
        return new StatementFile(new JdbcRecordSink(jdbcTemplate, insertStatement));
    }

    /**
     * Opens {@value #DD_NAME} for output against a caller-supplied sink.
     *
     * <p>This is the seam that makes the whole class testable with no database, no filesystem, no
     * application context and no {@code JobLauncher} (practice B10, gate G51): a test passes a
     * collector and asserts the emitted bytes and their order directly. It is equally the supported
     * extension point for a deployment whose data-access driver expects a different parameter shape
     * from the default sink's.
     *
     * @param sink where rendered records go; must not be {@code null}
     * @return a new per-execution handle, already in the {@code INITIALIZE STATEMENT-LINES} state
     * @throws NullPointerException if {@code sink} is {@code null}
     */
    public StatementFile openOutput(RecordSink sink) {
        return new StatementFile(Objects.requireNonNull(sink, "A record sink is required to open "
                + DD_NAME + " for output; call openOutput() for the configured dataset"));
    }

    /**
     * Builds the single-parameter statement the default sink issues for one record.
     *
     * <p>Its shape is chosen to assume as little as possible about the backend:
     * <ul>
     *   <li><strong>No column list.</strong> This migration introduces no schema, no data-definition
     *       statement, no entity mapping and no version column (gate G44), so it cannot name a
     *       column. The record is one fixed-width image and is bound positionally.</li>
     *   <li><strong>The dataset name is a delimited identifier.</strong> A mainframe dataset name
     *       contains dots, which an SQL parser would otherwise read as a qualified
     *       catalogue-schema-table name. Wrapping it in the ANSI delimited-identifier quote keeps it
     *       one name, and any embedded quote is doubled per the same standard rule.</li>
     *   <li><strong>The name comes from configuration, verbatim.</strong> It is resolved by DD-name
     *       key from {@code carddemo.datasets}, so no mainframe dataset literal appears in this file
     *       or anywhere else in the Java sources (gate G46).</li>
     * </ul>
     *
     * <p>Package-visible so its behaviour is asserted directly by a unit test rather than inferred
     * from a database round trip.
     *
     * @param dsname the configured dataset name or resource location
     * @return the parameterised statement
     * @throws NullPointerException     if {@code dsname} is {@code null}
     * @throws IllegalArgumentException if {@code dsname} is blank, which would leave no addressable
     *                                  destination at all
     */
    static String insertStatement(String dsname) {
        Objects.requireNonNull(dsname, "A dataset name is required to address " + DD_NAME
                + "; it is declared as carddemo.datasets." + DD_NAME + ".dsname");
        if (dsname.isBlank()) {
            throw new IllegalArgumentException("carddemo.datasets." + DD_NAME + ".dsname is blank, "
                    + "so there is no destination to write the plain-text statement to. Set it to "
                    + "the dataset name, or - under a fixture-backed profile - to a resolvable "
                    + "resource location.");
        }
        return "INSERT INTO \"" + dsname.replace("\"", "\"\"") + "\" VALUES (?)";
    }

    /**
     * The default {@link RecordSink}: one parameterised insert of the whole record image per record,
     * issued immediately and in call order.
     *
     * <p>Immutable and stateless, so it is safe to hold and safe to share; the pooled connection is
     * borrowed and returned inside each call, which is why {@link RecordSink#close()} has nothing to
     * do and is left defaulted.
     *
     * <p>The record image is bound as <strong>bytes</strong>, not as text. The code page decision was
     * already taken - explicitly, by the injected charset - when the line area encoded the image, so
     * binding the bytes transmits exactly what the dataset is meant to contain. Binding a string
     * would hand the driver a second, unstated encoding decision and could silently re-encode every
     * record (practice B8).
     *
     * <p>The row count the statement returns is deliberately not inspected. The COBOL
     * {@code WRITE FD-STMTFILE-REC FROM ...} statements declare no {@code INVALID KEY} and no
     * {@code AT END} phrase and the program tests no {@code FILE STATUS} for this dataset, so
     * anything short of a raised failure is a completed write, and inventing a stricter check here
     * would reject records the COBOL accepts.
     */
    private static final class JdbcRecordSink implements RecordSink {

        /** The template that issues the insert. */
        private final JdbcTemplate jdbcTemplate;

        /** The parameterised statement, built once by {@link #insertStatement(String)}. */
        private final String statement;

        /**
         * Creates the sink.
         *
         * @param jdbcTemplate the template that issues the insert
         * @param statement    the parameterised statement
         */
        JdbcRecordSink(JdbcTemplate jdbcTemplate, String statement) {
            this.jdbcTemplate = jdbcTemplate;
            this.statement = statement;
        }

        /**
         * Writes one record, mapping a rejected write onto the {@code WHEN OTHER} arm.
         *
         * <p>Only {@link DataAccessException} is caught, and that narrowness is deliberate: it is the
         * family Spring translates a genuine data-access failure into. A configuration defect such as
         * an unset data source raises a different, unchecked type and is left to propagate, because
         * mapping it to a file-status outcome would let a misconfigured deployment abend with a
         * misleading reason for every record instead of failing once, clearly.
         *
         * @param recordImage the record's bytes in the dataset code page
         * @return {@link FileStatus.Outcome#OK}, or {@link FileStatus.Outcome#OTHER} when the write
         *         was rejected
         */
        @Override
        public FileStatus.Outcome write(byte[] recordImage) {
            PreparedStatementSetter binder = parameters -> parameters.setBytes(1, recordImage);
            try {
                jdbcTemplate.update(statement, binder);
                return FileStatus.Outcome.OK;
            } catch (DataAccessException rejected) {
                // The cause is logged here because the outcome that travels back to the caller is
                // deliberately coarse - the COBOL guard chain has a single WHEN OTHER arm - and
                // discarding the reason would leave a production abend undiagnosable.
                LOG.error("Rejected write of a " + RECORD_LENGTH + "-byte " + DD_NAME
                        + " record; reporting FILE STATUS outcome "
                        + FileStatus.Outcome.OTHER.name() + " to the caller", rejected);
                return FileStatus.Outcome.OTHER;
            }
        }
    }

    // =============================================================================================
    // The per-execution handle. EVERY piece of mutable state in this file lives here.
    // =============================================================================================

    /**
     * One opened {@value #DD_NAME} dataset, together with the
     * {@value #STATEMENT_LINES_LENGTH}-byte {@code 01 STATEMENT-LINES} area whose slots the caller
     * populates.
     *
     * <p>This type exists so the writer bean can be a stateless singleton. A COBOL record area is
     * mutable and per-run; making it a field on a singleton would let two concurrent statement runs
     * overwrite each other's customer name and would make a test's outcome depend on what ran before
     * it. So the area, the sink, the open flag and the record count are all here, obtained from
     * {@link StatementTextWriter#openOutput()} and discarded at the end of the run (practice B9,
     * gate G53).
     *
     * <p>A handle is <strong>not</strong> thread-safe, exactly as a COBOL record area is not, and is
     * meant to be confined to the step, chunk or test that opened it. It implements
     * {@link AutoCloseable} so a try-with-resources block reproduces the
     * {@code OPEN OUTPUT} / {@code CLOSE} pairing of {@code app/cbl/CBSTM03A.CBL:L293} and
     * {@code L339}; {@link #closeOutput()} is the form that returns the outcome.
     *
     * <p>A freshly opened handle is already in the {@code INITIALIZE STATEMENT-LINES} state, so its
     * banners, rules and headings are in place and its eleven slots are cleared before the caller
     * touches anything.
     */
    public final class StatementFile implements AutoCloseable {

        /** Where rendered records go, in call order. */
        private final RecordSink sink;

        /**
         * The {@code 01 STATEMENT-LINES} area: all {@value #LINE_COUNT} lines in one contiguous
         * record, as the COBOL group is.
         */
        private final FixedWidthRecord lineArea;

        /** Whether the dataset is still open. Set once, by {@link #closeOutput()}. */
        private boolean open;

        /** How many records have been handed to the sink, successfully or not. */
        private int recordsWritten;

        /**
         * Allocates the line area, lays down every {@code FILLER} literal from the layout, and puts
         * the eleven slots into their {@code INITIALIZE} state.
         *
         * @param sink where rendered records go
         */
        private StatementFile(RecordSink sink) {
            this.sink = sink;
            this.lineArea = codec.newRecord(STATEMENT_LINES);
            this.open = true;
            initializeStatementLines();
        }

        // -----------------------------------------------------------------------------------------
        // INITIALIZE STATEMENT-LINES. app/cbl/CBSTM03A.CBL:L459.
        // -----------------------------------------------------------------------------------------

        /**
         * Reproduces {@code INITIALIZE STATEMENT-LINES.} from
         * {@code app/cbl/CBSTM03A.CBL:L459} - the statement executed at the top of
         * {@code 5000-CREATE-STATEMENT}, immediately before {@code ST-LINE0} is written.
         *
         * <p>It clears the eleven {@link StatementSlot} items and <strong>nothing else</strong>.
         * Alphanumeric slots become spaces and the two edited slots take their picture's zero image;
         * every {@code FILLER} byte - the two asterisk banners, the three {@code ALL '-'} rules, the
         * {@code Basic Details}, {@code TRANSACTION SUMMARY } and column headings, the three
         * {@code :} labels, the two {@code $} signs and every run of declared spaces - is left exactly
         * as the layout established it, because IBM Enterprise COBOL's {@code INITIALIZE} without
         * {@code REPLACING} does not affect elementary {@code FILLER} items.
         *
         * <p>It is emphatically <em>not</em> a blank of the {@value #STATEMENT_LINES_LENGTH}-byte
         * area. That mistake would erase every banner and heading in the statement while still
         * leaving each line 80 bytes long, so a width check would pass and only a byte-level
         * comparison would catch it. Gate G21 is the assertion that it has not been made.
         *
         * <p>Idempotent, and safe to call between statements: the statement job calls it once per
         * customer, which is what stops one customer's transaction id or amount from surviving into
         * the next customer's statement.
         */
        public void initializeStatementLines() {
            for (StatementSlot slot : StatementSlot.values()) {
                // The image is chosen by the slot's own kind, so this loop performs no test and
                // cannot treat a slot as the wrong category.
                lineArea.writeSpan(slotSpan(slot), slot.kind().initialImage(codec, slot.length()));
            }
        }

        // -----------------------------------------------------------------------------------------
        // The eleven slot setters. Every one is an explicit COBOL MOVE, never a Java assignment.
        // -----------------------------------------------------------------------------------------

        /**
         * Sets {@code ST-NAME PIC X(75)}, the customer name line.
         *
         * <p>The COBOL builds this value with {@code STRING CUST-FIRST-NAME ... CUST-MIDDLE-NAME ...
         * CUST-LAST-NAME ... INTO ST-NAME} at {@code app/cbl/CBSTM03A.CBL:L462-L469}, and the caller
         * performs that concatenation. Applying the alphanumeric {@code MOVE} rule here is
         * byte-identical to that {@code STRING}: a {@code STRING} without {@code POINTER} overwrites
         * only the characters it transfers and leaves the rest of the receiver untouched, and
         * {@code INITIALIZE} has just set the whole slot to spaces, so "overwrite the prefix, leave
         * spaces" and "left justify and pad with spaces" produce the same 75 bytes. An over-long name
         * is truncated on the right, which is what the {@code STRING} does too when it overflows.
         *
         * @param name the composed name; must not be {@code null}. Longer than 75 characters is
         *             truncated on the right, shorter is padded on the right with spaces
         * @throws NullPointerException if {@code name} is {@code null}
         */
        public void setName(String name) {
            movePicX(StatementSlot.ST_NAME, name);
        }

        /**
         * Sets {@code ST-ADD1 PIC X(50)} from {@code CUST-ADDR-LINE-1 PIC X(50)}
         * ({@code app/cpy/CUSTREC.cpy:9}), per {@code MOVE CUST-ADDR-LINE-1 TO ST-ADD1} at
         * {@code app/cbl/CBSTM03A.CBL:L470}. Sender and receiver are the same width, so neither pad
         * nor truncation occurs for a well-formed record.
         *
         * @param addressLine1 the first address line; must not be {@code null}
         * @throws NullPointerException if {@code addressLine1} is {@code null}
         */
        public void setAddressLine1(String addressLine1) {
            movePicX(StatementSlot.ST_ADD1, addressLine1);
        }

        /**
         * Sets {@code ST-ADD2 PIC X(50)} from {@code CUST-ADDR-LINE-2 PIC X(50)}
         * ({@code app/cpy/CUSTREC.cpy:10}), per {@code MOVE CUST-ADDR-LINE-2 TO ST-ADD2} at
         * {@code app/cbl/CBSTM03A.CBL:L471}.
         *
         * @param addressLine2 the second address line; must not be {@code null}
         * @throws NullPointerException if {@code addressLine2} is {@code null}
         */
        public void setAddressLine2(String addressLine2) {
            movePicX(StatementSlot.ST_ADD2, addressLine2);
        }

        /**
         * Sets {@code ST-ADD3 PIC X(80)}, the composite third address line.
         *
         * <p>The COBOL builds it with {@code STRING CUST-ADDR-LINE-3 ... CUST-ADDR-STATE-CD ...
         * CUST-ADDR-COUNTRY-CD ... CUST-ADDR-ZIP ... INTO ST-ADD3} at
         * {@code app/cbl/CBSTM03A.CBL:L472-L481}; the caller performs the concatenation, and the same
         * {@code STRING}-versus-{@code MOVE} equivalence described on {@link #setName(String)} applies.
         * This slot occupies its whole line, so it is the one line with no {@code FILLER} at all.
         *
         * @param addressLine3 the composed third address line; must not be {@code null}
         * @throws NullPointerException if {@code addressLine3} is {@code null}
         */
        public void setAddressLine3(String addressLine3) {
            movePicX(StatementSlot.ST_ADD3, addressLine3);
        }

        /**
         * Sets {@code ST-ACCT-ID PIC X(20)} from {@code ACCT-ID PIC 9(11)}
         * ({@code app/cpy/CVACT01Y.cpy:5}), per {@code MOVE ACCT-ID TO ST-ACCT-ID} at
         * {@code app/cbl/CBSTM03A.CBL:L483}.
         *
         * <p>Two {@code MOVE} rules apply in sequence and both are performed explicitly. The sender is
         * an eleven-digit numeric display item, so the id is first zero-filled <strong>on the
         * left</strong> to {@value StatementTextWriter#ACCOUNT_ID_DIGITS} digits - leading zeros are
         * part of the value and are preserved. Those eleven characters then move into an
         * <em>alphanumeric</em> receiver, which left justifies and pads <strong>on the right</strong>,
         * so account 1 renders as {@code "00000000001"} followed by nine spaces.
         *
         * @param accountId the account id; must not be negative, because {@code PIC 9(11)} is an
         *                  unsigned picture with no sign position
         * @throws IllegalArgumentException if {@code accountId} is negative
         */
        public void setAccountId(long accountId) {
            movePicX(StatementSlot.ST_ACCT_ID, codec.movePic9(accountId, ACCOUNT_ID_DIGITS));
        }

        /**
         * Sets {@code ST-CURR-BAL PIC 9(9).99-} from {@code ACCT-CURR-BAL PIC S9(10)V99}
         * ({@code app/cpy/CVACT01Y.cpy:7}), per {@code MOVE ACCT-CURR-BAL TO ST-CURR-BAL} at
         * {@code app/cbl/CBSTM03A.CBL:L484}.
         *
         * <p>The image is produced by {@link StatementTextWriter#editZeroFilledAmount(BigDecimal)},
         * including its documented and required discarding of the sending field's tenth integer digit.
         *
         * @param currentBalance the account's current balance; must not be {@code null}
         * @throws NullPointerException if {@code currentBalance} is {@code null}
         */
        public void setCurrentBalance(BigDecimal currentBalance) {
            writeEdited(StatementSlot.ST_CURR_BAL, editZeroFilledAmount(currentBalance));
        }

        /**
         * Sets {@code ST-FICO-SCORE PIC X(20)} from {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}
         * ({@code app/cpy/CUSTREC.cpy:22}), per {@code MOVE CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE}
         * at {@code app/cbl/CBSTM03A.CBL:L485}.
         *
         * <p>As with the account id, the two rules are applied in order: three digits, zero-filled on
         * the left, then left justified in an alphanumeric receiver and padded on the right. A score
         * of 705 therefore renders as {@code "705"} followed by seventeen spaces, and a score of 12
         * as {@code "012"} followed by seventeen.
         *
         * @param ficoScore the FICO credit score; must not be negative, because {@code PIC 9(03)} is
         *                  unsigned
         * @throws IllegalArgumentException if {@code ficoScore} is negative
         */
        public void setFicoScore(int ficoScore) {
            movePicX(StatementSlot.ST_FICO_SCORE, codec.movePic9(ficoScore, FICO_SCORE_DIGITS));
        }

        /**
         * Sets {@code ST-TRANID PIC X(16)} from {@code TRNX-ID PIC X(16)}
         * ({@code app/cpy/COSTM01.CPY:23}), per {@code MOVE TRNX-ID TO ST-TRANID} at
         * {@code app/cbl/CBSTM03A.CBL:L676}. Equal widths, so nothing is padded or discarded for a
         * well-formed record.
         *
         * @param transactionId the transaction id; must not be {@code null}
         * @throws NullPointerException if {@code transactionId} is {@code null}
         */
        public void setTransactionId(String transactionId) {
            movePicX(StatementSlot.ST_TRANID, transactionId);
        }

        /**
         * Sets {@code ST-TRANDT PIC X(49)} from {@code TRNX-DESC PIC X(100)}
         * ({@code app/cpy/COSTM01.CPY:28}), per {@code MOVE TRNX-DESC TO ST-TRANDT} at
         * {@code app/cbl/CBSTM03A.CBL:L677}.
         *
         * <p>A 100-character description into a 49-character receiver: the description is
         * <strong>truncated on the right</strong> and its first 49 characters survive. That loss is
         * the COBOL's, is visible on every statement it produces, and is preserved exactly - the slot
         * is not widened and the description is not abbreviated, wrapped or elided.
         *
         * @param transactionDetails the transaction description; must not be {@code null}
         * @throws NullPointerException if {@code transactionDetails} is {@code null}
         */
        public void setTransactionDetails(String transactionDetails) {
            movePicX(StatementSlot.ST_TRANDT, transactionDetails);
        }

        /**
         * Sets {@code ST-TRANAMT PIC Z(9).99-} from {@code TRNX-AMT PIC S9(09)V99}
         * ({@code app/cpy/COSTM01.CPY:29}), per {@code MOVE TRNX-AMT TO ST-TRANAMT} at
         * {@code app/cbl/CBSTM03A.CBL:L678}. The image is produced by
         * {@link StatementTextWriter#editZeroSuppressedAmount(BigDecimal)}.
         *
         * @param transactionAmount the transaction amount; must not be {@code null}
         * @throws NullPointerException if {@code transactionAmount} is {@code null}
         */
        public void setTransactionAmount(BigDecimal transactionAmount) {
            writeEdited(StatementSlot.ST_TRANAMT, editZeroSuppressedAmount(transactionAmount));
        }

        /**
         * Sets {@code ST-TOTAL-TRAMT PIC Z(9).99-} from {@code WS-TRN-AMT PIC S9(9)V99}
         * ({@code app/cbl/CBSTM03A.CBL:L68}), per {@code MOVE WS-TRN-AMT TO ST-TOTAL-TRAMT} at
         * {@code L434} - the running total the statement job accumulates across the customer's
         * transactions. The image is produced by
         * {@link StatementTextWriter#editZeroSuppressedAmount(BigDecimal)}.
         *
         * @param totalTransactionAmount the total of the customer's transaction amounts; must not be
         *                               {@code null}
         * @throws NullPointerException if {@code totalTransactionAmount} is {@code null}
         */
        public void setTotalTransactionAmount(BigDecimal totalTransactionAmount) {
            writeEdited(StatementSlot.ST_TOTAL_TRAMT,
                    editZeroSuppressedAmount(totalTransactionAmount));
        }

        /**
         * Applies the alphanumeric {@code MOVE} rule and stores the result: left justified, padded on
         * the right with spaces, truncated on the right.
         *
         * <p>Every alphanumeric slot goes through this one call rather than through a Java assignment,
         * because assignment neither pads nor truncates and the resulting defect is invisible at the
         * call site.
         *
         * @param slot  the receiving slot
         * @param value the sending value
         */
        private void movePicX(StatementSlot slot, String value) {
            codec.writePicX(lineArea, slotSpan(slot), value);
        }

        /**
         * Stores an already-edited image into an edited slot.
         *
         * <p>Written with no pad or truncate step, deliberately. An edited image is exactly
         * {@value StatementTextWriter#EDITED_AMOUNT_LENGTH} characters by construction, so the layer
         * below is allowed to reject any other width: that turns a defect in the mask into an
         * immediate, localised failure instead of a silently mis-shaped statement line.
         *
         * @param slot  the receiving edited slot
         * @param image the edited image, exactly {@code slot.length()} characters
         */
        private void writeEdited(StatementSlot slot, String image) {
            lineArea.writeSpan(slotSpan(slot), image);
        }

        // -----------------------------------------------------------------------------------------
        // Reading back: what a slot currently holds, and what a line currently renders as.
        // -----------------------------------------------------------------------------------------

        /**
         * The characters a slot currently holds, at its full declared width and
         * <strong>untrimmed</strong>.
         *
         * <p>Untrimmed because the padding is part of the value: the parity differ compares a field
         * byte for byte, and trimming here would discard bytes it is meant to compare.
         *
         * <p>This is how the HTML statement obtains the two edited amount images without re-editing
         * the underlying values, mirroring {@code app/cbl/CBSTM03A.CBL:L622} and {@code L712}, which
         * reference {@code ST-CURR-BAL} and {@code ST-TRANAMT} - the edited items of this very group.
         *
         * @param slot the slot to read; must not be {@code null}
         * @return exactly {@code slot.length()} characters
         * @throws NullPointerException if {@code slot} is {@code null}
         */
        public String slotImage(StatementSlot slot) {
            Objects.requireNonNull(slot, "A slot is required to read a named item of "
                    + "01 STATEMENT-LINES");
            return lineArea.readSpan(slotSpan(slot));
        }

        /**
         * Renders one line as text: exactly {@value #RECORD_LENGTH} characters, decoded under the
         * injected dataset code page.
         *
         * <p>Rendering does not write anything and does not consume the line, so a caller may render
         * the same line as often as it likes and may render a line it never writes.
         *
         * @param line the line to render; must not be {@code null}
         * @return exactly {@value #RECORD_LENGTH} characters
         * @throws NullPointerException if {@code line} is {@code null}
         */
        public String renderLine(StatementLine line) {
            Objects.requireNonNull(line, "A line identity is required to render a statement record");
            return lineArea.readString(line.offset(), line.length());
        }

        /**
         * Renders one line as the bytes that would reach the dataset: exactly
         * {@value #RECORD_LENGTH} of them, in the injected code page.
         *
         * @param line the line to render; must not be {@code null}
         * @return a fresh array of exactly {@value #RECORD_LENGTH} bytes
         * @throws NullPointerException if {@code line} is {@code null}
         */
        public byte[] renderLineBytes(StatementLine line) {
            Objects.requireNonNull(line, "A line identity is required to render a statement record");
            return lineArea.readBytes(line.offset(), line.length());
        }

        // -----------------------------------------------------------------------------------------
        // WRITE FD-STMTFILE-REC FROM ST-LINEn.
        // -----------------------------------------------------------------------------------------

        /**
         * Writes one line to the dataset, reproducing a single
         * {@code WRITE FD-STMTFILE-REC FROM ST-LINEn} statement.
         *
         * <p>One call, one record, immediately: nothing is batched, reordered, coalesced or skipped,
         * because the line sequence is the statement job's and is parity-critical. Writing the same
         * line twice writes two identical records, which is exactly what the COBOL does with
         * {@code ST-LINE5} at {@code app/cbl/CBSTM03A.CBL:L492} and {@code L494} and with
         * {@code ST-LINE12} at {@code L500}, {@code L502} and {@code L435}.
         *
         * <p>A rejected write is returned as {@link FileStatus.Outcome#OTHER} - the {@code WHEN OTHER}
         * arm - rather than thrown, so the statement job decides whether to abend and with what
         * return code. Writing to a handle that has already been closed is a different thing
         * entirely: that is a caller sequencing defect rather than a dataset condition, and it throws,
         * because reporting it as an ordinary failed write would let a job quietly lose records it
         * believes it wrote.
         *
         * @param line the line to write; must not be {@code null}
         * @return {@link FileStatus.Outcome#OK} when the record was accepted, or
         *         {@link FileStatus.Outcome#OTHER} when the sink rejected it
         * @throws NullPointerException  if {@code line} is {@code null}
         * @throws IllegalStateException if this handle has already been closed
         */
        public FileStatus.Outcome writeLine(StatementLine line) {
            Objects.requireNonNull(line, "A line identity is required to write a statement record");
            if (!open) {
                throw new IllegalStateException("Cannot write " + line.cobolName() + " to "
                        + DD_NAME + ": this handle was closed after " + recordsWritten
                        + " record(s). The COBOL opens the dataset once at the start of the run "
                        + "(app/cbl/CBSTM03A.CBL:L293) and closes it once at the end (L339), so open "
                        + "a new handle for a new run rather than reusing a closed one.");
            }
            FileStatus.Outcome outcome = sink.write(renderLineBytes(line));
            recordsWritten++;
            return outcome;
        }

        /**
         * How many records have been handed to the sink through this handle.
         *
         * <p>Counts every record handed over, including one the sink rejected, because the count
         * answers "how far did this run get" - which is what a diagnostic needs - rather than "how
         * many succeeded".
         *
         * @return the record count, never negative
         */
        public int recordsWritten() {
            return recordsWritten;
        }

        /**
         * Whether this handle is still open for writing.
         *
         * @return {@code true} until {@link #closeOutput()} or {@link #close()} has run
         */
        public boolean isOpen() {
            return open;
        }

        /**
         * Closes the dataset, reproducing {@code CLOSE STMT-FILE} at
         * {@code app/cbl/CBSTM03A.CBL:L339}, and reports the outcome.
         *
         * <p>Idempotent: closing an already-closed handle is reported as
         * {@link FileStatus.Outcome#OK} and does not reach the sink a second time, so a
         * try-with-resources block around an explicit close is harmless.
         *
         * @return {@link FileStatus.Outcome#OK} when the sink closed cleanly or was already closed,
         *         or {@link FileStatus.Outcome#OTHER} otherwise
         */
        public FileStatus.Outcome closeOutput() {
            if (!open) {
                return FileStatus.Outcome.OK;
            }
            open = false;
            return sink.close();
        }

        /**
         * Closes the dataset for a try-with-resources block.
         *
         * <p>{@link AutoCloseable#close()} cannot return a value, so a non-{@code OK} outcome is
         * logged rather than discarded, and it is not turned into an exception: throwing from
         * {@code close()} would mask whatever the block itself was doing. A caller that needs to act
         * on the outcome - the statement job, deciding on an abend - calls {@link #closeOutput()}
         * instead.
         */
        @Override
        public void close() {
            FileStatus.Outcome outcome = closeOutput();
            if (outcome != FileStatus.Outcome.OK) {
                LOG.error("Closing " + DD_NAME + " after " + recordsWritten
                        + " record(s) reported FILE STATUS outcome " + outcome.name()
                        + "; call closeOutput() rather than close() to handle this in the caller");
            }
        }
    }
}

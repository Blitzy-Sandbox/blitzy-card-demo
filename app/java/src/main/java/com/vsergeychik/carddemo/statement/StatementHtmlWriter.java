package com.vsergeychik.carddemo.statement;

import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The Java owner of the <strong>100-byte {@code HTMLFILE}</strong> statement record written by
 * {@code app/cbl/CBSTM03A.CBL}.
 *
 * <p>This class is a like-for-like translation, not a redesign. Every byte it emits is the byte
 * {@code CBSTM03A} emits. The HTML text below is <em>data</em> transcribed from a COBOL
 * {@code WORKING-STORAGE} declaration - it is not markup this class authored, and it is not this
 * class's business to tidy, reformat, minify, escape or validate it.
 *
 * <h2>The record is 100 bytes, NOT 80 (gate G20, risk R-G)</h2>
 *
 * <p>{@code app/jcl/CREASTMT.JCL} declares the {@code HTMLFILE} DD <strong>twice, with two
 * different {@code LRECL}s</strong>, and the two disagree:
 *
 * <table border="1">
 *   <caption>The conflicting {@code HTMLFILE} DD declarations in {@code app/jcl/CREASTMT.JCL}</caption>
 *   <tr><th>Line</th><th>Step</th><th>Role</th><th>{@code DCB}</th></tr>
 *   <tr>
 *     <td>L69</td>
 *     <td>{@code STEP030 EXEC PGM=IEFBR14,COND=(0,NE)}</td>
 *     <td>Pre-delete of the previous run's report</td>
 *     <td>{@code DCB=(LRECL=80,BLKSIZE=3200,RECFM=FB)}</td>
 *   </tr>
 *   <tr>
 *     <td>L94</td>
 *     <td>{@code STEP040 EXEC PGM=CBSTM03A,COND=(0,NE)}</td>
 *     <td><strong>Creates</strong> the report</td>
 *     <td><strong>{@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)}</strong></td>
 *   </tr>
 * </table>
 *
 * <p><strong>The creating step wins, so this record is 100 bytes.</strong> The conflict is
 * <em>recorded</em> here and deliberately <em>not reconciled</em>: the two declarations are left
 * exactly as the JCL states them, no average or first-wins rule is applied, and 80 is never used
 * (binding practice B4, "document conflicts, never silently fix them").
 *
 * <p>The COBOL corroborates the creating step independently, which settles the matter beyond doubt.
 * {@code app/cbl/CBSTM03A.CBL:L46-L47} declares:
 *
 * <pre>
 *   FD  HTML-FILE.
 *   01  FD-HTMLFILE-REC         PIC X(100).
 * </pre>
 *
 * <p>{@code app/java/src/main/resources/application.yml} states the same width once more, as the
 * single auditable width source, and this class <strong>cross-checks it and fails fast</strong> if
 * a deployment ever declares anything else - see the constructor. That guard is the mechanical
 * enforcement of gate G20.
 *
 * <p>Two further reading traps in the same JCL, noted so a future reader does not chase them:
 * <ul>
 *   <li>{@code CREASTMT.JCL:L90} is <strong>genuinely corrupted</strong> - it reads
 *       {@code //         SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS}, a copy/paste
 *       artefact. It belongs to the {@code STMTFILE} DD, not to this one, and L89 is authoritative
 *       there. It has no bearing on {@code HTMLFILE} and is ignored.</li>
 *   <li>{@link #BLOCK_SIZE} is carried as documentation only. Blocking is a physical dataset
 *       attribute with no Java counterpart, so it is never validated and never used to compose a
 *       record; a site that reblocks the dataset must not be refused startup for it.</li>
 * </ul>
 *
 * <h2>No HTML escaping. None. Anywhere.</h2>
 *
 * <p>The statement embeds customer data in the clear - full name, three address lines, account
 * identifier, current balance, FICO score, and every transaction identifier, description and
 * amount - and {@code CBSTM03A} concatenates all of it raw. There is no escaping, no entity
 * encoding, no sanitisation and no masking in the COBOL, so there is none here either. If a
 * customer name contained {@code <}, the COBOL would emit {@code <}; so does this class. Adding
 * output encoding is the single most likely well-intentioned way to break parity in this file:
 * it would change bytes, and every changed byte is a diff (practice B6, "security posture neither
 * weakened nor unrequestedly strengthened"). The exposure is an inherited property of the legacy
 * design, recorded here so it stays visible rather than buried.
 *
 * <h2>{@code STRING ... DELIMITED BY}: three distinct operations, easily confused</h2>
 *
 * <p>Ten of this class's records are built with COBOL's {@code STRING} statement, and its semantics
 * are reproduced explicitly by hand rather than approximated (practice B11). Three rules matter:
 *
 * <ol>
 *   <li><b>{@code DELIMITED BY '  '} is a two-space delimiter, not a trim.</b> It transfers the
 *       characters of the sending item up to but not including the first occurrence of <em>two
 *       consecutive spaces</em>. So {@code "AL  SMITH"} yields {@code "AL"}, while {@code "ALSMITH"}
 *       - which contains no two-space run - yields {@code "ALSMITH"} in full. A trim would give
 *       {@code "AL  SMITH"} for the first case, which is a different program.</li>
 *   <li><b>{@code DELIMITED BY '*'} on an asterisk-free item transfers everything</b>, trailing
 *       spaces included. The three basic-detail lines and the three transaction lines depend on
 *       exactly that: the whole fixed-width field arrives, padding and all, and nothing is
 *       trimmed.</li>
 *   <li><b>{@code STRING} does not blank its receiver.</b> It overlays from the receiver's leftmost
 *       character position and leaves everything beyond the last transferred character untouched.
 *       That is precisely why every site in {@code CBSTM03A} is preceded by an explicit
 *       {@code MOVE SPACES}, and why every method here reproduces that clear rather than relying on
 *       a freshly allocated buffer.</li>
 * </ol>
 *
 * <h2>{@code WRITE ... FROM} is a {@code MOVE} followed by a write</h2>
 *
 * <p>{@code WRITE FD-HTMLFILE-REC FROM HTML-ADDR-LN} moves the sending group into the record area
 * under the alphanumeric {@code MOVE} rule - right-padded when short, right-truncated when long -
 * and then writes the record area. The record area is therefore left holding what was written, and
 * that is modelled faithfully: {@link #writeFrom} performs the move into
 * {@link HtmlStatementFile#recordArea()} and emits from there. The one exception is the name line at
 * {@code CBSTM03A.CBL:L568}, which is a bare {@code WRITE FD-HTMLFILE-REC} with <em>no</em>
 * {@code FROM} clause because its {@code STRING} composed straight into the record area; that path
 * emits without an intervening move.
 *
 * <h2>The numeric edit masks are deliberately not implemented here</h2>
 *
 * <p>{@code ST-CURR-BAL} ({@code app/cbl/CBSTM03A.CBL:L113}) and {@code ST-TRANAMT} ({@code L137})
 * are <em>numeric-edited</em> items - read their {@code PICTURE} clauses from the source rather than
 * from a restated copy here, which could drift - belonging to {@code CBSTM03A}'s
 * {@code STATEMENT-LINES} group, the line layouts of the <em>plain-text</em> statement. The
 * {@code STRING} statements reference those items <em>after</em> the edit has been applied, so this
 * class receives the rendered 13-character images and embeds them verbatim. It accepts no
 * decimal-typed amount, performs no rounding, and contains no edit mask and no rounding mode of any
 * kind. The masks therefore stay implemented exactly once, in {@code StatementTextWriter} where they
 * belong, and gate G24 has exactly one place to be verified.
 *
 * <h2>Statelessness, and where the mutable bytes live</h2>
 *
 * <p>This is a stateless singleton. Its four fields are immutable collaborators supplied by
 * constructor injection, and it declares <strong>no mutable static state whatsoever</strong> (gate
 * G53). Every mutable byte - the {@code FD-HTMLFILE-REC} record area, the {@code HTML-FIXED-LN}
 * staging line and the three {@code HTML-ADDR-LN} / {@code HTML-BSIC-LN} / {@code HTML-TRAN-LN}
 * scratch buffers declared at {@code CBSTM03A.CBL:L221-L223} - lives in a per-execution
 * {@link HtmlStatementFile} handle that the caller obtains from {@link #open} and hands back to
 * every emit call. Two statement runs can therefore proceed independently, and a test can hold a
 * handle without disturbing any other.
 *
 * <p>The literal catalogue in {@link HtmlFixedLine} is {@code static final} and that is correct:
 * it is immutable constant data - the COBOL's own {@code 88}-level {@code VALUE} clauses - not
 * state.
 *
 * <h2>This class encodes no write order</h2>
 *
 * <p>Each method emits exactly <strong>one</strong> record. There is no convenience method that
 * emits several, and no sequence is embedded anywhere. The order in which the 100-byte records
 * appear is {@code CBSTM03A}'s paragraph structure - {@code 5100-WRITE-HTML-HEADER},
 * {@code 5200-WRITE-HTML-NMADBS}, {@code 6000-WRITE-TRANS} and {@code 4000-TRNXFILE-GET} - and it
 * belongs to {@code StatementGenerationJobA}, which owns that control flow. Emission is strictly in
 * call order: nothing is buffered in a way that could reorder, and nothing is deduplicated.
 *
 * <h2>Errors are outcomes, not decisions</h2>
 *
 * <p>Every emit method returns a {@link FileStatus.Outcome}. This class never abends: it reports
 * what happened and lets {@code StatementGenerationJobA} decide, exactly as {@code CBSTM03A} keeps
 * its {@code CALL 'CEE3ABD'} in the caller rather than in the file handler. {@code FileStatus}
 * deliberately does not depend on {@code AbendException}, and neither does this class.
 *
 * <h2>User-specified rules</h2>
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - and that single
 * line is the entire document, so <strong>no user rule governs this file</strong>. Its absence is
 * not licence to lower the bar. The twelve enterprise practices of the migration plan bind in their
 * place, and the ones bearing on this class are cited inline above and below: B1 (no dependency,
 * coordinate or version added - only Java 21, the Spring Boot 3.5.16 APIs already on the classpath,
 * and this module's own {@code common} and {@code config} packages), B3 (the reference trees
 * {@code app/cbl}, {@code app/cpy}, {@code app/jcl}, {@code app/csd} and {@code app/data} are
 * read-only evidence and are never written), B4 (the {@code LRECL} conflict is documented, not
 * reconciled), B6 (no escaping, no masking, no redaction), B7 (deterministic - no clock, no locale,
 * no default charset, no filesystem ordering), B8 (explicit over implicit - the charset is always
 * named and injected, imports are explicit with no wildcard, and the dataset name is resolved from
 * configuration so no mainframe dataset-name literal appears in Java), B9 (no static mutable
 * state; constructor injection only), B10 (every method is callable from a plain JUnit 5 test with
 * no application context, and the sink is injectable so records are captured in memory), B11
 * (hand-written, reviewable composition by absolute offset - no COBOL parser, no templating engine)
 * and B12 (the JDBC driver is a deployment-time input, recorded rather than absorbed). Rule R5
 * (fixed-width is the wire format) and rule R7 (structured control flow preserving evaluation
 * order) apply throughout.
 *
 * @see HtmlFixedLine
 * @see HtmlStatementFile
 * @see HtmlRecordSink
 */
@Component
public final class StatementHtmlWriter {

    // =============================================================================================
    // Dataset geometry. RECORD_LENGTH is the single highest-value fact in this file.
    // =============================================================================================

    /**
     * The DD name under which {@code app/jcl/CREASTMT.JCL} and {@code app/cbl/CBSTM03A.CBL} address
     * the HTML statement dataset, and therefore the key this class resolves from the
     * {@code carddemo.datasets} configuration catalogue.
     *
     * <p>{@code CBSTM03A.CBL:L40} declares {@code SELECT HTML-FILE ASSIGN TO HTMLFILE}. The DD name
     * is carried verbatim, in the upper case the JCL and the configuration both use, so a reviewer
     * can diff configuration against JCL line by line. The dataset <em>name</em> behind it never
     * appears in Java (gate G46).
     */
    public static final String HTMLFILE_DD_NAME = "HTMLFILE";

    /**
     * The record width in bytes: <strong>100</strong>.
     *
     * <p>Established twice over - by the creating JCL step at {@code app/jcl/CREASTMT.JCL:L94}
     * ({@code LRECL=100}) and by the COBOL file description at {@code app/cbl/CBSTM03A.CBL:L47}
     * ({@code 01 FD-HTMLFILE-REC PIC X(100)}). The pre-delete step's {@code LRECL=80} at L69 is
     * <em>not</em> authoritative; see this class's documentation for the full conflict record (gate
     * G20, risk R-G).
     *
     * <p>Every record this class emits is exactly this many bytes. Shorter content is right-space
     * padded; nothing is ever trimmed.
     */
    public static final int RECORD_LENGTH = 100;

    /**
     * The block size the creating JCL step declares: {@code BLKSIZE=800}
     * ({@code app/jcl/CREASTMT.JCL:L94}).
     *
     * <p><strong>Documentation only.</strong> Blocking is a physical dataset attribute with no Java
     * counterpart: it affects how many records share a physical block, never the content of a
     * record. It is therefore neither validated nor consulted anywhere in this class. It is
     * declared because a reader comparing this class against the JCL will look for it, and because
     * the pre-delete step's conflicting {@code BLKSIZE=3200} would otherwise be the only block size
     * a reader ever saw.
     */
    public static final int BLOCK_SIZE = 800;

    // =============================================================================================
    // The COBOL names of the record area and the four WORKING-STORAGE lines, carried verbatim.
    // =============================================================================================

    /** {@code app/cbl/CBSTM03A.CBL:L47} - {@code 01 FD-HTMLFILE-REC PIC X(100)}, the record area. */
    public static final String RECORD_AREA_NAME = "FD-HTMLFILE-REC";

    /**
     * {@code app/cbl/CBSTM03A.CBL:L149} - {@code 05 HTML-FIXED-LN PIC X(100)}, the field the
     * {@code 88}-level literals are {@code SET} into before {@code WRITE ... FROM HTML-FIXED-LN}.
     */
    public static final String FIXED_LINE_NAME = "HTML-FIXED-LN";

    /** {@code app/cbl/CBSTM03A.CBL:L221} - {@code 05 HTML-ADDR-LN PIC X(100)}. */
    public static final String ADDRESS_LINE_NAME = "HTML-ADDR-LN";

    /** {@code app/cbl/CBSTM03A.CBL:L222} - {@code 05 HTML-BSIC-LN PIC X(100)}. */
    public static final String BASIC_LINE_NAME = "HTML-BSIC-LN";

    /** {@code app/cbl/CBSTM03A.CBL:L223} - {@code 05 HTML-TRAN-LN PIC X(100)}. */
    public static final String TRANSACTION_LINE_NAME = "HTML-TRAN-LN";

    // =============================================================================================
    // STRING delimiters and the shared markup literals, from app/cbl/CBSTM03A.CBL:L560-L716.
    // =============================================================================================

    /**
     * The two-space delimiter of {@code DELIMITED BY '  '}
     * ({@code app/cbl/CBSTM03A.CBL:L563}, {@code L571}, {@code L579}, {@code L587}).
     *
     * <p>Two characters, both spaces. It is a delimiter, not a trim: see rule 1 in this class's
     * documentation.
     */
    public static final String TWO_SPACE_DELIMITER = "  ";

    /**
     * The delimiter of {@code DELIMITED BY '*'}, used on every literal operand and on the six
     * fixed-width value operands of the basic-detail and transaction lines.
     *
     * <p>No sending item at any of those sites contains an asterisk, so the effect is to transfer
     * the operand in full - trailing spaces and all. That is the intended reading, and it is why
     * those six values must never be trimmed.
     */
    public static final String ASTERISK_DELIMITER = "*";

    /**
     * The two literal spaces transferred {@code DELIMITED BY SIZE} immediately after the name and
     * after each address ({@code app/cbl/CBSTM03A.CBL:L564}, {@code L572}, {@code L580},
     * {@code L588}).
     *
     * <p>Textually identical to {@link #TWO_SPACE_DELIMITER} and semantically unrelated: this one is
     * <em>content</em> being sent, that one is a <em>delimiter</em> being searched for. They are
     * declared separately so a future edit to one cannot silently change the other.
     */
    public static final String TWO_SPACE_SEPARATOR = "  ";

    /**
     * {@code '<p>'} - the plain paragraph open tag that begins each address line
     * ({@code app/cbl/CBSTM03A.CBL:L570}, {@code L578}, {@code L586}) and each transaction line
     * ({@code L687}, {@code L699}, {@code L711}).
     */
    public static final String PARAGRAPH_OPEN_TAG = "<p>";

    /**
     * {@code '</p>'} - the paragraph close tag that ends the name line, the three address lines,
     * the three basic-detail lines and the three transaction lines.
     */
    public static final String PARAGRAPH_CLOSE_TAG = "</p>";

    /**
     * {@code '<p style="font-size:16px">'} - 26 characters.
     *
     * <p>Declared twice in the COBOL, with the same content, and used for two different purposes:
     * as the {@code FILLER PIC X(26) VALUE} of the {@code HTML-L23} group
     * ({@code app/cbl/CBSTM03A.CBL:L218-L219}) and as the first {@code STRING} operand of the name
     * line ({@code L562}). One constant serves both, so the two cannot drift apart.
     */
    public static final String STYLED_PARAGRAPH_OPEN_TAG = "<p style=\"font-size:16px\">";

    // =============================================================================================
    // HTML-L11 - the account-number heading group, app/cbl/CBSTM03A.CBL:L212-L216.
    //
    //   05  HTML-L11.
    //       10  FILLER   PIC X(34) VALUE '<h3>Statement for Account Number: '.
    //       10  L11-ACCT PIC X(20).
    //       10  FILLER   PIC X(05) VALUE '</h3>'.
    //
    // 34 + 20 + 5 = 59 bytes, right-space padded to 100 by WRITE ... FROM HTML-L11 at L530.
    // =============================================================================================

    /**
     * The 34-character {@code FILLER VALUE} that opens the {@code HTML-L11} group
     * ({@code app/cbl/CBSTM03A.CBL:L213-L214}).
     *
     * <p>The declared literal is exactly 34 characters and <strong>ends with a space</strong>. That
     * trailing space is inside the literal, is what separates the colon from the account number,
     * and is therefore part of the byte contract.
     */
    public static final String ACCOUNT_HEADING_PREFIX = "<h3>Statement for Account Number: ";

    /** The 5-character {@code FILLER VALUE} that closes {@code HTML-L11}: {@code '</h3>'}. */
    public static final String ACCOUNT_HEADING_SUFFIX = "</h3>";

    /** The COBOL name of the account-number field inside the group: {@code L11-ACCT}. */
    public static final String L11_ACCT_FIELD_NAME = "L11-ACCT";

    /** Declared width of {@code L11-ACCT PIC X(20)} ({@code app/cbl/CBSTM03A.CBL:L215}). */
    public static final int L11_ACCT_LENGTH = 20;

    /**
     * The total declared width of the {@code HTML-L11} group: {@code 34 + 20 + 5 = 59} bytes.
     *
     * <p>Shorter than {@link #RECORD_LENGTH}, so {@code WRITE FD-HTMLFILE-REC FROM HTML-L11} pads it
     * on the right to 100.
     */
    public static final int HTML_L11_LENGTH = 59;

    /**
     * The {@code HTML-L11} layout, span by span, at absolute offsets: {@code FILLER} 0-33 carrying
     * its declared literal, {@code L11-ACCT} 34-53, {@code FILLER} 54-58 carrying {@code '</h3>'}.
     *
     * <p>Both {@code FILLER}s are first-class positioned spans that emit their declared literals,
     * exactly as the copybook-style declaration states. {@link RecordLayout} refuses to be
     * constructed unless the spans are contiguous from offset 0 and total exactly
     * {@link #HTML_L11_LENGTH}, which turns a dropped {@code FILLER} from a silent byte shift into
     * an immediate failure.
     */
    public static final RecordLayout HTML_L11_LAYOUT = RecordLayout.of(HTML_L11_LENGTH,
            FieldSpan.filler(0, ACCOUNT_HEADING_PREFIX.length(), ACCOUNT_HEADING_PREFIX),
            FieldSpan.alphanumeric(L11_ACCT_FIELD_NAME, ACCOUNT_HEADING_PREFIX.length(),
                    L11_ACCT_LENGTH),
            FieldSpan.filler(ACCOUNT_HEADING_PREFIX.length() + L11_ACCT_LENGTH,
                    ACCOUNT_HEADING_SUFFIX.length(), ACCOUNT_HEADING_SUFFIX));

    // =============================================================================================
    // HTML-L23 - the name paragraph group, app/cbl/CBSTM03A.CBL:L217-L220.
    //
    //   05  HTML-L23.
    //       10  FILLER   PIC X(26) VALUE '<p style="font-size:16px>"'.
    //       10  L23-NAME PIC X(50).
    //
    // 26 + 50 = 76 bytes. DECLARED BUT NEVER WRITTEN: 5200-WRITE-HTML-NMADBS builds the equivalent
    // line with a STRING into the record area instead (L560-L568). The group is modelled here all
    // the same, because a declared-but-unused structure is preserved rather than deleted (B5); only
    // L23-NAME itself is live, as the receiver of MOVE ST-NAME TO L23-NAME at L560.
    // =============================================================================================

    /**
     * Declared width of {@code L23-NAME PIC X(50)} ({@code app/cbl/CBSTM03A.CBL:L220}).
     *
     * <p>This is the width that matters most in the name line, and it is <em>not</em> the width of
     * the sending field. {@code ST-NAME} is {@code PIC X(75)} ({@code L91}), and
     * {@code MOVE ST-NAME TO L23-NAME} at {@code L560} truncates it on the right to 50 characters
     * before the {@code STRING} ever sees it. A name longer than 50 characters therefore loses its
     * tail in the HTML statement while keeping it in the plain-text one - an asymmetry of the legacy
     * program that is preserved exactly.
     */
    public static final int L23_NAME_LENGTH = 50;

    /** The COBOL name of the name field inside the group: {@code L23-NAME}. */
    public static final String L23_NAME_FIELD_NAME = "L23-NAME";

    /** The total declared width of the {@code HTML-L23} group: {@code 26 + 50 = 76} bytes. */
    public static final int HTML_L23_LENGTH = 76;

    /**
     * The {@code HTML-L23} layout: {@code FILLER} 0-25 carrying
     * {@link #STYLED_PARAGRAPH_OPEN_TAG}, {@code L23-NAME} 26-75.
     *
     * <p>Preserved because {@code CBSTM03A} declares it, not because anything writes it - see
     * {@link #composeNameParagraphGroup(String)} for the full explanation and for the one operation
     * that materialises it.
     */
    public static final RecordLayout HTML_L23_LAYOUT = RecordLayout.of(HTML_L23_LENGTH,
            FieldSpan.filler(0, STYLED_PARAGRAPH_OPEN_TAG.length(), STYLED_PARAGRAPH_OPEN_TAG),
            FieldSpan.alphanumeric(L23_NAME_FIELD_NAME, STYLED_PARAGRAPH_OPEN_TAG.length(),
                    L23_NAME_LENGTH));

    // =============================================================================================
    // Whole-line spans. Each names a 100-byte buffer so codec.stringIntoDelimitedBySize can overlay
    // into it, and each carries its COBOL identifier so a failure diagnostic names the right line.
    // =============================================================================================

    /** The whole {@code FD-HTMLFILE-REC} record area as one addressable 100-byte span. */
    private static final FieldSpan RECORD_AREA_SPAN =
            FieldSpan.alphanumeric(RECORD_AREA_NAME, 0, RECORD_LENGTH);

    /** The whole {@code HTML-ADDR-LN} scratch line as one addressable 100-byte span. */
    private static final FieldSpan ADDRESS_LINE_SPAN =
            FieldSpan.alphanumeric(ADDRESS_LINE_NAME, 0, RECORD_LENGTH);

    /** The whole {@code HTML-BSIC-LN} scratch line as one addressable 100-byte span. */
    private static final FieldSpan BASIC_LINE_SPAN =
            FieldSpan.alphanumeric(BASIC_LINE_NAME, 0, RECORD_LENGTH);

    /** The whole {@code HTML-TRAN-LN} scratch line as one addressable 100-byte span. */
    private static final FieldSpan TRANSACTION_LINE_SPAN =
            FieldSpan.alphanumeric(TRANSACTION_LINE_NAME, 0, RECORD_LENGTH);

    /** The {@code L11-ACCT} span inside {@link #HTML_L11_LAYOUT}, resolved once by name. */
    private static final FieldSpan L11_ACCT_SPAN = HTML_L11_LAYOUT.span(L11_ACCT_FIELD_NAME);

    /** The {@code L23-NAME} span inside {@link #HTML_L23_LAYOUT}, resolved once by name. */
    private static final FieldSpan L23_NAME_SPAN = HTML_L23_LAYOUT.span(L23_NAME_FIELD_NAME);

    /** Sentinel returned by {@link #delimiterPosition} when the delimiter does not occur. */
    private static final int DELIMITER_NOT_FOUND = -1;

    /**
     * The COBOL {@code FILE STATUS} for a permanent I/O error: {@code '30'}.
     *
     * <p>Declared here rather than on {@link FileStatus} because no COBOL program in
     * {@code app/cbl} tests for it - every batch program guards {@code '00'}, {@code '10'},
     * {@code '23'} or {@code '22'} and abends on anything else - so it is not part of the estate's
     * shared status vocabulary. {@link FileStatus#outcomeOfStatus(String)} classifies it as
     * {@link FileStatus.Outcome#OTHER}, which is exactly the {@code WHEN OTHER} arm those guard
     * chains treat as fatal, so a caller's control flow is unchanged.
     */
    public static final String PERMANENT_ERROR_STATUS = "30";

    /**
     * The characters a configured dataset name may contain if it is to be interpolated into the
     * default sink's statement.
     *
     * <p>Upper and lower case letters, digits, and the punctuation a mainframe dataset name and a
     * relative generation actually use: {@code . _ $ @ # - ( ) +}. Everything else - whitespace,
     * quotation marks, semicolons, comment markers, path separators - is refused. A path separator
     * being refused is deliberate and useful: the fixture-backed {@code test} profile binds
     * {@code HTMLFILE} to a filesystem location, so asking for the default sink under that profile
     * fails with a diagnostic telling the caller to inject a sink instead, which is what every test
     * and the parity harness do anyway.
     */
    private static final String DSNAME_ALLOWED_PUNCTUATION = "._$@#-()+";

    /**
     * The text preceding the dataset identifier in the default sink's statement.
     *
     * <p>Split from {@link #INSERT_STATEMENT_SUFFIX} so the identifier position is unmistakable and
     * so no statement text is written inline in a method body. Assembled by concatenation rather
     * than by {@code String.format}, which would consult the default locale and so would breach the
     * determinism practice B7.
     */
    private static final String INSERT_STATEMENT_PREFIX = "INSERT INTO ";

    /**
     * The text following the dataset identifier in the default sink's statement: a single positional
     * parameter and no column list.
     *
     * <p>No column name is named because naming one would be declaring a schema, and this migration
     * creates none (gate G44).
     */
    private static final String INSERT_STATEMENT_SUFFIX = " VALUES (?)";

    // =============================================================================================
    // The fixed literal catalogue.
    // =============================================================================================

    /**
     * The thirty-four fixed HTML lines of {@code app/cbl/CBSTM03A.CBL:L150-L211}, transcribed
     * byte-exactly and in declaration order.
     *
     * <p>In the COBOL these are {@code 88}-level condition names on a single field:
     *
     * <pre>
     *   05  HTML-FIXED-LN        PIC X(100).
     *     88  HTML-L01 VALUE '&lt;!DOCTYPE html&gt;'.
     *     ...
     * </pre>
     *
     * <p>{@code SET HTML-Lxx TO TRUE} loads that literal into the 100-byte field and
     * {@code WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN} emits it, right-space padded to 100. Each
     * constant below is therefore one selectable line, and
     * {@link StatementHtmlWriter#writeFixedLine(HtmlStatementFile, HtmlFixedLine)} is the
     * {@code SET}-then-{@code WRITE} pair.
     *
     * <h2>Continuation lines are joined with nothing inserted</h2>
     *
     * <p>Eleven of these literals are continued across two or three source lines using the COBOL
     * fixed-format continuation - a hyphen in column 7 and the literal reopened with a quote. The
     * fragments are joined with <strong>no inserted whitespace</strong>, which is provably correct
     * here rather than merely conventional: every continued line in this source is exactly 72
     * characters long, so each fragment ends precisely at column 72 with no trailing spaces inside
     * the literal. The continued literals are {@link #HTML_L08}, {@link #HTML_L10},
     * {@link #HTML_L15}, {@link #HTML_L22_35}, {@link #HTML_L30_42}, {@link #HTML_L47},
     * {@link #HTML_L50}, {@link #HTML_L53}, {@link #HTML_L58}, {@link #HTML_L61} and
     * {@link #HTML_L64}.
     *
     * <h2>Three transcription traps, all real and all preserved</h2>
     *
     * <ul>
     *   <li>{@link #HTML_L08} has <strong>two spaces</strong> after {@code <table}, not one.</li>
     *   <li>{@link #HTML_L10}, {@link #HTML_L15}, {@link #HTML_L22_35} and {@link #HTML_L30_42} have
     *       <strong>no space</strong> after {@code padding:0px 5px;}, whereas {@link #HTML_L47},
     *       {@link #HTML_L50}, {@link #HTML_L53}, {@link #HTML_L58}, {@link #HTML_L61} and
     *       {@link #HTML_L64} <strong>do</strong>. The asymmetry is in the source and is kept.</li>
     *   <li>{@link #HTML_L22_35} and {@link #HTML_L30_42} carry compound COBOL names. The names
     *       record which output lines reuse one literal - line 22 and line 35 share the first, line
     *       30 and line 42 the second - so they are neither renumbered nor rationalised; only the
     *       hyphens become underscores, because a hyphen is not legal in a Java identifier.</li>
     * </ul>
     *
     * <p>Every literal is at most 100 characters, which the COBOL guarantees by construction because
     * the receiving field is {@code PIC X(100)}. Nothing here is escaped: {@code <}, {@code >} and
     * {@code &} are content.
     */
    public enum HtmlFixedLine {

        /** {@code CBSTM03A.CBL:L150} - the document type declaration. */
        HTML_L01("HTML-L01", "<!DOCTYPE html>"),

        /** {@code CBSTM03A.CBL:L151} - the root element. */
        HTML_L02("HTML-L02", "<html lang=\"en\">"),

        /** {@code CBSTM03A.CBL:L152} - head open. */
        HTML_L03("HTML-L03", "<head>"),

        /** {@code CBSTM03A.CBL:L153} - the declared document charset. Content, not configuration. */
        HTML_L04("HTML-L04", "<meta charset=\"utf-8\">"),

        /** {@code CBSTM03A.CBL:L154} - the document title. */
        HTML_L05("HTML-L05", "<title>HTML Table Layout</title>"),

        /** {@code CBSTM03A.CBL:L155} - head close. */
        HTML_L06("HTML-L06", "</head>"),

        /** {@code CBSTM03A.CBL:L156} - body open. */
        HTML_L07("HTML-L07", "<body style=\"margin:0px;\">"),

        /**
         * {@code CBSTM03A.CBL:L157-L158}, continued. The statement table.
         *
         * <p><strong>Two spaces after {@code <table}</strong>, exactly as declared.
         */
        HTML_L08("HTML-L08",
                "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">"),

        /** {@code CBSTM03A.CBL:L159} - table row open. The most frequently written line. */
        HTML_LTRS("HTML-LTRS", "<tr>"),

        /** {@code CBSTM03A.CBL:L160} - table row close. */
        HTML_LTRE("HTML-LTRE", "</tr>"),

        /**
         * {@code CBSTM03A.CBL:L161} - bare table cell open.
         *
         * <p>Declared by the COBOL and never {@code SET}: every cell {@code CBSTM03A} actually opens
         * is one of the styled variants below. Preserved because the declaration is preserved (B5).
         */
        HTML_LTDS("HTML-LTDS", "<td>"),

        /** {@code CBSTM03A.CBL:L162} - table cell close. */
        HTML_LTDE("HTML-LTDE", "</td>"),

        /** {@code CBSTM03A.CBL:L163-L164}, continued - the dark banner cell. No space after {@code 5px;}. */
        HTML_L10("HTML-L10",
                "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">"),

        /** {@code CBSTM03A.CBL:L165-L166}, continued - the amber bank-details cell. */
        HTML_L15("HTML-L15",
                "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">"),

        /** {@code CBSTM03A.CBL:L167-L168} - the bank name. */
        HTML_L16("HTML-L16", "<p style=\"font-size:16px\">Bank of XYZ</p>"),

        /** {@code CBSTM03A.CBL:L169-L170} - the bank street address. */
        HTML_L17("HTML-L17", "<p>410 Terry Ave N</p>"),

        /** {@code CBSTM03A.CBL:L171-L172} - the bank city, state and postal code. */
        HTML_L18("HTML-L18", "<p>Seattle WA 99999</p>"),

        /**
         * {@code CBSTM03A.CBL:L173-L175}, continued - the pale grey cell, used for output line 22
         * (the customer name and address block) and again for line 35 (the basic details block).
         */
        HTML_L22_35("HTML-L22-35",
                "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">"),

        /**
         * {@code CBSTM03A.CBL:L176-L178}, continued - the centred teal heading cell, used for output
         * line 30 (Basic Details) and again for line 42 (Transaction Summary).
         */
        HTML_L30_42("HTML-L30-42",
                "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">"),

        /** {@code CBSTM03A.CBL:L179-L180} - the Basic Details heading. */
        HTML_L31("HTML-L31", "<p style=\"font-size:16px\">Basic Details</p>"),

        /** {@code CBSTM03A.CBL:L181-L182} - the Transaction Summary heading. */
        HTML_L43("HTML-L43", "<p style=\"font-size:16px\">Transaction Summary</p>"),

        /**
         * {@code CBSTM03A.CBL:L183-L185}, continued - the green 25% column header cell, left
         * aligned. Note the space after {@code 5px;}, unlike {@link #HTML_L10}.
         */
        HTML_L47("HTML-L47",
                "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">"),

        /** {@code CBSTM03A.CBL:L186-L187} - the Tran ID column heading. */
        HTML_L48("HTML-L48", "<p style=\"font-size:16px\">Tran ID</p>"),

        /** {@code CBSTM03A.CBL:L188-L190}, continued - the green 55% column header cell, left aligned. */
        HTML_L50("HTML-L50",
                "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">"),

        /** {@code CBSTM03A.CBL:L191-L192} - the Tran Details column heading. */
        HTML_L51("HTML-L51", "<p style=\"font-size:16px\">Tran Details</p>"),

        /** {@code CBSTM03A.CBL:L193-L195}, continued - the green 20% column header cell, right aligned. */
        HTML_L53("HTML-L53",
                "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">"),

        /** {@code CBSTM03A.CBL:L196-L197} - the Amount column heading. */
        HTML_L54("HTML-L54", "<p style=\"font-size:16px\">Amount</p>"),

        /** {@code CBSTM03A.CBL:L198-L200}, continued - the grey 25% transaction-identifier cell. */
        HTML_L58("HTML-L58",
                "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">"),

        /** {@code CBSTM03A.CBL:L201-L203}, continued - the grey 55% transaction-description cell. */
        HTML_L61("HTML-L61",
                "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">"),

        /** {@code CBSTM03A.CBL:L204-L206}, continued - the grey 20% transaction-amount cell. */
        HTML_L64("HTML-L64",
                "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">"),

        /** {@code CBSTM03A.CBL:L207-L208} - the end-of-statement heading, written at {@code L443-L444}. */
        HTML_L75("HTML-L75", "<h3>End of Statement</h3>"),

        /** {@code CBSTM03A.CBL:L209} - table close. */
        HTML_L78("HTML-L78", "</table>"),

        /** {@code CBSTM03A.CBL:L210} - body close. */
        HTML_L79("HTML-L79", "</body>"),

        /** {@code CBSTM03A.CBL:L211} - document close. */
        HTML_L80("HTML-L80", "</html>");

        /**
         * Lookup of every constant by its COBOL condition name, built once from
         * {@link #values()} and wrapped unmodifiable.
         *
         * <p>{@code static final} and immutable, so it is constant data rather than shared mutable
         * state (practice B9). A {@link LinkedHashMap} is used so iteration follows COBOL
         * declaration order, which makes a diagnostic listing the known names read in the same order
         * as the source.
         */
        private static final Map<String, HtmlFixedLine> BY_COBOL_NAME = buildIndex();

        /** The COBOL {@code 88}-level condition name, verbatim, hyphens included. */
        private final String cobolName;

        /** The declared {@code VALUE} literal, verbatim and unpadded. */
        private final String literal;

        /**
         * @param cobolName the COBOL condition name exactly as declared
         * @param literal   the declared literal exactly as declared, continuations already joined
         */
        HtmlFixedLine(final String cobolName, final String literal) {
            this.cobolName = cobolName;
            this.literal = literal;
        }

        /**
         * Builds {@link #BY_COBOL_NAME}. A static method rather than a static initialiser block so
         * the field can stay {@code final} and the construction is one expression.
         *
         * @return an unmodifiable, declaration-ordered index of every constant by COBOL name
         */
        private static Map<String, HtmlFixedLine> buildIndex() {
            Map<String, HtmlFixedLine> index = new LinkedHashMap<>();
            for (HtmlFixedLine line : values()) {
                index.put(line.cobolName, line);
            }
            return Map.copyOf(index);
        }

        /**
         * The COBOL {@code 88}-level condition name, for example {@code HTML-L22-35}.
         *
         * @return the condition name exactly as {@code app/cbl/CBSTM03A.CBL} declares it
         */
        public String cobolName() {
            return this.cobolName;
        }

        /**
         * The declared literal, <strong>unpadded</strong> and unescaped.
         *
         * <p>Padding to {@link StatementHtmlWriter#RECORD_LENGTH} happens on emission, because that
         * is where the COBOL does it: the {@code 88}-level {@code VALUE} is the literal, and the
         * padding is a property of the {@code PIC X(100)} field it is {@code SET} into.
         *
         * @return the literal, between 4 and 85 characters and never more than 100
         */
        public String literal() {
            return this.literal;
        }

        /**
         * The literal's declared length in characters, which is what the COBOL {@code VALUE}
         * occupies before the {@code PIC X(100)} field pads it.
         *
         * @return the unpadded length, always at least 1 and never more than
         *         {@link StatementHtmlWriter#RECORD_LENGTH}
         */
        public int literalLength() {
            return this.literal.length();
        }

        /**
         * Resolves a constant by its COBOL condition name.
         *
         * <p>Matching is exact and case-sensitive, mirroring COBOL's own name resolution. An unknown
         * name is a programming error and is reported as one with the full list of valid names,
         * rather than silently yielding a default line - which would emit the wrong record and be
         * almost impossible to trace back from a byte diff.
         *
         * @param cobolName the condition name to resolve, for example {@code HTML-LTRS}
         * @return the matching constant; never {@code null}
         * @throws NullPointerException     if {@code cobolName} is {@code null}
         * @throws IllegalArgumentException if no constant carries that condition name
         */
        public static HtmlFixedLine ofCobolName(final String cobolName) {
            Objects.requireNonNull(cobolName, "A COBOL condition name is required to resolve a "
                    + "fixed HTML line");
            HtmlFixedLine line = BY_COBOL_NAME.get(cobolName);
            if (line == null) {
                throw new IllegalArgumentException("'" + cobolName + "' is not one of the "
                        + BY_COBOL_NAME.size() + " 88-level condition names declared on "
                        + "HTML-FIXED-LN at app/cbl/CBSTM03A.CBL:L150-L211. Names are matched "
                        + "exactly and case-sensitively, hyphens included. Declared names: "
                        + BY_COBOL_NAME.keySet() + ".");
            }
            return line;
        }

        /**
         * Whether a constant is declared under the given COBOL condition name.
         *
         * @param cobolName the condition name to test; may be {@code null}, which is never declared
         * @return {@code true} when {@link #ofCobolName(String)} would resolve it
         */
        public static boolean isDeclared(final String cobolName) {
            return cobolName != null && BY_COBOL_NAME.containsKey(cobolName);
        }
    }

    // =============================================================================================
    // The three identity enumerations. Each names WHICH line is being emitted and carries the
    // declared width of the COBOL sending field, which is what makes the emitted bytes exact.
    // Selecting a line is deliberately NOT the same as sequencing lines: each constant emits one
    // record, and the order is StatementGenerationJobA's.
    // =============================================================================================

    /**
     * The three customer address lines of {@code 5200-WRITE-HTML-NMADBS}
     * ({@code app/cbl/CBSTM03A.CBL:L569-L592}).
     *
     * <p>Each constant carries the declared width of its {@code STATEMENT-LINES} sending field,
     * because the {@code STRING} operates on the field at its full declared width - a caller that
     * passed a trimmed value would produce a different record. The widths differ, and the difference
     * is load-bearing: {@code ST-ADD1} and {@code ST-ADD2} are {@code PIC X(50)} while
     * {@code ST-ADD3} is {@code PIC X(80)}, because {@code 5000-CREATE-STATEMENT} builds the third
     * line by concatenating city, state, country and postal code
     * ({@code app/cbl/CBSTM03A.CBL:L472-L481}).
     */
    public enum AddressField {

        /**
         * {@code ST-ADD1 PIC X(50)} ({@code app/cbl/CBSTM03A.CBL:L94}), written at {@code L576}.
         * Populated by {@code MOVE CUST-ADDR-LINE-1 TO ST-ADD1} at {@code L470}.
         */
        ADDRESS_LINE_1("ST-ADD1", 50),

        /**
         * {@code ST-ADD2 PIC X(50)} ({@code app/cbl/CBSTM03A.CBL:L97}), written at {@code L584}.
         * Populated by {@code MOVE CUST-ADDR-LINE-2 TO ST-ADD2} at {@code L471}.
         */
        ADDRESS_LINE_2("ST-ADD2", 50),

        /**
         * {@code ST-ADD3 PIC X(80)} ({@code app/cbl/CBSTM03A.CBL:L100}), written at {@code L592}.
         * Eighty bytes, not fifty, because {@code L472-L481} strings city, state code, country code
         * and postal code into it.
         */
        ADDRESS_LINE_3("ST-ADD3", 80);

        /** The COBOL field name, verbatim. */
        private final String cobolName;

        /** The field's declared {@code PIC X} width, which the {@code STRING} sends from. */
        private final int declaredLength;

        /**
         * @param cobolName      the sending field's COBOL name
         * @param declaredLength the sending field's declared {@code PIC X} width
         */
        AddressField(final String cobolName, final int declaredLength) {
            this.cobolName = cobolName;
            this.declaredLength = declaredLength;
        }

        /**
         * The COBOL name of the sending field.
         *
         * @return {@code ST-ADD1}, {@code ST-ADD2} or {@code ST-ADD3}
         */
        public String cobolName() {
            return this.cobolName;
        }

        /**
         * The declared width the value is normalised to before the {@code STRING} runs.
         *
         * @return 50 for the first two lines, 80 for the third
         */
        public int declaredLength() {
            return this.declaredLength;
        }
    }

    /**
     * The three basic-detail lines of {@code 5200-WRITE-HTML-NMADBS}
     * ({@code app/cbl/CBSTM03A.CBL:L613-L633}).
     *
     * <p>Each constant pairs its label literal with the declared width of its value field. The three
     * labels are each exactly 24 characters, transcribed character by character from the source
     * including every internal space - the columns line up in the rendered statement only because
     * the padding is exactly right, and a single space out is a byte diff.
     *
     * <p>Every value is sent {@code DELIMITED BY '*'}, so the <strong>entire</strong> fixed-width
     * field is transferred, trailing spaces included. None of the three values is trimmed.
     */
    public enum BasicDetail {

        /**
         * {@code app/cbl/CBSTM03A.CBL:L614-L619}. Label {@code '<p>Account ID         : '} - three
         * characters of tag, {@code Account ID}, nine spaces, a colon and a space, 24 in all - and
         * the value {@code ST-ACCT-ID PIC X(20)} ({@code L109}), populated by
         * {@code MOVE ACCT-ID TO ST-ACCT-ID} at {@code L483}.
         */
        ACCOUNT_ID("<p>Account ID         : ", "ST-ACCT-ID", 20),

        /**
         * {@code app/cbl/CBSTM03A.CBL:L621-L626}. Label {@code '<p>Current Balance    : '} and the
         * value {@code ST-CURR-BAL} ({@code L113}), a numeric-edited item whose rendered image is
         * <strong>13 characters wide</strong>: nine digit positions, a decimal point, two more digit
         * positions and a trailing sign position.
         *
         * <p>The edit mask belongs to {@code StatementTextWriter}'s line group, not here. This class
         * receives the rendered image and embeds it verbatim.
         */
        CURRENT_BALANCE("<p>Current Balance    : ", "ST-CURR-BAL", 13),

        /**
         * {@code app/cbl/CBSTM03A.CBL:L628-L633}. Label {@code '<p>FICO Score         : '} and the
         * value {@code ST-FICO-SCORE PIC X(20)} ({@code L118}), populated by
         * {@code MOVE CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE} at {@code L485}.
         */
        FICO_SCORE("<p>FICO Score         : ", "ST-FICO-SCORE", 20);

        /** The 24-character label literal, verbatim. */
        private final String label;

        /** The COBOL name of the value field. */
        private final String cobolName;

        /** The value field's declared width. */
        private final int declaredLength;

        /**
         * @param label          the label literal exactly as declared, 24 characters
         * @param cobolName      the value field's COBOL name
         * @param declaredLength the value field's declared width
         */
        BasicDetail(final String label, final String cobolName, final int declaredLength) {
            this.label = label;
            this.cobolName = cobolName;
            this.declaredLength = declaredLength;
        }

        /**
         * The label literal that opens the line.
         *
         * @return the 24-character label, verbatim and unescaped
         */
        public String label() {
            return this.label;
        }

        /**
         * The COBOL name of the value field.
         *
         * @return {@code ST-ACCT-ID}, {@code ST-CURR-BAL} or {@code ST-FICO-SCORE}
         */
        public String cobolName() {
            return this.cobolName;
        }

        /**
         * The declared width the value is normalised to before the {@code STRING} runs.
         *
         * @return 20 for the identifier and the score, 13 for the edited balance
         */
        public int declaredLength() {
            return this.declaredLength;
        }
    }

    /**
     * The three per-transaction cells of {@code 6000-WRITE-TRANS}
     * ({@code app/cbl/CBSTM03A.CBL:L686-L716}).
     *
     * <p>All three lines share the shape {@code '<p>' + value + '</p>'} and differ only in the value
     * field and its declared width. Every value is sent {@code DELIMITED BY '*'}, so it is
     * transferred whole with its trailing spaces intact.
     */
    public enum TransactionField {

        /**
         * {@code app/cbl/CBSTM03A.CBL:L687-L692}. {@code ST-TRANID PIC X(16)} ({@code L133}),
         * populated by {@code MOVE TRNX-ID TO ST-TRANID} at {@code L676}; {@code TRNX-ID} is
         * {@code PIC X(16)} in {@code app/cpy/COSTM01.CPY}.
         */
        TRAN_ID("ST-TRANID", 16),

        /**
         * {@code app/cbl/CBSTM03A.CBL:L699-L704}. {@code ST-TRANDT PIC X(49)} ({@code L135}),
         * populated by {@code MOVE TRNX-DESC TO ST-TRANDT} at {@code L677}.
         *
         * <p>Forty-nine, not one hundred: {@code TRNX-DESC} is {@code PIC X(100)} in
         * {@code app/cpy/COSTM01.CPY}, so the move truncates the description on the right to 49
         * characters. That truncation is the legacy program's and is preserved.
         */
        TRAN_DETAILS("ST-TRANDT", 49),

        /**
         * {@code app/cbl/CBSTM03A.CBL:L711-L716}. {@code ST-TRANAMT} ({@code L137}), a numeric-edited
         * item whose rendered image is 13 characters wide, populated by
         * {@code MOVE TRNX-AMT TO ST-TRANAMT} at {@code L678}.
         *
         * <p>Its mask suppresses leading zeros to spaces, where {@code ST-CURR-BAL}'s prints them.
         * Both masks belong to {@code StatementTextWriter}; this class receives the rendered image
         * and never re-derives it.
         */
        TRAN_AMOUNT("ST-TRANAMT", 13);

        /** The COBOL name of the value field. */
        private final String cobolName;

        /** The value field's declared width. */
        private final int declaredLength;

        /**
         * @param cobolName      the value field's COBOL name
         * @param declaredLength the value field's declared width
         */
        TransactionField(final String cobolName, final int declaredLength) {
            this.cobolName = cobolName;
            this.declaredLength = declaredLength;
        }

        /**
         * The COBOL name of the value field.
         *
         * @return {@code ST-TRANID}, {@code ST-TRANDT} or {@code ST-TRANAMT}
         */
        public String cobolName() {
            return this.cobolName;
        }

        /**
         * The declared width the value is normalised to before the {@code STRING} runs.
         *
         * @return 16, 49 or 13
         */
        public int declaredLength() {
            return this.declaredLength;
        }
    }

    // =============================================================================================
    // The sink: where the 100-byte records go.
    // =============================================================================================

    /**
     * The destination of the 100-byte {@code HTMLFILE} records - the injectable seam that replaces
     * the COBOL {@code OPEN OUTPUT} / {@code WRITE} / {@code CLOSE} triple.
     *
     * <p>A functional interface, so a test supplies one with a lambda that appends to a list and
     * asserts the exact bytes and the exact order without touching a filesystem, a database or a
     * Spring context (practice B10, gate G51). The production default is
     * {@link StatementHtmlWriter#defaultSink()}, which writes through the module's
     * {@link JdbcTemplate} to the configured {@code HTMLFILE} binding.
     *
     * <p>Each method returns a two-character COBOL {@code FILE STATUS}, which is the shape
     * {@code CBSTM03A} itself works in - compare {@code WS-M03B-RC PIC X(02)} at
     * {@code app/cbl/CBSTM03A.CBL:L80} and the {@code EVALUATE WS-M03B-RC} guard at
     * {@code L353-L359}. Use the constants on {@link FileStatus}; {@link StatementHtmlWriter}
     * classifies the returned status into a {@link FileStatus.Outcome} for its caller.
     *
     * <p>An implementation must be strictly append-only and must preserve call order exactly. It must
     * not buffer in a way that could reorder records, must not deduplicate, and must not pad, trim,
     * re-encode or otherwise touch the bytes it is handed: they are already the final 100-byte
     * image, encoded in the injected dataset charset.
     */
    @FunctionalInterface
    public interface HtmlRecordSink {

        /**
         * Accepts one complete record. The COBOL {@code WRITE}.
         *
         * @param record exactly {@link StatementHtmlWriter#RECORD_LENGTH} bytes, already encoded in
         *               the dataset charset and already padded. The array is the caller's own copy
         *               and may be retained
         * @return a two-character COBOL {@code FILE STATUS}: {@link FileStatus#OK} on success, any
         *         other value to report a failure to {@code StatementGenerationJobA}
         */
        String write(byte[] record);

        /**
         * Prepares the destination. The COBOL {@code OPEN OUTPUT}
         * ({@code app/cbl/CBSTM03A.CBL:L293}).
         *
         * <p>Defaults to {@link FileStatus#OK}, because a sink that has nothing to allocate - an
         * in-memory list, for instance - has nothing to open and should not have to say so.
         *
         * @return a two-character COBOL {@code FILE STATUS}
         */
        default String open() {
            return FileStatus.OK;
        }

        /**
         * Releases the destination. The COBOL {@code CLOSE} ({@code app/cbl/CBSTM03A.CBL:L339}).
         *
         * <p>Defaults to {@link FileStatus#OK}, for the same reason as {@link #open()}. This
         * interface deliberately does <em>not</em> extend {@link AutoCloseable}: that contract
         * returns {@code void} and cannot report the {@code FILE STATUS} a COBOL {@code CLOSE}
         * produces.
         *
         * @return a two-character COBOL {@code FILE STATUS}
         */
        default String close() {
            return FileStatus.OK;
        }
    }

    /**
     * The per-execution handle for one opened {@code HTMLFILE} - the COBOL {@code FD} plus the
     * {@code WORKING-STORAGE} lines that feed it, scoped to a single statement run.
     *
     * <p><strong>Every mutable byte in this class lives here, and nothing here is {@code static}.</strong>
     * {@link StatementHtmlWriter} itself is a stateless singleton, so a handle is what a caller
     * obtains from {@link StatementHtmlWriter#open} and hands back to every emit call. Two runs can
     * proceed independently and a test can hold a handle in isolation (gate G53, practice B9).
     *
     * <p>The buffers correspond one-to-one to the COBOL declarations:
     *
     * <table border="1">
     *   <caption>Buffers and their COBOL declarations</caption>
     *   <tr><th>Accessor</th><th>COBOL</th><th>Source</th><th>Width</th></tr>
     *   <tr><td>{@link #recordArea()}</td><td>{@code FD-HTMLFILE-REC}</td>
     *       <td>{@code CBSTM03A.CBL:L47}</td><td>100</td></tr>
     *   <tr><td>{@link #fixedLine()}</td><td>{@code HTML-FIXED-LN}</td>
     *       <td>{@code CBSTM03A.CBL:L149}</td><td>100</td></tr>
     *   <tr><td>{@link #accountHeadingLine()}</td><td>{@code HTML-L11}</td>
     *       <td>{@code CBSTM03A.CBL:L212-L216}</td><td>59</td></tr>
     *   <tr><td>{@link #addressLine()}</td><td>{@code HTML-ADDR-LN}</td>
     *       <td>{@code CBSTM03A.CBL:L221}</td><td>100</td></tr>
     *   <tr><td>{@link #basicLine()}</td><td>{@code HTML-BSIC-LN}</td>
     *       <td>{@code CBSTM03A.CBL:L222}</td><td>100</td></tr>
     *   <tr><td>{@link #transactionLine()}</td><td>{@code HTML-TRAN-LN}</td>
     *       <td>{@code CBSTM03A.CBL:L223}</td><td>100</td></tr>
     * </table>
     *
     * <p>All six are carried, not only the three the agent brief names as scratch, because
     * {@code CBSTM03A} genuinely reads and writes all six and each has to survive between calls for
     * the {@code WRITE ... FROM} semantics to be faithful.
     *
     * <p>A handle is not safe for concurrent use and is not meant to be shared across threads.
     * Confine one to the step or test that owns it - which is exactly what {@code CBSTM03A} does,
     * since it opens the file once at {@code L293} and closes it once at {@code L339}.
     */
    public static final class HtmlStatementFile {

        /** Where the records go. Never {@code null}. */
        private final HtmlRecordSink sink;

        /** {@code FD-HTMLFILE-REC PIC X(100)} - the record area. */
        private final FixedWidthRecord recordArea;

        /** {@code HTML-FIXED-LN PIC X(100)} - the staging line for the 34 fixed literals. */
        private final FixedWidthRecord fixedLine;

        /** The {@code HTML-L11} group, 59 bytes, initialised from {@link #HTML_L11_LAYOUT}. */
        private final FixedWidthRecord accountHeadingLine;

        /** {@code HTML-ADDR-LN PIC X(100)}. */
        private final FixedWidthRecord addressLine;

        /** {@code HTML-BSIC-LN PIC X(100)}. */
        private final FixedWidthRecord basicLine;

        /** {@code HTML-TRAN-LN PIC X(100)}. */
        private final FixedWidthRecord transactionLine;

        /**
         * The two-character {@code FILE STATUS} the sink reported from {@link HtmlRecordSink#open()}.
         *
         * <p>Recorded rather than acted on. {@code app/cbl/CBSTM03A.CBL:L293} performs a bare
         * {@code OPEN OUTPUT STMT-FILE HTML-FILE.} with <strong>no</strong> {@code FILE STATUS}
         * clause and no guard at all, so branching on the status here would add a control path the
         * COBOL does not have. Surfacing it costs nothing and lets
         * {@code StatementGenerationJobA} inspect it if it wants to.
         */
        private final String openStatus;

        /**
         * {@code false} once {@link StatementHtmlWriter#close(HtmlStatementFile)} has run, so a
         * write after close is refused rather than silently appending past the COBOL's
         * {@code CLOSE}.
         */
        private boolean open;

        /** How many records this handle has emitted. Diagnostic and test evidence only. */
        private long recordsWritten;

        /**
         * @param sink       the destination
         * @param charset    the dataset charset, used to allocate every buffer so its pad bytes are
         *                   correct for the code page
         * @param openStatus the status the sink reported when it was opened
         */
        private HtmlStatementFile(final HtmlRecordSink sink, final Charset charset,
                                  final String openStatus) {
            this.sink = sink;
            this.recordArea = new FixedWidthRecord(RECORD_LENGTH, charset);
            this.fixedLine = new FixedWidthRecord(RECORD_LENGTH, charset);
            this.accountHeadingLine = FixedWidthRecord.forLayout(HTML_L11_LAYOUT, charset);
            this.addressLine = new FixedWidthRecord(RECORD_LENGTH, charset);
            this.basicLine = new FixedWidthRecord(RECORD_LENGTH, charset);
            this.transactionLine = new FixedWidthRecord(RECORD_LENGTH, charset);
            this.openStatus = openStatus;
            this.open = true;
            this.recordsWritten = 0L;
        }

        /**
         * The destination this handle writes to.
         *
         * @return the sink supplied when the handle was opened; never {@code null}
         */
        public HtmlRecordSink sink() {
            return this.sink;
        }

        /**
         * The {@code FD-HTMLFILE-REC} record area.
         *
         * <p>Exposed because {@code WRITE ... FROM} leaves its content behind, and a parity test has
         * to be able to assert that. Mutating it directly bypasses the emit methods and is not part
         * of the intended contract.
         *
         * @return the live 100-byte record area
         */
        public FixedWidthRecord recordArea() {
            return this.recordArea;
        }

        /**
         * The {@code HTML-FIXED-LN} staging line.
         *
         * @return the live 100-byte staging line
         */
        public FixedWidthRecord fixedLine() {
            return this.fixedLine;
        }

        /**
         * The {@code HTML-L11} account-heading group.
         *
         * @return the live 59-byte group
         */
        public FixedWidthRecord accountHeadingLine() {
            return this.accountHeadingLine;
        }

        /**
         * The {@code HTML-ADDR-LN} scratch line.
         *
         * @return the live 100-byte scratch line
         */
        public FixedWidthRecord addressLine() {
            return this.addressLine;
        }

        /**
         * The {@code HTML-BSIC-LN} scratch line.
         *
         * @return the live 100-byte scratch line
         */
        public FixedWidthRecord basicLine() {
            return this.basicLine;
        }

        /**
         * The {@code HTML-TRAN-LN} scratch line.
         *
         * @return the live 100-byte scratch line
         */
        public FixedWidthRecord transactionLine() {
            return this.transactionLine;
        }

        /**
         * Whether the file is still open.
         *
         * @return {@code true} until {@link StatementHtmlWriter#close(HtmlStatementFile)} runs
         */
        public boolean isOpen() {
            return this.open;
        }

        /**
         * How many records have been emitted through this handle.
         *
         * @return the count, starting at zero
         */
        public long recordsWritten() {
            return this.recordsWritten;
        }

        /**
         * The status the sink reported when this handle was opened.
         *
         * <p>See {@link #openStatus} for why it is recorded and not acted on.
         *
         * @return a two-character COBOL {@code FILE STATUS}; never {@code null}
         */
        public String openStatus() {
            return this.openStatus;
        }
    }

    /**
     * The production sink: one 100-byte record per {@link JdbcTemplate} statement, against the
     * dataset the {@code carddemo.datasets.HTMLFILE} binding names.
     *
     * <h2>What it does, and what it refuses to do</h2>
     *
     * <p>It issues exactly one parameterised statement per record, with the 100-byte image bound as
     * the single parameter. There is <strong>no DDL, no {@code CREATE}, no schema, no column name and
     * no generated table definition</strong> anywhere in it (gate G44): a single-column
     * {@code INSERT ... VALUES (?)} is the minimum SQL that appends one fixed-width record to a
     * sequential dataset, and inventing a column name would be inventing a schema this migration is
     * forbidden to create.
     *
     * <p>The dataset identifier comes wholly from configuration, so <strong>no mainframe dataset-name
     * literal appears in this Java file</strong> (gate G46). Because a SQL identifier cannot be
     * supplied as a bind parameter, the configured name is interpolated - and is therefore validated
     * first, character by character, against a strict allowlist. Anything outside that allowlist,
     * including whitespace, quotation marks, semicolons and comment markers, is refused with a
     * diagnostic naming the offending character.
     *
     * <p>The bytes are bound as {@code byte[]}, not as a {@code String}. They are already the final
     * 100-byte image, encoded once in this module using the injected dataset charset, and binding
     * them as text would invite the driver to re-encode them under some other code page - precisely
     * the silent corruption that {@code CobolCharsetConfig} exists to prevent (practice B8, rule R5).
     *
     * <h2>The driver is a deployment-time input (practice B12, risk R-E)</h2>
     *
     * <p>There is no {@code EXEC SQL} in any of the 28 COBOL programs and indexed VSAM has no
     * standard published JDBC driver, so this module pins no driver coordinate and production
     * connectivity cannot be exercised in this environment. How a site's driver maps
     * {@code INSERT INTO <sequential dataset> VALUES (?)} onto a physical record write is that
     * driver's business. That limitation is recorded rather than papered over, and it is exactly why
     * {@link HtmlRecordSink} is injectable: the parity harness and every unit test supply an
     * in-memory sink and assert the bytes directly.
     *
     * <h2>A failed write is reported, never swallowed</h2>
     *
     * <p>A {@link DataAccessException} is translated into the COBOL permanent-error status
     * {@value StatementHtmlWriter#PERMANENT_ERROR_STATUS}, which
     * {@link FileStatus#outcomeOfStatus(String)} classifies as {@link FileStatus.Outcome#OTHER} - the
     * {@code WHEN OTHER} arm every COBOL guard chain in the estate treats as fatal. The exception
     * itself is retained on {@link #lastFailure()} so the diagnostic is not lost; this is a
     * per-execution sink, created afresh by {@link StatementHtmlWriter#defaultSink()}, so that field
     * is per-run state and never shared (practice B9).
     */
    public static final class JdbcHtmlRecordSink implements HtmlRecordSink {

        /** The template the statement is issued through. Never {@code null}. */
        private final JdbcTemplate jdbcTemplate;

        /** The single parameterised statement, assembled once from the validated dataset name. */
        private final String insertStatement;

        /** The dataset name this sink writes to, as configuration supplied it. */
        private final String dsname;

        /**
         * The most recent write failure, or {@code null} when none has occurred.
         *
         * <p>Per-execution state on a per-execution object. It exists so that translating an
         * exception into a {@code FILE STATUS} does not discard the exception, which would be an
         * opaque error handler.
         */
        private DataAccessException lastFailure;

        /**
         * @param jdbcTemplate the module-wide template
         * @param dsname       the validated dataset name from the {@code HTMLFILE} binding
         */
        private JdbcHtmlRecordSink(final JdbcTemplate jdbcTemplate, final String dsname) {
            this.jdbcTemplate = jdbcTemplate;
            this.dsname = dsname;
            this.insertStatement = INSERT_STATEMENT_PREFIX + dsname + INSERT_STATEMENT_SUFFIX;
        }

        /**
         * Writes one record.
         *
         * @param record exactly {@link StatementHtmlWriter#RECORD_LENGTH} bytes
         * @return {@link FileStatus#OK} when the statement succeeded, otherwise
         *         {@value StatementHtmlWriter#PERMANENT_ERROR_STATUS}
         */
        @Override
        public String write(final byte[] record) {
            try {
                this.jdbcTemplate.update(this.insertStatement, (Object) record);
                this.lastFailure = null;
                return FileStatus.OK;
            } catch (DataAccessException failure) {
                this.lastFailure = failure;
                return PERMANENT_ERROR_STATUS;
            }
        }

        /**
         * The dataset name this sink writes to.
         *
         * @return the configured name, verbatim; never {@code null}
         */
        public String dsname() {
            return this.dsname;
        }

        /**
         * The statement this sink issues, exposed so a deployment can confirm what its driver will
         * receive without having to run the job.
         *
         * @return the single parameterised statement; never {@code null}
         */
        public String insertStatement() {
            return this.insertStatement;
        }

        /**
         * The most recent write failure.
         *
         * @return the last {@link DataAccessException}, or an empty {@link Optional} when the most
         *         recent write succeeded and when no write has been attempted
         */
        public Optional<DataAccessException> lastFailure() {
            return Optional.ofNullable(this.lastFailure);
        }
    }

    // =============================================================================================
    // Injected collaborators. All four are immutable; there is no other instance state.
    // =============================================================================================

    /**
     * The module-wide template from {@code config/DataSourceConfig}, used only by
     * {@link #defaultSink()}.
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * The active dataset code page, injected by qualifier from {@code config/CobolCharsetConfig}.
     *
     * <p>Never derived from the platform and never defaulted here. Every buffer this class allocates
     * takes it explicitly, so the pad bytes are right for the code page - EBCDIC space is
     * {@code x'40'}, ASCII space {@code x'20'} - and every encode names it (practice B8).
     */
    private final Charset datasetCharset;

    /** The hand-written fixed-width codec over {@link #datasetCharset}. */
    private final FixedWidthCodec codec;

    /** The resolved {@code carddemo.datasets.HTMLFILE} binding. */
    private final DatasetBinding binding;

    /**
     * Constructs the writer and <strong>verifies the 100-byte record width</strong>.
     *
     * <p>The width check is the mechanical enforcement of gate G20 and risk R-G. The
     * {@code HTMLFILE} binding is resolved from configuration and its declared record length is
     * compared against {@link #RECORD_LENGTH}; anything else refuses startup with a diagnostic that
     * names both conflicting JCL lines, so an operator who "corrects" the width to the pre-delete
     * step's 80 is told immediately why that is wrong rather than discovering it as 100 broken parity
     * cases.
     *
     * <p>The block size is deliberately <em>not</em> checked - see {@link #BLOCK_SIZE} - and the
     * dataset name is deliberately <em>not</em> validated here. Name validation happens in
     * {@link #defaultSink()} instead, so that activating the fixture-backed {@code test} profile,
     * whose {@code HTMLFILE} location is a filesystem path, still starts the application context
     * (gate G3) and still lets every test inject its own sink.
     *
     * @param jdbcTemplate   the module-wide {@link JdbcTemplate}
     * @param datasetCharset the active dataset code page, selected by qualifier because
     *                       {@code CobolCharsetConfig} publishes three {@link Charset} beans and
     *                       declares no primary
     * @param datasetBindings the {@code carddemo.datasets} catalogue
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if no {@code HTMLFILE} binding is configured, or if it declares
     *                               a record length other than {@link #RECORD_LENGTH}
     */
    public StatementHtmlWriter(
            final JdbcTemplate jdbcTemplate,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) final Charset datasetCharset,
            final DatasetBindings datasetBindings) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate,
                "A JdbcTemplate is required; config/DataSourceConfig publishes exactly one");
        this.datasetCharset = Objects.requireNonNull(datasetCharset,
                "A dataset Charset is required and must be selected by qualifier: "
                        + "config/CobolCharsetConfig publishes three Charset beans and declares no "
                        + "primary, so an unqualified injection point is ambiguous by design");
        Objects.requireNonNull(datasetBindings,
                "The carddemo.datasets binding catalogue is required; dataset names are never "
                        + "hard-coded in Java");
        this.codec = new FixedWidthCodec(datasetCharset);
        this.binding = datasetBindings.binding(HTMLFILE_DD_NAME);
        if (this.binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("carddemo.datasets." + HTMLFILE_DD_NAME
                    + ".record-length is " + this.binding.recordLength() + " but the HTML statement "
                    + "record is " + RECORD_LENGTH + " bytes. app/jcl/CREASTMT.JCL declares this DD "
                    + "TWICE with different widths: the STEP030 IEFBR14 pre-delete at L69 says "
                    + "LRECL=80,BLKSIZE=3200 and the STEP040 step that actually CREATES the file at "
                    + "L94 says LRECL=100,BLKSIZE=800. The creating step is authoritative, and "
                    + "app/cbl/CBSTM03A.CBL:L47 confirms it with '01 FD-HTMLFILE-REC PIC X(100)'. "
                    + "Restore record-length: " + RECORD_LENGTH + "; do not use 80 and do not "
                    + "average the two (gate G20, risk R-G).");
        }
    }

    // =============================================================================================
    // Geometry and configuration, exposed so a caller and a test can assert them.
    // =============================================================================================

    /**
     * The record width this writer emits.
     *
     * @return always {@link #RECORD_LENGTH}, that is 100
     */
    public int recordLength() {
        return RECORD_LENGTH;
    }

    /**
     * The block size the creating JCL step declares.
     *
     * @return always {@link #BLOCK_SIZE}, that is 800. Documentation only; nothing consults it
     */
    public int blockSize() {
        return BLOCK_SIZE;
    }

    /**
     * The active dataset code page.
     *
     * @return the injected {@link Charset}; never {@code null} and never a platform default
     */
    public Charset datasetCharset() {
        return this.datasetCharset;
    }

    /**
     * The resolved {@code carddemo.datasets.HTMLFILE} binding.
     *
     * @return the binding, whose {@code recordLength()} the constructor has already verified to be
     *         {@link #RECORD_LENGTH}
     */
    public DatasetBinding datasetBinding() {
        return this.binding;
    }

    // =============================================================================================
    // OPEN OUTPUT / CLOSE - app/cbl/CBSTM03A.CBL:L293 and L339.
    // =============================================================================================

    /**
     * Builds the production sink over the configured {@code HTMLFILE} dataset.
     *
     * <p>Called by {@link #open()}. Call it directly only to decorate it - for example to tee records
     * to a second destination - since the sink is otherwise supplied to {@link #open(HtmlRecordSink)}.
     *
     * @return a fresh per-execution {@link JdbcHtmlRecordSink}
     * @throws IllegalStateException if the configured dataset name is empty or contains a character
     *                               that cannot safely be interpolated into a SQL identifier -
     *                               which includes the filesystem locations the {@code test} profile
     *                               binds, and in that case the caller should inject its own sink
     */
    public JdbcHtmlRecordSink defaultSink() {
        return new JdbcHtmlRecordSink(this.jdbcTemplate, requireInterpolatableDsname(
                this.binding.dsname()));
    }

    /**
     * {@code OPEN OUTPUT HTML-FILE} against the production sink
     * ({@code app/cbl/CBSTM03A.CBL:L293}).
     *
     * @return a fresh per-execution handle
     * @throws IllegalStateException if {@link #defaultSink()} cannot be built
     */
    public HtmlStatementFile open() {
        return open(defaultSink());
    }

    /**
     * {@code OPEN OUTPUT HTML-FILE} against a supplied sink
     * ({@code app/cbl/CBSTM03A.CBL:L293}).
     *
     * <p>This is the injection point every test and the parity harness use: pass a lambda that
     * collects the 100-byte images and the records can be asserted byte for byte, in order, with no
     * filesystem, no database and no Spring context involved.
     *
     * <p>{@link HtmlRecordSink#open()} is invoked and its status recorded on the handle, but
     * <strong>not branched on</strong>: {@code CBSTM03A} performs a bare {@code OPEN OUTPUT} with no
     * {@code FILE STATUS} clause and no guard, and inventing a failure path here would add control
     * flow the COBOL does not have. Inspect {@link HtmlStatementFile#openStatus()} if the status
     * matters to the caller.
     *
     * @param sink where the records go
     * @return a fresh per-execution handle, already open
     * @throws NullPointerException if {@code sink} is {@code null}, or if it returns a {@code null}
     *                              status from {@link HtmlRecordSink#open()}
     */
    public HtmlStatementFile open(final HtmlRecordSink sink) {
        Objects.requireNonNull(sink, "A record sink is required to open " + HTMLFILE_DD_NAME
                + "; call open() for the configured default sink");
        final String status = Objects.requireNonNull(sink.open(),
                "A record sink must report a two-character FILE STATUS from open(), never null");
        return new HtmlStatementFile(sink, this.datasetCharset, status);
    }

    /**
     * {@code CLOSE HTML-FILE} ({@code app/cbl/CBSTM03A.CBL:L339}).
     *
     * <p>Closes exactly once. A second close is refused rather than treated as a no-op, because
     * {@code CBSTM03A} closes the file once, at the end of {@code 1000-MAINLINE}, and a duplicate
     * close in the Java translation would mean the caller's control flow has diverged from the
     * COBOL's.
     *
     * @param file the handle returned by {@link #open}
     * @return the outcome of the sink's own close
     * @throws NullPointerException  if {@code file} is {@code null}, or if the sink returns a
     *                               {@code null} status
     * @throws IllegalStateException if {@code file} is already closed
     */
    public FileStatus.Outcome close(final HtmlStatementFile file) {
        requireOpen(file, "close");
        file.open = false;
        final String status = Objects.requireNonNull(file.sink.close(),
                "A record sink must report a two-character FILE STATUS from close(), never null");
        return FileStatus.outcomeOfStatus(status);
    }

    // =============================================================================================
    // The six emit operations. ONE RECORD PER CALL. No sequence is encoded here.
    // =============================================================================================

    /**
     * {@code SET HTML-Lxx TO TRUE} followed by
     * {@code WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN} - one of the thirty-four fixed HTML lines.
     *
     * <p>The {@code SET} is modelled as what it is: a move of the {@code 88}-level literal into the
     * {@code PIC X(100)} {@code HTML-FIXED-LN} field, right-space padded. The {@code WRITE ... FROM}
     * then moves that field into the record area and emits it.
     *
     * @param file the open handle
     * @param line which fixed line to emit
     * @return the outcome the sink reported
     * @throws NullPointerException  if {@code file} or {@code line} is {@code null}
     * @throws IllegalStateException if {@code file} is closed
     */
    public FileStatus.Outcome writeFixedLine(final HtmlStatementFile file, final HtmlFixedLine line) {
        requireOpen(file, "write a fixed HTML line");
        Objects.requireNonNull(line, "A fixed HTML line is required; resolve one from the "
                + "HtmlFixedLine catalogue or by its COBOL condition name");
        // SET HTML-Lxx TO TRUE: the literal lands in HTML-FIXED-LN PIC X(100), right-space padded.
        file.fixedLine.writeString(0, RECORD_LENGTH,
                this.codec.movePicX(line.literal(), RECORD_LENGTH));
        return writeFrom(file, file.fixedLine);
    }

    /**
     * {@code MOVE ACCT-ID TO L11-ACCT} followed by
     * {@code WRITE FD-HTMLFILE-REC FROM HTML-L11} ({@code app/cbl/CBSTM03A.CBL:L529-L530}).
     *
     * <p>{@code ACCT-ID} is {@code PIC 9(11)} ({@code app/cpy/CVACT01Y.cpy:L5}) and {@code L11-ACCT}
     * is {@code PIC X(20)}. A numeric {@code DISPLAY} item moved to an alphanumeric receiver is moved
     * as though it were alphanumeric, so the eleven digits are placed left-justified with their
     * leading zeros intact and the receiver is padded on the right - account {@code 1} renders as
     * {@code "00000000001"} followed by nine spaces, not as {@code "1"} and not right-justified.
     * Supplying the identifier as the eleven-character image is therefore the caller's job, and
     * {@code StatementGenerationJobA} has it in that form already.
     *
     * <p>The two {@code FILLER}s are re-initialised from {@link #HTML_L11_LAYOUT} on every call, so
     * the group is always exactly {@code '<h3>Statement for Account Number: '} + the identifier +
     * {@code '</h3>'} regardless of what the previous call left behind. The 59-byte group is then
     * right-space padded to 100 by the {@code WRITE ... FROM}.
     *
     * @param file      the open handle
     * @param accountId the {@code ACCT-ID} image; right-truncated to
     *                  {@link #L11_ACCT_LENGTH} characters if longer, right-space padded if shorter,
     *                  exactly as the alphanumeric {@code MOVE} rule requires
     * @return the outcome the sink reported
     * @throws NullPointerException  if {@code file} or {@code accountId} is {@code null}
     * @throws IllegalStateException if {@code file} is closed
     */
    public FileStatus.Outcome writeAccountHeading(final HtmlStatementFile file,
                                                  final String accountId) {
        requireOpen(file, "write the HTML-L11 account heading");
        Objects.requireNonNull(accountId, "An ACCT-ID image is required for L11-ACCT; move an empty "
                + "string to blank it explicitly");
        file.accountHeadingLine.initialise(HTML_L11_LAYOUT);
        this.codec.writePicX(file.accountHeadingLine, L11_ACCT_SPAN, accountId);
        return writeFrom(file, file.accountHeadingLine);
    }

    /**
     * The customer name line of {@code 5200-WRITE-HTML-NMADBS}
     * ({@code app/cbl/CBSTM03A.CBL:L560-L568}), verbatim:
     *
     * <pre>
     *   MOVE ST-NAME TO L23-NAME.
     *   MOVE SPACES TO FD-HTMLFILE-REC
     *   STRING '&lt;p style="font-size:16px&gt;"' DELIMITED BY '*'
     *          L23-NAME                     DELIMITED BY '  '
     *          '  '                         DELIMITED BY SIZE
     *          '&lt;/p&gt;'                       DELIMITED BY '*'
     *          INTO FD-HTMLFILE-REC
     *   END-STRING.
     *   WRITE FD-HTMLFILE-REC.
     * </pre>
     *
     * <p>Three details make this the subtlest record in the file:
     *
     * <ul>
     *   <li>The {@code WRITE} has <strong>no {@code FROM} clause</strong>. The {@code STRING}
     *       composed straight into the record area, so the record area is emitted as it stands with no
     *       intervening move. This is the one emit path that does not go through
     *       {@link #writeFrom}.</li>
     *   <li>{@code MOVE ST-NAME TO L23-NAME} truncates {@code ST-NAME PIC X(75)} on the right to
     *       {@link #L23_NAME_LENGTH} characters <em>before</em> the {@code STRING} runs, so a long
     *       name loses its tail here while keeping it in the plain-text statement.</li>
     *   <li>{@code DELIMITED BY '  '} stops at the first <strong>two consecutive spaces</strong>. A
     *       name padded to 50 characters therefore contributes only its significant text; a name with
     *       a single internal space keeps it; and an all-spaces name contributes
     *       <strong>nothing</strong>, because the first two-space run starts at position 1 - the line
     *       then reads {@code '<p style="font-size:16px>"' + '  ' + '</p>'}.</li>
     * </ul>
     *
     * <p>No escaping is applied. A name containing {@code <}, {@code >} or {@code &} is emitted raw,
     * exactly as the COBOL emits it.
     *
     * @param file   the open handle
     * @param stName the {@code ST-NAME} value; normalised to {@link #L23_NAME_LENGTH} characters by
     *               the alphanumeric {@code MOVE} rule before the {@code STRING} sees it
     * @return the outcome the sink reported
     * @throws NullPointerException  if {@code file} or {@code stName} is {@code null}
     * @throws IllegalStateException if {@code file} is closed
     */
    public FileStatus.Outcome writeNameLine(final HtmlStatementFile file, final String stName) {
        requireOpen(file, "write the customer name line");
        Objects.requireNonNull(stName, "An ST-NAME value is required; move an empty string to blank "
                + "the line explicitly");
        // MOVE ST-NAME TO L23-NAME: PIC X(75) into PIC X(50), truncated on the right.
        final String l23Name = this.codec.movePicX(stName, L23_NAME_LENGTH);
        // MOVE SPACES TO FD-HTMLFILE-REC. STRING does not blank its receiver, so this is required.
        moveSpaces(file.recordArea);
        this.codec.stringIntoDelimitedBySize(file.recordArea, RECORD_AREA_SPAN,
                delimitedBy(STYLED_PARAGRAPH_OPEN_TAG, ASTERISK_DELIMITER),
                delimitedBy(l23Name, TWO_SPACE_DELIMITER),
                delimitedBySize(TWO_SPACE_SEPARATOR),
                delimitedBy(PARAGRAPH_CLOSE_TAG, ASTERISK_DELIMITER));
        // WRITE FD-HTMLFILE-REC. - no FROM clause, so no move precedes the write.
        return emitRecordArea(file);
    }

    /**
     * One customer address line of {@code 5200-WRITE-HTML-NMADBS}
     * ({@code app/cbl/CBSTM03A.CBL:L569-L592}), verbatim:
     *
     * <pre>
     *   MOVE SPACES TO HTML-ADDR-LN.
     *   STRING '&lt;p&gt;'   DELIMITED BY '*'
     *          ST-ADDn DELIMITED BY '  '
     *          '  '    DELIMITED BY SIZE
     *          '&lt;/p&gt;'  DELIMITED BY '*'
     *          INTO HTML-ADDR-LN
     *   END-STRING.
     *   WRITE FD-HTMLFILE-REC FROM HTML-ADDR-LN.
     * </pre>
     *
     * <p>The COBOL performs this three times, for {@code ST-ADD1}, {@code ST-ADD2} and
     * {@code ST-ADD3} in that order. The order belongs to {@code StatementGenerationJobA}; this
     * method emits exactly one line and takes which one as a parameter.
     *
     * <p>{@code DELIMITED BY '  '} again stops at the first two consecutive spaces, so a padded
     * address contributes only its significant text and an all-spaces address contributes nothing at
     * all - which is how a customer with a blank second address line still gets a well-formed empty
     * paragraph.
     *
     * @param file  the open handle
     * @param field which address line, carrying the declared width the {@code STRING} sends from
     * @param value the address value; normalised to {@link AddressField#declaredLength()} by the
     *              alphanumeric {@code MOVE} rule first, because the COBOL sends from the field at
     *              its full declared width
     * @return the outcome the sink reported
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if {@code file} is closed
     */
    public FileStatus.Outcome writeAddressLine(final HtmlStatementFile file,
                                               final AddressField field,
                                               final String value) {
        requireOpen(file, "write a customer address line");
        Objects.requireNonNull(field, "An address line identity is required; the three lines have "
                + "different declared widths, so which one is being written is not optional");
        Objects.requireNonNull(value, "An address value is required; move an empty string to blank "
                + "the line explicitly");
        final String sending = this.codec.movePicX(value, field.declaredLength());
        moveSpaces(file.addressLine);
        this.codec.stringIntoDelimitedBySize(file.addressLine, ADDRESS_LINE_SPAN,
                delimitedBy(PARAGRAPH_OPEN_TAG, ASTERISK_DELIMITER),
                delimitedBy(sending, TWO_SPACE_DELIMITER),
                delimitedBySize(TWO_SPACE_SEPARATOR),
                delimitedBy(PARAGRAPH_CLOSE_TAG, ASTERISK_DELIMITER));
        return writeFrom(file, file.addressLine);
    }

    /**
     * One basic-detail line of {@code 5200-WRITE-HTML-NMADBS}
     * ({@code app/cbl/CBSTM03A.CBL:L613-L633}), verbatim:
     *
     * <pre>
     *   MOVE SPACES TO HTML-BSIC-LN.
     *   STRING '&lt;p&gt;Account ID         : ' DELIMITED BY '*'
     *          ST-ACCT-ID                DELIMITED BY '*'
     *          '&lt;/p&gt;'                     DELIMITED BY '*'
     *          INTO HTML-BSIC-LN
     *   END-STRING.
     *   WRITE FD-HTMLFILE-REC FROM HTML-BSIC-LN.
     * </pre>
     *
     * <p><strong>Every operand here is {@code DELIMITED BY '*'} and none contains an asterisk, so
     * each is transferred in full - trailing spaces included.</strong> The value is deliberately
     * <em>not</em> trimmed: {@code ST-ACCT-ID PIC X(20)} contributes twenty characters, of which the
     * padding is as much part of the record as the digits. This is the opposite of the address lines,
     * and getting it the wrong way round is a silent byte diff.
     *
     * <p>The balance arrives as an already-edited 13-character image. This method neither formats nor
     * rounds it - see this class's documentation on why the edit masks live in
     * {@code StatementTextWriter}.
     *
     * @param file   the open handle
     * @param detail which detail line, carrying its 24-character label and its value's declared width
     * @param value  the value; normalised to {@link BasicDetail#declaredLength()} by the alphanumeric
     *               {@code MOVE} rule and then transferred whole
     * @return the outcome the sink reported
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if {@code file} is closed
     */
    public FileStatus.Outcome writeBasicDetail(final HtmlStatementFile file,
                                               final BasicDetail detail,
                                               final String value) {
        requireOpen(file, "write a basic-detail line");
        Objects.requireNonNull(detail, "A basic-detail identity is required; it selects both the "
                + "24-character label and the value's declared width");
        Objects.requireNonNull(value, "A basic-detail value is required; move an empty string to "
                + "blank it explicitly");
        final String sending = this.codec.movePicX(value, detail.declaredLength());
        moveSpaces(file.basicLine);
        this.codec.stringIntoDelimitedBySize(file.basicLine, BASIC_LINE_SPAN,
                delimitedBy(detail.label(), ASTERISK_DELIMITER),
                delimitedBy(sending, ASTERISK_DELIMITER),
                delimitedBy(PARAGRAPH_CLOSE_TAG, ASTERISK_DELIMITER));
        return writeFrom(file, file.basicLine);
    }

    /**
     * One per-transaction line of {@code 6000-WRITE-TRANS}
     * ({@code app/cbl/CBSTM03A.CBL:L686-L716}), verbatim:
     *
     * <pre>
     *   MOVE SPACES TO HTML-TRAN-LN.
     *   STRING '&lt;p&gt;'      DELIMITED BY '*'
     *          ST-TRANID  DELIMITED BY '*'
     *          '&lt;/p&gt;'     DELIMITED BY '*'
     *          INTO HTML-TRAN-LN
     *   END-STRING.
     *   WRITE FD-HTMLFILE-REC FROM HTML-TRAN-LN.
     * </pre>
     *
     * <p>As with the basic details, every operand is {@code DELIMITED BY '*'}, so the value is
     * transferred in full with its trailing spaces intact and is never trimmed. The amount arrives as
     * an already-edited 13-character image; no mask is applied here.
     *
     * @param file  the open handle
     * @param field which transaction cell, carrying its value's declared width
     * @param value the value; normalised to {@link TransactionField#declaredLength()} by the
     *              alphanumeric {@code MOVE} rule and then transferred whole
     * @return the outcome the sink reported
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if {@code file} is closed
     */
    public FileStatus.Outcome writeTransactionField(final HtmlStatementFile file,
                                                    final TransactionField field,
                                                    final String value) {
        requireOpen(file, "write a transaction line");
        Objects.requireNonNull(field, "A transaction field identity is required; the three cells "
                + "have different declared widths");
        Objects.requireNonNull(value, "A transaction value is required; move an empty string to "
                + "blank it explicitly");
        final String sending = this.codec.movePicX(value, field.declaredLength());
        moveSpaces(file.transactionLine);
        this.codec.stringIntoDelimitedBySize(file.transactionLine, TRANSACTION_LINE_SPAN,
                delimitedBy(PARAGRAPH_OPEN_TAG, ASTERISK_DELIMITER),
                delimitedBy(sending, ASTERISK_DELIMITER),
                delimitedBy(PARAGRAPH_CLOSE_TAG, ASTERISK_DELIMITER));
        return writeFrom(file, file.transactionLine);
    }

    // =============================================================================================
    // The declared-but-never-written HTML-L23 group, preserved rather than deleted (practice B5).
    // =============================================================================================

    /**
     * Materialises the {@code HTML-L23} group of {@code app/cbl/CBSTM03A.CBL:L217-L220} as its
     * declared 76 bytes.
     *
     * <pre>
     *   05  HTML-L23.
     *       10  FILLER   PIC X(26) VALUE '&lt;p style="font-size:16px&gt;"'.
     *       10  L23-NAME PIC X(50).
     * </pre>
     *
     * <p><strong>{@code CBSTM03A} never writes this group.</strong> {@code 5200-WRITE-HTML-NMADBS}
     * builds the equivalent line with a {@code STRING} into the record area instead
     * ({@code L560-L568}), which is what {@link #writeNameLine(HtmlStatementFile, String)}
     * reproduces. Only {@code L23-NAME} is live, as the receiver of
     * {@code MOVE ST-NAME TO L23-NAME} at {@code L560}.
     *
     * <p>The group is modelled here regardless, because a declared-but-unused structure is preserved
     * rather than tidied away: deleting it would be a behaviour change by omission, and the
     * difference between the group's 76-byte image and the {@code STRING}-built line is itself
     * evidence a reader may need. The two are genuinely different - the group pads {@code L23-NAME}
     * to 50 characters and has no {@code '</p>'} at all, while the {@code STRING} truncates at the
     * first two-space run and appends {@code '  </p>'} - so a reader who assumed they were
     * interchangeable would be wrong, and this method is how that can be demonstrated.
     *
     * <p>Nothing in this class emits the result. There is deliberately no {@code writeNameGroup}
     * method, because emitting it would be a record {@code CBSTM03A} does not write.
     *
     * @param stName the {@code ST-NAME} value, normalised to {@link #L23_NAME_LENGTH} by the
     *               alphanumeric {@code MOVE} rule exactly as {@code L560} does
     * @return exactly {@link #HTML_L23_LENGTH} bytes, encoded in the dataset charset
     * @throws NullPointerException if {@code stName} is {@code null}
     */
    public byte[] composeNameParagraphGroup(final String stName) {
        Objects.requireNonNull(stName, "An ST-NAME value is required to materialise HTML-L23");
        final FixedWidthRecord group =
                FixedWidthRecord.forLayout(HTML_L23_LAYOUT, this.datasetCharset);
        this.codec.writePicX(group, L23_NAME_SPAN, stName);
        return group.toByteArray();
    }

    // =============================================================================================
    // COBOL STRING ... DELIMITED BY, hand-written (practice B11).
    //
    // Nothing below uses split, trim, strip, replace or a regular expression. Those operations are
    // all subtly different from what COBOL does, and one of them looking close enough is exactly how
    // a parity defect gets in.
    // =============================================================================================

    /**
     * {@code STRING <sending> DELIMITED BY <delimiter>}: the characters of {@code sending} up to but
     * <strong>not including</strong> the first occurrence of {@code delimiter}, or all of
     * {@code sending} when the delimiter does not occur.
     *
     * <p>Worked examples, all from real sites in this file:
     * <table border="1">
     *   <caption>Delimiter semantics</caption>
     *   <tr><th>Sending</th><th>Delimiter</th><th>Transferred</th><th>Why</th></tr>
     *   <tr><td>{@code "AL  SMITH"}</td><td>{@code "  "}</td><td>{@code "AL"}</td>
     *       <td>Stops at the first two-space run</td></tr>
     *   <tr><td>{@code "ALSMITH"}</td><td>{@code "  "}</td><td>{@code "ALSMITH"}</td>
     *       <td>No two-space run, so everything transfers</td></tr>
     *   <tr><td>{@code "AL SMITH"}</td><td>{@code "  "}</td><td>{@code "AL SMITH"}</td>
     *       <td>A <em>single</em> space is not the delimiter</td></tr>
     *   <tr><td>{@code "    "}</td><td>{@code "  "}</td><td>{@code ""}</td>
     *       <td>The run starts at position 1, so nothing transfers</td></tr>
     *   <tr><td>{@code "12345      "}</td><td>{@code "*"}</td><td>{@code "12345      "}</td>
     *       <td>No asterisk, so the padding transfers too</td></tr>
     * </table>
     *
     * <p>Note the last row: it is why the basic-detail and transaction values keep their trailing
     * spaces, and it is the whole reason {@code DELIMITED BY '*'} appears at those sites at all.
     *
     * @param sending   the sending item, at its full declared width
     * @param delimiter the delimiter literal; at least one character
     * @return the transferred characters, possibly empty and never longer than {@code sending}
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code delimiter} is empty, which no COBOL
     *                                  {@code DELIMITED BY} phrase can express
     */
    public static String delimitedBy(final String sending, final String delimiter) {
        Objects.requireNonNull(sending, "A sending item is required for STRING ... DELIMITED BY");
        Objects.requireNonNull(delimiter, "A delimiter is required; use delimitedBySize(String) for "
                + "DELIMITED BY SIZE");
        if (delimiter.isEmpty()) {
            throw new IllegalArgumentException("An empty delimiter cannot be expressed by a COBOL "
                    + "DELIMITED BY phrase; use delimitedBySize(String) to transfer a sending item "
                    + "whole");
        }
        final int position = delimiterPosition(sending, delimiter);
        if (position == DELIMITER_NOT_FOUND) {
            return sending;
        }
        return sending.substring(0, position);
    }

    /**
     * {@code STRING <sending> DELIMITED BY SIZE}: the whole sending item, every declared character
     * of it.
     *
     * <p>The implementation is the identity function, and the method exists anyway. Naming the phrase
     * at the call site is what makes the four-operand {@code STRING} statements read like the COBOL
     * they came from, and it stops a reader wondering whether the bare literal in the middle of
     * {@link #writeNameLine(HtmlStatementFile, String)} was meant to be delimited by something.
     *
     * @param sending the sending item, at its full declared width
     * @return {@code sending}, unchanged
     * @throws NullPointerException if {@code sending} is {@code null}
     */
    public static String delimitedBySize(final String sending) {
        return Objects.requireNonNull(sending,
                "A sending item is required for STRING ... DELIMITED BY SIZE");
    }

    /**
     * The 0-based index of the first occurrence of {@code delimiter} in {@code sending}, or
     * {@link #DELIMITER_NOT_FOUND}.
     *
     * <p>An explicit left-to-right character scan. It is written out rather than delegated so that a
     * reviewer holding {@code app/cbl/CBSTM03A.CBL} open can see that the search is for the delimiter
     * as a <em>character sequence</em> at every position, which is what COBOL does, and not for any
     * of its characters individually.
     *
     * @param sending   the item being scanned
     * @param delimiter the sequence being looked for; at least one character
     * @return the 0-based index of the first match, or {@link #DELIMITER_NOT_FOUND}
     */
    private static int delimiterPosition(final String sending, final String delimiter) {
        final int lastStart = sending.length() - delimiter.length();
        for (int start = 0; start <= lastStart; start++) {
            boolean matched = true;
            for (int offset = 0; offset < delimiter.length(); offset++) {
                if (sending.charAt(start + offset) != delimiter.charAt(offset)) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return start;
            }
        }
        return DELIMITER_NOT_FOUND;
    }

    // =============================================================================================
    // MOVE SPACES, WRITE ... FROM, and the single emission point.
    // =============================================================================================

    /**
     * {@code MOVE SPACES TO <line>} - blanks a line in the dataset's own code page.
     *
     * <p>Reproduced explicitly at every {@code STRING} site because COBOL's {@code STRING} does not
     * blank its receiver: it overlays from the left and leaves the tail as it found it. Relying on a
     * buffer being freshly allocated instead would work on the first record of a run and quietly
     * leave the previous record's tail behind on every one after it.
     *
     * <p>The pad byte comes from the record itself, so it is {@code x'40'} under EBCDIC and
     * {@code x'20'} under ASCII and this class names no byte constant.
     *
     * @param line the line to blank
     */
    private static void moveSpaces(final FixedWidthRecord line) {
        line.fill(0, line.recordLength(), line.spacePadByte());
    }

    /**
     * {@code WRITE FD-HTMLFILE-REC FROM <source>} - moves the sending line into the record area under
     * the alphanumeric {@code MOVE} rule and then emits the record area.
     *
     * <p>The move is byte-level and its direction is explicit. The record area is space-filled first
     * and the sending bytes are then placed at its <strong>left</strong>, so a shorter source is
     * padded on the right and an over-wide one loses its tail rather than its head - the alphanumeric
     * rule, not the numeric one, which truncates on the left and would corrupt every line here.
     * {@code CBSTM03A} exercises the padding path with {@code HTML-L11}, which is 59 bytes, and the
     * exact path with the four {@code PIC X(100)} lines.
     *
     * <p>The clamp is written as {@code copyOf(sending, min(length, RECORD_LENGTH))} rather than as a
     * conditional deliberately: one expression covers pad, exact and truncate identically, so there is
     * no branch that production input can never reach and no arm that could drift out of step with the
     * other. Every source this class creates is 59 or 100 bytes, so the truncating case is a stated
     * invariant rather than a live path - and stating it as arithmetic keeps it correct without
     * pretending it is exercised.
     *
     * <p>Doing the move at all - rather than handing the source's bytes to the sink directly - is what
     * makes the record area hold what was last written, exactly as it does in COBOL. A parity test
     * asserting on {@link HtmlStatementFile#recordArea()} after a {@code WRITE ... FROM} depends on
     * it.
     *
     * @param file   the open handle
     * @param source the sending line
     * @return the outcome the sink reported
     */
    private FileStatus.Outcome writeFrom(final HtmlStatementFile file,
                                         final FixedWidthRecord source) {
        final byte[] sending = source.toByteArray();
        moveSpaces(file.recordArea);
        file.recordArea.writeBytes(0,
                Arrays.copyOf(sending, Math.min(sending.length, RECORD_LENGTH)));
        return emitRecordArea(file);
    }

    /**
     * The single point at which a record leaves this class: hands the record area's 100 bytes to the
     * sink, counts the record and classifies the reported status.
     *
     * <p>Every emit path funnels through here, so there is exactly one place where the count is
     * maintained and one place where a status becomes an outcome.
     *
     * <p>The 100-byte width (gates G19 and G20) is guaranteed <em>structurally</em> rather than by a
     * runtime re-check: {@link HtmlStatementFile#recordArea} is allocated at {@link #RECORD_LENGTH}
     * and {@link FixedWidthRecord#toByteArray()} returns exactly that many bytes, so a short or long
     * record is not representable. An {@code if} here could never be false, and a branch that
     * production can never take is worse than the invariant it guards - it hides in a coverage report
     * as untested code while proving nothing. The width is asserted where assertions belong, in the
     * tests, for every one of the shapes this class emits.
     *
     * @param file the open handle
     * @return the outcome the sink reported
     * @throws NullPointerException if the sink returns a {@code null} status
     */
    private FileStatus.Outcome emitRecordArea(final HtmlStatementFile file) {
        final byte[] record = file.recordArea.toByteArray();
        final String status = Objects.requireNonNull(file.sink.write(record),
                "A record sink must report a two-character FILE STATUS from write(byte[]), never "
                        + "null");
        file.recordsWritten++;
        return FileStatus.outcomeOfStatus(status);
    }

    // =============================================================================================
    // Guards.
    // =============================================================================================

    /**
     * Rejects a {@code null} or already-closed handle.
     *
     * @param file      the handle to check
     * @param operation what the caller was attempting, for the diagnostic
     * @throws NullPointerException  if {@code file} is {@code null}
     * @throws IllegalStateException if {@code file} has been closed
     */
    private static void requireOpen(final HtmlStatementFile file, final String operation) {
        Objects.requireNonNull(file, "An open " + HTMLFILE_DD_NAME + " handle is required to "
                + operation + "; obtain one from open() or open(HtmlRecordSink)");
        if (!file.open) {
            throw new IllegalStateException("Cannot " + operation + ": this " + HTMLFILE_DD_NAME
                    + " handle is closed. app/cbl/CBSTM03A.CBL opens the file once at L293 and "
                    + "closes it once at L339, so a second close or a write after close means the "
                    + "caller's control flow has diverged from the COBOL's. Open a new handle.");
        }
    }

    /**
     * Verifies that a configured dataset name is safe to interpolate into a SQL identifier position.
     *
     * <p>A SQL identifier cannot be supplied as a bind parameter, so the configured name has to be
     * interpolated - and is therefore checked character by character against
     * {@link #DSNAME_ALLOWED_PUNCTUATION} plus letters and digits. Whitespace, quotation marks,
     * semicolons, comment markers and path separators are all outside that set and are refused, with
     * the offending character and its position named.
     *
     * <p>Refusing a path separator is a feature rather than a limitation. The fixture-backed
     * {@code test} profile binds {@code HTMLFILE} to a filesystem location, so a caller that asks
     * for the default sink under that profile is told, precisely, to inject a sink instead - which is
     * what every unit test and the parity harness do.
     *
     * @param dsname the configured dataset name
     * @return {@code dsname}, unchanged
     * @throws IllegalStateException if {@code dsname} is {@code null}, blank, or contains a character
     *                               outside the allowed set
     */
    private static String requireInterpolatableDsname(final String dsname) {
        if (dsname == null || dsname.isEmpty()) {
            throw new IllegalStateException("carddemo.datasets." + HTMLFILE_DD_NAME
                    + ".dsname is not configured, so no default sink can be built. Configure the "
                    + "dataset name, or open the file with an explicit sink through "
                    + "open(HtmlRecordSink).");
        }
        for (int index = 0; index < dsname.length(); index++) {
            final char character = dsname.charAt(index);
            final boolean allowed = Character.isLetterOrDigit(character)
                    || DSNAME_ALLOWED_PUNCTUATION.indexOf(character) >= 0;
            if (!allowed) {
                throw new IllegalStateException("carddemo.datasets." + HTMLFILE_DD_NAME
                        + ".dsname contains '" + character + "' at position " + index
                        + ", which cannot be interpolated into a SQL identifier. Only letters, "
                        + "digits and " + DSNAME_ALLOWED_PUNCTUATION + " are accepted. A filesystem "
                        + "location - which the fixture-backed 'test' profile binds - is refused "
                        + "here on purpose: open the file with an explicit sink through "
                        + "open(HtmlRecordSink) instead, exactly as the unit tests and the parity "
                        + "harness do.");
            }
        }
        return dsname;
    }
}

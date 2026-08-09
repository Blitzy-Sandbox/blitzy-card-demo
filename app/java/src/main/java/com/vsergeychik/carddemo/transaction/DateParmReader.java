package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.DatasetObservation;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

/**
 * Reads the transaction report's date range out of the {@code DATEPARM} dataset: the Java form of the
 * three {@code DATEPARM} paragraphs of {@code app/cbl/CBTRN03C.cbl}.
 *
 * <h2>{@code DATEPARM} is a dataset. It is never a parameter.</h2>
 * <p><strong>This is the single most important fact about this class, and it is the reason the class
 * exists at all.</strong> {@code DATEPARM} is a DD statement naming a catalogued dataset - see
 * {@code app/jcl/TRANREPT.jcl:L73-L74} and the identical binding at
 * {@code app/proc/TRANREPT.prc:L71-L72}, both {@code DISP=SHR} over a dataset the report step only
 * reads. The COBOL declares it as a file and reads it as a file:
 * <pre>
 * app/cbl/CBTRN03C.cbl:L55-L57
 *     SELECT DATE-PARMS-FILE ASSIGN TO DATEPARM
 *            ORGANIZATION IS SEQUENTIAL
 *            FILE STATUS  IS DATEPARM-STATUS.
 * </pre>
 * The date range is therefore <em>data</em>, arriving through an I/O operation that can succeed, hit
 * end of file, or fail. It is emphatically <strong>not</strong> a batch job parameter, not a value
 * bound from a configuration property expression, and not something a step-scoped bean resolves. The
 * migration plan states the rule in one line: {@code CBTRN03C}'s {@code DATEPARM} DD becomes a
 * {@code DateParmReader} bean, not a job parameter, because the COBOL reads its date range from a
 * dataset. So this bean is a plain injected collaborator, and this file contains no parameter
 * machinery of any kind.
 *
 * <p>The trap is real and it is close by. {@code app/jcl/INTCALC.jcl} genuinely does pass a date-like
 * string as {@code PARM='2022071800'} to a different program, and {@code app/jcl/TRANREPT.jcl:L40-L44}
 * genuinely does carry two date literals - but those two are {@code SYMNAMES} symbols that configure
 * the {@code DFSORT INCLUDE} filter of the <em>sort</em> step, and they never reach
 * {@code CBTRN03C}. Turning either into a bound property would move the range out of the dataset the
 * report step actually reads and quietly change which transactions the report contains.
 *
 * <h2>An 80-byte record read into a 21-byte receiver</h2>
 * <p>The physical record is 80 bytes:
 * <pre>
 * app/cbl/CBTRN03C.cbl:L87-L88
 *     FD  DATE-PARMS-FILE.
 *     01 FD-DATEPARM-REC       PIC X(80).
 * </pre>
 * The receiving group is only 21:
 * <pre>
 * app/cbl/CBTRN03C.cbl:L122-L125
 *     01 WS-DATEPARM-RECORD.
 *         05 WS-START-DATE      PIC X(10).
 *         05 FILLER             PIC X(01).
 *         05 WS-END-DATE        PIC X(10).
 * </pre>
 * so {@code READ DATE-PARMS-FILE INTO WS-DATEPARM-RECORD} at {@code L221} performs an implicit
 * alphanumeric {@code MOVE} of 80 bytes into a 21-byte group, which COBOL right-truncates. Bytes 22
 * through 80 are read off the dataset and then discarded. This class reproduces that exactly, by
 * absolute offset:
 *
 * <table border="1">
 *   <caption>The 80-byte record, decomposed</caption>
 *   <tr><th>Bytes (1-based)</th><th>Offset (0-based)</th><th>Width</th><th>Item</th><th>Fate</th></tr>
 *   <tr><td>1-10</td><td>0</td><td>10</td><td>{@code WS-START-DATE PIC X(10)}</td>
 *       <td>kept, untrimmed</td></tr>
 *   <tr><td>11</td><td>10</td><td>1</td><td>{@code FILLER PIC X(01)}</td>
 *       <td>kept as the separator span</td></tr>
 *   <tr><td>12-21</td><td>11</td><td>10</td><td>{@code WS-END-DATE PIC X(10)}</td>
 *       <td>kept, untrimmed</td></tr>
 *   <tr><td>22-80</td><td>21</td><td>59</td><td>{@code FILLER} - the record tail</td>
 *       <td>read, then discarded</td></tr>
 * </table>
 *
 * <p>{@code 10 + 1 + 10 + 59 = 80}. That sum is not a comment: {@link RecordLayout} runs it as a
 * self-check when this bean is constructed, so a transcription error in the offsets fails the
 * application context immediately instead of shifting a field (gate G21). Every byte is declared,
 * {@code FILLER} included - dropping the 59-byte tail would leave the layout 59 bytes short of 80
 * and would be rejected on the spot.
 *
 * <p><strong>Neither date is trimmed.</strong> Both are {@code PIC X(10)} and the mainline compares
 * them positionally:
 * <pre>
 * app/cbl/CBTRN03C.cbl:L173-L174
 *     IF TRAN-PROC-TS (1:10) &gt;= WS-START-DATE
 *        AND TRAN-PROC-TS (1:10) &lt;= WS-END-DATE
 * </pre>
 * A COBOL alphanumeric comparison pads the shorter operand with spaces, so trailing spaces inside a
 * 10-byte date are part of the comparison and therefore part of the value. Trimming here would change
 * which transactions fall inside the range, which is precisely a parity failure. The decode uses the
 * codec's untrimmed read for both dates, deliberately.
 *
 * <h2>The status ladder, and what an empty dataset does</h2>
 * <p>{@code 0550-DATEPARM-READ} classifies the read into exactly three outcomes:
 * <pre>
 * app/cbl/CBTRN03C.cbl:L221-L229
 *     READ DATE-PARMS-FILE INTO WS-DATEPARM-RECORD
 *     EVALUATE DATEPARM-STATUS
 *       WHEN '00'   MOVE  0 TO APPL-RESULT
 *       WHEN '10'   MOVE 16 TO APPL-RESULT
 *       WHEN OTHER  MOVE 12 TO APPL-RESULT
 *     END-EVALUATE
 * </pre>
 * and the guard chain that follows it, at {@code L231-L243}, displays the range when the read
 * succeeded, sets the mainline's end-of-file flag on {@code '10'}, and otherwise displays an error,
 * renders the status and abends.
 *
 * <p>The {@code '10'} arm has a consequence that is easy to miss and must stay reachable:
 * {@code 0550-DATEPARM-READ} is performed at {@code L168}, <em>before</em> the report loop
 * {@code PERFORM UNTIL END-OF-FILE = 'Y'} at {@code L170}. So an <strong>empty {@code DATEPARM}
 * dataset sets the loop's end-of-file flag before a single transaction is read, and the job produces
 * no report body at all</strong> - no page, no account total, no grand total. That is real,
 * observable legacy behaviour, not a defect to be papered over, so {@link #read()} reports end of
 * file for an empty dataset rather than substituting a default range or refusing to start.
 *
 * <h2>What this class deliberately does not do</h2>
 * <p>It does not abend, it does not log, and it does not format a message. {@link #read()} returns a
 * discriminated {@link ReadResult} and stops there; {@link #open()} and {@link #close()} return a
 * two-character file status and stop there. The {@code DISPLAY 'Reporting from ' ...} line at
 * {@code L232-L233}, the {@code DISPLAY 'ERROR READING DATEPARM FILE'} line at {@code L238}, their
 * open and close counterparts at {@code L477} and {@code L616}, the status rendering by
 * {@code 9910-DISPLAY-IO-STATUS} and the abend at {@code 9999-ABEND-PROGRAM} all belong to the report
 * job. Keeping every emitted line in one class is what keeps the job's SYSOUT fingerprint in one
 * place and byte-comparable; a reader that logged on its own behalf would put half the fingerprint
 * here and make the other half impossible to assert.
 *
 * <p>The division of labour is drawn at exactly the point the COBOL draws it. This class is the
 * {@code READ}, the {@code OPEN INPUT} and the {@code CLOSE} together with the {@code EVALUATE} that
 * classifies each one's status - see {@link ReadResult#applResult()}, which is that
 * {@code EVALUATE}'s result value. The job is everything after it.
 *
 * <h2>No conversation state, and no cursor</h2>
 * <p>This bean holds no mutable state whatsoever and caches nothing: the decoded range is a returned
 * value, never a field. That is not merely tidy, it is provably sufficient, because
 * <strong>{@code CBTRN03C} reads {@code DATEPARM} exactly once</strong> - the sole {@code READ}
 * against it is {@code L221}, reached from the single {@code PERFORM} at {@code L168}, and no other
 * statement in the program touches the file except the open at {@code L166} and the close at
 * {@code L213}. There is no read loop over this dataset, so there is no sequence position to keep,
 * so no cursor exists to be shared, and the bean is safe to inject anywhere. Should some later caller
 * ever need a second sequential position, it must own that position itself; this class will not grow
 * one, because a stateful reader would silently couple two callers together.
 *
 * <p>By the same rule the dataset holds one record by construction. {@code app/cbl/CORPT00C.cbl}
 * writes exactly one line under its {@code //STEP10R.DATEPARM DD *} card, and the single COBOL
 * {@code READ} would ignore any surplus record anyway. {@link #read()} therefore takes the first row
 * and ignores the rest, which is what that one {@code READ} does.
 *
 * <h2>The online writer proves the layout independently</h2>
 * <p>The strongest available evidence for the 10 / 1 / 10 prefix inside 80 bytes is that another
 * program in this estate <em>writes</em> exactly the record this class reads.
 * {@code app/cbl/CORPT00C.cbl} builds a JCL skeleton as a table of 80-byte lines -
 * {@code JOB-DATA-2 REDEFINES JOB-DATA-1} with {@code 05 JOB-LINES OCCURS 1000 TIMES PIC X(80)} - and
 * its fifteenth line, immediately after the {@code //STEP10R.DATEPARM DD *} card at {@code L116}, is:
 * <pre>
 * app/cbl/CORPT00C.cbl:L117-L121
 *     05 FILLER-3.
 *        10 PARM-START-DATE-2       PIC X(10) VALUE SPACES.
 *        10 FILLER                  PIC X     VALUE SPACE.
 *        10 PARM-END-DATE-2         PIC X(10) VALUE SPACES.
 *        10 FILLER                  PIC X(59) VALUE SPACES.
 * </pre>
 * Ten, one, ten, fifty-nine. The same four spans, in the same order, summing to the same 80 bytes -
 * derived from the writer's copybook rather than inferred from the reader's, and agreeing with it
 * exactly. The separator is a literal space there, which is why {@link DateParm#separator()} is
 * surfaced rather than assumed: it is a byte of the record, so it is reported as one.
 *
 * <h2>The JDBC driver is a deployment-time input (residual risk R-E)</h2>
 * <p>This class reaches the dataset through the module's single {@link JdbcTemplate}, whose
 * {@code DataSource} is entirely configuration-bound: {@code app/java/pom.xml} pins no driver
 * coordinate, because there is not one embedded SQL statement in any of the twenty-eight COBOL
 * programs and indexed VSAM has no standard published driver. The site's data-access driver is
 * supplied at deployment time.
 *
 * <p>The consequence is stated plainly rather than absorbed: <strong>production connectivity cannot
 * be exercised in this build environment</strong>, so this reader is validated against its decode
 * seam and a substituted template instead of against a real backend. Two specific things are therefore
 * deployment-time inputs, and both are isolated so a site can see them at a glance:
 * <ul>
 *   <li><strong>How the dataset is named as a relation.</strong> The name comes from configuration
 *       and is quoted as a single SQL delimited identifier, which is the standard way to name a
 *       relation whose name contains periods. A driver that catalogues the dataset differently needs
 *       that one binding changed, not this class.</li>
 *   <li><strong>Where the record image sits in the row.</strong> The row is read by
 *       <em>column position</em> - {@value #RECORD_IMAGE_COLUMN_INDEX} - and never by column name,
 *       because a dataset with no relational metadata is presented as a single record-image column
 *       and inventing a name for it would be exactly the kind of unverifiable literal this migration
 *       forbids.</li>
 * </ul>
 * A failure in either lands on a non-{@code '00'} status, which is the same guard the COBOL already
 * has for an I/O failure, so the program's branch structure is preserved whichever way it fails.
 *
 * <p>What is <em>not</em> a deployment-time input is <strong>which</strong> guard a failure lands on.
 * {@code CBTRN03C} has two: {@code 0500-DATEPARM-OPEN} at {@code L466-L482} displays
 * {@code 'ERROR OPENING DATE PARM FILE'} and {@code 0550-DATEPARM-READ} at {@code L220-L243} displays
 * {@code 'ERROR READING DATE PARM FILE'}. {@link #open()} therefore resolves this dataset read-only
 * rather than merely establishing that a connection can be obtained, so an absent {@code DATEPARM}
 * relation fails at the open - where the COBOL fails - instead of being reported as a successful open
 * followed by a read error with the wrong message.
 *
 * <p>Every reported failure also writes a diagnostic naming the operation, the DD name, the dataset and
 * the rendered status, carrying the translated cause where there is one. The status remains the whole of
 * what the <em>caller</em> receives, exactly as the COBOL keeps nothing but {@code DATEPARM-STATUS}; the
 * cause is not discarded, because a two-character status names no reason. No record value is ever
 * logged - this record holds the report's date range and its trailing {@code FILLER} verbatim.
 *
 * <h2>Statuses model I/O outcomes; exceptions model contract violations</h2>
 * <p>The two are kept apart on purpose. An I/O outcome the COBOL enumerates - success, end of file,
 * or anything else - is reported as a status, because the caller's guard chain is built to branch on
 * exactly that. A violation of this class's own contract - a dataset binding that is absent or
 * declares the wrong width, a row wider than the record it is supposed to be - is thrown, because
 * folding it into a {@code '9x'} status would let a misconfiguration masquerade as a transient I/O
 * error and be abended over with no clue as to the cause.
 *
 * <h2>User-specified rules, and the practices that stand in for them</h2>
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - and that one line is
 * the whole document, so <strong>no user rule governs this file</strong>. Its absence is not licence
 * to lower the bar; the migration plan elevates twelve enterprise practices to binding constraints
 * instead, and the ones bearing on this file are B2 (the closed dependency set only - Spring JDBC's
 * template, and no parameter machinery), B3 (the COBOL, JCL and PROC trees cited throughout are
 * read-only and are cited purely as provenance - gate G5), B8 (explicit over implicit: explicit
 * imports with no wildcard, the code page injected and handed to the codec rather than defaulted, and
 * no dataset name written in Java - gates G46 and G52), B9 (no mutable static state; every
 * collaborator constructor-injected; the decoded range returned, never cached - gate G53), B11 (a
 * hand-written codec addressing absolute offsets, so every offset is reviewable against the copybook
 * - gate G21) and B12 (the environmental limit above is documented and escalated, not absorbed).
 *
 * <p>Nothing here is schema-shaped either (gate G44): the only statement this class issues reads
 * rows, there is no data-definition statement of any kind, no persistence mapping annotation, no
 * migration script and no generated relation. The dataset is read exactly as it already exists.
 *
 * <h2>Why {@code @Component} and not the data-access stereotype</h2>
 * <p>The migration plan's structural inventory counts this module's data-access classes precisely -
 * twelve repositories, three output writers and exactly one parameter reader, which is this class -
 * and gate G10 checks that count. Annotating the parameter reader with the data-access stereotype
 * would make a mechanical scan report thirteen repositories and quietly break an auditable count, so
 * the generic component stereotype is used. It changes no behaviour: nothing here needs exception
 * translation, and the bean is an ordinary singleton either way.
 *
 * @see FileStatus
 * @see FixedWidthCodec
 */
@Component
public class DateParmReader {

    /**
     * Logger for the diagnostics this reader emits when an operation fails.
     *
     * <p>{@code static final} and a reference to an immutable logger, so it introduces no shared mutable
     * state. It exists because what travels back to the caller is deliberately coarse - a two-character
     * file status, exactly as {@code DATEPARM-STATUS} is - and a discarded cause would leave a
     * production failure with nothing to diagnose it from. Only the operation, the DD name, the dataset
     * name and the rendered status are written; never a record value, because this record holds the
     * report's date range and the surrounding {@code FILLER} verbatim.
     */
    private static final Log LOG = LogFactory.getLog(DateParmReader.class);

    /**
     * The DD name under which the report date range is catalogued: {@code DATEPARM}.
     *
     * <p>A DD name, not a dataset name. It is the key this class resolves against
     * {@code carddemo.datasets} and it is spelled exactly as {@code app/cbl/CBTRN03C.cbl:L55} and
     * {@code app/jcl/TRANREPT.jcl:L73} spell it, which is what lets a reviewer diff the configuration
     * against the JCL line by line. The dataset it resolves to lives in configuration alone and
     * appears nowhere in Java (gate G46).
     */
    public static final String DD_NAME = "DATEPARM";

    /**
     * The physical record width in bytes: {@code 80}, from
     * {@code app/cbl/CBTRN03C.cbl:L87-L88 01 FD-DATEPARM-REC PIC X(80)}.
     *
     * <p>Asserted against the configured {@code record-length} of the resolved binding when this bean
     * is constructed, so a configuration that disagrees with the file description fails the
     * application context rather than reading a differently-shaped record.
     */
    public static final int RECORD_LENGTH = 80;

    /**
     * The width of the receiving group in bytes: {@code 21}, from
     * {@code app/cbl/CBTRN03C.cbl:L122-L125}. Everything past this is discarded by the
     * {@code READ ... INTO}, which is what {@link #DISCARDED_TAIL_LENGTH} accounts for.
     */
    public static final int RECEIVER_LENGTH = 21;

    /** Absolute 0-based offset of {@code WS-START-DATE}: {@code 0} - bytes 1 to 10. */
    public static final int START_DATE_OFFSET = 0;

    /** Declared width of {@code WS-START-DATE PIC X(10)}: {@code 10}. */
    public static final int START_DATE_LENGTH = 10;

    /** Absolute 0-based offset of the {@code FILLER PIC X(01)} separator: {@code 10} - byte 11. */
    public static final int SEPARATOR_OFFSET = 10;

    /** Declared width of the {@code FILLER PIC X(01)} separator: {@code 1}. */
    public static final int SEPARATOR_LENGTH = 1;

    /** Absolute 0-based offset of {@code WS-END-DATE}: {@code 11} - bytes 12 to 21. */
    public static final int END_DATE_OFFSET = 11;

    /** Declared width of {@code WS-END-DATE PIC X(10)}: {@code 10}. */
    public static final int END_DATE_LENGTH = 10;

    /**
     * Absolute 0-based offset of the record tail the {@code READ ... INTO} discards: {@code 21} -
     * bytes 22 to 80.
     */
    public static final int DISCARDED_TAIL_OFFSET = 21;

    /**
     * Width of the discarded record tail: {@code 59} bytes.
     *
     * <p>{@code 21 + 59 = 80}. Declared rather than ignored so the layout's own arithmetic proves the
     * record is 80 bytes wide (gate G21), and confirmed independently by the writer's
     * {@code FILLER PIC X(59) VALUE SPACES} at {@code app/cbl/CORPT00C.cbl:L121}.
     */
    public static final int DISCARDED_TAIL_LENGTH = 59;

    /**
     * The copybook name of the start-date item, verbatim: {@code WS-START-DATE}
     * ({@code app/cbl/CBTRN03C.cbl:L123}).
     *
     * <p>Carried verbatim because the parity differ compares field by field <em>by name</em>, so a
     * tidied-up name would make a real difference invisible.
     */
    public static final String START_DATE_FIELD = "WS-START-DATE";

    /**
     * The copybook name of the end-date item, verbatim: {@code WS-END-DATE}
     * ({@code app/cbl/CBTRN03C.cbl:L125}).
     */
    public static final String END_DATE_FIELD = "WS-END-DATE";

    /**
     * The 1-based result-set column position the record image is read from: {@code 1}.
     *
     * <p>Position, never name. A dataset that carries no relational metadata is presented as a single
     * record-image column, and inventing a name for that column would be an unverifiable literal of
     * exactly the kind gate G46 exists to prevent. See the residual risk R-E discussion on this
     * class.
     */
    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    /**
     * The {@code APPL-RESULT} value the COBOL moves for the {@code WHEN OTHER} arm: {@code 12}
     * ({@code app/cbl/CBTRN03C.cbl:L228}). Its two companions are already published as
     * {@link FileStatus#APPL_AOK} and {@link FileStatus#APPL_EOF}, so only this third value is
     * declared here.
     */
    public static final int APPL_RESULT_FATAL = 12;

    /**
     * The VSAM extended-status feedback code this reader reports for a permanent error: binary zero.
     *
     * <p>Not a magic number. z/OS COBOL reports an implementor-defined permanent error as {@code '9'}
     * in the first status byte with a binary feedback code in the second, and
     * {@code 9910-DISPLAY-IO-STATUS} in {@code app/cbl/CBTRN03C.cbl} is written specifically to decode
     * that form - its {@code IO-STAT1 = '9'} branch deposits the second byte into a
     * {@code PIC 9(4) BINARY} through a {@code REDEFINES} and renders it as three decimal digits.
     * Feedback code zero is the generic "permanent error, no more specific code available", which is
     * the honest report for a backend this class cannot interrogate further.
     */
    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The two-character file status this reader reports for a permanent error: {@code '9'} followed by
     * {@link #PERMANENT_ERROR_FEEDBACK_CODE}.
     *
     * <p>It renders as {@code "9000"} through {@link FileStatus#toStatusImage(String)}, and therefore
     * as {@code FILE STATUS IS: NNNN9000} through {@link FileStatus#toDisplayLine(String)} - the
     * exact line {@code 9910-DISPLAY-IO-STATUS} produces for this status, and one of the worked
     * examples that method already documents. So the value is not invented for this class: it is the
     * extended-status form the shared status vocabulary was built to render.
     *
     * <p>It is deliberately <em>not</em> one of the statuses the COBOL enumerates.
     * {@link FileStatus#outcomeOfStatus(String)} classifies it as {@link Outcome#OTHER}, which is the
     * {@code WHEN OTHER} arm, which is the abend path - exactly where an I/O failure belongs.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    /**
     * A predicate that is false on every row, so the relation can be resolved and described without any
     * of it being transferred. Two literals compared, which every SQL dialect accepts.
     */
    private static final String NEVER_TRUE_PREDICATE = "1 = 0";

    /**
     * The row limit this reader asks for: {@code CBTRN03C} performs one {@code READ} of
     * {@code DATE-PARM-FILE} and consumes exactly one record.
     */
    private static final int SINGLE_ROW = 1;

    /**
     * The module's single {@link JdbcTemplate}, constructor-injected. The only collaborator that
     * touches the backend, and the seam a unit test replaces.
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * The hand-written fixed-width codec, built over the injected dataset code page. Immutable and
     * stateless, so it is shared safely by every call.
     */
    private final FixedWidthCodec codec;

    /**
     * The one representation this reader's row transfer uses.
     *
     * <p>Injected rather than chosen here. This reader used {@code getString}, the statement writers of the
     * same deployment bound {@code setBytes}, and nothing reconciled the two. The date-parameter record is
     * the least sensitive record in the estate and the most consequential to misread: its 21 leading bytes
     * are the report's date range, so a conversion that shifted or substituted one byte would produce a
     * report over a different period, with no error anywhere to say so.
     */
    private final RecordImageForm recordImageForm;

    /**
     * The physical-record ordinal this dataset's read is ordered by: the deployment's answer to how a
     * stored record's position is recovered, injected rather than decided here.
     *
     * <p>{@value #DD_NAME} is a <strong>physical-sequential</strong> dataset written by
     * {@code app/cbl/CORPT00C.cbl:L117-L121} into the transient-data queue this job's DD is fed from, and
     * {@code 0550-DATEPARM-READ} takes its <em>first</em> record. "First" is only meaningful against an
     * order, and SQL supplies none unless a statement says which - so the read names the ordinal. See
     * {@link PhysicalSequence}.
     */
    private final PhysicalSequence physicalSequence;

    /**
     * The dataset as this module reaches it: the validated name, its delimited rendering, and the
     * statements composed over it.
     *
     * <p>The module's one data-access contract, shared with every repository, so that how a configured
     * dataset name becomes a SQL identifier is decided in a single place rather than once per class.
     */
    private final DatasetRelation relation;


    /**
     * The 80-byte record layout, self-checked at construction (gate G21). Deeply immutable: a record
     * holding an immutable list of records.
     */
    private final RecordLayout layout;

    /** Descriptor for {@code WS-START-DATE PIC X(10)} at offset 0. */
    private final FieldSpan startDateSpan;

    /** Descriptor for the {@code FILLER PIC X(01)} separator at offset 10. */
    private final FieldSpan separatorSpan;

    /** Descriptor for {@code WS-END-DATE PIC X(10)} at offset 11. */
    private final FieldSpan endDateSpan;

    /**
     * Resolves the {@code DATEPARM} binding, proves the record geometry, and captures the collaborators
     * this reader needs - all of it before the context finishes starting, so nothing that can be
     * checked early is left to fail mid-job.
     *
     * <p>Four things are established here, in this order, and each fails loudly rather than degrading:
     * <ol>
     *   <li>the collaborators are present - a missing one is a wiring defect, reported as such;</li>
     *   <li>the {@link #DD_NAME} binding exists - {@link DatasetBindings#binding(String)} matches the
     *       key exactly, with no case-insensitive or fuzzy fallback, and names every configured key in
     *       its diagnostic if it is absent;</li>
     *   <li>the configured record length is {@link #RECORD_LENGTH}. A binding that declares any other
     *       width is a configuration defect: this reader decodes by absolute offset against the file
     *       description in {@code app/cbl/CBTRN03C.cbl:L87-L88}, so reading a differently-sized record
     *       would silently misplace both dates. It is rejected here rather than tolerated;</li>
     *   <li>the layout's own arithmetic proves the four spans account for exactly 80 bytes.</li>
     * </ol>
     *
     * <p>The code page is injected and handed straight to the codec, never derived and never left to
     * the platform (practice B8). The dataset code-page bean is the one the whole data layer takes, so
     * the encoding of dataset input is governed by a single property rather than by a decision repeated
     * at each call site.
     *
     * @param jdbcTemplate   the module's shared template, from the data-access configuration
     * @param datasetBindings the DD-name-keyed dataset catalogue bound from {@code carddemo.datasets}
     * @param datasetCharset the active dataset code page, selected by bean name so the choice is
     *                       explicit at the injection point
     * @param recordImageForm how the deployment's driver presents a record image over JDBC, from
     *                        {@value RecordImageForm#FORM_PROPERTY}
     * @param physicalSequence the physical-record ordinal this dataset's read is ordered by, from
     *                        {@value PhysicalSequence#EXPRESSION_PROPERTY}. {@value #DD_NAME} is
     *                        physical-sequential and this reader takes its <em>first</em> record, so
     *                        without an ordinal "first" would be whichever row the backend handed over
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if no binding is configured for {@link #DD_NAME}, if that binding
     *                               declares a record length other than {@link #RECORD_LENGTH}, or if
     *                               its dataset name is unusable
     */
    public DateParmReader(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm,
            PhysicalSequence physicalSequence) {

        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + "report date range is read from the " + DD_NAME + " dataset through the module's "
                + "shared template");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "dataset names live in configuration and are never written in Java");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset is "
                + "required: a fixed-width mainframe record is bytes in a specific code page, so the "
                + "code page is stated explicitly and never taken from the platform"));
        this.recordImageForm = Objects.requireNonNull(recordImageForm, "A record-image representation is "
                + "required: whether this deployment's driver presents a record image as characters or as "
                + "bytes is stated once, by " + RecordImageForm.FORM_PROPERTY + ", and never decided per "
                + "reader");
        this.physicalSequence = Objects.requireNonNull(physicalSequence, "A physical-record ordinal is "
                + "required: " + DD_NAME + " is a physical-sequential dataset and 0550-DATEPARM-READ "
                + "takes its FIRST record, so which record that is depends on the order the dataset is "
                + "read in - and SQL returns rows in no order unless a statement says which. It is "
                + "stated once, by " + PhysicalSequence.EXPRESSION_PROPERTY + ", and never decided per "
                + "reader");
        RecordImageForm.requireSingleByteCodePage(datasetCharset);

        DatasetBinding binding = datasetBindings.binding(DD_NAME);
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' declares a "
                    + "record length of " + binding.recordLength() + ", but this reader decodes by "
                    + "absolute offset against a " + RECORD_LENGTH + "-byte record - "
                    + "app/cbl/CBTRN03C.cbl:L87-L88 declares 01 FD-DATEPARM-REC PIC X(" + RECORD_LENGTH
                    + "). Reading a differently-sized record would misplace both dates, so the "
                    + "disagreement is rejected here. Correct carddemo.datasets." + DD_NAME
                    + ".record-length to " + RECORD_LENGTH + ".");
        }
        this.relation = DatasetRelation.of(requireUsableDatasetName(binding.dsname()), RECORD_LENGTH);


        this.startDateSpan =
                FieldSpan.alphanumeric(START_DATE_FIELD, START_DATE_OFFSET, START_DATE_LENGTH);
        this.separatorSpan = FieldSpan.filler(SEPARATOR_OFFSET, SEPARATOR_LENGTH);
        this.endDateSpan = FieldSpan.alphanumeric(END_DATE_FIELD, END_DATE_OFFSET, END_DATE_LENGTH);
        // The self-check inside RecordLayout is the gate G21 proof: the spans must be contiguous from
        // offset 0 and must sum to exactly RECORD_LENGTH, FILLER included. 10 + 1 + 10 + 59 = 80.
        this.layout = RecordLayout.of(RECORD_LENGTH,
                this.startDateSpan,
                this.separatorSpan,
                this.endDateSpan,
                FieldSpan.filler(DISCARDED_TAIL_OFFSET, DISCARDED_TAIL_LENGTH));
    }

    /**
     * The resolved dataset name, exactly as configuration declares it.
     *
     * <p>Exposed for diagnostics and for a caller that wants to report which dataset it read - not as
     * a way to reach around this class. The value is configured, never hard-coded, so surfacing it
     * introduces no dataset literal into Java (gate G46).
     *
     * @return the configured dataset name for {@link #DD_NAME}; never {@code null} and never blank
     */
    public String datasetName() {
        return relation.dsname();
    }

    // =================================================================================================
    // 0500-DATEPARM-OPEN and 9500-DATEPARM-CLOSE - app/cbl/CBTRN03C.cbl:L466-L482 and L605-L621.
    //
    // The two paragraphs are byte-for-byte identical apart from the verb and the message they display,
    // so they share one probe here. They stay two methods because the caller's sequence and message
    // differ: the open is performed at L166, before the single read at L168, and the close at L213,
    // after the report loop has finished.
    // =================================================================================================

    /**
     * Opens the {@code DATEPARM} dataset for input, reporting only the resulting file status: the Java
     * form of {@code 0500-DATEPARM-OPEN} ({@code app/cbl/CBTRN03C.cbl:L466-L482}).
     *
     * <p>The COBOL is a two-way test - {@code IF DATEPARM-STATUS = '00'} moves {@code 0} into
     * {@code APPL-RESULT}, anything else moves {@code 12} - and this method reproduces exactly that
     * shape: {@link FileStatus#OK} or a non-{@code '00'} status, and nothing more. The caller owns the
     * guard chain, the {@code DISPLAY 'ERROR OPENING DATE PARM FILE'} line at {@code L477} and the
     * abend, as documented on this class.
     *
     * <p><strong>What "open" means against a configuration-bound driver, precisely.</strong> There is
     * no persistent handle to acquire: the shared template borrows and returns a connection per
     * operation. So the check performed here is a <em>describe of the dataset</em> - a statement that
     * names it and returns no row - which is dialect-free, transfers nothing, and fails when the dataset
     * is absent or unreachable. That is genuinely what an {@code OPEN INPUT} reports, and it is stronger
     * than merely proving a connection can be obtained: a connection-only check succeeds against a
     * reachable backend that has no such dataset, and would then hand the job an end-of-file it should
     * have seen as a failed open. Residual risk R-E still applies to the driver itself, which this build
     * cannot exercise.
     *
     * <p><strong>Why the dataset and not just the connection.</strong> {@code CBTRN03C} distinguishes an
     * open failure from a read failure: {@code 0500-DATEPARM-OPEN} at {@code L466-L482} displays
     * {@code 'ERROR OPENING DATE PARM FILE'}, while {@code 0550-DATEPARM-READ} at {@code L220-L243}
     * displays {@code 'ERROR READING DATE PARM FILE'} - two paragraphs, two messages, two branches. A
     * probe that only established that a connection could be obtained let an <em>absent</em>
     * {@code DATEPARM} relation report a successful open and then surface on the read path, so the job
     * emitted the wrong message and took the wrong branch. Resolving the relation here puts the failure
     * where the COBOL puts it.
     *
     * @return {@link FileStatus#OK} when the dataset is addressable, otherwise
     *         {@link #PERMANENT_ERROR_STATUS}; never {@code null}, always two characters
     */
    public String open() {
        return probeDatasetAvailability("OPEN INPUT");
    }

    /**
     * Closes the {@code DATEPARM} dataset, reporting only the resulting file status: the Java form of
     * {@code 9500-DATEPARM-CLOSE} ({@code app/cbl/CBTRN03C.cbl:L605-L621}).
     *
     * <p>Same two-way shape as {@link #open()}, and the caller likewise owns the
     * {@code DISPLAY 'ERROR CLOSING DATE PARM FILE'} line at {@code L616} and the abend.
     *
     * <p>Nothing is buffered and nothing is held between calls, so there is no flush to fail and no
     * handle to release. The one close-time failure this reader can genuinely detect is that the dataset
     * is no longer describable, which is the analogue of the file system reporting a problem when the
     * dataset is de-allocated - so the same probe as {@link #open()} is used, which also keeps both of
     * the COBOL's symmetric guards reachable rather than leaving one dead.
     *
     * @return {@link FileStatus#OK} when the dataset is still describable, otherwise
     *         {@link #PERMANENT_ERROR_STATUS}; never {@code null}, always two characters
     */
    public String close() {
        return probeDatasetAvailability("CLOSE");
    }

    /**
     * The shared open/close probe: describe the dataset and confirm it presents a record-image column.
     *
     * <p>The COBOL verb is passed in rather than inferred, because the two callers are the two
     * symmetric guards {@code CBTRN03C} keeps distinct - {@code 0500-DATEPARM-OPEN} and
     * {@code 9500-DATEPARM-CLOSE} - and an operator reading a refusal needs to know which one failed.
     *
     * <p>Read-only and dialect-free - the predicate is false on every row, which every dialect accepts -
     * so it costs one round trip and transfers nothing. A backend that describes the dataset with no
     * column at the record-image position is treated as unusable rather than as success: with the driver
     * a deployment-time input, reporting success on a relation this reader cannot read would hand the job
     * a range it never actually read.
     *
     * <p>The extractor deliberately never calls {@code next()}: there are no rows by construction, and
     * asking for the metadata is enough to prove the relation resolved. A driver that answers with no
     * metadata at all is treated as unusable rather than as success - with the driver a deployment-time
     * input, reporting success on an unusable answer would hand the job a range it never actually read.
     *
     * @param verb the COBOL verb being reproduced, for the diagnostic
     * @return {@link FileStatus#OK} or {@link #PERMANENT_ERROR_STATUS}
     */
    private String probeDatasetAvailability(String cobolOperation) {
        // A describe of the dataset, not a test of the connection. The predicate is false on every row,
        // so nothing is transferred - but the statement names the dataset, so an absent or unreachable
        // dataset fails here, which is exactly what a COBOL OPEN reports and what a connection-only
        // check cannot see.
        ResultSetExtractor<String> describe = resultSet -> {
            ResultSetMetaData metaData = resultSet.getMetaData();
            return metaData == null || metaData.getColumnCount() < RECORD_IMAGE_COLUMN_INDEX
                    ? PERMANENT_ERROR_STATUS
                    : FileStatus.OK;
        };
        try {
            String status = jdbcTemplate.query(relation.describeStatement(), describe);
            // A template that yields no status at all has told us nothing, and "nothing" is not
            // success. Reported as a permanent error, exactly as an unusable dataset is.
            return status == null ? PERMANENT_ERROR_STATUS : status;
        } catch (DataAccessException translated) {
            // The COBOL keeps no more than the status - it displays the rendered status and abends - so
            // the status is the whole of what is *returned*. What the backend said is not thrown away
            // with it: an operator reading the abend needs to know whether the dataset was missing, the
            // credentials were refused or the backend was unreachable, and only the driver knows that.
            logRefusal(translated, cobolOperation + " the " + DD_NAME + " dataset (a describe of the "
                    + "configured relation, which is what distinguishes an absent dataset from an "
                    + "unreadable record)");
            return PERMANENT_ERROR_STATUS;
        }
    }

    /**
     * Logs a backend refusal with the driver's own diagnosis and returns it.
     *
     * <p>Logged: what was attempted and the {@code SQLSTATE}, vendor code and exception type the driver
     * reported. Not logged: the record image - the date-parameter record is innocuous, but the rule is
     * the module's and is applied uniformly rather than judged per dataset - and not the exception
     * either. A driver's message is prose it composed around the values it refused, so handing the
     * {@link Throwable} to the logger emits that text and its whole cause chain verbatim (CWE-532) in a
     * form a control character can split into a forged entry (CWE-117). Sanitising the summary and
     * attaching the raw exception beside it sanitises nothing.
     *
     * @param refusal the exception raised
     * @param attempt what was being attempted, phrased to complete "Could not ..."
     * @return the diagnostic read out of {@code refusal}
     */
    private static BackendDiagnostic logRefusal(Throwable refusal, String attempt) {
        BackendDiagnostic diagnostic = BackendDiagnostic.of(refusal);
        LOG.error("Could not " + attempt + " - " + diagnostic.describe() + "; reporting file status "
                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
        return diagnostic;
    }

    // =================================================================================================
    // 0550-DATEPARM-READ - app/cbl/CBTRN03C.cbl:L220-L243, performed exactly once, from L168.
    // =================================================================================================

    /**
     * Reads the date range: the Java form of {@code 0550-DATEPARM-READ}'s {@code READ} and the
     * {@code EVALUATE} that classifies its status ({@code app/cbl/CBTRN03C.cbl:L221-L229}).
     *
     * <p>Three outcomes, and exactly the three the COBOL enumerates:
     * <ul>
     *   <li><strong>found</strong> - status {@code '00'}, carrying the decoded {@link DateParm}. The
     *       COBOL moves {@code 0} to {@code APPL-RESULT} and the caller displays the range;</li>
     *   <li><strong>end of file</strong> - status {@code '10'}, carrying no range, when the dataset
     *       holds no record. The COBOL moves {@code 16} and the caller sets its end-of-file flag,
     *       which - because the read happens before the report loop - means the job emits no report
     *       body at all;</li>
     *   <li><strong>other</strong> - any other status, carrying it verbatim. The COBOL moves
     *       {@code 12} and the caller displays the error, renders the status and abends.</li>
     * </ul>
     * Nothing is displayed, logged, formatted or abended here; see this class's documentation for why
     * that boundary sits exactly where it does.
     *
     * <p>The first row is the record. The dataset holds one by construction - the online writer emits
     * exactly one line under its inline data card - and the single COBOL {@code READ} would consume
     * only the first in any case, so a surplus row is ignored precisely as that one {@code READ}
     * ignores it. No state is kept, so calling this method again re-reads the same first record; that
     * is faithful, because the program performs this paragraph once and never resumes from a saved
     * position.
     *
     * <p><strong>A row that is not exactly {@link #RECORD_LENGTH} bytes is reported, not repaired.</strong>
     * The record is fixed-length, so widening a short one with spaces looks like the faithful repair -
     * the absent bytes could only be trailing {@code FILLER}, and a {@code FILLER} carrying no literal
     * holds spaces. What makes that reasoning wrong <em>here</em> is what the record is: the very next
     * thing done to it is the deliberate 80-into-21 move of {@code READ ... INTO WS-DATEPARM-RECORD},
     * which takes the first 21 bytes as the range. A row truncated inside {@code WS-END-DATE} pads to a
     * different end date - {@code '2022-07-06'} becomes {@code '2022-07   '} - and the job then reports a
     * different range, successfully and silently, with no operator anywhere told that the parameter it
     * ran on was not the parameter it was given. The report is wrong and nothing says so.
     *
     * <p>So the width is required first. {@code app/cbl/CBTRN03C.cbl:L87-L88} declares
     * {@code 01 FD-DATEPARM-REC PIC X(80)} and {@code app/cbl/CORPT00C.cbl:L117-L121} emits exactly 80
     * bytes into the queue this dataset is fed from, so a row of any other width can only mean the
     * backend is not serving this layout. It is reported on the {@code WHEN OTHER} arm, which is where
     * {@code CBTRN03C} displays {@code 'ERROR READING DATEPARM FILE'}, renders the status and abends -
     * exactly the path a parameter it cannot trust should take.
     *
     * @return the discriminated outcome; never {@code null}
     */
    public ReadResult read() {
        List<byte[]> rows;
        try {
            rows = jdbcTemplate.query(firstRowOnly(selectRecordSql()), this::mapRecordImage);
        } catch (DataAccessException translated) {
            // WHEN OTHER. An I/O failure, reported as a status so the caller's own guard chain decides
            // what to do about it - which, in CBTRN03C, is to display and abend - and carrying the
            // backend's own diagnosis so that abend can be traced to a cause.
            return ReadResult.other(PERMANENT_ERROR_STATUS,
                    logRefusal(translated, "read the " + DD_NAME + " dataset"));
        }
        if (rows == null || rows.isEmpty()) {
            // WHEN '10'. AT END with no record read: the empty-dataset case, which leaves the report
            // body empty. This is real legacy behaviour and is reported, never substituted.
            return ReadResult.endOfFile();
        }
        byte[] recordImage = rows.get(0);
        if (recordImage == null) {
            // A row whose record image is absent is not a readable 80-byte record. It is an I/O-level
            // defect rather than an end of file - there IS a record, it just cannot be read - so it is
            // reported on the WHEN OTHER arm and not mistaken for an empty dataset. No backend refusal
            // occurred, so there is no diagnostic to carry.
            LOG.error("The " + DD_NAME + " dataset presented a row with no record image at column "
                    + "position " + RECORD_IMAGE_COLUMN_INDEX + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
            return ReadResult.other(PERMANENT_ERROR_STATUS);
        }
        if (recordImage.length != RECORD_LENGTH) {
            // WHEN OTHER. Reported rather than padded, because the 80-into-21 receiver move that follows
            // would turn a truncated row into a different date range and the report would come out wrong
            // with nothing said. The observed width is named in the log line, labelled as what it is.
            LOG.error("The " + DD_NAME + " dataset presented a row whose "
                    + DatasetObservation.recordWidth(recordImage.length).describe() + ", but "
                    + "FD-DATEPARM-REC is declared PIC X(" + RECORD_LENGTH + ") by "
                    + "app/cbl/CBTRN03C.cbl:L87-L88; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than padding the row "
                    + "into a date range the dataset does not contain");
            return ReadResult.other(PERMANENT_ERROR_STATUS);
        }
        // WHEN '00'.
        return ReadResult.found(decode(recordImage));
    }

    /**
     * Maps one result-set row to its record image, by column position.
     *
     * <p>Position {@value #RECORD_IMAGE_COLUMN_INDEX} and never a column name, for the reason given
     * under residual risk R-E on this class: a dataset carrying no relational metadata is presented as
     * a single record-image column, and naming that column in Java would be an unverifiable literal.
     *
     * @param resultSet the row, positioned by the template
     * @param rowNumber the 0-based row index, part of the mapper contract and not used: every row of
     *                  this dataset has the identical shape, so the index carries no meaning here
     * @return the row's record image, which may be {@code null} if the column holds no value
     * @throws SQLException if the driver cannot supply the column
     */
    private byte[] mapRecordImage(ResultSet resultSet, int rowNumber) throws SQLException {
        return recordImageForm.readImage(resultSet, RECORD_IMAGE_COLUMN_INDEX, codec.charset());
    }

    /**
     * Bounds a statement to the one row this reader consumes.
     *
     * <p>{@code CBTRN03C} performs a single {@code READ} of {@code DATE-PARM-FILE} and never resumes from
     * it, so exactly one record is ever consumed. Asking the backend for the relation and then taking
     * element zero would ask for every record and discard all but that one - which costs the transfer and
     * the heap of the whole dataset to satisfy a read of 80 bytes, and does so on a dataset whose size is
     * a deployment's business rather than this class's.
     *
     * <p>Both limits are set, and they bound different things.
     * {@link java.sql.Statement#setMaxRows(int)} bounds what the backend will produce at all, so a
     * surplus row is never built into a result set; {@link java.sql.Statement#setFetchSize(int)} bounds
     * what one network round trip carries. Together they make the read cost one row rather than one
     * dataset, whatever the deployment's driver defaults are.
     *
     * <p><strong>No {@code ORDER BY} is added, and that is the parity point.</strong> {@code DATEPARM} is
     * a physical-sequential dataset ({@code app/proc/TRANREPT.prc:65-66} binds it with no key), so its
     * records are in the order they were written and {@code READ} returns the first of them. Ordering the
     * statement would change <em>which</em> record the single read sees; limiting it does not. The row
     * returned here is the same first record the unbounded form returned as element zero.
     *
     * @param statement the statement to bound
     * @return a creator that produces the bounded statement; never {@code null}
     */
    private static PreparedStatementCreator firstRowOnly(String statement) {
        return connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(SINGLE_ROW);
            prepared.setFetchSize(SINGLE_ROW);
            return prepared;
        };
    }

    // =================================================================================================
    // The decode seam. Pure, hand-written, and addressed entirely by absolute offset (practice B11).
    // Free of JDBC on purpose, so the parity harness and the unit tests exercise the byte-level
    // behaviour with no backend in the path - which matters here, because residual risk R-E means no
    // backend is reachable from this build.
    // =================================================================================================

    /**
     * Decodes a stored record image into the date range, reproducing the 80-into-21 truncation of
     * {@code READ DATE-PARMS-FILE INTO WS-DATEPARM-RECORD}.
     *
     * <p>The image is encoded in the injected dataset code page and then decoded by
     * {@link #decode(byte[])}, so a caller that already has the row as text does not have to choose a
     * code page of its own - there is one code page for the data layer and this class holds it.
     *
     * @param recordImage the stored record image, exactly {@link #RECORD_LENGTH} characters
     * @return the decoded range; both dates exactly 10 characters, untrimmed
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if {@code recordImage} does not encode to exactly
     *                                  {@link #RECORD_LENGTH} bytes
     */
    public DateParm decode(String recordImage) {
        Objects.requireNonNull(recordImage, "A record image is required to decode the " + DD_NAME
                + " record; an absent record is an end-of-file outcome, not a decodable image");
        return decode(codec.encodeImage(recordImage, "a " + DD_NAME + " record image"));
    }

    /**
     * Decodes stored record bytes into the date range, reproducing the 80-into-21 truncation of
     * {@code READ DATE-PARMS-FILE INTO WS-DATEPARM-RECORD}.
     *
     * <p>Three steps, in this order, and each one is the faithful counterpart of something the COBOL
     * does:
     * <ol>
     *   <li><strong>require exactly {@link #RECORD_LENGTH} bytes</strong>, in either direction. An
     *       over-long row means the data and the layout disagree, and a short one is no better here even
     *       though the absent bytes could only be trailing {@code FILLER}: the next step takes the first
     *       21 bytes as the date range, so a row truncated inside {@code WS-END-DATE} would pad into a
     *       <em>different</em> range and decode successfully. A decoder that silently answers a question
     *       it was not asked is worse than one that declines, so this one declines;</li>
     *   <li><strong>address the record by absolute offset</strong> through the layout whose arithmetic
     *       already proved it is 80 bytes wide (gate G21);</li>
     *   <li><strong>read the three receiver spans untrimmed</strong> and discard bytes 22 to 80, which
     *       is what the {@code READ ... INTO} a 21-byte group does. The tail is declared in the layout
     *       and read past, not ignored - that is what makes the width self-check meaningful.</li>
     * </ol>
     *
     * <p>Every component of the result is text. There is no numeric item anywhere in this record - both
     * dates are {@code PIC X(10)} and the separator is {@code PIC X(01)} - so no fixed-point or
     * fractional numeric conversion arises here at all (gate G22), and none is performed. The dates are
     * compared as characters by the mainline, and characters are what this returns.
     *
     * @param recordBytes the stored record bytes, exactly {@link #RECORD_LENGTH} of them
     * @return the decoded range; both dates exactly 10 characters, untrimmed
     * @throws NullPointerException     if {@code recordBytes} is {@code null}
     * @throws IllegalArgumentException if {@code recordBytes} is not exactly {@link #RECORD_LENGTH}
     *                                  bytes
     */
    public DateParm decode(byte[] recordBytes) {
        Objects.requireNonNull(recordBytes, "Record bytes are required to decode the " + DD_NAME
                + " record; an absent record is an end-of-file outcome, not a decodable image");
        if (recordBytes.length != RECORD_LENGTH) {
            throw new IllegalArgumentException("The " + DD_NAME + " record is declared PIC X("
                    + RECORD_LENGTH + ") at app/cbl/CBTRN03C.cbl:L87-L88 and this image is "
                    + recordBytes.length + " byte(s). It is not padded to width: the next step is the "
                    + "80-into-21 move of READ ... INTO WS-DATEPARM-RECORD, so a row truncated inside "
                    + "WS-END-DATE would pad into a different reporting range and decode without "
                    + "complaint. " + DatasetObservation.recordWidth(recordBytes.length).describe()
                    + ".");
        }
        FixedWidthRecord record = codec.wrap(recordBytes, layout);
        return new DateParm(
                codec.readPicX(record, startDateSpan),
                codec.readPicX(record, separatorSpan),
                codec.readPicX(record, endDateSpan));
    }

    /**
     * The statement this reader issues, exposed to its own tests so the composed SQL can be asserted
     * without a backend. Package-private: it is a construction detail, not part of the public contract.
     *
     * @return the row-reading statement over the configured dataset
     */
    String selectRecordSql() {
        return relation.selectAllInPhysicalSequence(physicalSequence);
    }

    /**
     * The statement that describes the dataset without transferring a row - what {@link #open()} and
     * {@link #close()} issue.
     *
     * <p>This is also the dataset-scoped, zero-row probe an {@code OPEN INPUT} reduces to, exposed so
     * the composed SQL can be asserted without a backend: the predicate is false on every row, so the
     * driver still has to resolve the relation and describe it while none of it crosses the wire. It is
     * composed by {@link DatasetRelation}, from the configured name, so no dataset name is written in
     * Java (gate G46).
     *
     * @return the describe statement over the configured dataset
     */
    String describeStatement() {
        return relation.describeStatement();
    }

    /**
     * The self-checked 80-byte layout, exposed to this class's own tests so the offsets and the width
     * proof can be asserted directly. Package-private, and safe to hand out because a
     * {@link RecordLayout} is deeply immutable.
     *
     * @return the layout of {@code FD-DATEPARM-REC}
     */
    RecordLayout layout() {
        return layout;
    }

    // =================================================================================================
    // Construction-time validation helpers. Private and static: no instance state is involved, and no
    // static state is introduced (practice B9, gate G53).
    // =================================================================================================

    /**
     * Validates the configured dataset name and returns it verbatim.
     *
     * <p>Nothing is trimmed, normalised or defaulted. A blank name means the binding is present but
     * says nothing, and a name carrying a control character cannot be a dataset name and would corrupt
     * the composed statement - both are configuration defects and both fail the context here rather
     * than producing a statement that reads the wrong thing, or nothing.
     *
     * @param candidate the {@code dsname} component of the resolved binding
     * @return {@code candidate}, unchanged
     * @throws IllegalStateException if {@code candidate} is {@code null}, blank, or contains a control
     *                               character
     */
    private static String requireUsableDatasetName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for DD name '" + DD_NAME
                    + "' declares no dataset name. Set carddemo.datasets." + DD_NAME
                    + ".dsname; this reader composes its statement from configuration alone and "
                    + "hard-codes no dataset name.");
        }
        // The grammar - what a z/OS dataset name may contain - lives in DatasetRelation, so it is
        // stated once for the whole module rather than restated, and diverging, in each class.
        return DatasetRelation.requireDatasetName(candidate);
    }

    /**
     * Asserts that a decoded span is exactly its declared width.
     *
     * <p>The widths are the whole point of a fixed-width record, so they are checked rather than
     * assumed: a value of the wrong width could only come from a caller constructing a
     * {@link DateParm} by hand, and letting one through would put a range into the report comparison
     * that the dataset never contained.
     *
     * @param value          the decoded span
     * @param expectedLength its declared width
     * @param itemName       how to name the item in a diagnostic
     * @return {@code value}, unchanged
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not exactly {@code expectedLength} long
     */
    private static String requireExactLength(String value, int expectedLength, String itemName) {
        Objects.requireNonNull(value, itemName + " is required; a fixed-width span is never absent, "
                + "only spaces");
        if (value.length() != expectedLength) {
            throw new IllegalArgumentException(itemName + " is declared PIC X(" + expectedLength
                    + ") but was given " + value.length() + " character(s): '" + value + "'. A "
                    + "fixed-width span carries its padding, so it is never trimmed and never short.");
        }
        return value;
    }

    /**
     * The report date range, exactly as {@code WS-DATEPARM-RECORD} holds it
     * ({@code app/cbl/CBTRN03C.cbl:L122-L125}).
     *
     * <p>An immutable value with three components and no interpretation. In particular the dates are
     * <strong>not</strong> parsed into a temporal type, and that is deliberate rather than
     * unfinished: the mainline compares them as characters against
     * {@code TRAN-PROC-TS (1:10)} at {@code L173-L174}, so a range of {@code '2022-01-01'} through
     * {@code '2022-07-06'} selects transactions by string ordering, not by calendar arithmetic.
     * Parsing them would impose validation the COBOL does not perform - a malformed date in this
     * dataset does not fail the job, it simply selects nothing - and would discard the padding that
     * the comparison depends on.
     *
     * <p>Both dates are carried untrimmed at their full declared width, so a value narrower than ten
     * characters keeps its trailing spaces. Nothing here is numeric, so there is no decimal scale to
     * preserve and no rounding decision to make (gate G22).
     *
     * @param startDate the {@code WS-START-DATE PIC X(10)} span, verbatim and untrimmed - exactly 10
     *                  characters
     * @param separator the {@code FILLER PIC X(01)} span between the dates, byte 11 of the record. It
     *                  is a space in every record the estate produces -
     *                  {@code app/cbl/CORPT00C.cbl:L119} writes it as {@code VALUE SPACE} - and it is
     *                  surfaced rather than assumed because it is a byte of the record, and a record's
     *                  bytes are what parity compares
     * @param endDate   the {@code WS-END-DATE PIC X(10)} span, verbatim and untrimmed - exactly 10
     *                  characters
     */
    public record DateParm(String startDate, String separator, String endDate) {

        /**
         * Validates each span against its declared width.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if any component is not exactly its declared width
         */
        public DateParm {
            requireExactLength(startDate, START_DATE_LENGTH, START_DATE_FIELD);
            requireExactLength(separator, SEPARATOR_LENGTH, "The FILLER PIC X(01) separator");
            requireExactLength(endDate, END_DATE_LENGTH, END_DATE_FIELD);
        }

        /**
         * The 21-byte image of {@code WS-DATEPARM-RECORD}: the two dates with the separator between
         * them, every byte at its declared width.
         *
         * <p>This is the receiver's whole content after the {@code READ ... INTO} - the record's first
         * 21 bytes and nothing else - so it is the value a byte-level comparison against the dataset
         * is made against.
         *
         * @return exactly {@link DateParmReader#RECEIVER_LENGTH} characters
         */
        public String receiverImage() {
            return startDate + separator + endDate;
        }
    }

    /**
     * The discriminated outcome of one {@code READ} of the {@code DATEPARM} dataset: the classification
     * {@code 0550-DATEPARM-READ}'s {@code EVALUATE} performs, and nothing beyond it
     * ({@code app/cbl/CBTRN03C.cbl:L221-L229}).
     *
     * <p>Three shapes, built through {@link #found(DateParm)}, {@link #endOfFile()} and
     * {@link #other(String)}. The vocabulary is {@link Outcome}, shared with every other dataset
     * operation in the module so a caller's branch structure looks the same whichever legacy program it
     * came from, and it is deliberately narrowed to the three arms this {@code EVALUATE} has: a keyed
     * status such as not-found or duplicate cannot arise on a sequential read and would in any case
     * fall into {@code WHEN OTHER}, so it is rejected here rather than admitted as a fourth arm that no
     * caller could act on.
     *
     * <p>The range is present for, and only for, a successful read - the invariant is enforced, not
     * merely documented - so a caller can neither find a range on an end-of-file result nor lose one on
     * a successful read.
     *
     * @param status   the two-character {@code DATEPARM-STATUS} the read reported, verbatim
     * @param outcome  its classification: {@link Outcome#OK}, {@link Outcome#END_OF_FILE} or
     *                 {@link Outcome#OTHER}
     * @param dateParm the decoded range, present exactly when {@code outcome} is {@link Outcome#OK}
     * @param diagnostic what the backend reported when it refused, present only on a
     *                 {@link Outcome#OTHER} arm the driver described - so the abend that follows can be
     *                 traced to a cause rather than to a status this class composed
     */
    public record ReadResult(String status,
                             Outcome outcome,
                             Optional<DateParm> dateParm,
                             Optional<BackendDiagnostic> diagnostic) {

        /**
         * Enforces every invariant of the three-armed ladder at construction.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly two characters, if
         *                                  {@code outcome} is not one of the three arms, if the range
         *                                  is present when the outcome is not success or absent when it
         *                                  is, or if {@code status} contradicts {@code outcome}
         */
        public ReadResult {
            Objects.requireNonNull(status, "A read result carries the two-character file status the "
                    + "read reported; it is never absent");
            Objects.requireNonNull(outcome, "A read result carries its classification; it is never "
                    + "absent");
            Objects.requireNonNull(dateParm, "A read result carries an empty range rather than a null "
                    + "one, so no null escapes the type");
            Objects.requireNonNull(diagnostic, "A read result carries an empty diagnostic rather than a "
                    + "null one, so no null escapes the type");
            if (status.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalArgumentException("A file status is exactly "
                        + FileStatus.STATUS_LENGTH + " characters, as DATEPARM-STATUS is declared at "
                        + "app/cbl/CBTRN03C.cbl:L118-L120; got " + status.length());
            }
            if (outcome != Outcome.OK && outcome != Outcome.END_OF_FILE && outcome != Outcome.OTHER) {
                throw new IllegalArgumentException("The EVALUATE at app/cbl/CBTRN03C.cbl:L222-L229 has "
                        + "three arms - '00', '10' and WHEN OTHER - so a DATEPARM read is classified as "
                        + "OK, END_OF_FILE or OTHER. A keyed status cannot arise on a sequential read "
                        + "and would fall into WHEN OTHER; got " + outcome + ".");
            }
            if (dateParm.isPresent() != (outcome == Outcome.OK)) {
                throw new IllegalArgumentException(dateParm.isPresent()
                        ? "A read that did not succeed carries no date range: outcome " + outcome
                                + " was given one. Only the '00' arm reaches WS-DATEPARM-RECORD."
                        : "A successful read carries the decoded date range, and this one carries "
                                + "none.");
            }
            String expectedForOutcome = outcome.batchStatus().orElse(null);
            if (expectedForOutcome != null && !expectedForOutcome.equals(status)) {
                throw new IllegalArgumentException("Outcome " + outcome + " corresponds to status '"
                        + expectedForOutcome + "', but status '" + status + "' was given; the status "
                        + "and its classification must agree.");
            }
            if (expectedForOutcome == null
                    && (FileStatus.isOk(status) || FileStatus.isEndOfFile(status))) {
                throw new IllegalArgumentException("Status '" + status + "' is one of the two statuses "
                        + "the EVALUATE names explicitly, so it cannot be classified as WHEN OTHER.");
            }
        }

        /**
         * The successful arm: {@code WHEN '00' MOVE 0 TO APPL-RESULT}
         * ({@code app/cbl/CBTRN03C.cbl:L223-L224}).
         *
         * @param dateParm the decoded range
         * @return a result carrying status {@link FileStatus#OK} and the range
         * @throws NullPointerException if {@code dateParm} is {@code null}
         */
        public static ReadResult found(DateParm dateParm) {
            Objects.requireNonNull(dateParm, "A successful read carries the decoded date range");
            return new ReadResult(FileStatus.OK, Outcome.OK, Optional.of(dateParm), Optional.empty());
        }

        /**
         * The end-of-file arm: {@code WHEN '10' MOVE 16 TO APPL-RESULT}
         * ({@code app/cbl/CBTRN03C.cbl:L225-L226}), which the caller turns into
         * {@code MOVE 'Y' TO END-OF-FILE} at {@code L236}.
         *
         * <p>Reached when the dataset holds no record. Because the read happens before the report loop,
         * the job then emits no report body - see this class's documentation.
         *
         * @return a result carrying status {@link FileStatus#END_OF_FILE} and no range
         */
        public static ReadResult endOfFile() {
            return new ReadResult(FileStatus.END_OF_FILE, Outcome.END_OF_FILE, Optional.empty(),
                    Optional.empty());
        }

        /**
         * The {@code WHEN OTHER} arm: {@code MOVE 12 TO APPL-RESULT}
         * ({@code app/cbl/CBTRN03C.cbl:L227-L228}), which the caller turns into an error line, a
         * rendered status and an abend at {@code L238-L241}.
         *
         * @param status the two-character status the read reported, carried verbatim so the caller can
         *               render it exactly as {@code 9910-DISPLAY-IO-STATUS} does
         * @return a result carrying {@code status} and no range
         * @throws NullPointerException     if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly two characters, or is one
         *                                  of the two statuses the {@code EVALUATE} names explicitly
         */
        public static ReadResult other(String status) {
            return new ReadResult(status, Outcome.OTHER, Optional.empty(), Optional.empty());
        }

        /**
         * The {@code WHEN OTHER} arm, carrying what the backend actually said about the refusal.
         *
         * <p>The status is what the caller branches on, because that is the quantity the COBOL guard
         * chain tests. The diagnostic is what makes the abend that follows diagnosable: a permanent-error
         * status says something went wrong and nothing about what, whereas the driver's {@code SQLSTATE}
         * distinguishes an unreachable backend from a missing dataset from a rejected credential.
         *
         * @param status     the permanent-error file status
         * @param diagnostic what the backend reported
         * @return the outcome
         * @throws NullPointerException if {@code diagnostic} is {@code null}
         */
        public static ReadResult other(String status, BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A diagnostic is required by this factory; use "
                    + "other(String) where there is no backend refusal to report");
            return new ReadResult(status, Outcome.OTHER, Optional.empty(), Optional.of(diagnostic));
        }

        /**
         * Whether the read succeeded and a range is available: the {@code IF APPL-AOK} test at
         * {@code app/cbl/CBTRN03C.cbl:L231}.
         *
         * @return {@code true} for the {@code '00'} arm
         */
        public boolean isFound() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether the dataset was empty: the {@code IF APPL-EOF} test at
         * {@code app/cbl/CBTRN03C.cbl:L235}.
         *
         * @return {@code true} for the {@code '10'} arm
         */
        public boolean isEndOfFile() {
            return outcome == Outcome.END_OF_FILE;
        }

        /**
         * Whether the read failed: the {@code ELSE} of the {@code APPL-EOF} test, which displays,
         * renders the status and abends ({@code app/cbl/CBTRN03C.cbl:L237-L242}).
         *
         * @return {@code true} for the {@code WHEN OTHER} arm
         */
        public boolean isOther() {
            return outcome == Outcome.OTHER;
        }

        /**
         * The {@code APPL-RESULT} value the {@code EVALUATE} moves for this outcome: {@code 0},
         * {@code 16} or {@code 12} ({@code app/cbl/CBTRN03C.cbl:L222-L229}).
         *
         * <p>The mapping lives here, in the class that embodies the paragraph the {@code EVALUATE}
         * belongs to, so that it exists exactly once. The final arm is a {@code default} for the same
         * reason the COBOL's is {@code WHEN OTHER}: it is the catch-all, and writing it as one keeps
         * the two readable side by side.
         *
         * @return {@link FileStatus#APPL_AOK}, {@link FileStatus#APPL_EOF} or
         *         {@link DateParmReader#APPL_RESULT_FATAL}
         */
        public int applResult() {
            return switch (outcome) {
                case OK -> FileStatus.APPL_AOK;
                case END_OF_FILE -> FileStatus.APPL_EOF;
                default -> APPL_RESULT_FATAL;
            };
        }
    }
}

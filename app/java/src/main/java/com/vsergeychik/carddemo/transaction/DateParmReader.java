package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import java.nio.charset.Charset;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
    public static final int RECORD_IMAGE_COLUMN_INDEX = 1;

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
     * The resolved dataset name, taken verbatim from the {@link DatasetBinding} for {@link #DD_NAME}.
     * Configuration is its only source; no part of it is written in Java (gate G46).
     */
    private final String datasetName;

    /**
     * The one statement this class issues: a row read over {@link #datasetName}. Composed once, at
     * construction, from the resolved binding - so it is not rebuilt per call and cannot vary between
     * calls.
     */
    private final String selectRecordSql;

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
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if no binding is configured for {@link #DD_NAME}, if that binding
     *                               declares a record length other than {@link #RECORD_LENGTH}, or if
     *                               its dataset name is unusable
     */
    public DateParmReader(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset) {

        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + "report date range is read from the " + DD_NAME + " dataset through the module's "
                + "shared template");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "dataset names live in configuration and are never written in Java");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset is "
                + "required: a fixed-width mainframe record is bytes in a specific code page, so the "
                + "code page is stated explicitly and never taken from the platform"));

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
        this.datasetName = requireUsableDatasetName(binding.dsname());
        this.selectRecordSql = "SELECT * FROM " + asDelimitedIdentifier(this.datasetName);

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
        return datasetName;
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
     * operation. So the check performed here is the strongest one that is both dataset-independent and
     * free of any SQL dialect - that a connection can be obtained and can describe itself. It proves
     * the backend is reachable, which is the failure an {@code OPEN INPUT} most often reports. It does
     * <em>not</em> prove the dataset itself is available; a driver that can only establish that at
     * first fetch will report it through {@link #read()} instead. Both paths yield a non-{@code '00'}
     * status and both reach the same COBOL guard, so the program's branch structure is preserved
     * whichever way the failure surfaces. This is part of residual risk R-E, documented on this class.
     *
     * @return {@link FileStatus#OK} when the dataset can be read, otherwise
     *         {@link #PERMANENT_ERROR_STATUS}; never {@code null}, always two characters
     */
    public String open() {
        return probeDatasetAvailability();
    }

    /**
     * Closes the {@code DATEPARM} dataset, reporting only the resulting file status: the Java form of
     * {@code 9500-DATEPARM-CLOSE} ({@code app/cbl/CBTRN03C.cbl:L605-L621}).
     *
     * <p>Same two-way shape as {@link #open()}, and the caller likewise owns the
     * {@code DISPLAY 'ERROR CLOSING DATE PARM FILE'} line at {@code L616} and the abend.
     *
     * <p>Nothing is buffered and nothing is held between calls, so there is no flush to fail and no
     * handle to release. The one close-time failure this reader can genuinely detect is that the
     * backend is no longer reachable, which is the analogue of the file system reporting a problem
     * when the dataset is de-allocated - so the same probe as {@link #open()} is used, which also
     * keeps both of the COBOL's symmetric guards reachable rather than leaving one dead.
     *
     * @return {@link FileStatus#OK} when the backend is still reachable, otherwise
     *         {@link #PERMANENT_ERROR_STATUS}; never {@code null}, always two characters
     */
    public String close() {
        return probeDatasetAvailability();
    }

    /**
     * The shared open/close probe: obtain a connection and confirm it can describe itself.
     *
     * <p>Read-only and dialect-free. Requesting the connection's own metadata is supported by every
     * JDBC driver and touches no dataset, which is what makes it usable against a driver this build
     * cannot exercise. A driver that answers with no metadata at all is treated as unusable rather
     * than as success: with the driver a deployment-time input, reporting success on an unusable
     * connection would hand the job a range it never actually read.
     *
     * @return {@link FileStatus#OK} or {@link #PERMANENT_ERROR_STATUS}
     */
    private String probeDatasetAvailability() {
        ConnectionCallback<String> probe = connection -> {
            DatabaseMetaData metaData = connection.getMetaData();
            return metaData == null ? PERMANENT_ERROR_STATUS : FileStatus.OK;
        };
        try {
            String status = jdbcTemplate.execute(probe);
            // A template that yields no status at all has told us nothing, and "nothing" is not
            // success. Reported as a permanent error, exactly as an unusable connection is.
            return status == null ? PERMANENT_ERROR_STATUS : status;
        } catch (DataAccessException translated) {
            // Every SQLException the driver raises arrives here already translated. The COBOL keeps no
            // more detail than the status either - it displays the rendered status and abends - so the
            // status is the whole of what is reported, and the cause is left for the driver's own log.
            return PERMANENT_ERROR_STATUS;
        }
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
     * <p>A short row is widened with spaces before decoding, because the absent bytes could only be
     * trailing {@code FILLER} and a {@code FILLER} carrying no literal holds spaces - the same
     * faithful repair the codec applies to the one under-width fixture in this repository. A row
     * <em>wider</em> than the record is a contract violation rather than an I/O outcome and is thrown,
     * for the reason set out under "Statuses model I/O outcomes" on this class.
     *
     * @return the discriminated outcome; never {@code null}
     * @throws IllegalArgumentException if the stored row is wider than {@link #RECORD_LENGTH} bytes,
     *                                  meaning the driver is not presenting the record this reader is
     *                                  configured for
     */
    public ReadResult read() {
        List<String> rows;
        try {
            RowMapper<String> recordImageMapper = this::mapRecordImage;
            rows = jdbcTemplate.query(selectRecordSql, recordImageMapper);
        } catch (DataAccessException translated) {
            // WHEN OTHER. An I/O failure, reported as a status so the caller's own guard chain decides
            // what to do about it - which, in CBTRN03C, is to display and abend.
            return ReadResult.other(PERMANENT_ERROR_STATUS);
        }
        if (rows == null || rows.isEmpty()) {
            // WHEN '10'. AT END with no record read: the empty-dataset case, which leaves the report
            // body empty. This is real legacy behaviour and is reported, never substituted.
            return ReadResult.endOfFile();
        }
        String recordImage = rows.get(0);
        if (recordImage == null) {
            // A row whose record image is absent is not a readable 80-byte record. It is an I/O-level
            // defect rather than an end of file - there IS a record, it just cannot be read - so it is
            // reported on the WHEN OTHER arm and not mistaken for an empty dataset.
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
    private String mapRecordImage(ResultSet resultSet, int rowNumber) throws SQLException {
        return resultSet.getString(RECORD_IMAGE_COLUMN_INDEX);
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
     * @param recordImage the stored record image, up to {@link #RECORD_LENGTH} characters
     * @return the decoded range; both dates exactly 10 characters, untrimmed
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if {@code recordImage} is wider than {@link #RECORD_LENGTH}
     */
    public DateParm decode(String recordImage) {
        Objects.requireNonNull(recordImage, "A record image is required to decode the " + DD_NAME
                + " record; an absent record is an end-of-file outcome, not a decodable image");
        return decode(recordImage.getBytes(codec.charset()));
    }

    /**
     * Decodes stored record bytes into the date range, reproducing the 80-into-21 truncation of
     * {@code READ DATE-PARMS-FILE INTO WS-DATEPARM-RECORD}.
     *
     * <p>Three steps, in this order, and each one is the faithful counterpart of something the COBOL
     * does:
     * <ol>
     *   <li><strong>widen a short row with spaces</strong> to the declared {@link #RECORD_LENGTH}. The
     *       record is fixed-length on the mainframe, so any absent trailing bytes could only be
     *       {@code FILLER}, and a {@code FILLER} with no literal holds spaces. An over-long row is
     *       rejected by the codec rather than truncated, because it means the data and the layout
     *       disagree;</li>
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
     * @param recordBytes the stored record bytes, up to {@link #RECORD_LENGTH} of them
     * @return the decoded range; both dates exactly 10 characters, untrimmed
     * @throws NullPointerException     if {@code recordBytes} is {@code null}
     * @throws IllegalArgumentException if {@code recordBytes} is wider than {@link #RECORD_LENGTH}
     */
    public DateParm decode(byte[] recordBytes) {
        Objects.requireNonNull(recordBytes, "Record bytes are required to decode the " + DD_NAME
                + " record; an absent record is an end-of-file outcome, not a decodable image");
        byte[] widened = codec.padToDeclaredWidth(recordBytes, RECORD_LENGTH);
        FixedWidthRecord record = codec.wrap(widened, layout);
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
        return selectRecordSql;
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
        for (int index = 0; index < candidate.length(); index++) {
            if (Character.isISOControl(candidate.charAt(index))) {
                throw new IllegalStateException("The dataset name configured at carddemo.datasets."
                        + DD_NAME + ".dsname contains a control character at position " + index
                        + "; a dataset name cannot contain one, and it would corrupt the composed "
                        + "statement. Correct the configured value.");
            }
        }
        return candidate;
    }

    /**
     * Renders a dataset name as a single SQL delimited identifier.
     *
     * <p>A mainframe dataset name contains periods, and an unquoted period is a name separator in SQL,
     * so the name has to be delimited or it would be parsed as a chain of qualifiers. Any embedded
     * quote character is repeated, which is how the SQL standard escapes one inside a delimited
     * identifier; together with the control-character rejection in
     * {@link #requireUsableDatasetName(String)} that leaves no way for a configured value to terminate
     * the identifier early.
     *
     * <p>How a given driver catalogues a dataset is a deployment-time input - residual risk R-E on this
     * class - and this is the one place it is expressed, so a site that needs a different form changes
     * a binding rather than this class.
     *
     * @param name the validated dataset name
     * @return the name as a delimited identifier
     */
    private static String asDelimitedIdentifier(String name) {
        String quote = "\"";
        return quote + name.replace(quote, quote + quote) + quote;
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
     */
    public record ReadResult(String status, Outcome outcome, Optional<DateParm> dateParm) {

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
            return new ReadResult(FileStatus.OK, Outcome.OK, Optional.of(dateParm));
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
            return new ReadResult(FileStatus.END_OF_FILE, Outcome.END_OF_FILE, Optional.empty());
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
            return new ReadResult(status, Outcome.OTHER, Optional.empty());
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

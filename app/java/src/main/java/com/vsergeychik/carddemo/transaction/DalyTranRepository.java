package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DatasetObservation;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.transaction.model.DalyTranRecord;

import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@code DALYTRAN} daily-transaction dataset, read forward and read only.
 *
 * <p>This is the input side of {@code app/jcl/POSTTRAN.jcl}, and it is the smallest data-access contract
 * in the estate: three operations and nothing else. Its whole difficulty is in the third arm of one
 * ladder - telling "the file ended" apart from "the read failed" - because the job on the other side of
 * it posts a transaction on the first answer and abends on the second.
 *
 * <h2>The two consumers, and where each one's ladder lives</h2>
 * <p>Both are batch programs. Neither is online, so no CICS {@code RESP} arises anywhere in this class.
 * <ul>
 *   <li><strong>{@code CBTRN02C}</strong> - the real {@code POSTTRAN} poster, and the only
 *       chunk-oriented job in the migration. {@code SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN
 *       ORGANIZATION IS SEQUENTIAL ACCESS MODE IS SEQUENTIAL FILE STATUS IS DALYTRAN-STATUS} at
 *       {@code app/cbl/CBTRN02C.cbl:L29-L32}; {@code 0000-DALYTRAN-OPEN} at {@code L236-L250};
 *       {@code 1000-DALYTRAN-GET-NEXT} at {@code L345-L369}; {@code 9000-DALYTRAN-CLOSE} at
 *       {@code L582-L598}. Its mainline at {@code L202-L219} loops {@code UNTIL END-OF-FILE = 'Y'}.</li>
 *   <li><strong>{@code CBTRN01C}</strong> - the orphan poster no JCL invokes. Identical {@code SELECT}
 *       and {@code FD} at {@code app/cbl/CBTRN01C.cbl:L29-L32} and {@code L66-L69};
 *       {@code 0000-DALYTRAN-OPEN} at {@code L252-L268}; {@code 1000-DALYTRAN-GET-NEXT} at
 *       {@code L202-L225}; {@code 9000-DALYTRAN-CLOSE} at {@code L361-L378}.</li>
 * </ul>
 *
 * <h2>The status ladder, and why this class does not walk it</h2>
 * <p>Both {@code 1000-DALYTRAN-GET-NEXT} paragraphs are the same three-armed guard, and both map the
 * file status onto {@code APPL-RESULT} identically:
 * <table border="1">
 *   <caption>Status to {@code APPL-RESULT}, from both consumers</caption>
 *   <tr><th>Status</th><th>{@code APPL-RESULT}</th><th>What the consumer then does</th></tr>
 *   <tr><td>{@code '00'}</td><td>{@code 0} ({@code APPL-AOK})</td>
 *       <td>process the record</td></tr>
 *   <tr><td>{@code '10'}</td><td>{@code 16} ({@code APPL-EOF})</td>
 *       <td>{@code MOVE 'Y' TO END-OF-FILE} ({@code CBTRN02C:L361}) or to
 *           {@code END-OF-DAILY-TRANS-FILE} ({@code CBTRN01C:L218})</td></tr>
 *   <tr><td>anything else</td><td>{@code 12}</td>
 *       <td>display the error line, then {@code 9910-DISPLAY-IO-STATUS} /
 *           {@code Z-DISPLAY-IO-STATUS}, then {@code 9999-ABEND-PROGRAM} /
 *           {@code Z-ABEND-PROGRAM}</td></tr>
 * </table>
 *
 * <p><strong>Nothing is displayed, logged as an operator message, formatted or abended here.</strong>
 * This class reports the two-character status and stops; {@link ReadResult#applResult()} names the
 * {@code APPL-RESULT} value that goes with it. That boundary is deliberate and it is not a stylistic
 * choice - the two consumers do not agree on the text, so a class that emitted the text would have to
 * pick one of them and would be wrong for the other:
 * <table border="1">
 *   <caption>The same failure, two different lines</caption>
 *   <tr><th>Paragraph</th><th>{@code CBTRN02C}</th><th>{@code CBTRN01C}</th></tr>
 *   <tr><td>open</td><td>{@code 'ERROR OPENING DALYTRAN'} ({@code L247})</td>
 *       <td>{@code 'ERROR OPENING DAILY TRANSACTION FILE'} ({@code L263})</td></tr>
 *   <tr><td>read</td><td>{@code 'ERROR READING DALYTRAN FILE'} ({@code L363})</td>
 *       <td>{@code 'ERROR READING DAILY TRANSACTION FILE'} ({@code L221})</td></tr>
 *   <tr><td>close</td><td>{@code 'ERROR CLOSING DALYTRAN FILE'} ({@code L593})</td>
 *       <td>{@code 'ERROR CLOSING CUSTOMER FILE'} ({@code L372}) - see below</td></tr>
 * </table>
 *
 * <p><strong>{@code CBTRN01C}'s close paragraph carries a transcription defect, and it must be
 * preserved.</strong> {@code 9000-DALYTRAN-CLOSE} closes {@code DALYTRAN-FILE} and tests
 * {@code DALYTRAN-STATUS}, but on the failure arm it displays {@code 'ERROR CLOSING CUSTOMER FILE'} and
 * moves {@code CUSTFILE-STATUS} - not {@code DALYTRAN-STATUS} - into {@code IO-STATUS}
 * ({@code app/cbl/CBTRN01C.cbl:L371-L373}). It also opens with
 * {@code ADD 8 TO ZERO GIVING APPL-RESULT} where every sibling paragraph writes {@code MOVE 8}. Both are
 * quirks of the legacy source, and practice B5 forbids tidying either: a failing close of this dataset
 * under {@code CBTRN01C} really does report the <em>customer</em> file's status. {@link #close} returns
 * this dataset's own status, which is the honest answer to the question it was asked; reproducing the
 * defect is the caller's job, and it is recorded here so the caller is not surprised by it.
 *
 * <h2>A physical-sequential dataset, deliberately not a KSDS</h2>
 * <p>{@code app/jcl/POSTTRAN.jcl:L30-L31} binds {@code //DALYTRAN DD DISP=SHR,DSN=<the configured
 * name>}, a {@code .PS} dataset, while the same step binds {@code TRANFILE}, {@code XREFFILE},
 * {@code ACCTFILE} and {@code TCATBALF} as {@code .VSAM.KSDS}. That difference is the whole shape of
 * this class: there is no key, so there is no keyed read, no alternate index, no
 * {@code READ ... FOR UPDATE} and no repositionable browse. There is one sequence, in the order the
 * records were written, and {@code READ} walks it.
 *
 * <h2>Read only: nothing here writes, and that was verified rather than assumed</h2>
 * <p>{@code grep -n "DALYTRAN" app/cbl/*.cbl} returns, across both consumers, exactly three verbs
 * against {@code DALYTRAN-FILE}: {@code OPEN INPUT}, {@code READ ... INTO} and {@code CLOSE}. There is
 * no {@code WRITE}, no {@code REWRITE}, no {@code DELETE}, no {@code START} and no keyed {@code READ}
 * anywhere in the estate. So none is exposed. AAP section 0.3.5 requires a repository to publish only
 * the access paths its COBOL actually performs, and an unused write path on an input dataset is not a
 * convenience - it is a way for a later change to write to a file the legacy system only ever read.
 *
 * <h2>The 350-byte record</h2>
 * <p>{@code app/cpy/CVTRA06Y.cpy} declares {@code DALYTRAN-RECORD} at {@code RECLN = 350} and
 * {@code app/cbl/CBTRN02C.cbl:L66-L69} confirms the width independently by splitting the same record as
 * {@code FD-TRAN-ID PIC X(16)} plus {@code FD-CUST-DATA PIC X(334)}. Decoding is
 * {@link DalyTranRecord}'s, addressed by absolute offset, {@code FILLER X(20)} declared and emitted
 * (gate G21), and the fourteen declared spans sum to exactly 350 - which this class re-proves at
 * construction so a layout regression cannot reach a read (gates G19, G21).
 *
 * <p>{@code DALYTRAN-AMT PIC S9(09)V99} occupies eleven bytes with its sign as a zoned overpunch in the
 * trailing byte, and it decodes to a {@link java.math.BigDecimal} at scale exactly
 * {@value #AMOUNT_SCALE} under {@link CobolDecimal#COBOL_ROUNDING} - truncation toward zero, because
 * {@code ROUNDED} appears nowhere in the 28 programs. Never a {@code double} and never a {@code float}
 * (gates G22, G23, G24). Fifty of the three hundred shipped fixture rows carry a negative overpunch, so
 * this is not a theoretical path.
 *
 * <h2>Why the read is a held cursor, and what was rejected</h2>
 * <p>{@link #open()} acquires a forward-only, read-only JDBC cursor over
 * {@link DatasetRelation#selectAll()}; {@link #readNext} advances it exactly one row; {@link #close}
 * releases it. That is the direct analogue of a COBOL sequential file handle, and each of the
 * alternatives was rejected for a reason worth recording:
 * <ul>
 *   <li><strong>An {@code ORDER BY} over the record image</strong> - which is how the keyed
 *       repositories in this package browse - would <em>reorder the file</em>. A PS dataset has no key,
 *       so ordering by the image would sort the day's transactions into ascending
 *       {@code DALYTRAN-ID} order, and both consumers depend on the order they were written:
 *       {@code CBTRN02C} accumulates into {@code TCATBALF} and {@code ACCTFILE} as it goes
 *       ({@code L508}, {@code L527}, {@code L547-L551}) and {@code CBTRN01C} displays each record as it
 *       reads it ({@code L168}). {@link DatasetRelation#selectAll()} is the unordered statement, and its
 *       own documentation records that it exists for exactly this case.</li>
 *   <li><strong>Materialising every row image at open and walking the snapshot</strong> would be a
 *       cache, which AAP section 0.8.6 rules out - this is not a performance refactoring - and it would
 *       make an open of a real daily-transaction file proportional to the file rather than to one
 *       record.</li>
 *   <li><strong>Re-reading with a growing row limit</strong> would be quadratic and, worse, would rest
 *       on an unordered statement returning the same sequence twice, which nothing guarantees.</li>
 * </ul>
 * A single held cursor is the only one of the four that is both faithful and bounded, and it transfers
 * one row per {@code READ}.
 *
 * <p><strong>The cursor runs on its own connection, and that is load-bearing.</strong> It is taken from
 * {@link JdbcTemplate#getDataSource()} directly rather than from the thread's transactional connection.
 * {@code CBTRN02C} is the migration's one chunk-oriented job, so its reader is advanced across chunk
 * commit boundaries; a cursor on the transactional connection would be closed by the first commit and
 * the job would see an end of file in the middle of the file, having posted a fraction of the day and
 * reported success. A private connection cannot be closed underneath the read. Nothing in the estate
 * writes to this dataset - the {@code grep} above is the proof - so reading it outside the caller's
 * transaction loses no visibility. This is also what Spring Batch's own cursor reader does by default,
 * and for the same reason.
 *
 * <h2>Statelessness and thread safety</h2>
 * <p>A Spring {@code @Repository} is a singleton, so every piece of per-read state - the cursor, the
 * position, the record count and the closed flag - lives on the {@link DalytranFile} that
 * {@link #open()} returns, never on this instance. This instance holds only immutable collaborators.
 * There is no static mutable state anywhere in this file (gate G53) and no field is reassigned after
 * construction, so two concurrent opens cannot interleave each other's reads.
 *
 * <h2>Configuration, not literals</h2>
 * <p>The dataset name, its record length and its record format are read from
 * {@code carddemo.datasets.DALYTRAN} through {@link DatasetBindings}; no dataset name appears in this
 * file (gate G46). The code page is the injected
 * {@value CobolCharsetConfig#DATASET_CHARSET_BEAN_NAME} bean and is passed explicitly into every encode
 * and decode - never a platform default (practice B8). No DDL, no entity annotation, no version column
 * and no index is declared or implied (gate G44).
 *
 * <h2>Residual risk R-E</h2>
 * <p>The production driver is a deployment-time input: there is no VSAM JDBC driver on Maven Central and
 * this build cannot exercise one. Everything here is therefore validated against the fixture-backed
 * harness with H2 at test scope, plus the pure decode seams {@link #decode(byte[])} and
 * {@link #decode(String)}, which have no JDBC in the path at all and are what the parity cases drive.
 * What that leaves unproven is the driver's own behaviour, not this class's - which is why the row
 * shape is checked on every read rather than trusted.
 *
 * @see DalyTranRecord
 * @see DateParmReader
 */
@Repository
public class DalyTranRepository {

    /**
     * This class's log. Every line is a single argument, composed and sanitized here: a
     * {@link Throwable} handed to a logger would emit the driver's own prose and its whole cause chain
     * verbatim (CWE-532), and that prose is composed around the record the driver refused. Record
     * content is never logged.
     */
    private static final Log LOG = LogFactory.getLog(DalyTranRepository.class);

    /**
     * The DD name both consumers assign this dataset to: {@code DALYTRAN}.
     *
     * <p>{@code ASSIGN TO DALYTRAN} at {@code app/cbl/CBTRN02C.cbl:L29} and
     * {@code app/cbl/CBTRN01C.cbl:L29}, and the DD name {@code app/jcl/POSTTRAN.jcl:L30} binds. It is
     * the key this class resolves under {@code carddemo.datasets}; there is no CICS {@code FILE} name,
     * because no online program reaches this dataset.
     */
    public static final String DD_NAME = "DALYTRAN";

    /**
     * The declared record width in bytes: {@value}, delegated to {@link DalyTranRecord#RECORD_LENGTH}
     * so the copybook has exactly one Java home.
     *
     * <p>{@code app/cpy/CVTRA06Y.cpy} states {@code RECLN = 350} and
     * {@code app/cbl/CBTRN02C.cbl:L66-L69} confirms it as {@code 16 + 334}.
     */
    public static final int RECORD_LENGTH = DalyTranRecord.RECORD_LENGTH;

    /**
     * The 1-based result-set column position the record image is read from: {@value}.
     *
     * <p>Position and never a column name. A dataset reached through a gateway carries no relational
     * metadata of its own, so it is presented as a single column holding the whole fixed-width image,
     * and a column name written into this file would be an unverifiable literal. A keyed read would have
     * to discover the name from the backend because SQL admits no ordinal in a {@code WHERE} clause -
     * this class performs no keyed read, so the question never arises here.
     */
    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    /**
     * The scale {@code DALYTRAN-AMT} decodes at: {@value}, from
     * {@link CobolDecimal#MONETARY_SCALE}.
     *
     * <p>{@code PIC S9(09)V99} declares two fractional digits, and every receiver the amount reaches -
     * {@code TRAN-AMT}, {@code TRAN-CAT-BAL}, {@code ACCT-CURR-BAL}, {@code ACCT-CURR-CYC-CREDIT} and
     * {@code ACCT-CURR-CYC-DEBIT} - is itself scale 2, so no scale change occurs anywhere along the
     * posting path. Published so a caller asserts the contract against a named constant rather than
     * against the digit 2.
     */
    public static final int AMOUNT_SCALE = CobolDecimal.MONETARY_SCALE;

    /**
     * {@code APPL-RESULT} for a successful read: {@value}, from {@link FileStatus#APPL_AOK}.
     *
     * <p>{@code MOVE 0 TO APPL-RESULT} at {@code app/cbl/CBTRN02C.cbl:L348} and
     * {@code app/cbl/CBTRN01C.cbl:L205}.
     */
    public static final int APPL_RESULT_OK = FileStatus.APPL_AOK;

    /**
     * {@code APPL-RESULT} for the end of the file: {@value}, from {@link FileStatus#APPL_EOF}.
     *
     * <p>{@code MOVE 16 TO APPL-RESULT} at {@code app/cbl/CBTRN02C.cbl:L352} and
     * {@code app/cbl/CBTRN01C.cbl:L209}. Sixteen and not twelve, which is the whole point of the middle
     * arm: the {@code 88 APPL-EOF VALUE 16} condition ({@code CBTRN02C:L144}) is what routes an end of
     * file to the loop's exit instead of to the abend.
     */
    public static final int APPL_RESULT_EOF = FileStatus.APPL_EOF;

    /**
     * {@code APPL-RESULT} for a failed read, open or close: {@value}.
     *
     * <p>{@code MOVE 12 TO APPL-RESULT} at {@code app/cbl/CBTRN02C.cbl:L242}, {@code L354} and
     * {@code L589}, and at {@code app/cbl/CBTRN01C.cbl:L211}, {@code L258} and {@code L367}. Declared
     * here rather than in the shared vocabulary because it is the value these paragraphs move, not a
     * property of the status itself.
     */
    public static final int APPL_RESULT_FATAL = 12;

    /**
     * The VSAM extended-status feedback code reported for a permanent error: binary zero.
     *
     * <p>z/OS COBOL reports an implementor-defined permanent error as {@code '9'} in the first status
     * byte with a binary feedback code in the second, and {@code 9910-DISPLAY-IO-STATUS}
     * ({@code app/cbl/CBTRN02C.cbl}) is written specifically to decode that form: its
     * {@code IO-STAT1 = '9'} branch deposits the second byte into a binary item through a
     * {@code REDEFINES} and renders it as three decimal digits. Feedback code zero is the generic
     * "permanent error, no more specific code available", which is the honest report for a backend this
     * class cannot interrogate further.
     */
    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The two-character file status reported for a permanent error: {@code '9'} followed by
     * {@link #PERMANENT_ERROR_FEEDBACK_CODE}.
     *
     * <p>It renders as {@code "9000"} through {@link FileStatus#toStatusImage(String)} and therefore as
     * {@code FILE STATUS IS: NNNN9000} through {@link FileStatus#toDisplayLine(String)} - exactly the
     * line {@code 9910-DISPLAY-IO-STATUS} produces for this status. So the value is not invented for
     * this class: it is the extended-status form the shared status vocabulary was built to render.
     *
     * <p>It is deliberately not one of the statuses the guard names explicitly.
     * {@link FileStatus#outcomeOfStatus(String)} classifies it as {@link Outcome#OTHER}, which is the
     * {@code MOVE 12} arm, which is the abend path - exactly where an I/O failure belongs.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    /**
     * The module's single {@link JdbcTemplate}, constructor-injected.
     *
     * <p>Held for its {@link JdbcTemplate#getDataSource()}: the cursor this class opens needs a
     * connection of its own, and taking it from the module's one template keeps the data source a
     * single injected thing rather than a second one wired in beside it.
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * The hand-written fixed-width codec over the injected dataset code page. Immutable and stateless,
     * so one instance is shared safely by every call.
     */
    private final FixedWidthCodec codec;

    /**
     * How this deployment's driver presents a record image over JDBC: as characters or as bytes.
     *
     * <p>Injected rather than decided here. A reader that chose for itself would eventually disagree
     * with the writers of the same deployment, and a one-byte disagreement in
     * {@code DALYTRAN-AMT}'s sign overpunch is the difference between a credit and a debit.
     */
    private final RecordImageForm recordImageForm;

    /**
     * The dataset as this module reaches it: the validated name, its delimited rendering and the one
     * statement composed over it.
     *
     * <p>Shared with every repository in the module, so how a configured dataset name becomes a SQL
     * identifier is decided in one place rather than once per class.
     */
    private final DatasetRelation relation;

    /**
     * The unordered select this class reads through, composed once at construction.
     *
     * <p>Composed once because it never varies: there is no key to bind, no position to express and no
     * ordering to impose, so the statement for the first record and the statement for the last are the
     * same text.
     */
    private final String selectRecordSql;

    /**
     * The 350-byte layout, taken from {@link DalyTranRecord#LAYOUT} rather than restated.
     *
     * <p>Held so the geometry can be asserted at construction and surfaced to this class's tests. It is
     * deeply immutable - a record holding an immutable list of records - so sharing it grants nothing
     * that can be altered.
     */
    private final RecordLayout layout;

    /**
     * Resolves the {@code DALYTRAN} binding, proves the record geometry and captures the collaborators
     * this repository needs - all of it while the context is still building, so nothing that can be
     * checked early is left to fail in the middle of a posting run.
     *
     * <p>Four things are established, and each one would otherwise surface as a plausible-looking
     * misread rather than as a failure:
     * <ol>
     *   <li>the code page is single-byte and carries the pad and sign-overpunch characters unchanged,
     *       because a fixed-width record is addressed by absolute byte offset;</li>
     *   <li>the configured record length is {@link #RECORD_LENGTH}. A binding that declares any other
     *       width is refused rather than honoured: this class decodes by absolute offset, so a
     *       different width silently misplaces every field after the first;</li>
     *   <li>the fourteen declared spans of {@link DalyTranRecord#LAYOUT} still sum to
     *       {@link #RECORD_LENGTH} with {@code FILLER} included (gates G19, G21);</li>
     *   <li>the configured dataset name is usable as a SQL identifier, checked by the module's shared
     *       grammar rather than by a rule restated here.</li>
     * </ol>
     *
     * @param jdbcTemplate    the module's shared template, whose data source the read cursor is taken
     *                        from
     * @param datasetBindings the {@code carddemo.datasets} catalogue - dataset names live in
     *                        configuration and are never written in Java
     * @param datasetCharset  the dataset code page, injected as
     *                        {@value CobolCharsetConfig#DATASET_CHARSET_BEAN_NAME} and stated
     *                        explicitly at every encode and decode
     * @param recordImageForm how the deployment's driver presents a record image, from
     *                        {@value RecordImageForm#FORM_PROPERTY}
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if no binding is configured for {@link #DD_NAME}, if the binding
     *                               declares a record length other than {@link #RECORD_LENGTH}, if the
     *                               declared spans no longer account for every byte, or if the
     *                               configured dataset name is unusable
     * @throws IllegalArgumentException if {@code datasetCharset} is not a single-byte code page
     */
    public DalyTranRepository(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm) {

        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + DD_NAME + " read cursor is opened on the data source the module's shared template "
                + "was configured with");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "dataset names live in configuration and are never written in Java");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset is "
                + "required: a fixed-width mainframe record is bytes in a specific code page, so the "
                + "code page is stated explicitly and never taken from the platform"));
        this.recordImageForm = Objects.requireNonNull(recordImageForm, "A record-image representation "
                + "is required: whether this deployment's driver presents a record image as characters "
                + "or as bytes is stated once, by " + RecordImageForm.FORM_PROPERTY + ", and never "
                + "decided per repository");
        RecordImageForm.requireSingleByteCodePage(datasetCharset);

        DatasetBinding binding = datasetBindings.binding(DD_NAME);
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' declares a "
                    + "record length of " + binding.recordLength() + ", but the daily-transaction "
                    + "record is " + RECORD_LENGTH + " bytes: app/cpy/CVTRA06Y.cpy declares "
                    + "RECLN = " + RECORD_LENGTH + " and app/cbl/CBTRN02C.cbl:L66-L69 confirms it as "
                    + "FD-TRAN-ID PIC X(16) plus FD-CUST-DATA PIC X(334). This repository addresses "
                    + "the record by absolute offset, so a differently-sized record would misplace "
                    + "every field after DALYTRAN-ID. Correct carddemo.datasets." + DD_NAME
                    + ".record-length to " + RECORD_LENGTH + ".");
        }
        this.layout = requireDeclaredGeometry(DalyTranRecord.LAYOUT,
                DalyTranRecord.sumOfDeclaredSpanLengths());
        this.relation = DatasetRelation.of(requireUsableDatasetName(binding.dsname()), RECORD_LENGTH);
        this.selectRecordSql = this.relation.selectAll();
    }

    /**
     * The resolved dataset name, exactly as configuration declares it.
     *
     * <p>Exposed for diagnostics and for a caller that reports which dataset it read - not as a way to
     * reach around this class. The value is configured, never hard-coded, so surfacing it introduces no
     * dataset literal into Java (gate G46).
     *
     * @return the configured dataset name for {@link #DD_NAME}; never {@code null} and never blank
     */
    public String datasetName() {
        return relation.dsname();
    }

    /**
     * The record width this repository reads, always {@link #RECORD_LENGTH}.
     *
     * @return {@value #RECORD_LENGTH}
     */
    public int recordLength() {
        return RECORD_LENGTH;
    }

    /**
     * The code page this repository decodes record images in.
     *
     * <p>Surfaced so a caller that renders or re-encodes a record uses the same code page rather than
     * choosing one of its own. {@code CBTRN02C:L447} moves the whole 350-byte record onto the rejects
     * file and {@code CBTRN01C:L168} displays it, and both are byte comparisons.
     *
     * @return the injected dataset charset; never {@code null}
     */
    public Charset datasetCharset() {
        return codec.charset();
    }

    /**
     * The unordered select this repository reads through.
     *
     * <p>Package-visible so this class's tests assert the composed text - in particular that it carries
     * no {@code ORDER BY} - without reaching around the class.
     *
     * @return {@code SELECT * FROM <relation>}
     */
    String selectRecordSql() {
        return selectRecordSql;
    }

    /**
     * The 350-byte layout this repository decodes against.
     *
     * <p>Package-visible for the same reason as {@link #selectRecordSql()}. Deeply immutable.
     *
     * @return the layout; never {@code null}
     */
    RecordLayout layout() {
        return layout;
    }

    // =================================================================================================
    // 0000-DALYTRAN-OPEN - app/cbl/CBTRN02C.cbl:L236-L250 and app/cbl/CBTRN01C.cbl:L252-L268.
    // =================================================================================================

    /**
     * Opens the dataset for input and positions before its first record: the Java form of
     * {@code OPEN INPUT DALYTRAN-FILE}.
     *
     * <p>Both consumers precede the verb with {@code MOVE 8 TO APPL-RESULT} - {@code CBTRN01C} spells it
     * {@code ADD 8 TO ZERO GIVING APPL-RESULT} - and then test {@code IF DALYTRAN-STATUS = '00'},
     * moving {@code 0} on success and {@code 12} otherwise. This method reproduces exactly the quantity
     * that test reads and nothing more: {@link DalytranFile#openStatus()} is {@link FileStatus#OK} or
     * {@link #PERMANENT_ERROR_STATUS}. The {@code MOVE 8} priming value is not modelled, because it is
     * only there so that a paragraph which fell through both arms would still be non-{@code AOK}; the
     * arms here are exhaustive. The error line and the abend belong to the caller, for the reason set
     * out on this class - the two consumers do not use the same text.
     *
     * <p><strong>What an open actually does here.</strong> It takes a connection of its own from the
     * module's data source, prepares the unordered select forward-only and read-only, executes it, and
     * confirms the answer presents a column at position {@value #RECORD_IMAGE_COLUMN_INDEX}. That is a
     * genuine dataset-scoped check rather than a connection test: an absent or unreachable dataset fails
     * here, which is what a COBOL {@code OPEN} reports, whereas a connection-only probe would succeed
     * against a reachable backend holding no such dataset and then hand the job an end of file it should
     * have seen as a failed open.
     *
     * <p>The returned handle owns the cursor, the position, the record count and the closed flag. Two
     * concurrent opens therefore read independently, which matters because this class is a singleton.
     * <strong>The handle must be closed.</strong> It is {@link AutoCloseable} for that reason, and a
     * failed open returns a handle that is safe to close and reports its own status when it is.
     *
     * <p>No exception escapes for an I/O condition: a refusal becomes {@link #PERMANENT_ERROR_STATUS} on
     * the handle, because the COBOL turns it into a status and lets its own guard chain decide. What the
     * backend said is not discarded with it - it is logged in sanitized form so the abend that follows
     * can be traced to a cause.
     *
     * @return the open handle, successful or failed; never {@code null}
     */
    public DalytranFile open() {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            // Not an I/O outcome and not a programming error either: the template was built without a
            // data source, which is a configuration defect. Reported as a failed open rather than
            // thrown, so a job reaches its own 'ERROR OPENING' arm instead of dying with a stack trace.
            LOG.error("Could not open the " + DD_NAME + " dataset for input: the module's JdbcTemplate "
                    + "carries no DataSource, so there is no connection to read the dataset through. "
                    + "Reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " to the caller");
            return new DalytranFile(this, PERMANENT_ERROR_STATUS, null);
        }

        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet rows = null;
        try {
            connection = dataSource.getConnection();
            // Forward-only and read-only: the JDBC statement of ORGANIZATION IS SEQUENTIAL with
            // ACCESS MODE IS SEQUENTIAL on an OPEN INPUT. No scrollability is requested, because no
            // consumer repositions, and no updatability, because no consumer writes.
            statement = connection.prepareStatement(selectRecordSql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            rows = statement.executeQuery();
            ResultSetMetaData metaData = rows.getMetaData();
            if (metaData == null || metaData.getColumnCount() < RECORD_IMAGE_COLUMN_INDEX) {
                // The dataset resolved but presents nothing this class can read. Treated as an unusable
                // dataset rather than as an empty one: reporting success here would hand the job an end
                // of file over a file it never actually read.
                LOG.error("Could not open the " + DD_NAME + " dataset for input: the backend describes "
                        + "no column at position " + RECORD_IMAGE_COLUMN_INDEX + ", so it is not "
                        + "presenting the dataset as a record-image relation. Reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
                releaseQuietly(rows, statement, connection, "a refused open");
                return new DalytranFile(this, PERMANENT_ERROR_STATUS, null);
            }
            return new DalytranFile(this, FileStatus.OK, new Cursor(connection, statement, rows));
        } catch (SQLException refusal) {
            releaseQuietly(rows, statement, connection, "a refused open");
            logRefusal(refusal, "open the " + DD_NAME + " dataset for input (a read of the configured "
                    + "relation, which is what distinguishes an absent dataset from an empty one)");
            return new DalytranFile(this, PERMANENT_ERROR_STATUS, null);
        }
    }

    // =================================================================================================
    // 1000-DALYTRAN-GET-NEXT - app/cbl/CBTRN02C.cbl:L345-L369 and app/cbl/CBTRN01C.cbl:L202-L225.
    // =================================================================================================

    /**
     * Reads the next record in physical dataset order: the Java form of
     * {@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD} and of the guard that classifies its status.
     *
     * <p>Four reported outcomes, and the first three are the three arms the COBOL enumerates:
     * <ul>
     *   <li><strong>found</strong> - status {@code '00'}, carrying the decoded record.
     *       {@code MOVE 0 TO APPL-RESULT}, and the consumer processes it;</li>
     *   <li><strong>end of file</strong> - status {@code '10'}, carrying no record.
     *       {@code MOVE 16 TO APPL-RESULT}, and the consumer sets its end-of-file flag and leaves the
     *       loop. Reported <strong>idempotently</strong>: once the file has ended, every further call
     *       reports the end again and none reaches the backend. That is faithful - both mainlines stop
     *       on the flag and never resume - and it is also what makes a redundant call harmless rather
     *       than a fresh round trip;</li>
     *   <li><strong>other</strong> - any other status, carried verbatim so the caller renders it exactly
     *       as {@code 9910-DISPLAY-IO-STATUS} does through {@link FileStatus#toDisplayLine(String)}.
     *       {@code MOVE 12 TO APPL-RESULT}, and the consumer displays, renders and abends. Three
     *       conditions reach it: a backend refusal, a row whose record image is absent, and a row that
     *       is not {@link #RECORD_LENGTH} bytes wide;</li>
     *   <li><strong>other</strong> carrying the <em>open's</em> status, when the open failed - so a
     *       caller that ignored {@link DalytranFile#openStatus()} still cannot mistake a dataset it
     *       never reached for one that was empty.</li>
     * </ul>
     *
     * <p><strong>No record is carried on an end of file, and the caller's own copy is left alone.</strong>
     * {@code READ ... INTO} performs its move only when a record is read, so at {@code AT END} the
     * receiving area still holds the previous record - and {@code CBTRN01C} depends on precisely that:
     * its mainline at {@code app/cbl/CBTRN01C.cbl:L171} moves {@code DALYTRAN-CARD-NUM} and performs
     * {@code 2000-LOOKUP-XREF} <em>outside</em> the {@code IF END-OF-DAILY-TRANS-FILE = 'N'} guard, so
     * after the last record it looks the previous card number up a second time. That is real legacy
     * behaviour, it is reproduced by returning no record rather than a blank one, and a reader that
     * cleared the caller's record would silently change it.
     *
     * <p><strong>Order is physical, and nothing here reorders it.</strong> The statement carries no
     * {@code ORDER BY}, the cursor is forward-only, and one row is transferred per call. The rejected
     * alternatives, and why each would have changed the posting sequence, are set out on this class.
     *
     * <p><strong>A row that is not exactly {@link #RECORD_LENGTH} bytes is reported, not repaired.</strong>
     * Widening a short one with spaces looks like the faithful repair, since the absent bytes could only
     * be trailing {@code FILLER}. What makes that wrong here is what the record is used for:
     * {@code app/cbl/CBTRN02C.cbl:L425-L436} maps every field of it onto {@code TRAN-RECORD} and posts
     * it, and {@code L447} moves the whole 350-byte group onto the 430-byte rejects record. A row
     * truncated inside {@code DALYTRAN-AMT} pads into a <em>different amount</em> - the sign overpunch
     * is the last byte of that field - and the job would post it successfully with nothing anywhere
     * saying the amount was not the amount the dataset held. So the width is required, and a
     * disagreement takes the arm the COBOL takes for a failed read.
     *
     * @param file the handle {@link #open()} returned, which owns the position
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException  if {@code file} is {@code null}
     * @throws IllegalArgumentException if {@code file} was opened by a different repository instance
     * @throws IllegalStateException if {@code file} has been closed
     */
    public ReadResult readNext(DalytranFile file) {
        Objects.requireNonNull(file, "An open " + DD_NAME + " handle is required to read from it; a "
                + "COBOL READ addresses an open file and there is no such thing as reading nothing");
        requireOwnHandle(file, "read the next record");
        file.requireOpen("read the next record");

        Cursor cursor = file.cursor();
        if (cursor == null) {
            // The OPEN never succeeded, so its status is reported rather than a fresh one and nothing is
            // sent to the backend. Deliberately not an end of file: a dataset that was never opened is
            // not a dataset that was empty.
            return ReadResult.other(file.openStatus());
        }
        if (file.atEndOfFile()) {
            return ReadResult.endOfFile();
        }

        byte[] recordImage;
        try {
            if (!cursor.next()) {
                // AT END. An expected outcome, not an error: the flag is set so every later call answers
                // from the handle instead of asking the backend again.
                file.markEndOfFile();
                return ReadResult.endOfFile();
            }
            recordImage = recordImageForm.readImage(cursor.rows(), RECORD_IMAGE_COLUMN_INDEX,
                    codec.charset());
        } catch (SQLException refusal) {
            // The fatal arm. Reported as a status so the caller's own guard chain decides what to do
            // about it - which, in both consumers, is to display, render the status and abend - and
            // carrying the backend's own diagnosis so that abend can be traced to a cause.
            return ReadResult.other(PERMANENT_ERROR_STATUS,
                    logRefusal(refusal, "read the next record of the " + DD_NAME + " dataset"));
        }

        if (recordImage == null) {
            // A row whose record image is absent is not a readable 350-byte record. There IS a record,
            // it simply cannot be read, so this is an I/O-level defect and must not be mistaken for an
            // end of file - a read that stopped here silently would post a fraction of the day.
            LOG.error("The " + DD_NAME + " dataset presented a row with no record image at column "
                    + "position " + RECORD_IMAGE_COLUMN_INDEX + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller rather than "
                    + "an end of file");
            return ReadResult.other(PERMANENT_ERROR_STATUS);
        }
        if (recordImage.length != RECORD_LENGTH) {
            // Reported rather than padded, for the reason given in this method's documentation. The
            // observed width is named through the shared observation type, which labels it as a width
            // rather than emitting a bare number, and no record content is logged.
            LOG.error("The " + DD_NAME + " dataset presented a row whose "
                    + DatasetObservation.recordWidth(recordImage.length).describe() + ", but "
                    + "DALYTRAN-RECORD is declared RECLN = " + RECORD_LENGTH + " by "
                    + "app/cpy/CVTRA06Y.cpy and confirmed as 16 + 334 by "
                    + "app/cbl/CBTRN02C.cbl:L66-L69; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than padding the row "
                    + "into a transaction the dataset does not contain");
            return ReadResult.other(PERMANENT_ERROR_STATUS);
        }

        file.countRecord();
        return ReadResult.found(decode(recordImage));
    }

    // =================================================================================================
    // 9000-DALYTRAN-CLOSE - app/cbl/CBTRN02C.cbl:L582-L598 and app/cbl/CBTRN01C.cbl:L361-L378.
    // =================================================================================================

    /**
     * Closes the dataset and releases the cursor: the Java form of {@code CLOSE DALYTRAN-FILE}.
     *
     * <p>Same two-way shape as the open - {@link FileStatus#OK}, or {@link #PERMANENT_ERROR_STATUS} when
     * the release itself failed - and the caller again owns the error line and the abend. Note that
     * {@code CBTRN01C}'s close paragraph reports the <em>customer</em> file's status on its failure arm;
     * that transcription defect is described on this class and is the caller's to reproduce, because
     * this method can only honestly answer for the dataset it was asked about.
     *
     * <p><strong>Idempotent.</strong> Closing an already-closed handle returns {@link FileStatus#OK} and
     * touches nothing, which is what makes {@code close()} safe in a {@code try}-with-resources around a
     * body that already closed explicitly. Closing a handle whose open failed returns that open's own
     * status, and issues nothing: there was never an open file to close.
     *
     * <p>After this returns, the handle is spent. A read against it is a programming error and throws;
     * see {@link #readNext(DalytranFile)}.
     *
     * @param file the handle {@link #open()} returned
     * @return {@link FileStatus#OK} or {@link #PERMANENT_ERROR_STATUS}; never {@code null}, always
     *         {@value FileStatus#STATUS_LENGTH} characters
     * @throws NullPointerException     if {@code file} is {@code null}
     * @throws IllegalArgumentException if {@code file} was opened by a different repository instance
     */
    public String close(DalytranFile file) {
        Objects.requireNonNull(file, "An open " + DD_NAME + " handle is required to close it; a COBOL "
                + "CLOSE addresses an open file");
        requireOwnHandle(file, "close the file");
        return file.releaseCursor();
    }

    // =================================================================================================
    // The decode seam. Pure, hand-written, addressed entirely by absolute offset (practice B11), and
    // free of JDBC on purpose - which is what lets the parity cases and the unit tests exercise the
    // byte-level behaviour with no backend in the path. Residual risk R-E makes that not merely
    // convenient but necessary: no backend is reachable from this build.
    // =================================================================================================

    /**
     * Decodes stored record bytes into a {@link DalyTranRecord}, with no backend in the path.
     *
     * <p>The bytes are retained verbatim as the record's backing span, sign overpunch and
     * {@code FILLER} included, so {@link DalyTranRecord#rawImage()} reproduces the input byte for byte -
     * which is what {@code app/cbl/CBTRN02C.cbl:L447}'s whole-group move onto the rejects file and
     * {@code app/cbl/CBTRN01C.cbl:L168}'s display of the whole record both require. Field values are
     * decoded on demand, so nothing is normalised on the way in.
     *
     * @param recordImage the stored record bytes, exactly {@link #RECORD_LENGTH} of them
     * @return the decoded record; never {@code null}
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if {@code recordImage} is not exactly {@link #RECORD_LENGTH}
     *                                  bytes. A short row is refused rather than padded, for the reason
     *                                  given on {@link #readNext(DalytranFile)}
     */
    public DalyTranRecord decode(byte[] recordImage) {
        Objects.requireNonNull(recordImage, "Record bytes are required to decode a DALYTRAN-RECORD; an "
                + "absent record is an end-of-file outcome, not a decodable image");
        if (recordImage.length != RECORD_LENGTH) {
            throw new IllegalArgumentException("DALYTRAN-RECORD is declared RECLN = " + RECORD_LENGTH
                    + " by app/cpy/CVTRA06Y.cpy and this image is " + recordImage.length + " byte(s). "
                    + "It is not padded to width: app/cbl/CBTRN02C.cbl:L425-L436 maps every field of "
                    + "the record onto TRAN-RECORD and posts it, so a row truncated inside "
                    + "DALYTRAN-AMT would pad into a different amount and post successfully. Widen it "
                    + "deliberately at the call site if a short row is genuinely expected.");
        }
        return DalyTranRecord.decode(recordImage, codec.charset());
    }

    /**
     * Decodes a stored record image supplied as text, for the ASCII fixture and the parity cases.
     *
     * <p>A convenience over {@link #decode(byte[])} for {@code app/data/ASCII/dailytran.txt}, whose 300
     * rows are each exactly {@link #RECORD_LENGTH} characters. The code page is this repository's own
     * rather than the caller's, so a caller holding a row as text does not have to choose one - and the
     * character-to-byte mapping of the zoned sign overpunches depends on that choice.
     *
     * @param recordImage the record's text image, exactly {@link #RECORD_LENGTH} characters under this
     *                    repository's charset
     * @return the decoded record; never {@code null}
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if {@code recordImage} does not encode to exactly
     *                                  {@link #RECORD_LENGTH} bytes
     */
    public DalyTranRecord decode(String recordImage) {
        Objects.requireNonNull(recordImage, "A record image is required to decode a DALYTRAN-RECORD; an "
                + "absent record is an end-of-file outcome, not a decodable image");
        return decode(codec.encodeImage(recordImage, "a " + DD_NAME + " record image"));
    }

    // =================================================================================================
    // Private support. Nothing below reaches a caller, and nothing below holds state.
    // =================================================================================================

    /**
     * Refuses a handle this repository did not open.
     *
     * <p>A handle owns a live cursor, so operating one repository's handle through another's would read
     * one dataset through another's charset and record-image representation. No COBOL path corresponds to
     * that, so it is a programming error and is reported as one rather than turned into a file status.
     *
     * @param file    the handle presented
     * @param attempt what was being attempted, for the diagnostic
     * @throws IllegalArgumentException if {@code file} belongs to another repository instance
     */
    private void requireOwnHandle(DalytranFile file, String attempt) {
        if (file.repository() != this) {
            throw new IllegalArgumentException("Cannot " + attempt + ": the handle was opened by a "
                    + "different " + DalyTranRepository.class.getSimpleName() + " instance. A handle "
                    + "owns a live cursor over one configured dataset, read in one code page, so it is "
                    + "only meaningful to the repository that opened it. Use the handle's own "
                    + "readNext() and closeFile(), or the repository that returned it.");
        }
    }

    /**
     * Re-proves the record geometry before any read is performed: the gate G19 and G21 arithmetic.
     *
     * <p>Two facts are required, and they are genuinely separate:
     * <ol>
     *   <li>the declared spans account for every byte of the layout they belong to. That only holds
     *       while {@code FILLER X(20)} is declared and {@code DALYTRAN-AMT} is eleven bytes - drop
     *       either and the sum is no longer the width;</li>
     *   <li>that width is the copybook's {@code RECLN = 350}. A layout of any other width would place
     *       every field somewhere the dataset does not hold it.</li>
     * </ol>
     *
     * <p>The span total is passed in rather than derived here, which is what makes both conditions
     * independently reachable: a caller can present a layout whose width is right and a total that is
     * not, or a coherent layout of the wrong width, and each takes its own arm. A guard whose arms
     * cannot be reached is a guard nobody has checked, so this one is written to be checkable.
     *
     * <p>Package-visible for that reason, and it holds no state: the layout it returns is the one it was
     * given, deeply immutable.
     *
     * @param candidate         the layout to read the record through
     * @param declaredSpanTotal the sum of that layout's declared span lengths
     * @return {@code candidate}, once proven
     * @throws NullPointerException  if {@code candidate} is {@code null}
     * @throws IllegalStateException if the spans do not account for the layout's width, or the width is
     *                               not {@link #RECORD_LENGTH}
     */
    static RecordLayout requireDeclaredGeometry(RecordLayout candidate, int declaredSpanTotal) {
        Objects.requireNonNull(candidate, "A record layout is required: the record is addressed by "
                + "absolute offset, and there is no reading it without one");
        if (declaredSpanTotal != candidate.recordLength()
                || candidate.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("The declared spans sum to " + declaredSpanTotal
                    + " over a layout of " + candidate.recordLength() + " byte(s), and both must be "
                    + RECORD_LENGTH + ". This is the gate G21 arithmetic: the fourteen spans of "
                    + "app/cpy/CVTRA06Y.cpy - FILLER X(20) included - account for every byte of "
                    + "DALYTRAN-RECORD, and they only sum to " + RECORD_LENGTH + " while FILLER is "
                    + "declared and DALYTRAN-AMT is 11 bytes. A read is refused rather than performed "
                    + "against a layout whose arithmetic no longer holds.");
        }
        return candidate;
    }

    /**
     * Validates the configured dataset name before it becomes a SQL identifier.
     *
     * <p>The grammar - what a z/OS dataset name may contain - lives in {@link DatasetRelation}, so it is
     * stated once for the whole module rather than restated, and diverging, in each class. Only the
     * "configured at all" check is local, because only this class knows which key was missing.
     *
     * @param candidate the configured name
     * @return the validated name
     * @throws IllegalStateException if no name is configured
     */
    private static String requireUsableDatasetName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for DD name '" + DD_NAME + "' declares "
                    + "no dataset name. Set carddemo.datasets." + DD_NAME + ".dsname; this repository "
                    + "composes its statement from configuration alone and hard-codes no dataset name.");
        }
        return DatasetRelation.requireDatasetName(candidate);
    }

    /**
     * Logs a backend refusal with the driver's own diagnosis and returns it.
     *
     * <p>Logged: what was attempted, and the {@code SQLSTATE}, vendor code and exception type the driver
     * reported. Not logged: the record image, and not the exception either. A driver's message is prose
     * it composed around the values it refused, so handing the {@link Throwable} to the logger emits
     * that text and its whole cause chain verbatim (CWE-532) in a form a control character can split into
     * a forged entry (CWE-117). Sanitising the summary and attaching the raw exception beside it
     * sanitises nothing, which is why every line in this file is a single composed argument.
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

    /**
     * Releases whatever part of a cursor was acquired, in reverse order of acquisition, without letting
     * a failure to release mask the failure that caused the release.
     *
     * <p>Used on the open's own failure paths, where the outcome is already decided: a connection left
     * open by a refused open is a leak that outlives the job, so each resource is closed independently
     * and a refusal to close is logged rather than propagated. {@link Cursor#release()} is the
     * counterpart for a successful open, where the caller <em>is</em> told whether the release worked.
     *
     * @param rows       the result set, or {@code null} if never obtained
     * @param statement  the statement, or {@code null} if never prepared
     * @param connection the connection, or {@code null} if never acquired
     * @param context    what the release is cleaning up after, for the diagnostic
     */
    private static void releaseQuietly(ResultSet rows, PreparedStatement statement,
            Connection connection, String context) {
        if (rows != null) {
            try {
                rows.close();
            } catch (SQLException ignored) {
                logRefusal(ignored, "release the " + DD_NAME + " result set after " + context);
            }
        }
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException ignored) {
                logRefusal(ignored, "release the " + DD_NAME + " statement after " + context);
            }
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                logRefusal(ignored, "release the " + DD_NAME + " connection after " + context);
            }
        }
    }

    /**
     * The live forward-only cursor behind one open: the JDBC form of an open sequential file's position.
     *
     * <p>Private and not exposed: a caller holds a {@link DalytranFile}, which is what the position and
     * the record count belong to, and never the JDBC objects themselves. The three resources are held
     * together because they are released together and in one order - result set, statement, connection -
     * and a partial release is a leak.
     */
    private static final class Cursor {

        /** The dedicated connection this cursor runs on. Never the thread's transactional connection. */
        private final Connection connection;

        /** The prepared unordered select, forward-only and read-only. */
        private final PreparedStatement statement;

        /** The open result set. This is the file position: {@code next()} is the {@code READ}. */
        private final ResultSet rows;

        /**
         * @param connection the dedicated connection
         * @param statement  the prepared select
         * @param rows       the executed result set
         */
        private Cursor(Connection connection, PreparedStatement statement, ResultSet rows) {
            this.connection = connection;
            this.statement = statement;
            this.rows = rows;
        }

        /**
         * Advances one row: the {@code READ} itself.
         *
         * @return {@code true} when a record is now current, {@code false} at the end of the file
         * @throws SQLException if the driver cannot advance
         */
        private boolean next() throws SQLException {
            return rows.next();
        }

        /**
         * The current row, for the configured record-image representation to read the image out of.
         *
         * @return the positioned result set; never {@code null}
         */
        private ResultSet rows() {
            return rows;
        }

        /**
         * Releases all three resources, in reverse order of acquisition, and says whether every one of
         * them released cleanly.
         *
         * <p>Every resource is attempted even when an earlier one fails, because stopping at the first
         * failure would leak the connection - which is the one that matters. The answer is the
         * conjunction, so a single refusal makes the close report a non-{@code '00'} status, exactly as
         * a file system complaining at de-allocation makes a COBOL {@code CLOSE} do.
         *
         * @return {@code true} when every resource released without complaint
         */
        private boolean release() {
            boolean clean = true;
            try {
                rows.close();
            } catch (SQLException refusal) {
                clean = false;
                logRefusal(refusal, "close the " + DD_NAME + " result set");
            }
            try {
                statement.close();
            } catch (SQLException refusal) {
                clean = false;
                logRefusal(refusal, "close the " + DD_NAME + " statement");
            }
            try {
                connection.close();
            } catch (SQLException refusal) {
                clean = false;
                logRefusal(refusal, "close the " + DD_NAME + " connection");
            }
            return clean;
        }
    }

    /**
     * One open of the dataset: the Java form of the file position an {@code OPEN INPUT} establishes and
     * each {@code READ} advances.
     *
     * <p>Every piece of per-read state lives here rather than on the repository, which is a singleton: the
     * cursor, whether the end of the file has been seen, how many records have been read, and whether the
     * file has been closed. Two concurrent opens therefore cannot interleave each other's reads (gate
     * G53).
     *
     * <p>It is {@link AutoCloseable}, so the whole of an {@code OPEN}/{@code READ}-loop/{@code CLOSE}
     * sequence fits a {@code try}-with-resources and the cursor cannot be leaked by an early return. A
     * failed open returns a handle too - one that carries the failure's status, reads nothing and is safe
     * to close - so a caller never has to null-check what it got back.
     *
     * <p>Not thread-safe, and deliberately so: a COBOL file position is not either. One handle belongs to
     * one reader.
     */
    public static final class DalytranFile implements AutoCloseable {

        /** The repository that opened this file, for its cursor, its codec and its dataset identity. */
        private final DalyTranRepository repository;

        /**
         * The status the {@code OPEN} reported: {@link FileStatus#OK}, or
         * {@link DalyTranRepository#PERMANENT_ERROR_STATUS} when the dataset could not be read.
         */
        private final String openStatus;

        /**
         * The live cursor, or {@code null} once released - and from the outset when the open failed.
         *
         * <p>The only mutable reference here that is not a counter, and it is nulled on close so a spent
         * handle cannot hold a connection alive.
         */
        private Cursor cursor;

        /**
         * Whether {@code AT END} has been reached: the Java form of {@code END-OF-FILE = 'Y'}
         * ({@code app/cbl/CBTRN02C.cbl:L146}) and of {@code END-OF-DAILY-TRANS-FILE = 'Y'}
         * ({@code app/cbl/CBTRN01C.cbl}).
         *
         * <p>Held so a further read answers from the handle rather than asking the backend again, which
         * is both faithful - neither mainline resumes after the flag is set - and what makes the end of
         * file idempotent rather than a repeated round trip.
         */
        private boolean endOfFile;

        /** How many records have been read successfully: the analogue of {@code WS-TRANSACTION-COUNT}. */
        private long recordsRead;

        /** Whether {@link #closeFile()} has been called. */
        private boolean closed;

        /**
         * Constructed only by {@link DalyTranRepository#open()}, which is what guarantees that a
         * successful open always arrives with a live cursor and a failed one never does.
         *
         * @param repository the opening repository
         * @param openStatus the status the open reported
         * @param cursor     the live cursor for a successful open, or {@code null} for a failed one
         */
        private DalytranFile(DalyTranRepository repository, String openStatus, Cursor cursor) {
            this.repository = repository;
            this.openStatus = openStatus;
            this.cursor = cursor;
            this.endOfFile = false;
            this.recordsRead = 0L;
            this.closed = false;
        }

        /**
         * The status the {@code OPEN} reported: the value {@code IF DALYTRAN-STATUS = '00'} tests at
         * {@code app/cbl/CBTRN02C.cbl:L239} and {@code app/cbl/CBTRN01C.cbl:L255} before moving
         * {@code 0} or {@code 12} into {@code APPL-RESULT}.
         *
         * @return {@link FileStatus#OK} or {@link DalyTranRepository#PERMANENT_ERROR_STATUS}; never
         *         {@code null}, always {@value FileStatus#STATUS_LENGTH} characters
         */
        public String openStatus() {
            return openStatus;
        }

        /**
         * The open status classified.
         *
         * @return {@link Outcome#OK} for a successful open, otherwise {@link Outcome#OTHER}
         */
        public Outcome openOutcome() {
            return FileStatus.outcomeOfStatus(openStatus);
        }

        /**
         * Whether the open succeeded: the {@code IF APPL-AOK} test that follows it.
         *
         * @return {@code true} when the dataset is open and readable
         */
        public boolean isOpen() {
            return !closed && cursor != null;
        }

        /**
         * Whether {@code AT END} has been reached: the value of {@code END-OF-FILE} /
         * {@code END-OF-DAILY-TRANS-FILE} that both mainline loops test.
         *
         * @return {@code true} once a read has reported the end of the file
         */
        public boolean atEndOfFile() {
            return endOfFile;
        }

        /**
         * How many records this open has read successfully.
         *
         * <p>The analogue of {@code ADD 1 TO WS-TRANSACTION-COUNT} at
         * {@code app/cbl/CBTRN02C.cbl:L206}, exposed so a job reports the count the COBOL displays at
         * {@code L227} without keeping a second counter that could disagree with the reads that actually
         * happened.
         *
         * @return the number of {@code '00'} reads, never negative
         */
        public long recordsRead() {
            return recordsRead;
        }

        /**
         * Whether this handle has been closed.
         *
         * @return {@code true} after {@link #closeFile()} or {@link #close()}
         */
        public boolean isClosed() {
            return closed;
        }

        /**
         * The configured dataset name this handle reads.
         *
         * @return the configured name; never {@code null}
         */
        public String datasetName() {
            return repository.datasetName();
        }

        /**
         * Reads the next record through the repository that opened this handle.
         *
         * <p>Identical in every way to {@link DalyTranRepository#readNext(DalytranFile)}; it exists so a
         * read loop reads as a loop over a file rather than as a loop over a repository, which is the
         * shape the sibling repositories in this module already use.
         *
         * @return the discriminated outcome; never {@code null}
         * @throws IllegalStateException if this handle has been closed
         */
        public ReadResult readNext() {
            return repository.readNext(this);
        }

        /**
         * Closes the file and reports the resulting status: {@link DalyTranRepository#close(DalytranFile)}
         * against the handle it is called on.
         *
         * @return {@link FileStatus#OK} or {@link DalyTranRepository#PERMANENT_ERROR_STATUS}
         */
        public String closeFile() {
            return releaseCursor();
        }

        /**
         * Closes the file, discarding the status: the {@link AutoCloseable} contract.
         *
         * <p>Discarding is correct <em>only</em> here. A caller that has to reproduce
         * {@code 9000-DALYTRAN-CLOSE}'s guard needs the status and must call {@link #closeFile()} or
         * {@link DalyTranRepository#close(DalytranFile)}; this method exists so that a
         * {@code try}-with-resources cannot leak the cursor when the body throws, and in that situation
         * the exception in flight is the more informative failure. A release refusal is still logged, so
         * nothing is lost silently.
         */
        @Override
        public void close() {
            releaseCursor();
        }

        /**
         * The one release path, shared by {@link #closeFile()}, {@link #close()} and
         * {@link DalyTranRepository#close(DalytranFile)} so the idempotence rule is stated once.
         *
         * @return the status the close reports
         */
        private String releaseCursor() {
            if (closed) {
                // Idempotent: a second close is not a failed close. This is what makes close() safe
                // inside a try-with-resources whose body already closed explicitly.
                return FileStatus.OK;
            }
            closed = true;
            Cursor released = cursor;
            cursor = null;
            if (released == null) {
                // The OPEN never succeeded, so there is no open file to close: its own status is reported
                // rather than a fresh one, and nothing is issued for a file that was never opened.
                return openStatus;
            }
            return released.release() ? FileStatus.OK : PERMANENT_ERROR_STATUS;
        }

        /**
         * The repository that opened this handle, for the ownership check.
         *
         * @return the owning repository
         */
        private DalyTranRepository repository() {
            return repository;
        }

        /**
         * The live cursor, or {@code null} when the open failed or the file has been closed.
         *
         * @return the cursor or {@code null}
         */
        private Cursor cursor() {
            return cursor;
        }

        /** Records that {@code AT END} has been reached. */
        private void markEndOfFile() {
            endOfFile = true;
        }

        /** Records one successful read. */
        private void countRecord() {
            recordsRead++;
        }

        /**
         * Refuses an operation on a closed handle.
         *
         * <p>Reading a closed file is a <strong>programming error, not an I/O outcome</strong>. COBOL
         * would report status {@code '47'} or {@code '49'} for a read against a file in the wrong open
         * mode, but neither consumer ever does it - both close once, after their loop - so there is no
         * legacy behaviour to reproduce, and the honest response is to fail loudly at the defect rather
         * than to return a status no COBOL path here would have produced.
         *
         * @param attempt what was being attempted, for the diagnostic
         * @throws IllegalStateException if this handle has been closed
         */
        private void requireOpen(String attempt) {
            if (closed) {
                throw new IllegalStateException("Cannot " + attempt + ": the " + DD_NAME + " file has "
                        + "been closed. Both consumers close once, after their read loop "
                        + "(app/cbl/CBTRN02C.cbl:L221 and app/cbl/CBTRN01C.cbl:L188), so a read after a "
                        + "close corresponds to no COBOL path and is reported as the defect it is "
                        + "rather than as a file status. Open the file again to read it again.");
            }
        }
    }

    /**
     * The outcome of one {@code READ}: the three arms of {@code 1000-DALYTRAN-GET-NEXT}'s guard, and
     * nothing else.
     *
     * <p>A discriminated result rather than a nullable record plus an out-parameter, because the COBOL
     * branches on the status and the presence of a record follows from it: exactly the {@code '00'} arm
     * carries a record, and the invariants below make any other combination unconstructible.
     *
     * <p>The status is what a caller branches on, because that is the quantity the guard tests. The
     * diagnostic is what makes an abend diagnosable: a permanent-error status says something went wrong
     * and nothing about what, whereas the driver's {@code SQLSTATE} distinguishes an unreachable backend
     * from a missing dataset from a rejected credential. It carries no driver message text and no record
     * content.
     *
     * @param status    the two-character file status the read reported
     * @param outcome   that status classified
     * @param dalyTran  the decoded record, present exactly when {@code outcome} is {@link Outcome#OK}
     * @param diagnostic what the backend reported, present only when a backend refused
     */
    public record ReadResult(String status,
                             Outcome outcome,
                             Optional<DalyTranRecord> dalyTran,
                             Optional<BackendDiagnostic> diagnostic) {

        /**
         * Enforces every invariant of the three-armed guard at construction.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *                                  {@value FileStatus#STATUS_LENGTH} characters, if
         *                                  {@code outcome} is not one of the three arms, if the record is
         *                                  present when the outcome is not success or absent when it is,
         *                                  or if {@code status} contradicts {@code outcome}
         */
        public ReadResult {
            Objects.requireNonNull(status, "A read result carries the two-character file status the read "
                    + "reported; it is never absent");
            Objects.requireNonNull(outcome, "A read result carries its classification; it is never "
                    + "absent");
            Objects.requireNonNull(dalyTran, "A read result carries an empty record rather than a null "
                    + "one, so no null escapes the type");
            Objects.requireNonNull(diagnostic, "A read result carries an empty diagnostic rather than a "
                    + "null one, so no null escapes the type");
            if (status.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalArgumentException("A file status is exactly "
                        + FileStatus.STATUS_LENGTH + " characters, as DALYTRAN-STATUS is declared at "
                        + "app/cbl/CBTRN02C.cbl:L103-L105 and app/cbl/CBTRN01C.cbl:L100-L102; got "
                        + status.length());
            }
            if (outcome != Outcome.OK && outcome != Outcome.END_OF_FILE && outcome != Outcome.OTHER) {
                throw new IllegalArgumentException("The guard at app/cbl/CBTRN02C.cbl:L347-L356 has "
                        + "three arms - '00', '10' and everything else - so a " + DD_NAME + " read is "
                        + "classified as OK, END_OF_FILE or OTHER. A keyed status cannot arise on a "
                        + "sequential read of a physical-sequential dataset and would fall into the "
                        + "third arm; got " + outcome + ".");
            }
            if (dalyTran.isPresent() != (outcome == Outcome.OK)) {
                throw new IllegalArgumentException(dalyTran.isPresent()
                        ? "A read that did not succeed carries no record: outcome " + outcome
                                + " was given one. Only the '00' arm reaches DALYTRAN-RECORD, because "
                                + "READ ... INTO moves nothing at AT END."
                        : "A successful read carries the decoded record, and this one carries none; "
                                + "build it with ReadResult.found(DalyTranRecord).");
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
                        + "the guard names explicitly, so it cannot be classified as the third arm.");
            }
        }

        /**
         * The successful arm: {@code IF DALYTRAN-STATUS = '00' MOVE 0 TO APPL-RESULT}
         * ({@code app/cbl/CBTRN02C.cbl:L347-L348}, {@code app/cbl/CBTRN01C.cbl:L204-L205}).
         *
         * @param dalyTran the decoded record
         * @return a result carrying {@link FileStatus#OK} and the record
         * @throws NullPointerException if {@code dalyTran} is {@code null}
         */
        public static ReadResult found(DalyTranRecord dalyTran) {
            Objects.requireNonNull(dalyTran, "A successful read carries the decoded DALYTRAN-RECORD");
            return new ReadResult(FileStatus.OK, Outcome.OK, Optional.of(dalyTran), Optional.empty());
        }

        /**
         * The end-of-file arm: {@code IF DALYTRAN-STATUS = '10' MOVE 16 TO APPL-RESULT}
         * ({@code app/cbl/CBTRN02C.cbl:L351-L352}, {@code app/cbl/CBTRN01C.cbl:L207-L209}), which the
         * caller turns into {@code MOVE 'Y' TO END-OF-FILE} ({@code CBTRN02C:L361}) or
         * {@code MOVE 'Y' TO END-OF-DAILY-TRANS-FILE} ({@code CBTRN01C:L218}).
         *
         * <p>Carries no record, which is what {@code READ ... INTO} does at {@code AT END}: the receiving
         * area is left holding whatever it held before.
         *
         * @return a result carrying {@link FileStatus#END_OF_FILE} and no record
         */
        public static ReadResult endOfFile() {
            return new ReadResult(FileStatus.END_OF_FILE, Outcome.END_OF_FILE, Optional.empty(),
                    Optional.empty());
        }

        /**
         * The third arm: {@code MOVE 12 TO APPL-RESULT} ({@code app/cbl/CBTRN02C.cbl:L354}), which the
         * caller turns into an error line, a rendered status and an abend
         * ({@code CBTRN02C:L363-L366}, {@code CBTRN01C:L221-L224}).
         *
         * @param status the two-character status the read reported, carried verbatim so the caller
         *               renders it exactly as {@code 9910-DISPLAY-IO-STATUS} does
         * @return a result carrying {@code status} and no record
         * @throws NullPointerException     if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *                                  {@value FileStatus#STATUS_LENGTH} characters, or is one of the
         *                                  two statuses the guard names explicitly
         */
        public static ReadResult other(String status) {
            return new ReadResult(status, Outcome.OTHER, Optional.empty(), Optional.empty());
        }

        /**
         * The third arm, carrying what the backend actually said about the refusal.
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
         * Whether the read succeeded and a record is available: the {@code IF APPL-AOK} test.
         *
         * @return {@code true} on the {@code '00'} arm
         */
        public boolean isFound() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether the file has ended: the {@code IF APPL-EOF} test
         * ({@code app/cbl/CBTRN02C.cbl:L360}, {@code app/cbl/CBTRN01C.cbl:L217}).
         *
         * @return {@code true} on the {@code '10'} arm
         */
        public boolean isEndOfFile() {
            return outcome == Outcome.END_OF_FILE;
        }

        /**
         * Whether the read failed: the {@code ELSE} of the {@code APPL-EOF} test, which displays, renders
         * the status and abends.
         *
         * @return {@code true} on the third arm
         */
        public boolean isOther() {
            return outcome == Outcome.OTHER;
        }

        /**
         * The {@code APPL-RESULT} value the guard moves for this outcome.
         *
         * <p>Derived from the outcome rather than stored, so it cannot disagree with the arm it belongs
         * to. It is the quantity {@code 88 APPL-AOK VALUE 0} and {@code 88 APPL-EOF VALUE 16}
         * ({@code app/cbl/CBTRN02C.cbl:L143-L144}) are tested against, and it is what routes an end of
         * file to the loop's exit rather than to the abend.
         *
         * @return {@link #APPL_RESULT_OK}, {@link #APPL_RESULT_EOF} or {@link #APPL_RESULT_FATAL}
         */
        public int applResult() {
            return switch (outcome) {
                case OK -> APPL_RESULT_OK;
                case END_OF_FILE -> APPL_RESULT_EOF;
                default -> APPL_RESULT_FATAL;
            };
        }

        /**
         * The status rendered exactly as {@code 9910-DISPLAY-IO-STATUS} /
         * {@code Z-DISPLAY-IO-STATUS} writes it.
         *
         * <p>Composed by the shared vocabulary rather than here, so every dataset in the module renders a
         * status identically - which is what makes a SYSOUT fingerprint comparable across programs.
         *
         * @return the display line, for example {@code FILE STATUS IS: NNNN0010}
         */
        public String displayLine() {
            return FileStatus.toDisplayLine(status);
        }
    }
}

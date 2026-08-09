package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.DatasetObservation;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.transaction.model.TranTypeRecord;

import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
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
import org.springframework.stereotype.Repository;

/**
 * The {@code TRANTYPE} transaction-type lookup: one dataset, one access path, one consumer.
 *
 * <h2>The dataset, and the one program that reads it</h2>
 * {@code app/cbl/CBTRN03C.cbl} - "Print the transaction detail report" - is the <strong>only</strong>
 * program in the repository that touches this dataset. {@code grep -n "TRANTYPE" app/cbl/*.cbl
 * app/cbl/*.CBL} returns twenty hits and every one of them is in that file, and the verbs among them
 * are exactly three:
 * <ul>
 *   <li>{@code OPEN INPUT TRANTYPE-FILE} - {@code app/cbl/CBTRN03C.cbl:432}, inside
 *       {@code 0300-TRANTYPE-OPEN};</li>
 *   <li>{@code READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD} - {@code :495}, inside
 *       {@code 1500-B-LOOKUP-TRANTYPE};</li>
 *   <li>{@code CLOSE TRANTYPE-FILE} - {@code :571}, inside {@code 9300-TRANTYPE-CLOSE}.</li>
 * </ul>
 * There is no {@code WRITE}, no {@code REWRITE}, no {@code DELETE}, no {@code STARTBR} and no
 * {@code READ NEXT} against it anywhere in the estate. That is why this class exposes
 * {@link #open()}, {@link #close()} and {@link #readByTranType(String)} and <strong>nothing
 * else</strong>: an unused write path is a capability the legacy system does not have, and a
 * repository that offers one invites a caller to use it.
 *
 * <p>The access path is declared at {@code app/cbl/CBTRN03C.cbl:39-43}:
 * <pre>
 *   SELECT TRANTYPE-FILE ASSIGN TO TRANTYPE
 *          ORGANIZATION IS INDEXED
 *          ACCESS MODE  IS RANDOM
 *          RECORD KEY   IS FD-TRAN-TYPE
 *          FILE STATUS  IS TRANTYPE-STATUS.
 * </pre>
 * {@code ACCESS MODE IS RANDOM} with a {@code RECORD KEY} is a keyed read and only a keyed read -
 * there is no sequential position to hold - so this class keeps no cursor and needs none.
 * {@code app/jcl/TRANREPT.jcl:69-70} binds the {@code TRANTYPE} DD as an input to
 * {@code STEP10R EXEC PGM=CBTRN03C} with {@code DISP=SHR}, which is the JCL saying the same thing:
 * shared, read, never written.
 *
 * <h2>The record: 60 bytes, from {@code app/cpy/CVTRA03Y.cpy}</h2>
 * <table border="1">
 *   <caption>{@code TRAN-TYPE-RECORD} - "RECLN = 60", the copybook's own header comment</caption>
 *   <tr><th>COBOL field</th><th>PICTURE</th><th>1-based</th><th>0-based offset</th><th>Length</th></tr>
 *   <tr><td>{@code TRAN-TYPE}</td><td>{@code X(02)}</td><td>1-2</td><td>0</td><td>2</td></tr>
 *   <tr><td>{@code TRAN-TYPE-DESC}</td><td>{@code X(50)}</td><td>3-52</td><td>2</td><td>50</td></tr>
 *   <tr><td>{@code FILLER}</td><td>{@code X(08)}</td><td>53-60</td><td>52</td><td>8</td></tr>
 * </table>
 * 2 + 50 + 8 = <strong>60</strong>, and the width is confirmed three independent ways:
 * {@link TranTypeRecord#LAYOUT}'s own arithmetic (gate G21), the consuming program's file
 * description at {@code app/cbl/CBTRN03C.cbl:73-75} which splits the same record as
 * {@code FD-TRAN-TYPE PIC X(02)} plus {@code FD-TRAN-DATA PIC X(58)}, and the seven 60-byte rows of
 * {@code app/data/ASCII/trantype.txt}. The configured {@code record-length} is checked against it at
 * construction, so a binding that disagrees fails the context rather than shifting every field after
 * the first (gate G19).
 *
 * <p>Every field is {@code PIC X}. There is no numeric item in this copybook at all, so no
 * fixed-point value, no scale, no rounding decision and certainly no primitive floating-point type
 * arises anywhere in this class (gate G22).
 *
 * <h2>The key is two characters, moved rather than assigned</h2>
 * {@code app/cbl/CBTRN03C.cbl:189} sets the key with
 * {@code MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE} - and the {@code OF TRAN-RECORD}
 * qualification is not decoration: {@code app/cpy/CVTRA04Y.cpy} declares a {@code TRAN-TYPE-CD} of
 * its own and the program copies both, so the COBOL has to say which one it means.
 *
 * <p>{@link #readByTranType(String)} reshapes its argument through
 * {@link FixedWidthCodec#movePicX(String, int)} at width {@value #TRAN_TYPE_KEY_LENGTH} rather than
 * using it as given. {@code TRAN-TYPE} is {@code PIC X(02)}, so the move rule is
 * <strong>space-padded and truncated on the right</strong>: {@code "4"} becomes {@code "4 "}, never
 * {@code "04"} and never {@code " 4"}. The distinction is the whole difference between finding a
 * record and not finding one, and it is the opposite of the numeric rule that left-zero-fills a
 * {@code PIC 9} key - which is why the direction is chosen by a named codec method instead of being
 * left implicit in a Java assignment.
 *
 * <h2>{@code TRAN-TYPE-DESC} is never trimmed</h2>
 * {@code app/cbl/CBTRN03C.cbl:366} moves the description into {@code TRAN-REPORT-TYPE-DESC}, which
 * {@code app/cpy/CVTRA07Y.cpy} declares as {@code PIC X(15)}. COBOL fills an alphanumeric receiver
 * from its leftmost position and discards the overflow, so the report line carries the
 * <em>first fifteen</em> of the fifty characters - {@code "Authorization  "}, with the two trailing
 * spaces that came from the padding. Reproducing that truncation requires the untrimmed 50-byte
 * value, so {@link TranTypeRecord#tranTypeDesc()} returns all fifty characters and the narrowing is
 * performed at the point of use by {@link TranTypeRecord#tranTypeDescMovedTo(int)}. This class
 * decodes and hands over the record; it formats nothing.
 *
 * <h2>Not found is a status, never an exception</h2>
 * {@code 1500-B-LOOKUP-TRANTYPE} owns its own failure handling, in full
 * ({@code app/cbl/CBTRN03C.cbl:494-502}):
 * <pre>
 *   READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD
 *      INVALID KEY
 *         DISPLAY 'INVALID TRANSACTION TYPE : '  FD-TRAN-TYPE
 *         MOVE 23 TO IO-STATUS
 *         PERFORM 9910-DISPLAY-IO-STATUS
 *         PERFORM 9999-ABEND-PROGRAM
 *   END-READ
 * </pre>
 * The message text, the literal {@code 23}, the rendered status line and the abend are all the
 * <em>program's</em>, and the report job has to reproduce them byte for byte. So
 * {@link #readByTranType(String)} reports {@link Outcome#NOT_FOUND} with status
 * {@link FileStatus#NOT_FOUND} and returns; it throws no abend, logs no message and composes no text.
 * {@link ReadResult#keyImage()} carries the two bytes the read actually used, because
 * {@code FD-TRAN-TYPE} is what {@code :497} displays and the caller needs exactly those bytes to
 * reproduce the line.
 *
 * <p>{@link #open()} and {@link #close()} are the same division of labour. Each reports
 * {@link FileStatus#OK} or a non-{@code '00'} status, which is precisely the two-way test
 * {@code 0300-TRANTYPE-OPEN} ({@code :430-446}) and {@code 9300-TRANTYPE-CLOSE} ({@code :569-585})
 * perform before moving {@code 0} or {@value #APPL_RESULT_FATAL} into {@code APPL-RESULT};
 * {@link ReadResult#applResult()} supplies that same mapping for a read. The
 * {@code DISPLAY 'ERROR OPENING TRANSACTION TYPE FILE'} at {@code :441} and its closing counterpart
 * at {@code :580} belong to the caller.
 *
 * <h2>Why there is no duplicate-key outcome</h2>
 * A sibling repository over a non-unique alternate index reports a duplicate condition, because CICS
 * genuinely raises one there. {@code TRANTYPE} is a base KSDS whose key is unique by construction and
 * whose COBOL read has exactly two arms - the record, or {@code INVALID KEY}. A second row carrying
 * the same two-byte key is therefore not a condition the legacy system can produce; it means the
 * relation being read is not presenting this dataset. That is reported on the {@code WHEN OTHER} arm
 * with a log line naming the anomaly, which routes the caller to the display-and-abend path where an
 * unexplained condition belongs, rather than silently handing back one of several rows as though it
 * were the only one.
 *
 * <h2>Data access: {@code JdbcTemplate} only, and no schema anywhere</h2>
 * The dataset is reached through the module's shared {@link JdbcTemplate} over the
 * configuration-bound {@code DataSource}. There is no entity, no table definition, no DDL, no
 * migration, no version column and no index created here (gate G44) - the record layout stays exactly
 * as {@code app/cpy/CVTRA03Y.cpy} defines it and is decoded by absolute offset by a hand-written
 * codec (practice B11).
 *
 * <p>The dataset <strong>name</strong> appears nowhere in this file (gate G46). It is resolved from
 * the {@code carddemo.datasets.}{@value #DD_NAME} entry of {@code application.yml}, which spells it
 * as an environment placeholder, and turned into a statement by {@link DatasetRelation} so that the
 * grammar of a z/OS dataset name is enforced in one place for the whole module. The code page is
 * likewise injected by bean name and handed straight to the codec, never derived and never taken from
 * the platform (practice B8).
 *
 * <h2>Residual risk R-E</h2>
 * The production driver for the existing VSAM backend is a deployment-time input: no such driver is
 * available in this build, so the statements this class composes cannot be exercised against the real
 * dataset here. What is exercised is everything on this side of the driver - the configured binding,
 * the composed statement text, the keyed predicate, the {@code PIC X} key move, the 60-byte decode and
 * every arm of the outcome ladder - against the shipped fixture. The residual risk is recorded rather
 * than absorbed (practice B12), and it is also why the record-image column is addressed by
 * <em>position</em> {@value #RECORD_IMAGE_COLUMN_INDEX} and never by a name written into Java: a
 * dataset carrying no relational metadata is presented as a single record-image column, and naming
 * that column here would be an unverifiable literal.
 *
 * <h2>Thread safety</h2>
 * A {@code @Repository} is a singleton and this one is safe to share. Every collaborator is
 * constructor-injected and {@code final}, there is no static mutable state (practice B9, gate G53),
 * and the single lazily-resolved field holds an immutable {@link String} published through a
 * {@code volatile} write. Recomputing it is harmless, so no lock is taken.
 *
 * @see TranTypeRecord
 * @see FileStatus
 */
@Repository
public class TranTypeRepository {

    /** Commons Logging, as the rest of the data layer uses. Immutable, so not mutable static state. */
    private static final Log LOG = LogFactory.getLog(TranTypeRepository.class);

    // =================================================================================================
    // Identity and geometry. Every value is transcribed from a cited artefact: the DD name from the
    // JCL and the SELECT, the widths from the copybook by way of TranTypeRecord.
    // =================================================================================================

    /**
     * The DD name this dataset is known by, and the key of its {@code carddemo.datasets} entry:
     * {@value}.
     *
     * <p>{@code app/cbl/CBTRN03C.cbl:39} is {@code SELECT TRANTYPE-FILE ASSIGN TO TRANTYPE} and
     * {@code app/jcl/TRANREPT.jcl:69} is {@code //TRANTYPE DD DISP=SHR,DSN=...}. The DD name is the
     * configuration key; the dataset name behind it is configured and never written here (gate G46).
     */
    public static final String DD_NAME = "TRANTYPE";

    /**
     * The record width in bytes, taken from the model rather than restated: {@code CVTRA03Y}'s
     * {@code TRAN-TYPE} 2 + {@code TRAN-TYPE-DESC} 50 + {@code FILLER} 8.
     */
    public static final int RECORD_LENGTH = TranTypeRecord.RECORD_LENGTH;

    /**
     * The key width in bytes: {@code TRAN-TYPE PIC X(02)}, named by
     * {@code RECORD KEY IS FD-TRAN-TYPE} at {@code app/cbl/CBTRN03C.cbl:42}.
     */
    public static final int TRAN_TYPE_KEY_LENGTH = TranTypeRecord.TRAN_TYPE_KEY_LENGTH;

    /**
     * The key's 0-based offset within the record: zero, because {@code TRAN-TYPE} begins the record.
     *
     * <p>Named rather than assumed. An alternate-index path elsewhere in this module carries its key
     * at offset 16 or 25, and the offset is what the keyed predicate positions its match on, so
     * getting it wrong reads the wrong bytes without failing anything.
     */
    public static final int TRAN_TYPE_KEY_OFFSET = TranTypeRecord.TRAN_TYPE_OFFSET;

    /**
     * The result-set position the record image is read from: {@value}.
     *
     * <p>A position and never a column name, for the reason given under residual risk R-E above.
     */
    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    /**
     * The organization the {@value #DD_NAME} binding must declare: an indexed base cluster.
     *
     * <p>{@code app/cbl/CBTRN03C.cbl:40-42} is {@code ORGANIZATION IS INDEXED} with a
     * {@code RECORD KEY}, so a binding that described this dataset as sequential or as an
     * alternate-index path would describe something this class cannot read the way the COBOL reads it.
     */
    public static final String EXPECTED_ORGANIZATION = DatasetBinding.KSDS;

    /**
     * The copybook the {@value #DD_NAME} binding must name: {@value}.
     *
     * <p>The binding carries the copybook alongside the record length precisely so the two can be
     * checked together. A width of 60 declared against some other copybook is a coincidence, not an
     * agreement.
     */
    public static final String EXPECTED_COPYBOOK = "CVTRA03Y";

    // =================================================================================================
    // Status vocabulary. Every enumerated value comes from common/FileStatus, which unifies the batch
    // two-character FILE STATUS with the online CICS RESP. Only the two values COBOL spells as
    // literals in this program's guard chain are named here.
    // =================================================================================================

    /**
     * The second byte of the permanent-error status: feedback code zero, meaning "permanent error, no
     * more specific code available".
     *
     * <p>Not arbitrary. z/OS COBOL reports an implementor-defined permanent error as {@code '9'} in
     * the first status byte with a binary feedback code in the second, and
     * {@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN03C.cbl:633-646} is written specifically
     * to decode that form - it tests {@code IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'} and renders the
     * second byte as a three-digit binary value. Zero is the honest feedback code for a backend this
     * class cannot interrogate further.
     */
    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The two-character file status reported for a permanent I/O error: {@code '9'} followed by
     * {@link #PERMANENT_ERROR_FEEDBACK_CODE}.
     *
     * <p>It renders as {@code "9000"} through {@link FileStatus#toStatusImage(String)}, and therefore
     * as {@code FILE STATUS IS: NNNN9000} through {@link FileStatus#toDisplayLine(String)}, which is
     * exactly the line {@code 9910-DISPLAY-IO-STATUS} produces for it. It is deliberately not one of
     * the statuses the COBOL enumerates: {@link FileStatus#outcomeOfStatus(String)} classifies it as
     * {@link Outcome#OTHER}, which is where an I/O failure belongs.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    /**
     * The {@code APPL-RESULT} value this program moves for a fatal status: {@code 12}.
     *
     * <p>{@code app/cbl/CBTRN03C.cbl:436} moves it when the open reports anything but {@code '00'},
     * and {@code :575} does the same for the close. {@link FileStatus} already carries
     * {@link FileStatus#APPL_AOK} of {@code 0} and {@link FileStatus#APPL_EOF} of {@code 16}, so only
     * the fatal value needs naming, and it is named here because this is the class whose outcomes it
     * classifies.
     */
    public static final int APPL_RESULT_FATAL = 12;

    // =================================================================================================
    // Read shaping.
    // =================================================================================================

    /**
     * Where the key sits inside the record image: {@code TRAN-TYPE PIC X(02)} at offset 0.
     *
     * <p>An offset and a length, not a column: {@code TRAN-TYPE} names a span of
     * {@code app/cpy/CVTRA03Y.cpy}, so the predicate that finds a record by it is expressed over the
     * record image at this offset for this length. Derived from the model's own declared span rather
     * than from two loose integers, so the predicate and the decode can never disagree about where
     * the key is.
     */
    private static final KeySpan KEY_SPAN =
            new KeySpan(TranTypeRecord.TRAN_TYPE.offset(), TranTypeRecord.TRAN_TYPE.length());

    /**
     * How many rows a keyed read transfers at most: {@value}.
     *
     * <p>One more than the answer needs. A unique key matches at most one record, so no row is
     * {@code INVALID KEY} and one row is the record; a second row settles the only remaining question
     * - whether the relation has lost that uniqueness - and nothing beyond it could change the
     * answer, so nothing beyond it is fetched. A COBOL {@code READ} returns one record and a
     * condition, never a count.
     */
    private static final int UNIQUENESS_CHECK_ROW_LIMIT = 2;

    // =================================================================================================
    // Injected collaborators. All final, all per-instance: no static mutable state (gate G53).
    // =================================================================================================

    /** The module's shared template, over the configuration-bound {@code DataSource}. */
    private final JdbcTemplate jdbcTemplate;

    /**
     * The hand-written fixed-width codec, built once over the explicitly injected dataset code page.
     * Held rather than rebuilt per row, so the charset is validated once.
     */
    private final FixedWidthCodec codec;

    /**
     * How this deployment's driver presents a record image over JDBC - characters or bytes - stated
     * once by {@value RecordImageForm#FORM_PROPERTY} and injected, never decided here. A driver asked
     * for the type a column does not have converts rather than refuses, so the wrong answer is wrong
     * silently.
     */
    private final RecordImageForm recordImageForm;

    /** The resolved dataset, from configuration: its validated name and the statements over it. */
    private final DatasetRelation relation;

    /**
     * The keyed-read statement, composed on first use.
     *
     * <p>Lazily, because the record-image column's name is discovered from the backend and a
     * repository must be constructible in a context that has not reached its backend yet.
     * {@code volatile} so the immutable {@link String} it holds is published safely to every thread;
     * recomputing it yields the same text, so no lock is needed and none is taken.
     */
    private volatile String keyedReadStatement;

    /**
     * Assembles the repository from the module's shared {@link JdbcTemplate}, the DD-name-keyed
     * dataset catalogue, the explicitly named dataset code page and the configured record-image
     * representation.
     *
     * <p>Constructor injection throughout, with no field injection and no setter, so an instance is
     * either fully wired or does not exist (practice B9).
     *
     * <p><strong>Everything checkable about the configuration is checked here, at startup, rather
     * than at the first read.</strong> Five invariants are enforced, and each one guards a defect that
     * would otherwise surface as plausible-looking wrong data rather than as a failure:
     * <ol>
     *   <li>the binding declares a record length of {@value #RECORD_LENGTH}. This class decodes by
     *       absolute offset, so a different width would put {@code TRAN-TYPE-DESC} somewhere else and
     *       the report would carry fifteen characters of the wrong bytes (gate G19);</li>
     *   <li>it names {@value #EXPECTED_COPYBOOK} as its copybook, so the width is an agreement with
     *       this layout rather than a coincidence shared with some other 60-byte record;</li>
     *   <li>it declares {@value #EXPECTED_ORGANIZATION} and no base of its own - an indexed base
     *       cluster, which is what {@code ORGANIZATION IS INDEXED} at
     *       {@code app/cbl/CBTRN03C.cbl:40} describes, and not an alternate-index path over something
     *       else;</li>
     *   <li>its key geometry matches {@code app/cpy/CVTRA03Y.cpy}: {@value #TRAN_TYPE_KEY_LENGTH}
     *       bytes at offset {@value #TRAN_TYPE_KEY_OFFSET}. A key of the wrong width or in the wrong
     *       place composes a predicate that matches the wrong records;</li>
     *   <li>the configured dataset name is present and well formed, so the composed statement names a
     *       real relation.</li>
     * </ol>
     *
     * <p>The layout's own total-width self-check runs here too, so 2 + 50 + 8 = 60 is proved before
     * the first row is read rather than assumed (gate G21).
     *
     * @param jdbcTemplate    the module-wide template; never {@code null}
     * @param datasetBindings the {@code carddemo.datasets} catalogue; never {@code null}. The entry
     *                        is looked up by the key {@value #DD_NAME}, so no dataset name is written
     *                        in Java (gate G46)
     * @param datasetCharset  the dataset code page, injected by bean name so the choice is explicit at
     *                        the injection point and never taken from the platform (practice B8)
     * @param recordImageForm how the deployment's driver presents a record image over JDBC, from
     *                        {@value RecordImageForm#FORM_PROPERTY}
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalStateException    if no binding is configured for {@value #DD_NAME}, or if the
     *                                  binding disagrees with the copybook about the width, the
     *                                  copybook name, the organization or the key geometry, or declares
     *                                  no dataset name at all
     * @throws IllegalArgumentException if the injected code page is not a single-byte one, or if the
     *                                  configured dataset name is not a well-formed z/OS dataset name -
     *                                  the grammar lives in {@link DatasetRelation} and raises its own
     *                                  diagnosis, which is more specific than anything this class could
     *                                  compose around it
     */
    public TranTypeRepository(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm) {

        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + DD_NAME + " lookup is served through the module's shared template over the "
                + "configuration-bound DataSource");
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
        requireCopybookRecordLength(binding);
        requireDeclaredCopybook(binding);
        requireIndexedBaseCluster(binding);
        requireCopybookKeyGeometry(binding);
        this.relation = DatasetRelation.of(requireUsableDatasetName(binding.dsname()), RECORD_LENGTH);

        // The width proof, re-derived from the declared spans rather than trusted: TRAN-TYPE 2 +
        // TRAN-TYPE-DESC 50 + FILLER 8 = 60. It throws rather than returning false, so there is no
        // branch here to leave untested - and it fails at construction, not at the first decode.
        TranTypeRecord.verifyDeclaredWidth();
    }

    // =================================================================================================
    // Construction guards. Each rejects a configuration that would otherwise read or compare the wrong
    // bytes, and each is reachable from a plain unit test with a hand-built binding.
    // =================================================================================================

    /**
     * Requires the binding to agree with {@code app/cpy/CVTRA03Y.cpy} about the record width.
     *
     * @param binding the configured binding
     * @throws IllegalStateException if the declared record length is not {@value #RECORD_LENGTH}
     */
    private static void requireCopybookRecordLength(DatasetBinding binding) {
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' declares a "
                    + "record length of " + binding.recordLength() + ", but " + EXPECTED_COPYBOOK
                    + ".cpy declares TRAN-TYPE-RECORD as " + RECORD_LENGTH + " bytes - TRAN-TYPE 2 + "
                    + "TRAN-TYPE-DESC 50 + FILLER 8 - and app/cbl/CBTRN03C.cbl:73-75 splits the same "
                    + "record as 2 + 58. This repository decodes by absolute offset, so a differently "
                    + "sized record would misplace TRAN-TYPE-DESC and the detail report would carry "
                    + "fifteen characters of the wrong bytes. Correct carddemo.datasets." + DD_NAME
                    + ".record-length to " + RECORD_LENGTH + ".");
        }
    }

    /**
     * Requires the binding to name the copybook this class decodes.
     *
     * <p>A width of 60 declared against another copybook is a coincidence rather than an agreement -
     * {@code CVTRA03Y} and {@code CVTRA04Y} are both 60 bytes and differ in every field after the
     * first two - so the copybook name is checked alongside the width and not instead of it.
     *
     * @param binding the configured binding
     * @throws IllegalStateException if the binding names any other copybook, or none
     */
    private static void requireDeclaredCopybook(DatasetBinding binding) {
        if (!EXPECTED_COPYBOOK.equals(binding.copybook())) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' names "
                    + "copybook '" + binding.copybook() + "', but this repository decodes "
                    + EXPECTED_COPYBOOK + " - TRAN-TYPE X(02), TRAN-TYPE-DESC X(50), FILLER X(08). "
                    + "app/cpy/CVTRA04Y.cpy is also 60 bytes and is a different record in every field "
                    + "after the key, so the width alone does not identify the layout. Correct "
                    + "carddemo.datasets." + DD_NAME + ".copybook to " + EXPECTED_COPYBOOK + ".");
        }
    }

    /**
     * Requires the binding to describe an indexed base cluster rather than a sequential dataset or an
     * alternate-index path.
     *
     * @param binding the configured binding
     * @throws IllegalStateException if the organization is not {@value #EXPECTED_ORGANIZATION}, or if
     *                               the entry declares a base cluster of its own
     */
    private static void requireIndexedBaseCluster(DatasetBinding binding) {
        if (!EXPECTED_ORGANIZATION.equals(binding.organization())) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' declares "
                    + "organization '" + binding.organization() + "', but app/cbl/CBTRN03C.cbl:40-42 "
                    + "reads it with ORGANIZATION IS INDEXED, ACCESS MODE IS RANDOM and RECORD KEY IS "
                    + "FD-TRAN-TYPE. A keyed read against a dataset configured as anything but '"
                    + EXPECTED_ORGANIZATION + "' would be reading it in a way the program does not. "
                    + "Correct carddemo.datasets." + DD_NAME + ".organization to "
                    + EXPECTED_ORGANIZATION + ".");
        }
        if (binding.base() != null) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' declares "
                    + "base '" + binding.base() + "', but it IS a base cluster: the transaction-type "
                    + "file has no alternate index anywhere in app/csd/CARDDEMO.CSD or app/jcl, and "
                    + "CBTRN03C opens it once, on its primary key. Only an alternate-index path "
                    + "declares a base. Remove carddemo.datasets." + DD_NAME + ".base.");
        }
    }

    /**
     * Requires the binding's key geometry to match {@code app/cpy/CVTRA03Y.cpy}.
     *
     * <p>The key span is where the predicate positions its match, so a wrong width or a wrong offset
     * composes a pattern that selects the wrong records - and selects them successfully, which is why
     * this is checked rather than trusted. The geometry is then taken from the copybook by way of
     * {@link #KEY_SPAN} rather than from configuration, so the decode and the predicate are addressing
     * one agreed span.
     *
     * @param binding the configured binding
     * @throws IllegalStateException if the declared key length is absent or not
     *                               {@value #TRAN_TYPE_KEY_LENGTH}, or if the declared key offset is
     *                               not {@value #TRAN_TYPE_KEY_OFFSET}
     */
    private static void requireCopybookKeyGeometry(DatasetBinding binding) {
        Integer declaredKeyLength = binding.keyLength();
        if (declaredKeyLength == null || declaredKeyLength != TRAN_TYPE_KEY_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' declares "
                    + "key-length " + declaredKeyLength + ", but RECORD KEY IS FD-TRAN-TYPE at "
                    + "app/cbl/CBTRN03C.cbl:42 names TRAN-TYPE PIC X(0" + TRAN_TYPE_KEY_LENGTH
                    + ") - exactly " + TRAN_TYPE_KEY_LENGTH + " bytes. A keyed predicate composed from "
                    + "a key of any other width matches records this key does not name. Set "
                    + "carddemo.datasets." + DD_NAME + ".key-length to " + TRAN_TYPE_KEY_LENGTH + ".");
        }
        if (binding.keyOffsetOrZero() != TRAN_TYPE_KEY_OFFSET) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' declares "
                    + "key-offset " + binding.keyOffsetOrZero() + ", but TRAN-TYPE begins "
                    + EXPECTED_COPYBOOK + "'s record, so its 0-based offset is "
                    + TRAN_TYPE_KEY_OFFSET + ". A non-zero offset belongs to an alternate-index path, "
                    + "and this dataset has none. Remove carddemo.datasets." + DD_NAME
                    + ".key-offset.");
        }
    }

    /**
     * Validates the configured dataset name and returns it verbatim.
     *
     * <p>Nothing is trimmed, normalised or defaulted. Blank is rejected because {@code application.yml}
     * spells the name as an environment placeholder, so a deployment that never supplied one yields an
     * empty string rather than {@code null}; the rest of the grammar - what a z/OS dataset name may
     * contain - lives in {@link DatasetRelation}, so it is stated once for the module rather than
     * restated, and diverging, in each repository.
     *
     * @param candidate the {@code dsname} component of the resolved binding
     * @return {@code candidate}, unchanged
     * @throws IllegalStateException    if it is absent or blank - the binding is present but says
     *                                  nothing, which is a defect in this module's own configuration
     * @throws IllegalArgumentException if it is present but not a well-formed z/OS dataset name. Raised
     *                                  by {@link DatasetRelation#requireDatasetName(String)} and
     *                                  deliberately not re-wrapped: it names the offending qualifier
     *                                  and its position, which is more useful to whoever must correct
     *                                  the value than any type this class could substitute
     */
    private static String requireUsableDatasetName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for DD name '" + DD_NAME
                    + "' declares no dataset name. Set carddemo.datasets." + DD_NAME + ".dsname; this "
                    + "repository composes its statements from configuration alone and hard-codes no "
                    + "dataset name (gate G46).");
        }
        return DatasetRelation.requireDatasetName(candidate);
    }

    /**
     * The resolved dataset name, exactly as configuration declares it.
     *
     * <p>Exposed for diagnostics and for a caller reporting which dataset it read - not as a way to
     * reach around this class. The value is configured rather than written here, so surfacing it
     * introduces no dataset literal into Java (gate G46).
     *
     * @return the configured dataset name for {@value #DD_NAME}; never {@code null}, never blank
     */
    public String datasetName() {
        return relation.dsname();
    }

    // =================================================================================================
    // 0300-TRANTYPE-OPEN and 9300-TRANTYPE-CLOSE - app/cbl/CBTRN03C.cbl:430-446 and 569-585.
    //
    // The two paragraphs are identical apart from the verb and the message they display, so they share
    // one probe. They stay two methods because the caller's sequence and message differ: the open is
    // performed at :164, before the report loop, and the close at :211, after it.
    // =================================================================================================

    /**
     * Opens the {@value #DD_NAME} dataset for input, reporting only the resulting file status: the Java
     * form of {@code 0300-TRANTYPE-OPEN} ({@code app/cbl/CBTRN03C.cbl:430-446}).
     *
     * <p>The COBOL is a two-way test - {@code IF TRANTYPE-STATUS = '00'} moves {@code 0} into
     * {@code APPL-RESULT}, anything else moves {@value #APPL_RESULT_FATAL} - and this method reproduces
     * exactly that shape and nothing more. The {@code DISPLAY 'ERROR OPENING TRANSACTION TYPE FILE'} at
     * {@code :441}, the rendered status line and the abend all belong to the caller.
     *
     * <p><strong>What "open" means against a configuration-bound driver.</strong> There is no
     * persistent handle to acquire: the shared template borrows and returns a connection per operation.
     * So the check performed here is a <em>describe of the dataset</em> - a statement that names it and
     * returns no row, which every dialect accepts - and it does two things a connection-only check
     * cannot. It fails when the dataset is absent or unreachable, which is what an {@code OPEN INPUT}
     * reports; and it names the record-image column, which is what the keyed read needs in order to be
     * composed at all. A connection-only probe would let an absent relation report a successful open and
     * then surface on the read path, so the job would emit the wrong message and take the wrong branch.
     *
     * <p>Nothing crosses the wire but the metadata, so the cost is one round trip. Residual risk R-E
     * still applies to the driver itself, which this build cannot exercise.
     *
     * @return {@link FileStatus#OK} when the dataset is addressable and presents a usable record-image
     *         column, otherwise {@link #PERMANENT_ERROR_STATUS}; never {@code null}, always two
     *         characters
     */
    public String open() {
        return probeDatasetAvailability("OPEN INPUT");
    }

    /**
     * Closes the {@value #DD_NAME} dataset, reporting only the resulting file status: the Java form of
     * {@code 9300-TRANTYPE-CLOSE} ({@code app/cbl/CBTRN03C.cbl:569-585}).
     *
     * <p>Same two-way shape as {@link #open()}, and the caller likewise owns the
     * {@code DISPLAY 'ERROR CLOSING TRANSACTION TYPE FILE'} at {@code :580} and the abend.
     *
     * <p>Nothing is buffered and no handle is held, so there is no flush to fail and nothing to
     * release. What this method does do is <strong>forget what the open learned</strong>: the discovered
     * record-image column and the statement composed from it are discarded, so a read after a close
     * describes the dataset again rather than reusing a statement over a relation that may since have
     * been de-allocated. The one close-time failure that can genuinely be detected - that the dataset is
     * no longer describable - is therefore reported by the same probe, which also keeps both of the
     * COBOL's symmetric guards reachable rather than leaving one of them dead.
     *
     * <p>The forgetting happens whether the probe succeeded or failed, because the dataset is closed
     * either way.
     *
     * @return {@link FileStatus#OK} when the dataset is still describable, otherwise
     *         {@link #PERMANENT_ERROR_STATUS}; never {@code null}, always two characters
     */
    public String close() {
        try {
            return probeDatasetAvailability("CLOSE");
        } finally {
            // A CLOSE forgets everything the OPEN learned. Done in a finally rather than behind a test
            // of the status, so there is no branch here and no path on which a stale statement survives.
            this.keyedReadStatement = null;
            this.relation.forgetRecordImageColumn();
        }
    }

    /**
     * The shared open/close probe: describe the dataset, name its record-image column, and compose the
     * keyed statement over it.
     *
     * <p>The COBOL verb is a parameter rather than an inference, because the two callers are the two
     * symmetric guards {@code CBTRN03C} keeps distinct and an operator reading a refusal needs to know
     * which one failed.
     *
     * <p>Read-only and dialect-free: the predicate is false on every row, so the statement resolves and
     * describes the relation while none of it is transferred. A relation that presents no usable
     * record-image column is treated as unusable rather than as a successful open - with the driver a
     * deployment-time input, reporting success on a relation this repository cannot read would hand the
     * report job a description it never actually read.
     *
     * @param cobolOperation the COBOL verb being reproduced, for the diagnostic
     * @return {@link FileStatus#OK} or {@link #PERMANENT_ERROR_STATUS}
     */
    private String probeDatasetAvailability(String cobolOperation) {
        try {
            describeAndComposeKeyedRead();
            return FileStatus.OK;
        } catch (DataAccessException translated) {
            // The COBOL keeps no more than the status - it renders the status and abends - so the status
            // is the whole of what is returned. What the backend said is not thrown away with it: an
            // operator needs to know whether the dataset was missing, the credentials were refused or
            // the backend was unreachable, and only the driver knows that.
            logRefusal(translated, cobolOperation + " the " + DD_NAME + " dataset (a describe of the "
                    + "configured relation, which is what distinguishes an absent dataset from an "
                    + "unreadable record)");
            return PERMANENT_ERROR_STATUS;
        } catch (IllegalStateException unusable) {
            // The relation resolved but presents nothing at the record-image position, so there is no
            // record to read and no statement that could be composed over it. Reported as a permanent
            // error, exactly as an absent dataset is. The message is this module's own text and carries
            // no value the driver supplied, so it is safe to log verbatim.
            LOG.error("Could not " + cobolOperation + " the " + DD_NAME + " dataset: "
                    + unusable.getMessage() + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
            return PERMANENT_ERROR_STATUS;
        }
    }

    // =================================================================================================
    // 1500-B-LOOKUP-TRANTYPE - app/cbl/CBTRN03C.cbl:494-502, performed once per report line from :190,
    // on the key moved at :189.
    // =================================================================================================

    /**
     * Reads one transaction-type record by its key: the Java form of {@code 1500-B-LOOKUP-TRANTYPE}
     * ({@code app/cbl/CBTRN03C.cbl:494-502}).
     *
     * <p><strong>The key is a COBOL {@code MOVE}, not a Java assignment.</strong> The supplied value
     * goes through {@link FixedWidthCodec#movePicX(String, int)} at width
     * {@value #TRAN_TYPE_KEY_LENGTH}, so a shorter value is space-padded <em>on the right</em> and a
     * longer one is truncated on the right, exactly as the {@code PIC X(02)} receiver
     * {@code FD-TRAN-TYPE} behaves when {@code :189} moves {@code TRAN-TYPE-CD OF TRAN-RECORD} into it.
     * {@code "4"} therefore becomes {@code "4 "} - never {@code "04"}, never {@code " 4"} - and the
     * bytes actually used are returned on {@link ReadResult#keyImage()} so the caller can display them
     * as {@code :497} does.
     *
     * <p>Three outcomes, and exactly the three this {@code READ} can produce:
     * <ul>
     *   <li><strong>{@link Outcome#OK}</strong>, status {@link FileStatus#OK}, carrying the record. The
     *       {@code NOT INVALID KEY} path, on which the program moves {@code TRAN-TYPE-DESC} into the
     *       report line at {@code :366};</li>
     *   <li><strong>{@link Outcome#NOT_FOUND}</strong>, status {@link FileStatus#NOT_FOUND}, carrying no
     *       record. This is {@code INVALID KEY}, and it is <em>not</em> an exception and <em>not</em> an
     *       end of file: a keyed read that matches nothing has not reached the end of anything. The
     *       program's own response - display, move 23, render, abend - is the caller's to reproduce;</li>
     *   <li><strong>{@link Outcome#OTHER}</strong>, status {@link #PERMANENT_ERROR_STATUS}, for an I/O
     *       failure, an unusable relation, a row with no record image, a row of the wrong width, or more
     *       than one row carrying the same unique key. Each is logged where it is detected, and each
     *       routes the caller to the branch where an unexplained condition belongs.</li>
     * </ul>
     *
     * <p>No state is kept between calls, so reading the same key twice reads it twice - which is
     * faithful, because {@code ACCESS MODE IS RANDOM} holds no position and the program performs this
     * paragraph once per report line.
     *
     * <p><strong>A row that is not exactly {@value #RECORD_LENGTH} bytes is reported, not repaired.</strong>
     * Padding a short row with spaces would look like the faithful repair, since the missing bytes could
     * only be trailing {@code FILLER}. What makes that wrong here is what the record is for: the
     * description is moved straight into a {@code PIC X(15)} report field, so a row truncated inside
     * {@code TRAN-TYPE-DESC} would pad into a <em>different</em> description and the detail report would
     * come out wrong, successfully and silently. It is reported on the {@code WHEN OTHER} arm instead,
     * with the observed width named in the log line.
     *
     * @param tranType the two-character transaction type code to look up; never {@code null}, and may be
     *                 shorter or longer than {@value #TRAN_TYPE_KEY_LENGTH} because the {@code PIC X}
     *                 move reshapes it. Pass an empty string to look up a key of spaces
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code tranType} is {@code null}
     */
    public ReadResult readByTranType(String tranType) {
        Objects.requireNonNull(tranType, "A transaction type code is required to read the "
                + TranTypeRecord.TRAN_TYPE_FIELD + " key; an absent key is a defect in the caller "
                + "rather than an INVALID KEY outcome, because app/cbl/CBTRN03C.cbl:189 always moves "
                + "two characters into FD-TRAN-TYPE. Pass an empty string for a key of SPACES.");
        // The PIC X move, named rather than implied: right-padded when short, right-truncated when long.
        String keyImage = codec.movePicX(tranType, TRAN_TYPE_KEY_LENGTH);

        List<byte[]> rows;
        try {
            rows = fetchByKey(keyImage);
        } catch (DataAccessException translated) {
            // WHEN OTHER. Reported as a status, because that is all the COBOL keeps, and carrying the
            // backend's own diagnosis so the abend that follows can be traced to a cause.
            return refused(keyImage, translated, "read the " + DD_NAME + " dataset by key");
        } catch (IllegalStateException unusable) {
            // The relation presents no usable record-image column, so the statement could not be
            // composed. An I/O-level failure, not a missing record - and reported without throwing,
            // because a COBOL READ reports a status and leaves the guard chain in control.
            LOG.error("Could not read the " + DD_NAME + " dataset by key: " + unusable.getMessage()
                    + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " to the caller");
            return ReadResult.other(keyImage, PERMANENT_ERROR_STATUS);
        }

        if (rows == null) {
            // A template that yielded no result object at all has told us nothing, and nothing is not
            // an absent record. Reported on the WHEN OTHER arm rather than mistaken for INVALID KEY.
            LOG.error("The " + DD_NAME + " dataset yielded no result object at all for a keyed read; "
                    + "reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than treating it as a dataset with no matching record");
            return ReadResult.other(keyImage, PERMANENT_ERROR_STATUS);
        }
        if (rows.isEmpty()) {
            // INVALID KEY - app/cbl/CBTRN03C.cbl:496. A normal branch, never an exception.
            return ReadResult.notFound(keyImage);
        }
        if (rows.size() > 1) {
            // TRAN-TYPE is the primary key of a KSDS and is unique by construction, so a second row
            // carrying it means the relation being read is not presenting this dataset. The legacy READ
            // cannot produce this condition at all, so it is reported on the WHEN OTHER arm - which is
            // the caller's display-and-abend path - rather than resolved by handing back one of them.
            LOG.error("The " + DD_NAME + " dataset presented more than one row ("
                    + DatasetObservation.matchingRows(rows.size()).describe() + ") for a key that "
                    + EXPECTED_COPYBOOK + " declares unique (RECORD KEY IS FD-TRAN-TYPE, "
                    + "app/cbl/CBTRN03C.cbl:42); reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than returning one of several records as though it were the only one");
            return ReadResult.other(keyImage, PERMANENT_ERROR_STATUS);
        }

        byte[] recordImage = rows.get(0);
        if (recordImage == null) {
            // A row whose record image is absent is not a readable 60-byte record. There IS a record, it
            // simply cannot be read, so this is an I/O-level defect and not an absent record.
            LOG.error("The matching row of the " + DD_NAME + " dataset carries no record image at "
                    + "column position " + RECORD_IMAGE_COLUMN_INDEX + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than reporting a record that is present as absent");
            return ReadResult.other(keyImage, PERMANENT_ERROR_STATUS);
        }
        if (recordImage.length != RECORD_LENGTH) {
            // WHEN OTHER. Reported rather than padded: the description is moved into a PIC X(15) report
            // field next, so a truncated row would pad into a different description and the report would
            // be wrong with nothing said.
            LOG.error("The " + DD_NAME + " dataset presented a row whose "
                    + DatasetObservation.recordWidth(recordImage.length).describe() + ", but "
                    + EXPECTED_COPYBOOK + " declares TRAN-TYPE-RECORD as " + RECORD_LENGTH
                    + " bytes and app/cbl/CBTRN03C.cbl:73-75 splits it as 2 + 58; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than padding the row into a description the dataset does not contain");
            return ReadResult.other(keyImage, PERMANENT_ERROR_STATUS);
        }
        // NOT INVALID KEY - READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD succeeded. The 60 bytes are decoded
        // by absolute offset, FILLER included and carried verbatim, so the record re-encodes to exactly
        // the row that was read.
        return ReadResult.found(keyImage, TranTypeRecord.decode(recordImage, codec));
    }

    // =================================================================================================
    // Row transfer. The key predicate lives in the STATEMENT, so the backend decides which records
    // qualify - a read that fetched the whole relation and compared keys in Java would transfer a file
    // to answer a single-record question, and one malformed row anywhere in it would fail a read of a
    // key that had nothing to do with it.
    // =================================================================================================

    /**
     * Executes the keyed read, transferring at most {@value #UNIQUENESS_CHECK_ROW_LIMIT} rows.
     *
     * <p>The predicate is an escaped {@code LIKE} over the record image, confined by {@link #KEY_SPAN}
     * to the key's own bytes at the key's own offset - which is the comparison VSAM performs. The key is
     * escaped rather than trusted because a {@code LIKE} metacharacter inside it would otherwise match
     * records the key does not name, and {@link KeySpan#pattern(String)} does that escaping in one place
     * for the whole module.
     *
     * <p>Both row limits are set deliberately: {@code setMaxRows} bounds what the backend will hand
     * over and {@code setFetchSize} bounds what it carries across in a round trip.
     *
     * <p>The image is kept as <strong>bytes</strong> rather than being turned into text on the way in.
     * Under a single-byte code page a round trip through a {@link String} preserves the width but not
     * necessarily the bytes - IBM037 maps {@code X'25'} to a character that encodes back as
     * {@code X'15'} - and a record whose {@code FILLER} must survive a decode-then-encode round trip
     * byte for byte cannot afford that. The bytes go straight to
     * {@link TranTypeRecord#decode(byte[], FixedWidthCodec)}.
     *
     * @param keyImage the key image, already reshaped by the {@code PIC X} move and therefore exactly
     *                 {@value #TRAN_TYPE_KEY_LENGTH} characters
     * @return the matching record images, at most two of them - a list element may itself be
     *         {@code null} when the column held no value, which the caller classifies rather than
     *         ignores - or {@code null} if the template yielded no result at all
     * @throws DataAccessException   if the backend refuses
     * @throws IllegalStateException if the relation presents no usable record-image column
     */
    private List<byte[]> fetchByKey(String keyImage) {
        String statement = keyedReadStatement();
        String pattern = KEY_SPAN.pattern(keyImage);
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(UNIQUENESS_CHECK_ROW_LIMIT);
            prepared.setFetchSize(UNIQUENESS_CHECK_ROW_LIMIT);
            recordImageForm.bindOperand(prepared, 1, pattern, codec.charset());
            return prepared;
        };
        ResultSetExtractor<List<byte[]>> extractor = resultSet -> {
            List<byte[]> images = new ArrayList<>(UNIQUENESS_CHECK_ROW_LIMIT);
            while (images.size() < UNIQUENESS_CHECK_ROW_LIMIT && resultSet.next()) {
                images.add(recordImageForm.readImage(resultSet, RECORD_IMAGE_COLUMN_INDEX,
                        codec.charset()));
            }
            return images;
        };
        return jdbcTemplate.query(creator, extractor);
    }

    // =================================================================================================
    // Statement resolution. One place decides the text of the one statement this repository sends, and
    // one describe - a statement that returns no row - learns the column name needed to compose it.
    // =================================================================================================

    /**
     * The keyed-read statement, composing it on first use.
     *
     * <p>Cached because the column name does not change while the dataset is open, and re-derived after
     * a {@link #close()} because a close forgets what the open learned. The cached value is an immutable
     * {@link String} published through a {@code volatile} field, and recomposing it yields identical
     * text, so two threads racing here produce the same answer and no lock is taken.
     *
     * @return the composed keyed-read statement
     * @throws DataAccessException   if the relation cannot be described
     * @throws IllegalStateException if the relation presents no usable record-image column
     */
    private String keyedReadStatement() {
        String resolved = this.keyedReadStatement;
        return resolved == null ? describeAndComposeKeyedRead() : resolved;
    }

    /**
     * Describes the relation, names its record-image column and composes the keyed-read statement over
     * it, unconditionally.
     *
     * <p>Unconditionally is the point: {@link #open()} and {@link #close()} must actually reach the
     * dataset every time they are called, or the second of the COBOL's two symmetric guards could never
     * fail. {@link #keyedReadStatement()} is the caller that wants the cached answer.
     *
     * @return the composed keyed-read statement, also cached for subsequent reads
     * @throws DataAccessException   if the relation cannot be described
     * @throws IllegalStateException if the relation presents no usable record-image column
     */
    private String describeAndComposeKeyedRead() {
        ResultSetExtractor<String> columnNameExtractor = TranTypeRepository::extractRecordImageColumn;
        String recordImageColumn = relation.rememberRecordImageColumn(
                jdbcTemplate.query(relation.describeStatement(), columnNameExtractor));
        String composed = relation.selectByKey(recordImageColumn);
        this.keyedReadStatement = composed;
        return composed;
    }

    /**
     * Reads the record-image column's name out of a described result set.
     *
     * <p>The extractor deliberately never calls {@code next()}: the describe predicate is false on every
     * row, so there is nothing to advance to, and the metadata is the whole of what is being asked for.
     *
     * @param resultSet the described, empty result set
     * @return the column name at the record-image position, or {@code null} if there is none
     * @throws SQLException if the driver fails while describing
     */
    private static String extractRecordImageColumn(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        return DatasetRelation.recordImageColumnOf(metaData);
    }

    // =================================================================================================
    // Refusal handling. What the driver said reaches the log; the exception itself never does.
    // =================================================================================================

    /**
     * Logs a backend refusal with the driver's own diagnosis and returns the {@code WHEN OTHER} outcome
     * carrying it.
     *
     * @param keyImage the key the read was attempting
     * @param refusal  the exception raised
     * @param attempt  what was being attempted, phrased to complete "Could not ..."
     * @return the outcome, carrying the diagnostic
     */
    private static ReadResult refused(String keyImage, Throwable refusal, String attempt) {
        return ReadResult.other(keyImage, PERMANENT_ERROR_STATUS, logRefusal(refusal, attempt));
    }

    /**
     * Logs a backend refusal with the driver's own diagnosis and returns it.
     *
     * <p>Logged: what was attempted, and the {@code SQLSTATE}, vendor code and exception type the driver
     * reported. <strong>Not</strong> logged: the exception itself. A driver's message is prose it
     * composed around the values it refused, so handing the {@link Throwable} to the logger emits that
     * text and its whole cause chain verbatim (CWE-532) in a form a control character can split into a
     * forged entry (CWE-117) - and sanitising the summary while attaching the raw exception beside it
     * sanitises nothing. The record this dataset holds is a transaction-type description and carries no
     * personal data, but the rule is the module's and is applied uniformly rather than judged per
     * dataset.
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
    // Test seams. Package-private: construction details, not part of the contract a caller programs
    // against. They exist so the composed SQL and the key geometry can be asserted with no backend in
    // the path, which matters because residual risk R-E means no backend is reachable from this build.
    // =================================================================================================

    /**
     * The statement that describes the dataset without transferring a row - what {@link #open()} and
     * {@link #close()} issue, and what a read issues once to learn its column name.
     *
     * @return the describe statement over the configured dataset
     */
    String describeStatement() {
        return relation.describeStatement();
    }

    /**
     * The composed keyed-read statement, or {@code null} while it has not been resolved.
     *
     * @return the resolved statement, or {@code null}
     */
    String resolvedKeyedReadStatement() {
        return keyedReadStatement;
    }

    /**
     * The escaped {@code LIKE} pattern a given key image is read with, for a test asserting that the
     * predicate is confined to the key's own bytes.
     *
     * @param keyImage the key image, exactly {@value #TRAN_TYPE_KEY_LENGTH} characters
     * @return the escaped pattern
     */
    String keyedPredicatePattern(String keyImage) {
        return KEY_SPAN.pattern(keyImage);
    }

    // =================================================================================================
    // The discriminated outcome.
    // =================================================================================================

    /**
     * The outcome of one keyed read of the {@value #DD_NAME} dataset: what happened, in the vocabulary
     * every dataset operation in this module speaks, plus the record when there is one.
     *
     * <p>Immutable, complete, and deliberately narrowed to the three arms this {@code READ} has. A
     * caller writes its guard chain as an exhaustive {@code switch} with a {@code default}-complete final
     * arm, which is the shape of the COBOL it stands for
     * ({@code app/cbl/CBTRN03C.cbl:189-190, 494-502}):
     * <pre>
     * TranTypeRepository.ReadResult result = repository.readByTranType(tran.tranTypeCd());
     * switch (result.outcome()) {
     *     case OK -&gt; reportLine.setTypeDesc(                     // MOVE TRAN-TYPE-DESC TO
     *             result.record().orElseThrow()                   //   TRAN-REPORT-TYPE-DESC, :366
     *                   .tranTypeDescMovedTo(TranTypeRecord.REPORT_TYPE_DESC_LENGTH));
     *     default -&gt; {                                           // INVALID KEY, :496-500
     *         display("INVALID TRANSACTION TYPE : " + result.keyImage());
     *         display(FileStatus.toDisplayLine(result.status()));  // 9910-DISPLAY-IO-STATUS
     *         throw AbendException.standard("CBTRN03C", result.applResult());
     *     }
     * }
     * </pre>
     * The display text and the abend are the caller's, exactly as they are the program's: the paragraph
     * that tests the status is the paragraph that decides, and this result only reports.
     *
     * <p>{@link Outcome#END_OF_FILE} and {@link Outcome#DUPLICATE} are rejected at construction. A keyed
     * read has reached the end of nothing, and the primary key of a KSDS is unique - so neither can
     * arise, and admitting them as arms no caller could act on would misrepresent what this read can do.
     *
     * @param keyImage   the key the read used, exactly {@value #TRAN_TYPE_KEY_LENGTH} characters after
     *                   the {@code PIC X} move. Carried because {@code app/cbl/CBTRN03C.cbl:497}
     *                   displays {@code FD-TRAN-TYPE} - these very bytes - in its own message, so a
     *                   caller reproducing that line needs them rather than the value it passed in
     * @param status     the two-character {@code TRANTYPE-STATUS} the read reported:
     *                   {@link FileStatus#OK}, {@link FileStatus#NOT_FOUND} or
     *                   {@link TranTypeRepository#PERMANENT_ERROR_STATUS}
     * @param outcome    the classification of {@code status}, from
     *                   {@link FileStatus#outcomeOfStatus(String)}, so the status and its meaning cannot
     *                   drift apart
     * @param record     the record, present exactly when one was read - so for {@link Outcome#OK} and
     *                   for nothing else
     * @param diagnostic what the backend reported when it refused, present only on an
     *                   {@link Outcome#OTHER} arm the driver described, so the abend that follows can be
     *                   traced to a cause rather than to a status this module composed
     */
    public record ReadResult(String keyImage,
                             String status,
                             Outcome outcome,
                             Optional<TranTypeRecord> record,
                             Optional<BackendDiagnostic> diagnostic) {

        /**
         * Enforces every invariant of the three-armed ladder at construction, so an inconsistent result
         * cannot be built even by a test.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if {@code keyImage} is not exactly
         *                                  {@value TranTypeRepository#TRAN_TYPE_KEY_LENGTH} characters,
         *                                  if {@code status} is not exactly two characters, if
         *                                  {@code outcome} does not classify {@code status}, if
         *                                  {@code outcome} is an arm this read cannot produce, or if the
         *                                  presence of {@code record} disagrees with {@code outcome}
         */
        public ReadResult {
            Objects.requireNonNull(keyImage, "A read result carries the key image the read used; "
                    + "app/cbl/CBTRN03C.cbl:497 displays exactly those bytes, so they are never absent");
            Objects.requireNonNull(status, "A read result carries the two-character file status the read "
                    + "reported; it is never absent");
            Objects.requireNonNull(outcome, "A read result carries its classification; it is never "
                    + "absent");
            Objects.requireNonNull(record, "An Optional is required, empty rather than null, so no null "
                    + "escapes this type");
            Objects.requireNonNull(diagnostic, "An Optional is required for the backend diagnostic, "
                    + "empty rather than null, so no null escapes this type");
            if (keyImage.length() != TRAN_TYPE_KEY_LENGTH) {
                throw new IllegalArgumentException("The key image is " + keyImage.length()
                        + " character(s); TRAN-TYPE is PIC X(0" + TRAN_TYPE_KEY_LENGTH + ") and "
                        + "FD-TRAN-TYPE always holds exactly " + TRAN_TYPE_KEY_LENGTH
                        + ", so a result carrying any other width did not come from a keyed read of "
                        + "this dataset.");
            }
            if (status.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalArgumentException("FILE STATUS '" + status + "' is " + status.length()
                        + " character(s); TRANTYPE-STATUS is declared as two PIC X items at "
                        + "app/cbl/CBTRN03C.cbl:104-106 and is compared as such.");
            }
            if (outcome != FileStatus.outcomeOfStatus(status)) {
                throw new IllegalArgumentException("Outcome " + outcome + " does not classify FILE "
                        + "STATUS '" + status + "', which classifies as "
                        + FileStatus.outcomeOfStatus(status) + ". The status and its meaning are two "
                        + "views of one fact and may not disagree.");
            }
            if (outcome == Outcome.END_OF_FILE) {
                throw new IllegalArgumentException("A keyed read cannot report end of file: "
                        + "app/cbl/CBTRN03C.cbl:41 is ACCESS MODE IS RANDOM, so there is no sequence to "
                        + "exhaust. A key that matches nothing is INVALID KEY, which is "
                        + Outcome.NOT_FOUND + " and FILE STATUS '" + FileStatus.NOT_FOUND + "'.");
            }
            if (outcome == Outcome.DUPLICATE) {
                throw new IllegalArgumentException("A read of TRAN-TYPE cannot report a duplicate: it is "
                        + "the primary key of a KSDS (RECORD KEY IS FD-TRAN-TYPE, "
                        + "app/cbl/CBTRN03C.cbl:42) and is unique by construction. More than one "
                        + "matching row means the relation is not presenting this dataset, which is "
                        + "reported as " + Outcome.OTHER + ".");
            }
            if (record.isPresent() != (outcome == Outcome.OK)) {
                throw new IllegalArgumentException(record.isPresent()
                        ? "Outcome " + outcome + " carries no record, but one was supplied. Only the '"
                                + FileStatus.OK + "' arm reaches TRAN-TYPE-RECORD."
                        : "A successful read carries the record it read, and this one carries none.");
            }
        }

        /**
         * The record was read: status {@link FileStatus#OK}. The {@code NOT INVALID KEY} path of
         * {@code app/cbl/CBTRN03C.cbl:495}.
         *
         * @param keyImage the key image the read used
         * @param record   the record that was read; never {@code null}
         * @return the outcome
         * @throws NullPointerException if {@code record} is {@code null}
         */
        public static ReadResult found(String keyImage, TranTypeRecord record) {
            Objects.requireNonNull(record, "A found outcome must carry the record it found");
            return new ReadResult(keyImage, FileStatus.OK, Outcome.OK, Optional.of(record),
                    Optional.empty());
        }

        /**
         * No record matched the key: status {@link FileStatus#NOT_FOUND}, which is the {@code 23} the
         * program itself moves into {@code IO-STATUS} at {@code app/cbl/CBTRN03C.cbl:498}.
         *
         * <p>The {@code INVALID KEY} arm - a normal branch, never an exception. The display, the
         * rendered status line and the abend that follow it at {@code :497-500} are the caller's.
         *
         * @param keyImage the key image the read used, which the caller displays
         * @return the outcome
         */
        public static ReadResult notFound(String keyImage) {
            return new ReadResult(keyImage, FileStatus.NOT_FOUND, Outcome.NOT_FOUND, Optional.empty(),
                    Optional.empty());
        }

        /**
         * Anything else: the {@code WHEN OTHER} arm, which in this program is the display-and-abend path.
         *
         * @param keyImage the key image the read used
         * @param status   the status to report, which must classify as {@link Outcome#OTHER} - normally
         *                 {@link TranTypeRepository#PERMANENT_ERROR_STATUS}
         * @return the outcome
         * @throws NullPointerException     if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not two characters, or classifies as one
         *                                  of the enumerated outcomes rather than as
         *                                  {@link Outcome#OTHER}
         */
        public static ReadResult other(String keyImage, String status) {
            return new ReadResult(keyImage, status, Outcome.OTHER, Optional.empty(), Optional.empty());
        }

        /**
         * The {@code WHEN OTHER} arm, carrying what the backend actually said about the refusal.
         *
         * <p>The status is what the caller branches on, because that is the quantity the COBOL guard
         * chain tests. The diagnostic is what makes the abend that follows diagnosable: a
         * permanent-error status says something went wrong and nothing about what, whereas the driver's
         * {@code SQLSTATE} distinguishes an unreachable backend from a missing dataset from a rejected
         * credential - three failures needing three different responses from whoever is on call.
         *
         * @param keyImage   the key image the read used
         * @param status     the permanent-error file status
         * @param diagnostic what the backend reported
         * @return the outcome
         * @throws NullPointerException if {@code diagnostic} is {@code null}
         */
        public static ReadResult other(String keyImage, String status, BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A diagnostic is required by this factory; use "
                    + "other(String, String) where there is no backend refusal to report");
            return new ReadResult(keyImage, status, Outcome.OTHER, Optional.empty(),
                    Optional.of(diagnostic));
        }

        /**
         * Whether the record was read - the {@code NOT INVALID KEY} test.
         *
         * @return {@code true} for {@link Outcome#OK}
         */
        public boolean isFound() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether the key matched nothing - the {@code INVALID KEY} test of
         * {@code app/cbl/CBTRN03C.cbl:496}.
         *
         * @return {@code true} for {@link Outcome#NOT_FOUND}
         */
        public boolean isNotFound() {
            return outcome == Outcome.NOT_FOUND;
        }

        /**
         * Whether this is the {@code WHEN OTHER} arm.
         *
         * @return {@code true} for {@link Outcome#OTHER}
         */
        public boolean isOther() {
            return outcome == Outcome.OTHER;
        }

        /**
         * The {@code APPL-RESULT} value this program moves for the outcome: {@code 0} on success and
         * {@value TranTypeRepository#APPL_RESULT_FATAL} otherwise.
         *
         * <p>{@code 1500-B-LOOKUP-TRANTYPE} does not itself touch {@code APPL-RESULT} - it abends
         * directly on {@code INVALID KEY} - so the mapping follows the convention every other guard in
         * the program uses: {@code app/cbl/CBTRN03C.cbl:434} moves {@code 0} for {@code '00'} and
         * {@code :436} moves {@value TranTypeRepository#APPL_RESULT_FATAL} for anything else. The final
         * arm is written as a {@code default} for the same reason the COBOL's is {@code WHEN OTHER}: it
         * is the catch-all, and both a missing record and an I/O failure end this program.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link TranTypeRepository#APPL_RESULT_FATAL}
         */
        public int applResult() {
            return switch (outcome) {
                case OK -> FileStatus.APPL_AOK;
                default -> APPL_RESULT_FATAL;
            };
        }

        /**
         * This status rendered as the four-character image {@code 9910-DISPLAY-IO-STATUS} produces
         * ({@code app/cbl/CBTRN03C.cbl:633-646}), for a caller composing that display line.
         *
         * @return the status image, exactly {@link FileStatus#STATUS_IMAGE_LENGTH} characters
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }
    }
}

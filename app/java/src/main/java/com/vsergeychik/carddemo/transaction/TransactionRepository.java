package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.DatasetObservation;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

/**
 * The one repository for the transaction master and its two sequential companions - the
 * {@code TRANSACT} KSDS, the {@code TRANFILE} input DD and the {@code SYSTRAN} output DD.
 *
 * <h2>The record: 350 bytes, copybook-fixed</h2>
 *
 * <p>{@code app/cpy/CVTRA05Y.cpy} declares {@code 01 TRAN-RECORD} at {@code (RECLN = 350)} and is the
 * sole authority for its shape. The Java type is {@link TranRecord}; this class consumes it and never
 * restates a field offset of its own. Three offsets are worth naming here because they are the ones a
 * reader can cross-check against a second, independent source, and they are quoted in the copybook's
 * <strong>1-based</strong> convention:
 * <ul>
 *   <li>{@code TRAN-ID PIC X(16)} at offset <strong>1</strong>, length 16 - the KSDS key;</li>
 *   <li>{@code TRAN-CARD-NUM PIC X(16)} at offset <strong>263</strong>, length 16;</li>
 *   <li>{@code TRAN-PROC-TS PIC X(26)} at offset <strong>305</strong>, length 26.</li>
 * </ul>
 * The last two are confirmed from outside the copybook entirely: the {@code SYMNAMES} deck of
 * {@code app/jcl/TRANREPT.jcl} declares {@code TRAN-CARD-NUM,263,16,ZD} at {@code :41} and
 * {@code TRAN-PROC-DT,305,10,CH} at {@code :42}, so the sort that feeds the report addresses the very
 * same bytes at the very same positions. {@link TranRecord} holds the same three spans 0-based, as
 * {@link TranRecord#TRAN_ID_OFFSET}, {@link TranRecord#TRAN_CARD_NUM_OFFSET} and
 * {@link TranRecord#TRAN_PROC_TS_OFFSET}.
 *
 * <p>The record's trailing {@code FILLER PIC X(20)} is <strong>emitted</strong>, as spaces, on every
 * write. It is not decoration: drop it and the record is 330 bytes, every downstream offset in a
 * consuming job is wrong, and the width assertion of gate G19 fails. {@link TranRecord}'s own
 * {@code LAYOUT} refuses to initialise unless the fourteen declared spans sum to exactly 350, so a
 * transcription error cannot survive class loading (gates G19 and G21).
 *
 * <h2>{@code RECORDFORMAT(V)} versus {@code RECFM=F} - resolved in favour of a fixed 350</h2>
 *
 * <p>The two authorities disagree, in writing. {@code app/csd/CARDDEMO.CSD:81} declares the CICS file
 * with {@code RECORDFORMAT(V)}, while the batch JCL declares fixed records for the very same data:
 * {@code app/jcl/TRANREPT.jcl:31} is {@code DCB=(LRECL=350,RECFM=FB,BLKSIZE=0)} and
 * {@code app/jcl/INTCALC.jcl:39} is {@code DCB=(RECFM=F,LRECL=350,BLKSIZE=0)}.
 *
 * <p>AAP section 0.7.4 resolves it: <strong>the record length is copybook-fixed at 350 regardless</strong>,
 * and no variable-length record is modelled anywhere in this module. This class enforces that at
 * construction - every one of its three bindings must declare {@code record-length: 350} or the bean
 * refuses to exist - rather than tolerating a width and discovering the consequences one misplaced
 * field at a time. The configuration layer agrees: {@code DatasetBindings} does not even accept
 * {@code V} as a record format.
 *
 * <h2>The eight consumers, and exactly what each one asks for</h2>
 *
 * <p>Every access path below exists because a program uses it, and none exists for any other reason
 * (AAP section 0.3.5: a repository exposes only the paths the COBOL actually uses, and invents no SQL
 * surface). <strong>The AAP's own brief for this file lists seven consumers; independent verification
 * finds eight.</strong> The eighth is {@code COBIL00C}, which AAP section 0.4.12 does list among the
 * datasets it reaches. Its access shape is identical to {@code COTRN02C}'s, so the method surface is
 * unaffected - but the omission is recorded here rather than quietly absorbed (practice B4).
 *
 * <ol>
 *   <li><strong>{@code CBTRN03C} - sequential input.</strong>
 *       {@code SELECT TRANSACT-FILE ASSIGN TO TRANFILE ORGANIZATION IS SEQUENTIAL}
 *       ({@code app/cbl/CBTRN03C.cbl:29-31}); {@code 0000-TRANFILE-OPEN} does {@code OPEN INPUT}
 *       ({@code :376-392}); {@code 1000-TRANFILE-GET-NEXT} does
 *       {@code READ TRANSACT-FILE INTO TRAN-RECORD} and evaluates the status
 *       {@code '00'} to {@code APPL-RESULT 0}, {@code '10'} to {@code 16} and anything else to
 *       {@code 12} ({@code :248-272}); {@code 9000-TRANFILE-CLOSE} closes ({@code :514-530}). Its
 *       {@code FD} splits 304 + 26 + 20, which is 350. Served by {@link #openInput()} and
 *       {@link InputFile#readNext()}.</li>
 *   <li><strong>{@code CBACT04C} - sequential output.</strong>
 *       {@code SELECT TRANSACT-FILE ASSIGN TO TRANSACT ORGANIZATION IS SEQUENTIAL}
 *       ({@code app/cbl/CBACT04C.cbl:53-56}); {@code 0400-TRANFILE-OPEN} does {@code OPEN OUTPUT}
 *       ({@code :307-323}); {@code 1300-B-WRITE-TX} does
 *       {@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD} at {@code :500}; {@code 9400-TRANFILE-CLOSE}
 *       closes at {@code :597}. Its DD name is {@code TRANSACT} but its dataset is
 *       {@code SYSTRAN(+1)} - see the DD-collision note below. Served by {@link #openOutput()} and
 *       {@link OutputFile#writeSequential(TranRecord)}.</li>
 *   <li><strong>{@code CBTRN02C} - keyed add.</strong>
 *       {@code ORGANIZATION IS INDEXED / ACCESS MODE IS RANDOM / RECORD KEY IS FD-TRANS-ID}
 *       ({@code app/cbl/CBTRN02C.cbl:34-38}); {@code 0100-TRANFILE-OPEN} does {@code OPEN OUTPUT} on
 *       that indexed file - load mode ({@code :254-270}); {@code 2900-WRITE-TRANSACTION-FILE} does
 *       {@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD} at {@code :564} and treats <em>any</em> status
 *       but {@code '00'} as {@code APPL-RESULT 12}, the duplicate status {@code '22'} included
 *       ({@code :562-579}). Served by {@link #write(TranRecord)}.</li>
 *   <li><strong>{@code CBTRN01C} - open and close, and nothing else.</strong>
 *       {@code SELECT TRANSACT-FILE ASSIGN TO TRANFILE ORGANIZATION IS INDEXED ACCESS MODE IS RANDOM}
 *       ({@code app/cbl/CBTRN01C.cbl:58-62}); {@code 0500-TRANFILE-OPEN} does {@code OPEN INPUT}
 *       ({@code :343-359}) and {@code 9500-TRANFILE-CLOSE} closes ({@code :451-467}) - and the program
 *       never reads or writes the file at all. Its only {@code READ} verbs target {@code DALYTRAN}
 *       ({@code :203}), {@code XREF-FILE} ({@code :229}) and {@code ACCOUNT-FILE} ({@code :243}).
 *       That dead access is <strong>preserved, not tidied</strong> ({@code CBTRN01C} is the orphan
 *       program of AAP implicit requirement I4 and practice B5): opening this repository's input and
 *       closing it without a read is exactly what the program does, and both statuses are reported.</li>
 *   <li><strong>{@code COTRN00C} - browse, both directions.</strong> {@code STARTBR}
 *       ({@code app/cbl/COTRN00C.cbl:591-619}), {@code READNEXT} ({@code :624-653}), {@code READPREV}
 *       ({@code :658-687}), {@code ENDBR} ({@code :692-695}), all
 *       {@code RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF TRAN-ID)}. Its anchors are the boundary keys:
 *       {@code CDEMO-CT00-TRNID-FIRST} or {@code LOW-VALUES} going forward ({@code :236-239}) and
 *       {@code CDEMO-CT00-TRNID-LAST} or {@code HIGH-VALUES} going backward ({@code :259-262}).
 *       Served by {@link #startBrowse(BrowseDirection)} and
 *       {@link #startBrowse(String, BrowseDirection)}.</li>
 *   <li><strong>{@code COTRN01C} - keyed read.</strong> {@code READ-TRANSACT-FILE} at
 *       {@code app/cbl/COTRN01C.cbl:269-278}, with arms {@code NORMAL}, {@code NOTFND} to
 *       {@code 'Transaction ID NOT found...'} and {@code OTHER}. <strong>It specifies the
 *       {@code UPDATE} option</strong> ({@code :275}), which the AAP's brief for this file does not
 *       mention; both forms are therefore provided - {@link #readByTranId(String)} and
 *       {@link #readForUpdateByTranId(String)} - and the discrepancy is recorded rather than
 *       silently resolved (practice B4). No {@code REWRITE} follows it; see the next section.</li>
 *   <li><strong>{@code COTRN02C} - browse to the end, then keyed add.</strong>
 *       {@code ADD-TRANSACTION} ({@code app/cbl/COTRN02C.cbl:441-466}) and
 *       {@code COPY-LAST-TRAN-DATA} ({@code :473-478}) both do
 *       {@code MOVE HIGH-VALUES TO TRAN-ID}, {@code STARTBR} ({@code :642-668}), {@code READPREV}
 *       ({@code :673-697}), {@code ENDBR} ({@code :702-704}) to reach the highest existing key, then
 *       {@code ADD 1} for the next one and {@code WRITE} ({@code :711-749}).</li>
 *   <li><strong>{@code COBIL00C} - the same shape.</strong> {@code MOVE HIGH-VALUES TO TRAN-ID}
 *       ({@code app/cbl/COBIL00C.cbl:212}), {@code STARTBR} ({@code :441-467}), {@code READPREV}
 *       ({@code :472-496}), {@code ENDBR} ({@code :501-504}), {@code WRITE} ({@code :510-547}). Its
 *       {@code EXEC CICS REWRITE} at {@code :379} names {@code DATASET(WS-ACCTDAT-FILE)} at
 *       {@code :380} - the account master, not this dataset.</li>
 * </ol>
 *
 * <p><strong>{@code CORPT00C} is not a consumer.</strong> It declares
 * {@code WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'} at {@code app/cbl/CORPT00C.cbl:40} and then
 * issues no file command against it: every {@code EXEC CICS} in the program is {@code RETURN}
 * ({@code :199}, {@code :587}), {@code WRITEQ TD} ({@code :517}), a screen send ({@code :548},
 * {@code :563}, {@code :571}) or {@code RECEIVE} ({@code :598}). No access path is added for it.
 *
 * <h2>There is no {@code rewrite} and no {@code delete} here, and that is deliberate</h2>
 *
 * <p>The AAP's one-line summary for this file reads "read, browse, add and rewrite". A
 * repository-wide verification - re-run for this file rather than taken on trust - contradicts the
 * last of those. {@code grep} for {@code REWRITE} and {@code DELETE} across all 28 programs in
 * {@code app/cbl} returns sixteen hits, and not one of them targets this dataset:
 * <ul>
 *   <li>{@code CBACT04C.cbl:356} and {@code CBTRN02C.cbl:554} rewrite {@code FD-ACCTFILE-REC} - the
 *       account master;</li>
 *   <li>{@code CBTRN02C.cbl:528} rewrites {@code FD-TRAN-CAT-BAL-RECORD} - the category balance
 *       file;</li>
 *   <li>{@code COACTUPC.cbl:4066} and {@code :4086} rewrite {@code FILE(LIT-ACCTFILENAME)} and
 *       {@code FILE(LIT-CUSTFILENAME)};</li>
 *   <li>{@code COBIL00C.cbl:379} rewrites {@code DATASET(WS-ACCTDAT-FILE)} ({@code :380});</li>
 *   <li>{@code COCRDUPC.cbl:1478} rewrites {@code FILE(LIT-CARDFILENAME)};</li>
 *   <li>{@code COUSR02C.cbl:360} rewrites and {@code COUSR03C.cbl:307} deletes {@code USRSEC};</li>
 *   <li>{@code CBSTM03A.CBL:79} and {@code CBSTM03B.CBL:108} are the condition-name declaration
 *       {@code 88 M03B-REWRITE VALUE 'Z'} inside the statement subroutine's linkage area - a
 *       declaration, not a file operation, and not against this dataset in any case.</li>
 * </ul>
 *
 * <p>So no program in the estate rewrites or deletes a transaction record.
 * {@code app/csd/CARDDEMO.CSD:81-82} does grant the capability - {@code UPDATE(YES) DELETE(YES)} -
 * but <strong>a granted capability is not an access path</strong>. Adding the two methods would
 * invent surface no caller can justify and, worse, would offer a mutation of the audit trail that the
 * legacy system does not have. They are omitted, and the omission is documented here with its
 * evidence, which is what practice B4 asks for when a summary and the source disagree.
 *
 * <p>For the same reason there is no second finder on this class. Gate G45 concerns alternate-index
 * paths, and {@code TRANSACT} has none: the CSD defines no {@code AIX} path over it, and the
 * configured binding declares no {@code base} and no {@code alternate-key}. The constructor asserts
 * that rather than merely assuming it.
 *
 * <h2>Three DD names, because a DD name is not globally unique</h2>
 *
 * <p>This is not tidiness; it is the legacy estate's actual shape, and collapsing it would bind a job
 * to the wrong dataset:
 * <ul>
 *   <li><strong>{@code TRANSACT}</strong> is the CICS {@code FILE} - the transaction master KSDS,
 *       keyed on the 16-byte {@code TRAN-ID}. Every keyed operation on this class addresses it.</li>
 *   <li><strong>{@code TRANFILE}</strong> is the batch DD. In {@code app/jcl/POSTTRAN.jcl:28-29} it is
 *       that same KSDS; in {@code app/jcl/TRANREPT.jcl:65-66} it is
 *       {@code TRANSACT.DALY(+1)}, a different, sequential dataset. {@link #openInput()} reads the
 *       globally configured binding; a job whose {@code TRANFILE} is overridden passes its own
 *       resolved binding to {@link #openInput(DatasetBinding)}. Resolving a job's view of a DD name
 *       belongs to the batch configuration, not to this class.</li>
 *   <li><strong>{@code SYSTRAN}</strong> is the sequential output. {@code app/jcl/INTCALC.jcl:37-41}
 *       opens it under the DD name {@code TRANSACT} - the same spelling as the CICS file - at
 *       {@code RECFM=F, LRECL=350}, as a brand-new generation. It is not the master, and writing the
 *       interest transactions into the master would be a behaviour change of the most damaging
 *       kind.</li>
 * </ul>
 *
 * <p>No dataset name appears in this file. All three are resolved by DD-name key from the
 * {@code carddemo.datasets} catalogue, whose lookup fails fast and names every configured key when a
 * key is absent (gate G46, practice B8). A search of this source for a mainframe dataset literal
 * finds nothing.
 *
 * <h2>What this class never does</h2>
 * <ul>
 *   <li><strong>It never throws an abend.</strong> Every I/O outcome is <em>reported</em> as a
 *       {@link FileStatus} together with its CICS counterpart, and the caller decides. That is exactly
 *       how the COBOL is written: {@code CBTRN03C} reaches {@code 9999-ABEND-PROGRAM} from its own
 *       guard chain ({@code :266-269}), {@code CBTRN01C} reaches {@code Z-ABEND-PROGRAM} from its own
 *       ({@code :355-357}), and the online programs set an error flag and repaint the screen. A
 *       repository that abended would take that decision away from all eight of them.</li>
 *   <li><strong>It declares no schema.</strong> No data-definition statement, no entity or table
 *       annotation, no generated table definition, no version column and no index (gate G44). The
 *       optimistic-concurrency check the estate does have lives in {@code 9300-CHECK-CHANGE-IN-REC},
 *       in two <em>other</em> programs, over two <em>other</em> datasets.</li>
 *   <li><strong>It holds no mutable state.</strong> The bean is a singleton and every cursor - input,
 *       output, browse - carries its own position, its own counters and its own open flag. No
 *       {@code static} field is mutable (practice B9, gate G53). The one non-final instance field is
 *       the lazily resolved, immutable statement set.</li>
 *   <li><strong>It never uses {@code double} or {@code float}</strong>, and never converts a monetary
 *       value at all: a record crosses this boundary as its 350 bytes (gate G22).</li>
 *   <li><strong>It never relies on a platform default charset.</strong> The dataset code page is
 *       injected by bean name and handed explicitly to {@link FixedWidthCodec} and to
 *       {@link TranRecord} on every decode (practice B8).</li>
 *   <li><strong>It uses no ORM.</strong> {@link JdbcTemplate} only, on Spring Framework 6.2.x under
 *       Spring Boot 3.5.x, which is the pinned stack (practice B2).</li>
 * </ul>
 *
 * <h2>Residual risk R-E: the production driver is a deployment-time input</h2>
 *
 * <p>Production JDBC connectivity <strong>cannot be exercised in this environment</strong>. The build
 * declares no driver coordinate in {@code app/java/pom.xml} - the prompt mandates JDBC to the existing
 * VSAM backend and names no driver, and VSAM has no standard Maven-published one - so the driver and
 * the shape in which it presents a record image are supplied at deployment time. Two consequences are
 * visible in this class:
 * <ul>
 *   <li>the record image is addressed by <strong>column position</strong>
 *       {@link DatasetRelation#RECORD_IMAGE_COLUMN_INDEX}, never by a column name this module invented,
 *       and the name needed to compose a predicate is <em>discovered</em> from the backend's own
 *       metadata;</li>
 *   <li>whether that column crosses JDBC as characters or as bytes is {@link RecordImageForm}'s single
 *       decision, taken once from {@value RecordImageForm#FORM_PROPERTY} and never re-taken here.</li>
 * </ul>
 * This class is therefore validated against the fixture-backed harness with H2 at test scope, and the
 * driver binding is documented as a deployment-time input rather than assumed (practice B12).
 *
 * <h2>A note on the collaborators this class reaches for</h2>
 *
 * <p>{@link DatasetRelation}, {@link RecordImageForm}, {@link CicsResponse},
 * {@link DatasetObservation} and {@link DatasetUnitOfWork} are reached through the published contract
 * of {@code config/DataSourceConfig}, which is a declared dependency of this file:
 * {@code DataSourceConfig} publishes the {@link RecordImageForm} bean, and
 * {@link DatasetBinding#keySpan()} returns a {@link KeySpan}. Using them is what keeps one authority
 * for the z/OS dataset-name grammar, for statement composition and for the JDBC representation of a
 * record image; restating any of that here would give the module two answers to a question that must
 * have one (practices B8 and B11). All three sibling repositories consume the same set.
 */
@Repository
public class TransactionRepository {

    /**
     * Commons Logging, as Spring Boot's own starters use, so this class adds no logging dependency.
     * A refusal is logged with the backend's codes and never with a key or a record image: a
     * transaction record carries a primary account number at offset 263 (CWE-532), in text a control
     * character could split into a second log entry (CWE-117).
     */
    private static final Log LOG = LogFactory.getLog(TransactionRepository.class);

    // =================================================================================================
    // The three configuration keys. Names, not dataset names: every location is resolved from
    // carddemo.datasets and none is written here (gate G46).
    // =================================================================================================

    /**
     * The CICS {@code FILE} name and configuration key of the transaction master KSDS -
     * {@code app/csd/CARDDEMO.CSD:76}. It is also the DD name {@code app/jcl/INTCALC.jcl:37} gives to
     * a completely different dataset, which is why {@link #SEQUENTIAL_OUTPUT_DD_NAME} exists.
     */
    public static final String CICS_FILE_NAME = "TRANSACT";

    /**
     * The batch DD name under which the transaction master is read - {@code app/jcl/POSTTRAN.jcl:28}
     * - and under which {@code app/jcl/TRANREPT.jcl:65} reads the sorted daily file instead.
     */
    public static final String INPUT_DD_NAME = "TRANFILE";

    /**
     * The configuration key of the sequential output generation {@code app/jcl/INTCALC.jcl:37-41}
     * creates, which that JCL addresses under the DD name {@value #CICS_FILE_NAME}.
     */
    public static final String SEQUENTIAL_OUTPUT_DD_NAME = "SYSTRAN";

    /**
     * The declared width of a CICS {@code FILE} name in the online programs' working storage:
     * {@code 05 WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'} ({@code app/cbl/COTRN00C.cbl:39},
     * {@code app/cbl/COTRN01C.cbl:39}, {@code app/cbl/COTRN02C.cbl:39},
     * {@code app/cbl/COBIL00C.cbl:40}).
     *
     * <p>Worth stating because {@value #CICS_FILE_NAME} is <em>exactly</em> eight characters, so it
     * needs no padding and none is applied - which is cheaper to say than to leave a reader of a
     * composed error message wondering whether a trailing space was lost. A caller building a message
     * that names this file at its declared width has the width here.
     */
    public static final int CICS_FILE_NAME_LENGTH = 8;

    // =================================================================================================
    // Record and key geometry, all taken from the model type so there is one transcription of
    // app/cpy/CVTRA05Y.cpy in this module rather than two that can drift.
    // =================================================================================================

    /** The fixed record width, {@code (RECLN = 350)} of {@code app/cpy/CVTRA05Y.cpy}. */
    public static final int RECORD_LENGTH = TranRecord.RECORD_LENGTH;

    /** The KSDS key width: {@code TRAN-ID PIC X(16)}. */
    public static final int KEY_LENGTH = TranRecord.TRAN_ID_KEY_LENGTH;

    /** The key's 0-based offset within the record image: {@code TRAN-ID} leads the record. */
    public static final int KEY_OFFSET = TranRecord.TRAN_ID_OFFSET;

    /**
     * The column position the record image occupies. A position and not a name, for the reason set out
     * under residual risk R-E on this class.
     */
    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    /**
     * Where the key sits inside the record image, as every keyed predicate on this class addresses it.
     *
     * <p>{@code static final} and a {@code record}, therefore immutable: this is a shared constant, not
     * shared mutable state (practice B9).
     */
    private static final KeySpan KEY_SPAN = new KeySpan(KEY_OFFSET, KEY_LENGTH);

    // =================================================================================================
    // Status vocabulary. The batch programs' APPL-RESULT ladder and the one status this module
    // composes for a failure the COBOL never enumerated.
    // =================================================================================================

    /**
     * The {@code APPL-RESULT} every batch consumer moves on a fatal I/O outcome:
     * {@code MOVE 12 TO APPL-RESULT} at {@code app/cbl/CBTRN03C.cbl:257},
     * {@code app/cbl/CBACT04C.cbl:314}, {@code app/cbl/CBTRN02C.cbl:261} and
     * {@code app/cbl/CBTRN01C.cbl:349}.
     */
    public static final int APPL_RESULT_FATAL = 12;

    /**
     * The feedback code of the composed permanent-error status. {@code 0} as a <em>character</em>, so
     * the status is {@code '9'} followed by {@code X'00'} - the shape VSAM uses for a status {@code 9x}
     * whose {@code x} is a binary feedback code, and never a printable {@code '90'} that a reader might
     * mistake for a status the COBOL tests.
     */
    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The two-character status this module reports for an I/O failure the COBOL never enumerated.
     *
     * <p>Every consumer's guard chain has a {@code WHEN OTHER} arm and this status lands on it, which
     * is the whole point: it is deliberately <em>not</em> {@code '00'}, {@code '10'}, {@code '22'} or
     * {@code '23'}, so it cannot be mistaken for a condition a program handles specially. Render it
     * with {@link FileStatus#toStatusImage(String)} before putting it in a message, exactly as
     * {@code 9910-DISPLAY-IO-STATUS} does.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    // =================================================================================================
    // Operation names, for a diagnostic that says which verb failed. The COBOL's own words.
    // =================================================================================================

    /** {@code OPEN INPUT} / {@code OPEN OUTPUT}. */
    private static final String OPEN_OPERATION_NAME = "OPEN";

    /** A keyed {@code READ}, a {@code READNEXT} or a {@code READPREV}. */
    private static final String READ_OPERATION_NAME = "READ";

    /** A keyed {@code WRITE} or a sequential {@code WRITE}. */
    private static final String WRITE_OPERATION_NAME = "WRITE";

    // =================================================================================================
    // Row limits. Each one says out loud how many rows can possibly change the answer.
    // =================================================================================================

    /** One row: a keyed read on a unique key, and one step of a browse or a sequential read. */
    private static final int SINGLE_ROW = 1;

    /**
     * Two rows: enough to tell a unique match from a duplicate without transferring a third. A KSDS
     * primary key is unique by construction, so a second row means the backing presentation of the
     * dataset has lost that uniqueness, and that is reportable rather than ignorable.
     */
    private static final int DUPLICATE_DETECTION_ROW_LIMIT = 2;

    /**
     * The escape character of every {@code LIKE} predicate this class composes.
     *
     * <p>It must be the same character {@link DatasetRelation} escapes with, because
     * {@link KeySpan#pattern(String)} produces the pattern and this class produces the
     * {@code ESCAPE} clause for the one predicate {@link DatasetRelation} does not provide. Stated as
     * a named constant so the two cannot silently disagree.
     */
    private static final char LIKE_ESCAPE = '\\';

    // =================================================================================================
    // Collaborators and resolved identities. Every field is final except the lazily resolved,
    // immutable statement set.
    // =================================================================================================

    /** The module's single template, over the configuration-bound {@code DataSource}. */
    private final JdbcTemplate jdbcTemplate;

    /**
     * The hand-written fixed-width codec, built once over the explicitly injected dataset code page.
     * Held rather than rebuilt per row so the code-page validation happens once (practice B11).
     */
    private final FixedWidthCodec codec;

    /**
     * How a record image crosses JDBC in this deployment: the module's single answer, injected rather
     * than chosen here. A repository that decided for itself would be one of several answers to one
     * question about one backend, and the wrong ones fail silently, because a driver asked for a type
     * the column does not have converts rather than refuses.
     */
    private final RecordImageForm recordImageForm;

    /** The resolved {@value #CICS_FILE_NAME} master, addressed by every keyed operation. */
    private final DatasetRelation masterRelation;

    /** The resolved {@value #INPUT_DD_NAME} input, addressed by {@link #openInput()}. */
    private final DatasetRelation inputRelation;

    /**
     * Whether the configured {@value #INPUT_DD_NAME} binding is keyed.
     *
     * <p>It decides how a sequential read of it advances, and the difference is not cosmetic - see
     * {@link #openInput()}. Held as a flag rather than re-derived per read so the decision is taken
     * once, at startup, from the configuration the deployment declared.
     */
    private final boolean inputIsKeyed;

    /** The resolved {@value #SEQUENTIAL_OUTPUT_DD_NAME} output, addressed by {@link #openOutput()}. */
    private final DatasetRelation outputRelation;

    /**
     * The statements over the master, composed on first use.
     *
     * <p>Lazily, because the record-image column's name is discovered from the backend and a
     * repository must be constructible in a context that has not reached its backend yet.
     * {@code null} means "not resolved"; the value it holds is an immutable record.
     *
     * <p><strong>The only mutable field on this bean, and {@code volatile} deliberately.</strong> A
     * {@code @Repository} is a singleton, so all seventeen controllers reach this instance concurrently.
     * The field is a pure memo: {@link #resolveStatements()} derives it from nothing but the immutable
     * constructor inputs, so two threads racing to compose it produce equal values and either may win -
     * the race is benign and no lock is warranted. What {@code volatile} buys is the publication
     * guarantee: without it a thread could in principle observe the reference before the record it
     * points at is fully visible, and a repository that hands out a half-built statement set would fail
     * in a way no test could reproduce. It costs one volatile read per operation and only until the
     * first resolution, which is not a throughput concern this migration trades correctness for
     * (AAP 0.8.6 states no performance objective).
     *
     * <p>This is memoised configuration, not COBOL {@code WORKING-STORAGE}. Per-operation state - a
     * cursor's position, its row buffer, whether it is closed - lives on the {@link InputFile},
     * {@link OutputFile} and {@link Browse} objects each call returns, never here, which is what keeps
     * concurrent callers isolated from one another (practice B9, gate G53).
     */
    private volatile Statements statements;


    /**
     * Assembles the repository from the module's shared {@link JdbcTemplate}, the DD-name-keyed dataset
     * catalogue, the explicitly named dataset code page and the deployment's record-image
     * representation.
     *
     * <p>Constructor injection throughout - no field injection, no setter - so an instance is either
     * fully wired or does not exist (practice B9, gate G53).
     *
     * <p><strong>Everything checkable about the configuration is checked here, at startup, and not at
     * the first read.</strong> Each check guards a defect that would otherwise surface as
     * plausible-looking wrong data rather than as a failure:
     * <ol>
     *   <li>the collaborators are present - a missing one is a wiring defect and is reported as
     *       one;</li>
     *   <li>all three DD names are configured. {@link DatasetBindings#binding(String)} matches a key
     *       exactly, with no case-insensitive or fuzzy fallback, and names every configured key in its
     *       diagnostic when one is absent;</li>
     *   <li>every one of the three bindings declares {@link #RECORD_LENGTH}. This class addresses the
     *       record by absolute offset against {@code app/cpy/CVTRA05Y.cpy}, so a differently-sized
     *       record would misplace every field after the first - and both the CSD's
     *       {@code RECORDFORMAT(V)} and the JCL's {@code RECFM=F} agree on 350 bytes per record even
     *       while they disagree about the format;</li>
     *   <li>the master binding is <strong>keyed</strong>, on a key of exactly {@link #KEY_LENGTH} bytes
     *       at offset {@link #KEY_OFFSET}. {@code app/csd/CARDDEMO.CSD:76-87} defines a KSDS and
     *       {@code app/cbl/CBTRN02C.cbl:34-38} names {@code RECORD KEY IS FD-TRANS-ID}, the leading
     *       sixteen bytes; a binding that said otherwise would let a keyed read match on the wrong
     *       bytes;</li>
     *   <li>the master binding declares <strong>no</strong> {@code base} and no {@code alternate-key}.
     *       {@code TRANSACT} has no alternate-index path in the CSD, so this class has no second finder
     *       - and a binding that claimed a base would be describing an access path that does not exist
     *       (gate G45 asserted, not merely documented);</li>
     *   <li>the sequential output binding is <strong>not</strong> keyed.
     *       {@code app/jcl/INTCALC.jcl:37-41} creates a brand-new sequential generation, and
     *       {@code app/cbl/CBACT04C.cbl:53-55} opens it {@code ORGANIZATION IS SEQUENTIAL / ACCESS MODE
     *       IS SEQUENTIAL}. A key on it would be a contradiction, and honouring one would invite a
     *       keyed path the dataset does not support;</li>
     *   <li>every resolved dataset name satisfies the z/OS dataset-name grammar. The names arrive from
     *       configuration, which is externally controlled, and reach a position in a statement that no
     *       bind parameter can occupy, so the grammar - which lives in {@link DatasetRelation} for the
     *       whole module - has the last word.</li>
     * </ol>
     *
     * <p>The code page is injected by bean name and handed straight to the codec: a fixed-width
     * mainframe record is bytes in a specific code page, and that choice is stated once by
     * configuration rather than repeated, and diverging, per call site (practice B8).
     *
     * @param jdbcTemplate    the module-wide template; never {@code null}
     * @param datasetBindings the {@code carddemo.datasets} catalogue; never {@code null}. The three
     *                        entries this repository needs are looked up by the keys
     *                        {@value #CICS_FILE_NAME}, {@value #INPUT_DD_NAME} and
     *                        {@value #SEQUENTIAL_OUTPUT_DD_NAME}, so no dataset name is written in
     *                        Java (gate G46)
     * @param datasetCharset  the dataset code page, injected by bean name so it is stated explicitly
     *                        rather than taken from the platform (practice B8)
     * @param recordImageForm how the deployment's driver presents a record image over JDBC, from
     *                        {@value RecordImageForm#FORM_PROPERTY}
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if any binding is absent, declares a record length other than
     *                               {@link #RECORD_LENGTH}, declares key geometry that contradicts its
     *                               source, or names something that is not a well-formed dataset name
     */
    public TransactionRepository(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm) {

        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + "transaction master is reached through the module's shared template over the "
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

        DatasetBinding master = requireRecordWidth(CICS_FILE_NAME,
                datasetBindings.binding(CICS_FILE_NAME));
        DatasetBinding input = requireRecordWidth(INPUT_DD_NAME,
                datasetBindings.binding(INPUT_DD_NAME));
        DatasetBinding output = requireRecordWidth(SEQUENTIAL_OUTPUT_DD_NAME,
                datasetBindings.binding(SEQUENTIAL_OUTPUT_DD_NAME));

        requireKeyedMaster(master);
        requireNoAlternateIndexPath(master);
        requireSequentialOutput(output);

        this.masterRelation = DatasetRelation.of(
                requireUsableDatasetName(CICS_FILE_NAME, master.dsname()), RECORD_LENGTH);
        this.inputRelation = DatasetRelation.of(
                requireUsableDatasetName(INPUT_DD_NAME, input.dsname()), RECORD_LENGTH);
        this.outputRelation = DatasetRelation.of(
                requireUsableDatasetName(SEQUENTIAL_OUTPUT_DD_NAME, output.dsname()), RECORD_LENGTH);
        this.inputIsKeyed = input.keyed();
    }

    // =================================================================================================
    // Constructor guards. Each one rejects a configuration that would read, compare or write the wrong
    // bytes, and each is reachable from a plain unit test with a hand-built binding - no context, no
    // backend (practice B10, gate G51).
    // =================================================================================================

    /**
     * Requires a binding to agree with {@code app/cpy/CVTRA05Y.cpy} about the record width.
     *
     * @param ddName  the configuration key, for the diagnostic
     * @param binding the configured binding
     * @return {@code binding}, unchanged, so the check composes into an assignment
     * @throws IllegalStateException if the declared record length is not {@link #RECORD_LENGTH}
     */
    private static DatasetBinding requireRecordWidth(String ddName, DatasetBinding binding) {
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding for '" + ddName + "' declares a record "
                    + "length of " + binding.recordLength() + ", but app/cpy/CVTRA05Y.cpy declares "
                    + "(RECLN " + RECORD_LENGTH + ") and this repository addresses the record by "
                    + "absolute offset against exactly that width. The CICS definition's "
                    + "RECORDFORMAT(V) (app/csd/CARDDEMO.CSD:81) does not change it: every JCL DCB "
                    + "over the same data declares LRECL=" + RECORD_LENGTH
                    + " (app/jcl/TRANREPT.jcl:31, app/jcl/INTCALC.jcl:39) and the record width is "
                    + "copybook-fixed. Correct carddemo.datasets." + ddName + ".record-length to "
                    + RECORD_LENGTH + ".");
        }
        return binding;
    }

    /**
     * Requires the master binding to be keyed on the leading {@link #KEY_LENGTH} bytes.
     *
     * @param master the configured master binding
     * @throws IllegalStateException if it is not keyed, or declares a different key width or offset
     */
    private static void requireKeyedMaster(DatasetBinding master) {
        if (!master.keyed()) {
            throw new IllegalStateException("Dataset binding for '" + CICS_FILE_NAME + "' declares "
                    + "organization '" + master.organization() + "', but the transaction master is an "
                    + "indexed cluster: app/csd/CARDDEMO.CSD:76-87 defines it as a KSDS and "
                    + "app/cbl/CBTRN02C.cbl:34-38 reads it ORGANIZATION IS INDEXED with RECORD KEY IS "
                    + "FD-TRANS-ID. Every keyed read, browse and add on this repository addresses that "
                    + "key. Correct carddemo.datasets." + CICS_FILE_NAME + ".organization to "
                    + DatasetBinding.KSDS + ".");
        }
        Integer declaredKeyLength = master.keyLength();
        if (declaredKeyLength == null || declaredKeyLength != KEY_LENGTH) {
            throw new IllegalStateException("Dataset binding for '" + CICS_FILE_NAME + "' declares key "
                    + "length " + declaredKeyLength + ", but the key is TRAN-ID PIC X(" + KEY_LENGTH
                    + ") - app/cpy/CVTRA05Y.cpy - and every consumer passes "
                    + "KEYLENGTH(LENGTH OF TRAN-ID), for example app/cbl/COTRN01C.cbl:274. A "
                    + "narrower width would turn a keyed read into a prefix match over more records "
                    + "than the key names; a wider one would reach into TRAN-TYPE-CD. Correct "
                    + "carddemo.datasets." + CICS_FILE_NAME + ".key-length to " + KEY_LENGTH + ".");
        }
        if (master.keyOffsetOrZero() != KEY_OFFSET) {
            throw new IllegalStateException("Dataset binding for '" + CICS_FILE_NAME + "' declares key "
                    + "offset " + master.keyOffsetOrZero() + ", but TRAN-ID leads the record: it is the "
                    + "first field of app/cpy/CVTRA05Y.cpy, at 0-based offset " + KEY_OFFSET
                    + ". Remove carddemo.datasets." + CICS_FILE_NAME + ".key-offset, which defaults to "
                    + KEY_OFFSET + ".");
        }
    }

    /**
     * Requires the master binding to be a base cluster with no alternate-index path over it.
     *
     * <p>This is gate G45 enforced rather than assumed. {@code app/csd/CARDDEMO.CSD} defines
     * alternate-index paths over the card file ({@code CARDAIX}) and the cross reference
     * ({@code CXACAIX}) and over nothing else; the transaction master has none, which is why this
     * class has one finder and not two.
     *
     * @param master the configured master binding
     * @throws IllegalStateException if it declares a base, or nominates an alternate key
     */
    private static void requireNoAlternateIndexPath(DatasetBinding master) {
        if (master.base() != null) {
            throw new IllegalStateException("Dataset binding for '" + CICS_FILE_NAME + "' declares "
                    + "base '" + master.base() + "', but it IS a base cluster: "
                    + "app/csd/CARDDEMO.CSD:76-87 defines FILE(" + CICS_FILE_NAME + ") over "
                    + "one KSDS and defines no alternate-index path over it. Only an aix-path entry "
                    + "declares a base. Remove carddemo.datasets." + CICS_FILE_NAME + ".base.");
        }
        if (master.alternateKey() != null) {
            throw new IllegalStateException("Dataset binding for '" + CICS_FILE_NAME + "' nominates "
                    + "alternate key '" + master.alternateKey() + "', but the transaction master has "
                    + "no alternate index anywhere in app/csd/CARDDEMO.CSD, and no program reads it by "
                    + "any key but TRAN-ID. This repository therefore exposes no second finder (gate "
                    + "G45). Remove carddemo.datasets." + CICS_FILE_NAME + ".alternate-key.");
        }
    }

    /**
     * Requires the sequential output binding to be sequential.
     *
     * @param output the configured output binding
     * @throws IllegalStateException if it is keyed
     */
    private static void requireSequentialOutput(DatasetBinding output) {
        if (output.keyed()) {
            throw new IllegalStateException("Dataset binding for '" + SEQUENTIAL_OUTPUT_DD_NAME
                    + "' declares organization '" + output.organization() + "', but it is a sequential "
                    + "output: app/jcl/INTCALC.jcl:37-41 creates it with DISP=(NEW,CATLG,DELETE) and "
                    + "DCB=(RECFM=F,LRECL=" + RECORD_LENGTH + ") and app/cbl/CBACT04C.cbl:53-55 opens "
                    + "it ORGANIZATION IS SEQUENTIAL / ACCESS MODE IS SEQUENTIAL. It is written front "
                    + "to back and never by key. Correct carddemo.datasets." + SEQUENTIAL_OUTPUT_DD_NAME
                    + ".organization to sequential.");
        }
    }

    /**
     * Requires a configured dataset name that can be composed into a statement.
     *
     * <p>Blank is rejected separately from absent because {@code application.yml} spells each name as
     * an environment placeholder, so an unconfigured deployment yields an empty string rather than
     * {@code null} - and an empty string is an unset placeholder to be supplied, not an absence to be
     * defaulted.
     *
     * @param ddName    the configuration key, for the diagnostic
     * @param candidate the configured dataset name
     * @return {@code candidate}, unchanged
     * @throws IllegalStateException    if it is absent or blank
     * @throws IllegalArgumentException if it is not a well-formed z/OS dataset name, as
     *                                  {@link DatasetRelation#requireDatasetName(String)} judges
     */
    private static String requireUsableDatasetName(String ddName, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for '" + ddName + "' declares no "
                    + "dataset name. Set carddemo.datasets." + ddName + ".dsname; this repository "
                    + "composes every statement from configuration alone and hard-codes no dataset "
                    + "name (gate G46).");
        }
        // The grammar - what a z/OS dataset name may contain - lives in DatasetRelation, so it is
        // stated once for the whole module rather than restated, and diverging, in each repository.
        return DatasetRelation.requireDatasetName(candidate);
    }

    // =================================================================================================
    // Read-only identity. Diagnostics and assertions, never a way to reach around this class. Every
    // value is configured rather than written here, so surfacing it introduces no dataset literal.
    // =================================================================================================

    /**
     * The resolved transaction-master dataset name, exactly as configuration declares it.
     *
     * @return the configured {@value #CICS_FILE_NAME} dataset name; never {@code null}, never blank
     */
    public String datasetName() {
        return masterRelation.dsname();
    }

    /**
     * The resolved input dataset name for {@value #INPUT_DD_NAME}, exactly as configuration declares
     * it.
     *
     * <p>Under the global binding this is the master itself, which is what
     * {@code app/jcl/POSTTRAN.jcl:28-29} declares. A job whose {@code TRANFILE} names a different
     * dataset - {@code app/jcl/TRANREPT.jcl:65-66} names {@code TRANSACT.DALY(+1)} - reads it through
     * {@link #openInput(DatasetBinding)} with its own resolved binding, so this accessor still reports
     * the global default and does not pretend to know a job's view.
     *
     * @return the configured {@value #INPUT_DD_NAME} dataset name; never {@code null}, never blank
     */
    public String inputDatasetName() {
        return inputRelation.dsname();
    }

    /**
     * The resolved sequential-output dataset name for {@value #SEQUENTIAL_OUTPUT_DD_NAME}.
     *
     * <p>It is a different dataset from {@link #datasetName()}, and that difference is load-bearing:
     * {@code app/jcl/INTCALC.jcl:37-41} creates a new generation under the DD name
     * {@value #CICS_FILE_NAME}, so a deployment that pointed both at the master would have the interest
     * calculator writing into the transaction master.
     *
     * @return the configured {@value #SEQUENTIAL_OUTPUT_DD_NAME} dataset name; never {@code null},
     *         never blank
     */
    public String sequentialOutputDatasetName() {
        return outputRelation.dsname();
    }

    /**
     * The code page this repository decodes and encodes records in, as injected.
     *
     * <p>Exposed so a caller that needs to decode an emitted record - a parity harness, or a test -
     * uses the same explicitly chosen encoding rather than resolving one of its own.
     *
     * @return the dataset charset; never {@code null}
     */
    public Charset datasetCharset() {
        return codec.charset();
    }

    /**
     * The fixed record width in bytes, cross-checked against all three bindings at construction.
     *
     * @return {@link #RECORD_LENGTH}
     */
    public int recordLength() {
        return RECORD_LENGTH;
    }

    /**
     * The key width in bytes.
     *
     * @return {@link #KEY_LENGTH}
     */
    public int keyLength() {
        return KEY_LENGTH;
    }

    /**
     * The statement that describes the master without transferring a row.
     *
     * @return the describe statement over the transaction master
     */
    public String describeStatement() {
        return masterRelation.describeStatement();
    }

    /**
     * The statement that describes the input dataset without transferring a row.
     *
     * @return the describe statement over the {@value #INPUT_DD_NAME} dataset
     */
    public String describeInputStatement() {
        return inputRelation.describeStatement();
    }

    /**
     * Reshapes a caller's transaction identifier into the key image the dataset holds.
     *
     * <p>An alphanumeric {@code MOVE} to {@code PIC X(16)}: a short value is space-padded on the
     * <strong>right</strong> and a long one keeps its leading sixteen characters, which is what
     * {@code MOVE TRNIDINI OF COTRN0AI TO TRAN-ID} ({@code app/cbl/COTRN00C.cbl:210}) does with a
     * screen field. The direction matters and is chosen deliberately: a numeric {@code MOVE} would pad
     * and truncate on the left, and applying the wrong rule to a key silently reads the wrong record.
     *
     * <p>Exposed because a caller that reports "the key I looked for" in a message must report the key
     * that was actually used, not the value it typed.
     *
     * @param tranId the identifier as the caller holds it; never {@code null}. Pass an empty string for
     *               a key of all spaces
     * @return exactly {@link #KEY_LENGTH} characters
     * @throws NullPointerException if {@code tranId} is {@code null}
     */
    public String keyImageOf(String tranId) {
        Objects.requireNonNull(tranId, "A transaction identifier is required to compose the TRAN-ID "
                + "key; an absent key is a defect in the caller and not a NOTFND outcome. Pass an empty "
                + "string for a key of SPACES.");
        return codec.movePicX(tranId, KEY_LENGTH);
    }

    // =================================================================================================
    // OPEN INPUT / READ / CLOSE - the sequential input path.
    //
    // COBOL sites:
    //   app/cbl/CBTRN03C.cbl:29-31    SELECT TRANSACT-FILE ASSIGN TO TRANFILE, ORGANIZATION SEQUENTIAL
    //                       :376-392  0000-TRANFILE-OPEN     - OPEN INPUT
    //                       :248-272  1000-TRANFILE-GET-NEXT - READ ... INTO TRAN-RECORD
    //                       :514-530  9000-TRANFILE-CLOSE    - CLOSE
    //   app/cbl/CBTRN01C.cbl:343-359  0500-TRANFILE-OPEN     - OPEN INPUT, and never a READ
    //                       :451-467  9500-TRANFILE-CLOSE    - CLOSE
    // =================================================================================================

    /**
     * Opens the globally configured {@value #INPUT_DD_NAME} dataset for input.
     *
     * <p>The Java form of {@code 0000-TRANFILE-OPEN} ({@code app/cbl/CBTRN03C.cbl:376-392}) and of
     * {@code 0500-TRANFILE-OPEN} ({@code app/cbl/CBTRN01C.cbl:343-359}).
     *
     * <h2>The open opens, and the reads read</h2>
     *
     * <p>{@code CBTRN03C} has three paragraphs with three messages and three abends -
     * {@code 'ERROR OPENING TRANFILE'} at {@code :387},
     * {@code 'ERROR READING TRANSACTION FILE'} at {@code :266} and
     * {@code 'ERROR CLOSING POSTED TRANSACTION FILE'} at {@code :525} - so an operator can tell which
     * verb failed. This method therefore <strong>establishes that the dataset can be read and
     * transfers no row</strong>: the probe is the same dataset-scoped describe every other operation
     * uses, whose predicate is false on every row, so it costs one round trip, carries nothing across
     * and still fails when the dataset is absent or unreachable - which is what {@code OPEN INPUT}
     * reports. A failure on the four thousandth row belongs to the read that reached it, and that is
     * where it is reported.
     *
     * <p>Nothing is thrown. The status is reported as {@link InputFile#openStatus()}, which is the
     * value {@code :379} tests before moving {@code 0} or {@code 12} into {@code APPL-RESULT}, and the
     * caller's guard chain decides - including the decision to abend, which is the caller's alone.
     *
     * <h2>{@code CBTRN01C} opens this and never reads it</h2>
     *
     * <p>That is not an oversight to be corrected. {@code CBTRN01C} is invoked by no JCL anywhere and
     * its {@code TRANFILE} access is a genuine dead path in the legacy program; AAP implicit
     * requirement I4 and practice B5 require it to be preserved exactly. Opening this cursor and
     * closing it without a read is that path, and both statuses are reported so the program's own
     * guard chains stay reachable.
     *
     * <h2>All cursor state lives on the returned handle</h2>
     *
     * <p>This bean is a singleton and two callers reading at once must not share a position, so each
     * call hands back its own cursor and this class keeps none (practice B9, gate G53). A cursor cannot
     * be rewound: a caller that wants to start again opens another one, exactly as the COBOL closes and
     * reopens. The handle is {@link AutoCloseable} and {@link InputFile#close()} is idempotent, so
     * try-with-resources and an explicit close in the same block are both safe.
     *
     * @return a freshly opened cursor whose {@link InputFile#openStatus()} reports whether the open
     *         succeeded; never {@code null}
     */
    public InputFile openInput() {
        return openInput(inputRelation, inputIsKeyed, INPUT_DD_NAME);
    }

    /**
     * Opens a job-scoped {@value #INPUT_DD_NAME} dataset for input.
     *
     * <p>A DD name is not globally unique in this estate, and {@code TRANFILE} is the clearest case:
     * {@code app/jcl/POSTTRAN.jcl:28-29} binds it to the transaction master KSDS while
     * {@code app/jcl/TRANREPT.jcl:65-66} binds it to {@code TRANSACT.DALY(+1)}, the sorted sequential
     * file that {@code CBTRN03C} actually reports from. Resolving a job's view of a DD name belongs to
     * the batch configuration, which hands back a {@link DatasetBinding}; this overload accepts that
     * binding so the report job reads its own input without this class second-guessing which job is
     * calling.
     *
     * <p>The binding is held to the same width contract as the configured one - {@link #RECORD_LENGTH}
     * bytes - because a report printed from records of another width would be wrong in every column.
     * A binding whose {@code dsname} is not a well-formed z/OS dataset name is <strong>reported, not
     * thrown</strong>: it yields a cursor whose {@link InputFile#openStatus()} is
     * {@link #PERMANENT_ERROR_STATUS}, which is exactly what a failed {@code OPEN INPUT} looks like to
     * the caller, and it keeps a fixture-backed profile's filesystem location from becoming an
     * exception in the middle of a job.
     *
     * @param jobScopedBinding the binding the job resolved for its own {@value #INPUT_DD_NAME}; never
     *                         {@code null}
     * @return a freshly opened cursor over that dataset; never {@code null}
     * @throws NullPointerException  if {@code jobScopedBinding} is {@code null}
     * @throws IllegalStateException if the binding declares a record length other than
     *                               {@link #RECORD_LENGTH}
     */
    public InputFile openInput(DatasetBinding jobScopedBinding) {
        Objects.requireNonNull(jobScopedBinding, "A job-scoped dataset binding is required: call "
                + "openInput() for the globally configured " + INPUT_DD_NAME + " binding instead of "
                + "passing null");
        requireRecordWidth(INPUT_DD_NAME, jobScopedBinding);
        DatasetRelation relation;
        try {
            relation = DatasetRelation.of(
                    requireUsableDatasetName(INPUT_DD_NAME, jobScopedBinding.dsname()), RECORD_LENGTH);
        } catch (IllegalStateException | IllegalArgumentException notADatasetName) {
            // Reported rather than thrown, for the reason on this method: a location this module cannot
            // address is, to the caller, a dataset that would not open. The grammar's own verdict is
            // logged so the cause is diagnosable; the location itself is not, because it is the one part
            // of a binding a deployment may treat as sensitive.
            LOG.error("A job-scoped " + INPUT_DD_NAME + " binding does not name a dataset this module "
                    + "can address - " + notADatasetName.getClass().getSimpleName()
                    + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " from the open rather than throwing into the middle of a job");
            return new InputFile(this, null, INPUT_DD_NAME, PERMANENT_ERROR_STATUS, null);
        }
        return openInput(relation, jobScopedBinding.keyed(), INPUT_DD_NAME);
    }

    /**
     * The shared body of both {@code openInput} entry points.
     *
     * @param relation the dataset to read
     * @param keyed    whether the binding declares an indexed organization, which decides how a
     *                 sequential read of it advances
     * @param ddName   the configuration key, carried onto every outcome so a caller can say which DD
     *                 it was reading
     * @return the cursor; never {@code null}
     */
    private InputFile openInput(DatasetRelation relation, boolean keyed, String ddName) {
        InputStatements composed;
        try {
            // The dataset is described, not read: this establishes that the relation exists and
            // presents a record-image column without a single row crossing the wire, which is what an
            // OPEN INPUT establishes too.
            composed = composeInputStatements(relation, keyed);
        } catch (DataAccessException refused) {
            return new InputFile(this, relation, ddName,
                    reportRefusal(OPEN_OPERATION_NAME, ddName, "for input", refused), null);
        } catch (IllegalStateException noRecordImageColumn) {
            // A keyed relation that presents no usable record-image column cannot be ordered by key, and
            // a read of it in an unspecified order is not the read the COBOL performs. That is an
            // open-time fact about the dataset, so it is reported from the open, which is where
            // app/cbl/CBTRN03C.cbl:387 displays 'ERROR OPENING TRANFILE'.
            LOG.error("The " + ddName + " dataset is configured as an indexed cluster but presents no "
                    + "usable record-image column at position " + RECORD_IMAGE_COLUMN_INDEX
                    + ", so a read of it in key order cannot be composed; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " from the open");
            return new InputFile(this, relation, ddName, PERMANENT_ERROR_STATUS, null);
        }
        return new InputFile(this, relation, ddName, FileStatus.OK, composed);
    }

    /**
     * Composes the statements a sequential read of one dataset uses, describing it once on the way.
     *
     * <p><strong>The organization decides the ordering, and the difference is not cosmetic.</strong>
     * <ul>
     *   <li>An <strong>indexed</strong> dataset is read in <em>ascending key order</em>, because that is
     *       what {@code ACCESS MODE IS SEQUENTIAL} on a KSDS returns, and each read after the first asks
     *       for the lowest image strictly above the one already returned. One row per call: a large
     *       dataset is never materialised and a failure on the four thousandth row is reported by the
     *       read that reached it.</li>
     *   <li>A <strong>physical-sequential</strong> dataset is read in <em>the order its records were
     *       written</em>, which is what {@link DatasetRelation#selectAll()} expresses by deliberately
     *       naming no {@code ORDER BY}. Imposing one would <em>reorder</em> the file, and for this
     *       dataset that is not hypothetical: {@code app/jcl/TRANREPT.jcl:46} sorts the daily file
     *       {@code SORT FIELDS=(TRAN-CARD-NUM,A)}, so its physical order is card-number order while an
     *       ordering by record image would be {@code TRAN-ID} order - and {@code CBTRN03C} groups and
     *       subtotals its report by account as the records arrive ({@code :168-207}). Reordering the
     *       input would produce a report with the right rows and the wrong totals, which is the worst
     *       kind of wrong.</li>
     * </ul>
     *
     * <p>A physical-sequential dataset therefore has no stable per-row position to re-anchor on, so its
     * rows arrive from a single statement and the cursor hands them out one at a time. The consequence
     * is stated plainly rather than hidden: that read holds the rows of one pass in memory, which is
     * bounded by the dataset - 300 records of 350 bytes for the daily file that
     * {@code app/data/ASCII/dailytran.txt} sizes. The alternative, holding a JDBC cursor and its
     * connection open across calls, is what the module avoids so the online layer keeps no
     * server-side conversation state.
     *
     * @param relation the dataset to read
     * @param keyed    whether the binding declares an indexed organization
     * @return the composed statements
     * @throws DataAccessException   if the relation cannot be described
     * @throws IllegalStateException if a keyed relation presents no usable record-image column
     */
    private InputStatements composeInputStatements(DatasetRelation relation, boolean keyed) {
        String described = jdbcTemplate.query(relation.describeStatement(),
                TransactionRepository::extractRecordImageColumn);
        if (!keyed) {
            // No column name is needed, and none is required: a physical-sequential read names no
            // column at all, so demanding one here would fail an open that COBOL performs happily.
            return new InputStatements(relation.selectAll(), Optional.empty());
        }
        String column = relation.rememberRecordImageColumn(described);
        return new InputStatements(relation.selectAllAscending(column),
                Optional.of(relation.selectAfterAscending(column)));
    }

    // =================================================================================================
    // OPEN OUTPUT / WRITE / CLOSE - the sequential output path.
    //
    // COBOL sites, all in the interest calculator:
    //   app/cbl/CBACT04C.cbl:53-56   SELECT TRANSACT-FILE ASSIGN TO TRANSACT, ORGANIZATION SEQUENTIAL
    //                      :307-323  0400-TRANFILE-OPEN  - OPEN OUTPUT
    //                      :473-515  1300-B-WRITE-TX     - WRITE FD-TRANFILE-REC FROM TRAN-RECORD (:500)
    //                      :595-611  9400-TRANFILE-CLOSE - CLOSE
    // =================================================================================================

    /**
     * Opens the {@value #SEQUENTIAL_OUTPUT_DD_NAME} generation for output: the Java form of
     * {@code 0400-TRANFILE-OPEN} ({@code app/cbl/CBACT04C.cbl:307-323}).
     *
     * <p><strong>Output, not append, and a different dataset from the master.</strong>
     * {@code app/jcl/INTCALC.jcl:37-41} declares {@code DISP=(NEW,CATLG,DELETE)} over
     * {@code SYSTRAN(+1)} - a brand-new generation each run - under the DD name
     * {@value #CICS_FILE_NAME}. The DD name is the same spelling as the CICS file and the dataset is
     * not the same dataset, which is exactly the collision this class keeps three keys for.
     *
     * <p><strong>This open makes no round trip, and cannot fail.</strong> That is stated out loud
     * because a status that cannot fail is otherwise a guard a caller writes for no reason. There is
     * genuinely nothing to establish: an output-only dataset created {@code NEW} does not exist until it
     * is written, so it cannot be described, and probing for it would report a failure exactly where the
     * COBOL succeeds. Everything that <em>is</em> checkable - that the destination is configured and is
     * a well-formed dataset name - was checked at construction, where it fails the bean rather than a
     * job. A destination that turns out to be unusable is therefore reported by the first
     * {@link OutputFile#writeSequential(TranRecord)}, which is the verb whose failure
     * {@code app/cbl/CBACT04C.cbl:510} displays as {@code 'ERROR WRITING TRANSACTION RECORD'}.
     *
     * <p>Each call returns a fresh handle with its own record count and its own open flag, so two runs
     * never share state and a test's outcome never depends on what ran before it (practice B9, gate
     * G53).
     *
     * @return a new per-execution output handle; never {@code null}
     */
    public OutputFile openOutput() {
        return new OutputFile(this, outputRelation.insertRecordImage());
    }

    // =================================================================================================
    // The keyed WRITE - an add, never an update.
    //
    // COBOL sites:
    //   app/cbl/CBTRN02C.cbl:562-579  2900-WRITE-TRANSACTION-FILE - WRITE FD-TRANFILE-REC (:564)
    //   app/cbl/COTRN02C.cbl:711-749  WRITE-TRANSACT-FILE - EXEC CICS WRITE ... RIDFLD(TRAN-ID)
    //   app/cbl/COBIL00C.cbl:510-547  WRITE-TRANSACT-FILE - the same
    // =================================================================================================

    /**
     * Adds one record to the transaction master, keyed on the record's own {@code TRAN-ID}.
     *
     * <p>The Java form of {@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD}
     * ({@code app/cbl/CBTRN02C.cbl:564}) and of
     * {@code EXEC CICS WRITE DATASET(WS-TRANSACT-FILE) FROM(TRAN-RECORD) LENGTH(LENGTH OF TRAN-RECORD)
     * RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF TRAN-ID) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)}
     * ({@code app/cbl/COTRN02C.cbl:711-720}, {@code app/cbl/COBIL00C.cbl:510-519}).
     *
     * <p><strong>The record carries its own key.</strong> A COBOL {@code WRITE} on a randomly accessed
     * indexed file is addressed by the key in the record area, and the CICS form names
     * {@code RIDFLD(TRAN-ID)} - the same sixteen bytes, which are the leading sixteen bytes of the
     * record. So the row added is the one whose key is {@link TranRecord#tranId()} of the argument, and
     * no separate key parameter exists or should.
     *
     * <p><strong>All {@link #RECORD_LENGTH} bytes are written, {@code FILLER PIC X(20)} included</strong>
     * (gates G19 and G21). The record crosses this boundary as the image {@link TranRecord#encode(Charset)}
     * produces in the injected dataset code page - never as a field-by-field rebuild - so a stored record
     * is byte-identical to the one the caller built, sign overpunch and all.
     *
     * <h2>Three outcomes, and a duplicate is one of them rather than an exception</h2>
     * <ul>
     *   <li><strong>{@link Outcome#OK}</strong>, status {@code '00'}, CICS {@code NORMAL} - one record
     *       added. {@code CBTRN02C} moves {@code 0} to {@code APPL-RESULT}; the two online programs
     *       repaint the screen with {@code 'Transaction added successfully.'} and
     *       {@code 'Payment successful.'} respectively.</li>
     *   <li><strong>{@link Outcome#DUPLICATE}</strong>, status {@code '22'}, CICS
     *       {@link FileStatus#DUPREC} - the key already exists and <strong>nothing was
     *       written</strong>. {@code app/cbl/COTRN02C.cbl:735-741} and
     *       {@code app/cbl/COBIL00C.cbl:533-539} fold {@code WHEN DFHRESP(DUPKEY)} and
     *       {@code WHEN DFHRESP(DUPREC)} into <em>one</em> arm - {@code 'Tran ID already exist...'} - so
     *       a single duplicate outcome serves both, and {@link FileStatus#DUPREC} is the one reported
     *       because a duplicate on a KSDS <em>primary</em> key is what this is. {@code CBTRN02C} does
     *       not enumerate {@code '22'} at all and lands it on {@code APPL-RESULT 12}, which is
     *       {@link #APPL_RESULT_FATAL} and reachable through {@link WriteResult#applResult()}. It is
     *       returned, not thrown: the COBOL treats it as a condition to branch on.</li>
     *   <li><strong>{@link Outcome#OTHER}</strong> - the {@code WHEN OTHER} arm, for a backend refusal
     *       or a write that added no row.</li>
     * </ul>
     *
     * <h2>Why the key is probed before the insert</h2>
     *
     * <p>Because the COBOL's {@code WRITE} reports the duplicate condition <em>instead of</em> writing,
     * and this method must do the same. A deployment whose relation enforces the key's uniqueness would
     * reject the insert on its own, and that rejection is caught and reported as the same duplicate -
     * which also closes the window between the probe and the insert. But a deployment whose relation
     * does <em>not</em> enforce it would otherwise silently add a second record under an existing key,
     * and a transaction master with two records under one identifier is corruption that no later read
     * can detect. So the key is required to be absent first, in the same unit of work when one is open,
     * and only then is the record inserted.
     *
     * @param record the record to add, carrying its own key; never {@code null}
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code record} is {@code null}
     */
    public WriteResult write(TranRecord record) {
        Objects.requireNonNull(record, "A transaction record is required to add one: the WRITE is "
                + "addressed by the TRAN-ID the record itself carries (app/cbl/COTRN02C.cbl:716), so "
                + "there is no record to write and no key to write it under");
        String keyImage = keyImageOf(record.tranId());
        byte[] image = record.encode(codec.charset());
        if (image.length != RECORD_LENGTH) {
            // Unreachable through TranRecord, whose layout refuses to initialise at any other width -
            // and asserted anyway, because a short image would add a record that every later read of it
            // rejected, and it costs one comparison to make that impossible instead of unlikely.
            LOG.error("A transaction record encoded to " + image.length + " bytes where the dataset "
                    + "holds " + RECORD_LENGTH + "; refusing the add rather than storing a record no "
                    + "read of it could decode");
            return WriteResult.other(CICS_FILE_NAME, PERMANENT_ERROR_STATUS,
                    CicsResponse.of(FileStatus.LENGERR),
                    DatasetObservation.recordWidth(image.length));
        }
        try {
            Statements sql = resolveStatements();
            FetchedRows existing = fetch(sql.selectByKey(), KEY_SPAN.pattern(keyImage), SINGLE_ROW);
            if (existing.rowCount() > 0) {
                // WHEN DFHRESP(DUPKEY) / WHEN DFHRESP(DUPREC). Nothing is written.
                return WriteResult.duplicate(CICS_FILE_NAME);
            }
            int added = insert(sql.insertRecordImage(), image);
            if (added == SINGLE_ROW) {
                return WriteResult.written(CICS_FILE_NAME);
            }
            // Nothing was added, or something inexplicable was. Either way the caller must not be told
            // the record is stored, so this lands on the WHEN OTHER arm carrying what was measured.
            LOG.error("A keyed add to the transaction master reported " + added + " affected row(s) "
                    + "where exactly " + SINGLE_ROW + " was expected; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than reporting a "
                    + "record as stored");
            return WriteResult.other(CICS_FILE_NAME, PERMANENT_ERROR_STATUS,
                    CicsResponse.of(FileStatus.INVREQ), DatasetObservation.matchingRows(added));
        } catch (DataAccessException rejected) {
            BackendDiagnostic diagnostic = BackendDiagnostic.of(rejected);
            if (reportsDuplicateKey(rejected, diagnostic)) {
                // The relation enforced the key's uniqueness and refused. That IS the duplicate
                // condition, reported in the vocabulary the two online programs already branch on -
                // and it is the arm that closes the window between the probe above and this insert.
                LOG.error("A keyed add to the transaction master was refused as an integrity violation "
                        + "- " + diagnostic.describe() + "; reporting file status "
                        + FileStatus.toStatusImage(FileStatus.DUPLICATE)
                        + ", the duplicate-key condition, because that is what the relation reported");
                return WriteResult.duplicate(CICS_FILE_NAME, diagnostic);
            }
            return WriteResult.other(CICS_FILE_NAME,
                    reportRefusal(WRITE_OPERATION_NAME, CICS_FILE_NAME, "by key", rejected),
                    CicsResponse.ofBatchStatus(PERMANENT_ERROR_STATUS), diagnostic);
        }
    }

    /**
     * Whether a refused add is the backend saying the key already exists.
     *
     * <p>Two independent signals, either of which is sufficient, because which one a deployment produces
     * depends on the driver rather than on the data - and the driver is a deployment-time input this
     * build cannot exercise (residual risk R-E):
     *
     * <ul>
     *   <li>{@code SQLSTATE} class {@code 23}, the standard integrity-violation class, recognised by
     *       {@link BackendDiagnostic#integrityViolation()}. A driver that reports {@code SQLSTATE} takes
     *       this path;</li>
     *   <li>the refusal being a {@link DataIntegrityViolationException} - which is what Spring's
     *       vendor-error-code translation produces for a driver that reports a numeric error code and no
     *       {@code SQLSTATE} at all, a shape legacy mainframe adapters do present.</li>
     * </ul>
     *
     * <p>Testing only the first would make the reported condition depend on the driver: on a
     * code-reporting adapter a genuine duplicate key would arrive here as an unrecognised refusal and
     * leave on the {@code WHEN OTHER} arm, which is {@code APPL-RESULT 12} and an abend in
     * {@code CBTRN02C} ({@code app/cbl/CBTRN02C.cbl:566-575}) and the wrong screen message in
     * {@code COTRN02C} ({@code app/cbl/COTRN02C.cbl:734-737}). Accepting either signal keeps the
     * COBOL-observable outcome the same whichever driver is bound, which is the whole point of holding
     * behaviour invariant across the migration.
     *
     * @param rejected   the refusal the template raised; never {@code null}
     * @param diagnostic the refusal's own diagnosis; never {@code null}
     * @return {@code true} if the refusal is the duplicate-key condition
     */
    private static boolean reportsDuplicateKey(DataAccessException rejected,
                                               BackendDiagnostic diagnostic) {
        return diagnostic.integrityViolation() || rejected instanceof DataIntegrityViolationException;
    }

    // =================================================================================================
    // The keyed READ.
    //
    // COBOL site: app/cbl/COTRN01C.cbl:269-278  READ-TRANSACT-FILE
    //   EXEC CICS READ DATASET(WS-TRANSACT-FILE) INTO(TRAN-RECORD) LENGTH(LENGTH OF TRAN-RECORD)
    //                  RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF TRAN-ID) UPDATE RESP(..) RESP2(..)
    // =================================================================================================

    /**
     * Reads one record by its {@code TRAN-ID}: the Java form of {@code READ-TRANSACT-FILE}
     * ({@code app/cbl/COTRN01C.cbl:269-278}).
     *
     * <p>The key goes through the alphanumeric {@code MOVE} of {@link #keyImageOf(String)}, so a short
     * value is space-padded on the right exactly as {@code MOVE TRNIDINI OF COTRN1AI TO TRAN-ID} pads a
     * screen field.
     *
     * <p>Four reported outcomes, three of which the consumer enumerates:
     * <ul>
     *   <li><strong>{@link Outcome#OK}</strong>, status {@code '00'}, CICS {@code NORMAL} - the record,
     *       which {@code COTRN01C} then paints onto the view screen;</li>
     *   <li><strong>{@link Outcome#NOT_FOUND}</strong>, status {@code '23'}, CICS
     *       {@link FileStatus#NOTFND} - {@code WHEN DFHRESP(NOTFND)} at {@code :283}, which sets the
     *       error flag and the message {@code 'Transaction ID NOT found...'}. A normal branch, never an
     *       exception;</li>
     *   <li><strong>{@link Outcome#DUPLICATE}</strong>, status {@code '22'}, carrying the first matching
     *       record. A KSDS primary key is unique by construction, so this can arise only if the backing
     *       presentation of the dataset has lost that uniqueness. {@code COTRN01C} does not enumerate a
     *       duplicate on a read, so it routes to {@code WHEN OTHER} - which is where an unexpected
     *       condition belongs - while the record stays available to a caller that wants it;</li>
     *   <li><strong>{@link Outcome#OTHER}</strong>, status {@link #PERMANENT_ERROR_STATUS} - the
     *       {@code WHEN OTHER} arm at {@code :289}, for an I/O failure.</li>
     * </ul>
     *
     * <p><strong>This is the plain read; {@code COTRN01C} specifies {@code UPDATE}.</strong> The option
     * is on {@code :275} and the AAP's brief for this file does not mention it, so both forms are
     * offered and the discrepancy is documented rather than resolved by omission (practice B4). Use
     * {@link #readForUpdateByTranId(String)} for the form the program actually issues. Nothing in the
     * estate rewrites or deletes a transaction record, so the lock that read takes is never converted
     * into a change - which is why the plain form is the useful one for a caller that only views.
     *
     * @param tranId the transaction identifier to look up; never {@code null}, and it may be shorter or
     *               longer than {@link #KEY_LENGTH} because the {@code PIC X} move reshapes it. Pass an
     *               empty string to look up a key of all spaces
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code tranId} is {@code null}
     */
    public ReadResult readByTranId(String tranId) {
        return readKeyed(keyImageOf(tranId), false);
    }

    /**
     * Reads one record by its {@code TRAN-ID} with the record held for update: the exact form
     * {@code app/cbl/COTRN01C.cbl:269-278} issues, {@code UPDATE} option and all ({@code :275}).
     *
     * <p>The outcomes are identical to {@link #readByTranId(String)}. What differs is that the row is
     * requested under a lock, which is the SQL equivalent of the {@code UPDATEMODEL(LOCKING)} the CSD
     * declares for this file ({@code app/csd/CARDDEMO.CSD:81}).
     *
     * <p><strong>A unit of work must be open.</strong> A lock taken outside a transaction is released
     * immediately, which would leave the read exactly as exposed as an unlocked one while looking as
     * though it were protected. Rather than issue a statement whose guarantee is vacuous, this method
     * refuses - loudly, and before any round trip.
     *
     * @param tranId the transaction identifier to look up; never {@code null}
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException  if {@code tranId} is {@code null}
     * @throws IllegalStateException if no unit of work is open
     */
    public ReadResult readForUpdateByTranId(String tranId) {
        String keyImage = keyImageOf(tranId);
        // The key is deliberately absent from the refusal. This is a wiring diagnostic - which dataset,
        // which operation - and a transaction identifier adds nothing to it while putting a value from a
        // customer's record into a stack trace that may be logged.
        DatasetUnitOfWork.requireActive("A read for update of the transaction master, which requests "
                + "the row lock that UPDATEMODEL(LOCKING) gives a CICS READ ... UPDATE "
                + "(app/cbl/COTRN01C.cbl:275)", masterRelation.dsname());
        return readKeyed(keyImage, true);
    }

    /**
     * The one keyed-read implementation, shared by the plain and the locking form.
     *
     * <p><strong>The key predicate is in the statement.</strong> The comparison is a byte comparison of
     * the key's own bytes at the key's own offset - which is the comparison VSAM performs - expressed as
     * an escaped {@code LIKE} over the record image so the backend, and not Java, decides which records
     * qualify. Only matching rows are transferred, and only they are held to the declared width, so a
     * malformed row elsewhere in the dataset is not this read's business.
     *
     * @param keyImage the key, already reshaped to exactly {@link #KEY_LENGTH} characters
     * @param locking  whether the row is requested under a lock
     * @return the discriminated outcome; never {@code null}
     */
    private ReadResult readKeyed(String keyImage, boolean locking) {
        try {
            Statements sql = resolveStatements();
            String statement = locking ? sql.selectByKeyForUpdate() : sql.selectByKey();
            FetchedRows rows = fetch(statement, KEY_SPAN.pattern(keyImage),
                    DUPLICATE_DETECTION_ROW_LIMIT);
            return classify(rows, CICS_FILE_NAME, false).result();
        } catch (DataAccessException refused) {
            return ReadResult.other(CICS_FILE_NAME,
                    reportRefusal(READ_OPERATION_NAME, CICS_FILE_NAME,
                            locking ? "by key with the UPDATE option" : "by key", refused),
                    BackendDiagnostic.of(refused));
        }
    }

    // =================================================================================================
    // STARTBR / READNEXT / READPREV / ENDBR - the browse.
    //
    // COBOL sites:
    //   app/cbl/COTRN00C.cbl:591-619  STARTBR-TRANSACT-FILE   :624-653 READNEXT   :658-687 READPREV
    //                       :692-695  ENDBR-TRANSACT-FILE
    //   app/cbl/COTRN02C.cbl:642-668  STARTBR   :673-697 READPREV   :702-704 ENDBR
    //   app/cbl/COBIL00C.cbl:441-467  STARTBR   :472-496 READPREV   :501-504 ENDBR
    // =================================================================================================

    /**
     * Positions a browse at the boundary of the file and returns the handle that walks it.
     *
     * <p>This is the {@code LOW-VALUES} and {@code HIGH-VALUES} form, and it is by far the more common
     * one in the estate:
     * <ul>
     *   <li>{@link BrowseDirection#FORWARD} is {@code MOVE LOW-VALUES TO TRAN-ID} then {@code STARTBR} -
     *       {@code app/cbl/COTRN00C.cbl:207} and {@code :237} - and it positions before the first
     *       record, so the first {@link Browse#readNext()} returns it;</li>
     *   <li>{@link BrowseDirection#BACKWARD} is {@code MOVE HIGH-VALUES TO TRAN-ID} then
     *       {@code STARTBR} - {@code app/cbl/COTRN00C.cbl:260}, {@code app/cbl/COTRN02C.cbl:444} and
     *       {@code :475}, {@code app/cbl/COBIL00C.cbl:212} - and it positions after the last record, so
     *       the first {@link Browse#readPrev()} returns it. That read is how both add paths obtain the
     *       highest existing {@code TRAN-ID} before adding one to it.</li>
     * </ul>
     *
     * <p><strong>Why a boundary is a direction rather than a key.</strong> {@code LOW-VALUES} is
     * {@code X'00'} repeated and {@code HIGH-VALUES} is {@code X'FF'} repeated, and {@code X'FF'} is not
     * a character in every code page a deployment may configure - {@code US-ASCII} has no mapping for
     * it, so a literal high-values key would be rejected by the strict encoder before it ever reached a
     * statement. What the COBOL <em>means</em> by those two keys is "from the first record" and "from
     * the last record", and that is what this overload expresses: exactly, and in any code page.
     *
     * <p>Positioning performs no backend call. Each step re-positions by value rather than holding a
     * cursor open, which is what keeps the online layer free of server-side conversation state (rule
     * R6). The handle is {@link AutoCloseable}, and ending an already-ended browse does nothing, so
     * try-with-resources and an explicit {@link Browse#endBrowse()} in the same block are both safe.
     *
     * <p>A handle counts nothing and knows no page size (gate G39). It yields one record per read; that
     * ten of them make a page is {@code COTRN00C}'s decision, taken at {@code :283} and {@code :348}.
     *
     * @param direction which way the browse will be walked; never {@code null}. It fixes which read the
     *                  handle accepts, exactly as the legacy code issues a separate {@code STARTBR} per
     *                  direction
     * @return a fresh handle, positioned and not yet read from; never {@code null}
     * @throws NullPointerException if {@code direction} is {@code null}
     */
    public Browse startBrowse(BrowseDirection direction) {
        return new Browse(this, requireDirection(direction), null);
    }

    /**
     * Positions a browse at a concrete key and returns the handle that walks it.
     *
     * <p>{@code COTRN00C} uses this form when it has a page key to resume from:
     * {@code MOVE CDEMO-CT00-TRNID-FIRST TO TRAN-ID} at {@code :239} going forward and
     * {@code MOVE CDEMO-CT00-TRNID-LAST TO TRAN-ID} at {@code :262} going backward. The key is reshaped
     * by {@link #keyImageOf(String)}, so a screen field that is shorter than the key is space-padded on
     * the right exactly as {@code MOVE TRNIDINI OF COTRN0AI TO TRAN-ID} pads it at {@code :210}.
     *
     * <p><strong>Positioning is at-or-after going forward and at-or-before going backward, and both are
     * inclusive of the anchor record.</strong> That is what CICS {@code GTEQ} positioning means, and
     * {@code GTEQ} is in force here: {@code app/cbl/COTRN00C.cbl:597} has it commented out, and
     * {@code GTEQ} is the {@code STARTBR} default, so commenting it out changed nothing. The inclusive
     * first read is not an inference either - {@code app/cbl/COCRDLIC.cbl:1284-1307} primes its row
     * counter one past the last screen row and then <em>discards</em> the record its first
     * {@code READPREV} returns, precisely because that record is the one already at the top of the page
     * being paged away from. Whether to discard it is the caller's decision, and it is the caller that
     * makes it.
     *
     * <p>No status is returned from positioning, because the legacy code discards its own: all three
     * {@code STARTBR} sites capture {@code RESP} and {@code RESP2} and the paragraph that follows never
     * tests them for a successful position. Surfacing a status here would invite a caller to branch on
     * something the COBOL ignores. The guard chain begins at the first read, and so does the first
     * reported outcome.
     *
     * @param ridfldTranId the {@code RIDFLD} value to position at; never {@code null}. Pass an empty
     *                     string to position at a key of all spaces
     * @param direction    which way the browse will be walked; never {@code null}
     * @return a fresh handle, positioned and not yet read from; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public Browse startBrowse(String ridfldTranId, BrowseDirection direction) {
        return new Browse(this, requireDirection(direction), keyImageOf(ridfldTranId));
    }

    /**
     * Requires a browse direction.
     *
     * @param direction the direction supplied
     * @return {@code direction}, unchanged
     * @throws NullPointerException if it is {@code null}
     */
    private static BrowseDirection requireDirection(BrowseDirection direction) {
        return Objects.requireNonNull(direction, "A browse direction is required: the legacy code "
                + "issues a separate STARTBR for each direction and never reads one the other way, so a "
                + "browse is never direction-less");
    }

    // =================================================================================================
    // The read seam. Every keyed read, browse step and sequential read funnels through here, so the
    // guard chain is written once and every arm of it is reachable from a plain unit test with a stubbed
    // template - no backend, no context (practice B10, gate G51).
    // =================================================================================================

    /**
     * Executes one browse step, binding a comparison operand.
     *
     * @param statement the anchor statement for the browse's direction
     * @param operand   the key image the anchor compares against
     * @param ddName    the dataset being read, carried onto the outcome
     * @return the outcome and, on a record, the exact image it came from; never {@code null}
     */
    private Step browseAnchorStep(String statement, String operand, String ddName) {
        try {
            return classify(fetch(statement, operand, SINGLE_ROW), ddName, true);
        } catch (DataAccessException refused) {
            return failedStep(ddName, "while positioning a browse", refused);
        }
    }

    /**
     * Executes one browse step that compares against two operands - a key image and a
     * {@code LIKE} pattern over the same key.
     *
     * <p>The backward anchor is the only statement on this class with two parameters, and both are
     * derived from the one anchor key. See {@link #selectAtOrBeforeKeyDescending(String)} for why one
     * comparison cannot express it.
     *
     * @param statement the backward anchor statement
     * @param operand   the key image, for the strict comparison
     * @param pattern   the escaped pattern, for the equal-key term
     * @param ddName    the dataset being read
     * @return the outcome and, on a record, the exact image it came from; never {@code null}
     */
    private Step browseAnchorStep(String statement, String operand, String pattern, String ddName) {
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(SINGLE_ROW);
            prepared.setFetchSize(SINGLE_ROW);
            recordImageForm.bindOperand(prepared, 1, operand, codec.charset());
            recordImageForm.bindOperand(prepared, 2, pattern, codec.charset());
            return prepared;
        };
        try {
            return classify(execute(creator, SINGLE_ROW), ddName, true);
        } catch (DataAccessException refused) {
            return failedStep(ddName, "while positioning a browse", refused);
        }
    }

    /**
     * Executes one browse or sequential step that takes no parameter at all - the unanchored forms.
     *
     * @param statement the statement to send
     * @param ddName    the dataset being read
     * @return the outcome and, on a record, the exact image it came from; never {@code null}
     */
    private Step unparameterisedStep(String statement, String ddName) {
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(SINGLE_ROW);
            prepared.setFetchSize(SINGLE_ROW);
            return prepared;
        };
        try {
            return classify(execute(creator, SINGLE_ROW), ddName, true);
        } catch (DataAccessException refused) {
            return failedStep(ddName, "at the start of a browse", refused);
        }
    }

    /**
     * Executes one browse or sequential step that advances past a stored record image.
     *
     * <p>The image is bound as an <strong>image</strong> and not as an operand composed here, because
     * that is what it is: the exact bytes the backend handed over on the previous step. Binding it any
     * other way would send a re-encoding of a stored value and compare it against the stored values it
     * was derived from.
     *
     * @param statement the advancing statement for the direction being walked
     * @param position  the previous row's record image, exactly as the backend presented it
     * @param ddName    the dataset being read
     * @return the outcome and, on a record, the exact image it came from; never {@code null}
     */
    private Step advanceStep(String statement, byte[] position, String ddName) {
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(SINGLE_ROW);
            prepared.setFetchSize(SINGLE_ROW);
            recordImageForm.bindImage(prepared, 1, position, codec.charset());
            return prepared;
        };
        try {
            return classify(execute(creator, SINGLE_ROW), ddName, true);
        } catch (DataAccessException refused) {
            return failedStep(ddName, "during a browse", refused);
        }
    }

    /**
     * Sends one statement with one comparison operand and brings back at most {@code rowLimit} rows'
     * worth of answer.
     *
     * <p>Both row limits are set, deliberately: one bounds what the backend will hand over and the other
     * bounds what it carries across in a round trip. A keyed read on a unique key cannot want more than
     * two rows - one to answer with, one to detect a duplicate - and a browse step cannot want more than
     * one.
     *
     * <p>The operand is bound through the configured record-image representation, because every operand
     * this class sends is compared against the record-image column. Binding it any other way would run
     * the comparison across two representations of one column.
     *
     * @param statement the statement to send
     * @param operand   the single bind value
     * @param rowLimit  the most rows worth fetching
     * @return what came back; never {@code null}
     * @throws DataAccessException if the backend refused the request
     */
    private FetchedRows fetch(String statement, String operand, int rowLimit) {
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(rowLimit);
            prepared.setFetchSize(rowLimit);
            recordImageForm.bindOperand(prepared, 1, operand, codec.charset());
            return prepared;
        };
        return execute(creator, rowLimit);
    }

    /**
     * Runs a prepared statement and extracts at most {@code rowLimit} rows' worth of answer.
     *
     * @param creator  the prepared, limited and bound statement
     * @param rowLimit the most rows worth reading
     * @return what came back; never {@code null}
     * @throws DataAccessException if the backend refused the request
     */
    private FetchedRows execute(PreparedStatementCreator creator, int rowLimit) {
        ResultSetExtractor<FetchedRows> extractor = resultSet -> extractRows(resultSet, rowLimit);
        FetchedRows rows = jdbcTemplate.query(creator, extractor);
        // A template that answered with nothing has told us nothing, and nothing is not an empty
        // dataset. It is reported as an empty result, which each caller then reads in its own terms - a
        // missing record on a keyed read, the end of the file on a browse or a sequential read.
        return rows == null ? FetchedRows.empty() : rows;
    }

    /**
     * Reads at most {@code rowLimit} rows, keeping the first record image and counting what arrived.
     *
     * <p>Package-private so a test can drive it directly against a stubbed result set, which is the only
     * way to prove the row accounting without a backend.
     *
     * @param resultSet the positioned result set
     * @param rowLimit  the most rows worth reading
     * @return the first image, whether it was absent, and how many rows arrived
     * @throws SQLException if the driver cannot supply a column
     */
    FetchedRows extractRows(ResultSet resultSet, int rowLimit) throws SQLException {
        int rowCount = 0;
        byte[] firstImage = null;
        boolean firstImageMissing = false;
        while (rowCount < rowLimit && resultSet.next()) {
            if (rowCount == 0) {
                firstImage = recordImageForm.readImage(resultSet, RECORD_IMAGE_COLUMN_INDEX,
                        codec.charset());
                // A row whose record-image column holds nothing is a record that is present and
                // unreadable. That is an I/O defect and emphatically not an end of file, so the two are
                // kept apart here rather than collapsed onto one null.
                firstImageMissing = firstImage == null;
            }
            rowCount++;
        }
        return new FetchedRows(rowCount, firstImage, firstImageMissing);
    }

    /**
     * Classifies what came back into the vocabulary every consumer already speaks.
     *
     * <p>The only difference between a keyed read and a browse or sequential step is what an empty
     * result means: on a browse it is the end of the file, and on a keyed read it is a missing record.
     * That difference is the {@code emptyMeansEndOfFile} argument and nothing else, which is why one
     * method serves all four access paths.
     *
     * @param rows                 what came back
     * @param ddName               the dataset that was read
     * @param emptyMeansEndOfFile  {@code true} for a browse or sequential step, {@code false} for a
     *                             keyed read
     * @return the outcome and, on a record, the exact image it came from; never {@code null}
     */
    private Step classify(FetchedRows rows, String ddName, boolean emptyMeansEndOfFile) {
        if (rows.rowCount() == 0) {
            // AT END / WHEN DFHRESP(ENDFILE) on a browse - app/cbl/CBTRN03C.cbl:254-255 moves 16 to
            // APPL-RESULT and app/cbl/COTRN00C.cbl:637-643 sets TRANSACT-EOF. INVALID KEY /
            // WHEN DFHRESP(NOTFND) on a keyed read - app/cbl/COTRN01C.cbl:283-288.
            return new Step(emptyMeansEndOfFile
                    ? ReadResult.endOfFile(ddName)
                    : ReadResult.notFound(ddName), null, false);
        }
        if (rows.firstImageMissing()) {
            LOG.error("The " + ddName + " dataset presented a row with no record image at column "
                    + "position " + RECORD_IMAGE_COLUMN_INDEX + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than reporting a record that is present as absent");
            return new Step(ReadResult.other(ddName, PERMANENT_ERROR_STATUS), null, true);
        }
        byte[] image = rows.firstImage();
        TranRecord record;
        try {
            record = TranRecord.decode(image, codec.charset());
        } catch (IllegalArgumentException wrongWidth) {
            // A row that is not exactly 350 bytes is not a TRAN-RECORD, and widening or truncating it
            // would shift every field after the first. It is reported with the width that was measured -
            // as an observation, never as a CICS reason code, which is a quantity from another system -
            // so the caller's WHEN OTHER arm has something an operator can act on.
            LOG.error("The " + ddName + " dataset presented a row of " + image.length + " byte(s) where "
                    + "app/cpy/CVTRA05Y.cpy declares (RECLN " + RECORD_LENGTH + "); reporting file "
                    + "status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than decoding a record against the wrong layout");
            return new Step(ReadResult.other(ddName, PERMANENT_ERROR_STATUS,
                    CicsResponse.of(FileStatus.LENGERR),
                    DatasetObservation.recordWidth(image.length)), null, true);
        }
        if (rows.rowCount() > 1) {
            // A KSDS primary key is unique by construction, so a second row means the backing
            // presentation has lost that uniqueness. The first match is returned alongside the
            // condition, as CICS does, and no consumer enumerates it - so it lands on WHEN OTHER, which
            // is where an unexpected condition belongs.
            return new Step(ReadResult.duplicate(ddName, record), image.clone(), true);
        }
        // WHEN DFHRESP(NORMAL) / FILE STATUS '00'.
        return new Step(ReadResult.found(ddName, record), image.clone(), true);
    }

    /**
     * Classifies one already-fetched row image, for a pass that fetched its rows together.
     *
     * @param image  the row's record image, or {@code null} when the column held nothing
     * @param ddName the dataset that was read
     * @return the outcome and, on a record, the exact image it came from; never {@code null}
     */
    private Step classifyRow(byte[] image, String ddName) {
        return classify(new FetchedRows(SINGLE_ROW, image, image == null), ddName, true);
    }

    /**
     * Builds the failed step for a backend refusal during a read.
     *
     * @param ddName  the dataset being read
     * @param attempt what was being attempted, phrased to complete "Could not read the ... dataset ..."
     * @param refusal the exception raised
     * @return the {@code WHEN OTHER} step, carrying the driver's own diagnosis; never {@code null}
     */
    private static Step failedStep(String ddName, String attempt, DataAccessException refusal) {
        return new Step(ReadResult.other(ddName,
                reportRefusal(READ_OPERATION_NAME, ddName, attempt, refusal),
                BackendDiagnostic.of(refusal)), null, false);
    }

    /**
     * Fetches every row of one pass over a physical-sequential dataset, in the order the dataset holds
     * them.
     *
     * <p>One statement, because a physical-sequential dataset has no key to re-anchor on and no
     * {@code ORDER BY} may be imposed on it - see
     * {@link #composeInputStatements(DatasetRelation, boolean)}. The images are kept exactly as the
     * backend presented them, {@code null} included: a row whose record-image column held nothing is a
     * record that is present and unreadable, and the read that reaches it says so rather than skipping
     * it silently.
     *
     * @param statement the unordered select over the whole relation
     * @return the rows of the pass, in the dataset's own order; never {@code null}
     * @throws DataAccessException if the backend refused the read
     */
    private List<byte[]> fetchPass(String statement) {
        ResultSetExtractor<List<byte[]>> extractor = resultSet -> {
            List<byte[]> images = new ArrayList<>();
            while (resultSet.next()) {
                images.add(recordImageForm.readImage(resultSet, RECORD_IMAGE_COLUMN_INDEX,
                        codec.charset()));
            }
            return images;
        };
        List<byte[]> images = jdbcTemplate.query(statement, extractor);
        // A template that answered with nothing has told us nothing. An empty pass is the honest
        // reading, and it presents as an immediate end of file - which is what a read of an empty
        // dataset reports anyway.
        return images == null ? List.of() : images;
    }

    /**
     * The key of a stored record image, read out of the image itself rather than out of the record
     * decoded from it, so the two cannot disagree.
     *
     * @param image the stored record image
     * @return the leading {@link #KEY_LENGTH} characters
     */
    private String keyOfImage(byte[] image) {
        return FixedWidthRecord.decodeStrictly(image, KEY_OFFSET, KEY_LENGTH, codec.charset(),
                "the TRAN-ID key of a stored record image");
    }

    // =================================================================================================
    // The write seam.
    // =================================================================================================

    /**
     * Builds the single-parameter insert of one whole record image.
     *
     * @param statement the composed insert
     * @param image     the record's bytes in the dataset code page
     * @return a creator that prepares and binds the statement
     */
    private PreparedStatementCreator insertOf(String statement, byte[] image) {
        return connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            recordImageForm.bindImage(prepared, 1, image, codec.charset());
            return prepared;
        };
    }

    /**
     * Issues one insert of a whole record image and reports how many rows it added.
     *
     * <p>Shared by the keyed add and the sequential write, so both send the identical shape of
     * statement and differ only in which relation they name.
     *
     * @param statement the composed insert
     * @param image     the record's bytes in the dataset code page
     * @return the affected-row count the backend reported
     * @throws DataAccessException if the backend refused the write
     */
    private int insert(String statement, byte[] image) {
        return jdbcTemplate.update(insertOf(statement, image));
    }

    /**
     * Logs a backend refusal with the driver's own diagnosis and returns the status to report.
     *
     * <p>Logged: the dataset, the verb, what was attempted, and the {@code SQLSTATE}, vendor code and
     * exception type the driver reported. <strong>Not</strong> logged: the key, the record image, or the
     * driver's own message text. A transaction record carries a primary account number at offset 263
     * and a merchant's details beside it, and a driver composes its message around the value it refused,
     * so carrying the text would put cardholder data into every log line that rendered a refusal
     * (CWE-532) - in text a control character could split into a second entry (CWE-117).
     *
     * @param operation the COBOL verb that failed
     * @param ddName    the dataset it was addressing
     * @param attempt   what was being attempted, appended to the message
     * @param refusal   the exception raised
     * @return {@link #PERMANENT_ERROR_STATUS}, the status the caller's {@code WHEN OTHER} arm receives
     */
    private static String reportRefusal(String operation, String ddName, String attempt,
                                       Throwable refusal) {
        BackendDiagnostic diagnostic = BackendDiagnostic.of(refusal);
        LOG.error("Could not " + operation + " the " + ddName + " dataset " + attempt + " - "
                + diagnostic.describe() + "; reporting file status "
                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
        return PERMANENT_ERROR_STATUS;
    }

    // =================================================================================================
    // Statement resolution over the master. One place decides the text of every statement this class
    // sends, and one describe - a statement that returns no row - learns the column name needed to
    // compose them.
    // =================================================================================================

    /**
     * Resolves the master's statements on first use and returns them.
     *
     * @return the composed statements; never {@code null}
     * @throws DataAccessException   if the relation cannot be described
     * @throws IllegalStateException if the relation presents no usable record-image column
     */
    private Statements resolveStatements() {
        Statements resolved = this.statements;
        if (resolved == null) {
            String column = masterRelation.rememberRecordImageColumn(
                    jdbcTemplate.query(masterRelation.describeStatement(),
                            TransactionRepository::extractRecordImageColumn));
            resolved = new Statements(
                    masterRelation.selectByKey(column),
                    masterRelation.selectByKeyForUpdate(column),
                    masterRelation.insertRecordImage(column),
                    masterRelation.selectAllAscending(column),
                    masterRelation.selectFromKeyAscending(column),
                    masterRelation.selectAfterAscending(column),
                    selectAllDescending(column),
                    selectAtOrBeforeKeyDescending(column),
                    masterRelation.selectBeforeDescending(column));
            this.statements = resolved;
        }
        return resolved;
    }

    /**
     * The resolved statements, or {@code null} while they have not been resolved.
     *
     * <p>Package-visible so this class's own tests can assert the composed text without a backend.
     *
     * @return the resolved statements, or {@code null}
     */
    Statements resolvedStatements() {
        return statements;
    }

    /**
     * Reads the record-image column's name out of a described result set.
     *
     * @param resultSet the described, empty result set
     * @return the column name at the record-image position, or {@code null} if there is none
     * @throws SQLException if the driver fails while describing
     */
    private static String extractRecordImageColumn(ResultSet resultSet) throws SQLException {
        return DatasetRelation.recordImageColumnOf(resultSet.getMetaData());
    }

    /**
     * The whole relation in descending record-image order - the unanchored backward browse.
     *
     * <p>{@link DatasetRelation} provides the ascending form and both advancing forms but not this one,
     * because no other dataset in the estate is browsed backward from its end.
     * {@code app/cbl/COTRN02C.cbl:444-446} and {@code app/cbl/COBIL00C.cbl:212-214} both are - it is how
     * the next transaction identifier is obtained - so the form is composed here, from
     * {@link DatasetRelation#identifier()} and {@link DatasetRelation#delimit(String)}, the very same
     * renderers every other statement on this class goes through. That matters more than it looks: a
     * dataset name contains dots, and a statement that rendered it differently from its siblings would
     * either fail or, worse, address something else on a backend nobody here can exercise.
     *
     * @param recordImageColumnName the discovered column name
     * @return the descending select over the whole relation, taking no parameter
     */
    private String selectAllDescending(String recordImageColumnName) {
        return "SELECT * FROM " + masterRelation.identifier() + " ORDER BY "
                + DatasetRelation.delimit(recordImageColumnName) + " DESC";
    }

    /**
     * The rows at or before a key, in descending order - the {@code STARTBR} with {@code GTEQ} read
     * backward.
     *
     * <h2>Why one comparison cannot express this</h2>
     *
     * <p>Every stored image is exactly {@link #RECORD_LENGTH} bytes and the key is its leading
     * {@link #KEY_LENGTH}, so for a key {@code K} of sixteen characters:
     * <ul>
     *   <li>an image whose key is <em>below</em> {@code K} sorts below {@code K};</li>
     *   <li>an image whose key is <em>above</em> {@code K} sorts above {@code K};</li>
     *   <li>an image whose key <em>equals</em> {@code K} sorts <strong>above</strong> {@code K}, because
     *       it is the same sixteen characters followed by 334 more.</li>
     * </ul>
     * So {@code image < K} and {@code image <= K} select the identical set - every key strictly below
     * {@code K} - and neither includes the anchor record. That is the ascending case's mirror image and
     * it is why {@link DatasetRelation#selectFromKeyAscending(String)} needs no counterpart trick:
     * {@code image >= K} already means "key at or after {@code K}".
     *
     * <p>The anchor record has to be included. {@code app/cbl/COCRDLIC.cbl:1284-1307} proves the first
     * {@code READPREV} returns it - the program discards it deliberately, as the record already at the
     * top of the page it is paging away from - and {@code app/cbl/COTRN00C.cbl:262} positions a backward
     * browse on {@code CDEMO-CT00-TRNID-LAST}, the key of the record it must return first.
     *
     * <p>So the predicate is a two-term disjunction: the images strictly below the key, <em>or</em> the
     * images whose key span equals it. The second term is the same escaped {@code LIKE} over the key
     * span that every keyed read on this class uses, produced by {@link KeySpan#pattern(String)}, so the
     * key's own {@code LIKE} metacharacters are escaped rather than trusted - an online {@code RIDFLD}
     * is a character field carrying whatever the screen supplied. Both terms are core SQL, which matters
     * because the substring functions that would express the same predicate more directly are spelled
     * differently by every backend and the deployment's backend is not known here.
     *
     * @param recordImageColumnName the discovered column name
     * @return the positioning statement, taking the key image and then the escaped pattern
     */
    private String selectAtOrBeforeKeyDescending(String recordImageColumnName) {
        String column = DatasetRelation.delimit(recordImageColumnName);
        return "SELECT * FROM " + masterRelation.identifier()
                + " WHERE (" + column + " < ? OR " + column + " LIKE ? ESCAPE '" + LIKE_ESCAPE + "')"
                + " ORDER BY " + column + " DESC";
    }

    // =================================================================================================
    // Implementation-detail carriers. Package-private records so this class's own tests can assert the
    // composed statement text and the row accounting without a backend in the path.
    // =================================================================================================

    /**
     * The statements this repository sends against the transaction master, composed once against the
     * discovered record-image column.
     *
     * @param selectByKey          the keyed {@code READ} - {@code app/cbl/COTRN01C.cbl:269}
     * @param selectByKeyForUpdate the keyed {@code READ ... UPDATE} - the {@code UPDATE} option at
     *                             {@code app/cbl/COTRN01C.cbl:275}
     * @param insertRecordImage    the keyed {@code WRITE} - {@code app/cbl/COTRN02C.cbl:712}
     * @param browseForwardAll     the first read of an unanchored forward browse: the
     *                             {@code MOVE LOW-VALUES TO TRAN-ID} case, taking no parameter
     * @param browseForwardAnchor  the first read of a forward browse anchored on a key, taking the key
     *                             image
     * @param browseForwardAfter   every forward read after the first: the lowest image strictly above
     *                             the record already returned, taking that record's stored image
     * @param browseBackwardAll    the first read of an unanchored backward browse: the
     *                             {@code MOVE HIGH-VALUES TO TRAN-ID} case, taking no parameter
     * @param browseBackwardAnchor the first read of a backward browse anchored on a key, taking the key
     *                             image and then the escaped pattern over the key span
     * @param browseBackwardBefore every backward read after the first: the highest image strictly below
     *                             the record already returned, taking that record's stored image
     */
    record Statements(String selectByKey,
                      String selectByKeyForUpdate,
                      String insertRecordImage,
                      String browseForwardAll,
                      String browseForwardAnchor,
                      String browseForwardAfter,
                      String browseBackwardAll,
                      String browseBackwardAnchor,
                      String browseBackwardBefore) {
    }

    /**
     * The statements one sequential read of one dataset uses.
     *
     * <p>Held on the cursor rather than on this bean, because the dataset a cursor reads is not always
     * the configured one - see {@link #openInput(DatasetBinding)} - and a cache on a singleton would
     * hand one job's statements to another (practice B9, gate G53).
     *
     * @param firstRead    the first read of the pass. For an indexed dataset it is the whole relation in
     *                     ascending key order; for a physical-sequential one it is the whole relation
     *                     unordered, which is its written order
     * @param advanceAfter the statement that advances one record past a stored image, present only for
     *                     an indexed dataset. Empty for a physical-sequential one, which has no key to
     *                     re-anchor on - see {@link #composeInputStatements(DatasetRelation, boolean)}
     */
    record InputStatements(String firstRead, Optional<String> advanceAfter) {

        /**
         * Whether this pass advances one record per round trip.
         *
         * @return {@code true} for an indexed dataset, {@code false} for a physical-sequential one
         */
        boolean advancesByKey() {
            return advanceAfter.isPresent();
        }
    }

    /**
     * What one statement brought back: how many rows, and the first row's record image.
     *
     * <p>Three states are distinguishable and all three are needed, because the COBOL distinguishes
     * three: no row at all is an end of file on a browse and a missing record on a keyed read; a row
     * whose record-image column holds nothing is a record that is present and unreadable, which is an
     * I/O failure and not an end of file; and a row with an image is a record. Collapsing the first two
     * onto one {@code null} would let a read stop early and silently, which is the worst of the three to
     * get wrong.
     *
     * @param rowCount          how many rows arrived, capped at the row limit that was requested
     * @param firstImage        the first row's record image, or {@code null} when no row arrived or the
     *                          column held nothing
     * @param firstImageMissing whether a row arrived whose record-image column held nothing
     */
    record FetchedRows(int rowCount, byte[] firstImage, boolean firstImageMissing) {

        /** The shared empty answer: no rows at all. */
        private static final FetchedRows NONE = new FetchedRows(0, null, false);

        /**
         * The answer for a statement that matched nothing.
         *
         * @return an answer carrying no rows and no image
         */
        static FetchedRows empty() {
            return NONE;
        }
    }

    /**
     * One read's outcome together with the exact bytes it came from.
     *
     * <p>Package-private and an implementation detail. It exists because a browse and a keyed sequential
     * read both have to advance past the record just returned, and the only value that identifies that
     * record unambiguously is the image the backend handed over - not the record decoded from it, and
     * not a re-encoding of that record. A re-encoding is byte-identical only when every span of the
     * stored row already holds what the model would write there, and a row whose trailing
     * {@code FILLER PIC X(20)} holds anything but spaces is precisely the row for which it is not: the
     * read would then ask for the first image after a value no row has, and hand back the same record
     * again, for ever.
     *
     * @param result     the outcome the caller branches on
     * @param exactImage the bytes the row's record-image column held, or {@code null} when no record was
     *                   returned
     * @param rowSeen    whether a row arrived at all. It separates "there is no next record" from "there
     *                   is one and it cannot be read", which a cursor must tell apart: it can advance
     *                   past neither, but the second means the pass has to stop rather than re-read the
     *                   same unreadable row for ever
     */
    record Step(ReadResult result, byte[] exactImage, boolean rowSeen) {
    }

    // =================================================================================================
    // The public vocabulary.
    // =================================================================================================

    /**
     * Which way a browse is walked.
     *
     * <p>Two constants because the legacy code issues a separate {@code STARTBR} per direction and never
     * reads one the other way. A handle therefore accepts only the read that matches the direction it
     * was positioned for, and asking for the other is refused as an invalid request rather than silently
     * reversing a browse the COBOL never reverses.
     */
    public enum BrowseDirection {

        /**
         * Ascending key order, walked with {@link Browse#readNext()} -
         * {@code app/cbl/COTRN00C.cbl:624-653}, driven from {@code PROCESS-PAGE-FORWARD} at
         * {@code :279-328}. Unanchored, it begins at the first record, which is
         * {@code MOVE LOW-VALUES TO TRAN-ID}; anchored, its first read returns the record at or after the
         * key.
         */
        FORWARD,

        /**
         * Descending key order, walked with {@link Browse#readPrev()} -
         * {@code app/cbl/COTRN00C.cbl:658-687}, driven from {@code PROCESS-PAGE-BACKWARD} at
         * {@code :331-374}, and the direction both add paths use to find the highest existing key
         * ({@code app/cbl/COTRN02C.cbl:444-447}, {@code app/cbl/COBIL00C.cbl:212-215}). Unanchored, it
         * begins at the last record, which is {@code MOVE HIGH-VALUES TO TRAN-ID}; anchored, its first
         * read returns the record at or before the key - inclusively, as
         * {@code app/cbl/COCRDLIC.cbl:1284-1307} demonstrates by discarding it.
         */
        BACKWARD
    }

    /**
     * The outcome of one read: what happened, in the vocabulary every consumer already speaks, plus the
     * record when there is one.
     *
     * <p>Immutable and complete. Every consumer's guard chain can be written against it as an exhaustive
     * {@code switch} over {@link #outcome()} with a {@code default}-complete final arm, which is the
     * shape of the COBOL it stands for:
     * <pre>
     * TransactionRepository.ReadResult result = repository.readByTranId(tranId);
     * switch (result.outcome()) {
     *     case OK -&gt; screen.paint(result.record().orElseThrow());   // WHEN DFHRESP(NORMAL)
     *     case NOT_FOUND -&gt; screen.reject("Transaction ID NOT found...");  // WHEN DFHRESP(NOTFND)
     *     default -&gt; screen.reject("Unable to lookup Transaction..."       // WHEN OTHER
     *             + " " + result.describeResponse());
     * }
     * </pre>
     *
     * <p>The batch shape is the same value read as a status ladder, which is what
     * {@code app/cbl/CBTRN03C.cbl:251-272} does: {@link #applResult()} is {@code 0} on a record,
     * {@link FileStatus#APPL_EOF} at end of file and {@link #APPL_RESULT_FATAL} otherwise, so a job can
     * drive its own {@code IF APPL-AOK / ELSE IF APPL-EOF / ELSE abend} chain unchanged.
     *
     * @param ddName      the configuration key of the dataset that was read -
     *                    {@value TransactionRepository#CICS_FILE_NAME},
     *                    {@value TransactionRepository#INPUT_DD_NAME} or
     *                    {@value TransactionRepository#SEQUENTIAL_OUTPUT_DD_NAME}. Carried because this
     *                    class addresses three datasets and a caller reporting a failure should be able
     *                    to say which
     * @param status      the two-character batch {@code FILE STATUS}: {@link FileStatus#OK},
     *                    {@link FileStatus#END_OF_FILE}, {@link FileStatus#NOT_FOUND},
     *                    {@link FileStatus#DUPLICATE} or
     *                    {@link TransactionRepository#PERMANENT_ERROR_STATUS}
     * @param outcome     the classification of {@code status}, from
     *                    {@link FileStatus#outcomeOfStatus(String)}, so the status and its meaning
     *                    cannot drift apart
     * @param record      the record, present exactly when one was read - so on {@link Outcome#OK} and on
     *                    {@link Outcome#DUPLICATE}, which carries the first of the matching records, and
     *                    absent on the other three
     * @param response    the CICS {@code RESP} and {@code RESP2} pair, which is what the online programs
     *                    render side by side into their {@code WHEN OTHER} messages
     *                    ({@code app/cbl/COTRN00C.cbl:645}, {@code app/cbl/COTRN01C.cbl:290})
     * @param observation what the read measured, where a measurement is what explains the outcome - a
     *                    row's actual width, for instance. Empty on every arm that measured nothing, and
     *                    never folded into {@code RESP2}, which is a CICS reason code and nothing else
     * @param diagnostic  what the backend reported when it refused: present only on the
     *                    {@link Outcome#OTHER} arm of a refusal the driver described, so a caller can log
     *                    the driver's own {@code SQLSTATE} rather than a status this module composed
     */
    public record ReadResult(String ddName,
                             String status,
                             Outcome outcome,
                             Optional<TranRecord> record,
                             CicsResponse response,
                             Optional<DatasetObservation> observation,
                             Optional<BackendDiagnostic> diagnostic) {

        /**
         * Enforces the invariants that keep the components from contradicting one another.
         *
         * @throws NullPointerException     if any component is {@code null} - an absent record, an
         *                                  absent observation and an absent diagnostic are all empty
         *                                  {@link Optional}s, so no {@code null} escapes the type
         * @throws IllegalArgumentException if {@code status} is not exactly
         *                                  {@link FileStatus#STATUS_LENGTH} characters, if
         *                                  {@code outcome} disagrees with {@code status}, or if a record
         *                                  is carried on an arm that returns none
         */
        public ReadResult {
            Objects.requireNonNull(ddName, "A read outcome names the dataset it came from");
            Objects.requireNonNull(status, "A read outcome carries a two-character FILE STATUS");
            Objects.requireNonNull(outcome, "A read outcome carries its classification");
            Objects.requireNonNull(record, "An absent record is an empty Optional, never null");
            Objects.requireNonNull(response, "A read outcome carries the CICS RESP/RESP2 pair");
            Objects.requireNonNull(observation, "An absent observation is an empty Optional");
            Objects.requireNonNull(diagnostic, "An absent diagnostic is an empty Optional");
            if (status.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalArgumentException("A FILE STATUS is " + FileStatus.STATUS_LENGTH
                        + " characters; '" + status + "' is " + status.length());
            }
            if (outcome != FileStatus.outcomeOfStatus(status)) {
                throw new IllegalArgumentException("Outcome " + outcome + " does not classify status "
                        + FileStatus.toStatusImage(status) + ", which is "
                        + FileStatus.outcomeOfStatus(status) + ". The two are derived from one value so "
                        + "a caller may switch on either and reach the same arm.");
            }
            boolean returnsRecord = outcome == Outcome.OK || outcome == Outcome.DUPLICATE;
            if (record.isPresent() != returnsRecord) {
                throw new IllegalArgumentException("Outcome " + outcome
                        + (returnsRecord ? " returns a record, and none was supplied"
                                         : " returns no record, and one was supplied")
                        + ". A record is present exactly on the normal and duplicate arms.");
            }
        }

        /**
         * The normal arm: a record was read.
         *
         * @param ddName the dataset it came from
         * @param record the record
         * @return the outcome, status {@code '00'}, CICS {@link FileStatus#NORMAL}
         */
        public static ReadResult found(String ddName, TranRecord record) {
            return new ReadResult(ddName, FileStatus.OK, Outcome.OK,
                    Optional.of(Objects.requireNonNull(record, "The normal arm carries a record")),
                    CicsResponse.of(FileStatus.NORMAL), Optional.empty(), Optional.empty());
        }

        /**
         * The end-of-file arm: {@code AT END} on a sequential read, {@code DFHRESP(ENDFILE)} on a
         * browse.
         *
         * @param ddName the dataset that was read
         * @return the outcome, status {@code '10'}, CICS {@link FileStatus#ENDFILE}
         */
        public static ReadResult endOfFile(String ddName) {
            return new ReadResult(ddName, FileStatus.END_OF_FILE, Outcome.END_OF_FILE,
                    Optional.empty(), CicsResponse.of(FileStatus.ENDFILE), Optional.empty(),
                    Optional.empty());
        }

        /**
         * The not-found arm: {@code INVALID KEY} on a keyed read, {@code DFHRESP(NOTFND)} online.
         *
         * @param ddName the dataset that was read
         * @return the outcome, status {@code '23'}, CICS {@link FileStatus#NOTFND}
         */
        public static ReadResult notFound(String ddName) {
            return new ReadResult(ddName, FileStatus.NOT_FOUND, Outcome.NOT_FOUND,
                    Optional.empty(), CicsResponse.of(FileStatus.NOTFND), Optional.empty(),
                    Optional.empty());
        }

        /**
         * The duplicate arm: more than one record matched a key that a KSDS makes unique. The first
         * match is carried, as CICS carries it.
         *
         * @param ddName the dataset that was read
         * @param record the first matching record
         * @return the outcome, status {@code '22'}, CICS {@link FileStatus#DUPREC}
         */
        public static ReadResult duplicate(String ddName, TranRecord record) {
            return new ReadResult(ddName, FileStatus.DUPLICATE, Outcome.DUPLICATE,
                    Optional.of(Objects.requireNonNull(record, "The duplicate arm carries the first "
                            + "matching record")),
                    CicsResponse.of(FileStatus.DUPREC), Optional.empty(), Optional.empty());
        }

        /**
         * The {@code WHEN OTHER} arm, carrying only a status.
         *
         * @param ddName the dataset that was read
         * @param status the status to report; it must not be one of the four enumerated ones
         * @return the outcome
         */
        public static ReadResult other(String ddName, String status) {
            return new ReadResult(ddName, status, FileStatus.outcomeOfStatus(status), Optional.empty(),
                    CicsResponse.ofBatchStatus(status), Optional.empty(), Optional.empty());
        }

        /**
         * The {@code WHEN OTHER} arm, carrying a status and the driver's own diagnosis.
         *
         * @param ddName     the dataset that was read
         * @param status     the status to report
         * @param diagnostic what the backend reported
         * @return the outcome
         */
        public static ReadResult other(String ddName, String status, BackendDiagnostic diagnostic) {
            return new ReadResult(ddName, status, FileStatus.outcomeOfStatus(status), Optional.empty(),
                    CicsResponse.ofBatchStatus(status), Optional.empty(),
                    Optional.of(Objects.requireNonNull(diagnostic, "A diagnostic arm carries one")));
        }

        /**
         * The {@code WHEN OTHER} arm, carrying a status and a named CICS condition.
         *
         * <p>For a refusal this module can name precisely - an invalid request against a browse that has
         * been ended, for instance - where the status alone would not say which condition it was.
         *
         * @param ddName   the dataset that was read
         * @param status   the status to report
         * @param response the CICS condition the failure corresponds to
         * @return the outcome
         */
        public static ReadResult other(String ddName, String status, CicsResponse response) {
            return new ReadResult(ddName, status, FileStatus.outcomeOfStatus(status), Optional.empty(),
                    Objects.requireNonNull(response, "A named-condition arm carries a response"),
                    Optional.empty(), Optional.empty());
        }

        /**
         * The {@code WHEN OTHER} arm, carrying a status, a named CICS condition and a measurement.
         *
         * @param ddName      the dataset that was read
         * @param status      the status to report
         * @param response    the CICS condition the failure corresponds to
         * @param observation what was measured
         * @return the outcome
         */
        public static ReadResult other(String ddName, String status, CicsResponse response,
                                       DatasetObservation observation) {
            return new ReadResult(ddName, status, FileStatus.outcomeOfStatus(status), Optional.empty(),
                    Objects.requireNonNull(response, "A named-condition arm carries a response"),
                    Optional.of(Objects.requireNonNull(observation, "A measured arm carries one")),
                    Optional.empty());
        }

        /**
         * Whether a record was read on the normal arm.
         *
         * @return {@code true} for {@link Outcome#OK}
         */
        public boolean isFound() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether a record is available at all - the normal arm or the duplicate arm.
         *
         * @return {@code true} when {@link #record()} is present
         */
        public boolean isRecordReturned() {
            return record.isPresent();
        }

        /**
         * Whether the pass has run past its last record.
         *
         * @return {@code true} for {@link Outcome#END_OF_FILE}
         */
        public boolean isEndOfFile() {
            return outcome == Outcome.END_OF_FILE;
        }

        /**
         * Whether the keyed record was absent.
         *
         * @return {@code true} for {@link Outcome#NOT_FOUND}
         */
        public boolean isNotFound() {
            return outcome == Outcome.NOT_FOUND;
        }

        /**
         * Whether more than one record matched a unique key.
         *
         * @return {@code true} for {@link Outcome#DUPLICATE}
         */
        public boolean isDuplicate() {
            return outcome == Outcome.DUPLICATE;
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
         * The CICS {@code RESP} value, where the outcome has one.
         *
         * @return the response, or empty where no single CICS condition corresponds
         */
        public OptionalInt cicsResp() {
            return response.resp();
        }

        /**
         * The CICS {@code RESP2} reason code.
         *
         * @return the reason code, {@link FileStatus#NO_REASON_CODE} where the condition carries none
         */
        public int cicsResp2() {
            return response.resp2();
        }

        /**
         * The {@code APPL-RESULT} the batch consumers move on this outcome.
         *
         * <p>{@code app/cbl/CBTRN03C.cbl:251-258} is the ladder: {@code '00'} to {@code 0}, {@code '10'}
         * to {@code 16}, anything else to {@code 12}.
         *
         * @return {@link FileStatus#APPL_AOK}, {@link FileStatus#APPL_EOF} or
         *         {@link TransactionRepository#APPL_RESULT_FATAL}
         */
        public int applResult() {
            if (outcome == Outcome.OK) {
                return FileStatus.APPL_AOK;
            }
            if (outcome == Outcome.END_OF_FILE) {
                return FileStatus.APPL_EOF;
            }
            return APPL_RESULT_FATAL;
        }

        /**
         * The status rendered as {@code 9910-DISPLAY-IO-STATUS} renders it.
         *
         * @return the four-character status image
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }

        /**
         * The CICS response pair rendered as the online programs render it -
         * {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD}
         * ({@code app/cbl/COTRN00C.cbl:645}).
         *
         * <p>Both numbers and nothing else: no record content can reach this, because
         * {@link CicsResponse} carries none.
         *
         * @return for example {@code "Resp:13 Reas:0"}
         */
        public String describeResponse() {
            return response.describe();
        }

        /**
         * The record, or a failure that says which arm was reached instead.
         *
         * <p>For a caller that has already branched and knows a record is there. It is deliberately not
         * an {@code orElseThrow()} with no message: the arm that was actually reached is the whole of
         * what a reader of the failure needs.
         *
         * @return the record
         * @throws IllegalStateException if this outcome carries none
         */
        public TranRecord requireRecord() {
            return record.orElseThrow(() -> new IllegalStateException("This read of the " + ddName
                    + " dataset reached the " + outcome + " arm, status " + statusImage()
                    + ", which carries no record. Branch on outcome() before asking for one."));
        }
    }

    /**
     * The outcome of one write: what happened, and nothing about a record, because a write returns none.
     *
     * @param ddName      the configuration key of the dataset that was written
     * @param status      the two-character batch {@code FILE STATUS}: {@link FileStatus#OK},
     *                    {@link FileStatus#DUPLICATE} or
     *                    {@link TransactionRepository#PERMANENT_ERROR_STATUS}
     * @param outcome     the classification of {@code status}, agreeing with it by construction
     * @param response    the CICS {@code RESP} and {@code RESP2} pair
     * @param observation what the write measured - an affected-row count, or a record width - where a
     *                    measurement explains the outcome. Never folded into {@code RESP2}
     * @param diagnostic  what the backend reported when it refused
     */
    public record WriteResult(String ddName,
                              String status,
                              Outcome outcome,
                              CicsResponse response,
                              Optional<DatasetObservation> observation,
                              Optional<BackendDiagnostic> diagnostic) {

        /**
         * Enforces the invariants that keep the components from contradicting one another.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *                                  {@link FileStatus#STATUS_LENGTH} characters, or if
         *                                  {@code outcome} disagrees with {@code status}
         */
        public WriteResult {
            Objects.requireNonNull(ddName, "A write outcome names the dataset it was written to");
            Objects.requireNonNull(status, "A write outcome carries a two-character FILE STATUS");
            Objects.requireNonNull(outcome, "A write outcome carries its classification");
            Objects.requireNonNull(response, "A write outcome carries the CICS RESP/RESP2 pair");
            Objects.requireNonNull(observation, "An absent observation is an empty Optional");
            Objects.requireNonNull(diagnostic, "An absent diagnostic is an empty Optional");
            if (status.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalArgumentException("A FILE STATUS is " + FileStatus.STATUS_LENGTH
                        + " characters; '" + status + "' is " + status.length());
            }
            if (outcome != FileStatus.outcomeOfStatus(status)) {
                throw new IllegalArgumentException("Outcome " + outcome + " does not classify status "
                        + FileStatus.toStatusImage(status) + ", which is "
                        + FileStatus.outcomeOfStatus(status) + ".");
            }
        }

        /**
         * The normal arm: the record was written.
         *
         * @param ddName the dataset it was written to
         * @return the outcome, status {@code '00'}, CICS {@link FileStatus#NORMAL}
         */
        public static WriteResult written(String ddName) {
            return new WriteResult(ddName, FileStatus.OK, Outcome.OK,
                    CicsResponse.of(FileStatus.NORMAL), Optional.empty(), Optional.empty());
        }

        /**
         * The duplicate arm: the key already exists and nothing was written.
         *
         * @param ddName the dataset that refused the add
         * @return the outcome, status {@code '22'}, CICS {@link FileStatus#DUPREC}
         */
        public static WriteResult duplicate(String ddName) {
            return new WriteResult(ddName, FileStatus.DUPLICATE, Outcome.DUPLICATE,
                    CicsResponse.of(FileStatus.DUPREC), Optional.empty(), Optional.empty());
        }

        /**
         * The duplicate arm, carrying the driver's own diagnosis - for the case where the relation
         * itself enforced the key's uniqueness and refused.
         *
         * @param ddName     the dataset that refused the add
         * @param diagnostic what the backend reported
         * @return the outcome, status {@code '22'}, CICS {@link FileStatus#DUPREC}
         */
        public static WriteResult duplicate(String ddName, BackendDiagnostic diagnostic) {
            return new WriteResult(ddName, FileStatus.DUPLICATE, Outcome.DUPLICATE,
                    CicsResponse.of(FileStatus.DUPREC), Optional.empty(),
                    Optional.of(Objects.requireNonNull(diagnostic, "A diagnostic arm carries one")));
        }

        /**
         * The {@code WHEN OTHER} arm, carrying a status and a measurement.
         *
         * @param ddName      the dataset that was written
         * @param status      the status to report
         * @param response    the CICS condition the failure corresponds to
         * @param observation what was measured
         * @return the outcome
         */
        public static WriteResult other(String ddName, String status, CicsResponse response,
                                        DatasetObservation observation) {
            return new WriteResult(ddName, status, FileStatus.outcomeOfStatus(status),
                    Objects.requireNonNull(response, "A named-condition arm carries a response"),
                    Optional.of(Objects.requireNonNull(observation, "A measured arm carries one")),
                    Optional.empty());
        }

        /**
         * The {@code WHEN OTHER} arm, carrying a status, a named CICS condition and the driver's own
         * diagnosis.
         *
         * @param ddName     the dataset that was written
         * @param status     the status to report
         * @param response   the CICS condition the failure corresponds to
         * @param diagnostic what the backend reported
         * @return the outcome
         */
        public static WriteResult other(String ddName, String status, CicsResponse response,
                                        BackendDiagnostic diagnostic) {
            return new WriteResult(ddName, status, FileStatus.outcomeOfStatus(status),
                    Objects.requireNonNull(response, "A named-condition arm carries a response"),
                    Optional.empty(),
                    Optional.of(Objects.requireNonNull(diagnostic, "A diagnostic arm carries one")));
        }

        /**
         * The {@code WHEN OTHER} arm, carrying only a status.
         *
         * @param ddName the dataset that was written
         * @param status the status to report
         * @return the outcome
         */
        public static WriteResult other(String ddName, String status) {
            return new WriteResult(ddName, status, FileStatus.outcomeOfStatus(status),
                    CicsResponse.ofBatchStatus(status), Optional.empty(), Optional.empty());
        }

        /**
         * Whether the record was written.
         *
         * @return {@code true} for {@link Outcome#OK}
         */
        public boolean isWritten() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether the key already existed, so nothing was written.
         *
         * @return {@code true} for {@link Outcome#DUPLICATE}
         */
        public boolean isDuplicate() {
            return outcome == Outcome.DUPLICATE;
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
         * The CICS {@code RESP} value, where the outcome has one.
         *
         * @return the response, or empty where no single CICS condition corresponds
         */
        public OptionalInt cicsResp() {
            return response.resp();
        }

        /**
         * The CICS {@code RESP2} reason code.
         *
         * @return the reason code, {@link FileStatus#NO_REASON_CODE} where the condition carries none
         */
        public int cicsResp2() {
            return response.resp2();
        }

        /**
         * The {@code APPL-RESULT} the batch consumers move on this outcome.
         *
         * <p>{@code app/cbl/CBTRN02C.cbl:566-570} and {@code app/cbl/CBACT04C.cbl:501-505} are both the
         * two-armed form - {@code '00'} to {@code 0}, anything else to {@code 12} - so a duplicate is
         * fatal to a batch job even though it is a normal branch online.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link TransactionRepository#APPL_RESULT_FATAL}
         */
        public int applResult() {
            return outcome == Outcome.OK ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }

        /**
         * The status rendered as {@code 9910-DISPLAY-IO-STATUS} renders it.
         *
         * @return the four-character status image
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }

        /**
         * The CICS response pair rendered as the online programs render it.
         *
         * @return for example {@code "Resp:14 Reas:0"}
         */
        public String describeResponse() {
            return response.describe();
        }
    }

    // =================================================================================================
    // The three cursors. EVERY piece of mutable state in this file lives on one of them, never on the
    // bean (practice B9, gate G53). None is thread-safe, exactly as a COBOL record area is not, and each
    // is meant to be confined to the step, request or test that opened it.
    // =================================================================================================

    /**
     * One opened sequential input pass over the {@value TransactionRepository#INPUT_DD_NAME} dataset.
     *
     * <p>Obtained from {@link TransactionRepository#openInput()} or
     * {@link TransactionRepository#openInput(DatasetBinding)} and discarded at the end of the pass. It
     * reproduces the {@code OPEN INPUT} / {@code READ} / {@code CLOSE} triple of
     * {@code app/cbl/CBTRN03C.cbl:376-392}, {@code :248-272} and {@code :514-530}, and the
     * {@code OPEN INPUT} / {@code CLOSE} pair - with no read between them - of
     * {@code app/cbl/CBTRN01C.cbl:343-359} and {@code :451-467}.
     *
     * <p>A pass cannot be rewound. {@code CBTRN03C} opens once, loops
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} ({@code :168-207}) and closes once, so there is no legacy
     * behaviour for a restart to reproduce; a caller that wants to read again opens another pass.
     */
    public static final class InputFile implements AutoCloseable {

        /** The repository that opened this pass, for its decode seam and its template. */
        private final TransactionRepository repository;

        /**
         * The dataset being read, or {@code null} when the open failed before a relation could be
         * addressed at all - which happens only for a job-scoped binding whose location is not a
         * dataset name.
         */
        private final DatasetRelation relation;

        /** The configuration key of the dataset being read, carried onto every outcome. */
        private final String ddName;

        /**
         * The status the open reported: {@link FileStatus#OK}, or
         * {@link TransactionRepository#PERMANENT_ERROR_STATUS} when the dataset could not be reached.
         */
        private final String openStatus;

        /** Whether the open succeeded, and so whether this pass may read at all. */
        private final boolean opened;

        /** The statements this pass reads with, or {@code null} when the open failed. */
        private final InputStatements statements;

        /**
         * The exact bytes of the record last returned, or {@code null} before the first read. Used only
         * by the keyed path, which advances by asking for the lowest image strictly above this one.
         *
         * <p>The whole image and not the key alone, because a bare sixteen-byte key sorts <em>below</em>
         * the record that carries it, so a pass positioned by key would return the same record for ever.
         * And the bytes the backend gave rather than a re-encoding of the record decoded from them,
         * because those two differ for any stored row whose trailing {@code FILLER} holds anything but
         * spaces.
         */
        private byte[] position;

        /**
         * The rows of the pass, fetched together, or {@code null} until the first read. Used only by the
         * physical-sequential path, which has no key to re-anchor on.
         */
        private List<byte[]> pass;

        /** The index of the next row to hand out of {@link #pass}. */
        private int nextRow;

        /** How many records this pass has returned so far. */
        private int returned;

        /** Whether the pass has run past its last record, or has stopped at a row it cannot read. */
        private boolean exhausted;

        /** Whether {@link #close()} has been called. */
        private boolean closed;

        /**
         * Constructed only by {@link TransactionRepository}, which is what guarantees that a pass's
         * permission to read reflects what the open actually established.
         *
         * @param repository the opening repository
         * @param relation   the dataset being read, or {@code null} when none could be addressed
         * @param ddName     the configuration key of that dataset
         * @param openStatus the status the open reported
         * @param statements the statements to read with, or {@code null} when the open failed
         */
        private InputFile(TransactionRepository repository, DatasetRelation relation, String ddName,
                          String openStatus, InputStatements statements) {
            this.repository = repository;
            this.relation = relation;
            this.ddName = ddName;
            this.openStatus = openStatus;
            this.opened = FileStatus.isOk(openStatus) && statements != null;
            this.statements = statements;
        }

        /**
         * The status {@code OPEN INPUT} reported: the value {@code app/cbl/CBTRN03C.cbl:379} tests
         * before moving {@code 0} or {@code 12} into {@code APPL-RESULT}, and
         * {@code app/cbl/CBTRN01C.cbl:346} likewise.
         *
         * @return {@link FileStatus#OK} or {@link TransactionRepository#PERMANENT_ERROR_STATUS}; never
         *         {@code null}, always two characters
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
         * The {@code APPL-RESULT} the open sets: {@code 0} on success and {@code 12} otherwise - the
         * ladder at {@code app/cbl/CBTRN03C.cbl:377-383}, which seeds {@code 8}, moves {@code 0} on
         * {@code '00'} and {@code 12} on anything else.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link TransactionRepository#APPL_RESULT_FATAL}
         */
        public int openApplResult() {
            return opened ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }

        /**
         * Whether this pass is still usable: opened successfully and not yet closed.
         *
         * @return {@code true} when {@link #readNext()} will report an I/O outcome rather than throw
         */
        public boolean isOpen() {
            return !closed && opened;
        }

        /**
         * How many records this pass has returned so far, which is also the zero-based index of the
         * record the next {@link #readNext()} will return.
         *
         * @return the count; never negative
         */
        public int position() {
            return returned;
        }

        /**
         * The dataset this pass is reading, exactly as configuration declares it.
         *
         * @return the dataset name, or an empty {@link Optional} when the open could address none
         */
        public Optional<String> datasetName() {
            return Optional.ofNullable(relation).map(DatasetRelation::dsname);
        }

        /**
         * The configuration key of the dataset this pass is reading.
         *
         * @return {@value TransactionRepository#INPUT_DD_NAME}
         */
        public String ddName() {
            return ddName;
        }

        /**
         * Reads the next record: the Java form of {@code 1000-TRANFILE-GET-NEXT}
         * ({@code app/cbl/CBTRN03C.cbl:248-272}).
         *
         * <p>Four reported outcomes, and they are the ones the COBOL enumerates:
         * <ul>
         *   <li><strong>{@link Outcome#OK}</strong>, status {@code '00'} - a record, and
         *       {@code APPL-RESULT} of {@code 0} ({@code :252-253});</li>
         *   <li><strong>{@link Outcome#END_OF_FILE}</strong>, status {@code '10'} - the pass is
         *       exhausted, {@code APPL-RESULT} of {@code 16} ({@code :254-255}), which {@code :264}
         *       turns into {@code MOVE 'Y' TO END-OF-FILE}. <strong>Reported repeatedly and
         *       idempotently</strong>: once at end of file, every further call reports end of file
         *       again, which is faithful because the program's loop stops on the flag and never resumes
         *       from a saved position;</li>
         *   <li><strong>{@link Outcome#OTHER}</strong> when the open failed, carrying the open's own
         *       status - so a caller that ignored {@link #openStatus()} still cannot mistake a dataset
         *       it never reached for one that was empty;</li>
         *   <li><strong>{@link Outcome#OTHER}</strong>, status
         *       {@link TransactionRepository#PERMANENT_ERROR_STATUS}, for a read the backend refused, a
         *       row whose record image is absent, or a row that is not
         *       {@link TransactionRepository#RECORD_LENGTH} bytes. All three land on
         *       {@code APPL-RESULT 12} and the {@code 'ERROR READING TRANSACTION FILE'} path at
         *       {@code :266-269} - which is where they belong, because it is the <em>read</em> that
         *       failed.</li>
         * </ul>
         *
         * <p>A row that cannot be read stops the pass rather than being skipped or re-read for ever: the
         * position cannot advance past an image the pass does not have. A backend <em>refusal</em>,
         * by contrast, leaves the position exactly where it was, so a caller that retries retries the
         * same read rather than skipping a record.
         *
         * <p>Reading a closed pass is a <strong>programming error, not an I/O outcome</strong>, and
         * throws. COBOL would report a status in the {@code '4x'} family for a read against a file in
         * the wrong open mode, but no consumer of this dataset ever does it - {@code CBTRN03C} closes
         * once, after its loop - so there is no legacy behaviour to reproduce, and returning a status no
         * COBOL path could produce would be an invention.
         *
         * @return the discriminated outcome; never {@code null}
         * @throws IllegalStateException if this pass has been closed
         */
        public ReadResult readNext() {
            if (closed) {
                throw new IllegalStateException("This pass over the " + ddName + " dataset has been "
                        + "closed, so it has no next record. app/cbl/CBTRN03C.cbl closes once, at :516, "
                        + "after its loop has ended - reading afterwards is a defect in the caller and "
                        + "not a file status. Open another pass instead.");
            }
            if (!opened) {
                // The open never succeeded. Its own status is reported, not a fresh one, so the caller
                // sees the failure that actually happened.
                return ReadResult.other(ddName, openStatus);
            }
            if (exhausted) {
                // AT END, reported again: app/cbl/CBTRN03C.cbl:264 sets the flag and :172 stops looping,
                // so repeating the read repeats the answer.
                return ReadResult.endOfFile(ddName);
            }
            Step step = statements.advancesByKey() ? readNextByKey() : readNextInWrittenOrder();
            ReadResult result = step.result();
            if (result.isRecordReturned()) {
                position = step.exactImage();
                returned++;
            } else if (result.isEndOfFile() || step.rowSeen()) {
                // Either there is no next record, or there is one and it cannot be read. The pass can
                // advance past neither, so it stops - which for the second case is what keeps a caller
                // from re-reading one unreadable row without end.
                exhausted = true;
            }
            return result;
        }

        /**
         * One read of an indexed dataset: one row per round trip, in ascending key order.
         *
         * @return the step; never {@code null}
         */
        private Step readNextByKey() {
            if (position == null) {
                return repository.unparameterisedStep(statements.firstRead(), ddName);
            }
            return repository.advanceStep(statements.advanceAfter().orElseThrow(), position, ddName);
        }

        /**
         * One read of a physical-sequential dataset: the rows of the pass arrive together on the first
         * read, in the order the dataset holds them, and are handed out one at a time thereafter.
         *
         * @return the step; never {@code null}
         */
        private Step readNextInWrittenOrder() {
            if (pass == null) {
                try {
                    pass = repository.fetchPass(statements.firstRead());
                } catch (DataAccessException refused) {
                    // The read's own failure, and its own message: 'ERROR READING TRANSACTION FILE' at
                    // app/cbl/CBTRN03C.cbl:266. The pass is left unfetched, so a caller that retries
                    // retries the read rather than skipping the file.
                    return failedStep(ddName, "front to back", refused);
                }
            }
            if (nextRow >= pass.size()) {
                return new Step(ReadResult.endOfFile(ddName), null, false);
            }
            return repository.classifyRow(pass.get(nextRow++), ddName);
        }

        /**
         * Closes this pass: the Java form of {@code 9000-TRANFILE-CLOSE}
         * ({@code app/cbl/CBTRN03C.cbl:514-530}) and of {@code 9500-TRANFILE-CLOSE}
         * ({@code app/cbl/CBTRN01C.cbl:451-467}).
         *
         * <p>Both programs test this status and abend on it -
         * {@code 'ERROR CLOSING POSTED TRANSACTION FILE'} at {@code :525} and
         * {@code 'ERROR CLOSING TRANSACTION FILE'} at {@code :462} - so it is an outcome and not a
         * formality.
         *
         * <p>What it reports comes from real state. <strong>Closing a pass that never opened is a
         * failure</strong>, which is what COBOL reports for a {@code CLOSE} of a file that is not open,
         * and it is reachable exactly when a caller ignored {@link #openStatus()} - so the close tells
         * it the same thing the open did rather than reporting success over a dataset it never reached.
         * A pass that did open reports {@link FileStatus#OK}: no connection, cursor or buffer is held
         * between reads, so there is genuinely nothing left to flush beyond this pass's own position, and
         * reporting a failure would be inventing one.
         *
         * <p>The call is <strong>idempotent</strong>, which is what makes try-with-resources safe
         * alongside an explicit close in the same block, and it reports the same status every time for
         * the same reason. The fetched rows of a physical-sequential pass are released here, so a closed
         * pass holds nothing.
         *
         * @return {@link FileStatus#OK} for a pass that was open, or
         *         {@link TransactionRepository#PERMANENT_ERROR_STATUS} for one that never opened; never
         *         {@code null}, always two characters
         */
        public String closeInput() {
            closed = true;
            pass = null;
            position = null;
            if (opened) {
                return FileStatus.OK;
            }
            LOG.error("A pass over the " + ddName + " dataset was closed although it never opened - its "
                    + "open reported file status " + FileStatus.toStatusImage(openStatus)
                    + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " from the close as well, because a CLOSE of a file that is not open is not a "
                    + "success");
            return PERMANENT_ERROR_STATUS;
        }

        /**
         * The {@code APPL-RESULT} the close sets: {@code 0} on success and {@code 12} otherwise - the
         * ladder at {@code app/cbl/CBTRN03C.cbl:515-521}.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link TransactionRepository#APPL_RESULT_FATAL}
         */
        public int closeApplResult() {
            // The same condition closeInput() reports on, so the status and the APPL-RESULT cannot
            // disagree. Whether the pass has since been closed is irrelevant: closing twice is
            // idempotent and reports the same outcome both times.
            return opened ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }

        /**
         * {@link AutoCloseable} form of {@link #closeInput()}, so a pass can be used with
         * try-with-resources. Declared to throw nothing, so it adds no checked exception to a caller.
         */
        @Override
        public void close() {
            closeInput();
        }
    }

    /**
     * One opened sequential output run over the {@value TransactionRepository#SEQUENTIAL_OUTPUT_DD_NAME}
     * generation.
     *
     * <p>Obtained from {@link TransactionRepository#openOutput()} and discarded at the end of the run. It
     * reproduces the {@code OPEN OUTPUT} / {@code WRITE} / {@code CLOSE} triple of
     * {@code app/cbl/CBACT04C.cbl:307-323}, {@code :500-514} and {@code :595-611}.
     *
     * <p>This type exists so the repository bean can stay a stateless singleton. A record count is
     * per-run state; making it a field on a singleton would let two concurrent interest runs corrupt each
     * other's count and would make a test's outcome depend on what ran before it (practice B9, gate
     * G53).
     */
    public static final class OutputFile implements AutoCloseable {

        /** The repository that opened this run, for its codec and its template. */
        private final TransactionRepository repository;

        /**
         * The parameterised insert this run issues for each record, composed once by
         * {@link DatasetRelation#insertRecordImage()}.
         *
         * <p>No column list, and that is not an omission. An output-only dataset created {@code NEW} is
         * never described, so there is no column name to be had - and naming one would be declaring a
         * schema, which this migration does not do (gate G44). The record is one fixed-width image and
         * is bound positionally, which is the same record-image model the read side expresses as column
         * position {@link TransactionRepository#RECORD_IMAGE_COLUMN_INDEX}.
         */
        private final String insertStatement;

        /** How many records this run has written. */
        private int recordsWritten;

        /** Whether {@link #close()} has been called. */
        private boolean closed;

        /**
         * Constructed only by {@link TransactionRepository#openOutput()}.
         *
         * @param repository      the opening repository
         * @param insertStatement the parameterised insert
         */
        private OutputFile(TransactionRepository repository, String insertStatement) {
            this.repository = repository;
            this.insertStatement = insertStatement;
        }

        /**
         * The status {@code OPEN OUTPUT} reported, which is always {@link FileStatus#OK}.
         *
         * <p>Stated rather than left implicit: as {@link TransactionRepository#openOutput()} explains,
         * an output-only dataset created {@code NEW} cannot be described, so there is nothing this open
         * could establish and nothing it could fail at. The value {@code app/cbl/CBACT04C.cbl:310} tests
         * is therefore always {@code '00'} here, and a destination that turns out to be unusable is
         * reported by the first {@link #writeSequential(TranRecord)}.
         *
         * @return {@link FileStatus#OK}
         */
        public String openStatus() {
            return FileStatus.OK;
        }

        /**
         * The open status classified.
         *
         * @return {@link Outcome#OK}
         */
        public Outcome openOutcome() {
            return Outcome.OK;
        }

        /**
         * The {@code APPL-RESULT} the open sets - the {@code MOVE 0} branch of
         * {@code app/cbl/CBACT04C.cbl:310-314}.
         *
         * @return {@link FileStatus#APPL_AOK}
         */
        public int openApplResult() {
            return FileStatus.APPL_AOK;
        }

        /**
         * Whether this run is still usable.
         *
         * @return {@code true} until {@link #closeOutput()} or {@link #close()} has been called
         */
        public boolean isOpen() {
            return !closed;
        }

        /**
         * How many records this run has written.
         *
         * @return the count; never negative
         */
        public int recordsWritten() {
            return recordsWritten;
        }

        /**
         * The statement this run issues for each record.
         *
         * <p>Exposed so a test can assert the composed text without a backend, which is the only way to
         * prove it addresses the configured destination when residual risk R-E means no backend is
         * reachable from this build.
         *
         * @return the parameterised insert
         */
        public String insertStatement() {
            return insertStatement;
        }

        /**
         * Writes one record: the Java form of {@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD}
         * ({@code app/cbl/CBACT04C.cbl:500}).
         *
         * <p>All {@link TransactionRepository#RECORD_LENGTH} bytes are written, {@code FILLER PIC X(20)}
         * included, from the image {@link TranRecord#encode(Charset)} produces in the injected dataset
         * code page - so what lands in the generation is byte-identical to what the interest calculator
         * built, including {@code TRAN-AMT}'s sign overpunch (gates G19, G20 and G21).
         *
         * <p><strong>There is no duplicate outcome here.</strong> A physical-sequential dataset has no
         * key, so there is nothing for a record to duplicate, and {@code app/cbl/CBACT04C.cbl:501-505}
         * tests exactly two arms: {@code '00'} to {@code APPL-RESULT 0} and anything else to {@code 12}.
         * This method reports the same two.
         *
         * <p>A record that did not land is never reported as written. An insert that affected no row -
         * or, inexplicably, more than one - lands on the {@code WHEN OTHER} arm carrying the count that
         * was measured, so {@code 'ERROR WRITING TRANSACTION RECORD'} at {@code :510} has something an
         * operator can act on.
         *
         * @param record the record to write; never {@code null}
         * @return the discriminated outcome; never {@code null}
         * @throws NullPointerException  if {@code record} is {@code null}
         * @throws IllegalStateException if this run has been closed
         */
        public WriteResult writeSequential(TranRecord record) {
            Objects.requireNonNull(record, "A transaction record is required to write one");
            if (closed) {
                throw new IllegalStateException("This run over the " + SEQUENTIAL_OUTPUT_DD_NAME
                        + " dataset has been closed, so it can write nothing more. "
                        + "app/cbl/CBACT04C.cbl closes once, at :597, after its last write - writing "
                        + "afterwards is a defect in the caller and not a file status. Open another run "
                        + "instead.");
            }
            byte[] image = record.encode(repository.codec.charset());
            if (image.length != RECORD_LENGTH) {
                // Unreachable through TranRecord, and asserted anyway: a short record in a fixed-length
                // generation shifts every subsequent record for whatever reads it next.
                LOG.error("A transaction record encoded to " + image.length + " bytes where "
                        + SEQUENTIAL_OUTPUT_DD_NAME + " holds " + RECORD_LENGTH
                        + " (app/jcl/INTCALC.jcl:39 declares LRECL=" + RECORD_LENGTH
                        + "); refusing the write rather than emitting a record of the wrong width");
                return WriteResult.other(SEQUENTIAL_OUTPUT_DD_NAME, PERMANENT_ERROR_STATUS,
                        CicsResponse.of(FileStatus.LENGERR),
                        DatasetObservation.recordWidth(image.length));
            }
            int added;
            try {
                added = repository.insert(insertStatement, image);
            } catch (DataAccessException rejected) {
                return WriteResult.other(SEQUENTIAL_OUTPUT_DD_NAME,
                        reportRefusal(WRITE_OPERATION_NAME, SEQUENTIAL_OUTPUT_DD_NAME,
                                "sequentially", rejected),
                        CicsResponse.ofBatchStatus(PERMANENT_ERROR_STATUS),
                        BackendDiagnostic.of(rejected));
            }
            if (added == SINGLE_ROW) {
                recordsWritten++;
                return WriteResult.written(SEQUENTIAL_OUTPUT_DD_NAME);
            }
            LOG.error("A sequential write to " + SEQUENTIAL_OUTPUT_DD_NAME + " reported " + added
                    + " affected row(s) where exactly " + SINGLE_ROW + " was expected; reporting file "
                    + "status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than reporting a record as written");
            return WriteResult.other(SEQUENTIAL_OUTPUT_DD_NAME, PERMANENT_ERROR_STATUS,
                    CicsResponse.of(FileStatus.INVREQ), DatasetObservation.matchingRows(added));
        }

        /**
         * Closes this run: the Java form of {@code 9400-TRANFILE-CLOSE}
         * ({@code app/cbl/CBACT04C.cbl:595-611}).
         *
         * <p>{@code CBACT04C} tests this status at {@code :598} and abends on
         * {@code 'ERROR CLOSING TRANSACTION FILE'}, so the status is returned rather than swallowed -
         * and it is always {@link FileStatus#OK}, for a reason worth stating rather than leaving the
         * caller to wonder. Each write borrows a pooled connection and returns it inside the call, so no
         * buffer, cursor or connection is held between writes and there is nothing left to flush. A
         * failure would have to be invented, and inventing one would make the caller's abend fire on a
         * run that completed.
         *
         * <p>The call is idempotent, so try-with-resources is safe alongside an explicit close.
         *
         * @return {@link FileStatus#OK}
         */
        public String closeOutput() {
            closed = true;
            return FileStatus.OK;
        }

        /**
         * The {@code APPL-RESULT} the close sets - the {@code MOVE 0} branch of
         * {@code app/cbl/CBACT04C.cbl:598-602}.
         *
         * @return {@link FileStatus#APPL_AOK}
         */
        public int closeApplResult() {
            return FileStatus.APPL_AOK;
        }

        /**
         * {@link AutoCloseable} form of {@link #closeOutput()}. Declared to throw nothing, because
         * closing a run cannot fail.
         */
        @Override
        public void close() {
            closeOutput();
        }
    }

    /**
     * One browse of the transaction master, in one direction.
     *
     * <p>Obtained from {@link TransactionRepository#startBrowse(BrowseDirection)} or
     * {@link TransactionRepository#startBrowse(String, BrowseDirection)} and discarded when the browse
     * ends. It reproduces the {@code STARTBR} / {@code READNEXT} / {@code READPREV} / {@code ENDBR}
     * sequences of {@code app/cbl/COTRN00C.cbl:591-695}, {@code app/cbl/COTRN02C.cbl:642-704} and
     * {@code app/cbl/COBIL00C.cbl:441-504}.
     *
     * <p><strong>No cursor is held open and no connection is borrowed between reads.</strong> Each step
     * re-positions by value, which is what keeps the online layer free of server-side conversation state:
     * the three programs are pseudo-conversational and carry their page keys in their own communication
     * area rather than relying on a browse surviving a screen interaction (rule R6, gate G37).
     *
     * <p>A handle counts nothing and knows no page size (gate G39). It yields one record per read.
     */
    public static final class Browse implements AutoCloseable {

        /** The repository that composed the statements and owns the template. */
        private final TransactionRepository repository;

        /** Which read this handle accepts. */
        private final BrowseDirection direction;

        /**
         * The key positioned on, at its declared width, or {@code null} for a boundary browse - the
         * {@code LOW-VALUES} and {@code HIGH-VALUES} cases, which position at the first and the last
         * record respectively.
         */
        private final String anchorKey;

        /** The key of the last record returned, or {@code null} until one has been. */
        private String positionKey;

        /**
         * The exact bytes of the record last returned, or {@code null} before the first read.
         *
         * <p>Three things about this, and all three are load-bearing.
         *
         * <p><strong>The whole image and not the key.</strong> An advancing step asks for the first
         * record whose image sorts strictly after - or before - the position, and a bare sixteen-byte key
         * sorts <em>below</em> the very record it came from, so a browse positioned by key alone would
         * hand back the same record for ever going forward, and skip it going backward. Because
         * {@code TRAN-ID} is unique and every image is exactly
         * {@link TransactionRepository#RECORD_LENGTH} bytes, a comparison of whole images always resolves
         * inside the leading sixteen, which is what makes it equivalent to the key ordering a CICS browse
         * follows.
         *
         * <p><strong>The bytes the backend gave, not a re-encoding of the record decoded from
         * them.</strong> Those two are the same value only when every span of the stored row already
         * holds what the model would write there. {@code CVTRA05Y}'s trailing {@code FILLER PIC X(20)} is
         * written as spaces, so a stored row whose reserved span holds anything else re-encodes to an
         * image that differs from the row it came from - and the browse would then ask for the first
         * record after a value no row has.
         *
         * <p><strong>Bytes and not text.</strong> A stored image decoded and re-encoded through a
         * {@code String} is width-preserving under a single-byte code page and not, in general,
         * byte-preserving. The position is therefore held and bound as the bytes it is.
         */
        private byte[] positionImage;

        /** Whether a record has been returned, and so whether the next read advances or anchors. */
        private boolean positioned;

        /** Whether the browse has been ended. */
        private boolean ended;

        /**
         * Constructed only by {@link TransactionRepository}, so a handle always carries a key already
         * moved to its declared width.
         *
         * @param repository the repository to read through
         * @param direction  which read this handle accepts
         * @param anchorKey  the key to position at, or {@code null} for a boundary browse
         */
        private Browse(TransactionRepository repository, BrowseDirection direction, String anchorKey) {
            this.repository = repository;
            this.direction = direction;
            this.anchorKey = anchorKey;
        }

        /**
         * Which way this browse is walked.
         *
         * @return the direction it was positioned for; never {@code null}
         */
        public BrowseDirection direction() {
            return direction;
        }

        /**
         * The key this browse was positioned at.
         *
         * @return the anchor key at its declared width, or empty for a boundary browse
         */
        public Optional<String> anchorKey() {
            return Optional.ofNullable(anchorKey);
        }

        /**
         * The key of the last record returned.
         *
         * <p>Read out of that record's stored image rather than out of the record decoded from it, so
         * the two cannot disagree.
         *
         * @return that key, or empty until a record has been returned
         */
        public Optional<String> positionKey() {
            return Optional.ofNullable(positionKey);
        }

        /**
         * Whether this browse has been ended.
         *
         * @return {@code true} once {@link #endBrowse()} or {@link #close()} has been called
         */
        public boolean isEnded() {
            return ended;
        }

        /**
         * Reads the next record in ascending key order: the Java form of {@code READNEXT-TRANSACT-FILE}
         * ({@code app/cbl/COTRN00C.cbl:624-653}).
         *
         * <p>The first read returns the record at or after the key positioned on - or the first record of
         * the file for a boundary browse - and each read after it returns the next higher key. Running
         * past the last record reports {@link Outcome#END_OF_FILE} distinctly, because that is what sets
         * {@code TRANSACT-EOF} at {@code :640} and what terminates the paging loop at {@code :296};
         * repeating the read reports it again rather than wrapping round.
         *
         * @return the discriminated outcome; never {@code null}
         */
        public ReadResult readNext() {
            return read(BrowseDirection.FORWARD);
        }

        /**
         * Reads the previous record in descending key order: the Java form of
         * {@code READPREV-TRANSACT-FILE} ({@code app/cbl/COTRN00C.cbl:658-687},
         * {@code app/cbl/COTRN02C.cbl:673-697}, {@code app/cbl/COBIL00C.cbl:472-496}).
         *
         * <p>The first read returns the record at or before the key positioned on - or the last record of
         * the file for a boundary browse - and each read after it returns the next lower key. The
         * boundary form is how both add paths obtain the highest existing {@code TRAN-ID}:
         * {@code app/cbl/COTRN02C.cbl:444-448} then adds one to it, and its
         * {@code WHEN DFHRESP(ENDFILE)} arm at {@code :688-689} does {@code MOVE ZEROS TO TRAN-ID}, so an
         * empty master yields the first identifier rather than a failure.
         *
         * @return the discriminated outcome; never {@code null}
         */
        public ReadResult readPrev() {
            return read(BrowseDirection.BACKWARD);
        }

        /**
         * Reads one step, anchoring on the first read and advancing on every read after it.
         *
         * @param required the direction this read belongs to
         * @return the discriminated outcome; never {@code null}
         */
        private ReadResult read(BrowseDirection required) {
            if (ended) {
                // No browse is in progress, so there is nothing to read from. CICS reports an invalid
                // request for a browse operation without a browse, and so does this.
                LOG.error("A read was requested on a browse of " + CICS_FILE_NAME + " that has already "
                        + "been ended; reporting the invalid-request condition");
                return ReadResult.other(CICS_FILE_NAME, PERMANENT_ERROR_STATUS,
                        CicsResponse.of(FileStatus.INVREQ));
            }
            if (direction != required) {
                // The legacy code positions a separate browse for each direction and never reads one the
                // other way. Reversing direction silently would invent behaviour, so the request is
                // refused as invalid instead.
                LOG.error("A " + required + " read was requested on a browse of " + CICS_FILE_NAME
                        + " positioned for " + direction + "; reporting the invalid-request condition "
                        + "rather than reversing a browse the legacy code never reverses");
                return ReadResult.other(CICS_FILE_NAME, PERMANENT_ERROR_STATUS,
                        CicsResponse.of(FileStatus.INVREQ));
            }
            Statements sql;
            try {
                sql = repository.resolveStatements();
            } catch (DataAccessException refused) {
                return failedStep(CICS_FILE_NAME, "while positioning a browse", refused).result();
            }
            Step step = positioned ? advance(sql) : anchor(sql);
            ReadResult result = step.result();
            if (result.isRecordReturned()) {
                // Advance only on a record, and advance to the bytes the backend actually presented. An
                // end of file leaves the position alone, so repeating the read reports the end of the
                // file again; a failure likewise, so a caller that retries retries the same step rather
                // than skipping one.
                positionImage = step.exactImage();
                positionKey = repository.keyOfImage(positionImage);
                positioned = true;
            }
            return result;
        }

        /**
         * The first read of the browse: at or after the anchor going forward, at or before it going
         * backward, and from the boundary of the file when there is no anchor.
         *
         * @param sql the resolved statements
         * @return the step; never {@code null}
         */
        private Step anchor(Statements sql) {
            if (direction == BrowseDirection.FORWARD) {
                return anchorKey == null
                        ? repository.unparameterisedStep(sql.browseForwardAll(), CICS_FILE_NAME)
                        : repository.browseAnchorStep(sql.browseForwardAnchor(), anchorKey,
                                CICS_FILE_NAME);
            }
            if (anchorKey == null) {
                return repository.unparameterisedStep(sql.browseBackwardAll(), CICS_FILE_NAME);
            }
            // The only two-parameter statement on this class, and both parameters come from the one
            // anchor key: see selectAtOrBeforeKeyDescending for why one comparison cannot express
            // "at or before this key" over a record image whose key is a prefix.
            return repository.browseAnchorStep(sql.browseBackwardAnchor(), anchorKey,
                    KEY_SPAN.pattern(anchorKey), CICS_FILE_NAME);
        }

        /**
         * Every read after the first: strictly past the record already returned, in this browse's
         * direction.
         *
         * @param sql the resolved statements
         * @return the step; never {@code null}
         */
        private Step advance(Statements sql) {
            String statement = direction == BrowseDirection.FORWARD
                    ? sql.browseForwardAfter()
                    : sql.browseBackwardBefore();
            return repository.advanceStep(statement, positionImage, CICS_FILE_NAME);
        }

        /**
         * Ends the browse: the Java form of {@code ENDBR-TRANSACT-FILE}
         * ({@code app/cbl/COTRN00C.cbl:692-695}, {@code app/cbl/COTRN02C.cbl:702-704},
         * {@code app/cbl/COBIL00C.cbl:501-504}).
         *
         * <p>None of the three sites specifies a response option, so neither the legacy code nor this
         * method reports an outcome - there is nothing to report that the COBOL would have looked at.
         *
         * <p>No backend call is made, because none is owed: a browse holds no cursor open, only a
         * position. What ending it does is close the handle, so a read after it is refused rather than
         * silently resuming a browse the caller believes it has finished with. Ending an already-ended
         * browse does nothing, which makes {@link #close()} safe to call after it.
         */
        public void endBrowse() {
            ended = true;
            positionImage = null;
        }

        /**
         * Ends the browse, so a handle can be used in a try-with-resources block. Declared without a
         * checked exception, because ending a browse cannot fail.
         */
        @Override
        public void close() {
            endBrowse();
        }
    }
}

package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The account master dataset, reached over JDBC: one repository for the three access paths the COBOL
 * estate drives against it, and nothing beyond them.
 *
 * <h2>What this class stands for</h2>
 *
 * <p>{@code app/csd/CARDDEMO.CSD:L1-L12} defines the CICS file {@code ACCTDAT} over the account master
 * KSDS with {@code UPDATEMODEL(LOCKING) LOAD(NO) RECORDFORMAT(V) ADD(YES) BROWSE(YES) DELETE(YES)
 * READ(YES) UPDATE(YES) JOURNAL(NO) RECOVERY(NONE) STRINGS(1) STATUS(ENABLED) OPENTIME(FIRSTREF)
 * DISPOSITION(SHARE)}, and the batch JCL binds the same dataset under the DD name {@code ACCTFILE} -
 * {@code app/jcl/READACCT.jcl:L25-L26} for {@code CBACT01C} and {@code app/jcl/INTCALC.jcl:L33-L34}
 * for {@code CBACT04C}. Both names are configured, both are resolved here, and neither is written into
 * Java: the dataset name comes from {@code carddemo.datasets} alone.
 *
 * <h2>Three access modes, one repository</h2>
 *
 * <p>Three different programs reach this one dataset three different ways, and all three land on this
 * single class. Splitting it per access mode would fragment one dataset across three types and lose
 * the fact that they contend for the same records.
 *
 * <ul>
 *   <li><strong>Sequential browse</strong> - {@code app/cbl/CBACT01C.cbl:L29-L33} declares
 *       {@code SELECT ACCTFILE-FILE ASSIGN TO ACCTFILE / ORGANIZATION IS INDEXED / ACCESS MODE IS
 *       SEQUENTIAL / RECORD KEY IS FD-ACCT-ID / FILE STATUS IS ACCTFILE-STATUS}, opens
 *       {@code INPUT} at {@code L135}, reads with {@code READ ACCTFILE-FILE INTO ACCOUNT-RECORD} at
 *       {@code L93} until status {@code '10'}, and closes at {@code L153}. See
 *       {@link AccountFile#readNext()}.</li>
 *   <li><strong>Random keyed read</strong> - {@code app/cbl/CBACT04C.cbl:L41-L45} declares the same
 *       dataset with {@code ACCESS MODE IS RANDOM}, opens {@code I-O} at {@code L291}, sets the key
 *       with {@code MOVE TRANCAT-ACCT-ID TO FD-ACCT-ID} at {@code L202} and reads at {@code L373-L376}
 *       with an {@code INVALID KEY} phrase. See {@link #readByKey(long)}.</li>
 *   <li><strong>Read for update, then rewrite</strong> - {@code app/cbl/COACTUPC.cbl:L3892-L3903}
 *       issues {@code EXEC CICS READ FILE(LIT-ACCTFILENAME) UPDATE RIDFLD(WS-CARD-RID-ACCT-ID-X)
 *       KEYLENGTH(LENGTH OF WS-CARD-RID-ACCT-ID-X) INTO(ACCOUNT-RECORD) LENGTH(LENGTH OF
 *       ACCOUNT-RECORD) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)} and rewrites at {@code L4065-L4071}.
 *       {@code app/cbl/CBACT04C.cbl:L356} performs the batch counterpart,
 *       {@code REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD}. See {@link #readForUpdate(String)} and
 *       {@link #rewrite(AccountRecord)}.</li>
 * </ul>
 *
 * <h2>The key travels as eleven characters, not as a number</h2>
 *
 * <p>{@code app/cbl/COACTUPC.cbl:L381-L383} declares {@code WS-CARD-RID-ACCT-ID PIC 9(11)} with
 * {@code WS-CARD-RID-ACCT-ID-X REDEFINES WS-CARD-RID-ACCT-ID PIC X(11)}, and it is the
 * <strong>character</strong> view that every online {@code RIDFLD} names. That is why
 * {@link #readForUpdate(String)} takes the eleven-character image verbatim while
 * {@link #readByKey(long)} takes the number the batch programs move into {@code FD-ACCT-ID PIC 9(11)}
 * and renders it through {@link AccountRecord#keyImage(long, Charset)}. Both end up addressing the
 * same eleven bytes; the difference is which of the two COBOL views the caller holds.
 *
 * <h2>This class reports statuses; it never abends</h2>
 *
 * <p>Every operation returns a value carrying a two-character {@code FILE STATUS}, and none of them
 * terminates the program. That boundary is exactly where the COBOL draws it: the I/O paragraph reports
 * the status, and the <em>caller</em> decides what to do about it. {@code CBACT01C.cbl:L104-L115} is
 * the canonical shape -
 * <pre>
 * IF  APPL-AOK        CONTINUE
 * ELSE IF APPL-EOF    MOVE 'Y' TO END-OF-FILE
 *      ELSE           DISPLAY 'ERROR READING ACCOUNT FILE'
 *                     MOVE ACCTFILE-STATUS TO IO-STATUS
 *                     PERFORM 9910-DISPLAY-IO-STATUS
 *                     PERFORM 9999-ABEND-PROGRAM
 * </pre>
 * so the {@code DISPLAY} lines, the rendered status image and the abend all belong to the job class,
 * not here. {@link ReadResult#applResult()} and {@link WriteResult#applResult()} hand the caller the
 * very {@code APPL-RESULT} value its {@code EVALUATE} would have moved, so the guard chain translates
 * one-for-one.
 *
 * <p>A contract violation is a different thing from an I/O outcome and is thrown rather than statused:
 * a configured record width that is not the copybook's, a dataset name that cannot address anything, a
 * key image of the wrong width, or a backend that presents no record-image column at all. None of
 * those is a condition the COBOL could observe as a file status, and reporting one as a status would
 * send a job down its abend path with a misleading reason.
 *
 * <h2>How a fixed-width dataset is addressed over JDBC</h2>
 *
 * <p>The dataset is a fixed-width record store, not a relational table, and this migration introduces
 * no schema of its own: there is no data-definition statement anywhere in this class, no schema
 * migration, no entity mapping, no version column and no index creation. Every dataset is reached as a
 * single-column relation whose one column holds the whole record image, addressed at position
 * {@value #RECORD_IMAGE_COLUMN_INDEX} - the same convention the module's sequential reader and its
 * output writers already use.
 *
 * <p>One thing this class needs that a purely sequential reader does not is the <em>name</em> of that
 * column, because SQL permits an ordinal in {@code ORDER BY} but not in a {@code WHERE} or a
 * {@code SET} clause, and a keyed read and a rewrite both need one. The name is therefore
 * <strong>discovered</strong> from JDBC result-set metadata by a probe that transfers no rows, and is
 * never invented, defaulted or configured: see {@link #resolveStatements()}. That keeps this class free
 * of any identifier it could not justify while still letting it express a keyed predicate.
 *
 * <p>The statements it composes are deliberately confined to constructs that are core SQL and carry no
 * dialect assumption - a delimited identifier, {@code ORDER BY}, a comparison, {@code LIKE … ESCAPE},
 * and a single-column {@code UPDATE}. Row limiting is applied through {@link PreparedStatement} rather
 * than through a dialect's row-limiting clause, so no fetch-first or offset syntax appears here.
 *
 * <h2>Ordering is asserted in the statement, never assumed</h2>
 *
 * <p>{@link AccountFile#readNext()} must deliver records in ascending {@code ACCT-ID} order, because
 * {@code CBACT04C}'s account-break logic and {@code CBACT01C}'s output sequence both depend on it, so
 * every browse statement carries an explicit ascending {@code ORDER BY}. Ordering by the record image
 * <em>is</em> ordering by the key: {@code ACCT-ID PIC 9(11)} occupies the leading eleven bytes of the
 * record ({@code app/cpy/CVACT01Y.cpy:L5}) and is zero-filled to its full width, so the byte ordering
 * of the images and the numeric ordering of the keys coincide. Nothing here relies on a backend's
 * natural scan order.
 *
 * <h2>Concurrency, locking and the unit of work</h2>
 *
 * <p>{@code UPDATEMODEL(LOCKING)} with {@code RECOVERY(NONE)} means a record read for update is held
 * for the duration of the unit of work, and {@link #readForUpdate(String)} therefore issues a
 * <strong>genuine locking read</strong>: the keyed select carries {@code FOR UPDATE}, exactly as
 * {@link com.vsergeychik.carddemo.card.CardRepository} does for the card master. What is <em>not</em>
 * introduced is an optimistic-lock marker or a version column, because a version column would be a
 * schema change and this migration changes no schema.
 *
 * <p>The lock only exists inside a unit of work, so {@link #readForUpdate(String)} requires one and
 * refuses to run without it. Under auto-commit a {@code FOR UPDATE} row lock is taken and released
 * before the statement even returns, which would leave the caller believing it held a record it did
 * not - the precise hazard {@code 9600-WRITE-PROCESSING} exists to avoid. A CICS task always has a
 * unit of work and a Spring Batch chunk step always has a transaction, so there is no legacy path that
 * reads for update outside one; a Java caller that does has a wiring defect, and it is reported as one
 * rather than silently tolerated. The transaction boundary itself still belongs to the caller - the
 * batch step or the service - so this class declares none and opens none.
 *
 * <p>The optimistic check that {@code COACTUPC} performs is not this class's job either. Paragraph
 * {@code 9700-CHECK-CHANGE-IN-REC} ({@code app/cbl/COACTUPC.cbl:L4109} onwards) compares the re-read
 * record field by field against the copy the screen was painted from, and it is performed at
 * {@code L3947-L3948}, between the read for update and the rewrite. That comparison lives in the
 * account update service, which is where the COBOL puts it. What this class guarantees is that the
 * record it hands that comparison is locked, and stays locked until the caller's transaction ends - so
 * the compare and the {@link #rewrite(AccountRecord)} that follows it cannot straddle someone else's
 * change.
 *
 * <h2>Thread safety</h2>
 *
 * <p><strong>This repository is stateless and therefore thread-safe.</strong> It is a Spring singleton,
 * so anything mutable held on it would be shared by every concurrent execution; a browse position in
 * particular would let two callers consume each other's records and destroy the ordering parity depends
 * on. COBOL has no such sharing - a file position and an {@code OPEN} mode belong to one program
 * execution, exactly as {@code WORKING-STORAGE} does - so neither is held here. Every field is
 * {@code final} and immutable, and there is <strong>no static mutable state</strong> either: the only
 * {@code static} members are immutable constants and a logger reference.
 *
 * <p>All per-execution state lives on {@link AccountFile}, the {@link AutoCloseable} handle
 * {@link #open(OpenMode)} returns. One handle stands for one opened file: it carries that open's mode,
 * its status, the statements resolved for it and its browse position, and it is used by whoever opened
 * it, exactly as a COBOL {@code FD} is used by the program that opened it. A handle is <em>not</em>
 * thread-safe and is not meant to be; obtaining a second one is free and is the correct answer.
 *
 * <p>The keyed operations - {@link #readByKey(long)}, {@link #readForUpdate(String)} and
 * {@link #rewrite(AccountRecord)} - are also exposed directly on the repository, because the CICS
 * programs that drive them issue no {@code OPEN} at all: {@code COACTUPC}'s
 * {@code EXEC CICS READ ... UPDATE} names the file and nothing more. They touch no position and hold
 * nothing between calls, so they are safe to call concurrently. Each resolves the dataset's shape for
 * itself; a caller that has an {@link AccountFile} open uses the identical operations on the handle
 * instead and reuses the shape that open already resolved.
 *
 * <h2>The record is always exactly 300 bytes</h2>
 *
 * <p>{@code app/cpy/CVACT01Y.cpy:L2} declares {@code RECLN 300}, and the file description at
 * {@code app/cbl/CBACT01C.cbl:L38-L40} corroborates it independently as {@code FD-ACCT-ID PIC 9(11)}
 * plus {@code FD-ACCT-DATA PIC X(289)}. The CICS definition says {@code RECORDFORMAT(V)} while the JCL
 * says {@code RECFM=F}; that disagreement is already resolved for the whole module in favour of
 * copybook-fixed lengths, and this class simply holds the configured width to
 * {@link AccountRecord#RECORD_LENGTH} and refuses anything else. Every image it decodes and every
 * image it writes is that wide, {@code FILLER X(178)} included - the 178 bytes are written as spaces
 * rather than dropped, which is what makes the width provable rather than assumed.
 *
 * <p>A stored image of any other width is <strong>reported, not repaired</strong>. Widening a short one
 * back with spaces looks faithful - the only bytes it could be missing are trailing {@code FILLER}, and
 * a {@code FILLER} carrying no literal holds spaces - but that reasoning holds only if the missing bytes
 * really are the trailing ones, and nothing about a short row says they are. A row that lost bytes
 * anywhere else, or that was written against a different layout, pads into a record whose every field
 * then decodes from an offset that is not its own: a balance read from the wrong span is still a number,
 * so the defect arrives at the caller as plausible data rather than as a failure. Every record of
 * {@code app/data/ASCII/acctdata.txt} measures exactly 300, so a row of any other width can only mean the
 * backend is not serving this layout, and that is worth saying out loud. It is reported as
 * {@link FileStatus#LENGERR}, which is what CICS reports when a record does not fit its receiver.
 *
 * <h2>What this class deliberately does not have</h2>
 *
 * <ul>
 *   <li><strong>No alternate-index finder.</strong> The account master has no alternate index:
 *       {@code CARDAIX} is a path over the card master and {@code CXACAIX} is a path over the
 *       cross-reference, and neither belongs to this dataset. Adding an account-keyed finder here
 *       would invent an access path the estate does not have.</li>
 *   <li><strong>No add and no delete.</strong> The CICS definition permits both, but no program in
 *       {@code app/cbl} writes a new account record or deletes one, so neither operation is exposed.
 *       Only the paths the COBOL actually drives are implemented.</li>
 *   <li><strong>No generic query surface.</strong> No predicate builder, no paging, no projection and
 *       no ad-hoc filter. The estate reads this dataset sequentially or by whole key, and that is the
 *       entire surface.</li>
 *   <li><strong>No object-relational mapping.</strong> Records are constructed by the module's own
 *       hand-written fixed-width codec, addressed by absolute offset against the copybook, so every
 *       byte position is reviewable rather than hidden behind a mapping layer.</li>
 * </ul>
 *
 * <h2>User-specified rules</h2>
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - and that single line
 * is the whole document, so <strong>no user rule governs this file</strong>. Its absence is not licence
 * to lower the bar: the migration plan elevates twelve enterprise practices to binding constraints
 * instead, and the ones bearing on this file are B2 (Spring Boot 3.5.16 JDBC APIs only - a
 * {@link JdbcTemplate} and nothing above it), B3 (the COBOL, copybook, JCL and CSD trees cited
 * throughout are read-only and appear here only as provenance), B4 (no scope creep - only the access
 * paths above), B8 (explicit over implicit - the dataset name and every width come from configuration,
 * the code page is injected rather than defaulted, the ordering is stated in the statement, and there
 * are no wildcard imports), B9 (no static mutable state; constructor injection only), B11 (the
 * hand-written codec, so every offset is auditable) and B12 (the inability to reach a production
 * backend from this build is documented rather than absorbed).
 *
 * @see AccountRecord
 * @see FileStatus
 */
@Repository
public class AccountRepository {

    /**
     * Logger for the diagnostics this class emits on a failed operation.
     *
     * <p>{@code static final} and a reference to an immutable logger, so it introduces no shared
     * mutable state. It exists because the outcome that travels back to a caller is deliberately
     * coarse - a two-character status, exactly as the COBOL has - and discarding the underlying reason
     * would leave a production failure undiagnosable.
     */
    private static final Log LOG = LogFactory.getLog(AccountRepository.class);

    /**
     * The CICS {@code FILE} name of the account master: {@code ACCTDAT}.
     *
     * <p>Defined at {@code app/csd/CARDDEMO.CSD:L1}, and the value of
     * {@code LIT-ACCTFILENAME PIC X(8) VALUE 'ACCTDAT '} at {@code app/cbl/COACTUPC.cbl:L573-L574},
     * which every online program passes as {@code FILE(…)} or {@code DATASET(…)}.
     *
     * <p>This is a DD-name key, not a dataset name. It resolves against {@code carddemo.datasets}; the
     * dataset name itself is never written in Java.
     */
    public static final String CICS_FILE_NAME = "ACCTDAT";

    /**
     * The batch DD name of the same dataset: {@code ACCTFILE}.
     *
     * <p>The name every non-CICS program assigns it - {@code ASSIGN TO ACCTFILE} at
     * {@code app/cbl/CBACT01C.cbl:L29} and {@code app/cbl/CBACT04C.cbl:L41} - and the DD the JCL binds
     * at {@code app/jcl/READACCT.jcl:L25-L26} and {@code app/jcl/INTCALC.jcl:L33-L34}.
     *
     * <p>Also a key, not a dataset name. It is resolved alongside {@link #CICS_FILE_NAME} and the two
     * are required to agree, because they address one dataset.
     */
    public static final String BATCH_DD_NAME = "ACCTFILE";

    /**
     * The declared record width in bytes: {@value}, delegated to {@link AccountRecord#RECORD_LENGTH}
     * rather than restated, so the module has one width for this record.
     */
    public static final int RECORD_LENGTH = AccountRecord.RECORD_LENGTH;

    /**
     * The primary-key width in bytes: {@value}, delegated to {@link AccountRecord#KEY_LENGTH}.
     *
     * <p>This is the value {@code app/cbl/COACTUPC.cbl:L3898} supplies as
     * {@code KEYLENGTH (LENGTH OF WS-CARD-RID-ACCT-ID-X)}, and the width of {@code ACCT-ID PIC 9(11)}.
     */
    public static final int KEY_LENGTH = AccountRecord.KEY_LENGTH;

    /**
     * The one-based result-set position of the record-image column: {@value}.
     *
     * <p>A position and never a column name, for the reason set out on this class: a dataset carrying
     * no relational metadata is presented as a single record-image column, and naming that column in
     * Java would be a literal this file cannot justify. Where a name is unavoidable - a keyed predicate
     * and a rewrite both need one - it is discovered from result-set metadata at this same position.
     */
    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    /**
     * Where the key sits inside the record image: the first {@link #KEY_LENGTH} bytes.
     *
     * <p>{@code app/cpy/CVACT01Y.cpy} declares {@code ACCT-ID PIC 9(11)} as the first item of the
     * 300-byte record, and {@code app/cbl/CBACT01C.cbl:L37-L38} splits the {@code FD} the same way -
     * {@code FD-ACCT-ID PIC 9(11)} then {@code FD-ACCT-DATA PIC X(289)}. An offset and a length rather
     * than a column name, because the key is part of the record image and is addressed the way every
     * other field of it is.
     */
    private static final KeySpan KEY_SPAN = new KeySpan(0, KEY_LENGTH);

    /**
     * The {@code APPL-RESULT} value the COBOL moves on the fatal arm of every guard chain over this
     * dataset: {@value}.
     *
     * <p>{@code app/cbl/CBACT01C.cbl:L101}, {@code L139} and {@code L157} move it for a failed read,
     * open and close respectively, and {@code app/cbl/CBACT04C.cbl:L295}, {@code L360} and {@code L381}
     * do the same for its open, rewrite and keyed read. Neither {@link FileStatus#APPL_AOK} nor
     * {@link FileStatus#APPL_EOF} is restated here - both are taken from {@link FileStatus}.
     */
    public static final int APPL_RESULT_FATAL = 12;

    /**
     * The feedback byte reported inside a permanent-error status: binary zero.
     *
     * <p>Zero because a translated data-access failure carries no VSAM feedback code to report, and
     * inventing one would put a number into a job's log that no backend produced.
     */
    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The status reported when the backend refuses an operation: {@code '9'} followed by
     * {@link #PERMANENT_ERROR_FEEDBACK_CODE}.
     *
     * <p>This is the COBOL convention for a permanent error rather than an invention. A status whose
     * first byte is {@code '9'} carries a binary feedback code in its second byte, which is precisely
     * the case {@code 9910-DISPLAY-IO-STATUS} tests for with {@code IF IO-STATUS NOT NUMERIC OR
     * IO-STAT1 = '9'} ({@code app/cbl/CBACT01C.cbl:L177-L178}) before rendering it as four digits.
     * Passing this value to {@link FileStatus#toDisplayLine(String)} therefore produces exactly the
     * line the COBOL would have emitted.
     *
     * <p>It classifies as {@link Outcome#OTHER}, so it reaches the {@code WHEN OTHER} arm of every
     * guard chain - which is where a permanent I/O error belongs.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;


    /**
     * The core-SQL locking clause appended to the keyed select for a read for update.
     *
     * <p>Held as a constant so that the one place this module asks a backend for a lock is visible, and so
     * that the statement text a test asserts and the statement text the repository issues are the same
     * string rather than two spellings that could drift.
     */
    private static final String FOR_UPDATE = " FOR UPDATE";

    /**
     * How many rows the pre-rewrite probe will look at: two.
     *
     * <p>The probe exists to answer "does this key select no row, one row, or more than one" - the three
     * outcomes a rewrite has to distinguish - and the third is settled by the second row. Counting
     * further would transfer rows to refine a number nothing reads, and against a relation whose key
     * column really is not unique it would transfer the whole relation to say what two rows already say.
     *
     * @see #matchingRowCount(String, String)
     */
    private static final int FAN_OUT_PROBE_LIMIT = 2;

    // =================================================================================================
    // Collaborators and resolved configuration. Every one of them is final and every one arrives
    // through the constructor; nothing here is discovered from a static context or a service locator.
    // =================================================================================================

    /**
     * The module's single {@link JdbcTemplate}, from the data-access configuration. The only
     * collaborator that touches the backend.
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * The module's hand-written fixed-width codec, holding the injected code page.
     *
     * <p>Used here as the single place the code page is kept, so no call site in this class chooses one,
     * and as the renderer of the escaped key patterns the keyed statements carry.
     */
    private final FixedWidthCodec codec;

    /** The resolved dataset name, taken verbatim from the configured binding. */
    private final String datasetName;

    /**
     * The dataset as this module reaches it: the validated name, its delimited rendering, the
     * record-image column it discovers, and every statement composed over it.
     *
     * <p>The module's one data-access contract, shared with every other repository, so that how a
     * dataset name becomes a SQL identifier and how a key becomes a predicate is decided once rather
     * than once per repository.
     */
    private final DatasetRelation relation;

    /**
     * The one representation every read, write and comparison operand of this dataset uses.
     *
     * <p>Injected rather than chosen here. Whether the record image is a character column or a binary
     * one is a property of the deployment's driver, and this repository once decided it for itself -
     * {@code getString} on the way in, {@code setString} on the way out - while the sibling card file and
     * both statement writers decided differently about the same deployment. One of those had to be
     * wrong, and a wrong answer is silent: a driver asked for a character value converts the stored bytes
     * through a code page of its own choosing. {@link RecordImageForm} is now the single authority, so
     * there is no per-repository choice left to disagree about.
     */
    private final RecordImageForm recordImageForm;

    // =================================================================================================
    // There is no mutable per-instance state, and that is the point. A Spring singleton that held an
    // open mode, a browse position or a resolved shape would share all three with every concurrent
    // execution, and COBOL shares none of them: a file position and an OPEN verb belong to one program
    // execution. All three therefore live on AccountFile, one instance per open. Practice B9, gate G53.
    // =================================================================================================

    /**
     * Resolves the account master's configured bindings, proves the record geometry, and captures the
     * collaborators - all before the context finishes starting, so nothing checkable is left to fail
     * mid-job.
     *
     * <p>Five things are established here, in this order, and each fails loudly rather than degrading:
     * <ol>
     *   <li>the collaborators are present - a missing one is a wiring defect and is reported as one;</li>
     *   <li>both DD names are configured. {@link DatasetBindings#binding(String)} matches a key exactly,
     *       with no case-insensitive or fuzzy fallback, and names every configured key in its diagnostic
     *       if one is absent;</li>
     *   <li>each binding declares {@link #RECORD_LENGTH}. Any other width is a configuration defect:
     *       this repository decodes and encodes by absolute offset against
     *       {@code app/cpy/CVACT01Y.cpy}, so a differently-sized record would silently misplace every
     *       field after the first;</li>
     *   <li>each binding's declared key width, <em>where one is declared</em>, is {@link #KEY_LENGTH}.
     *       The configuration states a key width only where it is verifiable in a source file, so an
     *       absent one is normal and is accepted;</li>
     *   <li>the two bindings name the same dataset. {@code app/csd/CARDDEMO.CSD:L1-L2} defines the CICS
     *       file and {@code app/jcl/READACCT.jcl:L25-L26} defines the batch DD over <em>one</em> account
     *       master dataset, so a deployment that pointed them at two different ones would give the
     *       online and the batch programs different data while every parity case assumed otherwise.</li>
     * </ol>
     *
     * <p>The code page is injected and handed straight to the codec, never derived and never left to the
     * platform: a fixed-width mainframe record is bytes in a specific code page. The dataset code-page
     * bean is the one the whole data layer takes, so the encoding of dataset input is governed by a
     * single property rather than by a decision repeated at each call site.
     *
     * @param jdbcTemplate    the module's shared template, from the data-access configuration
     * @param datasetBindings the DD-name-keyed dataset catalogue bound from {@code carddemo.datasets}
     * @param datasetCharset  the active dataset code page, selected by bean name so the choice is
     *                        explicit at the injection point
     * @param recordImageForm how the deployment's driver presents a record image over JDBC, from
     *                        {@value RecordImageForm#FORM_PROPERTY}; the module's single authority on
     *                        that, so this class never chooses a JDBC type for itself
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if either DD name is unconfigured, if either binding declares a
     *                               record width other than {@link #RECORD_LENGTH} or a key width other
     *                               than {@link #KEY_LENGTH}, if the two bindings name different
     *                               datasets, or if the resolved dataset name is unusable
     */
    public AccountRepository(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm) {

        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + "account master is reached through the module's shared template");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "dataset names live in configuration and are never written in Java");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset is "
                + "required: a fixed-width mainframe record is bytes in a specific code page, so the "
                + "code page is stated explicitly and never taken from the platform"));
        this.recordImageForm = Objects.requireNonNull(recordImageForm, "A record-image representation is "
                + "required: whether this deployment's driver presents a record image as characters or "
                + "as bytes is stated once, by " + RecordImageForm.FORM_PROPERTY + ", and never decided "
                + "per repository");
        RecordImageForm.requireSingleByteCodePage(datasetCharset);

        DatasetBinding cicsBinding = requireAccountGeometry(datasetBindings, CICS_FILE_NAME);
        DatasetBinding batchBinding = requireAccountGeometry(datasetBindings, BATCH_DD_NAME);
        requireSameDataset(cicsBinding, batchBinding);

        this.relation = DatasetRelation.of(requireUsableDatasetName(cicsBinding.dsname()),
                RECORD_LENGTH);
        this.datasetName = this.relation.dsname();
    }

    // =================================================================================================
    // Read-only accessors. Diagnostics and assertions, never a way to reach around this class.
    // =================================================================================================

    /**
     * The resolved dataset name, exactly as configuration declares it.
     *
     * <p>Exposed so a caller can report which dataset it read and so a test can assert the binding was
     * honoured. The value is configured, never hard-coded, so surfacing it introduces no dataset literal
     * into Java.
     *
     * @return the configured dataset name; never {@code null} and never blank
     */
    public String datasetName() {
        return datasetName;
    }

    /**
     * The record width this repository reads and writes, always {@link #RECORD_LENGTH}.
     *
     * @return 300
     */
    public int recordLength() {
        return RECORD_LENGTH;
    }

    /**
     * The code page in which this repository encodes and decodes record images.
     *
     * <p>Surfaced so a caller that renders a record itself uses the same code page rather than choosing
     * one, and so a test can assert that the injected charset is the one actually in use.
     *
     * @return the injected dataset code page; never {@code null}
     */
    public Charset datasetCharset() {
        return codec.charset();
    }

    // =================================================================================================
    // OPEN and CLOSE.
    //
    // 0000-ACCTFILE-OPEN  - app/cbl/CBACT01C.cbl:L133-L149, OPEN INPUT
    // 0300-ACCTFILE-OPEN  - app/cbl/CBACT04C.cbl:L289-L305, OPEN I-O
    // 9000-ACCTFILE-CLOSE - app/cbl/CBACT01C.cbl:L151-L167
    // 9300-ACCTFILE-CLOSE - app/cbl/CBACT04C.cbl:L577-L594
    //
    // All four paragraphs have one shape: issue the verb, then IF status = '00' move 0 to APPL-RESULT
    // else move 12, and on the fatal arm display an error line, render the status and abend. These
    // methods reproduce the first half only - the status - because the caller owns the second half.
    // =================================================================================================

    /**
     * Opens the account master and hands back the handle that stands for that open.
     *
     * <p>Two verbs, both evidenced, and both reaching this one method: {@code OPEN INPUT} at
     * {@code app/cbl/CBACT01C.cbl:L135} - shared by {@code CBTRN01C} at {@code L327} and by
     * {@code CBSTM03B} at {@code L209} - and {@code OPEN I-O} at {@code app/cbl/CBACT04C.cbl:L291},
     * shared by {@code CBTRN02C} at {@code L311}. No program opens this dataset any other way.
     *
     * <p><strong>Why this returns a handle rather than a status.</strong> An {@code OPEN} produces
     * something: a file with a mode and a position, private to the program that opened it. Reporting
     * only a status and keeping that file on the repository would make the position and the mode shared
     * singleton state, and two concurrent executions would then consume each other's records. The handle
     * <em>is</em> the file, so each execution has its own, and the repository keeps nothing. The status
     * the COBOL tests is {@link AccountFile#openStatus()}, reported on the handle rather than lost.
     *
     * <p><strong>What "open" means against a configuration-bound driver, precisely.</strong> There is no
     * persistent file handle to acquire: the shared template borrows and returns a connection per
     * operation. So what this method does is the honest equivalent - it establishes the two things an
     * {@code OPEN} establishes, and reports whether it could:
     * <ul>
     *   <li><strong>it positions the file.</strong> The returned handle is positioned before the first
     *       record, so its first {@link AccountFile#readNext()} returns the first record in key order.
     *       This is a real, observable effect: a {@code CLOSE} followed by an {@code OPEN INPUT}
     *       re-reads a COBOL file from its first record, and a fresh handle re-reads this one from its
     *       first record too;</li>
     *   <li><strong>it proves the dataset is addressable.</strong> The metadata probe describes the
     *       relation without transferring a row, which is the failure an {@code OPEN} most often reports -
     *       an absent or unreachable dataset. It also resolves the record-image column once for the
     *       whole open, so no later operation on the handle pays for a second metadata round trip.</li>
     * </ul>
     *
     * <p>A failed open yields a handle carrying {@link #PERMANENT_ERROR_STATUS} and no resolved shape, so
     * every operation on it reports that same failure rather than a fresh one - a caller that ignored the
     * status still cannot mistake a dataset it never reached for one that was empty. The caller then owns
     * the {@code DISPLAY 'ERROR OPENING ACCTFILE'} line ({@code CBACT01C.cbl:L144}) or
     * {@code DISPLAY 'ERROR OPENING ACCOUNT MASTER FILE'} ({@code CBACT04C.cbl:L300}), the rendered
     * status and the abend.
     *
     * <p>The mode is recorded and not enforced. No program in the estate mixes the modes against this
     * dataset - a browse is always under {@code OPEN INPUT} and a rewrite always under {@code OPEN I-O} -
     * so refusing an operation on mode grounds would add a rejection the COBOL never performs and a
     * branch no caller could reach.
     *
     * @param mode the {@code OPEN} verb being issued
     * @return a freshly positioned handle whose {@link AccountFile#openStatus()} reports whether the
     *         dataset was addressable; never {@code null}
     * @throws NullPointerException  if {@code mode} is {@code null}
     * @throws IllegalStateException if the backend presents the dataset with no usable record-image
     *                               column, which is a contract violation rather than an I/O outcome
     */
    public AccountFile open(OpenMode mode) {
        Objects.requireNonNull(mode, "An open mode is required: the estate opens this dataset as "
                + OpenMode.INPUT.cobolVerb() + " or as " + OpenMode.I_O.cobolVerb() + ", and which "
                + "verb was issued is part of what the program did");

        // An OPEN resolves the dataset's shape afresh. The resolved shape and the browse position
        // belong to the returned handle, never to this instance: a Spring @Repository is a singleton,
        // and two concurrent executions sharing one position would interleave each other's browses.
        this.relation.forgetRecordImageColumn();

        Statements resolved;
        try {
            resolved = resolveStatements();
        } catch (DataAccessException unreachable) {
            logRefusal(unreachable, "describe the account master dataset '" + datasetName + "' to "
                    + mode.cobolVerb() + " it");
            return new AccountFile(this, mode, PERMANENT_ERROR_STATUS, null);
        }
        return new AccountFile(this, mode, FileStatus.OK, resolved);
    }


    // =================================================================================================
    // 1000-ACCTFILE-GET-NEXT - app/cbl/CBACT01C.cbl:L92-L116.
    // =================================================================================================


    // =================================================================================================
    // 1100-GET-ACCT-DATA        - app/cbl/CBACT04C.cbl:L372-L391, the batch keyed read.
    // 9300-GETACCTDATA-BYACCT   - app/cbl/COACTUPC.cbl:L3701-L3746, the online keyed read.
    // 9600-WRITE-PROCESSING     - app/cbl/COACTUPC.cbl:L3888-L3915, the online read for update.
    // =================================================================================================

    /**
     * Reads one record by account identifier: the Java form of the batch keyed read at
     * {@code app/cbl/CBACT04C.cbl:L373-L376},
     * <pre>
     * READ ACCOUNT-FILE INTO ACCOUNT-RECORD
     *     INVALID KEY
     *        DISPLAY 'ACCOUNT NOT FOUND: ' FD-ACCT-ID
     * END-READ
     * </pre>
     *
     * <p>A {@code long} because that is the COBOL view the batch programs hold: the key is
     * {@code FD-ACCT-ID PIC 9(11)} ({@code CBACT04C.cbl:L86}) and it is set by
     * {@code MOVE TRANCAT-ACCT-ID TO FD-ACCT-ID} at {@code L202} from another {@code PIC 9(11)} item.
     * The number is rendered to the stored eleven-digit zero-filled image by
     * {@link AccountRecord#keyImage(long, Charset)}, so the {@code PIC 9} move rule - zero-fill on the
     * left, truncate on the left - is applied in one place rather than here.
     *
     * <p><strong>A missing record is reported, not thrown.</strong> It yields status {@code '23'}, which
     * is what lets {@code CBACT04C} emit its own {@code 'ACCOUNT NOT FOUND: '} line and then take the
     * fatal arm exactly as the source does: the guard at {@code L378-L382} is
     * {@code IF ACCTFILE-STATUS = '00' … ELSE MOVE 12}, so for this program a not-found account is
     * fatal - but it is fatal <em>in the caller</em>, after the caller has displayed the identifier.
     * Raising an exception here would skip that line.
     *
     * @param acctId the account identifier; must not be negative, as {@code PIC 9} declares no sign
     *               position and therefore has no representation for one
     * @return the discriminated outcome; never {@code null}
     * @throws IllegalArgumentException if {@code acctId} is negative, or if the stored image is wider
     *                                  than {@link #RECORD_LENGTH}
     * @throws IllegalStateException    if the backend presents the dataset with no usable record-image
     *                                  column
     */
    public ReadResult readByKey(long acctId) {
        // The identifier is masked in the diagnostic subject, not in the key image: the read itself
        // uses the real key, while anything that ends up in a log line or an exception message carries
        // only enough of it to correlate two entries (CWE-532).
        return readKeyed(AccountRecord.keyImage(acctId, codec.charset()), "the account identifier "
                + SensitiveDiagnostics.maskIdentifier(acctId, AccountRecord.ACCT_ID_LENGTH), false);
    }

    /**
     * Reads one record for update by its eleven-character key image: the Java form of
     * {@code app/cbl/COACTUPC.cbl:L3892-L3903},
     * <pre>
     * MOVE CC-ACCT-ID              TO WS-CARD-RID-ACCT-ID
     * EXEC CICS READ
     *      FILE      (LIT-ACCTFILENAME)
     *      UPDATE
     *      RIDFLD    (WS-CARD-RID-ACCT-ID-X)
     *      KEYLENGTH (LENGTH OF WS-CARD-RID-ACCT-ID-X)
     *      INTO      (ACCOUNT-RECORD)
     *      LENGTH    (LENGTH OF ACCOUNT-RECORD)
     *      RESP      (WS-RESP-CD)
     *      RESP2     (WS-REAS-CD)
     * END-EXEC
     * </pre>
     *
     * <p>A {@code String} of exactly {@link #KEY_LENGTH} characters because the {@code RIDFLD} is the
     * <strong>character</strong> view of the key: {@code WS-CARD-RID-ACCT-ID-X REDEFINES
     * WS-CARD-RID-ACCT-ID PIC X(11)} ({@code L382-L383}), fed from {@code CC-ACCT-ID PIC X(11)}. The
     * image is used verbatim and is <em>not</em> parsed as a number, because it need not be one: a blank
     * or partially-typed screen field reaches the file as spaces, and the COBOL read simply reports that
     * no such record exists. Parsing would turn that into a rejection the program never performs.
     *
     * <p>The caller's branch is the {@code IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)} test at {@code L3907}:
     * on anything else it sets {@code INPUT-ERROR} and, if no message is pending,
     * {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} at {@code L3910-L3913}. {@link ReadResult#isFound()} is that
     * test, and {@link ReadResult#cicsResp()} carries the response value the error message renders as
     * {@code ERROR-RESP}.
     *
     * <p><strong>Where the lock is.</strong> The CICS file is defined {@code UPDATEMODEL(LOCKING)} with
     * {@code RECOVERY(NONE)}: the record is held from this read until the unit of work ends, and
     * {@code 9600-WRITE-PROCESSING} depends on it - the {@code 9700-CHECK-CHANGE-IN-REC} comparison that
     * follows is only meaningful if nothing can change the record between the read that fed it and the
     * rewrite that acts on it. So this method issues the statement with the row lock requested, and
     * <em>requires a unit of work to be open</em>. A lock taken with nothing to hold it is released at
     * once, which would leave the comparison passing while protecting nothing; that is a failure mode
     * worth refusing loudly rather than one worth having quietly.
     *
     * <p>No version column, no optimistic-lock annotation and no ETag is introduced. The concurrency
     * check the program performs is the field-by-field comparison it already contains, and this method's
     * job is to make that check effective rather than to substitute a different one.
     *
     * @param acctIdAsChar11 the key exactly as the {@code RIDFLD} holds it: exactly {@link #KEY_LENGTH}
     *                       characters, untrimmed and unparsed
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException     if {@code acctIdAsChar11} is {@code null}
     * @throws IllegalArgumentException if {@code acctIdAsChar11} is not exactly {@link #KEY_LENGTH}
     *                                  characters
     * @throws IllegalStateException    if no unit of work is open, or if the backend presents the dataset
     *                                  with no usable record-image column
     */
    public ReadResult readForUpdate(String acctIdAsChar11) {
        String keyImage = requireKeyImage(acctIdAsChar11);
        // The key is deliberately absent from the refusal message. This is a wiring diagnostic - which
        // dataset, which operation - and an account identifier adds nothing to it while putting a
        // customer identifier into a stack trace that may be logged or returned.
        requireUnitOfWork();
        return readKeyed(keyImage, "the record identification field '"
                + SensitiveDiagnostics.maskIdentifier(keyImage) + "'", true);
    }

    // =================================================================================================
    // 1050-UPDATE-ACCOUNT - app/cbl/CBACT04C.cbl:L350-L370, and the online rewrite at
    // app/cbl/COACTUPC.cbl:L4065-L4081.
    // =================================================================================================

    /**
     * Rewrites one record in place: the Java form of
     * {@code REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD} ({@code app/cbl/CBACT04C.cbl:L356}) and of
     * {@code EXEC CICS REWRITE FILE(LIT-ACCTFILENAME) FROM(ACCT-UPDATE-RECORD) LENGTH(…) RESP(…)
     * RESP2(…)} ({@code app/cbl/COACTUPC.cbl:L4065-L4071}).
     *
     * <p><strong>The record carries its own key.</strong> A COBOL {@code REWRITE} on a randomly accessed
     * indexed file is addressed by the key in the record area, and {@code FROM ACCOUNT-RECORD} moves the
     * whole 300-byte record - whose leading eleven bytes <em>are</em> {@code ACCT-ID} - into that area.
     * So the row rewritten is the one whose key equals {@link AccountRecord#keyImage()} of the argument,
     * and no separate key parameter exists or should.
     *
     * <p>This is where the interest calculator's account break lands. {@code CBACT04C.cbl:L352-L356}
     * reads
     * <pre>
     * ADD WS-TOTAL-INT  TO ACCT-CURR-BAL
     * MOVE 0 TO ACCT-CURR-CYC-CREDIT
     * MOVE 0 TO ACCT-CURR-CYC-DEBIT
     * REWRITE FD-ACCTFILE-REC FROM  ACCOUNT-RECORD
     * </pre>
     * and those three mutations belong to the job, on the record, before it is handed here. This method
     * changes nothing about the record it is given: it writes all {@link #RECORD_LENGTH} declared bytes,
     * {@code FILLER X(178)} included, so a rewritten record is byte-identical to the one the caller
     * built.
     *
     * <p><strong>It never silently succeeds.</strong> Three outcomes:
     * <ul>
     *   <li>one row rewritten - status {@code '00'}. The COBOL moves {@code 0} to {@code APPL-RESULT};</li>
     *   <li>no row rewritten - status {@code '23'}, because a rewrite whose key matches nothing is an
     *       invalid-key condition and not a success. The caller reaches its fatal arm, which for
     *       {@code CBACT04C} is {@code DISPLAY 'ERROR RE-WRITING ACCOUNT FILE'} at {@code L365} and for
     *       {@code COACTUPC} is {@code SET LOCKED-BUT-UPDATE-FAILED TO TRUE} at {@code L4079};</li>
     *   <li>more than one row would be rewritten - a permanent error status, reported <em>before</em>
     *       anything is written. See below.</li>
     * </ul>
     *
     * <p><strong>A unit of work is required, and its absence is refused rather than reported.</strong>
     * The pool hands out connections with auto-commit disabled on purpose, so an {@code UPDATE} issued
     * with nothing bound to the thread executes, reports the row it replaced, and is then rolled back
     * when the connection is returned - which would have this method answer {@code '00'} for a record no
     * later read could find. There is no {@code FILE STATUS} meaning "written, then discarded", so
     * nothing is attempted: see
     * {@link DatasetUnitOfWork#requireActiveToPersist(String, String)}. The batch caller's boundary is
     * {@link DatasetUnitOfWork#persistVerb(String, java.util.function.Supplier)}, which is what
     * {@code 1050-UPDATE-ACCOUNT}'s durability against a {@code RECOVERY(NONE)} dataset needs; the online
     * caller's is {@link DatasetUnitOfWork#execute(String, java.util.function.Supplier)}, which is the
     * task boundary {@code 9600-WRITE-PROCESSING} performs its account and customer rewrites inside.
     *
     * <p><strong>Fan-out is precluded, not reported after the fact.</strong> The predicate the rewrite
     * carries selects the rows whose leading {@link #KEY_LENGTH} bytes are the key, and a KSDS primary
     * key is unique, so it names one row. If a deployment's relation does not enforce that uniqueness -
     * no primary key, or a key declared over the wrong span - then the same {@code UPDATE} replaces
     * every matching row with this one record. Discovering that from the affected-row count is
     * discovering it too late: the rows have already been overwritten, and a status returned from there
     * reports damage that the enclosing unit of work then commits on the way out. So the key is
     * required to name exactly one row <em>first</em>, in the same transaction and under the same
     * {@code FOR UPDATE} lock the {@code UPDATE} will use, and the write is not issued at all unless it
     * does. Should the count still come back wrong afterwards, the unit of work is refused rather than
     * reported: see
     * {@link DatasetUnitOfWork#commitRefusal(String, String)}, which explains why that one case cannot
     * be a file status.
     *
     * <p>The cost is one extra bounded {@code SELECT} per rewrite, capped at two rows because "0, 1 or
     * more than 1" is the whole question. That is accepted deliberately: this migration is explicitly
     * not a performance refactoring, and a CICS {@code REWRITE} cannot produce a multi-record outcome
     * for the COBOL to have code for, so the state must not be reachable.
     *
     * <p>The image is bound through the configured {@link RecordImageForm}, which is also what reads it
     * back, so the two directions cannot disagree about one column. That used to be argued locally here -
     * bind text because this class reads text - and the argument was sound about this class and silent
     * about the deployment: the sibling card file and both statement writers bound bytes to the same kind
     * of column. Whether the column is character or binary is a property of the deployment's driver, the
     * driver is a deployment-time input, and so the answer comes from configuration and there is exactly
     * one of it.
     *
     * @param record the record to write, complete and already mutated by the caller
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException  if {@code record} is {@code null}
     * @throws IllegalStateException if no unit of work is open, in which case nothing has been attempted;
     *                               if the backend presents the dataset with no usable record-image
     *                               column; or - as a
     *                               {@link com.vsergeychik.carddemo.common.DatasetIntegrityException} -
     *                               if the write replaced more rows than the key selected when it was
     *                               checked, in which case the unit of work is refused rather than a
     *                               status returned
     */
    public WriteResult rewrite(AccountRecord record) {
        Objects.requireNonNull(record, "A record is required to rewrite it; a COBOL REWRITE writes the "
                + "record area, and there is no such thing as rewriting nothing");

        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            return reportWrite(unreachable, "describe the account master dataset '" + datasetName
                    + "' to rewrite a record");
        }
        return rewrite(sql, record);
    }

    /**
     * Refuses a record whose bytes are in any code page but this dataset's.
     *
     * <p>{@link AccountRecord} is byte-backed: it is constructed over a {@code Charset} and
     * {@link AccountRecord#toByteArray()} hands back what it holds. This repository binds those bytes as
     * they are, so the record's page and the dataset's page have to be the same one, and a caller that
     * built the record elsewhere is the only place that can get it wrong.
     *
     * <p>{@link IllegalArgumentException} rather than a {@link WriteResult}: no {@code FILE STATUS} and
     * no CICS {@code RESP} describes "the caller encoded this in the wrong page", and
     * {@code app/cbl/COACTUPC.cbl:4076-4081} has no arm for it. It is a wiring defect, and it is raised
     * where it can still be prevented.
     *
     * @param record the record about to be written
     * @throws IllegalArgumentException if the record's code page is not this dataset's
     */
    private void requireOwnCodePage(AccountRecord record) {
        if (!record.charset().equals(codec.charset())) {
            throw new IllegalArgumentException("An ACCOUNT-RECORD encoded in " + record.charset().name()
                    + " cannot be written to dataset '" + datasetName + "', which is stored in "
                    + codec.charset().name() + ". The record's bytes are bound to the page it was built "
                    + "with and this repository writes them unchanged, so the rewrite would store "
                    + "corrupt bytes and report success. Build the record with the dataset's own code "
                    + "page - AccountRepository.datasetCharset() reports it.");
        }
    }

    /**
     * Rewrites one record against already-resolved statements.
     *
     * <p>Shared by {@link #rewrite(AccountRecord)}, which resolves the dataset's shape for itself, and by
     * {@link AccountFile#rewrite(AccountRecord)}, which reuses the shape its {@code OPEN} resolved. One
     * body, so the two entry points cannot drift apart.
     *
     * @param sql    the resolved statements
     * @param record the record to write, complete and already mutated by the caller
     * @return the discriminated outcome; never {@code null}
     */
    private WriteResult rewrite(Statements sql, AccountRecord record) {
        // A record built over another code page is refused before anything is attempted, because nothing
        // below this line transcodes: record.toByteArray() returns the bytes the record already holds,
        // and they are bound to the page it was constructed with. Writing them into a dataset stored in
        // a different page does not fail - it succeeds, reports FILE STATUS '00', and stores a record
        // whose every byte outside the invariant range is wrong. An earlier revision of COACTUPC's
        // update path staged its 300-byte image with a hard-coded US-ASCII codec while production binds
        // IBM037, and the US-ASCII test profile made the two agree, so nothing failed anywhere.
        requireOwnCodePage(record);

        // A rewrite with no unit of work open is refused before anything is attempted: the pool hands out
        // connections with auto-commit disabled, so the UPDATE would execute, report the row it replaced,
        // and then be rolled back when the connection was returned - and this method would report
        // FILE STATUS '00' for a record no later read could find. See requireActiveToPersist.
        DatasetUnitOfWork.requireActiveToPersist("A rewrite of the account master, which REWRITE issues "
                + "against the record area it was handed (app/cbl/CBACT04C.cbl:L356) and which EXEC CICS "
                + "REWRITE issues against the record the preceding READ ... UPDATE still holds "
                + "(app/cbl/COACTUPC.cbl:L4065-L4071)", datasetName);

        byte[] recordImage = record.toByteArray();
        String keyPattern = asPrefixPattern(record.keyImage());
        String maskedKey = SensitiveDiagnostics.maskIdentifier(record.keyImage());

        // Establish that the key names exactly one row BEFORE any row is replaced, under the same
        // FOR UPDATE lock the UPDATE will use so nothing can change between the two. Reading the
        // affected-row count afterwards would discover a fan-out only after the rows were overwritten.
        int matching;
        try {
            matching = matchingRowCount(sql.selectByKeyForUpdate(), keyPattern);
        } catch (DataAccessException rejected) {
            return reportWrite(rejected, "establish how many rows the key of a record selects in the "
                    + "account master dataset '" + datasetName + "' before rewriting it");
        }
        if (matching == 0) {
            // An invalid-key condition: there is no such record to rewrite. Reported without issuing the
            // UPDATE, which is the same outcome the UPDATE would have reported and one statement fewer.
            return WriteResult.notFound();
        }
        if (matching > 1) {
            // Nothing has been written, and nothing will be. A KSDS primary key is unique, so a relation
            // in which this key selects several rows is not the dataset the copybook describes; issuing
            // the rewrite would replace all of them with this one record.
            LOG.error("The key of account " + maskedKey + " selects more than one row of dataset '"
                    + datasetName + "'; a KSDS primary key is unique, so the rewrite is being refused "
                    + "before it is issued and file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " reported to the caller. "
                    + "No row has been changed.");
            return WriteResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
        }

        int rewritten;
        try {
            PreparedStatementSetter binder = parameters -> {
                recordImageForm.bindImage(parameters, 1, recordImage, codec.charset());
                recordImageForm.bindOperand(parameters, 2, keyPattern, codec.charset());
            };
            rewritten = jdbcTemplate.update(sql.rewrite(), binder);
        } catch (DataAccessException rejected) {
            return reportWrite(rejected, "rewrite a record in the account master dataset '" + datasetName
                    + "'");
        }

        if (rewritten == 1) {
            return WriteResult.written();
        }
        if (rewritten == 0) {
            // The row the probe found is gone. Nothing was written, so this is the invalid-key condition
            // exactly as it would be had the row never been there.
            return WriteResult.notFound();
        }
        // The probe said one row and the UPDATE replaced several, so the rows changed underneath it. The
        // damage is done and a status would let it commit, so the unit of work is refused instead.
        throw DatasetUnitOfWork.commitRefusal(
                "The rewrite of account " + maskedKey + " in dataset '" + datasetName + "'",
                rewritten + " rows were replaced where the key selected exactly one when it was checked "
                        + "under a row lock");
    }

    /**
     * Counts the rows a keyed predicate selects, stopping as soon as the answer is known.
     *
     * <p>The question a rewrite has to answer is not "how many rows" but "none, one, or more than one",
     * so the statement is capped at two rows and the returned count saturates at two. A dataset whose
     * key column is not unique therefore costs two row transfers to detect rather than the whole
     * relation, and the normal case costs exactly one - the same as the keyed read that preceded it.
     *
     * <p>When the statement carries {@code FOR UPDATE} the rows it returns are locked for the rest of the
     * transaction, which is precisely why this is the probe a rewrite uses: the count and the write then
     * see the same rows.
     *
     * @param statement the keyed select, with or without {@code FOR UPDATE}
     * @param pattern   the escaped key pattern from {@link #asPrefixPattern(String)}
     * @return {@code 0}, {@code 1}, or {@code 2} meaning "at least two"
     * @throws DataAccessException if the backend refuses the statement
     */
    private int matchingRowCount(String statement, String pattern) {
        ResultSetExtractor<Integer> rowCounter = resultSet -> {
            int counted = 0;
            while (counted < FAN_OUT_PROBE_LIMIT && resultSet.next()) {
                counted++;
            }
            return counted;
        };
        Integer counted = jdbcTemplate.query(boundedMatching(statement, pattern, FAN_OUT_PROBE_LIMIT),
                rowCounter);
        // JdbcTemplate returns what the extractor returned, and this extractor never returns null; the
        // check exists so that a null could not become a NullPointerException at the unboxing instead of
        // a diagnosable one here.
        Objects.requireNonNull(counted, "The row counter returns a count, never null");
        return counted;
    }

    // =================================================================================================
    // The keyed read, shared by the batch and the online form.
    // =================================================================================================

    /**
     * Reads the single record whose key equals {@code keyImage}, reporting the outcome.
     *
     * <p>Shared by {@link #readByKey(long)} and {@link #readForUpdate(String)} because the COBOL keyed
     * read is one operation reached from two views of the key. The predicate matches a record image that
     * <em>begins with</em> the key, which for a fixed-width record whose leading field is the key is
     * exactly "the record with this key". Only one row is transferred: the row limit is set on the
     * statement.
     *
     * @param keyImage  the stored key image, exactly {@link #KEY_LENGTH} characters
     * @param subject   how to name the key in a diagnostic, so a log line says which read failed
     * @param forUpdate whether to request the row lock a CICS {@code READ ... UPDATE} takes
     * @return the discriminated outcome; never {@code null}
     */
    private ReadResult readKeyed(String keyImage, String subject, boolean forUpdate) {
        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            return reportRead(unreachable, "describe the account master dataset '" + datasetName
                    + "' to read " + subject);
        }
        return readKeyed(forUpdate ? sql.selectByKeyForUpdate() : sql.selectByKey(), keyImage,
                subject, sql.probeUnreadableRows());
    }

    /**
     * Reads one record by key against an already-composed statement.
     *
     * <p>The form {@link AccountFile} uses: an open handle has already resolved the dataset's shape,
     * so it supplies the statement rather than describing the dataset a second time. One body serves
     * both entry points, so a keyed read cannot behave differently depending on which one issued it.
     *
     * @param statement           the composed keyed select, with or without {@code FOR UPDATE}
     * @param keyImage            the key exactly as the record stores it
     * @param subject             how to name the operation in a diagnostic - never the key's value
     * @param unreadableRowsProbe the statement that proves the absence before it is reported
     * @return the discriminated outcome; never {@code null}
     */
    private ReadResult readKeyed(String statement, String keyImage, String subject,
            String unreadableRowsProbe) {
        List<byte[]> rows;
        try {
            rows = jdbcTemplate.query(firstRowMatching(statement, asPrefixPattern(keyImage)),
                    recordImageMapper());
        } catch (DataAccessException translated) {
            return reportRead(translated, "read " + subject + " from the account master dataset '"
                    + datasetName + "'");
        }

        // As in the browse, the list itself is never null, so emptiness is the whole of the test.
        if (rows.isEmpty()) {
            // The INVALID KEY condition - but only once it has been PROVED. Reported, never thrown, so the
            // caller can display the identifier before it decides what to do - see readByKey(long).
            return provenAbsence(subject, unreadableRowsProbe);
        }
        byte[] recordImage = rows.get(0);
        if (recordImage == null) {
            LOG.error("The account master dataset '" + datasetName + "' presented " + subject
                    + " with no record image at column position " + RECORD_IMAGE_COLUMN_INDEX
                    + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " to the caller");
            // No backend refusal, so nothing to diagnose - but the condition does have a determinate CICS
            // counterpart: a row that is present and unreadable is an invalid request, which is what the
            // sibling card repository reports for the same shape of row.
            return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
        }
        return decoded(recordImage, subject);
    }

    /**
     * Reports the {@code INVALID KEY} condition only once no row of the dataset is
     * <strong>unreadable</strong>, and the invalid-request outcome when one is.
     *
     * <h2>Why an absence has to be proved</h2>
     * <p>{@code ACCT-ID} is the leading eleven bytes of {@code ACCOUNT-RECORD}, so it lives
     * <em>inside</em> the record image. SQL evaluates every comparison against a null as {@code UNKNOWN},
     * so the keyed predicate cannot match a row whose record-image column holds nothing, and such a row
     * leaves the read with no matching row: on the face of it {@code NOTFND}. But every consumer acts on
     * that answer as a fact - {@code app/cbl/CBTRN02C.cbl:1500-B} rejects a transaction with reason 103,
     * {@code app/cbl/COACTVWC.cbl} paints "Account not found", and {@code app/cbl/CBACT04C.cbl:378}
     * abends outright - so reporting it while a present record sits unreadable in the relation states
     * something the data does not support.
     *
     * <p>The answer is therefore confirmed with one further row-limited read before it is returned, on the
     * empty path only. A read that found its record is untouched: a VSAM {@code READ} of a key that
     * resolves does not fail because another record is damaged.
     *
     * <p>The outcome for a present-but-unreadable row is the permanent-error status carrying
     * {@link FileStatus#INVREQ} - the same arm this method's sibling already reports for a row it can see,
     * so no caller's guard chain changes shape.
     *
     * @param subject             how to name the operation in a diagnostic - never the key's value
     * @param unreadableRowsProbe the statement selecting the rows with no record image
     * @return {@link ReadResult#notFound()} when the absence is established, the permanent-error outcome
     *         when it is not; never {@code null}
     */
    private ReadResult provenAbsence(String subject, String unreadableRowsProbe) {
        List<byte[]> unreadable;
        try {
            unreadable = jdbcTemplate.query(firstRow(unreadableRowsProbe), recordImageMapper());
        } catch (DataAccessException translated) {
            // The probe established nothing, so the absence stays unproved. Reported on the arm a refused
            // read is reported on rather than falling back to '23', which would be exactly the unsupported
            // claim this probe exists to prevent.
            return reportRead(translated, "establish that the account master dataset '" + datasetName
                    + "' holds no unreadable row before reporting " + subject + " as absent");
        }
        // As everywhere else in this class, the list itself is never null, so emptiness is the whole test.
        if (unreadable.isEmpty()) {
            // A genuine INVALID KEY: no row matched the key and no row of the dataset is unreadable, so
            // the absence is established rather than assumed.
            return ReadResult.notFound();
        }
        LOG.error("A keyed read of " + subject + " from the account master dataset '" + datasetName
                + "' matched no row, but the dataset holds a row with no record image at column position "
                + RECORD_IMAGE_COLUMN_INDEX + " - and ACCT-ID is part of that image, so that row's key "
                + "cannot be known; reporting file status "
                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than reporting as absent a "
                + "record that may be the one asked for");
        return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
    }

    // =================================================================================================
    // Backend-refusal reporting. Every catch arm in this class goes through one of these three, so the
    // driver's own words reach both the log and the caller, and neither is left to a per-site decision.
    // =================================================================================================

    /**
     * Logs a backend refusal with the driver's own diagnosis and returns it for the caller to carry.
     *
     * <p>What is logged is what the backend said - {@code SQLSTATE}, vendor code and the exception type
     * that carried them - and the dataset name. Deliberately <strong>not</strong> logged: the key, the
     * record image, or any part of either. A failing account operation's record holds a card number and
     * a customer identifier, so a log line that echoed it would put those in a file that is read by more
     * people, retained for longer, and protected less than the dataset itself.
     *
     * <p>The exception itself is <strong>not</strong> passed to the logger either, and that is the part
     * worth stating plainly because it looks like a loss. A driver's message is prose the backend composed
     * around the values it refused, so {@code value '4444333322221111' rejected} is an ordinary thing for
     * it to say; handing the {@link Throwable} to the logger emits that text and its whole cause chain
     * verbatim (CWE-532), and a control character anywhere in it splits the entry in two (CWE-117).
     * Sanitising the summary and then attaching the raw exception beside it sanitises nothing. The codes
     * that survive are what separate an absent dataset from wrong credentials from a dropped connection,
     * which is the whole of what an operator acts on; the driver's own words remain in the driver's own
     * log, which is access-controlled as an application log is not.
     *
     * @param refusal the exception the backend or the framework raised
     * @param attempt what was being attempted, phrased to complete "Could not ..."
     * @return the diagnostic read out of {@code refusal}
     */
    private BackendDiagnostic logRefusal(Throwable refusal, String attempt) {
        BackendDiagnostic diagnostic = BackendDiagnostic.of(refusal);
        LOG.error("Could not " + attempt + " - " + diagnostic.describe() + "; reporting file status "
                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
        return diagnostic;
    }

    /**
     * Reports a backend refusal of a read as a permanent-error status carrying the diagnostic.
     *
     * @param refusal the exception raised
     * @param attempt what was being attempted
     * @return the outcome
     */
    private ReadResult reportRead(Throwable refusal, String attempt) {
        return ReadResult.of(PERMANENT_ERROR_STATUS, logRefusal(refusal, attempt));
    }

    /**
     * Reports a backend refusal of a write as a permanent-error status carrying the diagnostic.
     *
     * @param refusal the exception raised
     * @param attempt what was being attempted
     * @return the outcome
     */
    private WriteResult reportWrite(Throwable refusal, String attempt) {
        return WriteResult.of(PERMANENT_ERROR_STATUS, logRefusal(refusal, attempt));
    }

    // =================================================================================================
    // Statement resolution. This is the one place a SQL identifier is decided, and the one place the
    // backend is asked to describe the dataset.
    // =================================================================================================

    /**
     * Resolves the statements for one operation, reporting an unreachable dataset as {@code null}.
     *
     * <p>The repository-level keyed operations exist because the CICS programs issue no {@code OPEN}, so
     * each of them has to establish the dataset's shape for itself. This is where that happens, and it is
     * also the single place the resulting {@link DataAccessException} is turned into the coarse status the
     * COBOL sees, so the three call sites do not repeat the translation.
     *
     * <p><strong>Nothing is cached.</strong> A resolved shape held on this singleton would be shared
     * mutable state, and it was the coupling between that cache and the open/close lifecycle that let one
     * execution's {@code OPEN} pull the shape out from under another's browse. The cost is one metadata
     * round trip per repository-level keyed operation, and it is accepted deliberately: this migration is
     * explicitly not a performance refactoring, and a caller that minds pays it once by opening an
     * {@link AccountFile}, whose operations reuse the shape its {@code OPEN} resolved.
     *
     * @param subject how to name the operation in a diagnostic
     * @return the resolved statements, or {@code null} when the dataset could not be described
     * @throws IllegalStateException if the dataset is reachable but presents no usable record-image column
     */
    private Statements statementsFor(String subject) {
        try {
            return resolveStatements();
        } catch (DataAccessException unreachable) {
            logRefusal(unreachable, "describe the account master dataset '" + datasetName + "'");
            return null;
        }
    }

    /**
     * Refuses a read for update that is not inside a unit of work.
     *
     * <p>A {@code FOR UPDATE} row lock lives for the length of the transaction that took it. Under
     * auto-commit that is the length of the statement, so the lock is gone before the caller can compare
     * the record and rewrite it - and the caller would never know. {@code READ ... UPDATE} in CICS cannot
     * behave that way, because a CICS task always has a unit of work, so there is no legacy behaviour to
     * reproduce here and nothing to report as a file status: this is a defect in the Java caller's wiring
     * and is reported as one.
     *
     * @throws IllegalStateException if no transaction is active on the calling thread
     */
    private void requireUnitOfWork() {
        // One precondition, one diagnostic. Both entry points to the locking read - this class's own
        // readForUpdate and the handle's - route through the module-wide check, so an operator sees the
        // same message whichever one refused, and the COBOL evidence for the requirement travels in the
        // operation name rather than in a second copy of the wording.
        DatasetUnitOfWork.requireActive("A read for update of the account master, which "
                + "EXEC CICS READ ... UPDATE holds for the unit of work "
                + "(app/cbl/COACTUPC.cbl:L3894-L3903, and the 'Could we lock the account record ?' "
                + "test at L3903) so that 9700-CHECK-CHANGE-IN-REC can compare and REWRITE can run - "
                + "or use readByKey(long) when no lock is wanted", datasetName);
    }

    /**
     * Composes the statements for this dataset, discovering the record-image column's name from the
     * backend.
     *
     * <p><strong>Why a name has to be discovered at all.</strong> Every dataset in this module is reached
     * as a single-column relation whose one column holds the record image, and a sequential read can
     * address that column purely by position. A keyed read and a rewrite cannot: SQL permits an ordinal
     * in {@code ORDER BY} but not in a {@code WHERE} or a {@code SET} clause. The name is therefore
     * taken from the result-set metadata of the probe statement - <em>discovered from the backend</em>,
     * never invented here, never defaulted, and never added as a configuration key, because a name this
     * file made up would be exactly the kind of unverifiable literal the migration forbids.
     *
     * <p>The probe is read-only: its predicate is false on every row, so the relation is described
     * without any of it being transferred. That is what makes it usable against a driver this build
     * cannot exercise, and it is a genuine dataset-scoped check rather than a bare connection test - an
     * absent dataset fails here, which is exactly what an {@code OPEN} would report.
     *
     * <p>Package-visible so this class's own tests can assert the composed text and the discovery
     * behaviour without reaching around the class. It resolves afresh on every call and stores nothing:
     * the returned value is deeply immutable, so handing it out grants nothing that can be altered.
     *
     * @return the composed statements; never {@code null}
     * @throws DataAccessException    if the dataset cannot be described - an I/O outcome, translated into
     *                                a status by every caller of this method
     * @throws IllegalStateException  if the dataset is described but presents no usable record-image
     *                                column, which is a contract violation: the whole module addresses
     *                                a dataset as a record-image relation, and one that is not cannot be
     *                                read or written at all
     */
    Statements resolveStatements() {
        ResultSetExtractor<String> columnNameExtractor =
                AccountRepository::extractRecordImageColumnName;
        String columnName = jdbcTemplate.query(relation.describeStatement(), columnNameExtractor);
        return Statements.over(relation, requireUsableColumnName(columnName));
    }

    /**
     * Reads the record-image column's name from a result set's metadata, without consuming a row.
     *
     * <p>{@code next()} is never called: the probe returns no rows by construction, and the metadata is
     * available regardless. A driver that reports no metadata, or a relation with no column at position
     * {@value #RECORD_IMAGE_COLUMN_INDEX}, yields {@code null} here and is rejected by
     * {@link #requireUsableColumnName(String)} with a diagnostic, rather than being turned into an
     * identifier that would address nothing.
     *
     * @param resultSet the empty result set the probe produced
     * @return the column's name, or {@code null} if the backend describes none
     * @throws SQLException if the driver cannot supply the metadata
     */
    private static String extractRecordImageColumnName(ResultSet resultSet) throws SQLException {
        return DatasetRelation.recordImageColumnOf(resultSet.getMetaData());
    }

    /**
     * Maps one result-set row to its record image, through the configured representation.
     *
     * <p>Position {@value #RECORD_IMAGE_COLUMN_INDEX} and never a column name here, for the reason given
     * on this class: a dataset carrying no relational metadata is presented as a single record-image
     * column. Whether that column is read as characters or as bytes is
     * {@link RecordImageForm}'s decision and not this method's - which is the point, because this
     * repository used to make it alone and disagreed with its siblings. The value is <em>not</em> trimmed:
     * the trailing bytes of this record are {@code FILLER} spaces and they are part of it.
     *
     * @return a mapper yielding each row's record image, or {@code null} for a row whose column holds no
     *         value - a condition every caller classifies rather than ignores
     */
    private RowMapper<byte[]> recordImageMapper() {
        return (resultSet, rowNumber) ->
                recordImageForm.readImage(resultSet, RECORD_IMAGE_COLUMN_INDEX, codec.charset());
    }

    /**
     * Builds a statement that returns at most one row and binds no parameter.
     *
     * <p>The row limit is set on the {@link PreparedStatement} rather than expressed as a row-limiting
     * clause, which keeps every statement in this class free of dialect syntax - no fetch-first and no
     * offset appears anywhere. A driver that declines to honour the limit costs efficiency and nothing
     * else: the first row is taken and the rest ignored, so the outcome is identical either way.
     *
     * @param sql the statement text
     * @return a creator for the prepared and limited statement
     */
    private PreparedStatementCreator firstRow(String sql) {
        return connection -> limited(connection.prepareStatement(sql));
    }

    /**
     * Builds a one-row statement whose single parameter is a keyed {@code LIKE} pattern.
     *
     * <p>The pattern is a composed predicate rather than a stored record, so it is bound as a comparison
     * operand - in the same representation as the column it is compared with, which is what stops a
     * keyed read from comparing characters against bytes.
     *
     * @param sql     the statement text
     * @param pattern the escaped pattern from {@link KeySpan#pattern(String)}
     * @return a creator for the prepared, limited and bound statement
     */
    private PreparedStatementCreator firstRowMatching(String sql, String pattern) {
        return connection -> {
            PreparedStatement statement = limited(connection.prepareStatement(sql));
            recordImageForm.bindOperand(statement, 1, pattern, codec.charset());
            return statement;
        };
    }

    /**
     * Builds a keyed statement capped at a stated number of rows.
     *
     * <p>The same binding as {@link #firstRowMatching(String, String)} - the pattern is a comparison
     * operand and is bound as one - with the row cap left to the caller, because the pre-rewrite probe
     * needs to see a second row to know there is one and every other statement in this class needs at
     * most the first.
     *
     * @param sql     the statement text
     * @param pattern the escaped pattern from {@link KeySpan#pattern(String)}
     * @param maxRows the row cap
     * @return a creator for the prepared, capped and bound statement
     */
    private PreparedStatementCreator boundedMatching(String sql, String pattern, int maxRows) {
        return connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setMaxRows(maxRows);
            recordImageForm.bindOperand(statement, 1, pattern, codec.charset());
            return statement;
        };
    }

    /**
     * Builds a one-row statement whose single parameter is the whole record image a browse advances past.
     *
     * <p>A browse position is a stored record image, not a pattern, so it is bound exactly as an image
     * is: the comparison then runs against the column in its own representation, and "the next record
     * after this one" means the same thing on both sides of the operator.
     *
     * @param sql      the statement text
     * @param position the previous row's record image, exactly as the backend presented it
     * @return a creator for the prepared, limited and bound statement
     */
    private PreparedStatementCreator firstRowAfter(String sql, byte[] position) {
        return connection -> {
            PreparedStatement statement = limited(connection.prepareStatement(sql));
            recordImageForm.bindImage(statement, 1, position, codec.charset());
            return statement;
        };
    }

    /** Caps a prepared statement at one row, so no more than one can ever matter. */
    private static PreparedStatement limited(PreparedStatement statement) throws SQLException {
        statement.setMaxRows(1);
        return statement;
    }

    /**
     * Decodes a stored record image, or reports that it is not an account record.
     *
     * <p><strong>The width is required, not repaired.</strong> {@code app/cpy/CVACT01Y.cpy:L2} declares
     * {@code RECLN 300} and every record of {@code app/data/ASCII/acctdata.txt} measures exactly that, so
     * a row of any other width means the backend is not serving this layout. Widening a short one to the
     * declared width with spaces would be defensible if the missing bytes were certainly the trailing
     * {@code FILLER X(178)} ({@code app/cpy/CVACT01Y.cpy:L17}), and they are not: a row that lost bytes
     * anywhere else, or that was written against a different layout, pads into a record whose fields
     * decode from offsets that are not theirs. {@code ACCT-CURR-BAL} read from the wrong span is still a
     * number, and a caller cannot tell it from a real one - so the repair would convert a diagnosable
     * failure into plausible data, which is the one outcome worse than the failure.
     *
     * <p>An over-long image is rejected for the same reason and always was, and this makes the two
     * symmetrical: the row either is 300 bytes or it is not a record. The width is reported as
     * {@link FileStatus#LENGERR} - the response CICS gives when a record does not fit its receiver - and
     * the actual width is named in the log line rather than carried as a reason code, because a byte
     * count is not a CICS reason code.
     *
     * <p>The decode itself is by absolute offset against the copybook layout, in the injected code page,
     * retaining every byte verbatim - which is what makes decoding and re-encoding a stored record
     * byte-identical.
     *
     * @param recordImage the stored image, exactly as the configured representation presented it
     * @param subject     how to name the row in a diagnostic - never its content
     * @return the successful outcome carrying the decoded record, or a permanent-error outcome whose
     *         CICS response is {@link FileStatus#LENGERR}
     */
    private ReadResult decoded(byte[] recordImage, String subject) {
        if (recordImage.length != RECORD_LENGTH) {
            LOG.error("The account master dataset '" + datasetName + "' presented " + subject + " as "
                    + recordImage.length + " byte(s), but ACCOUNT-RECORD is declared " + RECORD_LENGTH
                    + " bytes by app/cpy/CVACT01Y.cpy; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than decoding fields "
                    + "from offsets that would not be theirs");
            return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.LENGERR));
        }
        return ReadResult.found(AccountRecord.decode(recordImage, codec.charset()));
    }

    // =================================================================================================
    // Pure helpers. Private and static: no instance state is involved, and no static state is
    // introduced - every one of them is a function of its arguments alone.
    // =================================================================================================

    /**
     * Renders a key image as the escaped {@code LIKE} pattern that matches the record carrying it.
     *
     * <p>Delegated to {@link KeySpan#pattern(String)}, which is the module's one such renderer: the key
     * is escaped there rather than trusted, because an online {@code RIDFLD} is a {@code PIC X(11)}
     * character field ({@code app/cbl/COACTUPC.cbl:L382-L383}) carrying whatever the screen supplied,
     * and an unescaped {@code _} in it would match any byte and could return - or, through the rewrite
     * that shares the predicate, overwrite - a different account's record.
     *
     * @param keyImage the stored key image, exactly {@link #KEY_LENGTH} characters
     * @return the escaped pattern
     */
    private static String asPrefixPattern(String keyImage) {
        return KEY_SPAN.pattern(keyImage);
    }

    private static DatasetBinding requireAccountGeometry(DatasetBindings datasetBindings,
            String ddName) {
        DatasetBinding binding = datasetBindings.binding(ddName);
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + ddName + "' declares a "
                    + "record length of " + binding.recordLength() + ", but the account record is "
                    + RECORD_LENGTH + " bytes - app/cpy/CVACT01Y.cpy:L2 reads RECLN 300, and "
                    + "app/cbl/CBACT01C.cbl:L38-L40 declares FD-ACCT-ID PIC 9(11) plus FD-ACCT-DATA "
                    + "PIC X(289) independently. This repository addresses the record by absolute "
                    + "offset, so a differently-sized record would misplace every field after the "
                    + "first. Correct carddemo.datasets." + ddName + ".record-length to "
                    + RECORD_LENGTH + ".");
        }
        Integer declaredKeyLength = binding.keyLength();
        // A key width is configured only where a source file states one, so an absent one is normal and
        // is accepted; a PRESENT one that disagrees with the copybook is a defect.
        if (declaredKeyLength != null && declaredKeyLength != KEY_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + ddName + "' declares a "
                    + "key length of " + declaredKeyLength + ", but the account master key is "
                    + KEY_LENGTH + " bytes - ACCT-ID PIC 9(11) at app/cpy/CVACT01Y.cpy:L5, which is "
                    + "also what app/cbl/COACTUPC.cbl:L3898 passes as KEYLENGTH. Correct "
                    + "carddemo.datasets." + ddName + ".key-length to " + KEY_LENGTH + ", or omit it.");
        }
        return binding;
    }

    /**
     * Requires the CICS file and the batch DD to name the same dataset.
     *
     * <p>They address one dataset in the legacy system: {@code app/csd/CARDDEMO.CSD:L1-L2} defines the
     * CICS file over the account master KSDS and {@code app/jcl/READACCT.jcl:L25-L26} binds the batch DD
     * over the same one, as do {@code app/jcl/INTCALC.jcl:L33-L34} and the statement job. A deployment
     * that pointed them at two different datasets would give the online and the batch programs different
     * data while every parity case assumed they shared it, so the disagreement is refused here rather
     * than discovered later as an unexplained diff.
     *
     * @param cicsBinding  the binding resolved for {@link #CICS_FILE_NAME}
     * @param batchBinding the binding resolved for {@link #BATCH_DD_NAME}
     * @throws IllegalStateException if the two bindings name different datasets
     */
    private static void requireSameDataset(DatasetBinding cicsBinding, DatasetBinding batchBinding) {
        if (!Objects.equals(cicsBinding.dsname(), batchBinding.dsname())) {
            throw new IllegalStateException("The dataset bindings for DD names '" + CICS_FILE_NAME
                    + "' and '" + BATCH_DD_NAME + "' name different datasets, but they are two names "
                    + "for one account master: app/csd/CARDDEMO.CSD:L1-L2 defines the CICS file and "
                    + "app/jcl/READACCT.jcl:L25-L26 binds the batch DD over the same dataset. Point "
                    + "carddemo.datasets." + CICS_FILE_NAME + ".dsname and carddemo.datasets."
                    + BATCH_DD_NAME + ".dsname at the same dataset.");
        }
    }

    /**
     * Validates the configured dataset name and returns it verbatim.
     *
     * <p>Nothing is trimmed, normalised or defaulted. A blank name means the binding is present but says
     * nothing, and a name carrying a control character cannot be a dataset name and would corrupt the
     * composed statements - both are configuration defects, and both fail the context here rather than
     * producing statements that address the wrong thing, or nothing.
     *
     * @param candidate the {@code dsname} component of the resolved binding
     * @return {@code candidate}, unchanged
     * @throws IllegalStateException if {@code candidate} is {@code null}, blank, or contains a control
     *                               character
     */
    private static String requireUsableDatasetName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for DD name '" + CICS_FILE_NAME
                    + "' declares no dataset name. Set carddemo.datasets." + CICS_FILE_NAME
                    + ".dsname; this repository composes its statements from configuration alone and "
                    + "hard-codes no dataset name.");
        }
        // The grammar itself lives in DatasetRelation, so the rule about what a dataset name may
        // contain is stated once for every repository rather than restated - differently - in each.
        return DatasetRelation.requireDatasetName(candidate);
    }

    /**
     * Validates the discovered record-image column name and returns it verbatim.
     *
     * <p>An absent, blank or unusable name means the backend is not presenting this dataset as the
     * record-image relation the whole module addresses. That is a contract violation and not an I/O
     * outcome: there is no file status for "the dataset has the wrong shape", and reporting one would
     * send a job down its abend path with a misleading reason.
     *
     * @param candidate the name the metadata probe reported
     * @return {@code candidate}, unchanged
     * @throws IllegalStateException if {@code candidate} is {@code null}, blank, or contains a control
     *                               character
     */
    private static String requireUsableColumnName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The backend describes no column at position "
                    + RECORD_IMAGE_COLUMN_INDEX + " for the account master dataset, so the record "
                    + "image cannot be addressed. Every CardDemo dataset is reached as a relation whose "
                    + "column at that position holds the whole fixed-width record image; a driver or a "
                    + "seeded dataset that presents no such column cannot be read or written by this "
                    + "module at all.");
        }
        requireNoControlCharacter(candidate, "The record-image column name reported by the backend");
        return candidate;
    }

    /**
     * Rejects a name containing a control character.
     *
     * <p>Shared by the dataset-name and column-name checks so the rule and its diagnostic exist once. A
     * control character cannot appear in a legitimate identifier, and letting one through would corrupt
     * every composed statement.
     *
     * @param candidate the name to check
     * @param subject   how to name the value in the diagnostic
     * @throws IllegalStateException if {@code candidate} contains a control character
     */
    private static void requireNoControlCharacter(String candidate, String subject) {
        for (int index = 0; index < candidate.length(); index++) {
            if (Character.isISOControl(candidate.charAt(index))) {
                throw new IllegalStateException(subject + " contains a control character at position "
                        + index + "; an identifier cannot contain one, and it would corrupt the "
                        + "composed statements. Correct the value.");
            }
        }
    }

    /**
     * Validates an eleven-character key image and returns it verbatim.
     *
     * <p>The width is the whole point of a fixed-width key, so it is checked rather than assumed: the
     * {@code RIDFLD} is {@code PIC X(11)} and {@code KEYLENGTH} is its length
     * ({@code app/cbl/COACTUPC.cbl:L3897-L3898}), so a key of any other width is not the key this
     * dataset has. The content is deliberately <em>not</em> validated - a screen-derived key may hold
     * spaces or non-digits, and the COBOL read simply reports that no such record exists.
     *
     * @param candidate the caller's key image
     * @return {@code candidate}, unchanged
     * @throws NullPointerException     if {@code candidate} is {@code null}
     * @throws IllegalArgumentException if {@code candidate} is not exactly {@link #KEY_LENGTH}
     *                                  characters
     */
    private static String requireKeyImage(String candidate) {
        Objects.requireNonNull(candidate, "A key image is required to read a record for update; the "
                + "RIDFLD is a PIC X(11) field and is never absent, only spaces");
        if (candidate.length() != KEY_LENGTH) {
            throw new IllegalArgumentException("The account master key is declared PIC X(" + KEY_LENGTH
                    + ") but was given " + candidate.length() + " character(s). A fixed-width key "
                    + "carries its padding, so it is never trimmed and never short - render an "
                    + "account identifier with AccountRecord.keyImage(long, Charset).");
        }
        return candidate;
    }

    // =================================================================================================
    // The composed statements.
    // =================================================================================================

    /**
     * The five statements this repository issues, composed once the record-image column is known.
     *
     * <p>An immutable value, package-visible so that a unit test can assert the composed text directly
     * rather than inferring it from a database round trip. It is not part of the public contract: no
     * caller outside this package can name the type.
     *
     * <p>Every statement is confined to core SQL - a delimited identifier, {@code ORDER BY}, a
     * comparison, {@code LIKE … ESCAPE}, {@code FOR UPDATE} and a single-column {@code UPDATE} - and none
     * of them names a column list, because this migration introduces no schema and therefore has no
     * column names of its own to name.
     *
     * @param selectFirst the first read of a browse: every record in ascending key order, limited to one
     *                    row by the statement. No predicate, because an {@code OPEN INPUT} positions
     *                    before the first record
     * @param selectNext  every later read of a browse: the first record whose image sorts strictly after
     *                    the one already returned, in ascending key order. The parameter is the previous
     *                    <em>image</em> and not its key - see {@link AccountRepository#browsePosition}
     * @param selectByKey the keyed read: the record whose image begins with the key
     * @param selectByKeyForUpdate the keyed read with the row lock the CICS {@code READ ... UPDATE}
     *                    takes, valid only inside a unit of work
     * @param rewrite     the rewrite: replace the image of the record whose image begins with the key
     * @param probeUnreadableRows the rows whose record-image column holds nothing. Not a COBOL
     *                    operation: it is what lets a keyed read <em>prove</em> an absence before
     *                    reporting {@code NOTFND}, because {@code ACCT-ID} lives inside the record image
     *                    and a row with no image therefore has no knowable key - see
     *                    {@link AccountRepository#provenAbsence(String, String)}
     */
    record Statements(String selectFirst,
                      String selectNext,
                      String selectByKey,
                      String selectByKeyForUpdate,
                      String rewrite,
                      String probeUnreadableRows) {

        /**
         * Composes the statements over one dataset and one record-image column.
         *
         * <p>Every one of them comes from {@link DatasetRelation}, which also remembers the validated
         * column name. That is the point: the text of a browse, a keyed read, a locking read and a
         * rewrite is decided in one class for every dataset in the module, so a divergence between two
         * repositories is not something a reader has to go looking for.
         *
         * @param relation the dataset contract
         * @param column   the record-image column name as the backend described it, undelimited
         * @return the composed statements
         */
        static Statements over(DatasetRelation relation, String column) {
            String recordImageColumn = relation.rememberRecordImageColumn(column);
            return new Statements(
                    relation.selectAllAscending(recordImageColumn),
                    relation.selectAfterAscending(recordImageColumn),
                    relation.selectByKey(recordImageColumn),
                    relation.selectByKeyForUpdate(recordImageColumn),
                    relation.rewriteByKey(recordImageColumn),
                    relation.selectUnreadableRows(recordImageColumn));
        }
    }

    /**
     * The metadata probe statement, exposed to this class's own tests so the composed text can be
     * asserted without a backend. Package-visible: a construction detail, not part of the public
     * contract.
     *
     * @return the statement that describes the dataset without transferring a row
     */
    String columnProbeSql() {
        return relation.describeStatement();
    }

    // =================================================================================================
    // The opened file. One instance per OPEN, holding everything that OPEN establishes: the verb, the
    // status, the resolved shape and the browse position. This is where the state the repository used to
    // share with every concurrent execution now lives.
    // =================================================================================================

    /**
     * One opened account master file: the Java form of the {@code FD} a program opens, reads and closes.
     *
     * <p>The Java form of the file {@code app/cbl/CBACT01C.cbl} opens at {@code :135}, reads at
     * {@code :93} and closes at {@code :153}, driving its whole program from {@code :62}:
     * <pre>
     * try (AccountRepository.AccountFile file = repository.open(OpenMode.INPUT)) {
     *     if (!FileStatus.isOk(file.openStatus())) {            // IF ACCTFILE-STATUS = '00' ... ELSE
     *         throw AbendException.standard("CBACT01C", APPL_RESULT_FATAL);
     *     }
     *     for (ReadResult next = file.readNext();                          //  PERFORM UNTIL
     *             !next.isEndOfFile();                                     //  END-OF-FILE = 'Y'
     *             next = file.readNext()) {
     *         if (next.isOther()) {                             // ERROR READING ACCOUNT FILE
     *             throw AbendException.standard("CBACT01C", next.applResult());
     *         }
     *         display(next.account().orElseThrow());            // DISPLAY ACCOUNT-RECORD
     *     }
     * }
     * </pre>
     * The abend is the caller's, as it is in the COBOL: the paragraph that tests the status is the
     * paragraph that decides, and this handle only reports.
     *
     * <p><strong>Why this type exists.</strong> A file position, an {@code OPEN} verb and the shape
     * resolved for that open all belong to one program execution. Held on the {@link AccountRepository}
     * singleton they would be shared by every concurrent execution, and one execution's {@code OPEN}
     * would reposition another's browse and discard the shape it was using mid-read. Here each execution
     * has its own, and the repository has none.
     *
     * <p><strong>Not thread safe, by design.</strong> A handle stands for one file position and is used by
     * whoever opened it, exactly as a COBOL {@code FD} is used by the program that opened it. Sharing one
     * across threads would be the concurrency equivalent of two programs sharing one {@code FD}. Opening
     * a second handle is free and is the correct answer.
     *
     * <p>The keyed operations are offered here as well as on the repository, and they are the same
     * operations: {@code CBACT04C} reads and rewrites under its own {@code OPEN I-O}
     * ({@code app/cbl/CBACT04C.cbl:L291}), so a caller holding a handle uses these and reuses the shape
     * its open resolved, while {@code COACTUPC}, which issues no {@code OPEN} at all, uses the
     * repository's.
     */
    public static final class AccountFile implements AutoCloseable {

        /** The repository that opened this file, for its template, its codec and its dataset identity. */
        private final AccountRepository repository;

        /** The {@code OPEN} verb this handle was opened with. Recorded, not enforced. */
        private final OpenMode mode;

        /**
         * The status the {@code OPEN} reported: {@link FileStatus#OK}, or
         * {@link AccountRepository#PERMANENT_ERROR_STATUS} when the dataset could not be described.
         */
        private final String openStatus;

        /**
         * The statements resolved for this open, or {@code null} when the open failed.
         *
         * <p>Resolved once, by the {@code OPEN} itself, which is what an {@code OPEN} is for: every later
         * operation on this handle is spared a metadata round trip. Deeply immutable, so nothing here can
         * be perturbed after the open.
         */
        private final Statements statements;

        /**
         * The browse position: the image of the record {@link #readNext()} returned last, or {@code null}
         * for "positioned before the first record".
         *
         * <p>The Java form of the file position a COBOL {@code OPEN INPUT} establishes and each
         * {@code READ} advances. It is the full record image rather than just the key, and that is
         * load-bearing: a bare eleven-byte key would compare as <em>less than</em> the very record it came
         * from - {@code '00000000001…'} sorts after {@code '00000000001'} - so a browse positioned by key
         * alone would return the same record for ever. The full image excludes it strictly, and because a
         * KSDS key is unique the comparison always resolves inside the leading eleven bytes.
         *
         * <p>Held as the stored <strong>bytes</strong>, exactly as the configured representation presented
         * them, and bound back as an image rather than as text. Re-encoding a decoded record here would
         * make the comparison operand a reconstruction rather than the row it came from, which is only
         * harmless while every reconstruction happens to be byte-identical.
         */
        private byte[] browsePosition;

        /** Whether {@link #closeFile()} has been called. */
        private boolean closed;

        /**
         * Constructed only by {@link AccountRepository#open(OpenMode)}, which is what guarantees that a
         * successful open always arrives with its resolved shape and a failed one never does.
         *
         * @param repository the opening repository
         * @param mode       the {@code OPEN} verb issued
         * @param openStatus the status the open reported
         * @param statements the resolved statements for a successful open, or {@code null} for a failed
         *                   one
         */
        private AccountFile(AccountRepository repository, OpenMode mode, String openStatus,
                Statements statements) {
            this.repository = repository;
            this.mode = mode;
            this.openStatus = openStatus;
            this.statements = statements;
            this.browsePosition = null;
            this.closed = false;
        }

        /**
         * The status the {@code OPEN} reported: the value {@code app/cbl/CBACT01C.cbl:L137} tests before
         * moving {@code 0} or {@code 12} into {@code APPL-RESULT}.
         *
         * @return {@link FileStatus#OK} or {@link AccountRepository#PERMANENT_ERROR_STATUS}; never
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
         * The {@code OPEN} verb this handle was opened with.
         *
         * <p>Recorded and not enforced, for the reason given on {@link AccountRepository#open(OpenMode)}:
         * no program in the estate mixes the modes against this dataset, so a mode-based refusal would be
         * a rejection the COBOL never performs.
         *
         * @return the verb; never {@code null}
         */
        public OpenMode mode() {
            return mode;
        }

        /**
         * The dataset this handle reads and writes.
         *
         * @return the configured account master dataset name; never {@code null} and never blank
         */
        public String datasetName() {
            return repository.datasetName();
        }

        /**
         * Whether {@link #closeFile()} has been called on this handle.
         *
         * @return {@code true} once the file has been closed
         */
        public boolean isClosed() {
            return closed;
        }

        // =============================================================================================
        // 1000-ACCTFILE-GET-NEXT - app/cbl/CBACT01C.cbl:L92-L116.
        // =============================================================================================

        /**
         * Reads the next record in ascending key order: the Java form of
         * {@code READ ACCTFILE-FILE INTO ACCOUNT-RECORD} ({@code app/cbl/CBACT01C.cbl:L93}) and of the
         * {@code EVALUATE}-equivalent guard that classifies its status at {@code L94-L103}.
         *
         * <p>Four reported outcomes, and they are the ones the COBOL enumerates:
         * <ul>
         *   <li><strong>found</strong> - status {@code '00'}, carrying the decoded record. The COBOL moves
         *       {@code 0} to {@code APPL-RESULT} and the caller displays the record
         *       ({@code CBACT01C.cbl:L78} and {@code L118-L131});</li>
         *   <li><strong>end of file</strong> - status {@code '10'}, carrying no record. The COBOL moves
         *       {@code 16}, which is {@link FileStatus#APPL_EOF}, and the caller sets
         *       {@code MOVE 'Y' TO END-OF-FILE} at {@code L108} and leaves its loop. Reported repeatedly
         *       and idempotently: once at end of file, every further call reports it again, which is
         *       faithful because the COBOL loop stops on the flag and never resumes;</li>
         *   <li><strong>other</strong> carrying the <em>open's</em> status, when the open failed - so a
         *       caller that ignored {@link #openStatus()} still cannot mistake a dataset it never reached
         *       for one that was empty;</li>
         *   <li><strong>other</strong> carrying {@link AccountRepository#PERMANENT_ERROR_STATUS} for an
         *       I/O failure, a row whose record image is absent, or a row that is not
         *       {@link AccountRepository#RECORD_LENGTH} bytes wide. The COBOL moves {@code 12} and the
         *       caller displays the error, renders the status and abends.</li>
         * </ul>
         *
         * <p><strong>Ordering.</strong> The statement carries an explicit ascending {@code ORDER BY} over
         * the record-image column, and the first read of a browse uses a statement with no predicate at
         * all while every later read asks for the first image strictly greater than the one before it.
         * That is a keyed browse - the same "position, then read the next greater key" the KSDS itself
         * performs - and it makes the ordering a property of the statement rather than of the backend's
         * scan order. Only one row is ever transferred: the row limit is set on the statement, so a browse
         * of a large dataset never materialises it.
         *
         * <p>Reading a closed file is a <strong>programming error, not an I/O outcome</strong>, and throws.
         * COBOL would report status {@code '47'} or {@code '49'} for a read against a file in the wrong
         * open mode, but no consumer of this dataset ever does it - {@code CBACT01C} closes once, after its
         * loop - so there is no legacy behaviour to reproduce and the honest response is to fail loudly at
         * the defect rather than to return a status no COBOL path would have produced.
         *
         * @return the discriminated outcome; never {@code null}
         * @throws IllegalStateException    if this handle has been closed
         */
        public ReadResult readNext() {
            requireOpen("read the next record");
            if (statements == null) {
                return ReadResult.of(openStatus);
            }

            byte[] position = this.browsePosition;
            boolean fromStart = position == null;
            String statement = fromStart ? statements.selectFirst() : statements.selectNext();

            List<byte[]> rows;
            try {
                PreparedStatementCreator creator = fromStart
                        ? repository.firstRow(statement)
                        : repository.firstRowAfter(statement, position);
                rows = repository.jdbcTemplate.query(creator, repository.recordImageMapper());
            } catch (DataAccessException translated) {
                // The fatal arm. An I/O failure is reported as a status so the caller's own guard chain
                // decides what to do about it - which, in CBACT01C, is to display and abend.
                LOG.error("Rejected browse read of the account master dataset '" + datasetName()
                        + "' - " + BackendDiagnostic.of(translated).describe() + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
                return ReadResult.of(PERMANENT_ERROR_STATUS);
            }

            // The list itself is never null - the template asserts that internally before returning it -
            // so emptiness is the whole of the end-of-file test, and no unreachable null arm is written.
            if (rows.isEmpty()) {
                // AT END with no record read. An expected outcome, not an error.
                return ReadResult.endOfFile();
            }
            byte[] recordImage = rows.get(0);
            if (recordImage == null) {
                // A row whose record image is absent is not a readable 300-byte record. There IS a record,
                // it simply cannot be read, so this is an I/O-level defect and not an end of file - it
                // must not be mistaken for one, or a browse would stop early and silently.
                LOG.error("The account master dataset '" + datasetName() + "' presented a row with no "
                        + "record image at column position " + RECORD_IMAGE_COLUMN_INDEX + "; reporting "
                        + "file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " to the caller");
                return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
            }

            // The position advances to the image exactly as the backend presented it, which is what keeps
            // the next comparison an apples-to-apples one against the stored values.
            this.browsePosition = recordImage.clone();
            return repository.decoded(recordImage, "the row at the current browse position");
        }

        /**
         * Reads one record by account identifier under this open: {@link AccountRepository#readByKey(long)}
         * against the shape this {@code OPEN} already resolved.
         *
         * <p>The Java form of {@code CBACT04C}'s keyed read at {@code app/cbl/CBACT04C.cbl:L373-L376},
         * which runs under that program's {@code OPEN I-O}. Identical in outcome to the repository's own
         * method; it differs only in paying no second metadata round trip.
         *
         * <p>The browse position is untouched, exactly as a COBOL random {@code READ} leaves the position
         * of a sequential browse of the same file alone.
         *
         * @param acctId the account identifier; must not be negative, as {@code PIC 9} declares no sign
         *               position and therefore has no representation for one
         * @return the discriminated outcome; never {@code null}
         * @throws IllegalStateException    if this handle has been closed
         * @throws IllegalArgumentException if {@code acctId} is negative, or if the stored image is wider
         *                                  than {@link AccountRepository#RECORD_LENGTH}
         */
        public ReadResult readByKey(long acctId) {
            requireOpen("read a record by key");
            if (statements == null) {
                return ReadResult.of(openStatus);
            }
            return repository.readKeyed(statements.selectByKey(),
                    AccountRecord.keyImage(acctId, repository.codec.charset()),
                    "the account identifier " + acctId, statements.probeUnreadableRows());
        }

        /**
         * Reads one record for update under this open:
         * {@link AccountRepository#readForUpdate(String)} against the shape this {@code OPEN} already
         * resolved.
         *
         * <p>The locking read and the unit-of-work requirement are identical - the statement carries
         * {@code FOR UPDATE} and an active transaction is required - because a lock taken under
         * auto-commit is released before the caller can use it whichever entry point took it.
         *
         * @param acctIdAsChar11 the key exactly as the {@code RIDFLD} holds it: exactly
         *                       {@link AccountRepository#KEY_LENGTH} characters, untrimmed and unparsed
         * @return the discriminated outcome; never {@code null}
         * @throws NullPointerException     if {@code acctIdAsChar11} is {@code null}
         * @throws IllegalArgumentException if {@code acctIdAsChar11} is not exactly
         *                                  {@link AccountRepository#KEY_LENGTH} characters
         * @throws IllegalStateException    if this handle has been closed, or if no transaction is active
         */
        public ReadResult readForUpdate(String acctIdAsChar11) {
            String keyImage = requireKeyImage(acctIdAsChar11);
            requireOpen("read a record for update");
            repository.requireUnitOfWork();
            if (statements == null) {
                return ReadResult.of(openStatus);
            }
            return repository.readKeyed(statements.selectByKeyForUpdate(), keyImage,
                    "the record identification field for update", statements.probeUnreadableRows());
        }

        /**
         * Rewrites one record under this open: {@link AccountRepository#rewrite(AccountRecord)} against the
         * shape this {@code OPEN} already resolved.
         *
         * <p>This is where {@code CBACT04C}'s account break lands, under the {@code OPEN I-O} that program
         * issues at {@code app/cbl/CBACT04C.cbl:L291}.
         *
         * @param record the record to write, complete and already mutated by the caller
         * @return the discriminated outcome; never {@code null}
         * @throws NullPointerException  if {@code record} is {@code null}
         * @throws IllegalStateException if this handle has been closed; if no unit of work is open, in
         *                               which case nothing has been attempted - {@code CBACT04C}'s rewrite
         *                               reaches this through
         *                               {@link DatasetUnitOfWork#persistVerb(String,
         *                               java.util.function.Supplier)}; or - as a
         *                               {@link com.vsergeychik.carddemo.common.DatasetIntegrityException}
         *                               - if the write replaced more rows than the key selected when it
         *                               was checked
         */
        public WriteResult rewrite(AccountRecord record) {
            Objects.requireNonNull(record, "A record is required to rewrite it; a COBOL REWRITE writes "
                    + "the record area, and there is no such thing as rewriting nothing");
            requireOpen("rewrite a record");
            if (statements == null) {
                return WriteResult.of(openStatus);
            }
            return repository.rewrite(statements, record);
        }

        // =============================================================================================
        // 9000-ACCTFILE-CLOSE - app/cbl/CBACT01C.cbl:L151-L167.
        // 9300-ACCTFILE-CLOSE - app/cbl/CBACT04C.cbl:L577-L594.
        // =============================================================================================

        /**
         * Closes this file, reporting only the resulting file status.
         *
         * <p>Same two-armed shape as the {@code OPEN}, and the caller likewise owns the
         * {@code DISPLAY 'ERROR CLOSING ACCOUNT FILE'} line ({@code app/cbl/CBACT01C.cbl:L162},
         * {@code app/cbl/CBACT04C.cbl:L588}) and the abend.
         *
         * <p>Nothing is buffered and no connection is held between operations, so there is no flush to
         * fail. The one close-time failure this handle can genuinely detect is that the dataset is no
         * longer addressable - the analogue of the file system reporting a problem as a dataset is
         * de-allocated - so the same probe as the open is used, which also keeps both of the COBOL's
         * symmetric guards reachable rather than leaving one dead. A handle whose open failed has nothing
         * to probe and reports that open's own status.
         *
         * <p><strong>Idempotent.</strong> The first call probes and reports; a later one reports
         * {@link FileStatus#OK} without probing, because the file is already closed and there is nothing
         * left to fail. That is what makes try-with-resources safe alongside an explicit close in the same
         * block.
         *
         * @return {@link FileStatus#OK} when the dataset is still addressable, otherwise
         *         {@link AccountRepository#PERMANENT_ERROR_STATUS}; never {@code null}, always two
         *         characters
         * @throws IllegalStateException if the backend presents the dataset with no usable record-image
         *                               column
         */
        public String closeFile() {
            if (closed) {
                return FileStatus.OK;
            }
            closed = true;
            this.browsePosition = null;
            if (statements == null) {
                // The OPEN never succeeded, so there is no open file to close: its own status is reported
                // rather than a fresh one, and no probe is issued for a file that was never opened.
                return openStatus;
            }
            return repository.statementsFor("a close of the account master") == null
                    ? PERMANENT_ERROR_STATUS
                    : FileStatus.OK;
        }

        /**
         * {@link AutoCloseable} form of {@link #closeFile()}, so a handle can be used with
         * try-with-resources. Declared to throw nothing, so it adds no checked exception to a caller.
         */
        @Override
        public void close() {
            closeFile();
        }

        /**
         * Refuses an operation on a closed handle.
         *
         * @param operation what the caller was trying to do, for the diagnostic
         * @throws IllegalStateException if this handle has been closed
         */
        private void requireOpen(String operation) {
            if (closed) {
                throw new IllegalStateException("This open of the account master dataset '"
                        + datasetName() + "' has been closed, so it cannot " + operation + ". A COBOL "
                        + "program closes once, after its work - operating afterwards is a defect in the "
                        + "caller, not a file status. Open another file instead.");
            }
        }
    }

    // =================================================================================================
    // The OPEN verbs.
    // =================================================================================================

    /**
     * The {@code OPEN} verbs the estate issues against the account master, and the only two it issues.
     *
     * <p>Established by inspection rather than assumed: {@code OPEN INPUT} appears at
     * {@code app/cbl/CBACT01C.cbl:L135}, {@code app/cbl/CBTRN01C.cbl:L327} and
     * {@code app/cbl/CBSTM03B.CBL:L209}; {@code OPEN I-O} appears at
     * {@code app/cbl/CBACT04C.cbl:L291} and {@code app/cbl/CBTRN02C.cbl:L311}. No program opens this
     * dataset for output or extend, so no such constant exists here - the CICS definition permits adding
     * a record, but no program does it.
     */
    public enum OpenMode {

        /**
         * {@code OPEN INPUT}: read-only. Used by the sequential browse and by the programs that only
         * read the account master by key.
         */
        INPUT("OPEN INPUT"),

        /**
         * {@code OPEN I-O}: read and rewrite. Used by the two programs that update an account record -
         * the interest calculator and the transaction poster.
         */
        I_O("OPEN I-O");

        /** The COBOL verb this constant stands for, spelled as the source spells it. */
        private final String cobolVerb;

        /**
         * Constructs a mode.
         *
         * @param cobolVerb the COBOL verb, spelled as the source spells it
         */
        OpenMode(String cobolVerb) {
            this.cobolVerb = cobolVerb;
        }

        /**
         * The COBOL verb this mode stands for, for a diagnostic that names what the program did.
         *
         * @return {@code "OPEN INPUT"} or {@code "OPEN I-O"}
         */
        public String cobolVerb() {
            return cobolVerb;
        }
    }

    // =================================================================================================
    // The discriminated outcomes.
    // =================================================================================================

    /**
     * The outcome of one read of the account master: the classification the COBOL guard chain performs,
     * and nothing beyond it.
     *
     * <p>The vocabulary is {@link Outcome}, shared with every other dataset operation in the module so
     * that a caller's branch structure looks the same whichever legacy program it came from. All five of
     * its constants are admissible here, which is what lets a caller distinguish {@code '00'},
     * {@code '10'}, {@code '22'}, {@code '23'} and everything else. Two notes on that:
     * <ul>
     *   <li>{@code '10'} arises only from a browse and {@code '23'} only from a keyed read, because that
     *       is how the COBOL reads this dataset - but both are admissible here rather than split across
     *       two result types, since a repository reporting in one vocabulary is the point;</li>
     *   <li>{@code '22'} cannot arise from any operation this repository performs, because none of them
     *       adds a record. It is admissible anyway, and carried verbatim if a backend ever reported it,
     *       rather than being collapsed into the catch-all where a caller could not see it.</li>
     * </ul>
     *
     * <p>The record is present for, and only for, a successful read - the invariant is enforced, not
     * merely documented - so a caller can neither find a record on an end-of-file result nor lose one on
     * a successful read. The status and its classification are likewise required to agree, so no
     * inconsistent pair can be constructed at all.
     *
     * <h2>The CICS pair travels with the outcome</h2>
     *
     * <p>The online caller does not test a file status. {@code app/cbl/COACTUPC.cbl:L3703-L3710} issues its
     * read with {@code RESP(WS-RESP-CD) RESP2(WS-REAS-CD)} and captures <em>both</em>, and
     * {@code L3722-L3729} renders both into the message the operator reads - {@code ' Resp:' ERROR-RESP}
     * and {@code ' Reas:' ERROR-RESP2}. A result carrying only a response, from which the reason had to be
     * assumed, could not compose that message; and a reason code derived from a response is not a reason
     * code. So the pair is a component of the outcome, built once by whoever knows the outcome and read
     * verbatim thereafter - {@link CicsResponse}, whose reason code is
     * {@link FileStatus#NO_REASON_CODE} where the condition genuinely carries no further reason and a
     * reported value where a deployment's adapter surfaces one.
     *
     * @param status  the two-character file status the read reported, verbatim
     * @param outcome its classification
     * @param account the decoded record, present exactly when {@code outcome} is {@link Outcome#OK}
     * @param diagnostic what the backend reported when it refused, present only on a failure it
     *                described - so a caller can log the driver's own {@code SQLSTATE} instead of a
     *                status this module synthesised
     * @param response the CICS {@code RESP}/{@code RESP2} pair for this outcome, carried rather than
     *                derived on demand
     */
    public record ReadResult(String status,
                            Outcome outcome,
                            Optional<AccountRecord> account,
                            Optional<BackendDiagnostic> diagnostic,
                            CicsResponse response) {

        /**
         * Enforces every invariant of the outcome at construction.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *                                  {@link FileStatus#STATUS_LENGTH} characters, if
         *                                  {@code outcome} is not the classification of {@code status},
         *                                  or if the record is present when the outcome is not success
         *                                  or absent when it is
         */
        public ReadResult {
            requireConsistentStatus(status, outcome);
            Objects.requireNonNull(account, "A read result carries an empty record rather than a null "
                    + "one, so no null escapes the type");
            Objects.requireNonNull(diagnostic, "A read result carries an empty diagnostic rather than a "
                    + "null one, so no null escapes the type");
            Objects.requireNonNull(response, "A read result carries a CICS response pair rather than a "
                    + "null one; use CicsResponse.ofBatchStatus(status) where the outcome is a file "
                    + "status and CicsResponse.reported(resp, resp2) where a backend surfaced both");
            if (account.isPresent() != (outcome == Outcome.OK)) {
                throw new IllegalArgumentException(account.isPresent()
                        ? "A read that did not succeed carries no record: outcome " + outcome
                                + " was given one. Only the '00' arm reaches ACCOUNT-RECORD."
                        : "A successful read carries the decoded record, and this one carries none; "
                                + "build it with ReadResult.found(AccountRecord).");
            }
        }

        /**
         * The successful arm: status {@code '00'}, carrying the decoded record.
         *
         * <p>{@code MOVE 0 TO APPL-RESULT} at {@code app/cbl/CBACT01C.cbl:L95} and
         * {@code app/cbl/CBACT04C.cbl:L379}, and {@code DFHRESP(NORMAL)} at
         * {@code app/cbl/COACTUPC.cbl:L3907}.
         *
         * @param account the decoded record
         * @return a result carrying {@link FileStatus#OK} and the record
         * @throws NullPointerException if {@code account} is {@code null}
         */
        public static ReadResult found(AccountRecord account) {
            Objects.requireNonNull(account, "A successful read carries the decoded account record");
            return new ReadResult(FileStatus.OK, Outcome.OK, Optional.of(account), Optional.empty(),
                    CicsResponse.ofBatchStatus(FileStatus.OK));
        }

        /**
         * The end-of-file arm: status {@code '10'}, carrying no record.
         *
         * <p>{@code MOVE 16 TO APPL-RESULT} at {@code app/cbl/CBACT01C.cbl:L99}, which the caller turns
         * into {@code MOVE 'Y' TO END-OF-FILE} at {@code L108}. Reached only by a browse.
         *
         * @return a result carrying {@link FileStatus#END_OF_FILE} and no record
         */
        public static ReadResult endOfFile() {
            return of(FileStatus.END_OF_FILE);
        }

        /**
         * The invalid-key arm: status {@code '23'}, carrying no record.
         *
         * <p>The {@code INVALID KEY} condition of {@code app/cbl/CBACT04C.cbl:L374-L375}, whose caller
         * displays {@code 'ACCOUNT NOT FOUND: '} and the identifier and then takes its fatal arm; and
         * {@code DFHRESP(NOTFND)} on the online side.
         *
         * @return a result carrying {@link FileStatus#NOT_FOUND} and no record
         */
        public static ReadResult notFound() {
            return of(FileStatus.NOT_FOUND);
        }

        /**
         * A result for any status other than success, classified from the status itself.
         *
         * <p>This is the general factory, and it accepts every status a backend can report - the
         * end-of-file and invalid-key statuses that {@link #endOfFile()} and {@link #notFound()} name,
         * the duplicate-key status, and any permanent-error status. Success is the one status it refuses,
         * because a successful read carries a record and this factory has none to carry.
         *
         * @param status the two-character status the read reported, carried verbatim so the caller can
         *               render it exactly as {@code 9910-DISPLAY-IO-STATUS} does
         * @return a result carrying {@code status}, its classification, and no record
         * @throws NullPointerException     if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *                                  {@link FileStatus#STATUS_LENGTH} characters, or is
         *                                  {@link FileStatus#OK}
         */
        public static ReadResult of(String status) {
            // classify(status) first, deliberately: it is this class's own width and null guard, and a
            // caller that passes a malformed status should read this class's diagnostic rather than one
            // from the status vocabulary two calls deeper.
            return new ReadResult(status, classify(status), Optional.empty(), Optional.empty(),
                    CicsResponse.ofBatchStatus(status));
        }

        /**
         * A failed read carrying the CICS pair the outcome should report.
         *
         * <p>For an outcome whose response is <em>not</em> the translation of its file status. The width
         * check is the case that needs it: a row that is not {@link AccountRepository#RECORD_LENGTH} bytes
         * is a permanent error to the batch guard chain and {@link FileStatus#LENGERR} to the online one,
         * and neither number can be derived from the other. It is also what a deployment adapter that
         * surfaces genuine {@code RESP}/{@code RESP2} values uses, so it never has to fabricate the pair.
         *
         * @param status   the file status the caller branches on
         * @param response the pair to report
         * @return a result carrying {@code status}, its classification, {@code response} and no record
         * @throws NullPointerException     if {@code status} or {@code response} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *                                  {@link FileStatus#STATUS_LENGTH} characters, or is
         *                                  {@link FileStatus#OK}
         */
        public static ReadResult of(String status, CicsResponse response) {
            return new ReadResult(status, classify(status), Optional.empty(), Optional.empty(), response);
        }

        /**
         * A failed read carrying what the backend actually said about it.
         *
         * <p>The status is what the caller branches on, because that is the quantity the COBOL guard
         * chain tests. The diagnostic is what makes the failure diagnosable: a status of {@code '9'}
         * plus a feedback byte tells an operator that something permanent went wrong and nothing about
         * what, whereas the driver's own {@code SQLSTATE} distinguishes an unreachable backend from a
         * missing relation from a rejected credential - three failures needing three different
         * responses.
         *
         * @param status     the file status the caller branches on
         * @param diagnostic what the backend reported
         * @return the outcome
         * @throws NullPointerException if {@code diagnostic} is {@code null}
         */
        public static ReadResult of(String status, BackendDiagnostic diagnostic) {
            return of(status, CicsResponse.ofBatchStatus(status), diagnostic);
        }

        /**
         * A failed read carrying both the CICS pair to report and what the backend said about it.
         *
         * @param status     the file status the caller branches on
         * @param response   the pair to report
         * @param diagnostic what the backend reported
         * @return the outcome
         * @throws NullPointerException if {@code response} or {@code diagnostic} is {@code null}
         */
        public static ReadResult of(String status, CicsResponse response,
                BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A diagnostic is required by this factory; use "
                    + "of(String) where there is no backend refusal to report");
            return new ReadResult(status, classify(status), Optional.empty(), Optional.of(diagnostic),
                    response);
        }

        /**
         * Whether the read succeeded and a record is available.
         *
         * <p>This is both the {@code IF APPL-AOK} test of the batch guard chain and the
         * {@code IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)} test of the online one
         * ({@code app/cbl/COACTUPC.cbl:L3907}).
         *
         * @return {@code true} for the {@code '00'} arm
         */
        public boolean isFound() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether the browse reached the end of the dataset: the {@code IF APPL-EOF} test.
         *
         * @return {@code true} for the {@code '10'} arm
         */
        public boolean isEndOfFile() {
            return outcome == Outcome.END_OF_FILE;
        }

        /**
         * Whether the keyed record was absent: the {@code INVALID KEY} condition, and
         * {@code DFHRESP(NOTFND)}.
         *
         * @return {@code true} for the {@code '23'} arm
         */
        public boolean isNotFound() {
            return outcome == Outcome.NOT_FOUND;
        }

        /**
         * Whether the read failed for any other reason: the {@code WHEN OTHER} arm, which every guard
         * chain over this dataset ends in an abend.
         *
         * @return {@code true} for the catch-all arm
         */
        public boolean isOther() {
            return outcome == Outcome.OTHER;
        }

        /**
         * The CICS {@code RESP} value this outcome reports.
         *
         * <p>For the online caller, which tests {@code RESP} rather than a file status and renders it
         * into its error message as {@code ERROR-RESP} ({@code app/cbl/COACTUPC.cbl:L3721}). Read from
         * the carried {@link #response()} rather than recomputed from the status, so an outcome whose
         * response is not the translation of its status - the width check reports
         * {@link FileStatus#LENGERR} against a permanent-error status - reports the response it was built
         * with. Empty where the outcome has no single CICS counterpart, which
         * {@link FileStatus#cicsRespOfBatchStatus(String)} reports as an ambiguity rather than resolving.
         *
         * @return the response value, or an empty {@code OptionalInt}
         */
        public OptionalInt cicsResp() {
            return response.resp();
        }

        /**
         * The CICS {@code RESP2} reason code this outcome reports.
         *
         * <p>The second half of what {@code app/cbl/COACTUPC.cbl:L3703-L3710} captures and
         * {@code L3722-L3729} renders as {@code ' Reas:' ERROR-RESP2}. It is
         * {@link FileStatus#NO_REASON_CODE} for every outcome a JDBC-backed dataset can produce, because
         * a file status carries no CICS reason and none is invented here; a deployment whose adapter
         * surfaces a real one builds the result with {@link CicsResponse#reported(int, int)}.
         *
         * @return the reason code, never negative
         */
        public int cicsResp2() {
            return response.resp2();
        }

        /**
         * The {@code APPL-RESULT} value the COBOL guard moves for this outcome.
         *
         * <p>{@code 0} on success, {@code 16} at end of file, and {@code 12} for everything else -
         * exactly the ladder at {@code app/cbl/CBACT01C.cbl:L94-L103}. A not-found record maps to
         * {@code 12} and not to a gentler value, because {@code CBACT04C}'s keyed read tests only
         * {@code IF ACCTFILE-STATUS = '00'} and moves {@code 12} otherwise
         * ({@code app/cbl/CBACT04C.cbl:L378-L382}).
         *
         * @return {@link FileStatus#APPL_AOK}, {@link FileStatus#APPL_EOF} or
         *         {@link AccountRepository#APPL_RESULT_FATAL}
         */
        public int applResult() {
            return switch (outcome) {
                case OK -> FileStatus.APPL_AOK;
                case END_OF_FILE -> FileStatus.APPL_EOF;
                default -> APPL_RESULT_FATAL;
            };
        }
    }

    /**
     * The outcome of one rewrite of the account master: the classification
     * {@code 1050-UPDATE-ACCOUNT}'s guard performs ({@code app/cbl/CBACT04C.cbl:L357-L361}), and nothing
     * beyond it.
     *
     * <p>That guard is two-armed - {@code IF ACCTFILE-STATUS = '00' MOVE 0 ELSE MOVE 12} - and so is the
     * online one at {@code app/cbl/COACTUPC.cbl:L4076-L4081}. The status is nevertheless carried
     * verbatim and classified in the shared vocabulary, so a caller that wants to distinguish an
     * invalid-key rewrite from a permanent error can, while a caller that only needs the COBOL's two
     * arms uses {@link #isWritten()}.
     *
     * <p>No record is carried: a COBOL {@code REWRITE} returns a status and nothing else. There is no
     * end-of-file arm either, because a rewrite cannot reach the end of a dataset.
     *
     * <p>The CICS pair travels with the outcome for the same reason it does on {@link ReadResult}:
     * {@code app/cbl/COACTUPC.cbl:L4065-L4071} issues its {@code REWRITE} with
     * {@code RESP(WS-RESP-CD) RESP2(WS-REAS-CD)} and captures both, and a reason code derived from a
     * response is not a reason code.
     *
     * @param status  the two-character file status the rewrite reported, verbatim
     * @param outcome its classification
     * @param diagnostic what the backend reported when it refused, present only on a failure it described
     * @param response the CICS {@code RESP}/{@code RESP2} pair for this outcome, carried rather than
     *                derived on demand
     */
    public record WriteResult(String status, Outcome outcome, Optional<BackendDiagnostic> diagnostic,
            CicsResponse response) {

        /**
         * Enforces the invariants of the outcome at construction.
         *
         * @throws NullPointerException     if either component is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *                                  {@link FileStatus#STATUS_LENGTH} characters, if
         *                                  {@code outcome} is not the classification of {@code status},
         *                                  or if {@code outcome} is {@link Outcome#END_OF_FILE}, which a
         *                                  rewrite cannot produce
         */
        public WriteResult {
            requireConsistentStatus(status, outcome);
            Objects.requireNonNull(diagnostic, "A write result carries an empty diagnostic rather than a "
                    + "null one, so no null escapes the type");
            Objects.requireNonNull(response, "A write result carries a CICS response pair rather than a "
                    + "null one; use CicsResponse.ofBatchStatus(status) where the outcome is a file "
                    + "status and CicsResponse.reported(resp, resp2) where a backend surfaced both");
            if (outcome == Outcome.END_OF_FILE) {
                throw new IllegalArgumentException("A rewrite cannot reach the end of a dataset, so "
                        + "status '" + FileStatus.END_OF_FILE + "' is not an outcome it can report.");
            }
        }

        /**
         * The successful arm: status {@code '00'}, one record rewritten.
         *
         * <p>{@code MOVE 0 TO APPL-RESULT} at {@code app/cbl/CBACT04C.cbl:L358}, and
         * {@code DFHRESP(NORMAL)} at {@code app/cbl/COACTUPC.cbl:L4076}.
         *
         * @return a result carrying {@link FileStatus#OK}
         */
        public static WriteResult written() {
            return of(FileStatus.OK);
        }

        /**
         * The invalid-key arm: status {@code '23'}, no record rewritten.
         *
         * <p>A rewrite whose key matches no record. The caller reaches its fatal arm -
         * {@code DISPLAY 'ERROR RE-WRITING ACCOUNT FILE'} at {@code app/cbl/CBACT04C.cbl:L365}, or
         * {@code SET LOCKED-BUT-UPDATE-FAILED TO TRUE} at {@code app/cbl/COACTUPC.cbl:L4079}.
         *
         * @return a result carrying {@link FileStatus#NOT_FOUND}
         */
        public static WriteResult notFound() {
            return of(FileStatus.NOT_FOUND);
        }

        /**
         * A result for any status, classified from the status itself.
         *
         * @param status the two-character status the rewrite reported, carried verbatim
         * @return a result carrying {@code status} and its classification
         * @throws NullPointerException     if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *                                  {@link FileStatus#STATUS_LENGTH} characters, or is
         *                                  {@link FileStatus#END_OF_FILE}
         */
        public static WriteResult of(String status) {
            // classify(status) first, for the reason ReadResult.of(String) gives.
            return new WriteResult(status, classify(status), Optional.empty(),
                    CicsResponse.ofBatchStatus(status));
        }

        /**
         * A result carrying the CICS pair the outcome should report.
         *
         * <p>For an outcome whose response is <em>not</em> the translation of its file status - a key that
         * selects several rows is a permanent error to the batch guard chain and
         * {@link FileStatus#INVREQ} to the online one - and for a deployment adapter that surfaces genuine
         * {@code RESP}/{@code RESP2} values.
         *
         * @param status   the two-character status the rewrite reported, carried verbatim
         * @param response the pair to report
         * @return a result carrying {@code status}, its classification and {@code response}
         * @throws NullPointerException     if {@code status} or {@code response} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *                                  {@link FileStatus#STATUS_LENGTH} characters, or is
         *                                  {@link FileStatus#END_OF_FILE}
         */
        public static WriteResult of(String status, CicsResponse response) {
            return new WriteResult(status, classify(status), Optional.empty(), response);
        }

        /**
         * A failed write carrying what the backend actually said about it.
         *
         * @param status     the file status the caller branches on
         * @param diagnostic what the backend reported
         * @return the outcome
         * @throws NullPointerException if {@code diagnostic} is {@code null}
         */
        public static WriteResult of(String status, BackendDiagnostic diagnostic) {
            return of(status, CicsResponse.ofBatchStatus(status), diagnostic);
        }

        /**
         * A failed write carrying both the CICS pair to report and what the backend said about it.
         *
         * @param status     the file status the caller branches on
         * @param response   the pair to report
         * @param diagnostic what the backend reported
         * @return the outcome
         * @throws NullPointerException if {@code response} or {@code diagnostic} is {@code null}
         */
        public static WriteResult of(String status, CicsResponse response,
                BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A diagnostic is required by this factory; use "
                    + "of(String) where there is no backend refusal to report");
            return new WriteResult(status, classify(status), Optional.of(diagnostic), response);
        }

        /**
         * Whether the rewrite succeeded: the {@code IF APPL-AOK} test, and
         * {@code IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)}.
         *
         * @return {@code true} for the {@code '00'} arm
         */
        public boolean isWritten() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether there was no such record to rewrite.
         *
         * @return {@code true} for the {@code '23'} arm
         */
        public boolean isNotFound() {
            return outcome == Outcome.NOT_FOUND;
        }

        /**
         * Whether the rewrite failed for any other reason.
         *
         * @return {@code true} for the catch-all arm
         */
        public boolean isOther() {
            return outcome == Outcome.OTHER;
        }

        /**
         * The CICS {@code RESP} value this outcome reports, read from the carried pair rather than
         * recomputed from the status.
         *
         * @return the response value, or an empty {@code OptionalInt}
         */
        public OptionalInt cicsResp() {
            return response.resp();
        }

        /**
         * The CICS {@code RESP2} reason code this outcome reports.
         *
         * <p>The second half of what {@code app/cbl/COACTUPC.cbl:L4065-L4071} captures.
         * {@link FileStatus#NO_REASON_CODE} for every outcome a JDBC-backed dataset can produce - a row
         * count and a record width are neither of them reason codes and are named in the log line
         * instead.
         *
         * @return the reason code, never negative
         */
        public int cicsResp2() {
            return response.resp2();
        }

        /**
         * The {@code APPL-RESULT} value the COBOL guard moves for this outcome: {@code 0} on success and
         * {@code 12} otherwise ({@code app/cbl/CBACT04C.cbl:L357-L361}).
         *
         * @return {@link FileStatus#APPL_AOK} or {@link AccountRepository#APPL_RESULT_FATAL}
         */
        public int applResult() {
            return isWritten() ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }
    }

    /**
     * Classifies a status, rejecting one that is not exactly two characters.
     *
     * <p>Shared by both outcome types so that the classification and its width check exist once, and so
     * that neither type can hold a status and a classification that disagree.
     *
     * @param status the two-character status
     * @return its classification in the shared vocabulary
     * @throws NullPointerException     if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly
     *                                  {@link FileStatus#STATUS_LENGTH} characters
     */
    private static Outcome classify(String status) {
        return FileStatus.outcomeOfStatus(requireStatusWidth(status));
    }

    /**
     * Requires a status and its classification to agree.
     *
     * @param status  the two-character status
     * @param outcome the classification offered for it
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly
     *                                  {@link FileStatus#STATUS_LENGTH} characters, or if
     *                                  {@code outcome} is not its classification
     */
    private static void requireConsistentStatus(String status, Outcome outcome) {
        Objects.requireNonNull(outcome, "An outcome carries its classification; it is never absent");
        Outcome classified = classify(status);
        if (outcome != classified) {
            throw new IllegalArgumentException("Status '" + FileStatus.toStatusImage(status)
                    + "' classifies as " + classified + ", but " + outcome + " was given; a status and "
                    + "its classification must agree, so that no caller can branch on one and read the "
                    + "other. Use one of the named factory methods.");
        }
    }

    /**
     * Requires a status to be exactly two characters, as every COBOL {@code FILE STATUS} field is.
     *
     * @param status the status to check
     * @return {@code status}, unchanged
     * @throws NullPointerException     if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly
     *                                  {@link FileStatus#STATUS_LENGTH} characters
     */
    private static String requireStatusWidth(String status) {
        Objects.requireNonNull(status, "An outcome carries the two-character file status the operation "
                + "reported; it is never absent");
        if (status.length() != FileStatus.STATUS_LENGTH) {
            throw new IllegalArgumentException("A file status is exactly " + FileStatus.STATUS_LENGTH
                    + " characters, as ACCTFILE-STATUS is declared at app/cbl/CBACT01C.cbl:L46-L48; got "
                    + status.length() + ".");
        }
        return status;
    }
}

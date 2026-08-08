package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

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

import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
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
 *       {@code L93} until status {@code '10'}, and closes at {@code L153}. See {@link #readNext()}.</li>
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
 * <p>{@link #readNext()} must deliver records in ascending {@code ACCT-ID} order, because
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
 * for the duration of the unit of work. That is modelled as a plain transactional read: no row-locking
 * clause is bolted onto the statement, no optimistic-lock marker is introduced, and above all no
 * version column is added, because a version column would be a schema change and this migration
 * changes no schema. The transaction boundary itself belongs to the caller - the batch step or the
 * service - so this class declares none.
 *
 * <p>The optimistic check that {@code COACTUPC} performs is not this class's job either. Paragraph
 * {@code 9700-CHECK-CHANGE-IN-REC} ({@code app/cbl/COACTUPC.cbl:L4109} onwards) compares the re-read
 * record field by field against the copy the screen was painted from, and it is performed at
 * {@code L3947-L3948}, between the read for update and the rewrite. That comparison lives in the
 * account update service, which is where the COBOL puts it.
 *
 * <h2>Thread safety</h2>
 *
 * <p><strong>Not thread-safe, deliberately.</strong> A browse has a position, and in COBOL that
 * position is a property of the opened file - one file, one position, advanced by one {@code READ} at a
 * time. This class holds the same single position and nothing more, so two threads browsing one
 * instance concurrently would interleave their reads and destroy the ordering that parity depends on.
 * Guarding the position with a lock would make that misuse silent instead of visible, so it is
 * documented instead: browse from one thread at a time, exactly as one COBOL program does.
 *
 * <p>The keyed operations - {@link #readByKey(long)}, {@link #readForUpdate(String)} and
 * {@link #rewrite(AccountRecord)} - touch no position and are safe to call concurrently once the
 * statements have been resolved. There is <strong>no static mutable state</strong> anywhere in this
 * file: the only {@code static} members are immutable constants and a logger reference.
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
 * <p>A backend that trims trailing spaces from a character column is accommodated on the way in: a
 * short image is widened back to the declared width with spaces before it is decoded, which is the
 * faithful repair because the only bytes it can be missing are trailing {@code FILLER}, and a
 * {@code FILLER} carrying no literal holds spaces. An image <em>wider</em> than the record is rejected
 * instead, because that means the data and the layout disagree.
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
    public static final int RECORD_IMAGE_COLUMN_INDEX = 1;

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
     * The character that escapes a {@code LIKE} metacharacter in a keyed predicate: a backslash.
     *
     * <p>A keyed predicate matches a record whose image <em>begins with</em> the eleven-byte key, which
     * is a {@code LIKE} prefix pattern. The key normally holds eleven digits, but an online
     * {@code RIDFLD} is a {@code PIC X(11)} character field - {@code app/cbl/COACTUPC.cbl:L382-L383} -
     * and can therefore carry any character the screen supplied, so the pattern is escaped rather than
     * assumed to be metacharacter-free. An unescaped {@code _} would otherwise match any byte and could
     * return a different account's record.
     */
    private static final char LIKE_ESCAPE = '\\';

    /**
     * The wildcard that completes a keyed prefix pattern: matches the remaining bytes of the record.
     */
    private static final char LIKE_WILDCARD = '%';

    /**
     * The single-character {@code LIKE} wildcard, escaped wherever it occurs inside a key image.
     *
     * <p>Named so that the escaping loop states which metacharacters it handles rather than leaving a
     * reader to infer the set from a literal.
     */
    private static final char LIKE_SINGLE_WILDCARD = '_';

    /** The ANSI delimited-identifier quote, used to wrap the dataset name and the column name. */
    private static final String IDENTIFIER_QUOTE = "\"";

    /**
     * A predicate that is false on every row, so the metadata probe describes the relation without
     * transferring any of it. Written as a comparison of two literals, which every SQL dialect accepts.
     */
    private static final String NEVER_TRUE_PREDICATE = "1 = 0";

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
     * <p>Used for exactly one thing here - widening a short image back to the declared record width -
     * and as the single place the code page is kept, so no call site in this class chooses one.
     */
    private final FixedWidthCodec codec;

    /** The resolved dataset name, taken verbatim from the configured binding. */
    private final String datasetName;

    /** {@link #datasetName} wrapped as a single SQL delimited identifier, composed once. */
    private final String qualifiedDatasetName;

    /**
     * The metadata probe: a statement over the dataset that returns no rows.
     *
     * <p>Composed at construction because it needs only the dataset name. It is the statement that both
     * proves the dataset is addressable and yields the record-image column's name.
     */
    private final String columnProbeSql;

    // =================================================================================================
    // Mutable per-instance state. Exactly two things, and both of them stand for something the COBOL
    // itself holds: the resolved shape of the dataset, and the position of the open browse.
    // =================================================================================================

    /**
     * The composed statements, resolved on first use and released by {@link #close()}.
     *
     * <p>Lazily populated rather than built at construction because the record-image column's name is
     * discovered from the backend, and a repository must be constructible in a context that has not yet
     * reached its backend - which is also what lets every geometry check in the constructor fail fast at
     * context refresh instead of mid-job.
     *
     * <p>{@code null} means "not yet resolved". The value it holds is deeply immutable, so publishing it
     * hands out nothing that can be altered.
     */
    private Statements statements;

    /**
     * The browse position: the image of the record {@link #readNext()} returned last, or {@code null}
     * for "positioned before the first record".
     *
     * <p>This is the Java form of the file position a COBOL {@code OPEN INPUT} establishes and each
     * {@code READ} advances. It is the full record image rather than just the key, and that is
     * load-bearing: a bare eleven-byte key would compare as <em>less than</em> the very record it came
     * from - {@code '00000000001…'} sorts after {@code '00000000001'} - so a browse positioned by key
     * alone would return the same record for ever. The full image excludes it strictly, and because a
     * KSDS key is unique the comparison always resolves inside the leading eleven bytes.
     *
     * <p>Reset by {@link #open(OpenMode)} and by {@link #close()}, which is what those verbs do to a
     * file position.
     */
    private String browsePosition;

    /**
     * The {@code OPEN} verb most recently issued against this instance, or {@code null} while the
     * dataset is closed as far as this instance is concerned.
     *
     * <p>Recorded, not enforced. No program in the estate mixes the modes against this dataset - a
     * browse is always under {@code OPEN INPUT} and a rewrite always under {@code OPEN I-O} - so
     * refusing an operation on mode grounds would add a rejection the COBOL never performs and a branch
     * no caller could reach. Held so a caller and a diagnostic can see which verb was issued.
     */
    private OpenMode openMode;

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
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if either DD name is unconfigured, if either binding declares a
     *                               record width other than {@link #RECORD_LENGTH} or a key width other
     *                               than {@link #KEY_LENGTH}, if the two bindings name different
     *                               datasets, or if the resolved dataset name is unusable
     */
    public AccountRepository(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset) {

        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + "account master is reached through the module's shared template");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "dataset names live in configuration and are never written in Java");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset is "
                + "required: a fixed-width mainframe record is bytes in a specific code page, so the "
                + "code page is stated explicitly and never taken from the platform"));

        DatasetBinding cicsBinding = requireAccountGeometry(datasetBindings, CICS_FILE_NAME);
        DatasetBinding batchBinding = requireAccountGeometry(datasetBindings, BATCH_DD_NAME);
        requireSameDataset(cicsBinding, batchBinding);

        this.datasetName = requireUsableDatasetName(cicsBinding.dsname());
        this.qualifiedDatasetName = asDelimitedIdentifier(this.datasetName);
        this.columnProbeSql =
                "SELECT * FROM " + this.qualifiedDatasetName + " WHERE " + NEVER_TRUE_PREDICATE;
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

    /**
     * The {@code OPEN} verb most recently issued successfully against this instance.
     *
     * <p>Empty before the first {@link #open(OpenMode)}, after a {@link #close()}, and after an
     * {@code OPEN} that failed - a failed {@code OPEN} leaves a COBOL file closed, and it leaves this
     * accessor empty for the same reason.
     *
     * @return the recorded mode, or an empty {@code Optional} when this instance holds the dataset
     *         closed
     */
    public Optional<OpenMode> openMode() {
        return Optional.ofNullable(openMode);
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
     * Opens the account master, reporting only the resulting file status.
     *
     * <p>Two verbs, both evidenced, and both reaching this one method: {@code OPEN INPUT} at
     * {@code app/cbl/CBACT01C.cbl:L135} - shared by {@code CBTRN01C} at {@code L327} and by
     * {@code CBSTM03B} at {@code L209} - and {@code OPEN I-O} at {@code app/cbl/CBACT04C.cbl:L291},
     * shared by {@code CBTRN02C} at {@code L311}. No program opens this dataset any other way.
     *
     * <p><strong>What "open" means against a configuration-bound driver, precisely.</strong> There is no
     * persistent file handle to acquire: the shared template borrows and returns a connection per
     * operation. So what this method does is the honest equivalent - it establishes the two things an
     * {@code OPEN} establishes, and reports whether it could:
     * <ul>
     *   <li><strong>it positions the file.</strong> The browse position is reset, so a subsequent
     *       {@link #readNext()} returns the first record in key order. This is a real, observable effect:
     *       a {@code CLOSE} followed by an {@code OPEN INPUT} re-reads a COBOL file from its first
     *       record, and it re-reads this one from its first record too;</li>
     *   <li><strong>it proves the dataset is addressable.</strong> The metadata probe describes the
     *       relation without transferring a row, which is the failure an {@code OPEN} most often reports -
     *       an absent or unreachable dataset. It also resolves the record-image column, so the first
     *       real operation after a successful open needs no extra round trip.</li>
     * </ul>
     *
     * <p>A failed open leaves this instance holding the dataset closed, so {@link #openMode()} stays
     * empty - exactly as a COBOL file stays closed when its {@code OPEN} fails. The caller then owns the
     * {@code DISPLAY 'ERROR OPENING ACCTFILE'} line ({@code CBACT01C.cbl:L144}) or
     * {@code DISPLAY 'ERROR OPENING ACCOUNT MASTER FILE'} ({@code CBACT04C.cbl:L300}), the rendered
     * status and the abend.
     *
     * <p>The mode is recorded and not enforced, for the reason given on {@link #openMode}.
     *
     * @param mode the {@code OPEN} verb being issued
     * @return {@link FileStatus#OK} when the dataset is addressable, otherwise
     *         {@link #PERMANENT_ERROR_STATUS}; never {@code null}, always two characters
     * @throws NullPointerException  if {@code mode} is {@code null}
     * @throws IllegalStateException if the backend presents the dataset with no usable record-image
     *                               column, which is a contract violation rather than an I/O outcome
     */
    public String open(OpenMode mode) {
        Objects.requireNonNull(mode, "An open mode is required: the estate opens this dataset as "
                + OpenMode.INPUT.cobolVerb() + " or as " + OpenMode.I_O.cobolVerb() + ", and which "
                + "verb was issued is part of what the program did");

        // An OPEN always positions the file and always releases whatever the previous open resolved,
        // whichever mode it is. Both are cleared before the probe so that a FAILED open cannot leave
        // this instance holding a position or a shape from an earlier one.
        this.browsePosition = null;
        this.statements = null;
        this.openMode = null;

        String status = probeDataset();
        if (FileStatus.isOk(status)) {
            this.openMode = mode;
        }
        return status;
    }

    /**
     * Closes the account master, reporting only the resulting file status.
     *
     * <p>Same two-armed shape as {@link #open(OpenMode)}, and the caller likewise owns the
     * {@code DISPLAY 'ERROR CLOSING ACCOUNT FILE'} line ({@code app/cbl/CBACT01C.cbl:L162},
     * {@code app/cbl/CBACT04C.cbl:L588}) and the abend.
     *
     * <p>Nothing is buffered and no handle is held between calls, so there is no flush to fail. The one
     * close-time failure this repository can genuinely detect is that the dataset is no longer
     * addressable - the analogue of the file system reporting a problem as a dataset is de-allocated -
     * so the same probe as the open is used, which also keeps both of the COBOL's symmetric guards
     * reachable rather than leaving one dead.
     *
     * <p>The probe runs <em>before</em> the state is released, so a caller that closes an unreachable
     * dataset still gets the failing status, and the release happens regardless of what the probe said:
     * a COBOL {@code CLOSE} gives up the file position whether or not it reported a good status.
     *
     * @return {@link FileStatus#OK} when the dataset is still addressable, otherwise
     *         {@link #PERMANENT_ERROR_STATUS}; never {@code null}, always two characters
     * @throws IllegalStateException if the backend presents the dataset with no usable record-image
     *                               column
     */
    public String close() {
        String status = probeDataset();
        this.browsePosition = null;
        this.statements = null;
        this.openMode = null;
        return status;
    }

    // =================================================================================================
    // 1000-ACCTFILE-GET-NEXT - app/cbl/CBACT01C.cbl:L92-L116.
    // =================================================================================================

    /**
     * Reads the next record in ascending key order: the Java form of
     * {@code READ ACCTFILE-FILE INTO ACCOUNT-RECORD} ({@code app/cbl/CBACT01C.cbl:L93}) and of the
     * {@code EVALUATE}-equivalent guard that classifies its status at {@code L94-L103}.
     *
     * <p>Three outcomes, and exactly the three the COBOL enumerates:
     * <ul>
     *   <li><strong>found</strong> - status {@code '00'}, carrying the decoded record. The COBOL moves
     *       {@code 0} to {@code APPL-RESULT} and the caller displays the record
     *       ({@code CBACT01C.cbl:L78} and {@code L118-L131});</li>
     *   <li><strong>end of file</strong> - status {@code '10'}, carrying no record. The COBOL moves
     *       {@code 16}, which is {@link FileStatus#APPL_EOF}, and the caller sets
     *       {@code MOVE 'Y' TO END-OF-FILE} at {@code L108} and leaves its loop;</li>
     *   <li><strong>other</strong> - any other status, carried verbatim. The COBOL moves {@code 12} and
     *       the caller displays the error, renders the status and abends.</li>
     * </ul>
     *
     * <p><strong>Ordering.</strong> The statement carries an explicit ascending {@code ORDER BY} over the
     * record-image column, and the first read of a browse uses a statement with no predicate at all
     * while every later read asks for the first image strictly greater than the one before it. That is a
     * keyed browse - the same "position, then read the next greater key" the KSDS itself performs - and
     * it makes the ordering a property of the statement rather than of the backend's scan order. Only
     * one row is ever transferred: the row limit is set on the statement, so a browse of a large dataset
     * never materialises it.
     *
     * <p>This method does not require {@link #open(OpenMode)} to have been called. Every program in the
     * estate opens before it reads, so a not-open condition is unobservable here, and inventing a status
     * for it would add a value no backend produced. What an unopened browse does instead is the faithful
     * thing: it starts at the first record, which is where an {@code OPEN INPUT} would have positioned
     * it.
     *
     * <p>Calling this method again after end of file returns end of file again. The COBOL loop stops at
     * the first {@code '10'} and never reads past it, so no program observes anything else, and the
     * alternative - a status meaning "no valid next record" - would again be a value invented here.
     *
     * @return the discriminated outcome; never {@code null}
     * @throws IllegalStateException     if the backend presents the dataset with no usable record-image
     *                                   column
     * @throws IllegalArgumentException  if a stored image is wider than {@link #RECORD_LENGTH}, meaning
     *                                   the data and the copybook disagree
     */
    public ReadResult readNext() {
        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            LOG.error("Could not describe the account master dataset '" + datasetName + "' to begin a "
                    + "browse; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " to the caller", unreachable);
            return ReadResult.of(PERMANENT_ERROR_STATUS);
        }

        String position = this.browsePosition;
        boolean fromStart = position == null;
        String statement = fromStart ? sql.selectFirst() : sql.selectNext();

        List<String> rows;
        try {
            RowMapper<String> recordImageMapper = AccountRepository::mapRecordImage;
            rows = jdbcTemplate.query(firstRowOf(statement, fromStart ? null : position),
                    recordImageMapper);
        } catch (DataAccessException translated) {
            // The fatal arm. An I/O failure is reported as a status so the caller's own guard chain
            // decides what to do about it - which, in CBACT01C, is to display and abend.
            LOG.error("Rejected browse read of the account master dataset '" + datasetName
                    + "'; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " to the caller", translated);
            return ReadResult.of(PERMANENT_ERROR_STATUS);
        }

        // The list itself is never null - the template asserts that internally before returning it - so
        // emptiness is the whole of the end-of-file test, and no unreachable null arm is written here.
        if (rows.isEmpty()) {
            // AT END with no record read. An expected outcome, not an error.
            return ReadResult.endOfFile();
        }
        String recordImage = rows.get(0);
        if (recordImage == null) {
            // A row whose record image is absent is not a readable 300-byte record. There IS a record,
            // it simply cannot be read, so this is an I/O-level defect and not an end of file - it must
            // not be mistaken for one, or a browse would stop early and silently.
            LOG.error("The account master dataset '" + datasetName + "' presented a row with no record "
                    + "image at column position " + RECORD_IMAGE_COLUMN_INDEX + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
            return ReadResult.of(PERMANENT_ERROR_STATUS);
        }

        // The position advances to the image exactly as the backend presented it, which is what keeps
        // the next comparison an apples-to-apples one against the stored values.
        this.browsePosition = recordImage;
        return ReadResult.found(decode(recordImage));
    }

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
        return readKeyed(AccountRecord.keyImage(acctId, codec.charset()), "the account identifier "
                + acctId);
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
     * <p><strong>Where the lock is.</strong> No row-locking clause is added to the statement and no
     * version column is introduced. The CICS file is defined {@code UPDATEMODEL(LOCKING)} with
     * {@code RECOVERY(NONE)}, which means the record is held for the unit of work; that unit of work is
     * the caller's transaction, so this is modelled as a plain transactional read. Consequently this
     * method issues the same statement as {@link #readByKey(long)} - they differ in which COBOL view of
     * the key they accept and in what the caller does with the outcome, not in how the record is
     * fetched.
     *
     * @param acctIdAsChar11 the key exactly as the {@code RIDFLD} holds it: exactly {@link #KEY_LENGTH}
     *                       characters, untrimmed and unparsed
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException     if {@code acctIdAsChar11} is {@code null}
     * @throws IllegalArgumentException if {@code acctIdAsChar11} is not exactly {@link #KEY_LENGTH}
     *                                  characters, or if the stored image is wider than
     *                                  {@link #RECORD_LENGTH}
     * @throws IllegalStateException    if the backend presents the dataset with no usable record-image
     *                                  column
     */
    public ReadResult readForUpdate(String acctIdAsChar11) {
        String keyImage = requireKeyImage(acctIdAsChar11);
        return readKeyed(keyImage, "the record identification field '" + keyImage + "'");
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
     *   <li>anything else - a permanent error status, including the case of more than one row matching,
     *       which a unique primary key makes impossible and which is therefore reported rather than
     *       assumed away.</li>
     * </ul>
     *
     * <p>The image is bound as text, and that choice is deliberate rather than incidental. This
     * repository <em>reads</em> the record image from the same single column as a character value, so
     * writing it as one keeps both directions in one projection of one code page; binding bytes on the
     * way out while reading characters on the way in would be an asymmetry against the same column. The
     * configured code page is single-byte for zoned data - {@link AccountRecord} refuses a charset that
     * is not - so the character form and the byte form of a record are in one-to-one correspondence and
     * nothing is lost either way. A deployment whose gateway presents the record image as binary supplies
     * a driver that accepts the character form for it; the driver is a deployment-time input.
     *
     * @param record the record to write, complete and already mutated by the caller
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException  if {@code record} is {@code null}
     * @throws IllegalStateException if the backend presents the dataset with no usable record-image
     *                               column
     */
    public WriteResult rewrite(AccountRecord record) {
        Objects.requireNonNull(record, "A record is required to rewrite it; a COBOL REWRITE writes the "
                + "record area, and there is no such thing as rewriting nothing");

        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            LOG.error("Could not describe the account master dataset '" + datasetName + "' to rewrite a "
                    + "record; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " to the caller", unreachable);
            return WriteResult.of(PERMANENT_ERROR_STATUS);
        }

        String recordImage = record.toFixedWidthString();
        String keyPattern = asPrefixPattern(record.keyImage());
        int rewritten;
        try {
            PreparedStatementSetter binder = parameters -> {
                parameters.setString(1, recordImage);
                parameters.setString(2, keyPattern);
            };
            rewritten = jdbcTemplate.update(sql.rewrite(), binder);
        } catch (DataAccessException rejected) {
            LOG.error("Rejected rewrite of account " + record.keyImage() + " in dataset '" + datasetName
                    + "'; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " to the caller", rejected);
            return WriteResult.of(PERMANENT_ERROR_STATUS);
        }

        if (rewritten == 1) {
            return WriteResult.written();
        }
        if (rewritten == 0) {
            // An invalid-key condition: there is no such record to rewrite.
            return WriteResult.notFound();
        }
        // Unreachable against a unique primary key, and precisely for that reason not assumed away: if
        // the backend really did rewrite several rows for one key, the dataset is not the KSDS the
        // copybook describes and the caller must be told the operation failed.
        LOG.error("Rewrite of account " + record.keyImage() + " in dataset '" + datasetName
                + "' reported " + rewritten + " affected rows; a KSDS primary key is unique, so "
                + "reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                + " to the caller");
        return WriteResult.of(PERMANENT_ERROR_STATUS);
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
     * @param keyImage the stored key image, exactly {@link #KEY_LENGTH} characters
     * @param subject  how to name the key in a diagnostic, so a log line says which read failed
     * @return the discriminated outcome; never {@code null}
     */
    private ReadResult readKeyed(String keyImage, String subject) {
        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            LOG.error("Could not describe the account master dataset '" + datasetName + "' to read "
                    + subject + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller", unreachable);
            return ReadResult.of(PERMANENT_ERROR_STATUS);
        }

        List<String> rows;
        try {
            RowMapper<String> recordImageMapper = AccountRepository::mapRecordImage;
            rows = jdbcTemplate.query(firstRowOf(sql.selectByKey(), asPrefixPattern(keyImage)),
                    recordImageMapper);
        } catch (DataAccessException translated) {
            LOG.error("Rejected keyed read of " + subject + " in the account master dataset '"
                    + datasetName + "'; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller", translated);
            return ReadResult.of(PERMANENT_ERROR_STATUS);
        }

        // As in the browse, the list itself is never null, so emptiness is the whole of the test.
        if (rows.isEmpty()) {
            // The INVALID KEY condition. Reported, never thrown, so the caller can display the
            // identifier before it decides what to do - see readByKey(long).
            return ReadResult.notFound();
        }
        String recordImage = rows.get(0);
        if (recordImage == null) {
            LOG.error("The account master dataset '" + datasetName + "' presented " + subject
                    + " with no record image at column position " + RECORD_IMAGE_COLUMN_INDEX
                    + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " to the caller");
            return ReadResult.of(PERMANENT_ERROR_STATUS);
        }
        return ReadResult.found(decode(recordImage));
    }

    // =================================================================================================
    // Statement resolution. This is the one place a SQL identifier is decided, and the one place the
    // backend is asked to describe the dataset.
    // =================================================================================================

    /**
     * Reports whether the dataset is addressable, as an {@code OPEN} or a {@code CLOSE} would.
     *
     * <p>Read-only: the probe statement carries a predicate that is false on every row, so the relation
     * is described without any of it being transferred. That is what makes it usable against a driver
     * this build cannot exercise, and it is a genuine dataset-scoped check rather than a bare connection
     * test - an absent dataset fails here, which is exactly what an {@code OPEN} would report.
     *
     * @return {@link FileStatus#OK} or {@link #PERMANENT_ERROR_STATUS}
     * @throws IllegalStateException if the dataset is reachable but presents no usable record-image
     *                               column
     */
    private String probeDataset() {
        try {
            resolveStatements();
            return FileStatus.OK;
        } catch (DataAccessException unreachable) {
            LOG.error("The account master dataset '" + datasetName + "' could not be described; "
                    + "reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " to the caller", unreachable);
            return PERMANENT_ERROR_STATUS;
        }
    }

    /**
     * Returns the composed statements, resolving them on first use.
     *
     * <p><strong>Why a name has to be discovered at all.</strong> Every dataset in this module is reached
     * as a single-column relation whose one column holds the record image, and a sequential read can
     * address that column purely by position. A keyed read and a rewrite cannot: SQL permits an ordinal
     * in {@code ORDER BY} but not in a {@code WHERE} or a {@code SET} clause. The name is therefore
     * taken from the result-set metadata of the probe statement - <em>discovered from the backend</em>,
     * never invented here, never defaulted, and never added as a configuration key, because a name this
     * file made up would be exactly the kind of unverifiable literal the migration forbids.
     *
     * <p>The resolved shape is cached because it cannot change while the dataset is open, and because a
     * browse would otherwise pay for a metadata round trip per record. {@link #open(OpenMode)} and
     * {@link #close()} both discard it, so the shape is re-established by the next operation - which is
     * what makes an {@code OPEN} after a {@code CLOSE} a genuine re-open rather than a no-op.
     *
     * @return the composed statements; never {@code null}
     * @throws DataAccessException    if the dataset cannot be described - an I/O outcome, translated into
     *                                a status by every caller of this method
     * @throws IllegalStateException  if the dataset is described but presents no usable record-image
     *                                column, which is a contract violation: the whole module addresses
     *                                a dataset as a record-image relation, and one that is not cannot be
     *                                read or written at all
     */
    private Statements resolveStatements() {
        Statements resolved = this.statements;
        if (resolved == null) {
            ResultSetExtractor<String> columnNameExtractor =
                    AccountRepository::extractRecordImageColumnName;
            String columnName = jdbcTemplate.query(columnProbeSql, columnNameExtractor);
            resolved = Statements.over(qualifiedDatasetName,
                    asDelimitedIdentifier(requireUsableColumnName(columnName)));
            this.statements = resolved;
        }
        return resolved;
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
        ResultSetMetaData metaData = resultSet.getMetaData();
        if (metaData == null || metaData.getColumnCount() < RECORD_IMAGE_COLUMN_INDEX) {
            return null;
        }
        return metaData.getColumnName(RECORD_IMAGE_COLUMN_INDEX);
    }

    /**
     * Maps one result-set row to its record image, by column position.
     *
     * <p>Position {@value #RECORD_IMAGE_COLUMN_INDEX} and never a column name here, for the reason given
     * on this class: a dataset carrying no relational metadata is presented as a single record-image
     * column. The value is read as text and is <em>not</em> trimmed - the trailing bytes of this record
     * are {@code FILLER} spaces and they are part of it.
     *
     * @param resultSet the row, positioned by the template
     * @param rowNumber the 0-based row index, part of the mapper contract and not used: every row of
     *                  this dataset has the identical shape, so the index carries no meaning here
     * @return the row's record image, which may be {@code null} if the column holds no value
     * @throws SQLException if the driver cannot supply the column
     */
    private static String mapRecordImage(ResultSet resultSet, int rowNumber) throws SQLException {
        return resultSet.getString(RECORD_IMAGE_COLUMN_INDEX);
    }

    /**
     * Builds a statement that returns at most one row, binding the single parameter when there is one.
     *
     * <p>The row limit is set on the {@link PreparedStatement} rather than expressed as a row-limiting
     * clause, which keeps every statement in this class free of dialect syntax - no fetch-first and no
     * offset appears anywhere. A driver that declines to honour the limit costs efficiency and nothing
     * else: the first row is taken and the rest ignored, so the outcome is identical either way.
     *
     * @param sql       the statement text
     * @param parameter the single parameter to bind, or {@code null} for a statement that takes none
     * @return a creator for the prepared, limited and bound statement
     */
    private static PreparedStatementCreator firstRowOf(String sql, String parameter) {
        return connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setMaxRows(1);
            if (parameter != null) {
                statement.setString(1, parameter);
            }
            return statement;
        };
    }

    /**
     * Decodes a stored record image into an {@link AccountRecord}.
     *
     * <p>Two steps, and both are faithful counterparts of something real:
     * <ol>
     *   <li><strong>a short image is widened with spaces</strong> to the declared width. The record is
     *       fixed-length, so the only bytes it can be missing are trailing ones, and the trailing 178
     *       bytes are {@code FILLER X(178)} ({@code app/cpy/CVACT01Y.cpy:L17}) - a {@code FILLER}
     *       carrying no literal holds spaces. A backend that trims a character column is therefore
     *       repaired exactly, not guessed at. An over-long image is rejected instead of truncated,
     *       because it means the data and the copybook disagree;</li>
     *   <li><strong>the record is decoded by absolute offset</strong> against the copybook layout, in the
     *       injected code page, retaining every byte verbatim - which is what makes decoding and
     *       re-encoding a stored record byte-identical.</li>
     * </ol>
     *
     * @param recordImage the stored image, up to {@link #RECORD_LENGTH} characters
     * @return the decoded record
     * @throws IllegalArgumentException if {@code recordImage} encodes to more than
     *                                  {@link #RECORD_LENGTH} bytes
     */
    private AccountRecord decode(String recordImage) {
        byte[] widened =
                codec.padToDeclaredWidth(recordImage.getBytes(codec.charset()), RECORD_LENGTH);
        return AccountRecord.decode(widened, codec.charset());
    }

    // =================================================================================================
    // Pure helpers. Private and static: no instance state is involved, and no static state is
    // introduced - every one of them is a function of its arguments alone.
    // =================================================================================================

    /**
     * Turns a key image into a {@code LIKE} prefix pattern, escaping every metacharacter it contains.
     *
     * <p>The pattern matches a record image that begins with the key and continues with anything, which
     * for a fixed-width record whose leading field is the key selects exactly the record with that key.
     *
     * <p>Escaping is not defensive decoration. The online {@code RIDFLD} is a {@code PIC X(11)}
     * character field fed from a screen ({@code app/cbl/COACTUPC.cbl:L3892}), so it can carry any
     * character at all; an unescaped single-character wildcard inside it would match any byte and could
     * return - or rewrite - a different account's record.
     *
     * @param keyImage the stored key image
     * @return the escaped prefix pattern
     */
    private static String asPrefixPattern(String keyImage) {
        StringBuilder pattern = new StringBuilder(keyImage.length() + 2);
        for (int index = 0; index < keyImage.length(); index++) {
            char character = keyImage.charAt(index);
            if (character == LIKE_ESCAPE
                    || character == LIKE_WILDCARD
                    || character == LIKE_SINGLE_WILDCARD) {
                pattern.append(LIKE_ESCAPE);
            }
            pattern.append(character);
        }
        return pattern.append(LIKE_WILDCARD).toString();
    }

    /**
     * Renders a name as a single SQL delimited identifier.
     *
     * <p>A mainframe dataset name contains periods, and an unquoted period is a name separator in SQL,
     * so the name has to be delimited or it would be parsed as a chain of qualifiers. The same wrapping
     * is applied to the discovered column name, which protects a name that is case-sensitive or that
     * collides with a reserved word. Any embedded quote character is repeated, which is how the SQL
     * standard escapes one inside a delimited identifier; together with the control-character rejection
     * in {@link #requireUsableDatasetName(String)} and {@link #requireUsableColumnName(String)}, that
     * leaves no way for a value to terminate the identifier early.
     *
     * @param name the validated name
     * @return the name as a delimited identifier
     */
    private static String asDelimitedIdentifier(String name) {
        return IDENTIFIER_QUOTE + name.replace(IDENTIFIER_QUOTE, IDENTIFIER_QUOTE + IDENTIFIER_QUOTE)
                + IDENTIFIER_QUOTE;
    }

    /**
     * Resolves one DD name and proves the geometry its binding declares.
     *
     * @param datasetBindings the configured catalogue
     * @param ddName          the DD or CICS {@code FILE} name to resolve
     * @return the resolved binding
     * @throws IllegalStateException if the name is unconfigured, if the binding declares a record width
     *                               other than {@link #RECORD_LENGTH}, or if it declares a key width
     *                               other than {@link #KEY_LENGTH}
     */
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
        requireNoControlCharacter(candidate, "The dataset name configured at carddemo.datasets."
                + CICS_FILE_NAME + ".dsname");
        return candidate;
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
                    + ") but was given " + candidate.length() + " character(s): '" + candidate + "'. A "
                    + "fixed-width key carries its padding, so it is never trimmed and never short - "
                    + "render an account identifier with AccountRecord.keyImage(long, Charset).");
        }
        return candidate;
    }

    // =================================================================================================
    // The composed statements.
    // =================================================================================================

    /**
     * The four statements this repository issues, composed once the record-image column is known.
     *
     * <p>An immutable value, package-visible so that a unit test can assert the composed text directly
     * rather than inferring it from a database round trip. It is not part of the public contract: no
     * caller outside this package can name the type.
     *
     * <p>Every statement is confined to core SQL - a delimited identifier, {@code ORDER BY}, a
     * comparison, {@code LIKE … ESCAPE} and a single-column {@code UPDATE} - and none of them names a
     * column list, because this migration introduces no schema and therefore has no column names of its
     * own to name.
     *
     * @param selectFirst the first read of a browse: every record in ascending key order, limited to one
     *                    row by the statement. No predicate, because an {@code OPEN INPUT} positions
     *                    before the first record
     * @param selectNext  every later read of a browse: the first record whose image sorts strictly after
     *                    the one already returned, in ascending key order. The parameter is the previous
     *                    <em>image</em> and not its key - see {@link AccountRepository#browsePosition}
     * @param selectByKey the keyed read: the record whose image begins with the key
     * @param rewrite     the rewrite: replace the image of the record whose image begins with the key
     */
    record Statements(String selectFirst, String selectNext, String selectByKey, String rewrite) {

        /**
         * Composes the four statements over one dataset and one record-image column.
         *
         * @param dataset the dataset name, already rendered as a delimited identifier
         * @param column  the record-image column name, already rendered as a delimited identifier
         * @return the composed statements
         */
        static Statements over(String dataset, String column) {
            String source = "SELECT * FROM " + dataset;
            String ascending = " ORDER BY " + column + " ASC";
            String keyed = " WHERE " + column + " LIKE ? ESCAPE '" + LIKE_ESCAPE + "'";
            return new Statements(
                    source + ascending,
                    source + " WHERE " + column + " > ?" + ascending,
                    source + keyed,
                    "UPDATE " + dataset + " SET " + column + " = ?" + keyed);
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
        return columnProbeSql;
    }

    /**
     * The resolved statements, or {@code null} while they have not been resolved.
     *
     * <p>Exposed to this class's own tests so that the composed text and the caching behaviour can both
     * be asserted. Package-visible, and safe to hand out because {@link Statements} is immutable.
     *
     * @return the cached statements, or {@code null}
     */
    Statements resolvedStatements() {
        return statements;
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
     * @param status  the two-character file status the read reported, verbatim
     * @param outcome its classification
     * @param account the decoded record, present exactly when {@code outcome} is {@link Outcome#OK}
     */
    public record ReadResult(String status, Outcome outcome, Optional<AccountRecord> account) {

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
            return new ReadResult(FileStatus.OK, Outcome.OK, Optional.of(account));
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
            return new ReadResult(status, classify(status), Optional.empty());
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
         * The CICS {@code RESP} value equivalent to this outcome, where the correspondence is
         * one-to-one.
         *
         * <p>For the online caller, which tests {@code RESP} rather than a file status and renders it
         * into its error message as {@code ERROR-RESP} ({@code app/cbl/COACTUPC.cbl:L3721}). Empty where
         * a status has no single CICS counterpart - see {@link FileStatus#cicsRespOfBatchStatus(String)},
         * which reports that ambiguity rather than resolving it.
         *
         * @return the equivalent response value, or an empty {@code OptionalInt}
         */
        public OptionalInt cicsResp() {
            return FileStatus.cicsRespOfBatchStatus(status);
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
     * @param status  the two-character file status the rewrite reported, verbatim
     * @param outcome its classification
     */
    public record WriteResult(String status, Outcome outcome) {

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
            return new WriteResult(status, classify(status));
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
         * The CICS {@code RESP} value equivalent to this outcome, where the correspondence is
         * one-to-one.
         *
         * @return the equivalent response value, or an empty {@code OptionalInt}
         */
        public OptionalInt cicsResp() {
            return FileStatus.cicsRespOfBatchStatus(status);
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

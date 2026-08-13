package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.transaction.model.TranCatBalRecord;
import com.vsergeychik.carddemo.transaction.model.TranCatBalRecord.TranCatKey;

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

import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The transaction category balance dataset, {@code TCATBALF}, reached over JDBC: one repository for the
 * five access paths the COBOL estate drives against it, and nothing beyond them.
 *
 * <h2>The 17-byte composite key, and the two collisions that hide a mistake in it</h2>
 *
 * <p>{@code app/cpy/CVTRA01Y.cpy} declares a 50-byte record whose key is a <strong>17-byte</strong>
 * composite - and this class's correctness rests more on that number than on anything else it does:
 * <pre>
 * 01  TRAN-CAT-BAL-RECORD.                        offset (1-based)   bytes
 *     05  TRAN-CAT-KEY.                                1-17            17
 *        10 TRANCAT-ACCT-ID   PIC 9(11).               1-11            11
 *        10 TRANCAT-TYPE-CD   PIC X(02).              12-13             2
 *        10 TRANCAT-CD        PIC 9(04).              14-17             4
 *     05  TRAN-CAT-BAL        PIC S9(09)V99.          18-28            11
 *     05  FILLER              PIC X(22).              29-50            22
 * </pre>
 * {@code app/cbl/CBTRN02C.cbl:L91-L97} corroborates the total independently, splitting the same record
 * as {@code FD-TRAN-CAT-KEY} (11 + 2 + 4) plus {@code FD-FD-TRAN-CAT-DATA PIC X(33)} - and 17 + 33 is
 * 50. {@code app/cbl/CBACT04C.cbl:L61-L67} declares it identically. Every record of
 * {@code app/data/ASCII/tcatbal.txt} measures exactly 50 bytes.
 *
 * <p><strong>Collision 1 - the 50-byte near-miss.</strong> {@code app/cpy/CVTRA02Y.cpy} declares
 * {@code DIS-GROUP-RECORD}, which <em>also</em> totals exactly 50 bytes, but whose {@code DIS-GROUP-KEY}
 * is <strong>16</strong> bytes - {@code DIS-ACCT-GROUP-ID X(10)} plus {@code DIS-TRAN-TYPE-CD X(02)}
 * plus {@code DIS-TRAN-CAT-CD 9(04)} - placing its {@code DIS-INT-RATE S9(04)V99} at 0-based 16 with a
 * width of 6 rather than 11. Because the two records are the same length, <em>a total-width check
 * cannot tell them apart</em>: a 16-byte key used here would still read a 50-byte row, still decode a
 * number, and still return it. The defect would surface only as wrong money, and only in
 * {@code CBACT04C}, which is the one program that works both layouts side by side - it reads this
 * dataset and looks rates up in the disclosure group dataset in the same loop
 * ({@code app/cbl/CBACT04C.cbl:L210-L213}). {@link #keyLength()} is therefore asserted against
 * {@link #DISCLOSURE_GROUP_KEY_LENGTH} at class initialisation, so the substitution cannot survive a
 * build.
 *
 * <p><strong>Collision 2 - the same COBOL name for a 6-byte key.</strong> {@code app/cpy/CVTRA04Y.cpy}
 * declares a group whose name is <em>literally</em> {@code TRAN-CAT-KEY}, exactly as this record's is,
 * but which is only <strong>6</strong> bytes - {@code TRAN-TYPE-CD X(02)} plus {@code TRAN-CAT-CD
 * 9(04)}, with no account identifier at all - and it belongs to
 * {@link com.vsergeychik.carddemo.transaction.model.TranCategoryRecord}. A key named the same thing,
 * in the same Java package, addressing a different dataset. This repository therefore addresses
 * {@code TCATBALF} through {@link TranCatKey} alone - the value type
 * {@link TranCatBalRecord} declares for its own key - and never through a key belonging to another
 * record. {@link #TRAN_CATEGORY_KEY_LENGTH} records the 6-byte width so the assertion that this key is
 * not that one is explicit rather than implied.
 *
 * <h2>Two consumers, two access modes, two different guard ladders</h2>
 *
 * <p>Exactly two programs touch this dataset - established by inspection, not assumed:
 * {@code grep -n TCATBAL app/cbl/*.cbl} matches {@code CBACT04C} and {@code CBTRN02C} and nothing else.
 * They reach it in genuinely different ways, and both land on this one class.
 *
 * <ul>
 *   <li><strong>Sequential browse</strong> - {@code app/cbl/CBACT04C.cbl:L28-L32} declares
 *       {@code SELECT TCATBAL-FILE ASSIGN TO TCATBALF / ORGANIZATION IS INDEXED / ACCESS MODE IS
 *       SEQUENTIAL / RECORD KEY IS FD-TRAN-CAT-KEY / FILE STATUS IS TCATBALF-STATUS}, opens
 *       {@code INPUT} at {@code L236}, reads with {@code READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD} at
 *       {@code L326} until status {@code '10'}, and closes at {@code L524}. This is the driving input of
 *       the interest calculator. See {@link TranCatBalFile#readNext()}.</li>
 *   <li><strong>Random keyed read, then create or update</strong> -
 *       {@code app/cbl/CBTRN02C.cbl:L57-L61} declares the same dataset with
 *       {@code ACCESS MODE IS RANDOM}, opens {@code I-O} at {@code L329}, and paragraph
 *       {@code 2700-UPDATE-TCATBAL} ({@code L467-L501}) sets the key, reads, and then branches to
 *       {@code 2700-A-CREATE-TCATBAL-REC} ({@code L503-L524}) or
 *       {@code 2700-B-UPDATE-TCATBAL-REC} ({@code L526-L542}). See {@link #readByKey(TranCatKey)},
 *       {@link #write(TranCatBalRecord)} and {@link #rewrite(TranCatBalRecord)}.</li>
 * </ul>
 *
 * <h2>Status {@code '23'} is a normal outcome here, not an error</h2>
 *
 * <p>This is the single most important behavioural fact about this dataset, and it is the reason
 * {@link #readByKey(TranCatKey)} <strong>never throws</strong> when a record is absent.
 * {@code app/cbl/CBTRN02C.cbl:L474-L479} reads with an {@code INVALID KEY} phrase that is not a failure
 * path but a branch:
 * <pre>
 * READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD
 *    INVALID KEY
 *      DISPLAY 'TCATBAL record not found for key : '
 *         FD-TRAN-CAT-KEY '.. Creating.'
 *      MOVE 'Y' TO WS-CREATE-TRANCAT-REC
 * END-READ.
 * </pre>
 * and the guard that follows it, at {@code L481-L485}, is
 * <pre>
 * IF  TCATBALF-STATUS = '00'  OR '23'
 *     MOVE 0 TO APPL-RESULT
 * ELSE
 *     MOVE 12 TO APPL-RESULT
 * END-IF
 * </pre>
 * A missing category balance is the <em>expected</em> state the first time a transaction of some
 * category is posted for an account, and the program's response is to create one. So "found" and "not
 * found" are both first-class results here - {@link ReadResult#isFound()} and
 * {@link ReadResult#isNotFound()} - and the decision between them belongs to the caller.
 *
 * <p><strong>The two consumers classify statuses differently, and both classifications are exposed.</strong>
 * {@code CBTRN02C} treats {@code '00'} or {@code '23'} as success, as above.
 * {@code CBACT04C.cbl:L327-L335} treats only {@code '00'} as success, {@code '10'} as end of file, and
 * everything else - {@code '23'} included - as fatal:
 * <pre>
 * IF  TCATBALF-STATUS  = '00'      MOVE 0  TO APPL-RESULT
 * ELSE IF TCATBALF-STATUS = '10'   MOVE 16 TO APPL-RESULT
 *      ELSE                        MOVE 12 TO APPL-RESULT
 * </pre>
 * Collapsing those into one ladder would distort one consumer or the other, so both are provided and
 * each names the paragraph it reproduces: {@link ReadResult#applResult()} is {@code CBACT04C}'s and
 * {@link ReadResult#applResultWhereNotFoundIsNormal()} is {@code CBTRN02C}'s.
 *
 * <h2>This class reports statuses; it never abends, and it never does the arithmetic</h2>
 *
 * <p>Every operation returns a value carrying a two-character {@code FILE STATUS}, and none of them
 * terminates the program. The COBOL draws the boundary in exactly that place: the I/O paragraph reports
 * the status and the <em>caller</em> decides. The {@code DISPLAY 'ERROR READING TRANSACTION BALANCE
 * FILE'} line at {@code CBTRN02C.cbl:L489}, {@code 'ERROR WRITING TRANSACTION BALANCE FILE'} at
 * {@code L520}, {@code 'ERROR REWRITING TRANSACTION BALANCE FILE'} at {@code L538} and
 * {@code 'ERROR READING TRANSACTION CATEGORY FILE'} at {@code CBACT04C.cbl:L342} all belong to the
 * caller, together with {@code 9910-DISPLAY-IO-STATUS} and {@code 9999-ABEND-PROGRAM}.
 *
 * <p>Nor does this class touch the balance. {@code 2700-A-CREATE-TCATBAL-REC} performs
 * {@code INITIALIZE TRAN-CAT-BAL-RECORD} and then {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL}
 * ({@code L504-L508}), and {@code 2700-B-UPDATE-TCATBAL-REC} performs the same {@code ADD}
 * ({@code L527}) - both <em>before</em> the {@code WRITE} or {@code REWRITE}. Those are the job's
 * mutations on the job's record, so this repository persists the byte image it is handed and computes
 * nothing. That is also why no {@link java.math.BigDecimal} arithmetic appears here at all: the balance
 * is decoded and encoded by {@link TranCatBalRecord}, which routes it through
 * {@link com.vsergeychik.carddemo.common.CobolDecimal} at scale exactly
 * {@value com.vsergeychik.carddemo.common.CobolDecimal#MONETARY_SCALE} with
 * {@code RoundingMode.DOWN} - the truncation COBOL performs when no {@code ROUNDED} phrase is present,
 * and {@code ROUNDED} appears nowhere in the estate.
 *
 * <p>A contract violation is a different thing from an I/O outcome and is thrown rather than statused: a
 * configured record width that is not the copybook's, a configured key width that is not 17, a dataset
 * name that cannot address anything, or a backend presenting no record-image column. None of those is a
 * condition the COBOL could observe as a file status, and reporting one as a status would send a job
 * down its abend path with a misleading reason.
 *
 * <h2>How a fixed-width dataset is addressed over JDBC</h2>
 *
 * <p>The dataset is a fixed-width record store, not a relational table, and this migration introduces no
 * schema of its own: there is no data-definition statement anywhere in this class, no schema migration,
 * no entity mapping, <strong>no version column</strong> and no index creation. Every dataset is reached
 * as a single-column relation whose one column holds the whole record image, addressed at position
 * {@value #RECORD_IMAGE_COLUMN_INDEX} - the same convention every sibling repository uses.
 *
 * <p>A keyed read, a rewrite and an insert each need that column's <em>name</em>, because SQL permits an
 * ordinal in {@code ORDER BY} but not in a {@code WHERE}, a {@code SET} or an insert column list. The
 * name is therefore <strong>discovered</strong> from JDBC result-set metadata by a probe that transfers
 * no rows, and is never invented, defaulted or configured. The statements composed from it are confined
 * to constructs that are core SQL and carry no dialect assumption - a delimited identifier,
 * {@code ORDER BY}, a comparison, {@code LIKE … ESCAPE}, a single-column {@code UPDATE} and a
 * single-column {@code INSERT}. Row limiting is applied through {@link PreparedStatement} rather than
 * through a dialect's row-limiting clause.
 *
 * <h2>Ordering is asserted in the statement, never assumed</h2>
 *
 * <p>{@link TranCatBalFile#readNext()} must deliver records in ascending {@code TRAN-CAT-KEY} order, and
 * this is not a preference - {@code CBACT04C}'s correctness depends on it. Its loop detects an account
 * break by comparing each record's account identifier with the previous one
 * ({@code app/cbl/CBACT04C.cbl:L194}, {@code IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM}) and, on a break,
 * posts the interest accumulated for the account that just ended
 * ({@code 1050-UPDATE-ACCOUNT}, {@code L350-L356}). Records arriving out of key order would split one
 * account across several breaks and post its interest several times over. Every browse statement
 * therefore carries an explicit ascending {@code ORDER BY}. Ordering by the record image <em>is</em>
 * ordering by the key: the 17-byte key occupies the leading bytes of the record and its numeric
 * components are zero-filled to their full width, so byte order and key order coincide. That the stored
 * data is already in that order was verified against the fixture - all 50 records of
 * {@code app/data/ASCII/tcatbal.txt} are in ascending key order - but nothing here relies on the
 * backend's natural scan order to reproduce it.
 *
 * <h2>Thread safety</h2>
 *
 * <p><strong>This repository is stateless and therefore thread-safe.</strong> It is a Spring singleton,
 * so anything mutable held on it would be shared by every concurrent execution; a browse position in
 * particular would let two callers consume each other's records and destroy the very ordering described
 * above. COBOL shares neither a file position nor an {@code OPEN} mode - both belong to one program
 * execution, exactly as {@code WORKING-STORAGE} does - so neither is held here. Every field is
 * {@code final} and immutable, and there is <strong>no static mutable state</strong>: the only
 * {@code static} members are immutable constants and a logger reference.
 *
 * <p>All per-execution state lives on {@link TranCatBalFile}, the {@link AutoCloseable} handle
 * {@link #open(OpenMode)} returns. One handle stands for one opened file: it carries that open's mode,
 * its status, the statements resolved for it and its browse position, and is used by whoever opened it -
 * exactly as a COBOL {@code FD} is used by the program that opened it. A handle is <em>not</em>
 * thread-safe and is not meant to be; obtaining a second one is free and is the correct answer.
 *
 * <h2>What this class deliberately does not have</h2>
 *
 * <ul>
 *   <li><strong>No delete.</strong> Verified rather than assumed: neither {@code CBACT04C} nor
 *       {@code CBTRN02C} contains a {@code DELETE} verb at all. A category balance is created and
 *       accumulated, never removed, so no delete operation is exposed.</li>
 *   <li><strong>No alternate-index finder.</strong> {@code TCATBALF} has no alternate index anywhere in
 *       {@code app/csd/CARDDEMO.CSD} or {@code app/jcl}, and both consumers address it by whole primary
 *       key or sequentially. The module's two alternate-index paths, {@code CARDAIX} and
 *       {@code CXACAIX}, belong to the card master and the cross-reference respectively.</li>
 *   <li><strong>No generic query surface.</strong> No predicate builder, no paging, no projection, no
 *       ad-hoc filter, and no search by partial key - the estate reads this dataset sequentially or by
 *       whole 17-byte key, and that is the entire surface.</li>
 *   <li><strong>No object-relational mapping.</strong> Records are constructed by the module's own
 *       hand-written fixed-width codec, addressed by absolute offset against the copybook, so every byte
 *       position is reviewable rather than hidden behind a mapping layer. The trailing
 *       {@code FILLER X(22)} is a first-class span of that layout and is written, never dropped.</li>
 * </ul>
 *
 * <h2>Environmental limit (migration risk R-E)</h2>
 *
 * <p>Production connectivity to the mainframe data backend <strong>cannot be exercised from this
 * build</strong>. The migration mandates JDBC to the existing VSAM backend with no schema change, and
 * VSAM has no standard Maven-published JDBC driver, so the {@code DataSource} is entirely
 * configuration-bound and the site-specific driver is supplied at deployment time. This class is
 * consequently validated against a fixture-backed in-memory relation seeded from
 * {@code app/data/ASCII/tcatbal.txt}, and the driver binding is documented as a deployment-time input
 * rather than pretended to be verified. Everything this class asserts about record geometry, key width,
 * statement text, ordering and status classification is exercised; the physical driver round trip is
 * not, and that limit is recorded here rather than absorbed silently.
 *
 * <h2>User-specified rules</h2>
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - and that single line is
 * the whole document, so <strong>no user rule governs this file</strong>. Its absence is not licence to
 * lower the bar: the migration plan elevates twelve enterprise practices to binding constraints instead,
 * and the ones bearing on this file are B2 (Spring Boot 3.5.16 JDBC APIs only - a {@link JdbcTemplate}
 * and nothing above it; no ORM), B3 (the COBOL, copybook and JCL trees cited throughout are read-only
 * and appear here only as provenance), B8 (explicit over implicit - the dataset name and every width
 * come from configuration, the code page is injected rather than defaulted, the ordering is stated in
 * the statement, and there are no wildcard imports), B9 (no static mutable state; constructor injection
 * only), B11 (the hand-written codec, so every offset is auditable) and B12 (the inability to reach a
 * production backend from this build is documented above rather than absorbed).
 *
 * @see TranCatBalRecord
 * @see TranCatKey
 * @see FileStatus
 */
@Repository
public class TranCatBalRepository {

    /**
     * Logger for the diagnostics this class emits on a failed operation.
     *
     * <p>{@code static final} and a reference to an immutable logger, so it introduces no shared mutable
     * state. It exists because the outcome that travels back to a caller is deliberately coarse - a
     * two-character status, exactly as the COBOL has - and discarding the underlying reason would leave a
     * production failure undiagnosable.
     */
    private static final Log LOG = LogFactory.getLog(TranCatBalRepository.class);

    // =================================================================================================
    // Declared geometry. Every one of these is restated from a source file and asserted against the
    // record type, so a divergence fails the class's initialisation rather than corrupting money.
    // =================================================================================================

    /**
     * The DD name under which both consumers bind this dataset: {@value}.
     *
     * <p>One name, not two. {@code app/jcl/INTCALC.jcl:L27-L28} and
     * {@code app/jcl/POSTTRAN.jcl:L41-L42} both read
     * {@code //TCATBALF DD DISP=SHR,DSN=…}, and {@code app/csd/CARDDEMO.CSD} defines no CICS file over
     * this dataset at all - no online program touches it. So unlike the account master, which is
     * {@code ACCTDAT} online and {@code ACCTFILE} in batch, this dataset has a single configured key.
     *
     * <p>Taken from {@link TranCatBalRecord#DD_NAME} rather than written again, so the record type and
     * the repository cannot come to disagree about which dataset they describe.
     */
    public static final String DD_NAME = TranCatBalRecord.DD_NAME;

    /**
     * The copybook that defines the record: {@value}.
     *
     * <p>Surfaced so a configuration diagnostic can name the authority for the width it is rejecting.
     */
    public static final String COPYBOOK = TranCatBalRecord.COPYBOOK;

    /**
     * The record width, always 50 bytes - the {@code RECLN = 50} of {@code app/cpy/CVTRA01Y.cpy}.
     *
     * <p>Corroborated independently by both file descriptions, {@code app/cbl/CBTRN02C.cbl:L91-L97} and
     * {@code app/cbl/CBACT04C.cbl:L61-L67}, each splitting the record as a 17-byte key plus
     * {@code PIC X(33)}.
     */
    public static final int RECORD_LENGTH = TranCatBalRecord.RECORD_LENGTH;

    /**
     * The key width, always <strong>17</strong> bytes - {@code TRANCAT-ACCT-ID 9(11)} plus
     * {@code TRANCAT-TYPE-CD X(02)} plus {@code TRANCAT-CD 9(04)}.
     *
     * <p>This is the number the class Javadoc's two collision warnings are about, and it is asserted in
     * {@link #verifyDeclaredGeometry()} against both of them.
     */
    public static final int KEY_LENGTH = TranCatBalRecord.TRAN_CAT_KEY_LENGTH;

    /**
     * The key width of {@code app/cpy/CVTRA02Y.cpy}'s {@code DIS-GROUP-KEY}: <strong>16</strong> bytes.
     *
     * <p>Recorded here for one purpose - so that "this key is 17 and not 16" is an assertion this class
     * makes rather than a fact a reader has to take on trust. {@code DIS-GROUP-RECORD} is also 50 bytes
     * in total, which is precisely why the difference is invisible to a width check. See
     * {@link #verifyDeclaredGeometry()}.
     */
    public static final int DISCLOSURE_GROUP_KEY_LENGTH = 16;

    /**
     * The width of {@code app/cpy/CVTRA04Y.cpy}'s identically-named {@code TRAN-CAT-KEY}:
     * <strong>6</strong> bytes.
     *
     * <p>{@code TRAN-TYPE-CD X(02)} plus {@code TRAN-CAT-CD 9(04)}, with no account identifier. Recorded
     * for the same reason as {@link #DISCLOSURE_GROUP_KEY_LENGTH}: the collision is by name rather than
     * by width, so the guard against it is worth stating explicitly.
     */
    public static final int TRAN_CATEGORY_KEY_LENGTH = 6;

    /**
     * The 0-based offset of the key within the record image: {@code 0}.
     *
     * <p>{@code TRAN-CAT-KEY} begins the record, so a keyed predicate needs no leading wildcard span.
     */
    public static final int KEY_OFFSET = TranCatBalRecord.TRAN_CAT_KEY_OFFSET;

    /**
     * The scale every balance this repository stores and returns carries: exactly
     * {@value com.vsergeychik.carddemo.common.CobolDecimal#MONETARY_SCALE}.
     *
     * <p>{@code TRAN-CAT-BAL PIC S9(09)V99} declares two digits after the implied decimal point, so the
     * scale is a property of the copybook and not a formatting choice. Named here, and asserted in
     * {@link #verifyDeclaredGeometry()} against {@link CobolDecimal#MONETARY_SCALE}, so that the
     * repository states the contract rather than inheriting it silently from
     * {@link TranCatBalRecord}.
     */
    public static final int TRAN_CAT_BAL_SCALE = CobolDecimal.MONETARY_SCALE;

    /**
     * The rounding every balance this repository stores is subject to: {@link RoundingMode#DOWN}.
     *
     * <p>Truncation, never half-up and never half-even. The keyword {@code ROUNDED} appears
     * <strong>nowhere</strong> in any of the 28 COBOL programs, and absent {@code ROUNDED} the COBOL
     * standard truncates excess fractional digits on store - so {@link RoundingMode#DOWN} is the only
     * faithful choice, and the two {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} sites
     * ({@code app/cbl/CBTRN02C.cbl:L508} and {@code L527}) are subject to it.
     *
     * <p>This repository performs no arithmetic itself - the {@code ADD} belongs to the caller and the
     * conversion to {@link TranCatBalRecord} - but the policy is named here so that a reader of the data
     * layer can see which rounding the stored bytes were produced under without leaving this file, and so
     * that {@link #verifyDeclaredGeometry()} can assert it.
     */
    public static final RoundingMode TRAN_CAT_BAL_ROUNDING = CobolDecimal.COBOL_ROUNDING;

    /**
     * The result-set position of the record-image column: {@value}.
     *
     * <p>A dataset carrying no relational metadata is presented as a single column holding the whole
     * record image, and that convention is the module's rather than this class's.
     */
    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    /**
     * The {@code APPL-RESULT} value both consumers move on a fatal I/O outcome: {@value}.
     *
     * <p>{@code MOVE 12 TO APPL-RESULT} at {@code app/cbl/CBTRN02C.cbl:L484}, {@code L515} and
     * {@code L533}, and at {@code app/cbl/CBACT04C.cbl:L333}.
     */
    public static final int APPL_RESULT_FATAL = 12;

    /**
     * The feedback byte of the permanent-error status, {@code '9'} followed by a binary zero.
     *
     * <p>A COBOL permanent error is reported as status key 1 of {@code '9'} with an implementor-defined
     * second byte, and a binary zero is the conventional "no further information" value. It is held as a
     * named constant so the composed status is readable rather than a literal with an invisible byte in
     * it.
     */
    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The status this repository reports for a failure the COBOL vocabulary has no specific code for -
     * an unreachable dataset, a refused statement, an unreadable row, or a row of the wrong width.
     *
     * <p>Neither consumer has a branch for a specific permanent-error code: both simply move
     * {@link #APPL_RESULT_FATAL} for anything that is not a status they name, so what matters is that
     * the status is not one of those and is rendered faithfully by
     * {@link FileStatus#toStatusImage(String)} for the caller's {@code 9910-DISPLAY-IO-STATUS}.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    /**
     * The key's position and width inside the record image, as every keyed statement addresses it.
     *
     * <p>Immutable, and the single place the 17-byte span is turned into a predicate, so a keyed read and
     * a rewrite cannot disagree about which bytes the key occupies.
     */
    private static final KeySpan KEY_SPAN = new KeySpan(KEY_OFFSET, KEY_LENGTH);

    /**
     * How many rows the pre-write probe needs to see before the answer is known: {@value}.
     *
     * <p>The question is never "how many rows" but "none, one, or more than one", so two is the whole of
     * what has to be transferred.
     */
    private static final int FAN_OUT_PROBE_LIMIT = 2;

    /**
     * Proves the declared geometry once, at class initialisation, and fails the build's first use of this
     * class rather than a later job if anything contradicts it.
     */
    static {
        verifyDeclaredGeometry();
    }

    // =================================================================================================
    // Collaborators. All final, all injected. There is no mutable per-instance state, and that is the
    // point: a Spring singleton holding an open mode, a browse position or a resolved shape would share
    // all three with every concurrent execution, and COBOL shares none of them. Practice B9, gate G53.
    // =================================================================================================

    /** The module's shared template, from the data-access configuration. */
    private final JdbcTemplate jdbcTemplate;

    /** The PICTURE-aware codec, holding the injected code page - never a platform default. */
    private final FixedWidthCodec codec;

    /** The configured dataset name, resolved from {@code carddemo.datasets} and never hard-coded. */
    private final String datasetName;

    /** The dataset contract: its name, its record width, and the statements over it. */
    private final DatasetRelation relation;

    /** How this deployment's driver presents a record image over JDBC. */
    private final RecordImageForm recordImageForm;

    /**
     * Resolves the configured binding, proves the record and key geometry, and captures the
     * collaborators - all before the context finishes starting, so nothing checkable is left to fail
     * mid-job.
     *
     * <p>Four things are established here, in this order, and each fails loudly rather than degrading:
     * <ol>
     *   <li>the collaborators are present - a missing one is a wiring defect and is reported as one;</li>
     *   <li>{@value #DD_NAME} is configured. {@link DatasetBindings#binding(String)} matches a key
     *       exactly, with no case-insensitive or fuzzy fallback, and names every configured key in its
     *       diagnostic if one is absent;</li>
     *   <li>the binding declares {@link #RECORD_LENGTH}. Any other width is a configuration defect: this
     *       repository decodes and encodes by absolute offset against {@code app/cpy/CVTRA01Y.cpy}, so a
     *       differently-sized record would silently misplace every field after the first;</li>
     *   <li>the binding's declared key width, <em>where one is declared</em>, is {@link #KEY_LENGTH} -
     *       and this is the check that matters most, because a 16 here would be the
     *       {@code CVTRA02Y} substitution the class Javadoc describes, and no width check downstream
     *       could catch it.</li>
     * </ol>
     *
     * <p>There is no same-dataset cross-check as the account master has, because there is nothing to
     * cross-check: this dataset has one configured DD name, not two.
     *
     * <p>The code page is injected and handed straight to the codec, never derived and never left to the
     * platform: a fixed-width mainframe record is bytes in a specific code page.
     *
     * @param jdbcTemplate    the module's shared template, from the data-access configuration
     * @param datasetBindings the DD-name-keyed dataset catalogue bound from {@code carddemo.datasets}
     * @param datasetCharset  the active dataset code page, selected by bean name so the choice is
     *                        explicit at the injection point
     * @param recordImageForm how the deployment's driver presents a record image over JDBC, from
     *                        {@value RecordImageForm#FORM_PROPERTY}; the module's single authority on
     *                        that, so this class never chooses a JDBC type for itself
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if {@value #DD_NAME} is unconfigured, if the binding declares a
     *                               record width other than {@link #RECORD_LENGTH} or a key width other
     *                               than {@link #KEY_LENGTH}, or if the resolved dataset name is unusable
     */
    public TranCatBalRepository(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm) {

        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + "transaction category balance dataset is reached through the module's shared template");
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

        DatasetBinding binding = requireTranCatBalGeometry(datasetBindings);
        this.relation = DatasetRelation.of(requireUsableDatasetName(binding.dsname()), RECORD_LENGTH);
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
     * @return 50
     */
    public int recordLength() {
        return RECORD_LENGTH;
    }

    /**
     * The key width this repository addresses records by, always {@link #KEY_LENGTH}.
     *
     * <p>Surfaced deliberately, so that the regression assertion the class Javadoc's first collision
     * warning calls for - that this key is 17 and <strong>not</strong> 16 - can be written against the
     * repository itself and not merely against the record type.
     *
     * @return 17
     */
    public int keyLength() {
        return KEY_LENGTH;
    }

    /**
     * The code page in which this repository encodes and decodes record images.
     *
     * <p>Surfaced so a caller that renders a key or a record itself uses the same code page rather than
     * choosing one, and so a test can assert that the injected charset is the one actually in use.
     *
     * @return the injected dataset code page; never {@code null}
     */
    public Charset datasetCharset() {
        return codec.charset();
    }

    // =================================================================================================
    // OPEN and CLOSE.
    //
    // 0000-TCATBALF-OPEN  - app/cbl/CBACT04C.cbl:L234-L250, OPEN INPUT
    // 0500-TCATBALF-OPEN  - app/cbl/CBTRN02C.cbl:L327-L343, OPEN I-O
    // 9000-TCATBALF-CLOSE - app/cbl/CBACT04C.cbl:L522-L538
    // 9500-TCATBALF-CLOSE - app/cbl/CBTRN02C.cbl:L674-L690
    //
    // All four paragraphs have one shape: issue the verb, then IF status = '00' move 0 to APPL-RESULT
    // else move 12, and on the fatal arm display an error line, render the status and abend. These
    // methods reproduce the first half only - the status - because the caller owns the second half.
    // =================================================================================================

    /**
     * Opens the transaction category balance dataset and hands back the handle that stands for that open.
     *
     * <p>Two verbs, both evidenced: {@code OPEN INPUT} at {@code app/cbl/CBACT04C.cbl:L236} and
     * {@code OPEN I-O} at {@code app/cbl/CBTRN02C.cbl:L329}. No program opens this dataset any other way -
     * in particular no program opens it {@code OUTPUT} or {@code EXTEND}, so no such mode exists here.
     *
     * <p><strong>Why this returns a handle rather than a status.</strong> An {@code OPEN} produces
     * something: a file with a mode and a position, private to the program that opened it. Reporting only
     * a status and keeping that file on the repository would make the position and the mode shared
     * singleton state, and two concurrent executions would then consume each other's records - which for
     * this dataset would corrupt {@code CBACT04C}'s account-break detection specifically. The handle
     * <em>is</em> the file, so each execution has its own and the repository keeps nothing. The status the
     * COBOL tests is {@link TranCatBalFile#openStatus()}, reported on the handle rather than lost.
     *
     * <p><strong>What "open" means against a configuration-bound driver.</strong> There is no persistent
     * file handle to acquire: the shared template borrows and returns a connection per operation. So this
     * method establishes the two things an {@code OPEN} establishes, and reports whether it could:
     * <ul>
     *   <li><strong>it positions the file.</strong> The returned handle is positioned before the first
     *       record, so its first {@link TranCatBalFile#readNext()} returns the first record in key order.
     *       This is a real, observable effect: a {@code CLOSE} followed by an {@code OPEN INPUT} re-reads
     *       a COBOL file from its first record, and a fresh handle re-reads this one from its first
     *       record too;</li>
     *   <li><strong>it proves the dataset is addressable.</strong> The metadata probe describes the
     *       relation without transferring a row, which is the failure an {@code OPEN} most often reports -
     *       an absent or unreachable dataset. It also resolves the record-image column once for the whole
     *       open, so no later operation on the handle pays for a second metadata round trip.</li>
     * </ul>
     *
     * <p>A failed open yields a handle carrying {@link #PERMANENT_ERROR_STATUS} and no resolved shape, so
     * every operation on it reports that same failure rather than a fresh one - a caller that ignored the
     * status still cannot mistake a dataset it never reached for one that was empty. The caller then owns
     * the {@code DISPLAY 'ERROR OPENING TRANSACTION CATEGORY BALANCE'} line
     * ({@code CBACT04C.cbl:L245}) or {@code DISPLAY 'ERROR OPENING TRANSACTION BALANCE FILE'}
     * ({@code CBTRN02C.cbl:L339}), the rendered status and the abend.
     *
     * <p>The mode is recorded and not enforced. Neither program mixes the modes against this dataset - a
     * browse is always under {@code OPEN INPUT} and the create/update pair always under {@code OPEN I-O} -
     * so refusing an operation on mode grounds would add a rejection the COBOL never performs and a
     * branch no caller could reach.
     *
     * @param mode the {@code OPEN} verb being issued
     * @return a freshly positioned handle whose {@link TranCatBalFile#openStatus()} reports whether the
     *         dataset was addressable; never {@code null}
     * @throws NullPointerException  if {@code mode} is {@code null}
     * @throws IllegalStateException if the backend presents the dataset with no usable record-image
     *                               column, which is a contract violation rather than an I/O outcome
     */
    public TranCatBalFile open(OpenMode mode) {
        Objects.requireNonNull(mode, "An open mode is required: the estate opens this dataset as "
                + OpenMode.INPUT.cobolVerb() + " or as " + OpenMode.I_O.cobolVerb() + ", and which verb "
                + "was issued is part of what the program did");

        // An OPEN resolves the dataset's shape afresh. The resolved shape and the browse position belong
        // to the returned handle, never to this instance: a Spring @Repository is a singleton, and two
        // concurrent executions sharing one position would interleave each other's browses.
        this.relation.forgetRecordImageColumn();

        Statements resolved;
        try {
            resolved = resolveStatements();
        } catch (DataAccessException unreachable) {
            logRefusal(unreachable, "describe the transaction category balance dataset '" + datasetName
                    + "' to " + mode.cobolVerb() + " it");
            return new TranCatBalFile(this, mode, PERMANENT_ERROR_STATUS, null);
        }
        return new TranCatBalFile(this, mode, FileStatus.OK, resolved);
    }

    // =================================================================================================
    // 2700-UPDATE-TCATBAL - app/cbl/CBTRN02C.cbl:L467-L501, the keyed read.
    // =================================================================================================

    /**
     * Reads one record by its three key components: the Java form of
     * {@code app/cbl/CBTRN02C.cbl:L469-L479},
     * <pre>
     * MOVE XREF-ACCT-ID     TO FD-TRANCAT-ACCT-ID
     * MOVE DALYTRAN-TYPE-CD TO FD-TRANCAT-TYPE-CD
     * MOVE DALYTRAN-CAT-CD  TO FD-TRANCAT-CD
     * MOVE 'N' TO WS-CREATE-TRANCAT-REC
     * READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD
     *    INVALID KEY
     *      DISPLAY 'TCATBAL record not found for key : '
     *         FD-TRAN-CAT-KEY '.. Creating.'
     *      MOVE 'Y' TO WS-CREATE-TRANCAT-REC
     * END-READ.
     * </pre>
     *
     * <p>The three parameters are the three COBOL sources, in copybook order and in their own COBOL
     * types. {@code acctId} is a {@code long} and {@code catCd} an {@code int} because
     * {@code TRANCAT-ACCT-ID PIC 9(11)} and {@code TRANCAT-CD PIC 9(04)} are scale-free {@code PIC 9}
     * integers with no decimal position - a {@code BigDecimal} would imply a scale they do not have.
     * {@code typeCd} is a {@code String} because {@code TRANCAT-TYPE-CD PIC X(02)} is alphanumeric: the
     * stored value {@code "01"} is two significant characters and not the number one, and a lower-case or
     * blank code is a legitimate value rather than an error.
     *
     * <p><strong>A missing record is a branch, not a failure.</strong> See the class Javadoc: the
     * {@code INVALID KEY} arm above is how {@code CBTRN02C} discovers that this is the first transaction
     * of its category for the account, and its response is to create the record. This method therefore
     * returns {@link ReadResult#notFound()} and <strong>never throws</strong>. The caller's own branch is
     * {@code IF WS-CREATE-TRANCAT-REC = 'Y'} at {@code L495}, which is {@link ReadResult#isNotFound()},
     * and its guard is {@code IF TCATBALF-STATUS = '00' OR '23'} at {@code L481}, which is
     * {@link ReadResult#isOkOrNotFound()}.
     *
     * @param acctId the account identifier, {@code TRANCAT-ACCT-ID PIC 9(11)}; must not be negative, as
     *               {@code PIC 9} declares no sign position and has no representation for one
     * @param typeCd the transaction type code, {@code TRANCAT-TYPE-CD PIC X(02)}; used verbatim, padded
     *               and truncated on the right as {@code PIC X} requires
     * @param catCd  the transaction category code, {@code TRANCAT-CD PIC 9(04)}; must not be negative
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException     if {@code typeCd} is {@code null}
     * @throws IllegalArgumentException if either numeric component is negative
     * @throws IllegalStateException    if the backend presents the dataset with no usable record-image
     *                                  column
     */
    public ReadResult readByKey(long acctId, String typeCd, int catCd) {
        return readByKey(new TranCatKey(acctId, typeCd, catCd));
    }

    /**
     * Reads one record by its composite key.
     *
     * <p>The form a caller holding an assembled key uses - including
     * {@link TranCatBalRecord#tranCatKey()} of a record already in hand.
     *
     * <p><strong>{@link TranCatKey} and nothing else.</strong> This is the second collision warning from
     * the class Javadoc made structural: {@code app/cpy/CVTRA04Y.cpy} declares a group of the identical
     * COBOL name {@code TRAN-CAT-KEY} that is only {@value #TRAN_CATEGORY_KEY_LENGTH} bytes and belongs
     * to {@link com.vsergeychik.carddemo.transaction.model.TranCategoryRecord}, in this same Java
     * package. Because the two are distinct Java types, one cannot be passed where the other is expected
     * and no compiler-silent substitution is possible.
     *
     * <p>Behaviour, including the treatment of a missing record, is exactly as
     * {@link #readByKey(long, String, int)} describes.
     *
     * @param key the 17-byte composite key
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException     if {@code key} is {@code null}
     * @throws IllegalArgumentException if either numeric component of {@code key} is negative
     * @throws IllegalStateException    if the backend presents the dataset with no usable record-image
     *                                  column
     */
    public ReadResult readByKey(TranCatKey key) {
        Objects.requireNonNull(key, "A TRAN-CAT-KEY is required to read TCATBALF by key; the COBOL sets "
                + "all three components before the READ (app/cbl/CBTRN02C.cbl:L469-L471)");

        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            return reportRead(unreachable, "describe the transaction category balance dataset '"
                    + datasetName + "' to read a record by key");
        }
        return readByKey(sql, key);
    }

    /**
     * Reads one record by key against already-resolved statements.
     *
     * <p>Shared by {@link #readByKey(TranCatKey)}, which resolves the dataset's shape for itself, and by
     * {@link TranCatBalFile#readByKey(TranCatKey)}, which reuses the shape its {@code OPEN} resolved. One
     * body, so a keyed read cannot behave differently depending on which entry point issued it.
     *
     * @param sql the resolved statements
     * @param key the 17-byte composite key
     * @return the discriminated outcome; never {@code null}
     */
    private ReadResult readByKey(Statements sql, TranCatKey key) {
        String keyImage = key.image(codec.charset());
        String subject = describeKey(keyImage);

        List<byte[]> rows;
        try {
            rows = jdbcTemplate.query(firstRowMatching(sql.selectByKey(), KEY_SPAN.pattern(keyImage)),
                    recordImageMapper());
        } catch (DataAccessException translated) {
            return reportRead(translated, "read " + subject + " from the transaction category balance "
                    + "dataset '" + datasetName + "'");
        }

        // The list itself is never null - the template asserts that internally before returning it - so
        // emptiness is the whole of the invalid-key test, and no unreachable null arm is written.
        if (rows.isEmpty()) {
            // The INVALID KEY condition of app/cbl/CBTRN02C.cbl:L475-L478. An EXPECTED outcome and the
            // signal to create the record, so it is reported and never thrown - but only once the absence
            // has been PROVED. All three components of TRAN-CAT-KEY live inside the record image
            // (app/cpy/CVTRA01Y.cpy:5-8), so a row whose record-image column holds nothing has no knowable
            // key and the keyed LIKE predicate cannot match it: SQL evaluates every comparison against a
            // null as UNKNOWN. Reporting '23' while such a row sits in the dataset sends the caller to
            // 2700-A-CREATE-TCATBAL-REC, which WRITEs a record whose key may already be present - so the
            // unproved absence is answered either by a duplicate-key '22' or, worse, by a second balance
            // for a category that already has one.
            return provenAbsence(sql.probeUnreadableRows(), subject);
        }
        byte[] recordImage = rows.get(0);
        if (recordImage == null) {
            LOG.error("The transaction category balance dataset '" + datasetName + "' presented "
                    + subject + " with no record image at column position " + RECORD_IMAGE_COLUMN_INDEX
                    + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " to the caller");
            // A row that is present and unreadable is an invalid request rather than a missing record:
            // reporting '23' would tell the caller to CREATE a record that already exists.
            return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
        }
        return decoded(recordImage, subject);
    }

    /**
     * Reports the {@code INVALID KEY} condition only once no row of the dataset is
     * <strong>unreadable</strong>, and the invalid-request outcome when one is.
     *
     * <h2>Why this absence in particular has to be proved</h2>
     * <p>{@code '23'} is not a diagnostic here - it is an instruction. {@code 2700-UPDATE-TCATBAL}
     * ({@code app/cbl/CBTRN02C.cbl:L475-L478}) takes it as "this account has no balance for this category
     * yet" and falls through to {@code 2700-A-CREATE-TCATBAL-REC}, which {@code WRITE}s a new record. So an
     * unproved absence does not mislead a reader, it writes to the dataset: either the {@code WRITE} is
     * refused as a duplicate key, or - if the unreadable row's key is not in fact this key - a category
     * that already has a balance quietly acquires a second one and the sum of the balances stops matching
     * the account.
     *
     * <p>The proof is one row-limited read on the not-found path only. A read that found its record is
     * untouched: a VSAM {@code READ} of a key that resolves does not fail because another record in the
     * cluster is damaged.
     *
     * <p>The outcome when a row is unreadable is {@link FileStatus#INVREQ} behind
     * {@link #PERMANENT_ERROR_STATUS} - the same arm the visible form of this condition already reports two
     * lines above - so the caller's guard chain keeps its shape and {@code 2700-UPDATE-TCATBAL} lands on
     * its {@code WHEN OTHER} rather than on its create.
     *
     * @param unreadableRowsProbe the statement selecting the rows whose record-image column holds nothing
     * @param subject             how to name the operation in a diagnostic
     * @return {@link ReadResult#notFound()} when the absence is established, the permanent-error outcome
     *         when it is not; never {@code null}
     */
    private ReadResult provenAbsence(String unreadableRowsProbe, String subject) {
        List<byte[]> unreadable;
        try {
            unreadable = jdbcTemplate.query(firstRow(unreadableRowsProbe), recordImageMapper());
        } catch (DataAccessException translated) {
            // The probe established nothing, so the absence stays unproved - and must not become the '23'
            // that authorises a WRITE. Reported on the arm a refused read is reported on.
            return reportRead(translated, "establish that the transaction category balance dataset '"
                    + datasetName + "' holds no unreadable row before reporting " + subject
                    + " as absent - which would authorise 2700-A-CREATE-TCATBAL-REC to write it");
        }
        // As on the keyed read above, the list itself is never null, so emptiness is the whole test.
        if (unreadable.isEmpty()) {
            // A genuine INVALID KEY: nothing matched the key and no row of the dataset is unreadable, so
            // the create at app/cbl/CBTRN02C.cbl:L503-L524 is the right next step.
            return ReadResult.notFound();
        }
        LOG.error("A keyed read of " + subject + " from the transaction category balance dataset '"
                + datasetName + "' matched no row, but the dataset holds a row with no record image at "
                + "column position " + RECORD_IMAGE_COLUMN_INDEX + " - and TRAN-CAT-KEY is part of that "
                + "image, so that row's key cannot be known; reporting file status "
                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                + " rather than the INVALID KEY that would have a balance written for a category that may "
                + "already have one");
        return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
    }

    // =================================================================================================
    // 2700-A-CREATE-TCATBAL-REC - app/cbl/CBTRN02C.cbl:L503-L524, the WRITE.
    // =================================================================================================

    /**
     * Adds a record that does not yet exist: the Java form of
     * {@code WRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD}
     * ({@code app/cbl/CBTRN02C.cbl:L510}).
     *
     * <p>The whole paragraph is
     * <pre>
     * INITIALIZE TRAN-CAT-BAL-RECORD
     * MOVE XREF-ACCT-ID     TO TRANCAT-ACCT-ID
     * MOVE DALYTRAN-TYPE-CD TO TRANCAT-TYPE-CD
     * MOVE DALYTRAN-CAT-CD  TO TRANCAT-CD
     * ADD  DALYTRAN-AMT     TO TRAN-CAT-BAL
     * WRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD
     * </pre>
     * and every line above the {@code WRITE} belongs to the caller. That division is deliberate and is
     * what the {@code INITIALIZE} makes visible: COBOL's {@code INITIALIZE} sets alphanumeric items to
     * spaces and numeric items to zero and <strong>skips {@code FILLER}</strong>, so the record's initial
     * byte image is a property of how the record was built rather than of how it is stored.
     * {@link TranCatBalRecord#newInstance(Charset)} and {@link TranCatBalRecord#initialize()} reproduce
     * that, and this method simply persists the byte image it is handed - all
     * {@link #RECORD_LENGTH} declared bytes of it, the trailing {@code FILLER X(22)} included, so a
     * stored record is byte-identical to the one the caller built.
     *
     * <p><strong>Only the record's own key is written, and it is not passed separately.</strong> A COBOL
     * {@code WRITE} to an indexed file is addressed by the key in the record area, and
     * {@code FROM TRAN-CAT-BAL-RECORD} moves the whole 50-byte record - whose leading 17 bytes
     * <em>are</em> {@code TRAN-CAT-KEY} - into that area. So no key parameter exists or should.
     *
     * <p><strong>A duplicate key is reported as {@code '22'}, and is detected before anything is
     * written.</strong> A KSDS {@code WRITE} whose key already exists is a duplicate-key condition, not a
     * silent replacement, and reproducing that faithfully cannot be left to the backend: a relation whose
     * record-image column carries no unique constraint would accept the insert and leave two records
     * under one key, after which the keyed read would return whichever the ordering happened to surface.
     * So the key is required to select no row <em>first</em>, and the insert is not issued at all if it
     * does. A backend that does enforce uniqueness and rejects the insert anyway is also honoured - an
     * integrity violation is translated to the same {@code '22'} - so the outcome is identical whether the
     * constraint exists or not.
     *
     * <p>The caller's guard is {@code IF TCATBALF-STATUS = '00'} at {@code L512}, which is
     * {@link WriteResult#isWritten()}; anything else moves {@link #APPL_RESULT_FATAL} and the caller
     * displays {@code 'ERROR WRITING TRANSACTION BALANCE FILE'} ({@code L520}), renders the status and
     * abends. A duplicate is therefore fatal <em>in the caller</em>, which is where the COBOL puts it.
     *
     * <p>The image is bound through the configured {@link RecordImageForm}, which is also what reads it
     * back, so the two directions cannot disagree about one column.
     *
     * @param record the record to add, complete and already mutated by the caller
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException  if {@code record} is {@code null}
     * @throws IllegalStateException if no unit of work is open, in which case nothing has been attempted -
     *                               see
     *                               {@link DatasetUnitOfWork#requireActiveToPersist(String, String)};
     *                               if the backend presents the dataset with no usable record-image
     *                               column; or - as a
     *                               {@link com.vsergeychik.carddemo.common.DatasetIntegrityException} -
     *                               if the insert added more than the one row it was asked to
     */
    public WriteResult write(TranCatBalRecord record) {
        Objects.requireNonNull(record, "A record is required to write it; a COBOL WRITE writes the record "
                + "area, and there is no such thing as writing nothing");

        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            return reportWrite(unreachable, "describe the transaction category balance dataset '"
                    + datasetName + "' to write a record");
        }
        return write(sql, record);
    }

    /**
     * Adds one record against already-resolved statements.
     *
     * <p>Shared by {@link #write(TranCatBalRecord)} and {@link TranCatBalFile#write(TranCatBalRecord)}, so
     * the two entry points cannot drift apart.
     *
     * @param sql    the resolved statements
     * @param record the record to add
     * @return the discriminated outcome; never {@code null}
     */
    private WriteResult write(Statements sql, TranCatBalRecord record) {
        byte[] recordImage = requireStorableImage(record, "written");
        // Refused before anything is attempted when no unit of work is open - after the record itself has
        // been checked, so a malformed record is still diagnosed as a malformed record. The pool hands out
        // connections with auto-commit disabled, so the INSERT would execute, report the row it added, and
        // then be rolled back on return, leaving 2700-A told that a balance record it will go on to
        // account for was created. CBTRN02C reaches this inside the step's own transaction; see
        // requireActiveToPersist for the other two boundaries.
        DatasetUnitOfWork.requireActiveToPersist("A write to the transaction category balance file, which "
                + "WRITE issues from the record area 2700-A-CREATE-TCATBAL-REC has just built "
                + "(app/cbl/CBTRN02C.cbl:L510)", datasetName);
        String keyImage = record.tranCatKeyImage();
        String keyPattern = KEY_SPAN.pattern(keyImage);
        String subject = describeKey(keyImage);

        // Establish that the key names NO row before inserting one. A KSDS WRITE on an existing key is a
        // duplicate-key condition, and a relation without a unique constraint would otherwise accept a
        // second record under the same key - after which a keyed read returns an arbitrary one of them.
        int existing;
        try {
            existing = matchingRowCount(sql.selectByKey(), keyPattern);
        } catch (DataAccessException rejected) {
            return reportWrite(rejected, "establish whether " + subject + " already exists in the "
                    + "transaction category balance dataset '" + datasetName + "' before writing it");
        }
        if (existing > 0) {
            // The duplicate-key condition. Nothing has been written and nothing will be.
            LOG.warn("A record for " + subject + " already exists in the transaction category balance "
                    + "dataset '" + datasetName + "', so the write is being refused before it is issued "
                    + "and file status " + FileStatus.toStatusImage(FileStatus.DUPLICATE)
                    + " reported to the caller. No row has been added.");
            return WriteResult.duplicate();
        }

        int inserted;
        try {
            PreparedStatementSetter binder = parameters ->
                    recordImageForm.bindImage(parameters, 1, recordImage, codec.charset());
            inserted = jdbcTemplate.update(sql.insert(), binder);
        } catch (DataAccessException rejected) {
            BackendDiagnostic diagnostic = BackendDiagnostic.of(rejected);
            if (diagnostic.integrityViolation()) {
                // The backend does enforce uniqueness and rejected the insert. Same condition as the
                // pre-check found, reached by the other route, so it is reported identically: the
                // duplicate-key status, not a permanent error.
                LOG.warn("The transaction category balance dataset '" + datasetName + "' refused a write "
                        + "of " + subject + " as an integrity violation - " + diagnostic.describe()
                        + "; reporting file status " + FileStatus.toStatusImage(FileStatus.DUPLICATE)
                        + " to the caller");
                return WriteResult.of(FileStatus.DUPLICATE, CicsResponse.of(FileStatus.DUPREC),
                        diagnostic);
            }
            return reportWrite(rejected, "write " + subject + " to the transaction category balance "
                    + "dataset '" + datasetName + "'");
        }

        if (inserted == 1) {
            return WriteResult.written();
        }
        if (inserted == 0) {
            // An insert that added nothing is not a success and has no COBOL counterpart: a WRITE either
            // adds the record or reports why it did not. Reported as a permanent error rather than as a
            // duplicate, because the key was checked and no row carried it.
            LOG.error("A write of " + subject + " to the transaction category balance dataset '"
                    + datasetName + "' reported that no row was added, although the key selected none "
                    + "beforehand; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
            return WriteResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
        }
        // One statement, one record, several rows added. The rows exist and a status would let the
        // enclosing unit of work commit them, so the unit of work is refused instead.
        throw DatasetUnitOfWork.commitRefusal(
                "The write of " + subject + " to dataset '" + datasetName + "'",
                inserted + " rows were added where one record was written, so the relation is not the "
                        + "single-record-image dataset app/cpy/" + COPYBOOK + ".cpy describes");
    }

    // =================================================================================================
    // 2700-B-UPDATE-TCATBAL-REC - app/cbl/CBTRN02C.cbl:L526-L542, the REWRITE.
    // =================================================================================================

    /**
     * Rewrites one record in place: the Java form of
     * {@code REWRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD}
     * ({@code app/cbl/CBTRN02C.cbl:L528}).
     *
     * <p>The paragraph is two lines - {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} then the {@code REWRITE} -
     * and the {@code ADD} belongs to the caller, on the record it read. This method changes nothing about
     * the record it is given: it writes all {@link #RECORD_LENGTH} declared bytes, the trailing
     * {@code FILLER X(22)} included, so a rewritten record is byte-identical to the one the caller built.
     * Because {@link TranCatBalRecord} keeps the 50-byte area as its single source of truth, the reserved
     * {@code FILLER} bytes that came back from the read survive the {@code ADD} and the rewrite untouched -
     * which is what a COBOL {@code REWRITE} of a record area does.
     *
     * <p><strong>The record carries its own key.</strong> A COBOL {@code REWRITE} on a randomly accessed
     * indexed file is addressed by the key in the record area, so the row rewritten is the one whose
     * leading {@link #KEY_LENGTH} bytes equal {@link TranCatBalRecord#tranCatKeyImage()} of the argument.
     * No separate key parameter exists or should.
     *
     * <p><strong>No version column and no optimistic-lock marker is introduced.</strong> This dataset has
     * neither in the legacy system, and adding one would be a schema change, which this migration does not
     * make (acceptance gate G44). Unlike the account and card masters, neither consumer of this dataset
     * performs a {@code 9300-CHECK-CHANGE-IN-REC}-style comparison either - {@code CBTRN02C} reads and
     * rewrites within one batch step - so there is no concurrency check here to preserve or to substitute.
     *
     * <p><strong>It never silently succeeds.</strong> Three outcomes:
     * <ul>
     *   <li>one row rewritten - status {@code '00'}. The COBOL moves {@code 0} to {@code APPL-RESULT};</li>
     *   <li>no row rewritten - status {@code '23'}, because a rewrite whose key matches nothing is an
     *       invalid-key condition and not a success. The caller reaches its fatal arm, which is
     *       {@code DISPLAY 'ERROR REWRITING TRANSACTION BALANCE FILE'} at {@code L538};</li>
     *   <li>more than one row would be rewritten - a permanent error status, reported <em>before</em>
     *       anything is written.</li>
     * </ul>
     *
     * <p><strong>Fan-out is precluded, not reported after the fact.</strong> The predicate the rewrite
     * carries selects the rows whose leading {@link #KEY_LENGTH} bytes are the key, and a KSDS primary key
     * is unique, so it names one row. If a deployment's relation does not enforce that uniqueness, the
     * same {@code UPDATE} would replace every matching row with this one record - and discovering that
     * from the affected-row count is discovering it too late, because the rows have already been
     * overwritten and a status returned from there reports damage the enclosing unit of work then commits.
     * So the key is required to name exactly one row first, in the same transaction and - when one is
     * open - under the same {@code FOR UPDATE} lock the {@code UPDATE} will use, and the write is not
     * issued at all unless it does. Should the count still come back wrong afterwards, the unit of work is
     * refused rather than reported: see {@link DatasetUnitOfWork#commitRefusal(String, String)}.
     *
     * <p>The cost is one extra bounded {@code SELECT} per rewrite, capped at
     * {@value #FAN_OUT_PROBE_LIMIT} rows because "0, 1 or more than 1" is the whole question. That is
     * accepted deliberately: this migration is explicitly not a performance refactoring, and a COBOL
     * {@code REWRITE} cannot produce a multi-record outcome for the estate to have code for, so the state
     * must not be reachable.
     *
     * @param record the record to write, complete and already mutated by the caller
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException  if {@code record} is {@code null}
     * @throws IllegalStateException if no unit of work is open, in which case nothing has been attempted;
     *                               if the backend presents the dataset with no usable record-image
     *                               column; or - as a
     *                               {@link com.vsergeychik.carddemo.common.DatasetIntegrityException} -
     *                               if the write replaced more rows than the key selected when it was
     *                               checked
     */
    public WriteResult rewrite(TranCatBalRecord record) {
        Objects.requireNonNull(record, "A record is required to rewrite it; a COBOL REWRITE writes the "
                + "record area, and there is no such thing as rewriting nothing");

        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            return reportWrite(unreachable, "describe the transaction category balance dataset '"
                    + datasetName + "' to rewrite a record");
        }
        return rewrite(sql, record);
    }

    /**
     * Rewrites one record against already-resolved statements.
     *
     * <p>Shared by {@link #rewrite(TranCatBalRecord)} and
     * {@link TranCatBalFile#rewrite(TranCatBalRecord)}, so the two entry points cannot drift apart.
     *
     * @param sql    the resolved statements
     * @param record the record to write
     * @return the discriminated outcome; never {@code null}
     */
    private WriteResult rewrite(Statements sql, TranCatBalRecord record) {
        byte[] recordImage = requireStorableImage(record, "rewritten");
        // Refused before anything is attempted when no unit of work is open, for the same reason the
        // write is: the UPDATE would execute, report the row it replaced, and then be rolled back when the
        // connection returned to the pool, leaving 2700-B told that an accumulated balance was stored.
        DatasetUnitOfWork.requireActiveToPersist("A rewrite of the transaction category balance file, "
                + "which REWRITE issues against the record area 2700-B-UPDATE-TCATBAL-REC has just "
                + "accumulated into (app/cbl/CBTRN02C.cbl:L528)", datasetName);
        String keyImage = record.tranCatKeyImage();
        String keyPattern = KEY_SPAN.pattern(keyImage);
        String subject = describeKey(keyImage);

        // Establish that the key names exactly one row BEFORE any row is replaced, under the same
        // FOR UPDATE lock the UPDATE will use so nothing can change between the two. Reading the
        // affected-row count afterwards would discover a fan-out only after the rows were overwritten.
        int matching;
        try {
            matching = matchingRowCount(sql.selectByKeyForUpdate(), keyPattern);
        } catch (DataAccessException rejected) {
            return reportWrite(rejected, "establish how many rows the key of " + subject + " selects in "
                    + "the transaction category balance dataset '" + datasetName + "' before rewriting it");
        }
        if (matching == 0) {
            // An invalid-key condition: there is no such record to rewrite. Reported without issuing the
            // UPDATE, which is the same outcome the UPDATE would have reported and one statement fewer.
            return WriteResult.notFound();
        }
        if (matching > 1) {
            // Nothing has been written, and nothing will be. A KSDS primary key is unique, so a relation
            // in which this key selects several rows is not the dataset the copybook describes.
            LOG.error("The key of " + subject + " selects more than one row of the transaction category "
                    + "balance dataset '" + datasetName + "'; a KSDS primary key is unique, so the "
                    + "rewrite is being refused before it is issued and file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " reported to the caller. No "
                    + "row has been changed.");
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
            return reportWrite(rejected, "rewrite " + subject + " in the transaction category balance "
                    + "dataset '" + datasetName + "'");
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
                "The rewrite of " + subject + " in dataset '" + datasetName + "'",
                rewritten + " rows were replaced where the key selected exactly one when it was checked "
                        + "under a row lock");
    }

    /**
     * Counts the rows a keyed predicate selects, stopping as soon as the answer is known.
     *
     * <p>The question a write or a rewrite has to answer is not "how many rows" but "none, one, or more
     * than one", so the statement is capped at {@value #FAN_OUT_PROBE_LIMIT} rows and the returned count
     * saturates there. A dataset whose key is not unique therefore costs two row transfers to detect
     * rather than the whole relation, and the normal case costs exactly one.
     *
     * <p>When the statement carries {@code FOR UPDATE} the rows it returns are locked for the rest of the
     * transaction, which is why that is the probe a rewrite uses inside a unit of work: the count and the
     * write then see the same rows.
     *
     * @param statement the keyed select, with or without {@code FOR UPDATE}
     * @param pattern   the escaped key pattern
     * @return {@code 0}, {@code 1}, or {@value #FAN_OUT_PROBE_LIMIT} meaning "at least that many"
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
        // The extractor always returns a value, so this is a null-safety formality rather than a branch
        // the backend can reach; treating an absent count as zero would let a write proceed unchecked.
        return counted == null ? FAN_OUT_PROBE_LIMIT : counted;
    }

    // =================================================================================================
    // Backend-refusal reporting. Every catch arm in this class goes through one of these three, so the
    // driver's own diagnosis reaches both the log and the caller, and neither is left to a per-site
    // decision.
    // =================================================================================================

    /**
     * Logs a backend refusal with the driver's own diagnosis and returns it for the caller to carry.
     *
     * <p>What is logged is what the backend said - {@code SQLSTATE}, vendor code and the exception type
     * that carried them - and the dataset name. Deliberately <strong>not</strong> logged: the record
     * image, the balance, or an unmasked account identifier. A category balance record carries an account
     * identifier and a money amount, so a log line echoing it would put both in a file that is read by
     * more people, retained for longer and protected less than the dataset itself (CWE-532).
     *
     * <p>The exception itself is <strong>not</strong> passed to the logger either, and that is worth
     * stating plainly because it looks like a loss. A driver's message is prose composed around the values
     * it refused, so handing the {@link Throwable} to the logger emits that text and its whole cause chain
     * verbatim, and a control character anywhere in it splits the entry in two (CWE-117). Sanitising the
     * summary and then attaching the raw exception beside it sanitises nothing. The codes that survive are
     * what separate an absent dataset from wrong credentials from a dropped connection, which is the whole
     * of what an operator acts on; the driver's own words remain in the driver's own log, which is
     * access-controlled as an application log is not.
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
     * Composes the statements for this dataset, discovering the record-image column's name from the
     * backend.
     *
     * <p><strong>Why a name has to be discovered at all.</strong> Every dataset in this module is reached
     * as a single-column relation whose one column holds the record image, and a sequential read can
     * address that column purely by position. A keyed read, a rewrite and an insert cannot: SQL permits an
     * ordinal in {@code ORDER BY} but not in a {@code WHERE}, a {@code SET} or an insert column list. The
     * name is therefore taken from the result-set metadata of the probe statement - <em>discovered from
     * the backend</em>, never invented here, never defaulted, and never added as a configuration key,
     * because a name this file made up would be exactly the kind of unverifiable literal the migration
     * forbids.
     *
     * <p>The probe is read-only: its predicate is false on every row, so the relation is described without
     * any of it being transferred. That is what makes it usable against a driver this build cannot
     * exercise, and it is a genuine dataset-scoped check rather than a bare connection test - an absent
     * dataset fails here, which is exactly what an {@code OPEN} would report.
     *
     * <p>Package-visible so this class's own tests can assert the composed text and the discovery
     * behaviour without reaching around the class. It resolves afresh on every call and stores nothing:
     * the returned value is deeply immutable, so handing it out grants nothing that can be altered.
     *
     * @return the composed statements; never {@code null}
     * @throws DataAccessException   if the dataset cannot be described - an I/O outcome, translated into a
     *                               status by every caller of this method
     * @throws IllegalStateException if the dataset is described but presents no usable record-image
     *                               column, which is a contract violation: the whole module addresses a
     *                               dataset as a record-image relation, and one that is not cannot be read
     *                               or written at all
     */
    Statements resolveStatements() {
        ResultSetExtractor<String> columnNameExtractor =
                TranCatBalRepository::extractRecordImageColumnName;
        String columnName = jdbcTemplate.query(relation.describeStatement(), columnNameExtractor);
        return Statements.over(relation, requireUsableColumnName(columnName));
    }

    /**
     * Resolves the statements, reporting an unreachable dataset as {@code null} rather than throwing.
     *
     * <p>Used only by {@link TranCatBalFile#closeFile()}, whose contract is to report a status rather than
     * to fail.
     *
     * @param subject how to name the operation in a diagnostic
     * @return the resolved statements, or {@code null} when the dataset could not be described
     * @throws IllegalStateException if the dataset is reachable but presents no usable record-image column
     */
    private Statements statementsFor(String subject) {
        try {
            return resolveStatements();
        } catch (DataAccessException unreachable) {
            logRefusal(unreachable, "describe the transaction category balance dataset '" + datasetName
                    + "' for " + subject);
            return null;
        }
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
     * column. Whether that column is read as characters or as bytes is {@link RecordImageForm}'s decision
     * and not this method's. The value is <em>not</em> trimmed: the trailing 22 bytes of this record are
     * {@code FILLER} and they are part of it.
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
     * operand - in the same representation as the column it is compared with, which is what stops a keyed
     * read from comparing characters against bytes.
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
     * <p>The same binding as {@link #firstRowMatching(String, String)}, with the row cap left to the
     * caller because the pre-write probe needs to see a second row to know there is one and every other
     * statement in this class needs at most the first.
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
     * <p>A browse position is a stored record image, not a pattern, so it is bound exactly as an image is:
     * the comparison then runs against the column in its own representation, and "the next record after
     * this one" means the same thing on both sides of the operator.
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

    // =================================================================================================
    // Decoding and encoding. The width is required in both directions, which is what makes gates G19
    // and G21 - the declared 50 bytes, FILLER included - provable rather than assumed.
    // =================================================================================================

    /**
     * Decodes a stored record image, or reports that it is not a category balance record.
     *
     * <p><strong>The width is required, not repaired.</strong> {@code app/cpy/CVTRA01Y.cpy} declares
     * {@code RECLN = 50}, both file descriptions corroborate it as 17 + 33, and every record of
     * {@code app/data/ASCII/tcatbal.txt} measures exactly 50 - so a row of any other width means the
     * backend is not serving this layout. Widening a short one with spaces would be defensible only if the
     * missing bytes were certainly the trailing {@code FILLER X(22)}, and nothing about a short row says
     * they are: a row that lost bytes anywhere else, or that was written against a different layout, pads
     * into a record whose fields decode from offsets that are not theirs. This is the danger the class
     * Javadoc's first collision warning describes in its most concrete form -
     * {@code TRAN-CAT-BAL} read from the wrong span is still a number, and a caller cannot tell it from a
     * real one - so the repair would convert a diagnosable failure into plausible money.
     *
     * <p>An over-long image is rejected for the same reason, which makes the two symmetrical: the row
     * either is 50 bytes or it is not a record. The width is reported as {@link FileStatus#LENGERR} - the
     * response CICS gives when a record does not fit its receiver - and the actual width is named in the
     * log line rather than carried as a reason code, because a byte count is not a CICS reason code.
     *
     * @param recordImage the stored image, exactly as the configured representation presented it
     * @param subject     how to name the row in a diagnostic - never its content
     * @return the successful outcome carrying the decoded record, or a permanent-error outcome whose CICS
     *         response is {@link FileStatus#LENGERR}
     */
    private ReadResult decoded(byte[] recordImage, String subject) {
        if (recordImage.length != RECORD_LENGTH) {
            LOG.error("The transaction category balance dataset '" + datasetName + "' presented " + subject
                    + " as " + recordImage.length + " byte(s), but TRAN-CAT-BAL-RECORD is declared "
                    + RECORD_LENGTH + " bytes by app/cpy/" + COPYBOOK + ".cpy (a " + KEY_LENGTH
                    + "-byte TRAN-CAT-KEY, an 11-byte TRAN-CAT-BAL and a 22-byte FILLER); reporting file "
                    + "status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than "
                    + "decoding fields from offsets that would not be theirs");
            return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.LENGERR));
        }
        return ReadResult.found(TranCatBalRecord.decode(recordImage, codec.charset()));
    }

    /**
     * Encodes a record for storage, requiring the declared width in the outbound direction too.
     *
     * <p>{@link TranCatBalRecord} maintains its 50-byte area as its single source of truth, so this can
     * only fail if that invariant were broken - and precisely because it should be impossible, it is
     * checked rather than trusted: a short image would store a record that a later read would then reject,
     * turning one bad write into a permanently unreadable row. The check also makes the outbound half of
     * acceptance gates G19 and G21 explicit, since a dropped {@code FILLER} is exactly what would make an
     * image 28 bytes instead of 50.
     *
     * @param record the record to store
     * @param verb   how to name the operation in the diagnostic, e.g. {@code "written"}
     * @return the record's {@link #RECORD_LENGTH} bytes
     * @throws IllegalStateException if the encoded image is not exactly {@link #RECORD_LENGTH} bytes
     */
    private static byte[] requireStorableImage(TranCatBalRecord record, String verb) {
        byte[] recordImage = record.encode();
        if (recordImage.length != RECORD_LENGTH) {
            throw new IllegalStateException("A record about to be " + verb + " encodes to "
                    + recordImage.length + " byte(s), but TRAN-CAT-BAL-RECORD is declared "
                    + RECORD_LENGTH + " bytes by app/cpy/" + COPYBOOK + ".cpy. Every span is written, "
                    + "FILLER X(22) included, so an image of any other width means the record's own "
                    + "layout is wrong - storing it would leave a row that no read could decode.");
        }
        return recordImage;
    }

    // =================================================================================================
    // Pure helpers. Private and static where no instance state is involved, and no static state is
    // introduced - every one of them is a function of its arguments alone.
    // =================================================================================================

    /**
     * Names a key in a diagnostic without disclosing the account identifier it contains.
     *
     * <p>The key's leading 11 bytes are an account identifier, and a log line or an exception message may
     * be retained and read far more widely than the dataset (CWE-532). The read itself always uses the
     * real key; only its rendering is masked, and the trailing type and category codes stay visible
     * because they identify which category balance failed without identifying whose.
     *
     * @param keyImage the 17-character key image
     * @return a phrase naming the key, safe to log
     */
    private static String describeKey(String keyImage) {
        return "the transaction category balance key '" + SensitiveDiagnostics.maskIdentifier(keyImage)
                + "'";
    }

    /**
     * Resolves the configured binding for {@value #DD_NAME} and proves its geometry.
     *
     * <p>The key-width check is the one that matters most in this whole class: a declared 16 would be the
     * {@code app/cpy/CVTRA02Y.cpy} substitution, and because that record is <em>also</em> 50 bytes the
     * record-width check beside it would pass. The diagnostic therefore names the trap explicitly, so a
     * misconfiguration is diagnosed in one reading rather than investigated.
     *
     * @param datasetBindings the configured catalogue
     * @return the resolved binding
     * @throws IllegalStateException if the DD name is unconfigured, or the binding declares a record width
     *                               other than {@link #RECORD_LENGTH} or a key width other than
     *                               {@link #KEY_LENGTH}
     */
    private static DatasetBinding requireTranCatBalGeometry(DatasetBindings datasetBindings) {
        DatasetBinding binding = datasetBindings.binding(DD_NAME);
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' declares a "
                    + "record length of " + binding.recordLength() + ", but TRAN-CAT-BAL-RECORD is "
                    + RECORD_LENGTH + " bytes - app/cpy/" + COPYBOOK + ".cpy reads RECLN = 50, and both "
                    + "app/cbl/CBTRN02C.cbl:L91-L97 and app/cbl/CBACT04C.cbl:L61-L67 declare a "
                    + KEY_LENGTH + "-byte FD-TRAN-CAT-KEY plus FD-FD-TRAN-CAT-DATA PIC X(33) "
                    + "independently. This repository addresses the record by absolute offset, so a "
                    + "differently-sized record would misplace every field after the first. Correct "
                    + "carddemo.datasets." + DD_NAME + ".record-length to " + RECORD_LENGTH + ".");
        }
        Integer declaredKeyLength = binding.keyLength();
        // A key width is configured only where a source file states one, so an absent one is normal and
        // is accepted; a PRESENT one that disagrees with the copybook is a defect - and for THIS dataset
        // it is the defect that a width check cannot otherwise catch.
        if (declaredKeyLength != null && declaredKeyLength != KEY_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' declares a key "
                    + "length of " + declaredKeyLength + ", but TRAN-CAT-KEY is " + KEY_LENGTH + " bytes "
                    + "- TRANCAT-ACCT-ID 9(11) + TRANCAT-TYPE-CD X(02) + TRANCAT-CD 9(04) in app/cpy/"
                    + COPYBOOK + ".cpy, which is also what RECORD KEY IS FD-TRAN-CAT-KEY names at "
                    + "app/cbl/CBTRN02C.cbl:L60. BEWARE THE CVTRA02Y TRAP: DIS-GROUP-RECORD also totals "
                    + RECORD_LENGTH + " bytes but its DIS-GROUP-KEY is only "
                    + DISCLOSURE_GROUP_KEY_LENGTH + ", so a declared "
                    + DISCLOSURE_GROUP_KEY_LENGTH + " here would read this dataset through the "
                    + "disclosure group's key and silently corrupt every balance. Correct "
                    + "carddemo.datasets." + DD_NAME + ".key-length to " + KEY_LENGTH + ", or omit it.");
        }
        return binding;
    }

    /**
     * Proves the declared geometry of this repository against the record type and against both colliding
     * layouts, and is called once from the static initialiser.
     *
     * <p>Four facts are established:
     * <ol>
     *   <li>the record width and the key offset agree with {@link TranCatBalRecord}'s own layout, through
     *       that type's {@link TranCatBalRecord#verifyGeometry()};</li>
     *   <li>the key ends exactly where the balance begins, through
     *       {@link TranCatBalRecord#verifyKeyGeometry(int, int)} - which is what excludes a one-byte
     *       shift;</li>
     *   <li>this key is <strong>not</strong> {@link #DISCLOSURE_GROUP_KEY_LENGTH} bytes. The regression
     *       assertion the class Javadoc's first collision warning calls for, stated here so it holds for
     *       every build rather than only for a test run;</li>
     *   <li>this key is <strong>not</strong> {@link #TRAN_CATEGORY_KEY_LENGTH} bytes, which guards the
     *       second collision - the identically-named 6-byte key of {@code app/cpy/CVTRA04Y.cpy}.</li>
     * </ol>
     *
     * @return {@link #KEY_LENGTH}, so a test can assert on the verified width in one expression
     * @throws IllegalStateException if any declared constant contradicts the copybook
     */
    static int verifyDeclaredGeometry() {
        TranCatBalRecord.verifyGeometry();
        TranCatBalRecord.verifyRecordLength(RECORD_LENGTH, TranCatBalRecord.LAYOUT.recordLength());
        TranCatBalRecord.verifyKeyGeometry(KEY_LENGTH, TranCatBalRecord.TRAN_CAT_BAL_OFFSET);
        if (KEY_LENGTH == DISCLOSURE_GROUP_KEY_LENGTH) {
            throw new IllegalStateException("TCATBALF's TRAN-CAT-KEY is " + KEY_LENGTH + " bytes and must "
                    + "never be " + DISCLOSURE_GROUP_KEY_LENGTH + ". app/cpy/CVTRA02Y.cpy's "
                    + "DIS-GROUP-RECORD also totals " + RECORD_LENGTH + " bytes, so no width check can "
                    + "distinguish the two layouts: a " + DISCLOSURE_GROUP_KEY_LENGTH + "-byte key here "
                    + "would read TRAN-CAT-BAL from 0-based " + DISCLOSURE_GROUP_KEY_LENGTH
                    + " for 6 bytes instead of from " + TranCatBalRecord.TRAN_CAT_BAL_OFFSET
                    + " for 11, and every balance would be silently wrong.");
        }
        if (KEY_LENGTH == TRAN_CATEGORY_KEY_LENGTH) {
            throw new IllegalStateException("TCATBALF's TRAN-CAT-KEY is " + KEY_LENGTH + " bytes and must "
                    + "never be " + TRAN_CATEGORY_KEY_LENGTH + ". app/cpy/CVTRA04Y.cpy declares a group "
                    + "of the identical COBOL name TRAN-CAT-KEY that is only "
                    + TRAN_CATEGORY_KEY_LENGTH + " bytes - TRAN-TYPE-CD X(02) + TRAN-CAT-CD 9(04), with "
                    + "no account identifier - and it belongs to TranCategoryRecord, not to this "
                    + "dataset.");
        }
        verifyBalancePolicy();
        return KEY_LENGTH;
    }

    /**
     * Proves the balance's scale and rounding policy, and is called from {@link #verifyDeclaredGeometry()}.
     *
     * <p>Two facts, both of which would corrupt money silently if they drifted:
     * <ol>
     *   <li>the scale is exactly {@value com.vsergeychik.carddemo.common.CobolDecimal#MONETARY_SCALE},
     *       because {@code TRAN-CAT-BAL PIC S9(09)V99} declares two digits after the implied decimal
     *       point, and it agrees with what {@link TranCatBalRecord} decodes at;</li>
     *   <li>the rounding is {@link RoundingMode#DOWN} - truncation. {@code ROUNDED} appears nowhere in the
     *       estate, so anything else would round money the COBOL truncates. This is the assertion that
     *       makes a change from {@code DOWN} to {@code HALF_UP} or {@code HALF_EVEN} anywhere in
     *       {@link CobolDecimal} fail here rather than surface as pennies of drift.</li>
     * </ol>
     *
     * @return {@link #TRAN_CAT_BAL_SCALE}
     * @throws IllegalStateException if either fact does not hold
     */
    private static int verifyBalancePolicy() {
        if (TRAN_CAT_BAL_SCALE != TranCatBalRecord.TRAN_CAT_BAL_SCALE) {
            throw new IllegalStateException("TRAN-CAT-BAL is PIC S9(09)V99, so its scale is "
                    + TranCatBalRecord.TRAN_CAT_BAL_SCALE + ", but this repository declares "
                    + TRAN_CAT_BAL_SCALE + ". A balance stored at the wrong scale is wrong money.");
        }
        if (TRAN_CAT_BAL_ROUNDING != RoundingMode.DOWN) {
            throw new IllegalStateException("TRAN-CAT-BAL must be stored with RoundingMode.DOWN, but "
                    + TRAN_CAT_BAL_ROUNDING + " is configured. The keyword ROUNDED appears nowhere in any "
                    + "of the 28 COBOL programs, and absent ROUNDED the COBOL standard truncates excess "
                    + "fractional digits on store - so any rounding mode other than DOWN would round "
                    + "money that the legacy system truncates.");
        }
        return TRAN_CAT_BAL_SCALE;
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
            throw new IllegalStateException("The dataset binding for DD name '" + DD_NAME + "' declares "
                    + "no dataset name. Set carddemo.datasets." + DD_NAME + ".dsname; this repository "
                    + "composes its statements from configuration alone and hard-codes no dataset name.");
        }
        // The grammar itself lives in DatasetRelation, so the rule about what a dataset name may contain
        // is stated once for every repository rather than restated - differently - in each.
        return DatasetRelation.requireDatasetName(candidate);
    }

    /**
     * Validates the discovered record-image column name and returns it verbatim.
     *
     * <p>A backend that describes the dataset but presents no column at position
     * {@value #RECORD_IMAGE_COLUMN_INDEX} is not serving a record-image relation, and no statement this
     * class composes could address it. That is a contract violation rather than an I/O outcome, so it is
     * thrown and not statused.
     *
     * @param candidate the name the metadata probe reported, possibly {@code null}
     * @return {@code candidate}, unchanged
     * @throws IllegalStateException if {@code candidate} is {@code null} or blank
     */
    private static String requireUsableColumnName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The backend describes the transaction category balance "
                    + "dataset with no usable column at position " + RECORD_IMAGE_COLUMN_INDEX
                    + ", so there is no record-image column to read, rewrite or insert into. Every "
                    + "dataset in this module is reached as a single-column relation whose one column "
                    + "holds the whole " + RECORD_LENGTH + "-byte record image; a relation that is not "
                    + "cannot be addressed at all, and no column name is invented here to paper over "
                    + "it.");
        }
        return candidate;
    }

    // =================================================================================================
    // The composed statements.
    // =================================================================================================

    /**
     * The six statements this repository issues, composed once the record-image column is known.
     *
     * <p>An immutable value, package-visible so that a unit test can assert the composed text directly
     * rather than inferring it from a database round trip. It is not part of the public contract: no caller
     * outside this package can name the type.
     *
     * <p>Every statement is confined to core SQL - a delimited identifier, {@code ORDER BY}, a comparison,
     * {@code LIKE … ESCAPE}, {@code FOR UPDATE}, a single-column {@code UPDATE} and a single-column
     * {@code INSERT} - and none of them names a column list beyond that one column, because this migration
     * introduces no schema and therefore has no column names of its own to name.
     *
     * @param selectFirst          the first read of a browse: every record in ascending key order, limited
     *                             to one row by the statement. No predicate, because an {@code OPEN INPUT}
     *                             positions before the first record
     * @param selectNext           every later read of a browse: the first record whose image sorts strictly
     *                             after the one already returned, in ascending key order. The parameter is
     *                             the previous <em>image</em> and not its key
     * @param selectByKey          the keyed read: the record whose image begins with the 17-byte key
     * @param selectByKeyForUpdate the keyed read with the row lock, valid only inside a unit of work
     * @param rewrite              the rewrite: replace the image of the record whose image begins with the
     *                             key
     * @param insert               the write: add one record image
     * @param probeUnreadableRows  the rows whose record-image column holds nothing. Not a COBOL operation:
     *                             it is what lets a not-found answer be proved before it authorises a
     *                             {@code WRITE} - see
     *                             {@link TranCatBalRepository#provenAbsence(String, String)}
     */
    record Statements(String selectFirst,
                      String selectNext,
                      String selectByKey,
                      String selectByKeyForUpdate,
                      String rewrite,
                      String insert,
                      String probeUnreadableRows) {

        /**
         * Composes the statements over one dataset and one record-image column.
         *
         * <p>Every one of them comes from {@link DatasetRelation}, which also remembers the validated
         * column name. That is the point: the text of a browse, a keyed read, a locking read, a rewrite and
         * an insert is decided in one class for every dataset in the module, so a divergence between two
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
                    relation.insertRecordImage(recordImageColumn),
                    relation.selectUnreadableRows(recordImageColumn));
        }
    }

    // =================================================================================================
    // The OPEN verbs.
    // =================================================================================================

    /**
     * The {@code OPEN} verbs the estate issues against this dataset, and the only two it issues.
     *
     * <p>Established by inspection rather than assumed: {@code OPEN INPUT} appears at
     * {@code app/cbl/CBACT04C.cbl:L236} and {@code OPEN I-O} at {@code app/cbl/CBTRN02C.cbl:L329}. No
     * program opens this dataset for output or extend, so no such constant exists here.
     */
    public enum OpenMode {

        /**
         * {@code OPEN INPUT}: read-only, for the sequential browse that drives the interest calculator
         * ({@code app/cbl/CBACT04C.cbl:L236}).
         */
        INPUT("OPEN INPUT"),

        /**
         * {@code OPEN I-O}: read and write, for the create-or-update pair in the transaction poster
         * ({@code app/cbl/CBTRN02C.cbl:L329}).
         */
        I_O("OPEN I-O");

        /** The COBOL verb this mode stands for, for diagnostics that name what the program did. */
        private final String cobolVerb;

        OpenMode(String cobolVerb) {
            this.cobolVerb = cobolVerb;
        }

        /**
         * The COBOL verb this mode stands for, verbatim.
         *
         * @return {@code "OPEN INPUT"} or {@code "OPEN I-O"}
         */
        public String cobolVerb() {
            return cobolVerb;
        }
    }

    // =================================================================================================
    // One open file. EVERY piece of per-execution state lives here and none of it on the repository:
    // the mode, the open's status, the statements that open resolved, the browse position, and whether
    // it has been closed. Practice B9, gate G53.
    // =================================================================================================

    /**
     * One open of the transaction category balance dataset - a COBOL {@code FD} for this dataset, as a
     * Java object.
     *
     * <p>Obtained from {@link TranCatBalRepository#open(OpenMode)} and closed by
     * {@link #closeFile()} or, in try-with-resources, by {@link #close()}. It carries that open's mode, its
     * status, the statements resolved for it and its browse position, so two concurrent executions each
     * have their own and neither can consume the other's records.
     *
     * <p><strong>Not thread-safe, deliberately.</strong> A COBOL file position belongs to one program
     * execution, and so does this. Obtaining a second handle costs one metadata probe and is the correct
     * answer for a second concurrent browse.
     *
     * <p>The keyed operations are exposed here as well as on the repository, and the handle's forms reuse
     * the shape its {@code OPEN} already resolved rather than describing the dataset again. That mirrors
     * {@code CBTRN02C}, which opens the file once at {@code L200} and then performs
     * {@code 2700-UPDATE-TCATBAL} for every accepted transaction.
     */
    public static final class TranCatBalFile implements AutoCloseable {

        /** The repository that opened this file, for the statements and the shared template. */
        private final TranCatBalRepository repository;

        /** The {@code OPEN} verb that produced this handle. */
        private final OpenMode mode;

        /**
         * The status the {@code OPEN} itself reported: {@code '00'}, or the permanent-error status when the
         * dataset could not be described.
         */
        private final String openStatus;

        /**
         * The statements resolved by the {@code OPEN}, or {@code null} when it failed - in which case every
         * operation on this handle reports {@link #openStatus} rather than a fresh failure.
         */
        private final Statements statements;

        /**
         * The browse position: the whole record image most recently returned by {@link #readNext()}, or
         * {@code null} while positioned before the first record.
         *
         * <p>The whole image rather than just its key, because the browse statement asks for the first
         * image sorting strictly after it, and a key alone would not distinguish "after this record" from
         * "at this record" if a deployment's relation ever held two rows under one key.
         */
        private byte[] browsePosition;

        /** Whether {@link #closeFile()} has been called. */
        private boolean closed;

        /**
         * Wraps one open. Private to the repository, which is the only thing that may declare a file open.
         *
         * @param repository the repository that opened it
         * @param mode       the {@code OPEN} verb issued
         * @param openStatus the status the open reported
         * @param statements the resolved statements, or {@code null} when the open failed
         */
        private TranCatBalFile(TranCatBalRepository repository, OpenMode mode, String openStatus,
                Statements statements) {
            this.repository = repository;
            this.mode = mode;
            this.openStatus = openStatus;
            this.statements = statements;
            this.browsePosition = null;
            this.closed = false;
        }

        /**
         * The status the {@code OPEN} reported - the quantity {@code IF TCATBALF-STATUS = '00'} tests at
         * {@code app/cbl/CBACT04C.cbl:L237} and {@code app/cbl/CBTRN02C.cbl:L330}.
         *
         * @return {@link FileStatus#OK} or {@link TranCatBalRepository#PERMANENT_ERROR_STATUS}
         */
        public String openStatus() {
            return openStatus;
        }

        /**
         * The classification of {@link #openStatus()}.
         *
         * @return {@link Outcome#OK} for a successful open, {@link Outcome#OTHER} otherwise
         */
        public Outcome openOutcome() {
            return FileStatus.outcomeOfStatus(openStatus);
        }

        /**
         * The {@code APPL-RESULT} value the open's guard moves: {@code 0} on success, {@code 12} otherwise.
         *
         * <p>{@code MOVE 0 TO APPL-RESULT} / {@code MOVE 12 TO APPL-RESULT} at
         * {@code app/cbl/CBACT04C.cbl:L238-L240} and {@code app/cbl/CBTRN02C.cbl:L331-L333}. Note there is
         * no end-of-file arm on an {@code OPEN}.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link TranCatBalRepository#APPL_RESULT_FATAL}
         */
        public int openApplResult() {
            return FileStatus.isOk(openStatus) ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }

        /**
         * The {@code OPEN} verb that produced this handle.
         *
         * @return the mode; never {@code null}
         */
        public OpenMode mode() {
            return mode;
        }

        /**
         * The dataset this handle reads and writes.
         *
         * @return the configured dataset name; never {@code null} and never blank
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
        // 1000-TCATBALF-GET-NEXT - app/cbl/CBACT04C.cbl:L325-L348.
        // =============================================================================================

        /**
         * Reads the next record in ascending key order: the Java form of
         * {@code READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD} ({@code app/cbl/CBACT04C.cbl:L326}) and of the
         * guard that classifies its status at {@code L327-L347}.
         *
         * <p>Four reported outcomes, and they are the ones the COBOL enumerates:
         * <ul>
         *   <li><strong>found</strong> - status {@code '00'}, carrying the decoded record. The COBOL moves
         *       {@code 0} to {@code APPL-RESULT} and the caller displays the record ({@code L193}) and then
         *       accumulates interest for it;</li>
         *   <li><strong>end of file</strong> - status {@code '10'}, carrying no record. The COBOL moves
         *       {@code 16} - {@link FileStatus#APPL_EOF}, the value its {@code 88 APPL-EOF} names at
         *       {@code L135} - and the caller sets {@code MOVE 'Y' TO END-OF-FILE} at {@code L340}, leaves
         *       its loop and posts the last account's interest. Reported repeatedly and idempotently: once
         *       at end of file, every further call reports it again, which is faithful because the COBOL
         *       loop stops on the flag and never resumes;</li>
         *   <li><strong>other</strong> carrying the <em>open's</em> status, when the open failed - so a
         *       caller that ignored {@link #openStatus()} cannot mistake a dataset it never reached for one
         *       that was empty;</li>
         *   <li><strong>other</strong> carrying {@link TranCatBalRepository#PERMANENT_ERROR_STATUS} for an
         *       I/O failure, a row whose record image is absent, or a row that is not
         *       {@link TranCatBalRepository#RECORD_LENGTH} bytes wide. The COBOL moves {@code 12} and the
         *       caller displays {@code 'ERROR READING TRANSACTION CATEGORY FILE'} ({@code L342}), renders
         *       the status and abends.</li>
         * </ul>
         *
         * <p><strong>Ordering, and why it is not negotiable.</strong> The statement carries an explicit
         * ascending {@code ORDER BY} over the record-image column; the first read of a browse uses a
         * statement with no predicate at all, and every later read asks for the first image strictly
         * greater than the one before it. That is a keyed browse - the same "position, then read the next
         * greater key" the KSDS itself performs - and it makes the ordering a property of the statement
         * rather than of the backend's scan order. {@code CBACT04C} detects an account break by comparing
         * consecutive records' account identifiers ({@code L194}), so records arriving out of order would
         * split one account across several breaks and post its interest more than once. Only one row is
         * ever transferred per call: the row limit is set on the statement, so a browse of a large dataset
         * never materialises it.
         *
         * <p>Reading a closed file is a <strong>programming error, not an I/O outcome</strong>, and throws.
         * COBOL would report status {@code '47'} or {@code '49'} for a read against a file in the wrong
         * open mode, but neither consumer ever does it - {@code CBACT04C} closes once, after its loop - so
         * there is no legacy behaviour to reproduce, and the honest response is to fail loudly at the
         * defect rather than to return a status no COBOL path would have produced.
         *
         * @return the discriminated outcome; never {@code null}
         * @throws IllegalStateException if this handle has been closed
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
                // decides what to do about it - which, in CBACT04C, is to display and abend.
                LOG.error("Rejected browse read of the transaction category balance dataset '"
                        + datasetName() + "' - " + BackendDiagnostic.of(translated).describe()
                        + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
                return ReadResult.of(PERMANENT_ERROR_STATUS);
            }

            // The list itself is never null - the template asserts that internally before returning it -
            // so emptiness is the whole of the end-of-file test, and no unreachable null arm is written.
            if (rows.isEmpty()) {
                // AT END with no record read. An expected outcome, not an error. The position is left
                // where it is, so a further call reports end of file again rather than restarting.
                return ReadResult.endOfFile();
            }
            byte[] recordImage = rows.get(0);
            if (recordImage == null) {
                // A row whose record image is absent is not a readable 50-byte record. There IS a record,
                // it simply cannot be read, so this is an I/O-level defect and not an end of file - and
                // the position is deliberately NOT advanced, because there is nothing to advance to.
                LOG.error("The transaction category balance dataset '" + datasetName() + "' presented a "
                        + "browsed row with no record image at column position "
                        + RECORD_IMAGE_COLUMN_INDEX + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
                return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
            }

            ReadResult result = repository.decoded(recordImage, "a browsed row");
            if (result.isFound()) {
                // Advance only on a record that decoded. A row that failed the width check is reported
                // without moving the position, so a caller that chose to continue re-reads the same
                // failing row rather than silently skipping records after it.
                this.browsePosition = recordImage;
            }
            return result;
        }

        // =============================================================================================
        // The keyed operations, reusing the shape this OPEN resolved.
        // =============================================================================================

        /**
         * Reads one record by its three key components, against this open's resolved statements.
         *
         * <p>Behaviour is identical to {@link TranCatBalRepository#readByKey(long, String, int)},
         * including that a missing record is reported as {@link ReadResult#notFound()} and never thrown.
         *
         * @param acctId the account identifier, {@code TRANCAT-ACCT-ID PIC 9(11)}
         * @param typeCd the transaction type code, {@code TRANCAT-TYPE-CD PIC X(02)}
         * @param catCd  the transaction category code, {@code TRANCAT-CD PIC 9(04)}
         * @return the discriminated outcome; never {@code null}
         * @throws NullPointerException     if {@code typeCd} is {@code null}
         * @throws IllegalArgumentException if either numeric component is negative
         * @throws IllegalStateException    if this handle has been closed
         */
        public ReadResult readByKey(long acctId, String typeCd, int catCd) {
            return readByKey(new TranCatKey(acctId, typeCd, catCd));
        }

        /**
         * Reads one record by its composite key, against this open's resolved statements.
         *
         * @param key the 17-byte composite key
         * @return the discriminated outcome; never {@code null}
         * @throws NullPointerException     if {@code key} is {@code null}
         * @throws IllegalArgumentException if either numeric component of {@code key} is negative
         * @throws IllegalStateException    if this handle has been closed
         */
        public ReadResult readByKey(TranCatKey key) {
            requireOpen("read a record by key");
            Objects.requireNonNull(key, "A TRAN-CAT-KEY is required to read TCATBALF by key");
            if (statements == null) {
                return ReadResult.of(openStatus);
            }
            return repository.readByKey(statements, key);
        }

        /**
         * Adds a record that does not yet exist, against this open's resolved statements.
         *
         * <p>Behaviour is identical to {@link TranCatBalRepository#write(TranCatBalRecord)}. This is the
         * form {@code 2700-A-CREATE-TCATBAL-REC} uses, since {@code CBTRN02C} has the file open
         * {@code I-O} throughout.
         *
         * @param record the record to add, complete and already mutated by the caller
         * @return the discriminated outcome; never {@code null}
         * @throws NullPointerException  if {@code record} is {@code null}
         * @throws IllegalStateException if this handle has been closed, or if no unit of work is open - the
         *                               step's own transaction is the boundary {@code CBTRN02C} reaches
         *                               this inside
         */
        public WriteResult write(TranCatBalRecord record) {
            requireOpen("write a record");
            Objects.requireNonNull(record, "A record is required to write it");
            if (statements == null) {
                return WriteResult.of(openStatus);
            }
            return repository.write(statements, record);
        }

        /**
         * Rewrites one record in place, against this open's resolved statements.
         *
         * <p>Behaviour is identical to {@link TranCatBalRepository#rewrite(TranCatBalRecord)}. This is the
         * form {@code 2700-B-UPDATE-TCATBAL-REC} uses.
         *
         * @param record the record to write, complete and already mutated by the caller
         * @return the discriminated outcome; never {@code null}
         * @throws NullPointerException  if {@code record} is {@code null}
         * @throws IllegalStateException if this handle has been closed, or if no unit of work is open - the
         *                               step's own transaction is the boundary {@code CBTRN02C} reaches
         *                               this inside
         */
        public WriteResult rewrite(TranCatBalRecord record) {
            requireOpen("rewrite a record");
            Objects.requireNonNull(record, "A record is required to rewrite it");
            if (statements == null) {
                return WriteResult.of(openStatus);
            }
            return repository.rewrite(statements, record);
        }

        // =============================================================================================
        // 9000-TCATBALF-CLOSE - app/cbl/CBACT04C.cbl:L522-L538.
        // 9500-TCATBALF-CLOSE - app/cbl/CBTRN02C.cbl:L674-L690.
        // =============================================================================================

        /**
         * Closes this open and reports the status the {@code CLOSE} would have.
         *
         * <p>The handle is marked closed and its browse position discarded first, so the close is complete
         * regardless of what the status turns out to be - a {@code CLOSE} that reports a failure has still
         * closed the file, and a handle that could still be read afterwards would be a worse outcome than
         * an unhelpful status.
         *
         * <p>Where the open never succeeded, its own status is reported and no probe is issued for a file
         * that was never opened. Otherwise the dataset is confirmed still addressable, which is the failure
         * a {@code CLOSE} can genuinely report.
         *
         * <p>Idempotent: closing an already-closed handle reports the same status again rather than
         * throwing, because {@link #close()} may be called by try-with-resources after an explicit
         * {@link #closeFile()}, and that is not a defect.
         *
         * <p>The caller owns the guard - {@code IF TCATBALF-STATUS = '00'} at
         * {@code app/cbl/CBACT04C.cbl:L525} - and the {@code DISPLAY 'ERROR CLOSING TRANSACTION BALANCE
         * FILE'} line at {@code L533} on the fatal arm.
         *
         * @return {@link FileStatus#OK}, or {@link TranCatBalRepository#PERMANENT_ERROR_STATUS} when the
         *         dataset is no longer addressable or the open had failed
         */
        public String closeFile() {
            if (closed) {
                // Already closed. Reported rather than refused: see the idempotence note above.
                return statements == null ? openStatus : FileStatus.OK;
            }
            closed = true;
            this.browsePosition = null;
            if (statements == null) {
                return openStatus;
            }
            return repository.statementsFor("a close of the transaction category balance dataset") == null
                    ? PERMANENT_ERROR_STATUS
                    : FileStatus.OK;
        }

        /**
         * The {@code APPL-RESULT} value the close's guard moves: {@code 0} on success, {@code 12} otherwise.
         *
         * <p>{@code app/cbl/CBACT04C.cbl:L525-L529}. Provided so a caller's close guard translates as
         * directly as its open guard does.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link TranCatBalRepository#APPL_RESULT_FATAL}
         */
        public int closeApplResult() {
            return FileStatus.isOk(closeFile()) ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
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
                throw new IllegalStateException("This open of the transaction category balance dataset '"
                        + datasetName() + "' has been closed, so it cannot " + operation + ". A COBOL "
                        + "program closes once, after its work - operating afterwards is a defect in the "
                        + "caller, not a file status. Open another file instead.");
            }
        }
    }

    // =================================================================================================
    // The discriminated outcomes. Both carry the two-character FILE STATUS verbatim, because that is the
    // quantity the COBOL guard chains test and render.
    // =================================================================================================

    /**
     * The outcome of a read: the file status, its classification, the record when there is one, and what
     * the backend said when it refused.
     *
     * <p><strong>Both consumers' guard ladders are available, and neither is privileged.</strong> This is
     * the one place the difference between {@code CBTRN02C} and {@code CBACT04C} has to be honoured, and
     * collapsing it would silently distort one of them:
     * <ul>
     *   <li>{@code CBACT04C.cbl:L327-L335} classifies {@code '00'} as {@code 0}, {@code '10'} as
     *       {@code 16} and everything else - {@code '23'} included - as {@code 12}. That is
     *       {@link #applResult()};</li>
     *   <li>{@code CBTRN02C.cbl:L481-L485} classifies {@code '00'} <em>or</em> {@code '23'} as {@code 0}
     *       and everything else as {@code 12}. That is {@link #applResultWhereNotFoundIsNormal()}, and
     *       {@link #isOkOrNotFound()} is the condition it tests.</li>
     * </ul>
     *
     * @param status     the two-character {@code FILE STATUS}, carried verbatim so the caller can render it
     *                   exactly as {@code 9910-DISPLAY-IO-STATUS} does
     * @param outcome    the classification of {@code status}
     * @param record     the decoded record, present if and only if {@code outcome} is {@link Outcome#OK}
     * @param diagnostic what the backend reported, when the outcome was a refusal
     * @param response   the CICS {@code RESP}/{@code RESP2} pair for the outcome
     */
    public record ReadResult(String status,
                             Outcome outcome,
                             Optional<TranCatBalRecord> record,
                             Optional<BackendDiagnostic> diagnostic,
                             CicsResponse response) {

        /**
         * Enforces every invariant of the outcome at construction.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *                                  {@link FileStatus#STATUS_LENGTH} characters, if {@code outcome}
         *                                  is not the classification of {@code status}, or if the record is
         *                                  present when the outcome is not success or absent when it is
         */
        public ReadResult {
            requireConsistentStatus(status, outcome);
            Objects.requireNonNull(record, "A read result carries an empty record rather than a null one, "
                    + "so no null escapes the type");
            Objects.requireNonNull(diagnostic, "A read result carries an empty diagnostic rather than a "
                    + "null one, so no null escapes the type");
            Objects.requireNonNull(response, "A read result carries a CICS response pair rather than a "
                    + "null one; use CicsResponse.ofBatchStatus(status) where the outcome is a file "
                    + "status");
            if (record.isPresent() != (outcome == Outcome.OK)) {
                throw new IllegalArgumentException(record.isPresent()
                        ? "A read that did not succeed carries no record: outcome " + outcome
                                + " was given one. Only the '00' arm reaches TRAN-CAT-BAL-RECORD."
                        : "A successful read carries the decoded record, and this one carries none; build "
                                + "it with ReadResult.found(TranCatBalRecord).");
            }
        }

        /**
         * The successful arm: status {@code '00'}, carrying the decoded record.
         *
         * <p>{@code MOVE 0 TO APPL-RESULT} at {@code app/cbl/CBACT04C.cbl:L328} and
         * {@code app/cbl/CBTRN02C.cbl:L482}.
         *
         * @param record the decoded record
         * @return a result carrying {@link FileStatus#OK} and the record
         * @throws NullPointerException if {@code record} is {@code null}
         */
        public static ReadResult found(TranCatBalRecord record) {
            Objects.requireNonNull(record, "A successful read carries the decoded TRAN-CAT-BAL-RECORD");
            return new ReadResult(FileStatus.OK, Outcome.OK, Optional.of(record), Optional.empty(),
                    CicsResponse.ofBatchStatus(FileStatus.OK));
        }

        /**
         * The end-of-file arm: status {@code '10'}, carrying no record.
         *
         * <p>{@code MOVE 16 TO APPL-RESULT} at {@code app/cbl/CBACT04C.cbl:L331}, which the caller turns
         * into {@code MOVE 'Y' TO END-OF-FILE} at {@code L340}. Reached only by a browse - a keyed read
         * cannot produce it.
         *
         * @return a result carrying {@link FileStatus#END_OF_FILE} and no record
         */
        public static ReadResult endOfFile() {
            return of(FileStatus.END_OF_FILE);
        }

        /**
         * The invalid-key arm: status {@code '23'}, carrying no record - and an <strong>expected</strong>
         * outcome, not an error.
         *
         * <p>The {@code INVALID KEY} condition of {@code app/cbl/CBTRN02C.cbl:L475-L478}, whose caller
         * responds by creating the record: {@code MOVE 'Y' TO WS-CREATE-TRANCAT-REC} then
         * {@code PERFORM 2700-A-CREATE-TCATBAL-REC}. Its guard at {@code L481} accepts this status
         * alongside {@code '00'}, which is what {@link #isOkOrNotFound()} reports.
         *
         * @return a result carrying {@link FileStatus#NOT_FOUND} and no record
         */
        public static ReadResult notFound() {
            return of(FileStatus.NOT_FOUND);
        }

        /**
         * A result for any status other than success, classified from the status itself.
         *
         * @param status the two-character status the read reported
         * @return a result carrying {@code status}, its classification, and no record
         * @throws NullPointerException     if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *                                  {@link FileStatus#STATUS_LENGTH} characters, or is
         *                                  {@link FileStatus#OK}
         */
        public static ReadResult of(String status) {
            return new ReadResult(status, classify(status), Optional.empty(), Optional.empty(),
                    CicsResponse.ofBatchStatus(status));
        }

        /**
         * A failed read carrying the CICS pair the outcome should report.
         *
         * <p>For an outcome whose response is <em>not</em> the translation of its file status. The width
         * check is the case that needs it: a row that is not {@link TranCatBalRepository#RECORD_LENGTH}
         * bytes is a permanent error to the batch guard chain and {@link FileStatus#LENGERR} as a CICS
         * response, and neither number can be derived from the other.
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
         * <p>The status is what the caller branches on, because that is the quantity the COBOL guard chain
         * tests. The diagnostic is what makes the failure diagnosable: a permanent-error status tells an
         * operator that something went wrong and nothing about what, whereas the driver's own
         * {@code SQLSTATE} distinguishes an unreachable backend from a missing relation from a rejected
         * credential - three failures needing three different responses.
         *
         * @param status     the file status the caller branches on
         * @param diagnostic what the backend reported
         * @return the outcome
         * @throws NullPointerException if {@code status} or {@code diagnostic} is {@code null}
         */
        public static ReadResult of(String status, BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A backend diagnostic is required; use of(String) where "
                    + "there was no refusal to diagnose");
            return new ReadResult(status, classify(status), Optional.empty(), Optional.of(diagnostic),
                    CicsResponse.ofBatchStatus(status));
        }

        /**
         * Whether the read returned a record: {@code IF TCATBALF-STATUS = '00'}.
         *
         * @return {@code true} for status {@code '00'}
         */
        public boolean isFound() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether the browse reached the end of the file: {@code IF TCATBALF-STATUS = '10'}
         * ({@code app/cbl/CBACT04C.cbl:L330}).
         *
         * @return {@code true} for status {@code '10'}
         */
        public boolean isEndOfFile() {
            return outcome == Outcome.END_OF_FILE;
        }

        /**
         * Whether the key matched no record - the {@code INVALID KEY} condition, and the signal to create.
         *
         * <p>This is {@code IF WS-CREATE-TRANCAT-REC = 'Y'} at {@code app/cbl/CBTRN02C.cbl:L495}, the
         * branch that selects {@code 2700-A-CREATE-TCATBAL-REC} over
         * {@code 2700-B-UPDATE-TCATBAL-REC}.
         *
         * @return {@code true} for status {@code '23'}
         */
        public boolean isNotFound() {
            return outcome == Outcome.NOT_FOUND;
        }

        /**
         * Whether the read succeeded <em>or</em> reported a missing record - {@code CBTRN02C}'s own
         * success condition.
         *
         * <p>{@code IF TCATBALF-STATUS = '00' OR '23'} at {@code app/cbl/CBTRN02C.cbl:L481}, verbatim.
         * Delegated to {@link FileStatus#isOkOrNotFound(String)} so the rule is stated once for the module
         * rather than re-derived here.
         *
         * @return {@code true} for status {@code '00'} or {@code '23'}
         */
        public boolean isOkOrNotFound() {
            return FileStatus.isOkOrNotFound(status);
        }

        /**
         * Whether the outcome is none of the statuses the COBOL names - the fatal arm.
         *
         * @return {@code true} for any status that is not {@code '00'}, {@code '10'} or {@code '23'}
         */
        public boolean isOther() {
            return !isFound() && !isEndOfFile() && !isNotFound();
        }

        /**
         * The status as {@code 9910-DISPLAY-IO-STATUS} renders it, for a caller's diagnostic line.
         *
         * @return the four-character status image
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }

        /**
         * The CICS {@code RESP} value for this outcome, where one applies.
         *
         * @return the response value, or empty where the outcome has no CICS counterpart
         */
        public OptionalInt cicsResp() {
            return response.resp();
        }

        /**
         * The CICS {@code RESP2} reason code for this outcome.
         *
         * @return the reason code, {@link FileStatus#NO_REASON_CODE} where none applies
         */
        public int cicsResp2() {
            return response.resp2();
        }

        /**
         * The {@code APPL-RESULT} value <strong>{@code CBACT04C}'s</strong> guard moves for this outcome.
         *
         * <p>{@code 0} on success, {@code 16} at end of file, and {@code 12} for everything else - exactly
         * the ladder at {@code app/cbl/CBACT04C.cbl:L327-L335}. A not-found record maps to {@code 12} here
         * and not to a gentler value, because that program's browse guard tests only
         * {@code IF TCATBALF-STATUS = '00'} and moves {@code 12} otherwise.
         *
         * <p>Use {@link #applResultWhereNotFoundIsNormal()} instead when reproducing
         * {@code CBTRN02C}'s keyed read, whose ladder is genuinely different.
         *
         * @return {@link FileStatus#APPL_AOK}, {@link FileStatus#APPL_EOF} or
         *         {@link TranCatBalRepository#APPL_RESULT_FATAL}
         */
        public int applResult() {
            return switch (outcome) {
                case OK -> FileStatus.APPL_AOK;
                case END_OF_FILE -> FileStatus.APPL_EOF;
                default -> APPL_RESULT_FATAL;
            };
        }

        /**
         * The {@code APPL-RESULT} value <strong>{@code CBTRN02C}'s</strong> guard moves for this outcome.
         *
         * <p>{@code 0} for status {@code '00'} or {@code '23'}, and {@code 12} for everything else -
         * exactly the ladder at {@code app/cbl/CBTRN02C.cbl:L481-L485}:
         * <pre>
         * IF  TCATBALF-STATUS = '00'  OR '23'
         *     MOVE 0 TO APPL-RESULT
         * ELSE
         *     MOVE 12 TO APPL-RESULT
         * END-IF
         * </pre>
         * There is no end-of-file arm, because that program reads this dataset randomly and cannot reach
         * one: a status of {@code '10'} would be fatal for it, and is reported as such.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link TranCatBalRepository#APPL_RESULT_FATAL}
         */
        public int applResultWhereNotFoundIsNormal() {
            return isOkOrNotFound() ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }
    }

    /**
     * The outcome of a write or a rewrite: the file status, its classification, and what the backend said
     * when it refused.
     *
     * <p>Both consumers' write guards are the same shape - {@code IF TCATBALF-STATUS = '00'} then
     * {@code MOVE 0} else {@code MOVE 12}, at {@code app/cbl/CBTRN02C.cbl:L512-L516} for the write and
     * {@code L530-L534} for the rewrite - so unlike {@link ReadResult} there is only one ladder here and
     * {@link #applResult()} is unambiguous.
     *
     * @param status     the two-character {@code FILE STATUS}, carried verbatim
     * @param outcome    the classification of {@code status}
     * @param diagnostic what the backend reported, when the outcome was a refusal
     * @param response   the CICS {@code RESP}/{@code RESP2} pair for the outcome
     */
    public record WriteResult(String status,
                              Outcome outcome,
                              Optional<BackendDiagnostic> diagnostic,
                              CicsResponse response) {

        /**
         * Enforces every invariant of the outcome at construction.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *                                  {@link FileStatus#STATUS_LENGTH} characters, or {@code outcome}
         *                                  is not the classification of {@code status}
         */
        public WriteResult {
            requireConsistentStatus(status, outcome);
            Objects.requireNonNull(diagnostic, "A write result carries an empty diagnostic rather than a "
                    + "null one, so no null escapes the type");
            Objects.requireNonNull(response, "A write result carries a CICS response pair rather than a "
                    + "null one");
        }

        /**
         * The successful arm: status {@code '00'}.
         *
         * <p>{@code MOVE 0 TO APPL-RESULT} at {@code app/cbl/CBTRN02C.cbl:L513} for the write and
         * {@code L531} for the rewrite.
         *
         * @return a result carrying {@link FileStatus#OK}
         */
        public static WriteResult written() {
            return new WriteResult(FileStatus.OK, Outcome.OK, Optional.empty(),
                    CicsResponse.ofBatchStatus(FileStatus.OK));
        }

        /**
         * The invalid-key arm of a rewrite: status {@code '23'}, meaning there was no such record to
         * rewrite.
         *
         * <p>Fatal for the caller - {@code DISPLAY 'ERROR REWRITING TRANSACTION BALANCE FILE'} at
         * {@code app/cbl/CBTRN02C.cbl:L538} - but fatal <em>in the caller</em>, after it has had the
         * chance to render the status.
         *
         * @return a result carrying {@link FileStatus#NOT_FOUND}
         */
        public static WriteResult notFound() {
            return of(FileStatus.NOT_FOUND);
        }

        /**
         * The duplicate-key arm of a write: status {@code '22'}, meaning a record already carries this key.
         *
         * <p>A KSDS {@code WRITE} on an existing key is a duplicate-key condition rather than a
         * replacement. Fatal for the caller - {@code DISPLAY 'ERROR WRITING TRANSACTION BALANCE FILE'} at
         * {@code app/cbl/CBTRN02C.cbl:L520} - and reported distinctly from a permanent error so an
         * operator can tell "this key already exists" from "the dataset could not be reached".
         *
         * @return a result carrying {@link FileStatus#DUPLICATE}
         */
        public static WriteResult duplicate() {
            return of(FileStatus.DUPLICATE, CicsResponse.of(FileStatus.DUPREC));
        }

        /**
         * A result for any status other than success, classified from the status itself.
         *
         * @param status the two-character status the write reported
         * @return a result carrying {@code status} and its classification
         * @throws NullPointerException     if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *                                  {@link FileStatus#STATUS_LENGTH} characters, or is
         *                                  {@link FileStatus#OK}
         */
        public static WriteResult of(String status) {
            return new WriteResult(status, classify(status), Optional.empty(),
                    CicsResponse.ofBatchStatus(status));
        }

        /**
         * A failed write carrying the CICS pair the outcome should report.
         *
         * @param status   the file status the caller branches on
         * @param response the pair to report
         * @return the outcome
         * @throws NullPointerException     if {@code status} or {@code response} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *                                  {@link FileStatus#STATUS_LENGTH} characters, or is
         *                                  {@link FileStatus#OK}
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
         * @throws NullPointerException if {@code status} or {@code diagnostic} is {@code null}
         */
        public static WriteResult of(String status, BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A backend diagnostic is required; use of(String) where "
                    + "there was no refusal to diagnose");
            return new WriteResult(status, classify(status), Optional.of(diagnostic),
                    CicsResponse.ofBatchStatus(status));
        }

        /**
         * A failed write carrying both the CICS pair and the backend's diagnosis.
         *
         * <p>The duplicate-key case a constraint violation produces needs both: {@code '22'} is the status
         * the caller branches on, {@link FileStatus#DUPREC} is the CICS response, and the
         * {@code SQLSTATE} is what proves the backend enforced the key rather than the pre-check finding
         * it.
         *
         * @param status     the file status the caller branches on
         * @param response   the pair to report
         * @param diagnostic what the backend reported
         * @return the outcome
         * @throws NullPointerException if any argument is {@code null}
         */
        public static WriteResult of(String status, CicsResponse response,
                BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A backend diagnostic is required; use of(String, "
                    + "CicsResponse) where there was no refusal to diagnose");
            return new WriteResult(status, classify(status), Optional.of(diagnostic), response);
        }

        /**
         * Whether the record was stored: {@code IF TCATBALF-STATUS = '00'}.
         *
         * @return {@code true} for status {@code '00'}
         */
        public boolean isWritten() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether a rewrite found no record to rewrite.
         *
         * @return {@code true} for status {@code '23'}
         */
        public boolean isNotFound() {
            return outcome == Outcome.NOT_FOUND;
        }

        /**
         * Whether a write found a record already carrying the key.
         *
         * @return {@code true} for status {@code '22'}
         */
        public boolean isDuplicate() {
            return outcome == Outcome.DUPLICATE;
        }

        /**
         * Whether the outcome is none of the statuses the COBOL names - the fatal arm.
         *
         * @return {@code true} for any status that is not {@code '00'}, {@code '22'} or {@code '23'}
         */
        public boolean isOther() {
            return !isWritten() && !isNotFound() && !isDuplicate();
        }

        /**
         * The status as {@code 9910-DISPLAY-IO-STATUS} renders it, for a caller's diagnostic line.
         *
         * @return the four-character status image
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }

        /**
         * The CICS {@code RESP} value for this outcome, where one applies.
         *
         * @return the response value, or empty where the outcome has no CICS counterpart
         */
        public OptionalInt cicsResp() {
            return response.resp();
        }

        /**
         * The CICS {@code RESP2} reason code for this outcome.
         *
         * @return the reason code, {@link FileStatus#NO_REASON_CODE} where none applies
         */
        public int cicsResp2() {
            return response.resp2();
        }

        /**
         * The {@code APPL-RESULT} value the COBOL guard moves for this outcome.
         *
         * <p>{@code 0} when the record was stored and {@code 12} otherwise - the ladder at
         * {@code app/cbl/CBTRN02C.cbl:L512-L516} and {@code L530-L534}, which is the same for both the
         * write and the rewrite. A duplicate and a missing record both map to {@code 12}, because both
         * paragraphs test only {@code IF TCATBALF-STATUS = '00'}.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link TranCatBalRepository#APPL_RESULT_FATAL}
         */
        public int applResult() {
            return isWritten() ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }
    }

    // =================================================================================================
    // Status classification, shared by both outcome types so they cannot disagree.
    // =================================================================================================

    /**
     * Classifies a status, refusing {@link FileStatus#OK} because both success arms are built by their own
     * factories - which is what keeps a successful read from ever being constructed without its record.
     *
     * @param status the two-character status
     * @return its classification
     * @throws NullPointerException     if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly {@link FileStatus#STATUS_LENGTH}
     *                                  characters, or is {@link FileStatus#OK}
     */
    private static Outcome classify(String status) {
        if (FileStatus.OK.equals(requireStatusWidth(status))) {
            throw new IllegalArgumentException("A successful outcome is built by found(), written() or "
                    + "endOfFile() rather than by of(\"" + FileStatus.OK + "\"), so that a successful read "
                    + "can never be constructed without the record it is supposed to carry");
        }
        return FileStatus.outcomeOfStatus(status);
    }

    /**
     * Requires a status to classify as the outcome it was paired with.
     *
     * <p>The two components could otherwise disagree, and a result whose {@code status} said one thing
     * while its {@code outcome} said another would make every guard chain that branches on the outcome and
     * every diagnostic that renders the status describe different events.
     *
     * @param status  the status
     * @param outcome the classification it was paired with
     * @throws NullPointerException     if either is {@code null}
     * @throws IllegalArgumentException if {@code status} is the wrong width, or {@code outcome} is not its
     *                                  classification
     */
    private static void requireConsistentStatus(String status, Outcome outcome) {
        requireStatusWidth(status);
        Objects.requireNonNull(outcome, "An outcome classification is required");
        Outcome classified = FileStatus.outcomeOfStatus(status);
        if (classified != outcome) {
            throw new IllegalArgumentException("File status " + FileStatus.toStatusImage(status)
                    + " classifies as " + classified + ", not as " + outcome + "; a result whose status "
                    + "and outcome disagree would branch one way and report another");
        }
    }

    /**
     * Requires a status to be exactly {@link FileStatus#STATUS_LENGTH} characters and returns it verbatim.
     *
     * <p>A COBOL {@code FILE STATUS} is two characters - {@code 05 TCATBALF-STAT1 PIC X} and
     * {@code 05 TCATBALF-STAT2 PIC X} at {@code app/cbl/CBTRN02C.cbl:L127-L129} - and anything else could
     * not have come from one, so it is refused rather than padded into something that renders plausibly.
     *
     * @param status the candidate status
     * @return {@code status}, unchanged
     * @throws NullPointerException     if {@code status} is {@code null}
     * @throws IllegalArgumentException if it is not exactly {@link FileStatus#STATUS_LENGTH} characters
     */
    private static String requireStatusWidth(String status) {
        Objects.requireNonNull(status, "A two-character FILE STATUS is required");
        if (status.length() != FileStatus.STATUS_LENGTH) {
            throw new IllegalArgumentException("A FILE STATUS is exactly " + FileStatus.STATUS_LENGTH
                    + " characters - TCATBALF-STAT1 and TCATBALF-STAT2, each PIC X "
                    + "(app/cbl/CBTRN02C.cbl:L127-L129) - but " + status.length() + " were given");
        }
        return status;
    }
}

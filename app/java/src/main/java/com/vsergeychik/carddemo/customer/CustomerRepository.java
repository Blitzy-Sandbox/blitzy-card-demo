package com.vsergeychik.carddemo.customer;

import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;

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
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The customer master dataset, reached over JDBC: one repository for the five access paths the COBOL
 * estate drives against it, and nothing beyond them.
 *
 * <h2>What this class stands for</h2>
 *
 * <p>{@code app/csd/CARDDEMO.CSD:L50-L62} defines the CICS file {@code CUSTDAT} -
 * {@code GROUP(CARDDEMO) DESCRIPTION(CARDDEMO CUSTOMER DATA) RLSACCESS(NO) LSRPOOLNUM(1)
 * READINTEG(UNCOMMITTED) DSNSHARING(ALLREQS) STRINGS(1) STATUS(ENABLED) OPENTIME(FIRSTREF)
 * DISPOSITION(SHARE) UPDATEMODEL(LOCKING) LOAD(NO) RECORDFORMAT(V) ADD(YES) BROWSE(YES) DELETE(YES)
 * READ(YES) UPDATE(YES) JOURNAL(NO) RECOVERY(NONE)} - and the batch JCL binds the same dataset under
 * the DD name {@code CUSTFILE} at {@code app/jcl/READCUST.jcl:L9-L10}. Both names are configured, both
 * are resolved here, and <strong>neither the dataset name nor any part of it is written into Java</strong>:
 * it comes from the {@code carddemo.datasets} keys {@code CUSTDAT} and {@code CUSTFILE} alone.
 *
 * <h2>Five access paths, one repository, and no sixth</h2>
 *
 * <p>Five programs reach this dataset, and the union of everything they do to it is enumerated below.
 * It was established by inspection rather than inferred from the CICS permissions, which are broader
 * than the estate's actual use.
 *
 * <ul>
 *   <li><strong>Open and close.</strong> {@code app/cbl/CBCUS01C.cbl:L118-L134}
 *       ({@code 0000-CUSTFILE-OPEN}) and {@code L136-L152} ({@code 9000-CUSTFILE-CLOSE});
 *       {@code app/cbl/CBSTM03B.CBL:L184} and {@code L196} under its {@code M03B-OPEN} and
 *       {@code M03B-CLOSE} operation codes; and {@code app/cbl/CBTRN01C.cbl:L273} and {@code L381}.
 *       {@code OPEN INPUT} is the <em>only</em> open verb this dataset ever sees - there is no
 *       {@code OPEN I-O}, {@code OUTPUT} or {@code EXTEND} anywhere - which is why {@link #openInput()}
 *       takes no mode argument and why {@link CustomerFile} is read-only. See {@link #openInput()}.</li>
 *   <li><strong>Sequential browse.</strong> {@code app/cbl/CBCUS01C.cbl:L29-L33} declares
 *       {@code SELECT CUSTFILE-FILE ASSIGN TO CUSTFILE / ORGANIZATION IS INDEXED / ACCESS MODE IS
 *       SEQUENTIAL / RECORD KEY IS FD-CUST-ID / FILE STATUS IS CUSTFILE-STATUS} and reads with
 *       {@code READ CUSTFILE-FILE INTO CUSTOMER-RECORD} at {@code L93} until status {@code '10'}. See
 *       {@link CustomerFile#readNext()}.</li>
 *   <li><strong>Random keyed read.</strong> {@code app/cbl/CBSTM03B.CBL:L43-L47} declares the same
 *       dataset with {@code ACCESS MODE IS RANDOM} and its {@code M03B-READ-K} arm sets the key with
 *       {@code MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-CUST-ID} at {@code L189} before reading at
 *       {@code L190}; {@code app/cbl/COACTVWC.cbl:L826-L834} and {@code app/cbl/COACTUPC.cbl:L3753-L3761}
 *       issue the online counterpart, {@code EXEC CICS READ DATASET(LIT-CUSTFILENAME)}. See
 *       {@link #readByKey(long)} and {@link #readByKey(String)}.</li>
 *   <li><strong>Read for update.</strong> {@code app/cbl/COACTUPC.cbl:L3921-L3930} issues
 *       {@code EXEC CICS READ FILE(LIT-CUSTFILENAME) UPDATE RIDFLD(WS-CARD-RID-CUST-ID-X)
 *       KEYLENGTH(LENGTH OF WS-CARD-RID-CUST-ID-X) INTO(CUSTOMER-RECORD) LENGTH(LENGTH OF
 *       CUSTOMER-RECORD) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)} under the comment "Could we lock the
 *       customer record ?" at {@code L3932}. A <em>distinct</em> operation from the plain keyed read,
 *       because the COBOL distinguishes them and the comparison that follows depends on the lock. See
 *       {@link #readForUpdate(String)}.</li>
 *   <li><strong>Rewrite.</strong> {@code app/cbl/COACTUPC.cbl:L4085-L4091} issues
 *       {@code EXEC CICS REWRITE FILE(LIT-CUSTFILENAME) FROM(CUST-UPDATE-RECORD) LENGTH(LENGTH OF
 *       CUST-UPDATE-RECORD) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)}. See {@link #rewrite(byte[])} and
 *       {@link #rewrite(CustomerRecord)}.</li>
 * </ul>
 *
 * <h2>The open is a first-class operation, not a detail of the browse</h2>
 *
 * <p>{@code app/cbl/CBTRN01C.cbl} <strong>opens the customer file at {@code L273} and closes it at
 * {@code L381} without ever reading it</strong> - there is no {@code READ CUSTOMER-FILE} anywhere in
 * that program. It is a genuine quirk of the legacy source, and it is preserved rather than tidied
 * away: the transaction-posting job has to be able to issue that open, observe its status, and take
 * the {@code DISPLAY 'ERROR OPENING CUSTOMER FILE'} ({@code L282}) and abend path if it fails. That is
 * only expressible if the open and the close are status-returning operations in their own right, so
 * {@link #openInput()} yields a handle whose {@link CustomerFile#openStatus()} and
 * {@link CustomerFile#closeFile()} are both observable and neither is implicit in a read.
 *
 * <h2>The key travels as nine characters, or as the number behind them</h2>
 *
 * <p>{@code app/cbl/COACTVWC.cbl:L75-L77} and {@code app/cbl/COACTUPC.cbl:L378-L380} both declare
 * {@code WS-CARD-RID-CUST-ID PIC 9(09)} with {@code WS-CARD-RID-CUST-ID-X REDEFINES
 * WS-CARD-RID-CUST-ID PIC X(09)}, and it is the <strong>character</strong> view that every
 * {@code RIDFLD} names while the <strong>numeric</strong> view is what the program moves into it
 * ({@code MOVE CDEMO-CUST-ID TO WS-CARD-RID-CUST-ID}, {@code COACTVWC.cbl:L708} and
 * {@code COACTUPC.cbl:L3919}). Both views therefore have an entry point: {@link #readByKey(String)}
 * takes the nine-character image verbatim, as {@code CBSTM03B}'s {@code LK-M03B-KEY} substring and the
 * online {@code RIDFLD} hold it, and {@link #readByKey(long)} takes the number and renders it through
 * the module's {@code PIC 9} move rule - zero-filled on the left, and <strong>truncated on the
 * left</strong> when the sending value is wider, which is what COBOL does and therefore what this does.
 * An over-wide identifier is reproduced, not rejected.
 *
 * <h2>There is no numeric scale anywhere in this record</h2>
 *
 * <p>{@code app/cpy/CVCUS01Y.cpy} declares no signed picture and no {@code V} anywhere:
 * {@code CUST-ID} and {@code CUST-SSN} are {@code PIC 9(09)} and {@code CUST-FICO-CREDIT-SCORE} is
 * {@code PIC 9(03)}, all scale-free. This is consequently the one dataset in the migration where the
 * absence of {@code BigDecimal} is the correct answer rather than a defect, and there is no
 * {@code double} or {@code float} here either. Nothing in this class performs arithmetic at all.
 *
 * <h2>Plaintext fields stay plaintext</h2>
 *
 * <p>{@code CUST-SSN PIC 9(09)} and {@code CUST-GOVT-ISSUED-ID PIC X(20)} are stored in the clear, and
 * this class neither masks, hashes, truncates nor redacts them on the way in or out. Doing any of those
 * would change what the dataset holds and break field-for-field diffing, which is the opposite of a
 * like-for-like migration. What <em>is</em> withheld is diagnostics: a log line or an exception message
 * from this class carries a masked identifier and never a record image, because an application log is
 * read by more people and retained for longer than the dataset itself.
 *
 * <h2>This class reports statuses; it never abends</h2>
 *
 * <p>Every operation returns a value carrying a two-character {@code FILE STATUS}, and none of them
 * terminates the program. That is exactly where the COBOL draws the line: the I/O paragraph reports the
 * status and the <em>caller</em> decides. {@code app/cbl/CBCUS01C.cbl:L104-L115} is the canonical shape -
 * <pre>
 * IF  APPL-AOK        CONTINUE
 * ELSE IF APPL-EOF    MOVE 'Y' TO END-OF-FILE
 *      ELSE           DISPLAY 'ERROR READING CUSTOMER FILE'
 *                     MOVE CUSTFILE-STATUS TO IO-STATUS
 *                     PERFORM Z-DISPLAY-IO-STATUS
 *                     PERFORM Z-ABEND-PROGRAM
 * </pre>
 * so the {@code DISPLAY} line, the rendered status image and the {@code CALL 'CEE3ABD'} at {@code L158}
 * all belong to the customer service and its job, not here. {@link ReadResult#applResult()} and
 * {@link WriteResult#applResult()} hand the caller the very {@code APPL-RESULT} value its guard would
 * have moved, so the chain translates one-for-one. The four-character {@code IO-STATUS-04} image that
 * {@code Z-DISPLAY-IO-STATUS} ({@code L161-L174}) renders is not reimplemented either -
 * {@link FileStatus#toDisplayLine(String)} already owns it, and a second copy would be a second source
 * of truth.
 *
 * <p>A contract violation is a different thing from an I/O outcome and is thrown rather than statused: a
 * configured record width that is not the copybook's, a dataset name that cannot address anything, a key
 * image of the wrong width, or a backend that presents no record-image column at all. None of those is a
 * condition the COBOL could observe as a file status, and reporting one would send a job down its abend
 * path with a misleading reason.
 *
 * <h2>How a fixed-width dataset is addressed over JDBC</h2>
 *
 * <p>The dataset is a fixed-width record store, not a relational table, and this migration introduces no
 * schema of its own: there is no data-definition statement anywhere in this class, no migration, no
 * entity mapping, no version column and no index creation. The dataset is reached as a single-column
 * relation whose one column holds the whole record image, addressed at position
 * {@value #RECORD_IMAGE_COLUMN_INDEX} - the convention every other repository in this module uses.
 *
 * <p>Where a column <em>name</em> is unavoidable - SQL permits an ordinal in {@code ORDER BY} but not in
 * a {@code WHERE} or a {@code SET} clause, and the keyed read and the rewrite both need one - it is
 * <strong>discovered</strong> from JDBC result-set metadata by a probe that transfers no rows, and is
 * never invented, defaulted or configured. The statements composed from it are confined to constructs
 * that are core SQL and carry no dialect assumption: a delimited identifier, {@code ORDER BY}, a
 * comparison, {@code LIKE … ESCAPE}, {@code FOR UPDATE} and a single-column {@code UPDATE}. Row limiting
 * is applied through {@link PreparedStatement} rather than through a dialect's row-limiting clause.
 *
 * <p><strong>No SQL text is written in this class at all.</strong> Every statement comes from
 * {@link DatasetRelation}, the module's single composer, so the grammar of a browse, a keyed read, a
 * locking read and a rewrite is decided once for every dataset rather than once per repository. That is
 * also why the read projection reads {@code SELECT *} rather than a column list, and it is worth being
 * precise about why that is not the anti-pattern it resembles: this relation has <em>exactly one</em>
 * column by contract - the whole record image - so a star projects that one column and nothing else.
 * There is no wider row for it to drag along and no column order for it to become sensitive to. Naming
 * the column instead would mean naming it <em>before</em> the metadata probe has discovered it, which
 * would put an unverifiable identifier into Java for no gain. The value is nevertheless read by explicit
 * position ({@value #RECORD_IMAGE_COLUMN_INDEX}) and never by scanning, and every {@code WHERE},
 * {@code SET} and {@code ORDER BY} clause names the discovered column explicitly.
 *
 * <h2>Ordering is asserted in the statement, never assumed</h2>
 *
 * <p>{@link CustomerFile#readNext()} must deliver records in ascending {@code CUST-ID} order, because
 * that is what a KSDS sequential read gives {@code CBCUS01C} and its output sequence is parity-relevant.
 * Every browse statement therefore carries an explicit ascending {@code ORDER BY}. Ordering by the record
 * image <em>is</em> ordering by the key: {@code CUST-ID PIC 9(09)} occupies the leading nine bytes of the
 * record ({@code app/cpy/CVCUS01Y.cpy:L5}), the cluster confirms it independently with
 * {@code KEYS(9 0)} ({@code app/jcl/CUSTFILE.jcl:L50}), and a {@code PIC 9} key is zero-filled to its
 * full width - so the byte ordering of the images and the numeric ordering of the keys coincide. Nothing
 * here relies on a backend's natural scan order.
 *
 * <h2>Concurrency, locking and the unit of work</h2>
 *
 * <p>{@code UPDATEMODEL(LOCKING)} with {@code READINTEG(UNCOMMITTED)} and {@code RECOVERY(NONE)}
 * ({@code app/csd/CARDDEMO.CSD:L53}, {@code L56}, {@code L59}) means a record read for update is held for
 * the duration of the unit of work, so {@link #readForUpdate(String)} issues a <strong>genuine locking
 * read</strong> and <em>requires</em> a unit of work to be open. Under auto-commit a {@code FOR UPDATE}
 * row lock is taken and released before the statement returns, which would leave the caller believing it
 * held a record it did not - and that is precisely the hazard {@code 9600-WRITE-PROCESSING} exists to
 * avoid. A CICS task always has a unit of work, so no legacy path reads for update outside one; a Java
 * caller that does has a wiring defect and is told so. The transaction boundary itself belongs to the
 * caller, so this class declares none and opens none.
 *
 * <p>The optimistic check {@code COACTUPC} performs is not this class's job either. Paragraph
 * {@code 9700-CHECK-CHANGE-IN-REC} ({@code app/cbl/COACTUPC.cbl:L4109-L4193}) compares the re-read record
 * field by field against the copy the screen was painted from, and it runs at {@code L3947-L3948},
 * between the read for update and the rewrite. That comparison lives in the account update service,
 * which is where the COBOL puts it. What this class guarantees is that the record it hands to that
 * comparison is locked and stays locked until the caller's transaction ends. <strong>No version column
 * is introduced</strong>, because a version column would be a schema change.
 *
 * <h2>Thread safety</h2>
 *
 * <p><strong>This repository is stateless and therefore thread-safe.</strong> It is a Spring singleton,
 * so anything mutable held on it would be shared by every concurrent execution; a browse position in
 * particular would let two callers consume each other's records and destroy the ordering parity depends
 * on. COBOL shares neither - a file position and an {@code OPEN} verb belong to one program execution,
 * exactly as {@code WORKING-STORAGE} does, so {@code CUSTFILE-STATUS}, {@code END-OF-FILE} and
 * {@code APPL-RESULT} become values that travel back to the caller rather than fields on this class.
 * Every field here is {@code final} and immutable, and there is <strong>no static mutable state</strong>:
 * the only {@code static} members are immutable constants and a logger reference.
 *
 * <p>All per-execution state lives on {@link CustomerFile}, the {@link AutoCloseable} handle
 * {@link #openInput()} returns. One handle stands for one opened file: it carries that open's status, the
 * statements resolved for it and its browse position, and it is used by whoever opened it, exactly as a
 * COBOL {@code FD} is. A handle is <em>not</em> thread-safe and is not meant to be; obtaining a second
 * one is free and is the correct answer.
 *
 * <p>The keyed operations are also exposed directly on the repository, because the CICS programs that
 * drive them issue no {@code OPEN} at all - {@code COACTUPC}'s {@code EXEC CICS READ ... UPDATE} names
 * the file and nothing more. They touch no position and hold nothing between calls, so they are safe to
 * call concurrently.
 *
 * <h2>The record is always exactly 500 bytes</h2>
 *
 * <p>Verified three independent ways. {@code app/cpy/CVCUS01Y.cpy:L2} declares {@code RECLN 500} and its
 * nineteen spans sum to exactly that; the file descriptions at {@code app/cbl/CBCUS01C.cbl:L37-L40},
 * {@code app/cbl/CBSTM03B.CBL:L70-L73} and {@code app/cbl/CBTRN01C.cbl:L71-L74} all split it as a
 * nine-byte identifier plus {@code PIC X(491)}; and {@code README.md:L72} lists the dataset as
 * {@code CVCUS01Y} / {@code FB} / {@code 500} with {@code custdata.txt} as its ASCII equivalent, which
 * measures 50 records of exactly 500 bytes.
 *
 * <p><strong>On the record format.</strong> The CICS definition says {@code RECORDFORMAT(V)}
 * ({@code app/csd/CARDDEMO.CSD:L56}) while the cluster definition says {@code RECORDSIZE(500 500)}
 * ({@code app/jcl/CUSTFILE.jcl:L51}) and the batch view is fixed {@code FB} at 500
 * ({@code README.md:L72}). That disagreement is resolved for the whole module in favour of
 * <strong>copybook-fixed lengths</strong>: this class holds the configured width to
 * {@link CustomerRecord#RECORD_LENGTH} and refuses anything else. Every image it decodes and every image
 * it writes is that wide, {@code FILLER X(168)} ({@code app/cpy/CVCUS01Y.cpy:L23}) included - the 168
 * bytes are written as spaces rather than dropped, which is what makes the width provable rather than
 * assumed.
 *
 * <p>A stored image of any other width is <strong>reported, not repaired</strong>. Widening a short one
 * with spaces looks faithful - the only bytes it could be missing are trailing {@code FILLER}, and a
 * {@code FILLER} carrying no literal holds spaces - but that reasoning holds only if the missing bytes
 * really are the trailing ones, and nothing about a short row says they are. A row that lost bytes
 * anywhere else pads into a record whose every later field decodes from an offset that is not its own: a
 * credit score read from the wrong span is still a number, so the defect would arrive at the caller as
 * plausible data rather than as a failure. It is reported as {@link FileStatus#LENGERR}, which is what
 * CICS reports when a record does not fit its receiver.
 *
 * <h2>What this class deliberately does not have</h2>
 *
 * <ul>
 *   <li><strong>No add and no delete.</strong> {@code app/csd/CARDDEMO.CSD:L56-L57} grants
 *       {@code ADD(YES)} and {@code DELETE(YES)}, but no program among the 28 writes a new customer
 *       record or deletes one - there is no {@code WRITE}, no {@code DELETE} and no batch
 *       {@code REWRITE} against this dataset anywhere. Exposing either would invent an access path the
 *       estate does not have.</li>
 *   <li><strong>No CICS browse.</strong> There is no {@code EXEC CICS STARTBR}, {@code READNEXT} or
 *       {@code ENDBR} on this dataset, so the only browse modelled is the batch sequential read.</li>
 *   <li><strong>No alternate-index finder.</strong> The customer master has no alternate index;
 *       {@code CARDAIX} is a path over the card master and {@code CXACAIX} over the cross-reference,
 *       and neither belongs here.</li>
 *   <li><strong>No handle-level update.</strong> {@link CustomerFile} offers no read for update and no
 *       rewrite, because the dataset is never opened {@code I-O}: the update path is CICS-only and
 *       reaches this repository directly.</li>
 *   <li><strong>No generic query surface.</strong> No predicate builder, no paging, no projection and no
 *       ad-hoc filter.</li>
 *   <li><strong>No object-relational mapping, and no codec of its own.</strong> Byte-to-field
 *       translation is delegated to {@link CustomerRecord}, which declares the copybook's spans against
 *       the module's hand-written fixed-width codec, so every offset stays reviewable against
 *       {@code app/cpy/CVCUS01Y.cpy} rather than hidden behind a mapping layer. This class knows the
 *       record's width and where its key sits, and nothing else about the layout.</li>
 * </ul>
 *
 * <h2>Who depends on this dataset</h2>
 *
 * <p>Six programs copy {@code CVCUS01Y}: {@code CBCUS01C}, {@code CBTRN01C}, {@code COACTUPC},
 * {@code COACTVWC}, {@code COCRDSLC} and {@code COCRDUPC}. Only the <strong>first four</strong> reach
 * the dataset - {@code COCRDSLC} and {@code COCRDUPC} contain no reference to {@code CUSTDAT} or
 * {@code CUSTFILE} at all and are layout-only consumers of the record shape. A fifth program reaches the
 * dataset without copying the copybook: {@code CBSTM03B}, which declares the layout inline in its own
 * {@code FD} ({@code app/cbl/CBSTM03B.CBL:L70-L73}) and is the statement job's data-access subroutine.
 *
 * <h2>User-specified rules</h2>
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - and that single line is
 * the whole document, so <strong>no user rule governs this file</strong>. Its absence is not licence to
 * lower the bar: the migration plan elevates twelve enterprise practices to binding constraints instead,
 * and the ones bearing on this file are B1 (no dependency coordinate is declared here), B3 (the COBOL,
 * copybook, JCL and CSD trees cited throughout are read-only and appear only as provenance), B4 (no scope
 * creep - the superseded relational design is ignored rather than followed, and this class carries no
 * entity shape), B5 (dead behaviour preserved - {@code CBTRN01C}'s open without a read stays reachable),
 * B6 (the plaintext social-security and government-identifier fields are neither weakened nor
 * unrequestedly strengthened), B8 (explicit over implicit - the dataset name and every width come from
 * configuration, the code page is injected rather than defaulted, the ordering is stated in the
 * statement, and there are no wildcard imports), B9 (no static mutable state; constructor injection
 * only), B10 (every decision belongs to the service, so this class holds none), B11 (the hand-written
 * codec, reached through the record model) and B12 (the inability to reach a production backend from this
 * build is documented rather than absorbed).
 *
 * @see CustomerRecord
 * @see FileStatus
 */
@Repository
public class CustomerRepository {

    /**
     * Logger for the diagnostics this class emits on a failed operation.
     *
     * <p>{@code static final} and a reference to an immutable logger, so it introduces no shared mutable
     * state. It exists because the outcome that travels back to a caller is deliberately coarse - a
     * two-character status, exactly as the COBOL has - and discarding the underlying reason would leave a
     * production failure undiagnosable.
     */
    private static final Log LOG = LogFactory.getLog(CustomerRepository.class);

    /**
     * The CICS {@code FILE} name of the customer master: {@code CUSTDAT}.
     *
     * <p>Defined at {@code app/csd/CARDDEMO.CSD:L50}, and the value of
     * {@code LIT-CUSTFILENAME PIC X(8) VALUE 'CUSTDAT '} at {@code app/cbl/COACTVWC.cbl:L188-L189} and
     * {@code app/cbl/COACTUPC.cbl:L575-L576}, which the online programs pass as {@code FILE(…)} or
     * {@code DATASET(…)}.
     *
     * <p>This is a DD-name key, not a dataset name. It resolves against {@code carddemo.datasets}; the
     * dataset name itself is never written in Java.
     */
    public static final String CICS_FILE_NAME = "CUSTDAT";

    /**
     * The batch DD name of the same dataset: {@code CUSTFILE}.
     *
     * <p>The name every non-CICS program assigns it - {@code ASSIGN TO CUSTFILE} at
     * {@code app/cbl/CBCUS01C.cbl:L29}, {@code app/cbl/CBSTM03B.CBL:L43} and
     * {@code app/cbl/CBTRN01C.cbl:L34} - and the DD the JCL binds at
     * {@code app/jcl/READCUST.jcl:L9-L10}.
     *
     * <p>Also a key, not a dataset name. It is resolved alongside {@link #CICS_FILE_NAME} and the two are
     * required to agree, because they address one dataset.
     */
    public static final String BATCH_DD_NAME = "CUSTFILE";

    /**
     * The declared record width in bytes: {@value}, delegated to {@link CustomerRecord#RECORD_LENGTH}
     * rather than restated, so the module has one width for this record.
     */
    public static final int RECORD_LENGTH = CustomerRecord.RECORD_LENGTH;

    /**
     * How a stored record image is named in a decoding diagnostic - never its content.
     *
     * <p>{@link FixedWidthCodec#decodeImage(byte[], String)} refuses a byte the dataset code page does
     * not define rather than substituting a replacement character, and it needs a name for what it was
     * decoding. That name is a source citation, because the stored bytes of a customer record are a name,
     * a home address, a phone number, a government-issued identifier and a social security number, and
     * none of those may reach a log or an exception message.
     */
    private static final String RECORD_IMAGE_SUBJECT =
            "the stored CUSTOMER-RECORD image displayed by app/cbl/CBCUS01C.cbl:78 and :96";

    /**
     * The primary-key width in bytes: nine, taken from the declared width of {@code CUST-ID} rather than
     * restated as a literal.
     *
     * <p>{@code CUST-ID PIC 9(09)} is the first item of the record ({@code app/cpy/CVCUS01Y.cpy:L5}), the
     * cluster declares {@code KEYS(9 0)} ({@code app/jcl/CUSTFILE.jcl:L50}), and this is the value
     * {@code app/cbl/COACTUPC.cbl:L3925} supplies as
     * {@code KEYLENGTH (LENGTH OF WS-CARD-RID-CUST-ID-X)}.
     */
    public static final int KEY_LENGTH = CustomerRecord.CUST_ID.length();

    /**
     * The one-based result-set position of the record-image column: {@value}.
     *
     * <p>A position and never a column name, for the reason set out on this class: a dataset carrying no
     * relational metadata is presented as a single record-image column, and naming that column in Java
     * would be a literal this file cannot justify. Where a name is unavoidable - a keyed predicate and a
     * rewrite both need one - it is discovered from result-set metadata at this same position.
     */
    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    /**
     * The {@code APPL-RESULT} value the COBOL moves on the fatal arm of every guard chain over this
     * dataset: {@value}.
     *
     * <p>{@code app/cbl/CBCUS01C.cbl:L101}, {@code L124} and {@code L142} move it for a failed read, open
     * and close respectively, and {@code app/cbl/CBTRN01C.cbl:L277} and {@code L385} do the same for its
     * open and close. Neither {@link FileStatus#APPL_AOK} nor {@link FileStatus#APPL_EOF} is restated
     * here - both are taken from {@link FileStatus}.
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
     * first byte is {@code '9'} carries a binary feedback code in its second byte, which is precisely the
     * case {@code Z-DISPLAY-IO-STATUS} tests for with
     * {@code IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'} ({@code app/cbl/CBCUS01C.cbl:L162-L163}) before
     * rendering it as four digits. Passing this value to {@link FileStatus#toDisplayLine(String)}
     * therefore produces exactly the line the COBOL would have emitted.
     *
     * <p>It classifies as {@link Outcome#OTHER}, so it reaches the {@code WHEN OTHER} arm of every guard
     * chain - which is where a permanent I/O error belongs.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    /**
     * Where the key sits inside the record image: the first {@link #KEY_LENGTH} bytes.
     *
     * <p>An offset and a length rather than a column name, because the key is part of the record image
     * and is addressed the way every other field of it is. Both components are taken from the record
     * model's own descriptor for {@code CUST-ID}, so this class states no offset of its own.
     */
    private static final KeySpan KEY_SPAN =
            new KeySpan(CustomerRecord.CUST_ID.offset(), KEY_LENGTH);

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
    // Collaborators and resolved configuration. Every one of them is final and every one arrives through
    // the constructor; nothing here is discovered from a static context or a service locator.
    // =================================================================================================

    /**
     * The module's single {@link JdbcTemplate}, from the data-access configuration. The only collaborator
     * that touches the backend.
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * The module's hand-written fixed-width codec, holding the injected code page.
     *
     * <p>Used here as the single place the code page is kept, so no call site in this class chooses one,
     * as the renderer of the {@code PIC 9} key image, and as the codec the record model encodes and
     * decodes through.
     */
    private final FixedWidthCodec codec;

    /** The resolved dataset name, taken verbatim from the configured binding. */
    private final String datasetName;

    /**
     * The dataset as this module reaches it: the validated name, its delimited rendering, the
     * record-image column it discovers, and every statement composed over it.
     *
     * <p>The module's one data-access contract, shared with every other repository, so that how a dataset
     * name becomes a SQL identifier and how a key becomes a predicate is decided once rather than once
     * per repository.
     */
    private final DatasetRelation relation;

    /**
     * The one representation every read, write and comparison operand of this dataset uses.
     *
     * <p>Injected rather than chosen here. Whether the record image is a character column or a binary one
     * is a property of the deployment's driver, and a per-repository answer to that question is a
     * per-repository chance to be wrong in silence: a driver asked for a character value converts the
     * stored bytes through a code page of its own choosing. {@link RecordImageForm} is the module's single
     * authority, so there is no local choice left to disagree about.
     */
    private final RecordImageForm recordImageForm;

    // =================================================================================================
    // There is no mutable per-instance state, and that is the point. A Spring singleton that held an open
    // status, a browse position or a resolved shape would share all three with every concurrent
    // execution, and COBOL shares none of them. All three therefore live on CustomerFile, one instance
    // per open. Practice B9, gate G53.
    // =================================================================================================

    /**
     * Resolves the customer master's configured bindings, proves the record geometry, and captures the
     * collaborators - all before the context finishes starting, so nothing checkable is left to fail
     * mid-job.
     *
     * <p>Five things are established here, in this order, and each fails loudly rather than degrading:
     * <ol>
     *   <li>the collaborators are present - a missing one is a wiring defect and is reported as one;</li>
     *   <li>both DD names are configured. {@link DatasetBindings#binding(String)} matches a key exactly,
     *       with no case-insensitive or fuzzy fallback, and names every configured key in its diagnostic
     *       if one is absent;</li>
     *   <li>each binding declares {@link #RECORD_LENGTH}. Any other width is a configuration defect: this
     *       repository addresses the record by absolute offset against {@code app/cpy/CVCUS01Y.cpy}, so a
     *       differently-sized record would silently misplace every field after the first;</li>
     *   <li>each binding's declared key width, <em>where one is declared</em>, is {@link #KEY_LENGTH}.
     *       The startup validator already requires a keyed entry to declare one, so an absent width means
     *       a caller built the binding by hand and is accepted; a present one that disagrees with the
     *       copybook is a defect;</li>
     *   <li>the two bindings name the same dataset. {@code app/csd/CARDDEMO.CSD:L50-L52} defines the CICS
     *       file and {@code app/jcl/READCUST.jcl:L9-L10} defines the batch DD over <em>one</em> customer
     *       master, so a deployment that pointed them at two different ones would give the online and the
     *       batch programs different data while every parity case assumed otherwise.</li>
     * </ol>
     *
     * <p>The code page is injected and handed straight to the codec, never derived and never left to the
     * platform: a fixed-width mainframe record is bytes in a specific code page. The dataset code-page
     * bean is the one the whole data layer takes, so the encoding of dataset input is governed by a single
     * property rather than by a decision repeated at each call site.
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
    public CustomerRepository(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm) {

        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + "customer master is reached through the module's shared template");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "dataset names live in configuration and are never written in Java");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset is "
                + "required: a fixed-width mainframe record is bytes in a specific code page, so the "
                + "code page is stated explicitly and never taken from the platform"));
        this.recordImageForm = Objects.requireNonNull(recordImageForm, "A record-image representation is "
                + "required: whether this deployment's driver presents a record image as characters or as "
                + "bytes is stated once, by " + RecordImageForm.FORM_PROPERTY + ", and never decided per "
                + "repository");
        RecordImageForm.requireSingleByteCodePage(datasetCharset);

        DatasetBinding cicsBinding = requireCustomerGeometry(datasetBindings, CICS_FILE_NAME);
        DatasetBinding batchBinding = requireCustomerGeometry(datasetBindings, BATCH_DD_NAME);
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
     * @return 500
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
    // 0000-CUSTFILE-OPEN  - app/cbl/CBCUS01C.cbl:L118-L134, OPEN INPUT
    // 9000-CUSTFILE-CLOSE - app/cbl/CBCUS01C.cbl:L136-L152
    // 0100-CUSTFILE-OPEN  - app/cbl/CBTRN01C.cbl:L271-L287, OPEN INPUT, never read from
    // 9100-CUSTFILE-CLOSE - app/cbl/CBTRN01C.cbl:L379-L395
    // 3000-CUSTFILE-PROC  - app/cbl/CBSTM03B.CBL:L181-L204, the M03B-OPEN and M03B-CLOSE arms
    //
    // Every one of those paragraphs has one shape: issue the verb, then IF status = '00' move 0 to
    // APPL-RESULT else move 12, and on the fatal arm display an error line, render the status and abend.
    // These methods reproduce the first half only - the status - because the caller owns the second half.
    // =================================================================================================

    /**
     * Opens the customer master for input and hands back the handle that stands for that open: the Java
     * form of {@code OPEN INPUT} at {@code app/cbl/CBCUS01C.cbl:L120},
     * {@code app/cbl/CBTRN01C.cbl:L273} and {@code app/cbl/CBSTM03B.CBL:L184}.
     *
     * <p><strong>No mode argument, because there is only one verb.</strong> Those three sites are the
     * complete set of opens against this dataset, and all three are {@code OPEN INPUT}; there is no
     * {@code OPEN I-O}, {@code OUTPUT} or {@code EXTEND} anywhere in the estate. A mode parameter would
     * therefore offer a choice the COBOL never makes, and the returned handle is read-only for the same
     * reason - the update path is CICS-only and issues no open at all.
     *
     * <p><strong>Why this returns a handle rather than a status.</strong> An {@code OPEN} produces
     * something: a file with a position, private to the program that opened it. Reporting only a status and
     * keeping that position on the repository would make it shared singleton state, and two concurrent
     * executions would then consume each other's records. The handle <em>is</em> the file, so each
     * execution has its own and the repository keeps nothing. The status the COBOL tests at
     * {@code CBCUS01C.cbl:L121} is {@link CustomerFile#openStatus()}, reported on the handle rather than
     * lost.
     *
     * <p><strong>What "open" means against a configuration-bound driver, precisely.</strong> There is no
     * persistent file handle to acquire: the shared template borrows and returns a connection per
     * operation. So what this method does is the honest equivalent - it establishes the two things an
     * {@code OPEN} establishes, and reports whether it could:
     * <ul>
     *   <li><strong>it positions the file.</strong> The returned handle is positioned before the first
     *       record, so its first {@link CustomerFile#readNext()} returns the first record in key order.
     *       This is a real, observable effect: a {@code CLOSE} followed by an {@code OPEN INPUT} re-reads a
     *       COBOL file from its first record, and a fresh handle re-reads this one from its first
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
     * the {@code DISPLAY 'ERROR OPENING CUSTFILE'} line ({@code CBCUS01C.cbl:L129}) or
     * {@code DISPLAY 'ERROR OPENING CUSTOMER FILE'} ({@code CBTRN01C.cbl:L282}), the rendered status and
     * the abend.
     *
     * <p><strong>Opening without reading is legitimate here.</strong> {@code CBTRN01C} opens this dataset
     * at {@code L273} and closes it at {@code L381} with no read in between, and that behaviour is
     * preserved rather than optimised away: a handle that is opened, checked and closed issues exactly the
     * two probes the COBOL's two guards test, and reports exactly the two statuses they branch on.
     *
     * @return a freshly positioned handle whose {@link CustomerFile#openStatus()} reports whether the
     *         dataset was addressable; never {@code null}
     * @throws IllegalStateException if the backend presents the dataset with no usable record-image
     *                               column, which is a contract violation rather than an I/O outcome
     */
    public CustomerFile openInput() {
        // An OPEN resolves the dataset's shape afresh. The resolved shape and the browse position belong
        // to the returned handle, never to this instance: a Spring @Repository is a singleton, and two
        // concurrent executions sharing one position would interleave each other's browses.
        this.relation.forgetRecordImageColumn();

        Statements resolved;
        try {
            resolved = resolveStatements();
        } catch (DataAccessException unreachable) {
            logRefusal(unreachable, "describe the customer master dataset '" + datasetName
                    + "' to OPEN INPUT it");
            return new CustomerFile(this, PERMANENT_ERROR_STATUS, null);
        }
        return new CustomerFile(this, FileStatus.OK, resolved);
    }

    // =================================================================================================
    // The keyed read.
    //
    // 3000-CUSTFILE-PROC        - app/cbl/CBSTM03B.CBL:L188-L193, the batch keyed read.
    // 9400-GETCUSTDATA-BYCUST   - app/cbl/COACTVWC.cbl:L825-L872 and app/cbl/COACTUPC.cbl:L3752-L3799,
    //                             the online keyed read.
    // =================================================================================================

    /**
     * Reads one record by customer identifier: the Java form of the numeric view of the key.
     *
     * <p>A {@code long} because that is the view the calling program holds before it moves the value into
     * the record identification field: {@code MOVE CDEMO-CUST-ID TO WS-CARD-RID-CUST-ID} at
     * {@code app/cbl/COACTVWC.cbl:L708} and {@code app/cbl/COACTUPC.cbl:L3919}, both into a
     * {@code PIC 9(09)} item. The number is rendered to the stored nine-digit zero-filled image by the
     * module's {@code PIC 9} move rule, so an identifier wider than nine digits loses its <em>high</em>
     * order digits exactly as COBOL's decimal-point alignment makes it lose them, and is
     * <strong>not</strong> rejected. A negative value is refused, because {@code PIC 9} declares no sign
     * position and therefore has no representation for one.
     *
     * <p>Wider than {@code int} on purpose: {@code CUST-ID} holds nine digits, so every legitimate value
     * fits an {@code int}, but the left-truncation rule is only expressible if an over-wide sending value
     * can be passed in the first place.
     *
     * <p><strong>A missing record is reported, not thrown.</strong> It yields status {@code '23'}, which is
     * what lets the online caller take its {@code DFHRESP(NOTFND)} arm - {@code SET INPUT-ERROR TO TRUE},
     * {@code SET FLG-CUSTFILTER-NOT-OK TO TRUE} and the {@code 'CustId:' … ' not found in customer
     * master.Resp: '} message at {@code app/cbl/COACTVWC.cbl:L839-L857} - rather than an exception that
     * would skip the message entirely.
     *
     * @param custId the customer identifier; must not be negative
     * @return the discriminated outcome; never {@code null}
     * @throws IllegalArgumentException if {@code custId} is negative
     * @throws IllegalStateException    if the backend presents the dataset with no usable record-image
     *                                  column
     */
    public ReadResult readByKey(long custId) {
        // The identifier is masked in the diagnostic subject, not in the key image: the read itself uses
        // the real key, while anything that ends up in a log line or an exception message carries only
        // enough of it to correlate two entries (CWE-532).
        return readKeyed(keyImageOf(custId), "the customer identifier "
                + SensitiveDiagnostics.maskIdentifier(custId, KEY_LENGTH), false);
    }

    /**
     * Reads one record by its nine-character key image: the Java form of
     * {@code app/cbl/COACTVWC.cbl:L826-L834},
     * <pre>
     * EXEC CICS READ
     *      DATASET   (LIT-CUSTFILENAME)
     *      RIDFLD    (WS-CARD-RID-CUST-ID-X)
     *      KEYLENGTH (LENGTH OF WS-CARD-RID-CUST-ID-X)
     *      INTO      (CUSTOMER-RECORD)
     *      LENGTH    (LENGTH OF CUSTOMER-RECORD)
     *      RESP      (WS-RESP-CD)
     *      RESP2     (WS-REAS-CD)
     * END-EXEC
     * </pre>
     * and of {@code app/cbl/CBSTM03B.CBL:L189-L190}, whose {@code M03B-READ-K} arm moves
     * {@code LK-M03B-KEY (1:LK-M03B-KEY-LN)} - a character substring - into {@code FD-CUST-ID PIC X(09)}
     * before reading.
     *
     * <p>A {@code String} of exactly {@link #KEY_LENGTH} characters because the {@code RIDFLD} is the
     * <strong>character</strong> view of the key: {@code WS-CARD-RID-CUST-ID-X REDEFINES
     * WS-CARD-RID-CUST-ID PIC X(09)} ({@code app/cbl/COACTVWC.cbl:L76-L77}). The image is used verbatim
     * and is <em>not</em> parsed as a number, because it need not be one: a blank or partially-typed
     * screen field reaches the file as spaces, and the COBOL read simply reports that no such record
     * exists. Parsing would turn that into a rejection the program never performs.
     *
     * <p>The caller's branch is the {@code EVALUATE WS-RESP-CD} at {@code app/cbl/COACTVWC.cbl:L836}:
     * {@code DFHRESP(NORMAL)} sets {@code FOUND-CUST-IN-MASTER}, {@code DFHRESP(NOTFND)} sets the input
     * error, and {@code WHEN OTHER} renders the file-error message. {@link ReadResult#isFound()},
     * {@link ReadResult#isNotFound()} and {@link ReadResult#isOther()} are those three arms, and
     * {@link ReadResult#cicsResp()} carries the value the message renders as {@code ERROR-RESP}.
     *
     * @param custIdAsChar9 the key exactly as the {@code RIDFLD} holds it: exactly {@link #KEY_LENGTH}
     *                      characters, untrimmed and unparsed
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException     if {@code custIdAsChar9} is {@code null}
     * @throws IllegalArgumentException if {@code custIdAsChar9} is not exactly {@link #KEY_LENGTH}
     *                                  characters
     * @throws IllegalStateException    if the backend presents the dataset with no usable record-image
     *                                  column
     */
    public ReadResult readByKey(String custIdAsChar9) {
        String keyImage = requireKeyImage(custIdAsChar9);
        return readKeyed(keyImage, "the record identification field '"
                + SensitiveDiagnostics.maskIdentifier(keyImage) + "'", false);
    }

    /**
     * Reads one record for update by its nine-character key image: the Java form of
     * {@code app/cbl/COACTUPC.cbl:L3921-L3930},
     * <pre>
     * MOVE CDEMO-CUST-ID              TO WS-CARD-RID-CUST-ID
     * EXEC CICS READ
     *      FILE      (LIT-CUSTFILENAME)
     *      UPDATE
     *      RIDFLD    (WS-CARD-RID-CUST-ID-X)
     *      KEYLENGTH (LENGTH OF WS-CARD-RID-CUST-ID-X)
     *      INTO      (CUSTOMER-RECORD)
     *      LENGTH    (LENGTH OF CUSTOMER-RECORD)
     *      RESP      (WS-RESP-CD)
     *      RESP2     (WS-REAS-CD)
     * END-EXEC
     * </pre>
     *
     * <p><strong>A distinct operation from {@link #readByKey(String)}, deliberately.</strong> The COBOL
     * distinguishes them - {@code 9400-GETCUSTDATA-BYCUST} reads without {@code UPDATE} and this site
     * reads with it - and the difference is not cosmetic: the account update service's optimistic check
     * is only meaningful if nothing can change the record between the read that feeds it and the rewrite
     * that acts on it. Collapsing the two would silently drop the lock.
     *
     * <p><strong>Where the lock is.</strong> The CICS file is defined {@code UPDATEMODEL(LOCKING)} with
     * {@code READINTEG(UNCOMMITTED)} and {@code RECOVERY(NONE)} ({@code app/csd/CARDDEMO.CSD:L53},
     * {@code L56}, {@code L59}): the record is held from this read until the unit of work ends, and the
     * source says so out loud - "Could we lock the customer record ?" at
     * {@code app/cbl/COACTUPC.cbl:L3932}, followed at {@code L3939} by
     * {@code SET COULD-NOT-LOCK-CUST-FOR-UPDATE TO TRUE} when it could not. So this method issues the
     * statement with the row lock requested, and <em>requires a unit of work to be open</em>. A lock taken
     * with nothing to hold it is released at once, which would leave the comparison passing while
     * protecting nothing; that is a failure mode worth refusing loudly rather than one worth having
     * quietly.
     *
     * <p>The caller's branch is {@code IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)} at {@code L3934}: on
     * anything else it sets {@code INPUT-ERROR} and, if no message is pending,
     * {@code COULD-NOT-LOCK-CUST-FOR-UPDATE}. {@link ReadResult#isFound()} is that test.
     *
     * <p>No version column, no optimistic-lock annotation and no ETag is introduced. The concurrency check
     * the program performs is the field-by-field comparison
     * {@code 9700-CHECK-CHANGE-IN-REC} ({@code app/cbl/COACTUPC.cbl:L4109-L4193}) already contains, run at
     * {@code L3947-L3948}; this method's job is to make that check effective rather than to substitute a
     * different one.
     *
     * @param custIdAsChar9 the key exactly as the {@code RIDFLD} holds it: exactly {@link #KEY_LENGTH}
     *                      characters, untrimmed and unparsed
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException     if {@code custIdAsChar9} is {@code null}
     * @throws IllegalArgumentException if {@code custIdAsChar9} is not exactly {@link #KEY_LENGTH}
     *                                  characters
     * @throws IllegalStateException    if no unit of work is open, or if the backend presents the dataset
     *                                  with no usable record-image column
     */
    public ReadResult readForUpdate(String custIdAsChar9) {
        String keyImage = requireKeyImage(custIdAsChar9);
        // The key is deliberately absent from the refusal message. This is a wiring diagnostic - which
        // dataset, which operation - and a customer identifier adds nothing to it while putting personal
        // data into a stack trace that may be logged or returned.
        requireUnitOfWork();
        return readKeyed(keyImage, "the record identification field '"
                + SensitiveDiagnostics.maskIdentifier(keyImage) + "' for update", true);
    }

    // =================================================================================================
    // The rewrite - app/cbl/COACTUPC.cbl:L4085-L4091, the only write of any kind against this dataset.
    // =================================================================================================

    /**
     * Rewrites one record in place from a complete record image: the Java form of
     * {@code app/cbl/COACTUPC.cbl:L4085-L4091},
     * <pre>
     * EXEC CICS
     *              REWRITE FILE(LIT-CUSTFILENAME)
     *              FROM(CUST-UPDATE-RECORD)
     *              LENGTH(LENGTH OF CUST-UPDATE-RECORD)
     *              RESP      (WS-RESP-CD)
     *              RESP2     (WS-REAS-CD)
     * END-EXEC
     * </pre>
     *
     * <p><strong>Why the operand is a raw image and not a {@link CustomerRecord}.</strong> The source
     * rewrites from {@code CUST-UPDATE-RECORD}, which is <em>not</em> {@code CUSTOMER-RECORD}: it is a
     * separate five-hundred-byte structure declared inline in {@code COACTUPC}'s working storage at
     * {@code app/cbl/COACTUPC.cbl:L434}, with its own {@code CUST-UPDATE-} field names and a comment that
     * misdescribes it as {@code (RECLN 300)} at {@code L436}. That structure belongs to the account update
     * program, not to the customer package, so this method takes the bytes the caller assembled and does
     * not presume to know which layout assembled them. {@link #rewrite(CustomerRecord)} is offered for the
     * caller that does hold the record model.
     *
     * <p><strong>The record carries its own key.</strong> A CICS {@code REWRITE} rewrites the record read
     * for update, and a COBOL {@code REWRITE} on a randomly accessed indexed file is addressed by the key
     * in the record area; either way the row written is the one whose key equals the leading
     * {@link #KEY_LENGTH} bytes of this image. No separate key parameter exists or should.
     *
     * <p>All {@link #RECORD_LENGTH} declared bytes are written, {@code FILLER X(168)}
     * ({@code app/cpy/CVCUS01Y.cpy:L23}) included, so a rewritten record is byte-identical to the image the
     * caller supplied. An image of any other width is refused as a contract violation rather than padded or
     * truncated, because a five-hundred-byte dataset cannot hold anything else and quietly reshaping the
     * operand would store fields at offsets that are not theirs.
     *
     * <p><strong>It never silently succeeds.</strong> Three outcomes:
     * <ul>
     *   <li>one row rewritten - status {@code '00'}. The COBOL takes its {@code DFHRESP(NORMAL)} arm at
     *       {@code L4095};</li>
     *   <li>no row rewritten - status {@code '23'}, because a rewrite whose key matches nothing is an
     *       invalid-key condition and not a success. The caller reaches
     *       {@code SET LOCKED-BUT-UPDATE-FAILED TO TRUE} at {@code L4098} and the
     *       {@code EXEC CICS SYNCPOINT ROLLBACK} at {@code L4099-L4101};</li>
     *   <li>more than one row would be rewritten - a permanent error status, reported <em>before</em>
     *       anything is written. See below.</li>
     * </ul>
     *
     * <p><strong>Fan-out is precluded, not reported after the fact.</strong> The predicate the rewrite
     * carries selects the rows whose leading {@link #KEY_LENGTH} bytes are the key, and a KSDS primary key
     * is unique - {@code app/jcl/CUSTFILE.jcl:L50} declares {@code KEYS(9 0)} on an {@code INDEXED}
     * cluster - so it names one row. If a deployment's relation does not enforce that uniqueness, the same
     * {@code UPDATE} would replace every matching row with this one record, and discovering that from the
     * affected-row count is discovering it too late: the rows have already been overwritten, and a status
     * returned from there reports damage that the enclosing unit of work then commits on the way out. So
     * the key is required to name exactly one row <em>first</em>, in the same transaction and - when one is
     * open - under the same {@code FOR UPDATE} lock the {@code UPDATE} will use, and the write is not
     * issued at all unless it does. Should the count still come back wrong afterwards, the unit of work is
     * refused rather than reported: see {@link DatasetUnitOfWork#commitRefusal(String, String)}.
     *
     * <p>The cost is one extra bounded {@code SELECT} per rewrite, capped at two rows because "0, 1 or more
     * than 1" is the whole question. That is accepted deliberately: this migration is explicitly not a
     * performance refactoring, and a CICS {@code REWRITE} cannot produce a multi-record outcome for the
     * COBOL to have code for, so the state must not be reachable.
     *
     * @param recordImage the complete record to write, exactly {@link #RECORD_LENGTH} bytes, already
     *                    assembled by the caller
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if {@code recordImage} is not exactly {@link #RECORD_LENGTH} bytes
     * @throws IllegalStateException    if the backend presents the dataset with no usable record-image
     *                                  column, or - as a
     *                                  {@link com.vsergeychik.carddemo.common.DatasetIntegrityException} -
     *                                  if the write replaced more rows than the key selected when it was
     *                                  checked, in which case the unit of work is refused rather than a
     *                                  status returned
     */
    public WriteResult rewrite(byte[] recordImage) {
        byte[] image = requireRecordImage(recordImage);
        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            return reportWrite(unreachable, "describe the customer master dataset '" + datasetName
                    + "' to rewrite a record");
        }
        return rewrite(sql, image);
    }

    /**
     * Rewrites one record in place from the module's record model: {@link #rewrite(byte[])} over
     * {@link CustomerRecord#encode(FixedWidthCodec)}.
     *
     * <p>Offered because {@code CUSTOMER-RECORD} is what {@code CBSTM03B}, {@code COACTVWC} and
     * {@code COACTUPC} all read into, so a caller that read a record, changed it and wants it written back
     * holds the model rather than a byte array. Encoding it here rather than at each call site keeps the
     * code page - and therefore the stored bytes - the same on the write as on the read that produced them.
     *
     * <p>Identical in outcome to {@link #rewrite(byte[])}, which does the work: the record is encoded in
     * this repository's injected code page and the resulting image is exactly
     * {@link #RECORD_LENGTH} bytes by construction, {@code FILLER} included.
     *
     * @param record the record to write, complete and already mutated by the caller
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException  if {@code record} is {@code null}
     * @throws IllegalStateException if the backend presents the dataset with no usable record-image column,
     *                               or - as a
     *                               {@link com.vsergeychik.carddemo.common.DatasetIntegrityException} - if
     *                               the write replaced more rows than the key selected when it was checked
     */
    public WriteResult rewrite(CustomerRecord record) {
        Objects.requireNonNull(record, "A record is required to rewrite it; a COBOL REWRITE writes the "
                + "record area, and there is no such thing as rewriting nothing");
        return rewrite(record.encode(codec));
    }

    /**
     * Rewrites one record image against already-resolved statements.
     *
     * <p>The single body both public rewrites reach, so the fan-out precaution and the outcome ladder
     * cannot drift between them.
     *
     * @param sql   the resolved statements
     * @param image the complete record to write, already validated as {@link #RECORD_LENGTH} bytes
     * @return the discriminated outcome; never {@code null}
     */
    private WriteResult rewrite(Statements sql, byte[] image) {
        String keyImage = keyImageOf(image);
        String keyPattern = asPrefixPattern(keyImage);
        String maskedKey = SensitiveDiagnostics.maskIdentifier(keyImage);

        // Establish that the key names exactly one row BEFORE any row is replaced. Where a unit of work is
        // open the probe takes the same row lock the UPDATE will use, so nothing can change between the
        // two; where none is open nothing can be atomic anyway, and the probe is still what keeps a
        // fan-out from being discovered only from the affected-row count, after the damage.
        boolean locking = DatasetUnitOfWork.active();
        int matching;
        try {
            matching = matchingRowCount(locking ? sql.selectByKeyForUpdate() : sql.selectByKey(),
                    keyPattern);
        } catch (DataAccessException rejected) {
            return reportWrite(rejected, "establish how many rows the key of a record selects in the "
                    + "customer master dataset '" + datasetName + "' before rewriting it");
        }
        if (matching == 0) {
            // An invalid-key condition: there is no such record to rewrite. Reported without issuing the
            // UPDATE, which is the same outcome the UPDATE would have reported and one statement fewer.
            return WriteResult.notFound();
        }
        if (matching > 1) {
            // Nothing has been written, and nothing will be. A KSDS primary key is unique, so a relation in
            // which this key selects several rows is not the dataset the copybook describes; issuing the
            // rewrite would replace all of them with this one record.
            LOG.error("The key of customer " + maskedKey + " selects more than one row of dataset '"
                    + datasetName + "'; a KSDS primary key is unique - app/jcl/CUSTFILE.jcl:L50 declares "
                    + "KEYS(9 0) - so the rewrite is being refused before it is issued and file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " reported to the caller. No row "
                    + "has been changed.");
            return WriteResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
        }

        int rewritten;
        try {
            PreparedStatementSetter binder = parameters -> {
                recordImageForm.bindImage(parameters, 1, image, codec.charset());
                recordImageForm.bindOperand(parameters, 2, keyPattern, codec.charset());
            };
            rewritten = jdbcTemplate.update(sql.rewrite(), binder);
        } catch (DataAccessException rejected) {
            return reportWrite(rejected, "rewrite a record in the customer master dataset '" + datasetName
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
                "The rewrite of customer " + maskedKey + " in dataset '" + datasetName + "'",
                rewritten + " rows were replaced where the key selected exactly one when it was checked "
                        + "under " + (locking ? "a row lock" : "no row lock, because no unit of work was "
                        + "open"));
    }

    /**
     * Counts the rows a keyed predicate selects, stopping as soon as the answer is known.
     *
     * <p>The question a rewrite has to answer is not "how many rows" but "none, one, or more than one", so
     * the statement is capped at two rows and the returned count saturates at two. A dataset whose key
     * column is not unique therefore costs two row transfers to detect rather than the whole relation, and
     * the normal case costs exactly one - the same as the keyed read that preceded it.
     *
     * <p>When the statement carries {@code FOR UPDATE} the rows it returns are locked for the rest of the
     * transaction, which is precisely why this is the probe a rewrite uses: the count and the write then see
     * the same rows.
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
        // check exists so that a null could not become a NullPointerException at the unboxing instead of a
        // diagnosable one here.
        Objects.requireNonNull(counted, "The row counter returns a count, never null");
        return counted;
    }

    // =================================================================================================
    // The keyed read, shared by the batch and the online form and by the plain and the locking read.
    // =================================================================================================

    /**
     * Reads the single record whose key equals {@code keyImage}, reporting the outcome.
     *
     * <p>Shared by {@link #readByKey(long)}, {@link #readByKey(String)} and
     * {@link #readForUpdate(String)}, because the COBOL keyed read is one operation reached from two views
     * of the key and with or without a lock. The predicate matches a record image that <em>begins with</em>
     * the key, which for a fixed-width record whose leading field is the key is exactly "the record with
     * this key". Only one row is transferred: the row limit is set on the statement.
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
            return reportRead(unreachable, "describe the customer master dataset '" + datasetName
                    + "' to read " + subject);
        }
        return readKeyed(forUpdate ? sql.selectByKeyForUpdate() : sql.selectByKey(), keyImage, subject);
    }

    /**
     * Reads one record by key against an already-composed statement.
     *
     * <p>The form {@link CustomerFile} uses: an open handle has already resolved the dataset's shape, so it
     * supplies the statement rather than describing the dataset a second time. One body serves both entry
     * points, so a keyed read cannot behave differently depending on which one issued it.
     *
     * @param statement the composed keyed select, with or without {@code FOR UPDATE}
     * @param keyImage  the key exactly as the record stores it
     * @param subject   how to name the operation in a diagnostic - never the key's value
     * @return the discriminated outcome; never {@code null}
     */
    private ReadResult readKeyed(String statement, String keyImage, String subject) {
        List<byte[]> rows;
        try {
            rows = jdbcTemplate.query(firstRowMatching(statement, asPrefixPattern(keyImage)),
                    recordImageMapper());
        } catch (DataAccessException translated) {
            return reportRead(translated, "read " + subject + " from the customer master dataset '"
                    + datasetName + "'");
        }

        // As in the browse, the list itself is never null, so emptiness is the whole of the test.
        if (rows.isEmpty()) {
            // The INVALID KEY condition, and DFHRESP(NOTFND) online. Reported, never thrown, so the caller
            // can compose its own 'CustId: … not found' message - see readByKey(long).
            return ReadResult.notFound();
        }
        byte[] recordImage = rows.get(0);
        if (recordImage == null) {
            LOG.error("The customer master dataset '" + datasetName + "' presented " + subject
                    + " with no record image at column position " + RECORD_IMAGE_COLUMN_INDEX
                    + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " to the caller");
            // No backend refusal, so nothing to diagnose - but the condition does have a determinate CICS
            // counterpart: a row that is present and unreadable is an invalid request.
            return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
        }
        return decoded(recordImage, subject);
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
     * that carried them - and the dataset name. Deliberately <strong>not</strong> logged: the key, the
     * record image, or any part of either. A customer record holds a name, a full address, a social
     * security number and a government-issued identifier, so a log line that echoed it would put all of
     * them into a file that is read by more people, retained for longer, and protected less than the
     * dataset itself.
     *
     * <p>The exception itself is <strong>not</strong> passed to the logger either, and that is the part
     * worth stating plainly because it looks like a loss. A driver's message is prose the backend composed
     * around the values it refused, so {@code value '123456789' rejected} is an ordinary thing for it to
     * say; handing the {@link Throwable} to the logger emits that text and its whole cause chain verbatim
     * (CWE-532), and a control character anywhere in it splits the entry in two (CWE-117). Sanitising the
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
     * <p><strong>Why a name has to be discovered at all.</strong> Every dataset in this module is reached as
     * a single-column relation whose one column holds the record image, and a sequential read can address
     * that column purely by position. A keyed read and a rewrite cannot: SQL permits an ordinal in
     * {@code ORDER BY} but not in a {@code WHERE} or a {@code SET} clause. The name is therefore taken from
     * the result-set metadata of the probe statement - <em>discovered from the backend</em>, never invented
     * here, never defaulted, and never added as a configuration key, because a name this file made up would
     * be exactly the kind of unverifiable literal the migration forbids.
     *
     * <p>The probe is read-only: its predicate is false on every row, so the relation is described without
     * any of it being transferred. That is what makes it usable against a driver this build cannot exercise,
     * and it is a genuine dataset-scoped check rather than a bare connection test - an absent dataset fails
     * here, which is exactly what an {@code OPEN} would report.
     *
     * <p><strong>Nothing is cached.</strong> A resolved shape held on this singleton would be shared mutable
     * state, and the coupling between such a cache and the open/close lifecycle is what would let one
     * execution's {@code OPEN} pull the shape out from under another's browse. The cost is one metadata round
     * trip per repository-level operation, and it is accepted deliberately: this migration is explicitly not
     * a performance refactoring, and a caller that minds pays it once by opening a {@link CustomerFile},
     * whose operations reuse the shape its {@code OPEN} resolved.
     *
     * <p>Package-visible so this class's own tests can assert the composed text and the discovery behaviour
     * without reaching around the class. It resolves afresh on every call and stores nothing: the returned
     * value is deeply immutable, so handing it out grants nothing that can be altered.
     *
     * @return the composed statements; never {@code null}
     * @throws DataAccessException   if the dataset cannot be described - an I/O outcome, translated into a
     *                               status by every caller of this method
     * @throws IllegalStateException if the dataset is described but presents no usable record-image column,
     *                               which is a contract violation: the whole module addresses a dataset as a
     *                               record-image relation, and one that is not cannot be read or written at
     *                               all
     */
    Statements resolveStatements() {
        ResultSetExtractor<String> columnNameExtractor =
                CustomerRepository::extractRecordImageColumnName;
        String columnName = jdbcTemplate.query(relation.describeStatement(), columnNameExtractor);
        return Statements.over(relation, requireUsableColumnName(columnName));
    }

    /**
     * Resolves the statements for one operation, reporting an unreachable dataset as {@code null}.
     *
     * <p>Used where the outcome of failing to describe the dataset is a status rather than a thrown
     * exception and there is no record to carry - the close, in practice - so the translation lives in one
     * place instead of being repeated at each such site.
     *
     * @param subject how to name the operation in a diagnostic
     * @return the resolved statements, or {@code null} when the dataset could not be described
     * @throws IllegalStateException if the dataset is reachable but presents no usable record-image column
     */
    private Statements statementsFor(String subject) {
        try {
            return resolveStatements();
        } catch (DataAccessException unreachable) {
            logRefusal(unreachable, "describe the customer master dataset '" + datasetName + "' for "
                    + subject);
            return null;
        }
    }

    /**
     * Refuses a read for update that is not inside a unit of work.
     *
     * <p>A {@code FOR UPDATE} row lock lives for the length of the transaction that took it. Under
     * auto-commit that is the length of the statement, so the lock is gone before the caller can compare the
     * record and rewrite it - and the caller would never know. {@code READ ... UPDATE} in CICS cannot behave
     * that way, because a CICS task always has a unit of work, so there is no legacy behaviour to reproduce
     * here and nothing to report as a file status: this is a defect in the Java caller's wiring and is
     * reported as one.
     *
     * @throws IllegalStateException if no transaction is active on the calling thread
     */
    private void requireUnitOfWork() {
        // One precondition, one diagnostic, in the module-wide checker - so an operator sees the same
        // message whichever dataset refused, and the COBOL evidence travels in the operation name rather
        // than in a second copy of the wording.
        DatasetUnitOfWork.requireActive("A read for update of the customer master, which "
                + "EXEC CICS READ ... UPDATE holds for the unit of work "
                + "(app/cbl/COACTUPC.cbl:L3921-L3930, and the 'Could we lock the customer record ?' test "
                + "at L3932) so that 9700-CHECK-CHANGE-IN-REC can compare and REWRITE can run - or use "
                + "readByKey(String) when no lock is wanted", datasetName);
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
     * <p>Position {@value #RECORD_IMAGE_COLUMN_INDEX} and never a column name here, for the reason given on
     * this class: a dataset carrying no relational metadata is presented as a single record-image column.
     * Whether that column is read as characters or as bytes is {@link RecordImageForm}'s decision and not
     * this method's. The value is <em>not</em> trimmed: the trailing 168 bytes of this record are
     * {@code FILLER} spaces and they are part of it.
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
     * clause, which keeps every statement in this class free of dialect syntax - no fetch-first and no offset
     * appears anywhere. A driver that declines to honour the limit costs efficiency and nothing else: the
     * first row is taken and the rest ignored, so the outcome is identical either way.
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
     * <p>The same binding as {@link #firstRowMatching(String, String)} - the pattern is a comparison operand
     * and is bound as one - with the row cap left to the caller, because the pre-rewrite probe needs to see a
     * second row to know there is one and every other statement in this class needs at most the first.
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
     * the comparison then runs against the column in its own representation, and "the next record after this
     * one" means the same thing on both sides of the operator.
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

    /**
     * Caps a prepared statement at one row, so no more than one can ever matter.
     *
     * @param statement the statement to cap
     * @return {@code statement}, capped
     * @throws SQLException if the driver refuses the limit
     */
    private static PreparedStatement limited(PreparedStatement statement) throws SQLException {
        statement.setMaxRows(1);
        return statement;
    }

    /**
     * Decodes a stored record image, or reports that it is not a customer record.
     *
     * <p><strong>The width is required, not repaired.</strong> {@code app/cpy/CVCUS01Y.cpy:L2} declares
     * {@code RECLN 500}, three file descriptions corroborate it as nine plus {@code X(491)}, and every record
     * of {@code app/data/ASCII/custdata.txt} measures exactly that - so a row of any other width means the
     * backend is not serving this layout. Widening a short one to the declared width with spaces would be
     * defensible if the missing bytes were certainly the trailing {@code FILLER X(168)}
     * ({@code app/cpy/CVCUS01Y.cpy:L23}), and they are not: a row that lost bytes anywhere else, or that was
     * written against a different layout, pads into a record whose fields decode from offsets that are not
     * theirs. {@code CUST-FICO-CREDIT-SCORE} read from the wrong span is still a number, and a caller cannot
     * tell it from a real one - so the repair would convert a diagnosable failure into plausible data, which
     * is the one outcome worse than the failure.
     *
     * <p>An over-long image is rejected for the same reason, which makes the two symmetrical: the row either
     * is 500 bytes or it is not a record. The width is reported as {@link FileStatus#LENGERR} - the response
     * CICS gives when a record does not fit its receiver - and the actual width is named in the log line
     * rather than carried as a reason code, because a byte count is not a CICS reason code.
     *
     * <p>The decode itself is delegated to the record model, by absolute offset against the copybook layout,
     * in the injected code page, retaining every byte verbatim - which is what makes decoding and re-encoding
     * a stored record byte-identical.
     *
     * @param recordImage the stored image, exactly as the configured representation presented it
     * @param subject     how to name the row in a diagnostic - never its content
     * @return the successful outcome carrying the decoded record, or a permanent-error outcome whose CICS
     *         response is {@link FileStatus#LENGERR}
     */
    private ReadResult decoded(byte[] recordImage, String subject) {
        if (recordImage.length != RECORD_LENGTH) {
            LOG.error("The customer master dataset '" + datasetName + "' presented " + subject + " as "
                    + recordImage.length + " byte(s), but CUSTOMER-RECORD is declared " + RECORD_LENGTH
                    + " bytes by app/cpy/CVCUS01Y.cpy; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than decoding fields from "
                    + "offsets that would not be theirs");
            return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.LENGERR));
        }
        // The stored bytes, decoded once, character for character, and carried alongside the decoded
        // fields. READ ... INTO CUSTOMER-RECORD moves the whole record area, so
        // DISPLAY CUSTOMER-RECORD (app/cbl/CBCUS01C.cbl:78 and :96) writes bytes the record model cannot
        // reproduce: CUSTOMER-RECORD ends with a FILLER that holds no field, and rendering the record
        // through CustomerRecord.recordImage allocates a fresh area and so emits that span as spaces
        // whatever the row held. The decode is strict - an unmappable byte is refused rather than
        // replaced - so this String is the row's bytes or nothing.
        return ReadResult.found(CustomerRecord.decode(recordImage, codec),
                codec.decodeImage(recordImage, RECORD_IMAGE_SUBJECT));
    }

    // =================================================================================================
    // Key rendering and extraction. Both go through the module's PIC 9 move rule or the record's own
    // layout descriptor, so this class states no offset and no width of its own.
    // =================================================================================================

    /**
     * Renders a customer identifier as the stored nine-digit key image.
     *
     * <p>The {@code PIC 9} move rule and nothing else: zero-filled on the left, truncated on the
     * <strong>left</strong> when the sending value is wider, and refused when it is negative because
     * {@code PIC 9} has no sign position. It is the codec's rule rather than a local one precisely so that
     * {@code String.format}, {@code String.valueOf} and manual concatenation - all of which would pad or
     * truncate the wrong end - cannot appear here.
     *
     * @param custId the customer identifier; must not be negative
     * @return exactly {@link #KEY_LENGTH} digits
     * @throws IllegalArgumentException if {@code custId} is negative
     */
    private String keyImageOf(long custId) {
        return codec.movePic9(custId, KEY_LENGTH);
    }

    /**
     * Extracts the stored key image from a complete record image.
     *
     * <p>The span is the record model's own descriptor for {@code CUST-ID}, so the offset and the width are
     * the copybook's rather than this class's. The bytes are decoded strictly in the injected code page: a
     * byte the code page does not define is a defect in the image, and substituting a replacement character
     * would compose a predicate that addresses a record nobody stored.
     *
     * @param recordImage the complete record image, already validated as {@link #RECORD_LENGTH} bytes
     * @return exactly {@link #KEY_LENGTH} characters
     * @throws IllegalArgumentException if the key span cannot be decoded under the injected code page
     */
    private String keyImageOf(byte[] recordImage) {
        return FixedWidthRecord.decodeStrictly(recordImage, KEY_SPAN.offset(), KEY_SPAN.length(),
                codec.charset(), "the CUST-ID key of a customer record image");
    }

    // =================================================================================================
    // Pure helpers. Private, and static wherever no instance state is involved: no static state is
    // introduced - every one of them is a function of its arguments alone.
    // =================================================================================================

    /**
     * Renders a key image as the escaped {@code LIKE} pattern that matches the record carrying it.
     *
     * <p>Delegated to {@link KeySpan#pattern(String)}, which is the module's one such renderer: the key is
     * escaped there rather than trusted, because an online {@code RIDFLD} is a {@code PIC X(09)} character
     * field ({@code app/cbl/COACTVWC.cbl:L76-L77}) carrying whatever the screen supplied, and an unescaped
     * {@code _} in it would match any byte and could return - or, through the rewrite that shares the
     * predicate, overwrite - a different customer's record.
     *
     * @param keyImage the stored key image, exactly {@link #KEY_LENGTH} characters
     * @return the escaped pattern
     */
    private static String asPrefixPattern(String keyImage) {
        return KEY_SPAN.pattern(keyImage);
    }

    /**
     * Resolves one configured binding and proves it describes the customer record.
     *
     * @param datasetBindings the configured catalogue
     * @param ddName          the key to resolve, {@link #CICS_FILE_NAME} or {@link #BATCH_DD_NAME}
     * @return the resolved binding
     * @throws IllegalStateException if the key is unconfigured, or if the binding declares a record width
     *                               other than {@link #RECORD_LENGTH} or a key width other than
     *                               {@link #KEY_LENGTH}
     */
    private static DatasetBinding requireCustomerGeometry(DatasetBindings datasetBindings, String ddName) {
        DatasetBinding binding = datasetBindings.binding(ddName);
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + ddName + "' declares a "
                    + "record length of " + binding.recordLength() + ", but the customer record is "
                    + RECORD_LENGTH + " bytes - app/cpy/CVCUS01Y.cpy:L2 reads RECLN 500, "
                    + "app/cbl/CBCUS01C.cbl:L37-L40 declares FD-CUST-ID PIC 9(09) plus FD-CUST-DATA "
                    + "PIC X(491) independently, and app/jcl/CUSTFILE.jcl:L51 defines the cluster "
                    + "RECORDSIZE(500 500). This repository addresses the record by absolute offset, so a "
                    + "differently-sized record would misplace every field after the first. Correct "
                    + "carddemo.datasets." + ddName + ".record-length to " + RECORD_LENGTH + ".");
        }
        Integer declaredKeyLength = binding.keyLength();
        // The startup validator requires a keyed entry to declare a key width, so an absent one here means
        // the binding was built by hand and is accepted; a PRESENT one that disagrees with the copybook is
        // a defect, because a keyed read would then address a span that is not the key.
        if (declaredKeyLength != null && declaredKeyLength != KEY_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + ddName + "' declares a key "
                    + "length of " + declaredKeyLength + ", but the customer master key is " + KEY_LENGTH
                    + " bytes - CUST-ID PIC 9(09) at app/cpy/CVCUS01Y.cpy:L5, KEYS(9 0) at "
                    + "app/jcl/CUSTFILE.jcl:L50, and the value app/cbl/COACTUPC.cbl:L3925 passes as "
                    + "KEYLENGTH. Correct carddemo.datasets." + ddName + ".key-length to " + KEY_LENGTH
                    + ", or omit it.");
        }
        return binding;
    }

    /**
     * Requires the CICS file and the batch DD to name the same dataset.
     *
     * <p>They address one dataset in the legacy system: {@code app/csd/CARDDEMO.CSD:L50-L52} defines the CICS
     * file over the customer master KSDS and {@code app/jcl/READCUST.jcl:L9-L10} binds the batch DD over the
     * same one. A deployment that pointed them at two different datasets would give the online and the batch
     * programs different data while every parity case assumed they shared it, so the disagreement is refused
     * here rather than discovered later as an unexplained diff.
     *
     * @param cicsBinding  the binding resolved for {@link #CICS_FILE_NAME}
     * @param batchBinding the binding resolved for {@link #BATCH_DD_NAME}
     * @throws IllegalStateException if the two bindings name different datasets
     */
    private static void requireSameDataset(DatasetBinding cicsBinding, DatasetBinding batchBinding) {
        if (!Objects.equals(cicsBinding.dsname(), batchBinding.dsname())) {
            throw new IllegalStateException("The dataset bindings for DD names '" + CICS_FILE_NAME
                    + "' and '" + BATCH_DD_NAME + "' name different datasets, but they are two names for "
                    + "one customer master: app/csd/CARDDEMO.CSD:L50-L52 defines the CICS file and "
                    + "app/jcl/READCUST.jcl:L9-L10 binds the batch DD over the same dataset. Point "
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
                    + "' declares no dataset name. Set carddemo.datasets." + CICS_FILE_NAME + ".dsname; "
                    + "this repository composes its statements from configuration alone and hard-codes no "
                    + "dataset name.");
        }
        // The grammar itself lives in DatasetRelation, so the rule about what a dataset name may contain is
        // stated once for every repository rather than restated - differently - in each.
        return DatasetRelation.requireDatasetName(candidate);
    }

    /**
     * Validates the discovered record-image column name and returns it verbatim.
     *
     * <p>An absent, blank or unusable name means the backend is not presenting this dataset as the
     * record-image relation the whole module addresses. That is a contract violation and not an I/O outcome:
     * there is no file status for "the dataset has the wrong shape", and reporting one would send a job down
     * its abend path with a misleading reason.
     *
     * @param candidate the name the metadata probe reported
     * @return {@code candidate}, unchanged
     * @throws IllegalStateException if {@code candidate} is {@code null}, blank, or contains a control
     *                               character
     */
    private static String requireUsableColumnName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The backend describes no column at position "
                    + RECORD_IMAGE_COLUMN_INDEX + " for the customer master dataset, so the record image "
                    + "cannot be addressed. Every CardDemo dataset is reached as a relation whose column at "
                    + "that position holds the whole fixed-width record image; a driver or a seeded dataset "
                    + "that presents no such column cannot be read or written by this module at all.");
        }
        requireNoControlCharacter(candidate, "The record-image column name reported by the backend");
        return candidate;
    }

    /**
     * Rejects a name containing a control character.
     *
     * <p>A control character cannot appear in a legitimate identifier, and letting one through would corrupt
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
                        + index + "; an identifier cannot contain one, and it would corrupt the composed "
                        + "statements. Correct the value.");
            }
        }
    }

    /**
     * Validates a nine-character key image and returns it verbatim.
     *
     * <p>The width is the whole point of a fixed-width key, so it is checked rather than assumed: the
     * {@code RIDFLD} is {@code PIC X(09)} and {@code KEYLENGTH} is its length
     * ({@code app/cbl/COACTUPC.cbl:L3924-L3925}), so a key of any other width is not the key this dataset
     * has. The content is deliberately <em>not</em> validated - a screen-derived key may hold spaces or
     * non-digits, and the COBOL read simply reports that no such record exists.
     *
     * @param candidate the caller's key image
     * @return {@code candidate}, unchanged
     * @throws NullPointerException     if {@code candidate} is {@code null}
     * @throws IllegalArgumentException if {@code candidate} is not exactly {@link #KEY_LENGTH} characters
     */
    private static String requireKeyImage(String candidate) {
        Objects.requireNonNull(candidate, "A key image is required to read a customer record; the RIDFLD is "
                + "a PIC X(09) field and is never absent, only spaces");
        if (candidate.length() != KEY_LENGTH) {
            throw new IllegalArgumentException("The customer master key is declared PIC X(0" + KEY_LENGTH
                    + ") but was given " + candidate.length() + " character(s). A fixed-width key carries "
                    + "its padding, so it is never trimmed and never short - render a customer identifier "
                    + "with CustomerRecord.custIdImage(Charset), or pass the identifier itself to "
                    + "readByKey(long).");
        }
        return candidate;
    }

    /**
     * Validates a complete record image and returns it defensively copied.
     *
     * <p>The width is required for the reason {@link #decoded(byte[], String)} gives on the way in: a
     * five-hundred-byte dataset holds five-hundred-byte records, and an operand of any other width is not one
     * - padding or truncating it would store fields at offsets that are not theirs.
     *
     * <p>The copy is what makes the operand immutable for the duration of the write. The array belongs to the
     * caller, the fan-out probe and the {@code UPDATE} are two statements apart, and an array mutated between
     * them would write bytes the probe never authorised.
     *
     * @param candidate the caller's record image
     * @return a private copy of {@code candidate}
     * @throws NullPointerException     if {@code candidate} is {@code null}
     * @throws IllegalArgumentException if {@code candidate} is not exactly {@link #RECORD_LENGTH} bytes
     */
    private static byte[] requireRecordImage(byte[] candidate) {
        Objects.requireNonNull(candidate, "A record image is required to rewrite a customer record; a COBOL "
                + "REWRITE writes the record area, and there is no such thing as rewriting nothing");
        if (candidate.length != RECORD_LENGTH) {
            throw new IllegalArgumentException("A customer record image is exactly " + RECORD_LENGTH
                    + " bytes - app/cpy/CVCUS01Y.cpy:L2 reads RECLN 500 and app/jcl/CUSTFILE.jcl:L51 defines "
                    + "the cluster RECORDSIZE(500 500) - but " + candidate.length + " byte(s) were given. "
                    + "Every declared span is written including FILLER X(168), so an image of another width "
                    + "is not this record; build it with CustomerRecord.encode(Charset), or with the same "
                    + "layout COACTUPC's CUST-UPDATE-RECORD declares.");
        }
        return candidate.clone();
    }

    // =================================================================================================
    // The composed statements.
    // =================================================================================================

    /**
     * The five statements this repository issues, composed once the record-image column is known.
     *
     * <p>An immutable value, package-visible so that a unit test can assert the composed text directly rather
     * than inferring it from a database round trip. It is not part of the public contract: no caller outside
     * this package can name the type.
     *
     * <p>Every statement is confined to core SQL - a delimited identifier, {@code ORDER BY}, a comparison,
     * {@code LIKE … ESCAPE}, {@code FOR UPDATE} and a single-column {@code UPDATE} - and none of them names a
     * column list, because this migration introduces no schema and therefore has no column names of its own
     * to name.
     *
     * @param selectFirst the first read of a browse: every record in ascending key order, limited to one row
     *                    by the statement. No predicate, because an {@code OPEN INPUT} positions before the
     *                    first record
     * @param selectNext  every later read of a browse: the first record whose image sorts strictly after the
     *                    one already returned, in ascending key order. The parameter is the previous
     *                    <em>image</em> and not its key
     * @param selectByKey the keyed read: the record whose image begins with the key
     * @param selectByKeyForUpdate the keyed read with the row lock the CICS {@code READ ... UPDATE} takes,
     *                    valid only inside a unit of work
     * @param rewrite     the rewrite: replace the image of the record whose image begins with the key
     */
    record Statements(String selectFirst,
                      String selectNext,
                      String selectByKey,
                      String selectByKeyForUpdate,
                      String rewrite) {

        /**
         * Composes the statements over one dataset and one record-image column.
         *
         * <p>Every one of them comes from {@link DatasetRelation}, which also remembers the validated column
         * name. That is the point: the text of a browse, a keyed read, a locking read and a rewrite is decided
         * in one class for every dataset in the module, so a divergence between two repositories is not
         * something a reader has to go looking for.
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
                    relation.rewriteByKey(recordImageColumn));
        }
    }

    /**
     * The metadata probe statement, exposed to this class's own tests so the composed text can be asserted
     * without a backend. Package-visible: a construction detail, not part of the public contract.
     *
     * @return the statement that describes the dataset without transferring a row
     */
    String columnProbeSql() {
        return relation.describeStatement();
    }

    // =================================================================================================
    // The opened file. One instance per OPEN INPUT, holding everything that open establishes: the status,
    // the resolved shape and the browse position. This is where the state a singleton must not hold lives.
    // =================================================================================================

    /**
     * One opened customer master file: the Java form of the {@code FD} a program opens, reads and closes.
     *
     * <p>The Java form of the file {@code app/cbl/CBCUS01C.cbl} opens at {@code :120}, reads at {@code :93}
     * and closes at {@code :138}, driving its whole program from {@code :72}:
     * <pre>
     * try (CustomerRepository.CustomerFile file = repository.openInput()) {
     *     if (!FileStatus.isOk(file.openStatus())) {          // IF CUSTFILE-STATUS = '00' ... ELSE
     *         throw AbendException.standard("CBCUS01C", file.openApplResult());
     *     }
     *     for (ReadResult next = file.readNext();                        //  PERFORM UNTIL
     *             !next.isEndOfFile();                                   //  END-OF-FILE = 'Y'
     *             next = file.readNext()) {
     *         if (next.isOther()) {                          // ERROR READING CUSTOMER FILE
     *             throw AbendException.standard("CBCUS01C", next.applResult());
     *         }
     *         display(next.customer().orElseThrow());         // DISPLAY CUSTOMER-RECORD
     *     }
     * }
     * </pre>
     * The abend is the caller's, as it is in the COBOL: the paragraph that tests the status is the paragraph
     * that decides, and this handle only reports.
     *
     * <p>{@code app/cbl/CBTRN01C.cbl} uses the same handle for less: it opens at {@code :273}, never reads,
     * and closes at {@code :381}. Both guards it tests are reachable through {@link #openStatus()} and
     * {@link #closeFile()}, so that behaviour survives translation exactly as written.
     *
     * <p><strong>Why this type exists.</strong> A file position and the shape resolved for an open both belong
     * to one program execution. Held on the {@link CustomerRepository} singleton they would be shared by every
     * concurrent execution, and one execution's {@code OPEN} would reposition another's browse and discard the
     * shape it was using mid-read. Here each execution has its own, and the repository has none.
     *
     * <p><strong>Not thread safe, by design.</strong> A handle stands for one file position and is used by
     * whoever opened it, exactly as a COBOL {@code FD} is used by the program that opened it. Sharing one
     * across threads would be the concurrency equivalent of two programs sharing one {@code FD}. Opening a
     * second handle is free and is the correct answer.
     *
     * <p><strong>Read-only, because every open of this dataset is.</strong> The keyed read is offered here as
     * well as on the repository, because {@code CBSTM03B} reads by key under its own {@code OPEN INPUT}
     * ({@code app/cbl/CBSTM03B.CBL:L184} then {@code L190}). A read for update and a rewrite are <em>not</em>
     * offered: the dataset is never opened {@code I-O}, so an update under a handle is a state the estate
     * cannot reach, and the online update path issues no open at all and uses the repository directly.
     */
    public static final class CustomerFile implements AutoCloseable {

        /** The repository that opened this file, for its template, its codec and its dataset identity. */
        private final CustomerRepository repository;

        /**
         * The status the {@code OPEN} reported: {@link FileStatus#OK}, or
         * {@link CustomerRepository#PERMANENT_ERROR_STATUS} when the dataset could not be described.
         */
        private final String openStatus;

        /**
         * The statements resolved for this open, or {@code null} when the open failed.
         *
         * <p>Resolved once, by the {@code OPEN} itself, which is what an {@code OPEN} is for: every later
         * operation on this handle is spared a metadata round trip. Deeply immutable, so nothing here can be
         * perturbed after the open.
         */
        private final Statements statements;

        /**
         * The browse position: the image of the record {@link #readNext()} returned last, or {@code null} for
         * "positioned before the first record".
         *
         * <p>The Java form of the file position a COBOL {@code OPEN INPUT} establishes and each {@code READ}
         * advances. It is the full record image rather than just the key, and that is load-bearing: a bare
         * nine-byte key would compare as <em>less than</em> the very record it came from -
         * {@code '000000001…'} sorts after {@code '000000001'} - so a browse positioned by key alone would
         * return the same record for ever. The full image excludes it strictly, and because a KSDS key is
         * unique the comparison always resolves inside the leading nine bytes.
         *
         * <p>Held as the stored <strong>bytes</strong>, exactly as the configured representation presented
         * them, and bound back as an image rather than as text. Re-encoding a decoded record here would make
         * the comparison operand a reconstruction rather than the row it came from, which is only harmless
         * while every reconstruction happens to be byte-identical.
         */
        private byte[] browsePosition;

        /** Whether {@link #closeFile()} has been called. */
        private boolean closed;

        /**
         * The status the first {@link #closeFile()} reported, or {@code null} until one has been.
         *
         * <p>Remembered so that closing twice reports the same outcome both times. Recomputing it would
         * make the second answer differ from the first - a file whose open never succeeded would report
         * its failure once and then {@code '00'} - and a caller holding the second answer would believe a
         * close succeeded that did not. A {@code CLOSE} of a file that is not open is not a success, and it
         * does not become one by being asked again.
         */
        private String closeStatus;

        /**
         * Constructed only by {@link CustomerRepository#openInput()}, which is what guarantees that a
         * successful open always arrives with its resolved shape and a failed one never does.
         *
         * @param repository the opening repository
         * @param openStatus the status the open reported
         * @param statements the resolved statements for a successful open, or {@code null} for a failed one
         */
        private CustomerFile(CustomerRepository repository, String openStatus, Statements statements) {
            this.repository = repository;
            this.openStatus = openStatus;
            this.statements = statements;
            this.browsePosition = null;
            this.closed = false;
            this.closeStatus = null;
        }

        /**
         * The status the {@code OPEN} reported: the value {@code app/cbl/CBCUS01C.cbl:L121} tests before
         * moving {@code 0} or {@code 12} into {@code APPL-RESULT}, and the value
         * {@code app/cbl/CBTRN01C.cbl:L274} tests for the open it never reads from.
         *
         * @return {@link FileStatus#OK} or {@link CustomerRepository#PERMANENT_ERROR_STATUS}; never
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
         * The {@code APPL-RESULT} value the open's guard moves: {@code 0} on success and {@code 12} otherwise.
         *
         * <p>{@code app/cbl/CBCUS01C.cbl:L121-L125} and {@code app/cbl/CBTRN01C.cbl:L274-L278}. Surfaced so a
         * job can hand the same number to its abend that the COBOL would have, rather than deriving it again.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link CustomerRepository#APPL_RESULT_FATAL}
         */
        public int openApplResult() {
            return FileStatus.isOk(openStatus) ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }

        /**
         * The dataset this handle reads.
         *
         * @return the configured customer master dataset name; never {@code null} and never blank
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
        // 1000-CUSTFILE-GET-NEXT - app/cbl/CBCUS01C.cbl:L92-L116.
        // =============================================================================================

        /**
         * Reads the next record in ascending key order: the Java form of
         * {@code READ CUSTFILE-FILE INTO CUSTOMER-RECORD} ({@code app/cbl/CBCUS01C.cbl:L93}) and of the guard
         * that classifies its status at {@code L94-L103}.
         *
         * <p>Four reported outcomes, and they are the ones the COBOL enumerates:
         * <ul>
         *   <li><strong>found</strong> - status {@code '00'}, carrying the decoded record. The COBOL moves
         *       {@code 0} to {@code APPL-RESULT} at {@code L95} and the caller displays the record
         *       ({@code L78} and {@code L96});</li>
         *   <li><strong>end of file</strong> - status {@code '10'}, carrying no record. The COBOL moves
         *       {@code 16} at {@code L99}, which is {@link FileStatus#APPL_EOF}, and the caller sets
         *       {@code MOVE 'Y' TO END-OF-FILE} at {@code L108} and leaves its loop. Reported repeatedly and
         *       idempotently: once at end of file, every further call reports it again, which is faithful
         *       because the COBOL loop stops on the flag and never resumes;</li>
         *   <li><strong>other</strong> carrying the <em>open's</em> status, when the open failed - so a caller
         *       that ignored {@link #openStatus()} still cannot mistake a dataset it never reached for one
         *       that was empty;</li>
         *   <li><strong>other</strong> carrying {@link CustomerRepository#PERMANENT_ERROR_STATUS} for an I/O
         *       failure, a row whose record image is absent, or a row that is not
         *       {@link CustomerRepository#RECORD_LENGTH} bytes wide. The COBOL moves {@code 12} at
         *       {@code L101} and the caller displays {@code 'ERROR READING CUSTOMER FILE'} ({@code L110}),
         *       renders the status and abends.</li>
         * </ul>
         *
         * <p><strong>Ordering.</strong> The statement carries an explicit ascending {@code ORDER BY} over the
         * record-image column, and the first read of a browse uses a statement with no predicate at all while
         * every later read asks for the first image strictly greater than the one before it. That is a keyed
         * browse - the same "position, then read the next greater key" the KSDS itself performs under
         * {@code ACCESS MODE IS SEQUENTIAL} ({@code app/cbl/CBCUS01C.cbl:L31}) - and it makes the ordering a
         * property of the statement rather than of the backend's scan order. Only one row is ever transferred:
         * the row limit is set on the statement, so a browse of a large dataset never materialises it.
         *
         * <p>Reading a closed file is a <strong>programming error, not an I/O outcome</strong>, and throws.
         * COBOL would report status {@code '47'} or {@code '49'} for a read against a file in the wrong open
         * mode, but no consumer of this dataset ever does it - {@code CBCUS01C} closes once, after its loop -
         * so there is no legacy behaviour to reproduce and the honest response is to fail loudly at the defect
         * rather than to return a status no COBOL path would have produced.
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
                // decides what to do about it - which, in CBCUS01C, is to display and abend.
                LOG.error("Rejected browse read of the customer master dataset '" + datasetName() + "' - "
                        + BackendDiagnostic.of(translated).describe() + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
                return ReadResult.of(PERMANENT_ERROR_STATUS);
            }

            // The list itself is never null - the template asserts that internally before returning it - so
            // emptiness is the whole of the end-of-file test, and no unreachable null arm is written.
            if (rows.isEmpty()) {
                // AT END with no record read. An expected outcome, not an error.
                return ReadResult.endOfFile();
            }
            byte[] recordImage = rows.get(0);
            if (recordImage == null) {
                // A row whose record image is absent is not a readable 500-byte record. There IS a record, it
                // simply cannot be read, so this is an I/O-level defect and not an end of file - it must not
                // be mistaken for one, or a browse would stop early and silently.
                LOG.error("The customer master dataset '" + datasetName() + "' presented a row with no "
                        + "record image at column position " + RECORD_IMAGE_COLUMN_INDEX + "; reporting file "
                        + "status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
                return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
            }

            // The position advances to the image exactly as the backend presented it, which is what keeps the
            // next comparison an apples-to-apples one against the stored values.
            this.browsePosition = recordImage.clone();
            return repository.decoded(recordImage, "the row at the current browse position");
        }

        // =============================================================================================
        // 3000-CUSTFILE-PROC, the M03B-READ-K arm - app/cbl/CBSTM03B.CBL:L188-L193.
        // =============================================================================================

        /**
         * Reads one record by customer identifier under this open:
         * {@link CustomerRepository#readByKey(long)} against the shape this {@code OPEN} already resolved.
         *
         * <p>The Java form of the numeric view of {@code CBSTM03B}'s keyed read, which runs under that
         * program's {@code OPEN INPUT} at {@code app/cbl/CBSTM03B.CBL:L184}. Identical in outcome to the
         * repository's own method; it differs only in paying no second metadata round trip.
         *
         * <p>The browse position is untouched, exactly as a COBOL random {@code READ} leaves the position of a
         * sequential browse of the same file alone.
         *
         * @param custId the customer identifier; must not be negative
         * @return the discriminated outcome; never {@code null}
         * @throws IllegalStateException    if this handle has been closed
         * @throws IllegalArgumentException if {@code custId} is negative
         */
        public ReadResult readByKey(long custId) {
            requireOpen("read a record by key");
            if (statements == null) {
                return ReadResult.of(openStatus);
            }
            return repository.readKeyed(statements.selectByKey(), repository.keyImageOf(custId),
                    "the customer identifier "
                            + SensitiveDiagnostics.maskIdentifier(custId, KEY_LENGTH));
        }

        /**
         * Reads one record by its nine-character key image under this open:
         * {@link CustomerRepository#readByKey(String)} against the shape this {@code OPEN} already resolved.
         *
         * <p>This is the closer form to the source: {@code app/cbl/CBSTM03B.CBL:L189} moves
         * {@code LK-M03B-KEY (1:LK-M03B-KEY-LN)} - a character substring of the linkage area - into
         * {@code FD-CUST-ID PIC X(09)} before reading at {@code L190}.
         *
         * @param custIdAsChar9 the key exactly as {@code LK-M03B-KEY} holds it: exactly
         *                      {@link CustomerRepository#KEY_LENGTH} characters, untrimmed and unparsed
         * @return the discriminated outcome; never {@code null}
         * @throws NullPointerException     if {@code custIdAsChar9} is {@code null}
         * @throws IllegalArgumentException if {@code custIdAsChar9} is not exactly
         *                                  {@link CustomerRepository#KEY_LENGTH} characters
         * @throws IllegalStateException    if this handle has been closed
         */
        public ReadResult readByKey(String custIdAsChar9) {
            String keyImage = requireKeyImage(custIdAsChar9);
            requireOpen("read a record by key");
            if (statements == null) {
                return ReadResult.of(openStatus);
            }
            return repository.readKeyed(statements.selectByKey(), keyImage,
                    "the record identification field '" + SensitiveDiagnostics.maskIdentifier(keyImage)
                            + "'");
        }

        // =============================================================================================
        // 9000-CUSTFILE-CLOSE - app/cbl/CBCUS01C.cbl:L136-L152.
        // 9100-CUSTFILE-CLOSE - app/cbl/CBTRN01C.cbl:L379-L395.
        // 3000-CUSTFILE-PROC, the M03B-CLOSE arm - app/cbl/CBSTM03B.CBL:L195-L198.
        // =============================================================================================

        /**
         * Closes this file, reporting only the resulting file status.
         *
         * <p>Same two-armed shape as the {@code OPEN}, and the caller likewise owns the
         * {@code DISPLAY 'ERROR CLOSING CUSTOMER FILE'} line ({@code app/cbl/CBCUS01C.cbl:L147},
         * {@code app/cbl/CBTRN01C.cbl:L390}) and the abend.
         *
         * <p>Nothing is buffered and no connection is held between operations, so there is no flush to fail.
         * The one close-time failure this handle can genuinely detect is that the dataset is no longer
         * addressable - the analogue of the file system reporting a problem as a dataset is de-allocated - so
         * the same probe as the open is used, which also keeps both of the COBOL's symmetric guards reachable
         * rather than leaving one dead. A handle whose open failed has nothing to probe and reports that
         * open's own status.
         *
         * <p><strong>Idempotent, and idempotent in the strong sense:</strong> the first call probes and
         * reports, and every later call reports <em>that same outcome</em> without probing again. Returning
         * {@link FileStatus#OK} to a second caller regardless would be the weaker and wrong reading - a file
         * whose open never succeeded would report its failure once and then report success, and a caller
         * holding the second answer would believe a close succeeded that did not. This is what makes
         * try-with-resources safe alongside an explicit close in the same block.
         *
         * @return {@link FileStatus#OK} when the dataset is still addressable, otherwise
         *         {@link CustomerRepository#PERMANENT_ERROR_STATUS} - or, for a handle whose open failed,
         *         that open's own status; never {@code null}, always two characters
         * @throws IllegalStateException if the backend presents the dataset with no usable record-image
         *                               column
         */
        public String closeFile() {
            if (closed) {
                return closeStatus;
            }
            closed = true;
            this.browsePosition = null;
            if (statements == null) {
                // The OPEN never succeeded, so there is no open file to close: its own status is reported
                // rather than a fresh one, and no probe is issued for a file that was never opened.
                closeStatus = openStatus;
            } else {
                closeStatus = repository.statementsFor("a close of the customer master") == null
                        ? PERMANENT_ERROR_STATUS
                        : FileStatus.OK;
            }
            return closeStatus;
        }

        /**
         * The {@code APPL-RESULT} value the close's guard moves: {@code 0} on success and {@code 12}
         * otherwise.
         *
         * <p>{@code app/cbl/CBCUS01C.cbl:L139-L143} expresses the same two arms arithmetically -
         * {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} for zero and {@code ADD 12 TO ZERO GIVING
         * APPL-RESULT} for the failure - and {@code app/cbl/CBTRN01C.cbl:L382-L386} moves the literals
         * directly. Both reduce to these two values.
         *
         * <p>Closes the file if it is still open, so a caller can use this in place of {@link #closeFile()}
         * when the number is what it needs; on an already-closed handle it reports the same outcome that
         * close reported, so the status and the {@code APPL-RESULT} can never disagree.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link CustomerRepository#APPL_RESULT_FATAL}
         * @throws IllegalStateException if the backend presents the dataset with no usable record-image
         *                               column
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
                throw new IllegalStateException("This open of the customer master dataset '" + datasetName()
                        + "' has been closed, so it cannot " + operation + ". A COBOL program closes once, "
                        + "after its work - operating afterwards is a defect in the caller, not a file "
                        + "status. Open another file instead.");
            }
        }
    }

    // =================================================================================================
    // The discriminated outcomes.
    // =================================================================================================

    /**
     * The outcome of one read of the customer master: the classification the COBOL guard chain performs, and
     * nothing beyond it.
     *
     * <p>The vocabulary is {@link Outcome}, shared with every other dataset operation in the module so that a
     * caller's branch structure looks the same whichever legacy program it came from. All five of its
     * constants are admissible here, which is what lets a caller distinguish {@code '00'}, {@code '10'},
     * {@code '22'}, {@code '23'} and everything else. Two notes on that:
     * <ul>
     *   <li>{@code '10'} arises only from a browse and {@code '23'} only from a keyed read, because that is
     *       how the COBOL reads this dataset - but both are admissible here rather than split across two
     *       result types, since a repository reporting in one vocabulary is the point;</li>
     *   <li>{@code '22'} cannot arise from any operation this repository performs, because none of them adds
     *       a record. It is admissible anyway, and carried verbatim if a backend ever reported it, rather
     *       than being collapsed into the catch-all where a caller could not see it.</li>
     * </ul>
     *
     * <p>The record is present for, and only for, a successful read - the invariant is enforced, not merely
     * documented - so a caller can neither find a record on an end-of-file result nor lose one on a
     * successful read, and <strong>{@code null} is never returned for "not found"</strong>. The status and its
     * classification are likewise required to agree, so no inconsistent pair can be constructed at all.
     *
     * <h2>The CICS pair travels with the outcome</h2>
     *
     * <p>The online caller does not test a file status. {@code app/cbl/COACTVWC.cbl:L832-L833} issues its read
     * with {@code RESP(WS-RESP-CD) RESP2(WS-REAS-CD)} and captures <em>both</em>, and {@code L843-L853}
     * renders both into the message the operator reads - {@code '.Resp: ' ERROR-RESP} and
     * {@code ' REAS:' ERROR-RESP2}. A result carrying only a response, from which the reason had to be
     * assumed, could not compose that message; and a reason code derived from a response is not a reason
     * code. So the pair is a component of the outcome, built once by whoever knows the outcome and read
     * verbatim thereafter - {@link CicsResponse}, whose reason code is {@link FileStatus#NO_REASON_CODE}
     * where the condition genuinely carries no further reason and a reported value where a deployment's
     * adapter surfaces one.
     *
     * @param status   the two-character file status the read reported, verbatim
     * @param outcome  its classification
     * @param customer the decoded record, present exactly when {@code outcome} is {@link Outcome#OK}
     * @param diagnostic what the backend reported when it refused, present only on a failure it described -
     *                 so a caller can log the driver's own {@code SQLSTATE} instead of a status this module
     *                 synthesised
     * @param response the CICS {@code RESP}/{@code RESP2} pair for this outcome, carried rather than derived
     *                 on demand
     */
    public record ReadResult(String status,
                             Outcome outcome,
                             Optional<CustomerRecord> customer,
                             Optional<String> storedImage,
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
            Objects.requireNonNull(customer, "A read result carries an empty record rather than a null one, "
                    + "so no null escapes the type");
            Objects.requireNonNull(storedImage, "A read result carries an empty stored image rather than a "
                    + "null one, so no null escapes the type");
            Objects.requireNonNull(diagnostic, "A read result carries an empty diagnostic rather than a null "
                    + "one, so no null escapes the type");
            Objects.requireNonNull(response, "A read result carries a CICS response pair rather than a null "
                    + "one; use CicsResponse.ofBatchStatus(status) where the outcome is a file status and "
                    + "CicsResponse.reported(resp, resp2) where a backend surfaced both");
            if (customer.isPresent() != (outcome == Outcome.OK)) {
                throw new IllegalArgumentException(customer.isPresent()
                        ? "A read that did not succeed carries no record: outcome " + outcome
                                + " was given one. Only the '00' arm reaches CUSTOMER-RECORD."
                        : "A successful read carries the decoded record, and this one carries none; build it "
                                + "with ReadResult.found(CustomerRecord, String).");
            }
            // The stored image travels with the record and never without it. That is what makes
            // DISPLAY CUSTOMER-RECORD reproducible: a caller on the successful arm can always reach the
            // row's own bytes and never has to render the decoded record, which allocates a fresh area
            // and so blanks the trailing FILLER whatever the row held.
            if (storedImage.isPresent() != customer.isPresent()) {
                throw new IllegalArgumentException(storedImage.isPresent()
                        ? "A read that did not succeed carries no record, so it carries no stored image "
                                + "either: outcome " + outcome + " was given one."
                        : "A successful read carries the stored image the record was decoded from, and "
                                + "this one carries none; build it with "
                                + "ReadResult.found(CustomerRecord, String).");
            }
            storedImage.ifPresent(image -> {
                if (image.length() != CustomerRecord.RECORD_LENGTH) {
                    throw new IllegalArgumentException("A stored CUSTOMER-RECORD image is "
                            + CustomerRecord.RECORD_LENGTH + " characters as app/cpy/CVCUS01Y.cpy "
                            + "declares, but this one is " + image.length()
                            + ". DISPLAY CUSTOMER-RECORD writes the whole record area, so an image of any "
                            + "other width would emit a line the program cannot produce.");
                }
            });
        }

        /**
         * The successful arm: status {@code '00'}, carrying the decoded record.
         *
         * <p>{@code MOVE 0 TO APPL-RESULT} at {@code app/cbl/CBCUS01C.cbl:L95}, and
         * {@code DFHRESP(NORMAL)} - {@code SET FOUND-CUST-IN-MASTER TO TRUE} - at
         * {@code app/cbl/COACTVWC.cbl:L837-L838}.
         *
         * <p>Both the decoded record and the bytes it was decoded from are carried, and the second is not
         * redundant: {@code DISPLAY CUSTOMER-RECORD} ({@code app/cbl/CBCUS01C.cbl:78} and {@code :96})
         * writes the whole record area, whose trailing {@code FILLER} holds no field and which rendering
         * the decoded record would therefore blank.
         *
         * @param customer    the decoded record
         * @param storedImage the row's own characters, exactly
         *                    {@value CustomerRecord#RECORD_LENGTH} of them
         * @return a result carrying {@link FileStatus#OK}, the record and its stored image
         * @throws NullPointerException     if either argument is {@code null}
         * @throws IllegalArgumentException if {@code storedImage} is not
         *                                  {@value CustomerRecord#RECORD_LENGTH} characters
         */
        public static ReadResult found(CustomerRecord customer, String storedImage) {
            Objects.requireNonNull(customer, "A successful read carries the decoded customer record");
            Objects.requireNonNull(storedImage, "A successful read carries the stored image the record "
                    + "was decoded from, so DISPLAY CUSTOMER-RECORD can write the row's own bytes");
            return new ReadResult(FileStatus.OK, Outcome.OK, Optional.of(customer),
                    Optional.of(storedImage), Optional.empty(), CicsResponse.ofBatchStatus(FileStatus.OK));
        }

        /**
         * The end-of-file arm: status {@code '10'}, carrying no record.
         *
         * <p>{@code MOVE 16 TO APPL-RESULT} at {@code app/cbl/CBCUS01C.cbl:L99}, which the caller turns into
         * {@code MOVE 'Y' TO END-OF-FILE} at {@code L108}. Reached only by a browse.
         *
         * @return a result carrying {@link FileStatus#END_OF_FILE} and no record
         */
        public static ReadResult endOfFile() {
            return of(FileStatus.END_OF_FILE);
        }

        /**
         * The invalid-key arm: status {@code '23'}, carrying no record.
         *
         * <p>The {@code INVALID KEY} condition of a keyed batch read, and {@code DFHRESP(NOTFND)} online -
         * {@code app/cbl/COACTVWC.cbl:L839-L857}, whose caller composes
         * {@code 'CustId:' … ' not found in customer master.Resp: '} and sets {@code INPUT-ERROR}.
         *
         * @return a result carrying {@link FileStatus#NOT_FOUND} and no record
         */
        public static ReadResult notFound() {
            return of(FileStatus.NOT_FOUND);
        }

        /**
         * A result for any status other than success, classified from the status itself.
         *
         * <p>This is the general factory, and it accepts every status a backend can report - the end-of-file
         * and invalid-key statuses that {@link #endOfFile()} and {@link #notFound()} name, the duplicate-key
         * status {@code '22'}, and any permanent-error status. Success is the one status it refuses, because a
         * successful read carries a record and this factory has none to carry.
         *
         * @param status the two-character status the read reported, carried verbatim so the caller can render
         *               it exactly as {@code Z-DISPLAY-IO-STATUS} does
         * @return a result carrying {@code status}, its classification, and no record
         * @throws NullPointerException     if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *                                  {@link FileStatus#STATUS_LENGTH} characters, or is
         *                                  {@link FileStatus#OK}
         */
        public static ReadResult of(String status) {
            // classify(status) first, deliberately: it is this class's own width and null guard, and a caller
            // that passes a malformed status should read this class's diagnostic rather than one from the
            // status vocabulary two calls deeper.
            return new ReadResult(status, classify(status), Optional.empty(), Optional.empty(),
                    Optional.empty(),
                    CicsResponse.ofBatchStatus(status));
        }

        /**
         * A failed read carrying the CICS pair the outcome should report.
         *
         * <p>For an outcome whose response is <em>not</em> the translation of its file status. The width check
         * is the case that needs it: a row that is not {@link CustomerRepository#RECORD_LENGTH} bytes is a
         * permanent error to the batch guard chain and {@link FileStatus#LENGERR} to the online one, and
         * neither number can be derived from the other. It is also what a deployment adapter that surfaces
         * genuine {@code RESP}/{@code RESP2} values uses, so it never has to fabricate the pair.
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
            return new ReadResult(status, classify(status), Optional.empty(), Optional.empty(),
                    Optional.empty(), response);
        }

        /**
         * A failed read carrying what the backend actually said about it.
         *
         * <p>The status is what the caller branches on, because that is the quantity the COBOL guard chain
         * tests. The diagnostic is what makes the failure diagnosable: a status of {@code '9'} plus a feedback
         * byte tells an operator that something permanent went wrong and nothing about what, whereas the
         * driver's own {@code SQLSTATE} distinguishes an unreachable backend from a missing relation from a
         * rejected credential - three failures needing three different responses.
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
        public static ReadResult of(String status, CicsResponse response, BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A diagnostic is required by this factory; use of(String) "
                    + "where there is no backend refusal to report");
            return new ReadResult(status, classify(status), Optional.empty(), Optional.empty(),
                    Optional.of(diagnostic),
                    response);
        }

        /**
         * Whether the read succeeded and a record is available.
         *
         * <p>This is both the {@code IF APPL-AOK} test of the batch guard chain
         * ({@code app/cbl/CBCUS01C.cbl:L104}) and the {@code WHEN DFHRESP(NORMAL)} arm of the online one
         * ({@code app/cbl/COACTVWC.cbl:L837}).
         *
         * @return {@code true} for the {@code '00'} arm
         */
        public boolean isFound() {
            return outcome == Outcome.OK;
        }

        /**
         * The row's own bytes as characters, for a caller already on the successful arm.
         *
         * <p>This is what {@code DISPLAY CUSTOMER-RECORD} ({@code app/cbl/CBCUS01C.cbl:78} and
         * {@code :96}) writes: the whole {@value CustomerRepository#RECORD_LENGTH}-byte record area,
         * exactly as the row held it. Rendering {@link #customer()} through
         * {@link CustomerRecord#recordImage(FixedWidthCodec)} instead would agree on every declared field
         * and disagree on the trailing {@code FILLER}, which holds no field and which a fresh area
         * necessarily emits as spaces - so the two are not interchangeable and a raw display must use
         * this one.
         *
         * @return exactly {@value CustomerRepository#RECORD_LENGTH} characters
         * @throws IllegalStateException if this arm carries no record, and so no image either
         */
        public String requireStoredImage() {
            return storedImage.orElseThrow(() -> new IllegalStateException("A read that reported file "
                    + "status " + FileStatus.toStatusImage(status) + " carries no record, so it carries "
                    + "no stored image. DISPLAY CUSTOMER-RECORD is reached only on the '" + FileStatus.OK
                    + "' arm - app/cbl/CBCUS01C.cbl:94 tests the status before it displays - so branch on "
                    + "the outcome first."));
        }

        /**
         * Whether the browse reached the end of the dataset: the {@code IF APPL-EOF} test
         * ({@code app/cbl/CBCUS01C.cbl:L107}).
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
         * Whether the backend reported a duplicate key: status {@code '22'}.
         *
         * <p>Unreachable through this repository, because none of its operations adds a record. It is
         * distinguishable rather than folded into the catch-all so that a deployment adapter reporting it
         * cannot have it silently reinterpreted as a permanent error.
         *
         * @return {@code true} for the {@code '22'} arm
         */
        public boolean isDuplicate() {
            return outcome == Outcome.DUPLICATE;
        }

        /**
         * Whether the read failed for any other reason: the {@code WHEN OTHER} arm, which every guard chain
         * over this dataset ends in an abend ({@code app/cbl/CBCUS01C.cbl:L109-L114}).
         *
         * @return {@code true} for the catch-all arm
         */
        public boolean isOther() {
            return outcome == Outcome.OTHER;
        }

        /**
         * The CICS {@code RESP} value this outcome reports.
         *
         * <p>For the online caller, which tests {@code RESP} rather than a file status and renders it into
         * its error message as {@code ERROR-RESP} ({@code app/cbl/COACTVWC.cbl:L843}). Read from the carried
         * {@link #response()} rather than recomputed from the status, so an outcome whose response is not the
         * translation of its status - the width check reports {@link FileStatus#LENGERR} against a
         * permanent-error status - reports the response it was built with. Empty where the outcome has no
         * single CICS counterpart.
         *
         * @return the response value, or an empty {@code OptionalInt}
         */
        public OptionalInt cicsResp() {
            return response.resp();
        }

        /**
         * The CICS {@code RESP2} reason code this outcome reports.
         *
         * <p>The second half of what {@code app/cbl/COACTVWC.cbl:L832-L833} captures and {@code L852-L853}
         * renders as {@code ' REAS:' ERROR-RESP2}. It is {@link FileStatus#NO_REASON_CODE} for every outcome a
         * JDBC-backed dataset can produce, because a file status carries no CICS reason and none is invented
         * here; a deployment whose adapter surfaces a real one builds the result with
         * {@link CicsResponse#reported(int, int)}.
         *
         * @return the reason code, never negative
         */
        public int cicsResp2() {
            return response.resp2();
        }

        /**
         * The four-character status image the COBOL renders, for a caller composing its own diagnostic line.
         *
         * <p>Delegated to {@link FileStatus#toStatusImage(String)}, which is the module's single
         * implementation of {@code Z-DISPLAY-IO-STATUS} ({@code app/cbl/CBCUS01C.cbl:L161-L174}); the full
         * {@code 'FILE STATUS IS: NNNN'} line comes from {@link FileStatus#toDisplayLine(String)}.
         *
         * @return exactly {@link FileStatus#STATUS_IMAGE_LENGTH} characters
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }

        /**
         * The {@code APPL-RESULT} value the COBOL guard moves for this outcome.
         *
         * <p>{@code 0} on success, {@code 16} at end of file, and {@code 12} for everything else - exactly the
         * ladder at {@code app/cbl/CBCUS01C.cbl:L94-L103}. A not-found record maps to {@code 12} and not to a
         * gentler value, because the batch guard tests only {@code IF CUSTFILE-STATUS = '00'} and its
         * {@code '10'} arm, and moves {@code 12} for anything else.
         *
         * @return {@link FileStatus#APPL_AOK}, {@link FileStatus#APPL_EOF} or
         *         {@link CustomerRepository#APPL_RESULT_FATAL}
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
     * The outcome of one rewrite of the customer master: the classification
     * {@code 9600-WRITE-PROCESSING}'s guard performs ({@code app/cbl/COACTUPC.cbl:L4095-L4103}), and nothing
     * beyond it.
     *
     * <p>That guard is two-armed - {@code IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL) CONTINUE ELSE SET
     * LOCKED-BUT-UPDATE-FAILED TO TRUE} followed by {@code EXEC CICS SYNCPOINT ROLLBACK}. The status is
     * nevertheless carried verbatim and classified in the shared vocabulary, so a caller that wants to
     * distinguish an invalid-key rewrite from a permanent error can, while a caller that only needs the
     * COBOL's two arms uses {@link #isWritten()}.
     *
     * <p>No record is carried: a {@code REWRITE} returns a status and nothing else. There is no end-of-file
     * arm either, because a rewrite cannot reach the end of a dataset.
     *
     * <p>The CICS pair travels with the outcome for the same reason it does on {@link ReadResult}:
     * {@code app/cbl/COACTUPC.cbl:L4089-L4090} issues its {@code REWRITE} with
     * {@code RESP(WS-RESP-CD) RESP2(WS-REAS-CD)} and captures both, and a reason code derived from a response
     * is not a reason code.
     *
     * @param status     the two-character file status the rewrite reported, verbatim
     * @param outcome    its classification
     * @param diagnostic what the backend reported when it refused, present only on a failure it described
     * @param response   the CICS {@code RESP}/{@code RESP2} pair for this outcome, carried rather than derived
     *                   on demand
     */
    public record WriteResult(String status, Outcome outcome, Optional<BackendDiagnostic> diagnostic,
            CicsResponse response) {

        /**
         * Enforces the invariants of the outcome at construction.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *                                  {@link FileStatus#STATUS_LENGTH} characters, if {@code outcome}
         *                                  is not the classification of {@code status}, or if {@code outcome}
         *                                  is {@link Outcome#END_OF_FILE}, which a rewrite cannot produce
         */
        public WriteResult {
            requireConsistentStatus(status, outcome);
            Objects.requireNonNull(diagnostic, "A write result carries an empty diagnostic rather than a "
                    + "null one, so no null escapes the type");
            Objects.requireNonNull(response, "A write result carries a CICS response pair rather than a null "
                    + "one; use CicsResponse.ofBatchStatus(status) where the outcome is a file status and "
                    + "CicsResponse.reported(resp, resp2) where a backend surfaced both");
            if (outcome == Outcome.END_OF_FILE) {
                throw new IllegalArgumentException("A rewrite cannot reach the end of a dataset, so status '"
                        + FileStatus.END_OF_FILE + "' is not an outcome it can report.");
            }
        }

        /**
         * The successful arm: status {@code '00'}, one record rewritten.
         *
         * <p>{@code DFHRESP(NORMAL)} at {@code app/cbl/COACTUPC.cbl:L4095}.
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
         * {@code SET LOCKED-BUT-UPDATE-FAILED TO TRUE} at {@code app/cbl/COACTUPC.cbl:L4098}, followed by the
         * {@code EXEC CICS SYNCPOINT ROLLBACK} at {@code L4099-L4101}.
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
         * selects several rows is a permanent error to the batch guard chain and {@link FileStatus#INVREQ} to
         * the online one - and for a deployment adapter that surfaces genuine {@code RESP}/{@code RESP2}
         * values.
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
        public static WriteResult of(String status, CicsResponse response, BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A diagnostic is required by this factory; use of(String) "
                    + "where there is no backend refusal to report");
            return new WriteResult(status, classify(status), Optional.of(diagnostic), response);
        }

        /**
         * Whether the rewrite succeeded: {@code IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)}
         * ({@code app/cbl/COACTUPC.cbl:L4095}).
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
         * Whether the backend reported a duplicate key: status {@code '22'}.
         *
         * <p>Unreachable through a rewrite, which replaces a record rather than adding one, and
         * distinguishable for the same reason {@link ReadResult#isDuplicate()} is.
         *
         * @return {@code true} for the {@code '22'} arm
         */
        public boolean isDuplicate() {
            return outcome == Outcome.DUPLICATE;
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
         * The CICS {@code RESP} value this outcome reports, read from the carried pair rather than recomputed
         * from the status.
         *
         * @return the response value, or an empty {@code OptionalInt}
         */
        public OptionalInt cicsResp() {
            return response.resp();
        }

        /**
         * The CICS {@code RESP2} reason code this outcome reports.
         *
         * <p>The second half of what {@code app/cbl/COACTUPC.cbl:L4089-L4090} captures.
         * {@link FileStatus#NO_REASON_CODE} for every outcome a JDBC-backed dataset can produce - a row count
         * is not a reason code and is named in the log line instead.
         *
         * @return the reason code, never negative
         */
        public int cicsResp2() {
            return response.resp2();
        }

        /**
         * The four-character status image the COBOL renders, for a caller composing its own diagnostic line.
         *
         * @return exactly {@link FileStatus#STATUS_IMAGE_LENGTH} characters
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }

        /**
         * The {@code APPL-RESULT} value the COBOL guard moves for this outcome: {@code 0} on success and
         * {@code 12} otherwise.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link CustomerRepository#APPL_RESULT_FATAL}
         */
        public int applResult() {
            return isWritten() ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }
    }

    /**
     * Classifies a status, rejecting one that is not exactly two characters.
     *
     * <p>Shared by both outcome types so that the classification and its width check exist once, and so that
     * neither type can hold a status and a classification that disagree.
     *
     * @param status the two-character status
     * @return its classification in the shared vocabulary
     * @throws NullPointerException     if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly {@link FileStatus#STATUS_LENGTH}
     *                                  characters
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
     *                                  {@link FileStatus#STATUS_LENGTH} characters, or if {@code outcome} is
     *                                  not its classification
     */
    private static void requireConsistentStatus(String status, Outcome outcome) {
        Objects.requireNonNull(outcome, "An outcome carries its classification; it is never absent");
        Outcome classified = classify(status);
        if (outcome != classified) {
            throw new IllegalArgumentException("Status '" + FileStatus.toStatusImage(status)
                    + "' classifies as " + classified + ", but " + outcome + " was given; a status and its "
                    + "classification must agree, so that no caller can branch on one and read the other. "
                    + "Use one of the named factory methods.");
        }
    }

    /**
     * Requires a status to be exactly two characters, as every COBOL {@code FILE STATUS} field is.
     *
     * @param status the status to check
     * @return {@code status}, unchanged
     * @throws NullPointerException     if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly {@link FileStatus#STATUS_LENGTH}
     *                                  characters
     */
    private static String requireStatusWidth(String status) {
        Objects.requireNonNull(status, "An outcome carries the two-character file status the operation "
                + "reported; it is never absent");
        if (status.length() != FileStatus.STATUS_LENGTH) {
            throw new IllegalArgumentException("A file status is exactly " + FileStatus.STATUS_LENGTH
                    + " characters, as CUSTFILE-STATUS is declared at app/cbl/CBCUS01C.cbl:L46-L48; got "
                    + status.length() + ".");
        }
        return status;
    }
}

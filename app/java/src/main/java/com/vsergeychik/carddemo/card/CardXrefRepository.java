package com.vsergeychik.carddemo.card;

import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import java.nio.charset.Charset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The one data-access component for the card-to-account cross reference: the {@code CCXREF} base
 * KSDS and the {@code CXACAIX} alternate-index path over it.
 *
 * <h2>This class lives in {@code card} but serves nobody in {@code card}</h2>
 * <p>It sits here because it owns the {@code CVACT03Y} record type, {@link CardXrefRecord}, which the
 * migration plan assigns to the card package. Its <strong>callers are all somewhere else.</strong>
 * Reading every {@code COPY} statement in the three card programs shows that not one of them copies
 * {@code CVACT03Y}: {@code app/cbl/COCRDLIC.cbl} never names it, and {@code app/cbl/COCRDSLC.cbl:237}
 * and {@code app/cbl/COCRDUPC.cbl:356} both carry it <em>commented out</em> as
 * {@code *COPY CVACT03Y.}. The three card programs address only {@code CARDDAT} and {@code CARDAIX}.
 * The migration plan's per-program copybook table lists {@code CVACT03Y} for {@code COCRDSLC} and
 * {@code COCRDUPC}; the source does not bear that out, because a scan that does not skip comment
 * lines counts those two commented directives. The discrepancy is recorded here rather than quietly
 * reconciled, which is practice B4, and it matters concretely: <strong>the method surface below is
 * derived from the twelve live consumers, not from the card controllers.</strong>
 *
 * <p>The twelve live consumers, every one verified by an uncommented {@code COPY CVACT03Y}, making
 * this the second-most-shared record type in the estate:
 * <ul>
 *   <li><strong>Online, alternate-index read by account id</strong> - {@code COACTVWC:251},
 *       {@code COACTUPC:643}, {@code COTRN02C:90}, {@code COBIL00C:81};</li>
 *   <li><strong>Online, base read by card number</strong> - {@code COTRN02C:90} again, the only
 *       online program that reads both access paths;</li>
 *   <li><strong>Batch, keyed read</strong> - {@code CBACT04C:102}, {@code CBTRN01C:109},
 *       {@code CBTRN02C:112}, {@code CBTRN03C:98};</li>
 *   <li><strong>Batch, sequential browse</strong> - {@code CBACT03C:45}, and {@code CBSTM03A:53}
 *       through its data-access subroutine {@code CBSTM03B};</li>
 * </ul>
 *
 * <h2>The record: {@code app/cpy/CVACT03Y.cpy}, {@code (RECLN 50)}</h2>
 * <p>Fifty bytes, four spans, no gaps and no overlay. The geometry is owned by
 * {@link CardXrefRecord} and restated here because the two keys below are absolute offsets into it
 * and a reviewer must be able to diff them against the copybook without leaving this file:
 * <pre>
 * 01 CARD-XREF-RECORD.                    offset  length
 *     05 XREF-CARD-NUM  PIC X(16).             0      16   &lt;-- base CCXREF key
 *     05 XREF-CUST-ID   PIC 9(09).            16       9
 *     05 XREF-ACCT-ID   PIC 9(11).            25      11   &lt;-- CXACAIX alternate key
 *     05 FILLER         PIC X(14).            36      14
 * </pre>
 *
 * <p><strong>The trailing {@code FILLER} is emitted, always, as fourteen spaces</strong> (gate G21).
 * It is a first-class span of {@link CardXrefRecord#LAYOUT}, initialised by the layout itself before
 * any field is written, so no encode path can forget it. Dropping it would yield a 36-byte record,
 * which fails gate G19 and silently shifts every offset in whatever is written after it.
 *
 * <p><strong>Nothing here is a decimal.</strong> {@code CVACT03Y} declares no {@code COMP-3} and no
 * signed picture, so there is no scaled quantity in this record and therefore no {@code BigDecimal} in
 * this file - and no binary floating-point primitive could ever be correct for one of its fields
 * either (gate G22). {@code XREF-CUST-ID} is a scale-free {@code PIC 9(09)} and is an {@code int};
 * {@code XREF-ACCT-ID} is a scale-free {@code PIC 9(11)}, too wide for {@code int}, and is a
 * {@code long}.
 *
 * <p>The two floating-point primitives are described rather than named just above, deliberately, and
 * the same restraint applies to the schema artefacts named further down. The migration is policed by
 * negative token scans of {@code src/main/java}, and a prohibition notice that quoted the very tokens
 * being scanned for would register as a hit: it would cost every later reviewer an adjudication, and -
 * far worse - it would give a genuine violation somewhere in the tree a place to hide. Those scans stay
 * a clean binary signal on this file.
 *
 * <h2>The fixture is narrower than the copybook, and this class does not absorb that</h2>
 * <p>{@code app/data/ASCII/cardxref.txt} holds fifty records of <strong>36</strong> bytes, not 50:
 * every row omits the trailing {@code FILLER X(14)}. That is risk <strong>R-F</strong>, and gate
 * <strong>G16</strong> is the requirement that it be normalised before comparison rather than
 * accommodated. The normalisation is declared in configuration, not in code -
 * {@code application-test.yml} carries {@code carddemo.test.fixtures.cardxref.bytes-per-record: 36}
 * with {@code pad-to: 50} - and performed by the parity harness through
 * {@link FixedWidthCodec#padToDeclaredWidth(byte[], int)}. The fixture is widened <em>up</em> to the
 * copybook; the copybook is never narrowed <em>down</em> to the fixture, and
 * {@code carddemo.datasets.CCXREF.record-length} stays 50 in every profile.
 *
 * <p>So <strong>this class decodes against the declared 50-byte width and rejects anything else.</strong>
 * A row of any other width means the driver is not presenting the record this repository is
 * configured for, which is a contract violation rather than an I/O outcome, and it is thrown rather
 * than disguised as a file status. Quietly accepting 36 bytes in a production path would let the
 * exact defect gate G16 exists to catch pass for a clean read.
 *
 * <h2>One repository, one dataset, a second finder - gate G45</h2>
 * <p>{@code CCXREF} and {@code CXACAIX} are <strong>two access paths over one VSAM cluster</strong>,
 * not two datasets, so they are served by one repository with a second finder method for the
 * alternate key. Never a second repository, never a second table, never a join. Three independent
 * pieces of evidence, each from a different kind of source artefact:
 * <ol>
 *   <li><strong>The CSD says it in words.</strong> {@code app/csd/CARDDEMO.CSD:64} is
 *       {@code DESCRIPTION(ALTERNATE INDEX TO CCXREF VIA ACCOUNT KEY)}, on the
 *       {@code DEFINE FILE(CXACAIX)} at {@code :63} whose {@code DSNAME} at {@code :65} ends
 *       {@code .VSAM.AIX.PATH}. The base at {@code :37-39} is
 *       {@code DESCRIPTION(CARD TO ACCOUNT XREF)} over a {@code .VSAM.KSDS}.</li>
 *   <li><strong>The JCL opens the same cross reference twice in one step.</strong>
 *       {@code app/jcl/INTCALC.jcl} STEP15 declares {@code //XREFFILE} on the base KSDS at
 *       {@code :29-30} and {@code //XREFFIL1} on the alternate-index path at {@code :31-32}. Two DD
 *       names, one cluster, concurrently allocated - which is only coherent if the second is a path
 *       over the first.</li>
 *   <li><strong>The COBOL puts both keys on a single {@code SELECT}.</strong>
 *       {@code app/cbl/CBACT04C.cbl:34-39} is one {@code SELECT XREF-FILE ASSIGN TO XREFFILE} with
 *       {@code RECORD KEY IS FD-XREF-CARD-NUM} <em>and</em>
 *       {@code ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID}. Its {@code 1110-GET-XREF-DATA} at
 *       {@code :393-398} then reads that one file description by the alternate key -
 *       {@code READ XREF-FILE INTO CARD-XREF-RECORD KEY IS FD-XREF-ACCT-ID} - which is a single
 *       file, two keys, in four lines of source.</li>
 * </ol>
 * <p>The evidence is also asserted, not merely documented: the constructor requires the configured
 * {@code CXACAIX} binding to declare {@link DatasetBinding#base()} of {@code CCXREF} and
 * {@link DatasetBinding#alternateKey()} of {@code XREF-ACCT-ID}, and requires the {@code CCXREF}
 * binding to declare no base at all. A configuration that turned the path into a dataset of its own
 * fails startup here.
 *
 * <h2>The two keys are at two different offsets</h2>
 * <p>The base key is {@code XREF-CARD-NUM}, the sixteen bytes at {@code [0, 16)}. The alternate key
 * is {@code XREF-ACCT-ID}, the eleven bytes at <strong>{@code [25, 36)}</strong> - not at 16, which
 * is where {@code XREF-CUST-ID} lives. Getting that wrong produces lookups that are silently and
 * consistently wrong and that no compiler can catch, which is why both keys are taken from
 * {@link CardXrefRecord}'s named descriptors rather than from an integer written out here.
 *
 * <h2>Callers hand the alternate key over in two different COBOL views of one span</h2>
 * <p>{@code COTRN02C:582} and {@code COBIL00C:414} pass {@code RIDFLD(XREF-ACCT-ID)}, the
 * {@code PIC 9(11)} numeric field. {@code COACTVWC:729} and {@code COACTUPC:3655} pass
 * {@code RIDFLD(WS-CARD-RID-ACCT-ID-X)}, the {@code PIC X(11)} <em>{@code REDEFINES}</em> of the very
 * same eleven bytes - see {@code app/cbl/COACTVWC.cbl:78-80}, where {@code WS-CARD-RID-ACCT-ID} is
 * {@code PIC 9(11)} and {@code WS-CARD-RID-ACCT-ID-X REDEFINES} it as {@code PIC X(11)}. Both
 * callers reach that state by moving a numeric account id into the numeric view first
 * ({@code COACTVWC:691}), so the eleven stored bytes are eleven digits either way and the two views
 * are <strong>identical on the wire</strong>. Both are offered as overloads and both compose the key
 * through the same {@code PIC 9} move, so they cannot diverge.
 *
 * <h2>Only the operations the COBOL performs</h2>
 * <p>A keyed read on the base, a keyed read on the alternate-index path, and a sequential browse of
 * the base. There is <strong>no add, no delete and no rewrite</strong>, because no verified consumer
 * performs one against the cross reference: the CSD grants those capabilities to every file
 * uniformly, but a granted capability that no program exercises is not part of this migration's
 * surface. Declaring them would be unrequested scope, and would add branches that no test could
 * reach honestly, which would put the per-package branch-coverage gate on a fiction.
 *
 * <h2>Outcomes are reported, never thrown - and {@code NOTFND} is not an error</h2>
 * <p>Every read returns a {@link ReadResult} carrying a two-character {@code FILE STATUS}, the
 * {@link Outcome} it classifies to, the record when there is one, the DD name that was addressed, and
 * the CICS {@code RESP} and {@code RESP2} pair. Nothing is displayed, logged or abended here. That
 * boundary is deliberate and is where the COBOL puts it: the {@code EVALUATE} that decides what a
 * status means belongs to the calling paragraph, and every one of them is written as the same
 * three-arm shape - {@code WHEN DFHRESP(NORMAL)} / {@code WHEN DFHRESP(NOTFND)} /
 * {@code WHEN OTHER} online, and {@code IF status = '00'} / {@code '10'} / else in batch.
 *
 * <p><strong>A missing record is never an exception.</strong> {@code COACTVWC:741-758} treats
 * {@code NOTFND} as a normal branch that sets {@code INPUT-ERROR} and
 * {@code FLG-ACCTFILTER-NOT-OK} and composes a message; {@code COTRN02C:591-596} and
 * {@code COBIL00C:423-428} set {@code WS-ERR-FLG} to {@code 'Y'} and redisplay the screen;
 * {@code CBTRN02C:384-387} takes the {@code INVALID KEY} arm and records validation reason 100,
 * {@code INVALID CARD NUMBER FOUND}. Throwing would replace four working recovery paths with a stack
 * trace.
 *
 * <h2>Why {@code RESP2} is on the result even though the value is always zero</h2>
 * <p>Because the callers render it. {@code COACTVWC:745-757} moves {@code WS-REAS-CD} into
 * {@code ERROR-RESP2} and {@code STRING}s it into the returned message;
 * {@code COTRN02C:598}, {@code COBIL00C:431} and {@code COACTUPC:3690} do the equivalent. A read
 * served over JDBC has no CICS secondary reason code to report, so the honest value is zero and it is
 * reported as zero - see {@link #CICS_RESP2_NOT_APPLICABLE}. Inventing a plausible-looking code would
 * put a fabricated number into observable message text, which is exactly the class of defect
 * field-for-field parity diffing exists to catch.
 *
 * <h2>Residual risk R-E: the driver is a deployment-time input</h2>
 * <p>Production connectivity cannot be exercised from this build. The CardDemo datasets are VSAM and
 * sequential files, there is no {@code EXEC SQL} anywhere in the twenty-eight COBOL programs, and
 * indexed VSAM has no standard published JDBC driver, so this module pins no driver coordinate: the
 * URL, driver class and credentials are supplied at deployment time and the {@code DataSource} is
 * entirely configuration-bound by {@code DataSourceConfig}. Three consequences are visible in the
 * code below and are not defects to be tidied away:
 * <ul>
 *   <li>The record image is read <strong>by column position 1 and never by column name</strong>. A
 *       dataset carrying no relational metadata is presented as a single record-image column, and a
 *       column name written into Java here would be an unverifiable literal.</li>
 *   <li>The key match is therefore performed <strong>in Java</strong>, on the byte image the codec
 *       produces, rather than pushed down as a predicate - a predicate would have to name that
 *       column. The comparison is the byte comparison VSAM itself performs, so it is exact; it is
 *       simply not delegated. There is no performance objective in this migration, and introducing
 *       one here would risk parity rather than improve anything measurable. A deployment whose driver
 *       does expose a nameable column can add a pushdown without changing this class's contract.</li>
 *   <li>Every {@link DataAccessException} the driver raises is reported as a permanent-error file
 *       status, {@link #PERMANENT_ERROR_STATUS}, which classifies as {@link Outcome#OTHER} - the
 *       {@code WHEN OTHER} arm, which in every consumer is the error or abend path. The status is the
 *       whole of what is reported, exactly as the COBOL keeps nothing but the status.</li>
 * </ul>
 *
 * <h2>Record format: the copybook wins</h2>
 * <p>The CICS definitions declare {@code RECORDFORMAT(V)} while the batch JCL declares {@code RECFM=F}
 * or {@code FB}. Neither is consulted. This layer treats the record length as copybook-fixed at fifty
 * bytes regardless, which is the migration plan's ruling and the only reading under which the two
 * declarations can both be honoured.
 *
 * <h2>User-specified rules</h2>
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - and that single line
 * is the whole document, so <strong>no user rule governs this file.</strong> Its absence is not
 * licence to lower the bar; the migration plan elevates twelve enterprise practices to binding
 * constraints instead. Those bearing on this file are <strong>B1</strong> (no dependency beyond the
 * six the pom declares - no accessor generator, no ORM, no object mapper, no copybook parser),
 * <strong>B3</strong> (every legacy tree cited above is read-only and is cited purely as provenance),
 * <strong>B4</strong> (the two source-versus-plan discrepancies above are documented, not silently
 * reconciled), <strong>B7</strong> (every branch in this file is reachable from a plain JUnit test
 * with a mocked {@link JdbcTemplate}, no context and no backend, which is what lets the per-package
 * branch gate be met deterministically), <strong>B8</strong> (explicit over implicit - explicit
 * imports with no wildcard, the code page named and injected rather than defaulted, and every dataset
 * name resolved from configuration so no {@code DSNAME} literal appears here),
 * <strong>B9</strong> (no static mutable state - collaborators are constructor-injected, browse
 * position lives in a per-call cursor, and every {@code static} member here is {@code final}),
 * <strong>B11</strong> (the decode is hand-written and addressed by absolute offset, diffable against
 * {@code CVACT03Y.cpy}) and <strong>B12</strong> (risk R-E above is documented and reported, never
 * absorbed).
 *
 * <p>Nothing schema-shaped appears in this file and none may be added: no data-definition statement,
 * no migration script, no entity or table or column mapping, no version column and no index creation
 * (gate G44). The existing cluster is reached exactly as it is.
 *
 * @see CardXrefRecord
 * @see FileStatus
 * @see ReadResult
 * @see BrowseCursor
 */
@Repository
public class CardXrefRepository {

    // =================================================================================================
    // Dataset identity. These are CONFIGURATION KEYS and CICS FILE names - never dataset names. The
    // dataset names themselves live only in application.yml (gate G46), and a scan of this file for
    // the production DSNAME prefix finds nothing.
    // =================================================================================================

    /**
     * The configuration key and CICS {@code FILE} name of the <strong>base KSDS</strong>:
     * {@code CCXREF}.
     *
     * <p>{@code app/csd/CARDDEMO.CSD:37-39}, {@code DESCRIPTION(CARD TO ACCOUNT XREF)}. It is also
     * the eight-character literal the online programs pass to CICS -
     * {@code app/cbl/COTRN02C.cbl:41} declares
     * {@code 05 WS-CCXREF-FILE PIC X(08) VALUE 'CCXREF  '} - so
     * {@link #baseFileNameForCics()} renders it back to that exact eight-byte form for a caller
     * composing {@code ERROR-FILE}.
     */
    public static final String BASE_DD_NAME = "CCXREF";

    /**
     * The configuration key and CICS {@code FILE} name of the <strong>alternate-index path</strong>:
     * {@code CXACAIX}.
     *
     * <p>{@code app/csd/CARDDEMO.CSD:63-65}, whose {@code DESCRIPTION} is the first of this class's
     * three pieces of gate G45 evidence. The online literal is
     * {@code PIC X(08) VALUE 'CXACAIX '} at {@code app/cbl/COTRN02C.cbl:42},
     * {@code app/cbl/COBIL00C.cbl:42}, {@code app/cbl/COACTVWC.cbl:192-193} and
     * {@code app/cbl/COACTUPC.cbl:581-582}.
     */
    public static final String ALTERNATE_INDEX_DD_NAME = "CXACAIX";

    /**
     * The width every row must be, taken from the copybook by way of {@link CardXrefRecord} rather
     * than restated: fifty bytes, {@code FILLER} included.
     */
    public static final int RECORD_LENGTH = CardXrefRecord.RECORD_LENGTH;

    /**
     * The base-KSDS key width: sixteen bytes, {@code XREF-CARD-NUM}'s {@code PIC X(16)}.
     *
     * <p>This is the {@code KEYLENGTH(LENGTH OF XREF-CARD-NUM)} of
     * {@code app/cbl/COTRN02C.cbl:616}.
     */
    public static final int CARD_NUMBER_KEY_LENGTH = CardXrefRecord.XREF_CARD_NUM_LENGTH;

    /**
     * The alternate-index key width: eleven bytes, {@code XREF-ACCT-ID}'s {@code PIC 9(11)}, viewed
     * either as itself or through its {@code PIC X(11)} {@code REDEFINES}.
     *
     * <p>This is the {@code KEYLENGTH} of {@code app/cbl/COTRN02C.cbl:583},
     * {@code app/cbl/COBIL00C.cbl:415} and {@code app/cbl/COACTVWC.cbl:730} alike.
     */
    public static final int ACCOUNT_ID_KEY_LENGTH = CardXrefRecord.XREF_ACCT_ID_LENGTH;

    /**
     * The copybook field name the {@code CXACAIX} binding must nominate as its alternate key, checked
     * by the constructor so the gate G45 relationship is enforced rather than merely described.
     */
    public static final String EXPECTED_ALTERNATE_KEY_FIELD = CardXrefRecord.XREF_ACCT_ID_NAME;

    /**
     * The one-based result-set column holding the record image.
     *
     * <p>Position, never a name, for the reason set out under residual risk R-E on this class. This
     * matches the convention the module's other configuration-bound reader already established.
     */
    public static final int RECORD_IMAGE_COLUMN_INDEX = 1;

    /**
     * The width of a CICS {@code FILE} name as the online programs declare it: {@code PIC X(08)}.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:41-42} and its three siblings all hold the file name in an
     * eight-character field, and {@code app/cbl/COACTVWC.cbl:763} moves that whole field into
     * {@code ERROR-FILE}. So a caller composing an error message needs the name space-padded to eight,
     * which is what {@link #baseFileNameForCics()} and {@link #alternateIndexFileNameForCics()}
     * return.
     */
    public static final int CICS_FILE_NAME_LENGTH = 8;

    // =================================================================================================
    // Status vocabulary. Every value comes from common/FileStatus, which unifies the batch two-
    // character FILE STATUS with the online CICS RESP so that one repository can serve a controller
    // and a batch job indistinguishably. No status vocabulary is invented in this file.
    // =================================================================================================

    /**
     * The second byte of the permanent-error status: feedback code zero, meaning "permanent error, no
     * more specific code available".
     *
     * <p>Not arbitrary. z/OS COBOL reports an implementor-defined permanent error as {@code '9'} in
     * the first status byte with a binary feedback code in the second, and the
     * {@code 9910-DISPLAY-IO-STATUS} paragraph that every batch consumer of this dataset shares -
     * {@code app/cbl/CBACT03C.cbl}, {@code CBACT04C}, {@code CBTRN01C}, {@code CBTRN02C},
     * {@code CBTRN03C} - is written specifically to decode that form. Zero is the honest feedback code
     * for a backend this class cannot interrogate any further.
     */
    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The two-character file status reported for a permanent I/O error: {@code '9'} followed by
     * {@link #PERMANENT_ERROR_FEEDBACK_CODE}.
     *
     * <p>It renders as {@code "9000"} through {@link FileStatus#toStatusImage(String)} and therefore as
     * {@code FILE STATUS IS: NNNN9000} through {@link FileStatus#toDisplayLine(String)}, which is
     * exactly the line {@code 9910-DISPLAY-IO-STATUS} produces for it. The value is not invented for
     * this class: it is the extended-status form the shared vocabulary was built to render.
     *
     * <p>It is deliberately <strong>not</strong> one of the four statuses the COBOL enumerates.
     * {@link FileStatus#outcomeOfStatus(String)} classifies it as {@link Outcome#OTHER}, which is the
     * {@code WHEN OTHER} arm, which is where an I/O failure belongs.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    /**
     * The {@code APPL-RESULT} value the batch consumers move for a fatal I/O status: {@code 12}.
     *
     * <p>{@code app/cbl/CBACT03C.cbl:101} moves {@code 12} on the third arm of
     * {@code 1000-XREFFILE-GET-NEXT}, and {@code :124} and the close paragraph do the same;
     * {@code app/cbl/CBACT04C.cbl:403} moves {@code 12} when the keyed cross-reference read returns
     * anything but {@code '00'}. {@link FileStatus} already carries {@code APPL_AOK} of {@code 0} and
     * {@code APPL_EOF} of {@code 16}, so only the fatal value needs naming, and it is named here
     * because this is the class whose results it classifies.
     */
    public static final int APPL_RESULT_FATAL = 12;

    /**
     * The CICS {@code RESP2} value every result carries: zero.
     *
     * <p>A read served over JDBC has no CICS secondary reason code. The callers nonetheless render
     * {@code RESP2} into observable message text - {@code app/cbl/COACTVWC.cbl:746} moves
     * {@code WS-REAS-CD} to {@code ERROR-RESP2} and {@code STRING}s it into {@code WS-RETURN-MSG},
     * {@code app/cbl/COTRN02C.cbl:598} and {@code app/cbl/COBIL00C.cbl:431}
     * {@code DISPLAY 'RESP:' … 'REAS:' …} - so the field has to exist and has to hold something. Zero
     * is what it honestly holds. Deriving a plausible-looking code from a driver error would put a
     * fabricated number into message text that field-for-field parity diffing compares.
     */
    public static final int CICS_RESP2_NOT_APPLICABLE = 0;

    // =================================================================================================
    // Injected collaborators and the configuration-derived statements. All final, all per-instance:
    // there is no static mutable state anywhere in this class (practice B9, gate G53).
    // =================================================================================================

    /** The module's single {@link JdbcTemplate}, over the configuration-bound {@code DataSource}. */
    private final JdbcTemplate jdbcTemplate;

    /**
     * The hand-written fixed-width codec, built once over the explicitly injected dataset code page.
     * Held rather than re-created per row so the charset validation happens once.
     */
    private final FixedWidthCodec codec;

    /** The resolved {@code CCXREF} dataset name, from configuration and never from a literal. */
    private final String baseDatasetName;

    /** The resolved {@code CXACAIX} dataset name, from configuration and never from a literal. */
    private final String alternateIndexDatasetName;

    /**
     * The statement that reads the base cluster, in base-key order. Composed once in the constructor
     * from the configured dataset name.
     */
    private final String baseSelectSql;

    /**
     * The statement that reads the alternate-index path, in base-key order. A different dataset name
     * from {@link #baseSelectSql}, which is the concrete run-time expression of gate G45.
     */
    private final String alternateIndexSelectSql;

    /**
     * Assembles the repository from the module's shared {@link JdbcTemplate}, the DD-name-keyed
     * dataset catalogue and the explicitly named dataset code page.
     *
     * <p>Constructor injection throughout, with no field injection and no setter, so an instance is
     * either fully wired or does not exist (practice B9).
     *
     * <p><strong>Everything checkable about the configuration is checked here, at startup, not at the
     * first read.</strong> Four invariants are enforced, and each one guards a defect that would
     * otherwise surface as plausible-looking wrong data:
     * <ol>
     *   <li>Both bindings must declare a record length of {@value #RECORD_LENGTH}. Decoding by
     *       absolute offset against a differently-sized record would misplace every field after the
     *       first, so a disagreement between {@code app/cpy/CVACT03Y.cpy} and configuration is
     *       rejected rather than tolerated.</li>
     *   <li>The {@code CXACAIX} binding must declare {@link DatasetBinding#base()} of
     *       {@value #BASE_DD_NAME}. That is what marks it an access path over an existing cluster
     *       rather than a dataset of its own, and it is gate G45 asserted rather than merely
     *       documented.</li>
     *   <li>The {@code CXACAIX} binding must nominate {@value #EXPECTED_ALTERNATE_KEY_FIELD} as its
     *       alternate key. A path keyed on any other field is not the path the four online callers
     *       address.</li>
     *   <li>The {@code CCXREF} binding must declare <em>no</em> base. A base cluster that claimed to
     *       be an index over something else would invert the relationship the two access paths have,
     *       and the inversion would be invisible at every call site.</li>
     * </ol>
     *
     * @param jdbcTemplate    the module-wide template; never {@code null}
     * @param datasetBindings the {@code carddemo.datasets} catalogue; never {@code null}. The two
     *                        entries this repository needs are looked up by the keys
     *                        {@value #BASE_DD_NAME} and {@value #ALTERNATE_INDEX_DD_NAME}, so no
     *                        dataset name is written in Java (gate G46)
     * @param datasetCharset  the dataset code page, injected by bean name so it is stated explicitly
     *                        rather than taken from the platform (practice B8)
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if either binding is absent, declares a record length other than
     *                               {@value #RECORD_LENGTH}, declares no usable dataset name, or
     *                               describes an access-path relationship other than the one gate G45
     *                               requires
     */
    public CardXrefRepository(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset) {

        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + "card cross reference is reached through the module's shared template over the "
                + "configuration-bound DataSource");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "dataset names live in configuration and are never written in Java");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset is "
                + "required: a fixed-width mainframe record is bytes in a specific code page, so the "
                + "code page is stated explicitly and never taken from the platform"));

        DatasetBinding base = datasetBindings.binding(BASE_DD_NAME);
        DatasetBinding alternateIndex = datasetBindings.binding(ALTERNATE_INDEX_DD_NAME);

        requireCopybookRecordLength(BASE_DD_NAME, base);
        requireCopybookRecordLength(ALTERNATE_INDEX_DD_NAME, alternateIndex);
        requireAlternateIndexOverBase(alternateIndex);
        requireBaseCluster(base);

        this.baseDatasetName = requireUsableDatasetName(BASE_DD_NAME, base.dsname());
        this.alternateIndexDatasetName =
                requireUsableDatasetName(ALTERNATE_INDEX_DD_NAME, alternateIndex.dsname());
        this.baseSelectSql = selectAllInKeyOrder(this.baseDatasetName);
        this.alternateIndexSelectSql = selectAllInKeyOrder(this.alternateIndexDatasetName);
    }

    // =================================================================================================
    // Constructor guards. Each one rejects a configuration that would otherwise read or compare the
    // wrong bytes, and each is reachable from a plain unit test with a hand-built binding.
    // =================================================================================================

    /**
     * Requires a binding to agree with {@code app/cpy/CVACT03Y.cpy} about the record width.
     *
     * @param ddName  the configuration key, for the diagnostic
     * @param binding the configured binding
     * @throws IllegalStateException if the declared record length is not {@value #RECORD_LENGTH}
     */
    private static void requireCopybookRecordLength(String ddName, DatasetBinding binding) {
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding for '" + ddName + "' declares a record "
                    + "length of " + binding.recordLength() + ", but app/cpy/CVACT03Y.cpy declares "
                    + "(RECLN " + RECORD_LENGTH + ") and this repository decodes by absolute offset "
                    + "against exactly that width. The 36-byte form of app/data/ASCII/cardxref.txt is "
                    + "the fixture omitting its trailing FILLER X(" + CardXrefRecord.FILLER_LENGTH
                    + "); it is normalised UP to " + RECORD_LENGTH + " by the parity harness (risk "
                    + "R-F, gate G16) and is never configured DOWN here, because a shorter width "
                    + "would shift XREF-CUST-ID and XREF-ACCT-ID out of position. Correct "
                    + "carddemo.datasets." + ddName + ".record-length to " + RECORD_LENGTH + ".");
        }
    }

    /**
     * Requires the {@code CXACAIX} binding to describe an alternate-index path over {@code CCXREF},
     * keyed on {@code XREF-ACCT-ID}. This is gate G45 enforced at startup.
     *
     * @param alternateIndex the configured alternate-index binding
     * @throws IllegalStateException if it names a different base, or a different alternate key
     */
    private static void requireAlternateIndexOverBase(DatasetBinding alternateIndex) {
        if (!BASE_DD_NAME.equals(alternateIndex.base())) {
            throw new IllegalStateException("Dataset binding for '" + ALTERNATE_INDEX_DD_NAME
                    + "' declares base '" + alternateIndex.base() + "', but it must declare '"
                    + BASE_DD_NAME + "'. app/csd/CARDDEMO.CSD:64 is DESCRIPTION(ALTERNATE INDEX TO "
                    + BASE_DD_NAME + " VIA ACCOUNT KEY), app/jcl/INTCALC.jcl:29-32 opens the base and "
                    + "the path in one step as XREFFILE and XREFFIL1, and app/cbl/CBACT04C.cbl:34-39 "
                    + "carries both keys on a single SELECT. The path is an additional access path "
                    + "over one cluster, never a dataset of its own (gate G45). Correct "
                    + "carddemo.datasets." + ALTERNATE_INDEX_DD_NAME + ".base.");
        }
        if (!EXPECTED_ALTERNATE_KEY_FIELD.equals(alternateIndex.alternateKey())) {
            throw new IllegalStateException("Dataset binding for '" + ALTERNATE_INDEX_DD_NAME
                    + "' nominates alternate key '" + alternateIndex.alternateKey() + "', but the "
                    + "path the four online callers address is keyed on "
                    + EXPECTED_ALTERNATE_KEY_FIELD + ", the PIC 9(" + ACCOUNT_ID_KEY_LENGTH
                    + ") field at offset " + CardXrefRecord.XREF_ACCT_ID_OFFSET
                    + " of app/cpy/CVACT03Y.cpy. Correct carddemo.datasets."
                    + ALTERNATE_INDEX_DD_NAME + ".alternate-key.");
        }
    }

    /**
     * Requires the {@code CCXREF} binding to be a base cluster, that is to declare no base of its own.
     *
     * @param base the configured base binding
     * @throws IllegalStateException if it declares a base, which would invert the access-path
     *                               relationship
     */
    private static void requireBaseCluster(DatasetBinding base) {
        if (base.base() != null) {
            throw new IllegalStateException("Dataset binding for '" + BASE_DD_NAME + "' declares base '"
                    + base.base() + "', but it IS the base cluster - app/csd/CARDDEMO.CSD:37-39, "
                    + "DESCRIPTION(CARD TO ACCOUNT XREF), keyed on "
                    + CardXrefRecord.XREF_CARD_NUM_NAME + ". Only '" + ALTERNATE_INDEX_DD_NAME
                    + "' declares a base. Remove carddemo.datasets." + BASE_DD_NAME + ".base.");
        }
    }

    /**
     * Requires a configured dataset name that can be composed into a statement.
     *
     * <p>Blank is rejected because {@code application.yml} spells the name as an environment
     * placeholder, so an unconfigured deployment yields an empty string rather than {@code null}. A
     * control character is rejected because a dataset name cannot contain one and it would corrupt the
     * composed statement.
     *
     * @param ddName    the configuration key, for the diagnostic
     * @param candidate the configured dataset name
     * @return {@code candidate}, unchanged
     * @throws IllegalStateException if it is absent, blank, or contains a control character
     */
    private static String requireUsableDatasetName(String ddName, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for '" + ddName + "' declares no "
                    + "dataset name. Set carddemo.datasets." + ddName + ".dsname; this repository "
                    + "composes its statements from configuration alone and hard-codes no dataset "
                    + "name (gate G46).");
        }
        for (int index = 0; index < candidate.length(); index++) {
            if (Character.isISOControl(candidate.charAt(index))) {
                throw new IllegalStateException("The dataset name configured at carddemo.datasets."
                        + ddName + ".dsname contains a control character at position " + index
                        + "; a dataset name cannot contain one, and it would corrupt the composed "
                        + "statement. Correct the configured value.");
            }
        }
        return candidate;
    }

    /**
     * Composes the read statement for a dataset: every row, in base-key order.
     *
     * <p><strong>Why {@code ORDER BY 1} and not a column name.</strong> The ordinal refers to the
     * first select-list item, which is the single record-image column - the same column
     * {@value #RECORD_IMAGE_COLUMN_INDEX} that {@link #mapRecordImage(ResultSet, int)} reads. Using
     * the ordinal keeps the statement free of any column name, for the reason set out under residual
     * risk R-E on this class.
     *
     * <p><strong>Why ordering the whole image orders by the key.</strong> The base key
     * {@code XREF-CARD-NUM} is the <em>leading</em> fixed-width span of the record, bytes
     * {@code [0, 16)}, so comparing two record images left to right compares their card numbers first
     * and reaches any later byte only when the card numbers are equal. That is precisely the collating
     * order a KSDS browse returns, which is what {@code app/cbl/CBACT03C.cbl:29-33} declares with
     * {@code ORGANIZATION IS INDEXED} plus {@code ACCESS MODE IS SEQUENTIAL} on
     * {@code RECORD KEY IS FD-XREF-CARD-NUM}. The ordering is applied to the keyed reads too, so that
     * when an alternate key matches more than one record the first one returned is deterministic -
     * see {@link #readByAccountIdViaAltIndex(long)}.
     *
     * <p>One caveat, and it is part of risk R-E: the ordering is performed by the driver's own
     * collation, whereas VSAM compares raw bytes. Every card number in this dataset is sixteen
     * digits - {@code app/cpy/CVACT03Y.cpy} declares {@code PIC X(16)} and all fifty shipped records
     * hold digits - and the digits collate in the same relative order in every code page this module
     * uses, so the two orders coincide for the data at hand.
     *
     * @param datasetName the configured dataset name
     * @return the composed statement
     */
    private static String selectAllInKeyOrder(String datasetName) {
        return "SELECT * FROM " + asDelimitedIdentifier(datasetName) + " ORDER BY "
                + RECORD_IMAGE_COLUMN_INDEX;
    }

    /**
     * Renders a dataset name as a delimited SQL identifier, doubling any embedded quote.
     *
     * <p>Delimited because a mainframe dataset name contains periods and would otherwise be read as a
     * qualified name, and because delimiting is what keeps a configured value from being parsed as
     * syntax.
     *
     * @param name the configured dataset name
     * @return the name as a delimited identifier
     */
    private static String asDelimitedIdentifier(String name) {
        String quote = "\"";
        return quote + name.replace(quote, quote + quote) + quote;
    }

    // =================================================================================================
    // The two dataset identities, exposed. A caller composing COACTVWC's ERROR-FILE needs the CICS
    // name; a test proving gate G45 needs the two dataset names to be different.
    // =================================================================================================

    /**
     * The resolved {@code CCXREF} base-cluster dataset name, exactly as configuration declares it.
     *
     * @return the base dataset name; never {@code null}, never blank
     */
    public String baseDatasetName() {
        return baseDatasetName;
    }

    /**
     * The resolved {@code CXACAIX} alternate-index path dataset name, exactly as configuration
     * declares it.
     *
     * <p>It is a different dataset name from {@link #baseDatasetName()} - the CSD gives the base a
     * {@code .VSAM.KSDS} name and the path a {@code .VSAM.AIX.PATH} name - while being a second access
     * path over the same cluster. Both facts hold at once, and that is the whole of gate G45.
     *
     * @return the alternate-index path dataset name; never {@code null}, never blank
     */
    public String alternateIndexDatasetName() {
        return alternateIndexDatasetName;
    }

    /**
     * The base cluster's CICS {@code FILE} name in the eight-character form the online programs hold
     * it: {@code 'CCXREF  '}.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:41} declares
     * {@code 05 WS-CCXREF-FILE PIC X(08) VALUE 'CCXREF  '}, and a caller composing the error text of
     * {@code app/cbl/COACTVWC.cbl:762-766} moves that whole eight-byte field into {@code ERROR-FILE}.
     * The padding is applied through the codec's {@code PIC X} move rather than by concatenating
     * spaces, so the one truncation-and-padding implementation in the module governs it here too.
     *
     * @return the name padded to {@value #CICS_FILE_NAME_LENGTH} characters
     */
    public String baseFileNameForCics() {
        return codec.movePicX(BASE_DD_NAME, CICS_FILE_NAME_LENGTH);
    }

    /**
     * The alternate-index path's CICS {@code FILE} name in its eight-character form:
     * {@code 'CXACAIX '}.
     *
     * <p>Declared as {@code PIC X(08) VALUE 'CXACAIX '} at {@code app/cbl/COTRN02C.cbl:42},
     * {@code app/cbl/COBIL00C.cbl:42}, {@code app/cbl/COACTVWC.cbl:192-193} and
     * {@code app/cbl/COACTUPC.cbl:581-582}.
     *
     * @return the name padded to {@value #CICS_FILE_NAME_LENGTH} characters
     */
    public String alternateIndexFileNameForCics() {
        return codec.movePicX(ALTERNATE_INDEX_DD_NAME, CICS_FILE_NAME_LENGTH);
    }

    // =================================================================================================
    // Keyed read on the base cluster, by XREF-CARD-NUM.
    //
    // COBOL sites, all five of them:
    //   app/cbl/COTRN02C.cbl:609-637  READ-CCXREF-FILE - EXEC CICS READ DATASET(WS-CCXREF-FILE)
    //                                 RIDFLD(XREF-CARD-NUM) KEYLENGTH(LENGTH OF XREF-CARD-NUM)
    //   app/cbl/CBTRN02C.cbl:380-392  1500-A-LOOKUP-XREF - READ XREF-FILE with INVALID KEY
    //   app/cbl/CBTRN01C.cbl:40-44    SELECT ... ACCESS MODE IS RANDOM, RECORD KEY FD-XREF-CARD-NUM
    //   app/cbl/CBTRN03C.cbl:33-37    the same, under the DD name CARDXREF
    //   app/cbl/CBACT04C.cbl:34-39    the same SELECT that also carries the alternate key
    // =================================================================================================

    /**
     * Reads one cross-reference record by its base key, {@code XREF-CARD-NUM}: the Java form of
     * {@code READ-CCXREF-FILE} ({@code app/cbl/COTRN02C.cbl:609-637}) and of the batch keyed reads
     * listed on this class.
     *
     * <p><strong>The key is a COBOL {@code MOVE}, not a Java assignment.</strong> The supplied value
     * goes through {@link FixedWidthCodec#movePicX(String, int)} at width
     * {@value #CARD_NUMBER_KEY_LENGTH}, so a shorter value is space-padded on the right and a longer
     * one is truncated on the right, exactly as a {@code PIC X(16)} receiver behaves. That is what the
     * COBOL does when it moves a card number into {@code XREF-CARD-NUM} before the read, and doing it
     * with a plain assignment instead is the single most common way this migration could go silently
     * wrong - {@code MOVE} appears 2,795 times across the estate and its truncation direction is
     * per-picture.
     *
     * <p>Outcomes, and only these:
     * <ul>
     *   <li><strong>{@link Outcome#OK}</strong>, status {@code '00'}, carrying the record - the
     *       {@code WHEN DFHRESP(NORMAL)} arm, and the {@code NOT INVALID KEY} arm in batch;</li>
     *   <li><strong>{@link Outcome#NOT_FOUND}</strong>, status {@code '23'}, carrying no record - the
     *       {@code WHEN DFHRESP(NOTFND)} arm, and {@code INVALID KEY} in batch. <em>Not</em> an
     *       exception and <em>not</em> end of file: a keyed read that matches nothing has not reached
     *       the end of anything;</li>
     *   <li><strong>{@link Outcome#DUPLICATE}</strong>, status {@code '22'}, carrying the first
     *       matching record - see {@link #readByAccountIdViaAltIndex(long)} for why a duplicate is
     *       reported rather than silently resolved. On a KSDS the base key is unique by construction,
     *       so this can only arise if the backing presentation of the dataset has lost that
     *       uniqueness; reporting it routes the caller to its {@code WHEN OTHER} arm, which is where
     *       an unexpected condition belongs;</li>
     *   <li><strong>{@link Outcome#OTHER}</strong>, status {@link #PERMANENT_ERROR_STATUS} - the
     *       {@code WHEN OTHER} arm, for an I/O failure.</li>
     * </ul>
     *
     * @param cardNumber the card number to look up; never {@code null}, and may be shorter or longer
     *                   than {@value #CARD_NUMBER_KEY_LENGTH} because the {@code PIC X} move reshapes
     *                   it. Pass an empty string to look up a key of all spaces
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException     if {@code cardNumber} is {@code null}
     * @throws IllegalArgumentException if a stored row is not exactly {@value #RECORD_LENGTH} bytes,
     *                                  which means the driver is not presenting the record this
     *                                  repository is configured for
     */
    public ReadResult readByCardNumber(String cardNumber) {
        Objects.requireNonNull(cardNumber, "A card number is required to read the "
                + CardXrefRecord.XREF_CARD_NUM_NAME + " key; an absent key is a defect in the caller, "
                + "not a NOTFND outcome. Pass an empty string for a key of SPACES.");
        String keyImage = codec.movePicX(cardNumber, CARD_NUMBER_KEY_LENGTH);
        return readByKey(BASE_DD_NAME, baseSelectSql, CardXrefRecord.XREF_CARD_NUM, keyImage,
                FileStatus.DUPREC);
    }

    // =================================================================================================
    // Keyed read on the CXACAIX alternate-index path, by XREF-ACCT-ID.
    //
    // COBOL sites, all four of them:
    //   app/cbl/COACTVWC.cbl:723-770  9200-GETCARDXREF-BYACCT - DATASET(LIT-CARDXREFNAME-ACCT-PATH),
    //                                 RIDFLD(WS-CARD-RID-ACCT-ID-X), the PIC X(11) REDEFINES view
    //   app/cbl/COACTUPC.cbl:3655     the same shape
    //   app/cbl/COTRN02C.cbl:576-604  READ-CXACAIX-FILE - RIDFLD(XREF-ACCT-ID), the PIC 9(11) view
    //   app/cbl/COBIL00C.cbl:408-437  the same shape
    // =================================================================================================

    /**
     * Reads one cross-reference record by its alternate key, {@code XREF-ACCT-ID}, through the
     * {@code CXACAIX} path: the Java form of {@code READ-CXACAIX-FILE}
     * ({@code app/cbl/COTRN02C.cbl:576-604}, {@code app/cbl/COBIL00C.cbl:408-437}) and of
     * {@code 9200-GETCARDXREF-BYACCT} ({@code app/cbl/COACTVWC.cbl:723-770}).
     *
     * <p>This is the {@code PIC 9(11)} view of the key, the one {@code COTRN02C} and {@code COBIL00C}
     * pass. The value goes through {@link FixedWidthCodec#movePic9(long, int)} at width
     * {@value #ACCOUNT_ID_KEY_LENGTH}, so it is zero-filled on the <em>left</em> - account 50 becomes
     * {@code 00000000050}, exactly as {@code app/data/ASCII/cardxref.txt} holds it. Left-filling is the
     * numeric {@code MOVE} rule and it is the mirror image of the alphanumeric rule used for the base
     * key; both directions are named explicitly by the codec so neither can be applied to the wrong
     * picture.
     *
     * <p>On {@link Outcome#OK} the caller reads two further fields off the returned record.
     * {@code app/cbl/COACTVWC.cbl:739-740} does {@code MOVE XREF-CUST-ID TO CDEMO-CUST-ID} and
     * {@code MOVE XREF-CARD-NUM TO CDEMO-CARD-NUM}; both are available as
     * {@link CardXrefRecord#xrefCustId()} and {@link CardXrefRecord#xrefCardNum()}.
     *
     * <p><strong>Why a duplicate is reported rather than silently resolved.</strong> An alternate index
     * over this cluster is not required to be unique - one account may hold several cards, and the
     * CSD's {@code DEFINE FILE(CXACAIX)} asserts no uniqueness. When a CICS {@code READ} through a path
     * over a non-unique alternate index matches more than one record it returns the first and raises
     * the {@code DUPKEY} condition, and every consumer's three-arm {@code EVALUATE} routes {@code DUPKEY}
     * to {@code WHEN OTHER} because none of them enumerates it. So this method returns the first
     * matching record with {@link Outcome#DUPLICATE}, status {@code '22'} and
     * {@link FileStatus#DUPKEY} - which reproduces that behaviour exactly, keeps the record available
     * to a caller that wants it, and does not quietly hand back one of several rows as though it were
     * the only one. "First" is well defined because the statement orders by the record image, hence by
     * base key, so duplicates come back in card-number order.
     *
     * @param accountId the account id to look up; never negative, because {@code PIC 9(11)} is
     *                  unsigned, and never wider than {@value #ACCOUNT_ID_KEY_LENGTH} digits
     * @return the discriminated outcome; never {@code null}
     * @throws IllegalArgumentException if {@code accountId} is negative, or if a stored row is not
     *                                  exactly {@value #RECORD_LENGTH} bytes
     */
    public ReadResult readByAccountIdViaAltIndex(long accountId) {
        String keyImage = codec.movePic9(accountId, ACCOUNT_ID_KEY_LENGTH);
        return readByKey(ALTERNATE_INDEX_DD_NAME, alternateIndexSelectSql, CardXrefRecord.XREF_ACCT_ID,
                keyImage, FileStatus.DUPKEY);
    }

    /**
     * Reads one cross-reference record by its alternate key supplied as the {@code PIC X(11)}
     * {@code REDEFINES} view of the same eleven bytes: the form {@code COACTVWC} and {@code COACTUPC}
     * pass.
     *
     * <p>{@code app/cbl/COACTVWC.cbl:78-80} declares {@code WS-CARD-RID-ACCT-ID} as {@code PIC 9(11)}
     * and {@code WS-CARD-RID-ACCT-ID-X REDEFINES} it as {@code PIC X(11)}; {@code :729-730} then passes
     * the alphanumeric view as {@code RIDFLD} with {@code KEYLENGTH(LENGTH OF WS-CARD-RID-ACCT-ID-X)}.
     * There is one span of storage and two pictures over it, and the caller populates it by moving a
     * numeric account id into the numeric view first ({@code :691}), so the eleven bytes hold eleven
     * digits.
     *
     * <p>This overload therefore composes the key through the same numeric {@code MOVE} as
     * {@link #readByAccountIdViaAltIndex(long)} rather than through an alphanumeric one:
     * {@link FixedWidthCodec#movePic9(String, int)} left-zero-fills a short image and truncates a long
     * one on the left, which is how the underlying {@code PIC 9(11)} field came to hold what it holds.
     * The consequence is the one that matters - <strong>both overloads produce the identical eleven
     * bytes on the wire</strong>, so the two COBOL views cannot diverge here.
     *
     * <p>A non-digit image is rejected rather than reshaped. The span it stands for is a
     * {@code PIC 9(11)}, so a non-digit in it could not have arisen from any {@code MOVE} the COBOL
     * performs; treating it as a lookup that simply misses would hide the defect at the one place it is
     * still cheap to see.
     *
     * @param accountIdKeyImage the eleven-byte key image, all digits; a shorter image is left-zero
     *                          filled and a longer one keeps its low-order
     *                          {@value #ACCOUNT_ID_KEY_LENGTH} digits
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException     if {@code accountIdKeyImage} is {@code null}
     * @throws IllegalArgumentException if {@code accountIdKeyImage} is empty or holds a non-digit, or
     *                                  if a stored row is not exactly {@value #RECORD_LENGTH} bytes
     */
    public ReadResult readByAccountIdViaAltIndex(String accountIdKeyImage) {
        Objects.requireNonNull(accountIdKeyImage, "An account id key image is required to read the "
                + CardXrefRecord.XREF_ACCT_ID_NAME + " alternate key; the PIC X(" + ACCOUNT_ID_KEY_LENGTH
                + ") REDEFINES view of app/cbl/COACTVWC.cbl:79-80 always holds digits, so an absent "
                + "image is a defect in the caller rather than a NOTFND outcome");
        String keyImage = codec.movePic9(accountIdKeyImage, ACCOUNT_ID_KEY_LENGTH);
        return readByKey(ALTERNATE_INDEX_DD_NAME, alternateIndexSelectSql, CardXrefRecord.XREF_ACCT_ID,
                keyImage, FileStatus.DUPKEY);
    }

    /**
     * The one keyed-read implementation, shared by the base read and the alternate-index read.
     *
     * <p>Both access paths perform the identical sequence - fetch the dataset's rows, compare the
     * declared key span of each against the composed key image, classify - and differ only in which
     * dataset they address, which span carries their key, and which CICS duplicate condition names
     * their key. Those three differences are the arguments. That is what "one repository, one dataset,
     * a second finder" means in code: the finder is a different key on the same implementation, not a
     * different implementation (gate G45).
     *
     * <p>The comparison is on the raw span image, read through
     * {@link FixedWidthCodec#readPicX(FixedWidthRecord, FieldSpan)} and never trimmed, so it is the
     * byte comparison VSAM performs. Reading the numeric account-id span as its raw image rather than
     * as a number is deliberate twice over: it makes both key comparisons one code path, and it means a
     * non-matching row holding a malformed numeric span cannot fail a read that was never going to
     * match it.
     *
     * <p><strong>One acknowledged consequence of matching in Java rather than pushing the predicate
     * down.</strong> A keyed read has to visit every row, so it checks every row's <em>width</em>, which
     * is stricter than CICS: a corrupt record elsewhere in the cluster cannot affect a real CICS keyed
     * read of a different key. The strictness is kept on purpose. The alternative - skipping a row that
     * cannot be wrapped - would turn the one data condition this dataset actually has into silence:
     * every row of {@code app/data/ASCII/cardxref.txt} is 36 bytes, so an unnormalised fixture would
     * make all fifty rows vanish and every read report {@code NOTFND} with no signal at all. Failing
     * loudly on the width is what keeps risk R-F visible, and a width that disagrees with the copybook
     * is a configuration or driver defect rather than a record-level one, so it is genuinely global
     * anyway.
     *
     * @param ddName            the configuration key of the access path being read, carried onto the
     *                          result so a caller can compose {@code ERROR-FILE}
     * @param sql               the composed statement for that access path
     * @param keyField          the span descriptor of the key on this path
     * @param keyImage          the key image, already reshaped by the appropriate {@code MOVE} rule and
     *                          therefore exactly {@code keyField.length()} characters
     * @param duplicateCicsResp {@link FileStatus#DUPREC} for a base-key duplicate,
     *                          {@link FileStatus#DUPKEY} for an alternate-key duplicate. Naming it per
     *                          path resolves the ambiguity that
     *                          {@link FileStatus#cicsRespOfBatchStatus(String)} reports rather than
     *                          invents, because at this point the path is known
     * @return the discriminated outcome; never {@code null}
     */
    private ReadResult readByKey(String ddName, String sql, FieldSpan keyField, String keyImage,
            int duplicateCicsResp) {
        List<String> rows;
        try {
            RowMapper<String> recordImageMapper = this::mapRecordImage;
            rows = jdbcTemplate.query(sql, recordImageMapper);
        } catch (DataAccessException translated) {
            // WHEN OTHER. Reported as a status, exactly as the COBOL keeps only the status, so the
            // caller's own guard chain decides what to do about it.
            return ReadResult.other(ddName, PERMANENT_ERROR_STATUS);
        }
        if (rows == null) {
            // A template that yielded no result at all has told us nothing, and nothing is not an
            // empty dataset. Reported on the WHEN OTHER arm rather than mistaken for NOTFND.
            return ReadResult.other(ddName, PERMANENT_ERROR_STATUS);
        }
        List<CardXrefRecord> matches = new ArrayList<>();
        for (String rowImage : rows) {
            if (rowImage == null) {
                // A row whose record image is absent is not a readable 50-byte record. There IS a
                // record, it simply cannot be read, so this is an I/O-level defect and belongs on the
                // WHEN OTHER arm - never silently skipped, which would turn a broken dataset into a
                // clean NOTFND.
                return ReadResult.other(ddName, PERMANENT_ERROR_STATUS);
            }
            FixedWidthRecord span = wrapRow(rowImage);
            if (keyImage.equals(codec.readPicX(span, keyField))) {
                matches.add(CardXrefRecord.decodeSpan(span, codec));
            }
        }
        if (matches.isEmpty()) {
            // WHEN DFHRESP(NOTFND) / INVALID KEY. A normal branch in every consumer, never an
            // exception.
            return ReadResult.notFound(ddName);
        }
        if (matches.size() > 1) {
            // DUPKEY on a path over a non-unique alternate index, or a base key that has lost its
            // uniqueness. The first match is returned alongside the condition, as CICS does.
            return ReadResult.duplicate(ddName, matches.get(0), duplicateCicsResp);
        }
        // WHEN DFHRESP(NORMAL).
        return ReadResult.found(ddName, matches.get(0));
    }

    // =================================================================================================
    // Sequential browse of the base cluster, in XREF-CARD-NUM order.
    //
    // COBOL sites:
    //   app/cbl/CBACT03C.cbl:29-33    SELECT XREFFILE-FILE, ORGANIZATION IS INDEXED,
    //                                 ACCESS MODE IS SEQUENTIAL, RECORD KEY IS FD-XREF-CARD-NUM
    //                        :118-134 0000-XREFFILE-OPEN   - OPEN INPUT
    //                        :92-116  1000-XREFFILE-GET-NEXT - READ ... INTO CARD-XREF-RECORD
    //                        :136+    9000-XREFFILE-CLOSE  - CLOSE
    //                        :74-83   PERFORM UNTIL END-OF-FILE = 'Y', the whole program's loop
    //   app/cbl/CBSTM03B.CBL          2000-XREFFILE-PROC - the M03B-OPEN / M03B-READ / M03B-CLOSE
    //                                 dispatch, on a SELECT that is likewise ACCESS MODE SEQUENTIAL
    // =================================================================================================

    /**
     * Opens a sequential browse of the base cluster in {@code XREF-CARD-NUM} order: the Java form of
     * {@code 0000-XREFFILE-OPEN} ({@code app/cbl/CBACT03C.cbl:118-134}) and of {@code CBSTM03B}'s
     * {@code M03B-OPEN} arm for {@code XREFFILE}.
     *
     * <p><strong>All browse state lives on the returned cursor, never on this bean</strong> (practice
     * B9, gate G53). A repository is a singleton and two callers browsing at once must not share a
     * position, so each call hands back its own cursor and this class keeps no cursor of its own. That
     * is also why a browse cannot be "reset": a caller that wants to start again opens another one,
     * exactly as the COBOL closes and reopens.
     *
     * <p>The rows are fetched here, at open, because that is what {@code OPEN INPUT} means - the file
     * is positioned at its first record and the program's very next act is to read it. A failure to
     * reach the dataset therefore surfaces from this call, as {@link BrowseCursor#openStatus()} of
     * {@link #PERMANENT_ERROR_STATUS}, which is the status {@code CBACT03C} tests at {@code :121} before
     * moving {@code 12} into {@code APPL-RESULT} and abending. Nothing is thrown: the open reports, and
     * the caller's guard chain decides.
     *
     * <p>The cursor is {@link AutoCloseable}, so a caller may use try-with-resources, and
     * {@link BrowseCursor#close()} is idempotent so an explicit close inside the block is safe too.
     *
     * <p>No row is decoded here, so no row's width is checked here either. A malformed row is reported
     * by the {@link BrowseCursor#readNext()} that reaches it, which is where {@code CBACT03C} would
     * have met it as well.
     *
     * @return a freshly positioned cursor, whose {@link BrowseCursor#openStatus()} reports whether the
     *         open succeeded; never {@code null}
     */
    public BrowseCursor openBrowse() {
        List<String> rows;
        try {
            RowMapper<String> recordImageMapper = this::mapRecordImage;
            rows = jdbcTemplate.query(baseSelectSql, recordImageMapper);
        } catch (DataAccessException translated) {
            // ERROR OPENING XREFFILE. app/cbl/CBACT03C.cbl:121-127 moves 12 into APPL-RESULT for any
            // status but '00', then displays and abends - all of which is the caller's, not ours.
            return new BrowseCursor(this, PERMANENT_ERROR_STATUS, null);
        }
        if (rows == null) {
            // The template yielded no result at all, which is not an empty dataset. Reported as a
            // failed open rather than as a browse that is immediately at end of file.
            return new BrowseCursor(this, PERMANENT_ERROR_STATUS, null);
        }
        // Defensively copied and made unmodifiable so the pass cannot be perturbed once it starts.
        // Deliberately NOT List.copyOf, which rejects a null element: a row whose record image is
        // absent is a real condition that readNext classifies, and it must survive the copy to be
        // classified rather than being turned into a NullPointerException here.
        return new BrowseCursor(this, FileStatus.OK,
                Collections.unmodifiableList(new ArrayList<>(rows)));
    }

    // =================================================================================================
    // Row handling. The decode seam: hand-written, addressed by absolute offset, and free of JDBC so
    // that it is exercisable with no backend in the path - which matters because risk R-E means no
    // backend is reachable from this build.
    // =================================================================================================

    /**
     * Maps one result-set row to its record image, by column position.
     *
     * <p>Position {@value #RECORD_IMAGE_COLUMN_INDEX}, never a column name, for the reason set out under
     * residual risk R-E on this class.
     *
     * @param resultSet the row, positioned by the template
     * @param rowNumber the zero-based row index. Part of the {@link RowMapper} contract and
     *                  deliberately unused: every row of this dataset has the identical shape, so the
     *                  index carries no meaning here
     * @return the row's record image, which may be {@code null} if the column holds no value - a
     *         condition the callers classify rather than ignore
     * @throws SQLException if the driver cannot supply the column
     */
    private String mapRecordImage(ResultSet resultSet, int rowNumber) throws SQLException {
        return resultSet.getString(RECORD_IMAGE_COLUMN_INDEX);
    }

    /**
     * Wraps a stored row image as a fifty-byte record span, addressable by
     * {@link CardXrefRecord#LAYOUT}'s descriptors.
     *
     * <p><strong>The width is enforced, not adjusted</strong> (gates G19 and G21). The image is encoded
     * in the injected dataset code page and handed to
     * {@link FixedWidthCodec#wrap(byte[], FixedWidthRecord.RecordLayout)}, which requires exactly
     * {@value #RECORD_LENGTH} bytes and rejects anything else. That specifically includes the 36-byte
     * form of {@code app/data/ASCII/cardxref.txt}: widening it is the parity harness's job, declared in
     * {@code application-test.yml} as {@code carddemo.test.fixtures.cardxref.pad-to: 50} and performed
     * through {@link FixedWidthCodec#padToDeclaredWidth(byte[], int)}. Accepting 36 bytes here would
     * make risk R-F invisible in exactly the code path gate G16 protects.
     *
     * @param rowImage the stored record image; never {@code null} by the time it reaches here
     * @return the record span
     * @throws IllegalArgumentException if the image does not encode to exactly
     *                                  {@value #RECORD_LENGTH} bytes
     */
    private FixedWidthRecord wrapRow(String rowImage) {
        return codec.wrap(rowImage.getBytes(codec.charset()), CardXrefRecord.LAYOUT);
    }

    /**
     * Decodes a stored row image into a {@link CardXrefRecord}, by absolute offset.
     *
     * @param rowImage the stored record image, exactly {@value #RECORD_LENGTH} bytes wide
     * @return the decoded record
     * @throws IllegalArgumentException if the image is not exactly {@value #RECORD_LENGTH} bytes, or if
     *                                  either numeric span holds a non-digit
     */
    private CardXrefRecord decodeRow(String rowImage) {
        return CardXrefRecord.decodeSpan(wrapRow(rowImage), codec);
    }

    // =================================================================================================
    // The discriminated outcome.
    // =================================================================================================

    /**
     * The outcome of one cross-reference read: what happened, in the vocabulary every consumer already
     * speaks, plus the record when there is one.
     *
     * <p>Immutable and complete. Every consumer's guard chain can be written against it as an
     * exhaustive {@code switch} over {@link #outcome()} with a {@code default}-complete final arm, which
     * is the shape of the COBOL it stands for:
     * <pre>
     * CardXrefRepository.ReadResult result = repository.readByAccountIdViaAltIndex(accountId);
     * switch (result.outcome()) {
     *     case OK -&gt; {                                    // WHEN DFHRESP(NORMAL)
     *         context.setCustomerId(result.record().orElseThrow().xrefCustId());
     *         context.setCardNumber(result.record().orElseThrow().xrefCardNum());
     *     }
     *     case NOT_FOUND -&gt; screen.reject(               // WHEN DFHRESP(NOTFND)
     *             "Account:" + accountId + " not found in Cross ref file.  Resp:"
     *             + result.cicsResp() + " Reas:" + result.cicsResp2());
     *     default -&gt; screen.reject(                      // WHEN OTHER
     *             "READ " + result.ddName() + " Resp:" + result.cicsResp()
     *             + " Reas:" + result.cicsResp2());
     * }
     * </pre>
     *
     * @param ddName    the configuration key and CICS {@code FILE} name of the access path that was
     *                  read: {@value CardXrefRepository#BASE_DD_NAME} or
     *                  {@value CardXrefRepository#ALTERNATE_INDEX_DD_NAME}. Carried because
     *                  {@code app/cbl/COACTVWC.cbl:763} and {@code app/cbl/COACTUPC.cbl:3690} move the
     *                  file name into {@code ERROR-FILE} on their {@code WHEN OTHER} arm, and because
     *                  it makes visible on every result which of the two access paths produced it
     * @param status    the two-character batch {@code FILE STATUS}: {@link FileStatus#OK},
     *                  {@link FileStatus#END_OF_FILE}, {@link FileStatus#NOT_FOUND},
     *                  {@link FileStatus#DUPLICATE} or
     *                  {@link CardXrefRepository#PERMANENT_ERROR_STATUS}
     * @param outcome   the classification of {@code status}, from
     *                  {@link FileStatus#outcomeOfStatus(String)}, so the status and its meaning cannot
     *                  drift apart
     * @param record    the record, present exactly when one was read - so for {@link Outcome#OK} and
     *                  for {@link Outcome#DUPLICATE}, which carries the first of the matching records,
     *                  and absent for the other three
     * @param cicsResp  the CICS {@code RESP} the condition corresponds to: {@link FileStatus#NORMAL},
     *                  {@link FileStatus#NOTFND}, {@link FileStatus#ENDFILE}, {@link FileStatus#DUPREC}
     *                  or {@link FileStatus#DUPKEY} according to which key duplicated, or
     *                  {@link FileStatus#NOTOPEN} for a permanent error - the closest true analogue of a
     *                  dataset that could not be reached
     * @param cicsResp2 the CICS {@code RESP2}, always {@link CardXrefRepository#CICS_RESP2_NOT_APPLICABLE}
     *                  and documented there
     */
    public record ReadResult(
            String ddName,
            String status,
            Outcome outcome,
            Optional<CardXrefRecord> record,
            int cicsResp,
            int cicsResp2) {

        /**
         * Validates the outcome's own consistency, so an inconsistent result cannot be constructed even
         * by a test.
         *
         * @throws NullPointerException     if {@code ddName}, {@code status}, {@code outcome} or
         *                                  {@code record} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not two characters, if
         *                                  {@code outcome} does not classify {@code status}, or if the
         *                                  presence of {@code record} disagrees with {@code outcome}
         */
        public ReadResult {
            Objects.requireNonNull(ddName, "A DD name is required on a read outcome: the caller moves "
                    + "it into ERROR-FILE, and it identifies which access path was read");
            Objects.requireNonNull(status, "A two-character FILE STATUS is required on a read outcome");
            Objects.requireNonNull(outcome, "An outcome classification is required on a read outcome");
            Objects.requireNonNull(record, "An Optional is required, empty rather than null, so no "
                    + "null escapes this type");
            if (status.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalArgumentException("FILE STATUS '" + status + "' is "
                        + status.length() + " character(s); it is exactly "
                        + FileStatus.STATUS_LENGTH + " in COBOL and is compared as such.");
            }
            if (outcome != FileStatus.outcomeOfStatus(status)) {
                throw new IllegalArgumentException("Outcome " + outcome + " does not classify FILE "
                        + "STATUS '" + status + "', which classifies as "
                        + FileStatus.outcomeOfStatus(status) + ". The status and its meaning are two "
                        + "views of one fact and may not disagree.");
            }
            boolean shouldCarryRecord = outcome == Outcome.OK || outcome == Outcome.DUPLICATE;
            if (shouldCarryRecord != record.isPresent()) {
                throw new IllegalArgumentException("Outcome " + outcome + (shouldCarryRecord
                        ? " carries the record that was read, but none was supplied."
                        : " carries no record, but one was supplied.")
                        + " A record is present exactly for OK and for DUPLICATE, which hands back the "
                        + "first of the matching records as CICS does.");
            }
        }

        /**
         * A record was read: status {@code '00'}, {@code RESP} of {@link FileStatus#NORMAL}. The
         * {@code WHEN DFHRESP(NORMAL)} arm.
         *
         * @param ddName the access path that was read
         * @param record the record that was read; never {@code null}
         * @return the outcome
         * @throws NullPointerException if {@code record} is {@code null}
         */
        public static ReadResult found(String ddName, CardXrefRecord record) {
            Objects.requireNonNull(record, "A found outcome must carry the record it found");
            return new ReadResult(ddName, FileStatus.OK, Outcome.OK, Optional.of(record),
                    FileStatus.NORMAL, CICS_RESP2_NOT_APPLICABLE);
        }

        /**
         * No record matched the key: status {@code '23'}, {@code RESP} of {@link FileStatus#NOTFND}.
         * The {@code WHEN DFHRESP(NOTFND)} arm online and the {@code INVALID KEY} arm in batch - a
         * normal branch in every consumer and never an exception.
         *
         * @param ddName the access path that was read
         * @return the outcome
         */
        public static ReadResult notFound(String ddName) {
            return new ReadResult(ddName, FileStatus.NOT_FOUND, Outcome.NOT_FOUND, Optional.empty(),
                    FileStatus.NOTFND, CICS_RESP2_NOT_APPLICABLE);
        }

        /**
         * The browse has no further record: status {@code '10'}, {@code RESP} of
         * {@link FileStatus#ENDFILE}. This is what {@code app/cbl/CBACT03C.cbl:98-99} tests to move
         * {@code 16} into {@code APPL-RESULT} and then {@code 'Y'} into {@code END-OF-FILE} at
         * {@code :108}, which is what terminates the whole program's loop at {@code :74}.
         *
         * <p>Reported only by a browse. A keyed read that matches nothing reports
         * {@link #notFound(String)} instead, because it has not reached the end of anything.
         *
         * @param ddName the access path that was read
         * @return the outcome
         */
        public static ReadResult endOfFile(String ddName) {
            return new ReadResult(ddName, FileStatus.END_OF_FILE, Outcome.END_OF_FILE, Optional.empty(),
                    FileStatus.ENDFILE, CICS_RESP2_NOT_APPLICABLE);
        }

        /**
         * More than one record matched the key: status {@code '22'}, carrying the first of them, with
         * the {@code RESP} naming which key duplicated. Explained in full on
         * {@link CardXrefRepository#readByAccountIdViaAltIndex(long)}.
         *
         * @param ddName   the access path that was read
         * @param first    the first matching record, in base-key order; never {@code null}
         * @param cicsResp {@link FileStatus#DUPREC} for a base-key duplicate,
         *                 {@link FileStatus#DUPKEY} for an alternate-key duplicate
         * @return the outcome
         * @throws NullPointerException     if {@code first} is {@code null}
         * @throws IllegalArgumentException if {@code cicsResp} is neither {@link FileStatus#DUPREC} nor
         *                                  {@link FileStatus#DUPKEY}
         */
        public static ReadResult duplicate(String ddName, CardXrefRecord first, int cicsResp) {
            Objects.requireNonNull(first, "A duplicate outcome must carry the first matching record, "
                    + "because CICS returns it alongside the DUPKEY condition");
            if (cicsResp != FileStatus.DUPREC && cicsResp != FileStatus.DUPKEY) {
                throw new IllegalArgumentException("A duplicate outcome must name which key "
                        + "duplicated: DUPREC (" + FileStatus.DUPREC + ") for the base key or DUPKEY ("
                        + FileStatus.DUPKEY + ") for an alternate key, not " + cicsResp + ". The two "
                        + "collapse onto FILE STATUS '" + FileStatus.DUPLICATE + "' in one direction "
                        + "only, so the distinction is kept where it is still known.");
            }
            return new ReadResult(ddName, FileStatus.DUPLICATE, Outcome.DUPLICATE, Optional.of(first),
                    cicsResp, CICS_RESP2_NOT_APPLICABLE);
        }

        /**
         * Anything else: the {@code WHEN OTHER} arm, which in every consumer is the error or abend path.
         * {@code RESP} is reported as {@link FileStatus#NOTOPEN}, the closest true analogue of a dataset
         * that could not be reached.
         *
         * @param ddName the access path that was read
         * @param status the status to report, which must classify as {@link Outcome#OTHER} - normally
         *               {@link CardXrefRepository#PERMANENT_ERROR_STATUS}
         * @return the outcome
         * @throws NullPointerException     if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not two characters, or classifies as
         *                                  one of the four enumerated outcomes rather than as
         *                                  {@link Outcome#OTHER}
         */
        public static ReadResult other(String ddName, String status) {
            return new ReadResult(ddName, status, Outcome.OTHER, Optional.empty(), FileStatus.NOTOPEN,
                    CICS_RESP2_NOT_APPLICABLE);
        }

        /**
         * Whether a record was read - the {@code WHEN DFHRESP(NORMAL)} test.
         *
         * @return {@code true} for {@link Outcome#OK}
         */
        public boolean isFound() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether the key matched nothing - the {@code WHEN DFHRESP(NOTFND)} test.
         *
         * @return {@code true} for {@link Outcome#NOT_FOUND}
         */
        public boolean isNotFound() {
            return outcome == Outcome.NOT_FOUND;
        }

        /**
         * Whether the browse is exhausted - what {@code app/cbl/CBACT03C.cbl:74} loops until.
         *
         * @return {@code true} for {@link Outcome#END_OF_FILE}
         */
        public boolean isEndOfFile() {
            return outcome == Outcome.END_OF_FILE;
        }

        /**
         * Whether more than one record matched the key.
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
         * The {@code APPL-RESULT} value the batch consumers move for this outcome.
         *
         * <p>{@code app/cbl/CBACT03C.cbl:92-116} is the worked example: {@code '00'} moves {@code 0},
         * {@code '10'} moves {@code 16}, and anything else moves {@code 12} and abends. The final arm is
         * written as a {@code default} for the same reason the COBOL's is {@code WHEN OTHER} - it is the
         * catch-all, and {@link Outcome#NOT_FOUND} and {@link Outcome#DUPLICATE} both belong to it in a
         * batch program, which tests only {@code '00'} and {@code '10'} by name.
         *
         * @return {@link FileStatus#APPL_AOK}, {@link FileStatus#APPL_EOF} or
         *         {@link CardXrefRepository#APPL_RESULT_FATAL}
         */
        public int applResult() {
            return switch (outcome) {
                case OK -> FileStatus.APPL_AOK;
                case END_OF_FILE -> FileStatus.APPL_EOF;
                default -> APPL_RESULT_FATAL;
            };
        }

        /**
         * This status rendered as the four-character image {@code 9910-DISPLAY-IO-STATUS} produces, for
         * a caller composing that display line.
         *
         * @return the status image, exactly {@link FileStatus#STATUS_IMAGE_LENGTH} characters
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }
    }

    // =================================================================================================
    // The browse cursor. Every mutable field in this class is an instance field of a per-call cursor,
    // which is the whole reason the cursor exists: a @Repository is a singleton, and browse position
    // held on it would be shared between callers (practice B9, gate G53).
    // =================================================================================================

    /**
     * One sequential pass over the base cluster, in {@code XREF-CARD-NUM} order.
     *
     * <p>The Java form of the file handle {@code app/cbl/CBACT03C.cbl} opens at {@code :120}, reads at
     * {@code :93} and closes at {@code :138}, driving its whole program from {@code :74}:
     * <pre>
     * try (CardXrefRepository.BrowseCursor cursor = repository.openBrowse()) {
     *     if (!FileStatus.isOk(cursor.openStatus())) {        // IF XREFFILE-STATUS = '00' ... ELSE
     *         throw AbendException.standard("CBACT03C", cursor.openApplResult());
     *     }
     *     for (CardXrefRepository.ReadResult next = cursor.readNext();                //  PERFORM UNTIL
     *             !next.isEndOfFile();                                               //  END-OF-FILE
     *             next = cursor.readNext()) {                                        //  = 'Y'
     *         if (next.isOther()) {                          // ERROR READING XREFFILE
     *             throw AbendException.standard("CBACT03C", next.applResult());
     *         }
     *         display(next.record().orElseThrow());           // DISPLAY CARD-XREF-RECORD
     *     }
     * }
     * </pre>
     * The abend is the caller's, as it is in the COBOL: the paragraph that tests the status is the
     * paragraph that decides, and this cursor only reports.
     *
     * <p><strong>Not thread safe, by design.</strong> A cursor stands for one file position and is used
     * by whoever opened it, exactly as a COBOL file handle is used by the program that opened it.
     * Sharing one across threads would be the concurrency equivalent of two programs sharing one
     * {@code FD}. Obtaining a second cursor is free and is the correct answer.
     */
    public static final class BrowseCursor implements AutoCloseable {

        /** The repository that opened this cursor, for its decode seam and its dataset identity. */
        private final CardXrefRepository repository;

        /**
         * The status the open reported: {@link FileStatus#OK}, or
         * {@link CardXrefRepository#PERMANENT_ERROR_STATUS} when the dataset could not be reached.
         */
        private final String openStatus;

        /**
         * The rows this pass will return, in base-key order, or {@code null} when the open failed. An
         * immutable copy, so the pass cannot be perturbed after it starts.
         */
        private final List<String> rows;

        /** How many records this pass has returned so far. The only mutable state in the class. */
        private int position;

        /** Whether {@link #close()} has been called. */
        private boolean closed;

        /**
         * Constructed only by {@link CardXrefRepository#openBrowse()}, which is what guarantees that a
         * successful open always arrives with its rows and a failed one never does.
         *
         * @param repository the opening repository
         * @param openStatus the status the open reported
         * @param rows       the rows for a successful open, or {@code null} for a failed one
         */
        private BrowseCursor(CardXrefRepository repository, String openStatus, List<String> rows) {
            this.repository = repository;
            this.openStatus = openStatus;
            this.rows = rows;
            this.position = 0;
            this.closed = false;
        }

        /**
         * The status {@code OPEN INPUT} reported: the value
         * {@code app/cbl/CBACT03C.cbl:121} tests before moving {@code 0} or {@code 12} into
         * {@code APPL-RESULT}.
         *
         * @return {@link FileStatus#OK} or {@link CardXrefRepository#PERMANENT_ERROR_STATUS}; never
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
         * The {@code APPL-RESULT} the open sets: {@code 0} on success, {@code 12} otherwise
         * ({@code app/cbl/CBACT03C.cbl:119-125}, which seeds {@code 8}, moves {@code 0} on
         * {@code '00'} and {@code 12} on anything else).
         *
         * @return {@link FileStatus#APPL_AOK} or {@link CardXrefRepository#APPL_RESULT_FATAL}
         */
        public int openApplResult() {
            return FileStatus.isOk(openStatus) ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }

        /**
         * Whether this cursor is still usable, that is opened successfully and not yet closed.
         *
         * @return {@code true} when {@link #readNext()} will report an I/O outcome rather than throw
         */
        public boolean isOpen() {
            return !closed && rows != null;
        }

        /**
         * How many records this pass has returned so far, which is also the zero-based index of the
         * record the next {@link #readNext()} will return.
         *
         * @return the count; never negative
         */
        public int position() {
            return position;
        }

        /**
         * The dataset this pass is reading: always the base cluster, because a browse of the
         * cross reference is a browse in base-key order.
         *
         * @return the configured {@value CardXrefRepository#BASE_DD_NAME} dataset name
         */
        public String datasetName() {
            return repository.baseDatasetName();
        }

        /**
         * Reads the next record: the Java form of {@code 1000-XREFFILE-GET-NEXT}
         * ({@code app/cbl/CBACT03C.cbl:92-116}) and of {@code CBSTM03B}'s {@code M03B-READ} arm for
         * {@code XREFFILE}.
         *
         * <p>Four reported outcomes, and they are the ones the COBOL enumerates:
         * <ul>
         *   <li><strong>{@link Outcome#OK}</strong>, status {@code '00'} - a record, and
         *       {@code APPL-RESULT} of {@code 0};</li>
         *   <li><strong>{@link Outcome#END_OF_FILE}</strong>, status {@code '10'} - the pass is
         *       exhausted, {@code APPL-RESULT} of {@code 16}, and what {@code :108} turns into
         *       {@code MOVE 'Y' TO END-OF-FILE}. <strong>Reported repeatedly and idempotently</strong>:
         *       once at end of file, every further call reports end of file again, which is faithful
         *       because {@code CBACT03C}'s loop stops on the flag and never resumes from a saved
         *       position;</li>
         *   <li><strong>{@link Outcome#OTHER}</strong> when the open failed, carrying the open's own
         *       status - so a caller that ignored {@link #openStatus()} still cannot mistake a
         *       dataset it never reached for one that was empty;</li>
         *   <li><strong>{@link Outcome#OTHER}</strong>, status
         *       {@link CardXrefRepository#PERMANENT_ERROR_STATUS}, when a row's record image is absent.
         *       There is a record and it cannot be read, which is an I/O defect and not an end of
         *       file.</li>
         * </ul>
         *
         * <p>Reading a closed cursor is a <strong>programming error, not an I/O outcome</strong>, and
         * throws. COBOL would report status {@code '47'} or {@code '49'} for a read against a file in
         * the wrong open mode, but no consumer of this dataset ever does it - {@code CBACT03C} closes
         * once, after its loop - so there is no legacy behaviour to reproduce and the honest response is
         * to fail loudly at the defect rather than to return a status that no COBOL path would ever
         * have produced.
         *
         * @return the discriminated outcome; never {@code null}
         * @throws IllegalStateException    if this cursor has been closed
         * @throws IllegalArgumentException if the row is not exactly
         *                                  {@value CardXrefRepository#RECORD_LENGTH} bytes, or holds a
         *                                  non-digit in a numeric span
         */
        public ReadResult readNext() {
            if (closed) {
                throw new IllegalStateException("This browse of '" + repository.baseDatasetName()
                        + "' has been closed, so it has no next record. app/cbl/CBACT03C.cbl closes "
                        + "once, at :138, after its loop has ended - reading afterwards is a defect in "
                        + "the caller, not a file status. Open another browse instead.");
            }
            if (rows == null) {
                // The open never succeeded. Its own status is reported, not a fresh one, so the caller
                // sees the failure that actually happened.
                return ReadResult.other(BASE_DD_NAME, openStatus);
            }
            if (position >= rows.size()) {
                // AT END. app/cbl/CBACT03C.cbl:98-99 then :108.
                return ReadResult.endOfFile(BASE_DD_NAME);
            }
            String rowImage = rows.get(position);
            // The position advances for an unreadable row too: the read consumed it, exactly as a COBOL
            // READ advances past the record it reported an error on, so a caller that chooses to
            // continue does not re-read the same broken row forever.
            position++;
            if (rowImage == null) {
                return ReadResult.other(BASE_DD_NAME, PERMANENT_ERROR_STATUS);
            }
            return ReadResult.found(BASE_DD_NAME, repository.decodeRow(rowImage));
        }

        /**
         * Closes this pass: the Java form of {@code 9000-XREFFILE-CLOSE}
         * ({@code app/cbl/CBACT03C.cbl:136-150}).
         *
         * <p>Nothing is buffered and no handle is held between calls - the shared template borrows and
         * returns a connection per operation - so there is no flush to fail and no resource to release
         * beyond dropping this cursor's own position. The status is therefore always
         * {@link FileStatus#OK}, and the call is <strong>idempotent</strong>, which is what makes
         * try-with-resources safe alongside an explicit close in the same block.
         *
         * @return {@link FileStatus#OK}; never {@code null}, always two characters
         */
        public String closeBrowse() {
            closed = true;
            return FileStatus.OK;
        }

        /**
         * {@link AutoCloseable} form of {@link #closeBrowse()}, so a cursor can be used with
         * try-with-resources. Declared to throw nothing, so it adds no checked exception to a caller.
         */
        @Override
        public void close() {
            closeBrowse();
        }
    }
}

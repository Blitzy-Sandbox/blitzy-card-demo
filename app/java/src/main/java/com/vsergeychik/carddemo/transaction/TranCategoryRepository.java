package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.transaction.model.TranCategoryRecord;

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
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The one data-access component for the {@code TRANCATG} transaction-category lookup: a 60-byte KSDS
 * addressed by a <strong>6-byte</strong> key, opened, read by key, and closed - and nothing else.
 *
 * <h2>One consumer, three verbs, and the grep that proves it</h2>
 * <p>{@code TRANCATG} is read by exactly one program, {@code app/cbl/CBTRN03C.cbl} - the transaction
 * detail report - and {@code grep -n "TRANCATG" app/cbl/*.cbl} returns nineteen hits across that one
 * file. Every one of them is accounted for here, and only three are I/O:
 * <ul>
 *   <li>{@code :45-49} {@code SELECT TRANCATG-FILE ASSIGN TO TRANCATG / ORGANIZATION IS INDEXED /
 *       ACCESS MODE IS RANDOM / RECORD KEY IS FD-TRAN-CAT-KEY / FILE STATUS IS TRANCATG-STATUS} - the
 *       access path, and the declaration that fixes the key;</li>
 *   <li>{@code :77-82} the {@code FD}, {@code :108} {@code COPY CVTRA04Y.}, {@code :109-111} the
 *       two-byte {@code TRANCATG-STATUS} group - declarations, not operations;</li>
 *   <li>{@code :450} {@code OPEN INPUT TRANCATG-FILE} - reproduced by {@link #open()};</li>
 *   <li>{@code :505} {@code READ TRANCATG-FILE INTO TRAN-CAT-RECORD ... INVALID KEY} - reproduced by
 *       {@link #readByKey(String, int)};</li>
 *   <li>{@code :589} {@code CLOSE TRANCATG-FILE} - reproduced by {@link #close()}.</li>
 * </ul>
 *
 * <p><strong>There is no {@code WRITE}, no {@code REWRITE}, no {@code DELETE}, no {@code START} and no
 * {@code READ NEXT} anywhere in the estate for this dataset</strong>, so this class offers none. That
 * is a deliberate design decision rather than an omission: a repository exposes only the access paths
 * the COBOL actually performs, so no unused SQL surface is invented and no caller can reach an
 * operation the legacy system never performed. The CICS-style capability flags do not apply either -
 * {@code TRANCATG} is not one of the eight {@code DEFINE FILE} entries of {@code app/csd/CARDDEMO.CSD}
 * at all. It is a batch-only DD, bound by {@code app/jcl/TRANREPT.jcl:71-72} under
 * {@code STEP10R EXEC PGM=CBTRN03C} at {@code :59}, listed among that step's <em>input</em> files, and
 * bound identically by {@code app/proc/TRANREPT.prc:69-70}.
 *
 * <h2>The layout, and four independent confirmations of it</h2>
 * <p>{@code app/cpy/CVTRA04Y.cpy} declares {@code 01 TRAN-CAT-RECORD} under the header comment
 * <em>"Data-structure for transaction category type (RECLN = 60)"</em>:
 * <table border="1">
 *   <caption>{@code 01 TRAN-CAT-RECORD} - 60 bytes, key 6</caption>
 *   <tr><th>COBOL item</th><th>PICTURE</th><th>1-based</th><th>0-based offset</th><th>Length</th>
 *       <th>Java type</th></tr>
 *   <tr><td>{@code 05 TRAN-CAT-KEY}</td><td>group</td><td>1-6</td><td>0</td><td>6</td>
 *       <td>the key image, a sub-span over the two items below</td></tr>
 *   <tr><td>{@code 10 TRAN-TYPE-CD}</td><td>{@code X(02)}</td><td>1-2</td><td>0</td><td>2</td>
 *       <td>{@link String}, right space-padded</td></tr>
 *   <tr><td>{@code 10 TRAN-CAT-CD}</td><td>{@code 9(04)}</td><td>3-6</td><td>2</td><td>4</td>
 *       <td>{@code int}, left zero-filled</td></tr>
 *   <tr><td>{@code 05 TRAN-CAT-TYPE-DESC}</td><td>{@code X(50)}</td><td>7-56</td><td>6</td><td>50</td>
 *       <td>{@link String}, <strong>untrimmed</strong></td></tr>
 *   <tr><td>{@code 05 FILLER}</td><td>{@code X(04)}</td><td>57-60</td><td>56</td><td>4</td>
 *       <td>reserved span, retained verbatim</td></tr>
 * </table>
 *
 * <p>No COBOL can be executed in this environment, so every width here is <strong>statically
 * derived</strong>. To keep a transcription error from becoming an invisible parity defect, the
 * geometry was corroborated four ways, from four different kinds of artefact - and all four agree:
 * <ol>
 *   <li><strong>the copybook</strong> - {@code 2 + 4 + 50 + 4 = 60}, key {@code 2 + 4 = 6};</li>
 *   <li><strong>the consumer's own {@code FD}</strong> - {@code app/cbl/CBTRN03C.cbl:78-82} splits the
 *       same record as {@code FD-TRAN-CAT-KEY} ({@code FD-TRAN-TYPE-CD PIC X(02)} plus
 *       {@code FD-TRAN-CAT-CD PIC 9(04)}) followed by {@code FD-TRAN-CAT-DATA PIC X(54)}, which is
 *       {@code 6 + 54 = 60}. {@code :48} then declares {@code RECORD KEY IS FD-TRAN-CAT-KEY}, and
 *       <em>that</em> is what fixes the key width at 6;</li>
 *   <li><strong>the {@code IDCAMS} definition</strong> - {@code app/jcl/TRANCATG.jcl} defines the
 *       cluster with {@code KEYS(6 0)} and {@code RECORDSIZE(60 60)}: a 6-byte key at offset 0, and a
 *       fixed 60-byte record. This is the only artefact in the repository that states the key offset
 *       explicitly, and it is what {@link #KEY_OFFSET} rests on;</li>
 *   <li><strong>the shipped fixture</strong> - {@code app/data/ASCII/trancatg.txt} measures exactly 18
 *       records of exactly 60 bytes. Decoding the first three at the offsets above yields
 *       {@code ("01", 1, "Regular Sales Draft")}, {@code ("01", 2, "Regular Cash Advance")} and
 *       {@code ("01", 3, "Convenience Check Debit")}. Any offset error would have shifted these
 *       visibly.</li>
 * </ol>
 * The module's configuration agrees independently: the {@code TRANCATG} entry of
 * {@code carddemo.datasets} declares {@code record-length: 60}, {@code key-length: 6},
 * {@code organization: ksds} and {@code copybook: CVTRA04Y}. Every one of those four is checked
 * against this class's constants at startup - see the constructor.
 *
 * <h2>BEWARE: {@code TRAN-CAT-KEY} is 6 bytes here and 17 bytes in {@code CVTRA01Y}</h2>
 * <p><strong>This is the single most dangerous thing about this dataset, and defending against it is
 * why several members of this class exist at all.</strong> {@code app/cpy/CVTRA01Y.cpy} declares a
 * group with the <em>identical</em> COBOL name {@code TRAN-CAT-KEY}, built from entirely different
 * members - {@code TRANCAT-ACCT-ID PIC 9(11)} plus {@code TRANCAT-TYPE-CD PIC X(02)} plus
 * {@code TRANCAT-CD PIC 9(04)} - totalling <strong>17</strong> bytes. It belongs to
 * {@code TRAN-CAT-BAL-RECORD}, a 50-byte record on the {@code TCATBALF} dataset, modelled by
 * {@code TranCatBalRecord} and reached through {@code TranCatBalRepository}.
 *
 * <p>Same name. Six bytes against seventeen. Different members, different record, different width,
 * different dataset. Substituting one for the other would not fail loudly: a 17-byte key against this
 * 60-byte record would compare bytes 0 through 16, which spans the whole 6-byte key <em>and the first
 * eleven characters of the description</em>, so every lookup would silently miss. The defences are
 * therefore explicit and layered:
 * <ul>
 *   <li>{@link #KEY_LENGTH} is derived from {@link TranCategoryRecord#TRAN_CAT_KEY_LENGTH} rather than
 *       written as a literal, and {@link #TRAN_CAT_BAL_KEY_LENGTH} names the 17 that must never appear
 *       here, so a test can assert the two are different rather than trusting that they are;</li>
 *   <li>{@link #verifyRecordGeometry(int, int, int, int)} refuses a key width of anything but 6 and
 *       names the {@code CVTRA01Y} trap in its diagnostic;</li>
 *   <li>the constructor rejects a {@code carddemo.datasets.TRANCATG} binding that declares
 *       {@code key-length} of anything but 6 - which is where a 17 would realistically arrive, since
 *       configuration is the one place the two datasets sit side by side;</li>
 *   <li>this class accepts and produces <strong>only</strong> its own 6-byte key image, through
 *       {@link #keyImage(String, int)} and {@link TranCategoryRecord#tranCatKeyImage(String, int,
 *       Charset)}. There is no overload, no setter and no accessor anywhere on this class through which
 *       a 17-byte key could enter or leave.</li>
 * </ul>
 *
 * <h2>{@code TRAN-TYPE-CD} and {@code TRAN-CAT-CD} are also {@code CVTRA05Y}'s field names</h2>
 * <p>A second, quieter collision. {@code app/cpy/CVTRA05Y.cpy} - the 350-byte transaction record
 * modelled by {@code TranRecord} - declares fields named {@code TRAN-TYPE-CD} and {@code TRAN-CAT-CD}
 * too. The clash is not hypothetical: {@code CBTRN03C} copies both copybooks into one program
 * ({@code COPY CVTRA05Y.} at {@code :93}, {@code COPY CVTRA04Y.} at {@code :108}), so the unqualified
 * names are genuinely ambiguous there and COBOL <em>forces</em> explicit qualification at five sites:
 * <ul>
 *   <li>{@code :189} {@code MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE} - that one feeds the
 *       {@code TRANTYPE} lookup, not this one;</li>
 *   <li>{@code :191-192} {@code MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE-CD OF
 *       FD-TRAN-CAT-KEY};</li>
 *   <li>{@code :193-194} {@code MOVE TRAN-CAT-CD OF TRAN-RECORD TO FD-TRAN-CAT-CD OF
 *       FD-TRAN-CAT-KEY};</li>
 *   <li>{@code :365} {@code MOVE TRAN-TYPE-CD OF TRAN-RECORD TO TRAN-REPORT-TYPE-CD};</li>
 *   <li>{@code :367} {@code MOVE TRAN-CAT-CD OF TRAN-RECORD TO TRAN-REPORT-CAT-CD}.</li>
 * </ul>
 * Note that the receivers at {@code :192} and {@code :194} are qualified as well - the ambiguity runs
 * in both directions. In Java the two records are distinct types, so the compiler cannot confuse them
 * and no qualification is needed; but they must <strong>stay</strong> distinct types and must never be
 * merged or aliased. {@link #readByKey(String, int)} takes the two halves as a {@code String} and an
 * {@code int} in exactly the order those two {@code MOVE} statements perform them, so a caller holding
 * a {@code TranRecord} passes its two fields straight through.
 *
 * <h2>Not-found is reported. It is never thrown</h2>
 * <p>{@code 1500-C-LOOKUP-TRANCATG} ({@code app/cbl/CBTRN03C.cbl:504-512}) reads:
 * <pre>
 * 1500-C-LOOKUP-TRANCATG.
 *     READ TRANCATG-FILE INTO TRAN-CAT-RECORD
 *        INVALID KEY
 *           DISPLAY 'INVALID TRAN CATG KEY : '  FD-TRAN-CAT-KEY
 *           MOVE 23 TO IO-STATUS
 *           PERFORM 9910-DISPLAY-IO-STATUS
 *           PERFORM 9999-ABEND-PROGRAM
 *     END-READ
 *     EXIT.
 * </pre>
 * A missing category is fatal for this program - but it is fatal <em>in the caller</em>, and only
 * after the caller has displayed the 6-byte key. So {@link #readByKey(String, int)} returns
 * {@link Outcome#NOT_FOUND} with status {@code '23'} and <strong>does not throw</strong>. Throwing here
 * would skip the {@code DISPLAY} line at {@code :507}, skip the rendered status from
 * {@code 9910-DISPLAY-IO-STATUS} ({@code :633-646}), and replace the program's own abend path with
 * this class's. {@link #keyImage(String, int)} exists precisely so the caller can render the key that
 * {@code DISPLAY} needs, and {@link ReadResult#statusImage()} produces the four-character
 * {@code IO-STATUS-04} image that {@code 9910} emits.
 *
 * <p>The same boundary governs {@link #open()} and {@link #close()}. Both COBOL paragraphs are the
 * same two-armed shape - {@code IF TRANCATG-STATUS = '00'} moves {@code 0} into {@code APPL-RESULT},
 * anything else moves {@code 12} - followed by a {@code DISPLAY} and an abend on the failing arm
 * ({@code :448-464} and {@code :587-603}). This class reproduces the status and the
 * {@code APPL-RESULT} classification, through {@link #applResultOfFileStatus(String)}, and nothing
 * beyond it: the message text ({@code 'ERROR OPENING TRANSACTION CATG FILE'} and
 * {@code 'ERROR CLOSING TRANSACTION CATG FILE'}) and the abend belong to the caller, which is where
 * the COBOL puts them.
 *
 * <h2>The description is returned untrimmed, and that is load-bearing</h2>
 * <p>{@code TRAN-CAT-TYPE-DESC} comes back as all <strong>50</strong> bytes, trailing spaces included,
 * because {@code app/cbl/CBTRN03C.cbl:368} performs
 * {@code MOVE TRAN-CAT-TYPE-DESC TO TRAN-REPORT-CAT-DESC} into a {@code PIC X(29)} receiver
 * ({@code app/cpy/CVTRA07Y.cpy}). COBOL truncates an alphanumeric {@code MOVE} on the
 * <strong>right</strong>, so the report line carries the first 29 characters of the 50-byte field.
 * That truncation belongs at the point of use, applied deliberately through
 * {@link FixedWidthCodec#movePicX(String, int)}, and never here: a repository that pre-trimmed would
 * discard 21 bytes the parity differ compares, and the truncation would no longer be reproducible.
 *
 * <h2>How a VSAM KSDS is reached over JDBC, with no schema</h2>
 * <p>Through {@link DatasetRelation}, which is the module's single answer to the four questions every
 * repository here faces: what SQL identifier names this dataset, which column carries the record
 * image, how a key at a byte offset becomes a predicate, and what a backend refusal means. The
 * consequences for this class:
 * <ul>
 *   <li><strong>No dataset name appears in this file</strong> (gate G46). The name is resolved from the
 *       {@code TRANCATG} entry of {@code carddemo.datasets} through
 *       {@link DatasetBindings#binding(String)} and validated as a well-formed z/OS dataset name before
 *       it reaches a statement.</li>
 *   <li><strong>No copybook field name is used as a SQL column name.</strong> {@code TRAN-CAT-KEY} is a
 *       span at offset {@value #KEY_OFFSET} for {@value #KEY_LENGTH} bytes; it is not a column. The
 *       record image is read by <em>position</em>, at ordinal
 *       {@value #RECORD_IMAGE_COLUMN_INDEX}, and its interior is addressed by absolute byte offset
 *       through the hand-written codec (practice B11).</li>
 *   <li><strong>The keyed predicate is pushed into the statement</strong> as an escaped {@code LIKE}
 *       over the record image, so the backend decides which rows qualify and only matching rows cross
 *       the wire. A malformed row elsewhere in the dataset is not this read's business.</li>
 *   <li><strong>No DDL, no entity annotation, no version column, no index</strong> (gate G44). Nothing
 *       here creates, migrates or describes a schema; the relation is whatever the deployment already
 *       exposes.</li>
 * </ul>
 *
 * <h2>Residual risk R-E: the driver is a deployment-time input</h2>
 * <p>The prompt mandates JDBC to the existing VSAM backend and names no driver, and VSAM has no
 * standard Maven-published JDBC driver, so the {@code DataSource} is entirely configuration-bound and
 * <strong>production connectivity cannot be exercised from this build</strong>. Two consequences are
 * worth stating plainly rather than discovering later. First, everything this class can check about its
 * own configuration is checked in the constructor, at context refresh, so a wrong record length or a
 * 17-byte key length fails at startup rather than in a batch window. Second, the decode seam is free of
 * JDBC on purpose - {@link TranCategoryRecord#decode(byte[], Charset)} takes bytes - so the byte-level
 * behaviour is fully exercised by unit tests and by the parity harness with no backend in the path.
 * What remains unexercised is the driver itself, and that is the residual risk.
 *
 * <h2>Thread safety</h2>
 * <p>Immutable after construction and safe to share. Every field is {@code final}, there is no mutable
 * static state anywhere (practice B9, gate G53), and every collaborator arrives by constructor
 * injection - no field injection, no setter, so an instance is either fully wired or does not exist.
 * No browse position or cursor is held, because this dataset is never browsed; each
 * {@link #readByKey(String, int)} is self-contained. Byte arrays never escape: the only array this
 * class handles is a row image, which is consumed by {@link TranCategoryRecord#decode(byte[], Charset)}
 * and never published.
 *
 * @see TranCategoryRecord
 * @see DateParmReader
 * @see FileStatus
 */
@Repository
public class TranCategoryRepository {

    /**
     * The module's logger. Refusals are reported through {@link #logRefusal(Throwable, String)}, which
     * logs the driver's own {@code SQLSTATE}, vendor code and exception type and <strong>never</strong>
     * the {@link Throwable} itself - a driver's message is prose it composed around the values it
     * refused, and handing it to a logger emits that text and its whole cause chain verbatim.
     */
    private static final Log LOG = LogFactory.getLog(TranCategoryRepository.class);

    /**
     * The mainframe DD name, and the {@code carddemo.datasets} configuration key: {@code TRANCATG}.
     *
     * <p>Spelled exactly as {@code app/cbl/CBTRN03C.cbl:45} and {@code app/jcl/TRANREPT.jcl:71} spell
     * it, because keeping the mainframe identifier intact is what lets a reviewer diff the
     * configuration against the JCL line by line. It is a DD name and not a CICS {@code FILE} name:
     * {@code TRANCATG} appears nowhere in {@code app/csd/CARDDEMO.CSD}, so this dataset has exactly one
     * name and no alias.
     */
    public static final String DD_NAME = "TRANCATG";

    /**
     * The {@code app/cpy} member defining the layout: {@code CVTRA04Y}.
     *
     * <p>Checked against the {@code copybook} component of the configured binding, so a binding that
     * names a different layout is rejected rather than trusted - {@code CVTRA01Y} being the specific
     * mistake worth catching, since it is the namesake described on this class.
     */
    public static final String COPYBOOK = "CVTRA04Y";

    /**
     * The sole COBOL consumer: {@code CBTRN03C}, the transaction detail report.
     *
     * <p>Named as a constant so diagnostics can cite the program a failing read belongs to without
     * restating it at each site.
     */
    public static final String CONSUMER_PROGRAM = "CBTRN03C";

    /**
     * The declared record width, 60 bytes - {@code RECLN = 60} from {@code app/cpy/CVTRA04Y.cpy} and
     * {@code RECORDSIZE(60 60)} from {@code app/jcl/TRANCATG.jcl}.
     *
     * <p>Derived from {@link TranCategoryRecord#RECORD_LENGTH} rather than restated as a literal, so
     * the repository and the record model cannot drift apart.
     */
    public static final int RECORD_LENGTH = TranCategoryRecord.RECORD_LENGTH;

    /**
     * The key width, <strong>6</strong> bytes: {@code TRAN-TYPE-CD X(02)} plus
     * {@code TRAN-CAT-CD 9(04)}.
     *
     * <p><strong>Not 17.</strong> See the collision warning on this class:
     * {@code app/cpy/CVTRA01Y.cpy} declares a group of the identical COBOL name that <em>is</em> 17
     * bytes wide. Derived from {@link TranCategoryRecord#TRAN_CAT_KEY_LENGTH} so there is one authority
     * for the width, and asserted against {@link #TRAN_CAT_BAL_KEY_LENGTH} by
     * {@link #verifyRecordGeometry(int, int, int, int)}.
     */
    public static final int KEY_LENGTH = TranCategoryRecord.TRAN_CAT_KEY_LENGTH;

    /**
     * The key's 0-based offset within the record: 0, the start of the record.
     *
     * <p>Two sources agree. {@code app/cbl/CBTRN03C.cbl:79-81} declares {@code FD-TRAN-CAT-KEY} as the
     * first item of {@code FD-TRAN-CAT-RECORD}, and {@code app/jcl/TRANCATG.jcl} says so explicitly
     * with {@code KEYS(6 0)} - width 6, offset 0.
     */
    public static final int KEY_OFFSET = TranCategoryRecord.TRAN_CAT_KEY_OFFSET;

    /**
     * The width of the <em>other</em> {@code TRAN-CAT-KEY}: {@code CVTRA01Y}'s 17 bytes, which belong to
     * {@code TCATBALF} and must never be used against {@code TRANCATG}.
     *
     * <p>Declared here, on the class that must reject it, rather than left implicit. A constant that
     * names the wrong value turns "do not confuse these two" from a comment into an assertion:
     * {@link #verifyRecordGeometry(int, int, int, int)} refuses this width by name, and a unit test can
     * prove {@link #KEY_LENGTH} and this value are different rather than trusting that they are.
     *
     * <p>{@code 9(11) + X(02) + 9(04) = 17}, from {@code app/cpy/CVTRA01Y.cpy}.
     */
    public static final int TRAN_CAT_BAL_KEY_LENGTH = 17;

    /**
     * The ordinal of the column carrying the whole record image: the first.
     *
     * <p>Position rather than name, because the name is a site-specific deployment detail while the
     * position is the module's contract. Re-exported from {@link DatasetRelation} so a caller or a test
     * can name it without reaching past this class.
     */
    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    /**
     * The value both COBOL paragraphs move into {@code APPL-RESULT} <em>before</em> attempting the
     * operation: {@code 8} ({@code app/cbl/CBTRN03C.cbl:449} and {@code :588}).
     *
     * <p>It is never observable - the very next statement overwrites it with {@code 0} or {@code 12} -
     * but it is the initial value the source sets, and naming it keeps the reproduction of those two
     * paragraphs complete rather than tacitly simplified.
     */
    public static final int APPL_RESULT_INITIAL = 8;

    /**
     * The {@code APPL-RESULT} value both paragraphs move on the failing arm: {@code 12}
     * ({@code app/cbl/CBTRN03C.cbl:454} and {@code :593}).
     *
     * <p>{@code APPL-AOK} is {@code VALUE 0} and {@code APPL-EOF} is {@code VALUE 16}
     * ({@code :150-152}); {@code 12} matches neither, so the {@code IF APPL-AOK} guard that follows
     * takes its {@code ELSE} arm and the program displays, renders the status and abends.
     */
    public static final int APPL_RESULT_FATAL = 12;

    /**
     * The feedback code of the permanent-error status: {@code 0}, so the status is {@code '9'} followed
     * by a {@code NUL}.
     *
     * <p>The shape a real COBOL runtime uses for a permanent I/O error: {@code IO-STAT1} is {@code '9'}
     * and {@code IO-STAT2} carries a single-byte binary feedback code. It is exactly the case
     * {@code 9910-DISPLAY-IO-STATUS} branches on - {@code IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'}
     * ({@code app/cbl/CBTRN03C.cbl:634-635}) - so choosing this form keeps that branch of the renderer
     * live rather than unreachable.
     */
    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The status reported when the backend refuses, or presents something that is not a readable
     * 60-byte record: {@code '9'} plus a {@code NUL} feedback byte.
     *
     * <p>Two characters, as every {@code FILE STATUS} is. It is deliberately <em>not</em> one of the
     * statuses {@code CBTRN03C} names explicitly, so it lands on the caller's failing arm - which is
     * where an unexpected condition belongs - and renders through
     * {@link FileStatus#toStatusImage(String)} as the {@code 9nnn} form rather than as {@code 00nn}.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    /**
     * The copybook layout, borrowed from the record model rather than re-declared.
     *
     * <p>Deeply immutable, and its construction already ran {@link RecordLayout}'s own self-check -
     * spans contiguous from offset 0, summing to exactly {@value #RECORD_LENGTH}, {@code FILLER}
     * included. Held here so the constructor can prove the configured geometry against the
     * <em>layout</em> rather than against a literal, which is the check the record model cannot perform
     * because it never sees configuration.
     */
    private static final RecordLayout LAYOUT = TranCategoryRecord.LAYOUT;

    /**
     * The trailing {@code FILLER X(04)} span, borrowed from the record model.
     *
     * <p>Present as a first-class span, never an implied gap. It is the last span of the layout, so its
     * exclusive end offset must be exactly {@value #RECORD_LENGTH}; if the {@code FILLER} were ever
     * dropped the layout would be 56 bytes and every byte of the dataset after the description would
     * shift. {@link #verifyRecordGeometry(int, int, int, int)} proves both facts (gates G19, G21).
     */
    private static final FieldSpan FILLER_SPAN = TranCategoryRecord.FILLER;

    /**
     * Where this dataset's key sits inside the record image: offset {@value #KEY_OFFSET}, width
     * {@value #KEY_LENGTH}.
     *
     * <p>{@code static final} and immutable - a record of two {@code int}s - so it introduces no shared
     * mutable state. It renders the escaped {@code LIKE} pattern that confines a keyed match to the
     * key's own six bytes, which is the comparison VSAM performs.
     */
    private static final KeySpan KEY_SPAN = new KeySpan(KEY_OFFSET, KEY_LENGTH);

    /**
     * How many rows a keyed read is willing to transfer: {@value}.
     *
     * <p>One more than the key can legitimately name. A KSDS primary key is unique by construction -
     * {@code app/jcl/TRANCATG.jcl} defines the cluster {@code INDEXED} with {@code KEYS(6 0)} - so a
     * second matching row means the relation is not the dataset the copybook describes. Fetching two
     * makes that detectable; fetching more would transfer rows no caller could act on.
     */
    private static final int KEYED_READ_ROW_LIMIT = 2;

    /**
     * How many rows a unique key may legitimately select: {@value}.
     *
     * <p>Named rather than written as a bare {@code 1} in the comparison, because the comparison is the
     * whole of the uniqueness check and a reader should not have to infer what the number means.
     */
    private static final int UNIQUE_KEY_ROW_COUNT = 1;

    /** The module's shared template, over the configuration-bound {@code DataSource}. */
    private final JdbcTemplate jdbcTemplate;

    /**
     * The hand-written fixed-width codec, built once over the explicitly injected dataset code page.
     *
     * <p>Held rather than re-created per row so the charset validation happens once, and it is the only
     * place this class obtains a {@link Charset} - never from the platform default (practice B8).
     */
    private final FixedWidthCodec codec;

    /**
     * How this deployment's driver presents a record image over JDBC: as characters or as bytes.
     *
     * <p>Injected, never decided here. The choice is a property of the deployment rather than of the
     * record, it is stated once by {@value RecordImageForm#FORM_PROPERTY}, and a repository that
     * decided for itself would be one more independent answer to a question that has exactly one
     * correct answer per backend - which is how a keyed read comes to compare characters against bytes
     * and silently convert instead of failing.
     */
    private final RecordImageForm recordImageForm;

    /**
     * The resolved dataset, from configuration and never from a literal (gate G46).
     *
     * <p>Composes every statement this class issues and validated its name as a well-formed z/OS
     * dataset name before doing so.
     */
    private final DatasetRelation relation;

    /**
     * Assembles the repository from the module's shared {@link JdbcTemplate}, the DD-name-keyed dataset
     * catalogue, the explicitly named dataset code page and the deployment's record-image
     * representation.
     *
     * <p>Constructor injection throughout, with no field injection and no setter, so an instance is
     * either fully wired or does not exist (practice B9, gate G53).
     *
     * <p><strong>Everything checkable is checked here, at context refresh, and not at the first
     * read.</strong> Residual risk R-E means no backend is reachable from this build, so startup is the
     * last point at which a configuration defect can be caught cheaply; after it, the next opportunity
     * is a batch window. Six invariants are enforced, and each one guards a defect that would otherwise
     * surface as plausible-looking wrong data rather than as a failure:
     * <ol>
     *   <li><strong>the copybook geometry is self-consistent</strong> - the layout's storage spans sum
     *       to {@value #RECORD_LENGTH}, the {@code FILLER} ends exactly at {@value #RECORD_LENGTH}, and
     *       the key is {@value #KEY_LENGTH} bytes rather than {@link #TRAN_CAT_BAL_KEY_LENGTH}
     *       (gates G19, G21);</li>
     *   <li><strong>the configured record length is {@value #RECORD_LENGTH}</strong>. This class decodes
     *       by absolute offset, so a differently-sized record would misplace the description and the
     *       {@code FILLER};</li>
     *   <li><strong>the binding is keyed, and its key is {@value #KEY_LENGTH} bytes at offset
     *       {@value #KEY_OFFSET}</strong>. This is the {@code CVTRA01Y} collision defence at the one
     *       place a 17 would realistically arrive, because configuration is where the {@code TRANCATG}
     *       and {@code TCATBALF} entries sit side by side;</li>
     *   <li><strong>the binding is a base cluster, not an alternate-index path</strong>. There is no
     *       alternate index over this dataset anywhere in the estate, so a binding declaring a base
     *       would describe an access path that does not exist;</li>
     *   <li><strong>the declared copybook, where one is declared, is {@value #COPYBOOK}</strong>. A
     *       binding naming another layout - {@code CVTRA01Y} in particular - is describing a different
     *       record;</li>
     *   <li><strong>the dataset name is present and well formed</strong>, validated by the module's one
     *       z/OS dataset-name grammar before it is ever composed into a statement.</li>
     * </ol>
     *
     * @param jdbcTemplate    the module-wide template; never {@code null}
     * @param datasetBindings the {@code carddemo.datasets} catalogue; never {@code null}. The single
     *                        entry this repository needs is looked up by the key {@value #DD_NAME}, so
     *                        no dataset name is written in Java (gate G46)
     * @param datasetCharset  the dataset code page, injected by bean name so the choice is explicit at
     *                        the injection point and never taken from the platform (practice B8)
     * @param recordImageForm how the deployment's driver presents a record image over JDBC, from
     *                        {@value RecordImageForm#FORM_PROPERTY}
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if this class's own geometry is inconsistent, if no binding is
     *                               configured for {@value #DD_NAME}, or if that binding declares a
     *                               record length, key geometry, base relationship, copybook or dataset
     *                               name this repository cannot honour
     */
    public TranCategoryRepository(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm) {

        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + DD_NAME + " category lookup is reached through the module's shared template over the "
                + "configuration-bound DataSource");
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

        // The copybook side of the contract, proven from the layout rather than from a literal. It also
        // forces TranCategoryRecord's class initialiser to run, so that type's own geometry self-check
        // fires here too rather than at the first read.
        TranCategoryRecord.verifyDeclaredGeometry();
        verifyRecordGeometry(LAYOUT.recordLength(), sumOfLayoutSpanWidths(),
                FILLER_SPAN.endOffsetExclusive(), KEY_LENGTH);

        // The configuration side of the contract.
        DatasetBinding binding = datasetBindings.binding(DD_NAME);
        requireCopybookRecordLength(binding);
        requireSixByteKey(binding);
        requireBaseCluster(binding);
        requireDeclaredCopybook(binding);

        this.relation = DatasetRelation.of(requireUsableDatasetName(binding.dsname()), RECORD_LENGTH);
    }

    // =================================================================================================
    // Startup guards.
    //
    // Every one is package-private and static, taking its subject as a parameter. That is a deliberate
    // testability seam rather than an accident of visibility: driven only from the constructor with the
    // shipped configuration, each failure branch below is UNREACHABLE, which would leave half of this
    // class's branches permanently uncoverable and put the module's mandated 90% branch threshold out of
    // reach through no fault of the tests. Taking the subject as a parameter lets the paired unit test
    // hand each guard a deliberately wrong binding - a 17-byte key, a 50-byte record, a base of its own -
    // and assert on the diagnostic, so every branch is driven and every message is verified to name the
    // trap it is meant to name. None of them adds behaviour: the constructor is the only production
    // caller and it always supplies the real binding.
    // =================================================================================================

    /**
     * The sum of the layout's declared storage span widths, computed rather than restated.
     *
     * <p>{@code TRAN-TYPE-CD 2 + TRAN-CAT-CD 4 + TRAN-CAT-TYPE-DESC 50 + FILLER 4 = 60}. Iterating the
     * spans is what makes a dropped {@code FILLER} arithmetic rather than a matter of trust: omit it and
     * this returns 56, which {@link #verifyRecordGeometry(int, int, int, int)} rejects.
     *
     * @return {@value #RECORD_LENGTH} for a correctly declared layout
     */
    static int sumOfLayoutSpanWidths() {
        int total = 0;
        for (FieldSpan span : LAYOUT.storageSpans()) {
            total += span.length();
        }
        return total;
    }

    /**
     * Proves the copybook geometry this repository decodes against, with every quantity passed in.
     *
     * <p>Four checks, in the order a defect would most likely be introduced:
     * <ol>
     *   <li>the layout declares {@value #RECORD_LENGTH} bytes;</li>
     *   <li>the storage spans <em>sum</em> to {@value #RECORD_LENGTH} - gate G19, and the check that a
     *       dropped {@code FILLER} fails;</li>
     *   <li>the {@code FILLER} span ends exactly at {@value #RECORD_LENGTH}, so the reserved bytes are
     *       the last four and are accounted for rather than implied - gate G21;</li>
     *   <li>the key is {@value #KEY_LENGTH} bytes and specifically <strong>not</strong>
     *       {@link #TRAN_CAT_BAL_KEY_LENGTH} - the {@code CVTRA01Y} namesake trap, named explicitly in
     *       the diagnostic because a reader meeting a 17 here needs to be told which record it came
     *       from.</li>
     * </ol>
     *
     * @param layoutRecordLength the width the layout declares
     * @param summedSpanWidths   the summed width of the layout's storage spans
     * @param fillerEndOffset    the exclusive end offset of the trailing {@code FILLER} span
     * @param keyLength          the declared width of the {@code TRAN-CAT-KEY} group
     * @throws IllegalStateException if the supplied geometry is inconsistent
     */
    static void verifyRecordGeometry(int layoutRecordLength,
                                     int summedSpanWidths,
                                     int fillerEndOffset,
                                     int keyLength) {
        if (layoutRecordLength != RECORD_LENGTH) {
            throw new IllegalStateException("The " + COPYBOOK + " layout declares a record length of "
                    + layoutRecordLength + ", but app/cpy/" + COPYBOOK + ".cpy reads RECLN = "
                    + RECORD_LENGTH + " and app/jcl/" + DD_NAME + ".jcl defines the cluster "
                    + "RECORDSIZE(" + RECORD_LENGTH + " " + RECORD_LENGTH + "). This repository decodes "
                    + "by absolute offset against exactly that width.");
        }
        if (summedSpanWidths != RECORD_LENGTH) {
            throw new IllegalStateException("The " + COPYBOOK + " layout's storage spans sum to "
                    + summedSpanWidths + ", not " + RECORD_LENGTH + ". The record is TRAN-CAT-KEY "
                    + KEY_LENGTH + " + TRAN-CAT-TYPE-DESC "
                    + TranCategoryRecord.TRAN_CAT_TYPE_DESC_LENGTH + " + FILLER "
                    + TranCategoryRecord.FILLER_LENGTH + "; a total of "
                    + (RECORD_LENGTH - TranCategoryRecord.FILLER_LENGTH)
                    + " means the trailing FILLER X(0" + TranCategoryRecord.FILLER_LENGTH
                    + ") has been dropped, which would shift every byte of the dataset after the "
                    + "description.");
        }
        if (fillerEndOffset != RECORD_LENGTH) {
            throw new IllegalStateException("The " + COPYBOOK + " trailing FILLER ends at 0-based "
                    + "offset " + fillerEndOffset + ", but it is the last span of a " + RECORD_LENGTH
                    + "-byte record and must end exactly there. FILLER is a first-class span in this "
                    + "module - declared, positioned and always emitted - never an implied gap.");
        }
        if (keyLength != KEY_LENGTH) {
            throw new IllegalStateException("The " + COPYBOOK + " TRAN-CAT-KEY must be " + KEY_LENGTH
                    + " bytes - TRAN-TYPE-CD X(02) plus TRAN-CAT-CD 9(04) - but is declared as "
                    + keyLength + ". BEWARE THE NAMESAKE: app/cpy/CVTRA01Y.cpy declares a group with "
                    + "the IDENTICAL COBOL name TRAN-CAT-KEY that is " + TRAN_CAT_BAL_KEY_LENGTH
                    + " bytes wide (TRANCAT-ACCT-ID 9(11) + TRANCAT-TYPE-CD X(02) + TRANCAT-CD 9(04)) "
                    + "and belongs to the TCATBALF dataset, not " + DD_NAME + ". A "
                    + TRAN_CAT_BAL_KEY_LENGTH + "-byte key against this " + RECORD_LENGTH
                    + "-byte record would compare the whole key AND the first "
                    + (TRAN_CAT_BAL_KEY_LENGTH - KEY_LENGTH) + " characters of "
                    + "TRAN-CAT-TYPE-DESC, so every lookup would silently miss.");
        }
    }

    /**
     * Requires the configured binding to agree with {@code app/cpy/CVTRA04Y.cpy} about the record width.
     *
     * @param binding the configured binding
     * @throws IllegalStateException if the declared record length is not {@value #RECORD_LENGTH}
     */
    static void requireCopybookRecordLength(DatasetBinding binding) {
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding for '" + DD_NAME + "' declares a record "
                    + "length of " + binding.recordLength() + ", but app/cpy/" + COPYBOOK + ".cpy "
                    + "declares (RECLN = " + RECORD_LENGTH + "), app/cbl/" + CONSUMER_PROGRAM
                    + ".cbl:78-82 splits the same record as FD-TRAN-CAT-KEY " + KEY_LENGTH
                    + " + FD-TRAN-CAT-DATA X(54), and app/jcl/" + DD_NAME + ".jcl defines the cluster "
                    + "RECORDSIZE(" + RECORD_LENGTH + " " + RECORD_LENGTH + "). All four agree. This "
                    + "repository decodes by absolute offset against exactly that width, so correct "
                    + "carddemo.datasets." + DD_NAME + ".record-length to " + RECORD_LENGTH + ".");
        }
    }

    /**
     * Requires the configured binding to be keyed on {@value #KEY_LENGTH} bytes at offset
     * {@value #KEY_OFFSET} - the {@code CVTRA01Y} collision defence, enforced at startup.
     *
     * <p>Configuration is the one place the {@code TRANCATG} and {@code TCATBALF} entries sit next to
     * each other, so it is the realistic place for a 17 to arrive; the diagnostic therefore names that
     * value and the record it belongs to explicitly, rather than reporting a bare mismatch.
     *
     * @param binding the configured binding
     * @throws IllegalStateException if the binding is not keyed, declares no key length, declares a key
     *                               length other than {@value #KEY_LENGTH}, or places the key anywhere
     *                               but offset {@value #KEY_OFFSET}
     */
    static void requireSixByteKey(DatasetBinding binding) {
        if (!binding.keyed()) {
            throw new IllegalStateException("Dataset binding for '" + DD_NAME + "' declares "
                    + "organization '" + binding.organization() + "', but app/cbl/" + CONSUMER_PROGRAM
                    + ".cbl:46-48 declares ORGANIZATION IS INDEXED with ACCESS MODE IS RANDOM and "
                    + "RECORD KEY IS FD-TRAN-CAT-KEY, and app/jcl/" + DD_NAME + ".jcl defines the "
                    + "cluster INDEXED. The only access this dataset supports is a keyed read, so "
                    + "correct carddemo.datasets." + DD_NAME + ".organization to "
                    + DatasetBinding.KSDS + ".");
        }
        Integer declaredKeyLength = binding.keyLength();
        if (declaredKeyLength == null) {
            throw new IllegalStateException("Dataset binding for '" + DD_NAME + "' declares no "
                    + "key-length, so nothing states where its key ends and a keyed read against it "
                    + "would be guessing. It is " + KEY_LENGTH + ": TRAN-TYPE-CD X(02) plus "
                    + "TRAN-CAT-CD 9(04) from app/cpy/" + COPYBOOK + ".cpy, confirmed by KEYS("
                    + KEY_LENGTH + " " + KEY_OFFSET + ") in app/jcl/" + DD_NAME + ".jcl.");
        }
        if (declaredKeyLength != KEY_LENGTH) {
            throw new IllegalStateException("Dataset binding for '" + DD_NAME + "' declares key-length "
                    + declaredKeyLength + ", but " + DD_NAME + "'s TRAN-CAT-KEY is " + KEY_LENGTH
                    + " bytes - TRAN-TYPE-CD X(02) plus TRAN-CAT-CD 9(04) - confirmed by KEYS("
                    + KEY_LENGTH + " " + KEY_OFFSET + ") in app/jcl/" + DD_NAME + ".jcl."
                    + (declaredKeyLength == TRAN_CAT_BAL_KEY_LENGTH
                            ? " THAT VALUE IS THE NAMESAKE'S: app/cpy/CVTRA01Y.cpy declares a group "
                                    + "with the IDENTICAL COBOL name TRAN-CAT-KEY that is "
                                    + TRAN_CAT_BAL_KEY_LENGTH + " bytes wide and belongs to TCATBALF, "
                                    + "not " + DD_NAME + ". The two records have been conflated; the "
                                    + TRAN_CAT_BAL_KEY_LENGTH + " belongs on the TCATBALF binding."
                            : "")
                    + " Correct carddemo.datasets." + DD_NAME + ".key-length to " + KEY_LENGTH + ".");
        }
        if (binding.keyOffsetOrZero() != KEY_OFFSET) {
            throw new IllegalStateException("Dataset binding for '" + DD_NAME + "' places its key at "
                    + "0-based offset " + binding.keyOffsetOrZero() + ", but TRAN-CAT-KEY is the first "
                    + "item of the record - app/cbl/" + CONSUMER_PROGRAM + ".cbl:79-81 declares "
                    + "FD-TRAN-CAT-KEY first, and app/jcl/" + DD_NAME + ".jcl says KEYS(" + KEY_LENGTH
                    + " " + KEY_OFFSET + ") explicitly. Remove carddemo.datasets." + DD_NAME
                    + ".key-offset, which defaults to " + KEY_OFFSET + ".");
        }
    }

    /**
     * Requires the configured binding to be a base cluster - that is, to declare no base of its own.
     *
     * <p>No alternate index over {@code TRANCATG} exists anywhere: it is absent from
     * {@code app/csd/CARDDEMO.CSD} entirely, and {@code app/jcl/TRANCATG.jcl} defines one cluster with
     * one {@code DATA} and one {@code INDEX} component. A binding declaring a base would describe an
     * access path that does not exist, and would invert the relationship at every call site.
     *
     * @param binding the configured binding
     * @throws IllegalStateException if the binding declares a base
     */
    static void requireBaseCluster(DatasetBinding binding) {
        if (binding.base() != null) {
            throw new IllegalStateException("Dataset binding for '" + DD_NAME + "' declares base '"
                    + binding.base() + "', but " + DD_NAME + " IS a base cluster: app/jcl/" + DD_NAME
                    + ".jcl defines it INDEXED with KEYS(" + KEY_LENGTH + " " + KEY_OFFSET + "), and no "
                    + "alternate index over it exists anywhere in the estate - it is not among the "
                    + "eight DEFINE FILE entries of app/csd/CARDDEMO.CSD at all. Remove "
                    + "carddemo.datasets." + DD_NAME + ".base.");
        }
    }

    /**
     * Requires the configured binding, where it names a copybook at all, to name {@value #COPYBOOK}.
     *
     * <p>A binding may legitimately omit the component - a few output datasets declare their layout
     * inline in the program instead - so an absent value is accepted. A <em>different</em> value is not:
     * it says configuration and this class disagree about which record the dataset holds, and
     * {@code CVTRA01Y} is exactly the wrong answer this dataset attracts.
     *
     * @param binding the configured binding
     * @throws IllegalStateException if the binding names a copybook other than {@value #COPYBOOK}
     */
    static void requireDeclaredCopybook(DatasetBinding binding) {
        String declared = binding.copybook();
        if (declared != null && !COPYBOOK.equals(declared)) {
            throw new IllegalStateException("Dataset binding for '" + DD_NAME + "' names copybook '"
                    + declared + "', but " + DD_NAME + " holds " + COPYBOOK + "'s "
                    + RECORD_LENGTH + "-byte TRAN-CAT-RECORD - app/cbl/" + CONSUMER_PROGRAM
                    + ".cbl:108 reads COPY " + COPYBOOK + ". If that value is CVTRA01Y, this binding "
                    + "has been confused with TCATBALF's, whose TRAN-CAT-KEY is "
                    + TRAN_CAT_BAL_KEY_LENGTH + " bytes rather than " + KEY_LENGTH + ". Correct "
                    + "carddemo.datasets." + DD_NAME + ".copybook to " + COPYBOOK + ".");
        }
    }

    /**
     * Requires a configured dataset name that can be composed into a statement.
     *
     * <p>Blank is rejected separately from absent because {@code application.yml} spells the name as an
     * environment placeholder, so an unconfigured deployment yields an empty string rather than
     * {@code null} - and an empty string is an unset placeholder to be supplied, not an absence to be
     * filled in. The grammar itself - what a z/OS dataset name may contain - lives in
     * {@link DatasetRelation#requireDatasetName(String)}, stated once for the whole module rather than
     * restated, and diverging, in each repository.
     *
     * @param candidate the configured dataset name
     * @return {@code candidate}, unchanged
     * @throws IllegalStateException    if it is absent or blank
     * @throws IllegalArgumentException if it is not a well-formed z/OS dataset name
     */
    static String requireUsableDatasetName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for '" + DD_NAME + "' declares no "
                    + "dataset name. Set carddemo.datasets." + DD_NAME + ".dsname; this repository "
                    + "composes its statements from configuration alone and hard-codes no dataset name "
                    + "(gate G46).");
        }
        return DatasetRelation.requireDatasetName(candidate);
    }

    // =================================================================================================
    // The dataset's identity and geometry, exposed. A caller composing a diagnostic needs the dataset
    // name; a parity test needs the widths without re-deriving them.
    // =================================================================================================

    /**
     * The resolved dataset name, exactly as configuration declares it.
     *
     * <p>Exposed for diagnostics and for a caller that wants to report which dataset it read - not as a
     * way to reach around this class. The value is configured, never hard-coded, so surfacing it
     * introduces no dataset literal into Java (gate G46).
     *
     * @return the configured dataset name for {@value #DD_NAME}; never {@code null}, never blank
     */
    public String datasetName() {
        return relation.dsname();
    }

    /**
     * The record width this repository decodes against: {@value #RECORD_LENGTH} bytes.
     *
     * @return the copybook-declared record width
     */
    public int recordLength() {
        return RECORD_LENGTH;
    }

    /**
     * The key width this repository addresses: {@value #KEY_LENGTH} bytes, and never
     * {@link #TRAN_CAT_BAL_KEY_LENGTH}.
     *
     * <p>An instance accessor as well as a constant, so a test can assert the property of a
     * <em>constructed</em> repository rather than of a compile-time constant - which is the assertion
     * that actually defends the {@code CVTRA01Y} collision, because it holds after the configured
     * binding has been read.
     *
     * @return {@value #KEY_LENGTH}
     */
    public int keyLength() {
        return KEY_LENGTH;
    }

    /**
     * The code page this repository encodes keys in and decodes records from.
     *
     * <p>Injected and stated explicitly, never the platform default (practice B8). Exposed so a caller
     * that needs to build a record for comparison uses the same one rather than choosing its own.
     *
     * @return the dataset code page; never {@code null}
     */
    public Charset datasetCharset() {
        return codec.charset();
    }

    // =================================================================================================
    // 0400-TRANCATG-OPEN  - app/cbl/CBTRN03C.cbl:448-464, performed once from :165.
    // 9400-TRANCATG-CLOSE - app/cbl/CBTRN03C.cbl:587-603, performed once from :212.
    //
    // The two paragraphs are byte-for-byte identical apart from the verb and the message they display,
    // so they share one probe. They stay two methods because the caller's sequence and message differ:
    // the open precedes the report loop and the close follows it.
    // =================================================================================================

    /**
     * Opens the {@code TRANCATG} dataset for input, reporting only the resulting file status: the Java
     * form of {@code 0400-TRANCATG-OPEN} ({@code app/cbl/CBTRN03C.cbl:448-464}).
     *
     * <p>The COBOL is a two-way test - {@code IF TRANCATG-STATUS = '00'} moves {@code 0} into
     * {@code APPL-RESULT}, anything else moves {@link #APPL_RESULT_FATAL} - and this method reproduces
     * exactly that shape and nothing more. The caller owns the guard chain, the
     * {@code DISPLAY 'ERROR OPENING TRANSACTION CATG FILE'} line at {@code :459}, the rendered status
     * from {@code 9910-DISPLAY-IO-STATUS} and the abend. {@link #applResultOfFileStatus(String)} turns
     * the returned status into the {@code APPL-RESULT} value the paragraph would have moved.
     *
     * <p><strong>What "open" means against a configuration-bound driver, precisely.</strong> There is no
     * persistent handle to acquire: the shared template borrows and returns a connection per operation.
     * So the check performed here is a <em>describe of the dataset</em> - a statement that names it and
     * returns no row - which is dialect-free, transfers nothing, and fails when the dataset is absent or
     * unreachable. That is genuinely what an {@code OPEN INPUT} reports, and it is stronger than merely
     * proving a connection can be obtained: a connection-only check succeeds against a reachable backend
     * that has no such dataset, and would then let the failure surface on the read path - so the program
     * would emit {@code 'INVALID TRAN CATG KEY : '} for a dataset that was never there, which is the
     * wrong message and the wrong branch. Residual risk R-E still applies to the driver itself, which
     * this build cannot exercise.
     *
     * <p>Nothing is thrown, nothing is displayed and nothing abends. The open reports; the caller
     * decides.
     *
     * @return {@link FileStatus#OK} when the dataset is addressable, otherwise
     *         {@link #PERMANENT_ERROR_STATUS}; never {@code null}, always two characters
     */
    public String open() {
        return probeDatasetAvailability("OPEN INPUT");
    }

    /**
     * Closes the {@code TRANCATG} dataset, reporting only the resulting file status: the Java form of
     * {@code 9400-TRANCATG-CLOSE} ({@code app/cbl/CBTRN03C.cbl:587-603}).
     *
     * <p>Same two-way shape as {@link #open()}, and the caller likewise owns the
     * {@code DISPLAY 'ERROR CLOSING TRANSACTION CATG FILE'} line at {@code :598} and the abend.
     *
     * <p>Nothing is buffered and no connection is held between operations, so there is no flush to fail
     * and no handle to release. The one close-time failure this repository can genuinely detect is that
     * the dataset is no longer describable - the analogue of the file system reporting a problem as a
     * dataset is de-allocated - so the same probe as {@link #open()} is used. That also keeps both of
     * the COBOL's symmetric guards reachable rather than leaving one of them dead.
     *
     * @return {@link FileStatus#OK} when the dataset is still describable, otherwise
     *         {@link #PERMANENT_ERROR_STATUS}; never {@code null}, always two characters
     */
    public String close() {
        return probeDatasetAvailability("CLOSE");
    }

    /**
     * The {@code APPL-RESULT} value the open and close paragraphs move for a given file status:
     * {@code 0} for {@code '00'}, {@link #APPL_RESULT_FATAL} for anything else.
     *
     * <p>The two-armed {@code IF TRANCATG-STATUS = '00' ... ELSE MOVE 12} of
     * {@code app/cbl/CBTRN03C.cbl:451-455} and {@code :590-594}, expressed once. Both paragraphs
     * additionally move {@link #APPL_RESULT_INITIAL} beforehand, which the next statement always
     * overwrites, so it is not observable and is not reproduced as a third outcome.
     *
     * <p>Note that {@link #APPL_RESULT_FATAL} matches neither {@code APPL-AOK} ({@code VALUE 0}) nor
     * {@code APPL-EOF} ({@code VALUE 16}) at {@code :150-152}, which is why the {@code IF APPL-AOK}
     * guard that follows takes its {@code ELSE} arm and the program abends.
     *
     * @param status the two-character file status an {@link #open()} or {@link #close()} reported
     * @return {@link FileStatus#APPL_AOK} or {@link #APPL_RESULT_FATAL}
     * @throws NullPointerException if {@code status} is {@code null}
     */
    public static int applResultOfFileStatus(String status) {
        Objects.requireNonNull(status, "A file status is required to classify it; open() and close() "
                + "always return one, so an absent status is a defect in the caller");
        return FileStatus.isOk(status) ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
    }

    /**
     * The shared open/close probe: describe the dataset and confirm it presents a record-image column.
     *
     * <p>The COBOL operation is passed in rather than inferred, because the two callers are the two
     * symmetric guards {@code CBTRN03C} keeps distinct and an operator reading a refusal needs to know
     * which one failed.
     *
     * <p>Read-only and dialect-free - the predicate is false on every row, which every dialect accepts -
     * so it costs one round trip and transfers nothing. The extractor deliberately never calls
     * {@code next()}: there are no rows by construction, and the metadata alone proves the relation
     * resolved. A backend that describes the dataset with <em>no</em> column at the record-image position
     * is treated as unusable rather than as success: with the driver a deployment-time input, reporting a
     * successful open on a relation this repository cannot read would hand the report job a lookup table
     * it never actually opened.
     *
     * @param cobolOperation the COBOL verb being reproduced, for the diagnostic
     * @return {@link FileStatus#OK} or {@link #PERMANENT_ERROR_STATUS}
     */
    private String probeDatasetAvailability(String cobolOperation) {
        ResultSetExtractor<String> describe = resultSet -> {
            ResultSetMetaData metaData = resultSet.getMetaData();
            return metaData == null || metaData.getColumnCount() < RECORD_IMAGE_COLUMN_INDEX
                    ? PERMANENT_ERROR_STATUS
                    : FileStatus.OK;
        };
        try {
            String status = jdbcTemplate.query(relation.describeStatement(), describe);
            // A template that yields no status at all has told us nothing, and "nothing" is not success.
            // Reported as a permanent error, exactly as an unusable dataset is.
            return status == null ? PERMANENT_ERROR_STATUS : status;
        } catch (DataAccessException translated) {
            // The COBOL keeps no more than the status - it displays the rendered status and abends - so
            // the status is the whole of what is RETURNED. What the backend said is not thrown away with
            // it: an operator reading the abend needs to know whether the dataset was missing, the
            // credentials were refused or the backend was unreachable, and only the driver knows that.
            logRefusal(translated, cobolOperation + " the " + DD_NAME + " dataset (a describe of the "
                    + "configured relation, which is what distinguishes an absent dataset from an "
                    + "unreadable record)");
            return PERMANENT_ERROR_STATUS;
        }
    }

    // =================================================================================================
    // 1500-C-LOOKUP-TRANCATG - app/cbl/CBTRN03C.cbl:504-512, performed from :195 once per report line.
    //
    // The key is assembled by the caller at :191-194, in two moves, from the transaction record:
    //   MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE-CD OF FD-TRAN-CAT-KEY
    //   MOVE TRAN-CAT-CD  OF TRAN-RECORD TO FD-TRAN-CAT-CD  OF FD-TRAN-CAT-KEY
    // =================================================================================================

    /**
     * Builds the {@value #KEY_LENGTH}-byte {@code TRAN-CAT-KEY} image for a lookup: the Java form of the
     * two moves at {@code app/cbl/CBTRN03C.cbl:191-194}.
     *
     * <p><strong>The two halves pad in opposite directions, and getting either one wrong silently misses
     * every lookup.</strong> {@code TRAN-TYPE-CD} is {@code PIC X(02)}, so it is padded and truncated on
     * the <strong>right</strong> with spaces - a one-character {@code "X"} becomes {@code "X "}.
     * {@code TRAN-CAT-CD} is {@code PIC 9(04)}, so it is zero-filled and truncated on the
     * <strong>left</strong> - category {@code 5} becomes {@code "0005"}. Both are applied through
     * {@link FixedWidthCodec}'s named {@code PIC X} and {@code PIC 9} move helpers rather than through
     * any ad-hoc formatting, which is what makes the direction a deliberate choice per picture instead of
     * an accident; the assembly itself lives on {@link TranCategoryRecord#tranCatKeyImage(String, int,
     * Charset)}, so this repository never re-derives the key's offsets and cannot drift toward
     * {@code CVTRA01Y}'s 17-byte namesake.
     *
     * <p>Exposed publicly because the caller needs it: {@code app/cbl/CBTRN03C.cbl:507} displays
     * {@code 'INVALID TRAN CATG KEY : ' FD-TRAN-CAT-KEY} on the not-found path, so the key image is part
     * of that program's observable output. Taking the code page from this repository's own injected
     * charset means the caller does not have to choose one.
     *
     * @param tranTypeCd the 2-character transaction type code, {@code "01"} rather than 1
     * @param tranCatCd  the category code; must not be negative, because {@code PIC 9(04)} is unsigned
     *                   and has no sign position
     * @return the key image, exactly {@value #KEY_LENGTH} characters
     * @throws NullPointerException     if {@code tranTypeCd} is {@code null}
     * @throws IllegalArgumentException if {@code tranCatCd} is negative
     */
    public String keyImage(String tranTypeCd, int tranCatCd) {
        return TranCategoryRecord.tranCatKeyImage(tranTypeCd, tranCatCd, codec.charset());
    }

    /**
     * Reads one category record by its composite key: the Java form of
     * {@code READ TRANCATG-FILE INTO TRAN-CAT-RECORD ... INVALID KEY}
     * ({@code app/cbl/CBTRN03C.cbl:505-511}).
     *
     * <p>The parameters are a {@link String} then an {@code int}, in exactly the order and the Java types
     * of the two moves the caller performs at {@code :191-194}, so a caller holding a transaction record
     * passes its {@code TRAN-TYPE-CD} and {@code TRAN-CAT-CD} straight through. Both are reshaped by
     * {@link #keyImage(String, int)} before the read, so a caller need not pre-pad either half.
     *
     * <h4>Three outcomes, and exactly the three this {@code READ} can produce</h4>
     * <ul>
     *   <li><strong>{@link Outcome#OK}</strong>, status {@code '00'}, carrying the decoded record. The
     *       description arrives at its full untrimmed {@value TranCategoryRecord#TRAN_CAT_TYPE_DESC_LENGTH}
     *       bytes, because {@code :368} truncates it into a {@code PIC X(29)} receiver at the point of
     *       use;</li>
     *   <li><strong>{@link Outcome#NOT_FOUND}</strong>, status {@code '23'} - the {@code INVALID KEY}
     *       condition, and the value the COBOL itself moves into {@code IO-STATUS} at {@code :508}.
     *       <strong>Reported, never thrown</strong>: the caller owns the {@code DISPLAY} at {@code :507},
     *       the rendered status from {@code 9910-DISPLAY-IO-STATUS} and the abend at {@code :510};</li>
     *   <li><strong>{@link Outcome#OTHER}</strong>, status {@link #PERMANENT_ERROR_STATUS} - for a
     *       backend refusal, a row with no record image, a row that is not
     *       {@value #RECORD_LENGTH} bytes, or a key that selected more than
     *       {@value #UNIQUE_KEY_ROW_COUNT} row.</li>
     * </ul>
     *
     * <p><strong>Why there is no end-of-file arm and no duplicate arm.</strong>
     * {@code app/cbl/CBTRN03C.cbl:47} declares {@code ACCESS MODE IS RANDOM}, so this read is never
     * positioned and can never reach an end of file; and a KSDS primary key is unique by construction -
     * {@code app/jcl/TRANCATG.jcl} defines the cluster {@code INDEXED} with {@code KEYS(6 0)} - so a
     * batch {@code READ ... INVALID KEY} has no duplicate condition available to it. Admitting either as
     * a fourth arm would offer the caller a branch the COBOL cannot take. A second matching row is
     * therefore reported on the {@code OTHER} arm, with the observed count named in the log line: it is
     * neither a success (returning one of several as though it were the only one would be worse than
     * failing) nor a not-found, so it belongs where an unexpected condition belongs. This is a deliberate
     * departure from the sibling card repositories, whose base reads are <em>online</em>
     * {@code EXEC CICS READ}s and whose consumers do enumerate {@code DUPREC} and {@code DUPKEY}; this
     * one is batch, and its {@code READ} enumerates neither.
     *
     * <p><strong>A row of the wrong width is reported, not repaired.</strong> Padding a short row with
     * spaces looks like the faithful fix - the absent bytes could only be trailing {@code FILLER} - but
     * every row of {@code app/data/ASCII/trancatg.txt} is exactly {@value #RECORD_LENGTH} bytes, so a
     * different width can only mean the backend is not serving this layout. Decoding it anyway would
     * place {@code TRAN-CAT-TYPE-DESC} at bytes that are not its own and print a plausible but wrong
     * description on the report, with nothing anywhere saying so. (Note the contrast with the
     * cross-reference dataset, whose fixture genuinely omits its trailing {@code FILLER} and is
     * deliberately padded <em>where it is seeded</em> - risk R-F. This dataset has no such deviation and
     * needs no such normalisation.)
     *
     * @param tranTypeCd the 2-character transaction type code; never {@code null}. Pass {@code "  "} to
     *                   look up a key whose type code is spaces
     * @param tranCatCd  the category code; must not be negative
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException     if {@code tranTypeCd} is {@code null}
     * @throws IllegalArgumentException if {@code tranCatCd} is negative
     * @throws IllegalStateException    if the backend presents the dataset with no usable record-image
     *                                  column, which is a contract violation rather than an I/O outcome
     */
    public ReadResult readByKey(String tranTypeCd, int tranCatCd) {
        return readByKeyImage(keyImage(tranTypeCd, tranCatCd));
    }

    /**
     * The keyed read itself, over an already-assembled {@value #KEY_LENGTH}-character key image.
     *
     * <p>Private on purpose. A public entry point taking a raw key image would be the one door through
     * which {@code CVTRA01Y}'s 17-byte namesake could arrive, and there is no COBOL caller that needs
     * one: {@code CBTRN03C} always assembles the key from the two transaction fields. The width is
     * nonetheless re-checked by {@link KeySpan#pattern(String)}, which refuses an image that is not
     * exactly the span's width - so a short key can never silently become a prefix match over more
     * records than it names.
     *
     * @param keyImage the key image, exactly {@value #KEY_LENGTH} characters
     * @return the discriminated outcome; never {@code null}
     */
    private ReadResult readByKeyImage(String keyImage) {
        String statement;
        try {
            statement = resolveKeyedStatement();
        } catch (DataAccessException unreachable) {
            return ReadResult.other(PERMANENT_ERROR_STATUS, logRefusal(unreachable, "describe the "
                    + DD_NAME + " dataset '" + relation.dsname() + "' to read it by key"));
        }

        List<byte[]> rows;
        try {
            rows = fetch(statement, KEY_SPAN.pattern(keyImage));
        } catch (DataAccessException translated) {
            // WHEN OTHER. Reported as a status, exactly as the COBOL keeps only the status, so the
            // caller's own guard chain decides what to do about it - and carrying the backend's own
            // diagnosis alongside it, so the abend that follows can be traced to a cause. The key is
            // deliberately absent from the message: this is a wiring diagnostic, and the key is already
            // in the caller's own DISPLAY line.
            return ReadResult.other(PERMANENT_ERROR_STATUS, logRefusal(translated, "read the " + DD_NAME
                    + " dataset '" + relation.dsname() + "' by key"));
        }

        if (rows == null) {
            // A template that yielded no result object at all has told us nothing, and nothing is not an
            // empty dataset. Reported on the WHEN OTHER arm rather than mistaken for INVALID KEY.
            LOG.error("The " + DD_NAME + " dataset yielded no result object at all for a keyed read; "
                    + "reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than treating it as a dataset with no matching record");
            return ReadResult.other(PERMANENT_ERROR_STATUS);
        }
        if (rows.isEmpty()) {
            // The INVALID KEY condition at app/cbl/CBTRN03C.cbl:506. A normal branch in the caller, and
            // never an exception: the caller displays the key, renders '23' and abends.
            return ReadResult.notFound();
        }
        if (rows.size() > UNIQUE_KEY_ROW_COUNT) {
            // A 6-byte primary key selected more than one row, which a KSDS defined KEYS(6 0) cannot do.
            // The relation is not the dataset the copybook describes. The count is what says how badly,
            // so it is named - labelled as a row count, never passed off as a reason code.
            LOG.error("A single " + KEY_LENGTH + "-byte " + DD_NAME + " key selected " + rows.size()
                    + " rows; app/jcl/" + DD_NAME + ".jcl defines the cluster INDEXED with KEYS("
                    + KEY_LENGTH + " " + KEY_OFFSET + "), so its primary key is unique by construction "
                    + "and exactly " + UNIQUE_KEY_ROW_COUNT + " row can match. Reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than returning one of "
                    + "several rows as though it were the only one");
            return ReadResult.other(PERMANENT_ERROR_STATUS);
        }

        byte[] recordImage = rows.get(UNIQUE_KEY_ROW_COUNT - 1);
        if (recordImage == null) {
            // A row whose record image is absent is not a readable 60-byte record. There IS a record, it
            // simply cannot be read, so this is an I/O-level defect and belongs on the WHEN OTHER arm -
            // never silently skipped, which would turn a broken dataset into a clean INVALID KEY.
            LOG.error("The matching " + DD_NAME + " row carries no record image at column position "
                    + RECORD_IMAGE_COLUMN_INDEX + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than reporting a record that is present as absent");
            return ReadResult.other(PERMANENT_ERROR_STATUS);
        }
        if (recordImage.length != RECORD_LENGTH) {
            LOG.error("The " + DD_NAME + " dataset presented a matching row of " + recordImage.length
                    + " byte(s) - a stored record width, not a reason code - but TRAN-CAT-RECORD is "
                    + "declared " + RECORD_LENGTH + " bytes by app/cpy/" + COPYBOOK + ".cpy and every "
                    + "row of app/data/ASCII/trancatg.txt is that width; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than decoding "
                    + "TRAN-CAT-TYPE-DESC from offsets that would not be its own");
            return ReadResult.other(PERMANENT_ERROR_STATUS);
        }
        // WHEN '00'. The FILLER is retained verbatim by the decode, so re-serialising the result
        // reproduces the stored row byte for byte - including the four ASCII zeros this dataset carries
        // there rather than the spaces a built record would.
        return ReadResult.found(TranCategoryRecord.decode(recordImage, codec.charset()));
    }

    // =================================================================================================
    // Statement resolution and row transfer. This is the one place a SQL identifier is decided for this
    // dataset, and the one place the backend is asked to describe it.
    // =================================================================================================

    /**
     * Composes the keyed-read statement, discovering the record-image column's name from the backend.
     *
     * <p><strong>Why a name has to be discovered at all.</strong> Every dataset in this module is reached
     * as a relation whose first column holds the whole record image, and a positional read needs only the
     * ordinal. A keyed read cannot: SQL permits an ordinal in {@code ORDER BY} but not in a
     * {@code WHERE} clause. The name is therefore taken from the result-set metadata of the probe -
     * <em>discovered from the backend</em>, never invented here, never defaulted and never added as a
     * configuration key, because a name this file made up would be exactly the kind of unverifiable
     * literal the migration forbids.
     *
     * <p><strong>Nothing is cached.</strong> A resolved name held on this singleton would be shared
     * mutable state (practice B9). The cost is one metadata round trip per keyed read, and it is accepted
     * deliberately: this migration is explicitly not a performance refactoring, and correctness under
     * concurrent report runs is worth more here than a saved round trip.
     *
     * <p>Package-visible so this class's own tests can assert the composed text and the discovery
     * behaviour without reaching around the class. It resolves afresh on every call and stores nothing of
     * its own.
     *
     * @return the composed keyed-read statement; never {@code null}
     * @throws DataAccessException   if the dataset cannot be described - an I/O outcome, translated into a
     *                               status by the caller
     * @throws IllegalStateException if the dataset is described but presents no usable record-image
     *                               column, which is a contract violation: the whole module addresses a
     *                               dataset as a record-image relation, and one that is not cannot be read
     *                               at all
     */
    String resolveKeyedStatement() {
        ResultSetExtractor<String> columnNameExtractor =
                TranCategoryRepository::extractRecordImageColumnName;
        String columnName = jdbcTemplate.query(relation.describeStatement(), columnNameExtractor);
        return relation.selectByKey(relation.rememberRecordImageColumn(columnName));
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

    /**
     * Reads the record-image column's name from a result set's metadata, without consuming a row.
     *
     * <p>{@code next()} is never called: the probe returns no rows by construction and the metadata is
     * available regardless. A driver that reports no metadata, or a relation with no column at position
     * {@value #RECORD_IMAGE_COLUMN_INDEX}, yields {@code null} here and is rejected by
     * {@link DatasetRelation#rememberRecordImageColumn(String)} with a diagnostic, rather than becoming
     * an identifier that would address nothing.
     *
     * @param resultSet the empty result set the probe produced
     * @return the column's name, or {@code null} if the backend describes none
     * @throws SQLException if the driver cannot supply the metadata
     */
    private static String extractRecordImageColumnName(ResultSet resultSet) throws SQLException {
        return DatasetRelation.recordImageColumnOf(resultSet.getMetaData());
    }

    /**
     * Executes the keyed read, transferring at most {@value #KEYED_READ_ROW_LIMIT} rows.
     *
     * <p>Both row limits are set deliberately: {@code setMaxRows} bounds what the backend will hand over,
     * {@code setFetchSize} bounds what it carries across in a round trip, and the extractor stops at the
     * same number so a driver that honours neither still cannot flood this method. Two rather than one,
     * because detecting a duplicate requires seeing the second.
     *
     * <p>The pattern is a composed predicate rather than a stored record, so it is bound as a comparison
     * <em>operand</em> - in the same representation as the column it is compared with, which is what
     * stops a keyed read from comparing characters against bytes.
     *
     * @param statement the composed keyed statement
     * @param pattern   the escaped {@code LIKE} pattern confining the match to the key's own bytes
     * @return the matching record images, at most {@value #KEYED_READ_ROW_LIMIT} of them, or {@code null}
     *         if the template yielded no result at all
     */
    private List<byte[]> fetch(String statement, String pattern) {
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(KEYED_READ_ROW_LIMIT);
            prepared.setFetchSize(KEYED_READ_ROW_LIMIT);
            recordImageForm.bindOperand(prepared, 1, pattern, codec.charset());
            return prepared;
        };
        RowMapper<byte[]> recordImageMapper = this::mapRecordImage;
        ResultSetExtractor<List<byte[]>> extractor = resultSet -> {
            List<byte[]> images = new ArrayList<>(KEYED_READ_ROW_LIMIT);
            int rowNumber = 0;
            while (images.size() < KEYED_READ_ROW_LIMIT && resultSet.next()) {
                images.add(recordImageMapper.mapRow(resultSet, rowNumber++));
            }
            return images;
        };
        return jdbcTemplate.query(creator, extractor);
    }

    /**
     * Maps one result-set row to its record image, through the configured representation.
     *
     * <p>By <em>position</em> {@value #RECORD_IMAGE_COLUMN_INDEX} and never by a column name, for the
     * reason given on this class: a VSAM dataset carries no relational metadata and is presented as a
     * single record-image column, so a copybook field name is not a column and naming one here would be
     * an unverifiable literal. Whether the column is read as characters or as bytes is
     * {@link RecordImageForm}'s decision and not this method's. The value is <strong>not</strong>
     * trimmed: the trailing bytes of this record are the {@code FILLER} and they are part of it.
     *
     * @param resultSet the row, positioned by the extractor
     * @param rowNumber the 0-based row index, part of the {@link RowMapper} contract and not used here:
     *                  every row of this dataset has the identical shape, so the index carries no meaning
     * @return the row's record image, which may be {@code null} if the column holds no value - a
     *         condition the caller classifies rather than ignores
     * @throws SQLException if the driver cannot supply the column
     */
    private byte[] mapRecordImage(ResultSet resultSet, int rowNumber) throws SQLException {
        return recordImageForm.readImage(resultSet, RECORD_IMAGE_COLUMN_INDEX, codec.charset());
    }

    /**
     * Logs a backend refusal with the driver's own diagnosis and returns it for the caller to carry.
     *
     * <p>Logged: what was attempted, the dataset, and the {@code SQLSTATE}, vendor code and exception
     * type the driver reported. <strong>Not</strong> logged: the key, the record image, or any part of
     * either - and not the exception itself. That last omission looks like a loss and is not: a driver's
     * message is prose the backend composed <em>around the values it refused</em>, so handing the
     * {@link Throwable} to the logger emits that text and its whole cause chain verbatim (CWE-532), and a
     * control character anywhere in it splits the entry in two (CWE-117). Sanitising the summary and then
     * attaching the raw exception beside it sanitises nothing. The codes that survive are what separate an
     * absent dataset from wrong credentials from a dropped connection, which is the whole of what an
     * operator acts on; the driver's own words remain in the driver's own log, which is access-controlled
     * as an application log is not.
     *
     * @param refusal the exception the backend or the framework raised
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
     * The discriminated outcome of one keyed read of {@code TRANCATG}: the classification
     * {@code 1500-C-LOOKUP-TRANCATG} performs, and nothing beyond it
     * ({@code app/cbl/CBTRN03C.cbl:504-512}).
     *
     * <p>Three shapes, built through {@link #found(TranCategoryRecord)}, {@link #notFound()} and
     * {@link #other(String)}. The vocabulary is {@link Outcome}, shared with every other dataset
     * operation in the module so a caller's branch structure looks the same whichever legacy program it
     * came from, and it is deliberately narrowed to the three arms this {@code READ} has:
     * {@link Outcome#END_OF_FILE} cannot arise under {@code ACCESS MODE IS RANDOM} ({@code :47}) and
     * {@link Outcome#DUPLICATE} has no counterpart in a batch {@code READ ... INVALID KEY} against a
     * unique KSDS key, so both are rejected here rather than admitted as arms no caller could act on.
     *
     * <p>The record is present for, and only for, a successful read - the invariant is enforced, not
     * merely documented - so a caller can neither find a record on a not-found result nor lose one on a
     * successful read.
     *
     * @param status     the two-character {@code TRANCATG-STATUS} the read reported, verbatim: {@code '00'},
     *                   {@code '23'}, or a permanent-error status
     * @param outcome    its classification: {@link Outcome#OK}, {@link Outcome#NOT_FOUND} or
     *                   {@link Outcome#OTHER}
     * @param record     the decoded record, present exactly when {@code outcome} is {@link Outcome#OK}
     * @param diagnostic what the backend reported when it refused, present only on an
     *                   {@link Outcome#OTHER} arm the driver described - so the abend that follows can be
     *                   traced to a cause rather than to a status this class composed
     */
    public record ReadResult(String status,
                             Outcome outcome,
                             Optional<TranCategoryRecord> record,
                             Optional<BackendDiagnostic> diagnostic) {

        /**
         * Enforces every invariant of the three-armed ladder at construction.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly two characters, if
         *                                  {@code outcome} is not one of the three arms, if the record is
         *                                  present when the outcome is not success or absent when it is,
         *                                  or if {@code status} contradicts {@code outcome}
         */
        public ReadResult {
            Objects.requireNonNull(status, "A read result carries the two-character file status the read "
                    + "reported; it is never absent");
            Objects.requireNonNull(outcome, "A read result carries its classification; it is never "
                    + "absent");
            Objects.requireNonNull(record, "A read result carries an empty record rather than a null "
                    + "one, so no null escapes the type");
            Objects.requireNonNull(diagnostic, "A read result carries an empty diagnostic rather than a "
                    + "null one, so no null escapes the type");
            if (status.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalArgumentException("A file status is exactly "
                        + FileStatus.STATUS_LENGTH + " characters, as TRANCATG-STATUS is declared at "
                        + "app/cbl/" + CONSUMER_PROGRAM + ".cbl:109-111; got " + status.length() + ".");
            }
            if (outcome != Outcome.OK && outcome != Outcome.NOT_FOUND && outcome != Outcome.OTHER) {
                throw new IllegalArgumentException("The READ at app/cbl/" + CONSUMER_PROGRAM
                        + ".cbl:505-511 has two named outcomes - success and INVALID KEY - plus a "
                        + "catch-all, so a " + DD_NAME + " read is classified as OK, NOT_FOUND or OTHER. "
                        + "END_OF_FILE cannot arise under ACCESS MODE IS RANDOM and DUPLICATE has no "
                        + "counterpart on a unique KSDS key; got " + outcome + ".");
            }
            if (record.isPresent() != (outcome == Outcome.OK)) {
                throw new IllegalArgumentException(record.isPresent()
                        ? "A read that did not succeed carries no record: outcome " + outcome
                                + " was given one. Only the successful arm reaches TRAN-CAT-RECORD."
                        : "A successful read carries the decoded record, and this one carries none.");
            }
            String expectedForOutcome = outcome.batchStatus().orElse(null);
            if (expectedForOutcome != null && !expectedForOutcome.equals(status)) {
                throw new IllegalArgumentException("Outcome " + outcome + " corresponds to status '"
                        + expectedForOutcome + "', but status '" + status + "' was given; the status and "
                        + "its classification must agree.");
            }
            if (expectedForOutcome == null
                    && (FileStatus.isOk(status) || FileStatus.isNotFound(status))) {
                throw new IllegalArgumentException("Status '" + status + "' is one of the two statuses "
                        + "this READ names explicitly, so it cannot be classified as the catch-all "
                        + "arm.");
            }
        }

        /**
         * The successful arm: the {@code READ} completed and {@code TRAN-CAT-RECORD} holds the category.
         *
         * @param record the decoded record
         * @return a result carrying status {@link FileStatus#OK} and the record
         * @throws NullPointerException if {@code record} is {@code null}
         */
        public static ReadResult found(TranCategoryRecord record) {
            Objects.requireNonNull(record, "A successful read carries the decoded TRAN-CAT-RECORD");
            return new ReadResult(FileStatus.OK, Outcome.OK, Optional.of(record), Optional.empty());
        }

        /**
         * The {@code INVALID KEY} arm: status {@code '23'}, which is the value the COBOL itself moves
         * into {@code IO-STATUS} at {@code app/cbl/CBTRN03C.cbl:508} before rendering it and abending.
         *
         * <p>Carries no record and no diagnostic. There was no backend refusal - the read worked and the
         * key simply names nothing - so there is nothing for the driver to have said about it.
         *
         * @return a result carrying status {@link FileStatus#NOT_FOUND} and no record
         */
        public static ReadResult notFound() {
            return new ReadResult(FileStatus.NOT_FOUND, Outcome.NOT_FOUND, Optional.empty(),
                    Optional.empty());
        }

        /**
         * The catch-all arm, for a condition the {@code READ} does not name.
         *
         * @param status the two-character status, carried verbatim so the caller can render it exactly as
         *               {@code 9910-DISPLAY-IO-STATUS} does
         * @return a result carrying {@code status} and no record
         * @throws NullPointerException     if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly two characters, or is one of
         *                                  the two statuses the {@code READ} names explicitly
         */
        public static ReadResult other(String status) {
            return new ReadResult(status, Outcome.OTHER, Optional.empty(), Optional.empty());
        }

        /**
         * The catch-all arm, carrying what the backend actually said about the refusal.
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
         * Whether the read succeeded and a category record is available.
         *
         * @return {@code true} for the successful arm
         */
        public boolean isFound() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether the key named no record: the {@code INVALID KEY} condition at
         * {@code app/cbl/CBTRN03C.cbl:506}.
         *
         * @return {@code true} for the {@code '23'} arm
         */
        public boolean isNotFound() {
            return outcome == Outcome.NOT_FOUND;
        }

        /**
         * Whether the read failed in a way the {@code READ} does not name.
         *
         * @return {@code true} for the catch-all arm
         */
        public boolean isOther() {
            return outcome == Outcome.OTHER;
        }

        /**
         * Whether the COBOL path from this outcome reaches {@code 9999-ABEND-PROGRAM}.
         *
         * <p>{@code true} for both failing arms, and that is not an over-simplification: for this
         * program a missing category is as fatal as an I/O error, because {@code :506-510} routes
         * {@code INVALID KEY} straight through {@code 9910-DISPLAY-IO-STATUS} to the abend with no
         * recovery in between. It is expressed as a method rather than as an {@code APPL-RESULT} value
         * because {@code 1500-C-LOOKUP-TRANCATG} <em>never assigns</em> {@code APPL-RESULT} - unlike the
         * open and close paragraphs, which do, and which are served by
         * {@link TranCategoryRepository#applResultOfFileStatus(String)}. Reporting a fabricated
         * {@code APPL-RESULT} here would invent a quantity the paragraph does not compute.
         *
         * @return {@code false} only for the successful arm
         */
        public boolean abends() {
            return outcome != Outcome.OK;
        }

        /**
         * The four-character {@code IO-STATUS-04} image {@code 9910-DISPLAY-IO-STATUS} renders for this
         * status ({@code app/cbl/CBTRN03C.cbl:633-646}).
         *
         * <p>{@code '00'} renders as {@code 0000}, the {@code INVALID KEY} status {@code '23'} as
         * {@code 0023}, and a permanent error as the {@code 9nnn} form - which is why
         * {@link TranCategoryRepository#PERMANENT_ERROR_STATUS} is spelled the way it is. The renderer
         * itself is shared module-wide, so this is a delegation rather than a second implementation of a
         * paragraph that is duplicated across eight batch programs.
         *
         * @return exactly {@link FileStatus#STATUS_IMAGE_LENGTH} characters
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }
    }
}

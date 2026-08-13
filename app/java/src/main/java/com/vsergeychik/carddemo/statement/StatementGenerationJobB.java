package com.vsergeychik.carddemo.statement;

import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
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
import com.vsergeychik.carddemo.statement.model.Stm03CustomerRecord;
import com.vsergeychik.carddemo.statement.model.TrnxRecord;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

/**
 * The Java form of {@code app/cbl/CBSTM03B.CBL} - the four-file, six-operation data-access
 * subroutine that {@code CBSTM03A} calls thirteen times to read every input the account statement
 * job consumes.
 *
 * <h2>This is a {@code @Component}, not a Spring Batch {@code Job} (gate G12)</h2>
 * <p>The prompt-mandated class name says "Job". The source says otherwise, and AAP rule <strong>R1</strong>
 * settles it: <em>the name comes from the prompt, the behaviour comes from the source.</em> Three
 * independent proofs, each verified against this checkout:
 * <ol>
 *   <li>{@code app/cbl/CBSTM03B.CBL:7} declares {@code * Type        : BATCH COBOL Subroutine}, and
 *       {@code :26} adds {@code * It does file handling}.</li>
 *   <li>{@code grep -rn "CBSTM03B" app/jcl app/proc app/csd} returns <strong>nothing</strong>. There is
 *       no {@code EXEC PGM=CBSTM03B} in any of the twenty-nine jobs or two procs, and no
 *       {@code DEFINE PROGRAM(CBSTM03B)} in {@code app/csd/CARDDEMO.CSD}. Nothing in the estate can
 *       start it as a program.</li>
 *   <li>{@code app/cbl/CBSTM03A.CBL} issues {@code CALL 'CBSTM03B' USING WS-M03B-AREA} at
 *       {@code :351}, {@code :377}, {@code :401}, {@code :734}, {@code :746}, {@code :769},
 *       {@code :787}, {@code :805}, {@code :835}, {@code :860}, {@code :877}, {@code :893} and
 *       {@code :909} - thirteen call sites, which is what a subroutine gets and what a job never
 *       does.</li>
 * </ol>
 * <p>Consequently this class carries no {@code @Configuration}, declares no {@code Job} or
 * {@code Step} bean, implements no {@code Tasklet}, and imports nothing whatsoever from
 * Spring Batch of any kind (practice <strong>B2</strong>). It is a plain collaborator that
 * {@link StatementGenerationJobA}-equivalent code constructor-injects and calls.
 *
 * <h2>This class is the entire data-access layer of the statement job</h2>
 * <p>The most consequential structural finding of the migration: {@code app/cbl/CBSTM03A.CBL} declares
 * only <strong>two</strong> files, {@code HTMLFILE} and {@code STMTFILE}, and both are outputs. All
 * <strong>four inputs</strong> are declared here, in {@code CBSTM03B.CBL:31-53} - which is exactly why
 * the caller has to come back thirteen times. {@code CBSTM03B}'s
 * {@code PROCEDURE DIVISION USING LK-M03B-AREA} is a repository interface expressed in COBOL: a DD-name
 * selector, an operation code, a status, a key, a key length and a thousand-byte record area.
 * {@code app/jcl/CREASTMT.JCL:83-86} binds the four DD names this class resolves.
 *
 * <h2>The linkage area is 1040 bytes, and it is INOUT</h2>
 * <p>{@code CBSTM03B.CBL:99-112} declares {@code 8 + 1 + 2 + 25 + 4 + 1000 = }
 * {@value #AREA_LENGTH} bytes. It is a single shared area, so several of its fields are read
 * <em>and</em> written by one call: {@code LK-M03B-RC} and {@code LK-M03B-FLDT} both arrive carrying
 * whatever the caller last put there and leave carrying whatever this subroutine did or did not put
 * there. That is not a detail - it is the whole of the {@code WHEN OTHER} behaviour documented below,
 * and it is why {@link Request} carries a status and a record area alongside the DD name, the operation
 * and the key.
 *
 * <h2>Four independent operation sets, not six uniform ones</h2>
 * <p>Six operation codes are declared in the linkage ({@code CBSTM03B.CBL:103-108}) but no DD supports
 * more than three of them, and the three differ by DD. Verified from {@code FILE-CONTROL}
 * ({@code :31-53}) and the four {@code PROC} paragraphs ({@code :133-229}):
 *
 * <table border="1">
 *   <caption>The per-DD operation matrix, as the source implements it</caption>
 *   <tr><th>DD</th><th>{@code ACCESS MODE}</th><th>{@code RECORD KEY}</th><th>Implemented</th></tr>
 *   <tr><td>{@value #TRNXFILE_DD}</td><td>SEQUENTIAL</td><td>{@code FD-TRNXS-ID} (32-byte group)</td>
 *       <td>{@code OPEN INPUT} :136, sequential {@code READ ... INTO} :141, {@code CLOSE} :147</td></tr>
 *   <tr><td>{@value #XREFFILE_DD}</td><td>SEQUENTIAL</td><td>{@code FD-XREF-CARD-NUM X(16)}</td>
 *       <td>{@code OPEN INPUT} :160, sequential {@code READ ... INTO} :165, {@code CLOSE} :171</td></tr>
 *   <tr><td>{@value #CUSTFILE_DD}</td><td>RANDOM</td><td>{@code FD-CUST-ID X(09)}</td>
 *       <td>{@code OPEN INPUT} :184, <em>keyed</em> {@code READ} :188-190, {@code CLOSE} :196</td></tr>
 *   <tr><td>{@value #ACCTFILE_DD}</td><td>RANDOM</td><td>{@code FD-ACCT-ID 9(11)}</td>
 *       <td>{@code OPEN INPUT} :209, <em>keyed</em> {@code READ} :213-215, {@code CLOSE} :221</td></tr>
 * </table>
 *
 * <p>So the two sequential files have <strong>no keyed read</strong>, the two random files have
 * <strong>no sequential read</strong>, and {@code M03B-WRITE} ({@code 'W'}) and {@code M03B-REWRITE}
 * ({@code 'Z'}) are tested <strong>nowhere in the program</strong>. All four files are
 * {@code OPEN INPUT}: this class never writes a record, never rewrites one and never deletes one.
 * Practice <strong>B5</strong> requires the two dead codes to be declared and left unimplemented, so
 * {@link Operation} declares all six and {@link #supportedOperations(String)} reports the three each DD
 * actually honours.
 *
 * <h2>The silent no-op fall-through, and why hardening it would break parity</h2>
 * <p>Every {@code PROC} paragraph is a chain of three {@code IF <op>} blocks, and in each one the
 * {@code GO TO nnn900-EXIT} sits <strong>inside</strong> the {@code IF}. When none of the three
 * conditions matches - a {@code WRITE} against {@value #TRNXFILE_DD}, say - control falls out of the
 * third {@code IF} and straight into {@code nnn900-EXIT}, whose one statement is
 * {@code MOVE <dd>FILE-STATUS TO LK-M03B-RC}. Two consequences, both reproduced exactly:
 * <ul>
 *   <li>An <strong>unsupported operation performs no I/O and returns that file's last-known
 *       {@code FILE STATUS}</strong> - the stale one. It is not an error, not an exception and not a
 *       freshly computed status.</li>
 *   <li>The status move runs on <strong>every</strong> path through a matched DD, matched operation or
 *       not. Only the DD-dispatch {@code WHEN OTHER} skips it.</li>
 * </ul>
 * <p>Turning either of these into a thrown exception is the single most likely parity break in this
 * file, which is why both are asserted by tests rather than merely described here.
 *
 * <h2>{@code WHEN OTHER} leaves the area untouched</h2>
 * <p>{@code CBSTM03B.CBL:127-131}: an unrecognised DD name goes {@code GO TO 9999-GOBACK} and returns.
 * No file is touched, no status is assigned, and {@code LK-M03B-RC} still holds whatever the caller put
 * there - which at all thirteen call sites is the {@code MOVE ZERO TO WS-M03B-RC} they perform first.
 * {@link #call(Session, Request)} therefore echoes the request's own status and record area back, and
 * invents nothing.
 *
 * <h2>Where the mutable state lives (practices B9, gate G53)</h2>
 * <p>The COBOL subprogram is genuinely stateful <em>across</em> its thirteen invocations: four open-file
 * cursors and four independent two-byte {@code FILE STATUS} areas ({@code CBSTM03B.CBL:83-97}) survive
 * from one {@code CALL} to the next. That state is modelled as a per-execution {@link Session} the
 * component hands out and the caller passes back, so this component holds <strong>no cursor, no status
 * and no static mutable field</strong> of its own, and two concurrent statement runs cannot see each
 * other's positions. Everything injected is {@code final} and arrives through the constructor.
 *
 * <h2>Statuses only - this class never abends</h2>
 * <p>{@code CBSTM03B} has no abend path at all; the one in the statement job is
 * {@code app/cbl/CBSTM03A.CBL:923}'s {@code PERFORM 9999-ABEND-PROGRAM}, reached from the caller's own
 * status arms. So every outcome here is reported as a two-character {@code FILE STATUS} and
 * {@code AbendException} is never thrown, which also keeps {@code FileStatus} free of a dependency on
 * it. The caller's expectations, for orientation: {@code OPEN} and {@code CLOSE} accept
 * {@code '00' OR '04'}; the sequential-read arms distinguish {@code '00'} / {@code '10'} / OTHER
 * ({@code CBSTM03A.CBL:353-359}, {@code :837-847}); the keyed-read arms distinguish only {@code '00'} /
 * OTHER, with no {@code '10'} arm at all ({@code :379-386}, {@code :403-410}).
 *
 * <h2>Record format: a documented conflict, deliberately not reconciled (practice B4)</h2>
 * <p>{@code app/csd/CARDDEMO.CSD} declares {@code RECORDFORMAT(V)} for {@code ACCTDAT} ({@code :6}),
 * {@code CCXREF} ({@code :43}) and {@code CUSTDAT} ({@code :56}), while the batch JCL declares fixed
 * records - {@code app/jcl/CREASTMT.JCL:50} is {@code DCB=(LRECL=350,BLKSIZE=3500,RECFM=FB)}. The two
 * disagree in the legacy estate. This class does <strong>not</strong> pick a winner and does not
 * "correct" either source: it treats record length as <strong>copybook-fixed</strong> throughout,
 * taking every width from the {@code RECORD_LENGTH} constant of the model type that owns the copybook
 * and cross-checking it against the configured binding at construction. The conflict is recorded here
 * so a reviewer meets it as a known property of the source rather than as an inconsistency in the
 * migration.
 *
 * <h2>{@code FD-ACCT-DATA} is declared twice, and stays that way (practice B5)</h2>
 * <p>{@code CBSTM03B.CBL:63} declares {@code 05 FD-ACCT-DATA PIC X(318)} inside the
 * {@value #TRNXFILE_DD} record, and {@code :78} declares {@code 05 FD-ACCT-DATA PIC X(289)} inside the
 * {@value #ACCTFILE_DD} record. The reuse is legal COBOL because the two sit in different {@code FD}
 * records, and it is preserved rather than tidied: the two spans surface as
 * {@link #TRNXFILE_ACCT_DATA_LENGTH} and {@link #ACCTFILE_ACCT_DATA_LENGTH}, two distinct constants
 * over two distinct record types, neither renamed to something "clearer" and neither merged into the
 * other.
 *
 * <h2>Data access</h2>
 * <p>Reached through the module's shared {@link JdbcTemplate} over the configuration-bound
 * {@code DataSource}. Every dataset name is resolved from the {@code carddemo.datasets} catalogue by DD
 * name, so not one dataset-name literal appears in this file (gate G46); the record code page
 * is injected by bean name and never taken from the platform (practice B8); records are handled as
 * fixed-width bytes by absolute offset through the hand-written {@link FixedWidthCodec} (practice
 * B11); and only the access paths the COBOL uses are exposed - a sequential browse for the two
 * sequential files and a keyed read for the two random ones. No insert, update, delete, upsert or
 * aggregate statement exists here, and no DDL, entity annotation, migration or version column (gate
 * G44).
 *
 * <h2>Raw bytes out, no decode</h2>
 * <p>{@code READ ... INTO LK-M03B-FLDT} is a group move of a 350-, 50-, 500- or 300-byte record into an
 * {@code X(1000)} receiver, which leaves the record left-justified and the remainder spaces. This class
 * reproduces that and stops there: {@link Response#fldt()} is always {@value #FLDT_LENGTH} characters
 * and is never decoded into a model type, because the caller does that itself
 * ({@code MOVE WS-M03B-FLDT TO TRNX-RECORD} at {@code CBSTM03A.CBL:756}, and its siblings for the other
 * three). The model types are depended on only for their {@code RECORD_LENGTH} and key constants.
 *
 * <h2>Usage</h2>
 * <pre>
 * try (StatementGenerationJobB.Session session = subroutine.newSession()) {
 *     subroutine.call(session, Request.open(StatementGenerationJobB.TRNXFILE_DD));
 *     Response response = subroutine.call(session, Request.read(StatementGenerationJobB.TRNXFILE_DD));
 *     while (FileStatus.isOk(response.rc())) {
 *         consume(response.fldt());
 *         response = subroutine.call(session, Request.read(StatementGenerationJobB.TRNXFILE_DD));
 *     }
 *     subroutine.call(session, Request.close(StatementGenerationJobB.TRNXFILE_DD));
 * }
 * </pre>
 *
 * @see FileStatus
 * @see FixedWidthCodec
 */
@Component
public class StatementGenerationJobB {

    /**
     * Diagnostics for backend refusals and data-integrity defects.
     *
     * <p>{@code static final} and immutable, so it is not shared mutable state (gate G53). What reaches
     * it is the DD name, the operation and the driver's own diagnosis; never a key and never a record
     * image, because these four datasets carry card numbers, government-issued identifiers and dates of
     * birth, and a log is read by more people and guarded less than the dataset it describes.
     */
    private static final Log LOG = LogFactory.getLog(StatementGenerationJobB.class);

    // =================================================================================================
    // The linkage area. app/cbl/CBSTM03B.CBL:99-112, field for field, offset for offset.
    // =================================================================================================

    /**
     * Total width of {@code LK-M03B-AREA} in bytes: exactly {@code 1040}.
     *
     * <p>{@code CBSTM03B.CBL:100-112} sums to {@code 8 + 1 + 2 + 25 + 4 + 1000}. Declared as a constant
     * and asserted by the static initialiser below, so a later edit to any single field width cannot
     * silently change the contract the caller's {@code 01 WS-M03B-AREA} has to match
     * ({@code app/cbl/CBSTM03A.CBL:71-84}, which declares the identical group).
     */
    public static final int AREA_LENGTH = 1040;

    /** Zero-based offset of {@code LK-M03B-DD} within the area: {@code 0}. */
    public static final int DD_OFFSET = 0;

    /** Declared width of {@code LK-M03B-DD PIC X(08)}: {@code 8}. */
    public static final int DD_LENGTH = 8;

    /** Zero-based offset of {@code LK-M03B-OPER} within the area: {@code 8}. */
    public static final int OPER_OFFSET = DD_OFFSET + DD_LENGTH;

    /** Declared width of {@code LK-M03B-OPER PIC X(01)}: {@code 1}. */
    public static final int OPER_LENGTH = 1;

    /** Zero-based offset of {@code LK-M03B-RC} within the area: {@code 9}. */
    public static final int RC_OFFSET = OPER_OFFSET + OPER_LENGTH;

    /**
     * Declared width of {@code LK-M03B-RC PIC X(02)}: {@code 2}.
     *
     * <p>Taken from {@link FileStatus#STATUS_LENGTH} rather than written as a literal, because it is the
     * same two-byte {@code FILE STATUS} width the whole module already agrees on.
     */
    public static final int RC_LENGTH = FileStatus.STATUS_LENGTH;

    /** Zero-based offset of {@code LK-M03B-KEY} within the area: {@code 11}. */
    public static final int KEY_OFFSET = RC_OFFSET + RC_LENGTH;

    /**
     * Declared width of {@code LK-M03B-KEY PIC X(25)}: {@code 25}.
     *
     * <p>Wide enough for either key the caller supplies and longer than both, which is precisely why the
     * reference modification at {@code CBSTM03B.CBL:189} and {@code :214} exists: the used prefix is
     * {@code LK-M03B-KEY-LN} bytes and the rest is padding.
     */
    public static final int KEY_LENGTH = 25;

    /** Zero-based offset of {@code LK-M03B-KEY-LN} within the area: {@code 36}. */
    public static final int KEY_LN_OFFSET = KEY_OFFSET + KEY_LENGTH;

    /**
     * Declared width of {@code LK-M03B-KEY-LN PIC S9(4)}: {@code 4} bytes.
     *
     * <p><strong>{@code DISPLAY}, not {@code COMP}.</strong> The declaration at
     * {@code CBSTM03B.CBL:111} carries no {@code USAGE} clause, so it is four zoned-decimal bytes with
     * the sign overpunched into the trailing byte - not the two bytes a {@code COMP} halfword would
     * occupy. Reading it as binary would misalign {@code LK-M03B-FLDT} by two bytes and every record the
     * caller decoded would be garbage.
     */
    public static final int KEY_LN_LENGTH = 4;

    /** Zero-based offset of {@code LK-M03B-FLDT} within the area: {@code 40}. */
    public static final int FLDT_OFFSET = KEY_LN_OFFSET + KEY_LN_LENGTH;

    /**
     * Declared width of {@code LK-M03B-FLDT PIC X(1000)}: {@code 1000}.
     *
     * <p>Deliberately not any dataset's record length. It is the receiver of a group {@code MOVE}, so a
     * 350-, 50-, 500- or 300-byte record lands left-justified and the remaining bytes are spaces.
     */
    public static final int FLDT_LENGTH = 1000;

    // =================================================================================================
    // The four DD names. app/cbl/CBSTM03B.CBL:118-126 tests these literals; app/jcl/CREASTMT.JCL:83-86
    // binds them; application.yml declares them under carddemo.datasets. Each is exactly DD_LENGTH
    // characters, which is what makes an exact match against LK-M03B-DD X(08) meaningful.
    // =================================================================================================

    /** {@code 'TRNXFILE'} - the sorted transaction extract, {@code CBSTM03B.CBL:31-35} and {@code :119}. */
    public static final String TRNXFILE_DD = "TRNXFILE";

    /** {@code 'XREFFILE'} - the card cross reference, {@code CBSTM03B.CBL:37-41} and {@code :121}. */
    public static final String XREFFILE_DD = "XREFFILE";

    /** {@code 'CUSTFILE'} - the customer master, {@code CBSTM03B.CBL:43-47} and {@code :123}. */
    public static final String CUSTFILE_DD = "CUSTFILE";

    /** {@code 'ACCTFILE'} - the account master, {@code CBSTM03B.CBL:49-53} and {@code :125}. */
    public static final String ACCTFILE_DD = "ACCTFILE";

    /**
     * The four DD names in the exact order {@code EVALUATE LK-M03B-DD} tests them
     * ({@code CBSTM03B.CBL:118-126}).
     *
     * <p>Immutable and ordered. The order is behaviour, not presentation: gate G30 requires the
     * {@code EVALUATE} arms to keep their source sequence with {@code WHEN OTHER} last, and this list is
     * what a test asserts that against.
     */
    public static final List<String> DD_NAMES =
            List.of(TRNXFILE_DD, XREFFILE_DD, CUSTFILE_DD, ACCTFILE_DD);

    // =================================================================================================
    // The FD record layouts. app/cbl/CBSTM03B.CBL:58-78. Every width is taken from the RECORD_LENGTH or
    // key constant of the model type that owns the copybook, never written as a literal here, so a
    // disagreement between this class and a copybook cannot exist: there is only one number.
    // =================================================================================================

    /**
     * {@value #TRNXFILE_DD} record width: {@code 350} bytes.
     *
     * <p>{@code CBSTM03B.CBL:59-63} splits it {@code FD-TRNXS-ID} ({@code FD-TRNX-CARD X(16)} +
     * {@code FD-TRNX-ID X(16)}) + {@code FD-ACCT-DATA X(318)}, which sums to 350 and independently
     * corroborates {@code app/cpy/COSTM01.CPY}. {@code app/jcl/CREASTMT.JCL:32}'s IDCAMS
     * {@code RECORDSIZE(350 350)} corroborates it a third time.
     */
    public static final int TRNXFILE_RECORD_LENGTH = TrnxRecord.RECORD_LENGTH;

    /**
     * {@value #TRNXFILE_DD} key width: {@code 32} bytes at offset {@code 0}.
     *
     * <p>{@code RECORD KEY IS FD-TRNXS-ID} ({@code CBSTM03B.CBL:34}) names the 32-byte group of card
     * number and transaction id, and {@code app/jcl/CREASTMT.JCL:30}'s {@code KEYS(32 0)} states the same
     * geometry to IDCAMS. Held for the configuration cross-check; the browse itself is sequential and
     * never positions by this key.
     */
    public static final int TRNXFILE_KEY_LENGTH = TrnxRecord.TRNX_KEY_LENGTH;

    /**
     * The {@value #TRNXFILE_DD} record's data span after its key: {@code 318} bytes.
     *
     * <p><strong>This is the first of the two {@code FD-ACCT-DATA} declarations</strong> -
     * {@code CBSTM03B.CBL:63}, {@code 05 FD-ACCT-DATA PIC X(318)}, inside the {@value #TRNXFILE_DD}
     * record. The second is {@link #ACCTFILE_ACCT_DATA_LENGTH}. The source reuses one field name across
     * two {@code FD} records, which COBOL permits because they are different records, and practice
     * <strong>B5</strong> requires the reuse to be preserved rather than tidied away: two distinct
     * constants over two distinct types, neither renamed and neither merged.
     */
    public static final int TRNXFILE_ACCT_DATA_LENGTH = TrnxRecord.TRNX_REST_LENGTH;

    /**
     * {@value #XREFFILE_DD} record width: {@code 50} bytes.
     *
     * <p>{@code CBSTM03B.CBL:66-68} splits it {@code FD-XREF-CARD-NUM X(16)} +
     * {@code FD-XREF-DATA X(34)}, summing to 50 and corroborating {@code app/cpy/CVACT03Y.cpy}'s
     * {@code (RECLN 50)}.
     */
    public static final int XREFFILE_RECORD_LENGTH = CardXrefRecord.RECORD_LENGTH;

    /**
     * {@value #XREFFILE_DD} key width: {@code 16} bytes at offset {@code 0}.
     *
     * <p>{@code RECORD KEY IS FD-XREF-CARD-NUM} ({@code CBSTM03B.CBL:40}), which is
     * {@code XREF-CARD-NUM PIC X(16)}. Held for the configuration cross-check; this browse is sequential
     * too.
     */
    public static final int XREFFILE_KEY_LENGTH = CardXrefRecord.XREF_CARD_NUM_LENGTH;

    /** The {@value #XREFFILE_DD} record's data span after its key: {@code 34} bytes, {@code CBSTM03B.CBL:68}. */
    public static final int XREFFILE_DATA_LENGTH = XREFFILE_RECORD_LENGTH - XREFFILE_KEY_LENGTH;

    /**
     * {@value #CUSTFILE_DD} record width: {@code 500} bytes.
     *
     * <p>{@code CBSTM03B.CBL:71-73} splits it {@code FD-CUST-ID X(09)} + {@code FD-CUST-DATA X(491)},
     * summing to 500 and corroborating {@code app/cpy/CUSTREC.cpy}.
     */
    public static final int CUSTFILE_RECORD_LENGTH = Stm03CustomerRecord.RECORD_LENGTH;

    /**
     * {@value #CUSTFILE_DD} key width: {@code 9} bytes at offset {@code 0}.
     *
     * <p>{@code RECORD KEY IS FD-CUST-ID} ({@code CBSTM03B.CBL:46}), declared {@code PIC X(09)} at
     * {@code :72} - an <em>alphanumeric</em> receiver, which is what makes the keyed move at {@code :189}
     * a same-width byte copy rather than a numeric alignment.
     */
    public static final int CUSTFILE_KEY_LENGTH = Stm03CustomerRecord.KEY_LENGTH;

    /** The {@value #CUSTFILE_DD} record's data span after its key: {@code 491} bytes, {@code CBSTM03B.CBL:73}. */
    public static final int CUSTFILE_DATA_LENGTH = CUSTFILE_RECORD_LENGTH - CUSTFILE_KEY_LENGTH;

    /**
     * {@value #ACCTFILE_DD} record width: {@code 300} bytes.
     *
     * <p>{@code CBSTM03B.CBL:76-78} splits it {@code FD-ACCT-ID 9(11)} + {@code FD-ACCT-DATA X(289)},
     * summing to 300 and corroborating {@code app/cpy/CVACT01Y.cpy}'s {@code (RECLN 300)}.
     */
    public static final int ACCTFILE_RECORD_LENGTH = AccountRecord.RECORD_LENGTH;

    /**
     * {@value #ACCTFILE_DD} key width: {@code 11} bytes at offset {@code 0}.
     *
     * <p>{@code RECORD KEY IS FD-ACCT-ID} ({@code CBSTM03B.CBL:52}), declared {@code PIC 9(11)} at
     * {@code :77} - a <em>numeric-display</em> receiver, which is why the keyed move at {@code :214}
     * routes through {@link FixedWidthCodec#movePic9(String, int)} rather than being a plain assignment.
     */
    public static final int ACCTFILE_KEY_LENGTH = AccountRecord.ACCT_ID_LENGTH;

    /**
     * The {@value #ACCTFILE_DD} record's data span after its key: {@code 289} bytes.
     *
     * <p><strong>This is the second of the two {@code FD-ACCT-DATA} declarations</strong> -
     * {@code CBSTM03B.CBL:78}, {@code 05 FD-ACCT-DATA PIC X(289)}, inside the {@value #ACCTFILE_DD}
     * record. The first is {@link #TRNXFILE_ACCT_DATA_LENGTH}, which is 318 bytes wide. Both are kept,
     * both keep the source's name, and neither is merged into the other - see practice
     * <strong>B5</strong> on this class.
     */
    public static final int ACCTFILE_ACCT_DATA_LENGTH = ACCTFILE_RECORD_LENGTH - ACCTFILE_KEY_LENGTH;

    /**
     * The key length the caller supplies for a {@value #CUSTFILE_DD} read: {@code 9}.
     *
     * <p>{@code app/cbl/CBSTM03A.CBL:374} computes it as {@code LENGTH OF XREF-CUST-ID}, so the authority
     * is {@code app/cpy/CVACT03Y.cpy}'s {@code XREF-CUST-ID PIC 9(09)} and the constant is taken from
     * {@link CardXrefRecord} rather than written as a {@code 9} here. It equals
     * {@link #CUSTFILE_KEY_LENGTH}, and the constructor asserts that it does - if the cross reference and
     * the customer master ever disagreed about how wide a customer id is, a keyed read would be
     * comparing a short key as a prefix.
     */
    public static final int CUSTFILE_CALLER_KEY_LENGTH = CardXrefRecord.XREF_CUST_ID_LENGTH;

    /**
     * The key length the caller supplies for an {@value #ACCTFILE_DD} read: {@code 11}.
     *
     * <p>{@code app/cbl/CBSTM03A.CBL:398} computes it as {@code LENGTH OF XREF-ACCT-ID}, so the authority
     * is {@code app/cpy/CVACT03Y.cpy}'s {@code XREF-ACCT-ID PIC 9(11)}. It equals
     * {@link #ACCTFILE_KEY_LENGTH}, asserted for the same reason.
     */
    public static final int ACCTFILE_CALLER_KEY_LENGTH = CardXrefRecord.XREF_ACCT_ID_LENGTH;

    // =================================================================================================
    // FILE STATUS values this class can report. The four the module already agrees on come from
    // FileStatus; the four below are the ones only this class needs, and each names its authority.
    // =================================================================================================

    /**
     * The content of a {@code FILE STATUS} area that no operation has touched yet: two spaces.
     *
     * <p>{@code CBSTM03B.CBL:83-97} declares the four status areas in {@code WORKING-STORAGE}
     * <strong>without a {@code VALUE} clause</strong>, so COBOL leaves their initial content formally
     * undefined. Rather than assert a value the standard does not define, this class chooses one that
     * <em>cannot be mistaken for a real file status</em>, so a test that ever observes it knows exactly
     * which path it reached.
     *
     * <p>The state is unreachable from the only caller in the estate: {@code CBSTM03A} opens each DD
     * before it does anything else with it ({@code :731-739} for {@value #TRNXFILE_DD} and the
     * equivalent for the other three), so no {@code MOVE <dd>FILE-STATUS TO LK-M03B-RC} ever runs against
     * an untouched area. It is reachable from a direct caller that asks for an unsupported operation
     * before opening, and that caller gets the stale-status behaviour faithfully - the stale value simply
     * happens to be "nothing has happened yet".
     */
    public static final String UNTOUCHED_STATUS = "  ";

    /**
     * {@code '41'} - an {@code OPEN} was attempted for a file already in the open mode.
     *
     * <p>Standard COBOL file status, not an invention of this class: status key 4 covers a logic error,
     * and {@code '41'} is specifically the already-open condition. Reported rather than thrown, because a
     * status is the only outcome {@code CBSTM03B}'s interface can express and because the caller's guard
     * chain - which accepts {@code '00' OR '04'} on an open and abends otherwise
     * ({@code app/cbl/CBSTM03A.CBL:736}) - is where the escalation belongs.
     */
    public static final String ALREADY_OPEN_STATUS = "41";

    /**
     * {@code '42'} - a {@code CLOSE} was attempted for a file not in the open mode.
     *
     * <p>Standard COBOL file status. Same reasoning as {@link #ALREADY_OPEN_STATUS}: reported, never
     * thrown.
     */
    public static final String NOT_OPEN_STATUS = "42";

    /**
     * {@code '47'} - a {@code READ} was attempted for a file not open in the input or I-O mode.
     *
     * <p>Standard COBOL file status, and the correct one here because every {@code OPEN} in this
     * subroutine is {@code OPEN INPUT}: a read before the open, or after the close, is exactly the
     * condition {@code '47'} names.
     */
    public static final String NOT_OPEN_FOR_READ_STATUS = "47";

    /**
     * The status reported when the backend refuses an operation: {@code '9'} followed by an
     * implementor-defined byte of binary zero.
     *
     * <p>Class {@code 9x} is the COBOL status class reserved for implementor-defined conditions, and this
     * is the spelling the sibling repositories of this module already use, so a caller matching on it
     * matches the same value everywhere. It lands on every caller's {@code WHEN OTHER} arm, which is
     * precisely where an unreachable dataset belongs.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + (char) 0;

    /**
     * An empty {@code LK-M03B-FLDT}: {@value #FLDT_LENGTH} spaces.
     *
     * <p>The value all thirteen call sites establish with {@code MOVE SPACES TO WS-M03B-FLDT} before
     * calling, and therefore the value a read that returns no record leaves in place. {@code String} is
     * immutable, so this constant is not shared mutable state.
     */
    public static final String SPACES_FLDT = " ".repeat(FLDT_LENGTH);

    /**
     * The {@code LK-M03B-OPER} byte rendered for an operation matching none of the six
     * {@code 88}-levels: a single space.
     *
     * <p>Not an error code. A space matches no {@code VALUE} clause, so every {@code IF M03B-...} test is
     * false and the paragraph falls through to its status move - which is precisely the behaviour a
     * {@code null} {@link Operation} stands for.
     */
    public static final String UNRECOGNISED_OPER_IMAGE = " ";

    /**
     * Rows transferred by one sequential read: {@code 1}.
     *
     * <p>A COBOL sequential {@code READ} returns one record, so the statement is capped at one row. The
     * browse therefore never materialises a dataset, and a defect in the four thousandth record is
     * reported by the read that reaches it rather than by the open.
     */
    private static final int BROWSE_ROW_LIMIT = 1;

    /**
     * Rows transferred by one keyed read: {@code 2}.
     *
     * <p>One more than a unique primary key can produce, so a violation of that uniqueness in the backing
     * relation is <em>detected</em> rather than passing unnoticed, and is then reported as
     * {@link #PERMANENT_ERROR_STATUS} rather than resolved by returning whichever row the backend ordered
     * first. Two is the cap because knowing "more than one" is all the decision needs; counting them all
     * would transfer the duplicates to no purpose.
     */
    private static final int KEYED_READ_ROW_LIMIT = 2;

    /**
     * One row: all the unreadable-row probe needs, because it asks a yes-or-no question. Whether a dataset
     * holds one unreadable row or fifty does not change what a keyed read reports.
     */
    private static final int UNREADABLE_ROW_PROBE_LIMIT = 1;

    /**
     * Compile-time-adjacent assertion that the declared field geometry actually sums to
     * {@link #AREA_LENGTH}.
     *
     * <p>{@link #AREA_LENGTH} is written as {@code 1040} because that is the number a reviewer checks
     * against {@code CBSTM03B.CBL:100-112}; this block proves the individual field constants agree with
     * it, so the two statements of the contract cannot drift apart. It runs once, at class
     * initialisation, and failing here fails loudly at startup instead of misaligning
     * {@code LK-M03B-FLDT} at run time.
     */
    static {
        int declared = DD_LENGTH + OPER_LENGTH + RC_LENGTH + KEY_LENGTH + KEY_LN_LENGTH + FLDT_LENGTH;
        if (declared != AREA_LENGTH) {
            throw new IllegalStateException("The LK-M03B-AREA field widths sum to " + declared
                    + " but AREA_LENGTH is " + AREA_LENGTH + "; app/cbl/CBSTM03B.CBL:100-112 declares "
                    + "X(08) + X(01) + X(02) + X(25) + S9(4) DISPLAY + X(1000) = 1040 bytes, and the "
                    + "caller's own 01 WS-M03B-AREA (app/cbl/CBSTM03A.CBL:71-84) is the identical "
                    + "group. Correct the field constants rather than AREA_LENGTH.");
        }
        if (FLDT_OFFSET + FLDT_LENGTH != AREA_LENGTH) {
            throw new IllegalStateException("LK-M03B-FLDT at offset " + FLDT_OFFSET + " for "
                    + FLDT_LENGTH + " bytes does not end at " + AREA_LENGTH + "; the field offsets are "
                    + "cumulative and must close the area exactly.");
        }
    }

    // =================================================================================================
    // The operation code. All six 88-levels of app/cbl/CBSTM03B.CBL:103-108 are declared; two of them
    // are dead in the source and stay dead here (practice B5).
    // =================================================================================================

    /**
     * The six operation codes {@code LK-M03B-OPER} can carry - the {@code 88}-level condition names of
     * {@code app/cbl/CBSTM03B.CBL:103-108}.
     *
     * <p>All six are declared because the source declares all six. Only four are ever tested by the
     * source, and no DD tests more than three of them:
     * <ul>
     *   <li>{@link #OPEN}, {@link #CLOSE} - tested by all four {@code PROC} paragraphs.</li>
     *   <li>{@link #READ} - tested by {@value StatementGenerationJobB#TRNXFILE_DD} ({@code :140}) and
     *       {@value StatementGenerationJobB#XREFFILE_DD} ({@code :164}) only.</li>
     *   <li>{@link #READ_K} - tested by {@value StatementGenerationJobB#CUSTFILE_DD} ({@code :188}) and
     *       {@value StatementGenerationJobB#ACCTFILE_DD} ({@code :213}) only.</li>
     *   <li>{@link #WRITE}, {@link #REWRITE} - <strong>declared and never tested anywhere in the
     *       program.</strong> A search of all 230 lines finds no {@code IF M03B-WRITE} and no
     *       {@code IF M03B-REWRITE}. They are dead condition names, and practice <strong>B5</strong>
     *       requires them to be preserved rather than deleted and <em>not</em> to be given behaviour
     *       they never had. Requesting either is a supported call that performs no I/O; see the
     *       fall-through documented on this class.</li>
     * </ul>
     *
     * <p>Every one of the four files is opened {@code OPEN INPUT}, so {@link #WRITE} and
     * {@link #REWRITE} could not have worked even if they had been tested - a further reason not to
     * implement them.
     */
    public enum Operation {

        /** {@code 88 M03B-OPEN VALUE 'O'} - {@code OPEN INPUT}, the only open mode this subroutine uses. */
        OPEN('O'),

        /** {@code 88 M03B-CLOSE VALUE 'C'} - {@code CLOSE}. */
        CLOSE('C'),

        /** {@code 88 M03B-READ VALUE 'R'} - a sequential {@code READ ... INTO}, honoured by the two SEQUENTIAL files. */
        READ('R'),

        /** {@code 88 M03B-READ-K VALUE 'K'} - a keyed {@code READ}, honoured by the two RANDOM files. */
        READ_K('K'),

        /**
         * {@code 88 M03B-WRITE VALUE 'W'} - declared at {@code CBSTM03B.CBL:107} and tested nowhere.
         * Preserved unimplemented (practice B5).
         */
        WRITE('W'),

        /**
         * {@code 88 M03B-REWRITE VALUE 'Z'} - declared at {@code CBSTM03B.CBL:108} and tested nowhere.
         * Preserved unimplemented (practice B5).
         */
        REWRITE('Z');

        /** The single character the {@code 88}-level compares {@code LK-M03B-OPER} against. */
        private final char code;

        /**
         * @param code the literal from the {@code VALUE} clause
         */
        Operation(char code) {
            this.code = code;
        }

        /**
         * The one-character code this operation is spelled with in the linkage area.
         *
         * @return the {@code VALUE} literal of the corresponding {@code 88}-level
         */
        public char code() {
            return code;
        }

        /**
         * The code as a one-character {@code LK-M03B-OPER} image.
         *
         * @return a string of exactly {@value StatementGenerationJobB#OPER_LENGTH} character
         */
        public String image() {
            return String.valueOf(code);
        }

        /**
         * Whether this operation is one of the two the source declares but never tests.
         *
         * <p>Exposed so the fact is checkable rather than only documented: a test asserts that exactly
         * {@link #WRITE} and {@link #REWRITE} answer {@code true}, which is what stops a later
         * contributor from quietly implementing one.
         *
         * @return {@code true} for {@link #WRITE} and {@link #REWRITE}, {@code false} otherwise
         */
        public boolean declaredButUnusedInSource() {
            return this == WRITE || this == REWRITE;
        }

        /**
         * Resolves an operation from its one-character code.
         *
         * <p>Empty rather than throwing for an unrecognised character, because {@code LK-M03B-OPER} is one
         * byte of a shared area and a caller may legitimately hand over a byte that matches none of the
         * six {@code 88}-levels. In COBOL that is not an error: every {@code IF M03B-...} test is simply
         * false and the paragraph falls through to its status move. An {@link Optional} lets the caller
         * express that outcome instead of being forced to invent one.
         *
         * @param code the character to resolve
         * @return the matching operation, or empty when no {@code 88}-level names {@code code}
         */
        public static Optional<Operation> ofCode(char code) {
            for (Operation candidate : values()) {
                if (candidate.code == code) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }
    }

    // =================================================================================================
    // The typed projection of LK-M03B-AREA. Two immutable records: what the caller supplies and what the
    // subroutine leaves behind. Both are Java records, so neither can be mutated after construction and
    // neither can be a channel for shared state between two sessions.
    // =================================================================================================

    /**
     * The inbound projection of {@code LK-M03B-AREA} - one {@code CALL 'CBSTM03B' USING WS-M03B-AREA}
     * worth of input.
     *
     * <p><strong>It carries {@code rc} and {@code fldt} as well as the DD name, operation and key,</strong>
     * and that is deliberate. {@code LK-M03B-AREA} is a single shared area, so those two fields are
     * genuinely inout: every call site sets them before calling ({@code MOVE ZERO TO WS-M03B-RC} and
     * usually {@code MOVE SPACES TO WS-M03B-FLDT}), and two paths through the subroutine leave one or both
     * exactly as they arrived - the {@code WHEN OTHER} DD arm, which assigns nothing at all, and any read
     * that returns no record, because COBOL's {@code AT END} does not disturb the {@code INTO} receiver.
     * Dropping them from the request would make both behaviours inexpressible.
     *
     * <p>The four factory methods spell the four calls the source actually makes, with the caller's own
     * pre-call initialisation already applied, so a call site reads like its COBOL original.
     *
     * @param dd        {@code LK-M03B-DD PIC X(08)} - the DD name to dispatch on. Compared exactly, as
     *                  {@code EVALUATE LK-M03B-DD} compares it; an unrecognised value takes the
     *                  {@code WHEN OTHER} arm
     * @param oper      {@code LK-M03B-OPER PIC X(01)} - the operation, or {@code null} to mean a byte
     *                  matching none of the six {@code 88}-levels, which is a fall-through and not an
     *                  error
     * @param rc        {@code LK-M03B-RC PIC X(02)} on the way in - the status the caller left in the
     *                  area, which is what the {@code WHEN OTHER} arm returns untouched. Exactly
     *                  {@value StatementGenerationJobB#RC_LENGTH} characters
     * @param key       {@code LK-M03B-KEY PIC X(25)} - the key, left-justified and space-padded exactly as
     *                  {@code MOVE XREF-CUST-ID TO WS-M03B-KEY} leaves it. Exactly
     *                  {@value StatementGenerationJobB#KEY_LENGTH} characters
     * @param keyLength {@code LK-M03B-KEY-LN PIC S9(4)} - how many of those 25 bytes the key occupies.
     *                  Only a keyed read reads it
     * @param fldt      {@code LK-M03B-FLDT PIC X(1000)} on the way in - the record area as the caller left
     *                  it, which a read that finds nothing returns unchanged. Exactly
     *                  {@value StatementGenerationJobB#FLDT_LENGTH} characters
     */
    public record Request(String dd,
                          Operation oper,
                          String rc,
                          String key,
                          int keyLength,
                          String fldt) {

        /**
         * Normalises and validates the fixed-width fields.
         *
         * <p>The three character fields are held at their declared widths - {@code dd} is padded or
         * truncated to {@value StatementGenerationJobB#DD_LENGTH} and {@code key} to
         * {@value StatementGenerationJobB#KEY_LENGTH} under the {@code PIC X} rule, and {@code fldt} and
         * {@code rc} are required to be exactly their declared widths rather than adjusted, because
         * silently padding a status or a record area would hide a caller defect that matters.
         *
         * @throws NullPointerException     if {@code dd}, {@code rc}, {@code key} or {@code fldt} is
         *                                  {@code null}
         * @throws IllegalArgumentException if {@code rc} is not exactly
         *                                  {@value StatementGenerationJobB#RC_LENGTH} characters, or
         *                                  {@code fldt} is not exactly
         *                                  {@value StatementGenerationJobB#FLDT_LENGTH} characters
         */
        public Request {
            Objects.requireNonNull(dd, "A DD name is required: LK-M03B-DD is the field "
                    + "EVALUATE LK-M03B-DD dispatches on (app/cbl/CBSTM03B.CBL:118)");
            Objects.requireNonNull(rc, "An inbound status is required: LK-M03B-RC arrives carrying "
                    + "what the caller left there, and the WHEN OTHER arm returns exactly that");
            Objects.requireNonNull(key, "A key image is required: LK-M03B-KEY is a fixed X(25) span, "
                    + "so an absent key is " + KEY_LENGTH + " spaces rather than null");
            Objects.requireNonNull(fldt, "A record area is required: LK-M03B-FLDT is a fixed X("
                    + FLDT_LENGTH + ") span, and a read that finds nothing returns it unchanged");
            if (rc.length() != RC_LENGTH) {
                throw new IllegalArgumentException("An inbound status of '" + rc.length()
                        + "' character(s) cannot occupy LK-M03B-RC PIC X(" + RC_LENGTH + "); a COBOL "
                        + "FILE STATUS is exactly two characters and is never padded into place.");
            }
            if (fldt.length() != FLDT_LENGTH) {
                throw new IllegalArgumentException("A record area of " + fldt.length()
                        + " character(s) cannot occupy LK-M03B-FLDT PIC X(" + FLDT_LENGTH + "). Use "
                        + "StatementGenerationJobB.SPACES_FLDT for the empty area every call site "
                        + "establishes with MOVE SPACES TO WS-M03B-FLDT.");
            }
            // PIC X receivers: right-padded when short, right-truncated when long. Applied through the
            // same rule the codec states for the whole module, spelled out here because Request is
            // constructible without a codec.
            dd = fitPicX(dd, DD_LENGTH);
            key = fitPicX(key, KEY_LENGTH);
        }

        /**
         * Applies the COBOL {@code PIC X} move rule: pad right with spaces, truncate on the right.
         *
         * <p>Duplicated from {@link FixedWidthCodec#movePicX(String, int)} rather than delegated, because
         * a {@code record}'s compact constructor runs before any collaborator is available and a request
         * has to be constructible in a plain unit test with no codec, no charset and no Spring context
         * (practice B10, gate G51). The rule is two lines and is stated identically in both places.
         *
         * @param value        the sending value
         * @param declaredWidth the receiver's declared width
         * @return an image of exactly {@code declaredWidth} characters
         */
        private static String fitPicX(String value, int declaredWidth) {
            if (value.length() == declaredWidth) {
                return value;
            }
            return value.length() > declaredWidth
                    ? value.substring(0, declaredWidth)
                    : value + " ".repeat(declaredWidth - value.length());
        }

        /**
         * An {@code OPEN INPUT} request, as {@code app/cbl/CBSTM03A.CBL:731-734} composes it:
         * {@code MOVE '<dd>' TO WS-M03B-DD}, {@code SET M03B-OPEN TO TRUE},
         * {@code MOVE ZERO TO WS-M03B-RC}.
         *
         * @param dd the DD name to open
         * @return the request
         * @throws NullPointerException if {@code dd} is {@code null}
         */
        public static Request open(String dd) {
            return new Request(dd, Operation.OPEN, FileStatus.OK, blankKey(), 0, SPACES_FLDT);
        }

        /**
         * A sequential {@code READ} request, as {@code app/cbl/CBSTM03A.CBL:346-350} composes it - with
         * the {@code MOVE ZERO TO WS-M03B-RC} and {@code MOVE SPACES TO WS-M03B-FLDT} the caller performs
         * first already applied.
         *
         * @param dd the DD name to read
         * @return the request
         * @throws NullPointerException if {@code dd} is {@code null}
         */
        public static Request read(String dd) {
            return new Request(dd, Operation.READ, FileStatus.OK, blankKey(), 0, SPACES_FLDT);
        }

        /**
         * A keyed {@code READ} request, as {@code app/cbl/CBSTM03A.CBL:367-376} composes it:
         * {@code MOVE XREF-CUST-ID TO WS-M03B-KEY} then
         * {@code COMPUTE WS-M03B-KEY-LN = LENGTH OF XREF-CUST-ID}.
         *
         * <p>{@code key} is fitted to the declared {@value StatementGenerationJobB#KEY_LENGTH}-character
         * span by the canonical constructor, which is exactly what the caller's
         * {@code MOVE <9- or 11-digit field> TO WS-M03B-KEY} does: left-justify and pad with spaces. The
         * padding is then ignored, because the read uses only the first {@code keyLength} bytes.
         *
         * @param dd        the DD name to read
         * @param key       the key value; shorter than the span is normal and is padded
         * @param keyLength how many bytes of the span the key occupies
         * @return the request
         * @throws NullPointerException if {@code dd} or {@code key} is {@code null}
         */
        public static Request readKeyed(String dd, String key, int keyLength) {
            return new Request(dd, Operation.READ_K, FileStatus.OK, key, keyLength, SPACES_FLDT);
        }

        /**
         * A {@code CLOSE} request, as {@code app/cbl/CBSTM03A.CBL:857-860} composes it.
         *
         * @param dd the DD name to close
         * @return the request
         * @throws NullPointerException if {@code dd} is {@code null}
         */
        public static Request close(String dd) {
            return new Request(dd, Operation.CLOSE, FileStatus.OK, blankKey(), 0, SPACES_FLDT);
        }

        /**
         * A request carrying an operation code that matches none of the six {@code 88}-levels.
         *
         * <p>Not a defect and not an error: in COBOL every {@code IF M03B-...} test is false and the
         * paragraph falls through to its status move. Provided as a factory so that path is as easy to
         * drive from a test as any other (gate G49's fall-through branches).
         *
         * @param dd the DD name to dispatch on
         * @return the request, with a {@code null} operation
         * @throws NullPointerException if {@code dd} is {@code null}
         */
        public static Request unrecognisedOperation(String dd) {
            return new Request(dd, null, FileStatus.OK, blankKey(), 0, SPACES_FLDT);
        }

        /**
         * An empty {@code LK-M03B-KEY}: {@value StatementGenerationJobB#KEY_LENGTH} spaces.
         *
         * @return the blank key image
         */
        public static String blankKey() {
            return " ".repeat(KEY_LENGTH);
        }

        /**
         * The {@code keyLength}-byte prefix of the key - COBOL's
         * {@code LK-M03B-KEY (1:LK-M03B-KEY-LN)} reference modification
         * ({@code app/cbl/CBSTM03B.CBL:189} and {@code :214}).
         *
         * <p><strong>The 1-based-to-0-based conversion lives here and nowhere else.</strong> COBOL counts
         * character positions from 1, so {@code (1:n)} is the first {@code n} bytes, which in Java is
         * {@code substring(0, n)} - not {@code substring(1, n)}, the off-book error this method exists to
         * make impossible to repeat at two call sites.
         *
         * <p>{@code keyLength} is not defended against zero or a negative value. The caller always sets
         * it - {@code app/cbl/CBSTM03A.CBL:374} and {@code :398} compute it from
         * {@code LENGTH OF XREF-CUST-ID} and {@code LENGTH OF XREF-ACCT-ID} - and COBOL's behaviour for a
         * reference modification with a non-positive length is undefined, so there is no legacy behaviour
         * to reproduce and inventing one would be worse than refusing.
         *
         * @return the used prefix of the key, exactly {@link #keyLength()} characters
         * @throws IllegalArgumentException if {@code keyLength} is not between 1 and
         *                                  {@value StatementGenerationJobB#KEY_LENGTH} inclusive
         */
        public String usedKey() {
            if (keyLength < 1 || keyLength > KEY_LENGTH) {
                throw new IllegalArgumentException("A key length of " + keyLength + " cannot address "
                        + "LK-M03B-KEY (1:" + keyLength + "): the span is " + KEY_LENGTH + " bytes and "
                        + "COBOL reference modification is 1-based, so the length must be between 1 and "
                        + KEY_LENGTH + ". app/cbl/CBSTM03A.CBL:374 and :398 always set it from "
                        + "LENGTH OF XREF-CUST-ID (" + CUSTFILE_CALLER_KEY_LENGTH + ") or "
                        + "LENGTH OF XREF-ACCT-ID (" + ACCTFILE_CALLER_KEY_LENGTH + ").");
            }
            // COBOL (1:n) is 1-based; Java substring is 0-based. This single conversion is the whole of it.
            return key.substring(0, keyLength);
        }
    }

    /**
     * The outbound projection of {@code LK-M03B-AREA} - the two fields one call can leave behind.
     *
     * <p>{@code rc} is what {@code MOVE <dd>FILE-STATUS TO LK-M03B-RC} put there at {@code nnn900-EXIT},
     * or, on the {@code WHEN OTHER} DD arm, what the request already carried. {@code fldt} is what
     * {@code READ ... INTO LK-M03B-FLDT} put there, or, when no record was read, what the request already
     * carried.
     *
     * @param rc   {@code LK-M03B-RC PIC X(02)} - exactly {@value StatementGenerationJobB#RC_LENGTH}
     *             characters
     * @param fldt {@code LK-M03B-FLDT PIC X(1000)} - always exactly
     *             {@value StatementGenerationJobB#FLDT_LENGTH} characters, with any record left-justified
     *             and the remainder spaces
     */
    public record Response(String rc, String fldt) {

        /**
         * Validates both fixed-width fields at their declared widths.
         *
         * @throws NullPointerException     if {@code rc} or {@code fldt} is {@code null}
         * @throws IllegalArgumentException if either is not exactly its declared width
         */
        public Response {
            Objects.requireNonNull(rc, "A status is required: every return from a matched DD runs "
                    + "MOVE <dd>FILE-STATUS TO LK-M03B-RC (app/cbl/CBSTM03B.CBL:152, :176, :201, :226)");
            Objects.requireNonNull(fldt, "A record area is required: LK-M03B-FLDT is a fixed X("
                    + FLDT_LENGTH + ") span and is returned even when no record was read");
            if (rc.length() != RC_LENGTH) {
                throw new IllegalArgumentException("A status of " + rc.length() + " character(s) "
                        + "cannot occupy LK-M03B-RC PIC X(" + RC_LENGTH + ").");
            }
            if (fldt.length() != FLDT_LENGTH) {
                throw new IllegalArgumentException("A record area of " + fldt.length()
                        + " character(s) cannot occupy LK-M03B-FLDT PIC X(" + FLDT_LENGTH + ").");
            }
        }

        /**
         * The status classified into the outcomes the callers' {@code EVALUATE WS-M03B-RC} arms
         * enumerate.
         *
         * @return {@link Outcome#OK} for {@code '00'}, {@link Outcome#END_OF_FILE} for {@code '10'},
         *         {@link Outcome#NOT_FOUND} for {@code '23'}, {@link Outcome#DUPLICATE} for {@code '22'},
         *         and {@link Outcome#OTHER} for everything else - which is exactly the set
         *         {@code app/cbl/CBSTM03A.CBL:353-359} distinguishes
         */
        public Outcome outcome() {
            return FileStatus.outcomeOfStatus(rc);
        }

        /**
         * Whether the operation reported {@code '00'}.
         *
         * @return {@code true} for {@link FileStatus#OK}
         */
        public boolean ok() {
            return FileStatus.isOk(rc);
        }

        /**
         * Whether the operation reported {@code '10'} - the {@code AT END} the two sequential browses
         * reach and the arm {@code app/cbl/CBSTM03A.CBL:356} turns into {@code MOVE 'Y' TO END-OF-FILE}.
         *
         * @return {@code true} for {@link FileStatus#END_OF_FILE}
         */
        public boolean endOfFile() {
            return FileStatus.isEndOfFile(rc);
        }

        /**
         * The record the caller would move out of {@code LK-M03B-FLDT}: its leading
         * {@code recordLength} characters.
         *
         * <p>{@code MOVE WS-M03B-FLDT TO TRNX-RECORD} ({@code app/cbl/CBSTM03A.CBL:756}) is a group move
         * into a 350-byte receiver, so it takes the leading 350 characters of the 1000 and discards the
         * padding. This method is that move, made explicit rather than left to a substring at each call
         * site.
         *
         * @param recordLength the receiving record's declared width
         * @return the leading {@code recordLength} characters of the record area
         * @throws IllegalArgumentException if {@code recordLength} is not between 1 and
         *                                  {@value StatementGenerationJobB#FLDT_LENGTH} inclusive
         */
        public String recordImage(int recordLength) {
            if (recordLength < 1 || recordLength > FLDT_LENGTH) {
                throw new IllegalArgumentException("A record length of " + recordLength + " cannot be "
                        + "taken from LK-M03B-FLDT PIC X(" + FLDT_LENGTH + "); a receiving record is at "
                        + "least 1 byte and no wider than the area that carries it.");
            }
            return fldt.substring(0, recordLength);
        }
    }

    // =================================================================================================
    // Injected collaborators. Every one is final and arrives through the constructor; there is no field
    // injection, no setter and no static mutable field (practice B9, gate G53).
    // =================================================================================================

    /** The module-wide template over the configuration-bound {@code DataSource}. */
    private final JdbcTemplate jdbcTemplate;

    /**
     * The hand-written fixed-width codec, built once over the explicitly injected dataset code page
     * (practices B8 and B11).
     */
    private final FixedWidthCodec codec;

    /**
     * How this deployment's driver presents a record image over JDBC - as characters or as bytes.
     *
     * <p>Injected rather than chosen here, so the four datasets this class reads are addressed the same
     * way the sibling repositories address the same bytes. A driver asked for the type a column does not
     * have converts rather than refuses, so getting this wrong is wrong silently.
     */
    private final RecordImageForm recordImageForm;

    /**
     * The four datasets, keyed by DD name and held in {@link #DD_NAMES} order.
     *
     * <p>Unmodifiable, and its values are effectively immutable apart from each one's memoised statement
     * text - a derived value that is computed idempotently and never varies, which is why it is not
     * conversation state. No cursor and no {@code FILE STATUS} is held here; both live on the
     * {@link Session}.
     */
    private final Map<String, DatasetAccess> datasets;

    /**
     * Assembles the subroutine from the module's shared {@link JdbcTemplate}, the DD-name-keyed dataset
     * catalogue and the explicitly named dataset code page.
     *
     * <p><strong>Everything checkable is checked here, at startup, not at the first read.</strong> Four
     * families of invariant are enforced, and each one guards a defect that would otherwise surface as
     * plausible-looking wrong data rather than as a failure:
     * <ol>
     *   <li><strong>Every binding must agree with its copybook about the record width</strong> (gate
     *       G19). The four widths are taken from {@link TrnxRecord#RECORD_LENGTH},
     *       {@link CardXrefRecord#RECORD_LENGTH}, {@link Stm03CustomerRecord#RECORD_LENGTH} and
     *       {@link AccountRecord#RECORD_LENGTH}, so there is one number per dataset rather than two that
     *       can disagree. A read against a differently-sized record would hand the caller a record area
     *       whose fields are all displaced.</li>
     *   <li><strong>Every binding must declare a key length, and it must match the {@code RECORD KEY}
     *       {@code CBSTM03B} declares.</strong> All four {@code SELECT} statements are
     *       {@code ORGANIZATION IS INDEXED} with a {@code RECORD KEY}, so a binding that declared none -
     *       or declared a different width - would leave a keyed read with no authority for where its key
     *       ends.</li>
     *   <li><strong>The two key widths the caller computes must equal the two the files declare.</strong>
     *       {@code app/cbl/CBSTM03A.CBL:374} and {@code :398} derive them from {@code CVACT03Y}'s
     *       {@code XREF-CUST-ID PIC 9(09)} and {@code XREF-ACCT-ID PIC 9(11)}, while {@code CBSTM03B}
     *       declares {@code FD-CUST-ID PIC X(09)} and {@code FD-ACCT-ID PIC 9(11)}. If those ever
     *       diverged, a keyed read would compare a short key as a prefix and match records the key does
     *       not name.</li>
     *   <li><strong>Every binding must declare a dataset name that can be composed into a
     *       statement.</strong> {@code application.yml} spells each one as an environment reference, so
     *       an unconfigured deployment yields blank rather than {@code null}.</li>
     * </ol>
     *
     * <p>No check is made of {@code record-format}. The CSD says {@code RECORDFORMAT(V)} and the JCL says
     * {@code RECFM=FB} for the same datasets, and practice <strong>B4</strong> forbids reconciling a
     * conflict in the source: this class treats length as copybook-fixed and records the disagreement in
     * its class documentation instead of asserting one side of it.
     *
     * @param jdbcTemplate    the module-wide template; never {@code null}
     * @param datasetBindings the {@code carddemo.datasets} catalogue; never {@code null}. The four
     *                        entries are looked up by the keys {@value #TRNXFILE_DD},
     *                        {@value #XREFFILE_DD}, {@value #CUSTFILE_DD} and {@value #ACCTFILE_DD}, so
     *                        no dataset name is written in Java (gate G46)
     * @param datasetCharset  the dataset code page, injected by bean name so it is stated explicitly
     *                        rather than taken from the platform (practice B8)
     * @param recordImageForm how the deployment's driver presents a record image over JDBC
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if any of the four bindings is absent, disagrees with its copybook
     *                               about the record width or the key width, or declares no usable
     *                               dataset name
     */
    public StatementGenerationJobB(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm) {

        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + "four statement inputs are reached through the module's shared template over the "
                + "configuration-bound DataSource");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "dataset names live in configuration and are never written in Java (gate G46)");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset is "
                + "required: a fixed-width mainframe record is bytes in a specific code page, so the "
                + "code page is stated explicitly and never taken from the platform (practice B8)"));
        this.recordImageForm = Objects.requireNonNull(recordImageForm, "A record-image representation "
                + "is required: whether this deployment's driver presents a record image as characters "
                + "or as bytes is stated once, by " + RecordImageForm.FORM_PROPERTY + ", and never "
                + "decided per collaborator");
        RecordImageForm.requireSingleByteCodePage(datasetCharset);

        // The caller's two key widths must be the two the files declare. Asserted before any binding is
        // read, because a divergence here is a defect in the copybooks' relationship to each other and
        // has nothing to do with how a deployment is configured.
        requireCallerKeyWidth(CUSTFILE_DD, CardXrefRecord.XREF_CUST_ID_NAME,
                CUSTFILE_CALLER_KEY_LENGTH, CUSTFILE_KEY_LENGTH);
        requireCallerKeyWidth(ACCTFILE_DD, CardXrefRecord.XREF_ACCT_ID_NAME,
                ACCTFILE_CALLER_KEY_LENGTH, ACCTFILE_KEY_LENGTH);

        Map<String, DatasetAccess> resolved = new LinkedHashMap<>();
        resolved.put(TRNXFILE_DD, access(datasetBindings, TRNXFILE_DD, TRNXFILE_RECORD_LENGTH,
                TRNXFILE_KEY_LENGTH, TrnxRecord.TRNX_KEY_OFFSET, KeyPicture.ALPHANUMERIC,
                SEQUENTIAL_OPERATIONS, "app/cpy/COSTM01.CPY"));
        resolved.put(XREFFILE_DD, access(datasetBindings, XREFFILE_DD, XREFFILE_RECORD_LENGTH,
                XREFFILE_KEY_LENGTH, CardXrefRecord.XREF_CARD_NUM_OFFSET, KeyPicture.ALPHANUMERIC,
                SEQUENTIAL_OPERATIONS, "app/cpy/CVACT03Y.cpy"));
        resolved.put(CUSTFILE_DD, access(datasetBindings, CUSTFILE_DD, CUSTFILE_RECORD_LENGTH,
                CUSTFILE_KEY_LENGTH, Stm03CustomerRecord.KEY_OFFSET, KeyPicture.ALPHANUMERIC,
                RANDOM_OPERATIONS, "app/cpy/CUSTREC.cpy"));
        resolved.put(ACCTFILE_DD, access(datasetBindings, ACCTFILE_DD, ACCTFILE_RECORD_LENGTH,
                ACCTFILE_KEY_LENGTH, AccountRecord.ACCT_ID_OFFSET, KeyPicture.NUMERIC_DISPLAY,
                RANDOM_OPERATIONS, "app/cpy/CVACT01Y.cpy"));
        this.datasets = Collections.unmodifiableMap(resolved);
    }

    // =================================================================================================
    // The per-DD capability sets. app/cbl/CBSTM03B.CBL:133-229: three IF <op> tests per paragraph, and
    // which three depends on the SELECT's ACCESS MODE.
    // =================================================================================================

    /**
     * What an {@code ACCESS MODE IS SEQUENTIAL} file honours: {@code OPEN}, sequential {@code READ},
     * {@code CLOSE}.
     *
     * <p>{@value #TRNXFILE_DD} ({@code :135}, {@code :140}, {@code :146}) and {@value #XREFFILE_DD}
     * ({@code :159}, {@code :164}, {@code :170}). Neither tests {@code M03B-READ-K}, so neither has a
     * keyed read at all. Unmodifiable.
     */
    private static final Set<Operation> SEQUENTIAL_OPERATIONS =
            Collections.unmodifiableSet(EnumSet.of(Operation.OPEN, Operation.READ, Operation.CLOSE));

    /**
     * What an {@code ACCESS MODE IS RANDOM} file honours: {@code OPEN}, keyed {@code READ},
     * {@code CLOSE}.
     *
     * <p>{@value #CUSTFILE_DD} ({@code :183}, {@code :188}, {@code :195}) and {@value #ACCTFILE_DD}
     * ({@code :208}, {@code :213}, {@code :220}). Neither tests {@code M03B-READ}, so neither has a plain
     * sequential read. Unmodifiable.
     */
    private static final Set<Operation> RANDOM_OPERATIONS =
            Collections.unmodifiableSet(EnumSet.of(Operation.OPEN, Operation.READ_K, Operation.CLOSE));

    /**
     * Which of the two {@code RECORD KEY} pictures a file declares, because the two take different
     * {@code MOVE} rules.
     *
     * <p>{@code CBSTM03B.CBL:189} moves into {@code FD-CUST-ID PIC X(09)} and {@code :214} into
     * {@code FD-ACCT-ID PIC 9(11)}. Those are not the same operation: a {@code PIC X} receiver is filled
     * from the left and truncated on the right, while a {@code PIC 9} receiver is aligned on its implied
     * decimal point and truncated on the <em>left</em>. Naming the distinction here is what stops the
     * two call sites from collapsing into one plain Java assignment, which would be neither.
     */
    private enum KeyPicture {

        /** {@code PIC X(n)} - {@link FixedWidthCodec#movePicX(String, int)}. */
        ALPHANUMERIC,

        /** {@code PIC 9(n)} - {@link FixedWidthCodec#movePic9(String, int)}. */
        NUMERIC_DISPLAY
    }

    // =================================================================================================
    // Constructor guards. Each rejects a configuration that would read or compare the wrong bytes, and
    // each is reachable from a plain unit test with a hand-built binding (practice B10, gate G51).
    // =================================================================================================

    /**
     * Resolves one binding into an access descriptor, checking everything checkable about it.
     *
     * @param bindings       the configured catalogue
     * @param ddName         the DD name to look up
     * @param recordLength   the width the copybook declares
     * @param keyLength      the width the {@code RECORD KEY} declares
     * @param keyOffset      the key's zero-based offset within the record
     * @param keyPicture     which {@code MOVE} rule the {@code RECORD KEY} receiver takes
     * @param capabilities   the three operations this DD's {@code PROC} paragraph implements
     * @param copybook       the copybook path, named in any diagnostic
     * @return the access descriptor
     * @throws IllegalStateException if the binding is absent or disagrees about a width or a name
     */
    private DatasetAccess access(DatasetBindings bindings,
                                 String ddName,
                                 int recordLength,
                                 int keyLength,
                                 int keyOffset,
                                 KeyPicture keyPicture,
                                 Set<Operation> capabilities,
                                 String copybook) {
        DatasetBinding binding = bindings.binding(ddName);
        requireCopybookRecordLength(ddName, binding, recordLength, copybook);
        requireDeclaredKeyLength(ddName, binding, keyLength, copybook);
        return new DatasetAccess(
                ddName,
                DatasetRelation.of(requireUsableDatasetName(ddName, binding.dsname()), recordLength),
                recordLength,
                new KeySpan(keyOffset, keyLength),
                keyPicture,
                capabilities);
    }

    /**
     * Requires a binding to agree with its copybook about the record width. Gate G19 at startup.
     *
     * @param ddName   the configuration key, for the diagnostic
     * @param binding  the configured binding
     * @param declared the width the copybook declares
     * @param copybook the copybook path
     * @throws IllegalStateException if the declared record length differs
     */
    private static void requireCopybookRecordLength(String ddName, DatasetBinding binding,
                                                    int declared, String copybook) {
        if (binding.recordLength() != declared) {
            throw new IllegalStateException("Dataset binding for '" + ddName + "' declares a record "
                    + "length of " + binding.recordLength() + ", but " + copybook + " declares "
                    + declared + " and app/cbl/CBSTM03B.CBL:58-78 splits its FD record to exactly that "
                    + "width. This subroutine returns raw record bytes in an X(" + FLDT_LENGTH + ") "
                    + "area, so a wrong width does not fail here - it displaces every field the caller "
                    + "then decodes. Correct carddemo.datasets." + ddName + ".record-length to "
                    + declared + ".");
        }
    }

    /**
     * Requires a binding to declare the key width the {@code RECORD KEY} declares.
     *
     * <p>All four {@code SELECT} statements are {@code ORGANIZATION IS INDEXED} with a {@code RECORD KEY}
     * ({@code app/cbl/CBSTM03B.CBL:31-53}), so a missing or mismatched key length is a configuration
     * defect for every one of them - including the two that only ever read sequentially, because their
     * browse orders by that key.
     *
     * @param ddName   the configuration key, for the diagnostic
     * @param binding  the configured binding
     * @param declared the width the {@code RECORD KEY} declares
     * @param copybook the copybook path
     * @throws IllegalStateException if no key length is declared, or a different one is
     */
    private static void requireDeclaredKeyLength(String ddName, DatasetBinding binding,
                                                 int declared, String copybook) {
        Integer configured = binding.keyLength();
        if (configured == null) {
            throw new IllegalStateException("Dataset binding for '" + ddName + "' declares no "
                    + "key-length, but app/cbl/CBSTM03B.CBL:31-53 declares it ORGANIZATION IS INDEXED "
                    + "with a RECORD KEY, and " + copybook + " gives that key a width of " + declared
                    + ". Set carddemo.datasets." + ddName + ".key-length to " + declared + ".");
        }
        if (configured != declared) {
            throw new IllegalStateException("Dataset binding for '" + ddName + "' declares key-length "
                    + configured + ", but the RECORD KEY of app/cbl/CBSTM03B.CBL is " + declared
                    + " bytes wide per " + copybook + ". A keyed read composed from the wrong width "
                    + "would compare a short key as a prefix and match records the key does not name. "
                    + "Correct carddemo.datasets." + ddName + ".key-length.");
        }
        if (binding.keyOffsetOrZero() != 0) {
            throw new IllegalStateException("Dataset binding for '" + ddName + "' declares key-offset "
                    + binding.keyOffsetOrZero() + ", but every RECORD KEY in app/cbl/CBSTM03B.CBL:58-78 "
                    + "is the first field of its FD record, so the key begins at offset 0. Remove "
                    + "carddemo.datasets." + ddName + ".key-offset.");
        }
    }

    /**
     * Requires the width the caller computes for a key to equal the width the file declares for it.
     *
     * @param ddName        the DD whose key it is
     * @param callerField   the cross-reference field the caller measures, named in the diagnostic
     * @param callerWidth   {@code LENGTH OF} that field
     * @param declaredWidth the {@code RECORD KEY} width
     * @throws IllegalStateException if the two disagree
     */
    private static void requireCallerKeyWidth(String ddName, String callerField,
                                              int callerWidth, int declaredWidth) {
        if (callerWidth != declaredWidth) {
            throw new IllegalStateException("The key width app/cbl/CBSTM03A.CBL computes for '" + ddName
                    + "' is LENGTH OF " + callerField + " = " + callerWidth + ", but "
                    + "app/cbl/CBSTM03B.CBL declares its RECORD KEY " + declaredWidth + " bytes wide. "
                    + "app/cpy/CVACT03Y.cpy and the file's own copybook must agree about how wide that "
                    + "identifier is, because the caller's COMPUTE WS-M03B-KEY-LN is what the keyed "
                    + "read's reference modification uses.");
        }
    }

    /**
     * Requires a configured dataset name that can be composed into a statement.
     *
     * <p>Blank is rejected because {@code application.yml} spells every name as an environment
     * reference, so an unconfigured deployment yields an empty string rather than {@code null}.
     *
     * @param ddName    the configuration key, for the diagnostic
     * @param candidate the configured dataset name
     * @return {@code candidate}, unchanged
     * @throws IllegalStateException if it is absent, blank, or not a usable dataset name
     */
    private static String requireUsableDatasetName(String ddName, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for '" + ddName + "' declares no "
                    + "dataset name. Set carddemo.datasets." + ddName + ".dsname; this class composes "
                    + "its statements from configuration alone and hard-codes no dataset name (gate "
                    + "G46).");
        }
        // The grammar of a z/OS dataset name lives in DatasetRelation, so it is stated once for the
        // whole module rather than restated, and diverging, here.
        return DatasetRelation.requireDatasetName(candidate);
    }

    // =================================================================================================
    // The dataset identities and the declared operation matrix, exposed. A caller composing a diagnostic
    // needs the resolved name; a test proving the asymmetry needs the capability sets.
    // =================================================================================================

    /**
     * The resolved dataset name behind a DD name, exactly as configuration declares it.
     *
     * @param dd one of {@value #TRNXFILE_DD}, {@value #XREFFILE_DD}, {@value #CUSTFILE_DD},
     *           {@value #ACCTFILE_DD}
     * @return the configured dataset name; never {@code null}, never blank
     * @throws IllegalArgumentException if {@code dd} is not one of the four
     */
    public String datasetName(String dd) {
        return require(dd).relation.dsname();
    }

    /**
     * The record width of the dataset behind a DD name, from its copybook.
     *
     * @param dd one of the four DD names
     * @return {@code 350}, {@code 50}, {@code 500} or {@code 300}
     * @throws IllegalArgumentException if {@code dd} is not one of the four
     */
    public int recordLength(String dd) {
        return require(dd).recordLength;
    }

    /**
     * The operations a DD actually honours - the three {@code IF <op>} tests its {@code PROC} paragraph
     * performs.
     *
     * <p>This is the asymmetry of {@code CBSTM03B} made checkable instead of merely described:
     * {@value #TRNXFILE_DD} and {@value #XREFFILE_DD} report {@link Operation#OPEN},
     * {@link Operation#READ}, {@link Operation#CLOSE}; {@value #CUSTFILE_DD} and {@value #ACCTFILE_DD}
     * report {@link Operation#OPEN}, {@link Operation#READ_K}, {@link Operation#CLOSE}. No DD reports
     * {@link Operation#WRITE} or {@link Operation#REWRITE}, because the source tests neither anywhere.
     *
     * <p>Requesting an operation outside a DD's set is <strong>not</strong> rejected. It is the documented
     * silent no-op that returns the file's stale {@code FILE STATUS}, and this method exists so a caller
     * or a test can tell the two apart deliberately rather than by accident.
     *
     * @param dd one of the four DD names
     * @return the unmodifiable set of honoured operations
     * @throws IllegalArgumentException if {@code dd} is not one of the four
     */
    public Set<Operation> supportedOperations(String dd) {
        return require(dd).capabilities;
    }

    /**
     * Whether a DD name is one of the four {@code EVALUATE LK-M03B-DD} recognises.
     *
     * <p>Comparison is exact and unpadded on the caller's side: a {@link Request} has already fitted its
     * DD name to {@value #DD_LENGTH} characters, and all four names are exactly that wide, so an exact
     * match here is the same comparison COBOL makes against {@code PIC X(08)}.
     *
     * @param dd the DD name to test; {@code null} answers {@code false}
     * @return {@code true} for the four recognised names
     */
    public boolean recognises(String dd) {
        return dd != null && datasets.containsKey(dd);
    }

    /**
     * Resolves a DD name to its access descriptor, for the accessors that require a known DD.
     *
     * @param dd the DD name
     * @return the descriptor
     * @throws IllegalArgumentException if {@code dd} is not one of the four
     */
    private DatasetAccess require(String dd) {
        DatasetAccess access = dd == null ? null : datasets.get(dd);
        if (access == null) {
            throw new IllegalArgumentException("'" + dd + "' is not one of the four DD names "
                    + "app/cbl/CBSTM03B.CBL:118-126 dispatches on " + DD_NAMES + ". This accessor "
                    + "requires a known DD; to exercise the WHEN OTHER arm, call "
                    + "call(Session, Request) with the unrecognised name - that path is a no-op that "
                    + "returns the request's own status untouched.");
        }
        return access;
    }

    // =================================================================================================
    // The session. app/cbl/CBSTM03B.CBL's four open-file cursors and four FILE STATUS areas survive from
    // one CALL to the next; this is where that state lives, and it is the only place it lives.
    // =================================================================================================

    /**
     * Opens a fresh execution session - four closed cursors with four untouched {@code FILE STATUS}
     * areas.
     *
     * <p>One session stands for one run of the statement job. Two sessions share nothing: their cursors
     * are separate objects with separate positions and separate statuses, so two concurrent runs cannot
     * see each other's progress. That is the whole reason the state is here rather than on this component
     * (practice B9, gate G53).
     *
     * <p>The returned session is {@link AutoCloseable}, so a caller may use try-with-resources, and
     * {@link Session#close()} is idempotent so an explicit close inside the block is safe too. Closing it
     * releases nothing scarce - a cursor holds a record image and a position, never a connection or a
     * result set - so a leaked session is a correctness question about the position it holds, not a
     * resource leak.
     *
     * @return a new session; never {@code null}
     */
    public Session newSession() {
        Map<String, Cursor> cursors = new LinkedHashMap<>();
        for (String dd : DD_NAMES) {
            cursors.put(dd, new Cursor(datasets.get(dd)));
        }
        return new Session(cursors);
    }

    // =================================================================================================
    // 0000-START. app/cbl/CBSTM03B.CBL:116-131 - the four-way dispatch and the WHEN OTHER arm.
    // =================================================================================================

    /**
     * The single generic entry point: the Java form of
     * {@code PROCEDURE DIVISION USING LK-M03B-AREA} and of {@code 0000-START}
     * ({@code app/cbl/CBSTM03B.CBL:114-131}). This is the method the statement job calls thirteen times.
     *
     * <pre>
     * EVALUATE LK-M03B-DD
     *   WHEN 'TRNXFILE'  PERFORM 1000-TRNXFILE-PROC THRU 1999-EXIT
     *   WHEN 'XREFFILE'  PERFORM 2000-XREFFILE-PROC THRU 2999-EXIT
     *   WHEN 'CUSTFILE'  PERFORM 3000-CUSTFILE-PROC THRU 3999-EXIT
     *   WHEN 'ACCTFILE'  PERFORM 4000-ACCTFILE-PROC THRU 4999-EXIT
     *   WHEN OTHER       GO TO 9999-GOBACK
     * </pre>
     *
     * <p>The arm order is the source's order and {@code WHEN OTHER} is last (gate G30). Each
     * {@code PERFORM ... THRU nnn9-EXIT} range collapses to exactly one Java method call, per AAP
     * &sect;0.7.5's classification of the twelve benign range-performs.
     *
     * <p><strong>The {@code WHEN OTHER} arm assigns nothing.</strong> {@code GO TO 9999-GOBACK} skips
     * every {@code nnn900-EXIT}, so no {@code MOVE <dd>FILE-STATUS TO LK-M03B-RC} runs, no file is
     * touched, and {@code LK-M03B-RC} and {@code LK-M03B-FLDT} still hold exactly what the caller left
     * there. This method reproduces that by echoing {@link Request#rc()} and {@link Request#fldt()}
     * straight back. It does not invent an error status, does not throw, and does not log - a log line
     * would be harmless but the temptation that follows it is to "improve" the status, and this arm's
     * whole content is that nothing happens.
     *
     * @param session the execution session holding the four cursors and their statuses
     * @param request the inbound projection of {@code LK-M03B-AREA}
     * @return the outbound projection: the status and the record area this call leaves behind
     * @throws NullPointerException  if {@code session} or {@code request} is {@code null}
     * @throws IllegalStateException if {@code session} has been closed
     */
    public Response call(Session session, Request request) {
        Objects.requireNonNull(session, "A session is required: the four cursors and their FILE STATUS "
                + "areas live on it, because app/cbl/CBSTM03B.CBL keeps them across all thirteen calls");
        Objects.requireNonNull(request, "A request is required: it is the inbound projection of "
                + "LK-M03B-AREA");
        session.requireUsable();

        // EVALUATE LK-M03B-DD, in source order, WHEN OTHER last.
        String dd = request.dd();
        if (TRNXFILE_DD.equals(dd)) {
            return trnxFileProc(session, request);
        }
        if (XREFFILE_DD.equals(dd)) {
            return xrefFileProc(session, request);
        }
        if (CUSTFILE_DD.equals(dd)) {
            return custFileProc(session, request);
        }
        if (ACCTFILE_DD.equals(dd)) {
            return acctFileProc(session, request);
        }
        // WHEN OTHER -> GO TO 9999-GOBACK. Nothing is touched and nothing is assigned.
        return new Response(request.rc(), request.fldt());
    }

    // =================================================================================================
    // The four PROC paragraphs. app/cbl/CBSTM03B.CBL:133-229. Each is three IF <op> blocks whose GO TO
    // sits INSIDE the IF, followed by nnn900-EXIT's unconditional MOVE of the file's status - which is
    // therefore reached both by a matched operation and by falling out of the third IF.
    // =================================================================================================

    /**
     * {@code 1000-TRNXFILE-PROC THRU 1999-EXIT} - {@code app/cbl/CBSTM03B.CBL:133-155}.
     *
     * <pre>
     * IF M03B-OPEN    OPEN INPUT TRNX-FILE            GO TO 1900-EXIT  END-IF.   :135-138
     * IF M03B-READ    READ TRNX-FILE INTO LK-M03B-FLDT GO TO 1900-EXIT END-IF.   :140-144
     * IF M03B-CLOSE   CLOSE TRNX-FILE                 GO TO 1900-EXIT  END-IF.   :146-149
     * 1900-EXIT.  MOVE TRNXFILE-STATUS TO LK-M03B-RC.                            :151-152
     * </pre>
     *
     * <p>{@code M03B-READ} is a <strong>sequential</strong> read: {@code ACCESS MODE IS SEQUENTIAL} on
     * {@code RECORD KEY IS FD-TRNXS-ID} ({@code :33-34}), the 32-byte group of card number and
     * transaction id that {@code app/jcl/CREASTMT.JCL:30} defines to IDCAMS as {@code KEYS(32 0)}. There
     * is no {@code IF M03B-READ-K} here, so a keyed read is one of the fall-through cases.
     *
     * @param session the session
     * @param request the request; its DD name is not re-checked, so this may be called directly
     * @return the status and record area this operation leaves behind
     * @throws NullPointerException  if either argument is {@code null}
     * @throws IllegalStateException if {@code session} has been closed
     */
    public Response trnxFileProc(Session session, Request request) {
        return proc(session, request, TRNXFILE_DD);
    }

    /**
     * {@code 2000-XREFFILE-PROC THRU 2999-EXIT} - {@code app/cbl/CBSTM03B.CBL:157-179}.
     *
     * <pre>
     * IF M03B-OPEN    OPEN INPUT XREF-FILE            GO TO 2900-EXIT  END-IF.   :159-162
     * IF M03B-READ    READ XREF-FILE INTO LK-M03B-FLDT GO TO 2900-EXIT END-IF.   :164-168
     * IF M03B-CLOSE   CLOSE XREF-FILE                 GO TO 2900-EXIT  END-IF.   :170-173
     * 2900-EXIT.  MOVE XREFFILE-STATUS TO LK-M03B-RC.                            :175-176
     * </pre>
     *
     * <p>Sequential, on {@code RECORD KEY IS FD-XREF-CARD-NUM} ({@code :39-40}). This is the browse that
     * drives the whole statement run: {@code app/cbl/CBSTM03A.CBL:346-364}'s
     * {@code 1000-XREFFILE-GET-NEXT} reads one cross-reference record, then looks up its customer and its
     * account by key.
     *
     * @param session the session
     * @param request the request
     * @return the status and record area this operation leaves behind
     * @throws NullPointerException  if either argument is {@code null}
     * @throws IllegalStateException if {@code session} has been closed
     */
    public Response xrefFileProc(Session session, Request request) {
        return proc(session, request, XREFFILE_DD);
    }

    /**
     * {@code 3000-CUSTFILE-PROC THRU 3999-EXIT} - {@code app/cbl/CBSTM03B.CBL:181-204}.
     *
     * <pre>
     * IF M03B-OPEN    OPEN INPUT CUST-FILE                            GO TO 3900-EXIT END-IF. :183-186
     * IF M03B-READ-K  MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-CUST-ID
     *                 READ CUST-FILE INTO LK-M03B-FLDT                GO TO 3900-EXIT END-IF. :188-193
     * IF M03B-CLOSE   CLOSE CUST-FILE                                 GO TO 3900-EXIT END-IF. :195-198
     * 3900-EXIT.  MOVE CUSTFILE-STATUS TO LK-M03B-RC.                                         :200-201
     * </pre>
     *
     * <p>{@code ACCESS MODE IS RANDOM} ({@code :45}), so the read is <strong>keyed</strong> and there is
     * no {@code IF M03B-READ}: a plain sequential read against this DD is a fall-through case.
     * {@code FD-CUST-ID} is {@code PIC X(09)} ({@code :72}), an alphanumeric receiver.
     *
     * @param session the session
     * @param request the request; a keyed read reads {@link Request#usedKey()}
     * @return the status and record area this operation leaves behind
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalStateException    if {@code session} has been closed
     * @throws IllegalArgumentException on a keyed read whose {@link Request#keyLength()} is outside
     *                                  {@code 1..}{@value #KEY_LENGTH}
     */
    public Response custFileProc(Session session, Request request) {
        return proc(session, request, CUSTFILE_DD);
    }

    /**
     * {@code 4000-ACCTFILE-PROC THRU 4999-EXIT} - {@code app/cbl/CBSTM03B.CBL:206-229}.
     *
     * <pre>
     * IF M03B-OPEN    OPEN INPUT ACCT-FILE                            GO TO 4900-EXIT END-IF. :208-211
     * IF M03B-READ-K  MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-ACCT-ID
     *                 READ ACCT-FILE INTO LK-M03B-FLDT                GO TO 4900-EXIT END-IF. :213-218
     * IF M03B-CLOSE   CLOSE ACCT-FILE                                 GO TO 4900-EXIT END-IF. :220-223
     * 4900-EXIT.  MOVE ACCTFILE-STATUS TO LK-M03B-RC.                                         :225-226
     * </pre>
     *
     * <p>{@code ACCESS MODE IS RANDOM} ({@code :51}), keyed read only. {@code FD-ACCT-ID} is
     * {@code PIC 9(11)} ({@code :77}) - a <strong>numeric-display</strong> receiver, so the key move takes
     * the {@code PIC 9} rule and not the {@code PIC X} one.
     *
     * @param session the session
     * @param request the request; a keyed read reads {@link Request#usedKey()}
     * @return the status and record area this operation leaves behind
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalStateException    if {@code session} has been closed
     * @throws IllegalArgumentException on a keyed read whose {@link Request#keyLength()} is outside
     *                                  {@code 1..}{@value #KEY_LENGTH}, or whose used key is not all
     *                                  digits - {@code FD-ACCT-ID} is a numeric picture, so a
     *                                  non-numeric key is a caller-contract violation rather than an
     *                                  I/O outcome
     */
    public Response acctFileProc(Session session, Request request) {
        return proc(session, request, ACCTFILE_DD);
    }

    /**
     * The shape all four {@code PROC} paragraphs share, parameterised by DD name.
     *
     * <p>The four paragraphs are byte-for-byte the same control structure - three {@code IF <op>} blocks
     * then an unconditional status move - differing only in which file they name and in whether their
     * middle test is {@code M03B-READ} or {@code M03B-READ-K}. Writing it once and selecting the middle
     * test from the DD's capability set keeps the four identical <em>by construction</em>, which is what
     * AAP practice R7 asks of restructured control flow: the evaluation order and the fall-through
     * outcome are preserved, and there is one place to read them.
     *
     * <p>Order of the three tests is the source's order: {@code OPEN}, then read, then {@code CLOSE}.
     * Since the three are mutually exclusive the order cannot change an outcome, but it is preserved
     * anyway because gate G30 is about the shape of the translated decision and not only about its
     * result.
     *
     * @param session the session
     * @param request the request
     * @param dd      the DD whose paragraph this is
     * @return the status and record area this operation leaves behind
     */
    private Response proc(Session session, Request request, String dd) {
        Objects.requireNonNull(session, "A session is required");
        Objects.requireNonNull(request, "A request is required");
        session.requireUsable();
        Cursor cursor = session.cursor(dd);
        Operation oper = request.oper();
        String fldt = request.fldt();

        // IF M03B-OPEN ... GO TO nnn900-EXIT END-IF.
        if (oper == Operation.OPEN) {
            cursor.openInput();
            return exit(cursor, fldt);
        }
        // IF M03B-READ ... END-IF, on the two SEQUENTIAL files only.
        if (oper == Operation.READ && cursor.access.capabilities.contains(Operation.READ)) {
            fldt = cursor.readNext(fldt);
            return exit(cursor, fldt);
        }
        // IF M03B-READ-K ... END-IF, on the two RANDOM files only.
        if (oper == Operation.READ_K && cursor.access.capabilities.contains(Operation.READ_K)) {
            fldt = cursor.readByKey(request, fldt);
            return exit(cursor, fldt);
        }
        // IF M03B-CLOSE ... GO TO nnn900-EXIT END-IF.
        if (oper == Operation.CLOSE) {
            cursor.close();
            return exit(cursor, fldt);
        }
        // No IF matched. In COBOL every GO TO nnn900-EXIT sits INSIDE its IF, so control falls out of the
        // third IF straight into nnn900-EXIT - which still performs its MOVE. So this is a SILENT NO-OP
        // that returns the file's LAST-KNOWN FILE STATUS, stale and unchanged, and the record area
        // exactly as it arrived.
        //
        // This reaches here for: READ-K against TRNXFILE or XREFFILE; plain READ against CUSTFILE or
        // ACCTFILE; WRITE or REWRITE against any of the four (both are declared at CBSTM03B.CBL:107-108
        // and tested nowhere); and an operation byte matching none of the six 88-levels.
        //
        // DO NOT "harden" this into an exception or a fresh error status. The caller's guard chains
        // (app/cbl/CBSTM03A.CBL:736, :353-359, :379-386) decide what a status means, and turning a no-op
        // into a throw would bypass every one of them. It is the most likely parity break in this file,
        // which is why it is asserted by tests and not only described here.
        return exit(cursor, fldt);
    }

    /**
     * {@code nnn900-EXIT. MOVE <dd>FILE-STATUS TO LK-M03B-RC.}
     *
     * <p>{@code app/cbl/CBSTM03B.CBL:152}, {@code :176}, {@code :201} and {@code :226} - one per
     * paragraph, and every path through a matched DD passes through one of them. That is why an
     * unsupported operation still returns a status: the move is not inside any {@code IF}.
     *
     * @param cursor the file whose status is moved
     * @param fldt   the record area as it now stands
     * @return the outbound projection
     */
    private static Response exit(Cursor cursor, String fldt) {
        return new Response(cursor.status(), fldt);
    }

    // =================================================================================================
    // The four operations, individually callable. Each is the generic entry point with one Request
    // factory applied, so a test - and the statement job itself - can express one operation without
    // composing an area (practice B10, gate G51). No Spring context and no JobLauncher is involved.
    // =================================================================================================

    /**
     * {@code OPEN INPUT} - {@code SET M03B-OPEN TO TRUE} then
     * {@code CALL 'CBSTM03B' USING WS-M03B-AREA}, as {@code app/cbl/CBSTM03A.CBL:731-734} does it.
     *
     * <p>All four files are opened {@code INPUT} and only {@code INPUT}; this class has no {@code I-O},
     * {@code OUTPUT} or {@code EXTEND} path and never writes a record.
     *
     * @param session the session
     * @param dd      the DD name to open
     * @return the status the open reported, with the record area untouched
     * @throws NullPointerException     if {@code session} or {@code dd} is {@code null}
     * @throws IllegalStateException    if {@code session} has been closed
     */
    public Response open(Session session, String dd) {
        return call(session, Request.open(dd));
    }

    /**
     * A sequential {@code READ ... INTO LK-M03B-FLDT}, as
     * {@code app/cbl/CBSTM03A.CBL:346-351} does it.
     *
     * <p>Honoured by {@value #TRNXFILE_DD} and {@value #XREFFILE_DD}. Against {@value #CUSTFILE_DD} or
     * {@value #ACCTFILE_DD} it is the documented silent no-op returning the stale status, because neither
     * paragraph tests {@code M03B-READ}.
     *
     * @param session the session
     * @param dd      the DD name to read
     * @return {@code '00'} and a record, {@code '10'} at end of file with the record area unchanged, or a
     *         reported failure
     * @throws NullPointerException  if {@code session} or {@code dd} is {@code null}
     * @throws IllegalStateException if {@code session} has been closed
     */
    public Response readNext(Session session, String dd) {
        return call(session, Request.read(dd));
    }

    /**
     * A keyed {@code READ}, as {@code app/cbl/CBSTM03A.CBL:367-377} and {@code :391-401} do it.
     *
     * <p>Honoured by {@value #CUSTFILE_DD} (key width {@value #CUSTFILE_CALLER_KEY_LENGTH}) and
     * {@value #ACCTFILE_DD} (key width {@value #ACCTFILE_CALLER_KEY_LENGTH}). Against
     * {@value #TRNXFILE_DD} or {@value #XREFFILE_DD} it is the documented silent no-op returning the stale
     * status.
     *
     * @param session   the session
     * @param dd        the DD name to read
     * @param key       the key value; it is fitted to the {@value #KEY_LENGTH}-byte span exactly as
     *                  {@code MOVE XREF-CUST-ID TO WS-M03B-KEY} fits it
     * @param keyLength how many bytes of the span the key occupies - what
     *                  {@code COMPUTE WS-M03B-KEY-LN = LENGTH OF ...} computes
     * @return {@code '00'} and a record, {@code '23'} when no record carries that key, or a reported
     *         failure
     * @throws NullPointerException  if {@code session}, {@code dd} or {@code key} is {@code null}
     * @throws IllegalStateException if {@code session} has been closed
     */
    public Response readByKey(Session session, String dd, String key, int keyLength) {
        return call(session, Request.readKeyed(dd, key, keyLength));
    }

    /**
     * {@code CLOSE}, as {@code app/cbl/CBSTM03A.CBL:857-860} does it.
     *
     * @param session the session
     * @param dd      the DD name to close
     * @return the status the close reported, with the record area untouched
     * @throws NullPointerException  if {@code session} or {@code dd} is {@code null}
     * @throws IllegalStateException if {@code session} has been closed
     */
    public Response close(Session session, String dd) {
        return call(session, Request.close(dd));
    }

    // =================================================================================================
    // The 1040-byte area image. app/cbl/CBSTM03B.CBL:100-112 as bytes, so the contract the caller's own
    // 01 WS-M03B-AREA has to match is expressible, round-trippable and testable rather than only
    // documented. This is also the form a parity fingerprint of one CALL takes.
    // =================================================================================================

    /**
     * The {@link FixedWidthRecord.RecordLayout} of {@code LK-M03B-AREA}, field for field.
     *
     * <p>Built once here rather than per call. The layout self-checks on construction - its spans must
     * account for exactly its declared record length - so a field width that did not close the
     * {@value #AREA_LENGTH}-byte area would fail at class initialisation and name the offending
     * descriptor, which is a second, independent guard on the same contract the static initialiser above
     * asserts arithmetically.
     *
     * <p>{@code LK-M03B-KEY-LN} is declared
     * {@link FixedWidthRecord.FieldSpan#signedScaled(String, int, int, int)} with
     * {@value #KEY_LN_LENGTH} integer digits and a scale of {@code 0}, because {@code PIC S9(4)} with no
     * {@code USAGE} clause is four zoned {@code DISPLAY} bytes with the sign overpunched into the
     * trailing one - not a two-byte binary halfword. Passing digit counts rather than a byte width is
     * what makes a phantom sign byte impossible: the span can only ever be four bytes wide, which is what
     * keeps {@code LK-M03B-FLDT} at offset {@value #FLDT_OFFSET}.
     */
    public static final FixedWidthRecord.RecordLayout AREA_LAYOUT = areaLayout();

    /** {@code 01 LK-M03B-DD PIC X(08)}, the field name verbatim from {@code CBSTM03B.CBL:101}. */
    public static final String DD_FIELD_NAME = "LK-M03B-DD";

    /** {@code LK-M03B-OPER PIC X(01)}, verbatim from {@code CBSTM03B.CBL:102}. */
    public static final String OPER_FIELD_NAME = "LK-M03B-OPER";

    /** {@code LK-M03B-RC PIC X(02)}, verbatim from {@code CBSTM03B.CBL:109}. */
    public static final String RC_FIELD_NAME = "LK-M03B-RC";

    /** {@code LK-M03B-KEY PIC X(25)}, verbatim from {@code CBSTM03B.CBL:110}. */
    public static final String KEY_FIELD_NAME = "LK-M03B-KEY";

    /** {@code LK-M03B-KEY-LN PIC S9(4)}, verbatim from {@code CBSTM03B.CBL:111}. */
    public static final String KEY_LN_FIELD_NAME = "LK-M03B-KEY-LN";

    /** {@code LK-M03B-FLDT PIC X(1000)}, verbatim from {@code CBSTM03B.CBL:112}. */
    public static final String FLDT_FIELD_NAME = "LK-M03B-FLDT";

    /**
     * Composes {@link #AREA_LAYOUT}.
     *
     * <p>The names are the copybook item names verbatim, which is what lets a reviewer diff this layout
     * against {@code app/cbl/CBSTM03B.CBL:100-112} line by line, and what lets a parity fingerprint of
     * one call be keyed by COBOL field name.
     *
     * @return the layout of the {@value #AREA_LENGTH}-byte linkage area
     */
    private static FixedWidthRecord.RecordLayout areaLayout() {
        return new FixedWidthRecord.RecordLayout(AREA_LENGTH, List.of(
                FixedWidthRecord.FieldSpan.alphanumeric(DD_FIELD_NAME, DD_OFFSET, DD_LENGTH),
                FixedWidthRecord.FieldSpan.alphanumeric(OPER_FIELD_NAME, OPER_OFFSET, OPER_LENGTH),
                FixedWidthRecord.FieldSpan.alphanumeric(RC_FIELD_NAME, RC_OFFSET, RC_LENGTH),
                FixedWidthRecord.FieldSpan.alphanumeric(KEY_FIELD_NAME, KEY_OFFSET, KEY_LENGTH),
                FixedWidthRecord.FieldSpan.signedScaled(KEY_LN_FIELD_NAME, KEY_LN_OFFSET,
                        KEY_LN_LENGTH, 0),
                FixedWidthRecord.FieldSpan.alphanumeric(FLDT_FIELD_NAME, FLDT_OFFSET, FLDT_LENGTH)));
    }

    /**
     * Renders one call's worth of {@code LK-M03B-AREA} as its {@value #AREA_LENGTH} bytes.
     *
     * <p>The area is shared, so the image is composed from both halves of the conversation: the DD name,
     * operation, key and key length come from the request, and the status and record area come from the
     * response - which is exactly the state of the area at the moment {@code GOBACK} returns control to
     * the caller. Pass {@code null} for {@code response} to render the area as it looked on the way
     * <em>in</em>, taking the status and record area from the request instead.
     *
     * <p>Every byte is encoded in the explicitly injected dataset code page (practice B8);
     * {@code LK-M03B-KEY-LN} is written as a signed zoned {@code DISPLAY} field, so a key length of
     * {@code 9} renders as {@code "000I"} and one of {@code 11} as {@code "001A"}, the overpunched forms
     * a mainframe caller would actually pass.
     *
     * @param request  the inbound half; never {@code null}
     * @param response the outbound half, or {@code null} to render the inbound area
     * @return the area image, exactly {@value #AREA_LENGTH} bytes
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public byte[] toAreaImage(Request request, Response response) {
        Objects.requireNonNull(request, "A request is required to render LK-M03B-AREA");
        String rc = response == null ? request.rc() : response.rc();
        String fldt = response == null ? request.fldt() : response.fldt();

        FixedWidthRecord area = codec.newRecord(AREA_LAYOUT);
        codec.writePicX(area, span(DD_FIELD_NAME), request.dd());
        codec.writePicX(area, span(OPER_FIELD_NAME),
                request.oper() == null ? UNRECOGNISED_OPER_IMAGE : request.oper().image());
        codec.writePicX(area, span(RC_FIELD_NAME), rc);
        codec.writePicX(area, span(KEY_FIELD_NAME), request.key());
        codec.writeSignedScaled(area, span(KEY_LN_FIELD_NAME),
                BigDecimal.valueOf(request.keyLength()), 0);
        codec.writePicX(area, span(FLDT_FIELD_NAME), fldt);
        return area.toByteArray();
    }

    /**
     * Parses a {@value #AREA_LENGTH}-byte {@code LK-M03B-AREA} image back into its inbound projection.
     *
     * <p>The inverse of {@link #toAreaImage(Request, Response)}, so the two round-trip: an area rendered
     * from a request and parsed back yields an equal request. That round trip is what proves the declared
     * offsets {@code 0/8/9/11/36/40} and widths {@code 8/1/2/25/4/1000} are the ones actually written,
     * rather than merely the ones documented.
     *
     * <p>An operation byte matching none of the six {@code 88}-levels yields a {@code null}
     * {@link Request#oper()}, which is the fall-through case and not an error.
     *
     * @param area the area image, exactly {@value #AREA_LENGTH} bytes
     * @return the inbound projection it encodes
     * @throws NullPointerException     if {@code area} is {@code null}
     * @throws IllegalArgumentException if {@code area} is not exactly {@value #AREA_LENGTH} bytes
     */
    public Request fromAreaImage(byte[] area) {
        Objects.requireNonNull(area, "An area image is required to parse LK-M03B-AREA");
        FixedWidthRecord record = codec.wrap(area, AREA_LAYOUT);
        String oper = codec.readPicX(record, span(OPER_FIELD_NAME));
        return new Request(
                codec.readPicX(record, span(DD_FIELD_NAME)),
                Operation.ofCode(oper.charAt(0)).orElse(null),
                codec.readPicX(record, span(RC_FIELD_NAME)),
                codec.readPicX(record, span(KEY_FIELD_NAME)),
                codec.readSignedScaled(record, span(KEY_LN_FIELD_NAME), 0).intValueExact(),
                codec.readPicX(record, span(FLDT_FIELD_NAME)));
    }

    /**
     * Resolves one field descriptor of {@link #AREA_LAYOUT} by name.
     *
     * @param name the COBOL field name
     * @return the descriptor
     * @throws IllegalStateException if the layout does not declare it, which would mean this class and
     *                               {@link #areaLayout()} had drifted apart
     */
    private static FixedWidthRecord.FieldSpan span(String name) {
        for (FixedWidthRecord.FieldSpan candidate : AREA_LAYOUT.spans()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        throw new IllegalStateException("LK-M03B-AREA declares no field named '" + name
                + "'; app/cbl/CBSTM03B.CBL:100-112 declares " + DD_FIELD_NAME + ", " + OPER_FIELD_NAME
                + ", " + RC_FIELD_NAME + ", " + KEY_FIELD_NAME + ", " + KEY_LN_FIELD_NAME + " and "
                + FLDT_FIELD_NAME + ", and AREA_LAYOUT must declare exactly those six.");
    }

    // =================================================================================================
    // One dataset's identity, geometry and access paths. Immutable apart from the memoised statement
    // text, which is a derived value computed idempotently - not conversation state, and not a cursor.
    // =================================================================================================

    /**
     * Everything this class knows about one of the four datasets, and the only place its statements are
     * sent.
     *
     * <p>An inner class so it can reach the enclosing component's {@link #jdbcTemplate}, {@link #codec}
     * and {@link #recordImageForm} without four fields restating them. It holds <strong>no cursor
     * position and no {@code FILE STATUS}</strong>; those are per-session and live on {@link Cursor}.
     */
    private final class DatasetAccess {

        /** The DD name, for diagnostics and for the capability lookup. */
        private final String ddName;

        /** The resolved dataset, from configuration; the sole source of the statement text. */
        private final DatasetRelation relation;

        /** The copybook's declared record width - 350, 50, 500 or 300. */
        private final int recordLength;

        /**
         * The {@code RECORD KEY}'s offset and width.
         *
         * <p>Held for all four DDs even though only two read by key, because a sequential browse of an
         * {@code ORGANIZATION IS INDEXED} file is a browse <em>in key order</em> and the key is what the
         * ordering is defined against.
         */
        private final KeySpan keySpan;

        /** Which {@code MOVE} rule the {@code RECORD KEY} receiver takes. */
        private final KeyPicture keyPicture;

        /** The three operations this DD's {@code PROC} paragraph implements; unmodifiable. */
        private final Set<Operation> capabilities;

        /**
         * The composed statements, resolved on first use.
         *
         * <p>Lazily, because the record-image column's name is discovered from the backend and this
         * component must be constructible in a context that has not reached its backend yet.
         * {@code volatile} so a session on another thread sees a fully published value; the computation is
         * idempotent, so a benign race recomputes an equal value rather than corrupting one.
         */
        private volatile Statements statements;

        /**
         * @param ddName       the DD name
         * @param relation     the resolved dataset
         * @param recordLength the copybook's record width
         * @param keySpan      the {@code RECORD KEY} span
         * @param keyPicture   the receiver's picture kind
         * @param capabilities the operations the source implements for this DD
         */
        private DatasetAccess(String ddName,
                              DatasetRelation relation,
                              int recordLength,
                              KeySpan keySpan,
                              KeyPicture keyPicture,
                              Set<Operation> capabilities) {
            this.ddName = ddName;
            this.relation = relation;
            this.recordLength = recordLength;
            this.keySpan = keySpan;
            this.keyPicture = keyPicture;
            this.capabilities = capabilities;
        }

        /**
         * Establishes that the dataset exists and presents a record-image column - what
         * {@code OPEN INPUT} establishes.
         *
         * <p>The probe is a describe: its predicate is false on every row, so nothing crosses the wire.
         * An unreachable dataset therefore fails here, at the open, exactly where the COBOL would have
         * reported it, rather than surfacing on the first read.
         *
         * @throws DataAccessException   if the dataset cannot be described
         * @throws IllegalStateException if it presents no usable record-image column
         */
        private void probe() {
            resolveStatements();
        }

        /**
         * Reads one row of a sequential browse, or reports that there is none.
         *
         * <p>One row per call, capped on the statement, so a browse never materialises the dataset and a
         * defect in the four-thousandth record is reported by the read that reaches it. The browse
         * advances by asking for the lowest record image strictly above the one already returned - the
         * whole image and not the key alone, because a bare key sorts <em>below</em> the record carrying
         * it and a browse positioned by key would return the same record for ever.
         *
         * @param position the stored image to advance past, or {@code null} for the first read
         * @return whether a row arrived and, if so, the bytes it carried; never {@code null}
         * @throws DataAccessException if the backend refused the read
         */
        private Row nextRow(byte[] position) {
            Statements sql = resolveStatements();
            String statement = position == null ? sql.browseFirst() : sql.browseAfter();
            PreparedStatementCreator creator = connection -> {
                PreparedStatement prepared = connection.prepareStatement(statement);
                prepared.setMaxRows(BROWSE_ROW_LIMIT);
                prepared.setFetchSize(BROWSE_ROW_LIMIT);
                if (position != null) {
                    // A stored image, bound as an image, so the comparison runs against the column in its
                    // own representation and "the next record after this one" means the same on both
                    // sides.
                    recordImageForm.bindImage(prepared, 1, position, codec.charset());
                }
                return prepared;
            };
            ResultSetExtractor<Row> extractor = resultSet -> resultSet.next()
                    ? new Row(true, recordImageForm.readImage(resultSet,
                            DatasetRelation.RECORD_IMAGE_COLUMN_INDEX, codec.charset()))
                    : Row.none();
            Row row = jdbcTemplate.query(creator, extractor);
            // This extractor never returns null; the check keeps a driver that somehow produced one from
            // becoming a NullPointerException in the cursor instead of a diagnosable failure here.
            return row == null ? Row.none() : row;
        }

        /**
         * Reads the rows whose key span equals a key image - the keyed {@code READ}.
         *
         * <p>The predicate is in the statement, not in the caller: a keyed read that fetched every row and
         * compared keys in Java would transfer the whole dataset to answer a single-record question, and
         * would fail on a malformed record that has nothing to do with the key.
         *
         * <p>At most {@value #KEYED_READ_ROW_LIMIT} rows are transferred, which is one more than a unique
         * primary key can produce, so a violation of that uniqueness is detected rather than passing
         * unnoticed.
         *
         * @param keyImage the key, exactly {@link KeySpan#length()} characters
         * @return the matching images, at most two; or {@code null} if the template yielded no result
         * @throws DataAccessException if the backend refused the read
         */
        private List<byte[]> rowsByKey(String keyImage) {
            Statements sql = resolveStatements();
            String pattern = keySpan.pattern(keyImage);
            PreparedStatementCreator creator = connection -> {
                PreparedStatement prepared = connection.prepareStatement(sql.selectByKey());
                prepared.setMaxRows(KEYED_READ_ROW_LIMIT);
                prepared.setFetchSize(KEYED_READ_ROW_LIMIT);
                recordImageForm.bindOperand(prepared, 1, pattern, codec.charset());
                return prepared;
            };
            ResultSetExtractor<List<byte[]>> extractor = resultSet -> {
                List<byte[]> images = new ArrayList<>(KEYED_READ_ROW_LIMIT);
                while (images.size() < KEYED_READ_ROW_LIMIT && resultSet.next()) {
                    images.add(recordImageForm.readImage(resultSet,
                            DatasetRelation.RECORD_IMAGE_COLUMN_INDEX, codec.charset()));
                }
                return images;
            };
            return jdbcTemplate.query(creator, extractor);
        }

        /**
         * Reads, at most one row, the rows of <em>this DD's</em> dataset whose record-image column holds
         * nothing.
         *
         * <p>Composed per access path deliberately: each {@code DatasetAccess} owns its own relation, so
         * the four DD names this subprogram dispatches over each prove their own dataset. A probe over one
         * of them would say nothing about the other three.
         *
         * <p>The same creator and extractor shape as {@link #rowsByKey(String)}, so the probe cannot drift
         * from the read it qualifies and exercises no code path the reads do not. It binds no parameter:
         * the predicate is {@code IS NULL} over the record-image column, which names no key.
         *
         * @return the rows found, at most one; or {@code null} if the template yielded no result at all
         * @throws DataAccessException if the backend refused
         */
        private List<byte[]> rowsWithNoImage() {
            Statements sql = resolveStatements();
            PreparedStatementCreator creator = connection -> {
                PreparedStatement prepared = connection.prepareStatement(sql.probeUnreadableRows());
                prepared.setMaxRows(UNREADABLE_ROW_PROBE_LIMIT);
                prepared.setFetchSize(UNREADABLE_ROW_PROBE_LIMIT);
                return prepared;
            };
            ResultSetExtractor<List<byte[]>> extractor = resultSet -> {
                List<byte[]> images = new ArrayList<>(UNREADABLE_ROW_PROBE_LIMIT);
                while (images.size() < UNREADABLE_ROW_PROBE_LIMIT && resultSet.next()) {
                    images.add(recordImageForm.readImage(resultSet,
                            DatasetRelation.RECORD_IMAGE_COLUMN_INDEX, codec.charset()));
                }
                return images;
            };
            return jdbcTemplate.query(creator, extractor);
        }

        /**
         * The {@code MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO <RECORD KEY>} of
         * {@code app/cbl/CBSTM03B.CBL:189} and {@code :214}.
         *
         * <p>Two distinct operations behind one COBOL statement shape, and the distinction is the whole
         * reason {@link KeyPicture} exists:
         * <ul>
         *   <li>{@value #CUSTFILE_DD}'s {@code FD-CUST-ID PIC X(09)} is <strong>alphanumeric</strong>, so
         *       the move fills from the left and pads or truncates on the right -
         *       {@link FixedWidthCodec#movePicX(String, int)}.</li>
         *   <li>{@value #ACCTFILE_DD}'s {@code FD-ACCT-ID PIC 9(11)} is <strong>numeric
         *       display</strong>, so the move aligns on the implied decimal point and zero-fills or
         *       truncates on the <em>left</em> - {@link FixedWidthCodec#movePic9(String, int)}.</li>
         * </ul>
         * Neither is a Java assignment, and that is deliberate: an assignment would neither pad nor
         * truncate, and the resulting parity defect would be invisible at the call site.
         *
         * @param usedKey the {@code keyLength}-byte prefix of {@code LK-M03B-KEY}
         * @return the key at the receiver's declared width
         * @throws IllegalArgumentException if the receiver is numeric and {@code usedKey} is not all
         *                                  digits
         */
        private String moveIntoRecordKey(String usedKey) {
            return keyPicture == KeyPicture.NUMERIC_DISPLAY
                    ? codec.movePic9(usedKey, keySpan.length())
                    : codec.movePicX(usedKey, keySpan.length());
        }

        /**
         * {@code READ ... INTO LK-M03B-FLDT} - the group move of a record into the
         * {@value #FLDT_LENGTH}-byte receiver.
         *
         * <p>{@code LK-M03B-FLDT} is {@code PIC X(1000)}
         * ({@code app/cbl/CBSTM03B.CBL:111}), so {@code READ INTO} performs an implicit alphanumeric
         * {@code MOVE} and the receiver's own rule applies: left justified, space-padded when the record
         * is shorter and <strong>truncated on the right</strong> when it is longer. That is
         * {@link FixedWidthCodec#movePicX(String, int)} exactly, and the returned area is always
         * {@value #FLDT_LENGTH} characters regardless of which of the four datasets produced it.
         *
         * <p>{@code movePicX} rather than {@code padToDeclaredWidth} because the latter refuses a row
         * wider than the receiver, and refusing is not what {@code MOVE} does. For every conforming row
         * the two are indistinguishable - each of the four records is far narrower than
         * {@value #FLDT_LENGTH} - and they differ only on a row that is over-wide, which is a
         * record-length conflict and reports {@link FileStatus#RECORD_LENGTH_CONFLICT} rather than
         * throwing.
         *
         * @param image the stored record image; a length other than {@link #recordLength} is a
         *              record-length conflict, which this method transfers rather than refuses
         * @return the {@value #FLDT_LENGTH}-character record area
         * @throws IllegalStateException if a stored byte is not valid in the dataset code page
         */
        private String intoRecordArea(byte[] image) {
            return codec.movePicX(codec.decodeImage(image, "a " + ddName + " row image"), FLDT_LENGTH);
        }

        /**
         * Resolves the statements on first use and returns them.
         *
         * @return the composed statements
         * @throws DataAccessException   if the dataset cannot be described
         * @throws IllegalStateException if it presents no usable record-image column
         */
        private Statements resolveStatements() {
            Statements resolved = this.statements;
            if (resolved == null) {
                ResultSetExtractor<String> columnNameExtractor =
                        StatementGenerationJobB::extractRecordImageColumn;
                String column = relation.rememberRecordImageColumn(
                        jdbcTemplate.query(relation.describeStatement(), columnNameExtractor));
                resolved = new Statements(
                        relation.selectAllAscending(column),
                        relation.selectAfterAscending(column),
                        relation.selectByKey(column),
                        relation.selectUnreadableRows(column));
                this.statements = resolved;
            }
            return resolved;
        }
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
     * The three statements one dataset needs - and not one more.
     *
     * <p>Only the access paths {@code CBSTM03B} uses are composed: a browse in key order, its advancing
     * form, and a keyed read. There is deliberately <strong>no</strong> insert, update, delete, upsert or
     * aggregate here, because all four files are {@code OPEN INPUT} and inventing a write path would give
     * this class a capability the source never had (gate G44).
     *
     * @param browseFirst  the first read of a sequential browse, in ascending key order
     * @param browseAfter  every read after the first: the lowest image strictly above the one already
     *                     returned, which is what makes a browse advance one record per read
     * @param selectByKey  the keyed read, taking an escaped {@code LIKE} pattern confined to the key span
     */
    private record Statements(String browseFirst, String browseAfter, String selectByKey,
                             String probeUnreadableRows) {
    }

    /**
     * Whether a read found a row, and the bytes it carried.
     *
     * <p>Three states, because the COBOL distinguishes three: no row is {@code AT END} (status
     * {@code '10'} on a browse, {@code '23'} on a keyed read); a row whose record-image column holds
     * nothing is a record that is present and unreadable, which is an I/O failure and emphatically not an
     * end of file; and a row with an image is a record. Collapsing the first two onto one {@code null}
     * would let a browse stop early and silently, which is the worst of the three to get wrong.
     *
     * @param present whether a row arrived at all
     * @param image   the row's bytes, which may be {@code null} even when a row arrived
     */
    private record Row(boolean present, byte[] image) {

        /** The shared end-of-read answer. */
        private static final Row NONE = new Row(false, null);

        /**
         * The answer for a read that found nothing.
         *
         * @return the shared end-of-read answer
         */
        private static Row none() {
            return NONE;
        }
    }

    // =================================================================================================
    // The session and its four cursors. app/cbl/CBSTM03B.CBL:83-97 declares four independent two-byte
    // FILE STATUS areas, and FILE-CONTROL gives each file its own open state and position. All of it
    // survives from one CALL to the next, and all of it lives here - never on the component, never
    // static (practice B9, gate G53).
    // =================================================================================================

    /**
     * One execution's worth of subroutine state: four cursors, each with its own open state, its own
     * position and its own {@code FILE STATUS}.
     *
     * <p><strong>The four statuses are independent, not one shared field.</strong>
     * {@code app/cbl/CBSTM03B.CBL:83-97} declares {@code TRNXFILE-STATUS}, {@code XREFFILE-STATUS},
     * {@code CUSTFILE-STATUS} and {@code ACCTFILE-STATUS} as four separate {@code 01} items, so a keyed
     * read that misses on {@value StatementGenerationJobB#CUSTFILE_DD} leaves
     * {@value StatementGenerationJobB#TRNXFILE_DD}'s status exactly as it was. That matters directly: the
     * statement job interleaves a {@value StatementGenerationJobB#XREFFILE_DD} browse with keyed reads of
     * the other two on every iteration, and collapsing the four onto one field would let one file's
     * outcome be read as another's.
     *
     * <p>Sessions share nothing. Two of them browse to two different positions, and neither can observe
     * the other - which is what makes the enclosing component safe to hold as a singleton and safe to
     * exercise from concurrent tests.
     *
     * <p>Not thread-safe <em>within</em> one session, deliberately: one session models one COBOL run unit,
     * and {@code CBSTM03A} is single-threaded. Sharing one session across threads would be sharing one
     * file position across threads, which has no COBOL counterpart.
     */
    public static final class Session implements AutoCloseable {

        /** The four cursors, keyed by DD name, in {@link StatementGenerationJobB#DD_NAMES} order. */
        private final Map<String, Cursor> cursors;

        /** Whether {@link #close()} has been called. */
        private boolean closed;

        /**
         * Constructed only by {@link StatementGenerationJobB#newSession()}, which is what guarantees that
         * a session always has exactly one cursor per DD.
         *
         * @param cursors the four cursors
         */
        private Session(Map<String, Cursor> cursors) {
            this.cursors = cursors;
            this.closed = false;
        }

        /**
         * The cursor for a DD name.
         *
         * @param dd one of the four DD names
         * @return its cursor
         * @throws IllegalArgumentException if {@code dd} is not one of the four
         */
        private Cursor cursor(String dd) {
            Cursor cursor = dd == null ? null : cursors.get(dd);
            if (cursor == null) {
                throw new IllegalArgumentException("'" + dd + "' is not one of the four DD names "
                        + DD_NAMES + " this session holds a cursor for. An unrecognised DD name takes "
                        + "the WHEN OTHER arm of call(Session, Request), which touches no file at all.");
            }
            return cursor;
        }

        /**
         * Refuses to operate on a closed session.
         *
         * @throws IllegalStateException if this session has been closed
         */
        private void requireUsable() {
            if (closed) {
                throw new IllegalStateException("This session has been closed, so its four cursors no "
                        + "longer hold a position. app/cbl/CBSTM03A.CBL closes each DD once, at :857-911, "
                        + "after its work is done - operating afterwards is a defect in the caller and "
                        + "not a file status, because no COBOL path could produce it. Open another "
                        + "session instead.");
            }
        }

        /**
         * The current {@code FILE STATUS} of one DD - the value the next
         * {@code MOVE <dd>FILE-STATUS TO LK-M03B-RC} would move.
         *
         * <p>This is the "stale status" an unsupported operation returns, exposed so a test can establish
         * it, request something unsupported and assert that the same value came back.
         *
         * @param dd one of the four DD names
         * @return the two-character status; {@link StatementGenerationJobB#UNTOUCHED_STATUS} if no
         *         operation has touched this DD yet
         * @throws IllegalArgumentException if {@code dd} is not one of the four
         */
        public String status(String dd) {
            return cursor(dd).status();
        }

        /**
         * Whether one DD is currently open.
         *
         * @param dd one of the four DD names
         * @return {@code true} between a successful {@code OPEN INPUT} and its {@code CLOSE}
         * @throws IllegalArgumentException if {@code dd} is not one of the four
         */
        public boolean isOpen(String dd) {
            return cursor(dd).opened;
        }

        /**
         * How many records a sequential browse of one DD has returned so far, which is also the
         * zero-based index of the record its next read would return.
         *
         * <p>Meaningful for the two sequential DDs. For the two random ones it stays {@code 0}, because a
         * keyed read has no position to advance - which is itself the fact that makes the two independent.
         *
         * @param dd one of the four DD names
         * @return the count; never negative
         * @throws IllegalArgumentException if {@code dd} is not one of the four
         */
        public int position(String dd) {
            return cursor(dd).returned;
        }

        /**
         * Whether a sequential browse of one DD has run past its last record.
         *
         * @param dd one of the four DD names
         * @return {@code true} once a read has reported {@code '10'}
         * @throws IllegalArgumentException if {@code dd} is not one of the four
         */
        public boolean isExhausted(String dd) {
            return cursor(dd).exhausted;
        }

        /**
         * Whether this session has been closed.
         *
         * @return {@code true} after {@link #close()}
         */
        public boolean isClosed() {
            return closed;
        }

        /**
         * Releases the session: marks every cursor closed and forgets every position.
         *
         * <p>Idempotent, so an explicit close inside a try-with-resources block is safe. It holds nothing
         * scarce - a cursor carries one record image and a count, never a connection or a result set - so
         * this is about the position it would otherwise keep, not about a resource leak.
         *
         * <p>It deliberately does <strong>not</strong> report a status. A COBOL run unit ending is not a
         * {@code CLOSE} statement, and manufacturing one here would put a status on a DD that no
         * {@code MOVE} ever touched.
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            for (Cursor cursor : cursors.values()) {
                cursor.release();
            }
            closed = true;
        }
    }

    /**
     * One file's open state, browse position and {@code FILE STATUS} - the per-session half of
     * {@code CBSTM03B}'s state.
     *
     * <p>Every operation here <strong>reports</strong> rather than throws, because a two-character
     * {@code FILE STATUS} is the only outcome {@code CBSTM03B}'s interface can express and because the
     * caller's guard chain is where the escalation belongs
     * ({@code app/cbl/CBSTM03A.CBL:736}, {@code :353-359}, {@code :379-386}, and
     * {@code :923}'s {@code PERFORM 9999-ABEND-PROGRAM}). Nothing in this class throws
     * {@code AbendException}, and that is also what keeps {@code FileStatus} free of a dependency on it.
     */
    private static final class Cursor {

        /** The dataset this cursor reads: its identity, geometry and access paths. */
        private final DatasetAccess access;

        /**
         * This file's two-byte {@code FILE STATUS} - one of the four independent
         * {@code 01 <dd>FILE-STATUS} items of {@code app/cbl/CBSTM03B.CBL:83-97}.
         */
        private String status;

        /** Whether {@code OPEN INPUT} has succeeded and {@code CLOSE} has not yet run. */
        private boolean opened;

        /**
         * The exact bytes of the record a sequential browse last returned, or {@code null} before the first
         * read.
         *
         * <p>The stored bytes rather than a re-encoding of anything decoded from them: those two differ for
         * any row that does not already hold exactly what a model would write - IBM037 maps {@code X'25'}
         * to a character that encodes back as {@code X'15'} - and a browse whose position is the stored
         * bytes must not advance past a value that differs from them.
         */
        private byte[] position;

        /** How many records this browse has returned so far. */
        private int returned;

        /** Whether this browse has run past its last record. */
        private boolean exhausted;

        /**
         * @param access the dataset to read
         */
        private Cursor(DatasetAccess access) {
            this.access = access;
            // No VALUE clause on the COBOL status areas, so nothing has been established yet. See
            // UNTOUCHED_STATUS for why this particular value and why the state is unreachable from
            // CBSTM03A.
            this.status = UNTOUCHED_STATUS;
            this.opened = false;
            this.position = null;
            this.returned = 0;
            this.exhausted = false;
        }

        /**
         * This file's current {@code FILE STATUS}.
         *
         * @return the two-character status
         */
        private String status() {
            return status;
        }

        /**
         * {@code OPEN INPUT <file>} - {@code app/cbl/CBSTM03B.CBL:136}, {@code :160}, {@code :184},
         * {@code :209}.
         *
         * <p>Positions at the start of the file and clears any previous pass, which is what re-opening a
         * closed file does. The dataset is described rather than read, so an unreachable dataset is
         * reported by the open - where {@code CBSTM03A.CBL:736} tests for it - rather than by the first
         * read.
         *
         * <p>Re-opening a file that is already open reports {@code '41'} and changes nothing: standard
         * COBOL for "an {@code OPEN} was attempted for a file already in the open mode". Reported, not
         * thrown, for the reason set out on this class.
         */
        private void openInput() {
            if (opened) {
                status = ALREADY_OPEN_STATUS;
                return;
            }
            try {
                access.probe();
            } catch (DataAccessException translated) {
                status = PERMANENT_ERROR_STATUS;
                LOG.error("Could not open " + access.ddName + " for input - "
                        + BackendDiagnostic.of(translated).describe() + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller, which "
                        + "app/cbl/CBSTM03A.CBL:736 tests before displaying and abending");
                return;
            } catch (IllegalStateException unusable) {
                // The relation exists but presents no record-image column, so there is nothing to read
                // from it. That is an open-time defect, and it is reported as one.
                status = PERMANENT_ERROR_STATUS;
                LOG.error("Could not open " + access.ddName + " for input: the configured dataset "
                        + "presents no usable record-image column - " + unusable.getMessage()
                        + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS));
                return;
            }
            opened = true;
            position = null;
            returned = 0;
            exhausted = false;
            status = FileStatus.OK;
        }

        /**
         * {@code CLOSE <file>} - {@code app/cbl/CBSTM03B.CBL:147}, {@code :171}, {@code :196},
         * {@code :221}.
         *
         * <p>Closing a file that is not open reports {@code '42'}: standard COBOL for "a {@code CLOSE} was
         * attempted for a file not in the open mode".
         */
        private void close() {
            if (!opened) {
                status = NOT_OPEN_STATUS;
                return;
            }
            opened = false;
            position = null;
            returned = 0;
            exhausted = false;
            status = FileStatus.OK;
        }

        /**
         * Releases the cursor when its session ends, without reporting a status.
         *
         * <p>Distinct from {@link #close()} on purpose: a run unit ending is not a {@code CLOSE}
         * statement, so no {@code FILE STATUS} is established and the last one observed is left intact for
         * anything still inspecting it.
         */
        private void release() {
            opened = false;
            position = null;
            exhausted = false;
        }

        /**
         * {@code READ <file> INTO LK-M03B-FLDT} on a sequential file -
         * {@code app/cbl/CBSTM03B.CBL:141} and {@code :165}.
         *
         * <p>Five reported outcomes, and every one of them is a status rather than an exception:
         * <ul>
         *   <li>{@code '00'} - a record. The {@value StatementGenerationJobB#FLDT_LENGTH}-byte area is
         *       returned with the record left-justified and the remainder spaces.</li>
         *   <li>{@code '10'} - {@code AT END}. <strong>The record area is returned unchanged</strong>,
         *       because COBOL's {@code AT END} does not disturb the {@code INTO} receiver, and the caller
         *       has already set it to spaces. Reported idempotently: once at end of file, every further
         *       read reports end of file again, which is faithful because
         *       {@code app/cbl/CBSTM03A.CBL:356} sets {@code END-OF-FILE} and the loop never resumes.</li>
         *   <li>{@code '47'} - the file is not open. Standard COBOL for a read against a file not open in
         *       input mode, which is exactly the condition, since every open here is {@code OPEN
         *       INPUT}.</li>
         *   <li>the permanent-error status - the backend refused the read, or a row is present but carries
         *       no record image. A record that exists and cannot be read is an I/O defect and emphatically
         *       not an end of file.</li>
         *   <li>the permanent-error status - the row is not exactly the copybook's declared width. Gate
         *       G19 enforced, but <em>as a status</em>: this class returns raw bytes to a caller that
         *       decodes them by absolute offset, so handing over a mis-sized record would displace every
         *       field it then reads. Throwing instead would bypass the caller's abend path, which is the
         *       one place the migration puts that decision.</li>
         * </ul>
         *
         * @param currentFldt the record area as it arrived, returned unchanged when no record is read
         * @return the record area after the read
         */
        private String readNext(String currentFldt) {
            if (!opened) {
                status = NOT_OPEN_FOR_READ_STATUS;
                return currentFldt;
            }
            if (exhausted) {
                // AT END, reported again. The pass is over and there is no saved position to resume from.
                status = FileStatus.END_OF_FILE;
                return currentFldt;
            }
            Row row;
            try {
                row = access.nextRow(position);
            } catch (DataAccessException translated) {
                status = PERMANENT_ERROR_STATUS;
                LOG.error("Could not read the next record of " + access.ddName + " - "
                        + BackendDiagnostic.of(translated).describe() + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than treating an "
                        + "unreachable dataset as one that has ended");
                return currentFldt;
            } catch (IllegalArgumentException unrepresentable) {
                // The row was fetched but its stored characters have no representation in the configured
                // dataset code page, so the record image could not be recovered as bytes at all. The
                // record exists and cannot be read: an I/O-level defect, reported as a status for the same
                // reason every other one is. See notReadable for why this is caught here and not wider.
                status = notReadable(access.ddName, "the next record of", unrepresentable);
                return currentFldt;
            }
            if (!row.present()) {
                exhausted = true;
                status = FileStatus.END_OF_FILE;
                return currentFldt;
            }
            String area = accept(row.image(), "the next record of");
            if (area == null) {
                return currentFldt;
            }
            // Advance by the bytes the backend gave, not by a re-encoding of them.
            position = row.image().clone();
            returned++;
            // The status is accept's to set, not this method's: a record whose length does not conform to
            // the file's fixed attributes was still read, and reports '04' rather than '00'. Assigning
            // FileStatus.OK here would erase that distinction on the very path CBSTM03A:L748 depends on.
            return area;
        }

        /**
         * {@code MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO <RECORD KEY>} then
         * {@code READ <file> INTO LK-M03B-FLDT} on a random file - {@code app/cbl/CBSTM03B.CBL:188-191}
         * and {@code :213-216}.
         *
         * <p>Outcomes: {@code '00'} with a record; {@code '23'} when no record carries that key, with the
         * record area unchanged, which is the {@code INVALID KEY} condition and a normal branch rather
         * than an error; {@code '47'} when the file is not open; and the permanent-error status for a
         * backend refusal, an absent record image or a mis-sized row.
         *
         * <p>There is <strong>no {@code '10'} outcome</strong> on this path, and the caller agrees: its
         * keyed-read arms at {@code app/cbl/CBSTM03A.CBL:379-386} and {@code :403-410} distinguish only
         * {@code '00'} from OTHER and declare no end-of-file arm at all.
         *
         * @param request     the request, whose {@link Request#usedKey()} supplies the key prefix
         * @param currentFldt the record area as it arrived, returned unchanged when no record is read
         * @return the record area after the read
         * @throws IllegalArgumentException if the request's key length is outside
         *                                  {@code 1..}{@value StatementGenerationJobB#KEY_LENGTH}, or if
         *                                  the receiver is numeric and the used key is not all digits.
         *                                  Both are caller-contract violations rather than I/O outcomes:
         *                                  the caller always derives the length from
         *                                  {@code LENGTH OF XREF-CUST-ID} or {@code LENGTH OF
         *                                  XREF-ACCT-ID}, and both of those are {@code PIC 9} fields
         */
        private String readByKey(Request request, String currentFldt) {
            if (!opened) {
                status = NOT_OPEN_FOR_READ_STATUS;
                return currentFldt;
            }
            // The 1-based-to-0-based reference modification, then the receiver's own MOVE rule. Both are
            // deliberate operations with named directions, never a Java assignment.
            String keyImage = access.moveIntoRecordKey(request.usedKey());
            List<byte[]> rows;
            try {
                rows = access.rowsByKey(keyImage);
            } catch (DataAccessException translated) {
                status = PERMANENT_ERROR_STATUS;
                LOG.error("Could not read " + access.ddName + " by key - "
                        + BackendDiagnostic.of(translated).describe() + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " rather than reporting a record that may exist as absent");
                return currentFldt;
            } catch (IllegalArgumentException unrepresentable) {
                // A matching row whose stored characters are not representable in the dataset code page.
                // The key move above has already completed, so this cannot be the caller's key being
                // refused - that propagates, deliberately, and is asserted to.
                status = notReadable(access.ddName, "a keyed record of", unrepresentable);
                return currentFldt;
            }
            if (rows == null) {
                // A template that yielded no result object has told us nothing, and nothing is not an
                // absent record.
                status = PERMANENT_ERROR_STATUS;
                LOG.error("The " + access.ddName + " keyed read yielded no result object at all; "
                        + "reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " rather than treating it as a key with no matching record");
                return currentFldt;
            }
            if (rows.isEmpty()) {
                // INVALID KEY. A normal branch; app/cbl/CBSTM03A.CBL takes it to its OTHER arm and abends,
                // but that is the caller's decision and not ours. Reported only once the absence has been
                // PROVED: every RECORD KEY this subprogram reads on lives INSIDE the record image
                // (FD-CUST-ID at app/cbl/CBSTM03B.CBL:46, FD-ACCT-ID at :52), so a row whose record-image
                // column holds nothing has no knowable key and the keyed LIKE cannot match it - SQL
                // evaluates every comparison against a null as UNKNOWN. Both statuses reach the same OTHER
                // arm in the caller, but they do not mean the same thing: '23' says the dataset does not
                // hold this customer or account, and saying that while an unreadable row sits in the
                // dataset reports a record that is present as absent.
                status = provenAbsenceStatus();
                return currentFldt;
            }
            if (rows.size() > 1) {
                // A base KSDS primary key is unique - RECORD KEY IS FD-CUST-ID (app/cbl/CBSTM03B.CBL:46)
                // and RECORD KEY IS FD-ACCT-ID (:52) - so more than one match cannot arise in the legacy
                // system and is an integrity defect in the backing relation.
                //
                // It is REPORTED, not absorbed. Returning row 0 with '00' would hand the caller one of
                // several records with no indication that a choice was made, and the choice would be
                // whichever row the backend happened to order first: CBSTM03A would then compose a
                // statement from an arbitrary customer or account and every downstream field would be
                // plausible and possibly wrong. There is no faithful answer to give, because the
                // condition the COBOL contract rules out has occurred; the honest outcome is the
                // permanent-error status, which the caller's WHEN OTHER arm already handles by abending
                // (app/cbl/CBSTM03A.CBL:L379-L386 and :L403-L410 distinguish only '00' from OTHER).
                //
                // The key itself is not logged: these datasets carry customer and account identifiers.
                status = PERMANENT_ERROR_STATUS;
                LOG.error("Could not read " + access.ddName + " by key: " + rows.size() + " rows match a "
                        + "single primary key, but its RECORD KEY (app/cbl/CBSTM03B.CBL:46, :52) is "
                        + "unique. Reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " rather than returning an arbitrary one of them as though it were the record: "
                        + "the caller composes a customer statement from whatever it is handed, so an "
                        + "arbitrary choice here would surface as plausible and wrong output. The backing "
                        + "relation needs a unique constraint on its key span");
                return currentFldt;
            }
            byte[] image = rows.get(0);
            String area = accept(image, "a keyed record of");
            if (area == null) {
                return currentFldt;
            }
            // As on the sequential path, the status belongs to accept: a keyed read of a record whose
            // length does not conform reports '04', and CBSTM03A's two keyed-read guards accept only
            // '00', so this is the status that decides whether the caller abends.
            return area;
        }

        /**
         * The status for a keyed read that matched no row: {@code '23'} once the absence is established,
         * and the permanent-error status while it is not.
         *
         * <h2>Why an absence has to be proved</h2>
         * <p>Both {@code RECORD KEY}s this subprogram reads on live <em>inside</em> the record image -
         * {@code FD-CUST-ID} is the leading nine bytes of the {@value #CUSTFILE_DD} record
         * ({@code app/cbl/CBSTM03B.CBL:46}) and {@code FD-ACCT-ID} the leading eleven of the
         * {@value #ACCTFILE_DD} record ({@code :52}). SQL evaluates every comparison against a null as
         * {@code UNKNOWN}, so the keyed {@code LIKE} cannot match a row whose record-image column holds
         * nothing, and such a row leaves the read with no matching row: on the face of it {@code '23'}.
         *
         * <p>{@code CBSTM03A} sends both {@code '23'} and the permanent-error status to the same
         * {@code WHEN OTHER} arm ({@code :379-386}, {@code :403-410}), so the caller's behaviour is
         * identical either way - but the two statuses do not <em>mean</em> the same thing, and it is the
         * meaning that is reproduced here. {@code '23'} asserts the dataset holds no such customer or
         * account; asserting that while an unreadable row sits in the dataset reports a record that is
         * present as absent, which is exactly what {@link #accept(byte[], String)} already refuses to do
         * for a row the read could see. Keeping them apart also keeps this class's own contract honest for
         * the four datasets it owns, rather than resting on one caller's arms happening to coincide.
         *
         * <p>The proof is one row-limited read against <em>this DD's own</em> dataset, on the not-found path
         * only. A read that found its record is untouched: a VSAM {@code READ} of a key that resolves does
         * not fail because another record in the cluster is damaged.
         *
         * @return {@link FileStatus#NOT_FOUND} when the absence is established, otherwise
         *         {@link StatementGenerationJobB#PERMANENT_ERROR_STATUS}
         */
        private String provenAbsenceStatus() {
            List<byte[]> unreadable;
            try {
                unreadable = access.rowsWithNoImage();
            } catch (DataAccessException translated) {
                // The probe established nothing, so the absence stays unproved. Reported on the arm a
                // refused read is reported on rather than as '23'.
                LOG.error("Could not establish that " + access.ddName + " holds no unreadable row before "
                        + "reporting a key as absent - " + BackendDiagnostic.of(translated).describe()
                        + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " rather than reporting a record that may exist as absent");
                return PERMANENT_ERROR_STATUS;
            }
            // No IllegalArgumentException arm, and deliberately none: the probe's predicate is
            // IS NULL over the record-image column, and RecordImageForm answers a null column with null
            // rather than decoding it - so the probe never reaches a stored character it could refuse. A
            // catch here would read as a condition that can occur and could never be exercised.
            if (unreadable == null) {
                // As on the keyed read: a template that yielded no result object has told us nothing, and
                // nothing does not establish an absence.
                LOG.error("The " + access.ddName + " unreadable-row probe yielded no result object at all, "
                        + "so a keyed read's INVALID KEY could not be established; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " rather than reporting a record that may exist as absent");
                return PERMANENT_ERROR_STATUS;
            }
            if (unreadable.isEmpty()) {
                // A genuine INVALID KEY: nothing matched the key and no row of this dataset is unreadable.
                return FileStatus.NOT_FOUND;
            }
            LOG.error("A keyed read of " + access.ddName + " matched no row, but the dataset holds a row "
                    + "with no record image at column position "
                    + DatasetRelation.RECORD_IMAGE_COLUMN_INDEX + " - and the RECORD KEY is part of that "
                    + "image, so that row's key cannot be known; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than reporting as absent a record that may well be present");
            return PERMANENT_ERROR_STATUS;
        }

        /**
         * Reports a stored row that cannot be recovered as bytes under the configured dataset code page.
         *
         * <p><strong>Why {@link IllegalArgumentException} is caught at all, and why only around the row
         * fetch.</strong> Reading a row means asking {@link RecordImageForm} to present it as the record's
         * bytes, and a stored character with no representation in the dataset code page makes that
         * impossible - the codec refuses rather than substituting a replacement byte, because substituting
         * one would corrupt a record addressed by absolute offset. That refusal is an
         * {@code IllegalArgumentException}, and it describes <em>stored data</em>, not a caller mistake, so
         * it belongs on the same reported-status path as an absent record image or a mis-sized row.
         *
         * <p>The catch is deliberately narrow in scope: it wraps only the fetch. The keyed path's
         * {@code MOVE} into the {@code RECORD KEY} runs <em>before</em> the fetch precisely so that a
         * caller supplying a non-numeric key for {@code FD-ACCT-ID PIC 9(11)}, or a key length outside
         * {@code 1..}{@value StatementGenerationJobB#KEY_LENGTH}, still propagates as the contract
         * violation it is instead of being flattened into a file status.
         *
         * @param ddName        the DD being read
         * @param attempt       what was being read, phrased to complete "Could not read ..."
         * @param unreadable    the codec's refusal
         * @return {@link StatementGenerationJobB#PERMANENT_ERROR_STATUS}
         */
        private static String notReadable(String ddName, String attempt,
                                          IllegalArgumentException unreadable) {
            LOG.error("Could not read " + attempt + " " + ddName + ": a stored character has no "
                    + "representation in the configured dataset code page, so the record image cannot be "
                    + "recovered as bytes - " + unreadable.getMessage() + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS));
            return PERMANENT_ERROR_STATUS;
        }

        /**
         * Validates a stored row, turns it into the {@value StatementGenerationJobB#FLDT_LENGTH}-byte
         * record area and <strong>sets the status the read reports</strong>, or answers {@code null}
         * having set a rejecting status.
         *
         * <p>Three outcomes, and the middle one is the whole reason this method owns the status rather
         * than leaving its caller to assign {@code '00'}:
         * <ul>
         *   <li><strong>{@code '00'}</strong> - the row is present and exactly its copybook's declared
         *       width. The record area is returned.</li>
         *   <li><strong>{@code '04'}</strong>, {@link FileStatus#RECORD_LENGTH_CONFLICT} - the row is
         *       present but its length does not conform to the file's fixed attributes. In COBOL that is
         *       a <em>successful</em> {@code READ} whose record was transferred anyway, so the record
         *       area <strong>is</strong> returned, filled under the {@code PIC X(1000)} receiver's own
         *       {@code MOVE} rule: left justified, space-padded when the row is short and truncated on
         *       the right when it is long. Reporting the conflict without the record, or collapsing it
         *       into a permanent error, would both make {@code '04'} unproducible - and
         *       {@code app/cbl/CBSTM03A.CBL} has ten distinct sites whose behaviour depends on it, nine
         *       accepting it ({@code IF WS-M03B-RC = '00' OR '04'}, including the first {@code TRNXFILE}
         *       read at {@code L748}) and three rejecting it (the loop read's {@code EVALUATE} at
         *       {@code L836-L847} and the two keyed reads). Every one of those arms is dead if this
         *       method cannot produce the status.</li>
         *   <li><strong>the permanent-error status</strong> - the row is present but carries no record
         *       image at all, or its stored bytes are not data in the configured dataset code page. In
         *       both cases the record exists and <em>cannot be read</em>, which is an I/O-level defect
         *       and not an end of file, and no record area is returned.</li>
         * </ul>
         *
         * <p><strong>Why a length conflict is no longer a refusal.</strong> It used to be, on the
         * reasoning that this subroutine hands raw bytes to a caller that decodes them by absolute offset
         * and a mis-sized record would displace every field. That reasoning describes a real hazard but
         * prescribes the wrong remedy: {@code '04'} is precisely COBOL's way of saying "here is the
         * record, and its length is not what the file declares", and the decision about what to do next
         * belongs to the caller - which is exactly where {@code CBSTM03A} puts it, accepting the status
         * at nine sites and abending at three. Gate G19 is still enforced, and more faithfully: the
         * conflict is reported rather than hidden, on the same path the COBOL reports it.
         *
         * @param image   the stored bytes, possibly {@code null}
         * @param attempt what was being read, phrased to complete "Could not read ..."
         * @return the record area, or {@code null} when the row was rejected
         */
        private String accept(byte[] image, String attempt) {
            if (image == null) {
                status = PERMANENT_ERROR_STATUS;
                LOG.error("Could not read " + attempt + " " + access.ddName + ": the row carries no "
                        + "record image at column position " + DatasetRelation.RECORD_IMAGE_COLUMN_INDEX
                        + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " rather than reporting a record that is present as absent");
                return null;
            }
            String area;
            try {
                area = access.intoRecordArea(image);
            } catch (IllegalStateException undecodable) {
                // A stored byte that is not a character in the configured code page. The record exists and
                // cannot be read, which is the same class of defect as an absent image.
                status = PERMANENT_ERROR_STATUS;
                LOG.error("Could not read " + attempt + " " + access.ddName + ": a stored byte is not "
                        + "valid data in the configured dataset code page - " + undecodable.getMessage()
                        + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS));
                return null;
            }
            if (image.length != access.recordLength) {
                // FILE STATUS '04'. The READ succeeded and the record was transferred; only its length
                // disagrees with the file's fixed attributes. The caller is told, and is given the record.
                status = FileStatus.RECORD_LENGTH_CONFLICT;
                LOG.warn("Read " + attempt + " " + access.ddName + " with a record-length conflict: the "
                        + "row is " + image.length + " byte(s) where its copybook declares "
                        + access.recordLength + ". Reporting file status "
                        + FileStatus.toStatusImage(FileStatus.RECORD_LENGTH_CONFLICT)
                        + " and returning the record area, which is what a COBOL READ does. A caller that "
                        + "decodes by absolute offset must treat this as fatal (gate G19); the nine "
                        + "app/cbl/CBSTM03A.CBL guards that accept '04' do not decode the area they "
                        + "accept it on, and the loop read that does decode it abends");
                return area;
            }
            status = FileStatus.OK;
            return area;
        }
    }
}

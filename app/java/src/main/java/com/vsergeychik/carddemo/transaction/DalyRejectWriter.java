package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.transaction.model.DalyTranRecord;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.util.Objects;

/**
 * Writes the {@code DALYREJS} rejected-transaction file: one fixed {@value #RECORD_LENGTH}-byte record
 * per rejected daily transaction, in the order the posting job rejects them, exactly as
 * {@code app/cbl/CBTRN02C.cbl} does.
 *
 * <p>This is the third of the module's three fixed-width output writers, and it is the only one whose
 * record is <em>two</em> things concatenated: the rejected transaction copied through verbatim, followed
 * by a trailer saying why it was rejected. The whole of its job is one COBOL sentence pair from
 * {@code 2500-WRITE-REJECT-REC} ({@code app/cbl/CBTRN02C.cbl:L446-L465}):
 *
 * <pre>
 *     MOVE DALYTRAN-RECORD       TO REJECT-TRAN-DATA
 *     MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER
 *     WRITE FD-REJS-RECORD FROM REJECT-RECORD
 * </pre>
 *
 * <h2>The 430-byte contract</h2>
 *
 * <p>From {@code app/jcl/POSTTRAN.jcl:L34-L38} - the {@code DALYREJS} DD of {@code STEP15}, which is
 * {@code EXEC PGM=CBTRN02C} ({@code L23}):
 *
 * <pre>
 *     //DALYREJS DD DISP=(NEW,CATLG,DELETE),
 *     //         UNIT=SYSDA,
 *     //         DCB=(RECFM=F,LRECL=430,BLKSIZE=0),
 *     //         SPACE=(CYL,(1,1),RLSE),
 *     //         DSN=AWS.M2.CARDDEMO.DALYREJS(+1)
 * </pre>
 *
 * <p>{@code RECFM=F}, {@code LRECL=430} - gate <strong>G20</strong>, and this class exists to make that
 * width structural rather than hoped for. Note {@code F} and not {@code FB}: the rejects file is fixed
 * <em>unblocked</em>, where its two sibling outputs {@code TRANREPT} and the statement pair are blocked.
 * The distinction changes nothing about record content and everything about whether a configuration
 * that declared the other one is describing this file, so it is cross-checked at construction rather
 * than left decorative.
 *
 * <p>The dataset is a generation data group: {@code app/jcl/DALYREJS.jcl:L21-L28} defines it with
 * {@code DEFINE GENERATIONDATAGROUP (NAME(AWS.M2.CARDDEMO.DALYREJS) LIMIT(5) SCRATCH)}, which is why
 * the DD names a relative generation {@code (+1)} and declares {@code DISP=(NEW,CATLG,DELETE)}. Each
 * run therefore produces a <strong>new generation</strong> and never extends the previous one, which is
 * exactly what {@code OPEN OUTPUT} at {@code L293} means and why {@link #openOutput()} is named for
 * output rather than append.
 *
 * <h2>Why 430 is 350 + 80, and 80 is 4 + 76</h2>
 *
 * <p>{@value #RECORD_LENGTH} has <strong>no copybook</strong>. Unlike every other record in this
 * module it is declared inline, three times over, and all three declarations must agree:
 *
 * <table border="1">
 *   <caption>The three inline declarations of the reject record and what each contributes</caption>
 *   <tr><th>Where</th><th>Declaration</th><th>Contributes</th></tr>
 *   <tr><td>{@code app/cbl/CBTRN02C.cbl:L81-L84}<br>the {@code FD}, written to</td>
 *       <td>{@code 01 FD-REJS-RECORD.}<br>
 *           {@code   05 FD-REJECT-RECORD      PIC X(350).}<br>
 *           {@code   05 FD-VALIDATION-TRAILER PIC X(80).}</td>
 *       <td>350 + 80 = {@value #RECORD_LENGTH}</td></tr>
 *   <tr><td>{@code app/cbl/CBTRN02C.cbl:L176-L178}<br>{@code WORKING-STORAGE}, written from</td>
 *       <td>{@code 01 REJECT-RECORD.}<br>
 *           {@code   05 REJECT-TRAN-DATA   PIC X(350).}<br>
 *           {@code   05 VALIDATION-TRAILER PIC X(80).}</td>
 *       <td>the same 350 + 80</td></tr>
 *   <tr><td>{@code app/cbl/CBTRN02C.cbl:L180-L182}<br>the trailer's own structure</td>
 *       <td>{@code 01 WS-VALIDATION-TRAILER.}<br>
 *           {@code   05 WS-VALIDATION-FAIL-REASON      PIC 9(04).}<br>
 *           {@code   05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76).}</td>
 *       <td>4 + 76 = 80</td></tr>
 * </table>
 *
 * <p>So the emitted record decomposes as <strong>350 + 4 + 76</strong>, with no gap and no overlap, and
 * that is not merely asserted here - it is the geometry of {@link #FD_REJS_RECORD_LAYOUT}, which
 * {@link RecordLayout} self-checks at class-initialisation time and rejects outright if the storage
 * spans leave a hole, overlap, or fail to sum to {@value #RECORD_LENGTH} (gates <strong>G19</strong>,
 * <strong>G20</strong>, <strong>G21</strong>). The 80-byte {@code FD-VALIDATION-TRAILER} the {@code FD}
 * itself declares is carried alongside as a {@code REDEFINES} overlay, so both views of those bytes -
 * the file description's single alphanumeric span and the working-storage group's two items - are
 * available without either one being able to displace the other.
 *
 * <h2>The first 350 bytes are copied, never rebuilt</h2>
 *
 * <p>{@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA} ({@code L447}) is a group-to-group alphanumeric
 * move between items of <strong>identical</strong> width - {@code CVTRA06Y}'s
 * {@code 01 DALYTRAN-RECORD} is 350 bytes, and so is the receiver - so it neither pads nor truncates
 * and the rejected transaction's bytes cross into the reject record verbatim. Verbatim includes two
 * things a field-by-field reconstruction would put at risk:
 *
 * <ul>
 *   <li><strong>The trailing {@code FILLER PIC X(20)}</strong> that {@code app/cpy/CVTRA06Y.cpy:L18}
 *       declares. Drop it and the record is 330 + 80 = 410 bytes, every trailer byte lands 20
 *       positions early, and nothing about the file looks wrong until it is measured
 *       (gate <strong>G21</strong>).</li>
 *   <li><strong>{@code DALYTRAN-AMT}'s sign overpunch.</strong> The amount is
 *       {@code PIC S9(09)V99} ({@code CVTRA06Y:L10}), a zoned {@code DISPLAY} field whose sign lives in
 *       the high-order half of its trailing byte. Decoding it to a number and re-encoding it would
 *       normalise a negative zero, and the rejects file is compared byte for byte.</li>
 * </ul>
 *
 * <p>That is why {@link RejectsFile#moveToRejectTranData(byte[])} takes an <em>already-encoded</em>
 * 350-byte image, and why the {@link DalyTranRecord} overload reaches it through
 * {@link DalyTranRecord#encode(Charset)} - whose own contract names it the only correct source for this
 * copy - rather than through any accessor that returns a decoded value.
 *
 * <h2>The trailer's two padding directions are opposite, and both are deliberate</h2>
 *
 * <p>{@code MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER} ({@code L448}) is likewise an
 * equal-width group move, so the trailer's 80 bytes are whatever its two items hold. Those two items
 * are filled by rules that point in opposite directions, which is the single most reversible mistake in
 * this file:
 *
 * <ul>
 *   <li>{@code WS-VALIDATION-FAIL-REASON PIC 9(04)} is <strong>numeric</strong>: zero-filled on the
 *       <strong>left</strong>. Reason {@value #REASON_OVERLIMIT_TRANSACTION} is stored as
 *       {@code "0102"}, not {@code "102 "}.</li>
 *   <li>{@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} is <strong>alphanumeric</strong>:
 *       space-padded on the <strong>right</strong>, and truncated on the right too if a description
 *       ever exceeded 76 characters. All five in the source are far shorter, the longest being the
 *       42-character {@value #DESC_TRANSACTION_AFTER_EXPIRATION}.</li>
 * </ul>
 *
 * <p>Both directions come from named {@link FixedWidthCodec} operations rather than from a substring or
 * a format string written at the call site, so the choice is made once and is reviewable
 * (practice B11, AAP section 0.3.7).
 *
 * <h2>The five reject reasons</h2>
 *
 * <p>Every code and every description below is transcribed character for character from
 * {@code app/cbl/CBTRN02C.cbl}. Which one applies is the <em>posting job's</em> decision - the
 * four-stage Card / Account / Credit-Limit / Expiration cascade lives in {@code 1500-VALIDATE-TRAN} and
 * its two subordinates - but the codes and their texts are the reject record's content, so they are
 * declared here, where the trailer is rendered.
 *
 * <table border="1">
 *   <caption>Reject reason codes, their descriptions and where each is set</caption>
 *   <tr><th>Code</th><th>Description</th><th>Set at</th><th>Reaches this writer</th></tr>
 *   <tr><td>{@value #REASON_NONE}</td><td><em>spaces</em></td>
 *       <td>{@code L208-L209}, before each validation</td>
 *       <td>No - {@code L211} posts instead of rejecting</td></tr>
 *   <tr><td>{@value #REASON_INVALID_CARD_NUMBER}</td>
 *       <td>{@value #DESC_INVALID_CARD_NUMBER}</td>
 *       <td>{@code L385-L387}, {@code 1500-A-LOOKUP-XREF} on {@code INVALID KEY}</td>
 *       <td>Yes</td></tr>
 *   <tr><td>{@value #REASON_ACCOUNT_RECORD_NOT_FOUND}</td>
 *       <td>{@value #DESC_ACCOUNT_RECORD_NOT_FOUND}</td>
 *       <td>{@code L397-L399}, {@code 1500-B-LOOKUP-ACCT} on {@code INVALID KEY}</td>
 *       <td>Yes</td></tr>
 *   <tr><td>{@value #REASON_OVERLIMIT_TRANSACTION}</td>
 *       <td>{@value #DESC_OVERLIMIT_TRANSACTION}</td>
 *       <td>{@code L410-L412}, when {@code ACCT-CREDIT-LIMIT} is below {@code WS-TEMP-BAL}</td>
 *       <td>Yes</td></tr>
 *   <tr><td>{@value #REASON_TRANSACTION_AFTER_EXPIRATION}</td>
 *       <td>{@value #DESC_TRANSACTION_AFTER_EXPIRATION}</td>
 *       <td>{@code L417-L419}, when {@code ACCT-EXPIRAION-DATE} precedes
 *           {@code DALYTRAN-ORIG-TS (1:10)}</td>
 *       <td>Yes</td></tr>
 *   <tr><td>{@value #REASON_ACCOUNT_NOT_FOUND_ON_REWRITE}</td>
 *       <td>{@value #DESC_ACCOUNT_RECORD_NOT_FOUND}</td>
 *       <td>{@code L556-L558}, {@code 2800-UPDATE-ACCOUNT-REC} on {@code REWRITE ... INVALID KEY}</td>
 *       <td><strong>No - unreachable, see below</strong></td></tr>
 * </table>
 *
 * <h2>Two verified legacy defects, preserved rather than corrected</h2>
 *
 * <p>Practice <strong>B5</strong> is explicit that behaviour is preserved including its defects, and
 * two of them touch this file. Neither is repaired here and neither should be repaired elsewhere.
 *
 * <ul>
 *   <li><strong>Reason {@value #REASON_ACCOUNT_NOT_FOUND_ON_REWRITE} can never be written.</strong> It
 *       is set inside {@code 2800-UPDATE-ACCOUNT-REC}, which {@code 2000-POST-TRANSACTION} performs at
 *       {@code L441} - and {@code 2000-POST-TRANSACTION} only runs on the {@code L211} arm where
 *       {@code WS-VALIDATION-FAIL-REASON} has <em>already</em> tested as zero. By the time the code is
 *       assigned, the decision to post rather than reject is made, {@code WS-REJECT-COUNT} has not been
 *       incremented, and {@code 2500-WRITE-REJECT-REC} will not be performed for this transaction. A
 *       failed account rewrite is therefore recorded nowhere at all: no reject record, no non-zero
 *       return code from that path, nothing. The constant is declared for completeness of the
 *       transcription and {@link #descriptionOfReason(int)} resolves it, so a caller that does supply
 *       it gets a correct record rather than a surprise; what must not happen is a change to the
 *       posting flow that makes it reachable, because that would invent a behaviour the mainframe does
 *       not have.</li>
 *   <li><strong>The close paragraph reports the wrong file's status.</strong>
 *       {@code 9300-DALYREJS-CLOSE} ({@code L637-L653}) tests {@code DALYREJS-STATUS} correctly at
 *       {@code L640} but then, on the failing arm, performs {@code MOVE XREFFILE-STATUS TO IO-STATUS}
 *       at {@code L649} - so the {@code 'ERROR CLOSING DAILY REJECTS FILE'} message at {@code L648} is
 *       followed by the <em>cross-reference</em> file's status rather than the rejects file's. All five
 *       sibling close paragraphs ({@code 9000}, {@code 9100}, {@code 9200}, {@code 9400}, {@code 9500})
 *       move their own status, so this is a transcription slip in the original. It lives in the guard
 *       chain, which belongs to the posting job, and it is recorded here so that whoever writes that
 *       ladder reproduces it deliberately instead of silently correcting it. What this class returns
 *       from {@link RejectsFile#closeOutput()} is unaffected: the outcome is the rejects file's own,
 *       because {@code L640} tests the right thing.</li>
 * </ul>
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <ul>
 *   <li><strong>It does not abend.</strong> Nothing here throws
 *       {@code com.vsergeychik.carddemo.common.AbendException}. All three of the COBOL paragraphs this
 *       class serves are guard chains that set {@code APPL-RESULT} to 8, then to 0 on
 *       {@code DALYREJS-STATUS = '00'} or 12 otherwise, and only then decide whether to display and
 *       abend. Those decisions - the {@code 'ERROR OPENING DALY REJECTS FILE'} text at {@code L302},
 *       the {@code 'ERROR WRITING TO REJECTS FILE'} text at {@code L460}, the
 *       {@code 'ERROR CLOSING DAILY REJECTS FILE'} text at {@code L648}, the
 *       {@code 9910-DISPLAY-IO-STATUS} rendering at {@code L714-L727} and the
 *       {@code 9999-ABEND-PROGRAM} call to {@code CEE3ABD} at {@code L707-L711} - all belong to
 *       {@code TransactionValidationJob}. This class reports a {@link FileStatus.Outcome} from the open
 *       ({@link RejectsFile#openOutcome()}), the write ({@link RejectsFile#writeRejectRec()}) and the
 *       close ({@link RejectsFile#closeOutput()}), and decides none of them.</li>
 *   <li><strong>It does not count rejects.</strong> {@code ADD 1 TO WS-REJECT-COUNT} happens at
 *       {@code L214}, in the mainline, <em>before</em> {@code 2500-WRITE-REJECT-REC} is performed, and
 *       it is what drives {@code IF WS-REJECT-COUNT &gt; 0 MOVE 4 TO RETURN-CODE} at
 *       {@code L229-L231}. A counter kept here would be incremented after the write instead of before
 *       it and would diverge the moment a write failed. {@link RejectsFile#recordsWritten()} is a
 *       diagnostic of how far a run got, not that counter.</li>
 *   <li><strong>It does not choose the reason.</strong> The validation cascade is the job's.
 *       One consequence is worth flagging because it looks like a bug and is not: {@code L407-L420}
 *       tests the credit limit and the expiry in two <em>independent</em> {@code IF} statements rather
 *       than an {@code ELSE} chain, so a transaction that is over limit <em>and</em> past expiry ends
 *       up carrying {@value #REASON_TRANSACTION_AFTER_EXPIRATION}, the second assignment having
 *       overwritten the first. Reproduce that ordering in the job; this writer records whichever code
 *       it is handed.</li>
 *   <li><strong>It performs no arithmetic and formats no number.</strong> The reject reason is a
 *       scale-free {@code PIC 9(04)} integer and is carried as an {@code int}. There is no
 *       {@code BigDecimal} in this file and - as everywhere in this module - no {@code double} and no
 *       {@code float} (gate <strong>G22</strong>).</li>
 *   <li><strong>It introduces no schema.</strong> No data-definition statement, no entity mapping, no
 *       version column, no index (gate <strong>G44</strong>). The dataset is addressed by the name
 *       configuration supplies, so no {@code AWS.M2.CARDDEMO} literal appears as a value anywhere in
 *       this file (gate <strong>G46</strong>) - the JCL excerpts above are quotations inside comments,
 *       which is provenance rather than configuration.</li>
 * </ul>
 *
 * <h2>Write order is part of the answer</h2>
 *
 * <p>Records are handed to the sink one at a time, immediately, in the order the caller writes them.
 * Nothing is buffered for reordering, nothing is coalesced, nothing is parallelised. The rejects file
 * is produced by a single sequential pass over {@code DALYTRAN} ({@code L202-L219}), so its record
 * order <em>is</em> the input's order and forms part of the parity fingerprint; a reordering buffer
 * would not be an optimisation but a defect. No performance tuning is applied anywhere in this class
 * for the same reason - no performance objective is stated for this migration and several jobs depend
 * on strict record ordering (AAP section 0.8.6).
 *
 * <h2>Thread safety, state and testability</h2>
 *
 * <p>The bean itself is immutable and therefore a safe singleton: it holds the template, the codec, the
 * resolved binding and the resolved dataset, and nothing else. Every piece of mutable state - the
 * {@value #RECORD_LENGTH}-byte {@code FD-REJS-RECORD} area, the open flag and the record count - lives
 * on the per-execution {@link RejectsFile} handle that {@link #openOutput()} returns, so two concurrent
 * posting runs cannot overwrite each other's record area and no test outcome depends on what ran before
 * it. Nothing {@code static} here is mutable (practice <strong>B9</strong>, gate <strong>G53</strong>).
 * A handle is not thread-safe, exactly as a COBOL record area is not, and belongs to the step or test
 * that opened it.
 *
 * <p>{@link #openOutput(RecordSink)} is the seam that makes all of this assertable with no database, no
 * filesystem, no application context and no {@code JobLauncher} (practice <strong>B10</strong>, gate
 * <strong>G51</strong>): a caller supplies a collector and reads the emitted bytes back directly.
 *
 * <h2>What the posting job must call, and when</h2>
 *
 * <p>{@code app/jcl/POSTTRAN.jcl:L34-L38} declares {@code DISP=(NEW,CATLG,DELETE)}, which is
 * <strong>three</strong> dispositions rather than two, and a caller has to honour all three:
 * <ul>
 *   <li>{@code NEW} - {@link #openOutput()} clears the destination, so a run writes into an empty
 *       generation. Nothing further is required of the caller.</li>
 *   <li>{@code CATLG} - the <em>normal</em> disposition. {@link RejectsFile#closeOutput()} leaves the
 *       rejects catalogued, which is what a run that reached {@code GOBACK} must do.</li>
 *   <li>{@code DELETE} - the <em>abnormal</em> disposition, and the one a caller can forget.
 *       {@link RejectsFile#discardGeneration()} must be called - after the close, and
 *       <strong>only</strong> when the step is ending abnormally - so an abended run leaves no
 *       generation at all. It is idempotent and never throws, so it is safe in a {@code finally}, and
 *       it must be applied in a boundary of its own
 *       ({@code DatasetUnitOfWork.persistDisposition}) or the failure that triggered it would undo
 *       it.</li>
 * </ul>
 *
 * <p>A close alone is not enough and a transaction rollback is not a substitute: each reject is durable
 * as it is written, so the third outcome - no dataset at all - has to be produced explicitly.
 * {@code AccountInterestCalcJob.releaseAbnormally()} and {@code TransactionReportJob} show the same
 * three-disposition handling for their own outputs.
 *
 * <h2>Provenance of the expectations - residual risk R-E</h2>
 *
 * <p>The legacy COBOL <strong>cannot be executed in this environment</strong>: there is no z/OS
 * runtime, the available compiler has indexed file support disabled, no Language Environment
 * {@code CEE*} services exist and no CICS emulator is present. Every width, offset, code and message
 * above is therefore <em>statically derived</em> - read out of {@code app/cbl/CBTRN02C.cbl},
 * {@code app/cpy/CVTRA06Y.cpy}, {@code app/jcl/POSTTRAN.jcl} and {@code app/jcl/DALYREJS.jcl} - rather
 * than captured from a live run, and each carries a dedicated assertion so a misreading surfaces as a
 * failing test rather than as a wrong byte in a rejects file.
 *
 * <p>Risk <strong>R-E</strong> applies equally and is recorded rather than absorbed (practice
 * <strong>B12</strong>): indexed VSAM has no standard published JDBC driver, so this module pins none
 * and the site-specific driver is a deployment-time input. <strong>Production connectivity cannot be
 * exercised here.</strong> The default sink is consequently the one part of this class no test can
 * prove end to end; it is kept as thin as possible for that reason, and everything above it is proven
 * through {@link #openOutput(RecordSink)}.
 *
 * @see DalyTranRecord
 * @see FileStatus
 * @see FixedWidthCodec
 */
@Component
public final class DalyRejectWriter {

    // =================================================================================================
    // The dataset contract. app/jcl/POSTTRAN.jcl:L34-L38 and app/jcl/DALYREJS.jcl:L21-L28.
    // =================================================================================================

    /**
     * The DD name this writer resolves from {@code carddemo.datasets}, verbatim from
     * {@code SELECT DALYREJS-FILE ASSIGN TO DALYREJS} at {@code app/cbl/CBTRN02C.cbl:L46}.
     *
     * <p>The DD name, not the dataset name: the dataset name lives only in configuration, so it can
     * differ per deployment without a recompile and never appears in Java (gate <strong>G46</strong>).
     */
    public static final String DD_NAME = "DALYREJS";

    /**
     * The fixed record width in bytes: {@code 430}.
     *
     * <p>Declared by {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} at {@code app/jcl/POSTTRAN.jcl:L36} and,
     * independently, by the {@code FD} at {@code app/cbl/CBTRN02C.cbl:L81-L84} as
     * {@code PIC X(350)} followed by {@code PIC X(80)}. Gate <strong>G20</strong>.
     *
     * <p>Unusually for this module the width has no copybook behind it - the layout is inline in the
     * program - which is precisely why both declarations are cited and why
     * {@link #FD_REJS_RECORD_LAYOUT} recomputes the sum rather than trusting this constant.
     */
    public static final int RECORD_LENGTH = 430;

    /**
     * The record format the JCL declares: {@code F}, fixed <em>unblocked</em>.
     *
     * <p>Checked against configuration at construction because it is the one geometry attribute that
     * would otherwise be decorative, and because it differs from both sibling outputs:
     * {@code TRANREPT} is {@code FB} and so is the statement pair. {@code F} is what makes "every
     * record is exactly {@value #RECORD_LENGTH} bytes" true, and a binding that declared a variable
     * format would leave every other number in this class unchanged while quietly describing a
     * different file.
     */
    public static final String RECORD_FORMAT = "F";

    /**
     * The block size the JCL declares: {@code BLKSIZE=0}, which asks the system to determine it.
     *
     * <p>Recorded for completeness of the transcription. It has no effect on record content and this
     * writer neither blocks nor buffers, since a rendered record is handed to the sink immediately.
     */
    public static final int BLOCK_SIZE = 0;

    // =================================================================================================
    // The record geometry. 430 == 350 + 4 + 76, with the FD's own 80-byte trailer view alongside.
    // app/cbl/CBTRN02C.cbl:L81-L84, L176-L178 and L180-L182.
    // =================================================================================================

    /**
     * The name of the {@code FD} record area: {@code FD-REJS-RECORD}
     * ({@code app/cbl/CBTRN02C.cbl:L82}).
     *
     * <p>Held as a constant because it is the subject named in every diagnostic this class raises, and a
     * diagnostic that named the Java field instead would send a reader looking for something the
     * copybook and the program do not contain.
     */
    public static final String FD_REJS_RECORD = "FD-REJS-RECORD";

    /**
     * The name of the first span, the copied transaction: {@code FD-REJECT-RECORD}
     * ({@code app/cbl/CBTRN02C.cbl:L83}).
     *
     * <p>The {@code FD}'s name is used rather than {@code WORKING-STORAGE}'s
     * {@code REJECT-TRAN-DATA} ({@code L177}) because this layout describes the bytes as
     * <em>written</em>. The two are the same 350 bytes and the {@code WRITE ... FROM} at {@code L451}
     * copies one to the other; naming the destination is what makes a byte-offset diagnostic point at
     * the file.
     */
    public static final String FD_REJECT_RECORD = "FD-REJECT-RECORD";

    /**
     * The name of the {@code FD}'s single 80-byte trailer span: {@code FD-VALIDATION-TRAILER}
     * ({@code app/cbl/CBTRN02C.cbl:L84}).
     *
     * <p>Present as a {@code REDEFINES} overlay over the reason and description spans, because the
     * {@code FD} genuinely declares those 80 bytes as one alphanumeric item while
     * {@code WS-VALIDATION-TRAILER} ({@code L180-L182}) genuinely declares them as two. Both views are
     * real and the {@code MOVE} at {@code L448} is what joins them.
     */
    public static final String FD_VALIDATION_TRAILER = "FD-VALIDATION-TRAILER";

    /**
     * The name of the trailer's numeric item: {@code WS-VALIDATION-FAIL-REASON}
     * ({@code app/cbl/CBTRN02C.cbl:L181}).
     */
    public static final String WS_VALIDATION_FAIL_REASON = "WS-VALIDATION-FAIL-REASON";

    /**
     * The name of the trailer's alphanumeric item: {@code WS-VALIDATION-FAIL-REASON-DESC}
     * ({@code app/cbl/CBTRN02C.cbl:L182}).
     */
    public static final String WS_VALIDATION_FAIL_REASON_DESC = "WS-VALIDATION-FAIL-REASON-DESC";

    /** Offset of the copied transaction within the reject record: {@code 0}. */
    public static final int FD_REJECT_RECORD_OFFSET = 0;

    /**
     * Width of the copied transaction: {@code 350}, from {@code PIC X(350)} at
     * {@code app/cbl/CBTRN02C.cbl:L83}.
     *
     * <p>Identical to {@link DalyTranRecord#RECORD_LENGTH}, which is what makes {@code L447} a
     * pad-free, truncate-free group move. The two are cross-checked at class-initialisation time by
     * {@link #FD_REJS_RECORD_LAYOUT}'s companion check below, so a future change to either side cannot
     * silently shear the copy.
     */
    public static final int FD_REJECT_RECORD_LENGTH = 350;

    /** Offset of the validation trailer within the reject record: {@code 350}. */
    public static final int VALIDATION_TRAILER_OFFSET = FD_REJECT_RECORD_LENGTH;

    /**
     * Width of the validation trailer: {@code 80}, from {@code PIC X(80)} at
     * {@code app/cbl/CBTRN02C.cbl:L84}, and equally the sum {@code 4 + 76} of the two items
     * {@code WS-VALIDATION-TRAILER} declares at {@code L180-L182}.
     */
    public static final int VALIDATION_TRAILER_LENGTH = 80;

    /** Offset of the reason code within the reject record: {@code 350}. */
    public static final int WS_VALIDATION_FAIL_REASON_OFFSET = VALIDATION_TRAILER_OFFSET;

    /**
     * Digit count of the reason code: {@code 4}, from {@code PIC 9(04)} at
     * {@code app/cbl/CBTRN02C.cbl:L181}.
     *
     * <p>Four digits, zero-filled on the left, which is why {@value #REASON_OVERLIMIT_TRANSACTION}
     * occupies the file as {@code "0102"}.
     */
    public static final int WS_VALIDATION_FAIL_REASON_LENGTH = 4;

    /** Offset of the reason description within the reject record: {@code 354}. */
    public static final int WS_VALIDATION_FAIL_REASON_DESC_OFFSET =
            WS_VALIDATION_FAIL_REASON_OFFSET + WS_VALIDATION_FAIL_REASON_LENGTH;

    /**
     * Width of the reason description: {@code 76}, from {@code PIC X(76)} at
     * {@code app/cbl/CBTRN02C.cbl:L182}.
     *
     * <p>Space-padded on the right, and right-truncated if a description ever exceeded it - the
     * opposite direction from the reason code immediately before it.
     */
    public static final int WS_VALIDATION_FAIL_REASON_DESC_LENGTH = 76;

    /**
     * The layout of {@code FD-REJS-RECORD}: the copied transaction, then the reason, then the
     * description, then the {@code FD}'s own 80-byte view of the last two as a {@code REDEFINES}
     * overlay.
     *
     * <p>This declaration is the load-bearing assertion of the whole class. {@link RecordLayout}
     * self-checks its geometry when this field initialises and refuses to construct if the storage
     * spans leave a gap, overlap one another, repeat a referable name, or fail to sum to exactly
     * {@value #RECORD_LENGTH}. So {@code 350 + 4 + 76 == 430} is <em>structural</em> here rather than
     * merely asserted somewhere, and a mistranscribed offset fails at class initialisation with the
     * offending descriptor named, not silently at byte 354 of a production file (gates
     * <strong>G19</strong>, <strong>G20</strong>, <strong>G21</strong>).
     *
     * <p>The overlay is declared last because an overlay may only redefine storage already declared
     * ahead of it. It contributes nothing to the record length and is never initialised separately,
     * which is exactly right: {@code FD-VALIDATION-TRAILER} is a second name for bytes 350 to 429, not
     * 80 further bytes.
     *
     * <p>{@code static final} and deeply immutable: {@link RecordLayout} is a record that copies its
     * span list defensively and {@link FieldSpan} is a record of primitives and strings, so this
     * introduces no shared mutable state (practice <strong>B9</strong>, gate <strong>G53</strong>).
     * Only the record <em>area</em> is per-handle.
     */
    public static final RecordLayout FD_REJS_RECORD_LAYOUT = RecordLayout.of(
            RECORD_LENGTH,
            FieldSpan.alphanumeric(FD_REJECT_RECORD, FD_REJECT_RECORD_OFFSET,
                    FD_REJECT_RECORD_LENGTH),
            FieldSpan.unsignedNumeric(WS_VALIDATION_FAIL_REASON, WS_VALIDATION_FAIL_REASON_OFFSET,
                    WS_VALIDATION_FAIL_REASON_LENGTH),
            FieldSpan.alphanumeric(WS_VALIDATION_FAIL_REASON_DESC,
                    WS_VALIDATION_FAIL_REASON_DESC_OFFSET, WS_VALIDATION_FAIL_REASON_DESC_LENGTH),
            FieldSpan.redefining(FD_VALIDATION_TRAILER, VALIDATION_TRAILER_OFFSET,
                    VALIDATION_TRAILER_LENGTH, PictureKind.ALPHANUMERIC));

    static {
        // The copied span and the record it copies must be the same width, or L447 stops being the
        // pad-free group move the COBOL relies on. Checked here rather than trusted, because the two
        // numbers are declared in different files - CVTRA06Y for one, CBTRN02C's FD for the other - and
        // nothing but this line would notice them drifting apart.
        if (FD_REJECT_RECORD_LENGTH != DalyTranRecord.RECORD_LENGTH) {
            throw new IllegalStateException("FD-REJECT-RECORD is declared PIC X("
                    + FD_REJECT_RECORD_LENGTH + ") at app/cbl/CBTRN02C.cbl:L83 but a DALYTRAN-RECORD "
                    + "is " + DalyTranRecord.RECORD_LENGTH + " bytes (app/cpy/CVTRA06Y.cpy). "
                    + "MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA at L447 is a group move between items "
                    + "of identical width and pads and truncates nothing, so these two must agree.");
        }
    }

    /** Where a non-{@code OK} outcome that cannot be returned is reported. */
    private static final Log LOG = LogFactory.getLog(DalyRejectWriter.class);

    // =================================================================================================
    // The reject reasons. Codes and texts transcribed character for character from
    // app/cbl/CBTRN02C.cbl; which one applies is the posting job's decision, not this writer's.
    // =================================================================================================

    /**
     * The reason code meaning "not rejected": {@code 0}.
     *
     * <p>{@code MOVE 0 TO WS-VALIDATION-FAIL-REASON} at {@code app/cbl/CBTRN02C.cbl:L208} resets the
     * code before each transaction is validated, and {@code IF WS-VALIDATION-FAIL-REASON = 0} at
     * {@code L211} is the test that sends a transaction to be posted rather than rejected. So a record
     * carrying this code never reaches the rejects file in the legacy flow.
     *
     * <p>It is nevertheless accepted by {@link RejectsFile#moveToValidationTrailer(int, String)} and
     * renders as {@code "0000"}, because it is a perfectly legal {@code PIC 9(04)} value and is the
     * trailer's own documented initial state. Refusing it here would be inventing a rule the COBOL does
     * not have.
     */
    public static final int REASON_NONE = 0;

    /**
     * Reason {@code 100} - the card number is not in the cross-reference file.
     *
     * <p>Set by {@code 1500-A-LOOKUP-XREF} on the {@code INVALID KEY} arm of its
     * {@code READ XREF-FILE}, at {@code app/cbl/CBTRN02C.cbl:L385-L387}. This is the first stage of the
     * validation cascade and the only one that can fail before an account is even identified.
     */
    public static final int REASON_INVALID_CARD_NUMBER = 100;

    /**
     * Reason {@code 101} - the cross-reference resolved, but its account is not in the account file.
     *
     * <p>Set by {@code 1500-B-LOOKUP-ACCT} on the {@code INVALID KEY} arm of its
     * {@code READ ACCOUNT-FILE}, at {@code app/cbl/CBTRN02C.cbl:L397-L399}.
     */
    public static final int REASON_ACCOUNT_RECORD_NOT_FOUND = 101;

    /**
     * Reason {@code 102} - posting the transaction would exceed the account's credit limit.
     *
     * <p>Set at {@code app/cbl/CBTRN02C.cbl:L410-L412}, on the {@code ELSE} of
     * {@code IF ACCT-CREDIT-LIMIT &gt;= WS-TEMP-BAL} ({@code L407}), where {@code WS-TEMP-BAL} is
     * {@code ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT} ({@code L403-L405}).
     *
     * <p>This is the code whose four-digit rendering, {@code "0102"}, is the worked example of the
     * left-zero-fill rule throughout this file.
     */
    public static final int REASON_OVERLIMIT_TRANSACTION = 102;

    /**
     * Reason {@code 103} - the transaction was originated after the account expired.
     *
     * <p>Set at {@code app/cbl/CBTRN02C.cbl:L417-L419}, on the {@code ELSE} of
     * {@code IF ACCT-EXPIRAION-DATE &gt;= DALYTRAN-ORIG-TS (1:10)} ({@code L414}) - note the
     * copybook's misspelling of {@code EXPIRAION}, which is preserved throughout this module because
     * field names are part of the contract.
     *
     * <p>Because {@code L414} is a separate {@code IF} rather than the {@code ELSE} of the credit-limit
     * test, this code <strong>overwrites</strong> {@value #REASON_OVERLIMIT_TRANSACTION} when a
     * transaction is both over limit and past expiry. That ordering belongs to the job; it is noted
     * here so the code set is not read as mutually exclusive.
     */
    public static final int REASON_TRANSACTION_AFTER_EXPIRATION = 103;

    /**
     * Reason {@code 109} - the account record could not be rewritten while posting.
     *
     * <p>Set at {@code app/cbl/CBTRN02C.cbl:L556-L558}, on the {@code INVALID KEY} arm of the
     * {@code REWRITE FD-ACCTFILE-REC} in {@code 2800-UPDATE-ACCOUNT-REC}.
     *
     * <p><strong>Unreachable through this writer, and deliberately left so</strong> (practice
     * <strong>B5</strong>). {@code 2800-UPDATE-ACCOUNT-REC} is performed from
     * {@code 2000-POST-TRANSACTION} at {@code L441}, which runs only on the {@code L211} arm where the
     * reason has already tested as zero. The decision to post rather than reject is therefore already
     * taken, {@code WS-REJECT-COUNT} is not incremented, and {@code 2500-WRITE-REJECT-REC} is not
     * performed - so a failed account rewrite produces no reject record and no non-zero return code
     * from that path. The constant is declared because the transcription is not complete without it,
     * and {@link #descriptionOfReason(int)} resolves it so a caller that supplies it still gets a
     * correct record. Do not restructure the posting flow to make it reachable.
     */
    public static final int REASON_ACCOUNT_NOT_FOUND_ON_REWRITE = 109;

    /**
     * The description for {@value #REASON_INVALID_CARD_NUMBER}, 25 characters, from
     * {@code app/cbl/CBTRN02C.cbl:L386}.
     */
    public static final String DESC_INVALID_CARD_NUMBER = "INVALID CARD NUMBER FOUND";

    /**
     * The description for {@value #REASON_ACCOUNT_RECORD_NOT_FOUND}, 24 characters, from
     * {@code app/cbl/CBTRN02C.cbl:L398}.
     *
     * <p>The same text is moved for {@value #REASON_ACCOUNT_NOT_FOUND_ON_REWRITE} at {@code L557}. The
     * two codes are distinct and the text is shared, which is the source's own arrangement and is not
     * tidied here: the code is what distinguishes "no account for this cross-reference" from "the
     * account vanished between read and rewrite".
     */
    public static final String DESC_ACCOUNT_RECORD_NOT_FOUND = "ACCOUNT RECORD NOT FOUND";

    /**
     * The description for {@value #REASON_OVERLIMIT_TRANSACTION}, 21 characters, from
     * {@code app/cbl/CBTRN02C.cbl:L411}.
     */
    public static final String DESC_OVERLIMIT_TRANSACTION = "OVERLIMIT TRANSACTION";

    /**
     * The description for {@value #REASON_TRANSACTION_AFTER_EXPIRATION}, 42 characters, from
     * {@code app/cbl/CBTRN02C.cbl:L418}.
     *
     * <p>The longest of the five and still 34 characters clear of the
     * {@value #WS_VALIDATION_FAIL_REASON_DESC_LENGTH}-character receiver, so no description in the
     * source is ever truncated. The truncation rule is implemented regardless, because it is the rule a
     * {@code PIC X} receiver obeys and a caller may supply anything.
     */
    public static final String DESC_TRANSACTION_AFTER_EXPIRATION =
            "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    /**
     * The description {@code MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC}
     * ({@code app/cbl/CBTRN02C.cbl:L209}) leaves behind: the empty string, which
     * {@link RejectsFile#moveToValidationTrailer(int, String)} space-pads to the full
     * {@value #WS_VALIDATION_FAIL_REASON_DESC_LENGTH} characters.
     *
     * <p>Empty rather than 76 literal spaces so that the padding rule does the padding, in one place,
     * and a reader cannot miscount the literal.
     */
    public static final String DESC_SPACES = "";

    /**
     * The description the COBOL pairs with a reject reason code.
     *
     * <p>A {@code switch} over the five codes the program sets, plus {@link #REASON_NONE}, mirroring
     * the {@code MOVE} that immediately follows each {@code MOVE nnn TO WS-VALIDATION-FAIL-REASON}.
     * This is a convenience for callers and for tests, <strong>not</strong> a policy: the posting job
     * decides which code applies, and {@link RejectsFile#moveToValidationTrailer(int, String)} accepts
     * any description at all, so a job that needed a text this method does not know can still supply
     * it.
     *
     * <p>An unrecognised code yields {@link #DESC_SPACES}, matching the trailer's reset state at
     * {@code L209} rather than raising: there is no COBOL statement that rejects an unknown code, and
     * this method is a lookup rather than a validator.
     *
     * @param reasonCode a reject reason code
     * @return the description the program moves for that code, or {@link #DESC_SPACES} when the code is
     *         not one the program sets; never {@code null}
     */
    public static String descriptionOfReason(int reasonCode) {
        return switch (reasonCode) {
            case REASON_INVALID_CARD_NUMBER -> DESC_INVALID_CARD_NUMBER;
            case REASON_ACCOUNT_RECORD_NOT_FOUND, REASON_ACCOUNT_NOT_FOUND_ON_REWRITE ->
                    DESC_ACCOUNT_RECORD_NOT_FOUND;
            case REASON_OVERLIMIT_TRANSACTION -> DESC_OVERLIMIT_TRANSACTION;
            case REASON_TRANSACTION_AFTER_EXPIRATION -> DESC_TRANSACTION_AFTER_EXPIRATION;
            default -> DESC_SPACES;
        };
    }

    // =================================================================================================
    // The sink seam. WRITE FD-REJS-RECORD FROM REJECT-RECORD, app/cbl/CBTRN02C.cbl:L451.
    // =================================================================================================

    /**
     * Where a rendered {@value DalyRejectWriter#RECORD_LENGTH}-byte reject record goes.
     *
     * <p>This is the seam that keeps the geometry above assertable and keeps the deployment-time
     * data-access decision out of it. {@link DalyRejectWriter#openOutput()} supplies a
     * {@link JdbcTemplate}-backed implementation that writes to the configured
     * {@value DalyRejectWriter#DD_NAME} dataset; {@link DalyRejectWriter#openOutput(RecordSink)}
     * accepts any other, which is how a unit test collects the emitted records in memory with no
     * database and no filesystem, and how a site whose data-access driver expects a different parameter
     * shape substitutes its own.
     *
     * <p>Implementations must preserve <strong>call order</strong> and must not buffer in a way that
     * could reorder or coalesce records: the rejects file is one sequential pass over {@code DALYTRAN}
     * ({@code app/cbl/CBTRN02C.cbl:L202-L219}), so its record order is part of the parity fingerprint.
     *
     * <p>The single abstract method returns a {@link FileStatus.Outcome} rather than throwing, because
     * {@code 2500-WRITE-REJECT-REC} ({@code app/cbl/CBTRN02C.cbl:L446-L465}) is a guard chain - it sets
     * {@code APPL-RESULT} to 8, tests {@code DALYREJS-STATUS = '00'}, resets it to 0 or 12, and only
     * then decides whether to display {@code 'ERROR WRITING TO REJECTS FILE'} and abend. That decision
     * is the posting job's, and {@link FileStatus} deliberately carries no dependency on the abend type,
     * which is what lets it stay there.
     */
    public interface RecordSink {

        /**
         * Accepts one whole reject record, reproducing
         * {@code WRITE FD-REJS-RECORD FROM REJECT-RECORD} at {@code app/cbl/CBTRN02C.cbl:L451}.
         *
         * @param recordImage the record's bytes in the dataset code page, exactly
         *                    {@value DalyRejectWriter#RECORD_LENGTH} of them. The array is freshly
         *                    allocated for this call and is neither retained nor reused by the caller,
         *                    so an implementation may keep it
         * @return {@link FileStatus.Outcome#OK} for the {@code DALYREJS-STATUS = '00'} arm, or
         *         {@link FileStatus.Outcome#OTHER} for any failure - the arm that sets
         *         {@code APPL-RESULT} to 12. <strong>Never {@code null}</strong>: there is no COBOL
         *         {@code FILE STATUS} meaning "no answer", so a {@code null} is an implementation defect
         *         and {@link RejectsFile#writeRejectRec()} rejects it rather than carrying it forward
         */
        FileStatus.Outcome write(byte[] recordImage);

        /**
         * Prepares the destination, mirroring {@code OPEN OUTPUT DALYREJS-FILE} in
         * {@code 0300-DALYREJS-OPEN} at {@code app/cbl/CBTRN02C.cbl:L293}.
         *
         * <p>Called exactly once, by {@link RejectsFile}'s constructor, and its answer is published as
         * {@link RejectsFile#openOutcome()} so the posting job can run the same {@code '00'}-or-12
         * ladder over the open that it runs over the write and the close. Without it the open would be
         * the one paragraph of the three whose guard chain - the
         * {@code 'ERROR OPENING DALY REJECTS FILE'} arm at {@code L302} - had no outcome to branch on.
         *
         * <p>Defaulted to {@link FileStatus.Outcome#OK} because an in-memory collector - what a unit test
         * and the parity harness supply - has no destination outside the process and so nothing that
         * could refuse to be established. Every sink that does address something outside the process
         * overrides this and reports whether that destination could be reached, the
         * {@link JdbcTemplate}-backed default included: it borrows a pooled connection per record rather
         * than holding one open, but the relation it will insert into either exists for this run or does
         * not, and that is an open-time fact. Leaving it defaulted there was a parity defect, because it
         * made {@code L302} reachable only through a write - and a run whose input is entirely clean
         * writes no reject at all.
         *
         * @return {@link FileStatus.Outcome#OK} when the destination is ready, or
         *         {@link FileStatus.Outcome#OTHER} otherwise. <strong>Never {@code null}</strong>, for
         *         the same reason {@link #write(byte[])} is never {@code null}
         */
        default FileStatus.Outcome open() {
            return FileStatus.Outcome.OK;
        }

        /**
         * Releases whatever the sink holds, mirroring {@code CLOSE DALYREJS-FILE} in
         * {@code 9300-DALYREJS-CLOSE} at {@code app/cbl/CBTRN02C.cbl:L639}.
         *
         * <p>Defaulted to {@link FileStatus.Outcome#OK} because a sink that holds nothing - the
         * in-memory collector a test supplies, and the {@link JdbcTemplate}-backed default, which
         * borrows a pooled connection per record and returns it immediately - has nothing to release.
         *
         * @return {@link FileStatus.Outcome#OK} when the sink closed cleanly, or
         *         {@link FileStatus.Outcome#OTHER} otherwise. <strong>Never {@code null}</strong>, for
         *         the same reason {@link #write(byte[])} is never {@code null}
         */
        default FileStatus.Outcome close() {
            return FileStatus.Outcome.OK;
        }

        /**
         * Applies the <strong>abnormal</strong> disposition {@code app/jcl/POSTTRAN.jcl:L34-L38} declares
         * for {@value DalyRejectWriter#DD_NAME}: the third positional of
         * {@code DISP=(NEW,CATLG,DELETE)}.
         *
         * <p>A {@code DISP} parameter carries three dispositions, and only two of them were reproduced
         * before this method existed. {@code NEW} is the status - the step allocates the generation -
         * and {@link #open()} reproduces it by clearing. {@code CATLG} is the <em>normal</em>
         * disposition - a step that ends normally leaves the generation catalogued - and
         * {@link #close()} reproduces it by leaving the records in place. {@code DELETE} is the
         * <em>abnormal</em> disposition, and it is a different outcome from either: a run that abends
         * leaves <strong>no generation at all</strong>. MVS does not unwrite the records; it deletes the
         * dataset that held them. So a step that wrote 40 rejects and then abended leaves nothing
         * behind, and the next run's {@code DALYREJS(+1)} is generation one again.
         *
         * <p>That is why this is not a transaction rollback and cannot be delegated to one. A rollback
         * offers two outcomes - every write kept, or the uncommitted writes dropped - and the mainframe's
         * third is neither. Each write here is durable as it completes, exactly as {@code RECOVERY(NONE)}
         * makes it ({@code app/csd/CARDDEMO.CSD:84}); this discard then removes them, in the same order
         * of events the mainframe uses. Nothing is buffered to make it possible, so the memory a run
         * needs does not grow with the number of rejects it writes.
         *
         * <p>Defaulted to {@link FileStatus.Outcome#OK} for the same reason {@link #open()} and
         * {@link #close()} are: an in-memory collector - what a unit test and the parity harness supply -
         * holds no catalogued generation. Its records are per-run state that ceases to exist when the run
         * does, which is precisely the outcome {@code DELETE} produces, so reporting {@code OK} without
         * issuing anything is the honest answer rather than a stub. Every sink that does address a
         * catalogued destination overrides this.
         *
         * <p>Called at most once per handle, by {@link RejectsFile#discardGeneration()}, and only on a
         * path that is already abending. An implementation must therefore <strong>not throw</strong>: a
         * failed disposition must not replace the abend that caused it.
         *
         * @param recordsWritten how many records this run handed to the sink, so an implementation
         *                       addressing a shared destination can establish that what it is about to
         *                       delete is the generation <em>this</em> run allocated rather than another
         *                       run's records
         * @return {@link FileStatus.Outcome#OK} when the generation was discarded or there was none, or
         *         {@link FileStatus.Outcome#OTHER} when it could not be. <strong>Never
         *         {@code null}</strong>, for the same reason {@link #write(byte[])} is never
         *         {@code null}
         */
        default FileStatus.Outcome discard(int recordsWritten) {
            return FileStatus.Outcome.OK;
        }
    }

    // =================================================================================================
    // Collaborators. All constructor-injected; every one of them immutable.
    // =================================================================================================

    /**
     * The module's single {@link JdbcTemplate}, declared by the data-source configuration. Used only by
     * the default sink that {@link #openOutput()} creates; a caller that supplies its own sink never
     * touches it.
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * The one representation this writer's record image crosses JDBC in.
     *
     * <p>Injected rather than decided here, and that is the point: whether a record image binds as
     * characters or as bytes is a property of the deployment's driver, so it is stated once, by
     * {@link RecordImageForm#FORM_PROPERTY}, and never re-decided by each writer. Two writers that
     * answered it differently would both look correct in isolation and at most one of them would be
     * right on a backend nobody here can exercise (risk <strong>R-E</strong>).
     */
    private final RecordImageForm recordImageForm;

    /**
     * The fixed-width codec, constructed over the injected dataset {@link Charset}.
     *
     * <p>Immutable and holds only that charset, so sharing one across every handle is safe. Every pad,
     * truncate and byte conversion this class performs goes through it, which keeps the code page
     * explicit at every boundary and keeps the two opposing {@code MOVE} rules - left zero-fill for the
     * reason, right space-pad for the description - in exactly one place in the module (practice
     * <strong>B11</strong>).
     */
    private final FixedWidthCodec codec;

    /**
     * The configured binding for {@value #DD_NAME}: where the dataset lives and what shape its records
     * are. Resolved once, by DD-name key, so no dataset name is written into this file.
     */
    private final DatasetBinding binding;

    /**
     * The dataset as the module's one data-access contract sees it, or {@code null} when the configured
     * name is not a dataset name at all.
     *
     * <p>Resolved at construction and held, not recomposed per open, so the configured name is validated
     * once. It is <strong>deliberately nullable</strong>: a deployment or profile may bind
     * {@value #DD_NAME} to something that is not a dataset name and cannot become a SQL identifier, and
     * refusing to construct the bean at all would stop the application context from starting under that
     * profile (gate <strong>G3</strong>) even though every test and the parity harness supply their own
     * sink and need no dataset. So the refusal is deferred to {@link #insertStatement()} - the one place
     * that would otherwise compose the name into SQL - and {@link #datasetRefusal} carries the reason
     * until then.
     *
     * <p>The default binding is a generation data group, {@code ...DALYREJS(+1)}, and the relative
     * generation suffix is part of a well-formed dataset name, so the ordinary configuration resolves
     * here rather than being deferred.
     */
    private final DatasetRelation relation;

    /**
     * Why {@link #relation} is absent, or {@code null} when it is present.
     *
     * <p>Kept so the diagnostic is the one the dataset-name grammar produced, at the position it found
     * the fault, rather than a second description written from memory of it.
     */
    private final RuntimeException datasetRefusal;

    /**
     * Wires the writer and verifies, before the application can start, that the configured dataset
     * geometry agrees with the JCL and the COBOL file description.
     *
     * <p>Four collaborators, all constructor-injected: there is no setter and no field injection, so a
     * fully constructed instance is always usable and never half-configured (practice
     * <strong>B9</strong>, gate <strong>G53</strong>).
     *
     * <p>The two geometry cross-checks are the point of doing any work here at all.
     * <ul>
     *   <li><strong>Record length.</strong> A binding declaring, say, 410 bytes - which is what dropping
     *       {@code CVTRA06Y}'s trailing {@code FILLER X(20)} would suggest - would misplace every
     *       trailer byte of every rejected transaction, and the failure would surface as a mass parity
     *       diff a long way from its cause. Failing at startup with the DD name, both widths and the
     *       authoritative sources cited turns that into a one-line fix. Gate <strong>G20</strong> is
     *       this check.</li>
     *   <li><strong>Record format.</strong> {@code RECFM=F} is what makes "every record is exactly
     *       {@value #RECORD_LENGTH} bytes" true. A binding that omitted the key, or declared the
     *       {@code FB} its two sibling outputs use, would leave every number in this class unchanged
     *       while quietly describing a different file, so it is refused here rather than tolerated. Note
     *       that the catalogue's own validation only checks the value is <em>one of</em> {@code F} and
     *       {@code FB}; which one this DD requires is knowledge that belongs to this class.</li>
     * </ul>
     *
     * @param jdbcTemplate    the module's single {@link JdbcTemplate}
     * @param datasetCharset  the active dataset code page, resolved by the charset configuration from
     *                        {@link CobolCharsetConfig#DATASET_CHARSET_PROPERTY} and named here by bean
     *                        qualifier so no platform default can be picked up by accident (practice
     *                        <strong>B8</strong>)
     * @param datasetBindings the DD-name-keyed dataset catalogue bound from {@code carddemo.datasets}
     * @param recordImageForm how a record image crosses JDBC in this deployment
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if no binding is configured for {@value #DD_NAME}, which the
     *                               catalogue itself reports, or if the configured record length is not
     *                               {@value #RECORD_LENGTH}, or if the configured record format is not
     *                               {@value #RECORD_FORMAT}
     */
    public DalyRejectWriter(
            JdbcTemplate jdbcTemplate,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            DatasetBindings datasetBindings,
            RecordImageForm recordImageForm) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required to "
                + "write the " + DD_NAME + " dataset; the data-source configuration declares the "
                + "single instance this module shares");
        this.recordImageForm = Objects.requireNonNull(recordImageForm, "A record-image representation "
                + "is required: whether this deployment's driver takes a record image as characters or "
                + "as bytes is stated once, by " + RecordImageForm.FORM_PROPERTY + ", and never "
                + "decided per writer");
        Objects.requireNonNull(datasetCharset, "A dataset charset is required: a fixed-width "
                + "mainframe record is bytes in a specific code page, so the code page is injected "
                + "explicitly and is never derived from the platform");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets catalogue is required to "
                + "resolve the " + DD_NAME + " dataset; dataset names are never hard-coded in Java");

        this.codec = new FixedWidthCodec(datasetCharset);
        RecordImageForm.requireSingleByteCodePage(datasetCharset);
        this.binding = datasetBindings.binding(DD_NAME);
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("carddemo.datasets." + DD_NAME + " declares record-length "
                    + binding.recordLength() + ", but a rejected-transaction record is "
                    + RECORD_LENGTH + " bytes: app/cbl/CBTRN02C.cbl:L81-L84 declares "
                    + FD_REJECT_RECORD + " PIC X(" + FD_REJECT_RECORD_LENGTH + ") followed by "
                    + FD_VALIDATION_TRAILER + " PIC X(" + VALIDATION_TRAILER_LENGTH + "), and "
                    + "app/jcl/POSTTRAN.jcl:L36 declares LRECL=" + RECORD_LENGTH + " on the creating "
                    + "step. Correct record-length to " + RECORD_LENGTH + " in application.yml; a "
                    + "record width is fixed by the program's file description and must never be "
                    + "overridden per profile.");
        }
        if (!RECORD_FORMAT.equalsIgnoreCase(binding.recordFormat())) {
            throw new IllegalStateException("carddemo.datasets." + DD_NAME + " declares record-format "
                    + describeConfiguredRecordFormat() + ", but app/jcl/POSTTRAN.jcl:L36 declares "
                    + "RECFM=" + RECORD_FORMAT + ". Fixed unblocked is what makes every reject record "
                    + "exactly " + RECORD_LENGTH + " bytes, and it differs from the FB the two sibling "
                    + "outputs use, so it is required rather than assumed. Set record-format to "
                    + RECORD_FORMAT + " in application.yml.");
        }

        // Null and empty are separated from malformed so the diagnostic says which of the two happened,
        // and so the only exception caught here is the one the dataset-name grammar raises rather than
        // any unchecked type that might come from somewhere else inside it.
        DatasetRelation resolved = null;
        RuntimeException refusal = null;
        if (binding.dsname() == null || binding.dsname().isEmpty()) {
            refusal = new IllegalArgumentException("carddemo.datasets." + DD_NAME + ".dsname is not "
                    + "configured, so there is no destination to address");
        } else {
            try {
                resolved = DatasetRelation.of(binding.dsname(), RECORD_LENGTH);
            } catch (IllegalArgumentException notADatasetName) {
                refusal = notADatasetName;
            }
        }
        this.relation = resolved;
        this.datasetRefusal = refusal;
    }

    /**
     * Renders the configured record format for the constructor's diagnostic, distinguishing an omitted
     * key from a wrong value.
     *
     * <p>They are different mistakes with different fixes - one is "the key is missing", the other is
     * "the key says {@code FB}" - and a message that rendered {@code null} as the text {@code "null"}
     * would read as though the value were the four-letter word.
     *
     * @return {@code "absent"} when no record format is configured, or the configured value in quotes
     */
    private String describeConfiguredRecordFormat() {
        return binding.recordFormat() == null ? "absent" : "'" + binding.recordFormat() + "'";
    }

    // =================================================================================================
    // What was configured, exposed so a caller need not re-resolve it.
    // =================================================================================================

    /**
     * The configured binding for {@value #DD_NAME} - its location, organization, record format, block
     * size and record length exactly as configuration declares them.
     *
     * @return the binding, never {@code null}
     */
    public DatasetBinding datasetBinding() {
        return binding;
    }

    /**
     * The code page this writer encodes records in, as injected.
     *
     * <p>Exposed so a caller that needs to decode an emitted record - the parity harness, or a test -
     * uses the same explicitly chosen encoding rather than resolving one of its own.
     *
     * @return the dataset charset, never {@code null}
     */
    public Charset datasetCharset() {
        return codec.charset();
    }

    /**
     * The fixed record width in bytes, {@value #RECORD_LENGTH}, cross-checked against configuration at
     * construction.
     *
     * @return {@value #RECORD_LENGTH}
     */
    public int recordLength() {
        return RECORD_LENGTH;
    }

    // =================================================================================================
    // Opening the dataset. 0300-DALYREJS-OPEN, app/cbl/CBTRN02C.cbl:L291-L307.
    // =================================================================================================

    /**
     * Opens {@value #DD_NAME} for output against the configured dataset, mirroring
     * {@code OPEN OUTPUT DALYREJS-FILE} at {@code app/cbl/CBTRN02C.cbl:L293}.
     *
     * <p>Output, not append: the JCL declares {@code DISP=(NEW,CATLG,DELETE)} against a relative
     * generation of a five-generation GDG ({@code app/jcl/POSTTRAN.jcl:L34-L38},
     * {@code app/jcl/DALYREJS.jcl:L24-L28}), so each run produces a new generation rather than extending
     * the previous one, and the COBOL opens once at {@code L198} and closes once at {@code L224}. Each
     * call returns a fresh handle with its own record area, so a caller that opens twice gets two
     * independent runs and never shares record state between them.
     *
     * <p>The COBOL open is itself a guard chain - {@code MOVE 8 TO APPL-RESULT} first, then {@code '00'}
     * to 0 or otherwise to 12, then the {@code 'ERROR OPENING DALY REJECTS FILE'} arm - so the outcome
     * is reported rather than assumed: read it from {@link RejectsFile#openOutcome()} and run the ladder
     * there. Two things are nevertheless refused outright rather than folded into that outcome, because
     * neither is a dataset condition the COBOL has a guard for: a configuration fault, which has already
     * failed the context at startup, and a configured name that cannot be addressed as a dataset at all,
     * which {@link #insertStatement()} reports.
     *
     * @return a new per-execution handle whose {@code FD-REJS-RECORD} area is
     *         {@value #RECORD_LENGTH} spaces, exactly as a freshly allocated FD record area is, and
     *         whose {@link RejectsFile#openOutcome()} carries what the sink reported
     * @throws IllegalStateException if the configured dataset name cannot be addressed as a dataset, as
     *                               {@link #insertStatement()} describes
     * @throws NullPointerException  if the sink returns a {@code null} outcome from
     *                               {@link RecordSink#open()}
     */
    public RejectsFile openOutput() {
        return new RejectsFile(new JdbcRecordSink(jdbcTemplate, insertStatement(), recordImageForm,
                codec.charset(), requireRelation().describeStatement(),
                requireRelation().deleteAll(), requireRelation().countAllStatement(),
                requireRelation().dsname()));
    }

    /**
     * Opens {@value #DD_NAME} for output against a caller-supplied sink.
     *
     * <p>This is the seam that makes the whole class assertable with no database, no filesystem, no
     * application context and no {@code JobLauncher} (practice <strong>B10</strong>, gate
     * <strong>G51</strong>): a test passes a collector and asserts the emitted bytes, their widths and
     * their order directly. It is equally the supported extension point for a deployment whose
     * data-access driver expects a different parameter shape from the default sink's.
     *
     * @param sink where rendered records go; must not be {@code null}
     * @return a new per-execution handle, whose {@link RejectsFile#openOutcome()} carries what the sink
     *         reported from {@link RecordSink#open()}
     * @throws NullPointerException if {@code sink} is {@code null}, or if the sink returns a
     *                              {@code null} outcome from {@link RecordSink#open()}
     */
    public RejectsFile openOutput(RecordSink sink) {
        return new RejectsFile(Objects.requireNonNull(sink, "A record sink is required to open "
                + DD_NAME + " for output; call openOutput() for the configured dataset"));
    }

    /**
     * The single-parameter statement the default sink issues for one record, composed by the module's
     * one data-access contract.
     *
     * <p>Every character of it comes from {@link DatasetRelation#insertRecordImage()} - the identifier
     * rendering included - and that matters more than it looks. Three sequential outputs in this module
     * are written by sibling classes against the same deployment driver, so if each rendered the
     * configured dataset name its own way, at most one of them could be right and the others would fail,
     * or worse address something else, on a backend nobody here can exercise. Routing them all through
     * one renderer makes the statements differ in exactly one respect: which dataset they name.
     *
     * <p>What that shape asserts, and why:
     * <ul>
     *   <li><strong>No column list.</strong> This migration introduces no schema, no data-definition
     *       statement, no entity mapping and no version column (gate <strong>G44</strong>), and an
     *       output-only dataset is never described, so there is no column name to be had. The record is
     *       one fixed-width image and is bound positionally, at
     *       {@link DatasetRelation#RECORD_IMAGE_COLUMN_INDEX}.</li>
     *   <li><strong>The dataset name is one delimited identifier.</strong> A mainframe dataset name
     *       contains dots, which an SQL parser would otherwise read as a qualified
     *       catalogue-schema-table reference, and this one additionally carries a {@code (+1)} relative
     *       generation.</li>
     *   <li><strong>The name is validated as a dataset name before it is rendered.</strong> It arrives
     *       from configuration, which is externally controlled, and reaches a position no bind parameter
     *       can occupy, so it must satisfy the z/OS dataset-name grammar rather than merely survive a
     *       scan for punctuation somebody thought of. That check happened at construction; this method
     *       reports its verdict.</li>
     *   <li><strong>The name comes from configuration, verbatim.</strong> Resolved by DD-name key from
     *       {@code carddemo.datasets}, so no mainframe dataset literal appears in this file or anywhere
     *       else in the Java sources (gate <strong>G46</strong>).</li>
     * </ul>
     *
     * <p>Package-visible so its text is asserted directly by a unit test rather than inferred from a
     * database round trip.
     *
     * @return the parameterised statement
     * @throws IllegalStateException if {@code carddemo.datasets.}{@value #DD_NAME}{@code .dsname} is
     *                               absent, or is not a well-formed z/OS dataset name. In that case
     *                               write through {@link #openOutput(RecordSink)} with a
     *                               caller-supplied sink instead, exactly as every unit test and the
     *                               parity harness do
     */
    String insertStatement() {
        return requireRelation().insertRecordImage();
    }

    /**
     * The resolved relation, or a refusal naming the configuration that could not be addressed.
     *
     * <p>Refused when a sink is actually built rather than at startup, for the reason the constructor
     * records: a profile may legitimately bind this DD name to something no JDBC statement can address,
     * and a fixture-backed profile, every unit test and the parity harness all write through
     * {@link #openOutput(RecordSink)} instead and never reach this.
     *
     * @return the relation; never {@code null}
     * @throws IllegalStateException if the configured name is not a dataset name
     */
    private DatasetRelation requireRelation() {
        if (relation == null) {
            throw new IllegalStateException("carddemo.datasets." + DD_NAME + ".dsname cannot be "
                    + "addressed as a dataset, so no statement can be composed for it and the default "
                    + "sink cannot be built. Set it to a well-formed z/OS dataset name, or write "
                    + "through openOutput(RecordSink) with your own sink - which is what a "
                    + "fixture-backed profile, every unit test and the parity harness do, and why this "
                    + "is refused here rather than at startup. The grammar's own verdict is attached.",
                    datasetRefusal);
        }
        return relation;
    }

    /**
     * The default {@link RecordSink}: one parameterised insert of the whole record image per record,
     * issued immediately and in call order.
     *
     * <p>Immutable and stateless, so it is safe to hold and safe to share; the pooled connection is
     * borrowed and returned inside each call, which is why {@link RecordSink#close()} has nothing to do
     * and is left defaulted. {@link RecordSink#open()} is <em>not</em> left defaulted: holding no
     * connection is not the same as having no destination, and whether the configured relation can be
     * addressed at all is what {@code OPEN OUTPUT} answers.
     *
     * <p>The record image is bound through the injected {@link RecordImageForm} - the module's single
     * authority on whether a record image crosses JDBC as characters or as bytes. The code page decision
     * was already taken, explicitly, when the record area encoded the image; what this sink must not do
     * is take a second decision about the column's JDBC type (practice <strong>B8</strong>).
     */
    private static final class JdbcRecordSink implements RecordSink {

        /** The template that issues the insert. */
        private final JdbcTemplate jdbcTemplate;

        /** The parameterised statement, built once by {@link DalyRejectWriter#insertStatement()}. */
        private final String statement;

        /** How the record image crosses JDBC: the deployment's answer, not this sink's. */
        private final RecordImageForm recordImageForm;

        /** The dataset code page, needed by the representation to bind a character image. */
        private final Charset charset;

        /**
         * A read-only statement that resolves and describes the destination without transferring any of
         * it - the probe both {@link #open()} and {@link #close()} use.
         */
        private final String describeStatement;

        /**
         * Empties the destination, which is what {@code DISP=(NEW,CATLG,DELETE)} means for a relation
         * that already exists. Issued by {@link #open()} and nowhere else.
         */
        private final String clearStatement;

        /**
         * Counts what the destination holds, so {@link #discard(int)} can establish that the generation
         * it is about to delete is the one this run allocated. Read-only, and issued nowhere else.
         */
        private final String countStatement;

        /**
         * The configured dataset name, carried for one purpose only: naming the destination in the refusal
         * {@link #write(byte[])} raises when there is no unit of work to persist a record in. It is
         * configuration rather than a literal, so surfacing it introduces no dataset name into source.
         */
        private final String datasetName;

        /**
         * Creates the sink.
         *
         * @param jdbcTemplate      the template that issues the insert
         * @param statement         the parameterised statement
         * @param recordImageForm   how a record image crosses JDBC in this deployment
         * @param charset           the dataset code page
         * @param describeStatement the read-only probe {@link #open()} and {@link #close()} issue
         * @param clearStatement    the statement {@link #open()} issues to establish an empty generation
         * @param countStatement    the read-only count {@link #discard(int)} issues before deleting
         * @param datasetName       the configured destination name, for the missing-unit-of-work refusal
         */
        JdbcRecordSink(JdbcTemplate jdbcTemplate, String statement, RecordImageForm recordImageForm,
                       Charset charset, String describeStatement, String clearStatement,
                       String countStatement, String datasetName) {
            this.jdbcTemplate = jdbcTemplate;
            this.statement = statement;
            this.recordImageForm = recordImageForm;
            this.charset = charset;
            this.describeStatement = describeStatement;
            this.clearStatement = clearStatement;
            this.countStatement = countStatement;
            this.datasetName = datasetName;
        }

        /**
         * Establishes the generation this run writes into: {@code OPEN OUTPUT DALYREJS-FILE} in
         * {@code 0300-DALYREJS-OPEN}, over a dataset {@code app/jcl/POSTTRAN.jcl:L34-L38} declares
         * {@code DISP=(NEW,CATLG,DELETE)} on {@code DALYREJS(+1)}.
         *
         * <p>Two statements, and each reproduces something the COBOL open does. The
         * <strong>describe</strong> resolves the DD name to a real destination and fails if it cannot -
         * read-only, its predicate false on every row, so nothing is transferred - which is how an
         * absent, unreachable or refused destination is reported once at the open rather than record by
         * record. The <strong>clear</strong> is what {@code NEW} means: the run writes into an empty
         * generation, so the previous run's rejects are not part of it, and a run that rejects nothing
         * still leaves an empty rejects dataset behind rather than none at all. Nothing touches the
         * relation's definition - this module issues no data-definition statement anywhere (gate G44).
         *
         * <p>A failure is the arm the caller's guard chain already has: it sets {@code APPL-RESULT} to
         * 12 and abends, exactly as a failed {@code OPEN} does.
         *
         * @return {@link FileStatus.Outcome#OK} when the destination is established, or
         *         {@link FileStatus.Outcome#OTHER} when it could not be
         */
        @Override
        public FileStatus.Outcome open() {
            try {
                jdbcTemplate.execute(describeStatement);
                jdbcTemplate.update(clearStatement);
                return FileStatus.Outcome.OK;
            } catch (DataAccessException refused) {
                LOG.error("Could not establish the " + DD_NAME + " generation for output - "
                        + BackendDiagnostic.of(refused).describe()
                        + "; reporting FILE STATUS outcome " + FileStatus.Outcome.OTHER.name()
                        + " to the caller, which is the arm that sets APPL-RESULT to 12");
                return FileStatus.Outcome.OTHER;
            }
        }

        /**
         * Confirms the destination survived the run: {@code CLOSE DALYREJS-FILE} in
         * {@code 9300-DALYREJS-CLOSE}.
         *
         * <p>There is nothing buffered to flush - each record was inserted as it was written, through a
         * connection borrowed and returned per record - so what a close can still discover is that the
         * destination is no longer there: a relation dropped, revoked or unreachable part-way through a
         * run. The same read-only describe {@link #open()} used answers that. A close that could not
         * fail would leave the COBOL's own close-failure arm unreachable, which is exactly what its
         * guard chain says must not be true.
         *
         * @return {@link FileStatus.Outcome#OK} when the destination is still addressable, or
         *         {@link FileStatus.Outcome#OTHER} when it is not
         */
        @Override
        public FileStatus.Outcome close() {
            try {
                jdbcTemplate.execute(describeStatement);
                return FileStatus.Outcome.OK;
            } catch (DataAccessException refused) {
                LOG.error("Could not confirm the " + DD_NAME + " destination on close - "
                        + BackendDiagnostic.of(refused).describe()
                        + "; reporting FILE STATUS outcome " + FileStatus.Outcome.OTHER.name()
                        + " to the caller, which is the arm that sets APPL-RESULT to 12");
                return FileStatus.Outcome.OTHER;
            }
        }

        /**
         * Deletes the generation this run wrote: the {@code DELETE} positional of
         * {@code app/jcl/POSTTRAN.jcl:L34-L38}.
         *
         * <p><strong>Refused unless the destination holds exactly this run's records.</strong>
         * {@code NEW} means the step allocates the generation, so a faithful deployment gives a run a
         * relation of its own and the two counts agree. A deployment that instead maps successive
         * generations onto one relation would have this delete another generation's rejects, so the count
         * is read first and a disagreement is reported rather than acted on. That is deliberately loud:
         * it is a deployment-time binding question, and silently deleting rejected customer transactions
         * that this run did not write would be far worse than an operator seeing an outcome.
         *
         * <p>Never throws. The caller is already abending.
         *
         * @param recordsWritten how many records this run handed to this sink
         * @return {@link FileStatus.Outcome#OK} when the generation was discarded, or
         *         {@link FileStatus.Outcome#OTHER} when it was not
         */
        @Override
        public FileStatus.Outcome discard(int recordsWritten) {
            try {
                Integer held = jdbcTemplate.queryForObject(countStatement, Integer.class);
                if (held == null || held != recordsWritten) {
                    LOG.error("Refusing to apply the " + DD_NAME + " abnormal disposition of "
                            + "app/jcl/POSTTRAN.jcl:L34: this run wrote " + recordsWritten
                            + " record(s) but the destination holds " + held
                            + ". DISP=(NEW,CATLG,DELETE) deletes the generation this step allocated, so "
                            + "a destination holding records this step did not write is not that "
                            + "generation. Leaving it untouched and reporting FILE STATUS outcome "
                            + FileStatus.Outcome.OTHER.name() + "; bind " + DD_NAME
                            + " to a relation of its own so each run allocates its own generation");
                    return FileStatus.Outcome.OTHER;
                }
                int removed = jdbcTemplate.update(clearStatement);
                if (removed == recordsWritten) {
                    return FileStatus.Outcome.OK;
                }
                LOG.error("The " + DD_NAME + " abnormal disposition removed " + removed
                        + " record(s) where this run wrote " + recordsWritten
                        + "; reporting FILE STATUS outcome " + FileStatus.Outcome.OTHER.name()
                        + " rather than reporting the generation as discarded");
                return FileStatus.Outcome.OTHER;
            } catch (DataAccessException refused) {
                LOG.error("Could not apply the " + DD_NAME + " abnormal disposition after "
                        + recordsWritten + " record(s) - " + BackendDiagnostic.of(refused).describe()
                        + "; reporting FILE STATUS outcome " + FileStatus.Outcome.OTHER.name()
                        + ". The generation may remain catalogued, which DISP=(NEW,CATLG,DELETE) says it "
                        + "should not; it must be deleted by hand before the next run");
                return FileStatus.Outcome.OTHER;
            }
        }

        /**
         * Writes one record, mapping a rejected write onto the arm that sets {@code APPL-RESULT} to 12.
         *
         * <p>Only {@link DataAccessException} is caught, and that narrowness is deliberate: it is the
         * family Spring translates a genuine data-access failure into. A configuration defect such as an
         * unset data source raises a different, unchecked type and is left to propagate, because mapping
         * it to a file-status outcome would let a misconfigured deployment abend with a misleading reason
         * for every rejected transaction instead of failing once, clearly.
         *
         * <p>The row count the statement returns is deliberately not inspected.
         * {@code WRITE FD-REJS-RECORD FROM REJECT-RECORD} declares no {@code INVALID KEY} and no
         * {@code AT END} phrase, and the program's only test is {@code DALYREJS-STATUS = '00'}, so
         * anything short of a raised failure is a completed write and inventing a stricter check here
         * would reject records the COBOL accepts.
         *
         * @param recordImage the record's bytes in the dataset code page
         * @return {@link FileStatus.Outcome#OK}, or {@link FileStatus.Outcome#OTHER} when the write was
         *         rejected
         * @throws IllegalStateException if no unit of work is open, in which case nothing is attempted -
         *                               the record would not have been stored and reporting
         *                               {@link FileStatus.Outcome#OK} would lose a reject silently
         */

        @Override
        public FileStatus.Outcome write(byte[] recordImage) {
            // Refused before anything is attempted when no unit of work is open. The pool hands out
            // connections with auto-commit disabled, so the INSERT would execute, report the row it added,
            // and then be rolled back when the connection returned - and this sink would report OK, which
            // is the DALYREJS-STATUS = '00' arm, for a rejected customer transaction that was never
            // recorded anywhere. A lost reject is the one outcome worse than an abend, because nothing
            // downstream can detect it, so the missing boundary is reported as the wiring defect it is.
            // The reject writes CBTRN02C performs run inside the step's own chunk transaction; note that
            // open() and discard() deliberately carry no such requirement, because Spring Batch runs the
            // ItemStream open and close callbacks outside that transaction.
            DatasetUnitOfWork.requireActiveToPersist("A write of a " + DD_NAME + " record, which "
                    + "WRITE FD-REJS-RECORD FROM REJECT-RECORD issues at app/cbl/CBTRN02C.cbl:L451",
                    datasetName);
            PreparedStatementSetter binder = parameters -> recordImageForm.bindImage(parameters,
                    DatasetRelation.RECORD_IMAGE_COLUMN_INDEX, recordImage, charset);
            try {
                jdbcTemplate.update(statement, binder);
                return FileStatus.Outcome.OK;
            } catch (DataAccessException rejected) {
                // The reason is logged because the outcome travelling back to the caller is deliberately
                // coarse - the COBOL guard chain has one failure arm - and discarding it would leave a
                // production abend undiagnosable. What is logged is the backend's SQLSTATE, vendor code
                // and exception type; the exception itself is not, because a driver's message is prose
                // it composed around the record it refused - and that record is a rejected customer
                // transaction, so its bytes must not reach a log.
                LOG.error("Rejected write of a " + RECORD_LENGTH + "-byte " + DD_NAME
                        + " record - " + BackendDiagnostic.of(rejected).describe()
                        + "; reporting FILE STATUS outcome " + FileStatus.Outcome.OTHER.name()
                        + " to the caller, which is the arm that sets APPL-RESULT to 12");
                return FileStatus.Outcome.OTHER;
            }
        }
    }

    // =================================================================================================
    // The per-execution handle. EVERY piece of mutable state in this file lives here.
    // =================================================================================================

    /**
     * One opened {@value #DD_NAME} dataset, together with the {@value #RECORD_LENGTH}-byte
     * {@code FD-REJS-RECORD} area that a rejected transaction and its trailer are moved into and written
     * from.
     *
     * <p>This type exists so the writer bean can be a stateless singleton. A COBOL record area is
     * mutable and per-run; making it a field on a singleton would let two concurrent posting runs
     * overwrite each other's reject record and would make a test's outcome depend on what ran before it.
     * So the area, the sink, the open flag and the record count are all here, obtained from
     * {@link DalyRejectWriter#openOutput()} and discarded at the end of the run (practice
     * <strong>B9</strong>, gate <strong>G53</strong>).
     *
     * <p>A handle is <strong>not</strong> thread-safe, exactly as a COBOL record area is not, and is
     * meant to be confined to the step, chunk or test that opened it. It implements {@link AutoCloseable}
     * so a try-with-resources block reproduces the {@code OPEN OUTPUT} / {@code CLOSE} pairing of
     * {@code app/cbl/CBTRN02C.cbl:L198} and {@code L224}; {@link #closeOutput()} is the form that returns
     * the outcome.
     *
     * <p>A freshly opened handle's area is established the way a program's record area is established at
     * load time: each span at its <em>category</em> default rather than uniformly blank. So the
     * {@value #FD_REJECT_RECORD_LENGTH}-byte transaction span and the
     * {@value #WS_VALIDATION_FAIL_REASON_DESC_LENGTH}-byte description span hold spaces, while the
     * {@code PIC 9(04)} reason span holds zoned zeros and therefore reads back as {@code "0000"}.
     *
     * <p>That is a happy coincidence worth naming, because it is exactly the trailer's own reset state:
     * {@code MOVE 0 TO WS-VALIDATION-FAIL-REASON} and
     * {@code MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC} at {@code app/cbl/CBTRN02C.cbl:L208-L209}
     * establish the same 80 bytes before every validation. A caller that moved a transaction image and
     * wrote without moving a trailer would therefore emit a well-formed record carrying reason
     * {@value #REASON_NONE} and a blank description, not a malformed one. It is still not a shape the
     * COBOL produces - {@code L448} always moves the trailer before {@code L451} writes - which is why
     * {@link #writeRejectRec(byte[], int, String)} fuses the two moves with the write so the ordinary
     * path cannot omit either.
     */
    public final class RejectsFile implements AutoCloseable {

        /** Where rendered records go, in call order. */
        private final RecordSink sink;

        /**
         * The {@code 01 FD-REJS-RECORD} area, exactly as the layout declares it: the copied transaction,
         * the reason and the description, with the {@code FD}'s 80-byte trailer view overlaid.
         */
        private final FixedWidthRecord rejectRecord;

        /**
         * What the sink reported when the destination was prepared: the answer to
         * {@code OPEN OUTPUT DALYREJS-FILE}, held so the job can branch on it.
         *
         * <p>Final, because an open happens once and its verdict does not change afterwards.
         */
        private final FileStatus.Outcome openOutcome;

        /** Whether the dataset is still open. Set once, by {@link #closeOutput()}. */
        private boolean open;

        /** How many records have been handed to the sink, successfully or not. */
        private int recordsWritten;

        /**
         * Whether the abnormal disposition has already been applied, so
         * {@link #discardGeneration()} is idempotent.
         *
         * <p>It has to be: an abnormal path can reach a cleanup more than once - a {@code finally} inside
         * a {@code finally}, or a caller that discards and then closes through try-with-resources - and a
         * second delete would find a count of zero against a non-zero {@code recordsWritten} and report a
         * refusal for work that had already succeeded.
         */
        private boolean discarded;

        /**
         * Allocates the record area and prepares the destination, in that order.
         *
         * <p>The area comes first because it exists independently of the file - a COBOL record area is
         * storage that a failed {@code OPEN} does not unallocate - and
         * {@link FixedWidthCodec#newRecord(RecordLayout)} establishes it as {@value #RECORD_LENGTH}
         * spaces. The sink's {@link RecordSink#open()} is then called exactly once and its answer is kept
         * for {@link #openOutcome()}.
         *
         * <p>A non-{@code OK} open leaves the handle usable and open, deliberately. The COBOL abends on
         * that arm ({@code app/cbl/CBTRN02C.cbl:L301-L305}) but the abend is the posting job's to raise,
         * and a writer that pre-closed the handle or threw would take the decision away from it - and
         * would do so inconsistently with how a rejected {@code WRITE} is treated. The writer reports;
         * the job decides.
         *
         * @param sink where rendered records go
         * @throws NullPointerException if the sink returns a {@code null} outcome from
         *                              {@link RecordSink#open()}
         */
        private RejectsFile(RecordSink sink) {
            this.sink = sink;
            this.rejectRecord = codec.newRecord(FD_REJS_RECORD_LAYOUT);
            this.open = true;
            this.openOutcome = Objects.requireNonNull(sink.open(),
                    "The record sink supplied for " + DD_NAME + " returned a null outcome from "
                            + "open(). A sink must report FileStatus.Outcome.OK when the destination "
                            + "is ready or FileStatus.Outcome.OTHER otherwise, because there is no "
                            + "COBOL FILE STATUS meaning 'no answer'.");
        }

        /**
         * What the sink reported when this handle was opened: the outcome of
         * {@code OPEN OUTPUT DALYREJS-FILE} in {@code 0300-DALYREJS-OPEN}
         * ({@code app/cbl/CBTRN02C.cbl:L291-L307}).
         *
         * <p>This is the third of the three outcomes a posting job needs, and it completes the set: the
         * open here, the write from {@link #writeRejectRec()}, and the close from
         * {@link #closeOutput()}. The COBOL runs the same shape of guard over all three - test
         * {@code DALYREJS-STATUS = '00'}, set {@code APPL-RESULT} to 0 or 12, and on the failing arm
         * display its own message before abending - so all three are reported here and none of them is
         * decided here.
         *
         * <p>Stable for the life of the handle, and unaffected by anything that happens afterwards: a
         * later rejected write does not retroactively make the open a failure.
         *
         * @return the open outcome, never {@code null}
         */
        public FileStatus.Outcome openOutcome() {
            return openOutcome;
        }

        // -------------------------------------------------------------------------------------------
        // MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA. app/cbl/CBTRN02C.cbl:L447.
        // -------------------------------------------------------------------------------------------

        /**
         * Performs {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA}: copies an already-encoded
         * {@value #FD_REJECT_RECORD_LENGTH}-byte transaction image into the first
         * {@value #FD_REJECT_RECORD_LENGTH} bytes of the record area, verbatim.
         *
         * <p><strong>Exactly {@value #FD_REJECT_RECORD_LENGTH} bytes, and nothing else is accepted.</strong>
         * This is the one place in the class where a width is refused rather than adjusted, and the reason
         * is that {@code L447} is a group move between items of <em>identical</em> declared width, so it
         * neither pads nor truncates and there is no COBOL rule to reproduce for a differently sized
         * sender. A short image would be a caller holding something that is not a
         * {@code DALYTRAN-RECORD} - most likely one built without {@code CVTRA06Y}'s trailing
         * {@code FILLER X(20)} - and silently space-padding it would manufacture a plausible-looking
         * record with 20 wrong bytes and shift nothing, which is precisely the defect gate
         * <strong>G21</strong> exists to catch. So it fails, naming both widths.
         *
         * <p>The bytes are copied without being decoded, so {@code DALYTRAN-AMT}'s sign overpunch and the
         * trailing {@code FILLER} cross into the reject record exactly as they stood in the input.
         *
         * @param transactionImage the rejected transaction's bytes in the dataset code page, exactly
         *                         {@value #FD_REJECT_RECORD_LENGTH} of them; the array is copied, not
         *                         retained
         * @throws NullPointerException     if {@code transactionImage} is {@code null}. A COBOL
         *                                  {@code MOVE} has no null sender
         * @throws IllegalArgumentException if {@code transactionImage} is not exactly
         *                                  {@value #FD_REJECT_RECORD_LENGTH} bytes long
         * @throws IllegalStateException    if this handle has already been closed
         */
        public void moveToRejectTranData(byte[] transactionImage) {
            Objects.requireNonNull(transactionImage, "A sending record is required to MOVE into "
                    + FD_REJECT_RECORD + "; app/cbl/CBTRN02C.cbl:L447 moves DALYTRAN-RECORD, and a "
                    + "COBOL MOVE has no null sender");
            requireOpen("MOVE a transaction into " + FD_REJECT_RECORD);
            if (transactionImage.length != FD_REJECT_RECORD_LENGTH) {
                throw new IllegalArgumentException("A rejected transaction image must be exactly "
                        + FD_REJECT_RECORD_LENGTH + " bytes but " + transactionImage.length
                        + " were supplied. MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA at "
                        + "app/cbl/CBTRN02C.cbl:L447 is a group move between items of identical width, "
                        + "so it neither pads nor truncates and there is no rule here to adjust a "
                        + "different width by. A "
                        + (transactionImage.length < FD_REJECT_RECORD_LENGTH ? "short" : "long")
                        + " image usually means the trailing FILLER X(20) that "
                        + "app/cpy/CVTRA06Y.cpy:L18 declares was dropped; encode the record through "
                        + "DalyTranRecord, whose width is " + DalyTranRecord.RECORD_LENGTH + " bytes.");
            }
            rejectRecord.writeSpanBytes(FD_REJS_RECORD_LAYOUT.span(FD_REJECT_RECORD), transactionImage);
        }

        /**
         * Performs {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA} from the record itself.
         *
         * <p>The convenient and the correct call, and they are the same call: the record is encoded
         * through {@link DalyTranRecord#encode(Charset)} under <em>this writer's</em> injected code page,
         * so a record that happens to have been built over a different charset is transcoded rather than
         * mixed into the output, and the code page is never the platform default (practice
         * <strong>B8</strong>). {@link DalyTranRecord#encode(Charset)} returns the backing bytes verbatim
         * when the charsets already agree, so the common path cannot lose a sign overpunch.
         *
         * @param transaction the rejected transaction; must not be {@code null}
         * @throws NullPointerException  if {@code transaction} is {@code null}
         * @throws IllegalStateException if this handle has already been closed
         */
        public void moveToRejectTranData(DalyTranRecord transaction) {
            Objects.requireNonNull(transaction, "A rejected transaction is required to MOVE into "
                    + FD_REJECT_RECORD);
            moveToRejectTranData(transaction.encode(codec.charset()));
        }

        // -------------------------------------------------------------------------------------------
        // MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER. app/cbl/CBTRN02C.cbl:L448.
        // -------------------------------------------------------------------------------------------

        /**
         * Performs {@code MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER}: renders the reason code and
         * its description into the trailing {@value #VALIDATION_TRAILER_LENGTH} bytes of the record area.
         *
         * <p>The two items are filled by rules that point in opposite directions, and both are the COBOL
         * rule rather than a convenience:
         * <ul>
         *   <li><strong>{@code WS-VALIDATION-FAIL-REASON PIC 9(04)} - zero-filled on the LEFT.</strong>
         *       {@value #REASON_OVERLIMIT_TRANSACTION} is stored as {@code "0102"} and
         *       {@value #REASON_NONE} as {@code "0000"}. A numeric receiver is aligned on its implied
         *       decimal point, so a value too wide for four digits loses its <em>high-order</em> digits -
         *       {@code 10102} would store {@code "0102"}. That is faithful to COBOL and is stated here
         *       rather than guarded against, because no {@code MOVE} in the source supplies more than
         *       three digits and inventing a rejection the mainframe does not have would be the larger
         *       error. Negative values are refused outright: {@code PIC 9} is unsigned and has nowhere to
         *       record a sign.</li>
         *   <li><strong>{@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} - space-padded on the
         *       RIGHT.</strong> An alphanumeric receiver is filled from its leftmost position, so a
         *       shorter description is padded to
         *       {@value #WS_VALIDATION_FAIL_REASON_DESC_LENGTH} characters and a longer one is
         *       <strong>truncated on the right</strong>, keeping its leading characters. It does not
         *       throw: COBOL discards the overflow, and a writer that threw here would abend a posting
         *       run the mainframe would have completed.</li>
         * </ul>
         *
         * <p>Separate from {@link #writeRejectRec()} because the COBOL separates them - {@code L447} and
         * {@code L448} are two moves and {@code L451} is the write - and because a caller may legitimately
         * set the trailer once and copy several transactions against it.
         * {@link #writeRejectRec(byte[], int, String)} fuses all three for the common case.
         *
         * @param reasonCode  the reject reason, a scale-free {@code PIC 9(04)} value; must not be
         *                    negative. {@link #descriptionOfReason(int)} names the five the program sets
         * @param description the reason text; any length is accepted, including empty
         *                    ({@link #DESC_SPACES}), and is normalised as described above
         * @throws NullPointerException     if {@code description} is {@code null}. To blank the field
         *                                  pass {@link #DESC_SPACES} explicitly, which is what
         *                                  {@code MOVE SPACES} at {@code L209} does
         * @throws IllegalArgumentException if {@code reasonCode} is negative
         * @throws IllegalStateException    if this handle has already been closed
         */
        public void moveToValidationTrailer(int reasonCode, String description) {
            Objects.requireNonNull(description, "A reason description is required to MOVE into "
                    + WS_VALIDATION_FAIL_REASON_DESC + "; to blank it pass DESC_SPACES explicitly, "
                    + "which is what MOVE SPACES at app/cbl/CBTRN02C.cbl:L209 does, rather than null");
            requireOpen("MOVE a validation trailer into " + FD_VALIDATION_TRAILER);
            // writePic9 refuses a negative value on the caller's behalf: PIC 9(04) is unsigned, so a
            // negative reason has no representation and must not be stored as its magnitude.
            codec.writePic9(rejectRecord, FD_REJS_RECORD_LAYOUT.span(WS_VALIDATION_FAIL_REASON),
                    reasonCode);
            codec.writePicX(rejectRecord, FD_REJS_RECORD_LAYOUT.span(WS_VALIDATION_FAIL_REASON_DESC),
                    description);
        }

        /**
         * Performs {@code MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER} using the description the
         * program pairs with the given code.
         *
         * <p>Equivalent to {@code moveToValidationTrailer(reasonCode, descriptionOfReason(reasonCode))},
         * which is the pairing every {@code MOVE nnn TO WS-VALIDATION-FAIL-REASON} in
         * {@code app/cbl/CBTRN02C.cbl} is immediately followed by. A code the program does not set yields
         * a blank description rather than an error, exactly as {@link #descriptionOfReason(int)}
         * describes.
         *
         * @param reasonCode the reject reason; must not be negative
         * @throws IllegalArgumentException if {@code reasonCode} is negative
         * @throws IllegalStateException    if this handle has already been closed
         */
        public void moveToValidationTrailer(int reasonCode) {
            moveToValidationTrailer(reasonCode, descriptionOfReason(reasonCode));
        }

        // -------------------------------------------------------------------------------------------
        // Reading the record area back.
        // -------------------------------------------------------------------------------------------

        /**
         * The current content of {@code FD-REJS-RECORD} as the bytes that would reach the dataset:
         * exactly {@value #RECORD_LENGTH} of them, in the injected code page.
         *
         * @return a fresh array of exactly {@value #RECORD_LENGTH} bytes, never the record's own backing
         *         array
         */
        public byte[] rejectRecordBytes() {
            return rejectRecord.toByteArray();
        }

        /**
         * The current content of {@code FD-REJS-RECORD}: exactly {@value #RECORD_LENGTH} characters,
         * every pad byte included.
         *
         * <p>Untrimmed, deliberately. The pad is part of the record - it is what makes the record
         * {@value #RECORD_LENGTH} bytes - and the parity differ compares byte for byte, so trimming here
         * would discard exactly the bytes it is meant to compare.
         *
         * @return the record image, exactly {@value #RECORD_LENGTH} characters
         */
        public String rejectRecord() {
            return rejectRecord.readString(0, RECORD_LENGTH);
        }

        /**
         * The copied transaction currently held in the record area, as bytes.
         *
         * @return a fresh array of exactly {@value #FD_REJECT_RECORD_LENGTH} bytes
         */
        public byte[] rejectTranDataBytes() {
            return rejectRecord.readSpanBytes(FD_REJS_RECORD_LAYOUT.span(FD_REJECT_RECORD));
        }

        /**
         * The validation trailer currently held in the record area, read through the {@code FD}'s own
         * single {@value #VALIDATION_TRAILER_LENGTH}-byte view.
         *
         * <p>Reads the {@code REDEFINES} overlay rather than concatenating the two working-storage items,
         * which is how the {@code FD} sees those bytes and therefore the right way to confirm that the
         * two views agree.
         *
         * @return the trailer image, exactly {@value #VALIDATION_TRAILER_LENGTH} characters
         */
        public String validationTrailer() {
            return rejectRecord.readSpan(FD_REJS_RECORD_LAYOUT.span(FD_VALIDATION_TRAILER));
        }

        /**
         * The reason code currently held in the record area, decoded from its four zoned digits.
         *
         * <p>Reads {@value #REASON_NONE} from a freshly opened handle, because a {@code PIC 9(04)} span
         * is established with zoned zeros rather than spaces - the same state
         * {@code MOVE 0 TO WS-VALIDATION-FAIL-REASON} at {@code app/cbl/CBTRN02C.cbl:L208} produces.
         *
         * @return the reason code
         * @throws IllegalArgumentException if the span does not hold four digits, which this class's own
         *                                  API cannot bring about - {@code writePic9} only ever stores
         *                                  digits - and which therefore indicates the record area was
         *                                  written through some other route
         */
        public int validationFailReason() {
            return codec.readPic9AsInt(rejectRecord,
                    FD_REJS_RECORD_LAYOUT.span(WS_VALIDATION_FAIL_REASON));
        }

        /**
         * The reason description currently held in the record area, untrimmed.
         *
         * @return the description image, exactly {@value #WS_VALIDATION_FAIL_REASON_DESC_LENGTH}
         *         characters, trailing pad included
         */
        public String validationFailReasonDesc() {
            return codec.readPicX(rejectRecord,
                    FD_REJS_RECORD_LAYOUT.span(WS_VALIDATION_FAIL_REASON_DESC));
        }

        // -------------------------------------------------------------------------------------------
        // 2500-WRITE-REJECT-REC. app/cbl/CBTRN02C.cbl:L446-L465.
        // -------------------------------------------------------------------------------------------

        /**
         * Writes whatever {@code FD-REJS-RECORD} currently holds, reproducing
         * {@code WRITE FD-REJS-RECORD FROM REJECT-RECORD} at {@code app/cbl/CBTRN02C.cbl:L451} - the
         * program's one and only write statement for this dataset.
         *
         * <p>One call, one record, immediately: nothing is batched, reordered, coalesced or skipped.
         * Calling it twice without an intervening move writes the same record twice, which is what the
         * COBOL would do and is therefore what it must do here. The record handed over is
         * <strong>always</strong> exactly {@value #RECORD_LENGTH} bytes - guaranteed structurally by the
         * record area's fixed width and re-verified here before the sink sees it, because a short or long
         * record silently corrupts every subsequent offset in a fixed-format file (gates
         * <strong>G19</strong>, <strong>G20</strong>).
         *
         * <p>A rejected write is returned as {@link FileStatus.Outcome#OTHER} rather than thrown, so the
         * posting job runs its own {@code '00'}-or-12 ladder, displays
         * {@code 'ERROR WRITING TO REJECTS FILE'} ({@code L460}) and decides whether to abend. Writing to
         * a handle that has already been closed is a different thing entirely: that is a caller
         * sequencing defect rather than a dataset condition, and it throws, because reporting it as an
         * ordinary failed write would let a job quietly lose rejects it believes it wrote.
         *
         * <p>A sink that answers {@code null} is a third thing again, and it is rejected here rather than
         * returned. {@code null} is not a {@code FILE STATUS} the COBOL can branch on, so carrying it
         * forward would let it reach {@link #close()} and surface there as an unrelated
         * {@link NullPointerException} - at a point where the sink that produced it is no longer in the
         * stack trace.
         *
         * @return {@link FileStatus.Outcome#OK} when the record was accepted, or
         *         {@link FileStatus.Outcome#OTHER} when the sink rejected it; never {@code null}
         * @throws NullPointerException  if the sink returns a {@code null} outcome
         * @throws IllegalStateException if this handle has already been closed; if the record area is
         *                               not {@value #RECORD_LENGTH} bytes - which cannot happen through
         *                               this class's own API and is checked because the consequence of it
         *                               happening is undetectable downstream; or, for a handle opened on
         *                               the configured dataset, if no unit of work is open, because the
         *                               record would be discarded rather than stored
         */
        public FileStatus.Outcome writeRejectRec() {
            requireOpen("WRITE " + FD_REJS_RECORD);
            byte[] image = rejectRecordBytes();
            if (image.length != RECORD_LENGTH) {
                throw new IllegalStateException("The " + FD_REJS_RECORD + " area rendered "
                        + image.length + " bytes but every " + DD_NAME + " record is exactly "
                        + RECORD_LENGTH + " (" + FD_REJECT_RECORD_LENGTH + " + "
                        + WS_VALIDATION_FAIL_REASON_LENGTH + " + "
                        + WS_VALIDATION_FAIL_REASON_DESC_LENGTH + "): app/jcl/POSTTRAN.jcl:L36 declares "
                        + "RECFM=" + RECORD_FORMAT + ",LRECL=" + RECORD_LENGTH + ". A record of any "
                        + "other width would shift every following record in a fixed-format file, so it "
                        + "is refused rather than written.");
            }
            FileStatus.Outcome outcome = Objects.requireNonNull(sink.write(image),
                    "The record sink supplied for " + DD_NAME + " returned a null outcome from "
                            + "write(byte[]) after " + recordsWritten + " record(s). A sink must "
                            + "report FileStatus.Outcome.OK for the DALYREJS-STATUS = '00' arm or "
                            + "FileStatus.Outcome.OTHER for any failure - the arm that sets "
                            + "APPL-RESULT to 12 - because there is no COBOL FILE STATUS meaning "
                            + "'no answer'.");
            recordsWritten++;
            return outcome;
        }

        /**
         * The whole of {@code 2500-WRITE-REJECT-REC}: both moves and the write, in the source's order.
         *
         * <p>This is the method a posting job normally calls, once per rejected transaction, in the order
         * the transactions are rejected. It exists because the three statements at {@code L447},
         * {@code L448} and {@code L451} are never performed apart, and fusing them means the ordinary
         * path cannot leave a stale trailer against a fresh transaction - or a fresh trailer against a
         * stale transaction, which would attribute one transaction's rejection to another.
         *
         * <p>Every rule described on {@link #moveToRejectTranData(byte[])},
         * {@link #moveToValidationTrailer(int, String)} and {@link #writeRejectRec()} applies unchanged.
         *
         * @param transactionImage the rejected transaction's bytes, exactly
         *                         {@value #FD_REJECT_RECORD_LENGTH} of them
         * @param reasonCode       the reject reason; must not be negative
         * @param description      the reason text; any length, normalised to
         *                         {@value #WS_VALIDATION_FAIL_REASON_DESC_LENGTH} characters
         * @return {@link FileStatus.Outcome#OK} when the record was accepted, or
         *         {@link FileStatus.Outcome#OTHER} when the sink rejected it; never {@code null}
         * @throws NullPointerException     if {@code transactionImage} or {@code description} is
         *                                  {@code null}, or if the sink returns a {@code null} outcome
         * @throws IllegalArgumentException if {@code transactionImage} is not exactly
         *                                  {@value #FD_REJECT_RECORD_LENGTH} bytes, or if
         *                                  {@code reasonCode} is negative
         * @throws IllegalStateException    if this handle has already been closed
         */
        public FileStatus.Outcome writeRejectRec(byte[] transactionImage, int reasonCode,
                                                String description) {
            moveToRejectTranData(transactionImage);
            moveToValidationTrailer(reasonCode, description);
            return writeRejectRec();
        }

        /**
         * The whole of {@code 2500-WRITE-REJECT-REC} from an encoded image, with the description the
         * program pairs with the given code.
         *
         * <p>Equivalent to
         * {@code writeRejectRec(transactionImage, reasonCode, descriptionOfReason(reasonCode))}. It
         * exists so the byte-image and {@link DalyTranRecord} forms are symmetric: both offer the
         * explicit-description shape and both offer this resolved-description shape, so a caller holding
         * an already-encoded image is not pushed into repeating the code-to-text pairing that
         * {@link #descriptionOfReason(int)} already states.
         *
         * @param transactionImage the rejected transaction's bytes, exactly
         *                         {@value #FD_REJECT_RECORD_LENGTH} of them
         * @param reasonCode       the reject reason; must not be negative
         * @return {@link FileStatus.Outcome#OK} when the record was accepted, or
         *         {@link FileStatus.Outcome#OTHER} when the sink rejected it; never {@code null}
         * @throws NullPointerException     if {@code transactionImage} is {@code null}, or if the sink
         *                                  returns a {@code null} outcome
         * @throws IllegalArgumentException if {@code transactionImage} is not exactly
         *                                  {@value #FD_REJECT_RECORD_LENGTH} bytes, or if
         *                                  {@code reasonCode} is negative
         * @throws IllegalStateException    if this handle has already been closed
         */
        public FileStatus.Outcome writeRejectRec(byte[] transactionImage, int reasonCode) {
            return writeRejectRec(transactionImage, reasonCode, descriptionOfReason(reasonCode));
        }

        /**
         * The whole of {@code 2500-WRITE-REJECT-REC} from the record itself, with the description the
         * program pairs with the given code.
         *
         * <p>The shortest faithful call there is, and the one a posting job should prefer: it takes the
         * {@link DalyTranRecord} the job just read from {@code DALYTRAN} and the code its validation
         * cascade settled on, and everything else follows from the sources.
         *
         * @param transaction the rejected transaction; must not be {@code null}
         * @param reasonCode  the reject reason; must not be negative
         * @return {@link FileStatus.Outcome#OK} when the record was accepted, or
         *         {@link FileStatus.Outcome#OTHER} when the sink rejected it; never {@code null}
         * @throws NullPointerException     if {@code transaction} is {@code null}, or if the sink returns
         *                                  a {@code null} outcome
         * @throws IllegalArgumentException if {@code reasonCode} is negative
         * @throws IllegalStateException    if this handle has already been closed
         */
        public FileStatus.Outcome writeRejectRec(DalyTranRecord transaction, int reasonCode) {
            moveToRejectTranData(transaction);
            moveToValidationTrailer(reasonCode);
            return writeRejectRec();
        }

        /**
         * How many records have been handed to the sink through this handle.
         *
         * <p>Counts every record handed over, including one the sink rejected, because the count answers
         * "how far did this run get" - which is what a diagnostic needs - rather than "how many
         * succeeded". It is emphatically <strong>not</strong> {@code WS-REJECT-COUNT}: that counter is
         * incremented in the mainline at {@code app/cbl/CBTRN02C.cbl:L214}, <em>before</em>
         * {@code 2500-WRITE-REJECT-REC} is performed, and it drives
         * {@code IF WS-REJECT-COUNT &gt; 0 MOVE 4 TO RETURN-CODE} at {@code L229-L231}. It lives in the
         * posting job.
         *
         * @return the record count, never negative
         */
        public int recordsWritten() {
            return recordsWritten;
        }

        /**
         * Whether this handle is still open for writing.
         *
         * @return {@code true} until {@link #closeOutput()} or {@link #close()} has run
         */
        public boolean isOpen() {
            return open;
        }

        /**
         * Closes the dataset, reproducing {@code CLOSE DALYREJS-FILE} in {@code 9300-DALYREJS-CLOSE} at
         * {@code app/cbl/CBTRN02C.cbl:L639}, and reports the outcome.
         *
         * <p>Idempotent: closing an already-closed handle is reported as {@link FileStatus.Outcome#OK}
         * and does not reach the sink a second time, so a try-with-resources block around an explicit
         * close is harmless.
         *
         * <p>The handle is marked closed before the sink is reached, so a sink that violates its contract
         * by answering {@code null} - which is rejected here, exactly as in {@link #writeRejectRec()} -
         * still cannot be closed a second time.
         *
         * <p>As with the write, a non-{@code OK} outcome is returned rather than thrown: the
         * {@code 'ERROR CLOSING DAILY REJECTS FILE'} display at {@code L648} and the abend that follows
         * it belong to the posting job. That arm carries the second preserved defect described on this
         * class - it renders {@code XREFFILE-STATUS} rather than {@code DALYREJS-STATUS} ({@code L649}) -
         * which affects only what the job displays. The outcome returned here is the rejects file's own,
         * because {@code L640} tests the right status.
         *
         * @return {@link FileStatus.Outcome#OK} when the sink closed cleanly or was already closed, or
         *         {@link FileStatus.Outcome#OTHER} otherwise; never {@code null}
         * @throws NullPointerException if the sink returns a {@code null} outcome
         */
        public FileStatus.Outcome closeOutput() {
            if (!open) {
                return FileStatus.Outcome.OK;
            }
            open = false;
            return Objects.requireNonNull(sink.close(),
                    "The record sink supplied for " + DD_NAME + " returned a null outcome from "
                            + "close() after " + recordsWritten + " record(s). A sink must report "
                            + "FileStatus.Outcome.OK when it closed cleanly or "
                            + "FileStatus.Outcome.OTHER otherwise, because there is no COBOL FILE "
                            + "STATUS meaning 'no answer'.");
        }

        /**
         * Applies the abnormal disposition of {@code app/jcl/POSTTRAN.jcl:L34-L38} -
         * {@code DISP=(NEW,CATLG,DELETE)} - by discarding everything this run wrote.
         *
         * <p><strong>Call this only when the step is ending abnormally</strong>, and after
         * {@link #closeOutput()}. The two are separate on purpose, because {@code DISP} says they are:
         * {@code CATLG} is the normal disposition and a close alone reproduces it, leaving the generation
         * catalogued. {@code DELETE} is the abnormal one, and a run that abends must leave no generation
         * at all. Closing then discarding is the order the mainframe uses - the dataset is closed, then
         * its disposition is applied - so a sink sees the same sequence of verbs it would there.
         *
         * <p>The record count is owned here rather than passed in, so a caller cannot get it wrong: it is
         * the same counter {@link #writeRejectRec()} increments, and it is what lets the sink establish
         * that the generation it is deleting is the one this run allocated.
         *
         * <p>Idempotent, and it never throws - not even a {@link NullPointerException} for a sink that
         * breaks its contract by answering {@code null}. Every other outcome on this handle is checked for
         * {@code null} and refuses, because a caller can still act on the refusal; here the caller is
         * already abending, and replacing the abend that a posting failure caused with a diagnostic about
         * the cleanup would lose the reason the run failed. A {@code null} is therefore logged and read as
         * {@link FileStatus.Outcome#OTHER}.
         *
         * @return {@link FileStatus.Outcome#OK} when the generation was discarded, when this run wrote
         *         nothing, or when the disposition had already been applied; otherwise
         *         {@link FileStatus.Outcome#OTHER}; never {@code null}
         */
        public FileStatus.Outcome discardGeneration() {
            if (discarded || recordsWritten == 0) {
                // Nothing was written, so there is no generation to delete. On the mainframe the step
                // still allocates and still deletes an empty dataset; there is no observable difference.
                discarded = true;
                return FileStatus.Outcome.OK;
            }
            discarded = true;
            FileStatus.Outcome outcome = sink.discard(recordsWritten);
            if (outcome == null) {
                LOG.error("The record sink supplied for " + DD_NAME + " returned a null outcome from "
                        + "discard(int) after " + recordsWritten + " record(s); reading it as FILE "
                        + "STATUS outcome " + FileStatus.Outcome.OTHER.name()
                        + " rather than raising, because this path is already abending");
                return FileStatus.Outcome.OTHER;
            }
            return outcome;
        }

        /**
         * Closes the dataset for a try-with-resources block.
         *
         * <p>{@link AutoCloseable#close()} cannot return a value, so a non-{@code OK} outcome is logged
         * rather than discarded, and it is not turned into an exception: throwing from {@code close()}
         * would mask whatever the block itself was doing. A caller that needs to act on the outcome - the
         * posting job, deciding on an abend - calls {@link #closeOutput()} instead.
         *
         * <p>A dataset condition and a broken sink are treated differently, deliberately. {@code OTHER}
         * is a dataset condition the COBOL has a guard for, so it is logged and the block continues; a
         * {@code null} outcome is a sink that does not implement its contract, so the
         * {@link NullPointerException} {@link #closeOutput()} raises is allowed to propagate. That cannot
         * mask a failure in the block either: a try-with-resources block whose body already threw records
         * a {@code close()} failure as a suppressed exception rather than replacing the primary one.
         *
         * @throws NullPointerException if the sink returns a {@code null} outcome from
         *                              {@link RecordSink#close()}
         */
        @Override
        public void close() {
            FileStatus.Outcome outcome = closeOutput();
            if (outcome != FileStatus.Outcome.OK) {
                LOG.error("Closing " + DD_NAME + " after " + recordsWritten
                        + " record(s) reported FILE STATUS outcome " + outcome.name()
                        + "; call closeOutput() rather than close() to handle this in the caller");
            }
        }

        /**
         * Refuses an operation on a closed handle, naming what was attempted and how far the run got.
         *
         * <p>One implementation rather than four copies of the same guard, so the two moves, the write and
         * the fused forms cannot drift apart in what they permit.
         *
         * @param attempt what the caller was trying to do, in COBOL terms
         * @throws IllegalStateException always, when the handle is closed
         */
        private void requireOpen(String attempt) {
            if (!open) {
                throw new IllegalStateException("Cannot " + attempt + " on " + DD_NAME
                        + ": this handle was closed after " + recordsWritten + " record(s). The COBOL "
                        + "opens the dataset once at the start of the run "
                        + "(app/cbl/CBTRN02C.cbl:L198) and closes it once at the end (L224), and the "
                        + "JCL creates a new GDG generation per run, so open a new handle for a new run "
                        + "rather than reusing a closed one.");
            }
        }
    }
}
